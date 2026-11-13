package io.github.gabrielxyz404.gabiflix;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Testes da revisão: legendas forçadas, referências que discordam, PT do hash, correção de fps, SubtitleText. Falha = "FALHOU". */
public class TRev {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok) { total++; if (!ok) fails++; System.out.println((ok ? "ok      " : "FALHOU  ") + name); }
    static Random R = new Random(5);
    static String fmt(long ms) { return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000); }

    static long[] base(int n, long end) {
        long[] a = new long[n]; long t = 60000;
        for (int i = 0; i < n; i++) { t += 1200 + (long) (-Math.log(1 - R.nextDouble()) * (end - 120000) / n); a[i] = t; }
        return a;
    }
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
    static SubtitleSync.Cand cand(String text, int rank, boolean hash) throws IOException {
        SubtitleSync.Cand c = new SubtitleSync.Cand();
        c.file = File.createTempFile("leg", ".srt"); c.file.deleteOnExit();
        c.text = SubtitleText.decode(text.getBytes(StandardCharsets.UTF_8));
        c.rank = rank; c.hashMatch = hash; c.lastMs = SubtitleText.lastCueEnd(c.text); c.url = "u" + rank;
        return c;
    }

    public static void main(String[] x) throws Exception {
        long end = 110 * 60000L;
        long[] truth = base(1500, end);
        // ---- 1. legenda forçada (só falas estrangeiras) com referência EN ----
        {
            long[] ref = SubtitleAlign.alignStarts(srt(truth, 0, 1, 200, 0.1, new String[]{"I don't know what you want."}));
            long[] forcedT = new long[70]; for (int i = 0; i < 70; i++) forcedT[i] = truth[i * 21];
            ArrayList<SubtitleSync.Cand> all = new ArrayList<>();
            all.add(cand(srt(forcedT, 0, 1, 50, 0, PT), 0, false));           // forçada, na frente da lista
            all.add(cand(srt(truth, 3000, 1, 200, 0.08, PT), 1, false));        // completa, 3 s atrasada
            SubtitleSync.AlignReport rp = SubtitleSync.alignAll(all, Arrays.asList(ref), new StringBuilder());
            ArrayList<SubtitleSync.Cand> r = SubtitleSync.rank(all, end, new StringBuilder());
            check("forçada com 70 falas NÃO é alinhada nem fica em primeiro", !all.get(0).aligned && r.get(0) == all.get(1));
            check("completa é alinhada e corrigida (~-3 s)", all.get(1).aligned && Math.abs(all.get(1).alignOffsetMs + 3000) < 400);
            check("forçada marcada como poucas falas", all.get(0).sparse);
        }
        // ---- 2. duas referências que discordam entre si ----
        {
            long[] r1 = SubtitleAlign.alignStarts(srt(truth, 0, 1, 200, 0.1, new String[]{"I don't know."}));
            long[] r2 = SubtitleAlign.alignStarts(srt(truth, 20000, 1, 200, 0.1, new String[]{"I don't know."}));
            ArrayList<SubtitleSync.Cand> all = new ArrayList<>();
            all.add(cand(srt(truth, 3000, 1, 200, 0.08, PT), 0, false));
            SubtitleSync.alignAll(all, Arrays.asList(r1, r2), new StringBuilder());
            check("duas referências com tempos diferentes (20 s): NÃO alinha", !all.get(0).aligned);
            ArrayList<SubtitleSync.Cand> all2 = new ArrayList<>();
            all2.add(cand(srt(truth, 3000, 1, 200, 0.08, PT), 0, false));
            long[] r1b = SubtitleAlign.alignStarts(srt(truth, 0, 1, 250, 0.12, new String[]{"I don't know."}));
            SubtitleSync.alignAll(all2, Arrays.asList(r1, r1b), new StringBuilder());
            check("duas referências que concordam: alinha", all2.get(0).aligned && Math.abs(all2.get(0).alignOffsetMs + 3000) < 400);
            ArrayList<SubtitleSync.Cand> all3 = new ArrayList<>();
            all3.add(cand(srt(truth, 3000, 1, 200, 0.08, PT), 0, false));
            SubtitleSync.alignAll(all3, Arrays.asList(r1, r2, r1b), new StringBuilder());
            check("3 referências, 2 concordam: alinha pela maioria", all3.get(0).aligned && Math.abs(all3.get(0).alignOffsetMs + 3000) < 400);
        }
        // ---- 3. sem referência de outro idioma: a PT do hash não pode ser a forçada ----
        {
            long[] forcedT = new long[70]; for (int i = 0; i < 70; i++) forcedT[i] = truth[i * 21];
            ArrayList<SubtitleSync.Cand> all = new ArrayList<>();
            all.add(cand(srt(forcedT, 0, 1, 50, 0, PT), 0, true));              // do hash, mas forçada
            all.add(cand(srt(truth, 0, 1, 200, 0.05, PT), 1, true));             // do hash, completa
            all.add(cand(srt(truth, 4000, 1, 200, 0.08, PT), 2, false));         // 4 s atrasada
            SubtitleSync.AlignReport rp = SubtitleSync.alignAll(all, new ArrayList<long[]>(), new StringBuilder());
            ArrayList<SubtitleSync.Cand> r = SubtitleSync.rank(all, end, new StringBuilder());
            check("PT do hash forçada não vira referência (a completa vira)", all.get(1).refSelf && !all.get(0).refSelf);
            check("a PT 4 s atrasada é alinhada à PT do hash completa", all.get(2).aligned && Math.abs(all.get(2).alignOffsetMs + 4000) < 400);
            check("a primeira do ranking é a completa do hash", r.get(0) == all.get(1));
        }
        // ---- 4. correção por fps só se as falas confirmarem ----
        {
            long[] t2 = base(1500, end);
            ArrayList<SubtitleSync.Cand> all = new ArrayList<>();
            all.add(cand(srt(truth, 0, 1, 100, 0.05, PT), 0, true));                    // do hash
            all.add(cand(srt(truth, 0, 25 / 23.976, 100, 0.05, PT), 1, false));        // MESMA legenda a 25 fps: deve ser corrigida
            all.add(cand(srt(t2, 0, 25 / 23.976, 100, 0.05, PT), 2, false));            // outra legenda (outro conteúdo) com fim ~4% maior: NÃO esticar
            SubtitleSync.AlignReport rp = SubtitleSync.alignAll(all, new ArrayList<long[]>(), new StringBuilder());
            StringBuilder lg = new StringBuilder();
            ArrayList<SubtitleSync.Cand> r = SubtitleSync.rank(all, end, lg);
            check("mesma legenda em 25 fps é corrigida", all.get(1).fixed || all.get(1).aligned);
            check("legenda com outro conteúdo e fim parecido a 25 fps NÃO é reescrita pela heurística do fim", !all.get(2).fixed);
        }
        // ---- 4b. deriva pequena de escala (0,9998) ----
        {
            int bad = 0, tot = 0; double worst = 0;
            for (int seed = 0; seed < 12; seed++) {
                Random rr = new Random(100 + seed);
                R = rr;
                long[] tr = base(1500, end);
                long[] ref = SubtitleAlign.alignStarts(srt(tr, 0, 1, 250, 0.1, new String[]{"I don't know."}));
                double k = 1 - 0.00012 * (1 + seed % 4);       // 0,9999 .. 0,9995
                long[] pt = SubtitleAlign.alignStarts(srt(tr, 80 + seed * 40, k, 250, 0.08, PT));
                SubtitleAlign.Fit f = SubtitleAlign.align(ref, pt);
                tot++;
                // erro na última fala: a transformação tem de levar a PT para perto do tempo certo (tr[last]), não 1 s ao lado
                long lastPt = pt[pt.length - 1];
                double err = Math.abs(f.map(lastPt) - lastPt / k * 1.0 + (80 + seed * 40) / k * 0) ;
                long expect = Math.round((lastPt - (80 + seed * 40)) / k);
                err = Math.abs(f.map(lastPt) - expect);
                worst = Math.max(worst, err);
                if (!f.ok() || err > 400) bad++;
            }
            System.out.println("deriva de escala: pior erro na última fala " + Math.round(worst) + " ms");
            check("deriva pequena de escala (0,9995-0,9999) corrigida até o fim (< 0,4 s) em 12 sorteios", bad == 0);
        }
        // ---- 5. SubtitleText ----
        {
            String body = "1\r\n00:00:01,000 --> 00:00:02,000\r\nNão sei você.\r\n\r\n2\r\n00:00:03,5 --> 00:00:04,50\r\nIsso é tudo, então vai.\r\n\r\n3\r\n00:01:03,000 --> 00:01:04,000\r\nOk\r\n\r\n";
            String t = SubtitleText.decode(body.getBytes(StandardCharsets.UTF_8));
            check("CRLF vira LF e tempos sem 3 dígitos viram o padrão", t != null && !t.contains("\r") && t.contains("00:00:03,500 --> 00:00:04,500"));
            byte[] bom = ("﻿" + body).getBytes(StandardCharsets.UTF_8);
            check("BOM UTF-8 removido", SubtitleText.decode(bom).startsWith("1\n"));
            byte[] u16 = body.getBytes(StandardCharsets.UTF_16LE);
            check("UTF-16 sem BOM lido", SubtitleText.decode(u16) != null && SubtitleText.decode(u16).contains("Não sei"));
            byte[] cp = body.getBytes("windows-1252");
            check("cp1252 lido com acentos", SubtitleText.decode(cp).contains("Não sei você"));
            String moji = new String(body.getBytes(StandardCharsets.UTF_8), "windows-1252");
            StringBuilder big = new StringBuilder(); for (int i = 0; i < 6; i++) big.append(moji);
            String fixed = SubtitleText.decode(big.toString().getBytes(StandardCharsets.UTF_8));
            check("mojibake (UTF-8 lido como cp1252) é desfeito", fixed != null && fixed.contains("Não sei você") && !fixed.contains("Ã"));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bo)) { z.putNextEntry(new java.util.zip.ZipEntry("legenda.srt")); z.write(body.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            String fromZip = SubtitleText.decode(bo.toByteArray());
            check("zip com .srt é aberto", fromZip != null && fromZip.contains("Isso é tudo"));
            StringBuilder ass = new StringBuilder("[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
            for (int i = 0; i < 8; i++) ass.append("Dialogue: 0,0:00:").append(String.format("%02d", i * 3)).append(".50,0:00:").append(String.format("%02d", i * 3 + 2)).append(".00,Default,,0,0,0,,{\\an8}Não, você\\Nvai {\\i1}já{\\i0}.\n");
            String a2 = SubtitleText.decode(ass.toString().getBytes(StandardCharsets.UTF_8));
            check("ASS vira SRT (8 falas, tags removidas)", a2 != null && SubtitleAlign.cueStarts(a2).length == 8 && a2.contains("Não, você\nvai já."));
            check("página de erro/HTML não é legenda", SubtitleText.decode("<html><body>Not found, sorry for the error page</body></html>".getBytes()) == null);
            // espanhol
            StringBuilder es = new StringBuilder(); for (int i = 0; i < 40; i++) es.append("Pero señor, ¿qué hay? Yo estoy muy bien, gracias. Los nosotros tengo.\n");
            StringBuilder pt = new StringBuilder(); for (int i = 0; i < 40; i++) pt.append("Não sei, você está aqui, então vai. Muito bem, obrigado.\n");
            StringBuilder gl = new StringBuilder(); for (int i = 0; i < 40; i++) gl.append("Eu non sei, meu amigo; tudo bem aqui, ñ ñ ¡ei! vai.\n");
            check("espanhol rejeitado, português aceito", !SubtitleText.looksPortuguese(es.toString()) && SubtitleText.looksPortuguese(pt.toString()));
            check("galego com ñ/¡ e sem ã/õ/ç rejeitado", !SubtitleText.looksPortuguese(gl.toString()));
        }
        // ---- 6. falas de diálogo ----
        {
            String s = "1\n00:00:01,000 --> 00:00:02,000\n[porta bate]\n\n2\n00:00:03,000 --> 00:00:04,000\n♪ música ♪\n\n3\n00:00:05,000 --> 00:00:06,000\n(suspira)\nOlá, tudo bem?\n\n4\n00:00:07,000 --> 00:00:08,000\n\n\n";
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 40; i++) big.append(i * 3 + 1).append("\n").append(fmt(i * 10000L)).append(" --> ").append(fmt(i * 10000L + 1000)).append("\n").append(i % 5 == 0 ? "[ruído]" : "Fala " + i).append("\n\n");
            long[] st = SubtitleAlign.alignStarts(big.toString());
            check("descrições [..] ficam fora dos inícios (32 de 40)", st.length == 32);
            long[] st2 = SubtitleAlign.alignStarts(s);
            check("poucas falas: volta a usar todas", st2.length == SubtitleAlign.cueStarts(s).length);
        }
        System.out.println(fails == 0 ? "TRev: " + total + " testes ok" : "TRev: FALHOU " + fails + " de " + total);
        if (fails > 0) System.exit(1);
    }
}
