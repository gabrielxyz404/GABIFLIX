// Motor de sincronização pelo áudio (a parte pesada, sem interface): captura 3 janelas de 90 s do filme numa segunda instância do VLCKit,
// passa o VAD (Silero; DSP de reserva se o ONNX Runtime não carregar) e resolve com o alinhador validado (z >= 9, porta de concordância de
// 150 ms entre as janelas, banda morta de 1100 ms, só escalas de fps conhecidas, cortes só como aviso). Roda numa fila de qualidade de serviço
// baixa, uma execução por vez, com memória limitada (16 kHz mono, uma janela de PCM por vez) e cancelável. Porte de AudioSyncCore.java.
import Foundation

final class AudioSyncCore {
    /** versão do motor + política: muda o que está guardado (cache) sobre cada legenda */
    static let version = "m3"
    static let winMs = 90_000
    static let nWin = 3
    /** janelas espalhadas pelo filme na análise (sem limite de dados); até maxDriftWindows com as extras */
    static let driftWin = 6
    /** a leitura estimada das 3 janelas: o automático só roda se couber no orçamento da rede (ver AudioSyncAuto); o botão nunca pula */
    static let noLimit = Int.max
    private static let minDurationMs = 8 * 60_000
    private static let minSpeechCues = 40

    struct Stats {
        var vad = "?"
        var windows = 0
        var captureWallMs = 0, vadMs = 0, vadCpuMs = 0, solveMs = 0, solveCpuMs = 0, totalCpuMs = 0, totalWallMs = 0
        var estBytes = 0
        var rssStartKb = 0, rssEndKb = 0

        func summary() -> String {
            return String(format: "vad=%@ janelas=%d captura=%d ms vad=%d ms (cpu %d) solve=%d ms (cpu %d) cpu_thread=%d ms total=%d ms leitura_estimada=%d MB rss=%d->%d MB",
                          vad, windows, captureWallMs, vadMs, vadCpuMs, solveMs, solveCpuMs, totalCpuMs, totalWallMs, estBytes >> 20, rssStartKb >> 10, rssEndKb >> 10)
        }
    }

    enum Kind { case fix, ok, refused, error, cancelled, skipped }

    final class Outcome {
        var kind = Kind.error
        var note = ""
        /** modelo achado: tempo corrigido = scale * tempoDaLegenda + offsetMs (offset sem a banda morta) */
        var scale = 1.0, offsetMs = 0.0
        /** atraso (ms) que a legenda deve ter AGORA para ficar certa (igual a offsetMs com escala 1) */
        var targetDelay = 0
        var cuts = false
        /** a escala (1,001 ou outra razão de fps) foi aceita pela prova de deriva das janelas */
        var drift = false
        /** frase pronta, em português simples, para mostrar ao usuário quando nada foi mudado ("" = não há) */
        var msg = ""
        var result: AudioResult?
        var windows: [AudioWindow] = []
        var st = Stats()
    }

    final class Check {
        var out: AudioRecheck.Out?
        var window: AudioWindow?
        /** o modelo atual explica o áudio desta janela */
        var explained = false
        var kind = Kind.error
        var note = ""
        var tookMs = 0, cpuMs = 0
    }

    private let dir: URL
    private let cap = AudioCap()
    private var vad: AudioVad?
    private var vadName = "?"
    private let busyLock = NSLock()
    private var busyFlag = false
    /** só nos testes: força o VAD por DSP (reserva) em vez do Silero */
    var forceDsp = false
    private let runLock = NSRecursiveLock()
    var logger: ((String) -> Void)?

    init() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("frameplay-audiosync-\(ProcessInfo.processInfo.processIdentifier)")
    }

    var busy: Bool { busyLock.lock(); defer { busyLock.unlock() }; return busyFlag }
    private func setBusy(_ b: Bool) { busyLock.lock(); busyFlag = b; busyLock.unlock() }

    /** cancela o que estiver rodando (pode ser chamado de qualquer thread) */
    func cancel() { cap.cancel() }

    /** cancela e libera o player de captura e o ONNX Runtime sem prender quem chama (a thread principal): espera a execução acabar */
    func close() {
        cancel()
        DispatchQueue.global(qos: .utility).async { [self] in
            runLock.lock()
            cap.close()
            vad = nil
            runLock.unlock()
            try? FileManager.default.removeItem(at: dir)
        }
    }

    private func log(_ s: String) { logger?("AUDIOSYNC " + s) }

    private func theVad() -> AudioVad {
        if let v = vad { return v }
        if !forceDsp, let s = AudioSileroVad.make(log: { [weak self] in self?.log("Silero indisponível: " + $0) }) {
            vad = s
            vadName = "silero"
        } else {
            // ONNX Runtime não carregou neste Mac (biblioteca/arquitetura/memória): usa o VAD por DSP (sem biblioteca nativa)
            if !forceDsp { log("usando o VAD por DSP") }
            let d = AudioDspVad()
            vad = d
            vadName = d.name
        }
        return vad!
    }

    /**
     * O tamanho do arquivo (bytes) pelo servidor, ou -1. Muitos servidores de links diretos recusam o HEAD (403/405): então pede só o
     * primeiro byte (Range: bytes=0-0) e lê o total em Content-Range.
     */
    static func contentLength(_ url: URL) -> Int {
        guard let sc = url.scheme, sc.hasPrefix("http") else { return -1 }
        let n = probe(url, head: true)
        return n > 0 ? n : probe(url, head: false)
    }

    private static func probe(_ url: URL, head: Bool) -> Int {
        var req = URLRequest(url: url)
        req.httpMethod = head ? "HEAD" : "GET"
        req.timeoutInterval = 8
        req.setValue("Mozilla/5.0 (Macintosh) FramePlay", forHTTPHeaderField: "User-Agent")
        if !head { req.setValue("bytes=0-0", forHTTPHeaderField: "Range") }
        let sem = DispatchSemaphore(value: 0)
        var len = -1
        let task = URLSession.shared.dataTask(with: req) { _, resp, _ in
            if let h = resp as? HTTPURLResponse, h.statusCode < 400 {
                if let cr = h.value(forHTTPHeaderField: "Content-Range"), let slash = cr.lastIndex(of: "/"), let t = Int(cr[cr.index(after: slash)...].trimmingCharacters(in: .whitespaces)) { len = t }
                else if h.statusCode == 200, h.expectedContentLength > 0 { len = Int(h.expectedContentLength) }
            }
            sem.signal()
        }
        task.resume()
        if sem.wait(timeout: .now() + 9) == .timedOut { task.cancel(); return -1 }
        return len
    }

    /** leitura estimada (bytes) para `seconds` s de filme: o tamanho do arquivo repartido pela duração; -1 se não deu para saber */
    static func estimateBytes(_ url: URL, _ durMs: Int, _ seconds: Int) -> Int {
        let len = contentLength(url)
        return len > 0 && durMs > 0 ? Int(Double(len) / Double(durMs) * 1000.0 * Double(seconds)) : -1
    }

    static func mb(_ bytes: Int) -> String {
        return bytes >= 1 << 30 ? String(format: "%.1f GB", Double(bytes) / 1073741824.0).replacingOccurrences(of: ".", with: ",") : "\(bytes >> 20) MB"
    }

    private static func countSpeech(_ cues: [AudioCue]) -> Int {
        var n = 0
        for c in cues where c.speech { n += 1 }
        return n
    }

    // ---------------------------------------------------------------- medidas
    private static func threadCpuMs() -> Int {
        var ts = timespec()
        if clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0 { return 0 }
        return Int(ts.tv_sec) * 1000 + Int(ts.tv_nsec) / 1_000_000
    }

    private static func nowMs() -> Int { return Int(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    /** memória residente do processo, em KB */
    static func rssKb() -> Int {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Int(info.resident_size) / 1024 : 0
    }

    /** por que a última captura falhou (frase curta, para o registro e para o aviso) */
    private var lastCapFail = ""

    private func captureWindow(_ url: URL, _ startMs: Int, _ lenMs: Int, _ o: Outcome) -> AudioWindow? {
        let wav = dir.appendingPathComponent("w\(startMs).wav")
        let t0 = AudioSyncCore.nowMs()
        var co = cap.capture(url: url, startMs: startMs, durMs: lenMs, out: wav, timeoutS: 420)
        if !co.ok && !cap.isCancelled {
            log("captura falhou em \(startMs / 1000) s (tentativa 1): \(co.end)")
            Thread.sleep(forTimeInterval: 2.0)
            co = cap.capture(url: url, startMs: startMs, durMs: lenMs, out: wav, timeoutS: 420)
        }
        o.st.captureWallMs += AudioSyncCore.nowMs() - t0
        defer { try? FileManager.default.removeItem(at: wav) }
        if !co.ok { lastCapFail = co.end; log("captura falhou em \(startMs / 1000) s: \(co.end)"); return nil }
        log(String(format: "captura %d s +%d s: %.2f s de PCM em %.2f s (%@)", startMs / 1000, lenMs / 1000, co.pcmS, co.wallS, co.end))
        let pcm = AudioCap.readWav(wav)
        if pcm.count < 16000 * (lenMs / 1000) / 3 { return nil }
        let c0 = AudioSyncCore.threadCpuMs(), w0 = AudioSyncCore.nowMs()
        var vd = theVad()
        var p = vd.speechProb(pcm, pcm.count, 16000)
        if p.count < pcm.count / 160 - 2 && vadName == "silero" {
            // o ONNX Runtime falhou no meio: troca de vez para o VAD por DSP
            log("Silero falhou (\(audioVadLastError(vd))): usando o VAD por DSP")
            let d = AudioDspVad()
            vad = d; vadName = d.name; vd = d
            p = vd.speechProb(pcm, pcm.count, 16000)
        }
        o.st.vadCpuMs += AudioSyncCore.threadCpuMs() - c0
        o.st.vadMs += AudioSyncCore.nowMs() - w0
        o.st.vad = vadName
        return AudioWindow(startMs: startMs, p: p)
    }

    private func begin(_ o: Outcome) {
        o.st.rssStartKb = AudioSyncCore.rssKb()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func end(_ o: Outcome, _ cpu0: Int, _ wall0: Int) {
        o.st.totalCpuMs = AudioSyncCore.threadCpuMs() - cpu0
        o.st.totalWallMs = AudioSyncCore.nowMs() - wall0
        o.st.rssEndKb = AudioSyncCore.rssKb()
    }

    /**
     * Sincronização completa (3 janelas de 90 s no plano por densidade de falas).
     * nowMs: posição do vídeo agora; currentDelay: atraso aplicado agora (a decisão de mexer compara com ele);
     * maxBytes: pula (.skipped) se a leitura estimada das 3 janelas passar disto; noLimit = botão do usuário (nunca pula)
     */
    func run(url: URL, subText: String?, durMs: Int, nowMs: Int, currentDelay: Int, maxBytes: Int) -> Outcome {
        runLock.lock()
        defer { runLock.unlock() }
        setBusy(true)
        defer { setBusy(false) }
        let o = Outcome()
        let cpu0 = AudioSyncCore.threadCpuMs(), wall0 = AudioSyncCore.nowMs()
        defer { end(o, cpu0, wall0) }
        cap.reset()
        begin(o)
        let cues = AudioCue.parseSrt(subText)
        if AudioSyncCore.countSpeech(cues) < AudioSyncCore.minSpeechCues { o.kind = .refused; o.note = "poucas falas na legenda"; o.msg = "A legenda tem poucas falas para conferir pelo áudio."; return o }
        if durMs < AudioSyncCore.minDurationMs { o.kind = .skipped; o.note = "vídeo curto ou duração desconhecida"; o.msg = "O vídeo é curto (ou a duração ainda é desconhecida) para conferir pelo áudio."; return o }
        if maxBytes == AudioSyncCore.noLimit {
            // sem limite: a primeira captura começa já; a estimativa (só para o registro) é calculada ao lado, sem esperar
            let ob = o
            DispatchQueue.global(qos: .utility).async { ob.st.estBytes = AudioSyncCore.estimateBytes(url, durMs, AudioSyncCore.winMs * AudioSyncCore.nWin / 1000) }
        } else {
            o.st.estBytes = AudioSyncCore.estimateBytes(url, durMs, AudioSyncCore.winMs * AudioSyncCore.nWin / 1000)
        }
        if maxBytes != AudioSyncCore.noLimit && o.st.estBytes > maxBytes {
            o.kind = .skipped
            o.note = "leitura estimada de \(o.st.estBytes >> 20) MB passa do limite de \(maxBytes >> 20) MB: só pelo botão"
            o.msg = "Ficheiro muito pesado para analisar sozinho (≈ \(AudioSyncCore.mb(o.st.estBytes)) a ler). Usa Sincronizar automaticamente para analisar."
            return o
        }
        let P = AudioAligner.Params()
        // 3 janelas espalhadas pelo filme (o que mostra a deriva da escala) e, se as janelas confiáveis sugerem deriva sem prova ainda, até 2 extras
        var first = true
        var failedAt = 0
        var used: [AudioWindow] = []
        let c0 = AudioSyncCore.threadCpuMs(), w0 = AudioSyncCore.nowMs()
        let res = AudioSyncSession.analyze(cues, durMs, AudioSyncCore.driftWin, AudioSyncCore.winMs, { st, ln, i, total in
            if self.cap.isCancelled { return nil }
            if !first { Thread.sleep(forTimeInterval: 1.0) }   // respiro entre as capturas: o player ao lado não disputa o tempo todo
            first = false
            guard let w = self.captureWindow(url, st, ln, o) else { failedAt = i + 1; return nil }
            o.st.windows += 1
            return w
        }, P, spread: true, used: &used)
        guard let r = res else {
            if cap.isCancelled { o.kind = .cancelled; o.note = "cancelado"; return o }
            o.kind = .error
            o.note = "captura da janela \(failedAt) falhou: \(lastCapFail)"
            o.msg = "Não consegui ouvir o áudio do filme (\(lastCapFail))."
            return o
        }
        o.windows = used
        o.st.solveCpuMs = AudioSyncCore.threadCpuMs() - c0 - o.st.vadCpuMs
        o.st.solveMs = 0
        o.result = r
        o.cuts = r.cutsWarning
        AudioSyncCore.fill(o, r, nowMs, currentDelay, P)
        return o
    }

    /** Corpo do relatório de cada análise (no Mac vai só para o registro): só números do motor, sem título, endereço ou nome de arquivo. Porte de reportBody. */
    static func reportBody(_ o: Outcome, _ durMs: Int, _ extra: String) -> String {
        var b = ""
        let r = o.result
        b += "duracao_s=\(durMs / 1000) janelas=\(o.windows.count) rodadas=\(r?.rounds ?? 0) vad=\(o.st.vad) motor=\(version)\n"
        if let r = r {
            for (i, w) in r.wins.enumerated() {
                b += String(format: "j%d inicio_s=%d centro_s=%d atraso_ms=%.0f ncc=%.2f segundo=%.2f z=%.1f confiavel=%d\n", i + 1, w.startMs / 1000, jround(w.centerMs / 1000), w.delayMs, w.ncc, w.second, w.z, w.confident ? 1 : 0)
            }
            b += String(format: "ajuste escala=%.6f deslocamento_ms=%.0f inclinacao=%.6f residuo_ms=%.0f residuo_sem_escala_ms=%.0f deriva_janelas_ms=%.0f deriva_filme_ms=%.0f confiaveis=%d deriva_provada=%d\n",
                        r.scale, r.rawOffsetMs, r.driftSlope, r.driftResMs, r.unitResMs, r.driftMeasMs, r.driftContentMs, r.driftConfident, r.driftScale ? 1 : 0)
            b += "motivo=" + r.reason.trimmingCharacters(in: .whitespaces) + " | " + r.driftNote + "\n"
        }
        b += "resultado=\(o.kind) nota=\(o.note)\n"
        return b + extra
    }

    /** transforma o resultado do alinhador em decisão: mexer só se aceito e se o atraso certo difere do atual por mais que a banda morta */
    static func fill(_ o: Outcome, _ r: AudioResult, _ nowMs: Int, _ currentDelay: Int, _ P: AudioAligner.Params) {
        o.note = String(format: "%@ z=%.1f janelas=%d/%d%@", r.accepted ? "aceita" : "recusada", r.z, r.winsAgree, r.winsValid,
                        r.accepted ? "" : " (" + r.reason.trimmingCharacters(in: .whitespaces) + ")")
        if !r.accepted { o.kind = .refused; o.msg = "Sem confiança: o áudio não confirma esta legenda (pode ser de outra versão do filme)."; return }
        if let sg = r.segments, sg.count > 1 { o.kind = .refused; o.note += " por partes: não aplico"; o.msg = "A legenda parece ter cortes em relação ao filme: não mexi. Usa o ajuste manual."; return }
        let a = r.scale, off = r.rawOffsetMs
        o.scale = a
        o.offsetMs = off
        o.drift = r.driftScale
        let cue = (Double(nowMs) - off) / a                 // fala da legenda que aparece agora
        var target = (a - 1.0) * cue + off                  // atraso que a deixa no tempo certo agora
        target = max(-600_000, min(600_000, target))
        o.targetDelay = jround(target / 10.0) * 10
        let scaled = abs(a - 1.0) > 1e-9
        o.kind = (scaled || abs(Double(o.targetDelay - currentDelay)) >= P.deadbandMs) ? .fix : .ok
        o.note += String(format: " escala=%.5f deslocamento=%.0f ms", a, off) + (r.driftScale ? " (deriva provada pelas janelas)" : "")
    }

    // ---------------------------------------------------------------- reverificação

    /**
     * Reverificação (monitor): captura 90 s a partir da posição atual e chama o Recheck. Nunca muda nada sozinha; devolve o veredito.
     * model = (scale, offsetMs) em uso; failing = janelas recentes que o modelo não explicava (até 2)
     */
    func recheck(url: URL, subText: String?, durMs: Int, posMs: Int, scale: Double, offsetMs: Double, prev: AudioRecheck.Out?, failing: [AudioWindow], maxBytes: Int) -> Check {
        runLock.lock()
        defer { runLock.unlock() }
        setBusy(true)
        defer { setBusy(false) }
        let ck = Check()
        let tmp = Outcome()
        let cpu0 = AudioSyncCore.threadCpuMs(), wall0 = AudioSyncCore.nowMs()
        defer {
            end(tmp, cpu0, wall0)
            ck.tookMs = tmp.st.totalWallMs
            ck.cpuMs = tmp.st.totalCpuMs
            tmp.st.windows = ck.window == nil ? 0 : 1
            log("reverificação: " + tmp.st.summary())
        }
        cap.reset()
        begin(tmp)
        let cues = AudioCue.parseSrt(subText)
        if AudioSyncCore.countSpeech(cues) < AudioSyncCore.minSpeechCues || durMs < AudioSyncCore.minDurationMs { ck.kind = .skipped; ck.note = "sem condições"; return ck }
        let est = AudioSyncCore.estimateBytes(url, durMs, AudioSyncCore.winMs / 1000)
        if maxBytes != AudioSyncCore.noLimit && est > maxBytes {
            ck.kind = .skipped
            ck.note = "cada janela leria ≈ \(AudioSyncCore.mb(est)), acima do limite de \(AudioSyncCore.mb(maxBytes)): não reverifico"
            return ck
        }
        let st = max(0, min(posMs, durMs - AudioSyncCore.winMs)) / 10 * 10
        guard let w = captureWindow(url, st, AudioSyncCore.winMs, tmp) else { ck.kind = .error; ck.note = "captura falhou: \(lastCapFail)"; return ck }
        ck.window = w
        let cur = AudioResult()
        cur.accepted = true
        cur.scale = scale
        cur.offsetMs = offsetMs
        let wins = [w] + failing
        let ro = AudioRecheck.check(cues, wins, cur, posMs, durMs, false, prev, AudioAligner.Params(), AudioRecheck.Params())
        ck.out = ro
        ck.explained = ro.verdict == .keep && ro.reason == "ok"
        ck.kind = .ok
        ck.note = "\(ro.verdict) (\(ro.reason)) " + String(format: "ncc=%.2f z=%.1f", ro.nccAtCurrent, ro.z) + " análise=\(ro.tookMs) ms"
        return ck
    }

    /**
     * Um CHANGE da reverificação só vale depois de uma sessão completa em volta da posição com as mesmas portas do alinhamento inicial:
     * a janela já capturada + 2 à frente. Devolve o resultado (kind fix/ok/refused...).
     */
    func confirmChange(url: URL, subText: String?, durMs: Int, posMs: Int, scale: Double, offsetMs: Double, have: AudioWindow) -> Outcome {
        runLock.lock()
        defer { runLock.unlock() }
        setBusy(true)
        defer { setBusy(false) }
        let o = Outcome()
        let cpu0 = AudioSyncCore.threadCpuMs(), wall0 = AudioSyncCore.nowMs()
        defer {
            end(o, cpu0, wall0)
            log("confirmação do reajuste: " + o.st.summary())
        }
        cap.reset()
        begin(o)
        let cues = AudioCue.parseSrt(subText)
        let P = AudioAligner.Params()
        let ses = AudioSyncSession(cues: cues, mediaDurationMs: durMs, params: P)
        ses.addWindow(have)
        o.st.windows = 1
        for s0 in [have.startMs + 150_000, have.startMs + 300_000] {
            var s = min(s0, durMs - AudioSyncCore.winMs) / 10 * 10
            if s < have.startMs + 100_000 { s = max(0, have.startMs - (s0 - have.startMs)) / 10 * 10 }
            if cap.isCancelled { o.kind = .cancelled; return o }
            guard let w = captureWindow(url, s, AudioSyncCore.winMs, o) else { o.kind = .error; o.note = "captura falhou"; return o }
            ses.addWindow(w)
            o.st.windows += 1
            Thread.sleep(forTimeInterval: 1.0)
        }
        let r = ses.solve()
        o.result = r
        // o modelo atual, em tempo de legenda, na posição: o atraso certo agora vem do novo deslocamento
        let curDelay = jround((scale - 1.0) * ((Double(posMs) - offsetMs) / scale) + offsetMs)
        AudioSyncCore.fill(o, r, posMs, curDelay, P)
        return o
    }
}
