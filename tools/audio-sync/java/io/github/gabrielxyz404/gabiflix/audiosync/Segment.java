package io.github.gabrielxyz404.gabiflix.audiosync;

/** Trecho de um ajuste por partes: a partir de fromMediaMs (tempo do vídeo) vale tempoCorrigido = scale * tempoLegenda + offsetMs. */
public final class Segment {
    public final long fromMediaMs;
    public final double scale, offsetMs;

    public Segment(long fromMediaMs, double scale, double offsetMs) {
        this.fromMediaMs = fromMediaMs; this.scale = scale; this.offsetMs = offsetMs;
    }

    @Override public String toString() {
        return String.format(java.util.Locale.ROOT, "[de %.1fs: k=%.5f off=%+.0fms]", fromMediaMs / 1000.0, scale, offsetMs);
    }
}
