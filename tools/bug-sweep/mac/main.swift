// Testes (sem janela, sem VLC) do que o app do Mac faz com o TEXTO das legendas: OverlayCues (o que aparece na tela, mac/Sources/SubOverlay.swift)
// e SubtitleText (bytes -> texto, mac/Sources/SubText.swift). Espelha tools/bug-sweep/io/.../SweepTest.java (os mesmos casos, o mesmo resultado).
// Compilar: swiftc -o t mac/Sources/SubText.swift mac/Sources/SubOverlay.swift tools/bug-sweep/mac/main.swift
import Foundation

var fails = 0, runs = 0
func eq<T: Equatable>(_ name: String, _ want: T, _ got: T) {
    runs += 1
    if want == got { print("ok      \(name)") }
    else { fails += 1; print("FALHOU  \(name)\n   esperado: \(want)\n   obtido:   \(got)") }
}
func cues(_ s: String) -> OverlayCues { return OverlayCues.parse(s) }
func utf8(_ s: String) -> [UInt8] { return Array(s.utf8) }

let srt = "1\n00:00:01,000 --> 00:00:03,000\nOlá, mundo\n\n2\n00:00:04,000 --> 00:00:06,000\n<i>Tudo bem?</i>\n"
let o = cues(srt)
eq("srt: 2 falas", 2, o.count)
eq("srt: texto aos 2 s", "Olá, mundo", o.at(2000))
eq("srt: tag <i> tirada", "Tudo bem?", o.at(5000))
eq("srt: entre falas nada", nil, o.at(3500))
eq("srt: BOM + CRLF", "Olá, mundo", cues("\u{FEFF}" + srt.replacingOccurrences(of: "\n", with: "\r\n")).at(2000))

let noBlank = "1\n00:00:01,000 --> 00:00:03,000\nPrimeira\n2\n00:00:04,000 --> 00:00:06,000\nSegunda\n3\n00:00:07,000 --> 00:00:09,000\nTerceira\n"
let nb = cues(noBlank)
eq("sem linhas em branco: 3 falas", 3, nb.count)
eq("sem linhas em branco: fala 1 sem o indice '2'", "Primeira", nb.at(2000))
eq("sem linhas em branco: fala 2 sem o indice '3'", "Segunda", nb.at(5000))
eq("sem linhas em branco: ultima fala", "Terceira", nb.at(8000))
eq("fala so com numero fica", "1984", cues("1\n00:00:01,000 --> 00:00:03,000\n1984\n\n").at(2000))
eq("ultima linha numerica com linha em branco depois fica", "Ano:\n1984", cues("1\n00:00:01,000 --> 00:00:03,000\nAno:\n1984\n\n2\n00:00:05,000 --> 00:00:06,000\nx\n").at(2000))

eq("tag <font>", "Vermelho", cues("1\n00:00:01,000 --> 00:00:03,000\n<font color=\"#ff0000\">Vermelho</font>\n").at(2000))
eq("ASS {\\an8}", "No topo", cues("1\n00:00:01,000 --> 00:00:03,000\n{\\an8}No topo\n").at(2000))
eq("entidade &amp;", "Tom & Jerry", cues("1\n00:00:01,000 --> 00:00:03,000\nTom &amp; Jerry\n").at(2000))
eq("entidade &quot;", "Disse \"oi\"", cues("1\n00:00:01,000 --> 00:00:03,000\nDisse &quot;oi&quot;\n").at(2000))
eq("entidade &#39;", "d'água", cues("1\n00:00:01,000 --> 00:00:03,000\nd&#39;água\n").at(2000))
eq("&amp;lt; nao decodifica duas vezes", "a &lt; b", cues("1\n00:00:01,000 --> 00:00:03,000\na &amp;lt; b\n").at(2000))
eq("menor-que solto fica", "2 < 3", cues("1\n00:00:01,000 --> 00:00:03,000\n2 < 3\n").at(2000))

let vtt = "WEBVTT\nKind: captions\n\nNOTE isto e um comentario\n\nintro\n00:01.000 --> 00:03.000 align:start position:10%\nOlá VTT\n\n00:04.000 --> 00:06.000\n<v Ana>Fala da Ana</v>\n"
let v = cues(vtt)
eq("vtt: 2 falas", 2, v.count)
eq("vtt: configuracoes depois do tempo", "Olá VTT", v.at(2000))
eq("vtt: <v Ana> tirada", "Fala da Ana", v.at(5000))
eq("vtt: marca de tempo interna", "um dois", cues("WEBVTT\n\n00:01.000 --> 00:03.000\num <00:01.500>dois\n").at(2000))

let oo = cues("1\n00:00:05,000 --> 00:00:08,000\nB\n\n2\n00:00:01,000 --> 00:00:06,000\nA\n")
eq("sobrepostas: ordenadas, A depois B", "A\nB", oo.at(5500))
eq("sobrepostas: so A", "A", oo.at(2000))
eq("sobrepostas: so B", "B", oo.at(7000))
eq("tempo com ponto no SRT", "ponto", cues("1\n00:00:01.000 --> 00:00:03.000\nponto\n").at(2000))
eq("fim antes do inicio vira 2 s", "x", cues("1\n00:00:05,000 --> 00:00:01,000\nx\n").at(6000))
eq("entrada vazia", 0, cues("").count)
eq("lixo", 0, cues("<html><body>Not found</body></html>").count)

// ---------- SubtitleText ----------
let pt = "1\n00:00:01,000 --> 00:00:03,000\nNão sei o que você está fazendo aqui, é muito tarde.\n\n2\n00:00:04,000 --> 00:00:06,000\nEstou aqui porque tudo isso é meu, já até vi.\n"
eq("utf8", pt, SubtitleText.decode(utf8(pt)) ?? "(nil)")
eq("utf8 com BOM", pt, SubtitleText.decode([0xEF, 0xBB, 0xBF] + utf8(pt)) ?? "(nil)")
func enc(_ s: String, _ e: String.Encoding) -> [UInt8] { return Array(s.data(using: e) ?? Data()) }
eq("cp1252", pt, SubtitleText.decode(enc(pt, .windowsCP1252)) ?? "(nil)")
eq("iso-8859-1", pt, SubtitleText.decode(enc(pt, .isoLatin1)) ?? "(nil)")
eq("utf16le com BOM", pt, SubtitleText.decode([0xFF, 0xFE] + enc(pt, .utf16LittleEndian)) ?? "(nil)")
eq("utf16be com BOM", pt, SubtitleText.decode([0xFE, 0xFF] + enc(pt, .utf16BigEndian)) ?? "(nil)")
eq("crlf", pt, SubtitleText.decode(utf8(pt.replacingOccurrences(of: "\n", with: "\r\n"))) ?? "(nil)")
eq("html nao e legenda", nil, SubtitleText.decode(utf8("<html><body><h1>404 Not Found</h1><p>nada aqui por favor</p></body></html>")))

var ass = "[Script Info]\nTitle: x\n\n[V4+ Styles]\nFormat: Name\nStyle: Default\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
for i in 0..<6 { ass += "Dialogue: 0,0:00:\(String(format: "%02d", i * 2)).50,0:00:\(String(format: "%02d", i * 2 + 1)).50,Default,,0,0,0,,{\\an8}Linha \(i), com vírgula\\Nsegunda\n" }
let asrt = SubtitleText.decode(utf8(ass)) ?? ""
eq("ass vira srt", true, asrt.contains("00:00:00,500 --> 00:00:01,500"))
eq("ass: texto com virgula e \\N", "Linha 0, com vírgula\nsegunda", cues(asrt).at(1000))
let ass2 = ass + "Dialogue: 0,0:00:20.00,0:00:22.00,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100 0 100{\\p0}\n"
eq("ass: desenho vetorial nao vira fala", nil, cues(SubtitleText.decode(utf8(ass2)) ?? "").at(21000))
eq("portugues reconhecido", true, SubtitleText.looksPortuguese(pt))

print("\n\(runs - fails)/\(runs) casos ok")
if fails > 0 { print("\(fails) FALHARAM"); exit(1) }
