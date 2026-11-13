package io.github.gabrielxyz404.gabiflix;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

/** Testes JVM da fase 3: VAD + alinhamento por áudio com áudio sintético (ruído + música + "fala" em rajadas nos tempos da legenda). */
public class AudioTest {
    static final int RATE = 8000;

    /** legenda "verdadeira" em tempo de vídeo: grupos de falas (diálogo) com pausas longas entre cenas */
    static ArrayList<long[]> makeCues(Random r, long durMs) {
        ArrayList<long[]> c = new ArrayList<>();
        long t = 20000 + r.nextInt(30000);
        while (t < durMs - 30000) {
            int scene = 3 + r.nextInt(14);
            for (int i = 0; i < scene && t < durMs - 10000; i++) {
                long len = 900 + r.nextInt(3500);
                c.add(new long[]{t, t + len});
                t += len + 250 + r.nextInt(2500);
            }
            t += 5000 + r.nextInt(70000); // cena de ação/música sem diálogo
        }
        return c;
    }

    static String srt(ArrayList<long[]> cues, double k, long off) { // tempo na legenda = (v - off) / k
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (long[] c : cues) {
            long a = Math.round((c[0] - off) / k), b = Math.round((c[1] - off) / k);
            if (a < 0) continue;
            sb.append(i++).append('\n').append(ts(a)).append(" --> ").append(ts(b)).append("\nfala ").append(i).append("\n\n");
        }
        return sb.toString();
    }

    static String ts(long n) { return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", n / 3600000, (n / 60000) % 60, (n / 1000) % 60, n % 1000); }

    /** áudio sintético do trecho [t0, t0+len) do filme: fundo (música + ruído + efeitos) + fala nas falas verdadeiras (deslocadas por pequena folga) */
    static float[] audio(Random r, ArrayList<long[]> cues, long t0, long lenMs, double musicAmp, double speechAmp, boolean speech) {
        int n = (int) (lenMs * RATE / 1000);
        float[] x = new float[n];
        // música: acordes que mudam a cada 2-4 s, com batida
        double[] fr = new double[4];
        long nextChord = 0;
        double ph = 0;
        for (int i = 0; i < n; i++) {
            long tm = t0 + i * 1000L / RATE;
            if (tm >= nextChord) { double root = 110 * Math.pow(2, r.nextInt(12) / 12.0); fr[0] = root; fr[1] = root * 1.26; fr[2] = root * 1.5; fr[3] = root * 2; nextChord = tm + 2000 + r.nextInt(2000); }
            double v = 0;
            for (double f : fr) v += Math.sin(2 * Math.PI * f * i / RATE + f);
            double beat = Math.pow(Math.max(0, Math.sin(2 * Math.PI * (tm % 500) / 500.0 * 0.5)), 6);
            x[i] = (float) (musicAmp * v / 4 * (0.6 + 0.4 * beat) + 0.01 * (r.nextDouble() - 0.5));
        }
        // efeitos: rajadas de ruído largas
        for (int e = 0; e < lenMs / 4000; e++) if (r.nextInt(3) == 0) {
            int a = r.nextInt(n), len = RATE / 2 + r.nextInt(RATE);
            for (int i = a; i < Math.min(n, a + len); i++) x[i] += (float) (0.25 * (r.nextDouble() - 0.5) * Math.exp(-(i - a) / (double) len * 3));
        }
        if (!speech) return x;
        for (long[] c : cues) {
            long a = c[0] + 100 + r.nextInt(150), b = c[1] - 150 - r.nextInt(150);
            if (b - a < 300 || b < t0 || a > t0 + lenMs) continue;
            double f0 = 110 + r.nextInt(120);
            double syll = 0, w = 0;
            for (long tm = Math.max(a, t0); tm < Math.min(b, t0 + lenMs); tm++) {
                int i0 = (int) ((tm - t0) * RATE / 1000);
                double ts = (tm - a) / 1000.0;
                double env = 0.5 * (1 + Math.sin(2 * Math.PI * (4 + 2 * Math.sin(ts)) * ts)); // sílabas
                if (((tm - a) / 350) % 5 == 4) env *= 0.05; // pausa entre palavras
                double f = f0 * (1 + 0.15 * Math.sin(ts * 3));
                for (int j = 0; j < RATE / 1000 && i0 + j < n; j++) {
                    double v = 0;
                    for (int h = 1; h <= 18; h++) {
                        double fh = f * h;
                        double form = Math.exp(-Math.pow((fh - 600) / 400, 2)) + 0.7 * Math.exp(-Math.pow((fh - 1700) / 500, 2)) + 0.4 * Math.exp(-Math.pow((fh - 2700) / 500, 2));
                        v += form / h * Math.sin(2 * Math.PI * fh * (i0 + j) / RATE);
                    }
                    x[i0 + j] += (float) (speechAmp * env * v * 0.5);
                }
            }
        }
        return x;
    }

    static AudioAlign.Win win(Random r, ArrayList<long[]> truth, long t0, long len, double music, double speechAmp) {
        float[] x = audio(r, truth, t0, len, music, speechAmp, true);
        byte[] v = AudioAlign.vad(x, RATE);
        return new AudioAlign.Win(t0, v, AudioAlign.lastSoft);
    }


    static final long WLEN = 15000;

    /** mesma lógica do AudioSyncRunner: 6 janelas; sem pico claro, mais 3 */
    static AudioAlign.Result pipeline(Random r, ArrayList<long[]> truth, AudioAlign.Spans sp, long dur, double music, StringBuilder log) {
        long[] starts = AudioAlign.planWindows(sp, dur, WLEN, 6);
        ArrayList<AudioAlign.Win> ws = new ArrayList<>();
        for (long t : starts) ws.add(win(r, truth, t, WLEN, music, 0.5));
        AudioAlign.Result res = AudioAlign.align(ws, sp, log);
        if (res.accepted) return res;
        long[] taken = starts;
        long[] ex = AudioAlign.planWindows(sp, dur, WLEN, java.util.Arrays.copyOf(AudioAlign.EXTRA_FRACTIONS, 3), taken);
        for (long t : ex) ws.add(win(r, truth, t, WLEN, music, 0.5));
        return AudioAlign.align(ws, sp, log);
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "OK    " : "FALHA ") + name + " " + detail);
        if (ok) pass++; else fail++;
    }

    /** um ensaio: legenda no arquivo = verdade deslocada por (k, off); recupera? */
    static boolean trial(long seed, double k, long off, double music, double speechAmp, long durMs, boolean verbose) {
        Random r = new Random(seed);
        ArrayList<long[]> truth = makeCues(r, durMs);
        String sub = srt(truth, k, off);
        AudioAlign.Spans sp = AudioAlign.spans(sub);
        StringBuilder log = new StringBuilder();
        AudioAlign.Result res = pipeline(r, truth, sp, durMs, music, log);
        if (verbose) System.out.print(log);
        boolean ok = res.accepted && Math.abs(res.offsetMs - off) <= 300 && Math.abs(res.scale - k) < 1e-6;
        System.out.println(String.format(Locale.ROOT, "  seed %d k=%.4f off=%+d -> %s (%s)", seed, k, off, res.accepted ? "aceita " + res.offsetMs + " k=" + res.scale : "recusada", res.summary()));
        return ok;
    }

    public static void main(String[] a) {
        long dur = 5400000;
        double[][] cases = {{1, 0}, {1, 7000}, {1, -45000}, {1, 150000}, {1, -280000}, {25 / 23.976, 15000}, {23.976 / 25, -30000}, {24 / 23.976, 3000}, {1.0, 0}};
        for (double music : new double[]{0.08, 0.2}) {
            for (double[] c : cases) {
                int okc = 0, tot = 6;
                for (int s = 1; s <= tot; s++) if (trial(s * 101 + (long) (c[1] / 1000), c[0], (long) c[1], music, 0.5, dur, false)) okc++;
                check(String.format(Locale.ROOT, "recupera k=%.4f off=%+d música=%.2f", c[0], (long) c[1], music), okc >= tot - 1, okc + "/" + tot);
            }
        }
        // controles negativos: legenda de outro filme (cues independentes) e áudio só de música
        for (double music : new double[]{0.08, 0.2}) {
            int wrong = 0, tot = 40;
            for (int s = 1; s <= tot; s++) {
                Random r = new Random(9000 + s);
                ArrayList<long[]> truth = makeCues(r, dur);
                ArrayList<long[]> other = makeCues(new Random(777 + s), dur);
                AudioAlign.Spans sp = AudioAlign.spans(srt(other, 1.0, 0));
                AudioAlign.Result res = pipeline(r, truth, sp, dur, music, new StringBuilder());
                if (res.accepted) { wrong++; System.out.println("  aceitou legenda de outro filme: seed " + s + " " + res.summary()); }
            }
            check("legenda de outro filme -> desiste (música " + music + ")", wrong == 0, "aceitou " + wrong + "/" + tot);
        }
        {
            int wrong = 0, tot = 8;
            for (int s = 1; s <= tot; s++) {
                Random r = new Random(5000 + s);
                ArrayList<long[]> truth = makeCues(r, dur);
                AudioAlign.Spans sp = AudioAlign.spans(srt(truth, 1.0, 7000));
                long[] starts = AudioAlign.planWindows(sp, dur, 15000, 6);
                ArrayList<AudioAlign.Win> ws = new ArrayList<>();
                for (long t : starts) ws.add(new AudioAlign.Win(t, AudioAlign.vad(audio(r, truth, t, 15000, 0.3, 0.5, false), RATE)));
                if (AudioAlign.align(ws, sp, new StringBuilder()).accepted) wrong++;
            }
            check("áudio sem fala -> desiste", wrong == 0, "aceitou " + wrong + "/" + tot);
        }
        // corte: primeira metade +5 s, segunda +25 s
        {
            int ok = 0, tot = 6;
            for (int s = 1; s <= tot; s++) {
                Random r = new Random(300 + s);
                ArrayList<long[]> truth = makeCues(r, dur);
                // legenda no arquivo: até o meio com off 5 s, depois off 25 s (tempo vídeo = sub + off)
                StringBuilder sb = new StringBuilder();
                int i = 1;
                for (long[] c : truth) {
                    long o = c[0] < dur / 2 ? 5000 : 25000;
                    long aa = c[0] - o, bb = c[1] - o;
                    if (aa < 0) continue;
                    sb.append(i++).append('\n').append(ts(aa)).append(" --> ").append(ts(bb)).append("\nfala\n\n");
                }
                AudioAlign.Spans sp = AudioAlign.spans(sb.toString());
                StringBuilder log = new StringBuilder();
                AudioAlign.Result res = pipeline(r, truth, sp, dur, 0.12, log);
                boolean good = res.accepted && res.pieced && Math.abs(res.fit.offs[0] - 5000) <= 300 && Math.abs(res.fit.offs[1] - 25000) <= 300;
                System.out.println("  corte seed " + s + ": " + res.summary() + (res.pieced ? " corte em " + res.cutAtMs : ""));
                if (good) ok++;
            }
            check("corte (+5 s / +25 s) -> por partes", ok >= tot - 1, ok + "/" + tot);
        }
        System.out.println("\nResumo: " + pass + " ok, " + fail + " falha(s)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
