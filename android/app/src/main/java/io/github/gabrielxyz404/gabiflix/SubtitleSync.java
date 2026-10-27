package io.github.gabrielxyz404.gabiflix;

import android.net.Uri;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Ajuda a achar a legenda certa para o arquivo exato que está tocando:
 * hash do OpenSubtitles (igual ao Stremio desktop), busca com o hash no addon, alinhamento das legendas
 * em português a uma legenda de referência (outro idioma, do mesmo hash) e ranking das candidatas.
 * A matemática do alinhamento fica em SubtitleAlign (Java puro).
 */
final class SubtitleSync {
    private SubtitleSync() {}

    static final String TAG = "GabiFlixPlayer";
    static void log(String s) { try { Log.i(TAG, s); } catch (Throwable ignored) {} }

    /** uma legenda baixada */
    static final class Cand {
        Uri uri;
        java.io.File file;
        String text;          // texto UTF-8 atual (já alinhado), base para os ajustes manuais
        long lastMs;          // fim da última fala
        int rank;             // posição na lista (menor = melhor): resultados da busca com hash vêm primeiro
        boolean hashMatch;    // o addon marcou como "casa com o hash do arquivo"
        boolean fixed;        // tempos reescalados pela heurística de taxa de quadros
        boolean aligned;      // alinhada a uma referência
        double alignScore;
        double alignScale = 1.0;
        long alignOffsetMs;
        int manualMs;         // ajuste manual atual (ms)
        double score;
    }

    /** um resultado da busca no addon */
    static final class Found {
        final String url;
        final boolean hashMatch;
        final String lang;
        Found(String url, boolean hashMatch, String lang) { this.url = url; this.hashMatch = hashMatch; this.lang = lang; }
    }

    /** resposta do addon: legendas em português + candidatas a referência de tempo (outros idiomas, nunca mostradas) */
    static final class SearchResult {
        final ArrayList<Found> pt = new ArrayList<>();
        final ArrayList<Found> refs = new ArrayList<>();
        boolean sawMatchField = false; // o addon mandou o campo "m" em algum item?
        boolean refsAssumed = false;   // sem campo "m": referências são só um palpite (as primeiras de outro idioma)
        int total = 0;
        boolean ok = false;
    }

    static final class VideoHash {
        String hash;          // null se falhou
        long size;
        String error;         // motivo da falha (para o diagnóstico)
        String headerName;    // nome vindo do Content-Disposition
        String finalUrl;
    }

    // ---------- hash do OpenSubtitles ----------
    private static final int CHUNK = 65536;
    private static final int FIRST_TIMEOUT_MS = 12000; // o debrid pode demorar para resolver o link

    /** calcula o hash com limite de tempo; nunca devolve null (error diz o que houve) */
    static VideoHash computeHashTimeboxed(final String url, long timeoutMs) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<VideoHash> f = ex.submit((Callable<VideoHash>) () -> computeHash(url));
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            VideoHash v = new VideoHash();
            v.error = "tempo esgotado (" + (timeoutMs / 1000) + " s)";
            return v;
        } finally {
            ex.shutdownNow();
        }
    }

    /** hash = tamanho + soma das palavras de 64 bits (little-endian) dos primeiros e dos últimos 64 KiB, módulo 2^64 */
    static VideoHash computeHash(String u) {
        VideoHash v = new VideoHash();
        try {
            if (u == null || !u.startsWith("http")) { v.error = "link não é http"; return v; }
            Chunk a = getRange(u, "bytes=0-" + (CHUNK - 1), false);
            if (a.data == null) { v.error = "início do arquivo: " + a.error; return v; }
            if (a.data.length != CHUNK) { v.error = "início do arquivo curto (" + a.data.length + " bytes)"; return v; }
            v.headerName = a.dispositionName;
            v.finalUrl = a.finalUrl;
            long size = a.total;
            if (size < 0) size = headSize(a.finalUrl != null ? a.finalUrl : u);
            if (size < CHUNK) { v.error = "tamanho do arquivo desconhecido"; return v; }
            String range = "bytes=" + (size - CHUNK) + "-" + (size - 1);
            // o link final já está resolvido pelo debrid: usa direto; se falhar, tenta o link original
            Chunk b = getRange(a.finalUrl != null ? a.finalUrl : u, range, true);
            if (b.data == null && a.finalUrl != null && !a.finalUrl.equals(u)) b = getRange(u, range, true);
            if (b.data == null) { v.error = "fim do arquivo: " + b.error; return v; }
            if (b.data.length != CHUNK) { v.error = "fim do arquivo curto (" + b.data.length + " bytes)"; return v; }
            long h = size + sum(a.data) + sum(b.data);
            v.hash = String.format(Locale.ROOT, "%016x", h);
            v.size = size;
            return v;
        } catch (Exception e) {
            v.error = "erro " + e.getClass().getSimpleName();
            return v;
        }
    }

    private static long sum(byte[] d) {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        long s = 0;
        while (bb.remaining() >= 8) s += bb.getLong(); // estouro de long = módulo 2^64
        return s;
    }

    private static final class Chunk {
        byte[] data;          // null = falhou
        String error = "";
        long total = -1;
        String finalUrl;
        String dispositionName;
    }

    private static long parseLong(String s) {
        try { return s == null ? -1 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** nome do arquivo no Content-Disposition (filename*=UTF-8''... ou filename="...") */
    static String dispositionName(String cd) {
        if (cd == null) return null;
        try {
            java.util.regex.Matcher m = Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) return java.net.URLDecoder.decode(m.group(1).trim().replace("+", "%2B"), "UTF-8");
            m = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return null;
    }

    /** GET com Range seguindo os redirecionamentos na mão (o link do debrid redireciona, e http->https não é seguido sozinho) */
    private static Chunk getRange(String u, String range, boolean mustBePartial) {
        Chunk k = new Chunk();
        String cur = u;
        try {
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setConnectTimeout(FIRST_TIMEOUT_MS);
                c.setReadTimeout(FIRST_TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "GabiFlix");
                c.setRequestProperty("Range", range);
                c.setRequestProperty("Accept-Encoding", "identity");
                try {
                    int code = c.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null) { k.error = "redirecionamento sem destino"; return k; }
                        cur = new URL(new URL(cur), loc).toString();
                        continue;
                    }
                    if (code != 206 && !(code == 200 && !mustBePartial)) {
                        k.error = code == 200 ? "servidor sem suporte a Range" : "HTTP " + code;
                        return k;
                    }
                    k.finalUrl = cur;
                    k.dispositionName = dispositionName(c.getHeaderField("Content-Disposition"));
                    String cr = c.getHeaderField("Content-Range"); // "bytes 0-65535/123456"
                    if (cr != null) {
                        int slash = cr.lastIndexOf('/');
                        if (slash >= 0) k.total = parseLong(cr.substring(slash + 1));
                    }
                    if (k.total < 0 && code == 200) k.total = parseLong(c.getHeaderField("Content-Length"));
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[CHUNK];
                    int off = 0, r;
                    while (off < CHUNK && (r = in.read(buf, off, CHUNK - off)) > 0) off += r;
                    k.data = off == CHUNK ? buf : Arrays.copyOf(buf, off);
                    return k;
                } finally { c.disconnect(); }
            }
            k.error = "redirecionamentos demais";
        } catch (Exception e) {
            k.error = e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
        }
        return k;
    }

    private static long headSize(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "GabiFlix");
            try {
                if (c.getResponseCode() >= 400) return -1;
                return parseLong(c.getHeaderField("Content-Length"));
            } finally { c.disconnect(); }
        } catch (Exception e) { return -1; }
    }

    // ---------- busca no addon ----------
    private static final Pattern VIDEO_FILE = Pattern.compile(".+\\.(mkv|mp4|avi|m4v|mov|wmv|ts|webm|mpg|mpeg)$", Pattern.CASE_INSENSITIVE);

    private static String videoLike(String s) {
        return s != null && VIDEO_FILE.matcher(s).matches() ? s : null;
    }

    /** nome do arquivo: último trecho do caminho do link ORIGINAL (decodificado; nos links do Torrentio é o nome real da release),
     *  senão o Content-Disposition, senão o último trecho do link final; null se nenhum parecer um vídeo */
    static String fileNameOf(String url, VideoHash vh) {
        String n = null;
        try { n = videoLike(Uri.parse(url).getLastPathSegment()); } catch (Exception ignored) {}
        if (n == null && vh != null) n = videoLike(vh.headerName);
        if (n == null && vh != null && vh.finalUrl != null) {
            try { n = videoLike(Uri.parse(vh.finalUrl).getLastPathSegment()); } catch (Exception ignored) {}
        }
        return n;
    }

    /** extras no formato do Stremio (videoHash=...&videoSize=...&filename=...), só com o que temos; "" se nada */
    static String extras(VideoHash vh, String filename) {
        StringBuilder sb = new StringBuilder();
        if (vh != null && vh.hash != null) sb.append("videoHash=").append(vh.hash).append("&videoSize=").append(vh.size);
        if (filename != null) {
            if (sb.length() > 0) sb.append('&');
            sb.append("filename=").append(Uri.encode(filename));
        }
        return sb.toString();
    }

    static boolean isPtLang(String lang) {
        return lang.equals("pob") || lang.equals("pt-br") || lang.equals("ptb") || lang.equals("ptbr")
                || lang.equals("por") || lang.equals("pt") || lang.equals("pt-pt");
    }

    /** legendas do OpenSubtitles do Stremio: português (na ordem do addon, hash primeiro) e candidatas a referência (outro idioma) */
    static SearchResult search(String type, String id, String extras) {
        SearchResult res = new SearchResult();
        try {
            String api = "https://opensubtitles-v3.strem.io/subtitles/" + Uri.encode(type) + "/" + Uri.encode(id, ":")
                    + (extras == null || extras.isEmpty() ? "" : "/" + extras) + ".json";
            byte[] data = PlayerActivity.httpGet(api);
            if (data == null) return res;
            org.json.JSONArray arr = new org.json.JSONObject(new String(data, StandardCharsets.UTF_8)).optJSONArray("subtitles");
            if (arr == null) return res;
            res.ok = true;
            res.total = arr.length();
            ArrayList<Found> others = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String lang = o.optString("lang", "").toLowerCase(Locale.ROOT), url = o.optString("url", "");
                if (!url.startsWith("http")) continue;
                if (o.has("m")) res.sawMatchField = true;
                // o addon marca com m="f" as que casam com o hash do arquivo (m="i" = só o título)
                boolean hash = "f".equals(o.optString("m", ""));
                if (isPtLang(lang)) { if (res.pt.size() < 10) res.pt.add(new Found(url, hash, lang)); }
                else others.add(new Found(url, hash, lang));
            }
            // referência: de preferência inglês (eng) com hash; até 3
            for (int pass = 0; pass < 2; pass++) {
                for (Found f : others) {
                    if (res.refs.size() >= 3) break;
                    boolean eng = f.lang.equals("eng") || f.lang.equals("en");
                    if (f.hashMatch && (pass == 0) == eng) res.refs.add(f);
                }
            }
            if (res.refs.isEmpty() && !res.sawMatchField) {
                // sem o campo "m" não dá para saber quem casa com o hash: usa as primeiras (inglês antes) e o alinhamento confere
                res.refsAssumed = true;
                for (Found f : others) if ((f.lang.equals("eng") || f.lang.equals("en")) && res.refs.size() < 2) res.refs.add(f);
                for (Found f : others) if (!res.refs.contains(f) && res.refs.size() < 2) res.refs.add(f);
            }
        } catch (Exception ignored) {}
        return res;
    }

    // ---------- alinhamento a uma referência ----------
    /** alinha cada candidata (reescrevendo o arquivo) à melhor das referências; devolve quantas ficaram alinhadas */
    static int alignAll(List<Cand> all, List<long[]> refs, StringBuilder lg) {
        int n = 0;
        if (refs.isEmpty()) return 0;
        for (Cand c : all) {
            long[] starts = SubtitleAlign.cueStarts(c.text);
            SubtitleAlign.Fit best = null;
            for (long[] r : refs) {
                SubtitleAlign.Fit f = SubtitleAlign.align(r, starts);
                if (best == null || f.score > best.score) best = f;
                if (best.score >= 0.6) break; // já está claro: não precisa das outras referências
            }
            if (best == null) continue;
            lg.append("\n  #").append(c.rank).append(" alinhamento: nota=").append(String.format(Locale.ROOT, "%.2f", best.score))
                    .append(" escala=").append(String.format(Locale.ROOT, "%.4f", best.scale)).append(" deslocamento=").append(best.offsetMs).append("ms");
            if (!best.ok()) continue;
            c.aligned = true;
            c.alignScore = best.score;
            c.alignScale = best.scale;
            c.alignOffsetMs = best.offsetMs;
            n++;
            if (best.scale != 1.0 || Math.abs(best.offsetMs) >= 50) {
                String t = SubtitleAlign.transform(c.text, best.scale, best.offsetMs);
                try {
                    FileOutputStream os = new FileOutputStream(c.file);
                    os.write(t.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    c.text = t;
                    c.lastMs = PlayerActivity.lastCueEnd(t);
                } catch (IOException e) { c.aligned = false; n--; }
            }
        }
        return n;
    }

    // ---------- ranking ----------
    /** razões entre taxas de quadros que dá para distinguir pelo fim da legenda (24/23,976 é pequena demais: 0,1%) */
    private static final double[] FPS_FACTORS = {25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0};

    private static double matchFactor(double ratio) {
        double best = 0, bestD = 0.012; // tolerância de 1,2%
        for (double f : FPS_FACTORS) {
            double d = Math.abs(ratio - f);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best;
    }

    private static boolean plausible(long lastMs, long len) {
        return lastMs > 0 && lastMs >= len * 0.6 && lastMs <= len * 1.05;
    }

    /**
     * Ordem final: primeiro as ALINHADAS a uma referência (maior nota primeiro); depois as demais pela heurística antiga,
     * usada também quando não há referência:
     * A última fala costuma terminar alguns minutos antes do fim do vídeo (créditos), então não comparamos com a
     * duração e sim com uma legenda de REFERÊNCIA DE FIM:
     *  1) a primeira candidata que o addon marcou como casando com o hash (e com duração plausível); ou
     *  2) o maior grupo de candidatas cujo fim da última fala difere menos de 1,5% (mín. 2).
     * Uma candidata (que não seja de hash) cujo fim é ~4,27% maior/menor (25/23,976, 23,976/25, 25/24, 24/25)
     * foi feita para outra taxa de quadros: reescalamos os tempos pelo fator inverso, conferimos que agora bate (1,5%)
     * e marcamos como "corrigida".
     * Nota = ajuste à referência (ou a 0,93 da duração) + 0,01 por posição na lista do addon + 0,01 se corrigida
     * - 0,02 se casa com o hash. Menor = melhor. Fim fora de 0,5x..1,12x da duração é descartado (se sobrar alguma).
     */
    static ArrayList<Cand> rank(List<Cand> all, long len, StringBuilder lg) {
        ArrayList<Cand> good = new ArrayList<>(), rest = new ArrayList<>();
        for (Cand c : all) { if (c.aligned) good.add(c); else rest.add(c); }
        Collections.sort(good, (a, b) -> {
            int d = Double.compare(b.alignScore, a.alignScore);
            return d != 0 ? d : Integer.compare(a.rank, b.rank);
        });
        ArrayList<Cand> out = new ArrayList<>(good);
        out.addAll(rankHeuristic(rest, len, lg));
        return out;
    }

    private static ArrayList<Cand> rankHeuristic(List<Cand> all, long len, StringBuilder lg) {
        ArrayList<Cand> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.rank, b.rank));
        if (len <= 0 || sorted.isEmpty()) return sorted;

        long ref = 0;
        String why = "sem referência";
        for (Cand c : sorted) {
            if (c.hashMatch && plausible(c.lastMs, len)) { ref = c.lastMs; why = "legenda do hash #" + c.rank; break; }
        }
        if (ref == 0) {
            int bestN = 0;
            for (Cand a : sorted) {
                if (!plausible(a.lastMs, len)) continue;
                int n = 0;
                for (Cand b : sorted) if (b != a && b.lastMs > 0 && Math.abs((double) b.lastMs / a.lastMs - 1) <= 0.015) n++;
                if (n > bestN) { bestN = n; ref = a.lastMs; }
            }
            if (ref > 0) why = "grupo de " + (bestN + 1) + " legendas parecidas";
        }
        lg.append("\n  referência de fim=").append(ref).append("ms (").append(why).append("), duração=").append(len).append("ms");

        if (ref > 0) {
            for (Cand c : sorted) {
                if (c.hashMatch || c.lastMs <= 0 || c.text == null || c.file == null) continue;
                double f = matchFactor((double) c.lastMs / ref);
                if (f <= 0) continue;
                String fixed = SubtitleAlign.transform(c.text, 1.0 / f, 0);
                long nl = PlayerActivity.lastCueEnd(fixed);
                if (nl <= 0 || Math.abs((double) nl / ref - 1) > 0.015) continue;
                try {
                    FileOutputStream os = new FileOutputStream(c.file);
                    os.write(fixed.getBytes(StandardCharsets.UTF_8));
                    os.close();
                } catch (IOException e) { continue; }
                lg.append("\n  #").append(c.rank).append(" corrigida: fim ").append(c.lastMs).append("->").append(nl)
                        .append("ms (fator ").append(String.format(Locale.ROOT, "%.4f", f)).append(")");
                c.text = fixed;
                c.lastMs = nl;
                c.fixed = true;
            }
        }

        ArrayList<Cand> use = new ArrayList<>();
        for (Cand c : sorted) if (c.lastMs <= 0 || (c.lastMs >= len * 0.5 && c.lastMs <= len * 1.12)) use.add(c);
        if (use.isEmpty()) use.addAll(sorted);
        for (Cand c : use) {
            double fit;
            if (c.lastMs <= 0) fit = 0.3;
            else {
                double d = ref > 0 ? Math.abs((double) c.lastMs / ref - 1) : Math.abs((double) c.lastMs / len - 0.93);
                fit = Math.min(0.3, Math.max(0, d - 0.005));
            }
            c.score = fit + 0.01 * c.rank + (c.fixed ? 0.01 : 0) - (c.hashMatch ? 0.02 : 0);
            lg.append("\n  #").append(c.rank).append(" fim=").append(c.lastMs).append("ms hash=").append(c.hashMatch)
                    .append(" corrigida=").append(c.fixed).append(" nota=").append(String.format(Locale.ROOT, "%.4f", c.score));
        }
        Collections.sort(use, (a, b) -> Double.compare(a.score, b.score));
        return use;
    }
}
