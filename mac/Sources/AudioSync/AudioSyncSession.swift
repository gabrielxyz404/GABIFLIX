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

    /**
     * plano espalhado pelo filme (para ver a deriva da escala): n janelas de lenMs, uma em cada faixa de ~1/n do filme (centros em 10%, 50%, 90% para n = 3;
     * 10%, 30%, 50%, 70%, 90% para n = 5), cada uma no trecho de MAIOR densidade de falas da sua faixa. Porte de planSpread.
     */
    static func planSpread(_ cues: [AudioCue], _ durMs: Int, _ n: Int, _ lenMs: Int) -> [[Int]] {
        if n < 2 || durMs < lenMs * n { return planDense(cues, durMs, n, lenMs) }
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
        let half = 24
        var pre = [Double](repeating: 0, count: nb + 1)
        for i in 0..<nb { pre[i + 1] = pre[i] + sp[i] }
        let wb = lenMs / bin
        let spacing = 0.80 / Double(n - 1), hw = 0.45 * spacing
        var w = [[Int]](repeating: [0, 0], count: n)
        for z in 0..<n {
            let fc = 0.10 + spacing * Double(z)
            let lo = max(jlong(Double(durMs) * 0.04), jlong(Double(durMs) * (fc - hw)) - lenMs / 2)
            let hi = min(durMs - durMs / 25, jlong(Double(durMs) * (fc + hw)) + lenMs / 2)
            var best = -1.0
            var bi = -1
            var i = lo / bin
            while (i + wb) * bin <= hi && i + wb <= nb {
                var clash = false
                for k in 0..<z where abs(i * bin - w[k][0]) < lenMs + bin { clash = true }   // sem sobrepor uma janela já escolhida
                if !clash {
                    let a = max(0, i - half), b = min(nb, i + wb + half)
                    let d = (pre[b] - pre[a]) / Double(b - a)
                    if d > best { best = d; bi = i }
                }
                i += 1
            }
            if bi < 0 || best < 0.05 { return planDense(cues, durMs, n, lenMs) }
            w[z][0] = bi * bin / 10 * 10
            w[z][1] = lenMs
        }
        w.sort { $0[0] < $1[0] }
        return w
    }

    /** união dos dois planos: as janelas densas primeiro e, depois, as espalhadas que não se sobrepõem a uma já escolhida. Porte de planUnion. */
    static func planUnion(_ dense: [[Int]], _ spread: [[Int]], _ lenMs: Int) -> [[Int]] {
        var out = dense
        for s in spread {
            var clash = false
            for o in out where abs(s[0] - o[0]) < lenMs { clash = true }
            if !clash { out.append(s) }
        }
        return out
    }

    /**
     * plano "perto de onde se está a ver" (rede lenta ou não): n janelas de lenMs à frente da posição do filme (a primeira logo depois de posMs + 5 s, as outras
     * espaçadas de gapMs), cada uma no trecho de MAIS fala dentro de +-45 s do seu ponto. É o áudio mais barato de obter: o que o player está a baixar agora
     * (servidor de torrent, debrid) e sem saltos para o fim do arquivo. Perto do fim do filme o plano recua para caber.
     */
    static func planNear(_ cues: [AudioCue], _ durMs: Int, _ n: Int, _ lenMs: Int, _ posMs: Int, _ gapMs: Int) -> [[Int]] {
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
        var pre = [Double](repeating: 0, count: nb + 1)
        for i in 0..<nb { pre[i + 1] = pre[i] + sp[i] }
        let wb = lenMs / bin
        let hiLimit = durMs - durMs / 25
        var first = max(posMs + 5000, durMs / 25)
        let over = first + (n - 1) * gapMs + lenMs - hiLimit
        if over > 0 { first = max(durMs / 25, first - over) }
        var w = [[Int]](repeating: [0, 0], count: n)
        for z in 0..<n {
            let anchor = first + z * gapMs
            var lo = max(z == 0 ? posMs + 5000 : 0, anchor - 45000)
            let hi = min(hiLimit - lenMs, anchor + 45000)
            lo = max(lo, 0)
            var best = -1.0
            var bs = max(0, min(anchor, durMs - lenMs))
            var s0 = lo / bin * bin
            while s0 <= hi {
                defer { s0 += bin }
                var clash = false
                for k in 0..<z where abs(s0 - w[k][0]) < lenMs + bin { clash = true }
                if clash || s0 < lo { continue }
                let i = s0 / bin
                if i + wb > nb { break }
                let d = (pre[i + wb] - pre[i]) / Double(wb)
                if d > best { best = d; bs = s0 }
            }
            w[z][0] = bs / 10 * 10
            w[z][1] = lenMs
        }
        return w
    }

    /**
     * O laço do app com TEMPO LIMITADO e saída antecipada (rede lenta): primeiro as 3 janelas "perto de onde se está a ver" (planNear); resolve; se o alinhador
     * aceita, acabou (3 janelas bastam: é o que o plano antigo, "denso", já fazia). Se não aceita (ou pede prova de deriva), captura as janelas extras longe
     * do ponto (as mais afastadas primeiro), resolvendo de novo a cada uma, até aceitar ou até stop() mandar parar (o tempo acabou). Uma janela que falha ou
     * que não cabe no tempo NÃO deita fora as que já vieram: resolve com o que há (se houver pelo menos 2). Devolve nil só se não deu para ter 2 janelas.
     * Plano B igual ao de analyze (motor de antes sobre as 3 primeiras janelas).
     */
    static func analyzeNear(_ cues: [AudioCue], _ durMs: Int, _ lenMs: Int, _ posMs: Int, _ gapMs: Int, _ src: Source, _ P: AudioAligner.Params,
                            stop: () -> Bool, prefetch: ([[Int]]) -> Void = { _ in }, used: inout [AudioWindow], stats: inout NearStats) -> AudioResult? {
        let near = planNear(cues, durMs, 3, lenMs, posMs, gapMs)
        prefetch(near)
        let ses = AudioSyncSession(cues: cues, mediaDurationMs: durMs, params: P)
        var all: [AudioWindow] = []
        stats.planned = near.count
        for p in near {
            if stop() { stats.stoppedEarly = true; break }
            guard let w = src(p[0], p[1], all.count, near.count) else { stats.failed += 1; break }
            ses.addWindow(w)
            all.append(w)
        }
        if all.count < 2 { used = all; return nil }
        var r = ses.solve()
        stats.solves += 1
        stats.stage1Accepted = r.accepted && (r.driftMore ?? []).isEmpty
        // etapa 2: janelas longe do ponto (as pedidas pela prova de deriva, senão as espalhadas), da mais afastada para a mais próxima
        if !stats.stage1Accepted && !stats.stoppedEarly && stats.failed == 0 {
            let cx = all.map { $0.startMs }.reduce(0, +) / max(1, all.count)
            var far = planUnion(planSpread(cues, durMs, 6, lenMs), planDense(cues, durMs, 3, lenMs), lenMs)
            far = far.filter { f in !all.contains(where: { abs($0.startMs - f[0]) < lenMs }) }
            far.sort { abs($0[0] - cx) > abs($1[0] - cx) }
            var queue = far
            while !(r.accepted && (r.driftMore ?? []).isEmpty), ses.windowCount < P.maxDriftWindows {
                if stop() { stats.stoppedEarly = true; break }
                var next: [Int]? = nil
                if let more = r.driftMore, let m = more.first(where: { f in !all.contains(where: { abs($0.startMs - f[0]) < lenMs / 2 }) }) { next = m }
                else if !queue.isEmpty { next = queue.removeFirst() }
                guard let p = next else { break }
                guard let w = src(p[0], p[1], all.count, max(all.count + 1, 6)) else { stats.failed += 1; break }
                ses.addWindow(w)
                all.append(w)
                r = ses.solve()
                stats.solves += 1
            }
        }
        r.windows = ses.windowCount
        let dense = 3
        if all.count >= dense && (!r.accepted || (!r.driftScale && abs(r.scale - 1.0) > 1e-9)) {
            var L = AudioAligner.Params()
            L.legacy = true
            let bs = AudioSyncSession(cues: cues, mediaDurationMs: durMs, params: L)
            for i in 0..<dense { bs.addWindow(all[i]) }
            let b = bs.solve()
            if b.accepted && AudioAligner.verifyModel(cues, all, b.scale, b.rawOffsetMs, P) {
                let agree = r.accepted && abs(b.scale - r.scale) < 1e-6 && abs(b.rawOffsetMs - r.rawOffsetMs) < 400
                if !agree {
                    b.planB = true
                    b.windows = all.count
                    b.driftNote = "plano B: motor anterior sobre as \(dense) primeiras janelas" + (r.accepted ? " (o ajuste de todas as janelas discordou de uma escala de fps comum)" : " (o ajuste de todas as janelas não passou: " + r.reason.trimmingCharacters(in: .whitespaces) + ")")
                    b.wins = r.wins
                    r = b
                }
            }
        }
        used = all
        return r
    }

    struct NearStats {
        var planned = 0, failed = 0, solves = 0
        var stoppedEarly = false
        var stage1Accepted = false
    }

    /** de onde vem o áudio de cada janela: o app captura (VLCKit) e passa o VAD; os testes leem de um arquivo. nil = falhou/cancelado */
    typealias Source = (_ startMs: Int, _ lenMs: Int, _ index: Int, _ total: Int) -> AudioWindow?

    /**
     * O laço completo do app (porte de AudioSyncSession.analyze): 3 janelas espalhadas pelo filme (planSpread; planDense se spread = false), resolve; se as janelas
     * confiáveis sugerem deriva mas a prova ainda não fecha, captura as janelas extras que o alinhador propõe (até maxDriftWindows no total) e resolve de novo.
     * Devolve nil se uma captura falhou. As janelas usadas voltam em used.
     */
    static func analyze(_ cues: [AudioCue], _ durMs: Int, _ nWin: Int, _ lenMs: Int, _ src: Source, _ P: AudioAligner.Params, spread: Bool, dense: Int, used: inout [AudioWindow]) -> AudioResult? {
        var plan = spread ? planSpread(cues, durMs, nWin, lenMs) : planDense(cues, durMs, nWin, lenMs)
        if dense > 0 { plan = planUnion(planDense(cues, durMs, dense, lenMs), plan, lenMs) }
        let ses = AudioSyncSession(cues: cues, mediaDurationMs: durMs, params: P)
        var all: [AudioWindow] = []
        var idx = 0
        for p in plan {
            guard let w = src(p[0], p[1], idx, plan.count) else { return nil }
            idx += 1
            ses.addWindow(w)
            all.append(w)
        }
        var r = ses.solve()
        var extra = 0
        while let more = r.driftMore, !more.isEmpty, extra < 2, ses.windowCount < P.maxDriftWindows {
            extra += 1
            let total = ses.windowCount + more.count
            for p in more {
                if ses.windowCount >= P.maxDriftWindows { break }
                guard let w = src(p[0], p[1], ses.windowCount, total) else { return nil }
                ses.addWindow(w)
                all.append(w)
            }
            r = ses.solve()
        }
        r.windows = ses.windowCount
        // plano B: o motor de ANTES (3 janelas densas, uma resolução, sem prova de deriva) quando o ajuste de todas as janelas não passou, ou quando achou uma escala
        // de fps comum (25/23,976 etc.) sem ser deriva provada: o resultado nunca é pior que o do motor anterior
        if dense > 0 && all.count >= dense && (!r.accepted || (!r.driftScale && abs(r.scale - 1.0) > 1e-9)) {
            var L = AudioAligner.Params()
            L.legacy = true
            let bs = AudioSyncSession(cues: cues, mediaDurationMs: durMs, params: L)
            for i in 0..<dense { bs.addWindow(all[i]) }
            let b = bs.solve()
            if b.accepted && AudioAligner.verifyModel(cues, all, b.scale, b.rawOffsetMs, P) {
                let agree = r.accepted && abs(b.scale - r.scale) < 1e-6 && abs(b.rawOffsetMs - r.rawOffsetMs) < 400
                if !agree {
                    b.planB = true
                    b.windows = all.count
                    b.driftNote = "plano B: motor anterior sobre as \(dense) janelas densas" + (r.accepted ? " (o ajuste de todas as janelas discordou de uma escala de fps comum)" : " (o ajuste de todas as janelas não passou: " + r.reason.trimmingCharacters(in: .whitespaces) + ")")
                    b.wins = r.wins
                    r = b
                }
            }
        }
        used = all
        return r
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
