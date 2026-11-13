package io.github.gabrielxyz404.gabiflix;

import java.util.*;

/** Versão ANTES (main 1.0.76): cópia fiel de fetchText/decodeText/looksPortuguese/lastCueEnd do PlayerActivity e starts = cueStarts */
final class Variant {
    static final String NAME = "antes";
    static String decode(byte[] data) {
        try {
            if (data == null || data.length < 20) return null;
            if ((data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) data = gunzip(data);
            String text = decodeText(data);
            if (text == null || !text.contains("-->")) return null;
            return text.replace("﻿", "");
        } catch (Exception e) { return null; }
    }
    private static byte[] gunzip(byte[] d) throws java.io.IOException {
        java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(d));
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        while ((r = in.read(b)) > 0) { buf.write(b, 0, r); if (buf.size() > 5_000_000) break; }
        in.close();
        return buf.toByteArray();
    }
    private static String decodeText(byte[] d) {
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return new String(d, 2, d.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return new String(d, 2, d.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(d)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(d, java.nio.charset.Charset.forName("windows-1252"));
        }
    }
    private static final HashSet<String> PT_W = words("não você vocês estou então isso são também muito porque pra obrigado tá nós meu minha ele dela já até bem tudo aqui foi vai tem sim uma");
    private static final HashSet<String> EN_W = words("the you and that what this with have are not but your i'm it's don't they there just know");
    private static final HashSet<String> ES_W = words("el los las pero usted esto estoy yo muy gracias señor tengo qué cómo hay soy eres nosotros");
    private static HashSet<String> words(String s) { return new HashSet<>(Arrays.asList(s.split(" "))); }
    static boolean looksPt(String text) {
        int pt = 0, en = 0, es = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}']+").matcher(text.length() > 300000 ? text.substring(0, 300000) : text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (PT_W.contains(w)) pt++;
            if (EN_W.contains(w)) en++;
            if (ES_W.contains(w)) es++;
        }
        return pt >= 3 && pt > 2 * Math.max(en, es);
    }
    private static final java.util.regex.Pattern CUE_END = java.util.regex.Pattern.compile("-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})");
    static long lastEnd(String text) {
        long max = 0;
        java.util.regex.Matcher m = CUE_END.matcher(text);
        while (m.find()) {
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long t = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
            if (t > max) max = t;
        }
        return max;
    }
    static long[] starts(String text) { return SubtitleAlign.cueStarts(text); }
}
