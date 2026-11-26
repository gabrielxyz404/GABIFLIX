import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.nio.file.*;
import java.util.*;

/** Estudo (só no CI, Java no PC) do plano "janelas perto de onde se está a ver": o que o alinhador (o MESMO motor do Mac e do Android) decide com
 *  3 janelas de L s à frente da posição do filme, contra o plano antigo (3 densas + espalhadas, 90 s).
 *  Uso: NearStudy audio16k.wav legenda25fps+4s.srt silero_vad.onnx   (a legenda é a certa deformada por tempo_legenda = 0,95904 * tempo_real + 4000)
 *  Saída: linhas "ESTUDO ..." com o resultado e o erro máximo do modelo achado (ms) ao longo do filme. */
public class NearStudy {
    static final double G = 23.976 / 25.0, O = 4000;

    static short[] pcm;
    static Vad vad;
    static Map<String, Window> cache = new HashMap<>();

    static Window win(long st, long ln) {
        String k = st + "/" + ln;
        Window w = cache.get(k);
        if (w == null) { w = PreCheck.analisa(vad, pcm, new long[]{st, ln}); cache.put(k, w); }
        return w;
    }

    /** o planNear do app (Swift): n janelas de lenMs a partir de pos+5 s, espaçadas de gap; cada uma no trecho de mais fala dentro de +-45 s do ponto */
    static long[][] planNear(Cue[] cues, long durMs, int n, long lenMs, long posMs, long gap) {
        int bin = 5000;
        int nb = (int) (durMs / bin) + 1;
        double[] sp = new double[nb];
        for (Cue c : cues) {
            if (!c.speech) continue;
            long e = c.speechEnd(true);
            long t = c.startMs;
            while (t < e && t < durMs) {
                sp[(int) (t / bin)] += (double) Math.min(bin, e - t) / bin;
                t += bin;
            }
        }
        double[] pre = new double[nb + 1];
        for (int i = 0; i < nb; i++) pre[i + 1] = pre[i] + sp[i];
        int wb = (int) (lenMs / bin);
        long hiLimit = durMs - durMs / 25;
        long first = Math.max(posMs + 5000, durMs / 25);
        long over = first + (n - 1) * gap + lenMs - hiLimit;
        if (over > 0) first = Math.max(durMs / 25, first - over);
        long[][] w = new long[n][2];
        for (int z = 0; z < n; z++) {
            long anchor = first + z * gap;
            long lo = Math.max(z == 0 ? posMs + 5000 : 0, anchor - 45000), hi = Math.min(hiLimit - lenMs, anchor + 45000);
            lo = Math.max(lo, 0);
            double best = -1;
            long bs = Math.max(0, Math.min(anchor, durMs - lenMs));
            for (long s = lo / bin * bin; s <= hi; s += bin) {
                boolean clash = false;
                for (int k = 0; k < z; k++) if (Math.abs(s - w[k][0]) < lenMs + bin) clash = true;
                if (clash || s < lo) continue;
                int i = (int) (s / bin);
                if (i + wb > nb) break;
                double d = (pre[i + wb] - pre[i]) / wb;
                if (d > best) { best = d; bs = s; }
            }
            w[z][0] = bs / 10 * 10;
            w[z][1] = lenMs;
        }
        return w;
    }

    static String err(Result r) {
        double mx = 0;
        for (int m = 5; m <= 85; m += 5) {
            double real = m * 60000.0, sub = G * real + O;
            double est = r.scale * sub + r.rawOffsetMs;
            mx = Math.max(mx, Math.abs(est - real));
        }
        return String.format(Locale.ROOT, "%.0f", mx);
    }

    public static void main(String[] a) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(a[0]));
        int p = 12;
        while (p + 8 <= b.length) {
            long sz = (b[p + 4] & 0xffL) | (b[p + 5] & 0xffL) << 8 | (b[p + 6] & 0xffL) << 16 | (b[p + 7] & 0xffL) << 24;
            boolean data = b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a';
            p += 8;
            if (data) {
                int n = (int) Math.min((b.length - p) / 2, sz / 2);
                pcm = new short[n];
                for (int i = 0; i < n; i++) pcm[i] = (short) ((b[p + 2 * i] & 0xff) | (b[p + 2 * i + 1] << 8));
                break;
            }
            p += (int) sz;
        }
        long dur = pcm.length / 16L;
        Cue[] cues = Cue.parseSrt(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
        Path wf = Paths.get(a[2]).resolveSibling("silero_v5_16k.bin");
        vad = new JavaSileroVad(Files.readAllBytes(wf), 2, null);
        AudioAligner.Params P = new AudioAligner.Params();
        long[] pos = {60_000L, 600_000L, 1_500_000L, 2_700_000L, 4_000_000L, 4_800_000L};
        long[] lens = {40_000L, 60_000L, 90_000L};
        long[] gaps = {180_000L, 300_000L};
        for (long L : lens) for (long gap : gaps) for (long ps : pos) {
            long[][] plan = planNear(cues, dur, 3, L, ps, gap);
            AudioSyncSession ses = new AudioSyncSession(cues, dur, P);
            for (long[] w : plan) ses.addWindow(win(w[0], w[1]));
            Result r = ses.solve();
            StringBuilder pl = new StringBuilder();
            for (long[] w : plan) pl.append(w[0] / 1000).append(",");
            System.out.printf(Locale.ROOT, "ESTUDO perto L=%d gap=%d pos=%d janelas=[%s] aceita=%d escala=%.5f desl=%.0f z=%.1f mais=%d deriva_mais=%d erro_max_ms=%s motivo=%s%n", L / 1000, gap / 1000, ps / 1000, pl, r.accepted ? 1 : 0, r.scale, r.rawOffsetMs, r.z,
                    r.more == null ? 0 : r.more.length, r.driftMore == null ? 0 : r.driftMore.length, r.accepted ? err(r) : "-", r.reason.trim().replace(' ', '_'));
            System.out.flush();
        }
        // referência: o plano antigo (3 densas + espalhadas, 90 s) inteiro
        Window[][] used = new Window[1][];
        Result r = AudioSyncSession.analyze(cues, dur, 6, 90000, (st, ln, i, total) -> win(st, ln), P, true, 3, used);
        StringBuilder pl = new StringBuilder();
        for (Window w : used[0]) pl.append(w.startMs / 1000).append(",");
        System.out.printf(Locale.ROOT, "ESTUDO antigo L=90 janelas=%d [%s] aceita=%d escala=%.5f desl=%.0f z=%.1f erro_max_ms=%s planB=%d%n", used[0].length, pl, r.accepted ? 1 : 0, r.scale, r.rawOffsetMs, r.z, r.accepted ? err(r) : "-", r.planB ? 1 : 0);
        ((AutoCloseable) vad).close();
    }
}
