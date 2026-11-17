import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** Teste sintético do alinhador (sem áudio real): fala = máscara das falas + ruído + trechos de "música". */
public class SelfTest {
    public static Cue[] makeCues(Random r, long durMs) {
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
    public static float[] truthTrack(Cue[] truth, long durMs, Random r, double quality) {
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
            boolean nearUnit = Math.abs(k - 1.0) > 1e-9 && Math.abs(k - 1.0) < P.nearUnitEps;   // 0,999/1,001: recusada de propósito
            boolean good = nearUnit ? !res.accepted : res.accepted && (err[err.length / 2] <= 250 || (res.inSync && Math.abs(b) < P.deadbandMs));   // dentro da banda morta: não mexe
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
        // ajuste por partes: um corte (d positivo e negativo), com sessão de janelas extras
        int pok = 0, ptot = 0;
        double[] ds = {-40000, -15000, 5000, 15000, 40000};
        for (int rep = 0; rep < 2; rep++) for (double dd : ds) {
            Cue[] truth = makeCues(r, dur);
            float[] track = truthTrack(truth, dur, r, 0.75);
            long cut = (long) (dur * (0.35 + 0.3 * rep));
            double b0 = 4000, b1 = b0 + dd;
            ArrayList<Cue> dl = new ArrayList<>();
            ArrayList<Long> tl = new ArrayList<>();
            long firstAfter = -1;
            for (Cue c : truth) {
                boolean after = c.startMs >= cut;
                double b = after ? b1 : b0;
                if (after && firstAfter < 0) firstAfter = Math.round(c.startMs - b);
                dl.add(new Cue(Math.round(c.startMs - b), Math.round(c.endMs - b), c.chars, true));
                tl.add(c.startMs);
            }
            // remove da legenda as falas anteriores que passam do começo do trecho seguinte
            ArrayList<Cue> d2 = new ArrayList<>(); ArrayList<Long> t2 = new ArrayList<>();
            for (int i = 0; i < dl.size(); i++) if (tl.get(i) < cut && dl.get(i).startMs >= firstAfter - 1000) continue; else { d2.add(dl.get(i)); t2.add(tl.get(i)); }
            Cue[] dist = d2.toArray(new Cue[0]);
            ArrayList<Window> wl = new ArrayList<>();
            double[] fr = {0.2, 0.5, 0.8};
            for (double f : fr) { long st = (long) (dur * f); wl.add(new Window(st, Arrays.copyOfRange(track, (int) (st / 10), (int) (st / 10) + 6000))); }
            Result res = AudioAligner.solve(dist, wl.toArray(new Window[0]), dur, P);
            int rounds = 0;
            if (System.getProperty("dbg") != null) System.err.println("  ronda 0: " + res);
            while (res.more != null && res.more.length > 0 && rounds < 6) {
                for (long[] m : res.more) wl.add(new Window(m[0], Arrays.copyOfRange(track, (int) (m[0] / 10), (int) (m[0] / 10) + (int) (m[1] / 10))));
                res = AudioAligner.solve(dist, wl.toArray(new Window[0]), dur, P);
                rounds++;
                if (System.getProperty("dbg") != null) System.err.println("  ronda " + rounds + ": " + res);
            }
            int good = 0;
            for (int i = 0; i < dist.length; i++) if (Math.abs(res.applySegments(dist[i].startMs) - t2.get(i)) <= 250) good++;
            double frac = good / (double) dist.length;
            ptot++;
            if (res.cutsWarning && !res.accepted) pok++;   // cortes: só aviso (nada é aplicado)
            System.out.printf("PARTES d=%7.0f cut=%.0fs -> %s %.1f%% das falas certas, rodadas=%d, janelas=%d, segs=%s%n", dd, cut / 1000.0, res.accepted ? "aceita" : "RECUSADA", frac * 100, rounds, wl.size(), Arrays.toString(res.segments));
        }
        // reverificação: legenda certa (offset/escala conhecidos) -> 0 mudanças; corte injetado -> detecta
        int falseCh = 0, nChecks = 0, det = 0, nCut = 0;
        Recheck.Params RP = new Recheck.Params();
        for (int rep = 0; rep < 6; rep++) {
            Cue[] truth = makeCues(r, dur);
            float[] track = truthTrack(truth, dur, r, 0.75);
            double b0 = 3000 * (rep % 3), k0 = rep < 3 ? 1.0 : 25 / 23.976;
            Cue[] sub = new Cue[truth.length];
            for (int i = 0; i < truth.length; i++) { Cue c = truth[i]; sub[i] = new Cue(Math.round((c.startMs - b0) / k0), Math.round((c.endMs - b0) / k0), c.chars, true); }
            Result cur = new Result(); cur.accepted = true; cur.scale = k0; cur.offsetMs = b0;
            // (1) legenda certa
            Recheck.Out prev = null;
            for (long t = 300000; t + 90000 <= dur; t += 600000) {
                Window[] w = {new Window(t, Arrays.copyOfRange(track, (int) (t / 10), (int) (t / 10) + 9000))};
                Recheck.Out o = Recheck.check(sub, w, cur, t, dur, false, prev, P, RP);
                prev = o; nChecks++;
                if (o.verdict != Recheck.Verdict.KEEP) { falseCh++; System.out.println("MUDANCA FALSA t=" + t / 1000 + "s " + o.verdict + " " + o.reason); }
            }
            // (2) corte: depois de cut o filme tem d ms a mais/menos
            double d = rep % 2 == 0 ? 15000 : -40000;
            long cut = (long) (dur * 0.5);
            Cue[] sub2 = new Cue[truth.length];
            for (int i = 0; i < truth.length; i++) { Cue c = truth[i]; double bb = c.startMs >= cut ? b0 + d : b0; sub2[i] = new Cue(Math.round((c.startMs - bb) / k0), Math.round((c.endMs - bb) / k0), c.chars, true); }
            prev = null; Result c2 = cur; long first = -1; double newOff = 0; long applyFrom = 0;
            for (long t = 300000; t + 90000 <= dur; t += 600000) {
                Window[] w = {new Window(t, Arrays.copyOfRange(track, (int) (t / 10), (int) (t / 10) + 9000))};
                Recheck.Out o = Recheck.check(sub2, w, c2, t, dur, false, prev, P, RP);
                prev = o;
                if (t < cut && o.verdict != Recheck.Verdict.KEEP) { falseCh++; System.out.println("MUDANCA FALSA (antes do corte) t=" + t / 1000 + "s " + o.verdict + " " + o.reason); }
                if (System.getProperty("dbg") != null && t > cut) System.err.println("  t=" + t / 1000 + " " + o.verdict + " " + o.reason + " ncc=" + o.nccAtCurrent + " z=" + o.z);
                if (t > cut && first < 0 && o.verdict == Recheck.Verdict.CHANGE) { first = t; newOff = o.newOffsetMs; applyFrom = o.applyFromSubMs; }
            }
            nCut++;
            boolean good = first >= 0 && Math.abs(newOff - (b0 + d)) <= 300;
            if (good) det++;
            System.out.printf("RECHECK corte d=%.0f s: %s, 1a mudanca em t=%ds, novo=%.0f esperado=%.0f, aplica a partir de sub=%ds%n", d / 1000, good ? "OK" : "FALHA", first / 1000, newOff, b0 + d, applyFrom / 1000);
        }
        System.out.println("recheck: checagens " + nChecks + ", mudancas falsas " + falseCh + ", cortes detectados " + det + "/" + nCut);
        System.out.println("partes " + pok + "/" + ptot);
        System.out.println("positivos " + ok + "/" + tot + ", falsos aceites " + fa + "/30");
    }
}
