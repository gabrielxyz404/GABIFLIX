package io.github.gabrielxyz404.gabiflix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fase 3 das legendas: acertar a legenda pelo ÁUDIO do vídeo (sem referência de texto).
 * Java puro (sem Android) para poder ser testado na JVM:
 *  - leitura do WAV capturado pelo VLC,
 *  - VAD (detector de fala por energia na banda da voz, limiar adaptativo, suavização) -> 1 bit por 10 ms,
 *  - correlação entre a fala detectada em várias janelas e a atividade das falas da legenda,
 *    procurando escala (as fixas de SubtitleAlign) x deslocamento (+-300 s),
 *  - aceita só com pico claro e concordância entre as janelas; senão desiste dizendo o motivo.
 */
final class AudioAlign {
    private AudioAlign() {}

    static final int FRAME_MS = 10;
    /** pico/segundo pico mínimo (segundo pico fora de EXCLUDE_MS do primeiro) */
    static final double MIN_RATIO = 1.3;
    /** janelas que precisam concordar no mesmo deslocamento (de 6) */
    static final int MIN_AGREE = 4;
    static final long AGREE_TOL_MS = 300;
    static final long EXCLUDE_MS = 1500;
    static final long RANGE_MS = 300000;
    static final long COARSE_MS = 100;
    /** média da correlação de Pearson por janela com fala exigida no pico (abaixo disso é ruído) */
    static final double MIN_MEAN_SCORE = 0.12;
    /** sustentação por janela: destaque (z) da nota no deslocamento global frente às notas dela nos outros deslocamentos, e nota mínima */
    static final double Z_MIN = 2.0, MIN_WIN_SCORE = 0.15;
    /** por partes: cada lado precisa de pico próprio claro e de z maior */
    static final double PIECE_RATIO = 1.5, PIECE_Z = 2.5;

    // ---------- WAV ----------
    static final class Pcm {
        float[] x;
        int rate = 8000;
        String note = "";
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static long le32(byte[] b, int o) { return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24); }

    /**
     * WAV (RIFF) 16 bits, ou PCM cru s16le (mono, rate padrão) quando não há cabeçalho RIFF.
     * Tolerante: o VLC deixa tamanhos 0/0xFFFFFFFF se o arquivo não foi finalizado.
     */
    static Pcm parseWav(byte[] b, int defaultRate) {
        Pcm p = new Pcm();
        p.rate = defaultRate;
        int off = 0, channels = 1, bits = 16;
        int end = b.length;
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            int pos = 12;
            boolean gotData = false;
            while (pos + 8 <= b.length) {
                String id = new String(b, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                long sz = le32(b, pos + 4);
                if (id.equals("fmt ") && pos + 24 <= b.length) {
                    channels = Math.max(1, le16(b, pos + 10));
                    p.rate = (int) le32(b, pos + 12);
                    bits = le16(b, pos + 22);
                } else if (id.equals("data")) {
                    off = pos + 8;
                    long avail = b.length - off;
                    end = (int) (sz <= 0 || sz > avail ? b.length : off + sz);
                    gotData = true;
                    p.note = sz <= 0 || sz > avail ? "cabeçalho sem tamanho final" : "";
                    break;
                }
                if (sz < 0 || pos + 8 + sz > b.length) break;
                pos += 8 + (int) sz + (int) (sz & 1);
            }
            if (!gotData) { p.x = new float[0]; p.note = "sem bloco data"; return p; }
        } else {
            p.note = "PCM cru";
        }
        if (bits != 16) { p.x = new float[0]; p.note = "formato " + bits + " bits"; return p; }
        int frames = (end - off) / (2 * channels);
        float[] x = new float[Math.max(0, frames)];
        for (int i = 0; i < frames; i++) {
            int s = 0;
            for (int c = 0; c < channels; c++) {
                int o = off + (i * channels + c) * 2;
                s += (short) ((b[o] & 0xFF) | (b[o + 1] << 8));
            }
            x[i] = s / (32768f * channels);
        }
        p.x = x;
        return p;
    }

    // ---------- VAD ----------
    private static final class Biquad {
        final double b0, b1, b2, a1, a2;
        double z1, z2;
        Biquad(boolean high, double fc, double q, double fs) {
            double w0 = 2 * Math.PI * Math.min(fc, fs * 0.45) / fs, cs = Math.cos(w0), al = Math.sin(w0) / (2 * q);
            double a0 = 1 + al;
            if (high) { b0 = (1 + cs) / 2 / a0; b1 = -(1 + cs) / a0; b2 = (1 + cs) / 2 / a0; }
            else { b0 = (1 - cs) / 2 / a0; b1 = (1 - cs) / a0; b2 = (1 - cs) / 2 / a0; }
            a1 = -2 * cs / a0;
            a2 = (1 - al) / a0;
        }
        double run(double in) {
            double out = b0 * in + z1;
            z1 = b1 * in - a1 * out + z2;
            z2 = b2 * in - a2 * out;
            return out;
        }
    }

    /** energia (dB) por quadro de 10 ms (janela de 25 ms centrada) na banda 300-3400 Hz */
    static double[] bandEnergyDb(float[] x, int rate) {
        Biquad h1 = new Biquad(true, 300, 0.707, rate), h2 = new Biquad(true, 300, 0.707, rate), lp = new Biquad(false, 3400, 0.707, rate);
        double[] y2 = new double[x.length];
        double prev = 0;
        for (int i = 0; i < x.length; i++) {
            double pe = x[i] - 0.5 * prev; // pré-ênfase leve
            prev = x[i];
            y2[i] = lp.run(h2.run(h1.run(pe))) ;
            y2[i] = y2[i] * y2[i];
        }
        int hop = rate / 100, half = (int) (rate * 0.0125);
        int n = x.length / hop;
        double[] db = new double[n];
        double[] cum = new double[y2.length + 1];
        for (int i = 0; i < y2.length; i++) cum[i + 1] = cum[i] + y2[i];
        for (int f = 0; f < n; f++) {
            int c = f * hop + hop / 2;
            int a = Math.max(0, c - half), b = Math.min(y2.length, c + half);
            double e = b > a ? (cum[b] - cum[a]) / (b - a) : 0;
            db[f] = 10 * Math.log10(e + 1e-10);
        }
        return db;
    }

    private static double percentile(double[] v, double p) {
        double[] c = v.clone();
        Arrays.sort(c);
        if (c.length == 0) return 0;
        return c[(int) Math.min(c.length - 1, Math.max(0, Math.round(p * (c.length - 1))))];
    }

    static final double VAD_LOW_PCT = 0.12, VAD_HIGH_PCT = 0.92, VAD_HI_FRAC = 0.40, VAD_LO_FRAC = 0.20, VAD_MIN_RANGE_DB = 8;
    static final int MIN_RUN = 15, BRIDGE = 40; // 150 ms e 400 ms

    /** 1 byte por 10 ms: 1 = fala; limiar adaptativo (percentis da própria janela) com histerese + suavização */
    static byte[] vad(float[] x, int rate) {
        double[] db = bandEnergyDb(x, rate);
        int n = db.length;
        byte[] sp = new byte[n];
        lastSoft = new float[n];
        if (n < 50) return sp;
        // mediana de 5 quadros tira picos isolados
        double[] m = new double[n];
        double[] w = new double[5];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 5; k++) w[k] = db[Math.min(n - 1, Math.max(0, i + k - 2))];
            Arrays.sort(w);
            m[i] = w[2];
        }
        if (BG_SUB) {
            // tira o "chão" local (música/ruído contínuos): mínimo móvel de ~1,2 s (percentil 10) alisado
            int h = BG_FRAMES / 2;
            double[] bg = new double[n];
            double[] buf = new double[2 * h + 1];
            for (int i = 0; i < n; i++) {
                int c = 0;
                for (int k = -h; k <= h; k += 3) buf[c++] = m[Math.max(0, Math.min(n - 1, i + k))];
                double[] cc = Arrays.copyOf(buf, c);
                Arrays.sort(cc);
                bg[i] = cc[(int) (cc.length * 0.15)];
            }
            for (int i = 0; i < n; i++) m[i] = m[i] - bg[i];
        }
        double lo = percentile(m, VAD_LOW_PCT), hi = percentile(m, VAD_HIGH_PCT);
        lastSoft = new float[n];
        if (hi - lo < VAD_MIN_RANGE_DB) return sp; // sem dinâmica: silêncio ou ruído contínuo
        for (int q = 0; q < n; q++) lastSoft[q] = (float) Math.max(0, Math.min(1, (m[q] - lo) / (hi - lo)));
        double thrH = lo + VAD_HI_FRAC * (hi - lo), thrL = lo + VAD_LO_FRAC * (hi - lo);
        // histerese: um trecho acima do limiar baixo só vale se tocar o limiar alto
        int i = 0;
        while (i < n) {
            if (m[i] <= thrL) { i++; continue; }
            int j = i;
            boolean strong = false;
            while (j < n && m[j] > thrL) { if (m[j] > thrH) strong = true; j++; }
            if (strong) for (int k = i; k < j; k++) sp[k] = 1;
            i = j;
        }
        // remove rajadas curtas, depois fecha buracos curtos (pausas entre palavras)
        dropShort(sp, 1, MIN_RUN);
        dropShort(sp, 0, BRIDGE);
        dropShort(sp, 1, MIN_RUN);
        return sp;
    }

    /** troca por (1-v) as sequências de v com menos de min quadros (que não estão encostadas nas pontas, no caso de 0) */
    private static void dropShort(byte[] a, int v, int min) {
        int n = a.length, i = 0;
        while (i < n) {
            if (a[i] != v) { i++; continue; }
            int j = i;
            while (j < n && a[j] == v) j++;
            boolean edge = i == 0 || j == n;
            if (j - i < min && !(v == 0 && edge)) for (int k = i; k < j; k++) a[k] = (byte) (1 - v);
            i = j;
        }
    }

    // ---------- janelas ----------
    static final class Win {
        long startMs;
        byte[] sp;
        float[] soft;
        double[] pre, pre2;
        int n;
        double frac;
        String info = "";
        Win(long startMs, byte[] sp) { this(startMs, sp, null); }
        Win(long startMs, byte[] sp, float[] soft) {
            this.startMs = startMs;
            this.sp = sp;
            n = sp.length;
            if (soft == null || !USE_SOFT) { soft = new float[n]; for (int i = 0; i < n; i++) soft[i] = sp[i]; }
            this.soft = soft;
            pre = new double[n + 1];
            pre2 = new double[n + 1];
            double bin = 0;
            for (int i = 0; i < n; i++) { pre[i + 1] = pre[i] + soft[i]; pre2[i + 1] = pre2[i] + soft[i] * soft[i]; bin += sp[i]; }
            frac = n == 0 ? 0 : bin / n;
        }
    }

    static boolean USE_SOFT = true;
    static boolean BG_SUB = true;
    static int BG_FRAMES = 120;
    /** fala "suave" (0..1) por 10 ms, calculada junto com o VAD binário */
    static float[] lastSoft;

    // ---------- legenda ----------
    /** falas (início/fim em ms, ordenadas, sem sobreposição) */
    static final class Spans {
        long[] s = new long[0], e = new long[0];
        int n() { return s.length; }
    }

    private static final Pattern SPAN = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})[ \\t]*-->[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})", Pattern.MULTILINE);

    private static long ms(String h, String m, String s, String f) {
        return (((h == null ? 0 : Long.parseLong(h)) * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + Long.parseLong(f);
    }

    static Spans spans(String text) {
        ArrayList<long[]> l = new ArrayList<>();
        Matcher m = SPAN.matcher(text);
        while (m.find() && l.size() < 8000) {
            long a = ms(m.group(1), m.group(2), m.group(3), m.group(4)), b = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            if (b <= a) b = a + 1500;
            if (b - a > 10000) b = a + 10000;
            l.add(new long[]{a, b});
        }
        l.sort((p, q) -> Long.compare(p[0], q[0]));
        ArrayList<long[]> mg = new ArrayList<>();
        for (long[] c : l) {
            if (!mg.isEmpty() && c[0] <= mg.get(mg.size() - 1)[1]) { long[] last = mg.get(mg.size() - 1); last[1] = Math.max(last[1], c[1]); }
            else mg.add(new long[]{c[0], c[1]});
        }
        Spans sp = new Spans();
        sp.s = new long[mg.size()];
        sp.e = new long[mg.size()];
        for (int i = 0; i < mg.size(); i++) { sp.s[i] = mg.get(i)[0]; sp.e[i] = mg.get(i)[1]; }
        return sp;
    }

    /**
     * Início (no tempo do vídeo, supondo deslocamento 0) de até `count` janelas de `lenMs` espalhadas pela duração.
     * Cada uma é escolhida perto do ponto-alvo (10%..85%) no meio de uma cena com bastante fala (cobertura da legenda
     * numa faixa de +-CONTEXT_MS em volta): assim, mesmo com a legenda adiantada/atrasada algumas dezenas de segundos,
     * a janela continua caindo em cima de diálogo. `fractions` = pontos-alvo (0..1).
     */
    static final long CONTEXT_MS = 40000;

    static long[] planWindows(Spans sp, long durationMs, long lenMs, double[] fractions, long[] avoid) {
        long[] out = new long[fractions.length];
        long span = Math.max(lenMs, durationMs / (fractions.length * 2 + 2));
        ArrayList<Long> taken = new ArrayList<>();
        if (avoid != null) for (long a : avoid) taken.add(a);
        for (int i = 0; i < fractions.length; i++) {
            long target = (long) (durationMs * fractions[i]);
            long best = Math.max(0, Math.min(Math.max(0, durationMs - lenMs), target - lenMs / 2));
            double bestCov = -1e9;
            long lo = Math.max(0, target - span), hi = Math.max(lo, Math.min(durationMs - lenMs, target + span));
            for (long t = lo; t <= hi; t += 1000) {
                boolean clash = false;
                for (long u : taken) if (Math.abs(u - t) < lenMs + 2000) { clash = true; break; }
                if (clash) continue;
                double ctx = coverage(sp, t - CONTEXT_MS, t + lenMs + CONTEXT_MS), own = coverage(sp, t, t + lenMs);
                double cov = ctx + 0.5 * own - Math.abs(t + lenMs / 2 - target) / (double) (span * 40);
                if (cov > bestCov) { bestCov = cov; best = t; }
            }
            out[i] = best;
            taken.add(best);
        }
        return out;
    }

    static final double[] BASE_FRACTIONS = {0.10, 0.25, 0.40, 0.55, 0.70, 0.85};
    static final double[] EXTRA_FRACTIONS = {0.175, 0.475, 0.775, 0.325, 0.625, 0.925};

    static long[] planWindows(Spans sp, long durationMs, long lenMs, int count) {
        double[] f = new double[count];
        for (int i = 0; i < count; i++) f[i] = count == 6 ? BASE_FRACTIONS[i] : 0.10 + 0.75 * i / Math.max(1, count - 1);
        return planWindows(sp, durationMs, lenMs, f, null);
    }

    private static double coverage(Spans sp, long a, long b) {
        long sum = 0;
        for (int i = firstEndAfter(sp.e, a); i < sp.n() && sp.s[i] < b; i++) sum += Math.min(b, sp.e[i]) - Math.max(a, sp.s[i]);
        return sum / (double) Math.max(1, b - a);
    }

    private static int firstEndAfter(long[] e, double v) {
        int lo = 0, hi = e.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (e[mid] > v) hi = mid; else lo = mid + 1;
        }
        return lo;
    }

    // ---------- correlação ----------
    /** correlação de Pearson entre a fala detectada na janela e a atividade da legenda transformada (t_video = k * t_legenda + off) */
    static double winScore(Win w, Spans sp, double k, long off) {
        if (w.n < 50) return 0;
        long t0 = w.startMs, t1 = t0 + (long) w.n * FRAME_MS;
        int i = firstEndAfter(sp.e, (t0 - off) / k - 1);
        long sumS = 0;
        double sumAS = 0;
        for (; i < sp.n(); i++) {
            long vs = Math.round(sp.s[i] * k) + off;
            if (vs >= t1) break;
            long ve = Math.round(sp.e[i] * k) + off;
            int a = (int) Math.max(0, Math.min(w.n, Math.floorDiv(vs - t0, FRAME_MS)));
            int b = (int) Math.max(0, Math.min(w.n, Math.floorDiv(ve - t0, FRAME_MS)));
            if (b <= a) continue;
            sumS += b - a;
            sumAS += w.pre[b] - w.pre[a];
        }
        double n = w.n, pa = w.pre[w.n] / n, ps = sumS / n;
        double va = w.pre2[w.n] / n - pa * pa, vs = ps * (1 - ps);
        if (va <= 1e-9 || vs <= 1e-9) return 0;
        return (sumAS / n - pa * ps) / Math.sqrt(va * vs);
    }

    private static double total(ArrayList<Win> ws, Spans sp, double k, long off) {
        double t = 0;
        for (Win w : ws) t += winScore(w, sp, k, off);
        return t;
    }

    static final class Result {
        boolean accepted;
        String why = "";
        SubtitleAlign.Fit fit;
        double score, second, ratio, stouffer;
        int agree, used;
        long[] ownOff;
        double[] ownScore;
        double scale = 1.0;
        long offsetMs;
        boolean pieced;
        long cutAtMs;

        String summary() {
            if (!accepted) return "áudio: desistiu (" + why + ")";
            if (pieced) return "áudio: por partes " + secs(fit.offs[0]) + " / " + secs(fit.offs[1]) + " (" + agree + "/" + used + " janelas)";
            return "áudio: " + secs(offsetMs) + (scale != 1.0 ? ", escala " + String.format(Locale.ROOT, "%.4f", scale) : "") + " (" + agree + "/" + used + " janelas)";
        }
    }

    static String secs(long ms) {
        String v = String.format(Locale.ROOT, "%.1f", Math.abs(ms) / 1000.0).replace('.', ',');
        return (ms > 0 ? "+" : ms < 0 ? "−" : "") + v + " s";
    }

    /** melhor deslocamento (grosso 100 ms, fino 10 ms) para uma escala e um conjunto de janelas; devolve {off, score} */
    private static double[] bestOffset(ArrayList<Win> ws, Spans sp, double k) {
        double best = -1e9;
        long bo = 0;
        for (long o = -RANGE_MS; o <= RANGE_MS; o += COARSE_MS) {
            double s = total(ws, sp, k, o);
            if (s > best) { best = s; bo = o; }
        }
        long c = bo;
        for (long o = c - COARSE_MS; o <= c + COARSE_MS; o += 10) {
            double s = total(ws, sp, k, o);
            if (s > best) { best = s; bo = o; }
        }
        return new double[]{bo, best};
    }

    static Result align(ArrayList<Win> all, Spans sp, StringBuilder log) {
        Result r = new Result();
        ArrayList<Win> ws = new ArrayList<>();
        for (Win w : all) if (w != null && w.n >= 500 && w.frac >= 0.03 && w.frac <= 0.97) ws.add(w);
        ws.sort((p, q) -> Long.compare(p.startMs, q.startMs));
        r.used = ws.size();
        if (sp.n() < 8) { r.why = "legenda com poucas falas"; return r; }
        if (ws.size() < MIN_AGREE) { r.why = "só " + ws.size() + " de " + all.size() + " janelas com fala detectável"; return r; }

        // 1) busca grossa: escala x deslocamento
        int ns = SubtitleAlign.SCALES.length, no = (int) (2 * RANGE_MS / COARSE_MS) + 1;
        double[][] sc = new double[ns][no];
        double best = -1e9;
        int bs = 0, bi = 0;
        for (int si = 0; si < ns; si++) {
            double k = SubtitleAlign.SCALES[si];
            for (int oi = 0; oi < no; oi++) {
                double s = total(ws, sp, k, -RANGE_MS + oi * COARSE_MS);
                sc[si][oi] = s;
                if (s > best) { best = s; bs = si; bi = oi; }
            }
        }
        double kb = SubtitleAlign.SCALES[bs];
        long ob = -RANGE_MS + bi * COARSE_MS;
        double fine = best;
        for (long o = ob - COARSE_MS; o <= ob + COARSE_MS; o += 10) {
            double s = total(ws, sp, kb, o);
            if (s > fine) { fine = s; ob = o; }
        }
        r.scale = kb;
        r.offsetMs = ob;
        r.score = fine;
        // 2) segundo pico: melhor hipótese que não equivale à melhor (em todos os centros de janela difere mais que EXCLUDE_MS)
        double second = 0;
        for (int si = 0; si < ns; si++) {
            double k = SubtitleAlign.SCALES[si];
            for (int oi = 0; oi < no; oi++) {
                if (sc[si][oi] <= second) continue;
                long o = -RANGE_MS + oi * COARSE_MS;
                boolean equiv = true;
                for (Win w : ws) {
                    long tc = w.startMs + (long) w.n * FRAME_MS / 2;
                    double sub = (tc - ob) / kb;
                    if (Math.abs(k * sub + o - tc) > EXCLUDE_MS) { equiv = false; break; }
                }
                if (!equiv) second = sc[si][oi];
            }
        }
        r.second = second;
        r.ratio = second <= 1e-6 ? 99 : fine / second;
        // 3) quantas janelas sustentam o deslocamento: a nota dela nele tem de se destacar da distribuição das notas
        // dela em todos os outros deslocamentos (z >= Z_MIN). O melhor deslocamento de CADA janela sozinha não serve:
        // uma janela de 12 s tem poucas falas e acha "picos" falsos em qualquer lugar de +-300 s.
        r.ownOff = new long[ws.size()];
        r.ownScore = new double[ws.size()];
        double[] zs = new double[ws.size()];
        int agree = 0;
        for (int i = 0; i < ws.size(); i++) {
            Win w = ws.get(i);
            double sum = 0, sum2 = 0, mx = -1e9;
            long mo = 0;
            int cnt = 0;
            for (long o = -RANGE_MS; o <= RANGE_MS; o += COARSE_MS) {
                double s = winScore(w, sp, kb, o);
                sum += s; sum2 += s * s; cnt++;
                if (s > mx) { mx = s; mo = o; }
            }
            double mean = sum / cnt, sd = Math.sqrt(Math.max(1e-9, sum2 / cnt - mean * mean));
            double at = winScore(w, sp, kb, ob);
            zs[i] = (at - mean) / sd;
            r.ownOff[i] = mo;
            r.ownScore[i] = at;
            if (zs[i] >= Z_MIN && at >= MIN_WIN_SCORE) agree++;
        }
        r.agree = agree;
        double zsum = 0;
        for (double z : zs) zsum += z;
        r.stouffer = zsum / Math.sqrt(Math.max(1, zs.length));
        log.append(String.format(Locale.ROOT, "áudio: melhor escala %.4f, deslocamento %d ms, nota %.2f (média %.2f), segundo pico %.2f, razão %.2f, concordam %d/%d\n",
                kb, ob, fine, fine / ws.size(), second, r.ratio, agree, ws.size()));
        for (int i = 0; i < ws.size(); i++)
            log.append(String.format(Locale.ROOT, "  janela %d (%s, fala %.0f%%): nota no global %.2f (z=%.1f), melhor sozinha %+d ms\n", i, clockMs(ws.get(i).startMs), ws.get(i).frac * 100, r.ownScore[i], zs[i], r.ownOff[i]));

        boolean peak = fine / ws.size() >= MIN_MEAN_SCORE && r.ratio >= MIN_RATIO;
        if (peak && agree >= MIN_AGREE) {
            r.accepted = true;
            SubtitleAlign.Fit f = new SubtitleAlign.Fit();
            f.scale = kb;
            f.offsetMs = ob;
            f.score = fine / ws.size();
            r.fit = f;
            return r;
        }
        // 4) corte: dois deslocamentos diferentes, cada um com janelas consecutivas
        Result pr = tryPieces(ws, sp, kb, r, log);
        if (pr != null) return pr;
        if (fine / ws.size() < MIN_MEAN_SCORE) r.why = String.format(Locale.ROOT, "correlação fraca (%.2f por janela)", fine / ws.size());
        else if (r.ratio < MIN_RATIO) r.why = String.format(Locale.ROOT, "pico ambíguo (razão %.2f)", r.ratio);
        else r.why = "só " + agree + " de " + ws.size() + " janelas concordam";
        return r;
    }

    private static String clockMs(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** tenta explicar as janelas com dois deslocamentos (corte no filme): grupo antes do corte e grupo depois */
    private static Result tryPieces(ArrayList<Win> ws, Spans sp, double k, Result base, StringBuilder log) {
        int n = ws.size();
        Result bestR = null;
        double bestScore = -1;
        for (int cut = 2; cut <= n - 2; cut++) {
            ArrayList<Win> a = new ArrayList<>(ws.subList(0, cut)), b = new ArrayList<>(ws.subList(cut, n));
            double[] oa = bestOffset(a, sp, k), ob = bestOffset(b, sp, k);
            if (Math.abs(oa[0] - ob[0]) < 1000) continue;
            // cada metade precisa de pico próprio, com a maioria das janelas dela concordando
            int agA = agreeCount(a, sp, k, (long) oa[0], PIECE_Z), agB = agreeCount(b, sp, k, (long) ob[0], PIECE_Z);
            double mA = oa[1] / a.size(), mB = ob[1] / b.size();
            log.append(String.format(Locale.ROOT, "  corte após janela %d: %+d ms (%d/%d, %.2f) | %+d ms (%d/%d, %.2f)\n", cut - 1, (long) oa[0], agA, a.size(), mA, (long) ob[0], agB, b.size(), mB));
            if (mA < MIN_MEAN_SCORE || mB < MIN_MEAN_SCORE) continue;
            if (agA < Math.max(2, a.size() - 1) || agB < Math.max(2, b.size() - 1)) continue;
            if (agA + agB < MIN_AGREE) continue;
            if (!sideConvincing(a, sp, k, (long) oa[0]) || !sideConvincing(b, sp, k, (long) ob[0])) continue;
            // razão de cada lado contra o outro deslocamento: o grupo A tem de preferir oa a ob e vice-versa
            double aOwn = total(a, sp, k, (long) oa[0]), aOther = total(a, sp, k, (long) ob[0]);
            double bOwn = total(b, sp, k, (long) ob[0]), bOther = total(b, sp, k, (long) oa[0]);
            if (aOwn < 1.3 * Math.max(0.01, aOther) || bOwn < 1.3 * Math.max(0.01, bOther)) continue;
            double sc = (oa[1] + ob[1]);
            if (sc > bestScore) {
                bestScore = sc;
                Result r = new Result();
                r.accepted = true;
                r.pieced = true;
                r.used = n;
                r.agree = agA + agB;
                r.scale = k;
                r.score = sc;
                r.ratio = base.ratio;
                SubtitleAlign.Fit f = new SubtitleAlign.Fit();
                f.scale = k;
                f.offsetMs = (long) oa[0];
                f.score = sc / n;
                // corte no tempo da legenda: no maior intervalo sem fala entre o fim do grupo A e o início do grupo B
                long tA = a.get(a.size() - 1).startMs + (long) a.get(a.size() - 1).n * FRAME_MS;
                long tB = b.get(0).startMs;
                double sA = (tA - oa[0]) / k, sB = (tB - ob[0]) / k;
                long cutSub = bestGap(sp, (long) sA, (long) Math.max(sA + 1, sB));
                f.cuts = new long[]{cutSub};
                f.offs = new long[]{(long) oa[0], (long) ob[0]};
                f.pieceOk = true;
                r.fit = f;
                r.cutAtMs = cutSub;
                r.offsetMs = (long) oa[0];
                bestR = r;
            }
        }
        if (bestR != null) log.append("áudio: corte aceito por partes\n");
        return bestR;
    }

    private static int agreeCount(ArrayList<Win> ws, Spans sp, double k, long off, double zmin) {
        int c = 0;
        for (Win w : ws) {
            double sum = 0, sum2 = 0;
            int cnt = 0;
            for (long o = -RANGE_MS; o <= RANGE_MS; o += COARSE_MS) {
                double s = winScore(w, sp, k, o);
                sum += s; sum2 += s * s; cnt++;
            }
            double mean = sum / cnt, sd = Math.sqrt(Math.max(1e-9, sum2 / cnt - mean * mean));
            double at = winScore(w, sp, k, off);
            if ((at - mean) / sd >= zmin && at >= MIN_WIN_SCORE) c++;
        }
        return c;
    }

    /** um lado do corte: com 3+ janelas, pico claro (razão); com 2, as duas bem destacadas e fortes */
    private static boolean sideConvincing(ArrayList<Win> g, Spans sp, double k, long off) {
        if (g.size() >= 3) return groupRatio(g, sp, k, off) >= PIECE_RATIO;
        for (Win w : g) if (winScore(w, sp, k, off) < 0.3) return false;
        return groupRatio(g, sp, k, off) >= 1.2;
    }

    /** razão entre o pico do grupo (no deslocamento `off`) e o melhor deslocamento fora de +-EXCLUDE_MS dele (mesma escala) */
    private static double groupRatio(ArrayList<Win> g, Spans sp, double k, long off) {
        double peak = total(g, sp, k, off), second = 0;
        for (long o = -RANGE_MS; o <= RANGE_MS; o += COARSE_MS) {
            if (Math.abs(o - off) <= EXCLUDE_MS) continue;
            second = Math.max(second, total(g, sp, k, o));
        }
        return second <= 1e-6 ? 99 : peak / second;
    }

    /** meio do maior intervalo entre falas dentro de [a, b] (tempo da legenda); no meio de [a, b] se não houver */
    private static long bestGap(Spans sp, long a, long b) {
        long bestLen = -1, bestMid = (a + b) / 2;
        long prevEnd = a;
        for (int i = firstEndAfter(sp.e, a); i <= sp.n(); i++) {
            long st = i < sp.n() ? Math.min(b, sp.s[i]) : b;
            if (st > prevEnd && st - prevEnd > bestLen) { bestLen = st - prevEnd; bestMid = (prevEnd + st) / 2; }
            if (i < sp.n()) { prevEnd = Math.max(prevEnd, sp.e[i]); if (sp.s[i] >= b) break; }
        }
        return bestMid;
    }
}
