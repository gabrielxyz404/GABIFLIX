package io.github.gabrielxyz404.gabiflix;

import java.util.ArrayList;
import java.util.List;

/**
 * Sincronização automática da legenda ativa, chamada pelo botão "Sincronizar automaticamente" do painel de legendas.
 * O painel só conhece a interface {@link Engine}: para ligar outro motor (por exemplo o que ouve o áudio do filme)
 * basta implementar {@link Engine} e trocar o motor em PlayerActivity (campo autoEngine). Corre sempre fora da thread da interface.
 */
final class AutoSync {
    private AutoSync() {}

    /** como terminou */
    static final int FIXED = 0;   // achou um atraso melhor e deve ser aplicado (delayMs)
    static final int OK = 1;      // a legenda já estava certa: não mexe
    static final int FAILED = 2;  // não conseguiu decidir: não mexe

    static final class Result {
        final int status;
        /** atraso final da legenda (ms; positivo = aparece mais tarde); só vale com FIXED */
        final int delayMs;
        final String note;
        /** frase pronta para o usuário (português simples) com o motivo exato; null = o painel usa o texto padrão */
        final String userMsg;
        /** modelo achado pelo áudio (tempo certo = scale * tempo da legenda + offsetMs); scale 1 = só um atraso. Só vale com FIXED vindo do áudio */
        double scale = 1.0, offsetMs;
        /** z do alinhador (0 = sem medida) e o corpo do relatório do motor (só números; vai para o ntfy) */
        double z;
        String report = "";
        /** o áudio foi quem decidiu (não a referência) */
        boolean fromAudio;
        Result(int status, int delayMs, String note) { this(status, delayMs, note, null); }
        Result(int status, int delayMs, String note, String userMsg) { this.status = status; this.delayMs = delayMs; this.note = note; this.userMsg = userMsg; }
        static Result failedMsg(String note, String userMsg) { return new Result(FAILED, 0, note, userMsg); }
        static Result fixed(int delayMs, String note) { return new Result(FIXED, delayMs, note); }
        static Result ok(String note) { return new Result(OK, 0, note); }
        static Result failed(String note) { return new Result(FAILED, 0, note); }
    }

    interface Engine {
        /**
         * @param active        a legenda baixada ativa (com o texto atual)
         * @param videoTimeMs   posição do vídeo agora
         * @param currentDelayMs atraso que a legenda tem agora (ms; positivo = mais tarde)
         * Chamado fora da thread da interface; pode demorar, mas nunca deve mexer no player.
         */
        Result sync(SubtitleSync.Cand active, long videoTimeMs, int currentDelayMs);
    }

    interface Source<T> { T get(); }

    /**
     * Motor atual: refaz o alinhamento automático (o mesmo de SubtitleSync.alignAll) da legenda ativa contra as referências
     * de tempo conhecidas e, em vez de reescrever o arquivo (o VLC já o carregou), devolve o atraso que o VLC deve aplicar.
     * Só age quando a referência valida por inteiro: na dúvida não mexe.
     */
    static final class RefEngine implements Engine {
        private static final int TOLERANCE_MS = 300;
        private static final int MAX_DELAY_MS = 600000;
        private final Source<List<long[]>> refs;
        private final Source<List<SubtitleSync.Cand>> others;

        RefEngine(Source<List<long[]>> refs, Source<List<SubtitleSync.Cand>> others) { this.refs = refs; this.others = others; }

        @Override
        public Result sync(SubtitleSync.Cand active, long videoTimeMs, int currentDelayMs) {
            if (active == null || active.text == null) return Result.failed("sem texto");
            long[] starts = SubtitleAlign.alignStarts(active.text);
            if (starts.length < 20) return Result.failed("poucas falas");
            ArrayList<long[]> use = new ArrayList<>(refs.get());
            if (use.isEmpty()) {
                // sem referência de outro idioma: serve outra legenda PT que case com o hash ou já esteja alinhada
                for (SubtitleSync.Cand o : others.get()) {
                    if (o == active || o.text == null || o.sparse) continue;
                    if (!(o.hashMatch || o.aligned || o.refSelf)) continue;
                    long[] s = SubtitleAlign.alignStarts(o.text);
                    if (s.length >= 20) use.add(s);
                }
            }
            if (use.isEmpty()) {
                if (active.refSelf) return Result.ok("é a própria referência");
                return Result.failed("sem referência de tempo");
            }
            SubtitleAlign.Fit best = null;
            ArrayList<SubtitleAlign.Fit> oks = new ArrayList<>();
            for (long[] r : use) {
                SubtitleAlign.Fit f = SubtitleAlign.align(r, starts);
                if (best == null || (f.ok() && !best.ok()) || (f.ok() == best.ok() && f.score > best.score)) best = f;
                if (f.ok()) oks.add(f);
            }
            if (best == null || !best.ok()) return Result.failed("a referência não casa");
            if (oks.size() >= 2) {
                // referências do mesmo vídeo têm de dar a mesma transformação; se discordam, não decide
                SubtitleAlign.Fit pick = null;
                int bestVotes = 0;
                for (SubtitleAlign.Fit a : oks) {
                    int votes = 0;
                    for (SubtitleAlign.Fit b : oks) if (SubtitleAlign.agreement(a, b, starts, 2000) >= 0.90) votes++;
                    if (votes > bestVotes || (votes == bestVotes && pick != null && a.score > pick.score)) { bestVotes = votes; pick = a; }
                }
                if (bestVotes < 2) return Result.failed("as referências discordam");
                best = pick;
            }
            // o atraso certo agora: onde a fala que aparece neste instante (tempo da legenda c) devia aparecer segundo a referência
            long c = Math.max(0, videoTimeMs - currentDelayMs);
            long want = best.map(c) - c;
            want = Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, want));
            if (Math.abs(want - currentDelayMs) < TOLERANCE_MS) return Result.ok("já estava certa");
            return Result.fixed((int) want, "ajuste " + want + " ms");
        }
    }
}
