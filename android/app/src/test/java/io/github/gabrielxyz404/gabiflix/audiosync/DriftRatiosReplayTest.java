package io.github.gabrielxyz404.gabiflix.audiosync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

/**
 * Replay sintético da prova de deriva para TODAS as razões de fps conhecidas (PAL 25/24, 25/23,976, 24/25, 23,976/25, 30/25...): janelas geradas com a escala
 * da razão + um deslocamento, uma janela fraca desviada; e os casos negativos (atraso constante, janelas incoerentes, minoria, inclinação que não é de nenhuma razão).
 */
public class DriftRatiosReplayTest {
    static final long DUR = 5_400_000L;
    static final double[] STARTS_S = {300, 900, 1700, 2500, 3300, 4100, 4800};

    private static Result.Win win(double startS, double delayMs, double ncc, double second, double z, boolean confident) {
        Result.Win w = new Result.Win();
        w.startMs = (long) (startS * 1000);
        w.centerMs = (startS + 45) * 1000.0;
        w.delayMs = delayMs;
        w.ncc = ncc;
        w.second = second;
        w.z = z;
        w.speech = true;
        w.confident = confident;
        return w;
    }

    /** janelas sobre a reta atraso(t) = beta + alpha*t (ruído de até +-70 ms); a penúltima é fraca e desviada de weakDevMs */
    private static Result.Win[] line(double scale, double offsetMs, double weakDevMs, long seed) {
        double al = AudioAligner.alphaOf(scale), beta = offsetMs / scale;
        Random rnd = new Random(seed);
        Result.Win[] ws = new Result.Win[STARTS_S.length];
        for (int i = 0; i < ws.length; i++) {
            double c = (STARTS_S[i] + 45) * 1000.0;
            double d = beta + al * c + (rnd.nextDouble() * 2 - 1) * 70;
            boolean weak = i == ws.length - 2;
            ws[i] = weak ? win(STARTS_S[i], d + weakDevMs, 0.30, 0.27, 2.4, false) : win(STARTS_S[i], d, 0.55 + 0.02 * i, 0.40, 4.5, true);
        }
        return ws;
    }

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

    @Test public void everyKnownRatioIsProvedWithItsOffset() {
        AudioAligner.Params P = new AudioAligner.Params();
        int tested = 0;
        for (double ks : P.knownScales) {
            if (Math.abs(ks - 1.0) < 1e-9 || Math.abs(AudioAligner.alphaOf(ks)) > P.maxAlpha) continue;
            for (double off : new double[]{8000, -12000}) {
                Result.Win[] ws = line(ks, off, 700, 7 + tested);
                Result R = new Result();
                AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
                String m = "razão " + ks + " desl. " + off + ": " + R.driftNote;
                assertTrue(m, R.driftScale);
                assertEquals(m, ks, R.scale, 1e-12);
                assertEquals(m, off, R.offsetMs, 300);
                assertTrue(m, R.unitResMs >= P.driftRelaxRatio * Math.max(R.driftResMs, P.driftResFloorMs));
                double[] d = devs(ws, R);
                assertTrue("a janela fraca desviada cabe na tolerância das fracas: " + m, AudioAligner.driftAgree(d, strong(ws, P), ws.length, P));
                assertTrue(m, Math.abs(d[ws.length - 2]) > P.driftGateDevMs);
            }
            tested++;
        }
        assertTrue("todas as razões com |alpha| <= maxAlpha (8): " + tested, tested >= 8);
    }

    @Test public void strongWindowOffTheLineStillRefuses() {
        AudioAligner.Params P = new AudioAligner.Params();
        Result.Win[] ws = line(25.0 / 23.976, 8000, 700, 3);
        Result R = new Result();
        AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
        assertTrue(R.driftNote, R.driftScale);
        double[] d = devs(ws, R);
        d[0] = 450;
        assertFalse(AudioAligner.driftAgree(d, strong(ws, P), ws.length, P));
    }

    @Test public void constantDelayIsNotDriftForAnyRatio() {
        AudioAligner.Params P = new AudioAligner.Params();
        Random rnd = new Random(5);
        for (double delay : new double[]{0, 3000, -9000, 14000}) {
            Result.Win[] ws = new Result.Win[STARTS_S.length];
            for (int i = 0; i < ws.length; i++) ws[i] = win(STARTS_S[i], delay + (rnd.nextDouble() * 2 - 1) * 60, 0.6, 0.4, 4.5, true);
            Result R = new Result();
            AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
            assertFalse("atraso constante " + delay + ": " + R.driftNote, R.driftScale);
        }
    }

    @Test public void incoherentWindowsAreNotDrift() {
        AudioAligner.Params P = new AudioAligner.Params();
        Random rnd = new Random(11);
        for (int t = 0; t < 200; t++) {
            Result.Win[] ws = new Result.Win[STARTS_S.length];
            for (int i = 0; i < ws.length; i++) ws[i] = win(STARTS_S[i], (rnd.nextDouble() * 2 - 1) * 200_000, 0.5, 0.4, 4.5, true);
            Result R = new Result();
            AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
            assertFalse("janelas incoerentes #" + t + ": " + R.driftNote, R.driftScale);
        }
    }

    @Test public void onlyAMinorityOnTheLineIsNotDrift() {
        AudioAligner.Params P = new AudioAligner.Params();
        Result.Win[] ws = line(25.0 / 24.0, 5000, 0, 9);
        double[] junk = {-40_000, 90_000, 12_000, -70_000, 31_000};
        int k = 0;
        for (int i = 0; i < ws.length; i++) if (i != 1 && i != 5) ws[i].delayMs = junk[k++];
        Result R = new Result();
        AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
        assertFalse(R.driftNote, R.driftScale);
    }

    @Test public void slopeOfNoKnownRatioIsNotDrift() {
        AudioAligner.Params P = new AudioAligner.Params();
        for (double al : new double[]{0.012, 0.02, 0.03, -0.02, 0.07}) {
            double scale = 1.0 / (1.0 - al);
            Result.Win[] ws = line(scale, 6000, 0, 21);
            Result R = new Result();
            AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
            assertFalse("inclinação " + al + " não é de razão conhecida: " + R.driftNote, R.driftScale);
        }
    }

    @Test public void twoConfidentWindowsAreNotEnough() {
        AudioAligner.Params P = new AudioAligner.Params();
        Result.Win[] ws = line(25.0 / 24.0, 5000, 0, 4);
        for (int i = 0; i < ws.length; i++) ws[i].confident = i < 2;
        Result R = new Result();
        AudioAligner.driftFromWins(R, null, null, ws, DUR, P);
        assertFalse(R.driftNote, R.driftScale);
    }
    /** ruído determinístico (inteiro, em ms) para o teste de paridade com o Swift (tools/mac-audio/swift/main.swift, runDriftRatios) */
    private static Result.Win[] lineDet(double scale, double offsetMs, double weakDevMs) {
        double al = AudioAligner.alphaOf(scale), beta = offsetMs / scale;
        Result.Win[] ws = new Result.Win[STARTS_S.length];
        for (int i = 0; i < ws.length; i++) {
            double c = (STARTS_S[i] + 45) * 1000.0;
            double d = beta + al * c + (((i * 37 + 11) % 141) - 70);
            ws[i] = i == ws.length - 2 ? win(STARTS_S[i], d + weakDevMs, 0.30, 0.27, 2.4, false) : win(STARTS_S[i], d, 0.55 + 0.02 * i, 0.40, 4.5, true);
        }
        return ws;
    }

    /** valores de referência copiados pelo Swift (paridade exata) */
    @Test public void deterministicReferenceValuesForParity() {
        AudioAligner.Params P = new AudioAligner.Params();
        double[][] cases = {{25.0 / 23.976, 8000, 7993.536, 100448.088}, {25.0 / 24.0, 8000, 7993.543, 98094.497}, {24.0 / 25.0, -12000, -12005.951, 102124.195}};
        for (double[] c : cases) {
            Result R = new Result();
            AudioAligner.driftFromWins(R, null, null, lineDet(c[0], c[1], 700), DUR, P);
            assertTrue(R.driftNote, R.driftScale);
            assertEquals(c[0], R.scale, 1e-12);
            assertEquals(c[2], R.offsetMs, 0.01);
            assertEquals(c[3], R.unitResMs, 0.01);
            assertEquals(58.199, R.driftResMs, 0.01);
            assertEquals(6, R.driftConfident);
        }
    }
}
