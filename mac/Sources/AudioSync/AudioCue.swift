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

    /** (letras de fala, letras em colchetes/notas) */
    static func countChars(_ s: String) -> (Int, Int) {
        var speech = 0, other = 0, depth = 0
        var tag = false
        for u in s.utf16 {
            if u == 0x3C { tag = true; continue }            // <
            if u == 0x3E { tag = false; continue }           // >
            if tag { continue }
            if u == 0x7B { tag = true; continue }            // {
            if u == 0x7D { tag = false; continue }           // }
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
