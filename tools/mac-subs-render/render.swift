// Prova visual da legenda do Mac (só no CI, macOS): desenha SubtitleOverlayView (o MESMO arquivo SubOverlay.swift do app, antes ou depois) por cima de
// quadros de vídeo, SEM janela e sem tela: num bitmap de 2x (como numa MacBook Retina), e grava PNGs.
// Uso: render <pasta de saída> <prefixo> <quadro1.png> [quadro2.png ...]
// Para cada quadro e cada fala: <prefixo>-<quadro>-<fala>.png (o quadro inteiro, 2880x1620 px = 1440x810 pt a 2x) e ...-zoom.png (o trecho da legenda em pixels reais).
import Cocoa

let args = CommandLine.arguments
if args.count < 4 { print("uso: render <saída> <prefixo> <quadros...>"); exit(2) }
let outDir = args[1], prefix = args[2]
let frames = Array(args[3...])
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let ptW: CGFloat = 1440, ptH: CGFloat = 810      // área de vídeo de uma MacBook de 13-14" em tela cheia, em pontos
let scale = Int(ProcessInfo.processInfo.environment["RENDER_SCALE"] ?? "2") ?? 2

let cases: [(String, String)] = [
    ("curta", "Eu vou voltar para casa."),
    ("acentos", "Não é possível, você não está a ouvir?\nAção, emoção e coração: à noite, até às seis."),
    ("duas", "- Você não entende o que está a dizer.\n- Eu entendo perfeitamente, doutor. Mas a questão é outra."),
    ("longa", "Quando a cidade dormia, ele atravessava a ponte devagar, a pensar em tudo o que tinha perdido e no pouco que ainda lhe restava para recomeçar a vida."),
]

func bitmap() -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(ptW) * scale, pixelsHigh: Int(ptH) * scale, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: ptW, height: ptH)    // pontos != pixels: o contexto vem com escala 2x, como numa tela Retina
    return rep
}

func png(_ rep: NSBitmapImageRep, _ path: String) {
    if let d = rep.representation(using: .png, properties: [:]) { try? d.write(to: URL(fileURLWithPath: path)) }
}

for fpath in frames {
    guard let img = NSImage(contentsOfFile: fpath) else { print("não abriu \(fpath)"); continue }
    let fname = (fpath as NSString).lastPathComponent.replacingOccurrences(of: ".png", with: "")
    for (cname, text) in cases {
        let rep = bitmap()
        guard let gctx = NSGraphicsContext(bitmapImageRep: rep) else { continue }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = gctx
        gctx.imageInterpolation = .high
        img.draw(in: NSRect(x: 0, y: 0, width: ptW, height: ptH), from: .zero, operation: .copy, fraction: 1)
        let v = SubtitleOverlayView(frame: NSRect(x: 0, y: 0, width: ptW, height: ptH))
        v.bottomMargin = max(24, (ptH * 0.06).rounded())      // como o app: 6 % da altura
        v.text = text
        v.displayIgnoringOpacity(v.bounds, in: gctx)
        NSGraphicsContext.restoreGraphicsState()
        let base = "\(outDir)/\(prefix)-\(fname)-\(cname)"
        png(rep, base + ".png")
        // o trecho da legenda em pixels reais (sem reduzir): 70 % da largura, 30 % da altura de baixo
        if let cg = rep.cgImage {
            let w = cg.width, h = cg.height
            let crop = CGRect(x: Int(Double(w) * 0.15), y: Int(Double(h) * 0.62), width: Int(Double(w) * 0.70), height: Int(Double(h) * 0.38))
            if let c = cg.cropping(to: crop) { png(NSBitmapImageRep(cgImage: c), base + "-zoom.png") }
        }
        print("ok \(base)")
    }
}
