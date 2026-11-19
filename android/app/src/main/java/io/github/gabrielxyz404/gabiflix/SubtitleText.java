package io.github.gabrielxyz404.gabiflix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
    static String decode(byte[] d) { return decode(d, 0); }

    /** videoFps: quadros por segundo do vídeo, se o chamador souber (só serve para legenda MicroDVD, que marca o tempo em quadros); <= 0 = não sabe */
    static String decode(byte[] d, double videoFps) {
        try {
            if (d == null || d.length < 20) return null;
            if (d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4) d = unzipFirst(d);
            else if ((d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B) d = gunzip(d);
            if (d == null || d.length < 20) return null;
            String text = bytesToString(d);
            if (text == null) return null;
            return normalize(text, videoFps);
        } catch (Exception e) { return null; }
    }

    static byte[] gunzip(byte[] d) throws IOException {
        return readAll(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(d)));
    }

    private static byte[] readAll(InputStream in) throws IOException { return readAll(in, true); }

    /** close=false: para ler uma entrada de um ZipInputStream sem fechar o zip inteiro (close() do ZipInputStream fecha tudo e o getNextEntry seguinte dá "Stream closed") */
    private static byte[] readAll(InputStream in, boolean close) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int r;
        while ((r = in.read(b)) > 0) { buf.write(b, 0, r); if (buf.size() > MAX_BYTES) break; }
        if (close) in.close();
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
            byte[] data = readAll(z, false);
            if (n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa")) return data;
            if (first == null) first = data;
        }
        return first;
    }

    /**
     * UTF-16 (com BOM, ou sem BOM com byte zero a cada dois), UTF-8 (com ou sem BOM) ou, se tiver bytes inválidos, a melhor entre
     * Windows-1252 e ISO-8859-1. Depois desfaz UTF-8 "duplamente codificado" (mojibake).
     */
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
        String s = utf8OrNull(d);
        if (s == null) s = legacyToString(d);
        return fixMojibake(s);
    }

    /** índice do primeiro byte que não é UTF-8 válido (sequência curta, "overlong", substituto ou > U+10FFFF); d.length se tudo é válido */
    static int utf8Valid(byte[] d) {
        int i = 0, n = d.length;
        while (i < n) {
            int b0 = d[i] & 0xFF;
            if (b0 < 0x80) { i++; continue; }
            int need;
            int cp;
            int min;
            if (b0 >= 0xC2 && b0 <= 0xDF) { need = 1; cp = b0 & 0x1F; min = 0x80; }
            else if (b0 >= 0xE0 && b0 <= 0xEF) { need = 2; cp = b0 & 0x0F; min = 0x800; }
            else if (b0 >= 0xF0 && b0 <= 0xF4) { need = 3; cp = b0 & 0x07; min = 0x10000; }
            else return i;
            if (i + need >= n) return i;
            for (int k = 1; k <= need; k++) {
                int b = d[i + k] & 0xFF;
                if ((b & 0xC0) != 0x80) return i;
                cp = (cp << 6) | (b & 0x3F);
            }
            if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return i;
            i += need + 1;
        }
        return n;
    }

    /** o que sobra a partir de p é o começo (cortado) de uma sequência UTF-8: o arquivo foi truncado no meio de uma letra */
    private static boolean truncatedTail(byte[] d, int p) {
        int left = d.length - p, b0 = d[p] & 0xFF;
        int need = b0 >= 0xC2 && b0 <= 0xDF ? 1 : b0 >= 0xE0 && b0 <= 0xEF ? 2 : b0 >= 0xF0 && b0 <= 0xF4 ? 3 : 0;
        if (need == 0 || left > need) return false;
        for (int k = 1; k < left; k++) if ((d[p + k] & 0xC0) != 0x80) return false;
        return true;
    }

    /** UTF-8 estrito (tolera só o corte no meio de uma letra no fim do arquivo, como o que o limite de 5 MB faz); null se não for UTF-8 */
    private static String utf8OrNull(byte[] d) {
        int p = utf8Valid(d), n = d.length;
        if (p == n) return new String(d, 0, n, StandardCharsets.UTF_8);
        if (n - p <= 3 && truncatedTail(d, p)) return new String(d, 0, p, StandardCharsets.UTF_8);
        return null;
    }

    /** 0x80..0x9F do Windows-1252; as 5 posições sem letra (0x81 0x8D 0x8F 0x90 0x9D) ficam como o caractere de controle de mesmo número (como o ISO-8859-1) */
    private static final String CP1252_HI = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ";

    /**
     * Sem UTF-8 válido: Windows-1252 ou ISO-8859-1. As duas só diferem nos bytes 0x80..0x9F: no 1252 são aspas, travessões, reticências...;
     * no ISO-8859-1 são caracteres de controle (nunca aparecem em texto de verdade). Pontuação: cada byte desses que o 1252 sabe ler
     * vale +1 para o 1252, e os cinco sem significado nele (0x81 0x8D 0x8F 0x90 0x9D) valem +1 para o ISO-8859-1, que os guarda como estão.
     * Empate (ou nenhum byte nessa faixa): Windows-1252.
     */
    private static String legacyToString(byte[] d) {
        int s1252 = 0, sLatin1 = 0;
        for (byte x : d) {
            int b = x & 0xFF;
            if (b < 0x80 || b >= 0xA0) continue;
            if (b == 0x81 || b == 0x8D || b == 0x8F || b == 0x90 || b == 0x9D) sLatin1++; else s1252++;
        }
        boolean latin1 = sLatin1 > s1252;
        char[] out = new char[d.length];
        for (int i = 0; i < d.length; i++) {
            int b = d[i] & 0xFF;
            out[i] = (b >= 0x80 && b < 0xA0 && !latin1) ? CP1252_HI.charAt(b - 0x80) : (char) b;
        }
        return new String(out);
    }

    /** byte que o caractere teria numa leitura errada de UTF-8 como Windows-1252/ISO-8859-1: 0x80..0xFF; -2 = U+FFFD (byte perdido); -1 = nenhum */
    private static int mojiByte(char c) {
        if (c < 0x80) return -1;
        if (c <= 0xFF) return c;
        if (c == 0xFFFD) return -2;
        int k = CP1252_HI.indexOf(c);
        return k >= 0 ? 0x80 + k : -1;
    }

    /**
     * "Ã" + U+FFFD em s[i]: o byte perdido foi 0x81 (Á) ou 0x8D (Í) (os outros três, Ï Ð Ý, são raros). Só maiúscula chega aqui (as minúsculas á í não
     * perdem byte). Palpite pela letra seguinte, medido nas legendas em português da bancada de 300 títulos (90% de acerto): Í antes de a c d n t
     * (Índia, Íamos, POLÍCIA, MANÍACO, TÍTULO), Á no resto (Água, África, ESTÁ, DEMOCRÁTICO).
     */
    private static int lostByte(String s, int i) {
        if (i + 2 >= s.length()) return 0x81;
        char c = Character.toLowerCase(s.charAt(i + 2));
        return c == 'a' || c == 'c' || c == 'd' || c == 'n' || c == 't' ? 0x8D : 0x81;
    }

    /**
     * Uma sequência UTF-8 lida como Windows-1252 começando em s[i] ("Ã£" = ã, "â€”" = —): devolve quantos caracteres ocupa (0 se não for uma) e o
     * caractere (ou par substituto) em out. lossy: aceita também "Ã" + espaço (o espaço que no lugar do NBSP: à), "Ã" + U+FFFD (byte perdido: Á) e
     * "â€" + U+FFFD (”), que é como os arquivos mojibake perdem os bytes 0x81, 0x8D, 0x8F, 0x90 e 0x9D.
     */
    private static int mojiSeq(String s, int i, boolean lossy, StringBuilder out) {
        int n = s.length();
        int lead = mojiByte(s.charAt(i));
        if (lead < 0xC2 || lead > 0xF4) return 0;
        int need = lead <= 0xDF ? 1 : lead <= 0xEF ? 2 : 3;
        if (i + need >= n) return 0;
        int cp = lead <= 0xDF ? lead & 0x1F : lead <= 0xEF ? lead & 0x0F : lead & 0x07;
        int prev = 0;
        for (int k = 1; k <= need; k++) {
            char c = s.charAt(i + k);
            int b = mojiByte(c);
            if (b == -2 && lossy) {
                if (need == 1 && lead == 0xC3) b = lostByte(s, i);
                else if (need == 2 && lead == 0xE2 && k == 2 && prev == 0x80) b = 0x9D;
                else return 0;
            } else if (c == ' ' && lossy && need == 1 && (lead == 0xC3 || lead == 0xC2)) b = 0xA0;
            if (b < 0x80 || b > 0xBF) return 0;
            cp = (cp << 6) | (b & 0x3F);
            prev = b;
        }
        int min = need == 1 ? 0x80 : need == 2 ? 0x800 : 0x10000;
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return 0;
        out.appendCodePoint(cp);
        return need + 1;
    }

    /**
     * UTF-8 lido como Windows-1252 ("nÃ£o" em vez de "não", "â€™" em vez de "’"), às vezes duas vezes: refaz o caminho inverso, sequência por
     * sequência (o que não forma UTF-8 válido não é mexido). Só age se o texto é mesmo mojibake: sequências desfeitas pelo menos metade
     * do número de letras acentuadas soltas (um texto certo com um "Ã©" por acaso não é tocado).
     */
    static String fixMojibake(String s) {
        for (int round = 0; round < 2; round++) {
            String r = fixMojibakeOnce(s);
            if (r == s) return s;
            s = r;
        }
        return s;
    }

    private static String fixMojibakeOnce(String s) {
        int n = s.length(), seqs = 0, lone = 0;
        boolean any = false;
        for (int i = 0; i < n; i++) { char c = s.charAt(i); if (c >= 0xC2 && c <= 0xF4) { any = true; break; } }
        if (!any) return s;
        StringBuilder tmp = new StringBuilder();
        for (int i = 0; i < n; ) {
            tmp.setLength(0);
            int len = mojiSeq(s, i, false, tmp);
            if (len > 0) { seqs++; i += len; continue; }
            char c = s.charAt(i);
            if (c >= 0xC0 && c <= 0xFF && c != 0xD7 && c != 0xF7) lone++;
            i++;
        }
        if (seqs == 0 || seqs * 2 < lone || (seqs < 3 && lone > 0)) return s;
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; ) {
            int len = mojiSeq(s, i, true, out);
            if (len > 0) { i += len; continue; }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    // ---------- normalização ----------
    private static final Pattern TIME_LINE = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?[ \\t]*-->[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?(.*)$");
    private static final Pattern CANON_LINE = Pattern.compile(
            "^[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3})[ \\t]*-->[ \\t]*(?:(\\d+):)?(\\d{1,2}):(\\d\\d)[,.](\\d{3}).*$");

    /** fim de linha "\n", sem BOM; ASS/SSA vira SRT; linhas de tempo fora do padrão (centésimos, sem fração) são reescritas no padrão */
    static String normalize(String t) { return normalize(t, 0); }

    static String normalize(String t, double videoFps) {
        if (t.indexOf('﻿') >= 0) t = t.replace("﻿", "");
        if (t.indexOf('\r') >= 0) t = t.replace("\r\n", "\n").replace('\r', '\n');
        if (!t.contains("-->")) {
            if (t.contains("[Events]") && t.contains("Dialogue:")) {
                String srt = assToSrt(t);
                return srt == null ? null : srt;
            }
            return microDvdToSrt(t, videoFps);
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
        String res = sb != null ? sb.toString() : t;
        return isVtt(res) ? cleanVtt(res) : res;
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

    /** bloco de efeitos do ASS que liga o modo de desenho (\p1, \p2...): o "texto" que vem depois são comandos de desenho */
    private static final Pattern ASS_DRAWING = Pattern.compile("\\{[^}]*\\\\p[1-9][^}]*\\}");

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
                if (ASS_DRAWING.matcher(f[iText]).find()) continue;   // desenho vetorial ({\p1}m 0 0 l 100 0...): não é fala, aparecia como texto na tela
                String body = f[iText].replaceAll("\\{[^}]*\\}", "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim();
                if (body.isEmpty()) continue;
                times.add(new long[]{a, b, times.size()});
                texts.add(body);
            }
        }
        if (times.size() < 5) return null;
        // na ordem do arquivo, como o SRT: quem usa as falas já ordena por início (a tela e o alinhador). Ordenar aqui trocava o índice de uma fala
        // fora de ordem do arquivo (screambloodymurder1975: 8 falas "deslocadas" até 104 s só porque o ASS saía ordenado e o SRT da mesma legenda não)
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

    // ---------- MicroDVD -> SRT ----------
    private static final double DEFAULT_FPS = 23.976;

    /** "{inicio}{fim}" no começo da linha (depois de espaços): {inicio, fim (-1 se vazio), índice do texto}; null se a linha não é de fala MicroDVD */
    private static long[] mdvdHead(String ln) {
        int n = ln.length(), p = 0;
        while (p < n && (ln.charAt(p) == ' ' || ln.charAt(p) == '\t')) p++;
        long[] v = new long[3];
        for (int k = 0; k < 2; k++) {
            if (p >= n || ln.charAt(p) != '{') return null;
            p++;
            long x = 0;
            int nd = 0;
            while (p < n && ln.charAt(p) >= '0' && ln.charAt(p) <= '9') { if (nd < 9) x = x * 10 + (ln.charAt(p) - '0'); nd++; p++; }
            if (nd > 9 || p >= n || ln.charAt(p) != '}') return null;
            if (nd == 0 && k == 0) return null;
            v[k] = nd == 0 ? -1 : x;
            p++;
        }
        v[2] = p;
        return v;
    }

    /** "23.976", "25", "29,97": número de quadros por segundo entre 5 e 120; 0 se não for */
    private static double parseFps(String s) {
        s = s.trim();
        int n = s.length(), dots = 0;
        if (n == 0 || n > 8) return 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '.' || c == ',') { dots++; if (i == 0 || i == n - 1) return 0; }
            else if (c < '0' || c > '9') return 0;
        }
        if (dots > 1) return 0;
        double f = Double.parseDouble(s.replace(',', '.'));
        return f >= 5 && f <= 120 ? f : 0;
    }

    /** texto de uma fala MicroDVD: tira {y:i}, {c:$0000FF}, {f:Arial}... e troca "|" por quebra de linha */
    private static String mdvdBody(String s) {
        StringBuilder a = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '{') {
                int j = s.indexOf('}', i + 1);
                if (j > 0) { i = j; continue; }
            }
            a.append(c == '|' ? '\n' : c);
        }
        StringBuilder out = new StringBuilder();
        for (String ln : a.toString().split("\n", -1)) {
            String t = ln.trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    /**
     * MicroDVD ("{120}{180}Texto|segunda linha", tempos em QUADROS) -> SRT. fps: o da linha de cabeçalho "{1}{1}23.976", se houver;
     * senão o do vídeo (videoFps, quando o chamador sabe); senão 23.976. null se o texto não for MicroDVD (menos de 3 falas nesse formato).
     */
    static String microDvdToSrt(String t, double videoFps) {
        double fps = 0;
        boolean first = true;
        ArrayList<long[]> fr = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        for (String ln : t.split("\n")) {
            long[] h = mdvdHead(ln);
            if (h == null) continue;
            String rest = ln.substring((int) h[2]);
            if (first) {
                first = false;
                if (h[0] <= 1 && h[1] >= 0 && h[1] <= 1) {
                    double f = parseFps(rest);
                    if (f > 0) { fps = f; continue; }
                }
            }
            String body = mdvdBody(rest);
            if (body.isEmpty()) continue;
            fr.add(new long[]{h[0], h[1]});
            texts.add(body);
        }
        if (fr.size() < 3) return null;
        if (fps <= 0) fps = videoFps >= 5 && videoFps <= 120 ? videoFps : DEFAULT_FPS;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < fr.size(); k++) {
            long a = Math.round(fr.get(k)[0] * 1000.0 / fps);
            long e = fr.get(k)[1] > fr.get(k)[0] ? Math.round(fr.get(k)[1] * 1000.0 / fps) : a + 2000;
            sb.append(k + 1).append('\n').append(fmt(a)).append(" --> ").append(fmt(e)).append('\n').append(texts.get(k)).append("\n\n");
        }
        return sb.toString();
    }

    // ---------- VTT ----------
    static boolean isVtt(String t) {
        int p = 0;
        while (p < t.length() && t.charAt(p) <= ' ') p++;
        return t.startsWith("WEBVTT", p);
    }

    /** tempo H:MM:SS.mmm / MM:SS.mmm / SS sem fração a partir de i (como o TIME_LINE): {ms, índice depois}; null se não bate */
    private static long[] timeAt(String u, int i) {
        int hi = u.length();
        for (int variant = 0; variant < 2; variant++) {
            int p = i;
            long h = 0;
            if (variant == 0) {
                int q = p;
                long v = 0;
                while (q < hi && u.charAt(q) >= '0' && u.charAt(q) <= '9') { v = v * 10 + (u.charAt(q) - '0'); q++; }
                if (q == p || q >= hi || u.charAt(q) != ':') continue;
                h = v;
                p = q + 1;
            }
            int q = p, nd = 0;
            long mi = 0;
            while (q < hi && nd < 2 && u.charAt(q) >= '0' && u.charAt(q) <= '9') { mi = mi * 10 + (u.charAt(q) - '0'); q++; nd++; }
            if (nd == 0 || q >= hi || u.charAt(q) != ':') continue;
            q++;
            long s = 0;
            nd = 0;
            while (q < hi && nd < 2 && u.charAt(q) >= '0' && u.charAt(q) <= '9') { s = s * 10 + (u.charAt(q) - '0'); q++; nd++; }
            if (nd == 0) continue;
            long ms = 0;
            if (q < hi && (u.charAt(q) == ',' || u.charAt(q) == '.')) {
                int k = q + 1, fd = 0;
                long f = 0;
                while (k < hi && fd < 3 && u.charAt(k) >= '0' && u.charAt(k) <= '9') { f = f * 10 + (u.charAt(k) - '0'); k++; fd++; }
                if (fd > 0) { while (fd < 3) { f *= 10; fd++; } ms = f; q = k; }
            }
            return new long[]{((h * 60 + mi) * 60 + s) * 1000 + ms, q};
        }
        return null;
    }

    /** linha "início --> fim [configurações]": {início, fim, índice onde começam as configurações}; null se não for */
    private static long[] timingLine(String ln) {
        int n = ln.length(), p = 0;
        while (p < n && (ln.charAt(p) == ' ' || ln.charAt(p) == '\t')) p++;
        long[] a = timeAt(ln, p);
        if (a == null) return null;
        int q = (int) a[1];
        while (q < n && (ln.charAt(q) == ' ' || ln.charAt(q) == '\t')) q++;
        if (!ln.startsWith("-->", q)) return null;
        q += 3;
        while (q < n && (ln.charAt(q) == ' ' || ln.charAt(q) == '\t')) q++;
        long[] b = timeAt(ln, q);
        if (b == null) return null;
        return new long[]{a[0], b[0], b[1]};
    }

    /** fim ('>') de uma marca que o navegador não mostra como texto: "<00:00:01.000>" (tempo dentro da fala), "<c>", "</c>", "<c.cor>"; -1 se não for */
    private static int inlineTagEnd(String s, int i) {
        int n = s.length(), j = i + 1;
        if (j >= n) return -1;
        char c = s.charAt(j);
        if (c >= '0' && c <= '9') {
            int k = j;
            while (k < n && k < i + 16 && ((s.charAt(k) >= '0' && s.charAt(k) <= '9') || s.charAt(k) == ':' || s.charAt(k) == '.' || s.charAt(k) == ',')) k++;
            return k < n && s.charAt(k) == '>' ? k : -1;
        }
        if (c == '/') j++;
        if (j >= n || s.charAt(j) != 'c') return -1;
        j++;
        if (j < n && s.charAt(j) == '.') { while (j < n && s.charAt(j) != '>' && s.charAt(j) != '<' && s.charAt(j) != ' ') j++; }
        return j < n && s.charAt(j) == '>' ? j : -1;
    }

    private static String stripInlineTags(String s) {
        if (s.indexOf('<') < 0) return s;
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '<') {
                int e = inlineTagEnd(s, i);
                if (e > 0) { i = e; continue; }
            }
            o.append(s.charAt(i));
        }
        return o.toString();
    }

    /** chave para comparar linhas: sem nenhuma tag, espaços juntados */
    private static String lineKey(String s) {
        StringBuilder o = new StringBuilder();
        boolean sp = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                int e = s.indexOf('>', i);
                if (e > 0) { i = e; continue; }
            }
            if (c <= ' ' || c == '\u00A0') { sp = o.length() > 0; continue; }
            if (sp) { o.append(' '); sp = false; }
            o.append(c);
        }
        return o.toString();
    }

    /**
     * VTT como o navegador mostra: a fala vai até uma linha VAZIA (uma linha só com espaço faz parte da fala: o autogerado do YouTube/IA abre
     * cada fala com " "), as marcas de tempo dentro da fala ("<00:00:01.000><c> palavra</c>") não são texto, e no "rolling caption" a fala
     * que só repete o que a anterior já mostrava na linha de baixo (a mesma coisa de novo por 10 ms) não é outra fala: o tempo dela vai para a
     * anterior. Sai em SRT-com-cabeçalho-VTT: "WEBVTT", e cada fala com tempo, linhas sem linha em branco no meio e uma linha vazia no fim.
     * Se não sobrar nenhuma fala devolve o texto como veio.
     */
    static String cleanVtt(String t) {
        String[] lines = t.split("\n", -1);
        boolean rolling = false;
        for (int k = t.indexOf('<'); k >= 0 && !rolling; k = t.indexOf('<', k + 1)) rolling = k + 1 < t.length() && t.charAt(k + 1) >= '0' && t.charAt(k + 1) <= '9' && inlineTagEnd(t, k) > 0;
        ArrayList<long[]> tm = new ArrayList<>();          // {início, fim}
        ArrayList<String> suffix = new ArrayList<>();
        ArrayList<ArrayList<String>> outs = new ArrayList<>(), keys = new ArrayList<>();
        int i = 0, n = lines.length;
        while (i < n) {
            if (lines[i].isEmpty()) { i++; continue; }
            int b0 = i;
            i++;
            while (i < n && !lines[i].isEmpty() && lines[i].indexOf("-->") < 0) i++;
            // bloco = lines[b0..i): pode ter o identificador antes do tempo
            int ti = -1;
            long[] tl = null;
            for (int k = b0; k < Math.min(i, b0 + 2); k++) {
                if (lines[k].indexOf("-->") >= 0) { tl = timingLine(lines[k]); ti = k; break; }
            }
            if (tl == null) continue;
            ArrayList<String> o = new ArrayList<>(), ky = new ArrayList<>();
            for (int k = ti + 1; k < i; k++) {
                String c = stripInlineTags(lines[k]).trim();
                if (c.isEmpty()) continue;
                o.add(c);
                ky.add(lineKey(c));
            }
            if (o.isEmpty()) continue;
            int last = tm.size() - 1;
            if (last >= 0) {
                long[] p = tm.get(last);
                ArrayList<String> pk = keys.get(last);
                // repete só o que a anterior já mostrava: os 10 ms do YouTube (a fala toda de novo, quase sem duração) ou, num arquivo de "rolling caption"
                // (tem marcas de tempo dentro da fala), as linhas de baixo da anterior. Duas falas iguais seguidas num arquivo normal continuam duas falas
                boolean suffixEq = tl[0] >= p[0] && tl[0] - p[1] <= 200 && ky.size() <= pk.size();
                for (int k = 0; suffixEq && k < ky.size(); k++) if (!ky.get(k).equals(pk.get(pk.size() - ky.size() + k))) suffixEq = false;
                boolean carried = suffixEq && (tl[1] - tl[0] <= 50 || (rolling && ky.size() < pk.size()));
                if (carried) { p[1] = Math.max(p[1], tl[1]); continue; }
            }
            tm.add(new long[]{tl[0], tl[1]});
            suffix.add(lines[ti].substring((int) tl[2]));
            outs.add(o);
            keys.add(ky);
        }
        if (tm.isEmpty()) return t;
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (int k = 0; k < tm.size(); k++) {
            sb.append(fmt(tm.get(k)[0])).append(" --> ").append(fmt(tm.get(k)[1])).append(suffix.get(k)).append('\n');
            for (String l : outs.get(k)) sb.append(l).append('\n');
            sb.append('\n');
        }
        return sb.toString();
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
        int pt = 0, en = 0, es = 0, total = 0;
        HashSet<String> distinct = new HashSet<>();
        String s = text.length() > 300000 ? text.substring(0, 300000) : text;
        Matcher m = WORD.matcher(s);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            total++;
            if (PT_W.contains(w)) { pt++; distinct.add(w); }
            if (EN_W.contains(w)) en++;
            if (ES_W.contains(w)) es++;
        }
        if (!(pt >= 3 && pt > 2 * Math.max(en, es))) return false;
        // italiano/galego têm "vai", "meu"... : português de verdade tem várias palavras típicas diferentes e em boa proporção (~10% das palavras)
        if (distinct.size() < 6 || pt < 0.03 * total) return false;
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
