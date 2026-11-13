package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Avaliação em escala do acerto automático de legendas por texto, com legendas reais (corpus do Fetch).
 * Compila contra a versão "antes" (base/Variant.java + SubtitleAlign da main) ou "depois" (new/Variant.java + SubtitleAlign do branch).
 * Uso: Eval corpusDir statsOut casesOut [v]
 * Saída: relatório em markdown (stdout), linhas "chave\tvalor" (statsOut) e uma linha por par PT x EN (casesOut) para o Diff.
 */
public class Eval {
    static final class Sub {
        String title, kind, lang, m, info, name;
        byte[] raw; String text; long[] starts; boolean ptOk;
        int idx;
    }
    static final class Title {
        String label, dir; boolean series; String show;
        List<Sub> pt = new ArrayList<>(), en = new ArrayList<>(), oth = new ArrayList<>();
    }
    static String f2(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    static String sec(long ms) { return String.format(Locale.ROOT, "%+.1fs", ms / 1000.0); }

    static final LinkedHashMap<String, String> stats = new LinkedHashMap<>();
    static void stat(String k, Object v) { stats.put(k, String.valueOf(v)); }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        boolean verbose = args.length > 3 && args[3].equals("v");
        List<Title> titles = load(root);
        PrintStream out = System.out;
        out.println("## Avaliação em escala (" + Variant.NAME + ")");
        parseReport(titles, out);
        pairReport(titles, out, Paths.get(args[2]));
        try (PrintWriter pw = new PrintWriter(args[1])) { for (Map.Entry<String, String> e : stats.entrySet()) pw.println(e.getKey() + "\t" + e.getValue()); }
    }

    // ---------- carga ----------
    static List<Title> load(Path root) throws Exception {
        List<Title> out = new ArrayList<>();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) { dirs = s.filter(Files::isDirectory).sorted().collect(Collectors.toList()); }
        for (Path d : dirs) {
            Path meta = d.resolve("meta.txt");
            if (!Files.exists(meta)) continue;
            Title t = new Title();
            t.dir = d.getFileName().toString();
            List<String> lines = Files.readAllLines(meta);
            String head = lines.isEmpty() ? "" : lines.get(0);
            Matcher lm = Pattern.compile("label=(.*)$").matcher(head);
            t.label = lm.find() ? lm.group(1) : t.dir;
            t.series = head.contains("type=series");
            t.show = t.label.replaceAll(" S\\d+E\\d+$", "");
            for (String ln : lines) {
                if (ln.startsWith("#") || ln.isEmpty()) continue;
                String[] f = ln.split("\t", 4);
                Sub s = new Sub();
                s.title = t.label; s.kind = f[0]; s.lang = f[1]; s.m = f[2]; s.info = f.length > 3 ? f[3] : "";
                Matcher nm = Pattern.compile("\"subtitleFileName\":\"([^\"]*)\"").matcher(s.info);
                s.name = nm.find() ? nm.group(1) : "";
                Path rp = d.resolve(s.kind + ".raw");
                if (!Files.exists(rp)) continue;
                s.raw = Files.readAllBytes(rp);
                s.idx = s.kind.matches(".*-\\d+$") ? Integer.parseInt(s.kind.substring(s.kind.lastIndexOf('-') + 1)) : 0;
                s.text = Variant.decode(s.raw);
                if (s.text != null) {
                    s.starts = Variant.starts(s.text);
                    if (s.kind.startsWith("pt")) s.ptOk = Variant.looksPt(s.text);
                }
                (s.kind.startsWith("pt") ? t.pt : s.kind.startsWith("en") ? t.en : t.oth).add(s);
            }
            out.add(t);
        }
        return out;
    }

    // ---------- 1. leitura dos arquivos ----------
    static final Pattern MOJI = Pattern.compile("Ã[ -¿]");
    static String head(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(40, d.length); i++) { int c = d[i] & 0xFF; sb.append(c >= 32 && c < 127 ? String.valueOf((char) c) : String.format("\\x%02x", c)); }
        return sb.toString();
    }

    static void parseReport(List<Title> titles, PrintStream out) {
        int files = 0, notSub = 0, ptFiles = 0, ptRejected = 0, enFiles = 0, ptNoStarts = 0, lossy = 0, moji = 0, fewCues = 0;
        int othFiles = 0, othAccepted = 0, bom = 0, crlf = 0, vtt = 0, utf16 = 0, gz = 0, zip = 0, ass = 0;
        StringBuilder exNot = new StringBuilder(), exLossy = new StringBuilder(), exMoji = new StringBuilder(), exRej = new StringBuilder(), exOth = new StringBuilder();
        for (Title t : titles) {
            List<Sub> all = new ArrayList<>(); all.addAll(t.pt); all.addAll(t.en); all.addAll(t.oth);
            for (Sub s : all) {
                files++;
                byte[] d = s.raw;
                if (d.length > 2 && (d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B) gz++;
                if (d.length > 4 && d[0] == 'P' && d[1] == 'K') zip++;
                if (d.length > 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB) bom++;
                if (d.length > 2 && ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE || (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF)) utf16++;
                boolean isPt = s.kind.startsWith("pt"), isOth = s.kind.startsWith("oth");
                if (isPt) ptFiles++; else if (isOth) othFiles++; else enFiles++;
                if (s.text == null) {
                    notSub++;
                    if (exNot.length() < 1800) exNot.append("- ").append(t.label).append(' ').append(s.kind).append(" (").append(s.lang).append(", ").append(s.name).append("): `").append(head(s.raw).replace("`", "'")).append("`\n");
                    if (isPt) ptRejected++;
                    continue;
                }
                String x = s.text;
                if (x.contains("\r\n")) crlf++;
                if (x.startsWith("WEBVTT")) vtt++;
                if (x.contains("[Events]")) ass++;
                int arrows = 0;
                for (int i = x.indexOf("-->"); i >= 0; i = x.indexOf("-->", i + 3)) arrows++;
                long[] cs = SubtitleAlign.cueStarts(x);
                if (arrows > 0 && cs.length < Math.min(6000, arrows) * 0.98) {
                    lossy++;
                    if (exLossy.length() < 1500) exLossy.append("- ").append(t.label).append(' ').append(s.kind).append(": ").append(arrows).append(" setas, ").append(cs.length).append(" falas lidas\n");
                }
                int mj = 0; Matcher mm = MOJI.matcher(x); while (mm.find()) mj++;
                if (mj >= 5) { moji++; if (exMoji.length() < 1500) exMoji.append("- ").append(t.label).append(' ').append(s.kind).append(": ").append(mj).append(" sequências Ã?\n"); }
                if (isPt) {
                    if (!s.ptOk) { ptRejected++; if (exRej.length() < 1800) exRej.append("- ").append(t.label).append(' ').append(s.kind).append(" (").append(s.lang).append(", ").append(s.name).append(") rejeitada pelo idioma\n"); }
                    if (s.starts.length < 20) ptNoStarts++;
                }
            }
            for (Sub s : t.oth) if (s.text != null && Variant.looksPt(s.text)) { othAccepted++; if (exOth.length() < 1500) exOth.append("- ").append(t.label).append(" ").append(s.lang).append(" (").append(s.name).append(") ACEITA COMO PORTUGUÊS\n"); }
        }
        out.println("### 1. Leitura dos arquivos");
        out.println("Arquivos: " + files + " (PT " + ptFiles + ", EN " + enFiles + ", outros idiomas " + othFiles + "). Não são legenda (descartados): " + notSub
                + ". gzip " + gz + ", zip " + zip + ", BOM UTF-8 " + bom + ", UTF-16 " + utf16 + ", CRLF " + crlf + ", VTT " + vtt + ", ASS/SSA " + ass + ".");
        out.println("PT descartadas (não-legenda ou idioma): " + ptRejected + " de " + ptFiles + ". PT com menos de 20 falas: " + ptNoStarts + ". Arquivos com setas '-->' que não viraram falas (>2%): " + lossy + ". Com mojibake (Ã?): " + moji + ".");
        out.println("Outros idiomas (espanhol/francês/italiano/galego/catalão/romeno/alemão) aceitos como português: **" + othAccepted + "** de " + othFiles + " (tem de ser 0).");
        if (exNot.length() > 0) out.print("Exemplos de não-legenda:\n" + exNot);
        if (exLossy.length() > 0) out.print("Exemplos de falas perdidas:\n" + exLossy);
        if (exMoji.length() > 0) out.print("Exemplos de mojibake:\n" + exMoji);
        if (exRej.length() > 0) out.print("Exemplos de PT rejeitada:\n" + exRej);
        if (exOth.length() > 0) out.print("Outro idioma aceito:\n" + exOth);
        out.println();
        stat("arquivos", files); stat("nao_legenda", notSub); stat("PT_descartadas", ptRejected); stat("falas_perdidas_arquivos", lossy); stat("mojibake_arquivos", moji);
        stat("outros_idiomas_aceitos_como_PT", othAccepted + "/" + othFiles); stat("ASS_zip_utf16", ass + "/" + zip + "/" + utf16);
    }

    // ---------- 2. pares ----------
    static final class Pair {
        Sub pt, en; SubtitleAlign.Fit f; String id;
        Pair(Sub p, Sub e) { pt = p; en = e; id = p.title + "|pt" + p.idx + "|en" + e.idx; }
    }
    static double ratioInWindow(SubtitleAlign.Fit f, long[] ref, long[] cand) {
        long lo = f.map(cand[0]), hi = f.map(cand[cand.length - 1]);
        int c = 0;
        for (long r : ref) if (r >= lo && r <= hi) c++;
        return cand.length / (double) Math.max(1, c);
    }

    static void pairReport(List<Title> titles, PrintStream out, Path casesOut) throws Exception {
        ForkJoinPool pool = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        List<Title> withBoth = titles.stream().filter(t -> !t.en.isEmpty() && t.pt.stream().anyMatch(s -> s.ptOk && s.starts != null && s.starts.length >= 20)).collect(Collectors.toList());
        // --- aceitação ---
        List<Pair> pairs = new ArrayList<>();
        for (Title t : withBoth) for (Sub p : t.pt) { if (!p.ptOk || p.starts == null || p.starts.length < 20) continue; for (Sub e : t.en) if (e.starts != null && e.starts.length >= 20) pairs.add(new Pair(p, e)); }
        long t0 = System.currentTimeMillis();
        pool.submit(() -> pairs.parallelStream().forEach(pr -> pr.f = SubtitleAlign.align(pr.en.starts, pr.pt.starts))).get();
        long cpu = System.currentTimeMillis() - t0;
        Map<Sub, List<Pair>> byPt = new LinkedHashMap<>();
        for (Pair p : pairs) byPt.computeIfAbsent(p.pt, k -> new ArrayList<>()).add(p);
        int ptTotal = byPt.size(), accepted = 0, pieced = 0, free = 0, partial = 0, nonId = 0;
        int[] hist = new int[6];
        String[] hl = {"<0,20", "0,20-0,35", "0,35-0,50", "0,50-0,70", "0,70-0,90", ">=0,90"};
        int sparseAcc = 0, sparseAny = 0;
        int[] rh = new int[7]; String[] rl = {"<0,30", "0,30-0,45", "0,45-0,60", "0,60-0,80", "0,80-1,20", "1,20-2", ">2"};
        StringBuilder exLow = new StringBuilder();
        StringBuilder exSparse = new StringBuilder(), exRej = new StringBuilder();
        PrintWriter cw = new PrintWriter(casesOut.toFile());
        Map<Integer, Integer> cutsHist = new TreeMap<>();
        for (Map.Entry<Sub, List<Pair>> en : byPt.entrySet()) {
            Pair bestOk = null; double bestScore = 0; Pair bestAny = null;
            for (Pair p : en.getValue()) {
                SubtitleAlign.Fit f = p.f;
                double r = ratioInWindow(f, p.en.starts, p.pt.starts);
                cw.println(p.id + "\t" + f.ok() + "\t" + f2(f.score) + "\t" + String.format(Locale.ROOT, "%.4f", f.scale) + "\t" + f.offsetMs + "\t" + (f.piecewise() ? f.cuts.length : 0) + "\t" + f2(f.coverage) + "\t" + f2(r));
                if (f.ok() && (bestOk == null || f.score > bestOk.f.score)) bestOk = p;
                if (bestAny == null || f.score > bestAny.f.score) bestAny = p;
                if ((f.ok() || f.score >= 0.5) && f.coverage >= 0.75) {
                    rh[r < 0.3 ? 0 : r < 0.45 ? 1 : r < 0.6 ? 2 : r < 0.8 ? 3 : r < 1.2 ? 4 : r < 2 ? 5 : 6]++;
                    if (r < 0.6 && exLow.length() < 1800) exLow.append("- ").append(p.id).append(": ").append(f.ok() ? "aceita" : "rejeitada").append(" nota ").append(f2(f.score)).append(", razão de falas ").append(f2(r)).append(" (PT ").append(p.pt.starts.length).append(" x EN ").append(p.en.starts.length).append(", ").append(p.pt.name).append(")\n");
                }
                if (f.ok() && r < 0.5 && f.coverage >= 0.75) { sparseAcc++; if (exSparse.length() < 1800) exSparse.append("- ").append(p.id).append(": aceita com nota ").append(f2(f.score)).append(" mas só tem ").append(Math.round(r * 100)).append("% das falas da referência (PT ").append(p.pt.starts.length).append(" x EN ").append(p.en.starts.length).append(", ").append(p.pt.name).append(")\n"); }
            }
            double sc = bestOk != null ? bestOk.f.score : bestAny.f.score;
            hist[sc < 0.2 ? 0 : sc < 0.35 ? 1 : sc < 0.5 ? 2 : sc < 0.7 ? 3 : sc < 0.9 ? 4 : 5]++;
            if (bestOk != null) {
                accepted++;
                SubtitleAlign.Fit f = bestOk.f;
                if (f.piecewise()) { pieced++; cutsHist.merge(f.cuts.length, 1, Integer::sum); }
                if (f.freeScale) free++;
                if (f.coverage < 0.75) partial++;
                if (Math.abs(f.scale - 1.0) > 1e-9 && !f.freeScale) nonId++;
            } else if (exRej.length() < 1500 && bestAny.f.score >= 0.2)
                exRej.append("- ").append(bestAny.id).append(": melhor nota ").append(f2(bestAny.f.score)).append(" metades ").append(f2(bestAny.f.scoreFirstHalf)).append("/").append(f2(bestAny.f.scoreSecondHalf)).append(" (").append(bestAny.pt.name).append(")\n");
        }
        cw.close();
        out.println("### 2. Aceitação (PT x todas as EN do mesmo título)");
        out.println("Títulos com PT e EN: " + withBoth.size() + "; legendas PT avaliadas: " + ptTotal + "; pares PT x EN: " + pairs.size() + "; CPU " + cpu + " ms (paralelo).");
        out.println("**PT aceitas (alguma EN valida): " + accepted + " (" + Math.round(100.0 * accepted / Math.max(1, ptTotal)) + "%)**; por partes: " + pieced + " " + cutsHist + "; escala livre: " + free + "; escala fps fixa ≠ 1: " + nonId + "; parciais (CD1/CD2): " + partial + ".");
        StringBuilder hs = new StringBuilder();
        for (int i = 0; i < 6; i++) hs.append(hl[i]).append(": ").append(hist[i]).append(i < 5 ? " | " : "");
        out.println("Melhor nota por PT: " + hs);
        StringBuilder rs = new StringBuilder();
        for (int i = 0; i < 7; i++) rs.append(rl[i]).append(": ").append(rh[i]).append(i < 6 ? " | " : "");
        out.println("Razão de falas PT/EN na janela coberta (pares com nota >= 0,5 ou aceitos): " + rs);
        if (exLow.length() > 0) out.print("Razão baixa (< 0,60):\n" + exLow);
        out.println("Aceitas COM MENOS DE 50% das falas da referência na mesma janela (forçadas/esparsas): **" + sparseAcc + "** pares.");
        if (exSparse.length() > 0) out.print(exSparse);
        if (exRej.length() > 0) out.print("Rejeitadas com nota >= 0,20 (exemplos):\n" + exRej);
        out.println();
        stat("PT_avaliadas", ptTotal); stat("PT_aceitas", accepted); stat("por_partes", pieced); stat("escala_livre", free); stat("parciais", partial);
        stat("notas", hs); stat("aceitas_esparsas_pares", sparseAcc);

        // --- falsos positivos ---
        List<Pair> neg = new ArrayList<>();
        List<Title> haveEn = titles.stream().filter(t -> t.en.stream().anyMatch(e -> e.starts != null && e.starts.length >= 20)).collect(Collectors.toList());
        int negOther = 0, negSame = 0;
        Random rnd = new Random(7);
        for (Title t : withBoth) {
            List<Sub> pts = t.pt.stream().filter(s -> s.ptOk && s.starts != null && s.starts.length >= 20).limit(3).collect(Collectors.toList());
            List<Title> others = new ArrayList<>(haveEn);
            others.remove(t);
            Collections.shuffle(others, rnd);
            int taken = 0;
            for (Title o : others) {
                boolean same = t.series && o.series && t.show.equals(o.show);
                if (!same && taken >= 14) continue;
                if (!same) taken++;
                List<Sub> ens = o.en.stream().filter(e -> e.starts != null && e.starts.length >= 20).limit(2).collect(Collectors.toList());
                for (Sub p : pts) for (Sub e : ens) { neg.add(new Pair(p, e)); if (same) negSame++; else negOther++; }
            }
        }
        pool.submit(() -> neg.parallelStream().forEach(pr -> pr.f = SubtitleAlign.align(pr.en.starts, pr.pt.starts))).get();
        int fpOther = 0, fpSame = 0;
        StringBuilder exFp = new StringBuilder();
        int hardest = 0;
        double maxNeg = 0;
        for (Pair p : neg) {
            boolean same = p.pt.title.replaceAll(" S\\d+E\\d+$", "").equals(p.en.title.replaceAll(" S\\d+E\\d+$", "")) && p.pt.title.matches(".* S\\d+E\\d+$");
            if (!p.f.ok()) maxNeg = Math.max(maxNeg, p.f.score);
            if (p.f.ok()) {
                if (same) fpSame++; else fpOther++;
                if (exFp.length() < 2500) exFp.append("- FALSO POSITIVO: PT ").append(p.pt.title).append(" pt").append(p.pt.idx).append(" x EN ").append(p.en.title).append(" en").append(p.en.idx).append(": nota ").append(f2(p.f.score)).append(p.f.piecewise() ? " por partes" : "").append(" escala ").append(String.format(Locale.ROOT, "%.4f", p.f.scale)).append(" off ").append(sec(p.f.offsetMs)).append("\n");
            }
        }
        out.println("### 3. Falsos positivos (PT de um título/episódio contra EN de outro; tem de ser 0)");
        out.println("Pares de outro título: " + negOther + " -> aceitos **" + fpOther + "**; pares de outro episódio da mesma série: " + negSame + " -> aceitos **" + fpSame + "**. Maior nota entre os rejeitados: " + f2(maxNeg) + ".");
        out.print(exFp);
        out.println();
        stat("FP_outro_titulo", fpOther + "/" + negOther); stat("FP_mesma_serie", fpSame + "/" + negSame); stat("maior_nota_negativa", f2(maxNeg));

        // --- consistência entre referências ---
        final int[] cnt = new int[4]; // pares PT com >= 2 EN aceitas, comparáveis, inconsistentes, EN-EN sem casamento
        StringBuilder exInc = new StringBuilder();
        List<Runnable> jobs = new ArrayList<>();
        Object lock = new Object();
        List<String> incLines = Collections.synchronizedList(new ArrayList<>());
        pool.submit(() -> byPt.entrySet().parallelStream().forEach(en -> {
            List<Pair> oks = en.getValue().stream().filter(p -> p.f.ok() && p.f.coverage >= 0.75).collect(Collectors.toList());
            if (oks.size() < 2) return;
            synchronized (lock) { cnt[0]++; }
            boolean flagged = false; boolean compared = false;
            for (int i = 0; i < oks.size(); i++) for (int j = i + 1; j < oks.size(); j++) {
                Pair a = oks.get(i), b = oks.get(j);
                SubtitleAlign.Fit g = SubtitleAlign.align(b.en.starts, a.en.starts);
                if (!g.ok() || g.coverage < 0.75) { synchronized (lock) { cnt[3]++; } continue; }
                compared = true;
                int bad = 0, n = 0; long maxd = 0;
                for (long c : a.pt.starts) {
                    long p1 = g.map(a.f.map(c)), p2 = b.f.map(c);
                    long d = Math.abs(p1 - p2);
                    n++; if (d > 600) bad++; maxd = Math.max(maxd, d);
                }
                if (bad > 0.05 * n) {
                    flagged = true;
                    incLines.add(a.id + " x " + b.en.title + " en" + b.en.idx + ": " + Math.round(100.0 * bad / n) + "% das falas divergem >0,6 s (máx " + sec(maxd) + "); EN" + a.en.idx + ": esc " + String.format(Locale.ROOT, "%.4f", a.f.scale) + " off " + sec(a.f.offsetMs) + (a.f.piecewise() ? " partes" : "") + " | EN" + b.en.idx + ": esc " + String.format(Locale.ROOT, "%.4f", b.f.scale) + " off " + sec(b.f.offsetMs) + (b.f.piecewise() ? " partes" : ""));
                }
            }
            if (compared) synchronized (lock) { cnt[1]++; if (flagged) cnt[2]++; }
        })).get();
        out.println("### 4. Transformações inconsistentes (a mesma PT aceita com duas EN que casam entre si, mas com resultados diferentes)");
        out.println("PT aceitas com >= 2 EN: " + cnt[0] + "; comparáveis: " + cnt[1] + "; **inconsistentes: " + cnt[2] + "** (>5% das falas divergem mais de 0,6 s).");
        Collections.sort(incLines);
        for (int i = 0; i < Math.min(12, incLines.size()); i++) out.println("- " + incLines.get(i));
        out.println();
        stat("inconsistentes", cnt[2] + "/" + cnt[1]);

        // --- recuperação de distorções conhecidas ---
        // referência de verdade: a transformação aceita (nota >= 0,8, escala/offset simples). Distorce a PT de forma conhecida e vê se a recuperação cai no mesmo lugar.
        List<Pair> truth = new ArrayList<>();
        for (Pair p : pairs) if (p.f.ok() && !p.f.piecewise() && p.f.score >= 0.8 && p.f.coverage >= 0.9 && Math.abs(p.f.scale - 1) < 0.002) truth.add(p);
        // no máximo 2 por PT
        Map<Sub, Integer> per = new HashMap<>();
        truth.removeIf(p -> per.merge(p.pt, 1, Integer::sum) > 2);
        double[] ks = {1.0, 25.0 / 23.976, 23.976 / 25.0, 1.0417, 0.96};
        long[] os = {-45000, 3200, 90000, 240000, -7000, 17000};
        AtomicInteger nTest = new AtomicInteger(), nAcc = new AtomicInteger(), nRight = new AtomicInteger(), nWrong = new AtomicInteger(), nRej = new AtomicInteger();
        List<String> wrong = Collections.synchronizedList(new ArrayList<>());
        pool.submit(() -> IntStream.range(0, truth.size()).parallel().forEach(ti -> {
            Pair p = truth.get(ti);
            Random r = new Random(1000 + ti);
            for (int rep = 0; rep < 3; rep++) {
                double k = ks[r.nextInt(ks.length)];
                long o = os[r.nextInt(os.length)];
                long[] d = new long[p.pt.starts.length];
                for (int i = 0; i < d.length; i++) d[i] = Math.max(0, Math.round(p.pt.starts[i] * k) + o);
                Arrays.sort(d);
                nTest.incrementAndGet();
                SubtitleAlign.Fit g = SubtitleAlign.align(p.en.starts, d);
                if (!g.ok()) { nRej.incrementAndGet(); continue; }
                nAcc.incrementAndGet();
                int good = 0;
                for (int i = 0; i < p.pt.starts.length; i++) {
                    long expect = p.f.map(p.pt.starts[i]);
                    long long_ = Math.max(0, Math.round(p.pt.starts[i] * k) + o);
                    if (Math.abs(g.map(long_) - expect) <= 400) good++;
                }
                if (good >= 0.95 * p.pt.starts.length) nRight.incrementAndGet();
                else { nWrong.incrementAndGet(); wrong.add(p.id + ": distorção x" + String.format(Locale.ROOT, "%.4f", k) + " " + sec(o) + " -> aceita com esc " + String.format(Locale.ROOT, "%.4f", g.scale) + " off " + sec(g.offsetMs) + (g.piecewise() ? " partes" : "") + " nota " + f2(g.score) + ", só " + Math.round(100.0 * good / p.pt.starts.length) + "% das falas no lugar certo"); }
            }
        })).get();
        out.println("### 5. Recuperação de distorções conhecidas (PT real boa, deslocada/esticada de propósito)");
        out.println("Testes: " + nTest + " | aceitos: " + nAcc + " (certos: " + nRight + ", **ERRADOS: " + nWrong + "**) | não alinhados (seguro): " + nRej + ".");
        Collections.sort(wrong);
        for (int i = 0; i < Math.min(10, wrong.size()); i++) out.println("- " + wrong.get(i));
        out.println();
        stat("distorcoes_testes", nTest); stat("distorcoes_certas", nRight); stat("distorcoes_ERRADAS", nWrong); stat("distorcoes_nao_alinhadas", nRej);
        pool.shutdown();
        consensusReport(titles, out);
    }

    // ---------- 6. consenso (sem referência de tempo) ----------
    static long lastOf(Sub s) { return Variant.lastEnd(s.text); }

    static void consensusReport(List<Title> titles, PrintStream out) {
        int tests = 0, oldRight = 0, newRight = 0, applied = 0, appliedRight = 0, appliedOld = 0, groupsMixed = 0, groupsAll = 0;
        StringBuilder ex = new StringBuilder();
        for (Title t : titles) {
            List<Sub> P = t.pt.stream().filter(s -> s.ptOk && s.starts != null && s.starts.length >= 20).collect(Collectors.toList());
            if (P.size() < 3) continue;
            List<long[]> st = new ArrayList<>();
            for (Sub p : P) st.add(p.starts);
            int[] comp = SubtitleAlign.consensusGroups(st);
            int ng = 0; for (int c : comp) ng = Math.max(ng, c + 1);
            int[] sizes = new int[ng]; for (int c : comp) sizes[c]++;
            for (Sub e : t.en) {
                if (e.starts == null || e.starts.length < 20) continue;
                boolean[] truth = new boolean[P.size()]; boolean any = false;
                for (int i = 0; i < P.size(); i++) { truth[i] = SubtitleAlign.matchesTol(e.starts, P.get(i).starts, 0, 300) / (double) Math.min(e.starts.length, P.get(i).starts.length) >= 0.45; any |= truth[i]; }
                if (!any) continue;
                boolean[] hash = new boolean[P.size()]; double[] nm = new double[P.size()]; long[] last = new long[P.size()];
                for (int i = 0; i < P.size(); i++) { nm[i] = SubtitleAlign.nameSimilarity(e.name, P.get(i).name); last[i] = lastOf(P.get(i)); }
                long len = Math.round(lastOf(e) / 0.95);
                int oldPick = -1; { long ref = 0; int bn = -1;
                    for (int i = 0; i < P.size(); i++) { if (last[i] < len * 0.6 || last[i] > len * 1.05) continue; int n = 0; for (int j = 0; j < P.size(); j++) if (j != i && Math.abs((double) last[j] / last[i] - 1) <= 0.015) n++; if (n > bn) { bn = n; ref = last[i]; } }
                    double bs = 1e9; for (int i = 0; i < P.size(); i++) { if (last[i] < len * 0.5 || last[i] > len * 1.12) continue; double sc = Math.min(0.3, Math.max(0, (ref > 0 ? Math.abs((double) last[i] / ref - 1) : 0.3) - 0.005)) + 0.01 * i; if (sc < bs) { bs = sc; oldPick = i; } } }
                int g = SubtitleAlign.pickGroup(comp, hash, nm, last, len, null);
                int newPick = oldPick; boolean app = false;
                if (g >= 0 && sizes[g] >= 2) { app = true; double bs = -1; for (int i = 0; i < P.size(); i++) if (comp[i] == g) { double sc = nm[i] - 0.001 * i; if (bs < -0.5 || sc > bs) { bs = sc; newPick = i; } } }
                tests++;
                if (oldPick >= 0 && truth[oldPick]) oldRight++;
                if (newPick >= 0 && truth[newPick]) newRight++;
                if (app) { applied++; if (truth[newPick]) appliedRight++; if (oldPick >= 0 && truth[oldPick]) appliedOld++; }
            }
            // pureza: dois PT no mesmo grupo mas que NÃO batem entre si (>=45% a 300 ms) são um grupo misto
            for (int i = 0; i < P.size(); i++) for (int j = i + 1; j < P.size(); j++) if (comp[i] == comp[j]) {
                groupsAll++;
                double a = SubtitleAlign.matchesTol(P.get(j).starts, P.get(i).starts, 0, 300) / (double) Math.min(P.get(i).starts.length, P.get(j).starts.length);
                if (a < 0.45) { groupsMixed++; if (ex.length() < 800) ex.append("- ").append(t.label).append(": PT").append(P.get(i).idx).append(" e PT").append(P.get(j).idx).append(" no mesmo grupo com só ").append(f2(a)).append(" de coincidência a 0,3 s\n"); }
            }
        }
        out.println("### 6. Consenso (sem referência de tempo: escolhe, não alinha)");
        out.println("Simulação: o vídeo tem o tempo de uma EN; certa = PT que bate com ela sem ajuste (>= 45% a 300 ms). Testes: " + tests + " | regra do fim da legenda: " + oldRight + " certas | consenso+regra: " + newRight
                + " | o consenso entrou em " + applied + ": acertou " + appliedRight + " (regra antiga acertaria " + appliedOld + "). Pares no mesmo grupo que não batem de verdade: " + groupsMixed + " de " + groupsAll + ".");
        out.print(ex);
        out.println();
        stat("consenso_testes", tests); stat("consenso_certas_regra_fim", oldRight); stat("consenso_certas_consenso", newRight); stat("consenso_entrou/acertou", applied + "/" + appliedRight); stat("grupos_mistos", groupsMixed + "/" + groupsAll);
    }
}
