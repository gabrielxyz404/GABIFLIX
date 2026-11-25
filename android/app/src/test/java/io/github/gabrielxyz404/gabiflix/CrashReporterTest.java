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
}
