package io.github.gabrielxyz404.gabiflix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versões das releases "android" e "tv" (Java puro, testado na JVM).
 * Formato novo do corpo: "Versão 2.1" + marcador escondido "<!-- build 1.0.<run> -->".
 * O marcador mantém o "1.0.<run>" que os apps já instalados (até 1.0.N) procuram com o regex 1\.0\.(\d+).
 * Formato antigo: "Versão 1.0.110" (o build é o último número).
 * O que o utilizador vê é só "X.Y"; o número do build (= versionCode = run) é interno.
 */
public final class ReleaseVersion {
    private ReleaseVersion() {}

    private static final Pattern BUILD = Pattern.compile("1\\.0\\.(\\d+)");
    private static final Pattern MARKETING = Pattern.compile("Vers[ãa]o\\s+(\\d+(?:\\.\\d+)+)");
    private static final Pattern XY = Pattern.compile("Vers[ãa]o\\s+(\\d+)\\.(\\d+)(?![\\d.])");

    /** número do build (o run) a partir de name + " " + body, ou -1 */
    public static long build(String name, String body) {
        Matcher m = BUILD.matcher((name == null ? "" : name) + " " + (body == null ? "" : body));
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** "2.1" (ou "1.0.110" no corpo antigo), ou null */
    public static String marketing(String body) {
        if (body == null) return null;
        Matcher m = MARKETING.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** a versão X.Y seguinte à do corpo: sobe 0.1 (2.9 -> 3.0, nunca 2.10); sem versão X.Y (ou release antiga 1.0.N) é 2.0 */
    public static String next(String body) {
        Matcher m = XY.matcher(body == null ? "" : body);
        if (!m.find() || Integer.parseInt(m.group(1)) < 2) return "2.0";
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2)) + 1;
        if (minor > 9) { major++; minor = 0; }
        return major + "." + minor;
    }

    /** o APK é o mesmo no celular e na TV: a versão seguinte à maior das releases publicadas (android e tv) */
    public static String nextOf(String... bodies) {
        String best = "2.0";
        for (String b : bodies) {
            String n = next(b);
            if (cmp(n, best) > 0) best = n;
        }
        return best;
    }

    private static int cmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        int c = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
        return c != 0 ? c : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
    }
}
