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

    /** nomes de tag (HTML do SRT/VTT) que não são texto da fala */
    private static final String TAGS_SIMPLE = ",i,b,u,s,c,em,strong,strike,br,big,small,sub,sup,tt,q,mark,del,ins,ruby,rt,rp,center,";
    private static final String TAGS_ATTR = ",v,lang,font,span,a,p,div,";

    /**
     * s[i] é '<': se começa uma tag de verdade devolve o índice do '>' que a fecha, senão -1 (o '<' é um caractere de texto, como em
     * "<Hoje estou alegre, >" ou "2 < 3"). Tag = "<00:01.500>" (tempo dentro da fala do VTT), "<i>", "</i>", "</ i>", "<c.cor>", "<v Ana>",
     * "<font color=...>" (nome conhecido; atributos só onde fazem sentido).
     */
    static int tagEnd(String s, int i) {
        int n = s.length(), j = i + 1;
        if (j >= n) return -1;
        char c = s.charAt(j);
        if (c >= '0' && c <= '9') {
            int k = j;
            while (k < n && k < i + 16 && ((s.charAt(k) >= '0' && s.charAt(k) <= '9') || s.charAt(k) == ':' || s.charAt(k) == '.' || s.charAt(k) == ',')) k++;
            return k < n && s.charAt(k) == '>' ? k : -1;
        }
        boolean closing = false;
        if (c == '/') {
            closing = true;
            j++;
            while (j < n && s.charAt(j) == ' ') j++;
        }
        int k = j;
        while (k < n) {
            char x = s.charAt(k);
            if ((x >= 'a' && x <= 'z') || (x >= 'A' && x <= 'Z') || (x >= '0' && x <= '9')) k++; else break;
        }
        if (k == j) return -1;
        String name = "," + s.substring(j, k).toLowerCase(java.util.Locale.ROOT) + ",";
        boolean simple = TAGS_SIMPLE.contains(name), attr = TAGS_ATTR.contains(name);
        if (!simple && !attr) return -1;
        int e = k;
        while (e < n && s.charAt(e) != '>' && s.charAt(e) != '<' && s.charAt(e) != '\n') e++;
        if (e >= n || s.charAt(e) != '>') return -1;
        if (e == k) return e;                                           // "<i>"
        String rest = s.substring(k, e);
        if (closing) return rest.trim().isEmpty() ? e : -1;
        char r0 = rest.charAt(0);
        if (r0 == '/' && rest.trim().equals("/")) return e;            // "<br/>"
        if (r0 == '.' && simple) return rest.indexOf(' ') < 0 ? e : -1;  // "<c.yellow>"
        if (r0 != ' ' && r0 != '\t') return -1;
        if (rest.trim().isEmpty()) return e;                            // "<i >"
        if (name.equals(",v,") || name.equals(",lang,")) return e;      // "<v Ana>", "<lang pt-BR>"
        return attr && rest.indexOf('=') > 0 ? e : -1;                  // "<font color=\"#fff\">"
    }

    /** s[i] é '{': fim ('}') de um bloco de efeitos do ASS/MicroDVD ({\an8}, {\i1}, {an8}, {y:i}) na mesma linha; -1 se for só uma chave no texto */
    static int braceEnd(String s, int i) {
        int n = s.length(), j = i + 1;
        if (j >= n) return -1;
        char c = s.charAt(j);
        boolean ok = c == '\\'
                || (((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) && j + 1 < n && s.charAt(j + 1) == ':')
                || (c == 'a' && j + 2 < n && s.charAt(j + 1) == 'n' && s.charAt(j + 2) >= '1' && s.charAt(j + 2) <= '9');
        if (!ok) return -1;
        for (int k = j; k < n; k++) {
            if (s.charAt(k) == '}') return k;
            if (s.charAt(k) == '\n') return -1;
        }
        return -1;
    }

    /** {letras de fala, letras em colchetes/notas} */
    static int[] countChars(String s) {
        int speech = 0, other = 0, depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { int e = tagEnd(s, i); if (e > 0) { i = e; continue; } }
            else if (c == '{') { int e = braceEnd(s, i); if (e > 0) { i = e; continue; } }
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
