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
        /** escala livre (fora das razões de fps): exige muito mais evidência (todos os falsos aceites medidos tinham escala livre) */
        public double minZFree = 12.0;
        public double minNccFree = 0.35;
        /** encaixe da escala: distância máxima (em alpha) até uma escala conhecida e resíduo máximo (ms) que o encaixe pode deixar */
        public double snapAlpha = 3e-3;
        public double snapResMs = 120;
        /** mínimo de blocos com fala para tentar */
        /** limiar final de z para ACEITAR o ajuste global (sem partes). Medido: z >= 9 deu 0 falsos aceites em 1823 negativos em todas as configurações; z >= 6 deu 3-9. */
        public double acceptMinZ = 9.0;
        /** alfa ajustado a menos disto de 0 => escala 1,0 (ver fineRefine) */
        public double unitAlpha = 6e-4;
        /** correção de escala 1,0 com |offset| menor que isto => a legenda já está sincronizada: não mexe (Result.inSync) */
        public double deadbandMs = 1100;
        /** a "família 1,001" (0,999 e 1,001001: 24 x 23,976 fps, 30 x 29,97 fps) só vale com PROVA nas janelas (ver driftCheck), nunca por si só */
        public double nearUnitEps = 0.0015;
        /** prova de deriva: janelas confiáveis (mínimo), cobertura do filme pelas janelas (fração e mínimo em ms), resíduo máximo da reta (ms), piso do resíduo
         *  na razão, deriva medida / resíduo (mínimo), deriva total no filme (mínimo, ms) e a janela só é confiável com NCC e z do pico acima disto */
        public int driftMinWins = 3;
        public double driftMinSpanFrac = 0.25;
        public long driftMinSpanMs = 240_000;
        public double driftMaxResMs = 250;
        /** com a deriva provada, o desvio de cada janela para o modelo final pode chegar a isto (ms) sem derrubar a aceitação (o ruído de uma janela de 90 s é de ~100-200 ms) */
        public double driftGateDevMs = 300;
        public double driftResFloorMs = 50;
        public double driftRatio = 3.0;
        public double driftContentMs = 800;
        public double winMinNcc = 0.15;
        /** o pico da janela se destaca do melhor pico a mais de 500 ms dele por pelo menos isto (NCC), ou tem z >= winMinZ */
        public double winMinMargin = 0.08;
        public double winMinZ = 5.0;
        /** acima disto (ms) a reta de escala 1,0 não explica as janelas confiáveis: suspeita de deriva => pede janelas extras (até maxDriftWindows) */
        public double driftSuspectMs = 150;
        public int maxDriftWindows = 8;
        /** porta (0 = desligada): todas as janelas com fala (>= 2) têm de concordar com o modelo dentro de gateWinDevMs (ms). Medida em CI: tira os falsos mexidos em legenda certa (4 -> 0 de 93) e os aceites errados (4,3% -> 0%) */
        public double gateWinDevMs = 150;
        /** porta opcional: mínimo de janelas com fala e concordantes (0 = desligada) */
        public int gateMinWinsAgree = 0;
        /** escalas fora das razões de fps conhecidas: ajuste dentro de unitSnap de 1,0 vira 1,0 (reajusta o offset); fora disso é recusado (allowFreeScale=false) */
        public double unitSnap = 0.0035;
        public boolean allowFreeScale = false;
        public int minBlocks = 3;
        public double minSpeechFrac = 0.03;
        public long maxCueGapForCut = 20_000;
        // ajuste por partes
        /** reta com escala de fps vence a reta livre se tem z >= minZ e explica pelo menos esta fração dos blocos */
        public double knownMinFrac = 0.4;
        public double pieceFitNcc = 0.25;
        public double pieceMinZ = 5.0;
        public double pieceExplained = 0.8;
        /** um bloco só é explicado por um modelo se a correlação nele chega a esta fração do maior pico do bloco */
        public double pieceRelPeak = 0.7;
        public int maxPieces = 4;
        public long cutResolutionMs = 50_000;
        public long bisectLenMs = 30_000;
        public double moreMinZ = 4.5;
        public int maxWindows = 9;
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
        double z;              // quanto o pico se destaca das demais defasagens (desvios-padrão), fora de +-500 ms do pico
        double speechFrac;
    }

    /** NCC fina de uma janela com o modelo (a, b), pesquisando deslocamentos de ±R*10 ms */
    static Fine fineWindow(float[] pSmooth, long startMs, float[] fine, double a, double b, int R) {
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
        double mu = 0, s2 = 0;
        int cn = 0;
        for (int i = 0; i < ncc.length; i++) if (Math.abs(i - bi) > 50) { mu += ncc[i]; s2 += ncc[i] * ncc[i]; cn++; }
        if (cn > 10) {
            mu /= cn;
            double sd = Math.sqrt(Math.max(1e-12, s2 / cn - mu * mu));
            out.z = (ncc[bi] - mu) / sd;
        }
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
        ArrayList<Boolean> known = new ArrayList<>();
        for (int a = 0; a < nu; a++) {
            Block A = bl[use[a]];
            for (int s = 0; s < P.knownScales.length; s++) {
                double al = alphaOf(P.knownScales[s]);
                if (Math.abs(al) > P.maxAlpha) continue;
                for (int pk = 0; pk < A.peakIdx.length; pk++) {
                    double L = (A.peakIdx[pk] - P.lagFrames) * (double) P.frameMs;
                    double beta = L - al * A.centerMs;
                    if (Math.abs(beta) <= P.maxOffsetMs * 1.2) { hyps.add(new double[]{al, beta}); known.add(Boolean.TRUE); }
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
                        hyps.add(new double[]{al, beta}); known.add(Boolean.FALSE);
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
        // nulo: retas aleatórias (não depende da hipótese)
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
        // preferência (Occam): se a melhor reta com escala "de fps" já é convincente (z alto e explica boa parte dos blocos), ela vence a reta livre
        int bestK = -1;
        for (int h = 0; h < H; h++) if (known.get(h) && (bestK < 0 || hs[h] > hs[bestK])) bestK = h;
        if (bestK >= 0 && bestK != bestH && (hs[bestK] - mu) / sd >= P.minZ) {
            double[] hk = hyps.get(bestK);
            int ex = 0;
            for (int t = 0; t < nu; t++) {
                Block b = bl[use[t]];
                float v = support(b, hk[0] * b.centerMs + hk[1], P);
                float thr = (float) P.pieceFitNcc;
                if (b.peakVal.length > 0) thr = Math.max(thr, (float) (P.pieceRelPeak * b.peakVal[0]));
                if (v >= thr) ex++;
            }
            if (ex >= P.knownMinFrac * nu) bestH = bestK;
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
        for (double ks : P.knownScales) if (Math.abs(ks - a0) < 1e-9) R.scaleKnown = true;
        if (!R.scaleKnown && Math.abs(a0 - 1.0) < P.unitSnap) {
            // escala "livre" muito perto de 1: é ruído (a legenda quase certa não tem escala); fixa 1,0 e reajusta só o deslocamento
            double uc = 0;
            for (Window w : windows) uc += w.startMs + w.p.length * 5.0;
            uc /= windows.length;
            double sc = (uc - b0) / a0;
            double[] m1 = fineRefine(windows, ps, fine, 1.0, uc - sc, P, R, true);
            a0 = 1.0; b0 = m1[1];
            R.scaleKnown = true;
        }
        R.scale = a0;
        R.offsetMs = b0;
        driftCheck(R, cues, windows, ps, fine, mediaDurationMs, P);
        finalEvidence(windows, ps, fine, R, P);
        decide(R, P);
        try {
            if (!R.driftScale) analyzeCuts(R, cues, windows, ps, bl, use, nu, P, mediaDurationMs, fine);
        } catch (RuntimeException e) {
            R.segments = null; R.more = null; R.pieceSuspect = true;   // o ajuste global continua valendo
        }
        if (R.segments != null && R.segments.length > 1) {
            // cortes: só AVISO (a precisão medida do ajuste por partes é baixa); não se aplica nada
            R.cutsWarning = true;
            R.accepted = false;
            R.reason = (R.reason + " possíveis cortes na legenda (só aviso);").trim();
        } else if (R.segments != null) {
            R.segments = null;
        }
        if (R.accepted && R.segments == null && !R.driftScale && R.z < P.acceptMinZ) {
            R.accepted = false;
            R.reason = (R.reason + " z abaixo do limiar de aceitação (" + P.acceptMinZ + ");").trim();
        }
        R.rawOffsetMs = R.offsetMs;
        if (R.accepted && R.segments == null && Math.abs(R.scale - 1.0) < 1e-9 && Math.abs(R.offsetMs) < P.deadbandMs) {
            R.inSync = true;
            R.offsetMs = 0;
        }
        if (!R.accepted) suggestMore(R, windows, P, mediaDurationMs);
        return R;
    }

    // ------------------------------------------------------------------ deriva (escala de fps, p.ex. 1,001)
    /** ajuste de atraso(t) = beta + alpha*t com alpha FIXO (mínimos quadrados ponderados): {beta, resíduo máximo em ms} */
    private static double[] fitFixedAlpha(double[] x, double[] y, double[] w, int n, double alpha) {
        double sw = 0, sy = 0;
        for (int i = 0; i < n; i++) { sw += w[i]; sy += w[i] * (y[i] - alpha * x[i]); }
        double beta = sw > 0 ? sy / sw : 0;
        double res = 0;
        for (int i = 0; i < n; i++) res = Math.max(res, Math.abs(y[i] - (beta + alpha * x[i])));
        return new double[]{beta, res};
    }

    /**
     * Prova de deriva. Cada janela mede, sozinha, o atraso local da legenda SEM escala (NCC fina numa faixa de +-3 s em volta do modelo atual).
     * Com as janelas confiáveis (NCC e z do pico próprios) ajusta atraso(t) = a + b*t. A família 1,001 (0,999 / 1,001001) só vale se TODAS estas coisas valem:
     *  - pelo menos driftMinWins janelas confiáveis, cobrindo pelo menos driftMinSpanFrac do filme (e driftMinSpanMs);
     *  - a reta com a inclinação da razão de fps deixa resíduo máximo <= driftMaxResMs;
     *  - a deriva medida nas janelas (|b| * cobertura) passa de driftRatio vezes o resíduo (com piso driftResFloorMs): a inclinação não é ruído;
     *  - a deriva no filme todo (|b| * duração) passa de driftContentMs, e a reta com escala 1,0 deixa o dobro do resíduo ou mais.
     * Sem isso a escala volta a 1,0 (se a reta sem escala explica as janelas) ou fica recusada (nearUnit em decide). Legenda certa (inclinação 0) nunca ganha escala.
     * Se as janelas confiáveis discordam de escala 1,0 mas a prova ainda não fecha, marca driftSuspect e propõe janelas extras espalhadas (driftMore).
     */
    private static void driftCheck(Result R, Cue[] cues, Window[] ws, float[][] ps, float[] fine, long durMs, Params P) {
        int W = ws.length;
        Result.Win[] info = new Result.Win[W];
        double[] x = new double[W], y = new double[W], wt = new double[W];
        int[] widx = new int[W];
        int n = 0;
        // o centro da pesquisa de cada janela: o modelo do ajuste grosso se ele é de escala ~1 (a faixa é de +-10 s em volta dele); se não, atraso 0 (+-15 s):
        // o modelo errado do ajuste grosso não pode impedir a prova (a legenda com deriva é justamente a que o ajuste grosso encaixa mal)
        final boolean nearUnitModel = Math.abs(R.scale - 1.0) < 0.0035;
        for (int w = 0; w < W; w++) {
            Result.Win wi = new Result.Win();
            double uc = ws[w].startMs + ws[w].p.length * 5.0;
            double d0 = nearUnitModel ? uc - (uc - R.offsetMs) / R.scale : 0;
            Fine f = fineWindow(ps[w], ws[w].startMs, fine, 1.0, d0, nearUnitModel ? 1000 : 1500);
            wi.startMs = ws[w].startMs;
            wi.centerMs = uc;
            wi.delayMs = d0 + f.bestDelta;
            wi.ncc = f.ncc;
            wi.z = f.z;
            wi.speech = f.speechFrac >= P.minSpeechFrac;
            wi.second = f.nccSecond;
            wi.confident = wi.speech && f.ncc >= P.winMinNcc && (f.ncc - f.nccSecond >= P.winMinMargin || f.z >= P.winMinZ);
            if (wi.confident) { x[n] = uc; y[n] = wi.delayMs; wt[n] = Math.max(0.01, f.ncc); widx[n] = w; n++; }
            info[w] = wi;
        }
        R.wins = info;
        R.driftConfident = n;
        if (n < 2) { R.driftNote = "menos de 2 janelas confiáveis (" + n + " de " + W + ")"; return; }
        double[] unit = fitFixedAlpha(x, y, wt, n, 0.0);
        R.unitResMs = unit[1];
        R.driftResMs = unit[1];
        // inclinação livre (só diagnóstico)
        {
            double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) { sw += wt[i]; sx += wt[i] * x[i]; sy += wt[i] * y[i]; sxx += wt[i] * x[i] * x[i]; sxy += wt[i] * x[i] * y[i]; }
            double den = sw * sxx - sx * sx;
            if (den > 1e-6 * sw * sw) R.driftSlope = (sw * sxy - sx * sy) / den;
        }

        final double[] ks = {24.0 / 23.976, 23.976 / 24.0};
        int best = -1;
        double bestRes = Double.MAX_VALUE, bestBeta = 0, bestSpan = 0;
        int bestN = 0, bestDrop = -1;
        boolean nearMiss = false;
        String why = "";
        for (int c = 0; c < 2; c++) {
            double al = alphaOf(ks[c]);
            // melhor subconjunto: todas as janelas confiáveis; se a reta não fecha e há folga, tira UMA (a que mais atrapalha)
            int drop = -1;
            double[] fr = fitFixedAlpha(x, y, wt, n, al);
            if (fr[1] > P.driftMaxResMs && n > P.driftMinWins) {
                for (int d = 0; d < n; d++) {
                    double[] xs = new double[n - 1], ys = new double[n - 1], ws2 = new double[n - 1];
                    int q = 0;
                    for (int i = 0; i < n; i++) if (i != d) { xs[q] = x[i]; ys[q] = y[i]; ws2[q] = wt[i]; q++; }
                    double[] f2 = fitFixedAlpha(xs, ys, ws2, n - 1, al);
                    if (f2[1] < fr[1]) { fr = f2; drop = d; }
                }
            }
            int nn = drop >= 0 ? n - 1 : n;
            double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE;
            double[] xs = new double[nn], ys = new double[nn], ws2 = new double[nn];
            int q = 0;
            for (int i = 0; i < n; i++) if (i != drop) { xs[q] = x[i]; ys[q] = y[i]; ws2[q] = wt[i]; q++; xmin = Math.min(xmin, x[i]); xmax = Math.max(xmax, x[i]); }
            double span = xmax - xmin;
            double[] un = fitFixedAlpha(xs, ys, ws2, nn, 0.0);
            double meas = Math.abs(al) * span, content = Math.abs(al) * durMs;
            boolean fits = fr[1] <= P.driftMaxResMs && 2 * fr[1] <= un[1] && meas >= 2 * Math.max(fr[1], P.driftResFloorMs);
            boolean spanOk = span >= Math.max((double) P.driftMinSpanMs, P.driftMinSpanFrac * durMs);
            boolean ok = fits && nn >= P.driftMinWins && spanOk && meas >= P.driftRatio * Math.max(fr[1], P.driftResFloorMs) && content >= P.driftContentMs;
            if (fits && !ok) {
                nearMiss = true;
                why = (nn < P.driftMinWins ? "só " + nn + " janelas confiáveis; " : "") + (!spanOk ? "janelas cobrem pouco do filme; " : "")
                        + (meas < P.driftRatio * Math.max(fr[1], P.driftResFloorMs) ? "deriva medida pequena frente ao resíduo; " : "")
                        + (content < P.driftContentMs ? "deriva no filme abaixo de " + (long) P.driftContentMs + " ms; " : "");
            }
            if (ok && fr[1] < bestRes) { best = c; bestRes = fr[1]; bestBeta = fr[0]; bestSpan = span; bestN = nn; bestDrop = drop; }
        }
        if (best >= 0) {
            double al = alphaOf(ks[best]);
            R.scale = ks[best];
            R.offsetMs = bestBeta * R.scale;
            R.scaleKnown = true;
            R.driftScale = true;
            R.driftDropWin = bestDrop >= 0 ? widx[bestDrop] : -1;
            R.driftResMs = bestRes;
            R.driftMeasMs = Math.abs(al) * bestSpan;
            R.driftContentMs = Math.abs(al) * durMs;
            R.driftNote = String.format(java.util.Locale.ROOT, "deriva provada: escala %.6f, resíduo %.0f ms (reta sem escala deixaria %.0f ms), deriva medida %.0f ms nas janelas e %.0f ms no filme, %d janelas confiáveis%s",
                    R.scale, bestRes, unit[1], R.driftMeasMs, R.driftContentMs, bestN, bestDrop >= 0 ? " (1 janela fora da reta descartada)" : "");
            return;
        }
        if (Math.abs(R.scale - 1.0) > 1e-9 && nearUnitModel) {
            // a escala 1,001 que o ajuste fino encaixou não tem prova: se a reta sem escala explica as janelas, é 1,0 (o encaixe era ruído)
            if (unit[1] <= P.driftMaxResMs) {
                R.scale = 1.0;
                R.offsetMs = unit[0];
                R.scaleKnown = true;
                R.driftNote = "escala 1,001 sem prova: a reta sem escala explica as janelas (resíduo " + Math.round(unit[1]) + " ms): fica 1,0";
                return;
            }
        }
        R.driftNote = "sem prova de deriva: resíduo da reta sem escala " + Math.round(unit[1]) + " ms" + (nearMiss ? " (" + why.trim() + ")" : "");
        if (unit[1] > P.driftSuspectMs || nearMiss) {
            R.driftSuspect = true;
            if (W < P.maxDriftWindows) R.driftMore = moreSpread(cues, ws, durMs, P);
        }
    }

    /** janelas extras espalhadas: das posições do plano de maxDriftWindows janelas, as mais afastadas das que já existem */
    private static long[][] moreSpread(Cue[] cues, Window[] ws, long durMs, Params P) {
        long len = ws.length > 0 ? ws[0].durationMs() : 90_000;
        long[][] plan = AudioSyncSession.planSpread(cues, durMs, P.maxDriftWindows, len);
        int want = P.maxDriftWindows - ws.length;
        ArrayList<long[]> pick = new ArrayList<>();
        boolean[] used = new boolean[plan.length];
        for (int k = 0; k < want; k++) {
            int bi = -1;
            long bd = -1;
            for (int i = 0; i < plan.length; i++) {
                if (used[i]) continue;
                long d = Long.MAX_VALUE;
                for (Window w : ws) d = Math.min(d, Math.abs(plan[i][0] - w.startMs));
                for (long[] q : pick) d = Math.min(d, Math.abs(plan[i][0] - q[0]));
                if (d > bd) { bd = d; bi = i; }
            }
            if (bi < 0 || bd < len + 10_000) break;
            used[bi] = true;
            pick.add(plan[bi]);
        }
        return pick.isEmpty() ? null : pick.toArray(new long[0][]);
    }

    /** refina (scale, offset) com a NCC fina por janela; encaixa a escala em valores conhecidos quando os resíduos permitem */
    private static double[] fineRefine(Window[] ws, float[][] ps, float[] fine, double a, double b, Params P, Result R) { return fineRefine(ws, ps, fine, a, b, P, R, false); }

    private static double[] fineRefine(Window[] ws, float[][] ps, float[] fine, double a, double b, Params P, Result R, boolean lock) {
        double bestA = a, bestB = b;
        int W = ws.length;
        for (int it = 0; it < 3; it++) {
            double[] sS = new double[W], uU = new double[W], wt = new double[W], nc = new double[W];
            for (int w = 0; w < W; w++) {
                Fine f = fineWindow(ps[w], ws[w].startMs, fine, bestA, bestB, 150);
                double bw = bestB + f.bestDelta;
                double uc = ws[w].startMs + ws[w].p.length * 5.0;
                sS[w] = (uc - bw) / bestA;
                uU[w] = uc;
                nc[w] = f.speechFrac > 0.03 ? Math.max(0.0, f.ncc) : 0.0;
            }
            // só entram janelas bem explicadas pelo modelo (as de outro trecho/ruído ficam de fora)
            double[] sorted = nc.clone();
            Arrays.sort(sorted);
            double med = sorted[W / 2];
            double thr = Math.max(P.minNcc * 0.8, 0.5 * med);
            int used = 0;
            for (int w = 0; w < W; w++) { wt[w] = nc[w] >= thr ? nc[w] : 0; if (wt[w] > 0) used++; }
            if (used == 0) break;
            double[] fit = lsFit(sS, uU, wt, bestA, bestB, used >= 2 && !lock);
            // descarta janelas com resíduo grande e ajusta de novo
            int kept = 0;
            for (int w = 0; w < W; w++) {
                if (wt[w] <= 0) continue;
                if (Math.abs(uU[w] - (fit[0] * sS[w] + fit[1])) > 250) wt[w] = 0; else kept++;
            }
            if (kept == 0) break;
            if (kept != used) fit = lsFit(sS, uU, wt, bestA, bestB, kept >= 2 && !lock);
            double na = fit[0], nb = fit[1];
            // encaixe: usa a escala conhecida mais próxima se os resíduos continuam pequenos
            double sa = alphaOf(na), bestRes = Double.MAX_VALUE, snapA = na, snapB = nb;
            double sw = 0, sx = 0, sy = 0;
            for (int w = 0; w < W; w++) { if (wt[w] <= 0) continue; sw += wt[w]; sx += wt[w] * sS[w]; sy += wt[w] * uU[w]; }
            // entre as escalas conhecidas viáveis (resíduo máximo <= snapResMs) vence a de alfa mais próximo da ajustada; a escala 1,0 vence se a ajustada está
            // a menos de unitAlpha de 1 (o ruído dos resíduos não pode trocar 1,000 por 1,001 ou 24/23,976 numa legenda que já está certa)
            double bestDist = Double.MAX_VALUE;
            for (int s = 0; s < P.knownScales.length; s++) {
                double ks = P.knownScales[s];
                if (Math.abs(alphaOf(ks) - sa) > P.snapAlpha) continue;
                double kb = (sy - ks * sx) / sw, mx = 0;
                for (int w = 0; w < W; w++) if (wt[w] > 0) mx = Math.max(mx, Math.abs(uU[w] - (ks * sS[w] + kb)));
                if (mx > P.snapResMs) continue;
                double dist = ks == 1.0 && Math.abs(sa) <= P.unitAlpha ? -1.0 : Math.abs(alphaOf(ks) - sa);
                if (dist < bestDist) { bestDist = dist; bestRes = mx; snapA = ks; snapB = kb; }
            }
            bestA = snapA; bestB = snapB;
            if (snapA == na) { bestA = na; bestB = nb; }
        }
        return new double[]{bestA, bestB};
    }

    /** mínimos quadrados ponderados de u = a*s + b; com um só ponto (ou pouca dispersão em s) mantém a escala a0 */
    private static double[] lsFit(double[] s, double[] u, double[] w, double a0, double b0, boolean free) {
        double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < s.length; i++) {
            if (w[i] <= 0) continue;
            sw += w[i]; sx += w[i] * s[i]; sy += w[i] * u[i]; sxx += w[i] * s[i] * s[i]; sxy += w[i] * s[i] * u[i];
        }
        if (sw <= 0) return new double[]{a0, b0};
        double den = sw * sxx - sx * sx;
        double spread = den / (sw * sw);
        double na = a0;
        if (free && spread > 600.0 * 600.0) {
            na = (sw * sxy - sx * sy) / den;
            if (Math.abs(na - a0) > 0.01) na = a0;
        }
        return new double[]{na, (sy - na * sx) / sw};
    }

    private static void finalEvidence(Window[] ws, float[][] ps, float[] fine, Result R, Params P) {
        double sum = 0, min = 9;
        int cnt = 0;
        double tp = 0, fp = 0, fn = 0;
        for (int w = 0; w < ws.length; w++) {
            if (w == R.driftDropWin) continue;
            Fine f = fineWindow(ps[w], ws[w].startMs, fine, R.scale, R.offsetMs, 40);
            if (f.speechFrac < P.minSpeechFrac) continue;
            Fine fw = fineWindow(ps[w], ws[w].startMs, fine, R.scale, R.offsetMs, 150);
            R.winsValid++;
            double dev = Math.abs(fw.bestDelta);
            if (dev > R.winMaxDev) R.winMaxDev = dev;
            if (dev <= 150) R.winsAgree++;
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
        if (!R.scaleKnown && (!P.allowFreeScale || R.z < P.minZFree || R.nccMean < P.minNccFree)) ok = false;
        // deriva provada pelas janelas (reta de escala conhecida que fecha em todas, ver driftCheck): a evidência é a delas; o z e a concordância dos BLOCOS
        // foram medidos contra o modelo grosso (que encaixa mal uma legenda com deriva) e não valem aqui
        if (R.driftScale) ok = R.nccMean >= P.minNcc && R.f1 >= P.minF1;
        // família 1,001: só com a prova de deriva (driftCheck); um valor assim sem prova é ruído de ajuste
        boolean nearUnit = !R.driftScale && Math.abs(R.scale - 1.0) > 1e-9 && Math.abs(R.scale - 1.0) < P.nearUnitEps;
        if (nearUnit) ok = false;
        boolean gateDev = P.gateWinDevMs > 0 && R.winsValid >= 2 && R.winMaxDev > (R.driftScale ? P.driftGateDevMs : P.gateWinDevMs);
        boolean gateWin = P.gateMinWinsAgree > 0 && R.winsAgree < P.gateMinWinsAgree;
        if (gateDev || gateWin) ok = false;
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
            if (!R.scaleKnown && (R.z < P.minZFree || R.nccMean < P.minNccFree)) sb.append("escala fora das razões de fps; ");
            if (nearUnit) sb.append("escala 0,999/1,001 sem prova de deriva; ");
            if (R.driftSuspect) sb.append("possível deriva: janelas discordam entre si; ");
            if (gateDev || gateWin) sb.append("janelas discordam; ");
            R.reason = sb.toString();
        }
    }

    // ------------------------------------------------------------------ cortes (por partes)
    /**
     * Ajuste por partes. Parte do modelo global aceito (escala comum, deslocamento por trecho): rotula cada bloco de 30 s com o modelo que o explica,
     * procura novos deslocamentos entre os blocos que ninguém explica (mesma escala), e coloca cada corte no maior intervalo sem falas
     * entre o último bloco de um trecho e o primeiro do próximo. Se o intervalo ainda é largo, pede janelas extras (Result.more) no meio dele.
     */
    private static void analyzeCuts(Result R, Cue[] cues, Window[] ws, float[][] ps, Block[] bl, int[] use, int nu, Params P,
                                    long durMs, float[] fine) {
        if (!R.accepted && R.z < P.minZ) return;
        final double a0 = R.scale, alpha = alphaOf(a0);
        double[] offs = new double[P.maxPieces];
        int nm = 1;
        offs[0] = R.offsetMs;
        int[] lab = new int[nu];
        relabel(bl, use, nu, alpha, offs, nm, a0, P, lab);
        Random rnd = new Random(P.seed + 7);
        while (nm < P.maxPieces) {
            int no = 0;
            for (int t = 0; t < nu; t++) if (lab[t] < 0) no++;
            if (no < 2) break;
            int[] oi = new int[no];
            int q = 0;
            for (int t = 0; t < nu; t++) if (lab[t] < 0) oi[q++] = t;
            double bestS = -1, bestBeta = 0;
            for (int k = 0; k < no; k++) {
                Block A = bl[use[oi[k]]];
                for (int pk = 0; pk < A.peakIdx.length; pk++) {
                    double L = (A.peakIdx[pk] - P.lagFrames) * (double) P.frameMs;
                    double beta = L - alpha * A.centerMs;
                    if (Math.abs(beta * a0) > P.maxOffsetMs * 1.2) continue;
                    double sc = 0;
                    for (int j = 0; j < no; j++) {
                        Block B = bl[use[oi[j]]];
                        sc += support(B, alpha * B.centerMs + beta, P);
                    }
                    if (sc > bestS) { bestS = sc; bestBeta = beta; }
                }
            }
            if (bestS <= 0) break;
            int nfit = 0;
            for (int j = 0; j < no; j++) {
                Block B = bl[use[oi[j]]];
                if (support(B, alpha * B.centerMs + bestBeta, P) >= P.pieceFitNcc) nfit++;
            }
            if (nfit < 2) break;
            double s1 = 0, s2 = 0;
            int nr = 300;
            for (int r = 0; r < nr; r++) {
                double beta = (rnd.nextDouble() * 2 - 1) * P.maxOffsetMs / a0;
                double sc = 0;
                for (int j = 0; j < no; j++) {
                    Block B = bl[use[oi[j]]];
                    sc += support(B, alpha * B.centerMs + beta, P);
                }
                s1 += sc; s2 += sc * sc;
            }
            double mu = s1 / nr, sd = Math.sqrt(Math.max(1e-9, s2 / nr - mu * mu));
            if ((bestS - mu) / sd < P.pieceMinZ) break;
            double off2 = bestBeta * a0;
            boolean dup = false;
            for (int m = 0; m < nm; m++) if (Math.abs(offs[m] - off2) < 600) dup = true;
            if (dup) break;
            // refino fino com as janelas inteiramente explicadas pelo novo modelo
            offs[nm] = off2;
            nm++;
            relabel(bl, use, nu, alpha, offs, nm, a0, P, lab);
            for (int it = 0; it < 2; it++) {
                double sw = 0, sd2 = 0;
                for (int w = 0; w < ws.length; w++) {
                    boolean all = false, mixed = false;
                    for (int t = 0; t < nu; t++) {
                        if (bl[use[t]].win != w) continue;
                        if (lab[t] == nm - 1) all = true; else mixed = true;
                    }
                    if (!all || mixed) continue;
                    Fine f = fineWindow(ps[w], ws[w].startMs, fine, a0, offs[nm - 1], 150);
                    if (f.ncc < P.minNcc) continue;
                    sw += f.ncc; sd2 += f.ncc * f.bestDelta;
                }
                if (sw <= 0) break;
                offs[nm - 1] += sd2 / sw;
            }
            relabel(bl, use, nu, alpha, offs, nm, a0, P, lab);
        }
        int no = 0;
        for (int t = 0; t < nu; t++) if (lab[t] < 0) no++;
        if (nm == 1) {
            R.pieceSuspect = no >= 1;
            // um único bloco forte sem explicação: pede uma janela colada nele para confirmar um possível 2º trecho
            if (no == 1 && R.accepted && ws.length < P.maxWindows) {
                for (int t = 0; t < nu; t++) {
                    if (lab[t] >= 0) continue;
                    Block b = bl[use[t]];
                    if (b.peakVal.length == 0 || b.peakVal[0] < 0.25f) continue;
                    long st = ws[b.win].startMs + ws[b.win].durationMs() + 5_000;
                    if (st + P.bisectLenMs > durMs) st = Math.max(0, ws[b.win].startMs - 5_000 - P.bisectLenMs);
                    boolean covered = false;
                    for (Window w : ws) if (st < w.startMs + w.durationMs() && st + P.bisectLenMs > w.startMs) covered = true;
                    if (!covered) R.more = new long[][]{{st / 10 * 10, P.bisectLenMs}};
                }
            }
            return;
        }

        // blocos rotulados em ordem de tempo
        int cnt = 0;
        for (int t = 0; t < nu; t++) if (lab[t] >= 0) cnt++;
        long[] bStart = new long[cnt], bEnd = new long[cnt];
        int[] bLab = new int[cnt];
        Integer[] ord = new Integer[nu];
        for (int t = 0; t < nu; t++) ord[t] = t;
        final Block[] blf = bl; final int[] usef = use;
        Arrays.sort(ord, (x, y) -> Long.compare(blf[usef[x]].uMs, blf[usef[y]].uMs));
        int q = 0;
        for (int k = 0; k < nu; k++) {
            int t = ord[k];
            if (lab[t] < 0) continue;
            Block b = bl[use[t]];
            bStart[q] = b.uMs; bEnd[q] = b.uMs + (long) b.n * P.frameMs; bLab[q] = lab[t]; q++;
        }
        if (cnt == 0) { R.pieceSuspect = true; return; }
        ArrayList<Segment> segs = new ArrayList<>();
        ArrayList<long[]> more = new ArrayList<>();
        segs.add(new Segment(Long.MIN_VALUE / 4, a0, offs[bLab[0]]));
        long lastCut = Long.MIN_VALUE / 4;
        boolean bad = false;
        for (int k = 1; k < cnt; k++) {
            if (bLab[k] == bLab[k - 1]) continue;
            long tL = bEnd[k - 1], tR = bStart[k];
            double oL = offs[bLab[k - 1]], oR = offs[bLab[k]];
            double lo = (tL - oL) / a0, hi = (tR - oR) / a0;
            if (hi < lo) { double tmp = lo; lo = hi; hi = tmp; }
            long cut = pickCut(cues, lo, hi);
            if (cut <= lastCut) { bad = true; break; }
            lastCut = cut;
            segs.add(new Segment(cut, a0, oR));
            long span = tR - tL;
            if (span > P.cutResolutionMs && span > P.bisectLenMs * 8 / 5) {
                long mid = (tL + tR) / 2;
                // meio do intervalo; se já há janela ali (p.ex. sem fala aproveitável), tenta posições vizinhas dentro do intervalo
                long pick = -1;
                for (int kk = 0; kk < 8 && pick < 0; kk++) {
                    long d = ((kk + 1) / 2) * (P.bisectLenMs + 5_000) * ((kk & 1) == 1 ? 1 : -1);
                    long c = mid + d;
                    long st = c - P.bisectLenMs / 2;
                    if (st < tL || st + P.bisectLenMs > tR || st < 0 || st + P.bisectLenMs > durMs) continue;
                    boolean covered = false;
                    for (Window w : ws) if (st < w.startMs + w.durationMs() && st + P.bisectLenMs > w.startMs) covered = true;
                    for (long[] m : more) if (st < m[0] + m[1] && st + P.bisectLenMs > m[0]) covered = true;
                    if (!covered) pick = st / 10 * 10;
                }
                if (pick >= 0) more.add(new long[]{pick, P.bisectLenMs});
            }
        }
        if (bad) { R.pieceSuspect = true; return; }
        if (!R.accepted) {
            // o pico "ambíguo" pode ser só o outro trecho: aceita se os trechos achados explicam a grande maioria dos blocos
            int[] per = new int[nm];
            for (int t = 0; t < nu; t++) if (lab[t] >= 0) per[lab[t]]++;
            boolean okp = (nu - no) >= P.pieceExplained * nu;
            for (int m = 0; m < nm; m++) if (per[m] < 2) okp = false;
            if (!okp) return;
            R.accepted = true;
            R.reason = "";
            R.confidence = Math.min(R.confidence, 0.6);
        }
        R.segments = segs.toArray(new Segment[0]);
        if (!more.isEmpty()) R.more = more.toArray(new long[0][]);
    }

    /** rotula cada bloco com o modelo (mesma escala, deslocamento por trecho) que melhor o explica; -1 se nenhum */
    private static void relabel(Block[] bl, int[] use, int nu, double alpha, double[] offs, int nm, double a0, Params P, int[] lab) {
        for (int t = 0; t < nu; t++) {
            Block b = bl[use[t]];
            int best = -1;
            float bv = (float) P.pieceFitNcc;
            if (b.peakVal.length > 0) bv = Math.max(bv, (float) (P.pieceRelPeak * b.peakVal[0]));
            for (int m = 0; m < nm; m++) {
                float v = support(b, alpha * b.centerMs + offs[m] / a0, P);
                if (v >= bv) { bv = v; best = m; }
            }
            lab[t] = best;
        }
    }

    /** corte (tempo da legenda) no maior intervalo entre falas dentro de [lo, hi]; no meio do intervalo se não houver falas */
    private static long pickCut(Cue[] cues, double lo, double hi) {
        int n = cues.length;
        long[] st = new long[n], en = new long[n];
        for (int i = 0; i < n; i++) { st[i] = cues[i].startMs; en[i] = cues[i].endMs; }
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (x, y) -> Long.compare(st[x], st[y]));
        long bestGap = -1, best = (long) ((lo + hi) / 2);
        long maxEnd = Long.MIN_VALUE;
        for (int k = 0; k + 1 < n; k++) {
            int i = ord[k], j = ord[k + 1];
            if (en[i] > maxEnd) maxEnd = en[i];
            long gap = st[j] - maxEnd;
            if (gap <= 0) continue;
            long mid = (maxEnd + st[j]) / 2;
            if (mid < lo - 1000 || mid > hi + 1000) continue;
            if (gap > bestGap) { bestGap = gap; best = mid; }
        }
        return best;
    }

    /** recusada mas promissora: sugere janelas extras nos maiores vãos entre as já capturadas */
    private static void suggestMore(Result R, Window[] ws, Params P, long durMs) {
        if (R.z < P.moreMinZ || R.blocksAgree < 2 || ws.length >= P.maxWindows) return;
        int n = ws.length;
        long[][] w = new long[n][2];
        long len = 0;
        for (int i = 0; i < n; i++) { w[i][0] = ws[i].startMs; w[i][1] = ws[i].startMs + ws[i].durationMs(); len += ws[i].durationMs(); }
        len = Math.max(20_000, Math.min(60_000, len / n));
        Arrays.sort(w, (x, y) -> Long.compare(x[0], y[0]));
        ArrayList<long[]> gaps = new ArrayList<>();
        long prev = 0;
        for (int i = 0; i <= n; i++) {
            long nxt = i < n ? w[i][0] : durMs;
            gaps.add(new long[]{nxt - prev, (prev + nxt) / 2});
            if (i < n) prev = w[i][1];
        }
        gaps.sort((x, y) -> Long.compare(y[0], x[0]));
        ArrayList<long[]> more = new ArrayList<>();
        for (int i = 0; i < gaps.size() && more.size() < 2; i++) {
            if (gaps.get(i)[0] < 2 * len) break;
            long st = Math.max(0, Math.min(gaps.get(i)[1] - len / 2, durMs - len)) / 10 * 10;
            more.add(new long[]{st, len});
        }
        if (!more.isEmpty()) R.more = more.toArray(new long[0][]);
    }
}
