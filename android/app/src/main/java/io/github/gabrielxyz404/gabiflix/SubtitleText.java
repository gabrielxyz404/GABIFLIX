package io.github.gabrielxyz404.gabiflix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texto das legendas (Java puro, testável na JVM): bytes -> texto UTF-8 limpo (gzip/zip, BOM, UTF-16, cp1252, "mojibake",
 * CRLF/CR, ASS/SSA -> SRT, tempos fora do padrão), conferência de idioma e fim da última fala.
 * Tudo o que sai daqui tem "\n" como fim de linha e tempos no formato "HH:MM:SS,mmm --> HH:MM:SS,mmm" (ou VTT).
 */
final class SubtitleText {
    private SubtitleText() {}

    private static final int MAX_BYTES = 5_000_000;

    /** bytes baixados -> texto de legenda normalizado; null se não for uma legenda (página de erro, arquivo vazio, outro formato) */
    static String decode(byte[] d) {
        try {
            if (d == null || d.length < 20) return null;
            if (d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4) d = unzipFirst(d);
            else if ((d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B) d = gunzip(d);
            if (d == null || d.length < 20) return null;
            String text = bytesToString(d);
            if (text == null) return null;
            return normalize(text);
        } catch (Exception e) { return null; }
    }

    static byte[] gunzip(byte[] d) throws IOException {
        return readAll(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(d)));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        while ((r = in.read(b)) > 0) { buf.write(b, 0, r); if (buf.size() > MAX_BYTES) break; }
        in.close();
        return buf.toByteArray();
    }

    /** primeira entrada de legenda de um .zip (.srt/.vtt/.ass/.ssa; senão a primeira que não seja pasta) */
    private static byte[] unzipFirst(byte[] d) throws IOException {
        java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(d));
        byte[] first = null;
        java.util.zip.ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String n = e.getName().toLowerCase(Locale.ROOT);
            byte[] data = readAll(z);
            if (n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa")) return data;
            if (first == null) first = data;
        }
        return first;
    }

    /** UTF-16 (com ou sem BOM), UTF-8 (com ou sem BOM) ou, se tiver bytes inválidos, Windows-1252 */
    static String bytesToString(byte[] d) {
        int n = d.length;
        if (n >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return new String(d, 2, n - 2, StandardCharsets.UTF_16LE);
        if (n >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return new String(d, 2, n - 2, StandardCharsets.UTF_16BE);
        if (n >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) { d = Arrays.copyOfRange(d, 3, n); n = d.length; }
        // UTF-16 sem BOM: um byte zero a cada dois, em texto que seria ASCII
        if (n >= 40) {
            int zeroOdd = 0, zeroEven = 0, sample = Math.min(n, 400) / 2;
            for (int i = 0; i < sample; i++) { if (d[2 * i] == 0) zeroEven++; if (d[2 * i + 1] == 0) zeroOdd++; }
            if (zeroOdd > sample * 0.4 && zeroEven == 0) return new String(d, StandardCharsets.UTF_16LE);
            if (zeroEven > sample * 0.4 && zeroOdd == 0) return new String(d, StandardCharsets.UTF_16BE);
        }
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d)).toString();
        } catch (CharacterCodingException e) {
            s = new String(d, Charset.forName("windows-1252"));
        }
        return fixMojibake(s);
    }

    private static final Pattern MOJI = Pattern.compile("Ã[ -¿]|Ã[ŒœŠšŸŽžƒˆ˜–—‘’‚“”„†‡•…‰‹›€™]");
    private static final Pattern GOOD_ACC = Pattern.compile("[ãõçéêáâíóôúàÃÕÇÉÊÁÂÍÓÔÚ]");

    /** UTF-8 lido como Windows-1252 ("nÃ£o" em vez de "não"): refaz o caminho inverso se isso der UTF-8 válido */
    static String fixMojibake(String s) {
        int bad = 0;
        Matcher m = MOJI.matcher(s);
        while (m.find()) bad++;
        if (bad < 5) return s;
        int good = 0;
        Matcher g = GOOD_ACC.matcher(s);
        while (g.find()) good++;
        if (good > bad) return s; // já tem acentos certos em quantidade: não mexe
        try {
            java.nio.charset.CharsetEncoder enc = Charset.forName("windows-1252").newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).onMalformedInput(CodingErrorAction.REPORT);
            ByteBuffer bb = enc.encode(java.nio.CharBuffer.wrap(s));
            byte[] raw = new byte[bb.remaining()];
            bb.get(raw);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (Exception e) { return s; }
    }

    // ---------- normalização ----------
    private static final Pattern TIME_LINE = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?[ \\t]*-->[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?(.*)$");
    private static final Pattern CANON_LINE = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})[ \\t]*-->[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3}).*$");

    /** fim de linha "\n", sem BOM; ASS/SSA vira SRT; linhas de tempo fora do padrão (centésimos, sem fração) são reescritas no padrão */
    static String normalize(String t) {
        if (t.indexOf('﻿') >= 0) t = t.replace("﻿", "");
        if (t.indexOf('\r') >= 0) t = t.replace("\r\n", "\n").replace('\r', '\n');
        if (!t.contains("-->")) {
            if (t.contains("[Events]") && t.contains("Dialogue:")) {
                String srt = assToSrt(t);
                return srt == null ? null : srt;
            }
            return null;
        }
        // só mexe nas linhas de tempo que não estão no padrão (a maioria dos arquivos passa direto)
        String[] lines = null;
        int i = 0, len = t.length();
        StringBuilder sb = null;
        while (i < len) {
            int e = t.indexOf('\n', i);
            if (e < 0) e = len;
            if (t.indexOf("-->", i) >= 0 && t.indexOf("-->", i) < e) {
                String ln = t.substring(i, e);
                if (!CANON_LINE.matcher(ln).matches()) {
                    Matcher m = TIME_LINE.matcher(ln);
                    if (m.matches()) {
                        if (sb == null) { sb = new StringBuilder(len + 64); sb.append(t, 0, i); }
                        sb.append(canon(m, 1)).append(" --> ").append(canon(m, 5)).append(m.group(9));
                        i = e;
                        if (i < len) { sb.append('\n'); i++; }
                        continue;
                    }
                }
            }
            if (sb != null) { sb.append(t, i, Math.min(len, e + 1)); }
            i = e + 1;
        }
        return sb != null ? sb.toString() : t;
    }

    private static String canon(Matcher m, int g) {
        long h = m.group(g) == null ? 0 : Long.parseLong(m.group(g));
        long mi = Long.parseLong(m.group(g + 1)), s = Long.parseLong(m.group(g + 2));
        String f = m.group(g + 3);
        long ms = 0;
        if (f != null) { while (f.length() < 3) f += "0"; ms = Long.parseLong(f); }
        long tot = ((h * 60 + mi) * 60 + s) * 1000 + ms;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", tot / 3600000, (tot / 60000) % 60, (tot / 1000) % 60, tot % 1000);
    }

    // ---------- ASS/SSA -> SRT ----------
    private static long assTime(String s) {
        Matcher m = Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?").matcher(s.trim());
        if (!m.matches()) return -1;
        String f = m.group(4);
        long ms = 0;
        if (f != null) { while (f.length() < 3) f += "0"; ms = Long.parseLong(f); }
        return ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + ms;
    }

    static String assToSrt(String t) {
        int iStart = 1, iEnd = 2, iText = 9, nf = 10;
        boolean inEvents = false;
        ArrayList<long[]> times = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        for (String ln : t.split("\n")) {
            String s = ln.trim();
            if (s.startsWith("[")) { inEvents = s.equalsIgnoreCase("[Events]"); continue; }
            if (!inEvents) continue;
            if (s.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] f = s.substring(7).split(",");
                nf = f.length;
                for (int k = 0; k < f.length; k++) {
                    String n = f[k].trim().toLowerCase(Locale.ROOT);
                    if (n.equals("start")) iStart = k; else if (n.equals("end")) iEnd = k; else if (n.equals("text")) iText = k;
                }
            } else if (s.regionMatches(true, 0, "Dialogue:", 0, 9)) {
                String[] f = s.substring(9).split(",", nf);
                if (f.length <= Math.max(iText, Math.max(iStart, iEnd))) continue;
                long a = assTime(f[iStart]), b = assTime(f[iEnd]);
                if (a < 0 || b < 0) continue;
                String body = f[iText].replaceAll("\\{[^}]*\\}", "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim();
                if (body.isEmpty()) continue;
                times.add(new long[]{a, b, times.size()});
                texts.add(body);
            }
        }
        if (times.size() < 5) return null;
        Collections.sort(times, (x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[2], y[2]));
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (long[] tm : times) {
            sb.append(n++).append('\n').append(fmt(tm[0])).append(" --> ").append(fmt(tm[1])).append('\n').append(texts.get((int) tm[2])).append("\n\n");
        }
        return sb.toString();
    }

    private static String fmt(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    // ---------- idioma ----------
    private static final HashSet<String> PT_W = words("não você vocês estou então isso são também muito porque pra obrigado tá nós meu minha ele dela já até bem tudo aqui foi vai tem sim uma");
    private static final HashSet<String> EN_W = words("the you and that what this with have are not but your i'm it's don't they there just know");
    private static final HashSet<String> ES_W = words("el los las pero usted esto estoy yo muy gracias señor tengo qué cómo hay soy eres nosotros");
    private static HashSet<String> words(String s) { return new HashSet<>(Arrays.asList(s.split(" "))); }

    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    /**
     * confere se o texto é mesmo português (e não inglês, espanhol, galego...): palavras típicas de cada idioma e,
     * como desempate, os sinais que só o espanhol/galego têm (ñ, ¿, ¡) contra os que só o português tem (ã, õ, ç)
     */
    static boolean looksPortuguese(String text) {
        int pt = 0, en = 0, es = 0;
        String s = text.length() > 300000 ? text.substring(0, 300000) : text;
        Matcher m = WORD.matcher(s);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (PT_W.contains(w)) pt++;
            if (EN_W.contains(w)) en++;
            if (ES_W.contains(w)) es++;
        }
        if (!(pt >= 3 && pt > 2 * Math.max(en, es))) return false;
        int ptMark = 0, esMark = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'ã' || c == 'õ' || c == 'Ã' || c == 'Õ' || c == 'ç') ptMark++;
            else if (c == 'ñ' || c == 'Ñ' || c == '¿' || c == '¡') esMark++;
        }
        return esMark <= ptMark; // texto sem nenhum ã/õ/ç mas com ñ/¿/¡ é espanhol/galego
    }

    // ---------- fim da última fala ----------
    private static final Pattern CUE_END = Pattern.compile("-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})");

    /** fim da última fala da legenda (ms): serve para ver se ela combina com a duração do filme */
    static long lastCueEnd(String text) {
        long max = 0;
        Matcher m = CUE_END.matcher(text);
        while (m.find()) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long t = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
            if (t > max) max = t;
        }
        return max;
    }
}
