package io.github.gabrielxyz404.gabiflix.audiosync;

import java.util.Locale;

/** Resultado do motor. Mapeamento: tempoCorrigido = scale * tempoLegenda + offsetMs (ms). */
public final class Result {
    public boolean accepted;
    /** aceito e a correção estimada é < deadbandMs com escala 1: a legenda já está sincronizada (offset zerado: não mexer) */
    public boolean inSync;
    /** o motor viu sinais de cortes (a legenda tem pedaços com deslocamentos diferentes): só aviso, nunca aplicado automaticamente (accepted = false) */
    public boolean cutsWarning;
    public double scale = 1.0, offsetMs = 0.0;
    /** a escala é uma razão de fps conhecida (a resposta confiável: escalas livres exigem evidência bem maior) */
    public boolean scaleKnown;
    /** 0..1: confiança composta (só informativa; a decisão está em accepted) */
    public double confidence;
    public String reason = "";
    // evidências
    public double z, margin, nccMean, nccMin, f1, support;
    public int blocksValid, blocksAgree, windows;
    /** ajuste por partes (null = um só ajuste global); em ordem de fromMediaMs */
    public Segment[] segments;
    /** o motor pede mais áudio: janelas {inicioMs, duracaoMs} (null/vazio = não precisa) */
    public long[][] more;
    /** só há dúvida de corte: o ajuste global vale por enquanto */
    public boolean pieceSuspect;
    /** milissegundos de áudio usados */
    public long audioMs;
    /** rodadas de janelas extras já usadas (preenchido pela sessão) */
    public int rounds;

    /** aplica o mapeamento global a um tempo da legenda */
    public double apply(double subMs) { return scale * subMs + offsetMs; }

    /** aplica o ajuste (por partes se houver) a um tempo da legenda */
    public double applySegments(double subMs) {
        if (segments == null || segments.length == 0) return apply(subMs);
        Segment cur = segments[0];
        for (int i = 1; i < segments.length; i++) if (subMs >= segments[i].fromSubMs) cur = segments[i];
        return cur.scale * subMs + cur.offsetMs;
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "%s k=%.5f off=%+.0fms conf=%.2f z=%.1f marg=%.2f ncc=%.2f/%.2f agree=%d/%d f1=%.2f seg=%s %s",
                accepted ? "ACEITA" : "recusada", scale, offsetMs, confidence, z, margin, nccMean, nccMin, blocksAgree, blocksValid, f1,
                segments == null ? "-" : java.util.Arrays.toString(segments), reason);
    }
}
