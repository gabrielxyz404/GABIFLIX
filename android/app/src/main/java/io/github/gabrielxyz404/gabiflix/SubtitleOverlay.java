package io.github.gabrielxyz404.gabiflix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.view.Gravity;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Legenda desenhada pelo próprio app (na TV), numa vista à parte, acima do vídeo: não entra na imagem do libVLC, então o zoom do
 * "Preencher" nunca a corta nem a amplia. Esta classe lê as falas (SRT, VTT, ASS já convertido em SRT) e responde "o que mostrar neste instante".
 * A leitura das falas (parse/at) não usa Android.
 */
final class SubtitleOverlay {
    private final long[] start, end;
    private final String[] text;

    private SubtitleOverlay(long[] s, long[] e, String[] t) { start = s; end = e; text = t; }

    int size() { return start.length; }

    /** falas com tags ({\an8}, <i>, ASS...) tiradas; fala que só tem tag é ignorada, mas uma fala com texto nunca é descartada */
    static SubtitleOverlay parse(String src) {
        ArrayList<long[]> times = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        if (src == null) return new SubtitleOverlay(new long[0], new long[0], new String[0]);
        if (src.length() > 0 && src.charAt(0) == '﻿') src = src.substring(1);
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String ln = lines[i];
            int arrow = ln.indexOf("-->");
            if (arrow < 0) { i++; continue; }
            long a = ts(ln.substring(0, arrow));
            String rest = ln.substring(arrow + 3).trim();
            int sp = rest.indexOf(' ');
            if (sp > 0) rest = rest.substring(0, sp);   // VTT: "00:01.000 --> 00:03.000 align:start"
            long b = ts(rest);
            i++;
            StringBuilder body = new StringBuilder();
            while (i < lines.length && lines[i].trim().length() > 0 && lines[i].indexOf("-->") < 0) {
                if (body.length() > 0) body.append('\n');
                body.append(lines[i]);
                i++;
            }
            if (a < 0 || b < 0) continue;
            if (b < a) b = a + 2000;
            String t = clean(body.toString());
            if (t.isEmpty()) continue;
            times.add(new long[]{a, b});
            texts.add(t);
        }
        int n = times.size();
        Integer[] idx = new Integer[n];
        for (int k = 0; k < n; k++) idx[k] = k;
        Arrays.sort(idx, (x, y) -> Long.compare(times.get(x)[0], times.get(y)[0]));
        long[] s = new long[n], e = new long[n];
        String[] t = new String[n];
        for (int k = 0; k < n; k++) { s[k] = times.get(idx[k])[0]; e[k] = times.get(idx[k])[1]; t[k] = texts.get(idx[k]); }
        return new SubtitleOverlay(s, e, t);
    }

    /** o que está na tela em ms (já sem o atraso); null se nada. Falas sobrepostas ficam uma embaixo da outra. */
    String at(long ms) {
        int lo = 0, hi = start.length - 1, last = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (start[mid] <= ms) { last = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (last < 0) return null;
        String out = null;
        for (int k = Math.max(0, last - 7); k <= last; k++) {
            if (end[k] > ms) out = out == null ? text[k] : out + "\n" + text[k];
        }
        return out;
    }

    private static long ts(String s) {
        s = s.trim();
        if (s.isEmpty()) return -1;
        try {
            int dot = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'));
            long ms = 0;
            String main = s;
            if (dot >= 0) {
                String f = s.substring(dot + 1).trim();
                while (f.length() < 3) f += "0";
                ms = Long.parseLong(f.substring(0, 3));
                main = s.substring(0, dot);
            }
            String[] p = main.trim().split(":");
            long h = 0, m, sec;
            if (p.length == 3) { h = Long.parseLong(p[0].trim()); m = Long.parseLong(p[1].trim()); sec = Long.parseLong(p[2].trim()); }
            else if (p.length == 2) { m = Long.parseLong(p[0].trim()); sec = Long.parseLong(p[1].trim()); }
            else return -1;
            return ((h * 60 + m) * 60 + sec) * 1000 + ms;
        } catch (NumberFormatException e) { return -1; }
    }

    /** tira {...} (ASS: {\an8}, {\i1}...), <...> (HTML do SRT/VTT), \N e \n literais do ASS; junta espaços; mantém as quebras de linha */
    static String clean(String s) {
        StringBuilder o = new StringBuilder();
        int depthBrace = 0;
        boolean tag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (depthBrace > 0) { if (c == '}') depthBrace--; continue; }
            if (tag) { if (c == '>') tag = false; continue; }
            if (c == '{') { depthBrace++; continue; }
            if (c == '<' && i + 1 < s.length() && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '/')) { tag = true; continue; }
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == 'N' || s.charAt(i + 1) == 'n')) { o.append('\n'); i++; continue; }
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == 'h') { o.append(' '); i++; continue; }
            o.append(c);
        }
        String r = o.toString().replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace(' ', ' ');
        StringBuilder out = new StringBuilder();
        for (String ln : r.split("\n")) {
            String t = ln.trim().replaceAll("[ \\t]+", " ");
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    /** TextView com contorno preto (como a legenda do libVLC): branco, contorno, centralizado, quebra de linha */
    static final class Text extends TextView {
        private final float stroke;
        Text(Context c, float strokePx) {
            super(c);
            stroke = strokePx;
            setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            setTextColor(0xFFFFFFFF);
            setIncludeFontPadding(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            Layout l = getLayout();
            if (l == null) return;
            canvas.save();
            canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
            TextPaint p = getPaint();
            p.setColor(0xFF000000);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeJoin(Paint.Join.ROUND);
            l.draw(canvas);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            l.draw(canvas);
            canvas.restore();
        }
    }
}
