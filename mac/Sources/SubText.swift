// Texto das legendas (Swift puro, só Foundation): porte fiel do SubtitleText.java do Android.
// bytes -> texto UTF-8 limpo (gzip/zip, BOM, UTF-16, cp1252, "mojibake", CRLF/CR, ASS/SSA -> SRT, tempos fora do padrão),
// conferência de idioma e fim da última fala. Tudo trabalha em UTF-16 (como o String do Java) para dar os mesmos resultados.
import Foundation

typealias U16 = [UInt16]

/// Utilidades no estilo do Java (arredondamento, formato, trim, split) usadas pelas portas das legendas.
enum J {
    /// Math.round(double) do Java: o inteiro mais próximo, meio para cima
    static func round(_ x: Double) -> Int64 {
        if x.isNaN { return 0 }
        if x >= 9.2e18 { return Int64.max }
        if x <= -9.2e18 { return Int64.min }
        let f = x.rounded(.down)
        return Int64(x - f >= 0.5 ? f + 1 : f)
    }

    /// String.format("%.Nf") do Java: parte dos dígitos mais curtos do double e arredonda "meio para cima" (HALF_UP)
    static func fmt(_ v: Double, _ decimals: Int) -> String {
        if v.isNaN { return "NaN" }
        if v.isInfinite { return v < 0 ? "-Infinity" : "Infinity" }
        let neg = v.sign == .minus
        let s = "\(abs(v))"
        var mant = s
        var exp = 0
        if let e = s.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            mant = String(s[s.startIndex..<e])
            exp = Int(s[s.index(after: e)...].replacingOccurrences(of: "+", with: "")) ?? 0
        }
        var intPart = mant
        var frac = ""
        if let dot = mant.firstIndex(of: ".") {
            intPart = String(mant[mant.startIndex..<dot])
            frac = String(mant[mant.index(after: dot)...])
        }
        // dígitos e posição da vírgula
        var digits = Array((intPart + frac).utf8).map { Int($0) - 48 }
        var point = intPart.count + exp
        if point < 0 { digits = [Int](repeating: 0, count: -point) + digits; point = 0 }
        if point > digits.count { digits += [Int](repeating: 0, count: point - digits.count) }
        // mantém `point + decimals` dígitos, arredondando pelo seguinte
        let keep = point + decimals
        var out = Array(digits.prefix(keep))
        if out.count < keep { out += [Int](repeating: 0, count: keep - out.count) }
        if digits.count > keep && digits[keep] >= 5 {
            var i = keep - 1
            while i >= 0 {
                if out[i] == 9 { out[i] = 0; i -= 1 } else { out[i] += 1; break }
            }
            if i < 0 { out.insert(1, at: 0); point += 1 }
        }
        let ip = out.prefix(point).map(String.init).joined()
        let fp = out.dropFirst(point).map(String.init).joined()
        let body = (ip.isEmpty ? "0" : ip) + (decimals > 0 ? "." + fp : "")
        return (neg ? "-" : "") + body
    }

    /// "%02d"/"%03d" do Java (preenche com zeros à esquerda; números negativos não ocorrem aqui)
    static func pad(_ n: Int64, _ w: Int) -> String {
        let s = String(n)
        return s.count >= w ? s : String(repeating: "0", count: w - s.count) + s
    }

    /// String.trim() do Java: tira os caracteres <= U+0020 das pontas
    static func trim(_ u: ArraySlice<UInt16>) -> U16 {
        var a = u.startIndex, b = u.endIndex
        while a < b && u[a] <= 0x20 { a += 1 }
        while b > a && u[b - 1] <= 0x20 { b -= 1 }
        return Array(u[a..<b])
    }
    static func trim(_ u: U16) -> U16 { return trim(u[0..<u.count]) }

    static func str(_ u: U16) -> String { return String(decoding: u, as: UTF16.self) }
    static func str(_ u: ArraySlice<UInt16>) -> String { return String(decoding: u, as: UTF16.self) }
    static func u16(_ s: String) -> U16 { return Array(s.utf16) }

    /// String.split(sep) do Java para um separador de 1 caractere; limit <= 0: sem limite (e, com 0, tira as vazias do fim); limit > 0: no máximo `limit` partes
    static func split(_ u: U16, _ sep: UInt16, limit: Int = 0) -> [U16] {
        var parts: [U16] = []
        var start = 0
        var i = 0
        while i < u.count {
            if u[i] == sep && (limit <= 0 || parts.count < limit - 1) {
                parts.append(Array(u[start..<i]))
                start = i + 1
            }
            i += 1
        }
        parts.append(Array(u[start..<u.count]))
        if limit == 0 { while parts.count > 1 && parts.last!.isEmpty { parts.removeLast() } ; if parts.count == 1 && parts[0].isEmpty && !u.isEmpty { parts = [] } }
        return parts
    }

    /// indexOf(padrão, desde) no array de unidades UTF-16; -1 se não achar
    static func indexOf(_ u: U16, _ pat: [UInt16], _ from: Int = 0) -> Int {
        let n = u.count, m = pat.count
        if m == 0 { return min(max(from, 0), n) }
        var i = max(from, 0)
        if n < m { return -1 }
        while i <= n - m {
            if u[i] == pat[0] {
                var k = 1
                while k < m && u[i + k] == pat[k] { k += 1 }
                if k == m { return i }
            }
            i += 1
        }
        return -1
    }

    static func indexOf(_ u: U16, _ ch: UInt16, _ from: Int = 0) -> Int {
        var i = max(from, 0)
        while i < u.count { if u[i] == ch { return i }; i += 1 }
        return -1
    }

    static func lower(_ c: UInt16) -> UInt16 { return (c >= 65 && c <= 90) ? c + 32 : c }

    /// regionMatches(ignoreCase = true) só com o alfabeto ASCII
    static func startsWithIgnoreCase(_ u: U16, _ pat: String) -> Bool {
        let p = Array(pat.utf16)
        if u.count < p.count { return false }
        for k in 0..<p.count { if lower(u[k]) != lower(p[k]) { return false } }
        return true
    }

    /// Character.isLetter para uma unidade UTF-16 (letras da categoria L*; metades de par substituto não contam)
    static func isLetter(_ c: UInt16) -> Bool {
        if c < 0x80 { return (c >= 65 && c <= 90) || (c >= 97 && c <= 122) }
        guard let sc = Unicode.Scalar(UInt32(c)) else { return false }
        return isLetterScalar(sc)
    }
    static func isLetterScalar(_ sc: Unicode.Scalar) -> Bool {
        switch sc.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }
}

// ---------- gzip / zip (inflate próprio: sem processos e sem bibliotecas) ----------
enum Zip {
    private static let crcTable: [UInt32] = {
        var t = [UInt32](repeating: 0, count: 256)
        for n in 0..<256 {
            var c = UInt32(n)
            for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
            t[n] = c
        }
        return t
    }()
    static func crc32(_ d: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for b in d { c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }

    private static let lbase: [Int] = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    private static let lext: [Int] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0]
    private static let dbase: [Int] = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577]
    private static let dext: [Int] = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]
    private static let clOrder: [Int] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]

    private struct Huff { var count = [Int](repeating: 0, count: 16); var symbol: [Int] = [] }

    private static func construct(_ lengths: [Int], _ n: Int) -> (Huff, Int) {
        var h = Huff()
        h.symbol = [Int](repeating: 0, count: n)
        for s in 0..<n { h.count[lengths[s]] += 1 }
        if h.count[0] == n { return (h, 0) }
        var left = 1
        for len in 1...15 {
            left <<= 1
            left -= h.count[len]
            if left < 0 { return (h, left) }
        }
        var offs = [Int](repeating: 0, count: 16)
        for len in 1..<15 { offs[len + 1] = offs[len] + h.count[len] }
        for s in 0..<n where lengths[s] != 0 { h.symbol[offs[lengths[s]]] = s; offs[lengths[s]] += 1 }
        return (h, left)
    }

    private struct Reader {
        let src: [UInt8]
        var pos: Int
        var bitBuf: Int = 0
        var bitCnt: Int = 0
        var out: [UInt8] = []
        var failed = false

        mutating func bits(_ need: Int) -> Int {
            var v = bitBuf
            while bitCnt < need {
                if pos >= src.count { failed = true; return 0 }
                v |= Int(src[pos]) << bitCnt
                pos += 1
                bitCnt += 8
            }
            bitBuf = v >> need
            bitCnt -= need
            return v & ((1 << need) - 1)
        }

        mutating func decode(_ h: Huff) -> Int {
            var code = 0, first = 0, index = 0
            for len in 1...15 {
                code |= bits(1)
                if failed { return -1 }
                let count = h.count[len]
                if code - count < first { return h.symbol[index + (code - first)] }
                index += count
                first += count
                first <<= 1
                code <<= 1
            }
            return -1
        }
    }

    private static func codes(_ r: inout Reader, _ lencode: Huff, _ distcode: Huff) -> Bool {
        while true {
            var sym = r.decode(lencode)
            if sym < 0 || r.failed { return false }
            if sym < 256 { r.out.append(UInt8(sym)) }
            else if sym == 256 { return true }
            else {
                sym -= 257
                if sym >= 29 { return false }
                let len = lbase[sym] + r.bits(lext[sym])
                let ds = r.decode(distcode)
                if ds < 0 || ds >= 30 || r.failed { return false }
                let dist = dbase[ds] + r.bits(dext[ds])
                if r.failed || dist > r.out.count { return false }
                var k = 0
                let start = r.out.count - dist
                while k < len { r.out.append(r.out[start + k]); k += 1 }
            }
            if r.out.count > 64_000_000 { return false }
        }
    }

    /// Deflate puro a partir de `from`; devolve (dados, posição do primeiro byte depois do fluxo) ou nil se estiver corrompido
    static func inflate(_ src: [UInt8], from: Int) -> ([UInt8], Int)? {
        var r = Reader(src: src, pos: from)
        var last = 0
        repeat {
            last = r.bits(1)
            let type = r.bits(2)
            if r.failed { return nil }
            switch type {
            case 0:
                r.bitBuf = 0; r.bitCnt = 0
                if r.pos + 4 > src.count { return nil }
                let len = Int(src[r.pos]) | (Int(src[r.pos + 1]) << 8)
                let nlen = Int(src[r.pos + 2]) | (Int(src[r.pos + 3]) << 8)
                r.pos += 4
                if len != (~nlen & 0xFFFF) { return nil }
                if r.pos + len > src.count { return nil }
                r.out.append(contentsOf: src[r.pos..<(r.pos + len)])
                r.pos += len
            case 1:
                var l = [Int](repeating: 8, count: 288)
                for i in 144..<256 { l[i] = 9 }
                for i in 256..<280 { l[i] = 7 }
                let (lc, _) = construct(l, 288)
                let (dc, _) = construct([Int](repeating: 5, count: 30), 30)
                if !codes(&r, lc, dc) { return nil }
            case 2:
                let nlen = r.bits(5) + 257, ndist = r.bits(5) + 1, ncode = r.bits(4) + 4
                if r.failed || nlen > 286 || ndist > 30 { return nil }
                var lengths = [Int](repeating: 0, count: 320)
                for i in 0..<ncode { lengths[clOrder[i]] = r.bits(3) }
                if r.failed { return nil }
                let (cl, err) = construct(lengths, 19)
                if err != 0 { return nil }
                var idx = 0
                lengths = [Int](repeating: 0, count: 320)
                while idx < nlen + ndist {
                    var sym = r.decode(cl)
                    if sym < 0 || r.failed { return nil }
                    if sym < 16 { lengths[idx] = sym; idx += 1 }
                    else {
                        var len = 0
                        var rep = 0
                        if sym == 16 {
                            if idx == 0 { return nil }
                            len = lengths[idx - 1]
                            rep = 3 + r.bits(2)
                        } else if sym == 17 { rep = 3 + r.bits(3) }
                        else { rep = 11 + r.bits(7) }
                        sym = 0
                        if r.failed || idx + rep > nlen + ndist { return nil }
                        while rep > 0 { lengths[idx] = len; idx += 1; rep -= 1 }
                    }
                }
                if lengths[256] == 0 { return nil }
                let (lc, e1) = construct(Array(lengths[0..<nlen]), nlen)
                if e1 != 0 && (e1 < 0 || nlen != lc.count[0] + lc.count[1]) { return nil }
                let (dc, e2) = construct(Array(lengths[nlen..<(nlen + ndist)]), ndist)
                if e2 != 0 && (e2 < 0 || ndist != dc.count[0] + dc.count[1]) { return nil }
                if !codes(&r, lc, dc) { return nil }
            default:
                return nil
            }
        } while last == 0
        return (r.out, r.pos)
    }

    /// GZIPInputStream do Java: um ou mais membros, confere o CRC e o tamanho; qualquer erro = nil
    static func gunzip(_ d: [UInt8]) -> [UInt8]? {
        var out: [UInt8] = []
        var pos = 0
        var first = true
        while pos + 10 <= d.count && d[pos] == 0x1F && d[pos + 1] == 0x8B {
            if d[pos + 2] != 8 { if first { return nil } else { break } }
            let flg = d[pos + 3]
            var p = pos + 10
            if flg & 4 != 0 {
                if p + 2 > d.count { return nil }
                p += 2 + (Int(d[p]) | (Int(d[p + 1]) << 8))
            }
            if flg & 8 != 0 { while p < d.count && d[p] != 0 { p += 1 }; p += 1 }
            if flg & 16 != 0 { while p < d.count && d[p] != 0 { p += 1 }; p += 1 }
            if flg & 2 != 0 { p += 2 }
            if p >= d.count { return nil }
            guard let (data, end) = inflate(d, from: p) else { return nil }
            if end + 8 > d.count { return nil }
            let crc = UInt32(d[end]) | (UInt32(d[end + 1]) << 8) | (UInt32(d[end + 2]) << 16) | (UInt32(d[end + 3]) << 24)
            let isz = UInt32(d[end + 4]) | (UInt32(d[end + 5]) << 8) | (UInt32(d[end + 6]) << 16) | (UInt32(d[end + 7]) << 24)
            if crc != crc32(data) || isz != UInt32(truncatingIfNeeded: data.count) { return nil }
            out.append(contentsOf: data)
            pos = end + 8
            first = false
            if out.count > SubtitleText.maxBytes { break }
        }
        if first { return nil }
        return out
    }

    private static func le32(_ d: [UInt8], _ p: Int) -> UInt32 {
        return UInt32(d[p]) | (UInt32(d[p + 1]) << 8) | (UInt32(d[p + 2]) << 16) | (UInt32(d[p + 3]) << 24)
    }
    private static func le16(_ d: [UInt8], _ p: Int) -> Int { return Int(d[p]) | (Int(d[p + 1]) << 8) }

    /// primeira entrada de legenda de um .zip (.srt/.vtt/.ass/.ssa; senão a primeira que não seja pasta); nil se nada/erro
    static func unzipFirst(_ d: [UInt8]) -> [UInt8]? {
        var pos = 0
        var firstData: [UInt8]? = nil
        while pos + 30 <= d.count && le32(d, pos) == 0x04034b50 {
            let flags = le16(d, pos + 6)
            let method = le16(d, pos + 8)
            var crc = le32(d, pos + 14)
            var csize = Int(le32(d, pos + 18))
            var usize = Int(le32(d, pos + 22))
            let nlen = le16(d, pos + 26), xlen = le16(d, pos + 28)
            let nameStart = pos + 30
            if nameStart + nlen + xlen > d.count { return nil }
            let name = String(decoding: d[nameStart..<(nameStart + nlen)], as: UTF8.self).lowercased()
            let dataStart = nameStart + nlen + xlen
            var data: [UInt8]
            var next: Int
            if method == 0 {
                if flags & 8 != 0 { return nil } // o Java não lê entrada guardada com descritor
                if dataStart + csize > d.count { return nil }
                data = Array(d[dataStart..<(dataStart + csize)])
                next = dataStart + csize
            } else if method == 8 {
                guard let (inf, end) = inflate(d, from: dataStart) else { return nil }
                data = inf
                next = end
                if flags & 8 != 0 {
                    if next + 4 <= d.count && le32(d, next) == 0x08074b50 { next += 4 }
                    if next + 12 > d.count { return nil }
                    crc = le32(d, next); csize = Int(le32(d, next + 4)); usize = Int(le32(d, next + 8))
                    next += 12
                } else {
                    if next - dataStart != csize { return nil }
                }
            } else { return nil }
            if usize != data.count || crc != crc32(data) { return nil }
            pos = next
            if name.hasSuffix("/") { continue }
            if name.hasSuffix(".srt") || name.hasSuffix(".vtt") || name.hasSuffix(".ass") || name.hasSuffix(".ssa") { return data }
            // o Java fecha o ZipInputStream em readAll(): o getNextEntry seguinte lança "Stream closed" e decode() devolve null
            _ = data; firstData = nil
            return nil
        }
        return firstData
    }
}

// ---------- o texto da legenda ----------
enum SubtitleText {
    static let maxBytes = 5_000_000

    /// bytes baixados -> texto de legenda normalizado; nil se não for uma legenda (página de erro, arquivo vazio, outro formato)
    static func decode(_ data: [UInt8]?) -> String? {
        guard var d = data, d.count >= 20 else { return nil }
        if d.count > 4 && d[0] == 0x50 && d[1] == 0x4B && d[2] == 3 && d[3] == 4 {
            guard let z = Zip.unzipFirst(d) else { return nil }
            d = z
        } else if d[0] == 0x1F && d[1] == 0x8B {
            guard let g = Zip.gunzip(d) else { return nil }
            d = g
        }
        if d.count < 20 { return nil }
        let text = bytesToUnits(d)
        guard let out = normalize(text) else { return nil }
        return J.str(out)
    }

    // ---------- bytes -> UTF-16 ----------
    private static func utf16Units(_ d: ArraySlice<UInt8>, bigEndian: Bool) -> U16 {
        var out = U16()
        out.reserveCapacity(d.count / 2 + 1)
        var i = d.startIndex
        let end = d.endIndex
        var pendingHigh: UInt16? = nil
        func unit(_ k: Int) -> UInt16 {
            return bigEndian ? (UInt16(d[k]) << 8 | UInt16(d[k + 1])) : (UInt16(d[k + 1]) << 8 | UInt16(d[k]))
        }
        while i + 1 < end {
            let u = unit(i)
            i += 2
            if let h = pendingHigh {
                if u >= 0xDC00 && u <= 0xDFFF { out.append(h); out.append(u); pendingHigh = nil; continue }
                out.append(0xFFFD); pendingHigh = nil
            }
            if u >= 0xD800 && u <= 0xDBFF { pendingHigh = u }
            else if u >= 0xDC00 && u <= 0xDFFF { out.append(0xFFFD) }
            else { out.append(u) }
        }
        if pendingHigh != nil { out.append(0xFFFD) }
        if i < end { out.append(0xFFFD) } // byte sobrando no fim
        return out
    }

    /// UTF-8 estrito: nil se tiver bytes inválidos
    private static func utf8Strict(_ d: [UInt8]) -> U16? {
        var out = U16()
        out.reserveCapacity(d.count)
        var i = 0
        let n = d.count
        while i < n {
            let b0 = d[i]
            if b0 < 0x80 { out.append(UInt16(b0)); i += 1; continue }
            var need = 0
            var cp: UInt32 = 0
            var minCp: UInt32 = 0
            if b0 >= 0xC2 && b0 <= 0xDF { need = 1; cp = UInt32(b0 & 0x1F); minCp = 0x80 }
            else if b0 >= 0xE0 && b0 <= 0xEF { need = 2; cp = UInt32(b0 & 0x0F); minCp = 0x800 }
            else if b0 >= 0xF0 && b0 <= 0xF4 { need = 3; cp = UInt32(b0 & 0x07); minCp = 0x10000 }
            else { return nil }
            if i + need >= n { return nil }
            for k in 1...need {
                let b = d[i + k]
                if b & 0xC0 != 0x80 { return nil }
                cp = (cp << 6) | UInt32(b & 0x3F)
            }
            if cp < minCp || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) { return nil }
            if cp >= 0x10000 {
                let v = cp - 0x10000
                out.append(UInt16(0xD800 + (v >> 10)))
                out.append(UInt16(0xDC00 + (v & 0x3FF)))
            } else { out.append(UInt16(cp)) }
            i += need + 1
        }
        return out
    }

    /// 0x80...0x9F do Windows-1252 (0xFFFD = sem mapeamento: o Java troca por U+FFFD)
    private static let cp1252: [UInt16] = [
        0x20AC, 0xFFFD, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0xFFFD, 0x017D, 0xFFFD,
        0xFFFD, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0xFFFD, 0x017E, 0x0178,
    ]

    /// UTF-16 (com ou sem BOM), UTF-8 (com ou sem BOM) ou, se tiver bytes inválidos, Windows-1252
    static func bytesToUnits(_ dd: [UInt8]) -> U16 {
        var d = dd
        var n = d.count
        if n >= 2 && d[0] == 0xFF && d[1] == 0xFE { return utf16Units(d[2..<n], bigEndian: false) }
        if n >= 2 && d[0] == 0xFE && d[1] == 0xFF { return utf16Units(d[2..<n], bigEndian: true) }
        if n >= 3 && d[0] == 0xEF && d[1] == 0xBB && d[2] == 0xBF { d = Array(d[3..<n]); n = d.count }
        // UTF-16 sem BOM: um byte zero a cada dois, em texto que seria ASCII
        if n >= 40 {
            var zeroOdd = 0, zeroEven = 0
            let sample = min(n, 400) / 2
            for i in 0..<sample { if d[2 * i] == 0 { zeroEven += 1 }; if d[2 * i + 1] == 0 { zeroOdd += 1 } }
            if Double(zeroOdd) > Double(sample) * 0.4 && zeroEven == 0 { return utf16Units(d[0..<n], bigEndian: false) }
            if Double(zeroEven) > Double(sample) * 0.4 && zeroOdd == 0 { return utf16Units(d[0..<n], bigEndian: true) }
        }
        let s: U16
        if let u = utf8Strict(d) { s = u }
        else {
            var o = U16()
            o.reserveCapacity(n)
            for b in d {
                if b >= 0x80 && b < 0xA0 { o.append(cp1252[Int(b) - 0x80]) } else { o.append(UInt16(b)) }
            }
            s = o
        }
        return fixMojibake(s)
    }

    private static let mojiSet: Set<UInt16> = [0x0152, 0x0153, 0x0160, 0x0161, 0x0178, 0x017D, 0x017E, 0x0192, 0x02C6, 0x02DC, 0x2013, 0x2014, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E, 0x2020, 0x2021, 0x2022, 0x2026, 0x2030, 0x2039, 0x203A, 0x20AC, 0x2122]
    private static let goodAcc: Set<UInt16> = Set("ãõçéêáâíóôúàÃÕÇÉÊÁÂÍÓÔÚ".utf16)

    /// UTF-8 lido como Windows-1252 ("nÃ£o" em vez de "não"): refaz o caminho inverso se isso der UTF-8 válido
    static func fixMojibake(_ s: U16) -> U16 {
        var bad = 0
        var i = 0
        let n = s.count
        while i < n {
            if s[i] == 0x00C3 && i + 1 < n && ((s[i + 1] >= 0x20 && s[i + 1] <= 0xBF) || mojiSet.contains(s[i + 1])) { bad += 1; i += 2 }
            else { i += 1 }
        }
        if bad < 5 { return s }
        var good = 0
        for c in s where goodAcc.contains(c) { good += 1 }
        if good > bad { return s }
        // codificação inversa em Windows-1252: um caractere sem byte correspondente cancela tudo
        var rev = [UInt16: UInt8]()
        for (k, u) in cp1252.enumerated() where u != 0xFFFD { rev[u] = UInt8(0x80 + k) }
        var raw = [UInt8]()
        raw.reserveCapacity(n)
        for c in s {
            if c < 0x80 || (c >= 0xA0 && c <= 0xFF) { raw.append(UInt8(c)) }
            else if let b = rev[c] { raw.append(b) }
            else { return s }
        }
        return utf8Strict(raw) ?? s
    }

    // ---------- normalização ----------
    private static let arrow: [UInt16] = [45, 45, 62]

    static func isDigit(_ c: UInt16) -> Bool { return c >= 48 && c <= 57 }
    static func isTab(_ c: UInt16) -> Bool { return c == 32 || c == 9 }
    /// terminadores de linha que o `.` do Java não atravessa (o texto já não tem \r)
    private static func isTerm(_ c: UInt16) -> Bool { return c == 10 || c == 13 || c == 0x85 || c == 0x2028 || c == 0x2029 }

    struct TL { var h: Int64; var m: Int64; var s: Int64; var f: U16; var sep: UInt16; var end: Int } // f = fração (dígitos) ou vazio

    /// `(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[,.](\d{1,3}))?` a partir de i; maxFrac... `exactCanon`: segundos \d\d e fração exatamente 3 dígitos (obrigatória)
    static func timeAt(_ u: U16, _ i: Int, _ hi: Int, canon: Bool) -> TL? {
        // variante A: com horas; B: sem
        for withHours in [true, false] {
            var p = i
            var h: Int64 = 0
            if withHours {
                var q = p
                var v: Int64 = 0
                while q < hi && isDigit(u[q]) { v = v &* 10 &+ Int64(u[q] - 48); q += 1 }
                if q == p || q >= hi || u[q] != 58 { continue }
                h = v
                p = q + 1
            }
            // minutos: 1 ou 2 dígitos seguidos de ':'
            var q = p
            var v: Int64 = 0
            var nd = 0
            while q < hi && nd < 2 && isDigit(u[q]) { v = v * 10 + Int64(u[q] - 48); q += 1; nd += 1 }
            if nd == 0 || q >= hi || u[q] != 58 { continue }
            let mi = v
            q += 1
            // segundos
            var s: Int64 = 0
            var sd = 0
            let maxSd = 2
            let r0 = q
            while q < hi && sd < maxSd && isDigit(u[q]) { s = s * 10 + Int64(u[q] - 48); q += 1; sd += 1 }
            if sd == 0 || (canon && sd != 2) { continue }
            _ = r0
            // fração
            var frac = U16()
            var sep: UInt16 = 44
            if canon {
                if q + 4 <= hi && (u[q] == 44 || u[q] == 46) && isDigit(u[q + 1]) && isDigit(u[q + 2]) && isDigit(u[q + 3]) {
                    sep = u[q]
                    frac = [u[q + 1], u[q + 2], u[q + 3]]
                    q += 4
                } else { continue }
            } else {
                if q < hi && (u[q] == 44 || u[q] == 46) {
                    var k = q + 1
                    var fd = U16()
                    while k < hi && fd.count < 3 && isDigit(u[k]) { fd.append(u[k]); k += 1 }
                    if !fd.isEmpty { frac = fd; q = k }
                }
            }
            return TL(h: h, m: mi, s: s, f: frac, sep: sep, end: q)
        }
        return nil
    }

    static func fracMs(_ f: U16) -> Int64 {
        if f.isEmpty { return 0 }
        var v: Int64 = 0
        for c in f { v = v * 10 + Int64(c - 48) }
        for _ in f.count..<3 { v *= 10 }
        return v
    }

    private static func canon(_ t: TL) -> U16 {
        let tot = ((t.h &* 60 &+ t.m) &* 60 &+ t.s) &* 1000 &+ fracMs(t.f)
        return J.u16(J.pad(tot / 3600000, 2) + ":" + J.pad((tot / 60000) % 60, 2) + ":" + J.pad((tot / 1000) % 60, 2) + "," + J.pad(tot % 1000, 3))
    }

    /// CANON_LINE.matches(ln)
    private static func isCanon(_ ln: U16) -> Bool {
        let n = ln.count
        var p = 0
        while p < n && isTab(ln[p]) { p += 1 }
        guard let a = timeAt(ln, p, n, canon: true) else { return false }
        var q = a.end
        while q < n && isTab(ln[q]) { q += 1 }
        if q + 3 > n || ln[q] != 45 || ln[q + 1] != 45 || ln[q + 2] != 62 { return false }
        q += 3
        while q < n && isTab(ln[q]) { q += 1 }
        guard let b = timeAt(ln, q, n, canon: true) else { return false }
        // `.*$` precisa chegar ao fim da linha sem passar por terminadores
        var k = b.end
        while k < n { if isTerm(ln[k]) { return false }; k += 1 }
        return true
    }

    /// TIME_LINE.matches(ln): devolve a linha reescrita no padrão, ou nil se a linha não bate
    private static func rewriteTimeLine(_ ln: U16) -> U16? {
        let n = ln.count
        var p = 0
        while p < n && isTab(ln[p]) { p += 1 }
        guard let a = timeAt(ln, p, n, canon: false) else { return nil }
        var q = a.end
        while q < n && isTab(ln[q]) { q += 1 }
        if q + 3 > n || ln[q] != 45 || ln[q + 1] != 45 || ln[q + 2] != 62 { return nil }
        q += 3
        while q < n && isTab(ln[q]) { q += 1 }
        guard let b = timeAt(ln, q, n, canon: false) else { return nil }
        var k = b.end
        while k < n { if isTerm(ln[k]) { return nil }; k += 1 }
        var out = canon(a)
        out += [32, 45, 45, 62, 32]
        out += canon(b)
        out += ln[b.end..<n]
        return out
    }

    /// fim de linha "\n", sem BOM; ASS/SSA vira SRT; linhas de tempo fora do padrão (centésimos, sem fração) são reescritas no padrão
    static func normalize(_ input: U16) -> U16? {
        var t = input
        if t.contains(0xFEFF) { t = t.filter { $0 != 0xFEFF } }
        if t.contains(13) {
            var o = U16()
            o.reserveCapacity(t.count)
            var i = 0
            while i < t.count {
                if t[i] == 13 {
                    if i + 1 < t.count && t[i + 1] == 10 { i += 1; continue }
                    o.append(10)
                } else { o.append(t[i]) }
                i += 1
            }
            t = o
        }
        if J.indexOf(t, arrow) < 0 {
            if J.indexOf(t, J.u16("[Events]")) >= 0 && J.indexOf(t, J.u16("Dialogue:")) >= 0 {
                return assToSrt(t)
            }
            return nil
        }
        let len = t.count
        var i = 0
        var sb: U16? = nil
        while i < len {
            var e = J.indexOf(t, 10, i)
            if e < 0 { e = len }
            // só mexe nas linhas de tempo que não estão no padrão (a maioria dos arquivos passa direto)
            var hasArrow = false
            if e - i >= 3 {
                var k = i
                while k + 2 < e { if t[k] == 45 && t[k + 1] == 45 && t[k + 2] == 62 { hasArrow = true; break }; k += 1 }
            }
            if hasArrow {
                let ln = Array(t[i..<e])
                if !isCanon(ln), let rw = rewriteTimeLine(ln) {
                    if sb == nil { sb = U16(); sb!.reserveCapacity(len + 64); sb!.append(contentsOf: t[0..<i]) }
                    sb!.append(contentsOf: rw)
                    i = e
                    if i < len { sb!.append(10); i += 1 }
                    continue
                }
            }
            if sb != nil { sb!.append(contentsOf: t[i..<min(len, e + 1)]) }
            i = e + 1
        }
        return sb ?? t
    }

    // ---------- ASS/SSA -> SRT ----------
    /// `(\d+):(\d{1,2}):(\d{1,2})(?:[.,](\d{1,3}))?` com matches() sobre o texto já aparado
    private static func assTime(_ s0: U16) -> Int64 {
        let s = J.trim(s0)
        let n = s.count
        var p = 0
        var h: Int64 = 0
        var nd = 0
        while p < n && isDigit(s[p]) { h = h &* 10 &+ Int64(s[p] - 48); p += 1; nd += 1 }
        if nd == 0 || p >= n || s[p] != 58 { return -1 }
        p += 1
        var m: Int64 = 0
        nd = 0
        while p < n && nd < 2 && isDigit(s[p]) { m = m * 10 + Int64(s[p] - 48); p += 1; nd += 1 }
        if nd == 0 || p >= n || s[p] != 58 { return -1 }
        p += 1
        var sec: Int64 = 0
        nd = 0
        while p < n && nd < 2 && isDigit(s[p]) { sec = sec * 10 + Int64(s[p] - 48); p += 1; nd += 1 }
        if nd == 0 { return -1 }
        var ms: Int64 = 0
        if p < n {
            if s[p] != 46 && s[p] != 44 { return -1 }
            p += 1
            var f = U16()
            while p < n && f.count < 3 && isDigit(s[p]) { f.append(s[p]); p += 1 }
            if f.isEmpty || p != n { return -1 }
            ms = fracMs(f)
        }
        return ((h &* 60 &+ m) &* 60 &+ sec) &* 1000 &+ ms
    }

    private static func fmtTime(_ ms: Int64) -> String {
        return J.pad(ms / 3600000, 2) + ":" + J.pad((ms / 60000) % 60, 2) + ":" + J.pad((ms / 1000) % 60, 2) + "," + J.pad(ms % 1000, 3)
    }

    /// remove os blocos {...} e troca \N, \n, \h do ASS
    private static func assBody(_ f: U16) -> U16 {
        var a = U16()
        var i = 0
        let n = f.count
        while i < n {
            if f[i] == 123 {
                var j = i + 1
                while j < n && f[j] != 125 { j += 1 }
                if j < n { i = j + 1; continue }
            }
            a.append(f[i]); i += 1
        }
        var b = U16()
        i = 0
        while i < a.count {
            if a[i] == 92 && i + 1 < a.count {
                let c = a[i + 1]
                if c == 78 || c == 110 { b.append(10); i += 2; continue }
                if c == 104 { b.append(32); i += 2; continue }
            }
            b.append(a[i]); i += 1
        }
        return J.trim(b)
    }

    static func assToSrt(_ t: U16) -> U16? {
        var iStart = 1, iEnd = 2, iText = 9, nf = 10
        var inEvents = false
        var times: [(Int64, Int64, Int)] = []
        var texts: [U16] = []
        for ln in J.split(t, 10) {
            let s = J.trim(ln)
            if let f0 = s.first, f0 == 91 {
                inEvents = J.startsWithIgnoreCase(s, "[Events]") && s.count == 8
                continue
            }
            if !inEvents { continue }
            if J.startsWithIgnoreCase(s, "Format:") {
                let f = J.split(Array(s[7...]), 44)
                nf = f.count
                for k in 0..<f.count {
                    let n = J.str(J.trim(f[k])).lowercased()
                    if n == "start" { iStart = k } else if n == "end" { iEnd = k } else if n == "text" { iText = k }
                }
            } else if J.startsWithIgnoreCase(s, "Dialogue:") {
                let f = J.split(Array(s[9...]), 44, limit: nf)
                if f.count <= max(iText, max(iStart, iEnd)) { continue }
                let a = assTime(f[iStart]), b = assTime(f[iEnd])
                if a < 0 || b < 0 { continue }
                let body = assBody(f[iText])
                if body.isEmpty { continue }
                times.append((a, b, times.count))
                texts.append(body)
            }
        }
        if times.count < 5 { return nil }
        let order = times.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.2 < $1.2 }
        var out = U16()
        var n = 1
        for tm in order {
            out += J.u16(String(n) + "\n" + fmtTime(tm.0) + " --> " + fmtTime(tm.1) + "\n")
            out += texts[tm.2]
            out += [10, 10]
            n += 1
        }
        return out
    }

    // ---------- idioma ----------
    private static func words(_ s: String) -> Set<U16> { return Set(s.split(separator: " ").map { Array($0.utf16) }) }
    private static let ptW = words("não você vocês estou então isso são também muito porque pra obrigado tá nós meu minha ele dela já até bem tudo aqui foi vai tem sim uma")
    private static let enW = words("the you and that what this with have are not but your i'm it's don't they there just know")
    private static let esW = words("el los las pero usted esto estoy yo muy gracias señor tengo qué cómo hay soy eres nosotros")

    /// confere se o texto é mesmo português (e não inglês, espanhol, galego...): palavras típicas de cada idioma e,
    /// como desempate, os sinais que só o espanhol/galego têm (ñ, ¿, ¡) contra os que só o português tem (ã, õ, ç)
    static func looksPortuguese(_ text: String) -> Bool { return looksPortuguese(J.u16(text)) }

    static func looksPortuguese(_ full: U16) -> Bool {
        var pt = 0, en = 0, es = 0, total = 0
        var distinct = Set<U16>()
        let s = full.count > 300000 ? Array(full[0..<300000]) : full
        // palavras: [\p{L}']+ (por ponto de código)
        var cur = U16()
        func flush() {
            if cur.isEmpty { return }
            let w = J.u16(J.str(cur).lowercased())
            total += 1
            if ptW.contains(w) { pt += 1; distinct.insert(w) }
            if enW.contains(w) { en += 1 }
            if esW.contains(w) { es += 1 }
            cur.removeAll(keepingCapacity: true)
        }
        var i = 0
        let n = s.count
        while i < n {
            let c = s[i]
            var isWord = false
            var step = 1
            if c == 39 { isWord = true }
            else if c >= 0xD800 && c <= 0xDBFF && i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF {
                let cp = 0x10000 + ((UInt32(c) - 0xD800) << 10) + (UInt32(s[i + 1]) - 0xDC00)
                if let sc = Unicode.Scalar(cp), J.isLetterScalar(sc) { isWord = true }
                step = 2
            } else if J.isLetter(c) { isWord = true }
            if isWord { for k in 0..<step { cur.append(s[i + k]) } } else { flush() }
            i += step
        }
        flush()
        if !(pt >= 3 && pt > 2 * max(en, es)) { return false }
        // italiano/galego têm "vai", "meu"... : português de verdade tem várias palavras típicas diferentes e em boa proporção (~10% das palavras)
        if distinct.count < 6 || Double(pt) < 0.03 * Double(total) { return false }
        var ptMark = 0, esMark = 0
        for c in s {
            if c == 0xE3 || c == 0xF5 || c == 0xC3 || c == 0xD5 || c == 0xE7 { ptMark += 1 }          // ã õ Ã Õ ç
            else if c == 0xF1 || c == 0xD1 || c == 0xBF || c == 0xA1 { esMark += 1 }                    // ñ Ñ ¿ ¡
        }
        return esMark <= ptMark
    }

    // ---------- fim da última fala ----------
    /// `-->\s*(?:(\d+):)?(\d{1,2}):(\d\d)[,.](\d{3})` : maior fim de fala (ms)
    static func lastCueEnd(_ text: String) -> Int64 { return lastCueEnd(J.u16(text)) }

    static func lastCueEnd(_ u: U16) -> Int64 {
        var maxV: Int64 = 0
        let n = u.count
        var i = 0
        while i + 3 <= n {
            if u[i] == 45 && u[i + 1] == 45 && u[i + 2] == 62 {
                var p = i + 3
                while p < n && (u[p] == 32 || u[p] == 9 || u[p] == 10 || u[p] == 11 || u[p] == 12 || u[p] == 13) { p += 1 }
                if let t = timeAt(u, p, n, canon: true) {
                    // o padrão exige \d\d nos segundos (não 1 dígito) e 3 dígitos de fração: igual ao `canon`
                    let v = ((t.h &* 60 &+ t.m) &* 60 &+ t.s) &* 1000 &+ fracMs(t.f)
                    if v > maxV { maxV = v }
                    i = t.end
                    continue
                }
            }
            i += 1
        }
        return maxV
    }
}
