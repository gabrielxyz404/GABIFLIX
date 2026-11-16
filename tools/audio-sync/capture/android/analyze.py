#!/usr/bin/env python3
"""Confere os WAVs capturados no Android contra a referência do ffmpeg: erro de início (ms) e correlação. Uso: analyze.py DIR_WAVS DIR_MEDIA OUT.json"""
import json, os, re, subprocess, sys
import numpy as np

def pcm_wav(path):
    b = open(path, "rb").read()
    i = b.find(b"data")
    return np.frombuffer(b[i + 8:], dtype="<i2").astype(np.float32) if i >= 0 else np.zeros(0, np.float32)

def ffpcm(args):
    r = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error"] + args + ["-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-"], capture_output=True)
    return np.frombuffer(r.stdout, dtype="<i2").astype(np.float32)

def env(x):
    n = len(x) // 160
    return np.log1p(np.sqrt((x[: n * 160].reshape(n, 160) ** 2).mean(1)))

def start_error(cap, ref, searchs=6.0):
    a = env(cap); b = env(ref)
    a = a - a.mean(); b = b - b.mean()
    L = int(searchs * 100); best, bd = -2, 0
    for d in range(-L, L + 1):
        i0 = max(0, -d); i1 = min(len(a), len(b) - d)
        if i1 - i0 < 200: continue
        x = a[i0:i1]; y = b[i0 + d:i1 + d]
        c = float(np.dot(x, y) / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-9))
        if c > best: best, bd = c, d
    return bd * 10.0, best

wd, md, out = sys.argv[1:4]
res = []
for f in sorted(os.listdir(wd)):
    m = re.match(r"(.+)_([a-z0-9]+)_(\d+)\.wav$", f)
    if not m or f.startswith("conc_"): continue
    variant, codec, start = m.group(1), m.group(2), int(m.group(3))
    cap = pcm_wav(os.path.join(wd, f))
    win = len(cap) / 16000.0
    if variant == "extractor" or len(cap) == 0:
        res.append({"file": f, "pcm_s": round(win, 2), "note": "sem WAV 16k (extractor devolve PCM bruto)" if variant == "extractor" else "vazio"}); continue
    ref = ffpcm(["-ss", str(start), "-t", str(min(win, 60)), "-i", os.path.join(md, codec + ".mkv")])
    err, corr = start_error(cap[: 16000 * 60], ref)
    res.append({"file": f, "variant": variant, "codec": codec, "start_s": start, "pcm_s": round(win, 2), "start_err_ms": err, "corr": round(corr, 3)})
    print(json.dumps(res[-1]), flush=True)
json.dump(res, open(out, "w"), indent=1)
