package io.github.gabrielxyz404.gabiflix.audiosync;

import java.util.ArrayList;

/** Uma fala da legenda (tempos em ms) e leitura de SRT. Sem regex, para portar 1:1 para Swift. */
public final class Cue {
    public final long startMs, endMs;
    /** letras/dígitos visíveis (sem tags nem colchetes) */
    public final int chars;
    /** false para efeitos sonoros/música: [música], (risos), ♪ ... */
    public final boolean speech;

    public Cue(long startMs, long endMs, int chars, boolean speech) {
        this.startMs = startMs; this.endMs = endMs; this.chars = chars; this.speech = speech;
    }

    /** fim "de fala" estimado: a legenda costuma ficar na tela além da fala (≈ 15 letras/s + folga) */
    public long speechEnd(boolean trim) {
        if (!trim) return endMs;
        long d = endMs - startMs;
        long est = 500L + chars * 62L;
        if (est < 700) est = 700;
        return startMs + Math.min(d, est);
    }

    public static Cue[] parseSrt(String text) {
        ArrayList<Cue> out = new ArrayList<>();
        if (text == null) return new Cue[0];
        if (text.length() > 0 && text.charAt(0) == '﻿') text = text.substring(1);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String ln = lines[i];
            int arrow = ln.indexOf("-->");
            if (arrow < 0) { i++; continue; }
            long a = parseTs(ln.substring(0, arrow)), b = parseTs(ln.substring(arrow + 3));
            i++;
            StringBuilder body = new StringBuilder();
            while (i < lines.length && lines[i].trim().length() > 0 && lines[i].indexOf("-->") < 0) {
                body.append(lines[i]).append('\n');
                i++;
            }
            if (a < 0 || b < a) continue;
            int[] cs = countChars(body.toString());
            if (cs[0] > 0 || cs[1] > 0) out.add(new Cue(a, b, cs[0], cs[0] > 0));
        }
        return out.toArray(new Cue[0]);
    }

    /** {letras de fala, letras em colchetes/notas} */
    static int[] countChars(String s) {
        int speech = 0, other = 0, depth = 0;
        boolean tag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { tag = true; continue; }
            if (c == '>') { tag = false; continue; }
            if (tag) continue;
            if (c == '{') { tag = true; continue; }
            if (c == '}') { tag = false; continue; }
            if (c == '[' || c == '(') { depth++; continue; }
            if (c == ']' || c == ')') { if (depth > 0) depth--; continue; }
            if (c == '♪' || c == '♫' || c == '♬') { other += 1; continue; }
            if (Character.isLetterOrDigit(c)) { if (depth > 0) other++; else speech++; }
        }
        return new int[]{speech, other};
    }

    static long parseTs(String s) {
        s = s.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        String[] p = s.replace('.', ',').split("[:,]");
        try {
            if (p.length == 4) return ((Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 60 + Long.parseLong(p[2])) * 1000 + frac(p[3]);
            if (p.length == 3) return (Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 1000 + frac(p[2]);
        } catch (NumberFormatException e) { return -1; }
        return -1;
    }

    private static long frac(String f) {
        if (f.length() == 1) return Long.parseLong(f) * 100;
        if (f.length() == 2) return Long.parseLong(f) * 10;
        return Long.parseLong(f.substring(0, 3));
    }
}
