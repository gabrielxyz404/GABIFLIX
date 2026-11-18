import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Prova da deriva (escala de fps) com audio REAL do filme (inteiro ou recortado) e a legenda certa (alass). Para cada variante da legenda
 * (certa, +3 s, -2 s, escalas 1,001 / 0,999 / 25/23,976 / 23,976/25 com deslocamentos, e legendas de OUTROS filmes) roda o laço do app
 * (Flow: 3 janelas de 90 s + janelas extras se o motor suspeita de deriva), aplica o modelo achado as falas da variante e compara com o tempo verdadeiro.
 * Uso: DerivaEval audio16k.wav certa.srt SILERO_WEIGHTS.bin MODO(old|spread|dense) SLUG [outro1.srt outro2.srt ...]
 * Saida: uma linha "R ..." por variante.
 */
public class DerivaEval {
    interface Src { Window get(long startMs, long lenMs); }

    static short[] pcm;
    static Vad vad;
    static final Map<String, Window> cache = new HashMap<>();
    static long vadMs;

    static Window window(long st, long ln) {
        String k = st + "/" + ln;
        Window w = cache.get(k);
        if (w == null) {
            int s0 = (int) Math.min(pcm.length, st * 16), n = (int) (ln * 16);
            short[] sl = Arrays.copyOfRange(pcm, s0, Math.min(pcm.length, s0 + n));
            long t0 = System.nanoTime();
            w = new Window(st, vad.speechProb(sl, sl.length, 16000));
            vadMs += (System.nanoTime() - t0) / 1_000_000;
            cache.put(k, w);
        }
        return w;
    }

    static short[] readWav(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        int p = 12;
        while (p + 8 <= b.length) {
            long sz = (b[p + 4] & 0xffL) | (b[p + 5] & 0xffL) << 8 | (b[p + 6] & 0xffL) << 16 | (b[p + 7] & 0xffL) << 24;
            boolean data = b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a';
            p += 8;
            if (data) { int n = (int) Math.min((b.length - p) / 2, sz / 2); short[] o = new short[n]; for (int i = 0; i < n; i++) o[i] = (short) ((b[p + 2 * i] & 0xff) | (b[p + 2 * i + 1] << 8)); return o; }
            p += (int) sz;
        }
        return new short[0];
    }

    static Cue[] transform(Cue[] base, double g, double o) {
        Cue[] out = new Cue[base.length];
        for (int i = 0; i < base.length; i++) out[i] = new Cue(Math.round(g * base[i].startMs + o), Math.round(g * base[i].endMs + o), base[i].chars, base[i].speech);
        return out;
    }

    public static void main(String[] a) throws Exception {
        pcm = readWav(a[0]);
        long dur = pcm.length / 16L;
        Cue[] certa = Cue.parseSrt(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
        // so as falas que cabem no audio
        ArrayList<Cue> keep = new ArrayList<>();
        for (Cue c : certa) if (c.endMs <= dur - 2000) keep.add(c);
        certa = keep.toArray(new Cue[0]);
        String[] modes = a[3].split(","); String slug = a[4];
        vad = new JavaSileroVad(Files.readAllBytes(Paths.get(a[2])), Integer.parseInt(System.getenv().getOrDefault("VAD_THREADS", "2")), null);
        // variantes: {nome, g, o, tipo}  tipo: C = certa (nao pode mexer), S = deve achar (escala/deslocamento), X = de outro filme (deve recusar)
        ArrayList<Object[]> vars = new ArrayList<>();
        vars.add(new Object[]{"certa", 1.0, 0.0, "C", certa});
        vars.add(new Object[]{"certa+400", 1.0, 400.0, "C", certa});
        vars.add(new Object[]{"mais3", 1.0, 3000.0, "S", certa});
        vars.add(new Object[]{"menos2", 1.0, -2000.0, "S", certa});
        double[] gs = {24.0 / 23.976, 23.976 / 24.0, 25.0 / 23.976, 23.976 / 25.0};
        String[] gn = {"1.001", "0.999", "25/23.976", "23.976/25"};
        double[] os = {0, 1800, -2500};
        for (int i = 0; i < gs.length; i++) for (double o : os) vars.add(new Object[]{"g" + gn[i] + "|o" + (o >= 0 ? "+" : "") + (long) o, gs[i], o, "S", certa});
        for (int k = 5; k < a.length; k++) {
            Cue[] oc = Cue.parseSrt(new String(Files.readAllBytes(Paths.get(a[k])), "UTF-8"));
            int n = k - 4;
            vars.add(new Object[]{"outro" + n, 1.0, 0.0, "X", oc});
            vars.add(new Object[]{"outro" + n + "|g1.001", 24.0 / 23.976, 0.0, "X", oc});
            vars.add(new Object[]{"outro" + n + "|g25/23.976", 25.0 / 23.976, -1500.0, "X", oc});
        }
        for (Object[] v : vars) {
            String name = (String) v[0];
            double g = (Double) v[1], o = (Double) v[2];
            String type = (String) v[3];
            Cue[] src = (Cue[]) v[4];
            Cue[] cues = type.equals("X") ? transform(src, g, o) : transform(src, g, o);
            for (String mode : modes) {
            long t0 = System.nanoTime();
            StringBuilder diag = new StringBuilder();
            Result r;
            try {
                r = Flow.run(cues, dur, DerivaEval::window, mode, diag);
            } catch (Throwable t) {
                System.out.println("R film=" + slug + " var=" + name + " modo=" + mode + " ERRO=" + t);
                continue;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // modelo aplicado pelo app: aceito e fora da banda morta/escala 1 -> scale*s'+rawOffset; senao a legenda fica como esta
            boolean applied = r.accepted && !r.inSync;
            double sc = applied ? r.scale : 1.0, off = applied ? r.rawOffsetMs : 0.0;
            ArrayList<Double> errs = new ArrayList<>(), before = new ArrayList<>();
            if (!type.equals("X")) {
                for (int i = 0; i < certa.length; i++) {
                    long truth = certa[i].startMs;
                    if (truth < 60_000 || truth > dur - 60_000) continue;
                    double sp = g * truth + o;
                    errs.add(Math.abs(sc * sp + off - truth));
                    before.add(Math.abs(sp - truth));
                }
            }
            Collections.sort(errs);
            Collections.sort(before);
            double eMax = errs.isEmpty() ? -1 : errs.get(errs.size() - 1), eP95 = errs.isEmpty() ? -1 : errs.get((int) (errs.size() * 0.95)), bMax = before.isEmpty() ? -1 : before.get(before.size() - 1);
            boolean unchanged = !applied;
            String cls;
            if (type.equals("C")) cls = unchanged ? "intacta" : "MEXIDA";
            else if (type.equals("X")) cls = r.accepted ? "FALSO" : "recusou";
            else cls = !r.accepted ? "recusou" : (eP95 <= 400 && eMax <= 800 ? "certo" : "ERRADO");
            System.out.printf(Locale.ROOT, "R film=%s var=%s modo=%s tipo=%s classe=%s acc=%d inSync=%d escala=%.6f desl=%.0f z=%.1f janelas=%d rodadas=%d erroMax=%.0f erroP95=%.0f antesMax=%.0f ms=%d motivo=%s | %s%n",
                    slug, name, mode, type, cls, r.accepted ? 1 : 0, r.inSync ? 1 : 0, r.scale, r.rawOffsetMs, r.z, r.windows, r.rounds, eMax, eP95, bMax, ms, r.reason.trim().replace(' ', '_'), diag.toString().trim());
            System.out.flush();
            }
        }
        System.out.println("VAD ms=" + vadMs + " janelas-calculadas=" + cache.size() + " dur=" + dur / 1000 + "s");
        ((AutoCloseable) vad).close();
    }
}
