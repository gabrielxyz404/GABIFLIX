// Sincronização automática da legenda ativa (porte do AutoSync.java do Android).
// O menu de legendas só conhece o protocolo AutoSyncEngine: para ligar outro motor (por exemplo o que ouve o áudio do filme)
// basta implementar o protocolo e trocar a fábrica em PlayerWindowController (makeAutoEngine). Corre sempre fora da thread principal.
import Foundation

enum AutoSyncStatus { case fixed, ok, failed }

struct AutoSyncResult {
    let status: AutoSyncStatus
    /** atraso final da legenda (ms; positivo = aparece mais tarde); só vale com .fixed */
    let delayMs: Int
    let note: String
    /** frase pronta para o usuário (português simples) com o motivo exato; nil = usa o texto padrão */
    var userMsg: String? = nil
    static func failedMsg(_ note: String, _ msg: String) -> AutoSyncResult { AutoSyncResult(status: .failed, delayMs: 0, note: note, userMsg: msg) }
    static func fixed(_ ms: Int, _ note: String) -> AutoSyncResult { AutoSyncResult(status: .fixed, delayMs: ms, note: note) }
    static func ok(_ note: String) -> AutoSyncResult { AutoSyncResult(status: .ok, delayMs: 0, note: note) }
    static func failed(_ note: String) -> AutoSyncResult { AutoSyncResult(status: .failed, delayMs: 0, note: note) }
}

protocol AutoSyncEngine {
    /** Chamado fora da thread principal; pode demorar, mas nunca deve mexer no player.
        active: a legenda baixada ativa (com o texto atual); videoTimeMs: posição do vídeo agora; currentDelayMs: atraso atual (positivo = mais tarde) */
    func sync(active: SubtitleSync.Cand, videoTimeMs: Int64, currentDelayMs: Int) -> AutoSyncResult
}

/** Motor atual: refaz o alinhamento da legenda ativa contra as referências de tempo conhecidas e devolve o atraso que o VLC deve aplicar.
    Só age quando a referência valida por inteiro: na dúvida não mexe. */
struct RefAutoSyncEngine: AutoSyncEngine {
    static let toleranceMs = 300
    static let maxDelayMs = 600000
    let refs: [[Int64]]
    let others: [SubtitleSync.Cand]

    func sync(active: SubtitleSync.Cand, videoTimeMs: Int64, currentDelayMs: Int) -> AutoSyncResult {
        guard let text = active.text else { return .failed("sem texto") }
        let starts = SubtitleAlign.alignStarts(text)
        if starts.count < 20 { return .failed("poucas falas") }
        var use = refs
        if use.isEmpty {
            // sem referência de outro idioma: serve outra legenda PT que case com o hash ou já esteja alinhada
            for o in others {
                if o === active || o.text == nil || o.sparse { continue }
                if !(o.hashMatch || o.aligned || o.refSelf) { continue }
                let s = SubtitleAlign.alignStarts(o.text ?? "")
                if s.count >= 20 { use.append(s) }
            }
        }
        if use.isEmpty {
            if active.refSelf { return .ok("é a própria referência") }
            return .failed("sem referência de tempo")
        }
        var best: SubtitleAlign.Fit? = nil
        var oks: [SubtitleAlign.Fit] = []
        for r in use {
            let f = SubtitleAlign.align(r, starts)
            if best == nil || (f.ok() && !(best?.ok() ?? false)) || (f.ok() == best?.ok() && f.score > (best?.score ?? 0)) { best = f }
            if f.ok() { oks.append(f) }
        }
        guard var b = best, b.ok() else { return .failed("a referência não casa") }
        if oks.count >= 2 {
            // referências do mesmo vídeo têm de dar a mesma transformação; se discordam, não decide
            var pick: SubtitleAlign.Fit? = nil
            var bestVotes = 0
            for a in oks {
                var votes = 0
                for o in oks where SubtitleAlign.agreement(a, o, starts, 2000) >= 0.90 { votes += 1 }
                if votes > bestVotes || (votes == bestVotes && pick != nil && a.score > (pick?.score ?? 0)) { bestVotes = votes; pick = a }
            }
            guard bestVotes >= 2, let p = pick else { return .failed("as referências discordam") }
            b = p
        }
        // o atraso certo agora: onde a fala que aparece neste instante (tempo da legenda c) devia aparecer segundo a referência
        let c = max(0, videoTimeMs - Int64(currentDelayMs))
        var want = b.map(c) - c
        want = max(-Int64(RefAutoSyncEngine.maxDelayMs), min(Int64(RefAutoSyncEngine.maxDelayMs), want))
        if abs(want - Int64(currentDelayMs)) < Int64(RefAutoSyncEngine.toleranceMs) { return .ok("já estava certa") }
        return .fixed(Int(want), "ajuste \(want) ms")
    }
}
