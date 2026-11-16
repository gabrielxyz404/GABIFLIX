import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** Teste sintético do alinhador (sem áudio real): fala = máscara das falas + ruído + trechos de "música". */
public class SelfTest {
    static Cue[] makeCues(Random r, long durMs) {
        ArrayList<Cue> l = new ArrayList<>();
        long t = 20000;
        while (t < durMs - 30000) {
            long d = 1000 + r.nextInt(3500);
            l.add(new Cue(t, t + d + 600, (int) (d / 70), true));
            t += d + 600 + 200 + (long) (-Math.log(1 - r.nextDouble()) * 4000);
        }
        return l.toArray(new Cue[0]);
    }

    /** VAD sintético: 100 Hz sobre a verdade (cues já na hora certa) */
    static float[] truthTrack(Cue[] truth, long durMs, Random r, double quality) {
        int n = (int) (durMs / 10);
        float[] p = new float[n];
        for (Cue c : truth) {
            long e = c.speechEnd(true);
            // falas reais têm pausas internas
            for (long t = c.startMs; t < e; t += 10) {
                int i = (int) (t / 10);
                if (i < n) p[i] = 1f;
            }
        }
        // música: 8% do tempo com p alto sem falas
        for (int k = 0; k < durMs / 120000; k++) {
            int a = r.nextInt(n - 6000);
            int len = 1500 + r.nextInt(4500);
            if (r.nextInt(3) == 0) for (int i = a; i < a + len && i < n; i++) p[i] = Math.max(p[i], 0.85f);
        }
        for (int i = 0; i < n; i++) {
            double v = p[i] * quality + (1 - quality) * r.nextDouble();
            v += r.nextGaussian() * 0.12;
            // erros de VAD: buracos e falsos positivos curtos
            p[i] = (float) Math.min(1, Math.max(0, v));
        }
        return p;
    }

    public static void main(String[] a) {
        Random r = new Random(7);
        long dur = 95 * 60000L;
        AudioAligner.Params P = new AudioAligner.Params();
        int ok = 0, tot = 0;
        double[][] cases = {
            {0, 1.0}, {500, 1.0}, {-1500, 1.0}, {7000, 1.0}, {-45000, 1.0}, {120000, 1.0}, {-300000, 1.0}, {590000, 1.0},
            {0, 25 / 23.976}, {0, 23.976 / 25}, {0, 25 / 24.0}, {0, 24 / 25.0}, {12000, 25 / 23.976}, {-30000, 23.976 / 25}, {3000, 1.001001}, {-2000, 0.999},
            {0, 1.0}};
        for (int rep = 0; rep < 3; rep++)
        for (double[] cs : cases) {
            Cue[] truth = makeCues(r, dur);
            float[] track = truthTrack(truth, dur, r, 0.75);
            // legenda distorcida: s = (u - b)/a
            double b = cs[0], k = cs[1];
            Cue[] dist = new Cue[truth.length];
            for (int i = 0; i < truth.length; i++) {
                Cue c = truth[i];
                dist[i] = new Cue(Math.round((c.startMs - b) / k), Math.round((c.endMs - b) / k), c.chars, true);
            }
            // janelas
            int nw = 3; long wl = 90000;
            Window[] ws = new Window[nw];
            double[] fr = {0.2, 0.5, 0.8};
            for (int w = 0; w < nw; w++) {
                long st = (long) (dur * fr[w]);
                int s0 = (int) (st / 10);
                ws[w] = new Window(st, Arrays.copyOfRange(track, s0, s0 + (int) (wl / 10)));
            }
            long t0 = System.nanoTime();
            Result res = AudioAligner.solve(dist, ws, dur, P);
            long ms = (System.nanoTime() - t0) / 1000000;
            // erro nos cues
            double[] err = new double[dist.length];
            for (int i = 0; i < dist.length; i++) err[i] = Math.abs(res.apply(dist[i].startMs) - truth[i].startMs);
            Arrays.sort(err);
            boolean good = res.accepted && err[err.length / 2] <= 250;
            tot++;
            if (good) ok++;
            System.out.printf("off=%8.0f k=%.5f -> %s medErr=%.0f p90=%.0f (%d ms)%n", b, k, good ? "OK  " : "FALHA", err[err.length / 2], err[(int) (err.length * 0.9)], ms);
            if (!good) System.out.println("     " + res);
        }
        // negativos: legenda de outro "filme"
        int fa = 0;
        for (int rep = 0; rep < 30; rep++) {
            Cue[] truth = makeCues(r, dur);
            float[] track = truthTrack(truth, dur, r, 0.75);
            Cue[] other = makeCues(r, dur);
            Window[] ws = new Window[3];
            double[] fr = {0.2, 0.5, 0.8};
            for (int w = 0; w < 3; w++) {
                long st = (long) (dur * fr[w]);
                int s0 = (int) (st / 10);
                ws[w] = new Window(st, Arrays.copyOfRange(track, s0, s0 + 9000));
            }
            Result res = AudioAligner.solve(other, ws, dur, P);
            if (res.accepted) { fa++; System.out.println("FALSO ACEITE " + res); }
        }
        System.out.println("positivos " + ok + "/" + tot + ", falsos aceites " + fa + "/30");
    }
}
