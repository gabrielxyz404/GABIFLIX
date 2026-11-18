import io.github.gabrielxyz404.gabiflix.audiosync.JavaSileroVad;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Ferramenta de prova do Silero em Java puro (roda no PC/CI, compila junto com audiosync/JavaSileroVad.java).
 *   SileroParity probs  pesos.bin audio16k.wav saida-bloco.f32 saida-100hz.f32 [fios]   probabilidades por bloco de 32 ms e a versão de 100 Hz
 *   SileroParity bench  pesos.bin audio16k.wav fios repeticoes                           tempo de análise (aquece o JIT e mede)
 */
public class SileroParity {
    static short[] readWav(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        int p = 12;
        while (p + 8 <= b.length) {
            long sz = (b[p + 4] & 0xffL) | (b[p + 5] & 0xffL) << 8 | (b[p + 6] & 0xffL) << 16 | (b[p + 7] & 0xffL) << 24;
            boolean data = b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a';
            p += 8;
            if (data) {
                int n = (int) Math.min((b.length - p) / 2, sz / 2);
                short[] pcm = new short[n];
                for (int i = 0; i < n; i++) pcm[i] = (short) ((b[p + 2 * i] & 0xff) | (b[p + 2 * i + 1] << 8));
                return pcm;
            }
            p += (int) sz;
        }
        throw new IOException("WAV sem bloco data");
    }

    static void writeF32(String path, float[] a) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(a);
        Files.write(Paths.get(path), bb.array());
    }

    public static void main(String[] a) throws Exception {
        byte[] w = Files.readAllBytes(Paths.get(a[1]));
        short[] pcm = readWav(a[2]);
        if (a[0].equals("probs")) {
            int th = a.length > 5 ? Integer.parseInt(a[5]) : 1;
            try (JavaSileroVad v = new JavaSileroVad(w, th, null)) {
                float[] raw = v.frameProbs(pcm, pcm.length);
                writeF32(a[3], raw);
                writeF32(a[4], v.speechProb(pcm, pcm.length, 16000));
                System.out.println("blocos " + raw.length + " (fios " + th + ")");
            }
        } else if (a[0].equals("bench")) {
            int th = Integer.parseInt(a[3]), reps = Integer.parseInt(a[4]);
            try (JavaSileroVad v = new JavaSileroVad(w, th, null)) {
                double audioS = pcm.length / 16000.0;
                for (int r = 0; r < reps; r++) {
                    long t0 = System.nanoTime();
                    float[] p = v.speechProb(pcm, pcm.length, 16000);
                    double s = (System.nanoTime() - t0) / 1e9;
                    System.out.printf(Locale.ROOT, "BENCH fios=%d rep=%d audio=%.1fs tempo=%.2fs fator=%.1fx tempo-real (n=%d)%n", th, r, audioS, s, audioS / s, p.length);
                }
            }
        }
    }
}
