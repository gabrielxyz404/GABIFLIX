package io.github.gabrielxyz404.gabiflix.audiosync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Alinha as falas de uma legenda ao áudio: correlação cruzada normalizada (FFT) entre a probabilidade de fala do VAD
 * (janelas capturadas em instantes conhecidos do vídeo) e a máscara de falas da legenda.
 *
 * Modelo: tempoCorrigido(ms) = scale * tempoLegenda + offsetMs.
 * Defasagem ("lag") entre as duas linhas do tempo: L(u) = u - s = alpha*u + beta, com alpha = 1 - 1/scale, beta = offset/scale.
 *
 * Etapas:
 *  1. Grossa (40 ms): cada janela é cortada em blocos de 30 s; cada bloco é correlacionado (FFT) com a máscara em ±1280 s.
 *  2. Hipóteses: retas L(u) = alpha*u + beta geradas por pares de picos de blocos de janelas diferentes e por escalas conhecidas (fps).
 *     Pontuação da reta = soma, nos blocos, do melhor valor de correlação perto da defasagem prevista.
 *  3. Fina (10 ms): em cada janela, correlação exata com a máscara deformada pela hipótese; ajuste por mínimos quadrados
 *     das defasagens locais (escala + deslocamento), com "encaixe" em escalas conhecidas.
 *  4. Confiança: z do melhor ajuste contra retas aleatórias, margem sobre o 2º melhor, concordância dos blocos, NCC fina, F1 de cobertura.
 *  5. Cortes (ajuste por partes): blocos que não concordam e concordam entre si numa 2ª reta de mesma escala.
 * Tudo em Java puro, sem coleções sofisticadas: pensado para portar 1:1 para Swift.
 */
public final class AudioAligner {
    private AudioAligner() {}

    public static final class Params {
        public int frameMs = 40;
        public int blockMs = 30_000;
        /** metade da faixa de defasagem pesquisada (quadros de frameMs): 32000 * 40 ms = ±1280 s */
        public int lagFrames = 32_000;
        public double maxOffsetMs = 600_000;
        public double maxAlpha = 0.125;
        public int topK = 12;
        public int tolFrames = 9;
        public boolean trimCues = true;
        public double smoothMs = 60;
        /** escalas "de fps" conhecidas (tempoCorrigido = scale * tempoLegenda) */
        public double[] knownScales = {1.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0,
                30.0 / 29.97, 29.97 / 30.0, 25.0 / 29.97, 29.97 / 25.0, 30.0 / 25.0, 25.0 / 30.0, 30.0 / 24.0, 24.0 / 30.0};
        public int randomHyps = 4000;
        public long seed = 12345;
        // decisão
        public double minZ = 6.0;
        public double minAgreeFrac = 0.55;
        public double minNcc = 0.10;
        public double minMargin = 0.15;
        public double minF1 = 0.0;
        public double snapTol = 2.5e-4;
        /** mínimo de blocos com fala para tentar */
        public int minBlocks = 3;
        public double minSpeechFrac = 0.03;
        public long maxCueGapForCut = 20_000;
    }

    // ------------------------------------------------------------------ estruturas
    static final class Block {
        int win;
        long uMs;          // início do bloco (múltiplo de frameMs)
        long centerMs;
        int n;
        float[] x;         // quadros grossos (zero-média)
        double norm;
        double speechFrac;
        boolean valid;
        float[] c;         // NCC por defasagem (índice = lagFrames + lag)
        int[] peakIdx;
        float[] peakVal;
    }

    static final class Hyp {
        double alpha, beta, score;
    }

    // ------------------------------------------------------------------ máscara
    /** cobertura de falas a 10 ms: fração do quadro coberta por alguma fala (0..1) */
    static float[] fineMask(Cue[] cues, boolean trim) {
        long end = 0;
        for (Cue c : cues) if (c.speech) end = Math.max(end, c.speechEnd(trim));
        int n = (int) (end / 10) + 400;
        float[] m = new float[n];
        for (Cue c : cues) {
            if (!c.speech) continue;
            long a = c.startMs, b = c.speechEnd(trim);
            if (b <= a) continue;
            if (b <= 0) continue;
            if (a < 0) a = 0;
            int i0 = (int) (a / 10), i1 = (int) (b / 10);
            for (int i = i0; i <= i1 && i < n; i++) {
                double lo = Math.max(a, i * 10L), hi = Math.min(b, (i + 1) * 10L);
                if (hi > lo) m[i] = Math.min(1f, m[i] + (float) ((hi - lo) / 10.0));
            }
        }
        return m;
    }

    static float[] coarseMask(float[] fine, int frameMs) {
        int k = frameMs / 10;
        int n = fine.length / k;
        float[] m = new float[n];
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int j = 0; j < k; j++) s += fine[i * k + j];
            m[i] = s / k;
        }
        return m;
    }

    /** cobertura da máscara fina no instante s (ms da legenda), interpolada */
    static float cov(float[] fine, double sMs) {
        double q = sMs / 10.0 - 0.5;
        int i = (int) Math.floor(q);
        double f = q - i;
        float a = (i >= 0 && i < fine.length) ? fine[i] : 0f;
        float b = (i + 1 >= 0 && i + 1 < fine.length) ? fine[i + 1] : 0f;
        return (float) (a * (1 - f) + b * f);
    }

    static float[] smooth(float[] p, double sigmaMs) {
        double sg = sigmaMs / 10.0;
        if (sg < 0.5) return p;
        int r = (int) Math.ceil(3 * sg);
        double[] k = new double[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) { k[i + r] = Math.exp(-0.5 * i * i / (sg * sg)); sum += k[i + r]; }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] o = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            double s = 0;
            for (int j = -r; j <= r; j++) {
                int t = i + j;
                if (t < 0) t = 0; else if (t >= p.length) t = p.length - 1;
                s += p[t] * k[j + r];
            }
            o[i] = (float) s;
        }
        return o;
    }

    // ------------------------------------------------------------------ etapa grossa
    private static Block[] makeBlocks(Window[] ws, Params P) {
        ArrayList<Block> out = new ArrayList<>();
        int k = P.frameMs / 10;
        int n = P.blockMs / P.frameMs;
        for (int w = 0; w < ws.length; w++) {
            float[] p = smooth(ws[w].p, P.smoothMs);
            // alinha o início à grade de frameMs: descarta o pedacinho inicial
            long off = (P.frameMs - (ws[w].startMs % P.frameMs)) % P.frameMs;
            int skip = (int) (off / 10);
            int frames = (p.length - skip) / k;
            int nb = Math.max(1, frames / n);
            if (frames < n * 2 / 3) continue;
            int per = Math.min(n, frames);
            if (frames >= n) {
                // divide em blocos de n quadros (o resto se junta ao último se for ≥ 2/3)
                nb = frames / n;
                int rest = frames - nb * n;
                if (rest >= n * 2 / 3) nb++;
            } else nb = 1;
            for (int b = 0; b < nb; b++) {
                int f0 = b * n;
                int len = Math.min(n, frames - f0);
                Block bl = new Block();
                bl.win = w;
                bl.n = len;
                bl.uMs = ((ws[w].startMs + P.frameMs - 1) / P.frameMs) * P.frameMs + (long) f0 * P.frameMs;
                bl.centerMs = bl.uMs + (long) len * P.frameMs / 2;
                bl.x = new float[len];
                double mean = 0;
                int sp = 0;
                for (int i = 0; i < len; i++) {
                    float s = 0;
                    for (int j = 0; j < k; j++) s += p[skip + (f0 + i) * k + j];
                    s /= k;
                    bl.x[i] = s;
                    mean += s;
                    if (s > 0.5f) sp++;
                }
                mean /= len;
                double nn = 0;
                for (int i = 0; i < len; i++) { bl.x[i] -= (float) mean; nn += (double) bl.x[i] * bl.x[i]; }
                bl.norm = Math.sqrt(nn);
                bl.speechFrac = sp / (double) len;
                bl.valid = bl.norm > 1e-3 && bl.speechFrac >= P.minSpeechFrac && bl.speechFrac <= 0.97;
                out.add(bl);
            }
        }
        return out.toArray(new Block[0]);
    }

    private static void correlate(Block bl, float[] m, Params P, Fft fft, double[] xr, double[] xi, double[] yr, double[] yi) {
        int Q = P.lagFrames, n = bl.n, N = fft.size();
        int ylen = 2 * Q + n;
        long g = bl.uMs / P.frameMs;
        Arrays.fill(xr, 0); Arrays.fill(xi, 0); Arrays.fill(yr, 0); Arrays.fill(yi, 0);
        for (int i = 0; i < n; i++) xr[i] = bl.x[i];
        double[] p1 = new double[ylen + 1], p2 = new double[ylen + 1];
        for (int j = 0; j < ylen; j++) {
            long idx = g - Q + j;
            double v = (idx >= 0 && idx < m.length) ? m[(int) idx] : 0.0;
            yr[j] = v;
            p1[j + 1] = p1[j] + v;
            p2[j + 1] = p2[j] + v * v;
        }
        fft.transform(xr, xi, false);
        fft.transform(yr, yi, false);
        // conj(X) * Y
        for (int i = 0; i < N; i++) {
            double a = xr[i], b = -xi[i], c = yr[i], d = yi[i];
            yr[i] = a * c - b * d;
            yi[i] = a * d + b * c;
        }
        fft.transform(yr, yi, true);
        float[] c = new float[2 * Q + 1];
        double floorVar = 0.03 * n;
        for (int d = 0; d <= 2 * Q; d++) {
            double s1 = p1[d + n] - p1[d], s2 = p2[d + n] - p2[d];
            double var = s2 - s1 * s1 / n;
            if (var < floorVar) var = floorVar;
            double num = yr[d] / N;
            double v = num / (bl.norm * Math.sqrt(var));
            c[2 * Q - d] = (float) v; // lag = Q - d  -> índice = lag + Q = 2Q - d
        }
        bl.c = c;
        // picos locais
        int K = P.topK, sep = 8;
        int[] pi = new int[K];
        float[] pv = new float[K];
        int cnt = 0;
        for (int i = sep; i < c.length - sep; i++) {
            float v = c[i];
            if (v < 0.05f) continue;
            if (cnt == K && v <= pv[K - 1]) continue;
            boolean top = true;
            for (int j = i - sep; j <= i + sep; j++) if (c[j] > v) { top = false; break; }
            if (!top) continue;
            // inserção ordenada
            int pos = cnt < K ? cnt : K - 1;
            while (pos > 0 && pv[pos - 1] < v) { if (pos < K) { pv[pos] = pv[pos - 1]; pi[pos] = pi[pos - 1]; } pos--; }
            pv[pos] = v; pi[pos] = i;
            if (cnt < K) cnt++;
        }
        bl.peakIdx = Arrays.copyOf(pi, cnt);
        bl.peakVal = Arrays.copyOf(pv, cnt);
    }

    // ------------------------------------------------------------------ hipóteses
    /** melhor valor de correlação do bloco perto da defasagem prevista (ms) */
    private static float support(Block b, double lagMs, Params P) {
        double li = lagMs / P.frameMs;
        int c = (int) Math.round(li) + P.lagFrames;
        int lo = c - P.tolFrames, hi = c + P.tolFrames;
        if (hi < 0 || lo >= b.c.length) return 0f;
        if (lo < 0) lo = 0;
        if (hi >= b.c.length) hi = b.c.length - 1;
        float best = 0f;
        for (int i = lo; i <= hi; i++) if (b.c[i] > best) best = b.c[i];
        return best;
    }

    private static double score(Block[] bl, int[] use, int nu, double alpha, double beta, Params P) {
        double s = 0;
        for (int t = 0; t < nu; t++) {
            Block b = bl[use[t]];
            s += support(b, alpha * b.centerMs + beta, P);
        }
        return s;
    }

    static double alphaOf(double scale) { return 1.0 - 1.0 / scale; }

    // ------------------------------------------------------------------ exato (10 ms)
    static final class Fine {
        double bestDelta;      // ms (já com interpolação)
        double ncc;            // NCC no melhor deslocamento
        double ncc0;           // NCC em deslocamento 0
        double nccSecond;      // melhor NCC a mais de 500 ms do melhor
        double speechFrac;
    }

    /** NCC fina de uma janela com o modelo (a, b), pesquisando deslocamentos de ±R*10 ms */
    private static Fine fineWindow(float[] pSmooth, long startMs, float[] fine, double a, double b, int R) {
        int n = pSmooth.length;
        double mean = 0;
        for (int i = 0; i < n; i++) mean += pSmooth[i];
        mean /= n;
        float[] x = new float[n];
        double nn = 0;
        int sp = 0;
        for (int i = 0; i < n; i++) { x[i] = (float) (pSmooth[i] - mean); nn += (double) x[i] * x[i]; if (pSmooth[i] > 0.5f) sp++; }
        double xnorm = Math.sqrt(nn);
        Fine out = new Fine();
        out.speechFrac = sp / (double) n;
        if (xnorm < 1e-6) return out;
        int L = n + 2 * R;
        float[] M = new float[L];
        double[] p1 = new double[L + 1], p2 = new double[L + 1];
        for (int j = 0; j < L; j++) {
            double u = startMs + 10.0 * (j - R) + 5.0;
            float v = cov(fine, (u - b) / a);
            M[j] = v;
            p1[j + 1] = p1[j] + v;
            p2[j + 1] = p2[j] + (double) v * v;
        }
        double[] ncc = new double[2 * R + 1];
        double floorVar = 0.03 * n;
        for (int D = -R; D <= R; D++) {
            // M[j - D] com índice deslocado de R
            int o = R - D;
            double num = 0;
            for (int j = 0; j < n; j++) num += x[j] * M[j + o];
            double s1 = p1[o + n] - p1[o], s2 = p2[o + n] - p2[o];
            double var = s2 - s1 * s1 / n;
            if (var < floorVar) var = floorVar;
            ncc[D + R] = num / (xnorm * Math.sqrt(var));
        }
        int bi = 0;
        for (int i = 1; i < ncc.length; i++) if (ncc[i] > ncc[bi]) bi = i;
        double frac = 0;
        if (bi > 0 && bi < ncc.length - 1) {
            double y0 = ncc[bi - 1], y1 = ncc[bi], y2 = ncc[bi + 1];
            double den = y0 - 2 * y1 + y2;
            if (den < -1e-12) frac = 0.5 * (y0 - y2) / den;
            if (frac > 1) frac = 1; else if (frac < -1) frac = -1;
        }
        out.bestDelta = ((bi - R) + frac) * 10.0;
        out.ncc = ncc[bi];
        out.ncc0 = ncc[R];
        double sec = -1;
        for (int i = 0; i < ncc.length; i++) if (Math.abs(i - bi) > 50 && ncc[i] > sec) sec = ncc[i];
        out.nccSecond = sec;
        return out;
    }

    // ------------------------------------------------------------------ solução
    public static Result solve(Cue[] cues, Window[] windows, long mediaDurationMs, Params P) {
        Result R = new Result();
        long audio = 0;
        for (Window w : windows) audio += w.durationMs();
        R.audioMs = audio;
        R.windows = windows.length;
        boolean any = false;
        for (Cue c : cues) if (c.speech) { any = true; break; }
        if (!any || windows.length == 0) { R.reason = "sem falas ou sem áudio"; return R; }

        float[] fine = fineMask(cues, P.trimCues);
        float[] coarse = coarseMask(fine, P.frameMs);
        Block[] bl = makeBlocks(windows, P);
        int N = Fft.nextPow2(2 * P.lagFrames + P.blockMs / P.frameMs);
        Fft fft = new Fft(N);
        double[] xr = new double[N], xi = new double[N], yr = new double[N], yi = new double[N];
        int[] use = new int[bl.length];
        int nu = 0;
        for (int i = 0; i < bl.length; i++) {
            if (bl[i].valid) {
                correlate(bl[i], coarse, P, fft, xr, xi, yr, yi);
                use[nu++] = i;
            }
        }
        R.blocksValid = nu;
        if (nu < P.minBlocks) { R.reason = "pouca fala no áudio capturado (" + nu + " blocos)"; return R; }

        // ---- hipóteses
        ArrayList<double[]> hyps = new ArrayList<>();
        for (int a = 0; a < nu; a++) {
            Block A = bl[use[a]];
            for (int s = 0; s < P.knownScales.length; s++) {
                double al = alphaOf(P.knownScales[s]);
                if (Math.abs(al) > P.maxAlpha) continue;
                for (int pk = 0; pk < A.peakIdx.length; pk++) {
                    double L = (A.peakIdx[pk] - P.lagFrames) * (double) P.frameMs;
                    double beta = L - al * A.centerMs;
                    if (Math.abs(beta) <= P.maxOffsetMs * 1.2) hyps.add(new double[]{al, beta});
                }
            }
            for (int b2 = a + 1; b2 < nu; b2++) {
                Block B = bl[use[b2]];
                if (B.win == A.win) continue;
                double du = B.centerMs - A.centerMs;
                if (Math.abs(du) < 1000) continue;
                for (int pa = 0; pa < A.peakIdx.length; pa++) {
                    double La = (A.peakIdx[pa] - P.lagFrames) * (double) P.frameMs;
                    for (int pb = 0; pb < B.peakIdx.length; pb++) {
                        double Lb = (B.peakIdx[pb] - P.lagFrames) * (double) P.frameMs;
                        double al = (Lb - La) / du;
                        if (Math.abs(al) > P.maxAlpha) continue;
                        double beta = La - al * A.centerMs;
                        if (Math.abs(beta) > P.maxOffsetMs * 1.2) continue;
                        hyps.add(new double[]{al, beta});
                    }
                }
            }
        }
        if (hyps.isEmpty()) { R.reason = "nenhum pico de correlação"; return R; }
        int H = hyps.size();
        double[] hs = new double[H];
        int bestH = 0;
        for (int h = 0; h < H; h++) {
            double[] hh = hyps.get(h);
            hs[h] = score(bl, use, nu, hh[0], hh[1], P);
            if (hs[h] > hs[bestH]) bestH = h;
        }
        double[] h1 = hyps.get(bestH);
        double best = hs[bestH];
        // 2º melhor "diferente": difere em ≥ 60% dos blocos por mais de 1 s
        double second = 0;
        for (int h = 0; h < H; h++) {
            if (hs[h] <= second) continue;
            double[] hh = hyps.get(h);
            int diff = 0;
            for (int t = 0; t < nu; t++) {
                Block b = bl[use[t]];
                double d = (hh[0] - h1[0]) * b.centerMs + (hh[1] - h1[1]);
                if (Math.abs(d) > 1000) diff++;
            }
            if (diff >= 0.6 * nu) second = hs[h];
        }
        // nulo: retas aleatórias
        Random rnd = new Random(P.seed);
        double sum = 0, sum2 = 0;
        int nr = P.randomHyps;
        for (int r = 0; r < nr; r++) {
            double al = (rnd.nextDouble() * 2 - 1) * Math.min(P.maxAlpha, 0.1);
            if (rnd.nextInt(3) == 0) al = 0;
            double beta = (rnd.nextDouble() * 2 - 1) * P.maxOffsetMs;
            double s = score(bl, use, nu, al, beta, P);
            sum += s; sum2 += s * s;
        }
        double mu = sum / nr, sd = Math.sqrt(Math.max(1e-9, sum2 / nr - mu * mu));
        R.z = (best - mu) / sd;
        R.margin = best > 0 ? (best - second) / best : 0;
        R.support = best / nu;

        // concordância dos blocos com a melhor reta
        int agree = 0;
        for (int t = 0; t < nu; t++) {
            Block b = bl[use[t]];
            if (support(b, h1[0] * b.centerMs + h1[1], P) >= P.minNcc) agree++;
        }
        R.blocksAgree = agree;

        // ---- refino: mínimos quadrados sobre picos de blocos (dentro da tolerância) para (alpha, beta)
        double alpha = h1[0], beta = h1[1];
        for (int it = 0; it < 2; it++) {
            double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int t = 0; t < nu; t++) {
                Block b = bl[use[t]];
                double pred = alpha * b.centerMs + beta;
                int c = (int) Math.round(pred / P.frameMs) + P.lagFrames;
                int lo = Math.max(0, c - P.tolFrames), hi = Math.min(b.c.length - 1, c + P.tolFrames);
                if (hi < lo) continue;
                int bi = lo;
                for (int i = lo; i <= hi; i++) if (b.c[i] > b.c[bi]) bi = i;
                if (b.c[bi] < P.minNcc) continue;
                double w = b.c[bi];
                double xx = b.centerMs, yy = (bi - P.lagFrames) * (double) P.frameMs;
                sw += w; sx += w * xx; sy += w * yy; sxx += w * xx * xx; sxy += w * xx * yy;
            }
            if (sw <= 0) break;
            double den = sw * sxx - sx * sx;
            if (den > 1e6 * sw) {
                double a2 = (sw * sxy - sx * sy) / den;
                if (Math.abs(a2 - alpha) < 0.02) { alpha = a2; beta = (sy - alpha * sx) / sw; }
            } else {
                beta = sy / sw - alpha * sx / sw;
            }
        }
        // modelo (scale, offset): scale = 1/(1-alpha), offset = beta * scale
        double a0 = 1.0 / (1.0 - alpha), b0 = beta * a0;

        // ---- etapa fina: por janela, refina deslocamento e escala
        float[][] ps = new float[windows.length][];
        for (int w = 0; w < windows.length; w++) ps[w] = smooth(windows[w].p, P.smoothMs);
        double[] model = fineRefine(windows, ps, fine, a0, b0, P, R);
        a0 = model[0]; b0 = model[1];
        R.scale = a0;
        R.offsetMs = b0;
        finalEvidence(windows, ps, fine, R, P);
        decide(R, P);
        analyzeCuts(R, cues, windows, ps, bl, use, nu, alpha, P, mediaDurationMs, fine);
        return R;
    }

    /** refina (scale, offset) com a NCC fina por janela; encaixa a escala em valores conhecidos quando serve */
    private static double[] fineRefine(Window[] ws, float[][] ps, float[] fine, double a, double b, Params P, Result R) {
        double bestA = a, bestB = b;
        double[] cand = new double[]{a};
        for (int it = 0; it < 3; it++) {
            // deslocamentos locais por janela com o modelo atual
            int W = ws.length;
            double[] sS = new double[W], uU = new double[W], wt = new double[W];
            int used = 0;
            for (int w = 0; w < W; w++) {
                Fine f = fineWindow(ps[w], ws[w].startMs, fine, bestA, bestB, 150);
                double bw = bestB + f.bestDelta;
                double uc = ws[w].startMs + ws[w].p.length * 5.0;
                sS[w] = (uc - bw) / bestA;
                uU[w] = uc;
                wt[w] = Math.max(0.0, f.ncc) * (f.speechFrac > 0.03 ? 1 : 0);
                if (f.ncc >= P.minNcc * 0.8 && Math.abs(f.bestDelta) < 1450) used++;
                else wt[w] = 0;
            }
            if (used == 0) break;
            // ajuste u = a' s + b' ponderado
            double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int w = 0; w < W; w++) {
                if (wt[w] <= 0) continue;
                sw += wt[w]; sx += wt[w] * sS[w]; sy += wt[w] * uU[w]; sxx += wt[w] * sS[w] * sS[w]; sxy += wt[w] * sS[w] * uU[w];
            }
            double den = sw * sxx - sx * sx;
            double na = bestA, nb;
            double spread = den / (sw * sw); // variância ponderada de s
            if (used >= 2 && spread > 600.0 * 600.0) {
                na = (sw * sxy - sx * sy) / den;
                if (Math.abs(na - bestA) > 0.01) na = bestA;
                nb = (sy - na * sx) / sw;
            } else {
                nb = (sy - bestA * sx) / sw;
            }
            // encaixe: se a escala está perto de uma conhecida, usa a conhecida (reajustando o deslocamento)
            double snap = na;
            double sa = alphaOf(na);
            for (int s = 0; s < P.knownScales.length; s++) {
                if (Math.abs(alphaOf(P.knownScales[s]) - sa) < P.snapTol) { snap = P.knownScales[s]; break; }
            }
            if (snap != na) {
                nb = (sy - snap * sx) / sw;
                na = snap;
            }
            bestA = na; bestB = nb;
        }
        return new double[]{bestA, bestB};
    }

    private static void finalEvidence(Window[] ws, float[][] ps, float[] fine, Result R, Params P) {
        double sum = 0, min = 9;
        int cnt = 0;
        double tp = 0, fp = 0, fn = 0;
        for (int w = 0; w < ws.length; w++) {
            Fine f = fineWindow(ps[w], ws[w].startMs, fine, R.scale, R.offsetMs, 40);
            if (f.speechFrac < P.minSpeechFrac) continue;
            sum += f.ncc0; cnt++;
            if (f.ncc0 < min) min = f.ncc0;
            for (int j = 0; j < ps[w].length; j++) {
                double u = ws[w].startMs + 10.0 * j + 5.0;
                boolean m = cov(fine, (u - R.offsetMs) / R.scale) > 0.5f;
                boolean s = ps[w][j] > 0.5f;
                if (m && s) tp++; else if (m) fp++; else if (s) fn++;
            }
        }
        R.nccMean = cnt > 0 ? sum / cnt : 0;
        R.nccMin = cnt > 0 ? min : 0;
        double pr = tp / Math.max(1, tp + fp), rc = tp / Math.max(1, tp + fn);
        R.f1 = (pr + rc) > 0 ? 2 * pr * rc / (pr + rc) : 0;
    }

    private static void decide(Result R, Params P) {
        double agreeFrac = R.blocksValid > 0 ? R.blocksAgree / (double) R.blocksValid : 0;
        boolean ok = R.z >= P.minZ && agreeFrac >= P.minAgreeFrac && R.nccMean >= P.minNcc && R.margin >= P.minMargin && R.f1 >= P.minF1;
        R.accepted = ok;
        double c = Math.min(1.0, Math.max(0.0, (R.z - 3.0) / 12.0)) * 0.4 + Math.min(1.0, agreeFrac) * 0.3
                + Math.min(1.0, R.nccMean / 0.4) * 0.2 + Math.min(1.0, R.margin) * 0.1;
        R.confidence = c;
        if (!ok) {
            StringBuilder sb = new StringBuilder();
            if (R.z < P.minZ) sb.append("z baixo; ");
            if (agreeFrac < P.minAgreeFrac) sb.append("poucos blocos concordam; ");
            if (R.nccMean < P.minNcc) sb.append("correlação fraca; ");
            if (R.margin < P.minMargin) sb.append("pico ambíguo; ");
            if (R.f1 < P.minF1) sb.append("cobertura baixa; ");
            R.reason = sb.toString();
        }
    }

    // ------------------------------------------------------------------ cortes (por partes)
    private static void analyzeCuts(Result R, Cue[] cues, Window[] ws, float[][] ps, Block[] bl, int[] use, int nu, double alpha, Params P,
                                    long durMs, float[] fine) {
        // reservado para a fase de cortes (AudioSyncSession.refineCuts); aqui só sinaliza janelas que discordam
        if (!R.accepted && R.z < 3) return;
        int off = 0, tot = 0;
        for (int w = 0; w < ws.length; w++) {
            Fine f = fineWindow(ps[w], ws[w].startMs, fine, R.scale, R.offsetMs, 150);
            if (f.speechFrac < P.minSpeechFrac) continue;
            tot++;
            if (f.ncc < P.minNcc || Math.abs(f.bestDelta) > 600) off++;
        }
        R.pieceSuspect = tot >= 2 && off > 0;
    }
}
