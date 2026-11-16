import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.util.*;

/**
 * Gera vetores de teste de paridade para o port Swift: entradas (legenda, janelas de fala em uint8 base64, parâmetros) e a saída do motor Java.
 * Uso: Vectors OUT.json [N]. O Swift roda o mesmo motor sobre as entradas e compara (tolerâncias em DESIGN.md).
 */
public class Vectors {
    static String b64(float[] p) {
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Math.round(Math.max(0, Math.min(1, p[i])) * 255);
        return Base64.getEncoder().encodeToString(b);
    }

    static void cuesJson(StringBuilder sb, Cue[] cues) {
        sb.append('[');
        for (int i = 0; i < cues.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(cues[i].startMs).append(',').append(cues[i].endMs).append(',').append(cues[i].chars).append(',').append(cues[i].speech ? 1 : 0).append(']');
        }
        sb.append(']');
    }

    public static void main(String[] a) throws Exception {
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 12;
        Random r = new Random(2026);
        long dur = 95 * 60000L;
        AudioAligner.Params P = new AudioAligner.Params();
        double[][] cases = {{0, 1.0}, {7000, 1.0}, {-45000, 1.0}, {120000, 1.0}, {0, 25 / 23.976}, {12000, 23.976 / 25}, {-2000, 25 / 24.0}, {0, 1.0}};
        StringBuilder sb = new StringBuilder("{\"params\":{\"frameMs\":" + P.frameMs + ",\"blockMs\":" + P.blockMs + ",\"lagFrames\":" + P.lagFrames + "},\"cases\":[\n");
        for (int c = 0; c < n; c++) {
            double[] cs = cases[c % cases.length];
            boolean neg = c >= cases.length;            // a 2ª volta: legenda de outro filme (deve recusar)
            Cue[] truth = SelfTest.makeCues(r, dur);
            float[] track = SelfTest.truthTrack(truth, dur, r, 0.75);
            Cue[] src = neg ? SelfTest.makeCues(r, dur) : truth;
            Cue[] dist = new Cue[src.length];
            for (int i = 0; i < src.length; i++) {
                Cue q = src[i];
                dist[i] = new Cue(Math.round((q.startMs - cs[0]) / cs[1]), Math.round((q.endMs - cs[0]) / cs[1]), q.chars, true);
            }
            double[] fr = {0.2, 0.5, 0.8};
            Window[] ws = new Window[3];
            for (int w = 0; w < 3; w++) {
                long st = (long) (dur * fr[w]) / 10 * 10;
                float[] p = Arrays.copyOfRange(track, (int) (st / 10), (int) (st / 10) + 6000);
                // quantiza como o Swift receberá (uint8) para as duas implementações verem a mesma entrada
                for (int i = 0; i < p.length; i++) p[i] = Math.round(Math.max(0, Math.min(1, p[i])) * 255) / 255f;
                ws[w] = new Window(st, p);
            }
            Result res = AudioAligner.solve(dist, ws, dur, P);
            if (c > 0) sb.append(",\n");
            sb.append("{\"name\":\"").append(neg ? "neg" : "pos").append(c).append("\",\"durationMs\":").append(dur).append(",\"cues\":");
            cuesJson(sb, dist);
            sb.append(",\"windows\":[");
            for (int w = 0; w < 3; w++) { if (w > 0) sb.append(','); sb.append("{\"startMs\":").append(ws[w].startMs).append(",\"u8\":\"").append(b64(ws[w].p)).append("\"}"); }
            sb.append("],\"expect\":{\"accepted\":").append(res.accepted).append(",\"scale\":").append(res.scale).append(",\"offsetMs\":").append(res.offsetMs)
              .append(",\"z\":").append(res.z).append(",\"margin\":").append(res.margin).append(",\"nccMean\":").append(res.nccMean).append(",\"blocksAgree\":").append(res.blocksAgree)
              .append(",\"blocksValid\":").append(res.blocksValid).append(",\"scaleKnown\":").append(res.scaleKnown).append("}}");
        }
        sb.append("\n]}\n");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(a[0]), "UTF-8")) { w.write(sb.toString()); }
        System.err.println("vetores: " + n);
    }
}
