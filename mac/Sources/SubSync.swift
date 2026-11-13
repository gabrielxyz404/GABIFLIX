// Escolha e alinhamento das legendas (Swift puro, só Foundation): porte fiel das partes puras do SubtitleSync.java do Android
// (busca no addon, alinhamento a uma referência, consenso entre PT, ranking). O hash do vídeo e as conexões ficam no player.
import Foundation

enum SubtitleSync {
    /// uma legenda baixada
    final class Cand {
        var url: String? = nil           // endereço de onde veio (identifica a legenda para guardar o atraso)
        var file: URL? = nil             // arquivo local (UTF-8) que o VLC lê
        var text: String? = nil          // texto UTF-8 atual (já alinhado), base para os ajustes manuais
        var lastMs: Int64 = 0            // fim da última fala
        var rank = 0                     // posição na lista (menor = melhor): resultados da busca com hash vêm primeiro
        var hashMatch = false            // o addon marcou como "casa com o hash do arquivo"
        var fixed = false                // tempos reescalados pela heurística de taxa de quadros
        var aligned = false              // alinhada a uma referência
        var refSelf = false              // é a legenda PT do hash usada como referência das outras (já está certa, não é reescrita)
        var alignNote = ""               // resultado do ajuste automático para o diagnóstico
        var alignScore = 0.0
        var alignScale = 1.0
        var alignOffsetMs: Int64 = 0
        var manualMs = 0
        var score = 0.0
        var rel = ""                     // nome da release da legenda (arquivo + release + grupo + formato, do addon)
        var group = -1                   // grupo de consenso (legendas PT com os mesmos tempos); -1 = não calculado
        var groupSize = 0
        var consensus = false            // escolhida por consenso (não alinhada: não há referência de tempo)
        var pieced = false               // ajuste por partes (cortes)
        var partial = false              // cobre só parte do vídeo (ex.: só o CD1 do filme): vai depois das completas
        var sparse = false               // tem muito menos falas que a referência: nunca é a primeira
        var sig = "raw"                  // assinatura da reescrita automática: o atraso manual guardado só vale para a MESMA reescrita
    }

    /// um resultado da busca no addon
    final class Found {
        let url: String
        let hashMatch: Bool
        let lang: String
        var rel = ""
        init(_ url: String, _ hashMatch: Bool, _ lang: String) { self.url = url; self.hashMatch = hashMatch; self.lang = lang }
    }

    /// resposta do addon: legendas em português + candidatas a referência de tempo (outros idiomas, nunca mostradas)
    final class SearchResult {
        var pt: [Found] = []
        var refs: [Found] = []
        var sawMatchField = false
        var refsAssumed = false
        var total = 0
        var ok = false
    }

    static func isPtLang(_ lang: String) -> Bool {
        return lang == "pob" || lang == "pt-br" || lang == "ptb" || lang == "ptbr" || lang == "por" || lang == "pt" || lang == "pt-pt"
    }

    private static func optString(_ o: [String: Any], _ k: String) -> String {
        guard let v = o[k] else { return "" }
        if let s = v as? String { return s }
        if v is NSNull { return "null" }
        if let n = v as? NSNumber { return n.stringValue }
        return "\(v)"
    }

    /// legendas do OpenSubtitles do Stremio: português (na ordem do addon, hash primeiro) e candidatas a referência (outro idioma)
    static func parseSearch(_ data: Data?) -> SearchResult {
        let res = SearchResult()
        guard let data = data,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = json["subtitles"] as? [Any] else { return res }
        res.ok = true
        res.total = arr.count
        var others: [Found] = [], ptAll: [Found] = []
        for item in arr {
            guard let o = item as? [String: Any] else { continue }
            let lang = optString(o, "lang").lowercased(), url = optString(o, "url")
            if !url.hasPrefix("http") { continue }
            if o["m"] != nil { res.sawMatchField = true }
            // o addon marca com m="f" as que casam com o hash do arquivo (m="i" = só o título)
            let hash = optString(o, "m") == "f"
            let fo = Found(url, hash, lang)
            let rel = optString(o, "subtitleFileName") + " " + optString(o, "movieReleaseName") + " " + optString(o, "releaseGroup") + " " + optString(o, "releaseFormat")
            fo.rel = J.str(J.trim(J.u16(rel)))
            if isPtLang(lang) { ptAll.append(fo) } else { others.append(fo) }
        }
        // as que casam com o hash vêm primeiro (mantém a ordem do addon dentro de cada grupo) antes de cortar em 10
        for pass in 0..<2 { for f in ptAll where f.hashMatch == (pass == 0) && res.pt.count < 10 { res.pt.append(f) } }
        // referência: de preferência inglês (eng) com hash; até 3
        for pass in 0..<2 {
            for f in others {
                if res.refs.count >= 3 { break }
                let eng = f.lang == "eng" || f.lang == "en"
                if f.hashMatch && (pass == 0) == eng { res.refs.append(f) }
            }
        }
        if res.refs.isEmpty && !res.sawMatchField {
            // sem o campo "m" não dá para saber quem casa com o hash: usa as primeiras (inglês antes) e o alinhamento confere
            res.refsAssumed = true
            for f in others where (f.lang == "eng" || f.lang == "en") && res.refs.count < 2 { res.refs.append(f) }
            for f in others where !res.refs.contains(where: { $0 === f }) && res.refs.count < 2 { res.refs.append(f) }
        }
        return res
    }

    // ---------- alinhamento a uma referência ----------
    final class AlignReport {
        var aligned = 0
        /// "outro idioma", "PT do hash", "consenso" (sem referência de tempo: só escolhe) ou "nenhuma"
        var refKind = "nenhuma"
        var pieced = 0
        var freeScaled = 0
        var consensusMax = 0
        var ptCount = 0
    }

    static func f2(_ v: Double) -> String { return J.fmt(v, 2).replacingOccurrences(of: ".", with: ",") }
    private static func f4(_ v: Double) -> String { return J.fmt(v, 4) }
    private static func f4c(_ v: Double) -> String { return J.fmt(v, 4).replacingOccurrences(of: ".", with: ",") }

    /// duas referências "concordam" se levam 90% das falas a menos de 2 s uma da outra
    static let AGREE_TOL_MS: Int64 = 2000
    static let AGREE_MIN = 0.90

    /// Alinha cada candidata (reescrevendo o arquivo) à melhor das referências; só reescreve se a nota global for boa E as duas
    /// metades baterem (senão é outra versão/corte do filme). Sem referência de outro idioma, a melhor PT que casa com o hash
    /// (a de menor posição) serve de referência para as outras PT. Sem nenhuma, não inventa nada.
    static func alignAll(_ all: [Cand], _ refs: [[Int64]], _ lg: StrBuf) -> AlignReport {
        let rep = AlignReport()
        markSparse(all, lg)
        var use = refs
        var selfC: Cand? = nil
        if !use.isEmpty { rep.refKind = "outro idioma" }
        else {
            // a PT do hash que serve de referência: a de menor posição entre as COMPLETAS (uma forçada tem poucas falas e não serve)
            var maxCues = 0
            for c in all where c.hashMatch && c.text != nil { maxCues = max(maxCues, SubtitleAlign.alignStarts(c.text!).count) }
            for c in all where c.hashMatch && c.text != nil && (selfC == nil || c.rank < selfC!.rank) {
                let st = SubtitleAlign.alignStarts(c.text!)
                if st.count >= 20 && Double(st.count) >= 0.6 * Double(maxCues) { selfC = c }
            }
            guard let sc = selfC else {
                consensus(all, rep, lg)
                return rep
            }
            use.append(SubtitleAlign.alignStarts(sc.text!))
            sc.refSelf = true
            sc.alignNote = "é a referência (PT do hash)"
            rep.refKind = "PT do hash"
            lg.a("\n  referência = PT do hash #\(sc.rank)")
        }
        for c in all {
            if c === selfC { continue }
            let starts = SubtitleAlign.alignStarts(c.text ?? "")
            var best: SubtitleAlign.Fit? = nil
            var oks: [SubtitleAlign.Fit] = []
            for r in use {
                let f = SubtitleAlign.align(r, starts)
                // prefere uma referência que valide por inteiro; entre iguais, a de maior nota
                if best == nil || (f.ok() && !best!.ok()) || (f.ok() == best!.ok() && f.score > best!.score) { best = f }
                if f.ok() { oks.append(f) }
                // com 2 referências que validam E concordam, está confirmado: não precisa das outras
                if oks.count >= 2 && SubtitleAlign.agreement(oks[0], oks[oks.count - 1], starts, AGREE_TOL_MS) >= AGREE_MIN { break }
            }
            guard var b = best else { continue }
            if oks.count >= 2 {
                // referências do mesmo vídeo têm de dar a MESMA transformação; se discordam, algo está errado: não alinha
                var pick: SubtitleAlign.Fit? = nil
                var bestVotes = 0
                for a in oks {
                    var votes = 0
                    for bb in oks where SubtitleAlign.agreement(a, bb, starts, AGREE_TOL_MS) >= AGREE_MIN { votes += 1 }
                    if votes > bestVotes || (votes == bestVotes && pick != nil && a.score > pick!.score) { bestVotes = votes; pick = a }
                }
                if bestVotes < 2 {
                    lg.a("\n  #\(c.rank) REFERÊNCIAS DISCORDAM (\(oks.count) validam com transformações diferentes): não alinha")
                    c.alignNote = "não alinhada: as referências de tempo discordam entre si"
                    continue
                }
                b = pick!
            }
            let nums = "nota=" + J.fmt(b.score, 2) + " metades=" + J.fmt(b.scoreFirstHalf, 2) + "/" + J.fmt(b.scoreSecondHalf, 2)
                + " escala=" + f4(b.scale) + " deslocamento=\(b.offsetMs)ms"
            lg.a("\n  #\(c.rank) alinhamento: " + nums)
            if !b.ok() {
                if b.sparse { c.sparse = true }
                if b.sparse { c.alignNote = "rejeitada: tem só \(J.round(b.cueRatio * 100))% das falas da referência (forçada ou incompleta)" }
                else if b.sparseRef { c.alignNote = "sem validação: a referência tem poucas falas" }
                else if b.differentVersion() { c.alignNote = "rejeitado: versão diferente (corte) — nota " + f2(b.score) + ", metades " + f2(b.scoreFirstHalf) + "/" + f2(b.scoreSecondHalf) }
                else { c.alignNote = "sem casamento com a referência (nota " + f2(b.score) + ")" }
                lg.a(" -> " + (b.differentVersion() ? "REJEITADO: corte/versão diferente" : "não casa"))
                continue
            }
            c.aligned = true
            c.sig = sigOf(b)
            c.alignScore = b.score
            c.partial = b.coverage < 0.75
            c.alignScale = b.scale
            c.alignOffsetMs = b.offsetMs
            rep.aligned += 1
            c.alignNote = "ajuste auto " + signedSecs(b.offsetMs) + (b.scale != 1.0 ? ", ×" + f4c(b.scale) : "")
            if b.piecewise() {
                let cuts = b.cuts!.count
                var sb = "ajuste por partes: \(cuts)" + (cuts == 1 ? " corte" : " cortes") + " ("
                for q in 0..<b.offs!.count { sb += (q > 0 ? "; " : "") + signedSecs(b.offs![q]) }
                c.alignNote = sb + ")" + (b.scale != 1.0 ? ", ×" + f4c(b.scale) : "")
                c.pieced = true
                rep.pieced += 1
                lg.a(" PARTES \(cuts) cortes")
            }
            if b.freeScale { rep.freeScaled += 1; lg.a(" escala-livre") }
            if c.partial {
                c.alignNote += " (parcial: cobre \(J.round(b.coverage * 100))% do tempo da referência)"
                lg.a(" PARCIAL " + J.fmt(b.coverage, 2))
            }
            if b.changes() {
                let t = b.apply(c.text ?? "")
                var wrote = false
                if let f = c.file { do { try Data(t.utf8).write(to: f); wrote = true } catch { wrote = false } }
                if wrote {
                    c.text = t
                    c.lastMs = SubtitleText.lastCueEnd(t)
                } else { c.aligned = false; c.alignNote = "falhou ao gravar o ajuste"; rep.aligned -= 1 }
            }
        }
        return rep
    }

    /// assinatura curta da reescrita (escala, deslocamento, cortes)
    static func sigOf(_ f: SubtitleAlign.Fit) -> String {
        var sb = "a" + f4(f.scale) + "/" + String(J.round(Double(f.offsetMs) / 100.0))
        if let cuts = f.cuts, let offs = f.offs {
            for i in 0..<cuts.count { sb += "/" + String(cuts[i] / 1000) + ":" + String(J.round(Double(offs[i + 1]) / 100.0)) }
        }
        return sb
    }

    /// legenda "forçada"/incompleta: tem muito menos falas que a mediana das candidatas (só com 3 ou mais)
    private static func markSparse(_ all: [Cand], _ lg: StrBuf) {
        var n: [Int] = []
        var cnt = [Int](repeating: 0, count: all.count)
        for i in 0..<all.count {
            let c = all[i]
            cnt[i] = c.text == nil ? 0 : SubtitleAlign.alignStarts(c.text!).count
            if cnt[i] >= 20 { n.append(cnt[i]) }
        }
        if n.count < 3 { return }
        n.sort()
        let med = n[n.count / 2]
        for i in 0..<all.count where Double(cnt[i]) < 0.4 * Double(med) {
            all[i].sparse = true
            lg.a("\n  #\(all[i].rank) parece forçada/incompleta: \(cnt[i]) falas (mediana \(med))")
        }
    }

    static func signedSecs(_ ms: Int64) -> String {
        let v = J.fmt(Double(abs(ms)) / 1000.0, 1).replacingOccurrences(of: ".", with: ",")
        return (ms > 0 ? "+" : ms < 0 ? "\u{2212}" : "") + v + " s"
    }

    // ---------- ranking ----------
    private static let FPS_FACTORS: [Double] = [25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0]

    private static func matchFactor(_ ratio: Double) -> Double {
        var best = 0.0, bestD = 0.012 // tolerância de 1,2%
        for f in FPS_FACTORS {
            let d = abs(ratio - f)
            if d < bestD { bestD = d; best = f }
        }
        return best
    }

    private static func plausible(_ lastMs: Int64, _ len: Int64) -> Bool {
        return lastMs > 0 && Double(lastMs) >= Double(len) * 0.6 && Double(lastMs) <= Double(len) * 1.05
    }

    /// Ordem final: primeiro as ALINHADAS a uma referência (maior nota primeiro); depois as demais pela heurística de fim da legenda,
    /// usada também quando não há referência (ver o comentário do SubtitleSync.rank no Android).
    static func rank(_ all: [Cand], _ len: Int64, _ videoName: String?, _ lg: StrBuf) -> [Cand] {
        var good: [Cand] = [], rest: [Cand] = []
        for c in all { if c.aligned || c.refSelf { good.append(c) } else { rest.append(c) } }
        good = SubtitleAlign.stableSorted(good) { a, b in
            if a.refSelf != b.refSelf { return a.refSelf }              // a PT do hash usada como referência vem primeiro
            if a.partial != b.partial { return !a.partial }              // as que cobrem só parte do vídeo (CD1/CD2) vão depois
            if a.alignScore != b.alignScore { return a.alignScore > b.alignScore }
            return a.rank < b.rank
        }
        var out = good
        if good.isEmpty {
            let cons = consensusPick(rest, len, videoName, lg)
            out.append(contentsOf: cons)
            rest.removeAll { c in cons.contains { $0 === c } }
        }
        out.append(contentsOf: rankHeuristic(rest, len, lg))
        // forçadas/incompletas nunca vão na frente das outras (ficam no menu, no fim)
        var full: [Cand] = [], thin: [Cand] = []
        for c in out { if c.sparse && !c.refSelf { thin.append(c) } else { full.append(c) } }
        full.append(contentsOf: thin)
        return full
    }

    // ---------- consenso (sem nenhuma referência de tempo) ----------
    private static func consensus(_ all: [Cand], _ rep: AlignReport, _ lg: StrBuf) {
        var use: [Cand] = []
        var st: [[Int64]] = []
        for c in all {
            c.group = -1
            let s = SubtitleAlign.alignStarts(c.text ?? "")
            if s.count >= 20 && !c.sparse { use.append(c); st.append(s) }
        }
        rep.ptCount = all.count
        if use.count < 2 {
            for c in all { c.alignNote = "sem referência — use Sincronizar pela fala" }
            lg.a("\n  sem referência (nem outro idioma, nem PT do hash) e menos de 2 PT para comparar: ranking antigo")
            return
        }
        let comp = SubtitleAlign.consensusGroups(st)
        var ng = 0
        for g in comp { ng = max(ng, g + 1) }
        var size = [Int](repeating: 0, count: ng)
        for g in comp { size[g] += 1 }
        for i in 0..<use.count { use[i].group = comp[i]; use[i].groupSize = size[comp[i]] }
        for g in size { rep.consensusMax = max(rep.consensusMax, g) }
        rep.refKind = "consenso"
        for c in all {
            if c.group < 0 { c.alignNote = "sem referência — use Sincronizar pela fala" }
            else if c.groupSize >= 2 { c.alignNote = "consenso: faz parte de um grupo de \(c.groupSize) PT iguais (sem alinhar)" }
            else { c.alignNote = "consenso: sem PT parecida" }
        }
        lg.a("\n  sem referência: consenso entre \(use.count) PT, grupos [" + size.map(String.init).joined(separator: ", ") + "]")
    }

    /// o grupo de consenso escolhido (hash, nome da release, duração), com os membros em ordem de preferência; vazio se não houver grupo de >= 2
    private static func consensusPick(_ rest: [Cand], _ len: Int64, _ videoName: String?, _ lg: StrBuf) -> [Cand] {
        var out: [Cand] = []
        var any = false
        for c in rest where c.group >= 0 && c.groupSize >= 2 { any = true }
        if !any { return out }
        let n = rest.count
        var comp = [Int](repeating: 0, count: n)
        var hash = [Bool](repeating: false, count: n)
        var name = [Double](repeating: 0, count: n)
        var last = [Int64](repeating: 0, count: n)
        var next = 0
        for c in rest { next = max(next, c.group + 1) }
        for i in 0..<n {
            let c = rest[i]
            if c.group >= 0 { comp[i] = c.group } else { comp[i] = next; next += 1 }
            hash[i] = c.hashMatch
            name[i] = SubtitleAlign.nameSimilarity(videoName, c.rel)
            last[i] = c.lastMs
        }
        let why = StrBuf()
        let g = SubtitleAlign.pickGroup(comp, hash, name, last, len, why)
        lg.a(why.s)
        if g < 0 { return out }
        var idx: [Int] = []
        for i in 0..<n where comp[i] == g { idx.append(i) }
        if idx.count < 2 { return out }
        idx = SubtitleAlign.stableSorted(idx) { a, b in
            let ca = rest[a], cb = rest[b]
            if ca.hashMatch != cb.hashMatch { return ca.hashMatch }
            if name[a] != name[b] { return name[a] > name[b] }
            return ca.rank < cb.rank
        }
        for k in 0..<idx.count {
            let c = rest[idx[k]]
            c.consensus = true
            c.alignNote = "escolhida por consenso: grupo de \(idx.count) PT iguais (escolhe, não alinha)"
            out.append(c)
        }
        lg.a("\n  consenso escolheu o grupo \(g) (\(idx.count) PT), primeira #\(out[0].rank)")
        return out
    }

    private static func rankHeuristic(_ all: [Cand], _ len: Int64, _ lg: StrBuf) -> [Cand] {
        let sorted = SubtitleAlign.stableSorted(all) { $0.rank < $1.rank }
        if len <= 0 || sorted.isEmpty { return sorted }

        var ref: Int64 = 0
        var refCand: Cand? = nil
        var why = "sem referência"
        for c in sorted where c.hashMatch && plausible(c.lastMs, len) { ref = c.lastMs; refCand = c; why = "legenda do hash #\(c.rank)"; break }
        if ref == 0 {
            var bestN = 0
            for a in sorted {
                if !plausible(a.lastMs, len) { continue }
                var n = 0
                for b in sorted where b !== a && b.lastMs > 0 && abs(Double(b.lastMs) / Double(a.lastMs) - 1) <= 0.015 { n += 1 }
                if n > bestN { bestN = n; ref = a.lastMs; refCand = a }
            }
            if ref > 0 { why = "grupo de \(bestN + 1) legendas parecidas" }
        }
        lg.a("\n  referência de fim=\(ref)ms (\(why)), duração=\(len)ms")

        if ref > 0 {
            for c in sorted {
                if c.hashMatch || c.lastMs <= 0 || c.text == nil || c.file == nil { continue }
                let f = matchFactor(Double(c.lastMs) / Double(ref))
                if f <= 0 { continue }
                let fixed = J.str(SubtitleAlign.transform(J.u16(c.text!), 1.0 / f, 0))
                let nl = SubtitleText.lastCueEnd(fixed)
                if nl <= 0 || abs(Double(nl) / Double(ref) - 1) > 0.015 { continue }
                // só o fim bate? Confirma pelas falas: depois da correção os inícios têm de concordar com os da legenda de referência
                guard let rc = refCand, let rt = rc.text else { continue }
                let rs = SubtitleAlign.alignStarts(rt), ns = SubtitleAlign.alignStarts(fixed)
                if SubtitleAlign.rawAgreement(ns, rs) < 0.4 || !SubtitleAlign.sameTiming(ns, rs) {
                    lg.a("\n  #\(c.rank) não corrigida: o fim sugere fator " + f4(f) + " mas as falas não confirmam")
                    continue
                }
                do { try Data(fixed.utf8).write(to: c.file!) } catch { continue }
                lg.a("\n  #\(c.rank) corrigida: fim \(c.lastMs)->\(nl)ms (fator " + f4(f) + ")")
                c.text = fixed
                c.lastMs = nl
                c.fixed = true
                c.sig = "fps" + f4(f)
            }
        }

        var use: [Cand] = []
        for c in sorted where c.lastMs <= 0 || (Double(c.lastMs) >= Double(len) * 0.5 && Double(c.lastMs) <= Double(len) * 1.12) { use.append(c) }
        if use.isEmpty { use.append(contentsOf: sorted) }
        for c in use {
            var fit: Double
            if c.lastMs <= 0 { fit = 0.3 }
            else {
                let d = ref > 0 ? abs(Double(c.lastMs) / Double(ref) - 1) : abs(Double(c.lastMs) / Double(len) - 0.93)
                fit = min(0.3, max(0, d - 0.005))
            }
            c.score = fit + 0.01 * Double(c.rank) + (c.fixed ? 0.01 : 0) - (c.hashMatch ? 0.02 : 0)
            lg.a("\n  #\(c.rank) fim=\(c.lastMs)ms hash=\(c.hashMatch) corrigida=\(c.fixed) nota=" + f4(c.score))
        }
        return SubtitleAlign.stableSorted(use) { $0.score < $1.score }
    }
}
