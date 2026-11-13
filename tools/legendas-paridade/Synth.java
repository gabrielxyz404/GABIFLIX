package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Gera os casos SINTÉTICOS do teste de paridade (os mesmos cenários dos testes T, T2 e TRev, mais arquivos de leitura/idioma "esquisitos"):
 *  - OUT/nums/NOME.txt : duas linhas (referência; candidata) com inícios de falas em ms, separados por vírgula
 *  - OUT/titles/NOME/  : pasta no formato do corpus (meta.txt + KIND.raw); com "# scenario len=MS" também roda o pipeline (alignAll + rank)
 * A versão Swift lê exatamente estes arquivos (não precisa reproduzir o gerador aleatório do Java).
 */
public class Synth {
    static Path out;
    static Random R = new Random(42);

    static String fmt(long ms) { return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000); }

    // ---------- T / T2 ----------
    static long[] base(long totalMs, int n) {
        ArrayList<Long> l = new ArrayList<>(); long t = 90000;
        while (l.size() < n && t < totalMs - 120000) {
            int burst = 5 + R.nextInt(30);
            for (int i = 0; i < burst && l.size() < n; i++) { t += 1200 + (long) (-Math.log(1 - R.nextDouble()) * 3500); l.add(t); }
            t += 20000 + R.nextInt(240000);
        }
        long[] a = new long[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a;
    }
    static long[] variant(long[] r, double drop, double add, int jit) {
        ArrayList<long[]> l = new ArrayList<>();
        for (long x : r) { if (R.nextDouble() < drop) continue; l.add(new long[]{x + (long) ((R.nextDouble() * 2 - 1) * jit), x}); if (R.nextDouble() < add) l.add(new long[]{x + 800 + R.nextInt(2000), x + 800}); }
        l.sort((p, q) -> Long.compare(p[0], q[0]));
        long[] a = new long[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i)[0]; return a;
    }
    static long[] xf(long[] a, double k, long off) { long[] o = new long[a.length]; for (int i = 0; i < a.length; i++) o[i] = Math.round(a[i] * k) + off; return o; }
    static long[] cutMid(long[] a, long at, long len) { long[] o = a.clone(); for (int i = 0; i < o.length; i++) if (o[i] >= at) o[i] += len; return o; }

    static int nn = 0;
    static void nums(String name, long[] ref, long[] cand) throws IOException {
        Path d = out.resolve("nums"); Files.createDirectories(d);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 2; k++) {
            long[] a = k == 0 ? ref : cand;
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append('\n');
        }
        Files.write(d.resolve(String.format("%03d_%s.txt", nn++, name.replaceAll("[^A-Za-z0-9]+", "_"))), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void tScenarios() throws IOException {
        long total = 110 * 60000L; long[] truth = base(total, 1500);
        long[] ref = variant(truth, 0.12, 0.10, 250);
        long[] pt = variant(truth, 0.08, 0.08, 250);
        nums("controle", ref, pt);
        for (long o : new long[]{3000, 7000, -12000, 45000, -90000, 115000, 125000, 150000, -200000}) nums("offset " + o, ref, xf(pt, 1, -o));
        nums("fps 25 -> 23.976", ref, xf(pt, 23.976 / 25, 0));
        nums("fps 23.976 -> 25", ref, xf(pt, 25 / 23.976, 0));
        nums("fps 25 + off 7", ref, xf(pt, 23.976 / 25, -7000));
        nums("fps 25 + off -30", ref, xf(pt, 23.976 / 25, 30000));
        nums("escala 1.001", ref, xf(pt, 1 / 1.001, 0));
        nums("escala 1.0043", ref, xf(pt, 1 / 1.0043, 2000));
        nums("escala 1.02", ref, xf(pt, 1 / 1.02, 0));
        nums("escala 1.04", ref, xf(pt, 1 / 1.04, 0));
        nums("corte 30 s aos 55", ref, cutMid(pt, 55 * 60000L, 30000));
        nums("corte 12 s aos 20", ref, cutMid(pt, 20 * 60000L, 12000));
        nums("corte -20 s aos 40", ref, cutMid(pt, 40 * 60000L, -20000));
        nums("dois cortes", ref, cutMid(cutMid(pt, 25 * 60000L, 15000), 70 * 60000L, -40000));
        nums("corte + offset", ref, xf(cutMid(pt, 50 * 60000L, 25000), 1, -7000));
        nums("corte aos 90", ref, cutMid(pt, 90 * 60000L, 30000));
        nums("escala livre + corte", ref, xf(cutMid(pt, 50 * 60000L, 20000), 1 / 1.02, 0));
        nums("intro 40 s", ref, xf(pt, 1, 40000));
        long[] v1 = variant(truth, 0.45, 0.02, 250); nums("45% removidas", ref, xf(v1, 1, -6000));
        long[] v2 = variant(truth, 0.1, 0.1, 600); nums("jitter 600", ref, xf(v2, 1, -6000));
        nums("referencia com corte", cutMid(ref, 55 * 60000L, 30000), pt);
        long[] other = base(total, 1500); nums("outro filme", ref, other);
        long[] ep = base(45 * 60000L, 500); long[] epRef = variant(ep, 0.1, 0.1, 250); long[] epPt = variant(ep, 0.1, 0.1, 250);
        for (long o : new long[]{7000, 40000}) nums("serie 45 min off " + o, epRef, xf(epPt, 1, -o));
        long[] ep2 = base(45 * 60000L, 500); nums("serie outro ep", variant(ep2, 0.1, 0.1, 250), xf(epPt, 1, -7000));
        // T2
        for (double s : new double[]{1.0005, 1.001, 1.002, 1.0043, 1.01}) nums("escala real " + s, ref, xf(pt, 1 / s, -3000));
        long[] part = pt.clone(); for (int i = 0; i < part.length; i++) if (part[i] < 8 * 60000L) part[i] += 25000; nums("+25 s nos primeiros 8 min", ref, part);
        long[] sparse = variant(truth, 0.7, 0.0, 250); nums("referencia esparsa", sparse, xf(pt, 1, -7000));
        // deriva pequena de escala (TRev 4b)
        for (int seed = 0; seed < 12; seed++) {
            R = new Random(100 + seed);
            long[] tr = base(1500, total);
            long[] r = variant(tr, 0.1, 0.1, 250);
            double k = 1 - 0.00012 * (1 + seed % 4);
            long[] p = xf(variant(tr, 0.08, 0.08, 250), k, 80 + seed * 40);
            nums("deriva " + seed, r, p);
        }
    }
    static long[] base(int n, long end) {
        long[] a = new long[n]; long t = 60000;
        for (int i = 0; i < n; i++) { t += 1200 + (long) (-Math.log(1 - R.nextDouble()) * (end - 120000) / n); a[i] = t; }
        return a;
    }

    // ---------- TRev (pipeline) ----------
    static String srt(long[] ts, long shift, double k, long jit, double drop, String[] txt) {
        StringBuilder sb = new StringBuilder(); int n = 1;
        for (long t : ts) {
            if (R.nextDouble() < drop) continue;
            long a = Math.round(t * k) + shift + (long) ((R.nextDouble() * 2 - 1) * jit);
            sb.append(n++).append("\r\n").append(fmt(a)).append(" --> ").append(fmt(a + 1500)).append("\r\n").append(txt[R.nextInt(txt.length)]).append("\r\n\r\n");
        }
        return sb.toString();
    }
    static final String[] PT = {"Não sei o que você quer.", "Eu estou aqui, então vai.", "Obrigado, tá bem."};
    static final String[] EN = {"I don't know what you want."};

    static void scenario(String name, long len, String[] refs, String[] cands, boolean[] hash, String video) throws IOException {
        Path d = out.resolve("titles").resolve(name);
        Files.createDirectories(d);
        StringBuilder meta = new StringBuilder("# type=movie id=syn label=" + name + " scenario len=" + len + (video != null ? " video=" + video : "") + "\n");
        for (int i = 0; i < refs.length; i++) {
            Files.write(d.resolve("en-" + i + ".raw"), refs[i].getBytes(StandardCharsets.UTF_8));
            meta.append("en-" + i + "\teng\tf\t{\"subtitleFileName\":\"ref" + i + ".srt\"}\n");
        }
        for (int i = 0; i < cands.length; i++) {
            Files.write(d.resolve("pt-" + i + ".raw"), cands[i].getBytes(StandardCharsets.UTF_8));
            meta.append("pt-" + i + "\tpor\t" + (hash != null && hash[i] ? "f" : "i") + "\t{\"subtitleFileName\":\"Movie.2020.1080p.WEB-DL.x264-GRP." + i + ".srt\"}\n");
        }
        Files.write(d.resolve("meta.txt"), meta.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void trev() throws IOException {
        long end = 110 * 60000L;
        R = new Random(5);
        long[] truth = base(1500, end);
        long[] forcedT = new long[70]; for (int i = 0; i < 70; i++) forcedT[i] = truth[i * 21];
        // 1. forçada + completa, com referência EN
        {
            String ref = srt(truth, 0, 1, 200, 0.1, EN);
            scenario("rev1_forcada", end, new String[]{ref}, new String[]{srt(forcedT, 0, 1, 50, 0, PT), srt(truth, 3000, 1, 200, 0.08, PT)}, null, null);
        }
        // 2. referências que discordam / concordam / maioria
        {
            String r1 = srt(truth, 0, 1, 200, 0.1, new String[]{"I don't know."});
            String r2 = srt(truth, 20000, 1, 200, 0.1, new String[]{"I don't know."});
            scenario("rev2a_discordam", end, new String[]{r1, r2}, new String[]{srt(truth, 3000, 1, 200, 0.08, PT)}, null, null);
            String r1b = srt(truth, 0, 1, 250, 0.12, new String[]{"I don't know."});
            scenario("rev2b_concordam", end, new String[]{r1, r1b}, new String[]{srt(truth, 3000, 1, 200, 0.08, PT)}, null, null);
            scenario("rev2c_maioria", end, new String[]{r1, r2, r1b}, new String[]{srt(truth, 3000, 1, 200, 0.08, PT)}, null, null);
        }
        // 3. sem referência: a PT do hash forçada não serve
        scenario("rev3_pt_hash", end, new String[0], new String[]{srt(forcedT, 0, 1, 50, 0, PT), srt(truth, 0, 1, 200, 0.05, PT), srt(truth, 4000, 1, 200, 0.08, PT)}, new boolean[]{true, true, false}, "Movie.2020.1080p.WEB-DL.x264-GRP.mkv");
        // 4. correção por fps só se as falas confirmarem
        {
            long[] t2 = base(1500, end);
            scenario("rev4_fps", end, new String[0], new String[]{srt(truth, 0, 1, 100, 0.05, PT), srt(truth, 0, 25 / 23.976, 100, 0.05, PT), srt(t2, 0, 25 / 23.976, 100, 0.05, PT)}, new boolean[]{true, false, false}, null);
        }
        // 5. sem hash nem referência: consenso entre PT (3 iguais de verdade + 1 de outro tempo + 1 de outro filme)
        {
            long[] t3 = base(1500, end);
            scenario("rev5_consenso", end, new String[0], new String[]{srt(truth, 0, 1, 150, 0.06, PT), srt(truth, 200, 1, 200, 0.08, PT), srt(truth, -150, 1, 200, 0.07, PT), srt(t3, 0, 1, 100, 0.05, PT), srt(truth, 30000, 1, 200, 0.08, PT)}, null, "Movie.2020.1080p.WEB-DL.x264-GRP.mkv");
        }
        // 6. só uma referência EN "de qualquer jeito" e várias PT com escalas diferentes (fps, deriva)
        {
            String ref = srt(truth, 0, 1, 250, 0.1, EN);
            scenario("rev6_escalas", end, new String[]{ref}, new String[]{srt(truth, 0, 23.976 / 25, 150, 0.05, PT), srt(truth, 5000, 1 / 1.0005, 150, 0.05, PT), srt(truth, 0, 1, 150, 0.05, PT), srt(cutMid(truth, 50 * 60000L, 20000), 0, 1, 150, 0.05, PT)}, null, null);
        }
    }

    // ---------- arquivos de leitura/idioma ----------
    static void fixture(String dirName, String kind, byte[] data, String lang) throws IOException {
        Path d = out.resolve("titles").resolve(dirName);
        Files.createDirectories(d);
        Files.write(d.resolve(kind + ".raw"), data);
        Path meta = d.resolve("meta.txt");
        String head = Files.exists(meta) ? "" : "# type=movie id=fx label=" + dirName + "\n";
        Files.write(meta, (head + kind + "\t" + lang + "\tf\t{\"subtitleFileName\":\"" + kind + ".srt\"}\n").getBytes(StandardCharsets.UTF_8), Files.exists(meta) ? new StandardOpenOption[]{StandardOpenOption.APPEND} : new StandardOpenOption[]{StandardOpenOption.CREATE});
    }

    static String cuesOf(String[] lines, int n, String eol, String timeStyle) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long a = 1000 + i * 4000L, b = a + 2000;
            String ta, tb;
            if (timeStyle.equals("dot")) { ta = fmt(a).replace(',', '.'); tb = fmt(b).replace(',', '.'); }
            else if (timeStyle.equals("short")) { ta = String.format("00:%02d:%02d,%d", (a / 60000) % 60, (a / 1000) % 60, (a % 1000) / 100); tb = String.format("00:%02d:%02d,%02d", (b / 60000) % 60, (b / 1000) % 60, (b % 1000) / 10); }
            else if (timeStyle.equals("nofrac")) { ta = String.format("00:%02d:%02d", (a / 60000) % 60, (a / 1000) % 60); tb = String.format("00:%02d:%02d", (b / 60000) % 60, (b / 1000) % 60); }
            else if (timeStyle.equals("nohour")) { ta = String.format("%02d:%02d.%03d", (a / 60000) % 60, (a / 1000) % 60, a % 1000); tb = String.format("%02d:%02d.%03d", (b / 60000) % 60, (b / 1000) % 60, b % 1000); }
            else if (timeStyle.equals("loose")) { ta = " " + fmt(a) + "  "; tb = "  " + fmt(b) + " X:0 Y:1"; }
            else { ta = fmt(a); tb = fmt(b); }
            sb.append(i + 1).append(eol).append(ta).append(timeStyle.equals("loose") ? "-->" : " --> ").append(tb).append(eol).append(lines[i % lines.length]).append(eol).append(eol);
        }
        return sb.toString();
    }

    static byte[] zip(String name, byte[] data, String other) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bo)) {
            if (other != null) { z.putNextEntry(new java.util.zip.ZipEntry(other)); z.write("LEIA-ME sem legenda".getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            z.putNextEntry(new java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry();
        }
        return bo.toByteArray();
    }
    static byte[] gz(byte[] data) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(bo)) { z.write(data); }
        return bo.toByteArray();
    }
    static byte[] cat(byte[]... p) { ByteArrayOutputStream b = new ByteArrayOutputStream(); for (byte[] x : p) b.write(x, 0, x.length); return b.toByteArray(); }

    static void fixtures() throws IOException {
        String[] ptL = {"Não sei você.", "Isso é tudo, então vai.", "Obrigado, tá bem, já até muito bem.", "Eu estou aqui, porque você vai também.", "Ele disse: nós temos uma casa. Sim, tudo aqui foi bem."};
        String body = cuesOf(ptL, 40, "\r\n", "std");
        byte[] u8 = body.getBytes(StandardCharsets.UTF_8);
        String D = "fx_leitura";
        fixture(D, "oth-utf8crlf-0", u8, "por");
        fixture(D, "oth-bom-0", cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, u8), "por");
        fixture(D, "oth-utf16le-0", cat(new byte[]{(byte) 0xFF, (byte) 0xFE}, body.getBytes(StandardCharsets.UTF_16LE)), "por");
        fixture(D, "oth-utf16be-0", cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, body.getBytes(StandardCharsets.UTF_16BE)), "por");
        fixture(D, "oth-utf16lenobom-0", body.getBytes(StandardCharsets.UTF_16LE), "por");
        fixture(D, "oth-utf16benobom-0", body.getBytes(StandardCharsets.UTF_16BE), "por");
        fixture(D, "oth-cp1252-0", body.getBytes("windows-1252"), "por");
        fixture(D, "oth-latin1-0", body.getBytes("ISO-8859-1"), "por");
        fixture(D, "oth-moji-0", new String(u8, "windows-1252").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-mojilatin-0", new String(u8, "ISO-8859-1").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-zipsrt-0", zip("legenda.srt", u8, null), "por");
        fixture(D, "oth-zipsecond-0", zip("Legenda.SRT", u8, "leia-me.txt"), "por");
        fixture(D, "oth-zipnosrt-0", zip("dados.bin", u8, null), "por");
        fixture(D, "oth-gz-0", gz(u8), "por");
        fixture(D, "oth-gztrunc-0", Arrays.copyOf(gz(u8), gz(u8).length - 10), "por");
        fixture(D, "oth-gzdouble-0", cat(gz(Arrays.copyOfRange(u8, 0, 1200)), gz(Arrays.copyOfRange(u8, 1200, u8.length))), "por");
        fixture(D, "oth-ziptrunc-0", Arrays.copyOf(zip("a.srt", u8, null), 400), "por");
        fixture(D, "oth-html-0", "<html><body>Not found, sorry for the error page, this is not a subtitle at all.</body></html>".getBytes(), "por");
        fixture(D, "oth-short-0", "abc".getBytes(), "por");
        fixture(D, "oth-binary-0", new byte[]{0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, (byte) 0x80, (byte) 0x81}, "por");
        fixture(D, "oth-cronly-0", cuesOf(ptL, 40, "\r", "std").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-lfonly-0", cuesOf(ptL, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-dot-0", cuesOf(ptL, 40, "\n", "dot").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-short-times-0", cuesOf(ptL, 40, "\n", "short").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-nofrac-0", cuesOf(ptL, 40, "\n", "nofrac").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-nohour-0", cuesOf(ptL, 40, "\n", "nohour").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-loose-0", cuesOf(ptL, 40, "\n", "loose").getBytes(StandardCharsets.UTF_8), "por");
        fixture(D, "oth-vtt-0", ("WEBVTT\n\n" + cuesOf(ptL, 40, "\n", "dot")).getBytes(StandardCharsets.UTF_8), "por");
        // ASS/SSA
        StringBuilder ass = new StringBuilder("[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name, Fontname\nStyle: Default,Arial\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (int i = 0; i < 8; i++) ass.append("Dialogue: 0,0:00:").append(String.format("%02d", i * 3)).append(".50,0:00:").append(String.format("%02d", i * 3 + 2)).append(".00,Default,,0,0,0,,{\\an8}Não, você\\Nvai {\\i1}já{\\i0}, então \\h isso, uma, aqui, bem, tudo.\n");
        fixture(D, "oth-ass-0", ass.toString().getBytes(StandardCharsets.UTF_8), "por");
        StringBuilder ass2 = new StringBuilder("[Events]\nFormat: Marked, Text, End, Start\n");
        for (int i = 0; i < 7; i++) ass2.append("Dialogue: Marked=0,Fala número ").append(i).append(" não você, estou, então,0:00:").append(String.format("%02d", i * 3 + 2)).append(".5,0:00:").append(String.format("%02d", i * 3)).append(".25\n");
        fixture(D, "oth-ass2-0", ass2.toString().getBytes(StandardCharsets.UTF_8), "por");
        StringBuilder ass3 = new StringBuilder("[Events]\nDialogue: 0,0:00:01.00,0:00:03.00,D,,0,0,0,,fala curta\nDialogue: 0,0:00:05.00,0:00:06.00,D,,0,0,0,,outra\n");
        fixture(D, "oth-ass3-0", ass3.toString().getBytes(StandardCharsets.UTF_8), "por");
        // idioma
        String[] es = {"Pero señor, ¿qué hay? Yo estoy muy bien, gracias. Los nosotros tengo."};
        String[] ptx = {"Não sei, você está aqui, então vai. Muito bem, obrigado."};
        String[] gl = {"Eu non sei, meu amigo; tudo bem aqui, ñ ñ ¡ei! vai."};
        String[] it = {"Non lo so, sono qui, vai tu, ma che cosa vuoi? Grazie mille, meu amico, tem tutto."};
        String[] en = {"I don't know what you want. They are not there, but you know this, just that."};
        String[] mix = {"Não sei você que isso muito bem, but I don't know what you want, they have it."};
        String L = "fx_idioma";
        fixture(L, "oth-es-0", cuesOf(es, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "spa");
        fixture(L, "oth-pt-0", cuesOf(ptx, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "por");
        fixture(L, "oth-gl-0", cuesOf(gl, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "glg");
        fixture(L, "oth-it-0", cuesOf(it, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "ita");
        fixture(L, "oth-en-0", cuesOf(en, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "eng");
        fixture(L, "oth-mix-0", cuesOf(mix, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "por");
        fixture(L, "oth-ptnfd-0", cuesOf(new String[]{"Não sei você, entao vai. Muito bem, obrigado. Isso e tudo, tem uma sim."}, 40, "\n", "std").getBytes(StandardCharsets.UTF_8), "por");
        // falas de diálogo (descrições, notas, tags)
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40; i++) big.append(i * 3 + 1).append("\n").append(fmt(i * 10000L)).append(" --> ").append(fmt(i * 10000L + 1000)).append("\n").append(i % 5 == 0 ? "[ruído]" : i % 7 == 0 ? "♪ la la ♪" : i % 11 == 0 ? "(suspira)" : i % 13 == 0 ? "<i>Itálico</i> {\\an8}com tags" : "Fala " + i).append("\n\n");
        fixture("fx_dialogo", "oth-descr-0", big.toString().getBytes(StandardCharsets.UTF_8), "por");
        String few = "1\n00:00:01,000 --> 00:00:02,000\n[porta bate]\n\n2\n00:00:03,000 --> 00:00:04,000\n♪ música ♪\n\n3\n00:00:05,000 --> 00:00:06,000\n(suspira)\nOlá, tudo bem?\n\n4\n00:00:07,000 --> 00:00:08,000\n\n\n";
        fixture("fx_dialogo", "oth-poucas-0", few.getBytes(StandardCharsets.UTF_8), "por");
        // tempos grandes, ordem bagunçada, falas vazias, 6000+ falas
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 6100; i++) many.append(i + 1).append("\n").append(fmt(((i * 7919L) % 6100) * 1000L + 500)).append(" --> ").append(fmt(((i * 7919L) % 6100) * 1000L + 1500)).append("\nFala ").append(i).append(" não\n\n");
        fixture("fx_muitas", "oth-muitas-0", many.toString().getBytes(StandardCharsets.UTF_8), "por");
    }

    public static void main(String[] a) throws Exception {
        out = Paths.get(a[0]);
        Files.createDirectories(out);
        R = new Random(42);
        tScenarios();
        trev();
        fixtures();
        System.out.println("sintéticos gerados em " + out);
    }
}
