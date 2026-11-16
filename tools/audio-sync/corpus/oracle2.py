#!/usr/bin/env python3
"""Verdade v2 (menos viés): mediana, por fala, de VÁRIOS oráculos independentes sobre o filme já em cache.
Oráculos: ffsubsync (webrtc), ffsubsync (auditok), alass (arquivos já gerados em FILM_DIR/subs) e "sil": alinhamento global em Python sobre a trilha do
Silero (vad_silero.u8, filme inteiro, FFT a 10 ms, escalas de fps discretas + refinamento fino), que tem VAD e algoritmo diferentes dos três.
Só entram na mediana os oráculos que concordam com a verdade v1 (mediana da diferença <= 500 ms); precisa de >= 2.
Saída em FILM_DIR/subs: NOME.truth2.srt, NOME.pairs2.tsv e FILM_DIR/oracle2.json (viés de cada oráculo contra a verdade v2).
Uso: oracle2.py FILM_DIR"""
import json, os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus

SCALES = [1.0, 25 / 23.976, 23.976 / 25, 25 / 24.0, 24 / 25.0, 24 / 23.976, 23.976 / 24, 30 / 29.97, 29.97 / 30, 30 / 25.0, 25 / 30.0, 30 / 24.0, 24 / 30.0]


def mask_for(cues, k, n, off=0):
    m = np.zeros(n, dtype=np.float32)
    for a, b, t in cues:
        dur = b - a
        sp = min(dur, max(700, 500 + 62 * len(t.replace("\n", " "))))
        i0 = int((k * a + off) / 10); i1 = int((k * (a + sp) + off) / 10)
        if i1 < 0 or i0 >= n: continue
        m[max(0, i0):min(n, i1 + 1)] = 1
    return m


def sil_oracle(cues, tr):
    """-> (k, offset_ms, score) alinhamento global pela trilha tr (float 0..1, 100 Hz)"""
    N = len(tr)
    best = None
    x = tr - tr.mean()
    nf = 1 << int(np.ceil(np.log2(N + 120000 + 10)))
    X = np.fft.rfft(x, nf)
    for k in SCALES:
        m = mask_for(cues, k, N + 70000)    # máscara em tempo "k*legenda" (offset 0); lags em [-60000, +60000] quadros (±600 s)
        mm = m - m.mean()
        M = np.fft.rfft(mm, nf)
        # corr[l] = sum_t x[t] * mm[t - l]  (a máscara deslocada l quadros para a direita)
        c = np.fft.irfft(X * np.conj(M), nf)
        lags = np.concatenate([np.arange(0, 60001), np.arange(-60000, 0)])
        idx = np.concatenate([np.arange(0, 60001), nf + np.arange(-60000, 0)])
        v = c[idx]
        i = int(np.argmax(v))
        sc = float(v[i]) / (np.sqrt((x * x).sum() * (mm * mm).sum()) + 1e-9)
        if best is None or sc > best[2]:
            best = (k, int(lags[i]) * 10, sc)
    k, off, sc = best
    # refino fino ±300 ms (passo 10 ms) por produto escalar direto
    bo, bs = off, -1e9
    for d in range(-30, 31):
        m = mask_for(cues, k, N, off + d * 10)
        s = float((x[:N] * (m - m.mean())).sum())
        if s > bs: bs, bo = s, off + d * 10
    return k, bo, sc


def main():
    fdir = sys.argv[1]
    odir = os.path.join(fdir, "subs")
    oracle = json.load(open(os.path.join(fdir, "oracle.json")))
    trp = os.path.join(fdir, "vad_silero.u8")
    tr = (np.fromfile(trp, np.uint8).astype(np.float32) / 255.0) if os.path.exists(trp) else None
    rep = {}
    for name, o in oracle.items():
        if not o.get("truth"): continue
        src = os.path.join(odir, name + ".srt")
        c_in = corpus.parse_srt(open(src, encoding="utf-8").read())
        v1 = {a: b for a, b in corpus.cue_map(c_in, corpus.parse_srt(open(os.path.join(odir, name + ".truth.srt"), encoding="utf-8").read()))}
        outs = {}
        for key, suf in (("ffs", ".ffs.srt"), ("ffsa", ".ffsa.srt"), ("alass", ".alass.srt")):
            p = os.path.join(odir, name + suf)
            if os.path.exists(p):
                outs[key] = {a: b for a, b in corpus.cue_map(c_in, corpus.parse_srt(open(p, encoding="utf-8").read()))}
        info = {}
        if tr is not None:
            try:
                k, off, sc = sil_oracle(c_in, tr)
                outs["sil"] = {a: int(round(k * a + off)) for a, b, t in c_in}
                info["sil"] = {"k": k, "off": off, "score": sc}
            except Exception as e:
                info["sil_err"] = str(e)
        # só os que concordam com a verdade v1
        use = {}
        for key, m in outs.items():
            d = sorted(abs(m[a] - v1[a]) for a in m if a in v1)
            if len(d) >= 10 and d[len(d) // 2] <= 500:
                use[key] = m
            info.setdefault("agree_v1", {})[key] = d[len(d) // 2] if len(d) >= 10 else None
        if len(use) < 2:
            rep[name] = dict(info, used=list(use), ok=False); continue
        t2 = {}
        for a, b, t in c_in:
            vals = [m[a] for m in use.values() if a in m]
            if len(vals) >= 2: t2[a] = int(np.median(vals))
        cues2 = [[t2[a], t2[a] + (b - a), t] for a, b, t in c_in if a in t2]
        corpus.write_srt(cues2, os.path.join(odir, name + ".truth2.srt"))
        with open(os.path.join(odir, name + ".pairs2.tsv"), "w") as pf:
            for a, b, t in c_in:
                if a in t2: pf.write("%d\t%d\n" % (a, t2[a]))
        bias = {}
        for key, m in outs.items():
            d = [m[a] - t2[a] for a in t2 if a in m]
            if d: bias[key] = {"med": float(np.median(d)), "p90abs": float(np.percentile(np.abs(d), 90))}
        d1 = [v1[a] - t2[a] for a in t2 if a in v1]
        rep[name] = dict(info, used=list(use), ok=True, n=len(t2), bias=bias, v1_minus_v2_med=float(np.median(d1)) if d1 else None, v1_minus_v2_p90abs=float(np.percentile(np.abs(d1), 90)) if d1 else None)
        print(name, json.dumps({k: rep[name][k] for k in ("used", "n", "v1_minus_v2_med")}), flush=True)
    json.dump(rep, open(os.path.join(fdir, "oracle2.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
