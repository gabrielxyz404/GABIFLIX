package io.github.gabrielxyz404.gabiflix.audiosync;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Silero VAD (modelo ONNX v5, 2,3 MB) via ONNX Runtime (a mesma API Java no PC e no Android: onnxruntime / onnxruntime-android).
 * Janelas de 512 amostras (32 ms) com contexto de 64 amostras e estado (2,1,128); a saída (31,25 Hz) é interpolada para 100 Hz exatamente como
 * o corpus faz em Python (corpus.py, vad_tracks), de modo que os números da avaliação valem para este código.
 * Uma instância por thread (guarda a sessão, mas o estado é local a cada chamada de speechProb).
 */
public final class SileroVad implements Vad, AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inName = "input", stName = "state", srName = "sr";

    public SileroVad(String modelPath) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(1);
        so.setInterOpNumThreads(1);
        session = env.createSession(modelPath, so);
    }

    public SileroVad(byte[] model) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(1);
        so.setInterOpNumThreads(1);
        session = env.createSession(model, so);
    }

    @Override public String name() { return "silero"; }

    @Override public float[] speechProb(short[] pcm, int n, int rate) {
        if (rate != 16000) throw new IllegalArgumentException("só 16 kHz");
        int nch = n / 512;
        int n10 = n / 160;
        float[] probs = new float[nch];
        float[][][] state = new float[2][1][128];
        float[] ctx = new float[64];
        try {
            for (int i = 0; i < nch; i++) {
                float[][] inp = new float[1][576];
                System.arraycopy(ctx, 0, inp[0], 0, 64);
                for (int k = 0; k < 512; k++) inp[0][64 + k] = pcm[i * 512 + k] / 32768f;
                try (OnnxTensor ti = OnnxTensor.createTensor(env, inp);
                     OnnxTensor ts = OnnxTensor.createTensor(env, state);
                     OnnxTensor tr = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{16000}), new long[0])) {
                    Map<String, OnnxTensor> in = new HashMap<>();
                    in.put(inName, ti); in.put(stName, ts); in.put(srName, tr);
                    try (OrtSession.Result r = session.run(in)) {
                        probs[i] = ((float[][]) r.get(0).getValue())[0][0];
                        state = (float[][][]) r.get(1).getValue();
                    }
                }
                System.arraycopy(inp[0], 64 + 512 - 64, ctx, 0, 64);
            }
        } catch (OrtException e) {
            throw new RuntimeException(e);
        }
        // 31,25 Hz -> 100 Hz por interpolação linear (np.interp: fora do intervalo vale o extremo)
        float[] out = new float[n10];
        for (int j = 0; j < n10; j++) {
            double t = (j * 0.01) / 0.032 - 0.5;   // posição em "índices de quadro" (centro do quadro i = i + 0,5)
            if (nch == 0) break;
            if (t <= 0) out[j] = probs[0];
            else if (t >= nch - 1) out[j] = probs[nch - 1];
            else { int a = (int) t; double f = t - a; out[j] = (float) (probs[a] * (1 - f) + probs[a + 1] * f); }
            out[j] = Math.min(1f, Math.max(0f, out[j]));
        }
        return out;
    }

    @Override public void close() throws OrtException { session.close(); }
}
