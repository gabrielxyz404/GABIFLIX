package io.github.gabrielxyz404.gabiflix.audiosync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

/**
 * VAD "cuidadoso" em DSP puro, pensado para achar FALA (inclusive com música/ruído de fundo) a cada 10 ms:
 *  - espectro de 25 ms (Hann, FFT 512 a 16 kHz) em 8 bandas de 100 Hz a 5 kHz, SNR de cada banda contra um piso de ruído adaptativo
 *    (percentil 15 de ±3 s, por banda);
 *  - planicidade espectral, fluxo espectral, centroide, razão grave/agudo, taxa de cruzamentos por zero;
 *  - harmonicidade (pico da autocorrelação normalizada em 70-400 Hz, sobre o sinal a 8 kHz);
 *  - modulação silábica (energia de 2-8 Hz do envelope em ±0,5 s) e contexto temporal (média/desvio/máximo do SNR);
 *  - um classificador pequeno (MLP 22-16-1) treinado com as próprias legendas como rótulo de "tem fala" (ver TrainVad).
 * Sem dependências; as contas são float/double simples para portar 1:1 para Swift.
 */
public final class DspVad implements Vad {
    public static final int NF = 22;
    static final int[][] BANDS_HZ = {{100, 300}, {300, 500}, {500, 800}, {800, 1200}, {1200, 1800}, {1800, 2600}, {2600, 3600}, {3600, 5000}};

    /** MLP: tanh na camada oculta, sigmoide na saída */
    public static final class Model {
        public float[] mean = new float[NF], std = new float[NF];
        public float[][] w1;   // [H][NF]
        public float[] b1, w2;
        public float b2;
        public int hidden;

        public static Model load(File f) throws IOException {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                Model m = new Model();
                String[] hd = br.readLine().trim().split("\\s+");
                int nf = Integer.parseInt(hd[0]);
                m.hidden = Integer.parseInt(hd[1]);
                if (nf != NF) throw new IOException("modelo com " + nf + " atributos, esperado " + NF);
                m.mean = row(br.readLine(), NF);
                m.std = row(br.readLine(), NF);
                m.w1 = new float[m.hidden][];
                for (int h = 0; h < m.hidden; h++) m.w1[h] = row(br.readLine(), NF);
                m.b1 = row(br.readLine(), m.hidden);
                m.w2 = row(br.readLine(), m.hidden);
                m.b2 = Float.parseFloat(br.readLine().trim());
                return m;
            }
        }

        private static float[] row(String s, int n) {
            String[] p = s.trim().split("\\s+");
            float[] r = new float[n];
            for (int i = 0; i < n; i++) r[i] = Float.parseFloat(p[i]);
            return r;
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append(NF).append(' ').append(hidden).append('\n');
            line(sb, mean); line(sb, std);
            for (float[] r : w1) line(sb, r);
            line(sb, b1); line(sb, w2);
            sb.append(b2).append('\n');
            return sb.toString();
        }

        private static void line(StringBuilder sb, float[] v) {
            for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(' '); sb.append(v[i]); }
            sb.append('\n');
        }

        public float predict(float[] x) {
            float out = b2;
            for (int h = 0; h < hidden; h++) {
                float s = b1[h];
                float[] w = w1[h];
                for (int i = 0; i < NF; i++) s += w[i] * ((x[i] - mean[i]) / std[i]);
                out += w2[h] * (float) Math.tanh(s);
            }
            return (float) (1.0 / (1.0 + Math.exp(-out)));
        }
    }

    /** modelo simples escolhido à mão (reserva quando não há modelo treinado) */
    static Model fallback() {
        Model m = new Model();
        m.hidden = 1;
        for (int i = 0; i < NF; i++) { m.mean[i] = 0; m.std[i] = 1; }
        m.w1 = new float[1][NF];
        m.w1[0][8] = 0.12f;    // SNR da banda de fala
        m.w1[0][12] = 1.5f;    // harmonicidade
        m.w1[0][15] = 1.0f;    // modulação silábica
        m.w1[0][10] = -1.0f;   // planicidade
        m.b1 = new float[]{-1.5f};
        m.w2 = new float[]{3f};
        m.b2 = -0.5f;
        return m;
    }

    private final Model model;
    private final String name;

    public DspVad(Model m, String name) { this.model = m; this.name = name; }

    /** "dsp" (modelo de /tmp/dspw/all.w ou o da propriedade dsp.weights) ou "dsp:<arquivo>" */
    public static DspVad forName(String name) {
        try {
            String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : System.getProperty("dsp.weights", "");
            if (!path.isEmpty() && new File(path).exists()) return new DspVad(Model.load(new File(path)), name);
        } catch (IOException e) {
            System.err.println("modelo DSP: " + e);
        }
        return new DspVad(fallback(), name + "(reserva)");
    }

    @Override public String name() { return name; }

    @Override
    public float[] speechProb(short[] pcm, int n, int rate) {
        float[][] f = features(pcm, n, rate);
        float[] p = new float[f.length];
        for (int t = 0; t < f.length; t++) p[t] = model.predict(f[t]);
        return p;
    }

    // ------------------------------------------------------------------ atributos
    /** matriz [quadro][NF] */
    public static float[][] features(short[] pcm, int n, int rate) {
        int hop = rate / 100, win = rate / 40;
        int T = n / hop;
        float[][] out = new float[T][NF];
        if (T < 5) return out;
        int N = 512;
        Fft fft = new Fft(N);
        double[] re = new double[N], im = new double[N];
        double[] hann = new double[win];
        for (int i = 0; i < win; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / win);
        double binHz = rate / (double) N;
        int nb = BANDS_HZ.length;
        double[][] logE = new double[nb][T];
        double[] spLog = new double[T], loLog = new double[T], flat = new double[T], cent = new double[T], lohi = new double[T], zcr = new double[T], harm = new double[T], lowRatio = new double[T];
        double[][] logSpec = new double[2][];
        double[] prevSpec = new double[0];
        double[] flux = new double[T];
        int kLo = (int) Math.round(300 / binHz), kHi = (int) Math.round(3400 / binHz), kFlat0 = (int) Math.round(200 / binHz), kFlat1 = (int) Math.round(4000 / binHz);
        int k1k = (int) Math.round(1000 / binHz), k100 = (int) Math.round(100 / binHz);
        // 8 kHz para a harmonicidade (média de pares: filtro passa-baixa simples)
        int dec = rate / 8000;
        int win8 = win / dec;
        double[] re8 = new double[N], im8 = new double[N], hann8 = new double[win8];
        for (int i = 0; i < win8; i++) hann8[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / win8);
        double[] cur = new double[N / 2 + 1];
        double[] prev = new double[N / 2 + 1];
        for (int t = 0; t < T; t++) {
            int c = t * hop + hop / 2;
            int s0 = c - win / 2;
            Arrays.fill(re, 0); Arrays.fill(im, 0);
            int zc = 0;
            double last = 0;
            for (int i = 0; i < win; i++) {
                int idx = s0 + i;
                double v = (idx >= 0 && idx < n) ? pcm[idx] / 32768.0 : 0.0;
                if (i > 0 && ((v >= 0) != (last >= 0))) zc++;
                last = v;
                re[i] = v * hann[i];
            }
            zcr[t] = zc / (double) win;
            fft.transform(re, im, false);
            for (int k = 0; k <= N / 2; k++) cur[k] = re[k] * re[k] + im[k] * im[k];
            for (int b = 0; b < nb; b++) {
                int a = Math.max(1, (int) Math.round(BANDS_HZ[b][0] / binHz)), z = Math.min(N / 2, (int) Math.round(BANDS_HZ[b][1] / binHz));
                double e = 0;
                for (int k = a; k < z; k++) e += cur[k];
                logE[b][t] = 10 * Math.log10(e + 1e-12);
            }
            double esp = 0, elo = 0, ehi = 0, cs = 0;
            for (int k = kLo; k < kHi; k++) { esp += cur[k]; cs += cur[k] * (k * binHz); }
            for (int k = k100; k < kLo; k++) elo += cur[k];
            double e1 = 0, e2 = 0;
            for (int k = kLo; k < k1k; k++) e1 += cur[k];
            for (int k = k1k; k < kHi; k++) e2 += cur[k];
            spLog[t] = 10 * Math.log10(esp + 1e-12);
            loLog[t] = 10 * Math.log10(elo + 1e-12);
            cent[t] = Math.log(cs / (esp + 1e-12) + 1.0);
            lohi[t] = 10 * Math.log10((e1 + 1e-12) / (e2 + 1e-12));
            lowRatio[t] = loLog[t] - spLog[t];
            // planicidade (média geométrica / aritmética) em 200-4000 Hz
            double lg = 0, ar = 0;
            int cnt = 0;
            for (int k = kFlat0; k < kFlat1; k++) { lg += Math.log(cur[k] + 1e-12); ar += cur[k]; cnt++; }
            flat[t] = Math.exp(lg / cnt) / (ar / cnt + 1e-12);
            // fluxo: diferença do espectro logarítmico normalizado
            double fl = 0;
            if (t > 0) {
                double ns = 0, np = 0;
                for (int k = kLo; k < kHi; k++) { ns += cur[k]; np += prev[k]; }
                for (int k = kLo; k < kHi; k++) {
                    double d = cur[k] / (ns + 1e-12) - prev[k] / (np + 1e-12);
                    fl += d * d;
                }
            }
            flux[t] = Math.sqrt(fl) * 10;
            System.arraycopy(cur, 0, prev, 0, cur.length);
            // harmonicidade a 8 kHz
            Arrays.fill(re8, 0); Arrays.fill(im8, 0);
            double m8 = 0;
            for (int i = 0; i < win8; i++) {
                double v = 0;
                for (int j = 0; j < dec; j++) {
                    int idx = s0 + i * dec + j;
                    v += (idx >= 0 && idx < n) ? pcm[idx] / 32768.0 : 0.0;
                }
                v /= dec;
                re8[i] = v * hann8[i];
            }
            fft.transform(re8, im8, false);
            for (int k = 0; k < N; k++) { re8[k] = re8[k] * re8[k] + im8[k] * im8[k]; im8[k] = 0; }
            fft.transform(re8, im8, true);
            double r0 = re8[0] + 1e-12;
            double best = 0;
            for (int lag = 20; lag <= 114; lag++) {
                double r = re8[lag] / r0;
                if (r > best) best = r;
            }
            harm[t] = best;
        }
        // piso de ruído por banda (percentil 15 em ±3 s, passo 0,5 s, interpolado)
        double[][] floor = new double[nb + 1][T];
        double[] spSnrBase = spLog;
        for (int b = 0; b <= nb; b++) {
            double[] src = b < nb ? logE[b] : spLog;
            floor[b] = percentileFloor(src, 300, 50, 0.15);
        }
        double[][] snr = new double[nb + 1][T];
        for (int b = 0; b <= nb; b++) {
            double[] src = b < nb ? logE[b] : spLog;
            for (int t = 0; t < T; t++) snr[b][t] = Math.max(-10, Math.min(60, src[t] - floor[b][t]));
        }
        double p95 = percentile(spLog, 0.95);
        // contexto temporal
        double[] snrSp = snr[nb];
        double[] mean25 = boxMean(snrSp, 12), mean50 = boxMean(snrSp, 50);
        double[] harm25 = boxMean(harm, 12);
        double[] sq = new double[T];
        for (int t = 0; t < T; t++) sq[t] = snrSp[t] * snrSp[t];
        double[] mean50sq = boxMean(sq, 50);
        double[] max25 = boxMax(snrSp, 12);
        double[] mod = modulation(snrSp, 50);
        for (int t = 0; t < T; t++) {
            float[] o = out[t];
            for (int b = 0; b < nb; b++) o[b] = (float) snr[b][t];
            o[8] = (float) snrSp[t];
            o[9] = (float) (spLog[t] - p95);
            o[10] = (float) flat[t];
            o[11] = (float) flux[t];
            o[12] = (float) harm[t];
            o[13] = (float) cent[t];
            o[14] = (float) lohi[t];
            o[15] = (float) mod[t];
            o[16] = (float) mean25[t];
            o[17] = (float) Math.sqrt(Math.max(0, mean50sq[t] - mean50[t] * mean50[t]));
            o[18] = (float) harm25[t];
            o[19] = (float) max25[t];
            o[20] = (float) zcr[t];
            o[21] = (float) lowRatio[t];
        }
        return out;
    }

    /** piso adaptativo: percentil p dos valores em ±halfWin quadros, calculado a cada step quadros e interpolado */
    static double[] percentileFloor(double[] v, int halfWin, int step, double p) {
        int T = v.length;
        int m = (T + step - 1) / step + 1;
        double[] val = new double[m];
        double[] buf = new double[2 * halfWin + 1];
        for (int i = 0; i < m; i++) {
            int c = Math.min(T - 1, i * step);
            int a = Math.max(0, c - halfWin), z = Math.min(T - 1, c + halfWin);
            int len = z - a + 1;
            System.arraycopy(v, a, buf, 0, len);
            Arrays.sort(buf, 0, len);
            val[i] = buf[(int) Math.floor(p * (len - 1))];
        }
        double[] o = new double[T];
        for (int t = 0; t < T; t++) {
            double q = t / (double) step;
            int i = (int) Math.floor(q);
            double f = q - i;
            double a = val[Math.min(i, m - 1)], b = val[Math.min(i + 1, m - 1)];
            o[t] = a * (1 - f) + b * f;
        }
        return o;
    }

    static double percentile(double[] v, double p) {
        double[] c = v.clone();
        Arrays.sort(c);
        return c[(int) Math.floor(p * (c.length - 1))];
    }

    static double[] boxMean(double[] v, int r) {
        int T = v.length;
        double[] ps = new double[T + 1];
        for (int i = 0; i < T; i++) ps[i + 1] = ps[i] + v[i];
        double[] o = new double[T];
        for (int t = 0; t < T; t++) {
            int a = Math.max(0, t - r), z = Math.min(T - 1, t + r);
            o[t] = (ps[z + 1] - ps[a]) / (z - a + 1);
        }
        return o;
    }

    static double[] boxMax(double[] v, int r) {
        int T = v.length;
        double[] o = new double[T];
        for (int t = 0; t < T; t++) {
            int a = Math.max(0, t - r), z = Math.min(T - 1, t + r);
            double m = -1e9;
            for (int i = a; i <= z; i++) if (v[i] > m) m = v[i];
            o[t] = m;
        }
        return o;
    }

    /** energia de modulação em 2-8 Hz do envelope (quadros de 10 ms) relativa à de 0,5-20 Hz, janela de ±r quadros */
    static double[] modulation(double[] env, int r) {
        int T = env.length;
        int W = 2 * r + 1;
        double[] mod = new double[T];
        double[] fr = {2, 3, 4, 5, 6, 8, 0.7, 1.2, 10, 14, 18};
        double[][] cs = new double[fr.length][W], sn = new double[fr.length][W];
        for (int k = 0; k < fr.length; k++) for (int i = 0; i < W; i++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / W);
            double ang = 2 * Math.PI * fr[k] * (i * 0.01);
            cs[k][i] = Math.cos(ang) * w; sn[k][i] = Math.sin(ang) * w;
        }
        double[] seg = new double[W];
        for (int t = 0; t < T; t++) {
            double mean = 0;
            for (int i = 0; i < W; i++) {
                int idx = t - r + i;
                idx = idx < 0 ? 0 : (idx >= T ? T - 1 : idx);
                seg[i] = env[idx];
                mean += seg[i];
            }
            mean /= W;
            double[] pw = new double[fr.length];
            for (int k = 0; k < fr.length; k++) {
                double a = 0, b = 0;
                for (int i = 0; i < W; i++) { double v = seg[i] - mean; a += v * cs[k][i]; b += v * sn[k][i]; }
                pw[k] = a * a + b * b;
            }
            double sp = pw[0] + pw[1] + pw[2] + pw[3] + pw[4] + pw[5];
            double all = sp + pw[6] + pw[7] + pw[8] + pw[9] + pw[10] + 1e-9;
            mod[t] = sp / all;
        }
        return mod;
    }
}
