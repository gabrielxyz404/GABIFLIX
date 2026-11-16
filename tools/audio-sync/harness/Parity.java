import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;

/** Confere a porta Java do VAD do WebRTC contra o py-webrtcvad (mesmo código C): os primeiros 120 s do filme, os 4 modos, partindo do estado inicial. */
public class Parity {
    public static void main(String[] a) throws Exception {
        Film f = new Film(new File(a[0]));
        long dur = Math.min(120000, f.durationMs);
        short[] pcm = f.pcm(0, dur);
        for (int mode = 0; mode < 4; mode++) {
            float[] ref = f.track("vad_webrtc_m" + mode, 0, dur);
            if (ref == null) { System.out.println(f.slug + " m" + mode + " sem trilha"); continue; }
            float[] j = new WebRtcVad(mode).speechProb(pcm, pcm.length, 16000);
            int n = Math.min(ref.length, j.length), diff = 0;
            for (int i = 0; i < n; i++) if ((ref[i] > 0.5f) != (j[i] > 0.5f)) diff++;
            System.out.printf("PARIDADE %s modo %d: %d quadros, %d diferentes (%.4f%%)%n", f.slug, mode, n, diff, 100.0 * diff / n);
        }
    }
}
