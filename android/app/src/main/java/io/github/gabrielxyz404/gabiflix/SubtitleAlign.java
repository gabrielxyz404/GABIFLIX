package io.github.gabrielxyz404.gabiflix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * O resultado só é aceito com nota >= 0,5 e se cada METADE das falas também casar (>= 0,35): se só uma parte
 * do filme bate, é outra versão/corte e o alinhamento é rejeitado em vez de piorar a legenda.
 *
 * Fase 2: (1) a escala é refinada por regressão robusta sobre os pares casados e há uma busca livre de 0,95 a 1,05;
 * (2) quando a transformação única falha, procura-se um modelo de 2 ou 3 trechos (mesma escala, deslocamento próprio),
 * por programação dinâmica, que corrige legendas de versões com cortes; (3) sem nenhuma referência, o consenso entre as
 * próprias legendas PT escolhe (não alinha) o grupo mais provável.
 */
final class SubtitleAlign {
    private SubtitleAlign() {}

    /** razões entre taxas de quadros mais comuns (a primeira, 1, é "sem mudança de velocidade") */
    static final double[] SCALES = {1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0};
    static final long COARSE_RANGE_MS = 300000;
    static final long COARSE_BIN_MS = 250;
    static final long TOLERANCE_MS = 300;
    /** pontuação mínima (fração das falas que cai em cima de uma fala da referência) para aceitar o alinhamento */
    static final double MIN_SCORE = 0.5;
    /** nota mínima para a exceção de metades consistentes (ver Fit.ok) */
    static final double LOW_SCORE = 0.4;
    /** abaixo disto nem parece a mesma legenda (outro filme/episódio); entre isto e MIN_SCORE = "versão diferente" */
    static final double WEAK_SCORE = 0.35;
    /** cada metade das falas precisa de pelo menos esta nota com a transformação encontrada */
    static final double MIN_HALF_SCORE = 0.35;
    /** a candidata precisa ter pelo menos esta fração das falas da referência na janela que cobre (forçadas têm 5-20%) */
    static final double MIN_CUE_RATIO = 0.25;
    /** a referência precisa ter pelo menos esta fração das falas da candidata (senão valida quase nada) */
    static final double MIN_REF_RATIO = 0.25;
    /** margem mínima da nota sobre a nota por acaso (ver Fit.nullScore) */
    static final double MIN_NULL_MARGIN = 0.0;
    /** só confere a razão de falas com referência de pelo menos isto (referências minúsculas já não passam de 20 falas) */
    static final int RATIO_MIN_REF = 60;
    /** uma escala diferente de 1 só vence se for melhor que a escala 1 por esta margem */
    static final double SCALE_MARGIN = 0.05;

    static final class Fit {
        double scale = 1.0;
        long offsetMs = 0;
        double score = 0;
        double scoreFirstHalf = 0, scoreSecondHalf = 0;
        /** ajuste por partes: instantes de corte (no tempo da legenda original) e o deslocamento de cada trecho; null = transformação única */
        long[] cuts = null;
        long[] offs = null;
        double[] segScores = null;
        int[] segCues = null;
        boolean pieceOk = false;
        /** fração do intervalo de tempo da referência que a legenda (já transformada) cobre; < 0,75 = legenda parcial (ex.: só o CD1) */
        double coverage = 1.0;
        /** a escala veio da busca livre/regressão (não é uma das razões fixas de fps) */
        boolean freeScale = false;
        /** a candidata tem muito menos falas que a referência na mesma janela (legenda "forçada", só falas estrangeiras, ou incompleta) */
        boolean sparse = false;
        /** a referência tem muito menos falas que a candidata (referência forçada/incompleta: valida pouco) */
        boolean sparseRef = false;
        /** falas da candidata / falas da referência na janela coberta */
        double cueRatio = 1.0;
        /** nota que a MESMA transformação tem quando deslocada de 9 a 53 s (o acaso: legendas densas casam com qualquer coisa); a nota real tem de estar bem acima */
        double nullScore = 0;
        boolean halvesOk() { return scoreFirstHalf >= MIN_HALF_SCORE && scoreSecondHalf >= MIN_HALF_SCORE; }
        boolean singleOk() {
            if (!halvesOk()) return false;
            if (score >= MIN_SCORE) return true;
            return score >= LOW_SCORE && Math.abs(scoreFirstHalf - scoreSecondHalf) <= 0.15;
        }
        /** aceito: transformação única com nota >= 0,5 e as duas metades batendo (ou 0,4-0,5 com metades parecidas), ou ajuste por partes validado */
        boolean ok() { return !sparse && !sparseRef && score - nullScore >= MIN_NULL_MARGIN && (cuts != null ? pieceOk : singleOk()); }
        boolean piecewise() { return cuts != null && pieceOk; }
        /** bateu em parte (nota global >= WEAK_SCORE) mas foi rejeitado: outra versão/corte do filme */
        boolean differentVersion() { return !ok() && !sparse && !sparseRef && score >= WEAK_SCORE; }
        boolean lowMargin() { return score - nullScore < MIN_NULL_MARGIN; }
        long offsetAt(long c) {
            if (cuts == null) return offsetMs;
            int k = 0;
            while (k < cuts.length && c >= cuts[k]) k++;
            return offs[k];
        }
        long map(long c) { return Math.round(c * scale) + offsetAt(c); }
        /** precisa reescrever o arquivo? */
        boolean changes() { return cuts != null || scale != 1.0 || Math.abs(offsetMs) >= 50; }
        String apply(String text) {
            return cuts == null ? transform(text, scale, offsetMs) : transformSegments(text, scale, cuts, offs);
        }
    }

    /** uma fala da legenda (início em ms e texto numa linha) */
    static final class Cue {
        final long startMs;
        final String text;
        Cue(long startMs, String text) { this.startMs = startMs; this.text = text; }
    }

    private static final Pattern START = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})[ \\t]*-->[^\\n]*", Pattern.MULTILINE);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>|\\{\\\\[^}]*\\}");

    /** falas (início + texto sem marcas, numa linha) em ordem de tempo (SRT ou VTT); no máximo 6000 */
    static ArrayList<Cue> cues(String text) {
        ArrayList<Cue> out = new ArrayList<>();
        Matcher m = START.matcher(text);
        while (m.find() && out.size() < 6000) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long start = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
            int a = m.end();
            int b = text.indexOf("\n\n", a);
            int b2 = text.indexOf("\r\n\r\n", a);
            if (b < 0 || (b2 >= 0 && b2 < b)) b = b2;
            if (b < 0) b = Math.min(text.length(), a + 400);
            String body = text.substring(a, Math.min(b, a + 400));
            body = TAGS.matcher(body).replaceAll("").replaceAll("\\s+", " ").trim();
            out.add(new Cue(start, body));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.startMs, y.startMs));
        return out;
    }

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

    private static final Pattern NON_SPEECH = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|<[^>]*>|\\{[^}]*\\}|[♪♫♬#*]+");

    /**
     * início das falas de DIÁLOGO (ordem crescente): ignora as falas que são só descrição de som ("[porta bate]", "(música)", "♪ ♪"),
     * que existem numa legenda para surdos (SDH) e não na outra e só atrapalham o casamento. Se sobrar quase nada, devolve todas.
     * É o que o alinhamento deve usar nos dois lados (referência e candidata).
     */
    static long[] alignStarts(String text) {
        ArrayList<Long> list = new ArrayList<>();
        long cur = -1;
        StringBuilder body = new StringBuilder();
        int total = 0;
        int i = 0, n = text.length();
        while (i <= n) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = n;
            String ln = text.substring(i, e);
            i = e + 1;
            Matcher m = START.matcher(ln);
            boolean isTime = m.find();
            if (isTime || ln.trim().isEmpty()) {
                if (cur >= 0 && list.size() < 6000) {
                    total++;
                    if (hasSpeech(body)) list.add(cur);
                }
                cur = -1;
                body.setLength(0);
                if (isTime) {
                    long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
                    cur = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
                }
            } else if (cur >= 0) {
                if (body.length() < 600) body.append(ln).append(' ');
            }
            if (e >= n) break;
        }
        if (cur >= 0 && list.size() < 6000) { total++; if (hasSpeech(body)) list.add(cur); }
        if (list.size() < 20 || list.size() < 0.5 * total) return cueStarts(text);
        long[] out = new long[list.size()];
        for (int k = 0; k < out.length; k++) out[k] = list.get(k);
        Arrays.sort(out);
        return out;
    }

    private static boolean hasSpeech(CharSequence body) {
        String s = NON_SPEECH.matcher(body).replaceAll(" ");
        for (int k = 0; k < s.length(); k++) if (Character.isLetter(s.charAt(k))) return true;
        return false;
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

    private static final Pattern FIRST_TS = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})");

    /** como transform, mas com um deslocamento por trecho: o trecho de cada fala vem do início dela (tempo original) e os cortes estão no tempo original */
    static String transformSegments(String text, double k, long[] cuts, long[] offs) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.contains("-->")) {
                Matcher m = FIRST_TS.matcher(ln);
                long off = offs[0];
                if (m.find()) {
                    long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
                    long ms = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
                    int q = 0;
                    while (q < cuts.length && ms >= cuts[q]) q++;
                    off = offs[q];
                }
                ln = transformLine(ln, k, off);
            }
            out.append(ln);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
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

    private static boolean near(long[] ref, long t, long tol) {
        int i = lowerBound(ref, t);
        return (i < ref.length && ref[i] - t <= tol) || (i > 0 && t - ref[i - 1] <= tol);
    }

    /** quantas falas da candidata (já multiplicadas por scale e somadas a off) caem a menos de TOLERANCE_MS de uma fala da referência */
    private static int countMatches(long[] ref, long[] cand, double scale, long off) {
        int hits = 0;
        for (long c : cand) if (near(ref, Math.round(c * scale) + off, TOLERANCE_MS)) hits++;
        return hits;
    }

    private static Fit single(long[] ref, long[] cand, double scale, long off) {
        Fit f = new Fit();
        f.scale = scale;
        f.offsetMs = off;
        f.score = countMatches(ref, cand, scale, off) / (double) Math.max(1, Math.min(cand.length, ref.length));
        return f;
    }

    /** melhor deslocamento para uma escala: histograma grosso (-300..+300 s) e depois refino de ±1 s em passos de 50 ms */
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

    /** nota de um trecho da candidata [from,to): acertos / min(falas do trecho, falas da referência na mesma janela de tempo) */
    private static double rangeScore(long[] ref, long[] cand, int from, int to, Fit f) {
        if (to - from < 5) return 0;
        long t0 = f.map(cand[from]) - TOLERANCE_MS, t1 = f.map(cand[to - 1]) + TOLERANCE_MS;
        int refCount = lowerBound(ref, t1 + 1) - lowerBound(ref, t0);
        int hits = 0;
        for (int k = from; k < to; k++) if (near(ref, f.map(cand[k]), TOLERANCE_MS)) hits++;
        int denom = Math.max(1, Math.min(to - from, refCount));
        return hits / (double) denom;
    }

    private static void halves(long[] ref, long[] cand, Fit f) {
        int mid = cand.length / 2;
        f.scoreFirstHalf = rangeScore(ref, cand, 0, mid, f);
        f.scoreSecondHalf = rangeScore(ref, cand, mid, cand.length, f);
    }

    // ---------- escala livre + regressão robusta ----------
    static final double FREE_MIN = 0.95, FREE_MAX = 1.05, FREE_STEP = 0.0005;
    /** com nota >= isto (e aceito) não procura mais nada */
    static final double SKIP_SCORE = 0.8;

    /** refina escala e deslocamento por mínimos quadrados sobre os pares casados, rejeitando pares fora da tolerância (que encolhe: 1000, 600, 400, 300 ms) */
    static Fit refine(long[] ref, long[] cand, Fit f) {
        double s = f.scale, o = f.offsetMs;
        double[] tols = {1000, 600, 400, 300};
        long[] pc = new long[cand.length], pr = new long[cand.length];
        int pn = 0;
        for (double tol : tols) {
            int n = 0;
            for (long c : cand) {
                double t = c * s + o;
                int i = lowerBound(ref, Math.round(t));
                long best = -1;
                double bd = tol + 1;
                if (i < ref.length && Math.abs(ref[i] - t) < bd) { bd = Math.abs(ref[i] - t); best = ref[i]; }
                if (i > 0 && Math.abs(ref[i - 1] - t) < bd) { best = ref[i - 1]; }
                if (best >= 0) { pc[n] = c; pr[n] = best; n++; }
            }
            if (n < 20) break;
            double mc = 0, mr = 0;
            for (int i = 0; i < n; i++) { mc += pc[i]; mr += pr[i]; }
            mc /= n; mr /= n;
            double sxy = 0, sxx = 0;
            for (int i = 0; i < n; i++) { sxy += (pc[i] - mc) * (pr[i] - mr); sxx += (pc[i] - mc) * (pc[i] - mc); }
            if (sxx <= 0) break;
            double a = sxy / sxx;
            if (!(a >= FREE_MIN && a <= FREE_MAX)) break;
            s = a;
            o = mr - a * mc;
            pn = n;
        }
        // perto de uma razão fixa de fps: usa a razão exata (só recalcula o deslocamento)
        if (pn >= 20) {
            double snapTo = -1;
            for (double fx : SCALES) if (Math.abs(s - fx) < 2e-4) snapTo = fx;
            if (snapTo > 0) {
                double[] d = new double[pn];
                for (int i = 0; i < pn; i++) d[i] = pr[i] - snapTo * pc[i];
                java.util.Arrays.sort(d);
                Fit g = single(ref, cand, snapTo, Math.round(d[pn / 2]));
                if (g.score >= f.score) return g;
            }
        }
        Fit g = single(ref, cand, s, Math.round(o));
        g.freeScale = !isFixedScale(s);
        return g.score >= f.score ? g : f;
    }

    private static boolean isFixedScale(double s) {
        for (double fx : SCALES) if (Math.abs(s - fx) < 3e-4) return true;
        return false;
    }

    /** varre escalas de 0,95 a 1,05 (passo 0,0005) com uma amostra das falas e histograma de deslocamentos; refina o melhor por regressão */
    static Fit freeScale(long[] ref, long[] cand) {
        int n = cand.length;
        int step = Math.max(1, n / 250);
        long t0 = cand[n / 2];
        final long range = COARSE_RANGE_MS;
        final long bin = 500;
        int bins = (int) (2 * range / bin) + 3;
        int[] hist = new int[bins];
        double bestS = 1, bestVal = -1;
        long bestD = 0;
        for (double s = FREE_MIN; s <= FREE_MAX + 1e-9; s += FREE_STEP) {
            java.util.Arrays.fill(hist, 0);
            for (int k = 0; k < n; k += step) {
                long u = Math.round((cand[k] - t0) * s) + t0;
                int i = lowerBound(ref, u - range);
                while (i < ref.length && ref[i] <= u + range) {
                    int b = (int) ((ref[i] - u + range) / bin) + 1;
                    if (b >= 0 && b < bins) hist[b]++;
                    i++;
                }
            }
            for (int b = 1; b < bins - 1; b++) {
                int v = hist[b - 1] + hist[b] + hist[b + 1];
                if (v > bestVal) { bestVal = v; bestS = s; bestD = (long) (b - 1) * bin - range + bin / 2; }
            }
        }
        Fit f = single(ref, cand, bestS, Math.round(t0 * (1 - bestS) + bestD));
        Fit g = refine(ref, cand, f);
        // a regressão parte de ±1 s: se a busca estava longe, tenta de novo a partir do melhor resultado
        g = refine(ref, cand, g);
        g.freeScale = !isFixedScale(g.scale);
        return g;
    }

    // ---------- ajuste por partes (cortes) ----------
    static final int MIN_SEG_CUES = 40;
    static final double SEG_MIN_SCORE = 0.5;
    static final double PIECE_MIN_SCORE = 0.55;
    /** o ajuste por partes só vence se a nota subir tanto em relação ao melhor ajuste único */
    static final double PIECE_GAIN = 0.08;
    static final int CUT_PENALTY = 10;
    static final long MIN_CUT_DELTA_MS = 1000;
    private static final int PIECE_WINDOW = 40, PIECE_STRIDE = 20, PIECE_MAX_OFFSETS = 16;

    /** picos do histograma de deslocamentos das falas [a,b) com a escala s: {deslocamento, apoio} */
    private static void windowPeaks(long[] ref, long[] cand, int a, int b, double s, ArrayList<long[]> out) {
        int bins = (int) (2 * COARSE_RANGE_MS / COARSE_BIN_MS) + 3;
        int[] hist = new int[bins];
        for (int k = a; k < b; k++) {
            long t = Math.round(cand[k] * s);
            int i = lowerBound(ref, t - COARSE_RANGE_MS);
            while (i < ref.length && ref[i] <= t + COARSE_RANGE_MS) {
                int bb = (int) ((ref[i] - t + COARSE_RANGE_MS) / COARSE_BIN_MS) + 1;
                if (bb >= 0 && bb < bins) hist[bb]++;
                i++;
            }
        }
        int[] sm = new int[bins];
        for (int i = 1; i < bins - 1; i++) sm[i] = hist[i - 1] + hist[i] + hist[i + 1];
        int p1 = 1;
        for (int i = 1; i < bins - 1; i++) if (sm[i] > sm[p1]) p1 = i;
        int p2 = -1;
        for (int i = 1; i < bins - 1; i++) if (Math.abs(i - p1) >= 8 && (p2 < 0 || sm[i] > sm[p2])) p2 = i;
        int[] peaks = p2 >= 0 && sm[p2] >= 0.6 * sm[p1] ? new int[]{p1, p2} : new int[]{p1};
        for (int p : peaks) {
            long center = (long) (p - 1) * COARSE_BIN_MS - COARSE_RANGE_MS + COARSE_BIN_MS / 2;
            int bestCnt = -1;
            ArrayList<Long> offs = new ArrayList<>();
            for (long off = center - 600; off <= center + 600; off += 50) {
                int cnt = 0;
                for (int k = a; k < b; k++) if (near(ref, Math.round(cand[k] * s) + off, TOLERANCE_MS)) cnt++;
                if (cnt > bestCnt) { bestCnt = cnt; offs.clear(); offs.add(off); } else if (cnt == bestCnt) offs.add(off);
            }
            if (bestCnt >= Math.max(6, 0.3 * (b - a))) out.add(new long[]{offs.get(offs.size() / 2), bestCnt});
        }
    }

    /** modelo de 2 ou 3 trechos (mesma escala, deslocamento próprio) por programação dinâmica; null se nada convence */
    static Fit piecewise(long[] ref, long[] cand, double[] scales, Fit singleFit) {
        Fit best = null;
        for (double s : scales) {
            Fit pw = piecewiseAt(ref, cand, s, singleFit);
            if (pw != null && (best == null || pw.score > best.score)) best = pw;
        }
        return best;
    }

    private static Fit piecewiseAt(long[] ref, long[] cand, double s, Fit singleFit) {
        int n = cand.length;
        if (n < 2 * MIN_SEG_CUES) return null;
        ArrayList<long[]> found = new ArrayList<>();
        for (int a = 0; ; a += PIECE_STRIDE) {
            int b = Math.min(n, a + PIECE_WINDOW);
            if (b - a < PIECE_WINDOW) a = Math.max(0, n - PIECE_WINDOW);
            windowPeaks(ref, cand, a, Math.min(n, a + PIECE_WINDOW), s, found);
            if (a + PIECE_WINDOW >= n) break;
        }
        if (singleFit != null && singleFit.scale == s) found.add(new long[]{singleFit.offsetMs, n / 4});
        Collections.sort(found, (x, y) -> Long.compare(x[0], y[0]));
        ArrayList<long[]> clusters = new ArrayList<>();
        for (int i = 0; i < found.size(); ) {
            int j = i;
            double sw = 0, so = 0;
            long first = found.get(i)[0];
            while (j < found.size() && found.get(j)[0] - first <= 400) { sw += found.get(j)[1]; so += (double) found.get(j)[0] * found.get(j)[1]; j++; }
            clusters.add(new long[]{Math.round(so / sw), (long) sw});
            i = j;
        }
        Collections.sort(clusters, (x, y) -> Long.compare(y[1], x[1]));
        int M = Math.min(PIECE_MAX_OFFSETS, clusters.size());
        if (M < 2) return null;
        long[] offs = new long[M];
        for (int m = 0; m < M; m++) offs[m] = clusters.get(m)[0];
        int[][] h = new int[M][n];
        for (int m = 0; m < M; m++) for (int i = 0; i < n; i++) h[m][i] = near(ref, Math.round(cand[i] * s) + offs[m], TOLERANCE_MS) ? 1 : 0;

        Fit res = null;
        for (int K = 2; K <= 3; K++) {
            Fit f = dpSegments(ref, cand, s, offs, h, K);
            if (f == null) continue;
            if (res == null || f.score > res.score + 0.03) res = f; // 3 trechos só se melhorar de verdade
        }
        return res;
    }

    private static Fit dpSegments(long[] ref, long[] cand, double s, long[] offs, int[][] h, int K) {
        int n = cand.length, M = offs.length;
        final int NEG = Integer.MIN_VALUE / 2;
        int[][][] g = new int[K + 1][n + 1][M];
        int[][][] from = new int[K + 1][n + 1][M];
        for (int[][] a : g) for (int[] b : a) java.util.Arrays.fill(b, NEG);
        for (int m = 0; m < M; m++) g[1][0][m] = 0;
        for (int i = 1; i <= n; i++) {
            for (int m = 0; m < M; m++) {
                int hit = h[m][i - 1];
                if (g[1][i - 1][m] > NEG) g[1][i][m] = g[1][i - 1][m] + hit;
                for (int k = 2; k <= K; k++) {
                    int bestV = NEG, bestF = 0;
                    if (g[k][i - 1][m] > NEG) { bestV = g[k][i - 1][m] + hit; bestF = 0; }
                    if (i >= 2) for (int q = 0; q < M; q++) {
                        if (q == m || g[k - 1][i - 1][q] <= NEG) continue;
                        int v = g[k - 1][i - 1][q] + hit - CUT_PENALTY;
                        if (v > bestV) { bestV = v; bestF = q + 1; }
                    }
                    g[k][i][m] = bestV;
                    from[k][i][m] = bestF;
                }
            }
        }
        int bm = -1;
        for (int m = 0; m < M; m++) if (g[K][n][m] > NEG && (bm < 0 || g[K][n][m] > g[K][n][bm])) bm = m;
        if (bm < 0) return null;
        ArrayList<int[]> segs = new ArrayList<>(); // {início, fim, índice do deslocamento}
        int i = n, k = K, m = bm, end = n;
        while (i > 0) {
            int fr = k > 1 ? from[k][i][m] : 0;
            if (fr != 0) { segs.add(new int[]{i - 1, end, m}); end = i - 1; m = fr - 1; k--; }
            i--;
        }
        segs.add(new int[]{0, end, m});
        Collections.reverse(segs);
        if (segs.size() != K) return null;
        int S = segs.size();
        long[] cuts = new long[S - 1], so = new long[S];
        for (int q = 0; q < S; q++) {
            so[q] = offs[segs.get(q)[2]];
            if (segs.get(q)[1] - segs.get(q)[0] < MIN_SEG_CUES) return null;
            if (q > 0) {
                cuts[q - 1] = (cand[segs.get(q)[0] - 1] + cand[segs.get(q)[0]]) / 2;
                if (Math.abs(so[q] - so[q - 1]) < MIN_CUT_DELTA_MS) return null;
            }
        }
        Fit f = new Fit();
        f.scale = s;
        f.cuts = cuts;
        f.offs = so;
        f.offsetMs = so[0];
        // escala comum refinada por regressão dentro dos trechos
        refinePieces(ref, cand, f, segs);
        int hits = 0;
        for (long c : cand) if (near(ref, f.map(c), TOLERANCE_MS)) hits++;
        f.score = hits / (double) Math.max(1, Math.min(n, ref.length));
        f.segScores = new double[S];
        f.segCues = new int[S];
        boolean ok = f.score >= PIECE_MIN_SCORE;
        for (int q = 0; q < S; q++) {
            f.segCues[q] = segs.get(q)[1] - segs.get(q)[0];
            f.segScores[q] = rangeScore(ref, cand, segs.get(q)[0], segs.get(q)[1], f);
            if (f.segCues[q] < MIN_SEG_CUES || f.segScores[q] < SEG_MIN_SCORE) ok = false;
        }
        halves(ref, cand, f);
        f.pieceOk = ok;
        f.freeScale = !isFixedScale(f.scale);
        return ok ? f : null;
    }

    /** mesma escala para todos os trechos (inclinação comum dos mínimos quadrados dentro de cada trecho) e deslocamento próprio */
    private static void refinePieces(long[] ref, long[] cand, Fit f, ArrayList<int[]> segs) {
        for (int round = 0; round < 2; round++) {
            double sxy = 0, sxx = 0;
            int S = segs.size();
            double[] mc = new double[S], mr = new double[S];
            int[] cnt = new int[S];
            ArrayList<long[]>[] pairs = new ArrayList[S];
            for (int q = 0; q < S; q++) {
                pairs[q] = new ArrayList<>();
                for (int i = segs.get(q)[0]; i < segs.get(q)[1]; i++) {
                    double t = cand[i] * f.scale + f.offs[q];
                    int lb = lowerBound(ref, Math.round(t));
                    long best = -1;
                    double bd = 401;
                    if (lb < ref.length && Math.abs(ref[lb] - t) < bd) { bd = Math.abs(ref[lb] - t); best = ref[lb]; }
                    if (lb > 0 && Math.abs(ref[lb - 1] - t) < bd) best = ref[lb - 1];
                    if (best >= 0) { pairs[q].add(new long[]{cand[i], best}); mc[q] += cand[i]; mr[q] += best; cnt[q]++; }
                }
                if (cnt[q] > 0) { mc[q] /= cnt[q]; mr[q] /= cnt[q]; }
                for (long[] p : pairs[q]) { sxy += (p[0] - mc[q]) * (p[1] - mr[q]); sxx += (p[0] - mc[q]) * (p[0] - mc[q]); }
            }
            if (sxx <= 0) return;
            double a = sxy / sxx;
            if (!(a >= FREE_MIN && a <= FREE_MAX)) return;
            for (double fx : SCALES) if (Math.abs(a - fx) < 2e-4) a = fx;
            double old = f.scale;
            f.scale = a;
            for (int q = 0; q < S; q++) if (cnt[q] >= 5) f.offs[q] = Math.round(mr[q] - a * mc[q]);
            f.offsetMs = f.offs[0];
            if (Math.abs(a - old) < 1e-6) return;
        }
    }

    /** contra o ajuste de jitter (traduções com falas cortadas diferente): corte pequeno (< 2,5 s) só vale com trechos quase perfeitos e ganho grande */
    static boolean pieceConvincing(Fit pw, double gain) {
        long minDelta = Long.MAX_VALUE;
        for (int q = 1; q < pw.offs.length; q++) minDelta = Math.min(minDelta, Math.abs(pw.offs[q] - pw.offs[q - 1]));
        if (minDelta >= 2500) return true;
        for (double sc : pw.segScores) if (sc < 0.85) return false;
        return gain >= 0.15;
    }

    /** melhor transformação (escala x deslocamento, ou por partes) que leva a candidata para cima da referência */
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
        best = refine(ref, cand, best);
        halves(ref, cand, best);
        Fit free = null;
        if (!(best.singleOk() && best.score >= SKIP_SCORE)) {
            free = freeScale(ref, cand);
            halves(ref, cand, free);
            if (free.score > best.score + SCALE_MARGIN && (free.singleOk() || !best.singleOk())) best = free;
        }
        if (!(best.singleOk() && best.score >= 0.75)) {
            ArrayList<Double> sc = new ArrayList<>();
            sc.add(best.scale);
            if (free != null && !sc.contains(free.scale)) sc.add(free.scale);
            if (!sc.contains(1.0)) sc.add(1.0);
            if (bestOther != null && !sc.contains(bestOther.scale)) sc.add(bestOther.scale);
            double[] arr = new double[Math.min(4, sc.size())];
            for (int i = 0; i < arr.length; i++) arr[i] = sc.get(i);
            Fit pw = piecewise(ref, cand, arr, best);
            if (pw != null && pw.pieceOk && pw.score >= best.score + PIECE_GAIN && pieceConvincing(pw, pw.score - best.score)) best = pw;
        }
        long lo = Math.max(ref[0], best.map(cand[0])), hi = Math.min(ref[ref.length - 1], best.map(cand[cand.length - 1]));
        best.coverage = Math.max(0, hi - lo) / (double) Math.max(1, ref[ref.length - 1] - ref[0]);
        // legenda "forçada"/esparsa: acerta todas as poucas falas que tem e ganhava nota máxima (a nota divide pelo MENOR número de falas)
        long wLo = best.map(cand[0]), wHi = best.map(cand[cand.length - 1]);
        int refWin = lowerBound(ref, wHi + 1) - lowerBound(ref, wLo);
        {
            best.cueRatio = cand.length / (double) Math.max(1, refWin);
            if (best.cueRatio < MIN_CUE_RATIO) best.sparse = true;
            if (refWin < MIN_REF_RATIO * cand.length) best.sparseRef = true;
        }
        best.nullScore = nullScore(ref, cand, best);
        return best;
    }

    private static final long[] NULL_SHIFTS = {-53000, -31000, -17000, -9000, 9000, 17000, 31000, 53000};

    /** maior nota que a mesma transformação (escala/partes) tem com o deslocamento mudado de 9 a 53 s: o nível do acaso */
    static double nullScore(long[] ref, long[] cand, Fit f) {
        int denom = Math.max(1, Math.min(cand.length, ref.length));
        int bestHits = 0;
        for (long d : NULL_SHIFTS) {
            int hits = 0;
            for (long c : cand) if (near(ref, f.map(c) + d, TOLERANCE_MS)) hits++;
            bestHits = Math.max(bestHits, hits);
        }
        return bestHits / (double) denom;
    }

    /** fração das falas da candidata que as duas transformações levam para menos de tol ms uma da outra (a mesma legenda ajustada por duas referências) */
    static double agreement(Fit a, Fit b, long[] cand, long tol) {
        if (cand == null || cand.length == 0) return 0;
        int ok = 0;
        for (long c : cand) if (Math.abs(a.map(c) - b.map(c)) <= tol) ok++;
        return ok / (double) cand.length;
    }

    // ---------- consenso entre legendas (sem referência de tempo) ----------
    /** concordância mínima (sem nenhum ajuste, tolerância de 500 ms: traduções diferentes cortam as falas de modo diferente) para duas legendas contarem como "do mesmo tempo" */
    static final double RAW_AGREE = 0.4;
    static final long RAW_TOL_MS = 500;

    static int matchesTol(long[] ref, long[] cand, long off, long tol) {
        int hits = 0;
        for (long c : cand) if (near(ref, c + off, tol)) hits++;
        return hits;
    }

    /** fração das falas que cai a <= 500 ms de uma fala da outra legenda, sem ajuste nenhum */
    static double rawAgreement(long[] a, long[] b) {
        if (a == null || b == null || a.length < 20 || b.length < 20) return 0;
        return matchesTol(b, a, 0, RAW_TOL_MS) / (double) Math.min(a.length, b.length);
    }

    /**
     * "mesmos tempos": concordância bruta >= RAW_AGREE E bem acima do acaso (a mesma comparação com as falas deslocadas de 9 e 23 s);
     * em séries/animes, com falas a cada 2 s, qualquer par concorda ~35% só por sorte
     */
    static boolean sameTiming(long[] a, long[] b) {
        double raw = rawAgreement(a, b);
        if (raw < RAW_AGREE) return false;
        double chance = 0;
        long[] offs = {9000, -9000, 23000, -23000};
        for (long o : offs) chance = Math.max(chance, matchesTol(b, a, o, RAW_TOL_MS) / (double) Math.min(a.length, b.length));
        return raw >= chance + 0.2;
    }

    /** componentes conexas por concordância bruta: ids 0.. na ordem do primeiro membro; legendas com poucas falas ficam sozinhas */
    static int[] consensusGroups(java.util.List<long[]> starts) {
        int n = starts.size();
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (sameTiming(starts.get(i), starts.get(j))) {
                int a = find(p, i), b = find(p, j);
                if (a != b) p[Math.max(a, b)] = Math.min(a, b);
            }
        }
        int[] id = new int[n];
        java.util.HashMap<Integer, Integer> map = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(p, i);
            Integer g = map.get(r);
            if (g == null) { g = map.size(); map.put(r, g); }
            id[i] = g;
        }
        return id;
    }

    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }

    private static final Pattern TOK_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final java.util.HashSet<String> SOURCE_T = new java.util.HashSet<>(Arrays.asList("webdl", "webrip", "web", "bluray", "remux", "hdtv", "dvdrip", "dvd", "cam", "ts", "hdrip", "hdcam", "telesync"));
    private static final java.util.HashSet<String> RES_T = new java.util.HashSet<>(Arrays.asList("2160p", "4k", "uhd", "1080p", "720p", "480p", "576p"));

    private static java.util.HashSet<String> tokens(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        t = t.replaceAll("web[-. _]?dl", "webdl").replaceAll("blu[-. _]?ray|bdrip|brrip|bd[-. _]?remux", "bluray").replaceAll("web[-. _]?rip", "webrip")
                .replaceAll("h[. ]?264", "x264").replaceAll("h[. ]?265|hevc", "x265").replaceAll("telesync", "ts");
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String w : TOK_SPLIT.split(t)) if (w.length() >= 2 && !w.equals("srt") && !w.equals("sub") && !w.equals("por") && !w.equals("pob") && !w.equals("eng")) out.add(w);
        return out;
    }

    /** parecença (0..1) entre o nome do arquivo de vídeo e o nome da release da legenda: fonte (WEB-DL/BluRay/REMUX...), resolução, grupo e o resto dos termos */
    static double nameSimilarity(String videoName, String subName) {
        if (videoName == null || subName == null || videoName.isEmpty() || subName.isEmpty()) return 0;
        java.util.HashSet<String> v = tokens(videoName), s = tokens(subName);
        if (v.isEmpty() || s.isEmpty()) return 0;
        double score = 0;
        boolean srcV = false, srcS = false, srcBoth = false, resBoth = false;
        for (String w : v) if (SOURCE_T.contains(w)) { srcV = true; if (s.contains(w)) srcBoth = true; }
        for (String w : s) if (SOURCE_T.contains(w)) srcS = true;
        for (String w : v) if (RES_T.contains(w) && s.contains(w)) resBoth = true;
        if (srcBoth) score += 0.35; else if (srcV && srcS) score -= 0.1;
        if (resBoth) score += 0.15;
        // grupo da release = último termo depois do último '-' do nome do vídeo (ex.: ...x264-RARBG)
        String vn = videoName.toLowerCase(Locale.ROOT).replaceAll("\\.[a-z0-9]{2,4}$", "");
        int dash = vn.lastIndexOf('-');
        if (dash > 0 && dash < vn.length() - 2) {
            String grp = vn.substring(dash + 1).replaceAll("[^a-z0-9]", "");
            if (grp.length() >= 3 && s.contains(grp)) score += 0.3;
        }
        int inter = 0;
        for (String w : v) if (s.contains(w)) inter++;
        score += 0.2 * inter / (double) (v.size() + s.size() - inter);
        return Math.max(0, Math.min(1, score));
    }

    /**
     * Escolhe o grupo de legendas que combina (consenso). Pontos do grupo: nº de legendas + 3 se alguma casa com o hash +
     * 2 x melhor parecença de nome + 1,5 se a duração da última fala (mediana do grupo) é compatível com o vídeo (-100 se
     * absurda). Devolve o id do grupo ou -1; só vale como consenso se tiver >= 2 legendas.
     */
    static int pickGroup(int[] comp, boolean[] hash, double[] name, long[] lastMs, long videoLenMs, StringBuilder why) {
        int ng = 0;
        for (int c : comp) ng = Math.max(ng, c + 1);
        int bestG = -1;
        double bestScore = -1e9;
        for (int g = 0; g < ng; g++) {
            int size = 0;
            boolean anyHash = false;
            double nm = 0;
            ArrayList<Long> ends = new ArrayList<>();
            for (int i = 0; i < comp.length; i++) if (comp[i] == g) {
                size++;
                anyHash |= hash[i];
                nm = Math.max(nm, name[i]);
                if (lastMs[i] > 0) ends.add(lastMs[i]);
            }
            if (size == 0) continue;
            double dur = 0;
            if (videoLenMs > 0 && !ends.isEmpty()) {
                Collections.sort(ends);
                long med = ends.get(ends.size() / 2);
                if (med >= videoLenMs * 0.6 && med <= videoLenMs * 1.05) dur = 1.5;
                else if (med < videoLenMs * 0.5 || med > videoLenMs * 1.12) dur = -100;
            }
            double sc = size + (anyHash ? 3 : 0) + 2 * nm + dur;
            if (why != null) why.append("\n  grupo ").append(g).append(": ").append(size).append(" legenda(s)").append(anyHash ? ", hash" : "")
                    .append(", nome ").append(String.format(Locale.ROOT, "%.2f", nm)).append(dur > 0 ? ", duração ok" : dur < 0 ? ", duração absurda" : "")
                    .append(" -> ").append(String.format(Locale.ROOT, "%.2f", sc));
            if (sc > bestScore) { bestScore = sc; bestG = g; }
        }
        return bestG;
    }
}
