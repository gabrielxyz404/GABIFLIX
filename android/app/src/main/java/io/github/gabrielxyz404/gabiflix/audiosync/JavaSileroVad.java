package io.github.gabrielxyz404.gabiflix.audiosync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Silero VAD v5 (16 kHz) em Java PURO: a mesma rede do silero_vad.onnx (STFT -> 4 convoluções -> LSTM -> decisor), sem ONNX Runtime e sem
 * código nativo. Existe porque, no Fire TV Stick 4K Max (ARM de 32 bits), a criação da sessão do ONNX Runtime derrubava o processo (SIGSEGV) em todos os
 * modos, e o VAD por DSP, bem menos preciso, recusava a maioria das legendas.
 *
 * Os pesos vêm de assets/silero_v5_16k.bin (tools/silero-java/export_weights.py os tira do ONNX; a CI confere o arquivo e a paridade com o ONNX Runtime).
 * Mesma saída que {@link SileroVad} (ONNX Runtime): blocos de 512 amostras (32 ms) com 64 de contexto, estado zerado a cada chamada, e a saída (31,25 Hz)
 * interpolada para 100 Hz como no corpus (corpus.py, vad_tracks).
 *
 * Estrutura (ramo de 16 kHz do modelo): cada bloco vira 640 amostras (64 de contexto + 512 + 64 refletidas à direita); STFT de 256 com salto de 128 (4 quadros,
 * 129 módulos); conv(129->128, k3) ReLU; conv(128->64, k3, passo 2) ReLU; conv(64->64, k3, passo 2) ReLU; conv(64->128, k3) ReLU (fica 1 passo no tempo);
 * LSTM de 128 (estado h,c entre blocos); ReLU; conv 1x1 (128->1); sigmoide. O codificador não depende do estado: os blocos são independentes entre si
 * (por isso pode usar vários fios); só o LSTM é sequencial.
 *
 * Sem alocação por bloco: os buffers são por fio e por chamada. Uma instância pode ser usada de vários fios (os pesos são só de leitura).
 */
public final class JavaSileroVad implements Vad, AutoCloseable {
    private static final int CH = 512, CTX = 64, IN = CTX + CH, PADN = IN + 64, BINS = 129, HID = 128, NT = 4;
    /** blocos (de 32 ms) codificados de uma vez antes do LSTM: 256 = 8,2 s de áudio */
    private static final int BLOCK = 256;
    private static final float INV = 1f / 32768f;

    private final float[] basis, w0, b0, w1, b1, w2, b2, w3, b3, wih, whh, bl, dw;
    private final float db;
    private final int threads;
    private final ExecutorService pool;

    /** @param threads quantos fios usar no codificador (1 = só o fio que chama); @param tf fábrica dos fios extras (prioridade baixa no Android), ou null */
    public JavaSileroVad(byte[] weights, int threads, ThreadFactory tf) {
        ByteBuffer bb = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.remaining() < 12 || bb.getInt() != 0x35564c53 /* "SLV5" */) throw new IllegalArgumentException("arquivo de pesos do Silero inválido");
        if (bb.getInt() != 1) throw new IllegalArgumentException("versão dos pesos do Silero não suportada");
        int nt = bb.getInt();
        if (nt != 14) throw new IllegalArgumentException("pesos do Silero: " + nt + " tensores (esperava 14)");
        int[] sizes = {258 * 256, 128 * 3 * 129, 128, 64 * 3 * 128, 64, 64 * 3 * 64, 64, 128 * 3 * 64, 128, 512 * 128, 512 * 128, 512, 128, 1};
        float[][] t = new float[14][];
        for (int i = 0; i < 14; i++) {
            int n = bb.getInt();
            if (n != sizes[i]) throw new IllegalArgumentException("pesos do Silero: tensor " + i + " com " + n + " valores (esperava " + sizes[i] + ")");
            t[i] = new float[n];
            bb.asFloatBuffer().get(t[i], 0, n);   // a visão começa na posição atual
            bb.position(bb.position() + 4 * n);
        }
        basis = t[0]; w0 = t[1]; b0 = t[2]; w1 = t[3]; b1 = t[4]; w2 = t[5]; b2 = t[6]; w3 = t[7]; b3 = t[8];
        wih = t[9]; whh = t[10]; bl = t[11]; dw = t[12]; db = t[13][0];
        this.threads = Math.max(1, threads);
        this.pool = this.threads > 1 ? Executors.newFixedThreadPool(this.threads - 1, tf != null ? tf : r -> { Thread th = new Thread(r, "gf-silero"); th.setDaemon(true); return th; }) : null;
    }

    public static JavaSileroVad load(InputStream is, int threads, ThreadFactory tf) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1_300_000);
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return new JavaSileroVad(bo.toByteArray(), threads, tf);
    }

    @Override public String name() { return "silero"; }

    @Override public void close() { if (pool != null) pool.shutdownNow(); }

    // ------------------------------------------------------------------------------------------------------------ inferência

    /** buffers de um fio (nada é alocado por bloco) */
    private static final class Work {
        final float[] x = new float[PADN];
        /** módulos do STFT: 4 quadros + 1 linha de zeros antes e depois (preenchimento do conv) */
        final float[] s = new float[(NT + 2) * BINS];
        final float[] a0 = new float[(NT + 2) * 128];
        final float[] a1 = new float[4 * 64];
        final float[] a2 = new float[64];
    }

    /** probabilidade de fala de cada bloco de 512 amostras (31,25 por segundo), estado zerado no começo */
    public float[] frameProbs(short[] pcm, int n) {
        int nch = n / CH;
        float[] probs = new float[nch];
        if (nch == 0) return probs;
        final float[] enc = new float[Math.min(BLOCK, nch) * HID];
        final Work[] works = new Work[threads];
        for (int i = 0; i < threads; i++) works[i] = new Work();
        float[] h = new float[HID], c = new float[HID], g = new float[4 * HID];
        for (int b0f = 0; b0f < nch; b0f += BLOCK) {
            final int cnt = Math.min(BLOCK, nch - b0f), first = b0f;
            encodeBlock(pcm, first, cnt, enc, works);
            for (int i = 0; i < cnt; i++) probs[first + i] = lstmStep(enc, i * HID, h, c, g);
        }
        return probs;
    }

    private void encodeBlock(final short[] pcm, final int first, final int cnt, final float[] enc, final Work[] works) {
        if (pool == null || cnt < 2 * threads) {
            for (int i = 0; i < cnt; i++) encode(works[0], pcm, first + i, enc, i * HID);
            return;
        }
        List<Future<?>> fs = new ArrayList<>(threads - 1);
        final int per = (cnt + threads - 1) / threads;
        for (int t = 1; t < threads; t++) {
            final int lo = t * per, hi = Math.min(cnt, lo + per), wk = t;
            if (lo >= hi) break;
            fs.add(pool.submit((Callable<Void>) () -> { for (int i = lo; i < hi; i++) encode(works[wk], pcm, first + i, enc, i * HID); return null; }));
        }
        for (int i = 0, hi = Math.min(cnt, per); i < hi; i++) encode(works[0], pcm, first + i, enc, i * HID);
        try {
            for (Future<?> f : fs) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /** STFT + 4 convoluções de um bloco (independente de todos os outros); grava 128 valores em out[off..] */
    private void encode(Work w, short[] pcm, int frame, float[] out, int off) {
        final float[] x = w.x;
        final int base = frame * CH - CTX;
        for (int j = 0; j < IN; j++) { int k = base + j; x[j] = k >= 0 ? pcm[k] * INV : 0f; }
        for (int m = 0; m < 64; m++) x[IN + m] = x[IN - 2 - m];   // reflexão à direita (sem repetir a borda)

        // STFT: 129 reais + 129 imaginários (a janela está nos pesos), 4 quadros de 256 com salto de 128; módulo
        final float[] s = w.s, bs = basis;
        for (int k = 0; k < BINS; k++) {
            float r0 = 0, r1 = 0, r2 = 0, r3 = 0, i0 = 0, i1 = 0, i2 = 0, i3 = 0;
            final int ro = k * 256, io = (BINS + k) * 256;
            for (int n = 0; n < 256; n++) {
                final float wr = bs[ro + n], wi = bs[io + n];
                final float x0 = x[n], x1 = x[n + 128], x2 = x[n + 256], x3 = x[n + 384];
                r0 += wr * x0; r1 += wr * x1; r2 += wr * x2; r3 += wr * x3;
                i0 += wi * x0; i1 += wi * x1; i2 += wi * x2; i3 += wi * x3;
            }
            s[BINS + k] = (float) Math.sqrt(r0 * r0 + i0 * i0);
            s[2 * BINS + k] = (float) Math.sqrt(r1 * r1 + i1 * i1);
            s[3 * BINS + k] = (float) Math.sqrt(r2 * r2 + i2 * i2);
            s[4 * BINS + k] = (float) Math.sqrt(r3 * r3 + i3 * i3);
        }
        // as linhas 0 e 5 (preenchimento) ficam zero: nunca são escritas

        // codificador 0: 129 -> 128, k3, passo 1, 4 passos no tempo; linha t+k do s alimenta a saída t com o tap k
        final float[] a0 = w.a0;
        for (int oc = 0; oc < 128; oc++) {
            float c0 = b0[oc], c1 = c0, c2 = c0, c3 = c0;
            for (int k = 0; k < 3; k++) {
                final int wo = (oc * 3 + k) * BINS, so = k * BINS;
                float t0 = 0, t1 = 0, t2 = 0, t3 = 0;
                for (int ic = 0; ic < BINS; ic++) {
                    final float wv = w0[wo + ic];
                    t0 += wv * s[so + ic]; t1 += wv * s[so + BINS + ic]; t2 += wv * s[so + 2 * BINS + ic]; t3 += wv * s[so + 3 * BINS + ic];
                }
                c0 += t0; c1 += t1; c2 += t2; c3 += t3;
            }
            a0[128 + oc] = c0 > 0 ? c0 : 0; a0[256 + oc] = c1 > 0 ? c1 : 0; a0[384 + oc] = c2 > 0 ? c2 : 0; a0[512 + oc] = c3 > 0 ? c3 : 0;
        }
        // codificador 1: 128 -> 64, k3, passo 2: a saída 0 usa as linhas 0..2 de a0, a saída 1 as linhas 2..4
        final float[] a1 = w.a1;
        for (int oc = 0; oc < 64; oc++) {
            float c0 = b1[oc], c1 = c0;
            for (int k = 0; k < 3; k++) {
                final int wo = (oc * 3 + k) * 128, ro0 = k * 128, ro1 = (k + 2) * 128;
                float t0 = 0, t1 = 0;
                for (int ic = 0; ic < 128; ic++) {
                    final float wv = w1[wo + ic];
                    t0 += wv * a0[ro0 + ic]; t1 += wv * a0[ro1 + ic];
                }
                c0 += t0; c1 += t1;
            }
            a1[64 + oc] = c0 > 0 ? c0 : 0; a1[128 + oc] = c1 > 0 ? c1 : 0;
        }
        // codificador 2: 64 -> 64, k3, passo 2, 2 passos viram 1: só os taps 1 e 2 olham dados (o tap 0 cai no preenchimento)
        final float[] a2 = w.a2;
        for (int oc = 0; oc < 64; oc++) {
            float t1 = 0, t2 = 0;
            final int wo1 = (oc * 3 + 1) * 64, wo2 = (oc * 3 + 2) * 64;
            for (int ic = 0; ic < 64; ic++) { t1 += w2[wo1 + ic] * a1[64 + ic]; t2 += w2[wo2 + ic] * a1[128 + ic]; }
            final float v = b2[oc] + t1 + t2;
            a2[oc] = v > 0 ? v : 0;
        }
        // codificador 3: 64 -> 128, k3, passo 1, 1 passo no tempo: só o tap do meio olha dados
        for (int oc = 0; oc < 128; oc++) {
            float t = 0;
            final int wo = (oc * 3 + 1) * 64;
            for (int ic = 0; ic < 64; ic++) t += w3[wo + ic] * a2[ic];
            final float v = b3[oc] + t;
            out[off + oc] = v > 0 ? v : 0;
        }
    }

    private static float sigmoid(float v) { return (float) (1.0 / (1.0 + Math.exp(-v))); }

    /** um passo do LSTM (portas do PyTorch: i, f, g, o) + ReLU + conv 1x1 + sigmoide */
    private float lstmStep(float[] enc, int eo, float[] h, float[] c, float[] g) {
        for (int r = 0; r < 4 * HID; r += 4) {
            float s0 = bl[r], s1 = bl[r + 1], s2 = bl[r + 2], s3 = bl[r + 3];
            final int o0 = r * HID, o1 = o0 + HID, o2 = o1 + HID, o3 = o2 + HID;
            for (int j = 0; j < HID; j++) {
                final float e = enc[eo + j], hv = h[j];
                s0 += wih[o0 + j] * e + whh[o0 + j] * hv;
                s1 += wih[o1 + j] * e + whh[o1 + j] * hv;
                s2 += wih[o2 + j] * e + whh[o2 + j] * hv;
                s3 += wih[o3 + j] * e + whh[o3 + j] * hv;
            }
            g[r] = s0; g[r + 1] = s1; g[r + 2] = s2; g[r + 3] = s3;
        }
        float y = db;
        for (int j = 0; j < HID; j++) {
            final float ig = sigmoid(g[j]), fg = sigmoid(g[HID + j]), gg = (float) Math.tanh(g[2 * HID + j]), og = sigmoid(g[3 * HID + j]);
            final float cn = fg * c[j] + ig * gg;
            c[j] = cn;
            final float hn = og * (float) Math.tanh(cn);
            h[j] = hn;
            if (hn > 0) y += hn * dw[j];
        }
        return sigmoid(y);
    }

    /** 31,25 Hz -> 100 Hz por interpolação linear (np.interp: fora do intervalo vale o extremo); igual ao {@link SileroVad} */
    static float[] to100Hz(float[] probs, int n) {
        int nch = probs.length, n10 = n / 160;
        float[] out = new float[n10];
        if (nch == 0) return out;
        for (int j = 0; j < n10; j++) {
            double t = (j * 0.01) / 0.032 - 0.5;   // posição em "índices de bloco" (o centro do bloco i é i + 0,5)
            if (t <= 0) out[j] = probs[0];
            else if (t >= nch - 1) out[j] = probs[nch - 1];
            else { int a = (int) t; double f = t - a; out[j] = (float) (probs[a] * (1 - f) + probs[a + 1] * f); }
            out[j] = Math.min(1f, Math.max(0f, out[j]));
        }
        return out;
    }

    @Override public float[] speechProb(short[] pcm, int n, int rate) {
        if (rate != 16000) throw new IllegalArgumentException("só 16 kHz");
        return to100Hz(frameProbs(pcm, n), n);
    }
}
