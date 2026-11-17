// Sincronização da legenda pelo áudio, totalmente automática (porte de AudioSyncAuto.java do Android/TV): quando o vídeo está tocando e há uma
// legenda baixada ativa, ouve o filme (3 janelas de 90 s numa segunda instância do VLCKit, fila de qualidade de serviço baixa) e, se o alinhador
// tem certeza, acerta o atraso da legenda; a cada ~10 min confere de novo (monitor). O mesmo motor atende o item "Sincronizar automaticamente"
// do menu de legendas (chain).
//
// Regras: o atraso manual do usuário sempre vence (atraso guardado que não foi posto por aqui, ou mexido depois, nunca é alterado); na dúvida não
// mexe; nada disto toca o player de reprodução nem a vista do vídeo (só o atraso da legenda, pelo mesmo caminho do ajuste manual).
import Foundation

/** o que a janela do player empresta a este controlador (tudo chamado na thread principal) */
struct AudioSyncHost {
    /** tocando agora: não pausado */
    var playing: () -> Bool
    var buffering: () -> Bool
    var timeMs: () -> Int
    var durationMs: () -> Int
    var url: () -> URL
    /** a legenda baixada ativa (nil se for embutida, desativada ou ainda desconhecida) */
    var activeCand: () -> SubtitleSync.Cand?
    /** chave do atraso guardado dessa legenda (a mesma do ajuste manual) */
    var delayKey: () -> String?
    var delayMs: () -> Int
    var savedDelay: (String) -> Int
    /** muda o atraso (guardando por legenda se persist) pelo mesmo caminho do ajuste manual */
    var applyDelay: (Int, Bool) -> Void
    var notice: (String) -> Void
    /** quadros de vídeo perdidos até agora (diagnóstico; -1 se não sabe) */
    var lostFrames: () -> Int
    var debug: () -> String
    var log: (String) -> Void
}

/** pequena caixa com trava, para o que as threads de fundo e a principal trocam entre si */
final class AudioBox<T> {
    private var v: T
    private let l = NSLock()
    init(_ v: T) { self.v = v }
    var value: T {
        get { l.lock(); defer { l.unlock() }; return v }
        set { l.lock(); v = newValue; l.unlock() }
    }
}

final class AudioSyncAuto {
    private static let prefs = "audio-sync-1:"
    private enum St { case new, done, off }
    private static let tickS = 1.0

    private let h: AudioSyncHost
    private let core = AudioSyncCore()
    private let queue = DispatchQueue(label: "frameplay.audiosync", qos: .utility)
    private let token = AudioBox<Int>(0)
    private var started = false, stopped = false
    private var timer: Timer?

    // configuração (as variáveis de ambiente só existem nos testes)
    private let autoOn: Bool
    private var startAfterMs = 25_000
    private var recheckMs = 600_000

    // estado da legenda ativa
    private var key: String?
    private var keySince = 0
    private var st = St.new
    private var running = false, retryPending = false
    private var retries = 0
    private var retryAt = 0
    private var mScale = 1.0, mOffset = 0.0
    private var lastWritten = 0
    private var nextRecheckAt = 0, nextFollowAt = 0
    private var prevOut: AudioRecheck.Out?
    private var failing: [AudioWindow] = []
    private let rescanAt = AudioBox<Int>(0)
    private let keyNow = AudioBox<String?>(nil)

    // saúde da reprodução (para medir que a análise não trava o filme)
    private var lastWall = 0, lastT = 0, hN = 0, hStalls = 0, hBuf = 0, lostAtStart = 0, lastStallAt = 0
    private var hMinRatio = 9.0
    private var lastPl = false, lastBuf = false
    private var lastDiag = 0
    /** maior intervalo entre duas passadas do ciclo (a thread principal não pode engasgar enquanto se analisa) */
    private var maxTickGapMs = 0
    private var lastTickAt = 0

    init(host: AudioSyncHost) {
        h = host
        let env = ProcessInfo.processInfo.environment
        autoOn = env["FP_AUDIO_AUTO"] != "false"
        if let v = env["FP_AUDIO_START_SEC"], let n = Int(v) { startAfterMs = n * 1000 }
        if let v = env["FP_AUDIO_RECHECK_SEC"], let n = Int(v) { recheckMs = n * 1000 }
        if env["FP_AUDIO_DSP"] == "true" { core.forceDsp = true }
        let hh = host
        core.logger = { hh.log($0) }
    }

    private static func now() -> Int { return Int(DispatchTime.now().uptimeNanoseconds / 1_000_000) }
    private func log(_ s: String) { h.log("AUDIOSYNC " + s) }

    func start() {
        if started || stopped { return }
        started = true
        log("pronto (automático=\(autoOn), começa \(startAfterMs / 1000) s depois de a legenda ficar ativa, reverifica a cada \(recheckMs / 1000) s)")
        let t = Timer(timeInterval: AudioSyncAuto.tickS, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    /** a janela vai fechar: cancela tudo e solta o player de captura e o ONNX Runtime */
    func stop() {
        stopped = true
        token.value += 1
        timer?.invalidate()
        timer = nil
        core.close()
    }

    /** saúde da reprodução desde a última análise (para os testes) */
    func healthText() -> String { return healthLine() }

    /** texto de diagnóstico do estado, para os testes */
    func stateText() -> String {
        return "st=\(st) executando=\(running) mScale=\(mScale) mOffset=\(Int(mOffset)) escrito=\(lastWritten) maiorIntervaloDoCiclo=\(maxTickGapMs) ms"
    }

    // ------------------------------------------------------------ item do menu (o painel de legendas)

    /**
     * Motor do item de menu: a referência/consenso de sempre primeiro; depois o áudio confere e, se tem certeza, corrige (ele manda no atraso).
     * Se o áudio recusa, vale o resultado de antes. Corre fora da thread principal. url/duração são lidos antes, na thread principal.
     */
    func chain(ref: AutoSyncEngine, url: URL, durationMs: Int) -> AutoSyncEngine {
        return AudioChainEngine(auto: self, ref: ref, url: url, durationMs: durationMs)
    }

    fileprivate func runForButton(ref: AutoSyncEngine, url: URL, durationMs: Int, active: SubtitleSync.Cand, videoTimeMs: Int64, currentDelayMs: Int) -> AutoSyncResult {
        let r1 = ref.sync(active: active, videoTimeMs: videoTimeMs, currentDelayMs: currentDelayMs)
        if stopped { return r1 }
        let d1 = r1.status == .fixed ? r1.delayMs : currentDelayMs
        token.value += 1   // a execução automática em andamento (se houver) é descartada
        core.cancel()
        let o = core.run(url: url, subText: active.text, durMs: durationMs, nowMs: Int(videoTimeMs), currentDelay: d1, force: true)
        log("botão: \(o.note) | \(o.st.summary())")
        let k = keyNow.value
        switch o.kind {
        case .fix:
            if let k = k { mark(k, "fix", o.scale, o.offsetMs, o.targetDelay) }   // o painel guarda este atraso como qualquer outro
            rescanAt.value = AudioSyncAuto.now() + 6000
            return AutoSyncResult.fixed(o.targetDelay, "áudio " + o.note)
        case .ok:
            if let k = k { mark(k, "ok", 1.0, o.offsetMs, d1) }
            rescanAt.value = AudioSyncAuto.now() + 6000
            return r1.status == .fixed ? r1 : AutoSyncResult.ok("já estava certa (o áudio confirma)")
        case .refused:
            if let k = k { mark(k, "no", 1.0, 0, 0) }
            return r1
        default:
            return r1
        }
    }

    // ------------------------------------------------------------ marcas guardadas (cache por legenda)

    private func mark(_ k: String, _ kind: String, _ scale: Double, _ off: Double, _ delay: Int) {
        UserDefaults.standard.set("\(AudioSyncCore.version)|\(kind)|\(scale)|\(off)|\(delay)", forKey: AudioSyncAuto.prefs + k)
    }

    private func readMark(_ k: String) -> [String]? {
        guard let v = UserDefaults.standard.string(forKey: AudioSyncAuto.prefs + k) else { return nil }
        let a = v.components(separatedBy: "|")
        return a.count == 5 && a[0] == AudioSyncCore.version ? a : nil
    }

    // ------------------------------------------------------------ o ciclo (1 vez por segundo, na thread principal)

    private func resetHealth() {
        hN = 0; hStalls = 0; hBuf = 0
        hMinRatio = 9
        lostAtStart = h.lostFrames()
    }

    private func healthLine() -> String {
        let lost = h.lostFrames()
        let lostTxt = (lost < 0 || lostAtStart < 0) ? "?" : String(lost - lostAtStart)
        return String(format: "saúde da reprodução: amostras=%d paradas=%d razão_mínima=%.2f carregando=%d quadros_perdidos=%@ maior_intervalo_do_ciclo=%d ms",
                      hN, hStalls, hN == 0 ? 0.0 : hMinRatio, hBuf, lostTxt, maxTickGapMs)
    }

    private func sampleHealth(_ now: Int, _ pl: Bool) {
        let t = h.timeMs()
        let buf = h.buffering()
        if buf && !lastBuf { hBuf += 1 }
        if lastWall > 0 && pl && lastPl && now - lastWall >= 700 {
            let ratio = Double(t - lastT) / Double(now - lastWall)
            if ratio >= 0 && ratio < 3 {
                hN += 1
                if ratio < hMinRatio { hMinRatio = ratio }
                if ratio < 0.5 { hStalls += 1; lastStallAt = now }
            }
        }
        lastWall = now; lastT = t; lastPl = pl; lastBuf = buf
    }

    private func newSub(_ k: String?) {
        token.value += 1
        if running { core.cancel(); log("legenda trocada: análise em andamento cancelada") }
        running = false
        key = k
        keyNow.value = k
        keySince = AudioSyncAuto.now()
        st = .new
        retries = 0
        retryPending = false
        prevOut = nil
        failing.removeAll()
        mScale = 1.0
        mOffset = 0
        if let k = k { log("legenda ativa: " + String(k.suffix(60))) }
    }

    private func tick() {
        if stopped { return }
        let now = AudioSyncAuto.now()
        if lastTickAt > 0 { maxTickGapMs = max(maxTickGapMs, now - lastTickAt) }
        lastTickAt = now
        if now - lastDiag >= 20_000 { lastDiag = now; log("estado: st=\(st) executando=\(running) " + h.debug()) }
        let pl = h.playing()
        sampleHealth(now, pl)
        let cand = h.activeCand()
        let k: String? = (cand == nil || cand?.text == nil) ? nil : h.delayKey()
        if k != key { newSub(k) }
        guard let kk = k, let cd = cand, autoOn else { return }
        let ra = rescanAt.value
        if ra > 0 && now >= ra && !running { rescanAt.value = 0; st = .new; keySince = 0; retries = 0 }
        switch st {
        case .new:
            if running || !pl || now - keySince < startAfterMs || (retryPending && now < retryAt) { return }
            if lastStallAt > 0 && now - lastStallAt < 20_000 { return }
            decideStart(cd, kk)
        case .done:
            if h.delayMs() != lastWritten { st = .off; log("atraso mexido à mão: não mexo mais nesta legenda"); return }
            if running || core.busy { return }
            if abs(mScale - 1.0) > 1e-9 && now >= nextFollowAt && pl { follow(now) }
            if pl && now >= nextRecheckAt && now - lastStallAt > 20_000 { startRecheck(cd, kk) }
        case .off:
            break
        }
    }

    /** a legenda tem de ser uma que o motor ainda não viu; atraso manual guardado vence */
    private func decideStart(_ cand: SubtitleSync.Cand, _ k: String) {
        let saved = h.savedDelay(k)
        if let m = readMark(k) {
            if m[1] == "no" { st = .off; log("esta legenda já foi vista pelo áudio e recusada: não tento de novo"); return }
            let markDelay = Int(m[4]) ?? Int.min
            if saved != markDelay { st = .off; log("atraso guardado (\(saved) ms) não é o do áudio (\(markDelay) ms): é manual, não mexo"); return }
            mScale = Double(m[2]) ?? 1.0
            mOffset = Double(m[3]) ?? 0.0
            if m[1] == "ok" { mOffset = Double(h.delayMs()) }
            lastWritten = h.delayMs()
            st = .done
            nextRecheckAt = AudioSyncAuto.now() + recheckMs
            nextFollowAt = 0
            log("legenda já sincronizada pelo áudio antes (\(m[1])): sem nova captura, reverifica em \(recheckMs / 1000) s")
            return
        }
        if saved != 0 || h.delayMs() != 0 { st = .off; log("atraso manual guardado (\(saved) ms): não mexo"); return }
        startRun(cand, k)
    }

    private func startRun(_ cand: SubtitleSync.Cand, _ k: String) {
        token.value += 1
        let tok = token.value
        running = true
        let d0 = h.delayMs()
        let pos = h.timeMs(), dur = h.durationMs()
        let url = h.url()
        let text = cand.text
        log("antes da análise: " + healthLine())
        resetHealth()
        maxTickGapMs = 0
        log("começando: posição \(pos / 1000) s, duração \(dur / 1000) s, atraso atual \(d0) ms")
        queue.async { [weak self] in
            guard let self = self else { return }
            let o = self.core.run(url: url, subText: text, durMs: dur, nowMs: pos, currentDelay: d0, force: false)
            DispatchQueue.main.async { self.finishRun(tok, k, d0, o) }
        }
    }

    private func finishRun(_ tok: Int, _ k: String, _ d0: Int, _ o: AudioSyncCore.Outcome) {
        if stopped || tok != token.value { log("resultado descartado (outra execução/legenda/tela): " + o.note); return }
        running = false
        log("resultado: " + o.note + (o.cuts ? " [aviso: a legenda parece ter cortes]" : "") + " | " + o.st.summary())
        log(healthLine())
        if k != key { return }
        if h.delayMs() != d0 { st = .off; log("o usuário mexeu no atraso durante a análise: descartado"); return }
        switch o.kind {
        case .fix:
            let persist = o.scale == 1.0
            h.applyDelay(o.targetDelay, persist)
            mScale = o.scale
            mOffset = o.offsetMs
            lastWritten = o.targetDelay
            mark(k, "fix", o.scale, o.offsetMs, persist ? o.targetDelay : 0)
            st = .done
            nextRecheckAt = AudioSyncAuto.now() + recheckMs
            nextFollowAt = AudioSyncAuto.now() + 15_000
            h.notice(persist ? "Legenda acertada pelo áudio (" + AudioSyncAuto.secs(o.targetDelay) + ")" : "Legenda acertada pelo áudio (velocidade e atraso)")
            log("aplicado: atraso \(o.targetDelay) ms, escala \(o.scale)")
        case .ok:
            mScale = 1.0
            mOffset = Double(h.delayMs())
            lastWritten = h.delayMs()
            mark(k, "ok", 1.0, o.offsetMs, h.delayMs())
            st = .done
            nextRecheckAt = AudioSyncAuto.now() + recheckMs
            log("a legenda já estava certa (deslocamento medido \(jround(o.offsetMs)) ms): não mexi")
        case .refused:
            mark(k, "no", 1.0, 0, 0)
            st = .off
            log("o áudio não tem certeza: legenda deixada como estava")
        default: // erro, cancelado, pulado: tenta de novo mais tarde (no máximo 2 vezes)
            if o.kind == .skipped || retries >= 2 { st = .off; log("desisti (\(o.note))") }
            else { retries += 1; retryPending = true; retryAt = AudioSyncAuto.now() + 120_000 * retries; log("tento de novo em \(120 * retries) s (\(o.note))") }
        }
    }

    // ------------------------------------------------------------ escala diferente de 1: o atraso acompanha o modelo

    private static func delayAt(_ a: Double, _ off: Double, _ t: Int) -> Int {
        let cue = (Double(t) - off) / a
        let v = jround(((a - 1.0) * cue + off) / 10.0) * 10
        return max(-600_000, min(600_000, v))
    }

    private func follow(_ now: Int) {
        nextFollowAt = now + 15_000
        let d = AudioSyncAuto.delayAt(mScale, mOffset, h.timeMs())
        if abs(d - h.delayMs()) < 250 { return }
        h.applyDelay(d, false)
        lastWritten = d
    }

    // ------------------------------------------------------------ reverificação a cada ~10 min

    private func startRecheck(_ cand: SubtitleSync.Cand, _ k: String) {
        token.value += 1
        let tok = token.value
        running = true
        resetHealth()
        maxTickGapMs = 0
        let pos = h.timeMs(), dur = h.durationMs()
        let url = h.url()
        let text = cand.text
        let a = mScale, b = mOffset
        let prev = prevOut
        let fl = failing
        log("reverificando na posição \(pos / 1000) s")
        queue.async { [weak self] in
            guard let self = self else { return }
            let ck = self.core.recheck(url: url, subText: text, durMs: dur, posMs: pos, scale: a, offsetMs: b, prev: prev, failing: fl)
            DispatchQueue.main.async { self.finishRecheck(tok, k, ck, pos, a, b) }
        }
    }

    private func finishRecheck(_ tok: Int, _ k: String, _ ck: AudioSyncCore.Check, _ pos: Int, _ a: Double, _ b: Double) {
        if stopped || tok != token.value { return }
        running = false
        nextRecheckAt = AudioSyncAuto.now() + recheckMs
        log("reverificação: \(ck.note) | \(healthLine())")
        guard k == key, let out = ck.out else { return }
        if h.delayMs() != lastWritten { st = .off; return }
        switch out.verdict {
        case .keep:
            if ck.explained { failing.removeAll(); prevOut = nil }
            else if let w = ck.window { addFailing(w) }
        case .proposed:
            prevOut = out
            if let w = ck.window { addFailing(w) }
            log("reverificação: proposta de novo deslocamento \(jround(out.newOffsetMs)) ms, aguardando confirmação (nada mudou)")
        case .change:
            confirmChange(k, ck, pos, a, b)
        }
    }

    private func addFailing(_ w: AudioWindow) {
        failing.append(w)
        while failing.count > 2 { failing.removeFirst() }
    }

    /** o Recheck viu um salto: só vale se uma sessão completa em volta da posição concordar (mesmas portas do alinhamento inicial) */
    private func confirmChange(_ k: String, _ ck: AudioSyncCore.Check, _ pos: Int, _ a: Double, _ b: Double) {
        token.value += 1
        let tok = token.value
        running = true
        guard let cand = h.activeCand(), let text = cand.text, let have = ck.window, let out = ck.out else { running = false; return }
        let dur = h.durationMs()
        let url = h.url()
        let applyFromSub = out.applyFromSubMs
        log("reverificação: salto proposto, confirmando com 3 janelas em volta de \(pos / 1000) s")
        queue.async { [weak self] in
            guard let self = self else { return }
            let o = self.core.confirmChange(url: url, subText: text, durMs: dur, posMs: pos, scale: a, offsetMs: b, have: have)
            DispatchQueue.main.async {
                if self.stopped || tok != self.token.value { return }
                self.running = false
                self.log("confirmação: " + o.note)
                if o.kind != .fix || abs(o.scale - a) > 1e-6 {
                    self.log("o reajuste não foi confirmado: legenda mantida")
                    self.prevOut = nil
                    return
                }
                if applyFromSub == Int.max { self.log("sem pausa de falas à frente para aplicar o reajuste: legenda mantida"); return }
                self.scheduleApply(k, tok, o, Int(a * Double(applyFromSub) + b), AudioSyncAuto.now() + 180_000)
            }
        }
    }

    /** o reajuste entra no intervalo entre falas: um pouco antes da fala que abre depois da pausa (nunca no meio de uma fala) */
    private func scheduleApply(_ k: String, _ tok: Int, _ o: AudioSyncCore.Outcome, _ mediaStartMs: Int, _ deadline: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self = self else { return }
            if self.stopped || tok != self.token.value || k != self.key || self.h.delayMs() != self.lastWritten { return }
            let t = self.h.timeMs()
            if AudioSyncAuto.now() > deadline || t > mediaStartMs + 5000 { self.log("reajuste: a pausa passou sem aplicar; legenda mantida"); return }
            if t < mediaStartMs - 300 { self.scheduleApply(k, tok, o, mediaStartMs, deadline); return }
            let persist = o.scale == 1.0
            let d = AudioSyncAuto.delayAt(o.scale, o.offsetMs, t)
            self.h.applyDelay(d, persist)
            self.lastWritten = d
            self.mScale = o.scale
            self.mOffset = o.offsetMs
            self.prevOut = nil
            self.failing.removeAll()
            self.mark(k, "fix", o.scale, o.offsetMs, persist ? d : 0)
            self.h.notice("Legenda reajustada pelo áudio (" + AudioSyncAuto.secs(d) + " a partir daqui)")
            self.log("reajuste aplicado: atraso \(d) ms")
        }
    }

    /** "+1,2 s" / "−3,0 s" (sinal de menos de verdade, vírgula decimal) */
    static func secs(_ ms: Int) -> String {
        let v = String(format: "%.1f", Double(abs(ms)) / 1000.0).replacingOccurrences(of: ".", with: ",")
        return (ms > 0 ? "+" : ms < 0 ? "\u{2212}" : "") + v + " s"
    }
}

/** liga o item de menu ao motor: a referência primeiro, depois o áudio (que manda se tem certeza) */
final class AudioChainEngine: AutoSyncEngine {
    private weak var auto: AudioSyncAuto?
    private let ref: AutoSyncEngine
    private let url: URL
    private let durationMs: Int

    fileprivate init(auto: AudioSyncAuto, ref: AutoSyncEngine, url: URL, durationMs: Int) {
        self.auto = auto
        self.ref = ref
        self.url = url
        self.durationMs = durationMs
    }

    func sync(active: SubtitleSync.Cand, videoTimeMs: Int64, currentDelayMs: Int) -> AutoSyncResult {
        guard let a = auto else { return ref.sync(active: active, videoTimeMs: videoTimeMs, currentDelayMs: currentDelayMs) }
        return a.runForButton(ref: ref, url: url, durationMs: durationMs, active: active, videoTimeMs: videoTimeMs, currentDelayMs: currentDelayMs)
    }
}
