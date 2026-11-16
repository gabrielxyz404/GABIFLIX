import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.util.*;

/**
 * Avaliação do motor no corpus (um filme por execução; várias execuções formam a tabela final).
 * Uso: Eval FILM_DIR OUT_CSV VADS CFGS [CATS]
 *   VADS: lista separada por vírgula (silero, pywebrtc3, webrtc0..3, dsp)
 *   CFGS: lista de NxLEN em segundos (3x60,3x90 ...)
 *   CATS: offset,scale,combo,piece1,piece2,natural,neg (padrão: todas)
 */
public class Eval {
    // ---------------------------------------------------------------- VAD
    interface VadSrc { float[] get(Film f, long startMs, long durMs) throws IOException; }

    static VadSrc vadSrc(String name) {
        if (name.equals("silero")) return (f, s, d) -> f.track("vad_silero", s, d);
        if (name.startsWith("pywebrtc")) { final String t = "vad_webrtc_m" + name.substring(8); return (f, s, d) -> f.track(t, s, d); }
        if (name.startsWith("webrtc")) {
            final int mode = Integer.parseInt(name.substring(6));
            return (f, s, d) -> {
                short[] p = f.pcm(s, d);
                return new WebRtcVad(mode).speechProb(p, p.length, 16000);
            };
        }
        if (name.startsWith("dsp")) {
            return (f, s, d) -> {
                short[] p = f.pcm(s, d);
                return DspVad.forName(name).speechProb(p, p.length, 16000);
            };
        }
        throw new IllegalArgumentException("VAD desconhecido: " + name);
    }

    // ---------------------------------------------------------------- janelas
    static long[][] plan(long durMs, int n, long lenMs) {
        long[][] w = new long[n][2];
        for (int i = 0; i < n; i++) {
            double fr = n == 1 ? 0.5 : 0.15 + 0.70 * i / (n - 1);
            long st = (long) (durMs * fr - lenMs / 2.0);
            st = Math.max(0, Math.min(st, durMs - lenMs));
            w[i][0] = st / 10 * 10; w[i][1] = lenMs;
        }
        return w;
    }

    static Window[] windows(Film f, VadSrc v, long[][] pl) throws IOException {
        Window[] ws = new Window[pl.length];
        for (int i = 0; i < pl.length; i++) ws[i] = new Window(pl[i][0], v.get(f, pl[i][0], pl[i][1]));
        return ws;
    }

    // ---------------------------------------------------------------- distorção
    /** legenda tal que corrigi-la com (a, b por trecho) devolve a verdade: s = (u - b)/a */
    static Cue[] distort(Cue[] truth, double a, long[] cuts, double[] offs) {
        Cue[] o = new Cue[truth.length];
        for (int i = 0; i < truth.length; i++) {
            Cue c = truth[i];
            int q = 0;
            while (q < cuts.length && c.startMs >= cuts[q]) q++;
            double b = offs[q];
            o[i] = new Cue(Math.round((c.startMs - b) / a), Math.round((c.endMs - b) / a), c.chars, c.speech);
        }
        return o;
    }

    /** como distort, mas torna realista o caso de deslocamento positivo (legenda de uma versão sem o trecho extra): remove da legenda as
     *  falas anteriores ao corte que passariam do primeiro tempo do trecho seguinte. Devolve {Cue[], long[] verdade}. */
    static Object[] distortPieces(Cue[] truth, double a, long[] cuts, double[] offs) {   // devolve {Cue[], int[] indices mantidos}
        Cue[] d = distort(truth, a, cuts, offs);
        boolean[] drop = new boolean[d.length];
        for (int k = 0; k < cuts.length; k++) {
            int first = -1;
            for (int i = 0; i < truth.length; i++) if (truth[i].startMs >= cuts[k]) { first = i; break; }
            if (first < 0) continue;
            long lim = d[first].startMs - 1000;
            for (int i = first - 1; i >= 0 && truth[i].startMs >= cuts[k] - 200_000; i--) if (d[i].startMs >= lim) drop[i] = true;
        }
        ArrayList<Cue> dc = new ArrayList<>();
        ArrayList<Integer> keep = new ArrayList<>();
        for (int i = 0; i < d.length; i++) if (!drop[i]) { dc.add(d[i]); keep.add(i); }
        int[] k2 = new int[keep.size()];
        for (int i = 0; i < k2.length; i++) k2[i] = keep.get(i);
        return new Object[]{dc.toArray(new Cue[0]), k2};
    }

    /** resolve pedindo mais janelas enquanto o motor pedir (AudioSyncSession), como faria o app */
    static Result session(Film f, VadSrc v, Cue[] cues, Window[] ws0, AudioAligner.Params P, int maxRounds) throws IOException {
        AudioSyncSession ss = new AudioSyncSession(cues, f.durationMs, P);
        for (Window w : ws0) ss.addWindow(w);
        Result r = ss.solve();
        for (int round = 0; round < maxRounds && r.more != null && r.more.length > 0; round++) {
            for (long[] m : r.more) ss.addWindow(new Window(m[0], v.get(f, m[0], m[1])));
            r = ss.solve();
        }
        return r;
    }

    /** em modo clipe só as falas dentro do trecho analisado contam (extrapolar a escala de 20 min para 2 h amplifica o erro e não é o que o app faz: lá as janelas cobrem o filme) */
    static long CLIPMS = Long.MAX_VALUE;

    static double[] errStats(Result r, Cue[] dist, long[] truthStart, boolean seg) {
        int n = Math.min(dist.length, truthStart.length);
        double[] e = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (truthStart[i] >= CLIPMS) continue;
            e[m++] = Math.abs((seg ? r.applySegments(dist[i].startMs) : r.apply(dist[i].startMs)) - truthStart[i]);
        }
        if (m == 0) return new double[]{-1, -1, -1};
        e = Arrays.copyOf(e, m);
        Arrays.sort(e);
        return new double[]{e[m / 2], e[(int) (m * 0.9)], e[m - 1]};
    }

    // ---------------------------------------------------------------- saída
    static PrintWriter out;
    static long[] REL;
    static boolean REL_OK;

    static void row(Film f, Film.Sub s, String vad, String cfg, String cat, String param, Result r, double[] err, double[] rel, long tookMs, String extra) {
        out.printf(Locale.ROOT, "%s,%s,%s,%s,%s,%s,%s,%d,%.0f,%.0f,%.1f,%.6f,%.2f,%.3f,%.3f,%.3f,%d,%d,%.3f,%.3f,%d,%d,%.0f,%.0f,%s%n",
                f.slug, s == null ? "-" : s.name, vad, cfg, cat, param.replace(',', ';'), "-", r.accepted ? 1 : 0,
                err == null ? -1 : err[0], err == null ? -1 : err[1], r.offsetMs, r.scale, r.z, r.margin, r.nccMean, r.nccMin, r.blocksAgree, r.blocksValid, r.f1, r.confidence,
                r.audioMs, tookMs, rel == null ? -1 : rel[0], rel == null ? -1 : rel[1], (extra + " " + r.reason).trim().replace(',', ';'));
        out.flush();
    }

    static final double[] OFFS = {-120, -45, -15, -5, -1.5, -0.5, 0.5, 1.5, 5, 15, 45, 120, -300, 300, -590, 590};
    static final double[][] RATIOS = {{25 / 23.976, 0}, {23.976 / 25, 0}, {25 / 24.0, 0}, {24 / 25.0, 0}, {24 / 23.976, 0}, {23.976 / 24, 0}};
    static final String[] RNAME = {"25/23.976", "23.976/25", "25/24", "24/25", "24/23.976", "23.976/24"};

    public static void main(String[] args) throws Exception {
        File fd = new File(args[0]);
        out = new PrintWriter(new FileWriter(args[1], true));
        String[] vads = args[2].split(",");
        String[] cfgs = args[3].split(",");
        Set<String> cats = new HashSet<>(Arrays.asList((args.length > 4 ? args[4] : "offset,scale,combo,piece1,piece2,natural").split(",")));
        int maxSubs = args.length > 5 ? Integer.parseInt(args[5]) : 3;
        String subsDir = args.length > 6 ? args[6] : "";
        String dw = System.getenv("DSPW_DIR");
        if (dw != null && !dw.isEmpty()) {
            File w = new File(dw, fd.getName() + ".w");
            System.setProperty("dsp.weights", (w.exists() ? w : new File(dw, "all.w")).getPath());
        }
        AudioAligner.Params P = new AudioAligner.Params();
        Film f = new Film(fd);
        CLIPMS = f.durationMs < f.fullDurationMs ? f.durationMs : Long.MAX_VALUE;
        System.err.printf("== %s: %.0f s, %d legendas (%d verdade)%n", f.slug, f.durationMs / 1000.0, f.subs.size(), countTruth(f));
        // legendas de verdade: até maxSubs (prefere 2 PT + 1 EN)
        ArrayList<Film.Sub> tsubs = new ArrayList<>();
        int pt = 0, en = 0;
        for (Film.Sub s : f.subs) {
            if (!s.truth || s.truthCues == null) continue;
            if (s.pt() && pt < Math.max(1, maxSubs - 1)) { tsubs.add(s); pt++; }
            else if (!s.pt() && en < 1) { tsubs.add(s); en++; }
        }
        Random rnd = new Random(42 + f.slug.hashCode());
        for (String vname : vads) {
            VadSrc v = vadSrc(vname);
            if (vname.equals("silero") && !f.hasTrack("vad_silero")) continue;
            for (String cfg0 : cfgs) {
                String[] q = cfg0.split("x");
                final String cfg = cfg0 + ("dens".equals(System.getenv("PLAN")) ? "d" : "");
                int nw = Integer.parseInt(q[0]);
                long len = Long.parseLong(q[1]) * 1000;
                final boolean dens = "dens".equals(System.getenv("PLAN"));
                final int nwF = nw; final long lenF = len;
                Window[] wsFixed = dens ? null : windows(f, v, plan(f.durationMs, nw, len));
                for (Film.Sub s : tsubs) {
                    Cue[] truth = s.truthCues;
                    Window[] ws = dens ? windows(f, v, AudioSyncSession.planDense(s.orig, f.durationMs, nwF, lenF)) : wsFixed;
                    long[] ts = new long[truth.length];
                    for (int i = 0; i < ts.length; i++) ts[i] = truth[i].startMs;
                    // alvo relativo: onde o próprio motor põe a legenda SEM distorção (tira o viés do oráculo; mede só a recuperação da distorção)
                    Result r0 = session(f, v, truth, ws, P, 6);
                    REL = new long[ts.length];
                    for (int i = 0; i < ts.length; i++) REL[i] = r0.accepted ? Math.round(r0.applySegments(truth[i].startMs)) : ts[i];
                    REL_OK = r0.accepted;
                    if (cats.contains("recheck")) recheckEval(f, v, s, vname, P, truth, r0);
                    if (cats.contains("offset")) for (double o : OFFS) run(f, v, s, vname, cfg, "offset", String.valueOf(o), P, ws, truth, ts, 1.0, new long[0], new double[]{o * 1000}, false);
                    if (cats.contains("scale")) for (int k = 0; k < RATIOS.length; k++) run(f, v, s, vname, cfg, "scale", RNAME[k], P, ws, truth, ts, RATIOS[k][0], new long[0], new double[]{0}, false);
                    if (cats.contains("combo")) for (int k = 0; k < RATIOS.length; k++) {
                        for (int j = 0; j < 2; j++) {
                            double o = OFFS[rnd.nextInt(OFFS.length)];
                            run(f, v, s, vname, cfg, "combo", RNAME[k] + "@" + o, P, ws, truth, ts, RATIOS[k][0], new long[0], new double[]{o * 1000}, false);
                        }
                    }
                    if (cats.contains("piece1")) {
                        double[] fr = {0.35, 0.5, 0.65};
                        double[] dl = {-40, -15, -4, 4, 15, 40};
                        for (int j = 0; j < 6; j++) {
                            long cut = (long) (f.durationMs * fr[j % 3]);
                            double b0 = (rnd.nextInt(30) - 10) * 1000.0;
                            run(f, v, s, vname, cfg, "piece1", "cut@" + cut / 1000 + "s d=" + dl[j], P, ws, truth, ts, 1.0, new long[]{cut}, new double[]{b0, b0 + dl[j] * 1000}, true);
                        }
                    }
                    if (cats.contains("piece2")) {
                        for (int j = 0; j < 3; j++) {
                            long c1 = (long) (f.durationMs * (0.3 + 0.05 * j)), c2 = (long) (f.durationMs * (0.6 + 0.05 * j));
                            double b0 = (rnd.nextInt(20) - 5) * 1000.0, d1 = (rnd.nextBoolean() ? 1 : -1) * (5 + rnd.nextInt(40)) * 1000.0, d2 = (rnd.nextBoolean() ? 1 : -1) * (5 + rnd.nextInt(40)) * 1000.0;
                            run(f, v, s, vname, cfg, "piece2", "cuts " + c1 / 1000 + "," + c2 / 1000, P, ws, truth, ts, 1.0, new long[]{c1, c2}, new double[]{b0, b0 + d1, b0 + d1 + d2}, true);
                        }
                    }
                }
                // safe: a legenda JÁ está certa (alinhada à verdade): o motor não pode mexer mais que 150 ms
                if (cats.contains("safe")) {
                    for (Film.Sub s : f.subs) {
                        if (!s.truth || s.truthCues == null || s.truthCues.length < 40) continue;
                        Window[] ws = dens ? windows(f, v, AudioSyncSession.planDense(s.truthCues, f.durationMs, nwF, lenF)) : wsFixed;
                        long t0 = System.nanoTime();
                        Result r = session(f, v, s.truthCues, ws, P, 3);
                        long took = (System.nanoTime() - t0) / 1000000;
                        double[] sh0 = new double[s.truthCues.length];
                        int sm = 0;
                        for (int i = 0; i < sh0.length; i++) if (s.truthCues[i].startMs < CLIPMS) sh0[sm++] = Math.abs(r.apply(s.truthCues[i].startMs) - s.truthCues[i].startMs);
                        if (sm == 0) continue;
                        double[] sh = Arrays.copyOf(sh0, sm);
                        Arrays.sort(sh);
                        double[] e = {sh[sh.length / 2], sh[(int) (sh.length * 0.9)], sh[sh.length - 1]};
                        row(f, s, vname, cfg, "safe", "truth", r, e, e, took, String.format(Locale.ROOT, "shiftmed=%.0f shiftp90=%.0f ", e[0], e[1]));
                    }
                }
                // natural: legendas originais (sem distorção) contra a verdade do oráculo
                if (cats.contains("natural")) {
                    for (Film.Sub s : f.subs) {
                        if (s.pairs == null) continue;
                        long t0 = System.nanoTime();
                        Window[] ws = dens ? windows(f, v, AudioSyncSession.planDense(s.orig, f.durationMs, nwF, lenF)) : wsFixed;
                        Result r = session(f, v, s.orig, ws, P, 3);
                        long took = (System.nanoTime() - t0) / 1000000;
                        double[] e = null;
                        String before = "";
                        int npc = 0;
                        if (s.truth) for (long[] pr : s.pairs) if (pr[1] < CLIPMS) npc++;
                        if (s.truth && npc > 0) {
                            double[] ee = new double[npc], bb = new double[npc];
                            int ic = 0;
                            for (long[] pr : s.pairs) if (pr[1] < CLIPMS) { ee[ic] = Math.abs(r.apply(pr[0]) - pr[1]); bb[ic] = Math.abs(pr[0] - pr[1]); ic++; }
                            Arrays.sort(ee); Arrays.sort(bb);
                            e = new double[]{ee[ee.length / 2], ee[(int) (ee.length * 0.9)], ee[ee.length - 1]};
                            before = String.format(Locale.ROOT, "before=%.0f beforep90=%.0f ", bb[bb.length / 2], bb[(int) (bb.length * 0.9)]);
                        }
                        row(f, s, vname, cfg, s.truth ? "natural" : "natural_nt", "orig", r, e, e, took, before);
                    }
                }
                // negativos: legendas de OUTROS filmes (do diretório de legendas cruas)
                if (cats.contains("neg") && !subsDir.isEmpty()) {
                    File[] others = new File(subsDir).listFiles(File::isDirectory);
                    Arrays.sort(others);
                    for (File od : others) {
                        if (od.getName().equals(f.slug)) continue;
                        String[] names = {"pt-0", "pt-1", "en-0"};
                        for (String nm : names) {
                            File sf = new File(od, nm + ".srt");
                            if (!sf.exists()) continue;
                            Cue[] c = Cue.parseSrt(new String(java.nio.file.Files.readAllBytes(sf.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                            if (c.length < 40) continue;
                            Window[] ws = dens ? windows(f, v, AudioSyncSession.planDense(c, f.durationMs, nwF, lenF)) : wsFixed;
                            long t0 = System.nanoTime();
                            Result r = session(f, v, c, ws, P, 3);
                            long took = (System.nanoTime() - t0) / 1000000;
                            Film.Sub fake = new Film.Sub();
                            fake.name = od.getName() + "/" + nm;
                            row(f, fake, vname, cfg, "neg", od.getName(), r, null, null, took, "");
                        }
                    }
                }
            }
        }
        out.close();
    }

    /** Reverificação durante a reprodução: (1) legenda já certa, checagens a cada RCK_STEP_S s (padrão 600) com janela de RCK_LEN_S s (padrão 90): qualquer
     *  PROPOSED/CHANGE é "mudança falsa" (meta 0); (2) corte injetado no filme (a legenda seguiu o filme sem o trecho): detecção, latência e erro do novo deslocamento. */
    static final int ACC = System.getenv("RCK_ACC") == null ? 1 : Integer.parseInt(System.getenv("RCK_ACC"));

    static void recheckEval(Film f, VadSrc v, Film.Sub s, String vad, AudioAligner.Params P, Cue[] truth, Result r0) throws IOException {
        if (!r0.accepted) return;
        String e1 = System.getenv("RCK_STEP_S"), e2 = System.getenv("RCK_LEN_S");
        long step = (e1 == null || e1.isEmpty() ? 600 : Long.parseLong(e1)) * 1000L, len = (e2 == null || e2.isEmpty() ? 90 : Long.parseLong(e2)) * 1000L;
        long full = f.fullDurationMs;
        Recheck.Params RP = new Recheck.Params();
        ArrayList<Long> pts = new ArrayList<>();
        for (long t = step / 2; t + len <= full; t += step) pts.add(t);
        // (1) legenda certa
        Recheck.Out prev = null;
        ArrayList<Window> acc = new ArrayList<>();
        for (long t : pts) {
            acc.add(new Window(t / 10 * 10, v.get(f, t / 10 * 10, len)));
            while (acc.size() > ACC) acc.remove(0);
            Recheck.Out o = Recheck.check(truth, acc.toArray(new Window[0]), r0, t, full, false, prev, P, RP);
            if (o.verdict == Recheck.Verdict.KEEP && "ok".equals(o.reason)) acc.clear();
            prev = o;
            Result fr = new Result();
            fr.accepted = o.verdict != Recheck.Verdict.KEEP; fr.z = o.z; fr.nccMean = o.nccAtCurrent; fr.offsetMs = o.newOffsetMs;
            fr.reason = o.verdict + " " + o.reason;
            row(f, s, vad, "rck" + len / 1000, "rck_keep", "t=" + t / 1000 + "s", fr, null, null, o.tookMs, "");
        }
        // (2) corte injetado: depois do corte a legenda fica d segundos fora
        double[] fr = {0.35, 0.6};
        double[] dl = {-40, 15};
        for (int k = 0; k < fr.length; k++) {
            long cut = (long) (full * fr[k]);   // tempo da legenda (verdade); a mídia do corte ~ cut
            double d = dl[k];
            Object[] od = distortPieces(truth, 1.0, new long[]{cut}, new double[]{0, d * 1000});
            Cue[] dc = (Cue[]) od[0];
            Result cur = r0;
            prev = null;
            acc.clear();
            long firstChange = -1;
            for (long t : pts) {
                acc.add(new Window(t / 10 * 10, v.get(f, t / 10 * 10, len)));
                while (acc.size() > ACC) acc.remove(0);
                Recheck.Out o = Recheck.check(dc, acc.toArray(new Window[0]), cur, t, full, false, prev, P, RP);
                if (o.verdict == Recheck.Verdict.KEEP && "ok".equals(o.reason)) acc.clear();
                prev = o;
                double exp = r0.offsetMs + d * 1000;
                Result rr = new Result();
                rr.accepted = o.verdict != Recheck.Verdict.KEEP; rr.z = o.z; rr.nccMean = o.nccAtCurrent; rr.offsetMs = o.newOffsetMs;
                rr.reason = o.verdict + " " + o.reason;
                double[] err = rr.accepted ? new double[]{Math.abs(o.newOffsetMs - exp), 0, 0} : null;
                row(f, s, vad, "rck" + len / 1000, "rck_cut", "cut@" + cut / 1000 + "s d=" + d + " t=" + t / 1000 + "s", rr, err, err, o.tookMs, t < cut ? "antes" : "depois");
                if (o.verdict == Recheck.Verdict.CHANGE && firstChange < 0) { firstChange = t; cur = new Result(); cur.accepted = true; cur.scale = r0.scale; cur.offsetMs = o.newOffsetMs; prev = null; acc.clear(); }
            }
        }
    }

    static int countTruth(Film f) { int n = 0; for (Film.Sub s : f.subs) if (s.truth) n++; return n; }

    static void run(Film f, VadSrc v, Film.Sub s, String vad, String cfg, String cat, String param, AudioAligner.Params P, Window[] ws,
                    Cue[] truth, long[] ts, double a, long[] cuts, double[] offs, boolean seg) throws IOException {
        Cue[] d;
        long[] tsx = ts, trx = REL;
        if (seg) {
            Object[] o = distortPieces(truth, a, cuts, offs);
            d = (Cue[]) o[0];
            int[] keep = (int[]) o[1];
            tsx = new long[keep.length]; trx = new long[keep.length];
            for (int i = 0; i < keep.length; i++) { tsx[i] = ts[keep[i]]; trx[i] = REL[keep[i]]; }
        } else d = distort(truth, a, cuts, offs);
        long t0 = System.nanoTime();
        Result r;
        try { r = session(f, v, d, ws, P, 6); }
        catch (RuntimeException e) { r = new Result(); r.reason = "EXCECAO " + e; e.printStackTrace(); }
        long took = (System.nanoTime() - t0) / 1000000;
        double[] abs = r.reason.startsWith("EXCECAO") ? null : errStats(r, d, tsx, seg), rel = r.reason.startsWith("EXCECAO") ? null : errStats(r, d, trx, seg);
        row(f, s, vad, cfg, cat, param, r, abs, rel, took, (REL_OK ? "" : "base_recusada ") + (seg ? "segs=" + (r.segments == null ? 1 : r.segments.length) + " rounds=" + r.rounds : ""));
    }
}
