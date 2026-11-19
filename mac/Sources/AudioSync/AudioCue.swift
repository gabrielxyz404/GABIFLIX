// Uma fala da legenda (tempos em ms) e leitura de SRT (porte 1:1 de Cue.java).
import Foundation

struct AudioCue {
    let startMs: Int
    let endMs: Int
    /** letras/dígitos visíveis (sem tags nem colchetes) */
    let chars: Int
    /** false para efeitos sonoros/música: [música], (risos), ♪ ... */
    let speech: Bool

    /** fim "de fala" estimado: a legenda costuma ficar na tela além da fala (≈ 15 letras/s + folga) */
    func speechEnd(_ trim: Bool) -> Int {
        if !trim { return endMs }
        let d = endMs - startMs
        var est = 500 + chars * 62
        if est < 700 { est = 700 }
        return startMs + min(d, est)
    }

    static func parseSrt(_ textIn: String?) -> [AudioCue] {
        guard var text = textIn else { return [] }
        var out: [AudioCue] = []
        if text.unicodeScalars.first == "\u{FEFF}" { text = String(text.unicodeScalars.dropFirst()) }
        let norm = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let lines = norm.components(separatedBy: "\n")
        var i = 0
        while i < lines.count {
            let ln = lines[i]
            guard let arrow = ln.range(of: "-->") else { i += 1; continue }
            let a = parseTs(String(ln[ln.startIndex..<arrow.lowerBound]))
            let b = parseTs(String(ln[arrow.upperBound...]))
            i += 1
            var body = ""
            while i < lines.count && !lines[i].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !lines[i].contains("-->") {
                body += lines[i] + "\n"
                i += 1
            }
            if a < 0 || b < a { continue }
            let cs = countChars(body)
            if cs.0 > 0 || cs.1 > 0 { out.append(AudioCue(startMs: a, endMs: b, chars: cs.0, speech: cs.0 > 0)) }
        }
        return out
    }

    /** nomes de tag (HTML do SRT/VTT) que não são texto da fala */
    private static let tagsSimple: Set<String> = ["i", "b", "u", "s", "c", "em", "strong", "strike", "br", "big", "small", "sub", "sup", "tt", "q", "mark", "del", "ins", "ruby", "rt", "rp", "center"]
    private static let tagsAttr: Set<String> = ["v", "lang", "font", "span", "a", "p", "div"]

    private static func isStampChar(_ c: UInt16) -> Bool {
        if c >= 0x30 && c <= 0x39 { return true }
        return c == 0x3A || c == 0x2E || c == 0x2C
    }

    private static func isAsciiAlnum(_ c: UInt16) -> Bool {
        if c >= 0x61 && c <= 0x7A { return true }
        if c >= 0x41 && c <= 0x5A { return true }
        return c >= 0x30 && c <= 0x39
    }

    private static func isBlank(_ r: ArraySlice<UInt16>) -> Bool {
        for c in r where c > 0x20 { return false }
        return true
    }

    /**
     * s[i] é '<': se começa uma tag de verdade devolve o índice do '>' que a fecha, senão -1 (o '<' é um caractere de texto, como em
     * "<Hoje estou alegre, >" ou "2 < 3"). Porte 1:1 de Cue.tagEnd.
     */
    static func tagEnd(_ s: [UInt16], _ i: Int) -> Int {
        let n = s.count
        var j = i + 1
        if j >= n { return -1 }
        let c = s[j]
        if c >= 0x30 && c <= 0x39 {
            var k = j
            while k < n && k < i + 16 && isStampChar(s[k]) { k += 1 }
            return k < n && s[k] == 0x3E ? k : -1
        }
        var closing = false
        if c == 0x2F {
            closing = true
            j += 1
            while j < n && s[j] == 0x20 { j += 1 }
        }
        var k = j
        while k < n {
            let x = s[k]
            if isAsciiAlnum(x) { k += 1 } else { break }
        }
        if k == j { return -1 }
        var name = ""
        for x in s[j..<k] { name.unicodeScalars.append(Unicode.Scalar(UInt8(x >= 0x41 && x <= 0x5A ? x + 32 : x))) }
        let simple = tagsSimple.contains(name), attr = tagsAttr.contains(name)
        if !simple && !attr { return -1 }
        var e = k
        while e < n && s[e] != 0x3E && s[e] != 0x3C && s[e] != 0x0A { e += 1 }
        if e >= n || s[e] != 0x3E { return -1 }
        if e == k { return e }                                             // "<i>"
        let rest = s[k..<e]
        if closing { return isBlank(rest) ? e : -1 }
        let r0 = rest[rest.startIndex]
        if r0 == 0x2F {                                                    // "<br/>"
            var only = true
            for c in rest where c > 0x20 && c != 0x2F { only = false }
            var slashes = 0
            for c in rest where c == 0x2F { slashes += 1 }
            if only && slashes == 1 { return e }
        }
        if r0 == 0x2E && simple { return rest.contains(0x20) ? -1 : e }    // "<c.yellow>"
        if r0 != 0x20 && r0 != 0x09 { return -1 }
        if isBlank(rest) { return e }                                      // "<i >"
        if name == "v" || name == "lang" { return e }                      // "<v Ana>", "<lang pt-BR>"
        if attr, let eq = rest.firstIndex(of: 0x3D), eq > rest.startIndex { return e }   // "<font color=...>"
        return -1
    }

    /** s[i] é '{': fim ('}') de um bloco de efeitos do ASS/MicroDVD ({\an8}, {\i1}, {an8}, {y:i}) na mesma linha; -1 se for só uma chave no texto */
    static func braceEnd(_ s: [UInt16], _ i: Int) -> Int {
        let n = s.count
        let j = i + 1
        if j >= n { return -1 }
        let c = s[j]
        var ok = c == 0x5C                                                   // {\an8}
        if !ok && isAsciiAlnum(c) && !(c >= 0x30 && c <= 0x39) && j + 1 < n && s[j + 1] == 0x3A { ok = true }   // {y:i}
        if !ok && c == 0x61 && j + 2 < n && s[j + 1] == 0x6E && s[j + 2] >= 0x31 && s[j + 2] <= 0x39 { ok = true }   // {an8}
        if !ok { return -1 }
        var k = j
        while k < n {
            if s[k] == 0x7D { return k }
            if s[k] == 0x0A { return -1 }
            k += 1
        }
        return -1
    }

    /** (letras de fala, letras em colchetes/notas) */
    static func countChars(_ str: String) -> (Int, Int) {
        let s = Array(str.utf16)
        var speech = 0, other = 0, depth = 0
        var i = 0
        while i < s.count {
            let u = s[i]
            if u == 0x3C { let e = tagEnd(s, i); if e > 0 { i = e + 1; continue } }            // <
            else if u == 0x7B { let e = braceEnd(s, i); if e > 0 { i = e + 1; continue } }      // {
            i += 1
            if u == 0x5B || u == 0x28 { depth += 1; continue }   // [ (
            if u == 0x5D || u == 0x29 { if depth > 0 { depth -= 1 }; continue }   // ] )
            if u == 0x266A || u == 0x266B || u == 0x266C { other += 1; continue }  // notas
            if isLetterOrDigit(u) { if depth > 0 { other += 1 } else { speech += 1 } }
        }
        return (speech, other)
    }

    /** Character.isLetterOrDigit(char) do Java: letras (Lu, Ll, Lt, Lm, Lo) e dígitos decimais (Nd) */
    private static func isLetterOrDigit(_ u: UInt16) -> Bool {
        guard let sc = Unicode.Scalar(UInt32(u)) else { return false }   // metades de par substituto: falso, como no Java
        switch sc.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
        default: return false
        }
    }

    static func parseTs(_ sIn: String) -> Int {
        var s = sIn.trimmingCharacters(in: .whitespacesAndNewlines)
        if let sp = s.firstIndex(of: " "), sp != s.startIndex { s = String(s[s.startIndex..<sp]) }
        s = s.replacingOccurrences(of: ".", with: ",")
        // String.split("[:,]") do Java: descarta as partes vazias do fim
        var p = s.split(omittingEmptySubsequences: false, whereSeparator: { $0 == ":" || $0 == "," }).map(String.init)
        while let l = p.last, l.isEmpty { p.removeLast() }
        func num(_ x: String) -> Int? { return Int(x) }
        if p.count == 4 {
            guard let h = num(p[0]), let m = num(p[1]), let sec = num(p[2]), let f = frac(p[3]) else { return -1 }
            return ((h * 60 + m) * 60 + sec) * 1000 + f
        }
        if p.count == 3 {
            guard let m = num(p[0]), let sec = num(p[1]), let f = frac(p[2]) else { return -1 }
            return (m * 60 + sec) * 1000 + f
        }
        return -1
    }

    private static func frac(_ f: String) -> Int? {
        if f.count == 1 { return Int(f).map { $0 * 100 } }
        if f.count == 2 { return Int(f).map { $0 * 10 } }
        return Int(String(f.prefix(3)))
    }
}
