// Deriva a partir de DOIS ajustes manuais do atraso (pontos {tempo do vídeo, atraso}): se o usuário acertou a legenda num ponto e, mais de ~10 min depois no
// filme, teve de acertar de novo com um atraso diferente, a legenda está desviando. Dois pontos sempre cabem numa reta, então só vale se a inclinação bate com
// uma razão de fps CONHECIDA dentro da precisão de um ajuste feito à mão. Porte de ManualDrift.java.
// Modelo: atraso(t) = alpha*t + beta, alpha = 1 - 1/escala; tempoCorrigido = escala*tempoLegenda + deslocamento.
import Foundation

enum ManualDrift {
    /** distância mínima entre os dois ajustes, no tempo do filme */
    static let minGapMs = 600_000
    /** a mudança de atraso entre os dois pontos tem de passar disto (menos é ruído de ajuste manual) */
    static let minChangeMs = 600.0
    /** quanto a mudança de atraso medida pode diferir da que a razão conhecida daria no intervalo (ms) */
    static let tolMs = 300.0

    struct Fit {
        var scale = 1.0, offsetMs = 0.0, slope = 0.0
        /** quanto (ms, no intervalo entre os pontos) a inclinação medida difere da conhecida */
        var errMs = 0.0
    }

    /** atraso (ms) que o modelo dá no instante t do vídeo */
    static func delayAt(_ scale: Double, _ offsetMs: Double, _ tMs: Double) -> Double {
        return (scale - 1.0) / scale * tMs + offsetMs / scale
    }

    /** deslocamento do modelo com a escala dada que passa exatamente pelo ponto (tMs, delayMs): o atraso não "pula" nesse ponto */
    static func anchorOffset(_ scale: Double, _ delayMs: Double, _ tMs: Double) -> Double {
        let alpha = (scale - 1.0) / scale
        return scale * (delayMs - alpha * tMs)
    }

    /** o ajuste (escala conhecida, deslocamento ancorado no ponto mais recente) ou nil se os dois pontos não indicam deriva */
    static func fit(_ ta: Int, _ da: Double, _ tb: Int, _ db: Double, _ knownScales: [Double]) -> Fit? {
        var t1 = ta, d1 = da, t2 = tb, d2 = db
        if t2 < t1 { swap(&t1, &t2); swap(&d1, &d2) }
        let dt = t2 - t1
        if dt < minGapMs || abs(d2 - d1) < minChangeMs { return nil }
        let slope = (d2 - d1) / Double(dt)
        var best: Fit? = nil
        for k in knownScales {
            if abs(k - 1.0) < 1e-9 { continue }
            let al = (k - 1.0) / k
            let err = abs(slope - al) * Double(dt)
            if err > tolMs { continue }
            if best == nil || err < best!.errMs { best = Fit(scale: k, offsetMs: 0, slope: slope, errMs: err) }
        }
        guard var b = best else { return nil }
        b.offsetMs = anchorOffset(b.scale, d2, Double(t2))
        return b
    }
}
