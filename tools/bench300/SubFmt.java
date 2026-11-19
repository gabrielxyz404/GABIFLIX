package io.github.gabrielxyz404.gabiflix;

import io.github.gabrielxyz404.gabiflix.audiosync.Cue;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Parser de legendas do app (SubtitleText.decode + Cue.parseSrt, o caminho real do PlayerActivity/AudioSyncCore) posto a prova:
 *  real FILE...  : cada arquivo bruto baixado (legenda de verdade) -> decodifica -> {bytes, decodificou?, cues, 1a/ultima fala, parece PT?}  (linha JSON "F ...")
 *  synth REF.srt : a legenda certa reescrita em ~20 formatos/codificacoes (BOM, CRLF, CR, cp1252, UTF-16, ASS, VTT, gzip, zip, ms de 1/2 digitos...) -> decodifica -> compara com a original
 *                  (mesmo numero de falas e inicio de cada fala a +-10 ms) (linha JSON "S ...")
 * Fica no pacote do app so para chamar o que e privado do pacote (SubtitleText.decode). Nao muda nada do app.
 */
public class SubFmt {
    static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '"' || c == '\\') b.append('\\').append(c); else if (c < 0x20) b.append(' '); else b.append(c); }
        return b.append('"').toString();
    }

    static long ts(String s) {
        s = s.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        String[] p = s.replace('.', ',').split("[:,]");
        try {
            if (p.length == 4) return ((Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 60 + Long.parseLong(p[2])) * 1000 + Long.parseLong((p[3] + "00").substring(0, 3));
        } catch (NumberFormatException e) { return -1; }
        return -1;
    }

    static String fmtSrt(long ms, char sep, int hdig, int fdig) {
        long h = ms / 3600000, m = ms / 60000 % 60, s = ms / 1000 % 60, f = ms % 1000;
        String fs = String.format(Locale.ROOT, "%03d", f);
        if (fdig == 2) fs = String.format(Locale.ROOT, "%02d", f / 10);
        if (fdig == 1) fs = String.format(Locale.ROOT, "%d", f / 100);
        return String.format(Locale.ROOT, hdig == 1 ? "%d:%02d:%02d%c%s" : "%02d:%02d:%02d%c%s", h, m, s, sep, fs);
    }

    static String assTime(long ms) {
        long h = ms / 3600000, m = ms / 60000 % 60, s = ms / 1000 % 60, cs = ms % 1000 / 10;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
    }

    static String vttTime(long ms, boolean shortForm) {
        long h = ms / 3600000, m = ms / 60000 % 60, s = ms / 1000 % 60, f = ms % 1000;
        if (shortForm && h == 0) return String.format(Locale.ROOT, "%02d:%02d.%03d", m, s, f);
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, f);
    }

    /** (inicio, fim, texto) da certa, relida de um SRT UTF-8 qualquer */
    static List<Object[]> readRef(String text) {
        List<Object[]> out = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            int a = lines[i].indexOf("-->");
            if (a < 0) { i++; continue; }
            long s = ts(lines[i].substring(0, a)), e = ts(lines[i].substring(a + 3));
            i++;
            StringBuilder b = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty() && !lines[i].contains("-->")) { if (b.length() > 0) b.append('\n'); b.append(lines[i]); i++; }
            if (s >= 0 && e >= s && b.length() > 0) out.add(new Object[]{s, e, b.toString()});
        }
        return out;
    }

    static byte[] gz(byte[] d) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(bo)) { g.write(d); }
        return bo.toByteArray();
    }

    static byte[] zip(byte[] d, String name) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bo)) {
            z.putNextEntry(new java.util.zip.ZipEntry("LEIAME.txt"));
            z.write("texto qualquer".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry(name));
            z.write(d);
            z.closeEntry();
        }
        return bo.toByteArray();
    }

    static byte[] cat(byte[]... p) { ByteArrayOutputStream b = new ByteArrayOutputStream(); for (byte[] x : p) b.write(x, 0, x.length); return b.toByteArray(); }

    static String toSrt(List<Object[]> cues, char sep, int hdig, int fdig, boolean numbered, String nl, boolean tags) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Object[] c : cues) {
            if (numbered) sb.append(n).append(nl);
            n++;
            sb.append(fmtSrt((Long) c[0], sep, hdig, fdig)).append(" --> ").append(fmtSrt((Long) c[1], sep, hdig, fdig)).append(nl);
            String t = (String) c[2];
            if (tags) t = "<i>" + t.replace("\n", "</i>\n{\\an8}<i>") + "</i>";
            sb.append(t.replace("\n", nl)).append(nl).append(nl);
        }
        return sb.toString();
    }

    static String toAss(List<Object[]> cues, boolean ssaMarked) {
        StringBuilder sb = new StringBuilder("[Script Info]\nTitle: x\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize\nStyle: Default,Arial,20\n\n[Events]\n");
        sb.append(ssaMarked ? "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" : "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (Object[] c : cues)
            sb.append("Dialogue: ").append(ssaMarked ? "Marked=0" : "0").append(',').append(assTime((Long) c[0])).append(',').append(assTime((Long) c[1])).append(",Default,,0,0,0,,{\\i1}")
                    .append(((String) c[2]).replace("\n", "\\N")).append("\n");
        return sb.toString();
    }

    static String toVtt(List<Object[]> cues, boolean ids, boolean shortForm) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        int n = 1;
        for (Object[] c : cues) {
            if (ids) sb.append("cue").append(n).append('\n');
            n++;
            sb.append(vttTime((Long) c[0], shortForm)).append(" --> ").append(vttTime((Long) c[1], shortForm)).append(" line:90% align:center\n").append((String) c[2]).append("\n\n");
        }
        return sb.toString();
    }

    static String toMicroDvd(List<Object[]> cues) {
        StringBuilder sb = new StringBuilder();
        for (Object[] c : cues) sb.append('{').append(Math.round((Long) c[0] / 1000.0 * 23.976)).append("}{").append(Math.round((Long) c[1] / 1000.0 * 23.976)).append('}').append(((String) c[2]).replace("\n", "|")).append('\n');
        return sb.toString();
    }

    static String mojibake(String s) { return new String(s.getBytes(StandardCharsets.UTF_8), Charset.forName("windows-1252")); }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("real") || a[0].equals("dec")) {
            boolean dec = a[0].equals("dec");   // dec ENTRADA SAIDA: grava o texto que o app teria (UTF-8, SRT/VTT normalizado) e imprime a mesma linha F
            int end = dec ? 2 : a.length;
            for (int i = 1; i < end; i++) {
                byte[] d = Files.readAllBytes(Paths.get(a[i]));
                long t0 = System.nanoTime();
                String t;
                String exc = "";
                try { t = SubtitleText.decode(d); } catch (Throwable x) { t = null; exc = x.toString(); }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                StringBuilder j = new StringBuilder("F {\"file\":" + esc(Paths.get(a[i]).getFileName().toString()) + ",\"bytes\":" + d.length + ",\"decode_ms\":" + ms);
                if (!exc.isEmpty()) j.append(",\"exc\":").append(esc(exc));
                if (t == null) j.append(",\"decoded\":0");
                else {
                    Cue[] cs = Cue.parseSrt(t);
                    long first = cs.length > 0 ? cs[0].startMs : -1, last = 0;
                    int speech = 0, unsorted = 0, nodur = 0, overlap = 0;
                    long prevS = -1, prevE = -1;
                    for (Cue c : cs) {
                        last = Math.max(last, c.endMs);
                        if (c.speech) speech++;
                        if (c.startMs < prevS) unsorted++;
                        else if (c.startMs < prevE - 200) overlap++;
                        if (c.endMs <= c.startMs) nodur++;
                        prevS = Math.max(prevS, c.startMs);
                        prevE = c.endMs;
                    }
                    boolean moji = t.indexOf('Ã') >= 0 && t.matches("(?s).*Ã[\u0080-¿].*");
                    j.append(",\"decoded\":1,\"vtt\":").append(t.startsWith("WEBVTT") ? 1 : 0).append(",\"cues\":").append(cs.length).append(",\"speech\":").append(speech).append(",\"first\":").append(first).append(",\"last\":").append(last)
                            .append(",\"unsorted\":").append(unsorted).append(",\"overlap\":").append(overlap).append(",\"nodur\":").append(nodur).append(",\"mojibake\":").append(moji ? 1 : 0)
                            .append(",\"pt\":").append(SubtitleText.looksPortuguese(t) ? 1 : 0).append(",\"lastCueEnd\":").append(SubtitleText.lastCueEnd(t));
                    if (dec) Files.write(Paths.get(a[2]), t.getBytes(StandardCharsets.UTF_8));
                }
                System.out.println(j.append("}"));
            }
            return;
        }
        // synth
        String ref = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8);
        String title = a.length > 2 ? a[2] : "?";
        List<Object[]> cues = readRef(ref);
        // so as primeiras 400 falas (rapido); mantem os textos reais (acentos!)
        if (cues.size() > 400) cues = new ArrayList<>(cues.subList(0, 400));
        Cue[] truth = Cue.parseSrt(toSrt(cues, ',', 2, 3, true, "\n", false));
        Charset w1252 = Charset.forName("windows-1252");
        String base = toSrt(cues, ',', 2, 3, true, "\n", false);
        LinkedHashMap<String, byte[]> v = new LinkedHashMap<>();
        v.put("srt-utf8", base.getBytes(StandardCharsets.UTF_8));
        v.put("srt-utf8-bom", cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, base.getBytes(StandardCharsets.UTF_8)));
        v.put("srt-crlf", toSrt(cues, ',', 2, 3, true, "\r\n", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-cr-only", toSrt(cues, ',', 2, 3, true, "\r", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-cp1252", base.getBytes(w1252));
        v.put("srt-cp1252-crlf", toSrt(cues, ',', 2, 3, true, "\r\n", false).getBytes(w1252));
        v.put("srt-utf16le-bom", cat(new byte[]{(byte) 0xFF, (byte) 0xFE}, base.getBytes(StandardCharsets.UTF_16LE)));
        v.put("srt-utf16be-bom", cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, base.getBytes(StandardCharsets.UTF_16BE)));
        v.put("srt-utf16le-sem-bom", base.getBytes(StandardCharsets.UTF_16LE));
        v.put("srt-ponto-no-ms", toSrt(cues, '.', 2, 3, true, "\n", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-ms-2-digitos", toSrt(cues, ',', 2, 2, true, "\n", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-ms-1-digito", toSrt(cues, ',', 2, 1, true, "\n", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-hora-1-digito", toSrt(cues, ',', 1, 3, true, "\n", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-sem-numeros", toSrt(cues, ',', 2, 3, false, "\n", false).getBytes(StandardCharsets.UTF_8));
        v.put("srt-tags-html-an8", toSrt(cues, ',', 2, 3, true, "\n", true).getBytes(StandardCharsets.UTF_8));
        v.put("srt-mojibake", mojibake(base).getBytes(StandardCharsets.UTF_8));
        v.put("ass", toAss(cues, false).getBytes(StandardCharsets.UTF_8));
        v.put("ssa-marked", toAss(cues, true).getBytes(StandardCharsets.UTF_8));
        v.put("ass-utf8-bom", cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, toAss(cues, false).getBytes(StandardCharsets.UTF_8)));
        v.put("vtt", toVtt(cues, false, false).getBytes(StandardCharsets.UTF_8));
        v.put("vtt-ids-mm:ss", toVtt(cues, true, true).getBytes(StandardCharsets.UTF_8));
        v.put("srt-gzip", gz(base.getBytes(StandardCharsets.UTF_8)));
        v.put("srt-zip", zip(base.getBytes(StandardCharsets.UTF_8), "legenda.srt"));
        v.put("microdvd(nao suportado)", toMicroDvd(cues).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : v.entrySet()) {
            StringBuilder j = new StringBuilder("S {\"title\":" + esc(title) + ",\"fmt\":" + esc(e.getKey()));
            String t;
            try { t = SubtitleText.decode(e.getValue()); } catch (Throwable x) { j.append(",\"ok\":0,\"problem\":").append(esc("excecao " + x)).append("}"); System.out.println(j); continue; }
            if (t == null) { j.append(",\"ok\":0,\"problem\":\"decode devolveu null (a legenda seria descartada)\"}"); System.out.println(j); continue; }
            Cue[] got;
            try { got = Cue.parseSrt(t); } catch (Throwable x) { j.append(",\"ok\":0,\"problem\":").append(esc("parseSrt excecao " + x)).append("}"); System.out.println(j); continue; }
            String problem = "";
            double maxd = 0;
            int bad = 0;
            if (got.length != truth.length) problem = "falas " + got.length + " em vez de " + truth.length;
            else {
                for (int i = 0; i < got.length; i++) {
                    double d = Math.abs(got[i].startMs - truth[i].startMs);
                    // ASS tem so centesimos: aceita 10 ms
                    maxd = Math.max(maxd, d);
                    if (d > (e.getKey().startsWith("ass") || e.getKey().startsWith("ssa") || e.getKey().contains("ms-2") || e.getKey().contains("ms-1") ? 1000 : 10)) bad++;
                    if (got[i].speech != truth[i].speech) bad++;
                }
                if (bad > 0) problem = bad + " falas com tempo/classificacao diferente (desvio max " + (long) maxd + " ms)";
            }
            // acentos: o texto decodificado tem que conter os mesmos acentos
            boolean acc = true;
            String sample = (String) cues.get(Math.min(cues.size() - 1, 10))[2];
            if (!t.contains(sample.split("\n")[0].trim()) && !e.getKey().contains("tags") && !e.getKey().startsWith("ass") && !e.getKey().startsWith("ssa") && !e.getKey().contains("microdvd")) acc = false;
            j.append(",\"ok\":").append(problem.isEmpty() ? (acc ? 1 : 2) : 0).append(",\"cues\":").append(got.length).append(",\"maxDeltaMs\":").append((long) maxd);
            if (!problem.isEmpty()) j.append(",\"problem\":").append(esc(problem));
            else if (!acc) j.append(",\"problem\":\"tempos certos mas o texto (acentos) saiu diferente\"");
            System.out.println(j.append("}"));
        }
    }
}
