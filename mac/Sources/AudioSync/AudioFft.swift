// FFT complex radix-2 iterativa, in-place (porte 1:1 de Fft.java). Sem dependências.
import Foundation

final class AudioFft {
    let n: Int
    private let cosT: [Double], sinT: [Double]
    private let rev: [Int]

    init(_ n: Int) {
        precondition(n >= 2 && (n & (n - 1)) == 0, "n deve ser potência de 2")
        self.n = n
        var c = [Double](repeating: 0, count: n / 2), s = [Double](repeating: 0, count: n / 2)
        for i in 0..<(n / 2) {
            let a = -2.0 * Double.pi * Double(i) / Double(n)
            c[i] = cos(a)
            s[i] = sin(a)
        }
        cosT = c
        sinT = s
        var bits = 0
        while (1 << bits) < n { bits += 1 }
        var r = [Int](repeating: 0, count: n)
        for i in 0..<n {
            var v = 0, x = i
            for _ in 0..<bits { v = (v << 1) | (x & 1); x >>= 1 }
            r[i] = v
        }
        rev = r
    }

    /** transformada direta (inverse=false) ou inversa SEM normalização 1/n (inverse=true) */
    func transform(_ re: inout [Double], _ im: inout [Double], _ inverse: Bool) {
        let n = self.n
        re.withUnsafeMutableBufferPointer { re in
            im.withUnsafeMutableBufferPointer { im in
                cosT.withUnsafeBufferPointer { cosT in
                    sinT.withUnsafeBufferPointer { sinT in
                        for i in 0..<n {
                            let j = rev[i]
                            if j > i {
                                var t = re[i]; re[i] = re[j]; re[j] = t
                                t = im[i]; im[i] = im[j]; im[j] = t
                            }
                        }
                        var len = 2
                        while len <= n {
                            let half = len >> 1, step = n / len
                            var i = 0
                            while i < n {
                                var j = 0, k = 0
                                while j < half {
                                    let wr = cosT[k], wi = inverse ? -sinT[k] : sinT[k]
                                    let a = i + j, b = a + half
                                    let xr = re[b] * wr - im[b] * wi
                                    let xi = re[b] * wi + im[b] * wr
                                    re[b] = re[a] - xr; im[b] = im[a] - xi
                                    re[a] += xr; im[a] += xi
                                    j += 1; k += step
                                }
                                i += len
                            }
                            len <<= 1
                        }
                    }
                }
            }
        }
    }

    static func nextPow2(_ v: Int) -> Int {
        var p = 1
        while p < v { p <<= 1 }
        return p
    }
}
