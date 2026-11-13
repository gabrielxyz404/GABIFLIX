package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Teste de paridade, lado Java (a "fonte da verdade", as classes do Android): lê o corpus de legendas reais (Fetch) e os casos sintéticos (Synth)
 * e escreve uma linha por decisão (TSV, ordenado). O Swift (ParidadeMain.swift) faz exatamente o mesmo com o código portado e o compara.js compara.
 * Uso: Paridade corpusDir synDir saida.tsv [maxPt=14] [maxEn=4]
 */
public class Paridade {
    // ---------- hashes e formatos ----------
    static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            h ^= (c & 0xFF); h *= 0x100000001b3L;
            h ^= (c >> 8); h *= 0x100000001b3L;
        }
        return h;
    }
    static long fnv(long[] a) {
        long h = 0xcbf29ce484222325L;
        for (long v : a) for (int k = 0; k < 8; k++) { h ^= (v >> (8 * k)) & 0xFF; h *= 0x100000001b3L; }
        return h;
    }
    static String hx(long v) { return String.format(Locale.ROOT, "%016x", v); }
    static String d9(double v) { return String.format(Locale.ROOT, "%.9f", v); }
    static String csv(long[] a) { if (a == null) return "-"; StringBuilder sb = new StringBuilder(); for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); } return sb.length() == 0 ? "-" : sb.toString(); }
    static String csv(double[] a) { if (a == null) return "-"; StringBuilder sb = new StringBuilder(); for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(d9(a[i])); } return sb.length() == 0 ? "-" : sb.toString(); }
    static String csv(int[] a) { if (a == null) return "-"; StringBuilder sb = new StringBuilder(); for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); } return sb.length() == 0 ? "-" : sb.toString(); }
    static String b(boolean v) { return v ? "1" : "0"; }

    static final ConcurrentSkipListMap<String, String> REC = new ConcurrentSkipListMap<>();
    static void rec(String tag, String key, String... kv) {
        StringBuilder sb = new StringBuilder(tag).append('\t').append(key);
        for (String s : kv) sb.append('\t').append(s);
        REC.put(tag + "\t" + key, sb.toString());
    }
    static void logLines(String key, String lg) {
        String[] ls = lg.split("\n", -1);
        for (int i = 0; i < ls.length; i++) rec("L", key + "|" + String.format("%04d", i), "t=" + ls[i].replace("\t", " "));
    }

    // ---------- dados ----------
    static final class Sub { String kind, lang, m, info, name; byte[] raw; String text; long[] starts; boolean ptOk; int idx; }
    static final class Title {
        String key, label, listJson; boolean scenario; long scenLen; String video;
        List<Sub> pt = new ArrayList<>(), en = new ArrayList<>(), oth = new ArrayList<>();
        List<Sub> all() { List<Sub> a = new ArrayList<>(pt); a.addAll(en); a.addAll(oth); return a; }
    }

    static List<Title> load(Path root, String prefix) throws Exception {
        List<Title> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) { dirs = s.filter(Files::isDirectory).sorted().collect(Collectors.toList()); }
        for (Path d : dirs) {
            Path meta = d.resolve("meta.txt");
            if (!Files.exists(meta)) continue;
            Title t = new Title();
            t.key = prefix + d.getFileName();
            List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
            String head = lines.isEmpty() ? "" : lines.get(0);
            t.scenario = head.contains(" scenario");
            Matcher lm = Pattern.compile("len=(\\d+)").matcher(head);
            if (lm.find()) t.scenLen = Long.parseLong(lm.group(1));
            Matcher vm = Pattern.compile("video=(\\S+)").matcher(head);
            if (vm.find()) t.video = vm.group(1);
            for (String ln : lines) {
                if (ln.startsWith("#") || ln.isEmpty()) continue;
                String[] f = ln.split("\t", 4);
                Sub s = new Sub();
                s.kind = f[0]; s.lang = f[1]; s.m = f[2]; s.info = f.length > 3 ? f[3] : "";
                Matcher nm = Pattern.compile("\"subtitleFileName\":\"([^\"]*)\"").matcher(s.info);
                s.name = nm.find() ? nm.group(1) : "";
                Path rp = d.resolve(s.kind + ".raw");
                if (!Files.exists(rp)) continue;
                s.raw = Files.readAllBytes(rp);
                s.idx = s.kind.matches(".*-\\d+$") ? Integer.parseInt(s.kind.substring(s.kind.lastIndexOf('-') + 1)) : 0;
                (s.kind.startsWith("pt") ? t.pt : s.kind.startsWith("en") ? t.en : t.oth).add(s);
            }
            Path lj = d.resolve("list.json");
            if (Files.exists(lj)) t.listJson = new String(Files.readAllBytes(lj), StandardCharsets.UTF_8);
            out.add(t);
        }
        return out;
    }

    // ---------- 1. leitura ----------
    static void decodeAll(Title t) {
        for (Sub s : t.all()) {
            s.text = SubtitleText.decode(s.raw);
            if (s.text == null) { rec("D", t.key + "|" + s.kind, "dec=null"); continue; }
            s.starts = SubtitleAlign.alignStarts(s.text);
            boolean pt = SubtitleText.looksPortuguese(s.text);
            if (s.kind.startsWith("pt")) s.ptOk = pt;
            long[] cs = SubtitleAlign.cueStarts(s.text);
            List<SubtitleAlign.Cue> cues = SubtitleAlign.cues(s.text);
            long hc = 0xcbf29ce484222325L;
            for (SubtitleAlign.Cue c : cues) { hc ^= c.startMs; hc *= 0x100000001b3L; hc ^= fnv(c.text); hc *= 0x100000001b3L; }
            rec("D", t.key + "|" + s.kind, "dec=" + s.text.length() + ":" + hx(fnv(s.text)), "pt=" + b(pt), "last=" + SubtitleText.lastCueEnd(s.text),
                    "ns=" + s.starts.length + ":" + hx(fnv(s.starts)), "cs=" + cs.length + ":" + hx(fnv(cs)), "cu=" + cues.size() + ":" + hx(hc));
        }
    }

    // ---------- 2. ajuste (Fit) ----------
    static void recFit(String key, SubtitleAlign.Fit f, String text) {
        String ap = "-";
        if (f.ok() && f.changes() && text != null) ap = hx(fnv(f.apply(text)));
        rec("F", key, "ok=" + b(f.ok()), "sc=" + d9(f.score), "h1=" + d9(f.scoreFirstHalf), "h2=" + d9(f.scoreSecondHalf), "scale=" + String.format(Locale.ROOT, "%.9f", f.scale),
                "off=" + f.offsetMs, "cuts=" + csv(f.cuts), "offs=" + csv(f.offs), "cov=" + d9(f.coverage), "nul=" + d9(f.nullScore),
                "sp=" + b(f.sparse), "spr=" + b(f.sparseRef), "cr=" + d9(f.cueRatio), "fs=" + b(f.freeScale), "pok=" + b(f.pieceOk), "pw=" + b(f.piecewise()),
                "dv=" + b(f.differentVersion()), "ssc=" + csv(f.segScores), "scu=" + csv(f.segCues), "ap=" + ap);
    }

    // ---------- 3. pipeline ----------
    static SubtitleSync.Cand mk(Sub p, int rank, boolean hash) throws IOException {
        SubtitleSync.Cand c = new SubtitleSync.Cand();
        c.file = File.createTempFile("leg", ".srt"); c.file.deleteOnExit();
        c.text = p.text; c.rank = rank; c.hashMatch = hash; c.url = "u" + rank; c.rel = p.name;
        c.lastMs = SubtitleText.lastCueEnd(p.text);
        return c;
    }

    static void pipeline(String key, List<SubtitleSync.Cand> all, List<long[]> refs, long len, String video) {
        StringBuilder lg = new StringBuilder("alinhamento com " + refs.size() + " referência(s):");
        SubtitleSync.AlignReport ar = SubtitleSync.alignAll(all, refs, lg);
        List<SubtitleSync.Cand> rk = SubtitleSync.rank(all, len, video, lg);
        StringBuilder order = new StringBuilder();
        for (SubtitleSync.Cand c : rk) { if (order.length() > 0) order.append(','); order.append(c.rank); }
        List<String> kv = new ArrayList<>();
        kv.add("rep=" + ar.aligned + "/" + ar.refKind + "/" + ar.pieced + "/" + ar.freeScaled + "/" + ar.consensusMax + "/" + ar.ptCount);
        kv.add("order=" + order);
        for (SubtitleSync.Cand c : all) {
            kv.add("c" + c.rank + "=" + b(c.aligned) + "|" + c.sig + "|" + c.alignNote.replace("\t", " ") + "|" + c.lastMs + "|" + b(c.sparse) + b(c.partial) + b(c.pieced) + b(c.consensus) + b(c.fixed) + b(c.refSelf)
                    + "|" + c.group + "|" + c.groupSize + "|" + d9(c.alignScale) + "|" + c.alignOffsetMs + "|" + d9(c.alignScore) + "|" + d9(c.score) + "|" + hx(fnv(c.text == null ? "" : c.text)));
        }
        rec("P", key, kv.toArray(new String[0]));
        logLines(key, lg.toString());
    }

    static double median(List<Integer> l) { Collections.sort(l); return l.isEmpty() ? 0 : l.get(l.size() / 2); }

    static void pipelines(Title t) throws IOException {
        if (t.scenario) {
            List<SubtitleSync.Cand> all = new ArrayList<>();
            for (Sub p : t.pt) if (p.text != null) all.add(mk(p, p.idx, "f".equals(p.m)));
            List<long[]> refs = new ArrayList<>();
            for (Sub e : t.en) if (e.starts != null && e.starts.length >= 20) refs.add(e.starts);
            pipeline(t.key + "|S", all, refs, t.scenLen, t.video);
            return;
        }
        List<Sub> P = t.pt.stream().filter(s -> s.ptOk && s.starts != null && s.starts.length >= 20).limit(10).collect(Collectors.toList());
        List<Sub> E0 = t.en.stream().filter(s -> s.starts != null && s.starts.length >= 60).collect(Collectors.toList());
        if (P.size() < 2 || E0.isEmpty()) return;
        List<Sub> E = new ArrayList<>();
        E.add(E0.get(0));
        for (int k = 1; k < E0.size() && E.size() < 3; k++) {
            SubtitleAlign.Fit g = SubtitleAlign.align(E0.get(0).starts, E0.get(k).starts);
            if (g.ok() && !g.piecewise() && Math.abs(g.scale - 1) < 0.0005 && Math.abs(g.offsetMs) < 2000 && g.coverage >= 0.9) E.add(E0.get(k));
        }
        long len = Math.round(SubtitleText.lastCueEnd(E.get(0).text) / 0.95);
        List<SubtitleSync.Cand> a = new ArrayList<>();
        for (int i = 0; i < P.size(); i++) a.add(mk(P.get(i), i, false));
        List<long[]> refs = new ArrayList<>();
        for (Sub e : E) refs.add(e.starts);
        pipeline(t.key + "|A", a, refs, len, null);
        List<SubtitleSync.Cand> b = new ArrayList<>();
        for (int i = 0; i < P.size(); i++) b.add(mk(P.get(i), i, i == 0));
        pipeline(t.key + "|B", b, new ArrayList<long[]>(), len, null);
        // C: sem hash nenhum e sem referência (consenso puro), com o nome do vídeo = nome da EN
        List<SubtitleSync.Cand> c = new ArrayList<>();
        for (int i = 0; i < P.size(); i++) c.add(mk(P.get(i), i, false));
        pipeline(t.key + "|C", c, new ArrayList<long[]>(), len, E.get(0).name);
    }

    // ---------- 4. busca no addon ----------
    static synchronized void search(Title t) throws Exception {
        if (t.listJson == null) return;
        for (int variant = 0; variant < 2; variant++) {
            String js = variant == 0 ? t.listJson : t.listJson.replace("\"m\":", "\"mm\":");
            PlayerActivity.mock = js.getBytes(StandardCharsets.UTF_8);
            SubtitleSync.SearchResult r = SubtitleSync.search("movie", "tt0", "");
            List<String> kv = new ArrayList<>();
            kv.add("flags=" + b(r.ok) + b(r.sawMatchField) + b(r.refsAssumed) + "/" + r.total);
            StringBuilder p = new StringBuilder();
            for (SubtitleSync.Found f : r.pt) p.append(hx(fnv(f.url))).append(':').append(b(f.hashMatch)).append(':').append(f.lang).append(':').append(hx(fnv(f.rel))).append(';');
            kv.add("pt=" + p);
            StringBuilder q = new StringBuilder();
            for (SubtitleSync.Found f : r.refs) q.append(hx(fnv(f.url))).append(':').append(b(f.hashMatch)).append(':').append(f.lang).append(':').append(hx(fnv(f.rel))).append(';');
            kv.add("refs=" + q);
            rec("S", t.key + "|" + variant, kv.toArray(new String[0]));
        }
        PlayerActivity.mock = null;
    }

    // ---------- 5. nomes e consenso ----------
    static void names(Title t) {
        List<Sub> ens = t.en.stream().filter(s -> s.text != null).collect(Collectors.toList());
        if (ens.isEmpty()) return;
        Sub e0 = ens.get(0);
        StringBuilder sb = new StringBuilder();
        for (Sub p : t.pt) { sb.append(d9(SubtitleAlign.nameSimilarity(e0.name, p.name))).append(','); sb.append(d9(SubtitleAlign.nameSimilarity(p.name, e0.name))).append(';'); }
        rec("N", t.key, "v=" + sb, "z=" + d9(SubtitleAlign.nameSimilarity(null, "a")) + "/" + d9(SubtitleAlign.nameSimilarity("a.mkv", "")));
    }

    static void consensus(Title t) {
        List<Sub> P = t.pt.stream().filter(s -> s.ptOk && s.starts != null && s.starts.length >= 20).collect(Collectors.toList());
        if (P.size() < 3) return;
        List<long[]> st = new ArrayList<>();
        for (Sub p : P) st.add(p.starts);
        int[] comp = SubtitleAlign.consensusGroups(st);
        rec("K", t.key, "comp=" + csv(comp));
        for (Sub e : t.en) {
            if (e.starts == null || e.starts.length < 20) continue;
            boolean[] hash = new boolean[P.size()]; double[] nm = new double[P.size()]; long[] last = new long[P.size()];
            for (int i = 0; i < P.size(); i++) { nm[i] = SubtitleAlign.nameSimilarity(e.name, P.get(i).name); last[i] = SubtitleText.lastCueEnd(P.get(i).text); hash[i] = i == 1; }
            long len = Math.round(SubtitleText.lastCueEnd(e.text) / 0.95);
            StringBuilder why = new StringBuilder();
            int g = SubtitleAlign.pickGroup(comp, hash, nm, last, len, why);
            rec("K", t.key + "|" + e.kind, "g=" + g, "why=" + hx(fnv(why.toString())));
            logLines("K|" + t.key + "|" + e.kind, why.toString());
        }
    }

    // ---------- principal ----------
    static final class Lite { String key; List<long[]> pt = new ArrayList<>(), en = new ArrayList<>(); List<String> ptKinds = new ArrayList<>(), enKinds = new ArrayList<>(); }

    public static void main(String[] args) throws Exception {
        Path corpus = Paths.get(args[0]), syn = Paths.get(args[1]);
        final int maxPt = args.length > 3 ? Integer.parseInt(args[3]) : 14, maxEn = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        long t0 = System.currentTimeMillis();
        List<Title> titles = load(corpus, "");
        titles.addAll(load(syn.resolve("titles"), "syn/"));
        System.err.println("Java: " + titles.size() + " títulos carregados");
        ForkJoinPool pool = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        final List<Lite> lites = Collections.synchronizedList(new ArrayList<>());
        pool.submit(() -> titles.parallelStream().forEach(t -> {
            try {
                decodeAll(t);
                // pares PT x EN
                Lite lt = new Lite(); lt.key = t.key;
                List<Sub> ptS = t.pt.stream().filter(s -> s.ptOk && s.starts != null && s.starts.length >= 20).limit(maxPt).collect(Collectors.toList());
                List<Sub> enS = t.en.stream().filter(s -> s.starts != null && s.starts.length >= 20).limit(maxEn).collect(Collectors.toList());
                for (Sub p : ptS) { lt.pt.add(p.starts); lt.ptKinds.add(p.kind); }
                for (Sub e : enS) { lt.en.add(e.starts); lt.enKinds.add(e.kind); }
                if (!t.scenario) lites.add(lt);
                for (Sub p : ptS) for (Sub e : enS) recFit(t.key + "|" + p.kind + "|" + e.kind, SubtitleAlign.align(e.starts, p.starts), p.text);
                pipelines(t);
                names(t);
                consensus(t);
                if (!t.scenario) search(t);
                t.pt.clear(); t.en.clear(); t.oth.clear();
            } catch (Exception e) { throw new RuntimeException(t.key, e); }
        })).get();
        System.err.println("Java: títulos prontos em " + (System.currentTimeMillis() - t0) + " ms");
        // casos numéricos sintéticos
        Path nums = syn.resolve("nums");
        if (Files.isDirectory(nums)) {
            List<Path> fs;
            try (Stream<Path> s = Files.list(nums)) { fs = s.sorted().collect(Collectors.toList()); }
            pool.submit(() -> fs.parallelStream().forEach(f -> {
                try {
                    List<String> ln = Files.readAllLines(f, StandardCharsets.UTF_8);
                    long[] ref = parse(ln.get(0)), cand = parse(ln.get(1));
                    recFit("G/" + f.getFileName(), SubtitleAlign.align(ref, cand), null);
                    rec("GS", "G/" + f.getFileName(), "st=" + hx(fnv(ref)) + ":" + hx(fnv(cand)));
                } catch (IOException e) { throw new RuntimeException(e); }
            })).get();
        }
        // falsos positivos: PT de um título contra EN de outros (determinístico: os 3 títulos seguintes que têm EN)
        lites.sort((x, y) -> x.key.compareTo(y.key));
        List<Lite> withEn = lites.stream().filter(l -> !l.en.isEmpty()).collect(Collectors.toList());
        List<Runnable> jobs = new ArrayList<>();
        for (int i = 0; i < lites.size(); i++) {
            final Lite a = lites.get(i);
            if (a.pt.isEmpty()) continue;
            int idx = withEn.indexOf(a), taken = 0;
            for (int k = 1; k <= withEn.size() && taken < 3; k++) {
                final Lite o = withEn.get((Math.max(idx, 0) + k) % withEn.size());
                if (o == a) continue;
                taken++;
                for (int p = 0; p < Math.min(2, a.pt.size()); p++) {
                    final int pp = p;
                    jobs.add(() -> recFit("neg|" + a.key + "|" + a.ptKinds.get(pp) + "|" + o.key + "|" + o.enKinds.get(0), SubtitleAlign.align(o.en.get(0), a.pt.get(pp)), null));
                }
            }
        }
        pool.submit(() -> jobs.parallelStream().forEach(Runnable::run)).get();
        pool.shutdown();
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(args[2]), StandardCharsets.UTF_8))) {
            for (String v : REC.values()) pw.println(v);
        }
        System.err.println("Java: " + REC.size() + " registros em " + (System.currentTimeMillis() - t0) + " ms");
    }

    static long[] parse(String s) {
        if (s.isEmpty()) return new long[0];
        String[] p = s.split(",");
        long[] a = new long[p.length];
        for (int i = 0; i < a.length; i++) a[i] = Long.parseLong(p[i]);
        return a;
    }
}
