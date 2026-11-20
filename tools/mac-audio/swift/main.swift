// Testes do motor de áudio em Swift (macOS): paridade com o motor Java (vetores, planos, SRT, DSP), fuzz sem travar, Silero x Python e o
// "PreCheck" de ponta a ponta (mesmo comando do PreCheck.java).
//   audiotest parity vectors.json expected.txt
//   audiotest silero modelo.onnx audio.wav ref.f32
//   audiotest pre audio16k.wav legenda.srt            (usa FP_SILERO_MODEL / FP_ORT_LIB; sem eles, DSP)
import Foundation

var failures = 0
func check(_ ok: Bool, _ msg: String) {
    print((ok ? "OK     " : "FALHA  ") + msg)
    if !ok { failures += 1 }
}

func readWav(_ path: String) -> [Int16] {
    guard let d = FileManager.default.contents(atPath: path) else { return [] }
    let b = [UInt8](d)
    var p = 12
    while p + 8 <= b.count {
        let isData = b[p] == 0x64 && b[p + 1] == 0x61 && b[p + 2] == 0x74 && b[p + 3] == 0x61
        let sz = Int(b[p + 4]) | Int(b[p + 5]) << 8 | Int(b[p + 6]) << 16 | Int(b[p + 7]) << 24
        p += 8
        if isData {
            let n = min((b.count - p) / 2, sz / 2)
            var s = [Int16](repeating: 0, count: n)
            for i in 0..<n { s[i] = Int16(bitPattern: UInt16(b[p + 2 * i]) | UInt16(b[p + 2 * i + 1]) << 8) }
            return s
        }
        p += sz
    }
    return []
}

func ms(_ t0: DispatchTime) -> Int { return Int((DispatchTime.now().uptimeNanoseconds - t0.uptimeNanoseconds) / 1_000_000) }

// ------------------------------------------------------------------ vetores do motor (os mesmos do CI do Android)
func runVectors(_ path: String) {
    guard let data = FileManager.default.contents(atPath: path),
          let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
          let cases = root["cases"] as? [[String: Any]] else { check(false, "vetores ilegíveis: \(path)"); return }
    let P = AudioAligner.Params()
    var bad = 0, n = 0
    for c in cases {
        let name = c["name"] as? String ?? "?"
        let dur = (c["durationMs"] as! NSNumber).intValue
        var cues: [AudioCue] = []
        for q in c["cues"] as! [[NSNumber]] {
            cues.append(AudioCue(startMs: q[0].intValue, endMs: q[1].intValue, chars: q[2].intValue, speech: q[3].doubleValue != 0))
        }
        var ws: [AudioWindow] = []
        for w in c["windows"] as! [[String: Any]] {
            let u8 = Data(base64Encoded: w["u8"] as! String)!
            var pr = [Float](repeating: 0, count: u8.count)
            for i in 0..<u8.count { pr[i] = Float(u8[i]) / 255 }
            ws.append(AudioWindow(startMs: (w["startMs"] as! NSNumber).intValue, p: pr))
        }
        let t0 = DispatchTime.now()
        let r = AudioAligner.solve(cues, ws, dur, P)
        let took = ms(t0)
        let e = c["expect"] as! [String: Any]
        let acc = (e["accepted"] as! NSNumber).boolValue
        let eo = (e["offsetMs"] as! NSNumber).doubleValue, es = (e["scale"] as! NSNumber).doubleValue, ez = (e["z"] as! NSNumber).doubleValue
        let ok = r.accepted == acc && (!acc || (abs(r.offsetMs - eo) <= 1 && abs(r.scale - es) <= 1e-6)) && abs(r.z - ez) <= 0.05
        n += 1
        if !ok { bad += 1 }
        print(String(format: "%@ %@: swift accepted=%d off=%.1f raw=%.1f k=%.6f z=%.2f | esperado accepted=%d off=%.1f k=%.6f z=%.2f | %d ms | %@",
                     ok ? "OK    " : "DIVERGE", name, r.accepted ? 1 : 0, r.offsetMs, r.rawOffsetMs, r.scale, r.z, acc ? 1 : 0, eo, es, ez, took, r.reason))
    }
    check(bad == 0 && n > 0, "vetores do motor: \(n), divergentes: \(bad)")
}

// ------------------------------------------------------------------ planos, SRT e DSP contra o Java (expected.txt)
func genCues(_ seed: Int, _ n: Int, _ step: Int) -> [AudioCue] {
    var r = JavaRandom(seed)
    var out: [AudioCue] = []
    for i in 0..<n {
        let s = 20000 + i * step + r.nextInt(3000)
        let e = s + 1500 + r.nextInt(2500)
        let chars = 20 + r.nextInt(40)
        let sp = r.nextInt(10) != 0
        out.append(AudioCue(startMs: s, endMs: e, chars: chars, speech: sp))
    }
    return out
}

func planText(_ name: String, _ p: [[Int]]) -> String {
    return "PLAN \(name) =>" + p.map { " \($0[0]),\($0[1])" }.joined()
}

func runExpected(_ path: String) {
    let lines = ((try? String(contentsOfFile: path, encoding: .utf8)) ?? "").components(separatedBy: "\n").filter { !$0.isEmpty }
    let c1 = genCues(1, 900, 6000), c2 = genCues(2, 120, 40000), c3 = genCues(3, 60, 4000)
    var got: [String] = [
        planText("dense-5700000", AudioSyncSession.planDense(c1, 5_700_000, 3, 90_000)),
        planText("dense-2400000", AudioSyncSession.planDense(c1, 2_400_000, 3, 90_000)),
        planText("uniform-5700000", AudioSyncSession.plan(5_700_000, 3, 90_000)),
        planText("dense-sparse-4800000", AudioSyncSession.planDense(c2, 4_800_000, 3, 90_000)),
        planText("dense-short-600000", AudioSyncSession.planDense(c3, 600_000, 3, 90_000)),
    ]
    let srt = "\u{FEFF}1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Olá, tudo bem?</i>\r\n\r\n2\r\n00:00:04.250 --> 00:00:06,000 X1:0\r\n[música]\r\n\r\n"
        + "3\n00:00:07,5 --> 00:00:09,25\n{\\an8}Isso é um teste\nde duas linhas ♪\n\n4\n00:00:10,000 --> 00:00:09,000\nfala invertida\n\n"
        + "5\n01:02:03,004 --> 01:02:05,999\n(risos) Não sei ação 123\n\n6\n00:10:00,000 --> 00:10:02,000\n♪♪\n\n7\n00:11:00 --> 00:11:02\nsem milésimos\n\n8\nlixo --> lixo\nnada\n"
    for c in AudioCue.parseSrt(srt) { got.append("SRT \(c.startMs),\(c.endMs),\(c.chars),\(c.speech ? 1 : 0)") }
    // sinal sintético do GenExpected.java (o gerador gaussiano do Java não existe aqui: usa o checksum só para avisar)
    for g in got {
        check(lines.contains(g), g)
    }
    for l in lines where (l.hasPrefix("PLAN") || l.hasPrefix("SRT")) && !got.contains(l) { check(false, "esperado e não produzido: " + l) }
}

/** gaussiana de java.util.Random.nextGaussian (polar de Marsaglia), para gerar o mesmo ruído do GenExpected.java */
struct JavaGauss {
    var r: JavaRandom
    var have = false
    var next = 0.0
    init(_ seed: Int) { r = JavaRandom(seed) }
    mutating func nextGaussian() -> Double {
        if have { have = false; return next }
        var v1 = 0.0, v2 = 0.0, s = 0.0
        repeat {
            v1 = 2 * r.nextDouble() - 1
            v2 = 2 * r.nextDouble() - 1
            s = v1 * v1 + v2 * v2
        } while s >= 1 || s == 0
        let mul = (-2 * log(s) / s).squareRoot()   // StrictMath.sqrt(-2 * StrictMath.log(s)/s)
        next = v2 * mul
        have = true
        return v1 * mul
    }
}

func runDsp(_ path: String) {
    let lines = ((try? String(contentsOfFile: path, encoding: .utf8)) ?? "").components(separatedBy: "\n")
    guard let dl = lines.first(where: { $0.hasPrefix("DSP n=") }), let cl = lines.first(where: { $0.hasPrefix("DSPPCM") }) else { check(false, "sem linha DSP"); return }
    let n = 16000 * 20
    var pcm = [Int16](repeating: 0, count: n)
    var g = JavaGauss(7)
    var cs: Int64 = 0
    for i in 0..<n {
        let t = Double(i) / 16000.0
        let on = (Int(t / 1.5) % 2 == 0) ? 1.0 : 0.0
        let f0 = 120 * (1 + 0.1 * sin(2 * Double.pi * 3 * t))
        var v = 0.0
        for h in 1...8 { v += sin(2 * Double.pi * f0 * Double(h) * t) / Double(h) }
        let env = 0.5 + 0.5 * sin(2 * Double.pi * 4 * t)
        let x = on * env * v * 4000 + g.nextGaussian() * 200
        pcm[i] = Int16(max(-32768, min(32767, jround(x))))
        cs = cs &* 31 &+ Int64(pcm[i])
    }
    check("DSPPCM checksum=\(cs)" == cl, "sinal sintético igual ao do Java (\(cs)) [\(cl)]")
    let t0 = DispatchTime.now()
    let p = AudioDspVad().speechProb(pcm, n, 16000)
    print("DSP em \(ms(t0)) ms")
    var mean = 0.0
    for f in p { mean += Double(f) }
    mean /= Double(p.count)
    // esperado: n=2000 mean=... p=v0,v1,...
    let parts = dl.components(separatedBy: " ")
    let en = Int(parts[1].dropFirst(2))!
    let emean = Double(parts[2].dropFirst(5))!
    let ev = parts[3].dropFirst(2).split(separator: ",").map { Double($0)! }
    check(p.count == en, "DSP: \(p.count) quadros (esperado \(en))")
    check(abs(mean - emean) < 1e-3, String(format: "DSP: média %.6f (esperado %.6f)", mean, emean))
    var maxd = 0.0
    var k = 0
    var i = 0
    while i < p.count && k < ev.count { maxd = max(maxd, abs(Double(p[i]) - ev[k])); i += 97; k += 1 }
    check(maxd < 2e-3, String(format: "DSP: maior diferença nas amostras %.5f (limite 0,002)", maxd))
}

// ------------------------------------------------------------------ fuzz: nada pode travar o app
func runFuzz() {
    var r = JavaRandom(99)
    let t0 = DispatchTime.now()
    var accepted = 0
    for it in 0..<120 {
        let nc = r.nextInt(5) == 0 ? r.nextInt(5) : r.nextInt(400)
        var cues: [AudioCue] = []
        for _ in 0..<nc {
            let s = r.nextInt(7_000_000)
            let e = s + r.nextInt(8000) - 500
            cues.append(AudioCue(startMs: s, endMs: e, chars: r.nextInt(80), speech: r.nextInt(8) != 0))
        }
        let nw = r.nextInt(5)
        var ws: [AudioWindow] = []
        for _ in 0..<nw {
            let len = r.nextInt(4) == 0 ? r.nextInt(500) : 3000 + r.nextInt(7000)
            var p = [Float](repeating: 0, count: len)
            let mode = r.nextInt(3)
            for j in 0..<len { p[j] = mode == 0 ? Float(r.nextDouble()) : (mode == 1 ? (j / 150) % 2 == 0 ? 0.9 : 0.05 : 0) }
            ws.append(AudioWindow(startMs: r.nextInt(6_000_000) / 10 * 10, p: p))
        }
        var P = AudioAligner.Params()
        if it % 5 == 0 { P.randomHyps = 500 }
        let res = AudioAligner.solve(cues, ws, r.nextInt(7_000_000), P)
        if res.accepted { accepted += 1 }
        // reverificação e planos
        let cur = AudioResult(); cur.accepted = true; cur.scale = 1; cur.offsetMs = Double(r.nextInt(5000) - 2500)
        _ = AudioRecheck.check(cues, ws, cur, r.nextInt(6_000_000), 7_000_000, false, nil, AudioAligner.Params(), AudioRecheck.Params())
        _ = AudioSyncSession.planDense(cues, r.nextInt(7_000_000), 3, 90_000)
        _ = AudioSyncSession.plan(r.nextInt(7_000_000), 3, 90_000)
    }
    // SRT com lixo
    let junk = ["", "-->", "1\n-->\n", "00:00:01,000 --> \nx", "\u{0}\u{1}-->", "99999999999999999999:00:00,000 --> 00:00:01,000\nabc", "1\n00:00:01,000 --> 00:00:02,000\n" + String(repeating: "<", count: 100)]
    for j in junk { _ = AudioCue.parseSrt(j) }
    // DSP com áudio muito curto
    for n in [0, 1, 100, 799, 800, 1600, 5000] { _ = AudioDspVad().speechProb([Int16](repeating: 100, count: n), n, 16000) }
    check(true, "fuzz: 120 sessões aleatórias, reverificação, planos, SRT e DSP sem travar (\(accepted) aceitas) em \(ms(t0)) ms")
}

// ------------------------------------------------------------------ Silero x referência Python
func runSilero(_ model: String, _ wav: String, _ refPath: String) {
    setenv("FP_SILERO_MODEL", model, 1)
    guard let vad = AudioSileroVad.make(log: { print("silero: " + $0) }) else { check(false, "Silero não iniciou"); return }
    let pcm = readWav(wav)
    let t0 = DispatchTime.now()
    let p = vad.speechProb(pcm, pcm.count, 16000)
    let took = ms(t0)
    guard let rd = FileManager.default.contents(atPath: refPath) else { check(false, "sem referência"); return }
    let ref: [Float] = rd.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    let nn = min(p.count, ref.count)
    var maxd: Float = 0, sum = 0.0, dis = 0
    for i in 0..<nn {
        let d = abs(p[i] - ref[i])
        maxd = max(maxd, d); sum += Double(d)
        if (p[i] > 0.5) != (ref[i] > 0.5) { dis += 1 }
    }
    print(String(format: "SILERO-MAC audio=%.1fs tempo=%d ms (%.0fx tempo real) quadros=%d ref=%d maxdiff=%.6f meandiff=%.7f discordam(>0,5)=%d", Double(pcm.count) / 16000, took,
                 Double(pcm.count) / 16000 / max(0.001, Double(took) / 1000), p.count, ref.count, maxd, sum / Double(max(1, nn)), dis))
    check(p.count == ref.count && maxd < 1e-3 && dis == 0, "Silero em Swift (ONNX Runtime) igual à referência Python")
}

// ------------------------------------------------------------------ PreCheck: o mesmo comando do PreCheck.java
func runPre(_ wav: String, _ srtPath: String) {
    let pcm = readWav(wav)
    let dur = pcm.count / 16
    let text = (try? String(contentsOfFile: srtPath, encoding: .utf8)) ?? ""
    let cues = AudioCue.parseSrt(text)
    let P = AudioAligner.Params()
    let plan = AudioSyncSession.planDense(cues, dur, 3, 90_000)
    let ses = AudioSyncSession(cues: cues, mediaDurationMs: dur, params: P)
    var vad: AudioVad = AudioDspVad()
    var name = "dsp"
    if let s = AudioSileroVad.make(log: { print("silero: " + $0) }) { vad = s; name = "silero" }
    for w in plan {
        let s0 = w[0] * 16, n = w[1] * 16
        let sl = Array(pcm[s0..<min(pcm.count, s0 + n)])
        ses.addWindow(AudioWindow(startMs: w[0], p: vad.speechProb(sl, sl.count, 16000)))
    }
    if ProcessInfo.processInfo.environment["SPREAD"] == "1" {
        // o laço do app (AudioSyncSession.analyze): 3 janelas espalhadas + até 2 extras se há suspeita de deriva
        var used: [AudioWindow] = []
        let rr = AudioSyncSession.analyze(cues, dur, 6, 90_000, { st, ln, _, _ in
            let s0 = st * 16, n = ln * 16
            let sl = Array(pcm[s0..<min(pcm.count, s0 + n)])
            return AudioWindow(startMs: st, p: vad.speechProb(sl, sl.count, 16000))
        }, P, spread: true, dense: 3, used: &used)
        guard let r = rr else { print("SPREAD falhou"); return }
        print(String(format: "SPREAD accepted=%d scale=%.6f raw=%.0f drift=%d confiaveis=%d janelas=%d inSync=%d z=%.1f res=%.0f unitRes=%.0f planB=%d consenso=%d contra=%d motivo=%@ fin=%@ at=%@", r.accepted ? 1 : 0, r.scale, r.rawOffsetMs,
                     r.driftScale ? 1 : 0, r.driftConfident, used.count, r.inSync ? 1 : 0, r.z, r.driftResMs, r.unitResMs, r.planB ? 1 : 0, r.winConsensus ? 1 : 0, r.shiftContra ? 1 : 0, r.reason.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "_"), r.finDetail.replacingOccurrences(of: " ", with: "_"), used.map { "[\($0.startMs)]," }.joined()))
        return
    }
    let r = ses.solve()
    print(String(format: "PRE accepted=%d raw=%.0f scale=%.5f z=%.1f wins=%d/%d inSync=%d vad=%@ janelas=%@", r.accepted ? 1 : 0, r.rawOffsetMs, r.scale, r.z, r.winsAgree, r.winsValid,
                 r.inSync ? 1 : 0, name, plan.map { "[\($0[0]), \($0[1])]" }.joined(separator: ", ")))
}

let a = CommandLine.arguments
if a.count >= 4 && a[1] == "parity" {
    runVectors(a[2])
    runExpected(a[3])
    runDsp(a[3])
    runFuzz()
    print(failures == 0 ? "TUDO CERTO" : "FALHAS: \(failures)")
    exit(failures == 0 ? 0 : 1)
} else if a.count >= 5 && a[1] == "silero" {
    runSilero(a[2], a[3], a[4])
    exit(failures == 0 ? 0 : 1)
} else if a.count >= 4 && a[1] == "pre" {
    runPre(a[2], a[3])
} else {
    print("uso: audiotest parity vectors.json expected.txt | silero modelo wav ref.f32 | pre audio.wav legenda.srt")
    exit(2)
}
