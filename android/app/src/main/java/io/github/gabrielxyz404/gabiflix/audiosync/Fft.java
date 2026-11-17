package io.github.gabrielxyz404.gabiflix.audiosync;

/** FFT complex radix-2 iterativa, in-place. Sem dependências (porta 1:1 para Swift). */
public final class Fft {
    private final int n;
    private final double[] cos, sin;
    private final int[] rev;

    public Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) throw new IllegalArgumentException("n deve ser potência de 2");
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double a = -2.0 * Math.PI * i / n;
            cos[i] = Math.cos(a);
            sin[i] = Math.sin(a);
        }
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    public int size() { return n; }

    /** transformada direta (inverse=false) ou inversa SEM normalização 1/n (inverse=true) */
    public void transform(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    double wr = cos[k], wi = inverse ? -sin[k] : sin[k];
                    int a = i + j, b = a + half;
                    double xr = re[b] * wr - im[b] * wi;
                    double xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                }
            }
        }
    }

    public static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }
}
