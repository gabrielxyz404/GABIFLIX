package io.github.gabrielxyz404.gabiflix.audiosync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Repetição de um relatório real (celular, v1.0.109, filme de 5615 s, legenda externa PT, https): as 7 janelas medidas contam uma história só
 * (~14,6 s de atraso + deriva 1000/1001), o motor provava a deriva e mesmo assim recusava ("janelas discordam", desvio máximo 788 ms de UMA janela fraca).
 */
public class DriftReplayTest {
    private static Result.Win win(long startS, double delayMs, double ncc, double second, double z, boolean confident) {
        Result.Win w = new Result.Win();
        w.startMs = startS * 1000;
        w.centerMs = (startS + 45) * 1000.0;
        w.delayMs = delayMs;
        w.ncc = ncc;
        w.second = second;
        w.z = z;
        w.speech = true;
        w.confident = confident;
        return w;
    }

    /** as 7 janelas da tentativa 1 do relatório, em ordem de captura */
    private static Result.Win[] report() {
        return new Result.Win[] {
            win(440, 14268, 0.63, 0.53, 4.2, true),
            win(865, 13588, 0.70, 0.61, 2.6, true),
            win(2490, 12024, 0.65, 0.58, 2.8, false),
            win(1025, 13535, 0.45, 0.37, 3.8, true),
            win(3425, 11141, 0.53, 0.44, 3.7, true),
            win(4000, 10750, 0.47, 0.46, 2.1, false),
            win(4600, 10730, 0.51, 0.46, 3.0, false),
        };
    }

    /** desvio de cada janela para a reta final (escala + deslocamento): o que finalEvidence mede */
    private static double[] devs(Result.Win[] ws, Result R) {
        double al = AudioAligner.alphaOf(R.scale), beta = R.offsetMs / R.scale;
        double[] d = new double[ws.length];
        for (int i = 0; i < ws.length; i++) d[i] = ws[i].delayMs - (beta + al * ws[i].centerMs);
        return d;
    }

    private static boolean[] strong(Result.Win[] ws, AudioAligner.Params P) {
        boolean[] s = new boolean[ws.length];
        for (int i = 0; i < ws.length; i++) s[i] = ws[i].ncc >= P.winMinNcc && (ws[i].ncc - ws[i].second >= P.winMinMargin - 1e-9 || ws[i].z >= P.winMinZ);
        return s;
    }

    @Test public void realReportIsAcceptedWithScaleAndOffset() {
        AudioAligner.Params P = new AudioAligner.Params();
        Result R = new Result();
        AudioAligner.driftFromWins(R, null, null, report(), 5_615_000L, P);
        assertTrue(R.driftNote, R.driftScale);
        assertEquals(1000.0 / 1001.0, R.scale, 2e-6);
        assertEquals(14_600, R.offsetMs, 300);
        Result.Win[] ws = report();
        double[] d = devs(ws, R);
        double max = 0;
        for (double v : d) max = Math.max(max, Math.abs(v));
        assertTrue("o motor de antes via um desvio máximo > 200 ms: " + max, max > 200);
        assertTrue("a janela fora é a de z baixo", Math.abs(d[6]) > 500);
        assertTrue("a maioria concorda com a reta", AudioAligner.driftAgree(d, strong(ws, P), ws.length, P));
        assertTrue(AudioAligner.driftFitCount(d, strong(ws, P), ws.length, P) >= 6);
    }

    @Test public void strongWindowContradictingTheLineStillRefuses() {
        AudioAligner.Params P = new AudioAligner.Params();
        Result R = new Result();
        AudioAligner.driftFromWins(R, null, null, report(), 5_615_000L, P);
        Result.Win[] ws = report();
        double[] d = devs(ws, R);
        boolean[] st = strong(ws, P);
        d[0] = 450;     // uma janela forte a 450 ms da reta
        assertFalse(AudioAligner.driftAgree(d, st, ws.length, P));
    }

    @Test public void minorityOfWindowsFittingIsNotEnough() {
        AudioAligner.Params P = new AudioAligner.Params();
        double[] d = {50, 60, 3000, 2500, 4000, 1800, 5000};
        boolean[] st = new boolean[7];
        assertFalse(AudioAligner.driftAgree(d, st, 7, P));
    }

    @Test public void constantOffsetWithoutSlopeIsNotDrift() {
        AudioAligner.Params P = new AudioAligner.Params();
        Result.Win[] ws = report();
        for (Result.Win w : ws) w.delayMs = 14000;      // atraso constante: sem deriva, sem escala
        Result R = new Result();
        AudioAligner.driftFromWins(R, null, null, ws, 5_615_000L, P);
        assertFalse(R.driftScale);
    }
}
