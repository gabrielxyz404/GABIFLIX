// Silero VAD no macOS via ONNX Runtime (SwiftPM). Uso: sileroprobe modelo.onnx audio.wav saida.f32
// Mesmo algoritmo do SileroVad.java: blocos de 512 amostras + contexto de 64, estado (2,1,128), saída a 31,25 Hz interpolada para 100 Hz.
import Foundation
import OnnxRuntimeBindings

let a = CommandLine.arguments
guard a.count >= 4 else { print("uso: sileroprobe modelo wav saida"); exit(2) }
let wav = try Data(contentsOf: URL(fileURLWithPath: a[2]))
var off = -1, len = 0, p = 12
while p + 8 <= wav.count {
    let sz = Int(wav.subdata(in: (p + 4)..<(p + 8)).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
    if wav[p] == 0x64 && wav[p + 1] == 0x61 && wav[p + 2] == 0x74 && wav[p + 3] == 0x61 { off = p + 8; len = min(sz, wav.count - off); break }
    p += 8 + sz + (sz & 1)
}
let n = len / 2
var pcm = [Int16](repeating: 0, count: n)
_ = pcm.withUnsafeMutableBytes { wav.copyBytes(to: $0, from: off..<(off + n * 2)) }

let env = try ORTEnv(loggingLevel: .warning)
let so = try ORTSessionOptions()
try so.setIntraOpNumThreads(1)
let session = try ORTSession(env: env, modelPath: a[1], sessionOptions: so)

let nch = n / 512, n10 = n / 160
var probs = [Float](repeating: 0, count: nch)
var state = [Float](repeating: 0, count: 256)
var ctx = [Float](repeating: 0, count: 64)
var sr: [Int64] = [16000]
let t0 = Date()
for i in 0..<nch {
    var inp = [Float](repeating: 0, count: 576)
    for k in 0..<64 { inp[k] = ctx[k] }
    for k in 0..<512 { inp[64 + k] = Float(pcm[i * 512 + k]) / 32768.0 }
    let inD = NSMutableData(bytes: &inp, length: 576 * 4)
    let stD = NSMutableData(bytes: &state, length: 256 * 4)
    let srD = NSMutableData(bytes: &sr, length: 8)
    let vIn = try ORTValue(tensorData: inD, elementType: .float, shape: [1, 576])
    let vSt = try ORTValue(tensorData: stD, elementType: .float, shape: [2, 1, 128])
    let vSr = try ORTValue(tensorData: srD, elementType: .int64, shape: [])
    let outs = try session.run(withInputs: ["input": vIn, "state": vSt, "sr": vSr], outputNames: ["output", "stateN"], runOptions: nil)
    let o = try outs["output"]!.tensorData() as Data
    probs[i] = o.withUnsafeBytes { $0.loadUnaligned(as: Float.self) }
    let s = try outs["stateN"]!.tensorData() as Data
    s.withUnsafeBytes { raw in for k in 0..<256 { state[k] = raw.loadUnaligned(fromByteOffset: k * 4, as: Float.self) } }
    for k in 0..<64 { ctx[k] = inp[64 + 512 - 64 + k] }
}
let dt = Date().timeIntervalSince(t0)
var out = [Float](repeating: 0, count: n10)
for j in 0..<n10 {
    let t = Double(j) * 0.01 / 0.032 - 0.5
    var v: Float
    if t <= 0 { v = probs[0] } else if t >= Double(nch - 1) { v = probs[nch - 1] } else { let ia = Int(t); let f = Float(t - Double(ia)); v = probs[ia] * (1 - f) + probs[ia + 1] * f }
    out[j] = min(1, max(0, v))
}
try out.withUnsafeBytes { Data($0) }.write(to: URL(fileURLWithPath: a[3]))
print(String(format: "SILERO-MAC audio=%.1fs tempo=%.2fs (%.0fx) frames=%d", Double(n) / 16000, dt, Double(n) / 16000 / dt, n10))
