// Sincronização da legenda pelo áudio: tipos básicos (porte 1:1 do motor Java que roda no Android/TV: android/.../audiosync).
// Nomes com prefixo "Audio" para não colidir com SubtitleAlign.Cue, Swift.Result etc.
import Foundation

/** Detector de fala: PCM 16 bits mono -> probabilidade de fala (0..1) a cada 10 ms (100 quadros/s). */
protocol AudioVad: AnyObject {
    func speechProb(_ pcm: [Int16], _ n: Int, _ rate: Int) -> [Float]
    var name: String { get }
}

/** Um trecho de áudio já passado pelo VAD: probabilidade de fala a 100 quadros/s, começando em startMs (tempo do vídeo). */
struct AudioWindow {
    let startMs: Int
    let p: [Float]
    var durationMs: Int { return p.count * 10 }
}

/** Trecho de um ajuste por partes (tempoCorrigido = scale * tempoLegenda + offsetMs a partir de fromSubMs). */
struct AudioSegment {
    let fromSubMs: Int
    let scale: Double
    let offsetMs: Double
}

/** Resultado do motor. Mapeamento: tempoCorrigido = scale * tempoLegenda + offsetMs (ms). */
final class AudioResult {
    var accepted = false
    /** aceito e a correção estimada é < deadbandMs com escala 1: a legenda já está sincronizada (offset zerado: não mexer) */
    var inSync = false
    /** o motor viu sinais de cortes: só aviso, nunca aplicado automaticamente (accepted = false) */
    var cutsWarning = false
    var scale = 1.0
    var offsetMs = 0.0
    /** deslocamento estimado antes da banda morta */
    var rawOffsetMs = 0.0
    var winsValid = 0
    var winsAgree = 0
    var winMaxDev = 0.0
    var scaleKnown = false
    var confidence = 0.0
    var reason = ""
    var z = 0.0, margin = 0.0, nccMean = 0.0, nccMin = 0.0, f1 = 0.0, support = 0.0
    var blocksValid = 0, blocksAgree = 0, windows = 0
    var segments: [AudioSegment]? = nil
    /** o motor pede mais áudio: janelas (inicioMs, duracaoMs) */
    var more: [[Int]]? = nil
    var pieceSuspect = false
    var audioMs = 0
    var rounds = 0

    // ---- deriva (escala de fps: 1,001 e afins). Cada janela mede o atraso local SEM escala; a escala só vale se a reta atraso(t) = a + b*t explica todas as janelas
    struct Win {
        var startMs = 0
        var centerMs = 0.0, delayMs = 0.0, ncc = 0.0, z = 0.0, second = 0.0
        var speech = false, confident = false
    }
    var wins: [Win] = []
    /** a escala (1,001 ou outra razão de fps) foi aceita pela evidência das janelas */
    var driftScale = false
    /** índice da janela confiável que ficou fora da reta de deriva (descartada); -1 = nenhuma */
    var driftDropWin = -1
    /** as janelas de z alto concordam em torno da mediana (porta robusta, ver finalEvidence) */
    var winConsensus = true
    /// invariante: o deslocamento aplicado contradiz em mais de shiftContraMs o que as janelas medem com busca larga
    var shiftContra = false
    var finDetail = ""
    /** o resultado é o do plano B (motor de antes sobre as 3 janelas densas) */
    var planB = false
    var driftSlope = 0.0, driftResMs = 0.0, unitResMs = 0.0, driftMeasMs = 0.0, driftContentMs = 0.0
    var driftConfident = 0
    /** as janelas confiáveis discordam de uma reta de escala 1,0: vale pedir mais janelas espalhadas */
    var driftSuspect = false
    var driftMore: [[Int]]? = nil
    var driftNote = ""

    func apply(_ subMs: Double) -> Double { return scale * subMs + offsetMs }
}

// ---------------------------------------------------------------- utilitários com a semântica do Java

/** (int) de um double em Java: trunca, NaN vira 0, satura nos limites de 32 bits */
@inline(__always) func jint(_ d: Double) -> Int {
    if d.isNaN { return 0 }
    if d >= 2147483647.0 { return 2147483647 }
    if d <= -2147483648.0 { return -2147483648 }
    return Int(d)
}

/** (long) de um double em Java */
@inline(__always) func jlong(_ d: Double) -> Int {
    if d.isNaN { return 0 }
    if d >= 9.2e18 { return Int.max }
    if d <= -9.2e18 { return Int.min }
    return Int(d)
}

/** Math.round(double) em Java: arredonda para cima nos meios (floor(x + 0,5)) */
@inline(__always) func jround(_ d: Double) -> Int {
    if d.isNaN { return 0 }
    return jlong((d + 0.5).rounded(.down))
}

/** java.util.Random (LCG de 48 bits) para o mesmo "nulo" aleatório do motor Java */
struct JavaRandom {
    private var seed: UInt64
    private static let mask: UInt64 = (1 << 48) - 1

    init(_ s: Int) { seed = (UInt64(bitPattern: Int64(s)) ^ 0x5DEECE66D) & JavaRandom.mask }

    private mutating func next(_ bits: Int) -> Int32 {
        seed = (seed &* 0x5DEECE66D &+ 0xB) & JavaRandom.mask
        return Int32(truncatingIfNeeded: Int64(bitPattern: seed >> UInt64(48 - bits)))
    }

    mutating func nextInt(_ bound: Int) -> Int {
        let b = Int32(bound)
        var r = next(31)
        let m = b &- 1
        if (b & m) == 0 {
            r = Int32(truncatingIfNeeded: (Int64(b) &* Int64(r)) >> 31)
        } else {
            var u = r
            while true {
                r = u % b
                if u &- r &+ m >= 0 { break }
                u = next(31)
            }
        }
        return Int(r)
    }

    mutating func nextDouble() -> Double {
        let a = Int64(next(26))
        let b = Int64(next(27))
        return Double((a << 27) &+ b) * (1.0 / 9007199254740992.0)
    }
}
