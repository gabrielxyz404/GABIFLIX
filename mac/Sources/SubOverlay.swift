// Legenda desenhada pelo próprio app (porte do SubtitleOverlay.java do Android/TV): uma janela filha transparente por cima do vídeo.
// O VLC recebe uma legenda EM BRANCO (mesma estrutura de faixas: menu, atrasos guardados e sincronização automática continuam a funcionar)
// e não desenha nada; o texto vem daqui, a cada 100 ms, com o tempo do leitor menos o atraso. Assim o Preencher nunca corta a legenda,
// e não há falha depois de mudar o atraso ou de saltar no filme. Faixas embutidas continuam a ser desenhadas pelo VLC.
import Cocoa

/** as falas de uma legenda (SRT, VTT ou ASS já convertido em SRT): "o que mostrar neste instante" */
final class OverlayCues {
    private var start: [Int64] = [], end: [Int64] = [], text: [String] = []
    var count: Int { start.count }

    /** falas com as marcas ({\an8}, <i>, ASS...) tiradas; fala que só tem marcas é ignorada */
    static func parse(_ src: String) -> OverlayCues {
        let o = OverlayCues()
        var s = src
        if s.hasPrefix("\u{FEFF}") { s.removeFirst() }
        let lines = s.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
        var items: [(Int64, Int64, String)] = []
        var i = 0
        while i < lines.count {
            let ln = lines[i]
            guard let r = ln.range(of: "-->") else { i += 1; continue }
            let a = ts(String(ln[ln.startIndex..<r.lowerBound]))
            var rest = String(ln[r.upperBound...]).trimmingCharacters(in: .whitespaces)
            if let sp = rest.firstIndex(of: " ") { rest = String(rest[rest.startIndex..<sp]) }   // VTT: "00:01.000 --> 00:03.000 align:start"
            var b = ts(rest)
            i += 1
            var body: [String] = []
            while i < lines.count && !lines[i].trimmingCharacters(in: .whitespaces).isEmpty && !lines[i].contains("-->") { body.append(lines[i]); i += 1 }
            // SRT sem linha em branco entre as falas: o número da fala SEGUINTE ficaria colado no fim desta ("Olá\n2"). Só se tira quando a fala
            // tem outra linha antes (uma fala que é só um número, como "1984", fica) e a linha seguinte é um tempo ("-->")
            if i < lines.count, lines[i].contains("-->"), body.count > 1, isIndexLine(body[body.count - 1]) { body.removeLast() }
            if a < 0 || b < 0 { continue }
            if b < a { b = a + 2000 }
            let t = clean(body.joined(separator: "\n"))
            if t.isEmpty { continue }
            items.append((a, b, t))
        }
        items.sort { $0.0 < $1.0 }
        for it in items { o.start.append(it.0); o.end.append(it.1); o.text.append(it.2) }
        return o
    }

    /** o que está na tela em ms (já sem o atraso); nil se nada. Falas sobrepostas ficam uma embaixo da outra.
        tolEarlyMs: a fala já aparece até isto antes do começo (o relógio do leitor anda aos saltos; evita piscar logo depois de saltar) */
    func at(_ ms: Int64, tolEarlyMs: Int64 = 0) -> String? {
        var lo = 0, hi = start.count - 1, last = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if start[mid] <= ms + tolEarlyMs { last = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        if last < 0 { return nil }
        var out: String? = nil
        for k in max(0, last - 7)...last where end[k] > ms { out = out == nil ? text[k] : out! + "\n" + text[k] }
        return out
    }

    private static func ts(_ str: String) -> Int64 {
        let s = str.trimmingCharacters(in: .whitespaces)
        if s.isEmpty { return -1 }
        var ms: Int64 = 0
        var main = s
        let dot = max(s.lastIndex(of: ",").map { s.distance(from: s.startIndex, to: $0) } ?? -1, s.lastIndex(of: ".").map { s.distance(from: s.startIndex, to: $0) } ?? -1)
        if dot >= 0 {
            var f = String(s.dropFirst(dot + 1)).trimmingCharacters(in: .whitespaces)
            while f.count < 3 { f += "0" }
            guard let v = Int64(f.prefix(3)) else { return -1 }
            ms = v
            main = String(s.prefix(dot))
        }
        let p = main.trimmingCharacters(in: .whitespaces).components(separatedBy: ":").map { Int64($0.trimmingCharacters(in: .whitespaces)) }
        if p.contains(where: { $0 == nil }) { return -1 }
        let n = p.map { $0! }
        if n.count == 3 { return ((n[0] * 60 + n[1]) * 60 + n[2]) * 1000 + ms }
        if n.count == 2 { return (n[0] * 60 + n[1]) * 1000 + ms }
        return -1
    }

    /** linha só com dígitos (o número de ordem de uma fala do SRT) */
    private static func isIndexLine(_ str: String) -> Bool {
        let s = str.trimmingCharacters(in: .whitespaces)
        return !s.isEmpty && s.count <= 6 && s.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /** "<00:01.500>" (marca de tempo dentro da fala, do VTT): só dígitos, ':', '.' e ',' até o '>' */
    private static func isTimeTag(_ c: [Character], _ i: Int) -> Bool {
        guard i + 1 < c.count, c[i + 1].isASCII, c[i + 1].isNumber else { return false }
        var k = i + 1
        while k < c.count && k < i + 16 {
            let ch = c[k]
            if ch == ">" { return true }
            if !((ch.isASCII && ch.isNumber) || ch == ":" || ch == "." || ch == ",") { return false }
            k += 1
        }
        return false
    }

    private static let entityRe = try? NSRegularExpression(pattern: "&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,6});")

    /** entidades HTML das legendas (&amp; &quot; &#39; &nbsp;...) numa passada só: "&amp;lt;" vira "&lt;" (não "<"); o que não se conhece fica como está */
    static func decodeEntities(_ s: String) -> String {
        guard s.contains("&"), let re = entityRe else { return s }
        let ns = s as NSString
        var out = ""
        var last = 0
        for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            let g = ns.substring(with: m.range(at: 1))
            var rep: String? = nil
            if g.hasPrefix("#") {
                let hex = g.hasPrefix("#x") || g.hasPrefix("#X")
                if let cp = UInt32(String(g.dropFirst(hex ? 2 : 1)), radix: hex ? 16 : 10), cp > 0, cp <= 0x10FFFF,
                   !(cp >= 0xD800 && cp <= 0xDFFF), let u = Unicode.Scalar(cp) { rep = String(Character(u)) }
            } else {
                switch g {
                case "amp": rep = "&"
                case "lt": rep = "<"
                case "gt": rep = ">"
                case "quot": rep = "\""
                case "apos": rep = "'"
                case "nbsp": rep = " "
                case "lrm", "rlm": rep = ""
                default: break
                }
            }
            guard let r = rep else { continue }
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last)) + r
            last = m.range.location + m.range.length
        }
        out += ns.substring(from: last)
        return out
    }

    /** tira {...} (ASS), <...> (HTML do SRT/VTT), \N e \n literais do ASS; junta espaços; mantém as quebras de linha */
    static func clean(_ s: String) -> String {
        let c = Array(s)
        var o = ""
        var brace = 0
        var tag = false
        var i = 0
        while i < c.count {
            let ch = c[i]
            if brace > 0 { if ch == "}" { brace -= 1 }; i += 1; continue }
            if tag { if ch == ">" { tag = false }; i += 1; continue }
            if ch == "{" { brace += 1; i += 1; continue }
            if ch == "<", i + 1 < c.count, c[i + 1].isLetter || c[i + 1] == "/" || isTimeTag(c, i) { tag = true; i += 1; continue }
            if ch == "\\", i + 1 < c.count, c[i + 1] == "N" || c[i + 1] == "n" { o.append("\n"); i += 2; continue }
            if ch == "\\", i + 1 < c.count, c[i + 1] == "h" { o.append(" "); i += 2; continue }
            o.append(ch); i += 1
        }
        let r = decodeEntities(o).replacingOccurrences(of: "\u{00A0}", with: " ")
        var out: [String] = []
        for ln in r.components(separatedBy: "\n") {
            let t = ln.split(whereSeparator: { $0 == " " || $0 == "\t" }).joined(separator: " ")
            if !t.isEmpty { out.append(t) }
        }
        return out.joined(separator: "\n")
    }
}

/** vista da legenda: branca com contorno preto, centrada, com quebra de linha, encostada em baixo */
final class SubtitleOverlayView: NSView {
    var text: String? { didSet { if text != oldValue { needsDisplay = true } } }
    var bottomMargin: CGFloat = 40 { didSet { if bottomMargin != oldValue { needsDisplay = true } } }
    override var isFlipped: Bool { false }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func draw(_ dirtyRect: NSRect) {
        guard let t = text, !t.isEmpty else { return }
        let size = max(15, (bounds.height * 0.046).rounded())
        let para = NSMutableParagraphStyle()
        para.alignment = .center
        para.lineBreakMode = .byWordWrapping
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.boldSystemFont(ofSize: size), .foregroundColor: NSColor.white,
            .strokeColor: NSColor.black, .strokeWidth: -6.0, .paragraphStyle: para]
        let margin = bounds.width * 0.03
        let w = max(50, bounds.width - 2 * margin)
        let s = NSAttributedString(string: t, attributes: attrs)
        let r = s.boundingRect(with: NSSize(width: w, height: bounds.height), options: [.usesLineFragmentOrigin, .usesFontLeading])
        // o texto nunca passa do topo da vista: se for muito comprido, sobe só até ao limite
        let h = min(ceil(r.height) + 4, bounds.height - bottomMargin)
        s.draw(with: NSRect(x: margin, y: bottomMargin, width: w, height: h), options: [.usesLineFragmentOrigin, .usesFontLeading])
    }
}
