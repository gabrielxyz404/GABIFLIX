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
        String url;           // endereço de onde veio (identifica a legenda para guardar o atraso)
        java.io.File file;
        String text;          // texto UTF-8 atual (já alinhado), base para os ajustes manuais
        long lastMs;          // fim da última fala
        int rank;             // posição na lista (menor = melhor): resultados da busca com hash vêm primeiro
        boolean hashMatch;    // o addon marcou como "casa com o hash do arquivo"
        boolean fixed;        // tempos reescalados pela heurística de taxa de quadros
        boolean aligned;      // alinhada a uma referência
        boolean refSelf;      // é a legenda PT do hash usada como referência das outras (já está certa, não é reescrita)
        String alignNote = "";// resultado do ajuste automático para o diagnóstico (ex.: "rejeitado: versão diferente")
        double alignScore;
        double alignScale = 1.0;
        long alignOffsetMs;
        int manualMs;         // ajuste manual atual (ms)
        double score;
        String rel = "";      // nome da release da legenda (arquivo + release + grupo + formato, do addon)
        int group = -1;       // grupo de consenso (legendas PT com os mesmos tempos); -1 = não calculado
        int groupSize;
        boolean consensus;    // escolhida por consenso (não alinhada: não há referência de tempo)
        boolean pieced;       // ajuste por partes (cortes)
        boolean partial;      // cobre só parte do vídeo (ex.: só o CD1 do filme): vai depois das completas
        boolean sparse;       // tem muito menos falas que a referência (forçada / só falas estrangeiras / incompleta): nunca é a primeira
        String sig = "raw";   // assinatura da reescrita automática (escala/deslocamento/partes): o atraso manual guardado só vale para a MESMA reescrita
    }

    /** um resultado da busca no addon */
    static final class Found {
        final String url;
        final boolean hashMatch;
        final String lang;
        String rel = "";
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
            ArrayList<Found> others = new ArrayList<>(), ptAll = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String lang = o.optString("lang", "").toLowerCase(Locale.ROOT), url = o.optString("url", "");
                if (!url.startsWith("http")) continue;
                if (o.has("m")) res.sawMatchField = true;
                // o addon marca com m="f" as que casam com o hash do arquivo (m="i" = só o título)
                boolean hash = "f".equals(o.optString("m", ""));
                Found fo = new Found(url, hash, lang);
                fo.rel = (o.optString("subtitleFileName", "") + " " + o.optString("movieReleaseName", "") + " " + o.optString("releaseGroup", "") + " " + o.optString("releaseFormat", "")).trim();
                if (isPtLang(lang)) ptAll.add(fo);
                else others.add(fo);
            }
            // as que casam com o hash vêm primeiro (mantém a ordem do addon dentro de cada grupo) antes de cortar em 10
            for (int pass = 0; pass < 2; pass++) for (Found f : ptAll) if (f.hashMatch == (pass == 0) && res.pt.size() < 10) res.pt.add(f);
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
    /** resultado do alinhamento: quantas ficaram alinhadas e que tipo de referência foi usada */
    static final class AlignReport {
        int aligned;
        /** "outro idioma", "PT do hash", "consenso" (sem referência de tempo: só escolhe) ou "nenhuma" */
        String refKind = "nenhuma";
        int pieced;       // quantas foram ajustadas por partes (cortes)
        int freeScaled;   // quantas precisaram de escala fora das razões fixas de fps
        int consensusMax; // tamanho do maior grupo de PT parecidas (modo consenso)
        int ptCount;
    }

    private static String f2(double v) { return String.format(Locale.ROOT, "%.2f", v).replace('.', ','); }

    /**
     * Alinha cada candidata (reescrevendo o arquivo) à melhor das referências; só reescreve se a nota global for boa E as duas
     * metades baterem (senão é outra versão/corte do filme). Sem referência de outro idioma, a melhor PT que casa com o hash
     * (a de menor posição) serve de referência para as outras PT. Sem nenhuma, não inventa nada.
     */
    /** duas referências "concordam" se levam 90% das falas a menos de 2 s uma da outra (deriva pequena de escala é normal; 20-30 s de diferença é outra versão) */
    private static final long AGREE_TOL_MS = 2000;
    private static final double AGREE_MIN = 0.90;

    static AlignReport alignAll(List<Cand> all, List<long[]> refs, StringBuilder lg) {
        AlignReport rep = new AlignReport();
        markSparse(all, lg);
        ArrayList<long[]> use = new ArrayList<>(refs);
        Cand self = null;
        if (!use.isEmpty()) rep.refKind = "outro idioma";
        else {
            // a PT do hash que serve de referência: a de menor posição entre as COMPLETAS (uma forçada tem poucas falas e não serve)
            int maxCues = 0;
            for (Cand c : all) if (c.hashMatch && c.text != null) maxCues = Math.max(maxCues, SubtitleAlign.alignStarts(c.text).length);
            for (Cand c : all) if (c.hashMatch && c.text != null && (self == null || c.rank < self.rank)) {
                long[] st = SubtitleAlign.alignStarts(c.text);
                if (st.length >= 20 && st.length >= 0.6 * maxCues) self = c;
            }
            if (self == null) {
                consensus(all, rep, lg);
                return rep;
            }
            use.add(SubtitleAlign.alignStarts(self.text));
            self.refSelf = true;
            self.alignNote = "é a referência (PT do hash)";
            rep.refKind = "PT do hash";
            lg.append("\n  referência = PT do hash #").append(self.rank);
        }
        for (Cand c : all) {
            if (c == self) continue;
            long[] starts = SubtitleAlign.alignStarts(c.text);
            SubtitleAlign.Fit best = null;
            ArrayList<SubtitleAlign.Fit> oks = new ArrayList<>();
            for (long[] r : use) {
                SubtitleAlign.Fit f = SubtitleAlign.align(r, starts);
                // prefere uma referência que valide por inteiro; entre iguais, a de maior nota
                if (best == null || (f.ok() && !best.ok()) || (f.ok() == best.ok() && f.score > best.score)) best = f;
                if (f.ok()) oks.add(f);
                // com 2 referências que validam E concordam, está confirmado: não precisa das outras
                if (oks.size() >= 2 && SubtitleAlign.agreement(oks.get(0), oks.get(oks.size() - 1), starts, AGREE_TOL_MS) >= AGREE_MIN) break;
            }
            if (best == null) continue;
            if (oks.size() >= 2) {
                // referências do mesmo vídeo têm de dar a MESMA transformação; se discordam, algo está errado (outra versão, referência errada): não alinha
                SubtitleAlign.Fit pick = null;
                int bestVotes = 0;
                for (SubtitleAlign.Fit a : oks) {
                    int votes = 0;
                    for (SubtitleAlign.Fit b : oks) if (SubtitleAlign.agreement(a, b, starts, AGREE_TOL_MS) >= AGREE_MIN) votes++;
                    if (votes > bestVotes || (votes == bestVotes && pick != null && a.score > pick.score)) { bestVotes = votes; pick = a; }
                }
                if (bestVotes < 2) {
                    lg.append("\n  #").append(c.rank).append(" REFERÊNCIAS DISCORDAM (").append(oks.size()).append(" validam com transformações diferentes): não alinha");
                    c.alignNote = "não alinhada: as referências de tempo discordam entre si";
                    continue;
                }
                best = pick;
            }
            String nums = "nota=" + String.format(Locale.ROOT, "%.2f", best.score) + " metades=" + String.format(Locale.ROOT, "%.2f/%.2f", best.scoreFirstHalf, best.scoreSecondHalf)
                    + " escala=" + String.format(Locale.ROOT, "%.4f", best.scale) + " deslocamento=" + best.offsetMs + "ms";
            lg.append("\n  #").append(c.rank).append(" alinhamento: ").append(nums);
            if (!best.ok()) {
                if (best.sparse) c.sparse = true;
                c.alignNote = best.sparse ? "rejeitada: tem só " + Math.round(best.cueRatio * 100) + "% das falas da referência (forçada ou incompleta)"
                        : best.sparseRef ? "sem validação: a referência tem poucas falas"
                        : best.differentVersion()
                        ? "rejeitado: versão diferente (corte) — nota " + f2(best.score) + ", metades " + f2(best.scoreFirstHalf) + "/" + f2(best.scoreSecondHalf)
                        : "sem casamento com a referência (nota " + f2(best.score) + ")";
                lg.append(" -> ").append(best.differentVersion() ? "REJEITADO: corte/versão diferente" : "não casa");
                continue;
            }
            c.aligned = true;
            c.sig = sigOf(best);
            c.alignScore = best.score;
            c.partial = best.coverage < 0.75;
            c.alignScale = best.scale;
            c.alignOffsetMs = best.offsetMs;
            rep.aligned++;
            c.alignNote = "ajuste auto " + signedSecs(best.offsetMs) + (best.scale != 1.0 ? ", ×" + String.format(Locale.ROOT, "%.4f", best.scale).replace('.', ',') : "");
            if (best.piecewise()) {
                int cuts = best.cuts.length;
                StringBuilder sb = new StringBuilder("ajuste por partes: " + cuts + (cuts == 1 ? " corte" : " cortes") + " (");
                for (int q = 0; q < best.offs.length; q++) sb.append(q > 0 ? "; " : "").append(signedSecs(best.offs[q]));
                c.alignNote = sb.append(")").append(best.scale != 1.0 ? ", ×" + String.format(Locale.ROOT, "%.4f", best.scale).replace('.', ',') : "").toString();
                c.pieced = true;
                rep.pieced++;
                lg.append(" PARTES ").append(cuts).append(" cortes");
            }
            if (best.freeScale) { rep.freeScaled++; lg.append(" escala-livre"); }
            if (c.partial) { c.alignNote += " (parcial: cobre " + Math.round(best.coverage * 100) + "% do tempo da referência)"; lg.append(" PARCIAL ").append(String.format(Locale.ROOT, "%.2f", best.coverage)); }
            if (best.changes()) {
                String t = best.apply(c.text);
                try {
                    FileOutputStream os = new FileOutputStream(c.file);
                    os.write(t.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    c.text = t;
                    c.lastMs = SubtitleText.lastCueEnd(t);
                } catch (IOException e) { c.aligned = false; c.alignNote = "falhou ao gravar o ajuste"; rep.aligned--; }
            }
        }
        return rep;
    }

    /** assinatura curta da reescrita (escala, deslocamento, cortes) */
    static String sigOf(SubtitleAlign.Fit f) {
        StringBuilder sb = new StringBuilder("a").append(String.format(Locale.ROOT, "%.4f", f.scale)).append('/').append(Math.round(f.offsetMs / 100.0));
        if (f.cuts != null) for (int i = 0; i < f.cuts.length; i++) sb.append('/').append(f.cuts[i] / 1000).append(':').append(Math.round(f.offs[i + 1] / 100.0));
        return sb.toString();
    }

    /** legenda "forçada"/incompleta: tem muito menos falas que a mediana das candidatas (só com 3 ou mais) */
    private static void markSparse(List<Cand> all, StringBuilder lg) {
        ArrayList<Integer> n = new ArrayList<>();
        int[] cnt = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Cand c = all.get(i);
            cnt[i] = c.text == null ? 0 : SubtitleAlign.alignStarts(c.text).length;
            if (cnt[i] >= 20) n.add(cnt[i]);
        }
        if (n.size() < 3) return;
        Collections.sort(n);
        int med = n.get(n.size() / 2);
        for (int i = 0; i < all.size(); i++) if (cnt[i] < 0.4 * med) {
            all.get(i).sparse = true;
            lg.append("\n  #").append(all.get(i).rank).append(" parece forçada/incompleta: ").append(cnt[i]).append(" falas (mediana ").append(med).append(")");
        }
    }

    static String signedSecs(long ms) {
        String v = String.format(Locale.ROOT, "%.1f", Math.abs(ms) / 1000.0).replace('.', ',');
        return (ms > 0 ? "+" : ms < 0 ? "−" : "") + v + " s";
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
    static ArrayList<Cand> rank(List<Cand> all, long len, StringBuilder lg) { return rank(all, len, null, lg); }

    /** videoName = nome do arquivo de vídeo (para comparar com a release de cada legenda no consenso); pode ser null */
    static ArrayList<Cand> rank(List<Cand> all, long len, String videoName, StringBuilder lg) {
        ArrayList<Cand> good = new ArrayList<>(), rest = new ArrayList<>();
        for (Cand c : all) { if (c.aligned || c.refSelf) good.add(c); else rest.add(c); }
        Collections.sort(good, (a, b) -> {
            if (a.refSelf != b.refSelf) return a.refSelf ? -1 : 1; // a PT do hash usada como referência vem primeiro
            if (a.partial != b.partial) return a.partial ? 1 : -1;  // as que cobrem só parte do vídeo (CD1/CD2) vão depois
            int d = Double.compare(b.alignScore, a.alignScore);
            return d != 0 ? d : Integer.compare(a.rank, b.rank);
        });
        ArrayList<Cand> out = new ArrayList<>(good);
        if (good.isEmpty()) {
            ArrayList<Cand> cons = consensusPick(rest, len, videoName, lg);
            out.addAll(cons);
            rest.removeAll(cons);
        }
        out.addAll(rankHeuristic(rest, len, lg));
        // forçadas/incompletas nunca vão na frente das outras (ficam no menu, no fim)
        ArrayList<Cand> full = new ArrayList<>(), thin = new ArrayList<>();
        for (Cand c : out) (c.sparse && !c.refSelf ? thin : full).add(c);
        full.addAll(thin);
        return full;
    }

    // ---------- consenso (sem nenhuma referência de tempo) ----------
    /**
     * Sem referência de outro idioma nem PT do hash não dá para alinhar nada. Mas dá para ESCOLHER: as legendas PT que têm os mesmos
     * tempos entre si (concordância >= 50% sem ajuste nenhum) formam grupos, e o maior grupo costuma ser a versão mais comum do filme.
     * Aqui só se marcam os grupos; a escolha (com hash, nome da release e duração) é feita em rank(), que conhece a duração do vídeo.
     */
    private static void consensus(List<Cand> all, AlignReport rep, StringBuilder lg) {
        ArrayList<Cand> use = new ArrayList<>();
        ArrayList<long[]> st = new ArrayList<>();
        for (Cand c : all) {
            c.group = -1;
            long[] s = SubtitleAlign.alignStarts(c.text);
            if (s.length >= 20 && !c.sparse) { use.add(c); st.add(s); }
        }
        rep.ptCount = all.size();
        if (use.size() < 2) {
            for (Cand c : all) c.alignNote = "sem referência — use Sincronizar pela fala";
            lg.append("\n  sem referência (nem outro idioma, nem PT do hash) e menos de 2 PT para comparar: ranking antigo");
            return;
        }
        int[] comp = SubtitleAlign.consensusGroups(st);
        int ng = 0;
        for (int g : comp) ng = Math.max(ng, g + 1);
        int[] size = new int[ng];
        for (int g : comp) size[g]++;
        for (int i = 0; i < use.size(); i++) { use.get(i).group = comp[i]; use.get(i).groupSize = size[comp[i]]; }
        for (int g : size) rep.consensusMax = Math.max(rep.consensusMax, g);
        rep.refKind = "consenso";
        for (Cand c : all) c.alignNote = c.group < 0 ? "sem referência — use Sincronizar pela fala"
                : c.groupSize >= 2 ? "consenso: faz parte de um grupo de " + c.groupSize + " PT iguais (sem alinhar)" : "consenso: sem PT parecida";
        lg.append("\n  sem referência: consenso entre ").append(use.size()).append(" PT, grupos ").append(java.util.Arrays.toString(size));
    }

    /** o grupo de consenso escolhido (hash, nome da release, duração), com os membros em ordem de preferência; vazio se não houver grupo de >= 2 */
    private static ArrayList<Cand> consensusPick(List<Cand> rest, long len, String videoName, StringBuilder lg) {
        ArrayList<Cand> out = new ArrayList<>();
        boolean any = false;
        for (Cand c : rest) if (c.group >= 0 && c.groupSize >= 2) any = true;
        if (!any) return out;
        int n = rest.size();
        int[] comp = new int[n];
        boolean[] hash = new boolean[n];
        double[] name = new double[n];
        long[] last = new long[n];
        int next = 0;
        for (Cand c : rest) next = Math.max(next, c.group + 1);
        for (int i = 0; i < n; i++) {
            Cand c = rest.get(i);
            comp[i] = c.group >= 0 ? c.group : next++;
            hash[i] = c.hashMatch;
            name[i] = SubtitleAlign.nameSimilarity(videoName, c.rel);
            last[i] = c.lastMs;
        }
        StringBuilder why = new StringBuilder();
        int g = SubtitleAlign.pickGroup(comp, hash, name, last, len, why);
        lg.append(why);
        if (g < 0) return out;
        ArrayList<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) if (comp[i] == g) idx.add(i);
        if (idx.size() < 2) return out;
        final double[] nm = name;
        final List<Cand> rr = rest;
        Collections.sort(idx, (a, b) -> {
            Cand ca = rr.get(a), cb = rr.get(b);
            if (ca.hashMatch != cb.hashMatch) return ca.hashMatch ? -1 : 1;
            int d = Double.compare(nm[b], nm[a]);
            return d != 0 ? d : Integer.compare(ca.rank, cb.rank);
        });
        for (int k = 0; k < idx.size(); k++) {
            Cand c = rest.get(idx.get(k));
            c.consensus = true;
            c.alignNote = "escolhida por consenso: grupo de " + idx.size() + " PT iguais (escolhe, não alinha)";
            out.add(c);
        }
        lg.append("\n  consenso escolheu o grupo ").append(g).append(" (").append(idx.size()).append(" PT), primeira #").append(out.get(0).rank);
        return out;
    }

    private static ArrayList<Cand> rankHeuristic(List<Cand> all, long len, StringBuilder lg) {
        ArrayList<Cand> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.rank, b.rank));
        if (len <= 0 || sorted.isEmpty()) return sorted;

        long ref = 0;
        Cand refCand = null;
        String why = "sem referência";
        for (Cand c : sorted) {
            if (c.hashMatch && plausible(c.lastMs, len)) { ref = c.lastMs; refCand = c; why = "legenda do hash #" + c.rank; break; }
        }
        if (ref == 0) {
            int bestN = 0;
            for (Cand a : sorted) {
                if (!plausible(a.lastMs, len)) continue;
                int n = 0;
                for (Cand b : sorted) if (b != a && b.lastMs > 0 && Math.abs((double) b.lastMs / a.lastMs - 1) <= 0.015) n++;
                if (n > bestN) { bestN = n; ref = a.lastMs; refCand = a; }
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
                long nl = SubtitleText.lastCueEnd(fixed);
                if (nl <= 0 || Math.abs((double) nl / ref - 1) > 0.015) continue;
                // só o fim bate? Confirma pelas falas: depois da correção os inícios têm de concordar com os da legenda de referência
                // (um corte maior/menor do filme também tem o fim ~4% diferente e NÃO deve ser esticado)
                if (refCand == null || refCand.text == null) continue;
                long[] rs = SubtitleAlign.alignStarts(refCand.text), ns = SubtitleAlign.alignStarts(fixed);
                if (SubtitleAlign.rawAgreement(ns, rs) < 0.4 || !SubtitleAlign.sameTiming(ns, rs)) {
                    lg.append("\n  #").append(c.rank).append(" não corrigida: o fim sugere fator ").append(String.format(Locale.ROOT, "%.4f", f)).append(" mas as falas não confirmam");
                    continue;
                }
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
                c.sig = "fps" + String.format(Locale.ROOT, "%.4f", f);
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
