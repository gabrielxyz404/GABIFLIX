// Silero VAD (modelo ONNX v5, 2,3 MB) pelo ONNX Runtime (API C), carregado com dlopen: se a biblioteca não existir ou não abrir,
// make() devolve nil e o app usa o VAD por DSP (sem biblioteca nativa). Mesmo algoritmo do SileroVad.java do Android/TV:
// blocos de 512 amostras (32 ms) + contexto de 64, estado (2,1,128), saída a 31,25 Hz interpolada para 100 Hz como no corpus de validação.
import Foundation

#if FP_NO_ORT
// compilado sem o ONNX Runtime (build sem o framework): só o VAD por DSP
enum AudioSileroVad {
    static func make(log: ((String) -> Void)? = nil) -> AudioVad? { log?("compilado sem ONNX Runtime"); return nil }
}
func audioVadLastError(_ v: AudioVad) -> String { return "?" }
#else

private typealias VP = UnsafeMutableRawPointer?
private typealias VPP = UnsafeMutablePointer<UnsafeMutableRawPointer?>?
private typealias CStr = UnsafePointer<CChar>?
private typealias FnGetApi = @convention(c) (UInt32) -> UnsafeRawPointer?
private typealias FnCreateEnv = @convention(c) (Int32, CStr, VPP) -> VP
private typealias FnCreateSessionOptions = @convention(c) (VPP) -> VP
private typealias FnSetThreads = @convention(c) (VP, Int32) -> VP
private typealias FnCreateSession = @convention(c) (VP, CStr, VP, VPP) -> VP
private typealias FnCreateCpuMemoryInfo = @convention(c) (Int32, Int32, VPP) -> VP
private typealias FnCreateTensor = @convention(c) (VP, UnsafeMutableRawPointer?, Int, UnsafePointer<Int64>?, Int, Int32, VPP) -> VP
private typealias FnRun = @convention(c) (VP, VP, UnsafePointer<CStr>?, UnsafePointer<UnsafeMutableRawPointer?>?, Int,
                                          UnsafePointer<CStr>?, Int, UnsafeMutablePointer<UnsafeMutableRawPointer?>?) -> VP
private typealias FnGetData = @convention(c) (VP, VPP) -> VP
private typealias FnRelease = @convention(c) (VP) -> Void
private typealias FnErrMsg = @convention(c) (VP) -> CStr

final class AudioSileroVad: AudioVad {
    let name = "silero"

    private let fCreateTensor: FnCreateTensor
    private let fRun: FnRun
    private let fGetData: FnGetData
    private let fRelValue: FnRelease, fRelStatus: FnRelease, fRelSession: FnRelease, fRelEnv: FnRelease, fRelOpts: FnRelease, fRelMem: FnRelease
    private let fErr: FnErrMsg
    private var env: UnsafeMutableRawPointer?
    private var session: UnsafeMutableRawPointer?
    private var mem: UnsafeMutableRawPointer?
    private let lib: UnsafeMutableRawPointer
    /** mensagem do último erro do ONNX Runtime (para o diagnóstico) */
    private(set) var lastError = ""

    private init(lib: UnsafeMutableRawPointer, api: UnsafePointer<OrtApi>, modelPath: String) throws {
        self.lib = lib
        let a = api.pointee
        let fCreateEnv = unsafeBitCast(a.CreateEnv, to: FnCreateEnv.self)
        let fCreateOpts = unsafeBitCast(a.CreateSessionOptions, to: FnCreateSessionOptions.self)
        let fIntra = unsafeBitCast(a.SetIntraOpNumThreads, to: FnSetThreads.self)
        let fInter = unsafeBitCast(a.SetInterOpNumThreads, to: FnSetThreads.self)
        let fCreateSession = unsafeBitCast(a.CreateSession, to: FnCreateSession.self)
        let fCreateMem = unsafeBitCast(a.CreateCpuMemoryInfo, to: FnCreateCpuMemoryInfo.self)
        fCreateTensor = unsafeBitCast(a.CreateTensorWithDataAsOrtValue, to: FnCreateTensor.self)
        fRun = unsafeBitCast(a.Run, to: FnRun.self)
        fGetData = unsafeBitCast(a.GetTensorMutableData, to: FnGetData.self)
        fRelValue = unsafeBitCast(a.ReleaseValue, to: FnRelease.self)
        fRelStatus = unsafeBitCast(a.ReleaseStatus, to: FnRelease.self)
        fRelSession = unsafeBitCast(a.ReleaseSession, to: FnRelease.self)
        fRelEnv = unsafeBitCast(a.ReleaseEnv, to: FnRelease.self)
        fRelOpts = unsafeBitCast(a.ReleaseSessionOptions, to: FnRelease.self)
        fRelMem = unsafeBitCast(a.ReleaseMemoryInfo, to: FnRelease.self)
        fErr = unsafeBitCast(a.GetErrorMessage, to: FnErrMsg.self)

        var e: UnsafeMutableRawPointer? = nil
        var opts: UnsafeMutableRawPointer? = nil
        var s: UnsafeMutableRawPointer? = nil
        var m: UnsafeMutableRawPointer? = nil
        var err = fCreateEnv(2, "frameplay", &e)   // 2 = ORT_LOGGING_LEVEL_WARNING
        if err == nil { err = fCreateOpts(&opts) }
        if err == nil { err = fIntra(opts, 1) }
        if err == nil { err = fInter(opts, 1) }
        if err == nil { err = fCreateSession(e, modelPath, opts, &s) }
        if err == nil { err = fCreateMem(1, 0, &m) }   // OrtArenaAllocator, OrtMemTypeDefault
        if let o = opts { fRelOpts(o) }
        env = e; session = s; mem = m
        if err != nil {
            let msg = AudioSileroVad.message(err, fErr, fRelStatus)
            releaseAll()
            throw NSError(domain: "AudioSileroVad", code: 1, userInfo: [NSLocalizedDescriptionKey: msg])
        }
    }

    deinit { releaseAll() }

    private func releaseAll() {
        if let m = mem { fRelMem(m); mem = nil }
        if let s = session { fRelSession(s); session = nil }
        if let e = env { fRelEnv(e); env = nil }
    }

    private static func message(_ status: UnsafeMutableRawPointer?, _ fErr: FnErrMsg, _ fRel: FnRelease) -> String {
        guard let st = status else { return "" }
        let msg = fErr(st).map { String(cString: $0) } ?? "erro do ONNX Runtime"
        fRel(st)
        return msg
    }

    /** procura o ONNX Runtime ao lado do app (Contents/Frameworks) e o modelo em Contents/Resources; nil se algo falta ou não abre */
    static func make(log: ((String) -> Void)? = nil) -> AudioVad? {
        let env = ProcessInfo.processInfo.environment
        var libs: [String] = []
        if let p = env["FP_ORT_LIB"] { libs.append(p) }
        if let fw = Bundle.main.privateFrameworksPath {
            libs.append(fw + "/onnxruntime.framework/onnxruntime")
            libs.append(fw + "/onnxruntime.framework/Versions/A/onnxruntime")
            libs.append(fw + "/libonnxruntime.dylib")
        }
        var model = env["FP_SILERO_MODEL"] ?? ""
        if model.isEmpty, let r = Bundle.main.path(forResource: "silero_vad", ofType: "onnx") { model = r }
        if model.isEmpty || !FileManager.default.fileExists(atPath: model) { log?("modelo Silero não encontrado"); return nil }
        var handle: UnsafeMutableRawPointer? = nil
        for p in libs where FileManager.default.fileExists(atPath: p) {
            handle = dlopen(p, RTLD_NOW | RTLD_LOCAL)
            if handle != nil { break }
            if let e = dlerror() { log?("dlopen \(p): \(String(cString: e))") }
        }
        guard let lib = handle else { log?("biblioteca do ONNX Runtime não encontrada ou não abre"); return nil }
        guard let sym = dlsym(lib, "OrtGetApiBase") else { log?("OrtGetApiBase ausente"); dlclose(lib); return nil }
        let getBase = unsafeBitCast(sym, to: (@convention(c) () -> UnsafeRawPointer?).self)
        guard let basePtr = getBase() else { dlclose(lib); return nil }
        let base = basePtr.assumingMemoryBound(to: OrtApiBase.self)
        let getApi = unsafeBitCast(base.pointee.GetApi, to: FnGetApi.self)
        guard let apiPtr = getApi(UInt32(ORT_API_VERSION)) else { log?("versão da API do ONNX Runtime não suportada"); dlclose(lib); return nil }
        do {
            let v = try AudioSileroVad(lib: lib, api: apiPtr.assumingMemoryBound(to: OrtApi.self), modelPath: model)
            return v
        } catch {
            log?("Silero não iniciou: \(error.localizedDescription)")
            dlclose(lib)
            return nil
        }
    }

    func speechProb(_ pcm: [Int16], _ n: Int, _ rate: Int) -> [Float] {
        if rate != 16000 || session == nil { return [] }
        let nch = n / 512
        let n10 = n / 160
        if nch == 0 { return [] }
        var probs = [Float](repeating: 0, count: nch)
        let inp = UnsafeMutablePointer<Float>.allocate(capacity: 576)
        let st = UnsafeMutablePointer<Float>.allocate(capacity: 256)
        let sr = UnsafeMutablePointer<Int64>.allocate(capacity: 1)
        inp.initialize(repeating: 0, count: 576)
        st.initialize(repeating: 0, count: 256)
        sr.initialize(to: 16000)
        let shIn = UnsafeMutablePointer<Int64>.allocate(capacity: 2)
        shIn[0] = 1; shIn[1] = 576
        let shSt = UnsafeMutablePointer<Int64>.allocate(capacity: 3)
        shSt[0] = 2; shSt[1] = 1; shSt[2] = 128
        let namesIn = [strdup("input"), strdup("state"), strdup("sr")]
        let namesOut = [strdup("output"), strdup("stateN")]
        var vIn: UnsafeMutableRawPointer? = nil, vSt: UnsafeMutableRawPointer? = nil, vSr: UnsafeMutableRawPointer? = nil
        var ok = true
        var cIn = [CStr](repeating: nil, count: 3), cOut = [CStr](repeating: nil, count: 2)
        for i in 0..<3 { cIn[i] = UnsafePointer(namesIn[i]) }
        for i in 0..<2 { cOut[i] = UnsafePointer(namesOut[i]) }
        defer {
            if let v = vIn { fRelValue(v) }
            if let v = vSt { fRelValue(v) }
            if let v = vSr { fRelValue(v) }
            for p in namesIn + namesOut { free(p) }
            inp.deallocate(); st.deallocate(); sr.deallocate(); shIn.deallocate(); shSt.deallocate()
        }
        if let e = check(fCreateTensor(mem, inp, 576 * 4, shIn, 2, 1, &vIn)) { lastError = e; ok = false }       // float
        if ok, let e = check(fCreateTensor(mem, st, 256 * 4, shSt, 3, 1, &vSt)) { lastError = e; ok = false }   // float
        if ok, let e = check(fCreateTensor(mem, sr, 8, nil, 0, 7, &vSr)) { lastError = e; ok = false }          // int64 escalar
        if !ok { return [] }
        var ctx = [Float](repeating: 0, count: 64)
        var inputs: [UnsafeMutableRawPointer?] = [vIn, vSt, vSr]
        var outs: [UnsafeMutableRawPointer?] = [nil, nil]
        for i in 0..<nch {
            for k in 0..<64 { inp[k] = ctx[k] }
            for k in 0..<512 { inp[64 + k] = Float(pcm[i * 512 + k]) / 32768 }
            outs[0] = nil; outs[1] = nil
            let status = inputs.withUnsafeBufferPointer { ib in
                outs.withUnsafeMutableBufferPointer { ob in
                    cIn.withUnsafeBufferPointer { cin in
                        cOut.withUnsafeBufferPointer { cout in
                            fRun(session, nil, cin.baseAddress, ib.baseAddress, 3, cout.baseAddress, 2, ob.baseAddress)
                        }
                    }
                }
            }
            if let e = check(status) { lastError = e; ok = false; break }
            var p0: UnsafeMutableRawPointer? = nil, p1: UnsafeMutableRawPointer? = nil
            if check(fGetData(outs[0], &p0)) == nil, check(fGetData(outs[1], &p1)) == nil, let a = p0, let b = p1 {
                probs[i] = a.assumingMemoryBound(to: Float.self)[0]
                let s = b.assumingMemoryBound(to: Float.self)
                for k in 0..<256 { st[k] = s[k] }
            } else { lastError = "saída do Silero ilegível"; ok = false }
            if let o = outs[0] { fRelValue(o) }
            if let o = outs[1] { fRelValue(o) }
            if !ok { break }
            for k in 0..<64 { ctx[k] = inp[64 + 512 - 64 + k] }
        }
        inputs.removeAll()
        if !ok { return [] }
        // 31,25 Hz -> 100 Hz por interpolação linear (np.interp: fora do intervalo vale o extremo)
        var out = [Float](repeating: 0, count: n10)
        for j in 0..<n10 {
            let t = (Double(j) * 0.01) / 0.032 - 0.5
            var v: Float
            if t <= 0 { v = probs[0] } else if t >= Double(nch - 1) { v = probs[nch - 1] } else {
                let ia = Int(t)
                let f = t - Double(ia)
                v = Float(Double(probs[ia]) * (1 - f) + Double(probs[ia + 1]) * f)
            }
            out[j] = min(1, max(0, v))
        }
        return out
    }

    /** nil se o status é de sucesso; senão a mensagem de erro (e libera o status) */
    private func check(_ status: UnsafeMutableRawPointer?) -> String? {
        guard status != nil else { return nil }
        let m = AudioSileroVad.message(status, fErr, fRelStatus)
        return m.isEmpty ? "erro do ONNX Runtime" : m
    }
}

func audioVadLastError(_ v: AudioVad) -> String { return (v as? AudioSileroVad)?.lastError ?? "?" }
#endif
