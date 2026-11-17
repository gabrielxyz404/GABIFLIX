// Sessão de um alinhamento e plano de janelas (porte 1:1 de AudioSyncSession.java).
import Foundation

final class AudioSyncSession {
    static let maxRounds = 6

    private let cues: [AudioCue]
    private let durationMs: Int
    private let params: AudioAligner.Params
    private var windows: [AudioWindow] = []
    private(set) var rounds = 0

    init(cues: [AudioCue], mediaDurationMs: Int, params: AudioAligner.Params = AudioAligner.Params()) {
        self.cues = cues
        self.durationMs = mediaDurationMs
        self.params = params
    }

    /** plano inicial: n janelas de lenMs espalhadas entre 15% e 85% do filme; devolve [inicioMs, duracaoMs] (múltiplos de 10 ms) */
    static func plan(_ durMs: Int, _ n: Int, _ lenMs: Int) -> [[Int]] {
        var w = [[Int]](repeating: [0, 0], count: n)
        for i in 0..<n {
            let fr = n == 1 ? 0.5 : 0.15 + 0.70 * Double(i) / Double(n - 1)
            var st = jlong(Double(durMs) * fr - Double(lenMs) / 2.0)
            st = max(0, min(st, durMs - lenMs))
            w[i][0] = st / 10 * 10
            w[i][1] = lenMs
        }
        return w
    }

    /**
     * plano inicial guiado pela legenda: n janelas de lenMs nos trechos onde a legenda tem MAIS fala (densidade suavizada em +-2 min),
     * afastadas entre si, fora dos 4% iniciais e finais. Sem fala suficiente (legenda curta) cai no plano uniforme.
     */
    static func planDense(_ cues: [AudioCue], _ durMs: Int, _ n: Int, _ lenMs: Int) -> [[Int]] {
        let bin = 5000
        let nb = durMs / bin + 1
        var sp = [Double](repeating: 0, count: nb)
        for c in cues {
            if !c.speech { continue }
            let e = c.speechEnd(true)
            var t = c.startMs
            while t < e && t < durMs {
                sp[t / bin] += Double(min(bin, e - t)) / Double(bin)
                t += bin
            }
        }
        let half = 24   // +-2 min
        var pre = [Double](repeating: 0, count: nb + 1)
        for i in 0..<nb { pre[i + 1] = pre[i] + sp[i] }
        let wb = lenMs / bin
        var lo = max(0, durMs / 25), hi = durMs - durMs / 25
        if hi - lo < lenMs * n { lo = 0; hi = durMs }
        let spacing = min(360_000, max(lenMs, (hi - lo - lenMs) / max(1, n)))
        var w = [[Int]](repeating: [0, 0], count: n)
        var got = 0
        while got < n {
            var best = -1.0
            var bi = -1
            var i = lo / bin
            while (i + wb) * bin <= hi && i + wb <= nb {
                defer { i += 1 }
                var bad = false
                var k = 0
                while k < got && !bad {
                    if abs(i * bin - w[k][0]) < spacing { bad = true }
                    k += 1
                }
                if bad { continue }
                let a = max(0, i - half), b = min(nb, i + wb + half)
                let d = (pre[b] - pre[a]) / Double(b - a)
                if d > best { best = d; bi = i }
            }
            if bi < 0 || best < 0.15 { break }
            w[got][0] = bi * bin / 10 * 10
            w[got][1] = lenMs
            got += 1
        }
        if got < n { return plan(durMs, n, lenMs) }
        w.sort { $0[0] < $1[0] }
        return w
    }

    func addWindow(_ w: AudioWindow) { windows.append(w) }

    var windowCount: Int { return windows.count }

    /** resolve com as janelas atuais; se pedir mais áudio e ainda há rodadas, a próxima chamada deve vir depois de addWindow */
    func solve() -> AudioResult {
        let r = AudioAligner.solve(cues, windows, durationMs, params)
        r.rounds = rounds
        if let m = r.more, !m.isEmpty {
            if rounds >= AudioSyncSession.maxRounds { r.more = nil } else { rounds += 1 }
        }
        return r
    }
}
