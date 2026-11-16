import io.github.gabrielxyz404.gabiflix.audiosync.SileroVad;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** PC: roda o SileroVad Java num WAV 16 kHz mono e compara com a referência Python (float32 100 Hz). Uso: SileroParity modelo.onnx audio.wav ref.f32 */
public class SileroParity {
    public static void main(String[] a) throws Exception {
        byte[] w = Files.readAllBytes(Paths.get(a[1]));
        int p = 12, off = -1, len = 0;
        while (p + 8 <= w.length) {
            int sz = ByteBuffer.wrap(w, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (w[p] == 'd' && w[p + 1] == 'a' && w[p + 2] == 't' && w[p + 3] == 'a') { off = p + 8; len = Math.min(sz, w.length - off); break; }
            p += 8 + sz + (sz & 1);
        }
        short[] pcm = new short[len / 2];
        ByteBuffer.wrap(w, off, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        try (SileroVad v = new SileroVad(a[0])) {
            long t0 = System.nanoTime();
            float[] j = v.speechProb(pcm, pcm.length, 16000);
            double s = (System.nanoTime() - t0) / 1e9;
            FloatBuffer fb = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[2]))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] r = new float[fb.remaining()];
            fb.get(r);
            int n = Math.min(r.length, j.length);
            double max = 0, sum = 0; int dis = 0, sp = 0;
            for (int i = 0; i < n; i++) { double d = Math.abs(r[i] - j[i]); max = Math.max(max, d); sum += d; if ((r[i] > 0.5) != (j[i] > 0.5)) dis++; if (r[i] > 0.5) sp++; }
            System.out.printf("SILERO-JAVA-PC frames=%d (ref %d) audio=%.1fs tempo=%.2fs (%.0fx) maxdiff=%.5f meandiff=%.6f discordam>0.5=%d de %d falantes=%d%n", j.length, r.length, pcm.length / 16000.0, s, pcm.length / 16000.0 / s, max, sum / n, dis, n, sp);
        }
    }
}
