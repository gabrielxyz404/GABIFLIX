package io.github.gabrielxyz404.gabiflix.audiosync;

/**
 * Trecho de um ajuste por partes. Vale para as falas da legenda cujo início (tempo da LEGENDA, ms) é >= fromSubMs, até o próximo trecho:
 * tempoCorrigido = scale * tempoLegenda + offsetMs.
 */
public final class Segment {
    public final long fromSubMs;
    public final double scale, offsetMs;

    public Segment(long fromSubMs, double scale, double offsetMs) {
        this.fromSubMs = fromSubMs; this.scale = scale; this.offsetMs = offsetMs;
    }

    @Override public String toString() {
        return String.format(java.util.Locale.ROOT, "[legenda>=%.1fs: k=%.5f off=%+.0fms]", fromSubMs / 1000.0, scale, offsetMs);
    }
}
