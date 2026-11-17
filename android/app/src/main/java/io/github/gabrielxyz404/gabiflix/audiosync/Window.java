package io.github.gabrielxyz404.gabiflix.audiosync;

/** Um trecho de áudio já passado pelo VAD: probabilidade de fala a 100 quadros/s, começando em startMs (tempo do vídeo). */
public final class Window {
    public final long startMs;
    public final float[] p;

    public Window(long startMs, float[] p) { this.startMs = startMs; this.p = p; }

    public long durationMs() { return p.length * 10L; }
}
