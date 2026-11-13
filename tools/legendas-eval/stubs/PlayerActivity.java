package io.github.gabrielxyz404.gabiflix;
/** só para compilar SubtitleSync na JVM (teste) */
final class PlayerActivity {
    private static final java.util.regex.Pattern CUE_END = java.util.regex.Pattern.compile("-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})");
    static long lastCueEnd(String text) {
        long max = 0;
        java.util.regex.Matcher m = CUE_END.matcher(text);
        while (m.find()) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long t = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
            if (t > max) max = t;
        }
        return max;
    }
    static byte[] httpGet(String u) throws java.io.IOException { return null; }
}
