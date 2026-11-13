// Alinhamento de legendas por referência (Swift puro, só Foundation): porte fiel do SubtitleAlign.java do Android
// (mesmos nomes, constantes e decisões). O teste de paridade (tools/legendas-paridade) compara os dois lado a lado.
//
// Ideia: uma legenda de OUTRO idioma que casa com o hash do arquivo (ex.: inglês) tem os tempos certos para este vídeo.
// Comparamos os INÍCIOS das falas da legenda candidata com os da referência e procuramos a transformação
// t' = t * escala + deslocamento que faz a maior parte das falas cair a menos de 300 ms de uma fala da referência.
import Foundation

/// StringBuilder do Java (classe, para passar por referência)
final class StrBuf {
    var s = ""
    @discardableResult func a(_ x: String) -> StrBuf { s += x; return self }
}

enum SubtitleAlign {
    /// razões entre taxas de quadros mais comuns (a primeira, 1, é "sem mudança de velocidade")
    static let SCALES: [Double] = [1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0]
    static let COARSE_RANGE_MS: Int64 = 300000
    static let COARSE_BIN_MS: Int64 = 250
    static let TOLERANCE_MS: Int64 = 300
    static let MIN_SCORE = 0.5
    static let LOW_SCORE = 0.4
    static let WEAK_SCORE = 0.35
    static let MIN_HALF_SCORE = 0.35
    static let MIN_CUE_RATIO = 0.25
    static let MIN_REF_RATIO = 0.25
    static let MIN_NULL_MARGIN = 0.0
    static let RATIO_MIN_REF = 60
    static let SCALE_MARGIN = 0.05

    final class Fit {
        var scale = 1.0
        var offsetMs: Int64 = 0
        var score = 0.0
        var scoreFirstHalf = 0.0, scoreSecondHalf = 0.0
        /// ajuste por partes: instantes de corte (no tempo da legenda original) e o deslocamento de cada trecho; nil = transformação única
        var cuts: [Int64]? = nil
        var offs: [Int64]? = nil
        var segScores: [Double]? = nil
        var segCues: [Int]? = nil
        var pieceOk = false
        var coverage = 1.0
        var freeScale = false
        var sparse = false
        var sparseRef = false
        var cueRatio = 1.0
        var nullScore = 0.0
        func halvesOk() -> Bool { return scoreFirstHalf >= SubtitleAlign.MIN_HALF_SCORE && scoreSecondHalf >= SubtitleAlign.MIN_HALF_SCORE }
        func singleOk() -> Bool {
            if !halvesOk() { return false }
            if score >= SubtitleAlign.MIN_SCORE { return true }
            return score >= SubtitleAlign.LOW_SCORE && abs(scoreFirstHalf - scoreSecondHalf) <= 0.15
        }
        func ok() -> Bool { return !sparse && !sparseRef && score - nullScore >= SubtitleAlign.MIN_NULL_MARGIN && (cuts != nil ? pieceOk : singleOk()) }
        func piecewise() -> Bool { return cuts != nil && pieceOk }
        func differentVersion() -> Bool { return !ok() && !sparse && !sparseRef && score >= SubtitleAlign.WEAK_SCORE }
        func lowMargin() -> Bool { return score - nullScore < SubtitleAlign.MIN_NULL_MARGIN }
        func offsetAt(_ c: Int64) -> Int64 {
            guard let cuts = cuts, let offs = offs else { return offsetMs }
            var k = 0
            while k < cuts.count && c >= cuts[k] { k += 1 }
            return offs[k]
        }
        func map(_ c: Int64) -> Int64 { return J.round(Double(c) * scale) + offsetAt(c) }
        func changes() -> Bool { return cuts != nil || scale != 1.0 || abs(offsetMs) >= 50 }
        func apply(_ text: String) -> String {
            if let cuts = cuts, let offs = offs { return J.str(SubtitleAlign.transformSegments(J.u16(text), scale, cuts, offs)) }
            return J.str(SubtitleAlign.transform(J.u16(text), scale, offsetMs))
        }
    }

    /// uma fala da legenda (início em ms e texto numa linha)
    struct Cue {
        let startMs: Int64
        let text: String
    }

    // ---------- leitura das falas ----------
    /// `^` do Java em modo MULTILINE: início do texto, ou depois de um terminador de linha
    private static func isLineStart(_ u: U16, _ p: Int, _ lo: Int) -> Bool {
        if p == lo { return true }
        let c = u[p - 1]
        if c == 10 || c == 0x85 || c == 0x2028 || c == 0x2029 { return true }
        if c == 13 { return u[p] != 10 }
        return false
    }

    /// `^[ \t]*(?:(\d+):)?(\d{1,2}):(\d\d)[,.](\d{3})[ \t]*-->[^\n]*` a partir de p: (início da fala em ms, fim do trecho casado)
    private static func startAt(_ u: U16, _ p0: Int, _ hi: Int) -> (Int64, Int)? {
        var p = p0
        while p < hi && (u[p] == 32 || u[p] == 9) { p += 1 }
        guard let t = SubtitleText.timeAt(u, p, hi, canon: true) else { return nil }
        var q = t.end
        while q < hi && (u[q] == 32 || u[q] == 9) { q += 1 }
        if q + 3 > hi || u[q] != 45 || u[q + 1] != 45 || u[q + 2] != 62 { return nil }
        q += 3
        while q < hi && u[q] != 10 { q += 1 }
        let ms = ((t.h &* 60 &+ t.m) &* 60 &+ t.s) &* 1000 &+ SubtitleText.fracMs(t.f)
        return (ms, q)
    }

    /// Matcher.find() em [lo, hi): a primeira fala que começa numa linha dentro da faixa
    private static func findStart(_ u: U16, _ lo: Int, _ hi: Int) -> Int64? {
        var p = lo
        while p < hi {
            if isLineStart(u, p, lo), let m = startAt(u, p, hi) { return m.0 }
            p += 1
        }
        return nil
    }

    /// falas (início + texto sem marcas, numa linha) em ordem de tempo (SRT ou VTT); no máximo 6000
    static func cues(_ text: String) -> [Cue] { return cues(J.u16(text)) }

    static func cues(_ u: U16) -> [Cue] {
        var out: [(Int64, Int, String)] = []
        let n = u.count
        var p = 0
        while p < n {
            if isLineStart(u, p, 0), let m = startAt(u, p, n) {
                if out.count >= 6000 { break }
                let start = m.0
                let a = m.1
                var b = J.indexOf(u, [10, 10], a)
                let b2 = J.indexOf(u, [13, 10, 13, 10], a)
                if b < 0 || (b2 >= 0 && b2 < b) { b = b2 }
                if b < 0 { b = min(n, a + 400) }
                var body = U16(u[a..<min(b, a + 400)])
                body = stripTags(body)
                body = collapseSpaces(body)
                body = J.trim(body)
                out.append((start, out.count, J.str(body)))
                p = m.1
                continue
            }
            p += 1
        }
        let sorted = out.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
        return sorted.map { Cue(startMs: $0.0, text: $0.2) }
    }

    /// remove `<[^>]*>` e `\{\\[^}]*\}`
    private static func stripTags(_ s: U16) -> U16 {
        var out = U16()
        var i = 0
        let n = s.count
        while i < n {
            let c = s[i]
            if c == 60 {
                var j = i + 1
                while j < n && s[j] != 62 { j += 1 }
                if j < n { i = j + 1; continue }
            } else if c == 123 && i + 1 < n && s[i + 1] == 92 {
                var j = i + 2
                while j < n && s[j] != 125 { j += 1 }
                if j < n { i = j + 1; continue }
            }
            out.append(c)
            i += 1
        }
        return out
    }

    /// replaceAll("\\s+", " ")  (\s do Java = espaço, \t, \n, \x0B, \f, \r)
    private static func collapseSpaces(_ s: U16) -> U16 {
        var out = U16()
        var i = 0
        let n = s.count
        func ws(_ c: UInt16) -> Bool { return c == 32 || c == 9 || c == 10 || c == 11 || c == 12 || c == 13 }
        while i < n {
            if ws(s[i]) {
                while i < n && ws(s[i]) { i += 1 }
                out.append(32)
            } else { out.append(s[i]); i += 1 }
        }
        return out
    }

    /// início de cada fala (ms), em ordem crescente (SRT ou VTT); no máximo 6000 falas
    static func cueStarts(_ text: String) -> [Int64] { return cueStarts(J.u16(text)) }

    static func cueStarts(_ u: U16) -> [Int64] {
        var list: [Int64] = []
        let n = u.count
        var p = 0
        while p < n {
            if isLineStart(u, p, 0), let m = startAt(u, p, n) {
                if list.count >= 6000 { break }
                list.append(m.0)
                p = m.1
                continue
            }
            p += 1
        }
        list.sort()
        return list
    }

    /// só tem descrição de som ("[porta bate]", "(música)", "♪ ♪"...)? Procura alguma letra fora desses trechos
    private static func hasSpeech(_ s: U16) -> Bool {
        var i = 0
        let n = s.count
        func closing(_ from: Int, _ ch: UInt16) -> Int {
            var j = from
            while j < n { if s[j] == ch { return j }; j += 1 }
            return -1
        }
        while i < n {
            let c = s[i]
            var close: UInt16 = 0
            if c == 91 { close = 93 } else if c == 40 { close = 41 } else if c == 60 { close = 62 } else if c == 123 { close = 125 }
            if close != 0 {
                let j = closing(i + 1, close)
                if j >= 0 { i = j + 1; continue }
            } else if c == 0x266A || c == 0x266B || c == 0x266C || c == 35 || c == 42 {
                i += 1
                continue
            }
            if J.isLetter(c) { return true }
            i += 1
        }
        return false
    }

    /// início das falas de DIÁLOGO (ordem crescente): ignora as falas que são só descrição de som; se sobrar quase nada, devolve todas
    static func alignStarts(_ text: String) -> [Int64] { return alignStarts(J.u16(text)) }

    static func alignStarts(_ u: U16) -> [Int64] {
        var list: [Int64] = []
        var cur: Int64 = -1
        var body = U16()
        var total = 0
        var i = 0
        let n = u.count
        while i <= n {
            var e = J.indexOf(u, 10, i)
            if e < 0 { e = n }
            let lo = i
            i = e + 1
            let m = findStart(u, lo, e)
            let isTime = m != nil
            var blank = true
            if !isTime { for k in lo..<e where u[k] > 0x20 { blank = false; break } }
            if isTime || blank {
                if cur >= 0 && list.count < 6000 {
                    total += 1
                    if hasSpeech(body) { list.append(cur) }
                }
                cur = -1
                body.removeAll(keepingCapacity: true)
                if let s = m { cur = s }
            } else if cur >= 0 {
                if body.count < 600 { body.append(contentsOf: u[lo..<e]); body.append(32) }
            }
            if e >= n { break }
        }
        if cur >= 0 && list.count < 6000 {
            total += 1
            if hasSpeech(body) { list.append(cur) }
        }
        if list.count < 20 || Double(list.count) < 0.5 * Double(total) { return cueStarts(u) }
        list.sort()
        return list
    }

    // ---------- reescrever os tempos ----------
    /// novo tempo = tempo * k + offsetMs em todas as linhas "a --> b" (SRT/VTT); o resto do texto não muda
    static func transform(_ text: U16, _ k: Double, _ offsetMs: Int64) -> U16 {
        var out = U16()
        out.reserveCapacity(text.count + 64)
        var start = 0
        let n = text.count
        while true {
            var e = J.indexOf(text, 10, start)
            if e < 0 { e = n }
            let ln = Array(text[start..<e])
            out.append(contentsOf: lineHasArrow(ln) ? transformLine(ln, k, offsetMs) : ln)
            if e >= n { break }
            out.append(10)
            start = e + 1
        }
        return out
    }

    private static func lineHasArrow(_ ln: U16) -> Bool {
        if ln.count < 3 { return false }
        var k = 0
        while k + 2 < ln.count { if ln[k] == 45 && ln[k + 1] == 45 && ln[k + 2] == 62 { return true }; k += 1 }
        return false
    }

    private static func transformLine(_ ln: U16, _ k: Double, _ offsetMs: Int64) -> U16 {
        var out = U16()
        let n = ln.count
        var i = 0
        while i < n {
            if let t = SubtitleText.timeAt(ln, i, n, canon: true) {
                let ms = ((t.h &* 60 &+ t.m) &* 60 &+ t.s) &* 1000 &+ SubtitleText.fracMs(t.f)
                let nn = max(0, J.round(Double(ms) * k) + offsetMs)
                out += J.u16(J.pad(nn / 3600000, 2) + ":" + J.pad((nn / 60000) % 60, 2) + ":" + J.pad((nn / 1000) % 60, 2))
                out.append(t.sep)
                out += J.u16(J.pad(nn % 1000, 3))
                i = t.end
            } else { out.append(ln[i]); i += 1 }
        }
        return out
    }

    /// como transform, mas com um deslocamento por trecho: o trecho de cada fala vem do início dela (tempo original) e os cortes estão no tempo original
    static func transformSegments(_ text: U16, _ k: Double, _ cuts: [Int64], _ offs: [Int64]) -> U16 {
        var out = U16()
        out.reserveCapacity(text.count + 64)
        var start = 0
        let n = text.count
        while true {
            var e = J.indexOf(text, 10, start)
            if e < 0 { e = n }
            let ln = Array(text[start..<e])
            if lineHasArrow(ln) {
                var off = offs[0]
                var i = 0
                while i < ln.count {
                    if let t = SubtitleText.timeAt(ln, i, ln.count, canon: true) {
                        let ms = ((t.h &* 60 &+ t.m) &* 60 &+ t.s) &* 1000 &+ SubtitleText.fracMs(t.f)
                        var q = 0
                        while q < cuts.count && ms >= cuts[q] { q += 1 }
                        off = offs[q]
                        break
                    }
                    i += 1
                }
                out.append(contentsOf: transformLine(ln, k, off))
            } else { out.append(contentsOf: ln) }
            if e >= n { break }
            out.append(10)
            start = e + 1
        }
        return out
    }

    // ---------- busca da melhor transformação ----------
    /// índice do primeiro elemento >= v
    static func lowerBound(_ a: [Int64], _ v: Int64) -> Int {
        var lo = 0, hi = a.count
        while lo < hi {
            let mid = (lo + hi) >> 1
            if a[mid] < v { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    static func near(_ ref: [Int64], _ t: Int64, _ tol: Int64) -> Bool {
        let i = lowerBound(ref, t)
        return (i < ref.count && ref[i] - t <= tol) || (i > 0 && t - ref[i - 1] <= tol)
    }

    /// quantas falas da candidata (já multiplicadas por scale e somadas a off) caem a menos de TOLERANCE_MS de uma fala da referência
    private static func countMatches(_ ref: [Int64], _ cand: [Int64], _ scale: Double, _ off: Int64) -> Int {
        var hits = 0
        for c in cand where near(ref, J.round(Double(c) * scale) + off, TOLERANCE_MS) { hits += 1 }
        return hits
    }

    private static func single(_ ref: [Int64], _ cand: [Int64], _ scale: Double, _ off: Int64) -> Fit {
        let f = Fit()
        f.scale = scale
        f.offsetMs = off
        f.score = Double(countMatches(ref, cand, scale, off)) / Double(max(1, min(cand.count, ref.count)))
        return f
    }

    /// melhor deslocamento para uma escala: histograma grosso (-300..+300 s) e depois refino de ±1 s em passos de 50 ms
    private static func alignWithScale(_ ref: [Int64], _ cand: [Int64], _ scale: Double) -> Fit {
        let bins = Int(2 * COARSE_RANGE_MS / COARSE_BIN_MS) + 3
        var hist = [Int](repeating: 0, count: bins)
        for c in cand {
            let t = J.round(Double(c) * scale)
            var i = lowerBound(ref, t - COARSE_RANGE_MS)
            while i < ref.count && ref[i] <= t + COARSE_RANGE_MS {
                let off = ref[i] - t
                let b = Int((off + COARSE_RANGE_MS) / COARSE_BIN_MS) + 1
                if b >= 0 && b < bins { hist[b] += 1 }
                i += 1
            }
        }
        var bestBin = 1, bestVal = -1
        for b in 1..<(bins - 1) {
            let v = hist[b - 1] + hist[b] + hist[b + 1] // 750 ms de largura
            if v > bestVal { bestVal = v; bestBin = b }
        }
        let center = Int64(bestBin - 1) * COARSE_BIN_MS - COARSE_RANGE_MS + COARSE_BIN_MS / 2
        var bestCount = -1
        var bestOffs: [Int64] = []
        var off = center - 1000
        while off <= center + 1000 {
            let cnt = countMatches(ref, cand, scale, off)
            if cnt > bestCount { bestCount = cnt; bestOffs = [off] }
            else if cnt == bestCount { bestOffs.append(off) }
            off += 50
        }
        let f = Fit()
        f.scale = scale
        f.offsetMs = bestOffs.isEmpty ? 0 : bestOffs[bestOffs.count / 2]
        let denom = max(1, min(cand.count, ref.count))
        f.score = Double(max(0, bestCount)) / Double(denom)
        return f
    }

    /// nota de um trecho da candidata [from,to): acertos / min(falas do trecho, falas da referência na mesma janela de tempo)
    private static func rangeScore(_ ref: [Int64], _ cand: [Int64], _ from: Int, _ to: Int, _ f: Fit) -> Double {
        if to - from < 5 { return 0 }
        let t0 = f.map(cand[from]) - TOLERANCE_MS, t1 = f.map(cand[to - 1]) + TOLERANCE_MS
        let refCount = lowerBound(ref, t1 + 1) - lowerBound(ref, t0)
        var hits = 0
        for k in from..<to where near(ref, f.map(cand[k]), TOLERANCE_MS) { hits += 1 }
        let denom = max(1, min(to - from, refCount))
        return Double(hits) / Double(denom)
    }

    static func halves(_ ref: [Int64], _ cand: [Int64], _ f: Fit) {
        let mid = cand.count / 2
        f.scoreFirstHalf = rangeScore(ref, cand, 0, mid, f)
        f.scoreSecondHalf = rangeScore(ref, cand, mid, cand.count, f)
    }

    // ---------- escala livre + regressão robusta ----------
    static let FREE_MIN = 0.95, FREE_MAX = 1.05, FREE_STEP = 0.0005
    static let SKIP_SCORE = 0.8

    /// refina escala e deslocamento por mínimos quadrados sobre os pares casados, rejeitando pares fora da tolerância (que encolhe: 1000, 600, 400, 300 ms)
    static func refine(_ ref: [Int64], _ cand: [Int64], _ f: Fit) -> Fit {
        var s = f.scale, o = Double(f.offsetMs)
        let tols: [Double] = [1000, 600, 400, 300]
        var pc = [Int64](repeating: 0, count: cand.count), pr = [Int64](repeating: 0, count: cand.count)
        var pn = 0
        for tol in tols {
            var n = 0
            for c in cand {
                let t = Double(c) * s + o
                let i = lowerBound(ref, J.round(t))
                var best: Int64 = -1
                var bd = tol + 1
                if i < ref.count && abs(Double(ref[i]) - t) < bd { bd = abs(Double(ref[i]) - t); best = ref[i] }
                if i > 0 && abs(Double(ref[i - 1]) - t) < bd { best = ref[i - 1] }
                if best >= 0 { pc[n] = c; pr[n] = best; n += 1 }
            }
            if n < 20 { break }
            var mc = 0.0, mr = 0.0
            for i in 0..<n { mc += Double(pc[i]); mr += Double(pr[i]) }
            mc /= Double(n); mr /= Double(n)
            var sxy = 0.0, sxx = 0.0
            for i in 0..<n { sxy += (Double(pc[i]) - mc) * (Double(pr[i]) - mr); sxx += (Double(pc[i]) - mc) * (Double(pc[i]) - mc) }
            if sxx <= 0 { break }
            let a = sxy / sxx
            if !(a >= FREE_MIN && a <= FREE_MAX) { break }
            s = a
            o = mr - a * mc
            pn = n
        }
        // perto de uma razão fixa de fps: usa a razão exata (só recalcula o deslocamento)
        if pn >= 20 {
            var snapTo = -1.0
            for fx in SCALES where abs(s - fx) < 2e-4 { snapTo = fx }
            if snapTo > 0 {
                var d = [Double](repeating: 0, count: pn)
                for i in 0..<pn { d[i] = Double(pr[i]) - snapTo * Double(pc[i]) }
                d.sort()
                let g = single(ref, cand, snapTo, J.round(d[pn / 2]))
                if g.score >= f.score { return g }
            }
        }
        let g = single(ref, cand, s, J.round(o))
        g.freeScale = !isFixedScale(s)
        return g.score >= f.score ? g : f
    }

    static func isFixedScale(_ s: Double) -> Bool {
        for fx in SCALES where abs(s - fx) < 3e-4 { return true }
        return false
    }

    /// varre escalas de 0,95 a 1,05 (passo 0,0005) com uma amostra das falas e histograma de deslocamentos; refina o melhor por regressão
    static func freeScale(_ ref: [Int64], _ cand: [Int64]) -> Fit {
        let n = cand.count
        let step = max(1, n / 250)
        let t0 = cand[n / 2]
        let range = COARSE_RANGE_MS
        let bin: Int64 = 500
        let bins = Int(2 * range / bin) + 3
        var hist = [Int](repeating: 0, count: bins)
        var bestS = 1.0, bestVal = -1.0
        var bestD: Int64 = 0
        var s = FREE_MIN
        while s <= FREE_MAX + 1e-9 {
            for i in 0..<bins { hist[i] = 0 }
            var k = 0
            while k < n {
                let u = J.round(Double(cand[k] - t0) * s) + t0
                var i = lowerBound(ref, u - range)
                while i < ref.count && ref[i] <= u + range {
                    let b = Int((ref[i] - u + range) / bin) + 1
                    if b >= 0 && b < bins { hist[b] += 1 }
                    i += 1
                }
                k += step
            }
            for b in 1..<(bins - 1) {
                let v = hist[b - 1] + hist[b] + hist[b + 1]
                if Double(v) > bestVal { bestVal = Double(v); bestS = s; bestD = Int64(b - 1) * bin - range + bin / 2 }
            }
            s += FREE_STEP
        }
        let f = single(ref, cand, bestS, J.round(Double(t0) * (1 - bestS) + Double(bestD)))
        var g = refine(ref, cand, f)
        // a regressão parte de ±1 s: se a busca estava longe, tenta de novo a partir do melhor resultado
        g = refine(ref, cand, g)
        g.freeScale = !isFixedScale(g.scale)
        return g
    }

    // ---------- ajuste por partes (cortes) ----------
    static let MIN_SEG_CUES = 40
    static let SEG_MIN_SCORE = 0.5
    static let PIECE_MIN_SCORE = 0.55
    static let PIECE_GAIN = 0.08
    static let CUT_PENALTY = 10
    static let MIN_CUT_DELTA_MS: Int64 = 1000
    private static let PIECE_WINDOW = 40, PIECE_STRIDE = 20, PIECE_MAX_OFFSETS = 16

    /// picos do histograma de deslocamentos das falas [a,b) com a escala s: {deslocamento, apoio}
    private static func windowPeaks(_ ref: [Int64], _ cand: [Int64], _ a: Int, _ b: Int, _ s: Double, _ out: inout [[Int64]]) {
        let bins = Int(2 * COARSE_RANGE_MS / COARSE_BIN_MS) + 3
        var hist = [Int](repeating: 0, count: bins)
        for k in a..<b {
            let t = J.round(Double(cand[k]) * s)
            var i = lowerBound(ref, t - COARSE_RANGE_MS)
            while i < ref.count && ref[i] <= t + COARSE_RANGE_MS {
                let bb = Int((ref[i] - t + COARSE_RANGE_MS) / COARSE_BIN_MS) + 1
                if bb >= 0 && bb < bins { hist[bb] += 1 }
                i += 1
            }
        }
        var sm = [Int](repeating: 0, count: bins)
        for i in 1..<(bins - 1) { sm[i] = hist[i - 1] + hist[i] + hist[i + 1] }
        var p1 = 1
        for i in 1..<(bins - 1) where sm[i] > sm[p1] { p1 = i }
        var p2 = -1
        for i in 1..<(bins - 1) where abs(i - p1) >= 8 && (p2 < 0 || sm[i] > sm[p2]) { p2 = i }
        let peaks: [Int] = (p2 >= 0 && Double(sm[p2]) >= 0.6 * Double(sm[p1])) ? [p1, p2] : [p1]
        for p in peaks {
            let center = Int64(p - 1) * COARSE_BIN_MS - COARSE_RANGE_MS + COARSE_BIN_MS / 2
            var bestCnt = -1
            var offs: [Int64] = []
            var off = center - 600
            while off <= center + 600 {
                var cnt = 0
                for k in a..<b where near(ref, J.round(Double(cand[k]) * s) + off, TOLERANCE_MS) { cnt += 1 }
                if cnt > bestCnt { bestCnt = cnt; offs = [off] } else if cnt == bestCnt { offs.append(off) }
                off += 50
            }
            if Double(bestCnt) >= max(6.0, 0.3 * Double(b - a)) { out.append([offs[offs.count / 2], Int64(bestCnt)]) }
        }
    }

    /// modelo de 2 ou 3 trechos (mesma escala, deslocamento próprio) por programação dinâmica; nil se nada convence
    static func piecewise(_ ref: [Int64], _ cand: [Int64], _ scales: [Double], _ singleFit: Fit?) -> Fit? {
        var best: Fit? = nil
        for s in scales {
            if let pw = piecewiseAt(ref, cand, s, singleFit), best == nil || pw.score > best!.score { best = pw }
        }
        return best
    }

    private static func piecewiseAt(_ ref: [Int64], _ cand: [Int64], _ s: Double, _ singleFit: Fit?) -> Fit? {
        let n = cand.count
        if n < 2 * MIN_SEG_CUES { return nil }
        var found: [[Int64]] = []
        var a = 0
        while true {
            let b = min(n, a + PIECE_WINDOW)
            if b - a < PIECE_WINDOW { a = max(0, n - PIECE_WINDOW) }
            windowPeaks(ref, cand, a, min(n, a + PIECE_WINDOW), s, &found)
            if a + PIECE_WINDOW >= n { break }
            a += PIECE_STRIDE
        }
        if let sf = singleFit, sf.scale == s { found.append([sf.offsetMs, Int64(n / 4)]) }
        found = stableSorted(found) { $0[0] < $1[0] }
        var clusters: [[Int64]] = []
        var i = 0
        while i < found.count {
            var j = i
            var sw = 0.0, so = 0.0
            let first = found[i][0]
            while j < found.count && found[j][0] - first <= 400 {
                sw += Double(found[j][1])
                so += Double(found[j][0]) * Double(found[j][1])
                j += 1
            }
            clusters.append([J.round(so / sw), Int64(sw)])
            i = j
        }
        clusters = stableSorted(clusters) { $0[1] > $1[1] }
        let M = min(PIECE_MAX_OFFSETS, clusters.count)
        if M < 2 { return nil }
        var offs = [Int64](repeating: 0, count: M)
        for m in 0..<M { offs[m] = clusters[m][0] }
        var h = [[Int]](repeating: [Int](repeating: 0, count: n), count: M)
        for m in 0..<M { for i in 0..<n { h[m][i] = near(ref, J.round(Double(cand[i]) * s) + offs[m], TOLERANCE_MS) ? 1 : 0 } }

        var res: Fit? = nil
        for K in 2...3 {
            guard let f = dpSegments(ref, cand, s, offs, h, K) else { continue }
            if res == nil || f.score > res!.score + 0.03 { res = f } // 3 trechos só se melhorar de verdade
        }
        return res
    }

    /// ordenação estável (o Collections.sort do Java é estável)
    static func stableSorted<T>(_ a: [T], _ less: (T, T) -> Bool) -> [T] {
        let idx = Array(0..<a.count).sorted { x, y in
            if less(a[x], a[y]) { return true }
            if less(a[y], a[x]) { return false }
            return x < y
        }
        return idx.map { a[$0] }
    }

    private static func dpSegments(_ ref: [Int64], _ cand: [Int64], _ s: Double, _ offs: [Int64], _ h: [[Int]], _ K: Int) -> Fit? {
        let n = cand.count, M = offs.count
        let NEG = Int(Int32.min) / 2
        let stride = (n + 1) * M
        var g = [Int](repeating: NEG, count: (K + 1) * stride)
        var from = [Int](repeating: 0, count: (K + 1) * stride)
        @inline(__always) func ix(_ k: Int, _ i: Int, _ m: Int) -> Int { return k * stride + i * M + m }
        for m in 0..<M { g[ix(1, 0, m)] = 0 }
        for i in 1...n {
            for m in 0..<M {
                let hit = h[m][i - 1]
                if g[ix(1, i - 1, m)] > NEG { g[ix(1, i, m)] = g[ix(1, i - 1, m)] + hit }
                if K >= 2 {
                    for k in 2...K {
                        var bestV = NEG, bestF = 0
                        if g[ix(k, i - 1, m)] > NEG { bestV = g[ix(k, i - 1, m)] + hit; bestF = 0 }
                        if i >= 2 {
                            for q in 0..<M {
                                if q == m || g[ix(k - 1, i - 1, q)] <= NEG { continue }
                                let v = g[ix(k - 1, i - 1, q)] + hit - CUT_PENALTY
                                if v > bestV { bestV = v; bestF = q + 1 }
                            }
                        }
                        g[ix(k, i, m)] = bestV
                        from[ix(k, i, m)] = bestF
                    }
                }
            }
        }
        var bm = -1
        for m in 0..<M where g[ix(K, n, m)] > NEG && (bm < 0 || g[ix(K, n, m)] > g[ix(K, n, bm)]) { bm = m }
        if bm < 0 { return nil }
        var segs: [[Int]] = [] // {início, fim, índice do deslocamento}
        var i = n, k = K, m = bm, end = n
        while i > 0 {
            let fr = k > 1 ? from[ix(k, i, m)] : 0
            if fr != 0 { segs.append([i - 1, end, m]); end = i - 1; m = fr - 1; k -= 1 }
            i -= 1
        }
        segs.append([0, end, m])
        segs.reverse()
        if segs.count != K { return nil }
        let S = segs.count
        var cuts = [Int64](repeating: 0, count: S - 1)
        var so = [Int64](repeating: 0, count: S)
        for q in 0..<S {
            so[q] = offs[segs[q][2]]
            if segs[q][1] - segs[q][0] < MIN_SEG_CUES { return nil }
            if q > 0 {
                cuts[q - 1] = (cand[segs[q][0] - 1] + cand[segs[q][0]]) / 2
                if abs(so[q] - so[q - 1]) < MIN_CUT_DELTA_MS { return nil }
            }
        }
        let f = Fit()
        f.scale = s
        f.cuts = cuts
        f.offs = so
        f.offsetMs = so[0]
        // escala comum refinada por regressão dentro dos trechos
        refinePieces(ref, cand, f, segs)
        var hits = 0
        for c in cand where near(ref, f.map(c), TOLERANCE_MS) { hits += 1 }
        f.score = Double(hits) / Double(max(1, min(n, ref.count)))
        f.segScores = [Double](repeating: 0, count: S)
        f.segCues = [Int](repeating: 0, count: S)
        var ok = f.score >= PIECE_MIN_SCORE
        for q in 0..<S {
            f.segCues[q] = segs[q][1] - segs[q][0]
            f.segScores![q] = rangeScore(ref, cand, segs[q][0], segs[q][1], f)
            if f.segCues![q] < MIN_SEG_CUES || f.segScores![q] < SEG_MIN_SCORE { ok = false }
        }
        halves(ref, cand, f)
        f.pieceOk = ok
        f.freeScale = !isFixedScale(f.scale)
        return ok ? f : nil
    }

    /// mesma escala para todos os trechos (inclinação comum dos mínimos quadrados dentro de cada trecho) e deslocamento próprio
    private static func refinePieces(_ ref: [Int64], _ cand: [Int64], _ f: Fit, _ segs: [[Int]]) {
        for _ in 0..<2 {
            var sxy = 0.0, sxx = 0.0
            let S = segs.count
            var mc = [Double](repeating: 0, count: S), mr = [Double](repeating: 0, count: S)
            var cnt = [Int](repeating: 0, count: S)
            for q in 0..<S {
                var pairs: [(Int64, Int64)] = []
                for i in segs[q][0]..<segs[q][1] {
                    let t = Double(cand[i]) * f.scale + Double(f.offs![q])
                    let lb = lowerBound(ref, J.round(t))
                    var best: Int64 = -1
                    var bd = 401.0
                    if lb < ref.count && abs(Double(ref[lb]) - t) < bd { bd = abs(Double(ref[lb]) - t); best = ref[lb] }
                    if lb > 0 && abs(Double(ref[lb - 1]) - t) < bd { best = ref[lb - 1] }
                    if best >= 0 { pairs.append((cand[i], best)); mc[q] += Double(cand[i]); mr[q] += Double(best); cnt[q] += 1 }
                }
                if cnt[q] > 0 { mc[q] /= Double(cnt[q]); mr[q] /= Double(cnt[q]) }
                for p in pairs { sxy += (Double(p.0) - mc[q]) * (Double(p.1) - mr[q]); sxx += (Double(p.0) - mc[q]) * (Double(p.0) - mc[q]) }
            }
            if sxx <= 0 { return }
            var a = sxy / sxx
            if !(a >= FREE_MIN && a <= FREE_MAX) { return }
            for fx in SCALES where abs(a - fx) < 2e-4 { a = fx }
            let old = f.scale
            f.scale = a
            for q in 0..<S where cnt[q] >= 5 { f.offs![q] = J.round(mr[q] - a * mc[q]) }
            f.offsetMs = f.offs![0]
            if abs(a - old) < 1e-6 { return }
        }
    }

    /// contra o ajuste de jitter: corte pequeno (< 2,5 s) só vale com trechos quase perfeitos e ganho grande
    static func pieceConvincing(_ pw: Fit, _ gain: Double) -> Bool {
        var minDelta = Int64.max
        let offs = pw.offs!
        for q in 1..<offs.count { minDelta = min(minDelta, abs(offs[q] - offs[q - 1])) }
        if minDelta >= 2500 { return true }
        for sc in pw.segScores! where sc < 0.85 { return false }
        return gain >= 0.15
    }

    static let POLISH_SKIP = 0.92

    /// deriva pequena de escala (±0,4%, passo 0,01%): cada escala vizinha ganha o seu melhor deslocamento (barato) e o melhor é refinado
    static func polish(_ ref: [Int64], _ cand: [Int64], _ best: Fit) -> Fit {
        var top = best
        let s0 = best.scale
        for k in -40...40 {
            if k == 0 { continue }
            let s = s0 + Double(k) * 0.0001
            if s < FREE_MIN || s > FREE_MAX { continue }
            let f = alignWithScale(ref, cand, s)
            if f.score > top.score { top = f }
        }
        if top === best { return best }
        var r = refine(ref, cand, top)
        r = r.score >= top.score ? r : top
        r.freeScale = !isFixedScale(r.scale)
        return r
    }

    /// melhor transformação (escala x deslocamento, ou por partes) que leva a candidata para cima da referência
    static func align(_ ref: [Int64]?, _ cand: [Int64]?) -> Fit {
        let none = Fit()
        guard let ref = ref, let cand = cand, ref.count >= 20, cand.count >= 20 else { return none }
        var best = alignWithScale(ref, cand, SCALES[0])
        var bestOther: Fit? = nil
        for i in 1..<SCALES.count {
            let f = alignWithScale(ref, cand, SCALES[i])
            if bestOther == nil || f.score > bestOther!.score { bestOther = f }
        }
        if let bo = bestOther, bo.score > best.score + SCALE_MARGIN { best = bo }
        best = refine(ref, cand, best)
        halves(ref, cand, best)
        var free: Fit? = nil
        if !(best.singleOk() && best.score >= SKIP_SCORE) {
            let fr = freeScale(ref, cand)
            free = fr
            halves(ref, cand, fr)
            if fr.score > best.score + SCALE_MARGIN && (fr.singleOk() || !best.singleOk()) { best = fr }
        }
        // só REFINA um ajuste que já seria aceito (não cria aceitações novas: mais graus de liberdade = mais acaso) e só se o refinado continuar aceito
        if best.cuts == nil && best.singleOk() && best.score >= MIN_SCORE && best.score < POLISH_SKIP {
            let pol = polish(ref, cand, best)
            if pol !== best {
                halves(ref, cand, pol)
                if pol.singleOk() && pol.score > best.score + 0.03 { best = pol }
            }
        }
        if !(best.singleOk() && best.score >= 0.75) {
            var sc: [Double] = [best.scale]
            if let fr = free, !sc.contains(fr.scale) { sc.append(fr.scale) }
            if !sc.contains(1.0) { sc.append(1.0) }
            if let bo = bestOther, !sc.contains(bo.scale) { sc.append(bo.scale) }
            let arr = Array(sc.prefix(4))
            if let pw = piecewise(ref, cand, arr, best), pw.pieceOk, pw.score >= best.score + PIECE_GAIN, pieceConvincing(pw, pw.score - best.score) { best = pw }
        }
        let lo = max(ref[0], best.map(cand[0])), hi = min(ref[ref.count - 1], best.map(cand[cand.count - 1]))
        best.coverage = Double(max(0, hi - lo)) / Double(max(1, ref[ref.count - 1] - ref[0]))
        // legenda "forçada"/esparsa: acerta todas as poucas falas que tem e ganhava nota máxima (a nota divide pelo MENOR número de falas)
        let wLo = best.map(cand[0]), wHi = best.map(cand[cand.count - 1])
        let refWin = lowerBound(ref, wHi + 1) - lowerBound(ref, wLo)
        best.cueRatio = Double(cand.count) / Double(max(1, refWin))
        if best.cueRatio < MIN_CUE_RATIO { best.sparse = true }
        if Double(refWin) < MIN_REF_RATIO * Double(cand.count) { best.sparseRef = true }
        best.nullScore = nullScore(ref, cand, best)
        return best
    }

    private static let NULL_SHIFTS: [Int64] = [-53000, -31000, -17000, -9000, 9000, 17000, 31000, 53000]

    /// maior nota que a mesma transformação (escala/partes) tem com o deslocamento mudado de 9 a 53 s: o nível do acaso
    static func nullScore(_ ref: [Int64], _ cand: [Int64], _ f: Fit) -> Double {
        let denom = max(1, min(cand.count, ref.count))
        var bestHits = 0
        for d in NULL_SHIFTS {
            var hits = 0
            for c in cand where near(ref, f.map(c) + d, TOLERANCE_MS) { hits += 1 }
            bestHits = max(bestHits, hits)
        }
        return Double(bestHits) / Double(denom)
    }

    /// fração das falas da candidata que as duas transformações levam para menos de tol ms uma da outra
    static func agreement(_ a: Fit, _ b: Fit, _ cand: [Int64]?, _ tol: Int64) -> Double {
        guard let cand = cand, !cand.isEmpty else { return 0 }
        var ok = 0
        for c in cand where abs(a.map(c) - b.map(c)) <= tol { ok += 1 }
        return Double(ok) / Double(cand.count)
    }

    // ---------- consenso entre legendas (sem referência de tempo) ----------
    static let RAW_AGREE = 0.4
    static let RAW_TOL_MS: Int64 = 500

    static func matchesTol(_ ref: [Int64], _ cand: [Int64], _ off: Int64, _ tol: Int64) -> Int {
        var hits = 0
        for c in cand where near(ref, c + off, tol) { hits += 1 }
        return hits
    }

    static func rawAgreement(_ a: [Int64]?, _ b: [Int64]?) -> Double {
        guard let a = a, let b = b, a.count >= 20, b.count >= 20 else { return 0 }
        return Double(matchesTol(b, a, 0, RAW_TOL_MS)) / Double(min(a.count, b.count))
    }

    /// "mesmos tempos": concordância bruta >= RAW_AGREE E bem acima do acaso (a mesma comparação com as falas deslocadas de 9 e 23 s)
    static func sameTiming(_ a: [Int64], _ b: [Int64]) -> Bool {
        let raw = rawAgreement(a, b)
        if raw < RAW_AGREE { return false }
        var chance = 0.0
        let offs: [Int64] = [9000, -9000, 23000, -23000]
        for o in offs { chance = max(chance, Double(matchesTol(b, a, o, RAW_TOL_MS)) / Double(min(a.count, b.count))) }
        return raw >= chance + 0.2
    }

    /// componentes conexas por concordância bruta: ids 0.. na ordem do primeiro membro; legendas com poucas falas ficam sozinhas
    static func consensusGroups(_ starts: [[Int64]]) -> [Int] {
        let n = starts.count
        var p = Array(0..<n)
        func find(_ x0: Int) -> Int {
            var x = x0
            while p[x] != x { p[x] = p[p[x]]; x = p[x] }
            return x
        }
        for i in 0..<n {
            for j in (i + 1)..<max(n, i + 1) where j < n {
                if sameTiming(starts[i], starts[j]) {
                    let a = find(i), b = find(j)
                    if a != b { p[max(a, b)] = min(a, b) }
                }
            }
        }
        var id = [Int](repeating: 0, count: n)
        var map: [Int: Int] = [:]
        for i in 0..<n {
            let r = find(i)
            var g = map[r]
            if g == nil { g = map.count; map[r] = g }
            id[i] = g!
        }
        return id
    }

    // ---------- parecença de nomes ----------
    private static let SOURCE_T: Set<String> = ["webdl", "webrip", "web", "bluray", "remux", "hdtv", "dvdrip", "dvd", "cam", "ts", "hdrip", "hdcam", "telesync"]
    private static let RES_T: Set<String> = ["2160p", "4k", "uhd", "1080p", "720p", "480p", "576p"]

    private static func rep(_ s: String, _ pattern: String, _ with: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        return re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: with)
    }

    private static func tokens(_ s: String?) -> Set<String> {
        var t = (s ?? "").lowercased()
        t = rep(t, "web[-. _]?dl", "webdl")
        t = rep(t, "blu[-. _]?ray|bdrip|brrip|bd[-. _]?remux", "bluray")
        t = rep(t, "web[-. _]?rip", "webrip")
        t = rep(t, "h[. ]?264", "x264")
        t = rep(t, "h[. ]?265|hevc", "x265")
        t = rep(t, "telesync", "ts")
        var out = Set<String>()
        var cur = U16()
        func flush() {
            if cur.count >= 2 {
                let w = J.str(cur)
                if w != "srt" && w != "sub" && w != "por" && w != "pob" && w != "eng" { out.insert(w) }
            }
            cur.removeAll(keepingCapacity: true)
        }
        for c in t.utf16 {
            if (c >= 97 && c <= 122) || (c >= 48 && c <= 57) { cur.append(c) } else { flush() }
        }
        flush()
        return out
    }

    /// parecença (0..1) entre o nome do arquivo de vídeo e o nome da release da legenda: fonte (WEB-DL/BluRay/REMUX...), resolução, grupo e o resto dos termos
    static func nameSimilarity(_ videoName: String?, _ subName: String?) -> Double {
        guard let videoName = videoName, let subName = subName, !videoName.isEmpty, !subName.isEmpty else { return 0 }
        let v = tokens(videoName), s = tokens(subName)
        if v.isEmpty || s.isEmpty { return 0 }
        var score = 0.0
        var srcV = false, srcS = false, srcBoth = false, resBoth = false
        for w in v where SOURCE_T.contains(w) { srcV = true; if s.contains(w) { srcBoth = true } }
        for w in s where SOURCE_T.contains(w) { srcS = true }
        for w in v where RES_T.contains(w) && s.contains(w) { resBoth = true }
        if srcBoth { score += 0.35 } else if srcV && srcS { score -= 0.1 }
        if resBoth { score += 0.15 }
        // grupo da release = último termo depois do último '-' do nome do vídeo (ex.: ...x264-RARBG)
        let vn = rep(videoName.lowercased(), "\\.[a-z0-9]{2,4}$", "")
        let vnu = J.u16(vn)
        var dash = -1
        var k = vnu.count - 1
        while k >= 0 { if vnu[k] == 45 { dash = k; break }; k -= 1 }
        if dash > 0 && dash < vnu.count - 2 {
            let grp = rep(J.str(Array(vnu[(dash + 1)...])), "[^a-z0-9]", "")
            if grp.utf16.count >= 3 && s.contains(grp) { score += 0.3 }
        }
        var inter = 0
        for w in v where s.contains(w) { inter += 1 }
        score += 0.2 * Double(inter) / Double(v.count + s.count - inter)
        return max(0, min(1, score))
    }

    /// Escolhe o grupo de legendas que combina (consenso). Pontos do grupo: nº de legendas + 3 se alguma casa com o hash +
    /// 2 x melhor parecença de nome + 1,5 se a duração da última fala (mediana do grupo) é compatível com o vídeo (-100 se absurda).
    static func pickGroup(_ comp: [Int], _ hash: [Bool], _ name: [Double], _ lastMs: [Int64], _ videoLenMs: Int64, _ why: StrBuf?) -> Int {
        var ng = 0
        for c in comp { ng = max(ng, c + 1) }
        var bestG = -1
        var bestScore = -1e9
        for g in 0..<ng {
            var size = 0
            var anyHash = false
            var nm = 0.0
            var ends: [Int64] = []
            for i in 0..<comp.count where comp[i] == g {
                size += 1
                anyHash = anyHash || hash[i]
                nm = max(nm, name[i])
                if lastMs[i] > 0 { ends.append(lastMs[i]) }
            }
            if size == 0 { continue }
            var dur = 0.0
            if videoLenMs > 0 && !ends.isEmpty {
                ends.sort()
                let med = ends[ends.count / 2]
                if Double(med) >= Double(videoLenMs) * 0.6 && Double(med) <= Double(videoLenMs) * 1.05 { dur = 1.5 }
                else if Double(med) < Double(videoLenMs) * 0.5 || Double(med) > Double(videoLenMs) * 1.12 { dur = -100 }
            }
            let sc = Double(size) + (anyHash ? 3 : 0) + 2 * nm + dur
            if let w = why {
                w.a("\n  grupo \(g): \(size) legenda(s)" + (anyHash ? ", hash" : "") + ", nome " + J.fmt(nm, 2) + (dur > 0 ? ", duração ok" : dur < 0 ? ", duração absurda" : "") + " -> " + J.fmt(sc, 2))
            }
            if sc > bestScore { bestScore = sc; bestG = g }
        }
        return bestG
    }
}
