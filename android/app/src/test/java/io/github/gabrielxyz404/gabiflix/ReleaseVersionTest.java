package io.github.gabrielxyz404.gabiflix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class ReleaseVersionTest {
    static final String OLD_NAME = "FramePlay para Android";
    static final String OLD_BODY = "Versão 1.0.110\nBaixe o FramePlay.apk abaixo no celular Android e toque nele para instalar o FramePlay.\n<!-- celular:0123456789abcdef -->";
    // o corpo que o android.yml publica (run 121)
    static final String NEW_BODY = "Versão 2.0\n<!-- build 1.0.121 -->\nBaixe o FramePlay.apk abaixo no celular Android e toque nele para instalar o FramePlay.\n<!-- celular:0123456789abcdef -->";
    static final String TV_BODY = "Versão 2.0\n<!-- build 1.0.121 -->\nApp da TV (Fire TV). Instale pelo Downloader: gabrielxyz404.github.io/GABIFLIX/tv";

    @Test public void oldBody() {
        assertEquals(110, ReleaseVersion.build(OLD_NAME, OLD_BODY));
        assertEquals("1.0.110", ReleaseVersion.marketing(OLD_BODY));
    }

    @Test public void newBody() {
        assertEquals(121, ReleaseVersion.build(OLD_NAME, NEW_BODY));
        assertEquals("2.0", ReleaseVersion.marketing(NEW_BODY));
        assertEquals(121, ReleaseVersion.build("FramePlay para TV", TV_BODY));
        assertEquals("2.0", ReleaseVersion.marketing(TV_BODY));
    }

    /** o código EXATO dos apps já instalados (AppUpdate.fetch até 1.0.N) sobre a release nova */
    @Test public void oldUpdaterFindsNewBuild() {
        String text = OLD_NAME + " " + NEW_BODY;
        Matcher m = Pattern.compile("1\\.0\\.(\\d+)").matcher(text);
        long latest = m.find() ? Long.parseLong(m.group(1)) : -1;
        assertEquals(121, latest);
        assertTrue(latest > 110); // um 1.0.110 instalado vê a 2.0 como nova
    }

    @Test public void missing() {
        assertEquals(-1, ReleaseVersion.build(OLD_NAME, "Versão 2.0"));
        assertNull(ReleaseVersion.marketing(null));
    }

    @Test public void increment() {
        assertEquals("2.1", ReleaseVersion.next("Versão 2.0"));
        assertEquals("2.9", ReleaseVersion.next("Versão 2.8"));
        assertEquals("3.0", ReleaseVersion.next("Versão 2.9"));
        assertEquals("4.0", ReleaseVersion.next("Versão 3.9"));
        assertEquals("2.0", ReleaseVersion.next(OLD_BODY));
        assertEquals("2.0", ReleaseVersion.next(""));
        assertEquals("2.0", ReleaseVersion.next(null));
        assertEquals("2.1", ReleaseVersion.next(NEW_BODY));
    }

    @Test public void phoneAndTvShareOneVersion() {
        assertEquals("2.0", ReleaseVersion.nextOf(OLD_BODY, "Versão 1.0.112"));
        assertEquals("2.4", ReleaseVersion.nextOf("Versão 2.1", "Versão 2.3"));
        assertEquals("3.0", ReleaseVersion.nextOf("", "Versão 2.9"));
    }
}
