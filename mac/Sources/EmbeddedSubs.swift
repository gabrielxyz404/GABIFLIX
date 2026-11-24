// Texto da legenda EMBUTIDA no arquivo (faixa de texto de um MKV/WebM), lido sem baixar o filme: o índice do Matroska (Cues) aponta para cada fala da faixa
// (cluster + posição dentro dele) e cada fala é lida com um pedido pequeno (Range) ao arquivo ou ao servidor de streaming. Com o texto e os tempos o app
// desenha a legenda ele mesmo (SubOverlay) e a sincronização pelo áudio passa a funcionar como numa legenda baixada. O Android não lê o texto embutido
// ("o texto embutido não é lido"): lá só as baixadas se sincronizam; aqui a embutida também.
//
// Limites (ditos ao usuário, nunca em silêncio): só MKV/WebM; só faixas de TEXTO (SRT, ASS/SSA; as de imagem, PGS/VobSub, não têm texto); o arquivo
// precisa ter as falas da faixa no índice (o mkvmerge e o ffmpeg põem). Sem isso a razão exata volta em Out.why.
import Foundation

protocol EmbByteSource: AnyObject {
    /** até len bytes a partir de pos (menos no fim do arquivo); nil se falhou (a razão fica em failure) */
    func read(_ pos: Int64, _ len: Int) -> Data?
    var failure: String { get }
    func close()
}

final class EmbFileSource: EmbByteSource {
    private let fh: FileHandle
    private let lock = NSLock()
    var failure = ""
    init?(_ url: URL) {
        guard let f = try? FileHandle(forReadingFrom: url) else { return nil }
        fh = f
    }
    func read(_ pos: Int64, _ len: Int) -> Data? {
        lock.lock(); defer { lock.unlock() }
        do {
            try fh.seek(toOffset: UInt64(max(0, pos)))
            let d = fh.readData(ofLength: len)
            return d.isEmpty ? nil : d
        } catch { failure = "\(error.localizedDescription)"; return nil }
    }
    func close() { try? fh.close() }
}

/** HTTP com Range (o servidor de streaming do Stremio, links diretos do debrid); vários pedidos ao mesmo tempo */
final class EmbHttpSource: NSObject, EmbByteSource, URLSessionDataDelegate {
    private final class Job {
        var data = Data()
        var status = 0
        var need = 0
        var failure = ""
        let sem = DispatchSemaphore(value: 0)
    }
    private let url: URL
    private var session: URLSession!
    private let lock = NSLock()
    private var jobs: [Int: Job] = [:]
    private var lastFailure = ""
    var failure: String { lock.lock(); defer { lock.unlock() }; return lastFailure }

    init(url: URL) {
        self.url = url
        super.init()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 20
        cfg.httpMaximumConnectionsPerHost = 6
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }

    func close() { session.invalidateAndCancel() }

    private func setFailure(_ s: String) { lock.lock(); lastFailure = s; lock.unlock() }

    func read(_ pos: Int64, _ len: Int) -> Data? {
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.setValue("bytes=\(pos)-\(pos + Int64(len) - 1)", forHTTPHeaderField: "Range")
        req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        req.setValue("FramePlay", forHTTPHeaderField: "User-Agent")
        let task = session.dataTask(with: req)
        let job = Job()
        job.need = len
        lock.lock(); jobs[task.taskIdentifier] = job; lock.unlock()
        task.resume()
        if job.sem.wait(timeout: .now() + 25) == .timedOut {
            task.cancel()
            lock.lock(); jobs[task.taskIdentifier] = nil; lock.unlock()
            setFailure("tempo esgotado")
            return nil
        }
        lock.lock(); jobs[task.taskIdentifier] = nil; lock.unlock()
        if job.status == 206 || (job.status == 200 && pos == 0) {
            if job.data.isEmpty { setFailure("resposta vazia"); return nil }
            return Data(job.data.prefix(len))
        }
        setFailure(job.failure.isEmpty ? "HTTP \(job.status)" : job.failure)
        return nil
    }

    private func job(_ t: URLSessionTask) -> Job? { lock.lock(); defer { lock.unlock() }; return jobs[t.taskIdentifier] }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        var r = request
        if let range = task.originalRequest?.value(forHTTPHeaderField: "Range") { r.setValue(range, forHTTPHeaderField: "Range") }
        r.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        completionHandler(r)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let j = job(dataTask), let h = response as? HTTPURLResponse else { completionHandler(.cancel); return }
        j.status = h.statusCode
        let pos = Int64((dataTask.originalRequest?.value(forHTTPHeaderField: "Range") ?? "").dropFirst(6).split(separator: "-").first.flatMap { Int64($0) } ?? 0)
        if !(h.statusCode == 206 || (h.statusCode == 200 && pos == 0)) {
            j.failure = h.statusCode == 200 ? "servidor sem suporte a Range" : "HTTP \(h.statusCode)"
            completionHandler(.cancel)
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let j = job(dataTask) else { return }
        j.data.append(data)
        if j.data.count >= j.need { dataTask.cancel() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let j = job(task) else { return }
        if let e = error, j.data.isEmpty, j.failure.isEmpty { j.failure = (e as NSError).code == NSURLErrorCancelled ? "cancelado" : e.localizedDescription }
        j.sem.signal()
    }
}

enum EmbeddedSubs {
    struct Out {
        var srt: String? = nil
        var cues = 0
        var why = ""
        var codec = ""
        var track = ""
        var ms = 0
    }

    struct Track {
        var number = 0
        var type = 0
        var codec = ""
        var lang = ""
        var name = ""
        var forced = false
        var isText: Bool { codec.hasPrefix("S_TEXT") }
        var isPt: Bool {
            let l = lang.lowercased()
            if l == "por" || l == "pob" || l.hasPrefix("pt") { return true }
            let n = name.lowercased()
            return n.contains("portug") || n.contains("brazil") || n.contains("brasil") || n.contains("pt-br")
        }
    }

    // ---------------------------------------------------------------- EBML

    private static let idEbml: UInt32 = 0x1A45DFA3, idSegment: UInt32 = 0x18538067, idSeekHead: UInt32 = 0x114D9B74, idSeek: UInt32 = 0x4DBB
    private static let idSeekId: UInt32 = 0x53AB, idSeekPos: UInt32 = 0x53AC, idInfo: UInt32 = 0x1549A966, idScale: UInt32 = 0x2AD7B1
    private static let idTracks: UInt32 = 0x1654AE6B, idEntry: UInt32 = 0xAE, idNumber: UInt32 = 0xD7, idType: UInt32 = 0x83, idCodec: UInt32 = 0x86
    private static let idLang: UInt32 = 0x22B59C, idLangBcp: UInt32 = 0x22B59D, idName: UInt32 = 0x536E, idForced: UInt32 = 0x55AA
    private static let idCues: UInt32 = 0x1C53BB6B, idCuePoint: UInt32 = 0xBB, idCueTime: UInt32 = 0xB3, idCuePos: UInt32 = 0xB7, idCueTrack: UInt32 = 0xF7
    private static let idCueCluster: UInt32 = 0xF1, idCueRel: UInt32 = 0xF0, idCueDur: UInt32 = 0xB2
    private static let idCluster: UInt32 = 0x1F43B675, idBlockGroup: UInt32 = 0xA0, idBlock: UInt32 = 0xA1, idSimple: UInt32 = 0xA3, idBlockDur: UInt32 = 0x9B

    struct Head { var id: UInt32; var size: Int64; var hl: Int; var unknown: Bool }

    static func readId(_ b: [UInt8], _ p: Int) -> (UInt32, Int)? {
        guard p >= 0, p < b.count else { return nil }
        let f = b[p]
        var l = 1
        var m: UInt8 = 0x80
        while l <= 4 && (f & m) == 0 { l += 1; m >>= 1 }
        if l > 4 || p + l > b.count { return nil }
        var v: UInt32 = 0
        for i in 0..<l { v = (v << 8) | UInt32(b[p + i]) }
        return (v, l)
    }

    /** (valor, bytes usados, tamanho desconhecido) */
    static func readSize(_ b: [UInt8], _ p: Int) -> (Int64, Int, Bool)? {
        guard p >= 0, p < b.count else { return nil }
        let f = b[p]
        var l = 1
        var m: UInt8 = 0x80
        while l <= 8 && (f & m) == 0 { l += 1; m >>= 1 }
        if l > 8 || p + l > b.count { return nil }
        var v = Int64(f & (m &- 1))
        for i in 1..<max(1, l) { v = (v << 8) | Int64(b[p + i]) }
        let allOnes = (Int64(1) << Int64(7 * l)) - 1
        return (v, l, v == allOnes)
    }

    static func head(_ b: [UInt8], _ p: Int) -> Head? {
        guard let (id, il) = readId(b, p), let (sz, sl, unk) = readSize(b, p + il) else { return nil }
        return Head(id: id, size: sz, hl: il + sl, unknown: unk)
    }

    static func uint(_ b: [UInt8], _ p: Int, _ n: Int) -> Int64 {
        var v: Int64 = 0
        for i in 0..<min(n, 8) where p + i < b.count { v = (v << 8) | Int64(b[p + i]) }
        return v
    }

    static func str(_ b: [UInt8], _ p: Int, _ n: Int) -> String {
        let e = min(b.count, p + n)
        if p >= e { return "" }
        var s = Array(b[p..<e])
        while let l = s.last, l == 0 { s.removeLast() }
        return String(decoding: s, as: UTF8.self)
    }

    /** filhos de um elemento já em memória: (id, posição do corpo, tamanho do corpo) */
    static func children(_ b: [UInt8], _ from: Int, _ to: Int) -> [(UInt32, Int, Int)] {
        var out: [(UInt32, Int, Int)] = []
        var p = from
        let end = min(to, b.count)
        while p < end, let h = head(b, p) {
            let body = p + h.hl
            let sz = h.unknown ? end - body : Int(min(h.size, Int64(end - body)))
            if sz < 0 { break }
            out.append((h.id, body, sz))
            p = body + sz
        }
        return out
    }

    // ---------------------------------------------------------------- leitura

    /** Lê a faixa de texto de ordem `ordinal` (entre as faixas de legenda do arquivo, na ordem do arquivo; o VLC lista na mesma ordem); se ela não for
        em português e houver uma única de texto em português, usa essa. cancelled: devolve true para parar; deadline: limite de tempo. */
    static func extract(source src: EmbByteSource, ordinal: Int, cancelled: @escaping () -> Bool, deadline: Date, log: @escaping (String) -> Void) -> Out {
        var out = Out()
        let t0 = Date()
        func fail(_ why: String) -> Out { out.why = why; out.ms = Int(Date().timeIntervalSince(t0) * 1000); return out }

        guard let first = src.read(0, 256 * 1024) else { return fail("não consegui ler o começo do arquivo (\(src.failure))") }
        let b0 = [UInt8](first)
        guard let eh = head(b0, 0), eh.id == idEbml else { return fail("o arquivo não é MKV (só MKV/WebM tem a legenda lida)") }
        let segPos = Int(eh.size) + eh.hl
        guard let sh = head(b0, segPos), sh.id == idSegment else { return fail("MKV sem segmento") }
        let segStart = Int64(segPos + sh.hl)

        // elementos de nível 1 antes do primeiro cluster
        func readAt(_ abs: Int64, _ len: Int) -> [UInt8]? {
            if abs + Int64(len) <= Int64(b0.count) { return Array(b0[Int(abs)..<Int(abs) + len]) }
            if abs < Int64(b0.count) {
                // parte já em memória, resto lido
                guard let d = src.read(abs, len) else { return nil }
                return [UInt8](d)
            }
            guard let d = src.read(abs, len) else { return nil }
            return [UInt8](d)
        }
        var seek: [UInt32: Int64] = [:]
        var tracksAt: (Int64, Int)? = nil
        var infoAt: (Int64, Int)? = nil
        var cuesAt: (Int64, Int)? = nil
        var q = segStart
        var n = 0
        while n < 64 {
            n += 1
            guard let hb = readAt(q, 16), let h = head(hb, 0) else { break }
            if h.id == idCluster { break }
            if h.unknown { break }
            let body = q + Int64(h.hl)
            let sz = Int(min(h.size, Int64(Int32.max)))
            switch h.id {
            case idSeekHead:
                if let bb = readAt(body, min(sz, 64 * 1024)) {
                    for (id, p, l) in children(bb, 0, bb.count) where id == idSeek {
                        var sid: UInt32 = 0
                        var spos: Int64 = -1
                        for (cid, cp, cl) in children(bb, p, p + l) {
                            if cid == idSeekId { sid = UInt32(truncatingIfNeeded: uint(bb, cp, cl)) }
                            if cid == idSeekPos { spos = uint(bb, cp, cl) }
                        }
                        if spos >= 0 { seek[sid] = spos }
                    }
                }
            case idTracks: tracksAt = (body, sz)
            case idInfo: infoAt = (body, sz)
            case idCues: cuesAt = (body, sz)
            default: break
            }
            q = body + Int64(sz)
        }
        if tracksAt == nil, let p = seek[idTracks] { if let hb = readAt(segStart + p, 16), let h = head(hb, 0), h.id == idTracks { tracksAt = (segStart + p + Int64(h.hl), Int(min(h.size, 4_000_000))) } }
        if infoAt == nil, let p = seek[idInfo] { if let hb = readAt(segStart + p, 16), let h = head(hb, 0), h.id == idInfo { infoAt = (segStart + p + Int64(h.hl), Int(min(h.size, 100_000))) } }
        if cuesAt == nil, let p = seek[idCues] { if let hb = readAt(segStart + p, 16), let h = head(hb, 0), h.id == idCues { cuesAt = (segStart + p + Int64(h.hl), Int(min(h.size, 32_000_000))) } }
        guard let (tpos, tlen) = tracksAt, let tb = readAt(tpos, tlen) else { return fail("MKV sem lista de faixas") }

        var scaleNs: Int64 = 1_000_000
        if let (ip, il) = infoAt, let ib = readAt(ip, il) {
            for (id, p, l) in children(ib, 0, ib.count) where id == idScale { scaleNs = max(1, uint(ib, p, l)) }
        }

        var tracks: [Track] = []
        for (id, p, l) in children(tb, 0, tb.count) where id == idEntry {
            var t = Track()
            for (cid, cp, cl) in children(tb, p, p + l) {
                switch cid {
                case idNumber: t.number = Int(uint(tb, cp, cl))
                case idType: t.type = Int(uint(tb, cp, cl))
                case idCodec: t.codec = str(tb, cp, cl)
                case idLang: if t.lang.isEmpty || t.lang == "eng" { t.lang = str(tb, cp, cl) }
                case idLangBcp: t.lang = str(tb, cp, cl)
                case idName: t.name = str(tb, cp, cl)
                case idForced: t.forced = uint(tb, cp, cl) != 0
                default: break
                }
            }
            tracks.append(t)
        }
        let subs = tracks.filter { $0.type == 17 }
        if subs.isEmpty { return fail("o arquivo não tem faixas de legenda") }
        var pick: Track? = ordinal >= 0 && ordinal < subs.count ? subs[ordinal] : nil
        if pick == nil || !(pick!.isPt) {
            let pts = subs.filter { $0.isPt && $0.isText }
            if pts.count == 1 { pick = pts[0] }
            else if pick == nil { pick = pts.first(where: { !$0.forced }) ?? pts.first }
        }
        guard let tr = pick else { return fail("não achei a faixa de legenda no arquivo") }
        out.codec = tr.codec
        out.track = tr.name.isEmpty ? tr.lang : tr.name
        if !tr.isText { return fail("a legenda do vídeo é em imagem (\(tr.codec)): não tem texto para ler") }
        guard let (cp, cl) = cuesAt else { return fail("o MKV não tem índice (Cues): não dá para achar as falas sem ler o filme todo") }
        guard let cb = readAt(cp, cl) else { return fail("não consegui ler o índice do MKV (\(src.failure))") }

        // falas da faixa no índice: (tempo em ticks, posição do cluster, posição relativa, duração em ticks)
        struct Ent { var t: Int64; var cluster: Int64; var rel: Int64; var dur: Int64 }
        var ents: [Ent] = []
        for (id, p, l) in children(cb, 0, cb.count) where id == idCuePoint {
            var time: Int64 = 0
            var tps: [(Int64, Int64, Int64, Int64, Int)] = []
            for (cid, ccp, ccl) in children(cb, p, p + l) {
                if cid == idCueTime { time = uint(cb, ccp, ccl) }
                if cid == idCuePos {
                    var tn = 0
                    var cluster: Int64 = -1
                    var rel: Int64 = -1
                    var dur: Int64 = 0
                    for (xid, xp, xl) in children(cb, ccp, ccp + ccl) {
                        if xid == idCueTrack { tn = Int(uint(cb, xp, xl)) }
                        if xid == idCueCluster { cluster = uint(cb, xp, xl) }
                        if xid == idCueRel { rel = uint(cb, xp, xl) }
                        if xid == idCueDur { dur = uint(cb, xp, xl) }
                    }
                    tps.append((0, cluster, rel, dur, tn))
                }
            }
            for tp in tps where tp.4 == tr.number && tp.1 >= 0 { ents.append(Ent(t: time, cluster: tp.1, rel: tp.2, dur: tp.3)) }
        }
        log("EMBUTIDA faixa \(tr.number) \(tr.codec) lang=\(tr.lang) falas no índice: \(ents.count)")
        if ents.count < 40 { return fail(ents.isEmpty ? "o índice do MKV não lista as falas da legenda (sem como achá-las sem ler o filme todo)" : "poucas falas no índice da legenda (\(ents.count))") }
        if cancelled() { return fail("cancelado") }

        // cada fala: um pedido pequeno. O cabeçalho do cluster costuma ter o mesmo tamanho em todos: aprende no primeiro e confere em cada leitura.
        let lock = NSLock()
        var hlCache: [Int64: Int] = [:]
        var guessHl = 0
        func clusterHl(_ c: Int64) -> Int? {
            lock.lock(); let known = hlCache[c]; lock.unlock()
            if let k = known { return k }
            guard let d = src.read(segStart + c, 16), let h = head([UInt8](d), 0), h.id == idCluster else { return nil }
            lock.lock(); hlCache[c] = h.hl; if guessHl == 0 { guessHl = h.hl }; lock.unlock()
            return h.hl
        }
        struct Got { var start: Int64; var end: Int64; var text: String }
        var got = [Got?](repeating: nil, count: ents.count)
        var bad = 0
        var timedOut = false
        let tnum = tr.number
        let isAss = tr.codec.contains("ASS") || tr.codec.contains("SSA")

        func parseBlock(_ buf: [UInt8]) -> (String, Int64)? {
            guard let h = head(buf, 0) else { return nil }
            var payload: (Int, Int)? = nil
            var dur: Int64 = -1
            if h.id == idSimple {
                payload = (h.hl, Int(min(h.size, Int64(buf.count - h.hl))))
            } else if h.id == idBlockGroup {
                for (id, p, l) in children(buf, h.hl, min(buf.count, h.hl + Int(min(h.size, Int64(buf.count))))) {
                    if id == idBlock && payload == nil { payload = (p, l) }
                    if id == idBlockDur { dur = uint(buf, p, l) }
                }
            } else { return nil }
            guard let (pp, pl) = payload, pl > 4, let (trk, tl, _) = readSize(buf, pp), Int(trk) == tnum else { return nil }
            guard pp + tl + 3 <= buf.count else { return nil }
            let flags = buf[pp + tl + 2]
            if (flags >> 1) & 3 != 0 { return nil }   // legenda de texto não usa "lacing"
            let fs = pp + tl + 3
            let fe = min(buf.count, pp + pl)
            if fs >= fe { return ("", dur) }
            var frame = Array(buf[fs..<fe])
            while let l = frame.last, l == 0 { frame.removeLast() }
            return (String(decoding: frame, as: UTF8.self), dur)
        }

        func fetch(_ i: Int) -> Got? {
            let e = ents[i]
            for attempt in 0..<2 {
                let hl: Int
                if attempt == 0 {
                    lock.lock(); let g = guessHl; lock.unlock()
                    if g > 0 { hl = g } else if let k = clusterHl(e.cluster) { hl = k } else { return nil }
                } else {
                    lock.lock(); hlCache[e.cluster] = nil; lock.unlock()
                    guard let k = clusterHl(e.cluster) else { return nil }
                    hl = k
                }
                let pos = segStart + e.cluster + Int64(hl) + max(0, e.rel)
                guard let d = src.read(pos, 4096) else { continue }
                if let (txt, bd) = parseBlock([UInt8](d)) {
                    let start = e.t * scaleNs / 1_000_000
                    let durTicks = bd >= 0 ? bd : (e.dur > 0 ? e.dur : 3000 * 1_000_000 / scaleNs)
                    let end = start + max(300, durTicks * scaleNs / 1_000_000)
                    return Got(start: start, end: end, text: txt)
                }
            }
            return nil
        }

        let workers = 6
        DispatchQueue.concurrentPerform(iterations: workers) { w in
            var i = w
            while i < ents.count {
                if cancelled() || Date() > deadline { lock.lock(); timedOut = timedOut || Date() > deadline; lock.unlock(); return }
                let g = fetch(i)
                lock.lock()
                if let g = g { got[i] = g } else { bad += 1 }
                let tooBad = bad > 30 && bad * 4 > ents.count
                lock.unlock()
                if tooBad { return }
                i += workers
            }
        }
        if cancelled() { return fail("cancelado") }
        if timedOut { return fail("a leitura da legenda do vídeo demorou demais (\(Int(Date().timeIntervalSince(t0))) s)") }
        lock.lock(); let nbad = bad; lock.unlock()
        if nbad > 30 && nbad * 4 > ents.count { return fail("o índice do MKV não bate com o conteúdo (\(nbad) de \(ents.count) falas ilegíveis)") }

        // texto -> SRT
        var items: [(Int64, Int64, String)] = []
        var seen = Set<String>()
        for g in got {
            guard let g = g else { continue }
            var t = g.text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            if isAss {
                // "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text": o texto vem depois da 8ª vírgula
                var commas = 0
                var idx = t.startIndex
                while idx < t.endIndex && commas < 8 { if t[idx] == "," { commas += 1 }; idx = t.index(after: idx) }
                t = commas == 8 ? String(t[idx...]) : t
                t = t.replacingOccurrences(of: "\\N", with: "\n").replacingOccurrences(of: "\\n", with: "\n").replacingOccurrences(of: "\\h", with: " ")
                if let re = try? NSRegularExpression(pattern: "\\{[^}]*\\}") { t = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "") }
            }
            t = t.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty { continue }
            if !seen.insert("\(g.start)|\(t)").inserted { continue }
            items.append((g.start, g.end, t))
        }
        items.sort { $0.0 < $1.0 }
        if items.count < 40 { return fail("poucas falas legíveis na legenda do vídeo (\(items.count))") }
        func ts(_ v: Int64) -> String {
            let x = max(0, v)
            return String(format: "%02d:%02d:%02d,%03d", Int(x / 3_600_000), Int(x / 60_000 % 60), Int(x / 1000 % 60), Int(x % 1000))
        }
        var sb = ""
        sb.reserveCapacity(items.count * 80)
        for (i, it) in items.enumerated() { sb += "\(i + 1)\n\(ts(it.0)) --> \(ts(it.1))\n\(it.2)\n\n" }
        out.srt = sb
        out.cues = items.count
        out.ms = Int(Date().timeIntervalSince(t0) * 1000)
        return out
    }

    /** a fonte de bytes certa para o endereço do filme (arquivo local ou HTTP) */
    static func source(for url: URL) -> EmbByteSource? {
        if url.isFileURL { return EmbFileSource(url) }
        if let s = url.scheme?.lowercased(), s == "http" || s == "https" { return EmbHttpSource(url: url) }
        return nil
    }
}
