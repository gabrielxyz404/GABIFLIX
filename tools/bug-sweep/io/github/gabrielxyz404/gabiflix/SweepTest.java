package io.github.gabrielxyz404.gabiflix;

import java.nio.charset.StandardCharsets;

/**
 * Testes na JVM (sem emulador) do que o app faz com o TEXTO das legendas: SubtitleOverlay.parse/at/clean (o que aparece na tela)
 * e SubtitleText (bytes -> texto: BOM, CP1252/ISO-8859-1, UTF-16, ASS, zip/gzip).
 * Sai com código 1 se algum caso falhar. Cada caso é um bug real encontrado na varredura (ver o nome).
 */
public class SweepTest {
    static int fails = 0, runs = 0;

    static void eq(String name, Object want, Object got) {
        runs++;
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) { fails++; System.out.println("FALHOU  " + name + "\n   esperado: " + show(want) + "\n   obtido:   " + show(got)); }
        else System.out.println("ok      " + name);
    }

    static String show(Object o) { return o == null ? "null" : "\"" + String.valueOf(o).replace("\n", "\\n") + "\""; }

    static byte[] b(String s, String cs) { try { return s.getBytes(cs); } catch (Exception e) { throw new RuntimeException(e); } }

    public static void main(String[] a) throws Exception {
        // ---------- SubtitleOverlay.parse ----------
        String srt = "1\n00:00:01,000 --> 00:00:03,000\nOlá, mundo\n\n2\n00:00:04,000 --> 00:00:06,000\n<i>Tudo bem?</i>\n";
        SubtitleOverlay o = SubtitleOverlay.parse(srt);
        eq("srt: 2 falas", 2, o.size());
        eq("srt: texto aos 2 s", "Olá, mundo", o.at(2000));
        eq("srt: tag <i> tirada", "Tudo bem?", o.at(5000));
        eq("srt: entre falas nada", null, o.at(3500));

        // BOM + CRLF
        SubtitleOverlay bom = SubtitleOverlay.parse("﻿" + srt.replace("\n", "\r\n"));
        eq("srt: BOM + CRLF", "Olá, mundo", bom.at(2000));

        // BUG overlay-indice: SRT sem linha em branco entre as falas -> o número da fala seguinte vazava para o fim da fala anterior
        String noBlank = "1\n00:00:01,000 --> 00:00:03,000\nPrimeira\n2\n00:00:04,000 --> 00:00:06,000\nSegunda\n3\n00:00:07,000 --> 00:00:09,000\nTerceira\n";
        SubtitleOverlay nb = SubtitleOverlay.parse(noBlank);
        eq("sem linhas em branco: 3 falas", 3, nb.size());
        eq("sem linhas em branco: fala 1 sem o indice '2'", "Primeira", nb.at(2000));
        eq("sem linhas em branco: fala 2 sem o indice '3'", "Segunda", nb.at(5000));
        eq("sem linhas em branco: ultima fala", "Terceira", nb.at(8000));
        // um numero de verdade no fim da fala (ano, placar) NAO pode sumir quando a fala tem so uma linha
        eq("fala so com numero fica", "1984", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\n1984\n\n").at(2000));
        // fala de duas linhas cuja ultima linha e um numero, seguida de linha em branco (normal): fica
        eq("ultima linha numerica com linha em branco depois fica", "Ano:\n1984", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\nAno:\n1984\n\n2\n00:00:05,000 --> 00:00:06,000\nx\n").at(2000));

        // tags HTML e ASS
        eq("tag <font>", "Vermelho", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\n<font color=\"#ff0000\">Vermelho</font>\n").at(2000));
        eq("ASS {\\an8}", "No topo", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\n{\\an8}No topo\n").at(2000));
        eq("entidade &amp;", "Tom & Jerry", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\nTom &amp; Jerry\n").at(2000));
        // BUG overlay-entidades: &quot; e &#39; apareciam literais
        eq("entidade &quot;", "Disse \"oi\"", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\nDisse &quot;oi&quot;\n").at(2000));
        eq("entidade &#39;", "d'água", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\nd&#39;água\n").at(2000));
        eq("&amp;lt; nao decodifica duas vezes", "a &lt; b", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\na &amp;lt; b\n").at(2000));
        eq("menor-que solto fica", "2 < 3", SubtitleOverlay.parse("1\n00:00:01,000 --> 00:00:03,000\n2 < 3\n").at(2000));

        // VTT
        String vtt = "WEBVTT\nKind: captions\n\nNOTE isto e um comentario\n\nintro\n00:01.000 --> 00:03.000 align:start position:10%\nOlá VTT\n\n00:04.000 --> 00:06.000\n<v Ana>Fala da Ana</v>\n";
        SubtitleOverlay v = SubtitleOverlay.parse(vtt);
        eq("vtt: 2 falas", 2, v.size());
        eq("vtt: configuracoes depois do tempo", "Olá VTT", v.at(2000));
        eq("vtt: <v Ana> tirada", "Fala da Ana", v.at(5000));
        // BUG overlay-vtt-tempo-interno: marcas de tempo <00:00:01.500> dentro da fala apareciam como texto
        eq("vtt: marca de tempo interna", "um dois", SubtitleOverlay.parse("WEBVTT\n\n00:01.000 --> 00:03.000\num <00:01.500>dois\n").at(2000));

        // sobrepostas e fora de ordem
        String ov = "1\n00:00:05,000 --> 00:00:08,000\nB\n\n2\n00:00:01,000 --> 00:00:06,000\nA\n";
        SubtitleOverlay oo = SubtitleOverlay.parse(ov);
        eq("sobrepostas: ordenadas, A depois B", "A\nB", oo.at(5500));
        eq("sobrepostas: so A", "A", oo.at(2000));
        eq("sobrepostas: so B", "B", oo.at(7000));
        // tempos estranhos
        eq("tempo com ponto no SRT", "ponto", SubtitleOverlay.parse("1\n00:00:01.000 --> 00:00:03.000\nponto\n").at(2000));
        eq("fim antes do inicio vira 2 s", "x", SubtitleOverlay.parse("1\n00:00:05,000 --> 00:00:01,000\nx\n").at(6000));
        eq("entrada vazia", 0, SubtitleOverlay.parse("").size());
        eq("entrada null", 0, SubtitleOverlay.parse(null).size());
        eq("lixo", 0, SubtitleOverlay.parse("<html><body>Not found</body></html>").size());

        // ---------- SubtitleText ----------
        String pt = "1\n00:00:01,000 --> 00:00:03,000\nNão sei o que você está fazendo aqui, é muito tarde.\n\n2\n00:00:04,000 --> 00:00:06,000\nEstou aqui porque tudo isso é meu, já até vi.\n";
        eq("utf8", pt.replace("\n", "\n"), SubtitleText.decode(b(pt, "UTF-8")));
        eq("utf8 com BOM", pt, SubtitleText.decode(b("﻿" + pt, "UTF-8")));
        eq("cp1252", pt, SubtitleText.decode(b(pt, "windows-1252")));
        eq("iso-8859-1", pt, SubtitleText.decode(b(pt, "ISO-8859-1")));
        eq("utf16le com BOM", pt, SubtitleText.decode(b("﻿" + pt, "UTF-16LE")));
        eq("utf16be com BOM", pt, SubtitleText.decode(b("﻿" + pt, "UTF-16BE")));
        eq("utf16le sem BOM", pt, SubtitleText.decode(b(pt, "UTF-16LE")));
        eq("crlf", pt, SubtitleText.decode(b(pt.replace("\n", "\r\n"), "UTF-8")));
        eq("cr antigo (Mac)", pt, SubtitleText.decode(b(pt.replace("\n", "\r"), "UTF-8")));
        // mojibake (UTF-8 lido como cp1252 e salvo de novo em UTF-8)
        String moji = new String(pt.getBytes(StandardCharsets.UTF_8), "windows-1252");
        eq("mojibake desfeito", pt, SubtitleText.decode(b(moji, "UTF-8")));
        // pagina de erro HTML
        eq("html nao e legenda", null, SubtitleText.decode(b("<html><body><h1>404 Not Found</h1><p>nada aqui por favor</p></body></html>", "UTF-8")));
        // tempos em centesimos e sem fracao
        String odd = "1\n00:00:01,5 --> 00:00:03,25\nUm\n\n2\n0:00:05 --> 0:00:06\nDois\n";
        String n = SubtitleText.normalize(odd);
        eq("tempo com 1 digito de fracao", true, n.contains("00:00:01,500 --> 00:00:03,250"));
        eq("tempo sem fracao e sem zero a esquerda", true, n.contains("00:00:05,000 --> 00:00:06,000"));
        // ultima linha sem \n
        eq("normalize preserva texto final", true, SubtitleText.normalize("1\n00:00:01,5 --> 00:00:03,0\nFim").endsWith("Fim"));
        // ASS
        StringBuilder ass = new StringBuilder("[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name\nStyle: Default\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (int i = 0; i < 6; i++) ass.append("Dialogue: 0,0:00:").append(String.format("%02d", i * 2)).append(".50,0:00:").append(String.format("%02d", i * 2 + 1)).append(".50,Default,,0,0,0,,{\\an8}Linha ").append(i).append(", com vírgula\\Nsegunda\n");
        String asrt = SubtitleText.decode(b(ass.toString(), "UTF-8"));
        eq("ass vira srt", true, asrt != null && asrt.contains("00:00:00,500 --> 00:00:01,500"));
        SubtitleOverlay ao = SubtitleOverlay.parse(asrt);
        eq("ass: texto com virgula e \\N", "Linha 0, com vírgula\nsegunda", ao.at(1000));
        // BUG ass-desenho: comandos de desenho vetorial {\p1}...{\p0} apareciam como texto
        StringBuilder ass2 = new StringBuilder(ass);
        ass2.append("Dialogue: 0,0:00:20.00,0:00:22.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100 0 100{\\p0}\n");
        String asrt2 = SubtitleText.decode(b(ass2.toString(), "UTF-8"));
        eq("ass: desenho vetorial nao vira fala", null, SubtitleOverlay.parse(asrt2).at(21000));
        // zip
        java.io.ByteArrayOutputStream zo = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(zo)) {
            z.putNextEntry(new java.util.zip.ZipEntry("leia-me.txt")); z.write("oi".getBytes()); z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("filme.srt")); z.write(b(pt, "windows-1252")); z.closeEntry();
        }
        eq("zip com leia-me antes da legenda", pt, SubtitleText.decode(zo.toByteArray()));
        java.io.ByteArrayOutputStream go = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(go)) { g.write(b(pt, "UTF-8")); }
        eq("gzip", pt, SubtitleText.decode(go.toByteArray()));
        // idioma
        eq("portugues reconhecido", true, SubtitleText.looksPortuguese(pt));
        eq("ingles recusado", false, SubtitleText.looksPortuguese("1\n00:00:01,000 --> 00:00:03,000\nYou are not what I thought, and this is what you have been told.\n"));
        eq("lastCueEnd", 6000L, SubtitleText.lastCueEnd(pt));

        System.out.println("\n" + (runs - fails) + "/" + runs + " casos ok");
        if (fails > 0) { System.out.println(fails + " FALHARAM"); System.exit(1); }
    }
}
