// Sonda de captura de áudio no VLCKit (macOS): um SEGUNDO VLCMediaPlayer, sem vídeo, que entrega PCM mono 16 kHz s16 de uma janela do vídeo.
// Métodos:
//   cb    : libvlc_audio_set_callbacks + libvlc_audio_set_format("S16N", 16000, 1)  (símbolos resolvidos com dlsym no próprio VLCKit; o
//           ponteiro libvlc_media_player_t* vem do ivar _playerInstance, como na sonda de vídeo da fase das legendas)
//   sout  : cadeia de saída (transcode -> WAV em arquivo): não toca nada, não segue o relógio de reprodução (método mais rápido no Linux)
//   afile : aout "afile" do VLC escrevendo um WAV (mono)
// uso: capprobe <url> <inicioS> <duracaoS> <metodo> <saida> [opcoes da biblioteca/midia separadas por espaço; ":x=y" vai para a mídia, "--x" para a biblioteca]
import Foundation
import VLCKit

func out(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) }
let a = CommandLine.arguments
guard a.count >= 6 else { out("uso: capprobe url inicio dur metodo saida [opcoes]"); exit(2) }
let url = a[1], startS = Double(a[2])!, durS = Double(a[3])!, method = a[4], outPath = a[5]
let extra = Array(a.dropFirst(6))

func sym<T>(_ n: String, _ t: T.Type) -> T {
    guard let p = dlsym(UnsafeMutableRawPointer(bitPattern: -2), n) else { out("falta símbolo \(n)"); exit(4) }
    return unsafeBitCast(p, to: t)
}

var libOpts: [String] = ["--no-video", "--no-spu", "--no-sub-autodetect-file", "--no-lua", "--no-osd", "--codec=avcodec", "--avcodec-hw=none", "--no-videotoolbox"]
var mediaOpts: [String] = [":no-video", ":no-spu", ":network-caching=600", ":http-reconnect",
                           ":start-time=\(String(format: "%.3f", startS))", ":stop-time=\(String(format: "%.3f", startS + durS))"]
for e in extra { if e.hasPrefix("--") { libOpts.append(e) } else if e.hasPrefix(":") { mediaOpts.append(e) } }
if method == "sout" { mediaOpts.append(":sout=#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=\(outPath)}") }
if method == "afile" { libOpts += ["--aout=afile", "--audiofile-file=\(outPath)", "--audiofile-wav", "--audiofile-channels=1"] }

let player = VLCMediaPlayer(options: libOpts)
guard let iv = class_getInstanceVariable(VLCMediaPlayer.self, "_playerInstance") else { out("sem _playerInstance"); exit(3) }
let mp = Unmanaged.passUnretained(player).toOpaque().advanced(by: ivar_getOffset(iv)).load(as: OpaquePointer?.self)!

// ---- método cb
final class Sink {
    var data = Data()
    var calls = 0
    var firstPts: Int64 = -1, lastPts: Int64 = 0
    var firstWall = 0.0
}
let sink = Sink()
typealias PlayCB = @convention(c) (UnsafeMutableRawPointer?, UnsafeRawPointer?, UInt32, Int64) -> Void
typealias PauseCB = @convention(c) (UnsafeMutableRawPointer?, Int64) -> Void
typealias DrainCB = @convention(c) (UnsafeMutableRawPointer?) -> Void
if method == "cb" {
    let setCb = sym("libvlc_audio_set_callbacks", (@convention(c) (OpaquePointer, PlayCB, PauseCB, PauseCB, PauseCB, DrainCB, UnsafeMutableRawPointer?) -> Void).self)
    let setFmt = sym("libvlc_audio_set_format", (@convention(c) (OpaquePointer, UnsafePointer<CChar>, UInt32, UInt32) -> Void).self)
    let play: PlayCB = { _, samples, count, pts in
        guard let s = samples else { return }
        if sink.firstPts < 0 { sink.firstPts = pts; sink.firstWall = Date().timeIntervalSince1970 }
        sink.lastPts = pts
        sink.calls += 1
        sink.data.append(Data(bytes: s, count: Int(count) * 2))
    }
    let nop: PauseCB = { _, _ in }
    let drain: DrainCB = { _ in }
    setCb(mp, play, nop, nop, nop, drain, nil)
    "S16N".withCString { setFmt(mp, $0, 16000, 1) }
}

let media = VLCMedia(url: URL(string: url)!)
for o in mediaOpts { media.addOption(o) }
player.media = media
let t0 = Date().timeIntervalSince1970
let cpu0 = clock()
player.play()
let need = Int(durS * 16000 * 2)
let deadline = t0 + max(120, durS * 3)
var endedBy = "?"
while Date().timeIntervalSince1970 < deadline {
    RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    if method == "cb" && sink.data.count >= need { endedBy = "bytes"; break }
    let st = player.state
    if st == .ended || st == .stopped || st == .error {
        if Date().timeIntervalSince1970 - t0 > 1.0 { endedBy = "estado \(st.rawValue)"; break }
    }
}
if endedBy == "?" { endedBy = "tempo-limite" }
let wall = Date().timeIntervalSince1970 - t0
player.stop()
RunLoop.current.run(until: Date().addingTimeInterval(0.4))
let cpu = Double(clock() - cpu0) / Double(CLOCKS_PER_SEC)
if method == "cb" { FileManager.default.createFile(atPath: outPath, contents: sink.data) }
let first = method == "cb" ? Double(sink.firstWall - t0) : -1
out("RESULT {\"method\":\"\(method)\",\"start\":\(startS),\"dur\":\(durS),\"wall\":\(String(format: "%.2f", wall)),\"cpu\":\(String(format: "%.2f", cpu)),\"bytes\":\(sink.data.count),\"calls\":\(sink.calls),\"firstPts\":\(sink.firstPts),\"lastPts\":\(sink.lastPts),\"firstDelay\":\(String(format: "%.2f", first)),\"end\":\"\(endedBy)\"}")
exit(0)
