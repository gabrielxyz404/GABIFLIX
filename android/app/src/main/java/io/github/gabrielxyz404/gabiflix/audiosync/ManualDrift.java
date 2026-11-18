package io.github.gabrielxyz404.gabiflix.audiosync;

/**
 * Deriva a partir de DOIS ajustes manuais do atraso (pontos {tempo do vídeo, atraso}): se o usuário acertou a legenda num ponto e, mais de ~10 min depois no
 * filme, teve de acertar de novo com um atraso diferente, a legenda está desviando. Dois pontos sempre cabem numa reta, então só vale se a inclinação bate com
 * uma razão de fps CONHECIDA (a mesma lista do alinhador) dentro da precisão de um ajuste feito à mão, e a mudança é grande o bastante para não ser ruído.
 * Modelo: atraso(t) = alpha*t + beta, com alpha = 1 - 1/escala; o modelo (escala, deslocamento) é o mesmo do alinhador: tempoCorrigido = escala*tempoLegenda + deslocamento.
 */
public final class ManualDrift {
    private ManualDrift() {}

    /** distância mínima entre os dois ajustes, no tempo do filme */
    public static final long MIN_GAP_MS = 600_000;
    /** a mudança de atraso entre os dois pontos tem de passar disto (menos é ruído de ajuste manual) */
    public static final double MIN_CHANGE_MS = 600;
    /** quanto a mudança de atraso medida pode diferir da que a razão conhecida daria no intervalo (ms): é a precisão de dois ajustes feitos à mão */
    public static final double TOL_MS = 300;

    public static final class Fit {
        public double scale, offsetMs, slope;
        /** quanto (ms, no intervalo entre os pontos) a inclinação medida difere da conhecida */
        public double errMs;
    }

    /** atraso (ms) que o modelo dá no instante t do vídeo */
    public static double delayAt(double scale, double offsetMs, double tMs) {
        return (scale - 1.0) / scale * tMs + offsetMs / scale;
    }

    /** deslocamento do modelo com a escala dada que passa exatamente pelo ponto (tMs, delayMs): o atraso não "pula" nesse ponto */
    public static double anchorOffset(double scale, double delayMs, double tMs) {
        double alpha = (scale - 1.0) / scale;
        return scale * (delayMs - alpha * tMs);
    }

    /**
     * @return o ajuste (escala conhecida, deslocamento ancorado no ponto mais recente) ou null se os dois pontos não indicam deriva
     */
    public static Fit fit(long t1, double d1, long t2, double d2, double[] knownScales) {
        if (t2 < t1) { long tt = t1; t1 = t2; t2 = tt; double dd = d1; d1 = d2; d2 = dd; }
        long dt = t2 - t1;
        if (dt < MIN_GAP_MS || Math.abs(d2 - d1) < MIN_CHANGE_MS) return null;
        double slope = (d2 - d1) / dt;
        Fit best = null;
        for (double k : knownScales) {
            if (Math.abs(k - 1.0) < 1e-9) continue;
            double al = (k - 1.0) / k;
            double err = Math.abs(slope - al) * dt;
            if (err > TOL_MS) continue;
            if (best == null || err < best.errMs) {
                best = new Fit();
                best.scale = k;
                best.slope = slope;
                best.errMs = err;
            }
        }
        if (best == null) return null;
        best.offsetMs = anchorOffset(best.scale, d2, t2);
        return best;
    }
}
