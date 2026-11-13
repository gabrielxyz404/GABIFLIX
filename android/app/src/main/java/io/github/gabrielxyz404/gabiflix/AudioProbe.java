package io.github.gabrielxyz404.gabiflix;

import java.util.Locale;

/** Só nos testes: decodifica o vídeo "varredura" (um bipe por segundo, frequência = 400 + 100 * (segundo mod 28)) para saber de que instante do vídeo vem cada trecho capturado. */
final class AudioProbe {
    private AudioProbe() {}

    /** devolve texto com os primeiros/últimos bipes e o desvio (instante real - instante pedido) */
    static String describe(float[] x, int rate, long startMs) {
        int hop = rate / 100;
        int n = x.length / hop;
        double[] env = new double[n];
        double mx = 0;
        for (int i = 0; i < n; i++) {
            double e = 0;
            for (int k = 0; k < hop; k++) { double v = x[i * hop + k]; e += v * v; }
            env[i] = Math.sqrt(e / hop);
            mx = Math.max(mx, env[i]);
        }
        StringBuilder sb = new StringBuilder();
        int count = 0, first = -1;
        double firstDelta = 0, lastDelta = 0;
        long lastS = -1;
        int i = 0;
        StringBuilder list = new StringBuilder();
        while (i < n) {
            if (env[i] < 0.3 * mx) { i++; continue; }
            int j = i;
            while (j < n && env[j] >= 0.3 * mx) j++;
            if (j - i >= 8) {
                // frequência por cruzamentos de zero no miolo do bipe
                int a = i * hop + 2 * hop, b = Math.min(x.length, j * hop - hop);
                int cross = 0;
                for (int k = a + 1; k < b; k++) if ((x[k - 1] < 0) != (x[k] < 0)) cross++;
                double f = b > a ? cross / 2.0 / ((b - a) / (double) rate) : 0;
                int idx = (int) Math.round((f - 400) / 100.0);
                double tf = i * 0.01;
                if (idx >= 0 && idx < 28) {
                    // segundo absoluto: o que tem resto idx (mod 28) mais perto de (início pedido + tf)
                    double guess = startMs / 1000.0 + tf;
                    long s = Math.round((guess - idx) / 28.0) * 28 + idx;
                    double delta = s - guess;
                    if (count == 0) { first = idx; firstDelta = delta; }
                    lastDelta = delta;
                    lastS = s;
                    if (count < 40) list.append(String.format(Locale.ROOT, "%.2f:%d ", tf, idx));
                    count++;
                }
            }
            i = j;
        }
        sb.append(String.format(Locale.ROOT, "bipes=%d primeiro(idx=%d desvio=%.2fs) último(desvio=%.2fs, s=%d) duração=%.2fs :: %s", count, first, firstDelta, lastDelta, lastS, x.length / (double) rate, list));
        return sb.toString();
    }
}
