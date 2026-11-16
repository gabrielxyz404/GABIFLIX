import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.util.*;

/**
 * Extrai atributos do DspVad para treino: o filme é cortado em trechos de 90 s (como o aparelho fará), os quadros são rotulados pelas
 * legendas "de verdade" (união de todas as legendas com verdade): 1 = dentro de uma fala (com folga de 0,15 s), 0 = a mais de 0,6 s de
 * qualquer fala. Guarda 1 em cada 3 quadros rotulados, junto com a probabilidade do Silero e a decisão do WebRTC (para comparar).
 * Uso: FeatDump FILM_DIR OUT_FILE
 */
public class FeatDump {
    public static void main(String[] a) throws Exception {
        Film f = new Film(new File(a[0]));
        ArrayList<Cue> all = new ArrayList<>();
        for (Film.Sub s : f.subs) if (s.truth && s.truthCues != null) all.addAll(Arrays.asList(s.truthCues));
        if (all.isEmpty()) { System.err.println("sem legendas de verdade: " + f.slug); new File(a[1]).delete(); return; }
        int T = (int) (f.durationMs / 10);
        byte[] lab = new byte[T]; // 0 = fora, 1 = fala, 2 = ignorar
        Arrays.fill(lab, (byte) 0);
        boolean[] near = new boolean[T];
        for (Cue c : all) {
            if (!c.speech) continue;
            long e = c.speechEnd(true);
            for (long t = Math.max(0, c.startMs - 600); t < e + 600 && t / 10 < T; t += 10) near[(int) (t / 10)] = true;
            for (long t = c.startMs + 150; t < e - 100 && t / 10 < T; t += 10) lab[(int) (t / 10)] = 1;
        }
        for (int i = 0; i < T; i++) if (lab[i] == 0 && near[i]) lab[i] = 2;
        boolean hasS = f.hasTrack("vad_silero"), hasW = f.hasTrack("vad_webrtc_m3");
        long chunk = 90000;
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[1])));
        int count = 0;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream bo = new DataOutputStream(body);
        Random r = new Random(1);
        for (long st = 0; st + chunk <= f.durationMs; st += chunk) {
            short[] pcm = f.pcm(st, chunk);
            float[][] ft = DspVad.features(pcm, pcm.length, 16000);
            float[] sil = hasS ? f.track("vad_silero", st, chunk) : null;
            float[] wr = hasW ? f.track("vad_webrtc_m3", st, chunk) : null;
            for (int t = 0; t < ft.length; t++) {
                int g = (int) (st / 10) + t;
                if (g >= T || lab[g] == 2) continue;
                if (r.nextInt(3) != 0) continue;
                for (int i = 0; i < DspVad.NF; i++) bo.writeFloat(ft[t][i]);
                bo.writeByte(lab[g]);
                bo.writeFloat(sil != null ? sil[t] : -1f);
                bo.writeFloat(wr != null ? wr[t] : -1f);
                count++;
            }
        }
        out.writeInt(count);
        out.write(body.toByteArray());
        out.close();
        System.err.printf("%s: %d quadros rotulados (%.0f%% fala)%n", f.slug, count, 0.0);
    }
}
