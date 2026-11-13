// Teste de paridade, lado Swift: roda o código portado (mac/Sources/SubText.swift, SubAlign.swift, SubSync.swift) sobre o MESMO corpus e os
// MESMOS casos sintéticos do lado Java (Paridade.java) e escreve os mesmos registros (TSV) para o compara.py comparar decisão por decisão.
// Uso: paridade corpusDir synDir saida.tsv [maxPt=14] [maxEn=4]
import Foundation

// ---------- hashes e formatos (iguais aos do Paridade.java) ----------
let FNV_BASIS: UInt64 = 0xcbf29ce484222325
let FNV_PRIME: UInt64 = 0x100000001b3
func fnv(_ s: String) -> UInt64 {
    var h = FNV_BASIS
    for c in s.utf16 {
        h ^= UInt64(c & 0xFF); h = h &* FNV_PRIME
        h ^= UInt64(c >> 8); h = h &* FNV_PRIME
    }
    return h
}
func fnv(_ a: [Int64]) -> UInt64 {
    var h = FNV_BASIS
    for v in a {
        let u = UInt64(bitPattern: v)
        for k in 0..<8 { h ^= (u >> UInt64(8 * k)) & 0xFF; h = h &* FNV_PRIME }
    }
    return h
}
func hx(_ v: UInt64) -> String { return String(format: "%016llx", v) }
func d9(_ v: Double) -> String { return J.fmt(v, 9) }
func csvL(_ a: [Int64]?) -> String { guard let a = a else { return "-" }; return a.isEmpty ? "-" : a.map(String.init).joined(separator: ",") }
func csvD(_ a: [Double]?) -> String { guard let a = a else { return "-" }; return a.isEmpty ? "-" : a.map(d9).joined(separator: ",") }
func csvI(_ a: [Int]?) -> String { guard let a = a else { return "-" }; return a.isEmpty ? "-" : a.map(String.init).joined(separator: ",") }
func b(_ v: Bool) -> String { return v ? "1" : "0" }

var REC = [String: String]()
let recLock = NSLock()
func rec(_ tag: String, _ key: String, _ kv: [String]) {
    var line = tag + "\t" + key
    for s in kv { line += "\t" + s }
    recLock.lock(); REC[tag + "\t" + key] = line; recLock.unlock()
}
func logLines(_ key: String, _ lg: String) {
    let ls = lg.components(separatedBy: "\n")
    for i in 0..<ls.count { rec("L", key + "|" + String(format: "%04d", i), ["t=" + ls[i].replacingOccurrences(of: "\t", with: " ")]) }
}

// ---------- dados ----------
final class Sub {
    var kind = "", lang = "", m = "", info = "", name = ""
    var raw: [UInt8] = []
    var text: String? = nil
    var starts: [Int64]? = nil
    var ptOk = false
    var idx = 0
}
final class Title {
    var key = "", listJson: String? = nil
    var scenario = false
    var scenLen: Int64 = 0
    var video: String? = nil
    var pt: [Sub] = [], en: [Sub] = [], oth: [Sub] = []
    func all() -> [Sub] { return pt + en + oth }
}

func firstMatch(_ s: String, _ pattern: String) -> [String]? {
    guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
    let ns = s as NSString
    guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
    var out: [String] = []
    for i in 0..<m.numberOfRanges { let r = m.range(at: i); out.append(r.location == NSNotFound ? "" : ns.substring(with: r)) }
    return out
}

func dirs(_ root: String) -> [String] {
    var isDir: ObjCBool = false
    guard FileManager.default.fileExists(atPath: root, isDirectory: &isDir), isDir.boolValue else { return [] }
    let names = (try? FileManager.default.contentsOfDirectory(atPath: root)) ?? []
    return names.sorted().filter { var d: ObjCBool = false; return FileManager.default.fileExists(atPath: root + "/" + $0, isDirectory: &d) && d.boolValue }
}

func loadTitle(_ root: String, _ name: String, _ prefix: String) -> Title? {
    let d = root + "/" + name
    guard let metaData = FileManager.default.contents(atPath: d + "/meta.txt") else { return nil }
    let meta = String(decoding: metaData, as: UTF8.self)
    var lines = meta.components(separatedBy: "\n")
    if lines.last == "" { lines.removeLast() }
    let t = Title()
    t.key = prefix + name
    let head = lines.first ?? ""
    t.scenario = head.contains(" scenario")
    if let m = firstMatch(head, "len=(\\d+)") { t.scenLen = Int64(m[1]) ?? 0 }
    if let m = firstMatch(head, "video=(\\S+)") { t.video = m[1] }
    for ln in lines {
        if ln.hasPrefix("#") || ln.isEmpty { continue }
        var f = ln.components(separatedBy: "\t")
        if f.count > 4 { f = Array(f[0..<3]) + [f[3...].joined(separator: "\t")] }
        let s = Sub()
        s.kind = f[0]; s.lang = f.count > 1 ? f[1] : ""; s.m = f.count > 2 ? f[2] : ""; s.info = f.count > 3 ? f[3] : ""
        if let m = firstMatch(s.info, "\"subtitleFileName\":\"([^\"]*)\"") { s.name = m[1] }
        guard let raw = FileManager.default.contents(atPath: d + "/" + s.kind + ".raw") else { continue }
        s.raw = [UInt8](raw)
        if let m = firstMatch(s.kind, "-(\\d+)$") { s.idx = Int(m[1]) ?? 0 }
        if s.kind.hasPrefix("pt") { t.pt.append(s) } else if s.kind.hasPrefix("en") { t.en.append(s) } else { t.oth.append(s) }
    }
    if let lj = FileManager.default.contents(atPath: d + "/list.json") { t.listJson = String(decoding: lj, as: UTF8.self) }
    return t
}

// ---------- 1. leitura ----------
func decodeAll(_ t: Title) {
    for s in t.all() {
        s.text = SubtitleText.decode(s.raw)
        guard let text = s.text else { rec("D", t.key + "|" + s.kind, ["dec=null"]); continue }
        let st = SubtitleAlign.alignStarts(text)
        s.starts = st
        let pt = SubtitleText.looksPortuguese(text)
        if s.kind.hasPrefix("pt") { s.ptOk = pt }
        let cs = SubtitleAlign.cueStarts(text)
        let cues = SubtitleAlign.cues(text)
        var hc = FNV_BASIS
        for c in cues { hc ^= UInt64(bitPattern: c.startMs); hc = hc &* FNV_PRIME; hc ^= fnv(c.text); hc = hc &* FNV_PRIME }
        rec("D", t.key + "|" + s.kind, ["dec=\(text.utf16.count):" + hx(fnv(text)), "pt=" + b(pt), "last=\(SubtitleText.lastCueEnd(text))",
            "ns=\(st.count):" + hx(fnv(st)), "cs=\(cs.count):" + hx(fnv(cs)), "cu=\(cues.count):" + hx(hc)])
    }
}

// ---------- 2. ajuste (Fit) ----------
func recFit(_ key: String, _ f: SubtitleAlign.Fit, _ text: String?) {
    var ap = "-"
    if f.ok() && f.changes(), let tx = text { ap = hx(fnv(f.apply(tx))) }
    rec("F", key, ["ok=" + b(f.ok()), "sc=" + d9(f.score), "h1=" + d9(f.scoreFirstHalf), "h2=" + d9(f.scoreSecondHalf), "scale=" + J.fmt(f.scale, 9),
        "off=\(f.offsetMs)", "cuts=" + csvL(f.cuts), "offs=" + csvL(f.offs), "cov=" + d9(f.coverage), "nul=" + d9(f.nullScore),
        "sp=" + b(f.sparse), "spr=" + b(f.sparseRef), "cr=" + d9(f.cueRatio), "fs=" + b(f.freeScale), "pok=" + b(f.pieceOk), "pw=" + b(f.piecewise()),
        "dv=" + b(f.differentVersion()), "ssc=" + csvD(f.segScores), "scu=" + csvI(f.segCues), "ap=" + ap])
}

// ---------- 3. pipeline ----------
let tmpDir = NSTemporaryDirectory() + "paridade-leg"
let tmpCounter = NSLock()
var tmpN = 0
func mk(_ p: Sub, _ rank: Int, _ hash: Bool) -> SubtitleSync.Cand {
    let c = SubtitleSync.Cand()
    tmpCounter.lock(); tmpN += 1; let n = tmpN; tmpCounter.unlock()
    c.file = URL(fileURLWithPath: tmpDir + "/leg\(n).srt")
    c.text = p.text; c.rank = rank; c.hashMatch = hash; c.url = "u\(rank)"; c.rel = p.name
    c.lastMs = SubtitleText.lastCueEnd(p.text ?? "")
    return c
}

func pipeline(_ key: String, _ all: [SubtitleSync.Cand], _ refs: [[Int64]], _ len: Int64, _ video: String?) {
    let lg = StrBuf()
    lg.a("alinhamento com \(refs.count) referência(s):")
    let ar = SubtitleSync.alignAll(all, refs, lg)
    let rk = SubtitleSync.rank(all, len, video, lg)
    let order = rk.map { String($0.rank) }.joined(separator: ",")
    var kv: [String] = []
    kv.append("rep=\(ar.aligned)/\(ar.refKind)/\(ar.pieced)/\(ar.freeScaled)/\(ar.consensusMax)/\(ar.ptCount)")
    kv.append("order=" + order)
    for c in all {
        kv.append("c\(c.rank)=" + b(c.aligned) + "|" + c.sig + "|" + c.alignNote.replacingOccurrences(of: "\t", with: " ") + "|\(c.lastMs)|" + b(c.sparse) + b(c.partial) + b(c.pieced) + b(c.consensus) + b(c.fixed) + b(c.refSelf)
            + "|\(c.group)|\(c.groupSize)|" + d9(c.alignScale) + "|\(c.alignOffsetMs)|" + d9(c.alignScore) + "|" + d9(c.score) + "|" + hx(fnv(c.text ?? "")))
    }
    rec("P", key, kv)
    logLines(key, lg.s)
}

func pipelines(_ t: Title) {
    if t.scenario {
        var all: [SubtitleSync.Cand] = []
        for p in t.pt where p.text != nil { all.append(mk(p, p.idx, p.m == "f")) }
        var refs: [[Int64]] = []
        for e in t.en { if let st = e.starts, st.count >= 20 { refs.append(st) } }
        pipeline(t.key + "|S", all, refs, t.scenLen, t.video)
        return
    }
    let P = Array(t.pt.filter { $0.ptOk && ($0.starts?.count ?? 0) >= 20 }.prefix(10))
    let E0 = t.en.filter { ($0.starts?.count ?? 0) >= 60 }
    if P.count < 2 || E0.isEmpty { return }
    var E: [Sub] = [E0[0]]
    var k = 1
    while k < E0.count && E.count < 3 {
        let g = SubtitleAlign.align(E0[0].starts!, E0[k].starts!)
        if g.ok() && !g.piecewise() && abs(g.scale - 1) < 0.0005 && abs(g.offsetMs) < 2000 && g.coverage >= 0.9 { E.append(E0[k]) }
        k += 1
    }
    let len = J.round(Double(SubtitleText.lastCueEnd(E[0].text!)) / 0.95)
    var a: [SubtitleSync.Cand] = []
    for i in 0..<P.count { a.append(mk(P[i], i, false)) }
    pipeline(t.key + "|A", a, E.map { $0.starts! }, len, nil)
    var bb: [SubtitleSync.Cand] = []
    for i in 0..<P.count { bb.append(mk(P[i], i, i == 0)) }
    pipeline(t.key + "|B", bb, [], len, nil)
    var cc: [SubtitleSync.Cand] = []
    for i in 0..<P.count { cc.append(mk(P[i], i, false)) }
    pipeline(t.key + "|C", cc, [], len, E[0].name)
}

// ---------- 4. busca no addon ----------
func search(_ t: Title) {
    guard let lj = t.listJson else { return }
    for variant in 0..<2 {
        let js = variant == 0 ? lj : lj.replacingOccurrences(of: "\"m\":", with: "\"mm\":")
        let r = SubtitleSync.parseSearch(js.data(using: .utf8))
        var kv: [String] = []
        kv.append("flags=" + b(r.ok) + b(r.sawMatchField) + b(r.refsAssumed) + "/\(r.total)")
        var p = ""
        for f in r.pt { p += hx(fnv(f.url)) + ":" + b(f.hashMatch) + ":" + f.lang + ":" + hx(fnv(f.rel)) + ";" }
        kv.append("pt=" + p)
        var q = ""
        for f in r.refs { q += hx(fnv(f.url)) + ":" + b(f.hashMatch) + ":" + f.lang + ":" + hx(fnv(f.rel)) + ";" }
        kv.append("refs=" + q)
        rec("S", t.key + "|\(variant)", kv)
    }
}

// ---------- 5. nomes e consenso ----------
func names(_ t: Title) {
    let ens = t.en.filter { $0.text != nil }
    guard let e0 = ens.first else { return }
    var sb = ""
    for p in t.pt { sb += d9(SubtitleAlign.nameSimilarity(e0.name, p.name)) + ","; sb += d9(SubtitleAlign.nameSimilarity(p.name, e0.name)) + ";" }
    rec("N", t.key, ["v=" + sb, "z=" + d9(SubtitleAlign.nameSimilarity(nil, "a")) + "/" + d9(SubtitleAlign.nameSimilarity("a.mkv", ""))])
}

func consensus(_ t: Title) {
    let P = t.pt.filter { $0.ptOk && ($0.starts?.count ?? 0) >= 20 }
    if P.count < 3 { return }
    let comp = SubtitleAlign.consensusGroups(P.map { $0.starts! })
    rec("K", t.key, ["comp=" + csvI(comp)])
    for e in t.en {
        guard let es = e.starts, es.count >= 20 else { continue }
        var hash = [Bool](repeating: false, count: P.count), nm = [Double](repeating: 0, count: P.count), last = [Int64](repeating: 0, count: P.count)
        for i in 0..<P.count { nm[i] = SubtitleAlign.nameSimilarity(e.name, P[i].name); last[i] = SubtitleText.lastCueEnd(P[i].text!); hash[i] = i == 1 }
        let len = J.round(Double(SubtitleText.lastCueEnd(e.text!)) / 0.95)
        let why = StrBuf()
        let g = SubtitleAlign.pickGroup(comp, hash, nm, last, len, why)
        rec("K", t.key + "|" + e.kind, ["g=\(g)", "why=" + hx(fnv(why.s))])
        logLines("K|" + t.key + "|" + e.kind, why.s)
    }
}

// ---------- principal ----------
final class Lite {
    var key = ""
    var pt: [[Int64]] = [], en: [[Int64]] = []
    var ptKinds: [String] = [], enKinds: [String] = []
}

func parseNums(_ s: String) -> [Int64] {
    if s.isEmpty { return [] }
    return s.split(separator: ",").map { Int64($0) ?? 0 }
}

let args = CommandLine.arguments
if args.count < 4 { print("uso: paridade corpusDir synDir saida.tsv [maxPt] [maxEn]"); exit(2) }
let corpus = args[1], syn = args[2]
let maxPt = args.count > 4 ? Int(args[4]) ?? 14 : 14, maxEn = args.count > 5 ? Int(args[5]) ?? 4 : 4
let t0 = Date()
try? FileManager.default.removeItem(atPath: tmpDir)
try? FileManager.default.createDirectory(atPath: tmpDir, withIntermediateDirectories: true)

var jobs: [(String, String, String)] = dirs(corpus).map { (corpus, $0, "") } + dirs(syn + "/titles").map { (syn + "/titles", $0, "syn/") }
jobs.sort { $0.2 + $0.1 < $1.2 + $1.1 }
FileHandle.standardError.write("Swift: \(jobs.count) títulos\n".data(using: .utf8)!)
var lites: [Lite] = []
let litesLock = NSLock()
DispatchQueue.concurrentPerform(iterations: jobs.count) { i in
    autoreleasepool {
        guard let t = loadTitle(jobs[i].0, jobs[i].1, jobs[i].2) else { return }
        decodeAll(t)
        let lt = Lite(); lt.key = t.key
        let ptS = Array(t.pt.filter { $0.ptOk && ($0.starts?.count ?? 0) >= 20 }.prefix(maxPt))
        let enS = Array(t.en.filter { ($0.starts?.count ?? 0) >= 20 }.prefix(maxEn))
        for p in ptS { lt.pt.append(p.starts!); lt.ptKinds.append(p.kind) }
        for e in enS { lt.en.append(e.starts!); lt.enKinds.append(e.kind) }
        if !t.scenario { litesLock.lock(); lites.append(lt); litesLock.unlock() }
        for p in ptS { for e in enS { recFit(t.key + "|" + p.kind + "|" + e.kind, SubtitleAlign.align(e.starts!, p.starts!), p.text) } }
        pipelines(t)
        names(t)
        consensus(t)
        if !t.scenario { search(t) }
    }
}
FileHandle.standardError.write("Swift: títulos prontos em \(Int(Date().timeIntervalSince(t0) * 1000)) ms\n".data(using: .utf8)!)

// casos numéricos sintéticos
let numFiles = ((try? FileManager.default.contentsOfDirectory(atPath: syn + "/nums")) ?? []).sorted()
DispatchQueue.concurrentPerform(iterations: numFiles.count) { i in
    let f = numFiles[i]
    guard let data = FileManager.default.contents(atPath: syn + "/nums/" + f) else { return }
    let ln = String(decoding: data, as: UTF8.self).components(separatedBy: "\n")
    let ref = parseNums(ln[0]), cand = parseNums(ln[1])
    recFit("G/" + f, SubtitleAlign.align(ref, cand), nil)
    rec("GS", "G/" + f, ["st=" + hx(fnv(ref)) + ":" + hx(fnv(cand))])
}

// falsos positivos
lites.sort { $0.key < $1.key }
let withEn = lites.filter { !$0.en.isEmpty }
var negJobs: [() -> Void] = []
for a in lites {
    if a.pt.isEmpty { continue }
    let idx = withEn.firstIndex { $0 === a } ?? -1
    var taken = 0
    var k = 1
    while k <= withEn.count && taken < 3 {
        let o = withEn[(max(idx, 0) + k) % withEn.count]
        k += 1
        if o === a { continue }
        taken += 1
        for p in 0..<min(2, a.pt.count) {
            negJobs.append { recFit("neg|" + a.key + "|" + a.ptKinds[p] + "|" + o.key + "|" + o.enKinds[0], SubtitleAlign.align(o.en[0], a.pt[p]), nil) }
        }
    }
}
DispatchQueue.concurrentPerform(iterations: negJobs.count) { i in negJobs[i]() }

let lines = REC.keys.sorted().map { REC[$0]! }
try? (lines.joined(separator: "\n") + "\n").write(toFile: args[3], atomically: true, encoding: .utf8)
FileHandle.standardError.write("Swift: \(REC.count) registros em \(Int(Date().timeIntervalSince(t0) * 1000)) ms\n".data(using: .utf8)!)
