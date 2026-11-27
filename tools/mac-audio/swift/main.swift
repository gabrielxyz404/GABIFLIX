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

// ------------------------------------------------------------------ repetição do relatório real (porte de DriftReplayTest.java)
/// valores de referência: o motor Java (AudioAligner.driftFromWins) corrido com as mesmas medidas (1.0.110)
func runDriftReplay() {
    func win(_ startS: Int, _ delay: Double, _ ncc: Double, _ second: Double, _ z: Double, _ conf: Bool) -> AudioResult.Win {
        var w = AudioResult.Win()
        w.startMs = startS * 1000
        w.centerMs = Double(startS + 45) * 1000.0
        w.delayMs = delay; w.ncc = ncc; w.second = second; w.z = z
        w.speech = true; w.confident = conf
        return w
    }
    // as 7 janelas da tentativa 1 do relatório (celular, v1.0.109, filme de 5615 s): ~14,6 s de atraso + deriva 1000/1001
    let report = [win(440, 14268, 0.63, 0.53, 4.2, true), win(865, 13588, 0.70, 0.61, 2.6, true), win(2490, 12024, 0.65, 0.58, 2.8, false),
                  win(1025, 13535, 0.45, 0.37, 3.8, true), win(3425, 11141, 0.53, 0.44, 3.7, true), win(4000, 10750, 0.47, 0.46, 2.1, false),
                  win(4600, 10730, 0.51, 0.46, 3.0, false)]
    let P = AudioAligner.Params()
    func strong(_ ws: [AudioResult.Win]) -> [Bool] { return ws.map { $0.ncc >= P.winMinNcc && ($0.ncc - $0.second >= P.winMinMargin - 1e-9 || $0.z >= P.winMinZ) } }
    func near(_ a: Double, _ b: Double, _ tol: Double) -> Bool { return abs(a - b) <= tol }

    let R = AudioResult()
    AudioAligner.driftFromWins(R, nil, nil, report, 5_615_000, P)
    check(R.driftScale, "replay real: deriva provada (\(R.driftNote))")
    check(near(R.scale, 1000.0 / 1001.0, 2e-6), "replay real: escala 0,999 (\(R.scale))")
    check(near(R.offsetMs, 14_600, 300), "replay real: deslocamento ~+14,6 s (\(R.offsetMs))")
    check(near(R.offsetMs, 14601.114, 0.01) && near(R.unitResMs, 2060.697, 0.01) && near(R.driftResMs, 137.755, 0.01) && R.driftConfident == 4 && R.driftDropWin == -1 && !R.driftSuspect,
          "replay real: igual ao Java (offset 14601.114, resíduo sem escala 2060.697, resíduo 137.755, 4 confiáveis)")
    check(R.unitResMs >= P.driftRelaxRatio * max(R.driftResMs, P.driftResFloorMs), "replay real: prova forte da porta relaxada (\(R.unitResMs) / \(R.driftResMs))")
    let al = AudioAligner.alphaOf(R.scale), beta = R.offsetMs / R.scale
    var d = report.map { $0.delayMs - (beta + al * $0.centerMs) }
    let st = strong(report)
    let expDev = [137.755, -116.819, -54.193, -9.659, -1.257, 183.319, 763.920]
    var devOk = true
    for i in 0..<7 where !near(d[i], expDev[i], 0.01) { devOk = false }
    check(devOk, "replay real: desvios por janela iguais ao Java")
    check(d.map { abs($0) }.max()! > 200 && abs(d[6]) > 500, "replay real: o desvio máximo bruto passava de 200 ms (a janela fraca fora é a de z baixo)")
    check(AudioAligner.driftAgree(d, st, P) && AudioAligner.driftFitCount(d, st, P) == 7, "replay real: as janelas concordam com a reta (7 de 7)")
    d[0] = 450
    check(!AudioAligner.driftAgree(d, st, P) && AudioAligner.driftFitCount(d, st, P) == 6, "replay: janela forte a 450 ms da reta continua a recusar")
    let minority = [50.0, 60, 3000, 2500, 4000, 1800, 5000]
    check(!AudioAligner.driftAgree(minority, [Bool](repeating: false, count: 7), P) && AudioAligner.driftFitCount(minority, [Bool](repeating: false, count: 7), P) == 2, "replay: minoria a caber não chega")

    // atraso constante, sem inclinação: não é deriva
    let flat = report.map { win($0.startMs / 1000, 14000, $0.ncc, $0.second, $0.z, $0.confident) }
    let R2 = AudioResult()
    AudioAligner.driftFromWins(R2, nil, nil, flat, 5_615_000, P)
    check(!R2.driftScale && R2.scale == 1.0, "replay: atraso constante sem deriva")

    // caso da bancada: só deslocamento (+7,5 s) com ruído de ~500 ms: a "deriva" de 1285 ms não pode passar a porta relaxada
    let noisy = [win(660, -7942, 0.25, 0.17, 3.3, true), win(1450, -7207, 0.59, 0.50, 3.3, true), win(1945, -6938, 0.39, 0.28, 4.6, true),
                 win(2750, -7521, 0.35, 0.30, 3.8, false), win(3165, -7374, 0.54, 0.39, 5.3, true), win(4330, -7633, 0.72, 0.69, 2.7, false)]
    let R3 = AudioResult()
    AudioAligner.driftFromWins(R3, nil, nil, noisy, 5_000_000, P)
    check(near(R3.offsetMs, -8771.251, 0.01) && near(R3.unitResMs, 639.508, 0.01) && near(R3.driftResMs, 165.520, 0.01) && R3.driftScale && R3.driftDropWin == 4,
          "replay bancada: igual ao Java (offset -8771.251, resíduos 639.508 / 165.520, janela 4 fora)")
    check(!R3.driftScale || R3.unitResMs < P.driftRelaxRatio * max(R3.driftResMs, P.driftResFloorMs), "replay bancada: sem prova forte a porta relaxada não vale")
}

// ------------------------------------------------------------------ deriva para TODAS as razões de fps conhecidas (porte de DriftRatiosReplayTest.java)
func runDriftRatios() {
    func win(_ startS: Double, _ delay: Double, _ ncc: Double, _ second: Double, _ z: Double, _ conf: Bool) -> AudioResult.Win {
        var w = AudioResult.Win()
        w.startMs = Int(startS * 1000)
        w.centerMs = (startS + 45) * 1000.0
        w.delayMs = delay; w.ncc = ncc; w.second = second; w.z = z
        w.speech = true; w.confident = conf
        return w
    }
    let starts: [Double] = [300, 900, 1700, 2500, 3300, 4100, 4800]
    let dur = 5_400_000
    let P = AudioAligner.Params()
    func line(_ scale: Double, _ off: Double, _ weak: Double) -> [AudioResult.Win] {
        let al = AudioAligner.alphaOf(scale), beta = off / scale
        var ws: [AudioResult.Win] = []
        for i in 0..<starts.count {
            let c = (starts[i] + 45) * 1000.0
            let d = beta + al * c + Double(((i * 37 + 11) % 141) - 70)
            ws.append(i == starts.count - 2 ? win(starts[i], d + weak, 0.30, 0.27, 2.4, false) : win(starts[i], d, 0.55 + 0.02 * Double(i), 0.40, 4.5, true))
        }
        return ws
    }
    func near(_ a: Double, _ b: Double, _ tol: Double) -> Bool { return abs(a - b) <= tol }
    // todas as razões com |alpha| <= maxAlpha: provadas com o seu deslocamento; a janela fraca desviada cabe
    var tested = 0
    for ks in P.knownScales where abs(ks - 1.0) > 1e-9 && abs(AudioAligner.alphaOf(ks)) <= P.maxAlpha {
        for off in [8000.0, -12000.0] {
            let ws = line(ks, off, 700)
            let R = AudioResult()
            AudioAligner.driftFromWins(R, nil, nil, ws, dur, P)
            check(R.driftScale && near(R.scale, ks, 1e-12) && near(R.offsetMs, off, 300), "razões: \(ks) desl. \(off) provada (\(R.driftNote))")
            check(R.unitResMs >= P.driftRelaxRatio * max(R.driftResMs, P.driftResFloorMs), "razões: \(ks) prova forte")
            let al = AudioAligner.alphaOf(R.scale), beta = R.offsetMs / R.scale
            let d = ws.map { $0.delayMs - (beta + al * $0.centerMs) }
            let st = ws.map { $0.ncc >= P.winMinNcc && ($0.ncc - $0.second >= P.winMinMargin - 1e-9 || $0.z >= P.winMinZ) }
            check(AudioAligner.driftAgree(d, st, P) && abs(d[5]) > P.driftGateDevMs, "razões: \(ks) janela fraca desviada cabe")
        }
        tested += 1
    }
    check(tested >= 8, "razões: todas testadas (\(tested))")
    // valores iguais ao Java (DriftRatiosReplayTest.deterministicReferenceValuesForParity)
    let refs: [(Double, Double, Double, Double)] = [(25.0 / 23.976, 8000, 7993.536, 100448.088), (25.0 / 24.0, 8000, 7993.543, 98094.497), (24.0 / 25.0, -12000, -12005.951, 102124.195)]
    for r in refs {
        let R = AudioResult()
        AudioAligner.driftFromWins(R, nil, nil, line(r.0, r.1, 700), dur, P)
        check(R.driftScale && near(R.scale, r.0, 1e-12) && near(R.offsetMs, r.2, 0.01) && near(R.unitResMs, r.3, 0.01) && near(R.driftResMs, 58.199, 0.01) && R.driftConfident == 6,
              "razões: \(r.0) igual ao Java (offset \(R.offsetMs), sem escala \(R.unitResMs))")
    }
    // negativos
    for delay in [0.0, 3000, -9000, 14000] {
        var ws: [AudioResult.Win] = []
        for i in 0..<starts.count { ws.append(win(starts[i], delay + Double(((i * 53 + 7) % 121) - 60), 0.6, 0.4, 4.5, true)) }
        let R = AudioResult()
        AudioAligner.driftFromWins(R, nil, nil, ws, dur, P)
        check(!R.driftScale, "razões: atraso constante \(delay) não é deriva")
    }
    var seed: UInt64 = 11
    func rnd() -> Double { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return Double((seed >> 33) % 2_000_001) / 1_000_000.0 - 1.0 }
    var incoherentOk = true
    for _ in 0..<200 {
        var ws: [AudioResult.Win] = []
        for i in 0..<starts.count { ws.append(win(starts[i], rnd() * 200_000, 0.5, 0.4, 4.5, true)) }
        let R = AudioResult()
        AudioAligner.driftFromWins(R, nil, nil, ws, dur, P)
        if R.driftScale { incoherentOk = false }
    }
    check(incoherentOk, "razões: janelas incoerentes (200) nunca viram deriva")
    var mino = line(25.0 / 24.0, 5000, 0)
    let junk = [-40_000.0, 90_000, 12_000, -70_000, 31_000]
    var k = 0
    for i in 0..<mino.count where i != 1 && i != 5 { mino[i].delayMs = junk[k]; k += 1 }
    let Rm = AudioResult()
    AudioAligner.driftFromWins(Rm, nil, nil, mino, dur, P)
    check(!Rm.driftScale, "razões: minoria na reta não é deriva")
    for al in [0.012, 0.02, 0.03, -0.02, 0.07] {
        let Rs = AudioResult()
        AudioAligner.driftFromWins(Rs, nil, nil, line(1.0 / (1.0 - al), 6000, 0), dur, P)
        check(!Rs.driftScale, "razões: inclinação \(al) de nenhuma razão não é deriva")
    }
    var two = line(25.0 / 24.0, 5000, 0)
    for i in 0..<two.count { two[i].confident = i < 2 }
    let R2 = AudioResult()
    AudioAligner.driftFromWins(R2, nil, nil, two, dur, P)
    check(!R2.driftScale, "razões: duas janelas confiáveis não chegam")
}

let a = CommandLine.arguments
if a.count >= 4 && a[1] == "parity" {
    runVectors(a[2])
    runExpected(a[3])
    runDsp(a[3])
    runFuzz()
    runDriftReplay()
    runDriftRatios()
    print(failures == 0 ? "TUDO CERTO" : "FALHAS: \(failures)")
    exit(failures == 0 ? 0 : 1)
} else if a.count >= 2 && a[1] == "driftreplay" {
    runDriftReplay()
    runDriftRatios()
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
