package io.github.gabrielxyz404.gabiflix.audiosync;

import java.util.ArrayList;

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
