package io.github.gabrielxyz404.gabiflix.tv;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Descobre o formato HDR de uma fonte (nome, título, descrição).
 * As MESMAS regras existem em gabiflix/detail.js (função sourceHdr): se mudar uma, mude a outra.
 * Devolve "Dolby Vision", "DV + HDR10", "HDR10+", "HDR10", "HDR", "HLG" ou "" (nada: sem selo).
 * Palavra inteira: HDRip, HDTV, HDCAM etc. NÃO são HDR.
 */
final class SourceHdr {
    private SourceHdr() {}

    private static final String L = "a-z0-9\\u00e0-\\u00ff";

    private static Pattern word(String alt) {
        return Pattern.compile("(?:^|[^" + L + "])(?:" + alt + ")(?![" + L + "])", Pattern.MULTILINE);
    }

    private static final Pattern DV = word("dv|dovi|dolby[ ._-]?vision");
    private static final Pattern HDR10P = word("hdr10(?:\\+|[ ._-]?plus)");
    private static final Pattern HDR10 = word("hdr10");
    private static final Pattern HDR = word("hdr");
    private static final Pattern HLG = word("hlg");

    static String detect(String name, String title) {
        String all = ((name == null ? "" : name) + "\n" + (title == null ? "" : title)).toLowerCase(Locale.ROOT);
        boolean dv = DV.matcher(all).find();
        String hdr = "";
        if (HDR10P.matcher(all).find()) hdr = "HDR10+";
        else if (HDR10.matcher(all).find()) hdr = "HDR10";
        else if (HDR.matcher(all).find()) hdr = "HDR";
        if (dv) return hdr.isEmpty() ? "Dolby Vision" : "DV + " + hdr;
        if (!hdr.isEmpty()) return hdr;
        return HLG.matcher(all).find() ? "HLG" : "";
    }
}
