import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Pre-escolha do material da prova (PC): roda o MESMO motor (Silero via ONNX Runtime Java, 3x90 s por densidade) numa legenda contra o audio do trecho.
 *  Uso: PreCheck audio16k.wav legenda.srt silero_vad.onnx -> "PRE accepted=1 raw=... scale=... z=... wins=a/b" */
public class PreCheck {
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
        try (SileroVad v = new SileroVad(a[2])) {
            for (long[] w : plan) {
                int s0 = (int) (w[0] * 16), n = (int) (w[1] * 16);
                short[] sl = Arrays.copyOfRange(pcm, s0, Math.min(pcm.length, s0 + n));
                ses.addWindow(new Window(w[0], v.speechProb(sl, sl.length, 16000)));
            }
        }
        Result r = ses.solve();
        System.out.printf(Locale.ROOT, "PRE accepted=%d raw=%.0f scale=%.5f z=%.1f wins=%d/%d inSync=%d janelas=%s%n", r.accepted ? 1 : 0, r.rawOffsetMs, r.scale, r.z, r.winsAgree, r.winsValid, r.inSync ? 1 : 0, Arrays.deepToString(plan));
    }
}
