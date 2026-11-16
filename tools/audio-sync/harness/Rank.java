import io.github.gabrielxyz404.gabiflix.Baseline;
import io.github.gabrielxyz404.gabiflix.audiosync.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Ranking de candidatas por áudio contra o consenso sem referência do app. Um filme por execução.
 * Uso: Rank FILM_DIR OUT_CSV VAD CFG [LANG=pt]
 * Candidatas = todas as legendas da língua do filme (com ou sem verdade). Baseline: consenso (SubtitleAlign.consensusGroups/pickGroup) e a 1ª do grupo.
 * Áudio: cada candidata é alinhada (sessão de janelas); ordena por aceita > confiança > z; a escolhida é a de cima.
 * Acerto = a escolhida tem verdade E, depois da correção (baseline: nenhuma; áudio: a do motor), as falas ficam a <= 250 ms da verdade (mediana).
 * "não verificável" = a escolhida não tem verdade (o oráculo não decidiu): contada como erro na taxa conservadora e à parte.
 */
public class Rank {
    static double med(long[][] pairs, java.util.function.DoubleUnaryOperator f) {
        double[] e = new double[pairs.length];
        for (int i = 0; i < e.length; i++) e[i] = Math.abs(f.applyAsDouble(pairs[i][0]) - pairs[i][1]);
        Arrays.sort(e);
        return e.length == 0 ? 1e9 : e[e.length / 2];
    }

    public static void main(String[] args) throws Exception {
        File fd = new File(args[0]);
        PrintWriter out = new PrintWriter(new FileWriter(args[1], true));
        String vname = args[2];
        String cfg = args[3];
        String lang = args.length > 4 ? args[4] : "pt";
        String dw = System.getenv("DSPW_DIR");
        if (dw != null && !dw.isEmpty()) {
            File w = new File(dw, fd.getName() + ".w");
            System.setProperty("dsp.weights", (w.exists() ? w : new File(dw, "all.w")).getPath());
        }
        Film f = new Film(fd);
        ArrayList<Film.Sub> cands = new ArrayList<>();
        for (Film.Sub s : f.subs) if (s.name.startsWith(lang)) cands.add(s);
        int nTruth = 0;
        for (Film.Sub s : cands) if (s.truth && s.pairs != null) nTruth++;
        if (cands.size() < 2 || nTruth == 0) { System.err.println("pula " + f.slug + " (candidatas " + cands.size() + ", verdade " + nTruth + ")"); out.close(); return; }

        // ---- baseline: consenso por texto, sem referência
        ArrayList<long[]> starts = new ArrayList<>();
        long[] last = new long[cands.size()];
        for (int i = 0; i < cands.size(); i++) {
            String txt = new String(Files.readAllBytes(new File(fd, "subs/" + cands.get(i).name + ".srt").toPath()), StandardCharsets.UTF_8);
            long[] st = Baseline.starts(txt);
            starts.add(st);
            last[i] = st.length > 0 ? st[st.length - 1] : 0;
        }
        int[] comp = Baseline.groups(starts);
        int g = Baseline.pick(comp, last, f.durationMs);
        int gsize = 0;
        for (int c : comp) if (c == g) gsize++;
        String bOut; String bName = "-";
        if (g < 0 || gsize < 2) bOut = "sem_consenso";
        else {
            int pick = -1;
            for (int i = 0; i < comp.length; i++) if (comp[i] == g) { pick = i; break; }
            Film.Sub s = cands.get(pick);
            bName = s.name;
            if (!s.truth || s.pairs == null) bOut = "nao_verificavel";
            else bOut = med(s.pairs, x -> x) <= 250 ? "certa" : "errada";
        }

        // ---- áudio
        Eval.VadSrc v = Eval.vadSrc(vname);
        String[] q = cfg.split("x");
        Window[] ws = Eval.windows(f, v, Eval.plan(f.durationMs, Integer.parseInt(q[0]), Long.parseLong(q[1]) * 1000));
        AudioAligner.Params P = new AudioAligner.Params();
        Result[] rs = new Result[cands.size()];
        Integer[] ord = new Integer[cands.size()];
        for (int i = 0; i < cands.size(); i++) { rs[i] = Eval.session(f, v, cands.get(i).orig, ws, P, 6); ord[i] = i; }
        Arrays.sort(ord, (a, b) -> {
            Result x = rs[a], y = rs[b];
            if (x.accepted != y.accepted) return x.accepted ? -1 : 1;
            return Double.compare(y.confidence * 100 + y.z, x.confidence * 100 + x.z);
        });
        int top = ord[0];
        Film.Sub s = cands.get(top);
        String aOut;
        if (!rs[top].accepted) aOut = "recusou_tudo";
        else if (!s.truth || s.pairs == null) aOut = "nao_verificavel";
        else {
            final Result r = rs[top];
            aOut = med(s.pairs, x -> r.applySegments(x)) <= 250 ? "certa" : "errada";
        }
        // melhor possível: existe candidata com verdade que o motor acerta?
        boolean anyGood = false;
        StringBuilder det = new StringBuilder();
        for (int i = 0; i < cands.size(); i++) {
            Film.Sub c = cands.get(i);
            boolean good = false;
            if (c.truth && c.pairs != null && rs[i].accepted) { final Result r = rs[i]; good = med(c.pairs, x -> r.applySegments(x)) <= 250; }
            anyGood |= good;
            det.append(c.name).append(':').append(c.truth ? 'T' : 'n').append(rs[i].accepted ? 'A' : 'r').append(good ? '+' : '-').append(String.format(Locale.ROOT, "%.1f", rs[i].z)).append(' ');
        }
        out.printf(Locale.ROOT, "%s,%s,%s,%d,%d,%s,%s,%s,%s,%b,%s%n", f.slug, vname, cfg, cands.size(), nTruth, bOut, bName, aOut, s.name, anyGood, det.toString().trim());
        out.close();
    }
}
