import Foundation

/// Lê a release "mac" da API: o número do build (inteiro, o do run) e a versão de marketing ("2.0").
/// Formato novo: título "FramePlay para Mac", corpo "Versão 2.1 — …" + marcador invisível `<!-- build 1.0.46 -->`.
/// O marcador mantém o formato "1.0.<build>" que os Macs antigos (≤ 1.0.45) procuram com o regex `1\.0\.(\d+)`.
/// Formato antigo: "Versão 1.0.45 — …" (o build é o último número).
enum UpdateParse {
    static func firstGroup(_ pattern: String, in text: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        let ns = text as NSString
        guard let m = re.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges > 1,
              m.range(at: 1).location != NSNotFound else { return nil }
        return ns.substring(with: m.range(at: 1))
    }

    /// (build, versão de marketing). build = -1 se não achar.
    static func parse(name: String, body: String) -> (build: Int, marketing: String?) {
        let text = "\(name) \(body)"
        // marcador novo "build 1.0.46" ou formato antigo "1.0.45": os dois acabam em 1.0.<build>
        let build = firstGroup("1\\.0\\.(\\d+)", in: text).flatMap { Int($0) } ?? -1
        // versão de marketing: "Versão X.Y" no corpo (no formato antigo dá "1.0.45")
        let marketing =firstGroup("Vers[ãa]o\\s+(\\d+(?:\\.\\d+)+)", in: body)
        return (build, marketing)
    }
}
