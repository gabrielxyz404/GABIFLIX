import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.util.*;

/** Gera os valores esperados (do motor Java do Android/TV) para o teste de paridade do Swift: plano de janelas, leitura de SRT e VAD por DSP. */
public class GenExpected {
    static String plan(String name, long[][] p) {
        StringBuilder sb = new StringBuilder("PLAN " + name + " =>");
        for (long[] w : p) sb.append(' ').append(w[0]).append(',').append(w[1]);
        return sb.toString();
    }

    static Cue[] genCues(int seed, int n, long step) {
        Random r = new Random(seed);
        Cue[] c = new Cue[n];
        for (int i = 0; i < n; i++) {
            long s = 20000 + i * step + r.nextInt(3000);
            long e = s + 1500 + r.nextInt(2500);
            int chars = 20 + r.nextInt(40);
            boolean sp = r.nextInt(10) != 0;
            c[i] = new Cue(s, e, chars, sp);
        }
        return c;
    }

    public static void main(String[] a) throws Exception {
        // 1) planos
        Cue[] c1 = genCues(1, 900, 6000);
        System.out.println(plan("dense-5700000", AudioSyncSession.planDense(c1, 5_700_000, 3, 90_000)));
        System.out.println(plan("dense-2400000", AudioSyncSession.planDense(c1, 2_400_000, 3, 90_000)));
        System.out.println(plan("uniform-5700000", AudioSyncSession.plan(5_700_000, 3, 90_000)));
        Cue[] c2 = genCues(2, 120, 40000);
        System.out.println(plan("dense-sparse-4800000", AudioSyncSession.planDense(c2, 4_800_000, 3, 90_000)));
        Cue[] c3 = genCues(3, 60, 4000);
        System.out.println(plan("dense-short-600000", AudioSyncSession.planDense(c3, 600_000, 3, 90_000)));
        // 2) SRT
        String srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Olá, tudo bem?</i>\r\n\r\n2\r\n00:00:04.250 --> 00:00:06,000 X1:0\r\n[música]\r\n\r\n"
                + "3\n00:00:07,5 --> 00:00:09,25\n{\\an8}Isso é um teste\nde duas linhas ♪\n\n4\n00:00:10,000 --> 00:00:09,000\nfala invertida\n\n"
                + "5\n01:02:03,004 --> 01:02:05,999\n(risos) Não sei ação 123\n\n6\n00:10:00,000 --> 00:10:02,000\n♪♪\n\n7\n00:11:00 --> 00:11:02\nsem milésimos\n\n8\nlixo --> lixo\nnada\n";
        for (Cue c : Cue.parseSrt(srt)) System.out.println("SRT " + c.startMs + "," + c.endMs + "," + c.chars + "," + (c.speech ? 1 : 0));
        // 3) DSP (modelo de reserva) num sinal sintético determinístico
        int n = 16000 * 20;
        short[] pcm = new short[n];
        Random r = new Random(7);
        for (int i = 0; i < n; i++) {
            double t = i / 16000.0;
            double on = (((int) (t / 1.5)) % 2 == 0) ? 1.0 : 0.0;
            double f0 = 120 * (1 + 0.1 * Math.sin(2 * Math.PI * 3 * t));
            double v = 0;
            for (int h = 1; h <= 8; h++) v += Math.sin(2 * Math.PI * f0 * h * t) / h;
            double env = 0.5 + 0.5 * Math.sin(2 * Math.PI * 4 * t);
            double x = on * env * v * 4000 + (r.nextGaussian() * 200);
            pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(x)));
        }
        long cs = 0;
        for (short s : pcm) cs = cs * 31 + s;
        System.out.println("DSPPCM checksum=" + cs);
        float[] p = DspVad.forName("dsp").speechProb(pcm, n, 16000);
        double mean = 0;
        for (float f : p) mean += f;
        StringBuilder sb = new StringBuilder("DSP n=" + p.length + " mean=" + String.format(Locale.ROOT, "%.6f", mean / p.length) + " p=");
        for (int i = 0; i < p.length; i += 97) sb.append(String.format(Locale.ROOT, "%.5f", p[i])).append(',');
        System.out.println(sb);
    }
}
