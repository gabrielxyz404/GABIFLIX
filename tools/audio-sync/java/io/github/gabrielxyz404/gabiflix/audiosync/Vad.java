package io.github.gabrielxyz404.gabiflix.audiosync;

/** Detector de fala: PCM 16 bits mono -> probabilidade de fala (0..1) a cada 10 ms (100 quadros/s). */
public interface Vad {
    float[] speechProb(short[] pcm, int n, int rate);
    String name();
}
