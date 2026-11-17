import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Paridade do motor copiado para o app: roda AudioAligner.solve (o mesmo codigo de android/app/src/main/java/.../audiosync) nos vetores
 * do motor original (cues, janelas, resultado esperado) e compara: accepted igual; offsetMs +-1 ms; scale +-1e-6; z +-0,05.
 * Uso: java ParityCheck vectors.json   (sai com 1 se algum vetor divergir)
 */
public class ParityCheck {
    // JSON minimo (so o que os vetores usam)
    static int p; static String s;
    static Object val() {
        ws();
        char c = s.charAt(p);
        if (c == '{') { Map<String, Object> m = new LinkedHashMap<>(); p++; ws(); if (s.charAt(p) == '}') { p++; return m; }
            while (true) { ws(); String k = (String) val(); ws(); p++; m.put(k, val()); ws(); if (s.charAt(p++) == '}') return m; } }
        if (c == '[') { List<Object> l = new ArrayList<>(); p++; ws(); if (s.charAt(p) == ']') { p++; return l; }
            while (true) { l.add(val()); ws(); if (s.charAt(p++) == ']') return l; } }
        if (c == '"') { int q = s.indexOf('"', p + 1); String r = s.substring(p + 1, q); p = q + 1; return r; }
        int q = p; while (q < s.length() && ",}] \n\r\t".indexOf(s.charAt(q)) < 0) q++;
        String t = s.substring(p, q); p = q;
        if (t.equals("true")) return Boolean.TRUE; if (t.equals("false")) return Boolean.FALSE; if (t.equals("null")) return null;
        return Double.parseDouble(t);
    }
    static void ws() { while (p < s.length() && " \n\r\t".indexOf(s.charAt(p)) >= 0) p++; }
    @SuppressWarnings("unchecked") static Map<String, Object> M(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Object> L(Object o) { return (List<Object>) o; }

    public static void main(String[] a) throws Exception {
        s = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"); p = 0;
        Map<String, Object> root = M(val());
        AudioAligner.Params P = new AudioAligner.Params();
        int bad = 0, n = 0;
        for (Object co : L(root.get("cases"))) {
            Map<String, Object> c = M(co);
            long dur = ((Double) c.get("durationMs")).longValue();
            List<Object> cl = L(c.get("cues"));
            Cue[] cues = new Cue[cl.size()];
            for (int i = 0; i < cues.length; i++) { List<Object> q = L(cl.get(i)); cues[i] = new Cue(((Double) q.get(0)).longValue(), ((Double) q.get(1)).longValue(), ((Double) q.get(2)).intValue(), ((Double) q.get(3)) != 0); }
            List<Object> wl = L(c.get("windows"));
            Window[] ws = new Window[wl.size()];
            for (int w = 0; w < ws.length; w++) {
                Map<String, Object> wm = M(wl.get(w));
                byte[] u8 = Base64.getDecoder().decode((String) wm.get("u8"));
                float[] pr = new float[u8.length];
                for (int i = 0; i < pr.length; i++) pr[i] = (u8[i] & 0xff) / 255f;
                ws[w] = new Window(((Double) wm.get("startMs")).longValue(), pr);
            }
            Result r = AudioAligner.solve(cues, ws, dur, P);
            Map<String, Object> e = M(c.get("expect"));
            boolean acc = (Boolean) e.get("accepted");
            double eo = (Double) e.get("offsetMs"), es = (Double) e.get("scale"), ez = (Double) e.get("z");
            double off = r.offsetMs;
            boolean ok = r.accepted == acc && (!acc || (Math.abs(off - eo) <= 1 && Math.abs(r.scale - es) <= 1e-6)) && Math.abs(r.z - ez) <= 0.05;
            n++;
            if (!ok) bad++;
            System.out.printf(Locale.ROOT, "%s %s: motor accepted=%b off=%.1f raw=%.1f k=%.6f z=%.2f | esperado accepted=%b off=%.1f k=%.6f z=%.2f | %s%n",
                    ok ? "OK  " : "DIVERGE", c.get("name"), r.accepted, r.offsetMs, r.rawOffsetMs, r.scale, r.z, acc, eo, es, ez, r.reason);
        }
        System.out.println("vetores: " + n + ", divergentes: " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
