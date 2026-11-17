// VAD "cuidadoso" em DSP puro (porte 1:1 de DspVad.java): reserva do Silero quando o ONNX Runtime não carrega.
// Espectro de 25 ms em 8 bandas, SNR contra piso de ruído adaptativo, planicidade, fluxo, centroide, harmonicidade, modulação silábica
// e contexto temporal; um MLP minúsculo (modelo de reserva, o mesmo do app Android) dá a probabilidade de fala a cada 10 ms.
import Foundation

final class AudioDspVad: AudioVad {
    static let NF = 22
    static let bandsHz: [[Int]] = [[100, 300], [300, 500], [500, 800], [800, 1200], [1200, 1800], [1800, 2600], [2600, 3600], [3600, 5000]]

    /** MLP: tanh na camada oculta, sigmoide na saída */
    struct Model {
        var mean = [Float](repeating: 0, count: AudioDspVad.NF)
        var std = [Float](repeating: 0, count: AudioDspVad.NF)
        var w1: [[Float]] = []
        var b1: [Float] = []
        var w2: [Float] = []
        var b2: Float = 0
        var hidden = 0

        func predict(_ x: [Float]) -> Float {
            var out = b2
            for h in 0..<hidden {
                var s = b1[h]
                let w = w1[h]
                for i in 0..<AudioDspVad.NF { s += w[i] * ((x[i] - mean[i]) / std[i]) }
                out += w2[h] * Float(tanh(Double(s)))
            }
            return Float(1.0 / (1.0 + exp(-Double(out))))
        }
    }

    /** modelo simples escolhido à mão (o que o app Android usa) */
    static func fallback() -> Model {
        var m = Model()
        m.hidden = 1
        for i in 0..<NF { m.mean[i] = 0; m.std[i] = 1 }
        var w = [Float](repeating: 0, count: NF)
        w[8] = 0.12     // SNR da banda de fala
        w[12] = 1.5     // harmonicidade
        w[15] = 1.0     // modulação silábica
        w[10] = -1.0    // planicidade
        m.w1 = [w]
        m.b1 = [-1.5]
        m.w2 = [3]
        m.b2 = -0.5
        return m
    }

    private let model: Model
    let name: String

    init(model: Model = AudioDspVad.fallback(), name: String = "dsp(reserva)") {
        self.model = model
        self.name = name
    }

    func speechProb(_ pcm: [Int16], _ n: Int, _ rate: Int) -> [Float] {
        let f = AudioDspVad.features(pcm, n, rate)
        var p = [Float](repeating: 0, count: f.count)
        for t in 0..<f.count { p[t] = model.predict(f[t]) }
        return p
    }

    // ------------------------------------------------------------------ atributos
    /** matriz [quadro][NF] */
    static func features(_ pcm: [Int16], _ n: Int, _ rate: Int) -> [[Float]] {
        let hop = rate / 100, win = rate / 40
        let T = n / hop
        var out = [[Float]](repeating: [Float](repeating: 0, count: NF), count: T)
        if T < 5 { return out }
        let N = 512
        let fft = AudioFft(N)
        var re = [Double](repeating: 0, count: N), im = [Double](repeating: 0, count: N)
        var hann = [Double](repeating: 0, count: win)
        for i in 0..<win { hann[i] = 0.5 - 0.5 * cos(2 * Double.pi * (Double(i) + 0.5) / Double(win)) }
        let binHz = Double(rate) / Double(N)
        let nb = bandsHz.count
        var logE = [[Double]](repeating: [Double](repeating: 0, count: T), count: nb)
        var spLog = [Double](repeating: 0, count: T), loLog = [Double](repeating: 0, count: T), flat = [Double](repeating: 0, count: T)
        var cent = [Double](repeating: 0, count: T), lohi = [Double](repeating: 0, count: T), zcr = [Double](repeating: 0, count: T)
        var harm = [Double](repeating: 0, count: T), lowRatio = [Double](repeating: 0, count: T)
        var flux = [Double](repeating: 0, count: T)
        let kLo = jround(300 / binHz), kHi = jround(3400 / binHz), kFlat0 = jround(200 / binHz), kFlat1 = jround(4000 / binHz)
        let k1k = jround(1000 / binHz), k100 = jround(100 / binHz)
        // 8 kHz para a harmonicidade (média de pares: filtro passa-baixa simples)
        let dec = rate / 8000
        let win8 = win / dec
        var re8 = [Double](repeating: 0, count: N), im8 = [Double](repeating: 0, count: N)
        var hann8 = [Double](repeating: 0, count: win8)
        for i in 0..<win8 { hann8[i] = 0.5 - 0.5 * cos(2 * Double.pi * (Double(i) + 0.5) / Double(win8)) }
        var cur = [Double](repeating: 0, count: N / 2 + 1)
        var prev = [Double](repeating: 0, count: N / 2 + 1)
        for t in 0..<T {
            let c = t * hop + hop / 2
            let s0 = c - win / 2
            for i in 0..<N { re[i] = 0; im[i] = 0 }
            var zc = 0
            var last = 0.0
            for i in 0..<win {
                let idx = s0 + i
                let v: Double = (idx >= 0 && idx < n) ? Double(pcm[idx]) / 32768.0 : 0.0
                if i > 0 && ((v >= 0) != (last >= 0)) { zc += 1 }
                last = v
                re[i] = v * hann[i]
            }
            zcr[t] = Double(zc) / Double(win)
            fft.transform(&re, &im, false)
            for k in 0...(N / 2) { cur[k] = re[k] * re[k] + im[k] * im[k] }
            for b in 0..<nb {
                let a = max(1, jround(Double(bandsHz[b][0]) / binHz)), z = min(N / 2, jround(Double(bandsHz[b][1]) / binHz))
                var e = 0.0
                var k = a
                while k < z { e += cur[k]; k += 1 }
                logE[b][t] = 10 * log10(e + 1e-12)
            }
            var esp = 0.0, elo = 0.0, cs = 0.0
            var k = kLo
            while k < kHi { esp += cur[k]; cs += cur[k] * (Double(k) * binHz); k += 1 }
            k = k100
            while k < kLo { elo += cur[k]; k += 1 }
            var e1 = 0.0, e2 = 0.0
            k = kLo
            while k < k1k { e1 += cur[k]; k += 1 }
            k = k1k
            while k < kHi { e2 += cur[k]; k += 1 }
            spLog[t] = 10 * log10(esp + 1e-12)
            loLog[t] = 10 * log10(elo + 1e-12)
            cent[t] = log(cs / (esp + 1e-12) + 1.0)
            lohi[t] = 10 * log10((e1 + 1e-12) / (e2 + 1e-12))
            lowRatio[t] = loLog[t] - spLog[t]
            // planicidade (média geométrica / aritmética) em 200-4000 Hz
            var lg = 0.0, ar = 0.0
            var cnt = 0
            k = kFlat0
            while k < kFlat1 { lg += log(cur[k] + 1e-12); ar += cur[k]; cnt += 1; k += 1 }
            flat[t] = exp(lg / Double(cnt)) / (ar / Double(cnt) + 1e-12)
            // fluxo: diferença do espectro normalizado
            var fl = 0.0
            if t > 0 {
                var ns = 0.0, np = 0.0
                k = kLo
                while k < kHi { ns += cur[k]; np += prev[k]; k += 1 }
                k = kLo
                while k < kHi {
                    let d = cur[k] / (ns + 1e-12) - prev[k] / (np + 1e-12)
                    fl += d * d
                    k += 1
                }
            }
            flux[t] = fl.squareRoot() * 10
            for i in 0..<cur.count { prev[i] = cur[i] }
            // harmonicidade a 8 kHz
            for i in 0..<N { re8[i] = 0; im8[i] = 0 }
            for i in 0..<win8 {
                var v = 0.0
                for j in 0..<dec {
                    let idx = s0 + i * dec + j
                    v += (idx >= 0 && idx < n) ? Double(pcm[idx]) / 32768.0 : 0.0
                }
                v /= Double(dec)
                re8[i] = v * hann8[i]
            }
            fft.transform(&re8, &im8, false)
            for kk in 0..<N { re8[kk] = re8[kk] * re8[kk] + im8[kk] * im8[kk]; im8[kk] = 0 }
            fft.transform(&re8, &im8, true)
            let r0 = re8[0] + 1e-12
            var best = 0.0
            for lag in 20...114 {
                let r = re8[lag] / r0
                if r > best { best = r }
            }
            harm[t] = best
        }
        // piso de ruído por banda (percentil 15 em ±3 s, passo 0,5 s, interpolado)
        var floorB = [[Double]](repeating: [], count: nb + 1)
        for b in 0...nb {
            let src = b < nb ? logE[b] : spLog
            floorB[b] = percentileFloor(src, 300, 50, 0.15)
        }
        var snr = [[Double]](repeating: [Double](repeating: 0, count: T), count: nb + 1)
        for b in 0...nb {
            let src = b < nb ? logE[b] : spLog
            for t in 0..<T { snr[b][t] = max(-10, min(60, src[t] - floorB[b][t])) }
        }
        let p95 = percentile(spLog, 0.95)
        // contexto temporal
        let snrSp = snr[nb]
        let mean25 = boxMean(snrSp, 12), mean50 = boxMean(snrSp, 50)
        let harm25 = boxMean(harm, 12)
        var sq = [Double](repeating: 0, count: T)
        for t in 0..<T { sq[t] = snrSp[t] * snrSp[t] }
        let mean50sq = boxMean(sq, 50)
        let max25 = boxMax(snrSp, 12)
        let mod = modulation(snrSp, 50)
        for t in 0..<T {
            var o = [Float](repeating: 0, count: NF)
            for b in 0..<nb { o[b] = Float(snr[b][t]) }
            o[8] = Float(snrSp[t])
            o[9] = Float(spLog[t] - p95)
            o[10] = Float(flat[t])
            o[11] = Float(flux[t])
            o[12] = Float(harm[t])
            o[13] = Float(cent[t])
            o[14] = Float(lohi[t])
            o[15] = Float(mod[t])
            o[16] = Float(mean25[t])
            o[17] = Float(max(0, mean50sq[t] - mean50[t] * mean50[t]).squareRoot())
            o[18] = Float(harm25[t])
            o[19] = Float(max25[t])
            o[20] = Float(zcr[t])
            o[21] = Float(lowRatio[t])
            out[t] = o
        }
        return out
    }

    /** piso adaptativo: percentil p dos valores em ±halfWin quadros, calculado a cada step quadros e interpolado */
    static func percentileFloor(_ v: [Double], _ halfWin: Int, _ step: Int, _ p: Double) -> [Double] {
        let T = v.count
        let m = (T + step - 1) / step + 1
        var val = [Double](repeating: 0, count: m)
        for i in 0..<m {
            let c = min(T - 1, i * step)
            let a = max(0, c - halfWin), z = min(T - 1, c + halfWin)
            let len = z - a + 1
            let buf = v[a..<(a + len)].sorted()
            val[i] = buf[jint((p * Double(len - 1)).rounded(.down))]
        }
        var o = [Double](repeating: 0, count: T)
        for t in 0..<T {
            let q = Double(t) / Double(step)
            let i = jint(q.rounded(.down))
            let f = q - Double(i)
            let a = val[min(i, m - 1)], b = val[min(i + 1, m - 1)]
            o[t] = a * (1 - f) + b * f
        }
        return o
    }

    static func percentile(_ v: [Double], _ p: Double) -> Double {
        let c = v.sorted()
        return c[jint((p * Double(c.count - 1)).rounded(.down))]
    }

    static func boxMean(_ v: [Double], _ r: Int) -> [Double] {
        let T = v.count
        var ps = [Double](repeating: 0, count: T + 1)
        for i in 0..<T { ps[i + 1] = ps[i] + v[i] }
        var o = [Double](repeating: 0, count: T)
        for t in 0..<T {
            let a = max(0, t - r), z = min(T - 1, t + r)
            o[t] = (ps[z + 1] - ps[a]) / Double(z - a + 1)
        }
        return o
    }

    static func boxMax(_ v: [Double], _ r: Int) -> [Double] {
        let T = v.count
        var o = [Double](repeating: 0, count: T)
        for t in 0..<T {
            let a = max(0, t - r), z = min(T - 1, t + r)
            var m = -1e9
            for i in a...z where v[i] > m { m = v[i] }
            o[t] = m
        }
        return o
    }

    /** energia de modulação em 2-8 Hz do envelope (quadros de 10 ms) relativa à de 0,5-20 Hz, janela de ±r quadros */
    static func modulation(_ env: [Double], _ r: Int) -> [Double] {
        let T = env.count
        let W = 2 * r + 1
        var mod = [Double](repeating: 0, count: T)
        let fr: [Double] = [2, 3, 4, 5, 6, 8, 0.7, 1.2, 10, 14, 18]
        var cs = [[Double]](repeating: [Double](repeating: 0, count: W), count: fr.count)
        var sn = [[Double]](repeating: [Double](repeating: 0, count: W), count: fr.count)
        for k in 0..<fr.count {
            for i in 0..<W {
                let w = 0.5 - 0.5 * cos(2 * Double.pi * (Double(i) + 0.5) / Double(W))
                let ang = 2 * Double.pi * fr[k] * (Double(i) * 0.01)
                cs[k][i] = cos(ang) * w
                sn[k][i] = sin(ang) * w
            }
        }
        var seg = [Double](repeating: 0, count: W)
        var pw = [Double](repeating: 0, count: fr.count)
        for t in 0..<T {
            var mean = 0.0
            for i in 0..<W {
                var idx = t - r + i
                idx = idx < 0 ? 0 : (idx >= T ? T - 1 : idx)
                seg[i] = env[idx]
                mean += seg[i]
            }
            mean /= Double(W)
            for k in 0..<fr.count {
                var a = 0.0, b = 0.0
                let ck = cs[k], sk = sn[k]
                for i in 0..<W { let v = seg[i] - mean; a += v * ck[i]; b += v * sk[i] }
                pw[k] = a * a + b * b
            }
            let sp = pw[0] + pw[1] + pw[2] + pw[3] + pw[4] + pw[5]
            let all = sp + pw[6] + pw[7] + pw[8] + pw[9] + pw[10] + 1e-9
            mod[t] = sp / all
        }
        return mod
    }
}
