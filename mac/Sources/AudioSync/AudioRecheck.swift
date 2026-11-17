// Reverificação durante a reprodução (porte 1:1 de Recheck.java). A cada ~10 min o app captura 90 s de áudio na posição atual
// e chama check(). Regras: atraso manual vence sempre; o modelo atual que ainda explica o áudio => KEEP (caminho barato e comum);
// só com evidência forte de um salto a busca larga acha o novo deslocamento; a mudança vale a partir da próxima pausa entre falas.
import Foundation

enum AudioRecheck {
    enum Verdict { case keep, proposed, change }

    final class Out {
        var verdict = Verdict.keep
        var reason = ""
        /** novo deslocamento (ms) para a escala atual, quando PROPOSED/CHANGE */
        var newOffsetMs = 0.0
        /** a partir de qual fala (início, tempo da LEGENDA) o novo deslocamento vale */
        var applyFromSubMs = Int.max
        var nccAtCurrent = 0.0, z = 0.0
        var tookMs = 0
    }

    struct Params {
        var okNcc = 0.20
        var okDeltaMs = 500.0
        var minCueCoverage = 0.05
        var searchMs = 300_000
        var minZ = 7.0, minNcc = 0.25
        var strongZ = 11.0, strongNcc = 0.40
        var minJumpMs = 1500.0
        var sameProposalMs = 400.0
        var minGapMs = 500
    }

    static func check(_ cues: [AudioCue], _ wins: [AudioWindow], _ current: AudioResult?, _ playMs: Int, _ mediaDurationMs: Int,
                      _ manualLocked: Bool, _ prev: Out?, _ AP: AudioAligner.Params, _ P: Params) -> Out {
        let t0 = DispatchTime.now().uptimeNanoseconds
        let o = Out()
        defer { o.tookMs = Int((DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000) }
        if manualLocked { o.reason = "atraso manual do usuário"; return o }
        guard let current = current, current.accepted else { o.reason = "sem ajuste automático em uso"; return o }
        // modelo (escala, deslocamento) do trecho em uso na posição atual
        var a = current.scale, b = current.offsetMs
        if let segs = current.segments {
            let sub = (Double(playMs) - b) / a
            for s in segs where sub >= Double(s.fromSubMs) { a = s.scale; b = s.offsetMs }
        }
        let fine = AudioAligner.fineMask(cues, AP.trimCues)
        // 1) o modelo atual ainda explica o áudio?
        var bestNcc = 0.0
        var usable = false
        for w in wins {
            let ps = AudioAligner.smooth(w.p, AP.smoothMs)
            let f = AudioAligner.fineWindow(ps, w.startMs, fine, a, b, 150)
            var cov = 0.0
            var j = 0
            while j < ps.count {
                if AudioAligner.cov(fine, (Double(w.startMs) + 10.0 * Double(j) - b) / a) > 0.5 { cov += 1 }
                j += 10
            }
            if f.speechFrac >= 0.03 && cov * 10.0 / Double(ps.count) >= P.minCueCoverage {
                usable = true
                if f.ncc > bestNcc { bestNcc = f.ncc }
                if f.ncc >= P.okNcc && abs(f.bestDelta) <= P.okDeltaMs { o.nccAtCurrent = f.ncc; o.reason = "ok"; return o }
            }
        }
        o.nccAtCurrent = bestNcc
        if !usable { o.reason = "pouca fala/legenda na janela"; return o }
        // 2) busca larga só com a escala atual: aplica o modelo atual às falas e procura só um deslocamento adicional
        var warped: [AudioCue] = []
        warped.reserveCapacity(cues.count)
        for c in cues {
            warped.append(AudioCue(startMs: jround(a * Double(c.startMs) + b), endMs: jround(a * Double(c.endMs) + b), chars: c.chars, speech: c.speech))
        }
        var q = AudioAligner.Params()
        q.knownScales = [1.0]
        q.maxAlpha = 1e-4
        q.maxOffsetMs = Double(P.searchMs)
        q.acceptMinZ = 0
        q.minZ = P.minZ; q.minNcc = P.minNcc; q.minMargin = 0.10; q.minAgreeFrac = 0.6
        q.randomHyps = 1500
        q.minBlocks = 2
        let r = AudioAligner.solve(warped, wins, mediaDurationMs, q)
        o.z = r.z
        if !r.accepted || abs(r.scale - 1.0) > 1e-9 { o.reason = "sem evidência de salto (" + r.reason.trimmingCharacters(in: .whitespaces) + ")"; return o }
        let jump = r.offsetMs
        if abs(jump) < P.minJumpMs { o.reason = "desvio pequeno"; return o }
        let strong = r.z >= P.strongZ && r.nccMean >= P.strongNcc
        var confirmed = false
        if let pv = prev, pv.verdict != .keep, abs(pv.newOffsetMs - (b + r.offsetMs)) <= P.sameProposalMs { confirmed = true }
        o.newOffsetMs = b + r.offsetMs
        o.applyFromSubMs = nextGap(cues, a, b, playMs, P.minGapMs)
        o.verdict = (strong || confirmed) ? .change : .proposed
        o.reason = strong ? "evidência forte" : confirmed ? "confirmada em 2 checagens" : "aguardando confirmação"
        return o
    }

    /** início (tempo da legenda) da primeira fala que começa depois de uma pausa >= minGapMs e depois da posição atual + 1 s */
    static func nextGap(_ cues: [AudioCue], _ a: Double, _ b: Double, _ playMs: Int, _ minGapMs: Int) -> Int {
        let n = cues.count
        var ord = Array(0..<n)
        ord.sort { (x, y) in cues[x].startMs != cues[y].startMs ? cues[x].startMs < cues[y].startMs : x < y }
        var maxEnd = Int.min
        for k in 0..<n {
            let c = cues[ord[k]]
            let startMedia = a * Double(c.startMs) + b
            if k > 0 && startMedia > Double(playMs + 1000) && Double(c.startMs - maxEnd) >= Double(minGapMs) / a { return c.startMs }
            maxEnd = max(maxEnd, c.endMs)
        }
        return Int.max
    }
}
