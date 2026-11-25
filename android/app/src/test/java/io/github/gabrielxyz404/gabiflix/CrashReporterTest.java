package io.github.gabrielxyz404.gabiflix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrashReporterTest {
    // ApplicationExitInfo.REASON_*
    private static final int SIGNALED = 2, LOW_MEMORY = 3, CRASH = 4, CRASH_NATIVE = 5, ANR = 6, OTHER = 13;
    private static final int FOREGROUND = 100, FGS = 125, VISIBLE = 200, PERCEPTIBLE = 230, SERVICE = 300, CACHED = 400;

    @Test public void lowMemoryForegroundMainNotices() {
        assertTrue(CrashReporter.shouldNotice(LOW_MEMORY, FOREGROUND, true, false));
        assertTrue(CrashReporter.shouldNotice(LOW_MEMORY, FGS, true, false));
        assertTrue(CrashReporter.shouldNotice(LOW_MEMORY, VISIBLE, true, false));
        assertTrue(CrashReporter.shouldNotice(LOW_MEMORY, PERCEPTIBLE, true, false));
        assertTrue(CrashReporter.shouldNotice(SIGNALED, FOREGROUND, true, false));
    }

    @Test public void lowMemoryBackgroundIsNormal() {
        assertFalse(CrashReporter.shouldNotice(LOW_MEMORY, PERCEPTIBLE + 1, true, true));
        assertFalse(CrashReporter.shouldNotice(LOW_MEMORY, SERVICE, true, true));
        assertFalse(CrashReporter.shouldNotice(LOW_MEMORY, CACHED, true, true));
        assertFalse(CrashReporter.shouldNotice(SIGNALED, CACHED, true, true));
    }

    @Test public void auxiliaryProcessNeedsPlayerAlive() {
        assertFalse(CrashReporter.shouldNotice(LOW_MEMORY, FOREGROUND, false, false));
        assertTrue(CrashReporter.shouldNotice(LOW_MEMORY, FOREGROUND, false, true));
        assertFalse(CrashReporter.shouldNotice(LOW_MEMORY, CACHED, false, true));
    }

    @Test public void realCrashesAlwaysNotice() {
        for (int imp : new int[] {FOREGROUND, SERVICE, CACHED}) {
            for (boolean main : new boolean[] {true, false}) {
                assertTrue(CrashReporter.shouldNotice(CRASH, imp, main, false));
                assertTrue(CrashReporter.shouldNotice(CRASH_NATIVE, imp, main, false));
                assertTrue(CrashReporter.shouldNotice(ANR, imp, main, false));
            }
        }
    }

    @Test public void otherReasonsDoNotNotice() {
        assertFalse(CrashReporter.shouldNotice(OTHER, FOREGROUND, true, true));
    }

    @Test public void wordingAndImportanceNames() {
        assertEquals("O sistema fechou a app por falta de memória (código LOW_MEMORY-f1bd)", CrashReporter.noticeText("LOW_MEMORY-f1bd"));
        assertEquals("A app fechou por um erro (código CRASH-12ab)", CrashReporter.noticeText("CRASH-12ab"));
        assertEquals("FOREGROUND", CrashReporter.importanceName(100));
        assertEquals("VISIBLE", CrashReporter.importanceName(200));
        assertEquals("PERCEPTIBLE", CrashReporter.importanceName(230));
        assertEquals("SERVICE", CrashReporter.importanceName(300));
        assertEquals("CACHED", CrashReporter.importanceName(400));
    }

    @Test public void analiseStreamTypeNeverLeaksAddress() {
        assertEquals("local(http)", CrashReporter.streamType("http://127.0.0.1:11470/abc/0?x=1"));
        assertEquals("local(http)", CrashReporter.streamType("http://10.0.2.2:8000/film.mkv"));
        assertEquals("local(http)", CrashReporter.streamType("http://192.168.1.20:11470/x"));
        assertEquals("local(https)", CrashReporter.streamType("https://localhost/x"));
        assertEquals("https", CrashReporter.streamType("https://real-debrid.example/d/AAA/film.mkv"));
        assertEquals("http", CrashReporter.streamType("http://cdn.example.org/film.mkv"));
        assertEquals("arquivo", CrashReporter.streamType("file:///sdcard/film.mkv"));
    }

    @Test public void analiseFpsHint() {
        assertEquals("sem escala", CrashReporter.fpsHint(1.0));
        assertEquals("25/23.976", CrashReporter.fpsHint(25.0 / 23.976));
        assertEquals("23.976/25", CrashReporter.fpsHint(23.976 / 25.0));
        assertTrue(CrashReporter.fpsHint(1.02).startsWith("outra("));
    }

    @Test public void analiseBodyHasTheSameFieldsAsTheMac() {
        String b = CrashReporter.analiseBody("botao_sincronizar", "sincronizada", "áudio aceita", 25.0 / 23.976, 4000, 9.5, "embutida(texto lido: 900 falas, S_TEXT/UTF8, 4000 ms)",
                "https://x.example/film.mkv", 5400_000, 2, "duracao_s=5400 janelas=3\n");
        for (String k : new String[] {"origem=botao_sincronizar tentativa=2", "resultado=sincronizada", "motivo=áudio aceita", "escala=1.042", "deslocamento_ms=4000", "z=9.5",
                "legenda=embutida(texto lido: 900 falas", "fps_dica=25/23.976", "fluxo=https duracao_s=5400", "duracao_s=5400 janelas=3"}) assertTrue(k + " em:\n" + b, b.contains(k));
        assertFalse(b.contains("x.example"));
    }
}
