// Relatório anônimo de CADA tentativa de sincronização da legenda (botão Sincronizar e análise automática), igual ao ANALISE do Android
// (CrashReporter.analise): vai para o mesmo tópico do ntfy. Leva só números e rótulos: resultado, motivo, escala, deslocamento, z, origem da legenda
// (embutida/externa), pistas de fps, tipo de fluxo (http/local/arquivo, nunca o endereço), duração e a versão do app.
// Nada de título, endereço completo, nome de arquivo, nome do usuário ou do computador: endereços e nomes de arquivo que apareçam num texto viram "<url>" / "<arquivo>".
import Foundation

enum AnalysisReport {
    static let topic = "https://ntfy.sh/frameplay-crash-7f3k9q2w"
    /** registro do app (só nos testes vai a algum lugar) */
    static var log: ((String) -> Void)?
    /** só nos testes (FP_NO_REPORT=1): o relatório vai só para o registro, não para o ntfy */
    private static var offline: Bool {
        let v = ProcessInfo.processInfo.environment["FP_NO_REPORT"] ?? ""
        return v == "1" || v == "true"
    }
    /** só nos testes: o resultado do último envio ("HTTP 200"), para a prova ver que chegou */
    private(set) static var lastStatus = ""

    static func appVersion() -> String {
        return (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "?"
    }

    /** http / local (servidor de streaming na própria máquina) / arquivo: nunca o endereço */
    static func streamType(_ u: URL) -> String {
        if u.isFileURL { return "arquivo" }
        let host = (u.host ?? "").lowercased()
        let scheme = (u.scheme ?? "http").lowercased()
        if host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]" || host.hasSuffix(".local") { return "local(" + scheme + ")" }
        return scheme == "https" ? "https" : "http"
    }

    /** a razão de fps que a escala lembra (a legenda de 25 fps num filme de 23,976 fps é a mais comum) */
    static func fpsHint(_ scale: Double) -> String {
        if abs(scale - 1.0) < 1e-6 { return "sem escala" }
        let known: [(Double, String)] = [(25.0 / 23.976, "25/23.976"), (23.976 / 25.0, "23.976/25"), (24.0 / 23.976, "24/23.976"), (23.976 / 24.0, "23.976/24"),
                                         (25.0 / 24.0, "25/24"), (24.0 / 25.0, "24/25")]
        for (v, n) in known where abs(scale - v) < 0.0008 { return n }
        return String(format: "outra(%.5f)", scale)
    }

    static func clean(_ s: String) -> String {
        var t = s
        if let re = try? NSRegularExpression(pattern: "https?://\\S+") { t = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "<url>") }
        if let re = try? NSRegularExpression(pattern: "[A-Za-z0-9_.-]+\\.(mkv|mp4|avi|srt|m4v|webm|ts)\\b", options: .caseInsensitive) {
            t = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "<arquivo>")
        }
        return t
    }

    private static func osLine() -> String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        #if arch(arm64)
        let arch = "arm64"
        #else
        let arch = "x86_64"
        #endif
        return "Mac | macOS \(v.majorVersion).\(v.minorVersion).\(v.patchVersion) | \(arch)"
    }

    /** monta e envia (numa fila de fundo); nunca bloqueia nem falha para quem chama */
    static func send(origin: String, result: String, reason: String, scale: Double, offsetMs: Double, z: Double, subSource: String, fpsHint: String,
                     url: URL, durationMs: Int, attempt: Int, engineBody: String) {
        var b = "FramePlay \(appVersion()) | ANALISE | processo main\n"
        b += "Aparelho: \(osLine())\n"
        b += "origem=\(origin) tentativa=\(attempt)\n"
        b += "resultado=\(result)\n"
        b += "motivo=\(reason)\n"
        b += String(format: "escala=%.6f deslocamento_ms=%.0f z=%.1f\n", scale, offsetMs, z)
        b += "legenda=\(subSource) fps_dica=\(fpsHint)\n"
        b += "fluxo=\(streamType(url)) duracao_s=\(durationMs / 1000)\n"
        if !engineBody.isEmpty { b += "\n" + engineBody }
        let text = clean(b)
        if offline {
            log?("ANALISE (teste: não enviada)\n" + text)
            lastStatus = "offline"
            return
        }
        log?("ANALISE enviando ao ntfy (\(text.utf8.count) bytes)")
        DispatchQueue.global(qos: .utility).async { post(text) }
    }

    /** POST de texto simples ao tópico; o ntfy transforma mensagens maiores que ~4 KB em anexo: divide em partes de ~3,6 KB (no máximo 4) */
    private static func post(_ text: String) {
        let all = Array(text.utf8)
        let part = 3600
        let parts = min(4, max(1, (all.count + part - 1) / part))
        for p in 0..<parts {
            let chunk = Array(all[(p * part)..<min(all.count, (p + 1) * part)])
            var req = URLRequest(url: URL(string: topic)!, timeoutInterval: 12)
            req.httpMethod = "POST"
            req.httpBody = Data(chunk)
            req.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.setValue("FramePlay-crash", forHTTPHeaderField: "User-Agent")
            req.setValue("FramePlay \(appVersion()) ANALISE" + (parts > 1 ? " (\(p + 1)/\(parts))" : ""), forHTTPHeaderField: "Title")
            req.setValue("warning", forHTTPHeaderField: "Tags")
            let sem = DispatchSemaphore(value: 0)
            var code = -1
            URLSession.shared.dataTask(with: req) { _, resp, _ in
                code = (resp as? HTTPURLResponse)?.statusCode ?? -1
                sem.signal()
            }.resume()
            if sem.wait(timeout: .now() + 15) == .timedOut { code = -2 }
            lastStatus = "HTTP \(code)"
            log?("ANALISE ntfy parte \(p + 1)/\(parts): HTTP \(code)")
            if code < 200 || code >= 300 { return }
        }
    }
}
