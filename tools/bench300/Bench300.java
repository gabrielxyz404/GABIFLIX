import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Bancada dos 300 titulos: roda o laco REAL do app (guardas + plano de janelas + VAD Silero em Java puro + AudioAligner + regra de decisao "fill")
 * sobre as variantes de UMA legenda de UM titulo e escreve uma linha JSON por variante.
 *
 * Uso: Bench300 MOTOR audio16k.wav silero_v5_16k.bin certa.srt variantes.tsv saida.jsonl TITULO [timeout_s]
 *   variantes.tsv: nome TAB familia TAB tipo(C|S|X) TAB g TAB o_ms TAB arquivo_srt ("-" = a certa)
 *     tipo C = legenda ja certa (nao pode ser mexida); S = deslocada/deformada (deve achar); X = legenda de OUTRO titulo (deve recusar)
 *     legenda da variante = g * tempo_certo + o  (como a DerivaEval)
 * O motor (Flow) e compilado em separado: FlowOld = origin/main (3 janelas por densidade, uma resolucao), FlowNew = ramo da deriva (6 espalhadas + 3 densas + extras).
 * Nunca altera o motor: so mede.
 */
public class Bench300 {
    interface Src { Window get(long startMs, long lenMs); }

    static final long MIN_DURATION_MS = 8 * 60_000L;   // AudioSyncCore.MIN_DURATION_MS
    static final int MIN_SPEECH_CUES = 40;              // AudioSyncCore.MIN_SPEECH_CUES

    static short[] pcm;
    static Vad vad;
    /** janelas ja calculadas: chave inicio/duracao -> {Window, ms gastos}. Cada variante soma o custo "de verdade" de cada janela usada (mesmo vindo do cache). */
    static final Map<String, Object[]> cache = new HashMap<>();
    static long curVadMs, curVadActual;
    static volatile boolean abort;

    static Window window(long st, long ln) {
        if (abort) throw new RuntimeException("abortado (tempo limite)");
        String k = st + "/" + ln;
        Object[] e = cache.get(k);
        if (e == null) {
            int s0 = (int) Math.min(pcm.length, st * 16), n = (int) (ln * 16);
            short[] sl = Arrays.copyOfRange(pcm, s0, Math.min(pcm.length, s0 + n));
            long t0 = System.nanoTime();
            float[] p = vad.speechProb(sl, sl.length, 16000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            e = new Object[]{new Window(st, p), ms};
            cache.put(k, e);
            curVadActual += ms;
        }
        curVadMs += (Long) e[1];
        return (Window) e[0];
    }

    /** WAV 16 bits mono lido em blocos (filmes de 3 h+ nao duplicam a memoria) */
    static short[] readWav(String path) throws IOException {
        try (FileChannel ch = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {
            ByteBuffer hdr = ByteBuffer.allocate(12);
            ch.read(hdr, 0);
            long pos = 12, size = ch.size();
            ByteBuffer ck = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            while (pos + 8 <= size) {
                ck.clear();
                ch.read(ck, pos);
                ck.flip();
                byte[] id = new byte[4];
                ck.get(id);
                long sz = ck.getInt() & 0xffffffffL;
                pos += 8;
                if (id[0] == 'd' && id[1] == 'a' && id[2] == 't' && id[3] == 'a') {
                    long avail = size - pos;
                    if (sz == 0 || sz > avail) sz = avail;   // ffmpeg em pipe grava 0/0xFFFFFFFF
                    int n = (int) (sz / 2);
                    short[] out = new short[n];
                    ByteBuffer buf = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
                    int done = 0;
                    long at = pos;
                    while (done < n) {
                        buf.clear();
                        int r = ch.read(buf, at);
                        if (r <= 1) break;
                        buf.flip();
                        int k = Math.min(buf.remaining() / 2, n - done);
                        buf.asShortBuffer().get(out, done, k);
                        done += k;
                        at += k * 2L;
                    }
                    return out;
                }
                pos += sz + (sz & 1);
            }
        }
        return new short[0];
    }

    static Cue[] transform(Cue[] base, double g, double o) {
        Cue[] out = new Cue[base.length];
        for (int i = 0; i < base.length; i++) out[i] = new Cue(Math.round(g * base[i].startMs + o), Math.round(g * base[i].endMs + o), base[i].chars, base[i].speech);
        return out;
    }

    static int countSpeech(Cue[] cues) { int n = 0; for (Cue c : cues) if (c.speech) n++; return n; }

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e12) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.6g", d);
    }

    /** procura NaN/Infinity em qualquer campo double (e nos de Result.Win) do resultado */
    static String scanNaN(Object r) {
        StringBuilder sb = new StringBuilder();
        try {
            scan(r, "", sb, 0);
        } catch (Throwable t) { /* ignora */ }
        return sb.toString();
    }

    static void scan(Object o, String path, StringBuilder sb, int depth) throws Exception {
        if (o == null || depth > 3) return;
        for (Field f : o.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Object v = f.get(o);
            Class<?> t = f.getType();
            if (t == double.class || t == float.class) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) sb.append(path).append(f.getName()).append('=').append(d).append(' ');
            } else if (t.isArray() && v != null && t.getComponentType() == double.class) {
                for (double d : (double[]) v) if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append(path).append(f.getName()).append("[]=").append(d).append(' '); break; }
            } else if (t.isArray() && v != null && !t.getComponentType().isPrimitive() && t.getComponentType() != String.class && depth < 2) {
                Object[] a = (Object[]) v;
                for (int i = 0; i < Math.min(a.length, 20); i++) scan(a[i], path + f.getName() + "[" + i + "].", sb, depth + 1);
            }
        }
    }

    static final class Out {
        Result r;
        String diag = "";
        Throwable err;
        long vadMs, solveMs, totalMs;
        boolean done;
    }

    public static void main(String[] a) throws Exception {
        String engine = a[0], title = a[6];
        long timeoutS = a.length > 7 ? Long.parseLong(a[7]) : 240;
        pcm = readWav(a[1]);
        long dur = pcm.length / 16L;
        Cue[] ref0 = Cue.parseSrt(new String(Files.readAllBytes(Paths.get(a[3])), StandardCharsets.UTF_8));
        ArrayList<Cue> keep = new ArrayList<>();
        for (Cue c : ref0) if (c.endMs <= dur - 2000) keep.add(c);   // so as falas que cabem no audio (como a DerivaEval)
        Cue[] certa = keep.toArray(new Cue[0]);
        vad = new JavaSileroVad(Files.readAllBytes(Paths.get(a[2])), Integer.parseInt(System.getenv().getOrDefault("VAD_THREADS", "2")), null);
        List<String> lines = Files.readAllLines(Paths.get(a[4]), StandardCharsets.UTF_8);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(a[5], true), StandardCharsets.UTF_8))) {
            pw.println("{\"rec\":\"meta\",\"title\":" + js(title) + ",\"engine\":" + js(engine) + ",\"dur_s\":" + dur / 1000 + ",\"cues_ref\":" + certa.length + ",\"java\":" + js(System.getProperty("java.version")) + ",\"maxMemMB\":" + Runtime.getRuntime().maxMemory() / 1048576 + "}");
            pw.flush();
            for (String ln : lines) {
                if (ln.trim().isEmpty()) continue;
                String[] f = ln.split("\t", -1);
                String name = f[0], fam = f[1], type = f[2];
                double g = Double.parseDouble(f[3]), o = Double.parseDouble(f[4]);
                Cue[] base;
                String kindNote = "";
                try {
                    base = f[5].equals("-") ? certa : Cue.parseSrt(new String(Files.readAllBytes(Paths.get(f[5])), StandardCharsets.UTF_8));
                } catch (Throwable t) {
                    pw.println("{\"rec\":\"var\",\"title\":" + js(title) + ",\"engine\":" + js(engine) + ",\"var\":" + js(name) + ",\"fam\":" + js(fam) + ",\"type\":" + js(type) + ",\"kind\":\"ERROR\",\"exc\":" + js("leitura da legenda: " + t) + "}");
                    pw.flush();
                    continue;
                }
                final Cue[] cues = transform(base, g, o);
                // ---- o laco do app
                final Out out = new Out();
                curVadMs = 0;
                curVadActual = 0;
                abort = false;
                Thread th = new Thread(() -> {
                    long t0 = System.nanoTime();
                    try {
                        if (countSpeech(cues) < MIN_SPEECH_CUES) { out.diag = "GUARDA poucas falas na legenda"; out.r = null; }
                        else if (dur < MIN_DURATION_MS) { out.diag = "GUARDA video curto (<8 min)"; out.r = null; }
                        else {
                            StringBuilder dg = new StringBuilder();
                            out.r = Flow.run(cues, dur, Bench300::window, dg);
                            out.diag = dg.toString();
                        }
                    } catch (Throwable t) {
                        out.err = t;
                    }
                    out.totalMs = (System.nanoTime() - t0) / 1_000_000;
                    out.done = true;
                }, "bench-var");
                long t0 = System.nanoTime();
                th.start();
                th.join(timeoutS * 1000);
                boolean timedOut = !out.done;
                if (timedOut) {
                    abort = true;
                    th.interrupt();
                    th.join(60_000);
                }
                out.vadMs = curVadMs;
                out.solveMs = Math.max(0, out.totalMs - curVadActual);   // o resto do tempo medido alem do VAD que rodou de verdade nesta variante = alinhador (solve)
                StringBuilder j = new StringBuilder();
                j.append("{\"rec\":\"var\",\"title\":").append(js(title)).append(",\"engine\":").append(js(engine)).append(",\"var\":").append(js(name)).append(",\"fam\":").append(js(fam)).append(",\"type\":").append(js(type))
                        .append(",\"g\":").append(num(g)).append(",\"o\":").append(num(o));
                if (timedOut) {
                    j.append(",\"kind\":\"TIMEOUT\",\"exc\":").append(js("passou de " + timeoutS + " s; zumbi=" + th.isAlive())).append("}");
                    pw.println(j);
                    pw.flush();
                    if (th.isAlive()) { pw.println("{\"rec\":\"abort\",\"title\":" + js(title) + ",\"engine\":" + js(engine) + "}"); pw.flush(); Runtime.getRuntime().halt(3); }
                    continue;
                }
                if (out.err != null) {
                    StringWriter sw = new StringWriter();
                    out.err.printStackTrace(new PrintWriter(sw));
                    String[] st = sw.toString().split("\n");
                    j.append(",\"kind\":\"ERROR\",\"exc\":").append(js(out.err + " @ " + (st.length > 1 ? st[1].trim() : "") + (st.length > 2 ? " / " + st[2].trim() : ""))).append("}");
                    pw.println(j);
                    pw.flush();
                    continue;
                }
                Result r = out.r;
                if (r == null) {   // guarda do app (poucas falas / curto): o app nem analisa
                    j.append(",\"kind\":\"SKIPPED\",\"acc\":0,\"cls\":\"pulado\",\"reason\":").append(js(out.diag)).append("}");
                    pw.println(j);
                    pw.flush();
                    continue;
                }
                // ---- AudioSyncCore.fill (currentDelay = 0, nowMs = 0): aceita? por partes? aplica?
                String kind;
                double sc = 1.0, off = 0.0;
                boolean accepted = r.accepted;
                AudioAligner.Params P = new AudioAligner.Params();
                int targetDelay = 0;
                if (!accepted) kind = "REFUSED";
                else if (r.segments != null && r.segments.length > 1) { kind = "REFUSED"; accepted = false; out.diag += " [por partes: o app nao aplica]"; }
                else {
                    double aa = r.scale, oo = r.rawOffsetMs;
                    double cue = (0 - oo) / aa, target = (aa - 1.0) * cue + oo;
                    target = Math.max(-600_000, Math.min(600_000, target));
                    targetDelay = (int) (Math.round(target / 10.0) * 10);
                    boolean scaled = Math.abs(aa - 1.0) > 1e-9;
                    boolean fix = scaled || Math.abs(targetDelay - 0) >= P.deadbandMs;
                    kind = fix ? "FIX" : "OK";
                    if (fix) { sc = aa; off = oo; }
                }
                // ---- erro contra o tempo verdadeiro (so C e S)
                double eMax = -1, eP95 = -1, eMed = -1, bMax = -1;
                if (!type.equals("X")) {
                    ArrayList<Double> errs = new ArrayList<>(), before = new ArrayList<>();
                    for (Cue c : certa) {
                        long truth = c.startMs;
                        if (truth < 60_000 || truth > dur - 60_000) continue;
                        double sp = g * truth + o;
                        errs.add(Math.abs(sc * sp + off - truth));
                        before.add(Math.abs(sp - truth));
                    }
                    Collections.sort(errs);
                    Collections.sort(before);
                    if (!errs.isEmpty()) { eMax = errs.get(errs.size() - 1); eP95 = errs.get((int) (errs.size() * 0.95)); eMed = errs.get(errs.size() / 2); bMax = before.get(before.size() - 1); }
                }
                String cls;
                boolean acc = kind.equals("FIX") || kind.equals("OK");
                if (type.equals("C")) cls = kind.equals("FIX") ? "MEXIDA" : "intacta";
                else if (type.equals("X")) cls = acc ? (kind.equals("FIX") ? "FALSO" : "FALSO-ok") : "recusou";
                else cls = !acc ? "recusou" : (eP95 <= 400 && eMax <= 800 ? "certo" : "ERRADO");
                String nan = scanNaN(r);
                j.append(",\"kind\":").append(js(kind)).append(",\"acc\":").append(acc ? 1 : 0).append(",\"cls\":").append(js(cls))
                        .append(",\"inSync\":").append(r.inSync ? 1 : 0).append(",\"scale\":").append(num(r.scale)).append(",\"raw\":").append(num(r.rawOffsetMs)).append(",\"target\":").append(targetDelay)
                        .append(",\"z\":").append(num(r.z)).append(",\"margin\":").append(num(r.margin)).append(",\"ncc\":").append(num(r.nccMean)).append(",\"f1\":").append(num(r.f1))
                        .append(",\"wa\":").append(r.winsAgree).append(",\"wv\":").append(r.winsValid).append(",\"wdev\":").append(num(r.winMaxDev)).append(",\"bv\":").append(r.blocksValid).append(",\"ba\":").append(r.blocksAgree)
                        .append(",\"win\":").append(r.windows).append(",\"rounds\":").append(r.rounds).append(",\"known\":").append(r.scaleKnown ? 1 : 0)
                        .append(",\"eMax\":").append(num(eMax)).append(",\"eP95\":").append(num(eP95)).append(",\"eMed\":").append(num(eMed)).append(",\"bMax\":").append(num(bMax))
                        .append(",\"vadMs\":").append(out.vadMs).append(",\"solveMs\":").append(out.solveMs).append(",\"ms\":").append(out.vadMs + out.solveMs)
                        .append(",\"nan\":").append(js(nan)).append(",\"reason\":").append(js(r.reason == null ? "" : r.reason.trim())).append(",\"diag\":").append(js(out.diag.trim())).append("}");
                pw.println(j);
                pw.flush();
            }
            pw.println("{\"rec\":\"end\",\"title\":" + js(title) + ",\"engine\":" + js(engine) + ",\"cache_windows\":" + cache.size() + "}");
        }
        ((AutoCloseable) vad).close();
    }
}
