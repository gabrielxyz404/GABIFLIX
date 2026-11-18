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

    /**
     * plano espalhado pelo filme (para ver a deriva da escala): n janelas de lenMs, uma em cada faixa de ~1/n do filme (centros em 10%, 50%, 90% para n = 3;
     * 10%, 30%, 50%, 70%, 90% para n = 5), cada uma no trecho de MAIOR densidade de falas da sua faixa (mesma densidade suavizada do planDense).
     * Faixas dos extremos vão até 4% e 96% do filme. Sem fala suficiente numa faixa cai no planDense inteiro.
     */
    public static long[][] planSpread(Cue[] cues, long durMs, int n, long lenMs) {
        if (n < 2 || durMs < lenMs * n) return planDense(cues, durMs, n, lenMs);
        final int bin = 5000;
        int nb = (int) (durMs / bin) + 1;
        double[] sp = new double[nb];
        for (Cue c : cues) {
            if (!c.speech) continue;
            long e = c.speechEnd(true);
            for (long t = c.startMs; t < e && t < durMs; t += bin) sp[(int) (t / bin)] += Math.min(bin, e - t) / (double) bin;
        }
        int half = 24;
        double[] pre = new double[nb + 1];
        for (int i = 0; i < nb; i++) pre[i + 1] = pre[i] + sp[i];
        int wb = (int) (lenMs / bin);
        double spacing = 0.80 / (n - 1), hw = 0.45 * spacing + 0.02;
        long[][] w = new long[n][2];
        for (int z = 0; z < n; z++) {
            double fc = 0.10 + spacing * z;
            long lo = Math.max((long) (durMs * 0.04), (long) (durMs * (fc - hw)) - lenMs / 2);
            long hi = Math.min(durMs - durMs / 25, (long) (durMs * (fc + hw)) + lenMs / 2);
            double best = -1;
            int bi = -1;
            for (int i = (int) (lo / bin); (i + wb) * (long) bin <= hi && i + wb <= nb; i++) {
                int a = Math.max(0, i - half), b = Math.min(nb, i + wb + half);
                double d = (pre[b] - pre[a]) / (b - a);
                if (d > best) { best = d; bi = i; }
            }
            if (bi < 0 || best < 0.15) return planDense(cues, durMs, n, lenMs);
            w[z][0] = bi * (long) bin / 10 * 10;
            w[z][1] = lenMs;
        }
        Arrays.sort(w, (x, y) -> Long.compare(x[0], y[0]));
        return w;
    }

    /** de onde vem o áudio de cada janela: o app captura (libVLC) e passa o VAD; os testes leem de um arquivo. null = falhou/cancelado */
    public interface Source {
        Window capture(long startMs, long lenMs, int index, int total) throws Exception;
    }

    /**
     * O laço completo do app: 3 janelas espalhadas pelo filme (planSpread; planDense se spread = false), resolve; se as janelas confiáveis sugerem deriva mas a prova
     * ainda não fecha, captura as janelas extras que o alinhador propõe (até maxDriftWindows janelas no total) e resolve de novo. Devolve null se uma captura falhou.
     * As janelas usadas ficam em usedOut[0] (se não for null).
     */
    public static Result analyze(Cue[] cues, long durMs, int nWin, long lenMs, Source src, AudioAligner.Params P, boolean spread, Window[][] usedOut) throws Exception {
        long[][] plan = spread ? planSpread(cues, durMs, nWin, lenMs) : planDense(cues, durMs, nWin, lenMs);
        AudioSyncSession ses = new AudioSyncSession(cues, durMs, P);
        int total = plan.length, idx = 0;
        for (long[] p : plan) {
            Window w = src.capture(p[0], p[1], idx++, total);
            if (w == null) return null;
            ses.addWindow(w);
        }
        Result r = ses.solve();
        int extra = 0;
        while (r.driftMore != null && r.driftMore.length > 0 && extra < 2 && ses.windows() < P.maxDriftWindows) {
            extra++;
            total = ses.windows() + r.driftMore.length;
            for (long[] p : r.driftMore) {
                if (ses.windows() >= P.maxDriftWindows) break;
                Window w = src.capture(p[0], p[1], ses.windows(), total);
                if (w == null) return null;
                ses.addWindow(w);
            }
            r = ses.solve();
        }
        r.windows = ses.windows();
        if (usedOut != null && usedOut.length > 0) usedOut[0] = ses.all();
        return r;
    }

    public Window[] all() { return windows.toArray(new Window[0]); }

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
