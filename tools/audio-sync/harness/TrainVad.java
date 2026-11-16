import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.util.*;

/**
 * Treina o MLP do DspVad com os quadros de FeatDump, validando por grupos de filmes (um modelo por grupo, treinado SEM os filmes do grupo)
 * e escreve um arquivo de pesos por filme (dspw/FILME.w) + all.w (todos os filmes). Também mede AUC/F1 por quadro do DSP, do Silero e do WebRTC.
 * Uso: TrainVad DUMP_DIR OUT_DIR [folds=5] [epochs=8]
 */
public class TrainVad {
    static final int NF = DspVad.NF, REC = NF + 1 + 2;

    static final class Data { String slug; float[][] x; byte[] y; float[] sil, wr; }

    static Data load(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20))) {
            int n = in.readInt();
            Data d = new Data();
            d.slug = f.getName().replace(".feat", "");
            d.x = new float[n][NF]; d.y = new byte[n]; d.sil = new float[n]; d.wr = new float[n];
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < NF; k++) d.x[i][k] = in.readFloat();
                d.y[i] = in.readByte();
                d.sil[i] = in.readFloat();
                d.wr[i] = in.readFloat();
            }
            return d;
        }
    }

    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.endsWith(".feat"));
        Arrays.sort(fs);
        int folds = a.length > 2 ? Integer.parseInt(a[2]) : 5, epochs = a.length > 3 ? Integer.parseInt(a[3]) : 8;
        File outDir = new File(a[1]);
        outDir.mkdirs();
        ArrayList<Data> ds = new ArrayList<>();
        for (File f : fs) { Data d = load(f); if (d.y.length > 1000) ds.add(d); }
        System.out.println("filmes com dados: " + ds.size());
        // grupos
        int[] fold = new int[ds.size()];
        for (int i = 0; i < ds.size(); i++) fold[i] = i % folds;
        double[] allP = null;
        StringBuilder rep = new StringBuilder("filme,frames,fala%,auc_dsp,auc_silero,auc_webrtc3,f1_dsp@0.5,f1_silero@0.5,f1_webrtc3\n");
        for (int g = 0; g < folds; g++) {
            ArrayList<Data> tr = new ArrayList<>();
            for (int i = 0; i < ds.size(); i++) if (fold[i] != g) tr.add(ds.get(i));
            if (tr.isEmpty()) continue;
            DspVad.Model m = train(tr, epochs, 100 + g);
            for (int i = 0; i < ds.size(); i++) {
                if (fold[i] != g) continue;
                Data d = ds.get(i);
                try (PrintWriter w = new PrintWriter(new File(outDir, d.slug + ".w"))) { w.print(m.serialize()); }
                double[] p = new double[d.y.length];
                for (int k = 0; k < p.length; k++) p[k] = m.predict(d.x[k]);
                double[] y = new double[d.y.length];
                double pos = 0;
                for (int k = 0; k < y.length; k++) { y[k] = d.y[k]; pos += y[k]; }
                double[] s = new double[y.length], wr = new double[y.length];
                for (int k = 0; k < y.length; k++) { s[k] = d.sil[k]; wr[k] = d.wr[k]; }
                rep.append(String.format(Locale.ROOT, "%s,%d,%.1f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n", d.slug, y.length, 100 * pos / y.length,
                        auc(p, y), d.sil[0] >= 0 ? auc(s, y) : -1, d.wr[0] >= 0 ? auc(wr, y) : -1, f1(p, y, 0.5), d.sil[0] >= 0 ? f1(s, y, 0.5) : -1, d.wr[0] >= 0 ? f1(wr, y, 0.5) : -1));
            }
        }
        DspVad.Model all = train(ds, epochs, 7);
        try (PrintWriter w = new PrintWriter(new File(outDir, "all.w"))) { w.print(all.serialize()); }
        try (PrintWriter w = new PrintWriter(new File(outDir, "vad-quadros.csv"))) { w.print(rep); }
        System.out.println(rep);
    }

    static DspVad.Model train(List<Data> ds, int epochs, long seed) {
        int n = 0;
        for (Data d : ds) n += d.y.length;
        // média/desvio
        double[] mean = new double[NF], var = new double[NF];
        for (Data d : ds) for (float[] x : d.x) for (int k = 0; k < NF; k++) mean[k] += x[k];
        for (int k = 0; k < NF; k++) mean[k] /= n;
        for (Data d : ds) for (float[] x : d.x) for (int k = 0; k < NF; k++) { double t = x[k] - mean[k]; var[k] += t * t; }
        DspVad.Model m = new DspVad.Model();
        int H = 16;
        m.hidden = H;
        for (int k = 0; k < NF; k++) { m.mean[k] = (float) mean[k]; m.std[k] = (float) Math.max(1e-3, Math.sqrt(var[k] / n)); }
        Random r = new Random(seed);
        m.w1 = new float[H][NF]; m.b1 = new float[H]; m.w2 = new float[H];
        for (int h = 0; h < H; h++) { for (int k = 0; k < NF; k++) m.w1[h][k] = (float) (r.nextGaussian() * 0.3); m.w2[h] = (float) (r.nextGaussian() * 0.3); }
        // índices
        int[][] idx = new int[n][2];
        int c = 0;
        for (int i = 0; i < ds.size(); i++) for (int j = 0; j < ds.get(i).y.length; j++) { idx[c][0] = i; idx[c][1] = j; c++; }
        double pos = 0;
        for (int[] q : idx) pos += ds.get(q[0]).y[q[1]];
        double wPos = 0.5 * n / Math.max(1, pos), wNeg = 0.5 * n / Math.max(1, n - pos);
        // Adam
        int P = H * NF + H + H + 1;
        double[] g = new double[P], m1 = new double[P], m2 = new double[P];
        int B = 256;
        double lr = 3e-3;
        long step = 0;
        float[] xs = new float[NF];
        for (int ep = 0; ep < epochs; ep++) {
            for (int i = n - 1; i > 0; i--) { int j = r.nextInt(i + 1); int[] t = idx[i]; idx[i] = idx[j]; idx[j] = t; }
            double loss = 0;
            for (int b0 = 0; b0 < n; b0 += B) {
                Arrays.fill(g, 0);
                int bn = Math.min(B, n - b0);
                for (int bi = 0; bi < bn; bi++) {
                    int[] q = idx[b0 + bi];
                    Data d = ds.get(q[0]);
                    float[] x = d.x[q[1]];
                    double y = d.y[q[1]];
                    for (int k = 0; k < NF; k++) xs[k] = (x[k] - m.mean[k]) / m.std[k];
                    double[] hid = new double[H];
                    double o = m.b2;
                    for (int h = 0; h < H; h++) {
                        double s = m.b1[h];
                        for (int k = 0; k < NF; k++) s += m.w1[h][k] * xs[k];
                        hid[h] = Math.tanh(s);
                        o += m.w2[h] * hid[h];
                    }
                    double p = 1 / (1 + Math.exp(-o));
                    double w = y > 0.5 ? wPos : wNeg;
                    loss += -w * (y * Math.log(p + 1e-9) + (1 - y) * Math.log(1 - p + 1e-9));
                    double go = w * (p - y);
                    g[P - 1] += go;
                    for (int h = 0; h < H; h++) {
                        g[H * NF + H + h] += go * hid[h];
                        double gh = go * m.w2[h] * (1 - hid[h] * hid[h]);
                        g[H * NF + h] += gh;
                        for (int k = 0; k < NF; k++) g[h * NF + k] += gh * xs[k];
                    }
                }
                step++;
                for (int i = 0; i < P; i++) {
                    double gi = g[i] / bn;
                    m1[i] = 0.9 * m1[i] + 0.1 * gi;
                    m2[i] = 0.999 * m2[i] + 0.001 * gi * gi;
                    double up = lr * (m1[i] / (1 - Math.pow(0.9, step))) / (Math.sqrt(m2[i] / (1 - Math.pow(0.999, step))) + 1e-8);
                    if (i < H * NF) m.w1[i / NF][i % NF] -= up;
                    else if (i < H * NF + H) m.b1[i - H * NF] -= up;
                    else if (i < H * NF + 2 * H) m.w2[i - H * NF - H] -= up;
                    else m.b2 -= up;
                }
            }
            lr *= 0.7;
            System.out.printf(Locale.ROOT, "  época %d perda %.4f%n", ep, loss / n);
        }
        return m;
    }

    static double auc(double[] p, double[] y) {
        int n = p.length;
        Integer[] o = new Integer[n];
        for (int i = 0; i < n; i++) o[i] = i;
        Arrays.sort(o, (u, v) -> Double.compare(p[u], p[v]));
        double rs = 0, np = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && p[o[j + 1]] == p[o[i]]) j++;
            double rank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) if (y[o[k]] > 0.5) { rs += rank; np++; }
            i = j + 1;
        }
        double nn = n - np;
        return (np == 0 || nn == 0) ? 0.5 : (rs - np * (np + 1) / 2) / (np * nn);
    }

    static double f1(double[] p, double[] y, double th) {
        double tp = 0, fp = 0, fn = 0;
        for (int i = 0; i < p.length; i++) {
            boolean s = p[i] > th, t = y[i] > 0.5;
            if (s && t) tp++; else if (s) fp++; else if (t) fn++;
        }
        return 2 * tp / Math.max(1, 2 * tp + fp + fn);
    }
}
