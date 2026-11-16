package io.github.gabrielxyz404.gabiflix.audiosync;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Sessão progressiva de um alinhamento: o app captura as janelas do plano inicial, chama {@link #solve()}; se {@code Result.more} vier preenchido,
 * captura as janelas pedidas, chama {@code addWindow} e {@code solve()} de novo (até {@link #MAX_ROUNDS} rodadas). Aplica só se {@code accepted}.
 * Não guarda nada além das janelas: pode ser refeita do zero a qualquer momento.
 */
public final class AudioSyncSession {
    public static final int MAX_ROUNDS = 6;

    private final Cue[] cues;
    private final long durationMs;
    private final AudioAligner.Params params;
    private final ArrayList<Window> windows = new ArrayList<>();
    private int rounds;

    public AudioSyncSession(Cue[] cues, long mediaDurationMs, AudioAligner.Params params) {
        this.cues = cues;
        this.durationMs = mediaDurationMs;
        this.params = params == null ? new AudioAligner.Params() : params;
    }

    /** plano inicial: n janelas de lenMs espalhadas entre 15% e 85% do filme; devolve {inicioMs, duracaoMs} (múltiplos de 10 ms) */
    public static long[][] plan(long durMs, int n, long lenMs) {
        long[][] w = new long[n][2];
        for (int i = 0; i < n; i++) {
            double fr = n == 1 ? 0.5 : 0.15 + 0.70 * i / (n - 1);
            long st = (long) (durMs * fr - lenMs / 2.0);
            st = Math.max(0, Math.min(st, durMs - lenMs));
            w[i][0] = st / 10 * 10;
            w[i][1] = lenMs;
        }
        return w;
    }

    /**
     * plano inicial guiado pela legenda: n janelas de lenMs nos trechos onde a legenda tem MAIS fala (densidade suavizada em +-2 min, para tolerar
     * deslocamentos de alguns minutos), afastadas entre si, fora dos 4% iniciais e finais. Sem fala suficiente (legenda curta) cai no plano uniforme.
     * Escolher onde ouvir é a maior alavanca de acerto: janelas em cenas sem diálogo (música, ação) não alinham nada.
     */
    public static long[][] planDense(Cue[] cues, long durMs, int n, long lenMs) {
        final int bin = 5000;
        int nb = (int) (durMs / bin) + 1;
        double[] sp = new double[nb];
        for (Cue c : cues) {
            if (!c.speech) continue;
            long e = c.speechEnd(true);
            for (long t = c.startMs; t < e && t < durMs; t += bin) sp[(int) (t / bin)] += Math.min(bin, e - t) / (double) bin;
        }
        int half = 24;   // +-2 min
        double[] pre = new double[nb + 1];
        for (int i = 0; i < nb; i++) pre[i + 1] = pre[i] + sp[i];
        int wb = (int) (lenMs / bin);
        long lo = Math.max(0, durMs / 25), hi = durMs - durMs / 25;
        if (hi - lo < lenMs * n) { lo = 0; hi = durMs; }
        long spacing = Math.min(360000L, Math.max(lenMs, (hi - lo - lenMs) / Math.max(1, n)));
        long[][] w = new long[n][2];
        boolean[] used = new boolean[nb];
        int got = 0;
        for (; got < n; got++) {
            double best = -1;
            int bi = -1;
            for (int i = (int) (lo / bin); (i + wb) * (long) bin <= hi && i + wb <= nb; i++) {
                boolean bad = false;
                for (int k = 0; k < got && !bad; k++) if (Math.abs(i * (long) bin - w[k][0]) < spacing) bad = true;
                if (bad) continue;
                int a = Math.max(0, i - half), b = Math.min(nb, i + wb + half);
                double d = (pre[b] - pre[a]) / (b - a);
                if (d > best) { best = d; bi = i; }
            }
            if (bi < 0 || best < 0.15) break;
            w[got][0] = bi * (long) bin / 10 * 10;
            w[got][1] = lenMs;
        }
        if (got < n) return plan(durMs, n, lenMs);
        Arrays.sort(w, (x, y) -> Long.compare(x[0], y[0]));
        return w;
    }

    public void addWindow(Window w) { windows.add(w); }

    public int windows() { return windows.size(); }

    public int rounds() { return rounds; }

    /** resolve com as janelas atuais; se pedir mais áudio e ainda há rodadas, a próxima chamada deve vir depois de addWindow */
    public Result solve() {
        Result r = AudioAligner.solve(cues, windows.toArray(new Window[0]), durationMs, params);
        r.rounds = rounds;
        if (r.more != null && r.more.length > 0) {
            if (rounds >= MAX_ROUNDS) r.more = null; else rounds++;
        }
        return r;
    }
}
