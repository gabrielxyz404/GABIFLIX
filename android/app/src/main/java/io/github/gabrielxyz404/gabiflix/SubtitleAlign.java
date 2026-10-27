package io.github.gabrielxyz404.gabiflix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alinhamento de legendas por referência (Java puro, sem Android, para poder ser testado na JVM).
 *
 * Ideia: uma legenda de OUTRO idioma que casa com o hash do arquivo (ex.: inglês) tem os tempos certos para
 * este vídeo. Comparamos os INÍCIOS das falas da legenda candidata com os da referência e procuramos a
 * transformação  t' = t * escala + deslocamento  que faz a maior parte das falas cair a menos de 300 ms de
 * uma fala da referência. Funciona para qualquer título (não usa nome, id nem duração).
 */
final class SubtitleAlign {
    private SubtitleAlign() {}

    /** razões entre taxas de quadros mais comuns (a primeira, 1, é "sem mudança de velocidade") */
    static final double[] SCALES = {1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0};
    static final long COARSE_RANGE_MS = 120000;
    static final long COARSE_BIN_MS = 250;
    static final long TOLERANCE_MS = 300;
    /** pontuação mínima (fração das falas que cai em cima de uma fala da referência) para aceitar o alinhamento */
    static final double MIN_SCORE = 0.35;
    /** uma escala diferente de 1 só vence se for melhor que a escala 1 por esta margem */
    static final double SCALE_MARGIN = 0.05;

    static final class Fit {
        double scale = 1.0;
        long offsetMs = 0;
        double score = 0;
        boolean ok() { return score >= MIN_SCORE; }
    }

    private static final Pattern START = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})[ \\t]*-->", Pattern.MULTILINE);

    /** início de cada fala (ms), em ordem crescente (SRT ou VTT); no máximo 6000 falas */
    static long[] cueStarts(String text) {
        ArrayList<Long> list = new ArrayList<>();
        Matcher m = START.matcher(text);
        while (m.find() && list.size() < 6000) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            list.add(((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4)));
        }
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        Arrays.sort(out);
        return out;
    }

    // ---------- reescrever os tempos ----------
    private static final Pattern TS = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d\\d)([,.])(\\d{3})");

    /** novo tempo = tempo * k + offsetMs em todas as linhas "a --> b" (SRT/VTT); o resto do texto não muda */
    static String transform(String text, double k, long offsetMs) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.contains("-->")) ln = transformLine(ln, k, offsetMs);
            out.append(ln);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String transformLine(String ln, double k, long offsetMs) {
        Matcher m = TS.matcher(ln);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long ms = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(5));
            long n = Math.max(0, Math.round(ms * k) + offsetMs);
            String rep = String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", n / 3600000, (n / 60000) % 60, (n / 1000) % 60, m.group(4), n % 1000);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------- busca da melhor transformação ----------
    /** índice do primeiro elemento >= v */
    private static int lowerBound(long[] a, long v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** quantas falas da candidata (já multiplicadas por scale e somadas a off) caem a menos de TOLERANCE_MS de uma fala da referência */
    private static int countMatches(long[] ref, long[] cand, double scale, long off) {
        int hits = 0;
        for (long c : cand) {
            long t = Math.round(c * scale) + off;
            int i = lowerBound(ref, t);
            boolean hit = false;
            if (i < ref.length && ref[i] - t <= TOLERANCE_MS) hit = true;
            if (!hit && i > 0 && t - ref[i - 1] <= TOLERANCE_MS) hit = true;
            if (hit) hits++;
        }
        return hits;
    }

    /** melhor deslocamento para uma escala: histograma grosso (-120..+120 s) e depois refino de ±1 s em passos de 50 ms */
    private static Fit alignWithScale(long[] ref, long[] cand, double scale) {
        int bins = (int) (2 * COARSE_RANGE_MS / COARSE_BIN_MS) + 3;
        int[] hist = new int[bins];
        for (long c : cand) {
            long t = Math.round(c * scale);
            int i = lowerBound(ref, t - COARSE_RANGE_MS);
            while (i < ref.length && ref[i] <= t + COARSE_RANGE_MS) {
                long off = ref[i] - t;
                int b = (int) ((off + COARSE_RANGE_MS) / COARSE_BIN_MS) + 1;
                if (b >= 0 && b < bins) hist[b]++;
                i++;
            }
        }
        int bestBin = 1, bestVal = -1;
        for (int b = 1; b < bins - 1; b++) {
            int v = hist[b - 1] + hist[b] + hist[b + 1]; // 750 ms de largura
            if (v > bestVal) { bestVal = v; bestBin = b; }
        }
        long center = (long) (bestBin - 1) * COARSE_BIN_MS - COARSE_RANGE_MS + COARSE_BIN_MS / 2;
        int bestCount = -1;
        ArrayList<Long> bestOffs = new ArrayList<>();
        for (long off = center - 1000; off <= center + 1000; off += 50) {
            int cnt = countMatches(ref, cand, scale, off);
            if (cnt > bestCount) { bestCount = cnt; bestOffs.clear(); bestOffs.add(off); }
            else if (cnt == bestCount) bestOffs.add(off);
        }
        Fit f = new Fit();
        f.scale = scale;
        f.offsetMs = bestOffs.isEmpty() ? 0 : bestOffs.get(bestOffs.size() / 2);
        int denom = Math.max(1, Math.min(cand.length, ref.length));
        f.score = Math.max(0, bestCount) / (double) denom;
        return f;
    }

    /** melhor transformação (escala x deslocamento) que leva a candidata para cima da referência; score < MIN_SCORE = não confiável */
    static Fit align(long[] ref, long[] cand) {
        Fit none = new Fit();
        if (ref == null || cand == null || ref.length < 20 || cand.length < 20) return none;
        Fit best = alignWithScale(ref, cand, SCALES[0]);
        Fit bestOther = null;
        for (int i = 1; i < SCALES.length; i++) {
            Fit f = alignWithScale(ref, cand, SCALES[i]);
            if (bestOther == null || f.score > bestOther.score) bestOther = f;
        }
        if (bestOther != null && bestOther.score > best.score + SCALE_MARGIN) best = bestOther;
        return best;
    }
}
