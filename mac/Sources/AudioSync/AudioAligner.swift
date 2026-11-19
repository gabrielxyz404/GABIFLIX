// Alinha as falas de uma legenda ao ÁUDIO do vídeo: correlação cruzada normalizada (FFT) entre a probabilidade de fala do VAD
// (janelas capturadas em instantes conhecidos do vídeo) e a máscara de falas da legenda. Porte 1:1 de AudioAligner.java (Android/TV),
// com os mesmos parâmetros validados: z >= 9, só escalas de fps conhecidas (0,999 e 1,001001 recusadas), porta de concordância entre
// janelas de 150 ms, banda morta de 1100 ms e cortes só como aviso.
//
// Modelo: tempoCorrigido(ms) = scale * tempoLegenda + offsetMs.  Defasagem: L(u) = u - s = alpha*u + beta, alpha = 1 - 1/scale, beta = offset/scale.
import Foundation

enum AudioAligner {
    struct Params {
        var frameMs = 40
        var blockMs = 30_000
        /** metade da faixa de defasagem pesquisada (quadros de frameMs): 32000 * 40 ms = ±1280 s */
        var lagFrames = 32_000
        var maxOffsetMs = 600_000.0
        var maxAlpha = 0.125
        var topK = 12
        var tolFrames = 9
        var trimCues = true
        var smoothMs = 60.0
        /** escalas "de fps" conhecidas (tempoCorrigido = scale * tempoLegenda) */
        var knownScales: [Double] = [1.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 23.976, 23.976 / 25.0, 25.0 / 24.0, 24.0 / 25.0,
                                     30.0 / 29.97, 29.97 / 30.0, 25.0 / 29.97, 29.97 / 25.0, 30.0 / 25.0, 25.0 / 30.0, 30.0 / 24.0, 24.0 / 30.0]
        var randomHyps = 4000
        var seed = 12345
        var minZ = 6.0
        var minAgreeFrac = 0.55
        var minNcc = 0.10
        var minMargin = 0.15
        var minF1 = 0.0
        var minZFree = 12.0
        var minNccFree = 0.35
        var snapAlpha = 3e-3
        var snapResMs = 120.0
        var acceptMinZ = 9.0
        var unitAlpha = 6e-4
        var deadbandMs = 1100.0
        /** a "família 1,001" (0,999 e 1,001001) só vale com PROVA nas janelas (driftCheck), nunca por si só */
        var nearUnitEps = 0.0015
        var driftMinWins = 3
        var driftMinSpanFrac = 0.25
        var driftMinSpanMs = 240_000
        var driftMaxResMs = 250.0
        /** a reta de deriva só é aplicada se o resíduo máximo não passa disto (ms); acima disso o ajuste é "inseguro": pede janelas extras e, se não fecha, recusa */
        var driftSafeResMs = 180.0
        /** com a deriva provada, o desvio de cada janela para o modelo final pode chegar a isto (ms) sem derrubar a aceitação */
        var driftGateDevMs = 200.0
        var driftResFloorMs = 50.0
        var driftRatio = 3.0
        var driftContentMs = 800.0
        var winMinNcc = 0.15
        var winMinMargin = 0.08
        var winMinZ = 5.0
        var driftSuspectMs = 150.0
        var maxDriftWindows = 10
        var gateWinDevMs = 150.0
        var gateMinWinsAgree = 0
        /** porta de concordância robusta (sem deriva): descarta as janelas de z baixo (< gateZFrac x o maior z), pede que as que ficam concordem em torno da mediana
         *  (desvio máximo <= min(gateTolMaxMs, gateWinDevMs + gateTolStepMs x (janelas - 3))) e que o desvio robusto (1,4826 x MAD) fique abaixo de gateSigmaMs */
        var gateZFrac = 0.6
        var gateTolMaxMs = 350.0
        var gateTolStepMs = 50.0
        var gateSigmaMs = 150.0
        /** motor de ANTES (plano B): sem prova de deriva e com a porta de 150 ms sobre o desvio máximo bruto; é o que o origin/main fazia */
        var legacy = false
        var unitSnap = 0.0035
        var allowFreeScale = false
        var minBlocks = 3
        var minSpeechFrac = 0.03
        var maxCueGapForCut = 20_000
        var knownMinFrac = 0.4
        var pieceFitNcc = 0.25
        var pieceMinZ = 5.0
        var pieceExplained = 0.8
        var pieceRelPeak = 0.7
        var maxPieces = 4
        var cutResolutionMs = 50_000
        var bisectLenMs = 30_000
        var moreMinZ = 4.5
        var maxWindows = 9
    }

    // ------------------------------------------------------------------ estruturas
    final class Block {
        var win = 0
        var uMs = 0           // início do bloco (múltiplo de frameMs)
        var centerMs = 0
        var n = 0
        var x: [Float] = []   // quadros grossos (zero-média)
        var norm = 0.0
        var speechFrac = 0.0
        var valid = false
        var c: [Float] = []   // NCC por defasagem (índice = lagFrames + lag)
        var peakIdx: [Int] = []
        var peakVal: [Float] = []
    }

    struct Fine {
        var bestDelta = 0.0   // ms (já com interpolação)
        var ncc = 0.0         // NCC no melhor deslocamento
        var ncc0 = 0.0        // NCC em deslocamento 0
        var nccSecond = 0.0
        var z = 0.0           // quanto o pico se destaca das demais defasagens (desvios-padrão), fora de +-500 ms do pico
        var speechFrac = 0.0
    }

    // ------------------------------------------------------------------ máscara
    /** cobertura de falas a 10 ms: fração do quadro coberta por alguma fala (0..1) */
    static func fineMask(_ cues: [AudioCue], _ trim: Bool) -> [Float] {
        var end = 0
        for c in cues where c.speech { end = max(end, c.speechEnd(trim)) }
        let n = end / 10 + 400
        var m = [Float](repeating: 0, count: max(n, 1))
        for c in cues {
            if !c.speech { continue }
            var a = c.startMs
            let b = c.speechEnd(trim)
            if b <= a { continue }
            if b <= 0 { continue }
            if a < 0 { a = 0 }
            let i0 = a / 10, i1 = b / 10
            var i = i0
            while i <= i1 && i < n {
                let lo = Double(max(a, i * 10)), hi = Double(min(b, (i + 1) * 10))
                if hi > lo { m[i] = min(1, m[i] + Float((hi - lo) / 10.0)) }
                i += 1
            }
        }
        return m
    }

    static func coarseMask(_ fine: [Float], _ frameMs: Int) -> [Float] {
        let k = frameMs / 10
        let n = fine.count / k
        var m = [Float](repeating: 0, count: n)
        for i in 0..<n {
            var s: Float = 0
            for j in 0..<k { s += fine[i * k + j] }
            m[i] = s / Float(k)
        }
        return m
    }

    /** cobertura da máscara fina no instante s (ms da legenda), interpolada */
    static func cov(_ fine: [Float], _ sMs: Double) -> Float {
        let q = sMs / 10.0 - 0.5
        let i = jint(q.rounded(.down))
        let f = q - Double(i)
        let a: Float = (i >= 0 && i < fine.count) ? fine[i] : 0
        let b: Float = (i + 1 >= 0 && i + 1 < fine.count) ? fine[i + 1] : 0
        return Float(Double(a) * (1 - f) + Double(b) * f)
    }

    static func smooth(_ p: [Float], _ sigmaMs: Double) -> [Float] {
        let sg = sigmaMs / 10.0
        if sg < 0.5 { return p }
        let r = jint((3 * sg).rounded(.up))
        var k = [Double](repeating: 0, count: 2 * r + 1)
        var sum = 0.0
        for i in -r...r {
            let v = exp(-0.5 * Double(i) * Double(i) / (sg * sg))
            k[i + r] = v
            sum += v
        }
        for i in 0..<k.count { k[i] /= sum }
        var o = [Float](repeating: 0, count: p.count)
        let cnt = p.count
        p.withUnsafeBufferPointer { p in
            k.withUnsafeBufferPointer { k in
                for i in 0..<cnt {
                    var s = 0.0
                    for j in -r...r {
                        var t = i + j
                        if t < 0 { t = 0 } else if t >= cnt { t = cnt - 1 }
                        s += Double(p[t]) * k[j + r]
                    }
                    o[i] = Float(s)
                }
            }
        }
        return o
    }

    // ------------------------------------------------------------------ etapa grossa
    private static func makeBlocks(_ ws: [AudioWindow], _ P: Params) -> [Block] {
        var out: [Block] = []
        let k = P.frameMs / 10
        let n = P.blockMs / P.frameMs
        for w in 0..<ws.count {
            let p = smooth(ws[w].p, P.smoothMs)
            // alinha o início à grade de frameMs: descarta o pedacinho inicial
            let off = (P.frameMs - (ws[w].startMs % P.frameMs)) % P.frameMs
            let skip = off / 10
            let frames = (p.count - skip) / k
            var nb = max(1, frames / n)
            if frames < n * 2 / 3 { continue }
            if frames >= n {
                // divide em blocos de n quadros (o resto se junta ao último se for ≥ 2/3)
                nb = frames / n
                let rest = frames - nb * n
                if rest >= n * 2 / 3 { nb += 1 }
            } else { nb = 1 }
            for b in 0..<nb {
                let f0 = b * n
                let len = min(n, frames - f0)
                let bl = Block()
                bl.win = w
                bl.n = len
                bl.uMs = ((ws[w].startMs + P.frameMs - 1) / P.frameMs) * P.frameMs + f0 * P.frameMs
                bl.centerMs = bl.uMs + len * P.frameMs / 2
                var x = [Float](repeating: 0, count: len)
                var mean = 0.0
                var sp = 0
                for i in 0..<len {
                    var s: Float = 0
                    for j in 0..<k { s += p[skip + (f0 + i) * k + j] }
                    s /= Float(k)
                    x[i] = s
                    mean += Double(s)
                    if s > 0.5 { sp += 1 }
                }
                mean /= Double(len)
                var nn = 0.0
                for i in 0..<len { x[i] -= Float(mean); nn += Double(x[i]) * Double(x[i]) }
                bl.x = x
                bl.norm = nn.squareRoot()
                bl.speechFrac = Double(sp) / Double(len)
                bl.valid = bl.norm > 1e-3 && bl.speechFrac >= P.minSpeechFrac && bl.speechFrac <= 0.97
                out.append(bl)
            }
        }
        return out
    }

    private static func correlate(_ bl: Block, _ m: [Float], _ P: Params, _ fft: AudioFft,
                                  _ xr: inout [Double], _ xi: inout [Double], _ yr: inout [Double], _ yi: inout [Double]) {
        let Q = P.lagFrames, n = bl.n, N = fft.n
        let ylen = 2 * Q + n
        let g = bl.uMs / P.frameMs
        for i in 0..<N { xr[i] = 0; xi[i] = 0; yr[i] = 0; yi[i] = 0 }
        for i in 0..<n { xr[i] = Double(bl.x[i]) }
        var p1 = [Double](repeating: 0, count: ylen + 1), p2 = [Double](repeating: 0, count: ylen + 1)
        for j in 0..<ylen {
            let idx = g - Q + j
            let v: Double = (idx >= 0 && idx < m.count) ? Double(m[idx]) : 0.0
            yr[j] = v
            p1[j + 1] = p1[j] + v
            p2[j + 1] = p2[j] + v * v
        }
        fft.transform(&xr, &xi, false)
        fft.transform(&yr, &yi, false)
        // conj(X) * Y
        for i in 0..<N {
            let a = xr[i], b = -xi[i], c = yr[i], d = yi[i]
            yr[i] = a * c - b * d
            yi[i] = a * d + b * c
        }
        fft.transform(&yr, &yi, true)
        var c = [Float](repeating: 0, count: 2 * Q + 1)
        let floorVar = 0.03 * Double(n)
        for d in 0...(2 * Q) {
            let s1 = p1[d + n] - p1[d], s2 = p2[d + n] - p2[d]
            var v2 = s2 - s1 * s1 / Double(n)
            if v2 < floorVar { v2 = floorVar }
            let num = yr[d] / Double(N)
            let v = num / (bl.norm * v2.squareRoot())
            c[2 * Q - d] = Float(v)   // lag = Q - d  -> índice = lag + Q = 2Q - d
        }
        bl.c = c
        // picos locais
        let K = P.topK, sep = 8
        var pi = [Int](repeating: 0, count: K)
        var pv = [Float](repeating: 0, count: K)
        var cnt = 0
        var i = sep
        while i < c.count - sep {
            defer { i += 1 }
            let v = c[i]
            if v < 0.05 { continue }
            if cnt == K && v <= pv[K - 1] { continue }
            var top = true
            var j = i - sep
            while j <= i + sep { if c[j] > v { top = false; break }; j += 1 }
            if !top { continue }
            // inserção ordenada
            var pos = cnt < K ? cnt : K - 1
            while pos > 0 && pv[pos - 1] < v {
                if pos < K { pv[pos] = pv[pos - 1]; pi[pos] = pi[pos - 1] }
                pos -= 1
            }
            pv[pos] = v; pi[pos] = i
            if cnt < K { cnt += 1 }
        }
        bl.peakIdx = Array(pi[0..<cnt])
        bl.peakVal = Array(pv[0..<cnt])
    }

    // ------------------------------------------------------------------ hipóteses
    /** melhor valor de correlação do bloco perto da defasagem prevista (ms) */
    private static func support(_ b: Block, _ lagMs: Double, _ P: Params) -> Float {
        let li = lagMs / Double(P.frameMs)
        let c = jround(li) + P.lagFrames
        var lo = c - P.tolFrames
        var hi = c + P.tolFrames
        if hi < 0 || lo >= b.c.count { return 0 }
        if lo < 0 { lo = 0 }
        if hi >= b.c.count { hi = b.c.count - 1 }
        var best: Float = 0
        var i = lo
        while i <= hi { if b.c[i] > best { best = b.c[i] }; i += 1 }
        return best
    }

    private static func score(_ bl: [Block], _ use: [Int], _ nu: Int, _ alpha: Double, _ beta: Double, _ P: Params) -> Double {
        var s = 0.0
        for t in 0..<nu {
            let b = bl[use[t]]
            s += Double(support(b, alpha * Double(b.centerMs) + beta, P))
        }
        return s
    }

    static func alphaOf(_ scale: Double) -> Double { return 1.0 - 1.0 / scale }

    // ------------------------------------------------------------------ exato (10 ms)
    /** NCC fina de uma janela com o modelo (a, b), pesquisando deslocamentos de ±R*10 ms */
    static func fineWindow(_ pSmooth: [Float], _ startMs: Int, _ fine: [Float], _ a: Double, _ b: Double, _ R: Int) -> Fine {
        let n = pSmooth.count
        var out = Fine()
        if n == 0 { return out }
        var mean = 0.0
        for i in 0..<n { mean += Double(pSmooth[i]) }
        mean /= Double(n)
        var x = [Float](repeating: 0, count: n)
        var nn = 0.0
        var sp = 0
        for i in 0..<n {
            x[i] = Float(Double(pSmooth[i]) - mean)
            nn += Double(x[i]) * Double(x[i])
            if pSmooth[i] > 0.5 { sp += 1 }
        }
        let xnorm = nn.squareRoot()
        out.speechFrac = Double(sp) / Double(n)
        if xnorm < 1e-6 { return out }
        let L = n + 2 * R
        var M = [Float](repeating: 0, count: L)
        var p1 = [Double](repeating: 0, count: L + 1), p2 = [Double](repeating: 0, count: L + 1)
        for j in 0..<L {
            let u = Double(startMs) + 10.0 * Double(j - R) + 5.0
            let v = cov(fine, (u - b) / a)
            M[j] = v
            p1[j + 1] = p1[j] + Double(v)
            p2[j + 1] = p2[j] + Double(v) * Double(v)
        }
        var ncc = [Double](repeating: 0, count: 2 * R + 1)
        let floorVar = 0.03 * Double(n)
        x.withUnsafeBufferPointer { xp in
            M.withUnsafeBufferPointer { mp in
                for D in -R...R {
                    // M[j - D] com índice deslocado de R
                    let o = R - D
                    var num = 0.0
                    for j in 0..<n { num += Double(xp[j] * mp[j + o]) }
                    let s1 = p1[o + n] - p1[o], s2 = p2[o + n] - p2[o]
                    var v2 = s2 - s1 * s1 / Double(n)
                    if v2 < floorVar { v2 = floorVar }
                    ncc[D + R] = num / (xnorm * v2.squareRoot())
                }
            }
        }
        var bi = 0
        for i in 1..<ncc.count where ncc[i] > ncc[bi] { bi = i }
        var frac = 0.0
        if bi > 0 && bi < ncc.count - 1 {
            let y0 = ncc[bi - 1], y1 = ncc[bi], y2 = ncc[bi + 1]
            let den = y0 - 2 * y1 + y2
            if den < -1e-12 { frac = 0.5 * (y0 - y2) / den }
            if frac > 1 { frac = 1 } else if frac < -1 { frac = -1 }
        }
        out.bestDelta = (Double(bi - R) + frac) * 10.0
        out.ncc = ncc[bi]
        out.ncc0 = ncc[R]
        var sec = -1.0
        for i in 0..<ncc.count where abs(i - bi) > 50 && ncc[i] > sec { sec = ncc[i] }
        out.nccSecond = sec
        var mu = 0.0, s2z = 0.0
        var cn = 0
        for i in 0..<ncc.count where abs(i - bi) > 50 { mu += ncc[i]; s2z += ncc[i] * ncc[i]; cn += 1 }
        if cn > 10 {
            mu /= Double(cn)
            let sd = max(1e-12, s2z / Double(cn) - mu * mu).squareRoot()
            out.z = (ncc[bi] - mu) / sd
        }
        return out
    }

    // ------------------------------------------------------------------ solução
    static func solve(_ cues: [AudioCue], _ windows: [AudioWindow], _ mediaDurationMs: Int, _ P: Params) -> AudioResult {
        let R = AudioResult()
        var audio = 0
        for w in windows { audio += w.durationMs }
        R.audioMs = audio
        R.windows = windows.count
        var any = false
        for c in cues where c.speech { any = true; break }
        if !any || windows.isEmpty { R.reason = "sem falas ou sem áudio"; return R }

        let fine = fineMask(cues, P.trimCues)
        let coarse = coarseMask(fine, P.frameMs)
        let bl = makeBlocks(windows, P)
        let N = AudioFft.nextPow2(2 * P.lagFrames + P.blockMs / P.frameMs)
        let fft = AudioFft(N)
        var xr = [Double](repeating: 0, count: N), xi = [Double](repeating: 0, count: N)
        var yr = [Double](repeating: 0, count: N), yi = [Double](repeating: 0, count: N)
        var use = [Int](repeating: 0, count: bl.count)
        var nu = 0
        for i in 0..<bl.count where bl[i].valid {
            correlate(bl[i], coarse, P, fft, &xr, &xi, &yr, &yi)
            use[nu] = i
            nu += 1
        }
        R.blocksValid = nu
        if nu < P.minBlocks { R.reason = "pouca fala no áudio capturado (\(nu) blocos)"; return R }

        // ---- hipóteses
        var hyps: [(Double, Double)] = []
        var known: [Bool] = []
        for a in 0..<nu {
            let A = bl[use[a]]
            for s in 0..<P.knownScales.count {
                let al = alphaOf(P.knownScales[s])
                if abs(al) > P.maxAlpha { continue }
                for pk in 0..<A.peakIdx.count {
                    let L = Double(A.peakIdx[pk] - P.lagFrames) * Double(P.frameMs)
                    let beta = L - al * Double(A.centerMs)
                    if abs(beta) <= P.maxOffsetMs * 1.2 { hyps.append((al, beta)); known.append(true) }
                }
            }
            var b2 = a + 1
            while b2 < nu {
                defer { b2 += 1 }
                let B = bl[use[b2]]
                if B.win == A.win { continue }
                let du = Double(B.centerMs - A.centerMs)
                if abs(du) < 1000 { continue }
                for pa in 0..<A.peakIdx.count {
                    let La = Double(A.peakIdx[pa] - P.lagFrames) * Double(P.frameMs)
                    for pb in 0..<B.peakIdx.count {
                        let Lb = Double(B.peakIdx[pb] - P.lagFrames) * Double(P.frameMs)
                        let al = (Lb - La) / du
                        if abs(al) > P.maxAlpha { continue }
                        let beta = La - al * Double(A.centerMs)
                        if abs(beta) > P.maxOffsetMs * 1.2 { continue }
                        hyps.append((al, beta)); known.append(false)
                    }
                }
            }
        }
        if hyps.isEmpty { R.reason = "nenhum pico de correlação"; return R }
        let H = hyps.count
        var hs = [Double](repeating: 0, count: H)
        var bestH = 0
        for h in 0..<H {
            hs[h] = score(bl, use, nu, hyps[h].0, hyps[h].1, P)
            if hs[h] > hs[bestH] { bestH = h }
        }
        // nulo: retas aleatórias (não depende da hipótese)
        var rnd = JavaRandom(P.seed)
        var sum = 0.0, sum2 = 0.0
        let nr = P.randomHyps
        for _ in 0..<nr {
            var al = (rnd.nextDouble() * 2 - 1) * min(P.maxAlpha, 0.1)
            if rnd.nextInt(3) == 0 { al = 0 }
            let beta = (rnd.nextDouble() * 2 - 1) * P.maxOffsetMs
            let s = score(bl, use, nu, al, beta, P)
            sum += s; sum2 += s * s
        }
        let mu = sum / Double(nr), sd = max(1e-9, sum2 / Double(nr) - mu * mu).squareRoot()
        // preferência (Occam): se a melhor reta com escala "de fps" já é convincente, ela vence a reta livre
        var bestK = -1
        for h in 0..<H where known[h] && (bestK < 0 || hs[h] > hs[bestK]) { bestK = h }
        if bestK >= 0 && bestK != bestH && (hs[bestK] - mu) / sd >= P.minZ {
            let hk = hyps[bestK]
            var ex = 0
            for t in 0..<nu {
                let b = bl[use[t]]
                let v = support(b, hk.0 * Double(b.centerMs) + hk.1, P)
                var thr = Float(P.pieceFitNcc)
                if b.peakVal.count > 0 { thr = max(thr, Float(P.pieceRelPeak * Double(b.peakVal[0]))) }
                if v >= thr { ex += 1 }
            }
            if Double(ex) >= P.knownMinFrac * Double(nu) { bestH = bestK }
        }
        let h1 = hyps[bestH]
        let best = hs[bestH]
        // 2º melhor "diferente": difere em ≥ 60% dos blocos por mais de 1 s
        var second = 0.0
        for h in 0..<H {
            if hs[h] <= second { continue }
            let hh = hyps[h]
            var diff = 0
            for t in 0..<nu {
                let b = bl[use[t]]
                let d = (hh.0 - h1.0) * Double(b.centerMs) + (hh.1 - h1.1)
                if abs(d) > 1000 { diff += 1 }
            }
            if Double(diff) >= 0.6 * Double(nu) { second = hs[h] }
        }
        R.z = (best - mu) / sd
        R.margin = best > 0 ? (best - second) / best : 0
        R.support = best / Double(nu)

        // concordância dos blocos com a melhor reta
        var agree = 0
        for t in 0..<nu {
            let b = bl[use[t]]
            if Double(support(b, h1.0 * Double(b.centerMs) + h1.1, P)) >= P.minNcc { agree += 1 }
        }
        R.blocksAgree = agree

        // ---- refino: mínimos quadrados sobre picos de blocos (dentro da tolerância) para (alpha, beta)
        var alpha = h1.0, beta = h1.1
        for _ in 0..<2 {
            var sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
            for t in 0..<nu {
                let b = bl[use[t]]
                let pred = alpha * Double(b.centerMs) + beta
                let c = jround(pred / Double(P.frameMs)) + P.lagFrames
                let lo = max(0, c - P.tolFrames), hi = min(b.c.count - 1, c + P.tolFrames)
                if hi < lo { continue }
                var bi = lo
                var i = lo
                while i <= hi { if b.c[i] > b.c[bi] { bi = i }; i += 1 }
                if Double(b.c[bi]) < P.minNcc { continue }
                let w = Double(b.c[bi])
                let xx = Double(b.centerMs), yy = Double(bi - P.lagFrames) * Double(P.frameMs)
                sw += w; sx += w * xx; sy += w * yy; sxx += w * xx * xx; sxy += w * xx * yy
            }
            if sw <= 0 { break }
            let den = sw * sxx - sx * sx
            if den > 1e6 * sw {
                let a2 = (sw * sxy - sx * sy) / den
                if abs(a2 - alpha) < 0.02 { alpha = a2; beta = (sy - alpha * sx) / sw }
            } else {
                beta = sy / sw - alpha * sx / sw
            }
        }
        // modelo (scale, offset): scale = 1/(1-alpha), offset = beta * scale
        var a0 = 1.0 / (1.0 - alpha), b0 = beta * a0

        // ---- etapa fina: por janela, refina deslocamento e escala
        var ps: [[Float]] = []
        for w in 0..<windows.count { ps.append(smooth(windows[w].p, P.smoothMs)) }
        let model = fineRefine(windows, ps, fine, a0, b0, P, R, false)
        a0 = model.0; b0 = model.1
        for ks in P.knownScales where abs(ks - a0) < 1e-9 { R.scaleKnown = true }
        if !R.scaleKnown && abs(a0 - 1.0) < P.unitSnap {
            // escala "livre" muito perto de 1: é ruído; fixa 1,0 e reajusta só o deslocamento
            var uc = 0.0
            for w in windows { uc += Double(w.startMs) + Double(w.p.count) * 5.0 }
            uc /= Double(windows.count)
            let sc = (uc - b0) / a0
            let m1 = fineRefine(windows, ps, fine, 1.0, uc - sc, P, R, true)
            a0 = 1.0; b0 = m1.1
            R.scaleKnown = true
        }
        R.scale = a0
        R.offsetMs = b0
        if !P.legacy { driftCheck(R, cues, windows, ps, fine, mediaDurationMs, P) }
        finalEvidence(windows, ps, fine, R, P)
        decide(R, P)
        if !R.driftScale { analyzeCuts(R, cues, windows, ps, bl, use, nu, P, mediaDurationMs, fine) }
        if let sg = R.segments, sg.count > 1 {
            // cortes: só AVISO (a precisão medida do ajuste por partes é baixa); não se aplica nada
            R.cutsWarning = true
            R.accepted = false
            R.reason = (R.reason + " possíveis cortes na legenda (só aviso);").trimmingCharacters(in: .whitespaces)
        } else if R.segments != nil {
            R.segments = nil
        }
        if R.accepted && R.segments == nil && !R.driftScale && R.z < P.acceptMinZ {
            R.accepted = false
            R.reason = (R.reason + " z abaixo do limiar de aceitação (\(javaDouble(P.acceptMinZ)));").trimmingCharacters(in: .whitespaces)
        }
        R.rawOffsetMs = R.offsetMs
        if R.accepted && R.segments == nil && abs(R.scale - 1.0) < 1e-9 && abs(R.offsetMs) < P.deadbandMs {
            R.inSync = true
            R.offsetMs = 0
        }
        if !R.accepted { suggestMore(R, windows, P, mediaDurationMs) }
        return R
    }

    /** Double.toString do Java para valores simples (9.0) */
    private static func javaDouble(_ d: Double) -> String {
        return d == d.rounded() && abs(d) < 1e7 ? String(format: "%.1f", d) : "\(d)"
    }

    // ------------------------------------------------------------------ deriva (escala de fps, p.ex. 1,001)
    /** ajuste de atraso(t) = beta + alpha*t com alpha FIXO (mínimos quadrados ponderados): (beta, resíduo máximo em ms) */
    private static func fitFixedAlpha(_ x: [Double], _ y: [Double], _ w: [Double], _ n: Int, _ alpha: Double) -> (Double, Double) {
        var sw = 0.0, sy = 0.0
        for i in 0..<n { sw += w[i]; sy += w[i] * (y[i] - alpha * x[i]) }
        let beta = sw > 0 ? sy / sw : 0.0
        var res = 0.0
        for i in 0..<n { res = max(res, abs(y[i] - (beta + alpha * x[i]))) }
        return (beta, res)
    }

    /**
     * Prova de deriva (porte de AudioAligner.driftCheck). Cada janela mede, sozinha, o atraso local da legenda SEM escala (NCC fina em volta do modelo grosso se ele é
     * de escala ~1, +-10 s; senão em volta do atraso 0, +-15 s). A família 1,001 só vale com: >= driftMinWins janelas confiáveis cobrindo o filme; resíduo da reta com
     * a inclinação da razão de fps <= driftMaxResMs (podendo descartar UMA janela fora da reta); deriva medida > driftRatio x resíduo; deriva no filme todo >
     * driftContentMs; e a reta sem escala deixa o dobro do resíduo ou mais.
     */
    private static func driftCheck(_ R: AudioResult, _ cues: [AudioCue], _ ws: [AudioWindow], _ ps: [[Float]], _ fine: [Float], _ durMs: Int, _ P: Params) {
        let W = ws.count
        var info: [AudioResult.Win] = []
        var x = [Double](repeating: 0, count: W), y = [Double](repeating: 0, count: W), wt = [Double](repeating: 0, count: W)
        var widx = [Int](repeating: 0, count: W)
        var n = 0
        let nearUnitModel = abs(R.scale - 1.0) < 0.0035
        for w in 0..<W {
            var wi = AudioResult.Win()
            let uc = Double(ws[w].startMs) + Double(ws[w].p.count) * 5.0
            let d0 = nearUnitModel ? uc - (uc - R.offsetMs) / R.scale : 0.0
            let f = fineWindow(ps[w], ws[w].startMs, fine, 1.0, d0, nearUnitModel ? 1000 : 1500)
            wi.startMs = ws[w].startMs
            wi.centerMs = uc
            wi.delayMs = d0 + f.bestDelta
            wi.ncc = f.ncc
            wi.z = f.z
            wi.second = f.nccSecond
            wi.speech = f.speechFrac >= P.minSpeechFrac
            wi.confident = wi.speech && f.ncc >= P.winMinNcc && (f.ncc - f.nccSecond >= P.winMinMargin || f.z >= P.winMinZ)
            if wi.confident { x[n] = uc; y[n] = wi.delayMs; wt[n] = max(0.01, f.ncc); widx[n] = w; n += 1 }
            info.append(wi)
        }
        R.wins = info
        R.driftConfident = n
        if n < 2 { R.driftNote = "menos de 2 janelas confiáveis (\(n) de \(W))"; return }
        let unit = fitFixedAlpha(x, y, wt, n, 0.0)
        R.unitResMs = unit.1
        R.driftResMs = unit.1
        do {
            var sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
            for i in 0..<n { sw += wt[i]; sx += wt[i] * x[i]; sy += wt[i] * y[i]; sxx += wt[i] * x[i] * x[i]; sxy += wt[i] * x[i] * y[i] }
            let den = sw * sxx - sx * sx
            if den > 1e-6 * sw * sw { R.driftSlope = (sw * sxy - sx * sy) / den }
        }

        let ks = [24.0 / 23.976, 23.976 / 24.0]
        var best = -1
        var bestRes = Double.greatestFiniteMagnitude, bestBeta = 0.0, bestSpan = 0.0
        var bestN = 0, bestDrop = -1
        var nearMiss = false
        var why = ""
        for c in 0..<2 {
            let al = alphaOf(ks[c])
            var drop = -1
            var fr = fitFixedAlpha(x, y, wt, n, al)
            if fr.1 > P.driftMaxResMs && n > P.driftMinWins {
                for d in 0..<n {
                    var xs: [Double] = [], ys: [Double] = [], w2: [Double] = []
                    for i in 0..<n where i != d { xs.append(x[i]); ys.append(y[i]); w2.append(wt[i]) }
                    let f2 = fitFixedAlpha(xs, ys, w2, n - 1, al)
                    if f2.1 < fr.1 { fr = f2; drop = d }
                }
            }
            let nn = drop >= 0 ? n - 1 : n
            var xmin = Double.greatestFiniteMagnitude, xmax = -Double.greatestFiniteMagnitude
            var xs: [Double] = [], ys: [Double] = [], w2: [Double] = []
            for i in 0..<n where i != drop { xs.append(x[i]); ys.append(y[i]); w2.append(wt[i]); xmin = min(xmin, x[i]); xmax = max(xmax, x[i]) }
            let span = xmax - xmin
            let un = fitFixedAlpha(xs, ys, w2, nn, 0.0)
            let meas = abs(al) * span, content = abs(al) * Double(durMs)
            let fits = fr.1 <= P.driftMaxResMs && 2 * fr.1 <= un.1 && meas >= 2 * max(fr.1, P.driftResFloorMs)
            let spanOk = span >= max(Double(P.driftMinSpanMs), P.driftMinSpanFrac * Double(durMs))
            let safe = fr.1 <= P.driftSafeResMs
            let ok = fits && safe && nn >= P.driftMinWins && spanOk && meas >= P.driftRatio * max(fr.1, P.driftResFloorMs) && content >= P.driftContentMs
            if fits && !ok {
                nearMiss = true
                why = (!safe ? "resíduo \(jround(fr.1)) ms acima de \(Int(P.driftSafeResMs)) ms: ajuste inseguro; " : "") + (nn < P.driftMinWins ? "só \(nn) janelas confiáveis; " : "") + (!spanOk ? "janelas cobrem pouco do filme; " : "")
                    + (meas < P.driftRatio * max(fr.1, P.driftResFloorMs) ? "deriva medida pequena frente ao resíduo; " : "")
                    + (content < P.driftContentMs ? "deriva no filme abaixo de \(Int(P.driftContentMs)) ms; " : "")
            }
            if ok && fr.1 < bestRes { best = c; bestRes = fr.1; bestBeta = fr.0; bestSpan = span; bestN = nn; bestDrop = drop }
        }
        if best >= 0 {
            let al = alphaOf(ks[best])
            R.scale = ks[best]
            R.offsetMs = bestBeta * R.scale
            R.scaleKnown = true
            R.driftScale = true
            R.driftDropWin = bestDrop >= 0 ? widx[bestDrop] : -1
            R.driftResMs = bestRes
            R.driftMeasMs = abs(al) * bestSpan
            R.driftContentMs = abs(al) * Double(durMs)
            R.driftNote = String(format: "deriva provada: escala %.6f, resíduo %.0f ms (reta sem escala deixaria %.0f ms), deriva medida %.0f ms nas janelas e %.0f ms no filme, %d janelas confiáveis%@",
                                 R.scale, bestRes, unit.1, R.driftMeasMs, R.driftContentMs, bestN, bestDrop >= 0 ? " (1 janela fora da reta descartada)" : "")
            return
        }
        if abs(R.scale - 1.0) > 1e-9 && nearUnitModel && unit.1 <= P.driftMaxResMs {
            R.scale = 1.0
            R.offsetMs = unit.0
            R.scaleKnown = true
            R.driftNote = "escala 1,001 sem prova: a reta sem escala explica as janelas (resíduo \(jround(unit.1)) ms): fica 1,0"
            return
        }
        R.driftNote = "sem prova de deriva: resíduo da reta sem escala \(jround(unit.1)) ms" + (nearMiss ? " (" + why.trimmingCharacters(in: .whitespaces) + ")" : "")
        if unit.1 > P.driftSuspectMs || nearMiss {
            R.driftSuspect = true
            if W < P.maxDriftWindows { R.driftMore = moreSpread(cues, ws, durMs, P) }
        }
    }

    /** janelas extras espalhadas: das posições do plano de maxDriftWindows janelas, as mais afastadas das que já existem */
    private static func moreSpread(_ cues: [AudioCue], _ ws: [AudioWindow], _ durMs: Int, _ P: Params) -> [[Int]]? {
        let len = ws.count > 0 ? ws[0].durationMs : 90_000
        let plan = AudioSyncSession.planSpread(cues, durMs, P.maxDriftWindows, len)
        let want = P.maxDriftWindows - ws.count
        var pick: [[Int]] = []
        var used = [Bool](repeating: false, count: plan.count)
        var k = 0
        while k < want {
            var bi = -1
            var bd = -1
            for i in 0..<plan.count where !used[i] {
                var d = Int.max
                for w in ws { d = min(d, abs(plan[i][0] - w.startMs)) }
                for q in pick { d = min(d, abs(plan[i][0] - q[0])) }
                if d > bd { bd = d; bi = i }
            }
            if bi < 0 || bd < len + 10_000 { break }
            used[bi] = true
            pick.append(plan[bi])
            k += 1
        }
        return pick.isEmpty ? nil : pick
    }

    /** refina (scale, offset) com a NCC fina por janela; encaixa a escala em valores conhecidos quando os resíduos permitem */
    private static func fineRefine(_ ws: [AudioWindow], _ ps: [[Float]], _ fine: [Float], _ a: Double, _ b: Double,
                                   _ P: Params, _ R: AudioResult, _ lock: Bool) -> (Double, Double) {
        var bestA = a, bestB = b
        let W = ws.count
        for _ in 0..<3 {
            var sS = [Double](repeating: 0, count: W), uU = [Double](repeating: 0, count: W)
            var wt = [Double](repeating: 0, count: W), nc = [Double](repeating: 0, count: W)
            for w in 0..<W {
                let f = fineWindow(ps[w], ws[w].startMs, fine, bestA, bestB, 150)
                let bw = bestB + f.bestDelta
                let uc = Double(ws[w].startMs) + Double(ws[w].p.count) * 5.0
                sS[w] = (uc - bw) / bestA
                uU[w] = uc
                nc[w] = f.speechFrac > 0.03 ? max(0.0, f.ncc) : 0.0
            }
            // só entram janelas bem explicadas pelo modelo (as de outro trecho/ruído ficam de fora)
            let sorted = nc.sorted()
            let med = sorted[W / 2]
            let thr = max(P.minNcc * 0.8, 0.5 * med)
            var used = 0
            for w in 0..<W { wt[w] = nc[w] >= thr ? nc[w] : 0; if wt[w] > 0 { used += 1 } }
            if used == 0 { break }
            var fit = lsFit(sS, uU, wt, bestA, bestB, used >= 2 && !lock)
            // descarta janelas com resíduo grande e ajusta de novo
            var kept = 0
            for w in 0..<W {
                if wt[w] <= 0 { continue }
                if abs(uU[w] - (fit.0 * sS[w] + fit.1)) > 250 { wt[w] = 0 } else { kept += 1 }
            }
            if kept == 0 { break }
            if kept != used { fit = lsFit(sS, uU, wt, bestA, bestB, kept >= 2 && !lock) }
            let na = fit.0, nb = fit.1
            // encaixe: usa a escala conhecida mais próxima se os resíduos continuam pequenos
            let sa = alphaOf(na)
            var snapA = na, snapB = nb
            var sw = 0.0, sx = 0.0, sy = 0.0
            for w in 0..<W { if wt[w] <= 0 { continue }; sw += wt[w]; sx += wt[w] * sS[w]; sy += wt[w] * uU[w] }
            var bestDist = Double.greatestFiniteMagnitude
            for s in 0..<P.knownScales.count {
                let ks = P.knownScales[s]
                if abs(alphaOf(ks) - sa) > P.snapAlpha { continue }
                let kb = (sy - ks * sx) / sw
                var mx = 0.0
                for w in 0..<W where wt[w] > 0 { mx = max(mx, abs(uU[w] - (ks * sS[w] + kb))) }
                if mx > P.snapResMs { continue }
                let dist = (ks == 1.0 && abs(sa) <= P.unitAlpha) ? -1.0 : abs(alphaOf(ks) - sa)
                if dist < bestDist { bestDist = dist; snapA = ks; snapB = kb }
            }
            bestA = snapA; bestB = snapB
            if snapA == na { bestA = na; bestB = nb }
        }
        return (bestA, bestB)
    }

    /** mínimos quadrados ponderados de u = a*s + b; com um só ponto (ou pouca dispersão em s) mantém a escala a0 */
    private static func lsFit(_ s: [Double], _ u: [Double], _ w: [Double], _ a0: Double, _ b0: Double, _ free: Bool) -> (Double, Double) {
        var sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
        for i in 0..<s.count {
            if w[i] <= 0 { continue }
            sw += w[i]; sx += w[i] * s[i]; sy += w[i] * u[i]; sxx += w[i] * s[i] * s[i]; sxy += w[i] * s[i] * u[i]
        }
        if sw <= 0 { return (a0, b0) }
        let den = sw * sxx - sx * sx
        let spread = den / (sw * sw)
        var na = a0
        if free && spread > 600.0 * 600.0 {
            na = (sw * sxy - sx * sy) / den
            if abs(na - a0) > 0.01 { na = a0 }
        }
        return (na, (sy - na * sx) / sw)
    }

    private static func finalEvidence(_ ws: [AudioWindow], _ ps: [[Float]], _ fine: [Float], _ R: AudioResult, _ P: Params) {
        var sum = 0.0, mn = 9.0
        var cnt = 0
        var tp = 0.0, fp = 0.0, fn = 0.0
        var devs: [Double] = [], zs: [Double] = []
        for w in 0..<ws.count {
            if w == R.driftDropWin { continue }
            let f = fineWindow(ps[w], ws[w].startMs, fine, R.scale, R.offsetMs, 40)
            if f.speechFrac < P.minSpeechFrac { continue }
            let fw = fineWindow(ps[w], ws[w].startMs, fine, R.scale, R.offsetMs, 150)
            R.winsValid += 1
            let dev = abs(fw.bestDelta)
            if dev > R.winMaxDev { R.winMaxDev = dev }
            if dev <= 150 { R.winsAgree += 1 }
            devs.append(fw.bestDelta); zs.append(fw.z)
            sum += f.ncc0; cnt += 1
            if f.ncc0 < mn { mn = f.ncc0 }
            for j in 0..<ps[w].count {
                let u = Double(ws[w].startMs) + 10.0 * Double(j) + 5.0
                let m = cov(fine, (u - R.offsetMs) / R.scale) > 0.5
                let s = ps[w][j] > 0.5
                if m && s { tp += 1 } else if m { fp += 1 } else if s { fn += 1 }
            }
        }
        R.winConsensus = consensus(devs, zs, P)
        R.nccMean = cnt > 0 ? sum / Double(cnt) : 0
        R.nccMin = cnt > 0 ? mn : 0
        let pr = tp / max(1, tp + fp), rc = tp / max(1, tp + fn)
        R.f1 = (pr + rc) > 0 ? 2 * pr * rc / (pr + rc) : 0
    }

    /** Confere um modelo (escala, deslocamento) contra TODAS as janelas capturadas pela mesma porta robusta (porte de verifyModel). */
    static func verifyModel(_ cues: [AudioCue], _ ws: [AudioWindow], _ scale: Double, _ offsetMs: Double, _ P: Params) -> Bool {
        let fine = fineMask(cues, P.trimCues)
        var devs: [Double] = [], zs: [Double] = []
        for w in ws {
            let p = smooth(w.p, P.smoothMs)
            let f = fineWindow(p, w.startMs, fine, scale, offsetMs, 40)
            if f.speechFrac < P.minSpeechFrac { continue }
            let fw = fineWindow(p, w.startMs, fine, scale, offsetMs, 150)
            devs.append(fw.bestDelta); zs.append(fw.z)
        }
        return consensus(devs, zs, P)
    }

    private static func median(_ v: [Double]) -> Double {
        let c = v.sorted()
        let n = c.count
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2])
    }

    /**
     * Porta de concordância robusta entre as janelas (porte de consensus): descarta as de z baixo (< gateZFrac x o maior z); exige que fiquem pelo menos metade das
     * janelas (e 2); as que ficam concordam em torno da MEDIANA: desvio máximo <= tolerância (150 ms com 3 janelas, +gateTolStepMs por janela a mais, no máximo
     * gateTolMaxMs) e desvio robusto (1,4826 x MAD) <= gateSigmaMs.
     */
    private static func consensus(_ dev: [Double], _ z: [Double], _ P: Params) -> Bool {
        let n = dev.count
        if n < 2 { return true }
        var zmax = 0.0
        for v in z { zmax = max(zmax, v) }
        var kd: [Double] = []
        for i in 0..<n where z[i] >= P.gateZFrac * zmax { kd.append(dev[i]) }
        let k = kd.count
        if k < max(2, (n + 1) / 2) { return false }
        let med = median(kd)
        var ad: [Double] = []
        var maxd = 0.0
        for v in kd { let d = abs(v - med); ad.append(d); maxd = max(maxd, d) }
        let sigma = 1.4826 * median(ad)
        let tol = min(P.gateTolMaxMs, P.gateWinDevMs + P.gateTolStepMs * Double(max(0, k - 3)))
        return maxd <= tol && sigma <= P.gateSigmaMs
    }

    private static func decide(_ R: AudioResult, _ P: Params) {
        let agreeFrac = R.blocksValid > 0 ? Double(R.blocksAgree) / Double(R.blocksValid) : 0
        var ok = R.z >= P.minZ && agreeFrac >= P.minAgreeFrac && R.nccMean >= P.minNcc && R.margin >= P.minMargin && R.f1 >= P.minF1
        if !R.scaleKnown && (!P.allowFreeScale || R.z < P.minZFree || R.nccMean < P.minNccFree) { ok = false }
        // deriva provada pelas janelas: a evidência é a delas (o z e a concordância dos BLOCOS foram medidos contra o modelo grosso e não valem aqui)
        if R.driftScale { ok = R.nccMean >= P.minNcc && R.f1 >= P.minF1 }
        // família 1,001: só com a prova de deriva (driftCheck); um valor assim sem prova é ruído de ajuste
        let nearUnit = !R.driftScale && abs(R.scale - 1.0) > 1e-9 && abs(R.scale - 1.0) < P.nearUnitEps
        if nearUnit { ok = false }
        let gateDev = P.gateWinDevMs > 0 && R.winsValid >= 2
            && (P.legacy ? R.winMaxDev > P.gateWinDevMs : R.driftScale ? R.winMaxDev > P.driftGateDevMs : !R.winConsensus)
        let gateWin = P.gateMinWinsAgree > 0 && R.winsAgree < P.gateMinWinsAgree
        if gateDev || gateWin { ok = false }
        R.accepted = ok
        let c1: Double = min(1.0, max(0.0, (R.z - 3.0) / 12.0)) * 0.4
        let c2: Double = min(1.0, agreeFrac) * 0.3
        let c3: Double = min(1.0, R.nccMean / 0.4) * 0.2
        let c4: Double = min(1.0, R.margin) * 0.1
        R.confidence = c1 + c2 + c3 + c4
        if !ok {
            var sb = ""
            if R.z < P.minZ { sb += "z baixo; " }
            if agreeFrac < P.minAgreeFrac { sb += "poucos blocos concordam; " }
            if R.nccMean < P.minNcc { sb += "correlação fraca; " }
            if R.margin < P.minMargin { sb += "pico ambíguo; " }
            if R.f1 < P.minF1 { sb += "cobertura baixa; " }
            if !R.scaleKnown && (R.z < P.minZFree || R.nccMean < P.minNccFree) { sb += "escala fora das razões de fps; " }
            if nearUnit { sb += "escala 0,999/1,001 sem prova de deriva; " }
            if R.driftSuspect { sb += "possível deriva: janelas discordam entre si; " }
            if gateDev || gateWin { sb += "janelas discordam; " }
            R.reason = sb
        }
    }

    // ------------------------------------------------------------------ cortes (por partes)
    /** ordena índices por chave, desempatando pelo índice (o sort do Java é estável) */
    private static func orderBy(_ n: Int, _ key: (Int) -> Int) -> [Int] {
        var ord = Array(0..<n)
        ord.sort { (x, y) in
            let kx = key(x), ky = key(y)
            return kx != ky ? kx < ky : x < y
        }
        return ord
    }

    /**
     * Ajuste por partes. Parte do modelo global aceito (escala comum, deslocamento por trecho): rotula cada bloco de 30 s com o modelo que o explica,
     * procura novos deslocamentos entre os blocos que ninguém explica (mesma escala) e coloca cada corte no maior intervalo sem falas.
     * Aqui só serve de AVISO (o app não aplica ajuste por partes).
     */
    private static func analyzeCuts(_ R: AudioResult, _ cues: [AudioCue], _ ws: [AudioWindow], _ ps: [[Float]], _ bl: [Block], _ use: [Int], _ nu: Int,
                                    _ P: Params, _ durMs: Int, _ fine: [Float]) {
        if !R.accepted && R.z < P.minZ { return }
        let a0 = R.scale, alpha = alphaOf(a0)
        var offs = [Double](repeating: 0, count: max(P.maxPieces, 1))
        var nm = 1
        offs[0] = R.offsetMs
        var lab = [Int](repeating: -1, count: nu)
        relabel(bl, use, nu, alpha, offs, nm, a0, P, &lab)
        var rnd = JavaRandom(P.seed + 7)
        while nm < P.maxPieces {
            var no = 0
            for t in 0..<nu where lab[t] < 0 { no += 1 }
            if no < 2 { break }
            var oi = [Int](repeating: 0, count: no)
            var q = 0
            for t in 0..<nu where lab[t] < 0 { oi[q] = t; q += 1 }
            var bestS = -1.0, bestBeta = 0.0
            for k in 0..<no {
                let A = bl[use[oi[k]]]
                for pk in 0..<A.peakIdx.count {
                    let L = Double(A.peakIdx[pk] - P.lagFrames) * Double(P.frameMs)
                    let beta = L - alpha * Double(A.centerMs)
                    if abs(beta * a0) > P.maxOffsetMs * 1.2 { continue }
                    var sc = 0.0
                    for j in 0..<no {
                        let B = bl[use[oi[j]]]
                        sc += Double(support(B, alpha * Double(B.centerMs) + beta, P))
                    }
                    if sc > bestS { bestS = sc; bestBeta = beta }
                }
            }
            if bestS <= 0 { break }
            var nfit = 0
            for j in 0..<no {
                let B = bl[use[oi[j]]]
                if Double(support(B, alpha * Double(B.centerMs) + bestBeta, P)) >= P.pieceFitNcc { nfit += 1 }
            }
            if nfit < 2 { break }
            var s1 = 0.0, s2 = 0.0
            let nr = 300
            for _ in 0..<nr {
                let beta = (rnd.nextDouble() * 2 - 1) * P.maxOffsetMs / a0
                var sc = 0.0
                for j in 0..<no {
                    let B = bl[use[oi[j]]]
                    sc += Double(support(B, alpha * Double(B.centerMs) + beta, P))
                }
                s1 += sc; s2 += sc * sc
            }
            let mu = s1 / Double(nr), sd = max(1e-9, s2 / Double(nr) - mu * mu).squareRoot()
            if (bestS - mu) / sd < P.pieceMinZ { break }
            let off2 = bestBeta * a0
            var dup = false
            for m in 0..<nm where abs(offs[m] - off2) < 600 { dup = true }
            if dup { break }
            // refino fino com as janelas inteiramente explicadas pelo novo modelo
            offs[nm] = off2
            nm += 1
            relabel(bl, use, nu, alpha, offs, nm, a0, P, &lab)
            for _ in 0..<2 {
                var sw = 0.0, sd2 = 0.0
                for w in 0..<ws.count {
                    var all = false, mixed = false
                    for t in 0..<nu {
                        if bl[use[t]].win != w { continue }
                        if lab[t] == nm - 1 { all = true } else { mixed = true }
                    }
                    if !all || mixed { continue }
                    let f = fineWindow(ps[w], ws[w].startMs, fine, a0, offs[nm - 1], 150)
                    if f.ncc < P.minNcc { continue }
                    sw += f.ncc; sd2 += f.ncc * f.bestDelta
                }
                if sw <= 0 { break }
                offs[nm - 1] += sd2 / sw
            }
            relabel(bl, use, nu, alpha, offs, nm, a0, P, &lab)
        }
        var no = 0
        for t in 0..<nu where lab[t] < 0 { no += 1 }
        if nm == 1 {
            R.pieceSuspect = no >= 1
            // um único bloco forte sem explicação: pede uma janela colada nele para confirmar um possível 2º trecho
            if no == 1 && R.accepted && ws.count < P.maxWindows {
                for t in 0..<nu {
                    if lab[t] >= 0 { continue }
                    let b = bl[use[t]]
                    if b.peakVal.isEmpty || b.peakVal[0] < 0.25 { continue }
                    var st = ws[b.win].startMs + ws[b.win].durationMs + 5_000
                    if st + P.bisectLenMs > durMs { st = max(0, ws[b.win].startMs - 5_000 - P.bisectLenMs) }
                    var covered = false
                    for w in ws where st < w.startMs + w.durationMs && st + P.bisectLenMs > w.startMs { covered = true }
                    if !covered { R.more = [[st / 10 * 10, P.bisectLenMs]] }
                }
            }
            return
        }

        // blocos rotulados em ordem de tempo
        var cnt = 0
        for t in 0..<nu where lab[t] >= 0 { cnt += 1 }
        var bStart = [Int](repeating: 0, count: cnt), bEnd = [Int](repeating: 0, count: cnt), bLab = [Int](repeating: 0, count: cnt)
        let ord = orderBy(nu) { bl[use[$0]].uMs }
        var q = 0
        for k in 0..<nu {
            let t = ord[k]
            if lab[t] < 0 { continue }
            let b = bl[use[t]]
            bStart[q] = b.uMs; bEnd[q] = b.uMs + b.n * P.frameMs; bLab[q] = lab[t]; q += 1
        }
        if cnt == 0 { R.pieceSuspect = true; return }
        var segs: [AudioSegment] = []
        var more: [[Int]] = []
        segs.append(AudioSegment(fromSubMs: Int.min / 4, scale: a0, offsetMs: offs[bLab[0]]))
        var lastCut = Int.min / 4
        var bad = false
        var k = 1
        while k < cnt {
            defer { k += 1 }
            if bLab[k] == bLab[k - 1] { continue }
            let tL = bEnd[k - 1], tR = bStart[k]
            let oL = offs[bLab[k - 1]], oR = offs[bLab[k]]
            var lo = (Double(tL) - oL) / a0, hi = (Double(tR) - oR) / a0
            if hi < lo { let tmp = lo; lo = hi; hi = tmp }
            let cut = pickCut(cues, lo, hi)
            if cut <= lastCut { bad = true; break }
            lastCut = cut
            segs.append(AudioSegment(fromSubMs: cut, scale: a0, offsetMs: oR))
            let span = tR - tL
            if span > P.cutResolutionMs && span > P.bisectLenMs * 8 / 5 {
                let mid = (tL + tR) / 2
                // meio do intervalo; se já há janela ali, tenta posições vizinhas dentro do intervalo
                var pick = -1
                var kk = 0
                while kk < 8 && pick < 0 {
                    defer { kk += 1 }
                    let d = ((kk + 1) / 2) * (P.bisectLenMs + 5_000) * ((kk & 1) == 1 ? 1 : -1)
                    let c = mid + d
                    let st = c - P.bisectLenMs / 2
                    if st < tL || st + P.bisectLenMs > tR || st < 0 || st + P.bisectLenMs > durMs { continue }
                    var covered = false
                    for w in ws where st < w.startMs + w.durationMs && st + P.bisectLenMs > w.startMs { covered = true }
                    for m in more where st < m[0] + m[1] && st + P.bisectLenMs > m[0] { covered = true }
                    if !covered { pick = st / 10 * 10 }
                }
                if pick >= 0 { more.append([pick, P.bisectLenMs]) }
            }
        }
        if bad { R.pieceSuspect = true; return }
        if !R.accepted {
            // o pico "ambíguo" pode ser só o outro trecho: aceita se os trechos achados explicam a grande maioria dos blocos
            var per = [Int](repeating: 0, count: nm)
            for t in 0..<nu where lab[t] >= 0 { per[lab[t]] += 1 }
            var okp = Double(nu - no) >= P.pieceExplained * Double(nu)
            for m in 0..<nm where per[m] < 2 { okp = false }
            if !okp { return }
            R.accepted = true
            R.reason = ""
            R.confidence = min(R.confidence, 0.6)
        }
        R.segments = segs
        if !more.isEmpty { R.more = more }
    }

    /** rotula cada bloco com o modelo (mesma escala, deslocamento por trecho) que melhor o explica; -1 se nenhum */
    private static func relabel(_ bl: [Block], _ use: [Int], _ nu: Int, _ alpha: Double, _ offs: [Double], _ nm: Int, _ a0: Double,
                                _ P: Params, _ lab: inout [Int]) {
        for t in 0..<nu {
            let b = bl[use[t]]
            var best = -1
            var bv = Float(P.pieceFitNcc)
            if b.peakVal.count > 0 { bv = max(bv, Float(P.pieceRelPeak * Double(b.peakVal[0]))) }
            for m in 0..<nm {
                let v = support(b, alpha * Double(b.centerMs) + offs[m] / a0, P)
                if v >= bv { bv = v; best = m }
            }
            lab[t] = best
        }
    }

    /** corte (tempo da legenda) no maior intervalo entre falas dentro de [lo, hi]; no meio do intervalo se não houver falas */
    private static func pickCut(_ cues: [AudioCue], _ lo: Double, _ hi: Double) -> Int {
        let n = cues.count
        let ord = orderBy(n) { cues[$0].startMs }
        var bestGap = -1
        var best = jlong((lo + hi) / 2)
        var maxEnd = Int.min
        var k = 0
        while k + 1 < n {
            defer { k += 1 }
            let i = ord[k], j = ord[k + 1]
            if cues[i].endMs > maxEnd { maxEnd = cues[i].endMs }
            let gap = cues[j].startMs - maxEnd
            if gap <= 0 { continue }
            let mid = (maxEnd + cues[j].startMs) / 2
            if Double(mid) < lo - 1000 || Double(mid) > hi + 1000 { continue }
            if gap > bestGap { bestGap = gap; best = mid }
        }
        return best
    }

    /** recusada mas promissora: sugere janelas extras nos maiores vãos entre as já capturadas */
    private static func suggestMore(_ R: AudioResult, _ ws: [AudioWindow], _ P: Params, _ durMs: Int) {
        if R.z < P.moreMinZ || R.blocksAgree < 2 || ws.count >= P.maxWindows { return }
        let n = ws.count
        var w = [[Int]](repeating: [0, 0], count: n)
        var len = 0
        for i in 0..<n { w[i][0] = ws[i].startMs; w[i][1] = ws[i].startMs + ws[i].durationMs; len += ws[i].durationMs }
        len = max(20_000, min(60_000, len / n))
        w.sort { $0[0] < $1[0] }
        var gaps: [[Int]] = []
        var prev = 0
        for i in 0...n {
            let nxt = i < n ? w[i][0] : durMs
            gaps.append([nxt - prev, (prev + nxt) / 2])
            if i < n { prev = w[i][1] }
        }
        // decrescente pelo tamanho do vão; empates na ordem original (sort estável do Java)
        let order = Array(0..<gaps.count).sorted { (x, y) in gaps[x][0] != gaps[y][0] ? gaps[x][0] > gaps[y][0] : x < y }
        var more: [[Int]] = []
        for oi in order {
            if more.count >= 2 { break }
            if gaps[oi][0] < 2 * len { break }
            let st = max(0, min(gaps[oi][1] - len / 2, durMs - len)) / 10 * 10
            more.append([st, len])
        }
        if !more.isEmpty { R.more = more }
    }
}
