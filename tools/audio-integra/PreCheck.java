import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Pre-escolha do material da prova (PC): roda o MESMO motor (Silero em Java puro, 3x90 s por densidade) numa legenda contra o audio do trecho.
 *  Uso: PreCheck audio16k.wav legenda.srt silero_vad.onnx -> "PRE accepted=1 raw=... scale=... z=... wins=a/b ... vad=java ms=..."
 *  O VAD e o do app (JavaSileroVad, pesos em silero_v5_16k.bin ao lado do .onnx, ou em SILERO_WEIGHTS). Com VAD=ort (so para comparar) usa o ONNX Runtime
 *  de referencia (SileroOrt, tools/silero-java; precisa estar no classpath junto com o ort.jar); com VAD=dsp, o VAD por DSP (ultimo recurso do app). */
public class PreCheck {
    /** analisa uma janela {inicioMs, duracaoMs} do audio com o VAD (o que o app faz depois de capturar a janela) */
    static Window analisa(Vad v, short[] pcm, long[] w) {
        int s0 = (int) Math.min(pcm.length, w[0] * 16), n = (int) (w[1] * 16);
        short[] sl = Arrays.copyOfRange(pcm, s0, Math.min(pcm.length, s0 + n));
        return new Window(w[0], v.speechProb(sl, sl.length, 16000));
    }

    public static void main(String[] a) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(a[0]));
        int p = 12; short[] pcm = new short[0];
        while (p + 8 <= b.length) {
            long sz = (b[p + 4] & 0xffL) | (b[p + 5] & 0xffL) << 8 | (b[p + 6] & 0xffL) << 16 | (b[p + 7] & 0xffL) << 24;
            boolean data = b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a';
            p += 8;
            if (data) { int n = (int) Math.min((b.length - p) / 2, sz / 2); pcm = new short[n]; for (int i = 0; i < n; i++) pcm[i] = (short) ((b[p + 2 * i] & 0xff) | (b[p + 2 * i + 1] << 8)); break; }
            p += (int) sz;
        }
        long dur = pcm.length / 16L;
        Cue[] cues = Cue.parseSrt(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
        AudioAligner.Params P = new AudioAligner.Params();
        long[][] plan = AudioSyncSession.planDense(cues, dur, 3, 90000);
        AudioSyncSession ses = new AudioSyncSession(cues, dur, P);
        Vad v;
        if ("ort".equals(System.getenv("VAD"))) v = (Vad) Class.forName("SileroOrt").getConstructor(String.class).newInstance(a[2]);
        else if ("dsp".equals(System.getenv("VAD"))) v = DspVad.forName("dsp");   // o ultimo recurso do app (modelo de reserva embutido)
        else {
            String wp = System.getenv("SILERO_WEIGHTS");
            Path wf = wp != null && !wp.isEmpty() ? Paths.get(wp) : Paths.get(a[2]).resolveSibling("silero_v5_16k.bin");
            v = new JavaSileroVad(Files.readAllBytes(wf), Integer.parseInt(System.getenv().getOrDefault("VAD_THREADS", "1")), null);
        }
        long t0 = System.nanoTime();
        Result r;
        try {
            if ("1".equals(System.getenv("SPREAD"))) {
                // o laco do app (AudioSyncSession.analyze): 3 janelas espalhadas + ate 2 extras se ha suspeita de deriva
                final short[] pc = pcm; final Vad vv = v;
                Window[][] used = new Window[1][];
                r = AudioSyncSession.analyze(cues, dur, 6, 90000, (st, ln, i, total) -> analisa(vv, pc, new long[]{st, ln}), P, true, 3, used);
                StringBuilder js = new StringBuilder();
                for (Window w : used[0]) js.append("[").append(w.startMs).append("],");
                System.out.printf(Locale.ROOT, "SPREAD accepted=%d scale=%.6f raw=%.0f drift=%d confiaveis=%d janelas=%d inSync=%d z=%.1f res=%.0f unitRes=%.0f planB=%d consenso=%d motivo=%s at=%s%n", r.accepted ? 1 : 0, r.scale, r.rawOffsetMs, r.driftScale ? 1 : 0, r.driftConfident, used[0].length, r.inSync ? 1 : 0, r.z, r.driftResMs, r.unitResMs, r.planB ? 1 : 0, r.winConsensus ? 1 : 0, r.reason.trim().replace(' ', '_'), js);
                return;
            }
            for (long[] w : plan) ses.addWindow(analisa(v, pcm, w));
            r = ses.solve();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String vn = v.name() + (v instanceof JavaSileroVad ? "-java" : v instanceof DspVad ? "" : "-ort");
            System.out.printf(Locale.ROOT, "PRE accepted=%d raw=%.0f scale=%.5f z=%.1f wins=%d/%d inSync=%d vad=%s ms=%d margem=%.2f ncc=%.2f f1=%.2f blocos=%d/%d desvioMax=%.0f motivo=%s janelas=%s%n", r.accepted ? 1 : 0, r.rawOffsetMs, r.scale, r.z, r.winsAgree, r.winsValid, r.inSync ? 1 : 0, vn, ms, r.margin, r.nccMean, r.f1, r.blocksAgree, r.blocksValid, r.winMaxDev, r.reason.replace(' ', '_'), Arrays.deepToString(plan));
            // hipotese (o AudioSyncCore de hoje resolve uma vez so, com as 3 janelas): se o motor pedisse mais janelas (ate MAX_ROUNDS rodadas), analisa e resolve de novo
            while (r.more != null && r.more.length > 0) {
                for (long[] w : r.more) ses.addWindow(analisa(v, pcm, w));
                r = ses.solve();
            }
            System.out.printf(Locale.ROOT, "FULL accepted=%d raw=%.0f scale=%.5f z=%.1f wins=%d/%d inSync=%d rodadas=%d janelas=%d vad=%s ms=%d margem=%.2f ncc=%.2f f1=%.2f blocos=%d/%d desvioMax=%.0f motivo=%s%n", r.accepted ? 1 : 0, r.rawOffsetMs, r.scale, r.z, r.winsAgree, r.winsValid, r.inSync ? 1 : 0, ses.rounds(), ses.windows(), vn, (System.nanoTime() - t0) / 1_000_000, r.margin, r.nccMean, r.f1, r.blocksAgree, r.blocksValid, r.winMaxDev, r.reason.replace(' ', '_'));
        } finally {
            if (v instanceof AutoCloseable) ((AutoCloseable) v).close();
        }
    }
}
