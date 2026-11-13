package android.net;
/** só para compilar SubtitleSync na JVM (teste) */
public class Uri {
    final String s;
    Uri(String s) { this.s = s; }
    public static Uri parse(String s) { return new Uri(s); }
    public static Uri fromFile(java.io.File f) { return new Uri("file://" + f.getPath()); }
    public String getLastPathSegment() { int i = s.lastIndexOf('/'); return i < 0 ? s : s.substring(i + 1); }
    public static String encode(String s) { return s; }
    public static String encode(String s, String allow) { return s; }
    public String toString() { return s; }
}
