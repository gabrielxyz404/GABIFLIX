// Captura de áudio para a sincronização pelo áudio: PCM mono 16 kHz de uma janela [início, início+duração) do MESMO endereço do vídeo,
// numa SEGUNDA instância do VLCKit (o player de reprodução não é tocado, nem a vista de vídeo). A mídia vai para uma cadeia de saída
// "transcode -> WAV em arquivo": sem saída de áudio o VLC não segue o relógio e lê/decodifica o mais rápido que a rede e a CPU deixam,
// com o corte exato (:start-time / :stop-time). Medido no macOS: 110-260x o tempo real. O VLCKit não chega a "ended" com saída em
// arquivo, então o fim é detectado pelo tamanho do WAV (32000 bytes por segundo). Uma captura por vez; sempre fora da thread principal.
import Foundation
import VLCKit

final class AudioCap {
    struct Out {
        var ok = false
        var end = "?"
        var wallS = 0.0
        var pcmS = 0.0
        /** segundos até chegar o primeiro dado (a latência do servidor + a busca no arquivo); -1 = nunca chegou */
        var firstDataS = -1.0
        /** a captura foi cortada (tempo ou leitura parada) mas já tinha áudio suficiente: vale como janela mais curta */
        var partial = false
    }

    private var player: VLCMediaPlayer?
    private let lock = NSLock()
    private var cancelledFlag = false

    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelledFlag }
    func cancel() { lock.lock(); cancelledFlag = true; lock.unlock() }
    func reset() { lock.lock(); cancelledFlag = false; lock.unlock() }

    /** as chamadas ao VLCKit vão sempre pela thread principal (como o resto do app); quem espera é a thread de fundo */
    private func onMain<T>(_ f: () -> T) -> T {
        if Thread.isMainThread { return f() }
        return DispatchQueue.main.sync(execute: f)
    }

    private func instance() -> VLCMediaPlayer {
        if let p = player { return p }
        let opts = ["--no-video", "--no-spu", "--no-sub-autodetect-file", "--no-lua", "--no-osd",
                    "--codec=avcodec", "--avcodec-hw=none", "--no-videotoolbox"]
        let p = VLCMediaPlayer(options: opts)
        player = p
        return p
    }

    /** solta o player de captura sem prender quem chama (a thread principal) */
    func close() {
        let p = player
        player = nil
        guard let pl = p else { return }
        DispatchQueue.main.async { pl.stop() }
    }

    /** Bloqueia até terminar (ou cancelar/estourar o tempo). Chamar só fora da thread principal.
        firstDataS: quanto esperar pelo PRIMEIRO dado (a busca no arquivo remoto); stallS: quanto aceitar sem crescer depois disso (rede ou servidor travado);
        minPartialS: se o tempo ou a leitura acabar com pelo menos tanto áudio, vale como janela mais curta (0 = só a janela inteira). */
    func capture(url: URL, startMs: Int, durMs: Int, out: URL, timeoutS: Double, firstDataS: Double = 60, stallS: Double = 30, minPartialS: Double = 0) -> Out {
        var r = Out()
        try? FileManager.default.removeItem(at: out)
        if isCancelled { r.end = "cancelado"; return r }
        let startS = Double(startMs) / 1000.0, durS = Double(durMs) / 1000.0
        let opts: [String] = [":no-video", ":no-spu", ":network-caching=600", ":http-reconnect",
                              ":start-time=\(String(format: "%.3f", startS))", ":stop-time=\(String(format: "%.3f", startS + durS))",
                              ":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=\(out.path)}"]
        var pl: VLCMediaPlayer! = nil
        onMain {
            let p = self.instance()
            let m = VLCMedia(url: url)
            for o in opts { m.addOption(o) }
            p.media = m
            p.play()
            pl = p
        }
        let t0 = Date()
        var why = "tempo esgotado"
        let need = Int(durS * 32000 * 0.985)
        let deadline = t0.addingTimeInterval(timeoutS)
        var lastGrow = t0, lastSz = 0
        while Date() < deadline {
            if isCancelled { why = "cancelado"; break }
            if let at = try? FileManager.default.attributesOfItem(atPath: out.path), let sz = at[.size] as? Int {
                if sz >= need { why = "fim"; break }
                if sz > lastSz { lastSz = sz; lastGrow = Date() }
            }
            // antes do primeiro dado a busca no arquivo remoto pode demorar (60 s); depois, 30 s parado = rede ou servidor travou
            if lastSz > 44 && r.firstDataS < 0 { r.firstDataS = Date().timeIntervalSince(t0) }
            if Date().timeIntervalSince(lastGrow) > (lastSz > 44 ? stallS : firstDataS) { why = lastSz > 44 ? "a leitura parou" : "sem dados do servidor"; break }
            let st = pl.state
            if Date().timeIntervalSince(t0) > 1.5 {
                if st == .error { why = "erro"; break }
                if st == .ended || st == .stopped { why = "fim"; break }
            }
            Thread.sleep(forTimeInterval: 0.05)
        }
        r.wallS = Date().timeIntervalSince(t0)
        let p2: VLCMediaPlayer = pl
        DispatchQueue.main.async { p2.stop() }
        r.end = why
        // o arquivo só fecha direito depois do stop: espera o tamanho parar de mudar (no máximo ~2,5 s)
        var lastSize = -1
        var stable = 0
        var waited = 0.0
        while waited < 2.5 && stable < 3 {
            Thread.sleep(forTimeInterval: 0.15)
            waited += 0.15
            let sz = ((try? FileManager.default.attributesOfItem(atPath: out.path))?[.size] as? Int) ?? -1
            if sz == lastSize { stable += 1 } else { stable = 0; lastSize = sz }
        }
        if let at = try? FileManager.default.attributesOfItem(atPath: out.path), let sz = at[.size] as? Int {
            r.pcmS = Double(max(0, sz - 44)) / 32000.0
            if minPartialS <= 0 || why == "fim" {
                r.ok = r.pcmS > 0 && !isCancelled
            } else if r.pcmS >= minPartialS && why != "cancelado" && why != "erro" {
                r.ok = true
                r.partial = true
            }
        }
        return r
    }

    /** lê o WAV (mono s16 16 kHz) para [Int16]; pula o cabeçalho procurando o bloco "data" */
    static func readWav(_ url: URL) -> [Int16] {
        guard let d = try? Data(contentsOf: url) else { return [] }
        let b = [UInt8](d)
        var p = 12
        while p + 8 <= b.count {
            let isData = b[p] == 0x64 && b[p + 1] == 0x61 && b[p + 2] == 0x74 && b[p + 3] == 0x61
            let sz = Int(b[p + 4]) | Int(b[p + 5]) << 8 | Int(b[p + 6]) << 16 | Int(b[p + 7]) << 24
            p += 8
            if isData {
                let avail = (b.count - p) / 2
                let n = (sz == 0 || sz > b.count) ? avail : min(avail, sz / 2)
                var s = [Int16](repeating: 0, count: n)
                for i in 0..<n { s[i] = Int16(bitPattern: UInt16(b[p + 2 * i]) | UInt16(b[p + 2 * i + 1]) << 8) }
                return s
            }
            p += sz
        }
        return []
    }
}
