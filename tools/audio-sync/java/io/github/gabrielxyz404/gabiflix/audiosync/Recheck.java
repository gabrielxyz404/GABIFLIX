package io.github.gabrielxyz404.gabiflix.audiosync;

import java.util.Arrays;

/**
 * Reverificação durante a reprodução. A cada ~10 min o app captura 1-2 min de áudio na posição atual (em thread de baixa prioridade, ver DESIGN.md) e chama
 * {@link #check}. Regras:
 *  - atraso manual do usuário (manualLocked) vence sempre: nunca muda nada;
 *  - o modelo atual que ainda explica o áudio (NCC e deslocamento locais) => KEEP, sem buscar nada (caminho barato e o mais comum);
 *  - só havendo evidência forte de um salto (corte/cena extra) a busca larga acha o novo deslocamento (mesma escala); e mesmo assim só vira CHANGE
 *    com evidência muito forte numa checagem, ou a mesma proposta confirmada em duas checagens seguidas (PROPOSED -> CHANGE);
 *  - a mudança vale a partir da próxima pausa entre falas depois da posição atual (applyFromSubMs), nunca no meio de uma fala.
 */
public final class Recheck {
    private Recheck() {}

    public enum Verdict { KEEP, PROPOSED, CHANGE }

    public static final class Out {
        public Verdict verdict = Verdict.KEEP;
        public String reason = "";
        /** novo deslocamento (ms) para a escala atual, quando PROPOSED/CHANGE */
        public double newOffsetMs;
        /** a partir de qual fala (início, tempo da LEGENDA) o novo deslocamento vale: primeira fala depois de uma pausa depois da posição atual */
        public long applyFromSubMs;
        public double nccAtCurrent, z;
        /** CPU/tempo de análise em ms (informativo) */
        public long tookMs;
    }

    public static final class Params {
        /** o modelo atual vale se, em alguma janela com fala, NCC >= isto e |deslocamento| <= okDeltaMs */
        public double okNcc = 0.20;
        public double okDeltaMs = 500;
        /** falas da legenda cobrindo ao menos esta fração da janela para a checagem valer */
        public double minCueCoverage = 0.05;
        /** evidência de salto: busca larga (±searchMs) com z >= minZ e NCC médio >= minNcc */
        public long searchMs = 300_000;
        public double minZ = 7, minNcc = 0.25;
        /** evidência "muito forte" que dispensa a 2ª checagem */
        public double strongZ = 11, strongNcc = 0.40;
        /** salto mínimo para propor mudança (menor que isto é ruído) */
        public double minJumpMs = 1500;
        public double sameProposalMs = 400;
        /** pausa mínima entre falas (ms, tempo corrigido) para aplicar */
        public long minGapMs = 500;
    }

    /**
     * @param cues        falas da legenda (tempo da legenda)
     * @param wins        1-2 janelas capturadas agora (tempo do vídeo)
     * @param current     modelo em uso (global ou por partes; segmentos tratados pelo trecho que cobre a posição)
     * @param playMs      posição atual de reprodução (tempo do vídeo)
     * @param manualLocked o usuário definiu atraso manual para este vídeo: nunca mudar
     * @param prev        proposta da checagem anterior (ou null)
     */
    public static Out check(Cue[] cues, Window[] wins, Result current, long playMs, long mediaDurationMs, boolean manualLocked, Out prev, AudioAligner.Params AP, Params P) {
        long t0 = System.nanoTime();
        Out o = new Out();
        try {
            if (manualLocked) { o.reason = "atraso manual do usuário"; return o; }
            if (current == null || !current.accepted) { o.reason = "sem ajuste automático em uso"; return o; }
            // modelo (escala, deslocamento) do trecho em uso na posição atual
            double a = current.scale, b = current.offsetMs;
            if (current.segments != null) {
                double sub = (playMs - b) / a;
                for (Segment s : current.segments) if (sub >= s.fromSubMs) { a = s.scale; b = s.offsetMs; }
            }
            float[] fine = AudioAligner.fineMask(cues, AP.trimCues);
            // 1) o modelo atual ainda explica o áudio?
            double bestNcc = 0;
            boolean usable = false;
            for (Window w : wins) {
                float[] ps = AudioAligner.smooth(w.p, AP.smoothMs);
                AudioAligner.Fine f = AudioAligner.fineWindow(ps, w.startMs, fine, a, b, 150);
                double cov = 0;
                for (int j = 0; j < ps.length; j += 10) if (AudioAligner.cov(fine, (w.startMs + 10.0 * j - b) / a) > 0.5f) cov += 1;
                if (f.speechFrac >= 0.03 && cov * 10.0 / ps.length >= P.minCueCoverage) {
                    usable = true;
                    if (f.ncc > bestNcc) bestNcc = f.ncc;
                    if (f.ncc >= P.okNcc && Math.abs(f.bestDelta) <= P.okDeltaMs) { o.nccAtCurrent = f.ncc; o.reason = "ok"; return o; }
                }
            }
            o.nccAtCurrent = bestNcc;
            if (!usable) { o.reason = "pouca fala/legenda na janela"; return o; }
            // 2) busca larga só com a escala atual: aplica o modelo atual às falas (ficam em tempo de vídeo) e procura só um deslocamento adicional
            Cue[] warped = new Cue[cues.length];
            for (int i = 0; i < cues.length; i++) {
                Cue c = cues[i];
                warped[i] = new Cue(Math.round(a * c.startMs + b), Math.round(a * c.endMs + b), c.chars, c.speech);
            }
            AudioAligner.Params q = new AudioAligner.Params();
            q.knownScales = new double[]{1.0};
            q.maxAlpha = 1e-4;
            q.maxOffsetMs = P.searchMs;
            q.minZ = P.minZ; q.minNcc = P.minNcc; q.minMargin = 0.10; q.minAgreeFrac = 0.6;
            q.randomHyps = 1500;
            q.minBlocks = 2;
            Result r = AudioAligner.solve(warped, wins, mediaDurationMs, q);
            o.z = r.z;
            if (!r.accepted || Math.abs(r.scale - 1.0) > 1e-9) { o.reason = "sem evidência de salto (" + r.reason.trim() + ")"; return o; }
            double jump = r.offsetMs;
            if (Math.abs(jump) < P.minJumpMs) { o.reason = "desvio pequeno"; return o; }
            boolean strong = r.z >= P.strongZ && r.nccMean >= P.strongNcc;
            boolean confirmed = prev != null && prev.verdict != Verdict.KEEP && Math.abs(prev.newOffsetMs - (b + r.offsetMs)) <= P.sameProposalMs;
            o.newOffsetMs = b + r.offsetMs;
            o.applyFromSubMs = nextGap(cues, a, b, playMs, P.minGapMs);
            o.verdict = (strong || confirmed) ? Verdict.CHANGE : Verdict.PROPOSED;
            o.reason = strong ? "evidência forte" : confirmed ? "confirmada em 2 checagens" : "aguardando confirmação";
            return o;
        } finally {
            o.tookMs = (System.nanoTime() - t0) / 1_000_000;
        }
    }

    /** início (tempo da legenda) da primeira fala que começa depois de uma pausa >= minGapMs e depois da posição atual + 1 s (modelo corrente a,b) */
    public static long nextGap(Cue[] cues, double a, double b, long playMs, long minGapMs) {
        int n = cues.length;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, (x, y) -> Long.compare(cues[x].startMs, cues[y].startMs));
        long maxEnd = Long.MIN_VALUE;
        for (int k = 0; k < n; k++) {
            Cue c = cues[ord[k]];
            double startMedia = a * c.startMs + b;
            if (k > 0 && startMedia > playMs + 1000 && c.startMs - maxEnd >= minGapMs / a) return c.startMs;
            maxEnd = Math.max(maxEnd, c.endMs);
        }
        return Long.MAX_VALUE;
    }
}
