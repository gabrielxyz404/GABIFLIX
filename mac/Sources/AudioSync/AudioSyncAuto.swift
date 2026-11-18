// Sincronização da legenda pelo áudio, totalmente automática (porte de AudioSyncAuto.java do Android/TV): quando o vídeo está tocando e há uma
// legenda baixada ativa, ouve o filme (3 janelas de 90 s numa segunda instância do VLCKit, fila de qualidade de serviço baixa) e, se o alinhador
// tem certeza, acerta o atraso da legenda. O mesmo motor atende o item "Sincronizar automaticamente" do menu de legendas (chain).
//
// Quantas vezes se analisa (igual ao Android/TV; pedido do usuário: o motor verificava a legenda vezes demais):
//  - depois de um resultado CONFIANTE (acertou ou "já estava certa") a legenda NÃO é analisada de novo nesta sessão e o resultado fica guardado
//    por filme+legenda durante 30 dias (reabrir o mesmo filme não analisa de novo). Só volta a analisar quando o usuário troca de faixa de legenda
//    (outra legenda), quando há um salto de mais de 10 min na linha do tempo JUNTO com um ajuste manual do atraso, ou quando toca em
//    "Sincronizar automaticamente";
//  - depois de um resultado SEM CONFIANÇA tenta mais UMA vez, 20 min depois, e para;
//  - só aparece aviso na tela quando o atraso muda de verdade; confirmação silenciosa só na primeira conferência de um filme.
// Toda decisão de iniciar (ou não) uma análise sai no registro: "AUDIOSYNC GATILHO motivo=… decisão=…".
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
    private static let tickS = 1.0
    /** NEW = ainda não analisada; DONE = resultado confiante (não se analisa de novo); HOLD = DONE mas o usuário mexeu no atraso à mão; OFF = parou */
    private enum St { case new, done, hold, off }
    /** quanto vale um resultado confiante guardado (fix/ok) e um "sem confiança" */
    private static let confidentTtlMs = 30 * 86_400_000
    private static let noTtlMs = 3 * 86_400_000

    /** estado de UMA legenda (por filme+fonte+legenda) nesta sessão do player; fica guardado se a faixa for trocada e voltar */
    private final class Sub {
        var st = St.new
        /** análises começadas para esta legenda nesta sessão (para o registro/provas) */
        var analyses = 0
        /** análises que terminaram sem confiança (recusada, erro): no máximo 2 */
        var attempts = 0
        var retryPending = false, retryFromRefusal = false
        var retryAt = 0, keySince = 0
        var mScale = 1.0, mOffset = 0.0
        var lastWritten = 0
        var nextFollowAt = 0
        /** gatilho "ajuste manual + salto grande": os dois têm de acontecer depois do resultado confiante */
        var manualEdited = false, farSeek = false
    }

    private let h: AudioSyncHost
    private let core = AudioSyncCore()
    private let queue = DispatchQueue(label: "frameplay.audiosync", qos: .utility)
    private let token = AudioBox<Int>(0)
    private var started = false, stopped = false
    private var timer: Timer?

    // configuração (as variáveis de ambiente só existem nos testes)
    private let autoOn: Bool
    private var startAfterMs = 25_000
    /** depois de um resultado sem confiança: uma segunda e última tentativa daqui a tanto (teste: FP_AUDIO_RETRY_SEC encurta) */
    private var retryMs = 20 * 60_000
    /** salto na linha do tempo que conta como "salto grande" (teste: FP_AUDIO_FARSEEK_SEC) */
    private var farSeekMs = 600_000

    private var subs: [String: Sub] = [:]
    private var cur: Sub?
    private var key: String?
    private var running = false
    private var lastStartAt = 0
    private let rescanAt = AudioBox<Int>(0)
    private let keyNow = AudioBox<String?>(nil)
    private var lastPosMs = -1, lastPosWall = 0

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
        if let v = env["FP_AUDIO_RETRY_SEC"], let n = Int(v) { retryMs = n * 1000 }
        if let v = env["FP_AUDIO_FARSEEK_SEC"], let n = Int(v) { farSeekMs = n * 1000 }
        if env["FP_AUDIO_DSP"] == "true" { core.forceDsp = true }
        let hh = host
        core.logger = { hh.log($0) }
    }

    /** sem limite de dados: a sincronização automática roda sempre, qualquer que seja o tamanho do arquivo e o tipo de rede (decisão do usuário) */
    private func autoBudget() -> Int { return AudioSyncCore.noLimit }

    private static func now() -> Int { return Int(DispatchTime.now().uptimeNanoseconds / 1_000_000) }
    private func log(_ s: String) { h.log("AUDIOSYNC " + s) }
    private static func shortKey(_ k: String?) -> String { return k == nil ? "-" : String(k!.suffix(48)) }
    private static func stName(_ st: St) -> String {
        switch st {
        case .new: return "nova"
        case .done: return "confiante"
        case .hold: return "manual"
        case .off: return "parada"
        }
    }

    /** o registro de cada gatilho (iniciar ou não uma análise) e a razão: o log do CI conta as linhas "decisão=análise" */
    private func trigger(_ why: String, _ k: String?, _ decision: String) {
        var extra = ""
        if let kk = k, let s = subs[kk] { extra = " [análises=\(s.analyses) sem_confiança=\(s.attempts) estado=\(AudioSyncAuto.stName(s.st))]" }
        log("GATILHO motivo=\(why) legenda=\(AudioSyncAuto.shortKey(k)) decisão=\(decision)\(extra)")
    }

    func start() {
        if started || stopped { return }
        started = true
        purgeOldMarks()
        log("pronto (sem limite de dados; automático=\(autoOn), começa \(startAfterMs / 1000) s depois de a legenda ficar ativa; resultado confiante vale \(AudioSyncAuto.confidentTtlMs / 86_400_000) dias e não se reverifica; sem confiança: 1 nova tentativa em \(retryMs / 1000) s; salto grande = \(farSeekMs / 1000) s)")
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
        let name = cur == nil ? "-" : AudioSyncAuto.stName(cur!.st)
        return "st=\(name) executando=\(running) análises=\(cur?.analyses ?? 0) sem_confiança=\(cur?.attempts ?? 0) mScale=\(cur?.mScale ?? 1.0) mOffset=\(Int(cur?.mOffset ?? 0)) escrito=\(cur?.lastWritten ?? 0) maiorIntervaloDoCiclo=\(maxTickGapMs) ms"
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
        let k = keyNow.value
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let kk = k, let s = self.subs[kk] { s.analyses += 1 }
            self.trigger("botão-sincronizar", k, "análise")
        }
        let o = core.run(url: url, subText: active.text, durMs: durationMs, nowMs: Int(videoTimeMs), currentDelay: d1, maxBytes: AudioSyncCore.noLimit)
        log("botão: \(o.note) | \(o.st.summary())")
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
            rescanAt.value = AudioSyncAuto.now() + 6000
            return r1.status == .failed ? AutoSyncResult.failedMsg(r1.note, o.msg) : r1
        case .cancelled:
            return r1
        default: // erro de captura, pulado: diz o motivo em vez de um "não consegui" genérico (se a referência já acertou, vale a referência)
            return r1.status == .failed ? AutoSyncResult.failedMsg(r1.note, o.msg.isEmpty ? "Não consegui ouvir o áudio do filme." : o.msg) : r1
        }
    }

    // ------------------------------------------------------------ marcas guardadas (cache por filme+legenda)

    private func mark(_ k: String, _ kind: String, _ scale: Double, _ off: Double, _ delay: Int) {
        UserDefaults.standard.set("\(AudioSyncCore.version)|\(kind)|\(scale)|\(off)|\(delay)|\(Int(Date().timeIntervalSince1970 * 1000))", forKey: AudioSyncAuto.prefs + k)
    }

    private static func expired(_ a: [String]) -> Bool {
        let age = Int(Date().timeIntervalSince1970 * 1000) - (Int(a[5]) ?? 0)
        // "sem confiança" vale por 3 dias (a causa pode ter sido passageira); o confiante por 30 dias
        return age > (a[1] == "no" ? noTtlMs : confidentTtlMs)
    }

    private func readMark(_ k: String) -> [String]? {
        guard let v = UserDefaults.standard.string(forKey: AudioSyncAuto.prefs + k) else { return nil }
        let a = v.components(separatedBy: "|")
        if a.count != 6 || a[0] != AudioSyncCore.version { return nil }
        if AudioSyncAuto.expired(a) { return nil }
        return a
    }

    /** apaga as marcas vencidas (30 dias; "sem confiança" 3 dias) e as de versões antigas do motor */
    private func purgeOldMarks() {
        let d = UserDefaults.standard
        var n = 0
        for (name, val) in d.dictionaryRepresentation() where name.hasPrefix(AudioSyncAuto.prefs) && !name.hasPrefix(AudioSyncAuto.prefs + "filme:") {
            guard let v = val as? String else { continue }
            let a = v.components(separatedBy: "|")
            if a.count != 6 || a[0] != AudioSyncCore.version || AudioSyncAuto.expired(a) { d.removeObject(forKey: name); n += 1 }
        }
        if n > 0 { log("marcas vencidas apagadas: \(n)") }
    }

    /** a primeira conferência deste filme (nenhuma outra marca dele guardada antes): só nela a confirmação silenciosa mostra um aviso */
    private func firstCheckOfFilm(_ k: String) -> Bool {
        let film = AudioSyncAuto.prefs + "filme:" + (k.components(separatedBy: "|").first ?? k)
        let nowMs = Int(Date().timeIntervalSince1970 * 1000)
        if let v = UserDefaults.standard.string(forKey: film), let t = Int(v), nowMs - t < AudioSyncAuto.confidentTtlMs { return false }
        UserDefaults.standard.set(String(nowMs), forKey: film)
        return true
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

    /** salto na linha do tempo (para frente ou para trás) maior que o limite: guarda para o gatilho "ajuste manual + salto grande" */
    private func watchSeek(_ now: Int, _ pl: Bool) {
        let t = h.timeMs()
        if lastPosMs >= 0 && t > 0 {
            let expected = lastPosMs + (pl ? now - lastPosWall : 0)
            let jump = abs(t - expected)
            if jump > farSeekMs, let s = cur {
                log("salto na linha do tempo de \(jump / 1000) s (de \(lastPosMs / 1000) s para \(t / 1000) s): só conta se o usuário também mexer no atraso à mão; sozinho não dispara análise")
                s.farSeek = true
            }
        }
        if t > 0 { lastPosMs = t; lastPosWall = now }
    }

    /** a legenda ativa mudou: o estado de cada legenda fica guardado (voltar a ela não recomeça do zero) */
    private func switchSub(_ k: String?, _ now: Int) {
        if running, let kk = k, let old = key, kk != old {
            token.value += 1
            core.cancel()
            running = false
            log("legenda trocada: análise em andamento cancelada")
        }
        key = k
        keyNow.value = k
        guard let kk = k else { cur = nil; return }
        var fresh = false
        var found = subs[kk]
        if found == nil {
            let n = Sub()
            n.keySince = now
            subs[kk] = n
            found = n
            fresh = true
        }
        cur = found
        let seen = found.map { "já vista nesta sessão: \(AudioSyncAuto.stName($0.st)), análises=\($0.analyses)" } ?? ""
        log("legenda ativa: " + AudioSyncAuto.shortKey(kk) + (fresh ? " (nova nesta sessão)" : " (" + seen + ")"))
        if fresh { trigger("troca-de-legenda", kk, "avaliar (espera \(startAfterMs / 1000) s tocando; se há resultado guardado, não analisa)") }
    }

    private func tick() {
        if stopped { return }
        let now = AudioSyncAuto.now()
        if lastTickAt > 0 { maxTickGapMs = max(maxTickGapMs, now - lastTickAt) }
        lastTickAt = now
        if now - lastDiag >= 20_000 { lastDiag = now; log("estado: st=\(cur == nil ? "-" : AudioSyncAuto.stName(cur!.st)) executando=\(running) " + h.debug()) }
        let pl = h.playing()
        sampleHealth(now, pl)
        watchSeek(now, pl)
        let cand = h.activeCand()
        let k: String? = (cand == nil || cand?.text == nil) ? nil : h.delayKey()
        if k != key { switchSub(k, now) }
        guard let kk = k, let cd = cand, let s = cur, autoOn else { return }
        if running && hBuf >= 2 && now - lastStartAt > 5000 { abortForStall(s, kk) }
        let ra = rescanAt.value
        if ra > 0 && now >= ra && !running {
            // o item de menu acabou de terminar: adota o resultado que ele guardou (sem nova captura)
            rescanAt.value = 0
            s.st = .new; s.keySince = 0; s.attempts = 0; s.retryPending = false; s.retryFromRefusal = false
            trigger("botão-adotar-resultado", kk, "sem análise (só lê a marca que o botão guardou)")
        }
        switch s.st {
        case .new:
            if running || !pl || now - s.keySince < startAfterMs || (s.retryPending && now < s.retryAt) { return }
            if lastStallAt > 0 && now - lastStallAt < 20_000 { return }
            decideStart(s, cd, kk)
        case .done:
            if h.delayMs() != s.lastWritten {
                s.st = .hold
                s.manualEdited = true
                log("atraso mexido à mão: não mexo no atraso; só uma nova análise se também houver um salto de mais de \(farSeekMs / 1000) s na linha do tempo")
                return
            }
            if running || core.busy { return }
            if abs(s.mScale - 1.0) > 1e-9 && now >= s.nextFollowAt && pl { follow(s, now) }
        case .hold:
            if running || core.busy || !pl { return }
            if s.manualEdited && s.farSeek {
                s.manualEdited = false
                s.farSeek = false
                startRun(s, cd, kk, "ajuste-manual+salto-grande")
            }
        case .off:
            break
        }
    }

    /** a legenda tem de ser uma que o motor ainda não viu; atraso manual guardado vence */
    private func decideStart(_ s: Sub, _ cand: SubtitleSync.Cand, _ k: String) {
        let saved = h.savedDelay(k)
        let second = s.retryFromRefusal
        if !second, let m = readMark(k) {
            if m[1] == "no" {
                s.st = .off
                trigger("início", k, "ignorado: já vista pelo áudio e recusada (vale 3 dias)")
                return
            }
            let markDelay = Int(m[4]) ?? Int.min
            if saved != markDelay {
                s.st = .off
                trigger("início", k, "ignorado: atraso guardado (\(saved) ms) não é o do áudio (\(markDelay) ms): é manual")
                return
            }
            s.mScale = Double(m[2]) ?? 1.0
            s.mOffset = Double(m[3]) ?? 0.0
            if m[1] == "ok" { s.mOffset = Double(h.delayMs()) }
            s.lastWritten = h.delayMs()
            s.st = .done
            s.nextFollowAt = 0
            let dias = (Int(Date().timeIntervalSince1970 * 1000) - (Int(m[5]) ?? 0)) / 86_400_000
            trigger("início", k, "ignorado: resultado confiante guardado (\(m[1]), há \(dias) dias): sem captura e sem reverificação")
            return
        }
        if saved != 0 || h.delayMs() != 0 {
            s.st = .off
            trigger(second ? "segunda-tentativa" : "início", k, "ignorado: atraso manual guardado (\(saved != 0 ? saved : h.delayMs()) ms)")
            return
        }
        startRun(s, cand, k, second ? "segunda-tentativa" : "início")
    }

    private func startRun(_ s: Sub, _ cand: SubtitleSync.Cand, _ k: String, _ why: String) {
        if running || core.busy { trigger(why, k, "ignorado: já há uma análise em andamento (nunca duas ao mesmo tempo)"); return }
        token.value += 1
        let tok = token.value
        running = true
        s.analyses += 1
        s.retryPending = false
        s.retryFromRefusal = false
        trigger(why, k, "análise")
        lastStartAt = AudioSyncAuto.now()
        let d0 = h.delayMs()
        let pos = h.timeMs(), dur = h.durationMs()
        let url = h.url()
        let text = cand.text
        log("antes da análise: " + healthLine())
        resetHealth()
        maxTickGapMs = 0
        let budget = autoBudget()
        log("começando: posição \(pos / 1000) s, duração \(dur / 1000) s, atraso atual \(d0) ms, sem limite de dados")
        queue.async { [weak self] in
            guard let self = self else { return }
            let o = self.core.run(url: url, subText: text, durMs: dur, nowMs: pos, currentDelay: d0, maxBytes: budget)
            DispatchQueue.main.async { self.finishRun(tok, k, d0, o) }
        }
    }

    /** a reprodução engasgou duas vezes durante a análise: cancela e conta como resultado sem confiança */
    private func abortForStall(_ s: Sub, _ k: String) {
        log("a reprodução engasgou \(hBuf) vezes durante a análise: cancelo para não atrapalhar o filme")
        token.value += 1
        core.cancel()
        running = false
        inconclusive(s, k, "reprodução engasgou")
    }

    /** resultado sem confiança (recusada, erro de captura, engasgo): uma segunda tentativa, 20 min depois, e para */
    private func inconclusive(_ s: Sub, _ k: String, _ why: String) {
        s.attempts += 1
        if s.attempts >= 2 {
            s.st = .off
            s.retryPending = false
            trigger("sem-confiança", k, "parar: \(why) (2 tentativas feitas)")
        } else {
            s.st = .new
            s.retryPending = true
            s.retryFromRefusal = true
            s.retryAt = AudioSyncAuto.now() + retryMs
            trigger("sem-confiança", k, "agendar UMA segunda tentativa em \(retryMs / 1000) s: \(why)")
        }
    }

    private func finishRun(_ tok: Int, _ k: String, _ d0: Int, _ o: AudioSyncCore.Outcome) {
        if stopped || tok != token.value { log("resultado descartado (outra execução/legenda/tela): " + o.note); return }
        running = false
        log("resultado: " + o.note + (o.cuts ? " [aviso: a legenda parece ter cortes]" : "") + " | " + o.st.summary())
        log(healthLine())
        guard let s = subs[k] else { return }
        if let kk = key, k != kk { return }
        if h.delayMs() != d0 { s.st = .off; log("o usuário mexeu no atraso durante a análise: descartado"); return }
        switch o.kind {
        case .fix:
            let persist = o.scale == 1.0
            let changed = o.targetDelay != d0 || !persist
            if changed { h.applyDelay(o.targetDelay, persist) }
            s.mScale = o.scale
            s.mOffset = o.offsetMs
            s.lastWritten = changed ? o.targetDelay : d0
            mark(k, "fix", o.scale, o.offsetMs, persist ? o.targetDelay : 0)
            s.st = .done
            s.attempts = 0
            s.manualEdited = false
            s.farSeek = false
            s.nextFollowAt = AudioSyncAuto.now() + 15_000
            // o atraso mudou de verdade: único caso com aviso (além da primeira confirmação do filme)
            if changed { h.notice(persist ? "Legenda acertada pelo áudio (" + AudioSyncAuto.secs(o.targetDelay) + ")" : "Legenda acertada pelo áudio (velocidade e atraso)") }
            log("aplicado: atraso \(o.targetDelay) ms, escala \(o.scale) (resultado confiante: não será reverificado)")
        case .ok:
            s.mScale = 1.0
            s.mOffset = Double(h.delayMs())
            s.lastWritten = h.delayMs()
            mark(k, "ok", 1.0, o.offsetMs, h.delayMs())
            s.st = .done
            s.attempts = 0
            s.manualEdited = false
            s.farSeek = false
            log("a legenda já estava certa (deslocamento medido \(jround(o.offsetMs)) ms): não mexi (resultado confiante: não será reverificado)")
            if firstCheckOfFilm(k) { h.notice("Legenda confirmada pelo áudio") }   // confirmação silenciosa: só na primeira conferência do filme
        case .refused:
            mark(k, "no", 1.0, 0, 0)
            log("o áudio não tem certeza: legenda deixada como estava")
            inconclusive(s, k, "o áudio não tem certeza")
        default: // erro, cancelado, pulado
            if o.kind == .cancelled {
                s.st = .new; s.retryPending = true; s.retryAt = AudioSyncAuto.now() + 30_000
                log("análise cancelada (\(o.note)): recomeça em 30 s (não conta como tentativa)")
            } else if o.kind == .skipped {
                s.st = .off
                trigger("pulado", k, "parar: \(o.note)")
            } else { inconclusive(s, k, "erro: \(o.note)") }
        }
    }

    // ------------------------------------------------------------ escala diferente de 1: o atraso acompanha o modelo (sem nova análise)

    private static func delayAt(_ a: Double, _ off: Double, _ t: Int) -> Int {
        let cue = (Double(t) - off) / a
        let v = jround(((a - 1.0) * cue + off) / 10.0) * 10
        return max(-600_000, min(600_000, v))
    }

    private func follow(_ s: Sub, _ now: Int) {
        s.nextFollowAt = now + 15_000
        let d = AudioSyncAuto.delayAt(s.mScale, s.mOffset, h.timeMs())
        if abs(d - h.delayMs()) < 250 { return }
        h.applyDelay(d, false)
        s.lastWritten = d
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
