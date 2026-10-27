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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ajuda a achar a legenda certa para o arquivo exato que está tocando:
 * hash do OpenSubtitles (igual ao Stremio desktop), busca com o hash no addon e ranking das candidatas,
 * incluindo a correção de legendas feitas para outra taxa de quadros (23,976 / 24 / 25 fps).
 */
final class SubtitleSync {
    private SubtitleSync() {}

    static final String TAG = "GabiFlixPlayer";
    static void log(String s) { try { Log.i(TAG, s); } catch (Throwable ignored) {} }

    /** uma legenda baixada */
    static final class Cand {
        Uri uri;
        java.io.File file;
        String text;          // texto UTF-8 (para poder reescrever os tempos)
        long lastMs;          // fim da última fala
        int rank;             // posição na lista (menor = melhor): resultados da busca com hash vêm primeiro
        boolean hashMatch;    // o addon marcou como "casa com o hash do arquivo"
        boolean fixed;        // tempos reescalados (taxa de quadros diferente)
        double score;
    }

    /** resultado da busca no addon */
    static final class Found {
        final String url;
        final boolean hashMatch;
        Found(String url, boolean hashMatch) { this.url = url; this.hashMatch = hashMatch; }
    }

    static final class VideoHash {
        String hash;
        long size;
    }

    // ---------- hash do OpenSubtitles ----------
    private static final int CHUNK = 65536;

    /** calcula o hash com limite de tempo; null se falhar (a busca segue sem ele) */
    static VideoHash computeHashTimeboxed(final String url, long timeoutMs) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<VideoHash> f = ex.submit((java.util.concurrent.Callable<VideoHash>) () -> computeHash(url));
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log("hash: falhou ou demorou demais (" + e.getClass().getSimpleName() + ")");
            return null;
        } finally {
            ex.shutdownNow();
        }
    }

    /** hash = tamanho + soma das palavras de 64 bits (little-endian) dos primeiros e dos últimos 64 KiB, módulo 2^64 */
    static VideoHash computeHash(String u) {
        try {
            if (u == null || !u.startsWith("http")) return null;
            Chunk a = getRange(u, "bytes=0-" + (CHUNK - 1), false);
            if (a == null || a.data.length != CHUNK) { log("hash: não leu o início do arquivo"); return null; }
            long size = a.total;
            if (size < 0) size = headSize(u);
            if (size < CHUNK) { log("hash: tamanho desconhecido"); return null; }
            Chunk b = getRange(u, "bytes=" + (size - CHUNK) + "-" + (size - 1), true);
            if (b == null || b.data.length != CHUNK) { log("hash: não leu o fim do arquivo (sem suporte a Range?)"); return null; }
            long h = size + sum(a.data) + sum(b.data);
            VideoHash v = new VideoHash();
            v.hash = String.format(Locale.ROOT, "%016x", h);
            v.size = size;
            return v;
        } catch (Exception e) {
            log("hash: erro " + e);
            return null;
        }
    }

    private static long sum(byte[] d) {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        long s = 0;
        while (bb.remaining() >= 8) s += bb.getLong(); // estouro de long = módulo 2^64
        return s;
    }

    private static final class Chunk {
        byte[] data;
        long total = -1;
        int code;
    }

    private static long parseLong(String s) {
        try { return s == null ? -1 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static Chunk getRange(String u, String range, boolean mustBePartial) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GabiFlix");
        c.setRequestProperty("Range", range);
        c.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = c.getResponseCode();
            if (code != 206 && !(code == 200 && !mustBePartial)) return null;
            Chunk k = new Chunk();
            k.code = code;
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

    private static long headSize(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
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

    /** nome do arquivo (último trecho do caminho, decodificado) se parecer um vídeo; senão null */
    static String fileNameOf(String url) {
        try {
            String s = Uri.parse(url).getLastPathSegment();
            if (s != null && VIDEO_FILE.matcher(s).matches()) return s;
        } catch (Exception ignored) {}
        return null;
    }

    /** extras no formato do Stremio (videoHash=...&videoSize=...&filename=...), só com o que temos; "" se nada */
    static String extras(VideoHash vh, String filename) {
        StringBuilder sb = new StringBuilder();
        if (vh != null) sb.append("videoHash=").append(vh.hash).append("&videoSize=").append(vh.size);
        if (filename != null) {
            if (sb.length() > 0) sb.append('&');
            sb.append("filename=").append(Uri.encode(filename));
        }
        return sb.toString();
    }

    /** legendas em português do OpenSubtitles do Stremio, na ordem do addon (as que casam com o hash vêm primeiro) */
    static ArrayList<Found> search(String type, String id, String extras) {
        ArrayList<Found> out = new ArrayList<>();
        try {
            String api = "https://opensubtitles-v3.strem.io/subtitles/" + Uri.encode(type) + "/" + Uri.encode(id, ":")
                    + (extras == null || extras.isEmpty() ? "" : "/" + extras) + ".json";
            byte[] data = PlayerActivity.httpGet(api);
            if (data == null) return out;
            org.json.JSONArray arr = new org.json.JSONObject(new String(data, StandardCharsets.UTF_8)).optJSONArray("subtitles");
            if (arr == null) return out;
            for (int i = 0; i < arr.length() && out.size() < 10; i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String lang = o.optString("lang", "").toLowerCase(Locale.ROOT), url = o.optString("url", "");
                if (!url.startsWith("http")) continue;
                boolean pt = lang.equals("pob") || lang.equals("pt-br") || lang.equals("ptb") || lang.equals("ptbr")
                        || lang.equals("por") || lang.equals("pt") || lang.equals("pt-pt");
                if (!pt) continue;
                // o addon marca com m="f" as que casam com o hash do arquivo (m="i" = só o título)
                out.add(new Found(url, "f".equals(o.optString("m", ""))));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- ranking e correção de taxa de quadros ----------
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
     * Heurística: a última fala costuma terminar alguns minutos antes do fim do vídeo (créditos), então não
     * comparamos com a duração e sim com uma legenda de REFERÊNCIA:
     *  1) a primeira candidata que o addon marcou como casando com o hash do arquivo (e com duração plausível); ou
     *  2) se não houver, o maior grupo de candidatas cujo fim da última fala difere menos de 1,5% (mín. 2).
     * Uma candidata (que não seja de hash) cujo fim é ~4,27% maior/menor que o da referência (25/23,976 ou 23,976/25;
     * também 25/24 e 24/25) foi feita para outra taxa de quadros: reescalamos todos os tempos pelo fator inverso,
     * conferimos que agora bate com a referência (1,5%) e marcamos como "corrigida".
     * Nota: 24 x 23,976 (0,1%) não dá para detectar assim; o ajuste manual cobre esse caso.
     * Nota de pontuação: nota = ajuste à referência (ou a 0,93 da duração, sem referência) + 0,01 por posição
     * na lista do addon (hash primeiro) + 0,01 se foi corrigida - 0,02 se casa com o hash. Menor = melhor.
     * Candidatas com fim fora de 0,5x..1,12x da duração são descartadas (se sobrar alguma).
     */
    static ArrayList<Cand> rank(List<Cand> all, long len, StringBuilder lg) {
        ArrayList<Cand> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.rank, b.rank));
        if (len <= 0) return sorted;

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
        lg.append("referência de fim=").append(ref).append("ms (").append(why).append("), duração=").append(len).append("ms");

        if (ref > 0) {
            for (Cand c : sorted) {
                if (c.hashMatch || c.lastMs <= 0 || c.text == null || c.file == null) continue;
                double f = matchFactor((double) c.lastMs / ref);
                if (f <= 0) continue;
                String fixed = rescale(c.text, 1.0 / f);
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

    // ---------- reescalar os tempos de uma legenda (SRT/VTT) ----------
    private static final Pattern TS = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d\\d)([,.])(\\d{3})");

    /** multiplica todos os tempos das linhas "a --> b" por k */
    static String rescale(String text, double k) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.contains("-->")) ln = scaleLine(ln, k);
            out.append(ln);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String scaleLine(String ln, double k) {
        Matcher m = TS.matcher(ln);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long ms = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(5));
            long n = Math.round(ms * k);
            String rep = String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", n / 3600000, (n / 60000) % 60, (n / 1000) % 60, m.group(4), n % 1000);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
