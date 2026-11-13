package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

/** Baixa o corpus de legendas reais (PT e EN) do addon OpenSubtitles do Stremio para uma pasta. Uso: Fetch titles.txt outdir */
public class Fetch {
    static HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(java.time.Duration.ofSeconds(15)).build();
    static byte[] get(String u) throws Exception {
        for (int a = 0; a < 3; a++) {
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(u)).timeout(java.time.Duration.ofSeconds(40)).header("User-Agent", "GabiFlixEval").build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) return r.body();
                System.err.println("HTTP " + r.statusCode() + " " + u);
            } catch (Exception e) { System.err.println("erro " + e + " " + u); }
            Thread.sleep(1500);
        }
        return null;
    }
    static String decode(byte[] d) throws Exception {
        if (d.length > 2 && (d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B) d = new GZIPInputStream(new ByteArrayInputStream(d)).readAllBytes();
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(d)).toString(); }
        catch (CharacterCodingException e) { return new String(d, Charset.forName("windows-1252")); }
    }
    static boolean isPt(String l) { return l.equals("pob") || l.equals("por") || l.equals("pt") || l.equals("pt-br") || l.equals("ptb") || l.equals("ptbr") || l.equals("pt-pt"); }
    static boolean isEn(String l) { return l.equals("eng") || l.equals("en"); }
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);
        int ptMax = args.length > 2 ? Integer.parseInt(args[2]) : 14, enMax = args.length > 3 ? Integer.parseInt(args[3]) : 8;
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\|");
            String type = p[0], id = p[1], label = p[2];
            if (id.startsWith("search:")) {
                String[] s = id.split(":");
                byte[] b = get("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + s[1] + ".json");
                String js = b == null ? "" : new String(b, StandardCharsets.UTF_8);
                System.out.println("cinemeta " + s[1] + ": " + js.replaceAll("\\s+", " ").substring(0, Math.min(1500, js.length())));
                Matcher m = Pattern.compile("\"imdb_id\":\"(tt\\d+)\"[^{}]*?\"name\":\"([^\"]*)\"[^{}]*?\"releaseInfo\":\"(\\d{4})").matcher(js);
                id = null;
                while (m.find()) { if (m.group(3).equals(s[2])) { id = m.group(1); System.out.println("  -> " + id + " " + m.group(2)); break; } }
                if (id == null) { Matcher m2 = Pattern.compile("\"id\":\"(tt\\d+)\"").matcher(js); System.out.println("  sem ano igual; ids: "); while (m2.find()) System.out.print(m2.group(1) + " "); System.out.println(); continue; }
            }
            String dir = (label.replaceAll("[^A-Za-z0-9]+", "_"));
            Path td = out.resolve(dir);
            Files.createDirectories(td);
            byte[] lb = get("https://opensubtitles-v3.strem.io/subtitles/" + type + "/" + id + ".json");
            if (lb == null) { System.out.println(label + ": lista falhou"); continue; }
            String js = new String(lb, StandardCharsets.UTF_8);
            Files.writeString(td.resolve("list.json"), js);
            // itens como objetos simples {...}
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(js);
            int pt = 0, en = 0, total = 0;
            StringBuilder meta = new StringBuilder("type=" + type + "\nid=" + id + "\nlabel=" + label + "\n");
            while (m.find()) {
                String o = m.group();
                String url = field(o, "url"), lang = field(o, "lang").toLowerCase(), mm = field(o, "m");
                if (url.isEmpty()) continue;
                total++;
                if (total <= 3) System.out.println("  item: " + o);
                String kind = isPt(lang) && pt < ptMax ? "pt-" + pt : isEn(lang) && en < enMax ? "en-" + en : null;
                if (kind == null) continue;
                byte[] d = get(url);
                if (d == null) continue;
                String text = decode(d);
                if (!text.contains("-->")) continue;
                if (kind.startsWith("pt")) pt++; else en++;
                Files.writeString(td.resolve(kind + ".srt"), text.replace("﻿", ""));
                dump(label, kind, lang, mm, o, text);
                meta.append(kind).append('\t').append(lang).append('\t').append(mm).append('\t').append(o.replace('\n', ' ')).append('\n');
            }
            Files.writeString(td.resolve("meta.txt"), meta.toString());
            System.out.println(label + " (" + id + "): itens=" + total + " PT baixadas=" + pt + " EN baixadas=" + en);
        }
    }
    /** linha CORPUS|... com os inícios das falas (delta + varint + gzip + base64) para eu analisar offline pelos logs */
    static void dump(String label, String kind, String lang, String m, String o, String text) throws Exception {
        long[] st = SubtitleAlign.cueStarts(text);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        long prev = 0;
        for (long v : st) { long d = v - prev; prev = v; while (d >= 128) { bo.write((int) (d & 127) | 128); d >>= 7; } bo.write((int) d); }
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(z)) { g.write(bo.toByteArray()); }
        String b64 = Base64.getEncoder().encodeToString(z.toByteArray());
        String rel = (field(o, "subtitleFileName") + " ## " + field(o, "movieReleaseName") + " ## " + field(o, "releaseGroup") + " ## " + field(o, "releaseFormat")).replace("|", "/");
        System.out.println("CORPUS|" + label + "|" + kind + "|" + lang + "|" + m + "|" + field(o, "fpsMilli") + "|" + rel + "|" + lastEnd(text) + "|" + st.length + "|" + b64);
    }
    static long lastEnd(String text) {
        long max = 0;
        Matcher m = Pattern.compile("-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})").matcher(text);
        while (m.find()) { long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1)); long t = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4)); if (t > max) max = t; }
        return max;
    }
    static String field(String o, String k) {
        Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(\\d+))").matcher(o);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)).replace("\\/", "/") : "";
    }
}
