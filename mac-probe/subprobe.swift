// Sonda: toca um vídeo no VLCKit (mesma biblioteca do app) com saída de vídeo em memória (vmem, sem GPU; as legendas são
// desenhadas pelo mesmo renderizador de texto do app) e grava quadros BGRA nos tempos pedidos.
// uso: subprobe <modo> <video> <srt> <pasta-saida> <largura> <altura> <escala|-> <atrasoMs> <tempos-s separados por ,> [extras separados por ;]
// modos: media (sub-file + :sub-text-scale na mídia), lib (sub-text-scale na biblioteca), slave (addPlaybackSlave depois do play, como o app)
import Foundation
import VLCKit

func out(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) }
let a = CommandLine.arguments
guard a.count >= 10 else { out("uso"); exit(2) }
let mode = a[1], video = a[2], srt = a[3], outDir = a[4]
let W = Int(a[5])!, H = Int(a[6])!, scale = a[7], delayMs = Int(a[8]) ?? 0
var targets = a[9].split(separator: ",").map { Int((Double($0)! * 1000).rounded()) }.sorted()
let extra = a.count > 10 ? a[10].split(separator: ";").map(String.init) : []

let lgr = VLCConsoleLogger(); lgr.level = .debug; VLCLibrary.shared().loggers = [lgr]
var libOpts: [String] = ["--codec=avcodec", "--avcodec-hw=none", "--no-videotoolbox"] // sem VideoToolbox: o desenho das legendas por cima de CVPX falha na vmem
if mode == "lib" && scale != "-" { libOpts.append("--sub-text-scale=\(scale)") }
for e in extra where e.hasPrefix("--") { libOpts.append(e) }
let player = VLCMediaPlayer(options: libOpts)

// ponteiro libvlc_media_player_t* do VLCMediaPlayer (ivar _playerInstance)
guard let iv = class_getInstanceVariable(VLCMediaPlayer.self, "_playerInstance") else { out("sem _playerInstance"); exit(3) }
let mp = Unmanaged.passUnretained(player).toOpaque().advanced(by: ivar_getOffset(iv)).load(as: OpaquePointer?.self)!
typealias LockCB = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutablePointer<UnsafeMutableRawPointer?>?) -> UnsafeMutableRawPointer?
typealias UnlockCB = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafePointer<UnsafeMutableRawPointer?>?) -> Void
typealias DisplayCB = @convention(c) (UnsafeMutableRawPointer?, UnsafeMutableRawPointer?) -> Void
func sym<T>(_ n: String, _ t: T.Type) -> T {
    guard let p = dlsym(UnsafeMutableRawPointer(bitPattern: -2), n) else { out("falta símbolo \(n)"); exit(4) }
    return unsafeBitCast(p, to: t)
}
let setCallbacks = sym("libvlc_video_set_callbacks", (@convention(c) (OpaquePointer, LockCB, UnlockCB, DisplayCB, UnsafeMutableRawPointer?) -> Void).self)
let setFormat = sym("libvlc_video_set_format", (@convention(c) (OpaquePointer, UnsafePointer<CChar>, UInt32, UInt32, UInt32) -> Void).self)
let getTime = sym("libvlc_media_player_get_time", (@convention(c) (OpaquePointer) -> Int64).self)

let pitch = W * 4
let bufSize = pitch * H
let buf = UnsafeMutableRawPointer.allocate(byteCount: bufSize, alignment: 64)
var nextIdx = 0
var saved: [(Int, Int)] = []   // (alvo ms, tempo real ms)
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let lockCB: LockCB = { _, planes in planes?.pointee = buf; return nil }
let unlockCB: UnlockCB = { _, _, _ in }
let displayCB: DisplayCB = { _, _ in
    let t = Int(getTime(mp))
    while nextIdx < targets.count && t >= targets[nextIdx] {
        let tg = targets[nextIdx]; nextIdx += 1
        let path = "\(outDir)/f\(tg).bgra"
        FileManager.default.createFile(atPath: path, contents: Data(bytes: buf, count: bufSize))
        saved.append((tg, t))
    }
}
setCallbacks(mp, lockCB, unlockCB, displayCB, nil)
"RV32".withCString { setFormat(mp, $0, UInt32(W), UInt32(H), UInt32(pitch)) }

let media = VLCMedia(path: video)
media.addOption(":avcodec-hw=none") // sem VideoToolbox: o desenho das legendas por cima de CVPX falha na vmem
if mode == "media" || mode == "slave" { if scale != "-" { media.addOption(":sub-text-scale=\(scale)") } }
if mode == "media" || mode == "lib" { media.addOption(":sub-file=\(srt)") }
for e in extra where e.hasPrefix(":") { media.addOption(e) }
out("modo=\(mode) escala=\(scale) atraso=\(delayMs) tam=\(W)x\(H) lib=\(libOpts) extra=\(extra) alvos=\(targets)")
player.media = media
player.play()
var added = false, delayDone = false
let t0 = Date()
var lastLog = Date.distantPast
while Date().timeIntervalSince(t0) < 90 {
    RunLoop.main.run(until: Date().addingTimeInterval(0.05))
    let el = Date().timeIntervalSince(t0)
    let pt = Int(getTime(mp))
    if mode == "slave" && !added && pt > 2000 {
        added = true
        let ok = player.addPlaybackSlave(URL(fileURLWithPath: srt), type: .subtitle, enforce: true)
        out("addPlaybackSlave -> \(ok) em t=\(pt)")
    }
    if delayMs != 0 && !delayDone && (mode != "slave" || pt > 3500) {
        delayDone = true
        player.currentVideoSubTitleDelay = delayMs * 1000
        out("delay aplicado=\(delayMs * 1000)us em t=\(pt) lido=\(player.currentVideoSubTitleDelay)")
    }
    if Date().timeIntervalSince(lastLog) > 2 {
        lastLog = Date()
        out("t=\(Int(el)) estado=\(player.state.rawValue) tempo=\(pt) faixas=\((player.videoSubTitlesNames as? [String]) ?? []) idx=\((player.videoSubTitlesIndexes as? [NSNumber]) ?? []) atual=\(player.currentVideoSubTitleIndex) atraso=\(player.currentVideoSubTitleDelay) salvos=\(saved.count)")
    }
    if nextIdx >= targets.count && pt > (targets.last ?? 0) + 300 { break }
    if el > 3 && (player.state == .ended || player.state == .stopped || player.state == .error) { break }
}
out("fim estado=\(player.state.rawValue) salvos=\(saved)")
player.stop()
RunLoop.main.run(until: Date().addingTimeInterval(1))
exit(0)
