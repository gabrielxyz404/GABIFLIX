// Sonda: toca um vídeo no VLCKit (mesma biblioteca do app) gravando o resultado com as legendas queimadas (transcode soverlay).
// uso: subprobe <modo> <video> <srt> <saida.ts> <escala|-> <atrasoMs> [opcoes-extra-de-midia separadas por ;]
// modos: media (sub-file + :sub-text-scale na mídia), lib (sub-text-scale na biblioteca), slave (addPlaybackSlave depois do play, como o app)
import Foundation
import VLCKit

func out(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) }
let a = CommandLine.arguments
guard a.count >= 7 else { out("uso"); exit(2) }
let mode = a[1], video = a[2], srt = a[3], dst = a[4], scale = a[5], delayMs = Int(a[6]) ?? 0
let extra = a.count > 7 ? a[7].split(separator: ";").map(String.init) : []

var libOpts: [String] = []
if mode == "lib" && scale != "-" { libOpts.append("--sub-text-scale=\(scale)") }
for e in extra where e.hasPrefix("--") { libOpts.append(e) }
let player: VLCMediaPlayer = libOpts.isEmpty ? VLCMediaPlayer() : VLCMediaPlayer(options: libOpts)
let media = VLCMedia(path: video)
media.addOption(":sout=#transcode{vcodec=h264,vb=3000,soverlay}:std{access=file,mux=ts,dst=\(dst)}")
media.addOption(":no-sout-all")
if mode == "media" || mode == "slave" { if scale != "-" { media.addOption(":sub-text-scale=\(scale)") } }
if mode == "media" { media.addOption(":sub-file=\(srt)") }
for e in extra where e.hasPrefix(":") { media.addOption(e) }
out("modo=\(mode) escala=\(scale) atraso=\(delayMs) lib=\(libOpts) extra=\(extra)")
player.media = media
player.play()
var added = false
let t0 = Date()
var lastLog = Date.distantPast
while Date().timeIntervalSince(t0) < 120 {
    RunLoop.main.run(until: Date().addingTimeInterval(0.2))
    let st = player.state
    let el = Date().timeIntervalSince(t0)
    if mode == "slave" && !added && el > 2.0 {
        added = true
        let ok = player.addPlaybackSlave(URL(fileURLWithPath: srt), type: .subtitle, enforce: true)
        out("addPlaybackSlave -> \(ok) em t=\(player.time.intValue)")
    }
    if delayMs != 0 && (mode != "slave" || el > 3.5) && player.currentVideoSubTitleDelay != delayMs * 1000 {
        player.currentVideoSubTitleDelay = delayMs * 1000
        out("delay aplicado=\(delayMs * 1000)us em t=\(player.time.intValue) lido=\(player.currentVideoSubTitleDelay)")
    }
    if Date().timeIntervalSince(lastLog) > 2 {
        lastLog = Date()
        out("t=\(el) estado=\(st.rawValue) tempo=\(player.time.intValue) faixas=\((player.videoSubTitlesNames as? [String]) ?? []) idx=\((player.videoSubTitlesIndexes as? [NSNumber]) ?? []) atual=\(player.currentVideoSubTitleIndex) atraso=\(player.currentVideoSubTitleDelay)")
    }
    if el > 3 && (st == .ended || st == .stopped || st == .error) { break }
}
out("fim estado=\(player.state.rawValue)")
player.stop()
RunLoop.main.run(until: Date().addingTimeInterval(2))
exit(0)
