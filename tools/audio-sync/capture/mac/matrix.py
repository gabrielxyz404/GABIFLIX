#!/usr/bin/env python3
"""macOS: roda capprobe (VLCKit) em variantes e janelas e mede o erro de início contra o PCM de referência do ffmpeg. Uso: matrix.py MEDIA_DIR OUT_DIR CAPPROBE stage"""
import json, os, subprocess, sys, time
import numpy as np

MEDIA, OUT, BIN, STAGE = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
os.makedirs(OUT, exist_ok=True)
URL = "http://127.0.0.1:8000/%s.mkv"


def server_bytes(name):
    import urllib.request
    try:
        for ln in urllib.request.urlopen("http://127.0.0.1:8000/__bytes", timeout=5).read().decode().splitlines():
            n, b = ln.split()
            if n == name + ".mkv":
                return int(b)
    except Exception:
        pass
    return 0


def to16k(path):
    tmp = path + ".raw"
    r = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", path, "-ac", "1", "-ar", "16000", "-f", "s16le", tmp], capture_output=True)
    if r.returncode != 0 or not os.path.exists(tmp):
        return None
    x = np.fromfile(tmp, dtype="<i2").astype(np.float32) / 32768
    os.remove(tmp)
    return x


def raw16k(path):
    return np.fromfile(path, dtype="<i2").astype(np.float32) / 32768


def envelope(x, hop=160):
    n = len(x) // hop
    return np.log10(np.sqrt((x[:n * hop].reshape(n, hop) ** 2).mean(1)) + 1e-5)


def start_error(cap, ref, searchs=6.0):
    """deslocamento (ms) do conteudo capturado em relacao a referencia: positivo = a captura comeca mais TARDE no filme; e a correlacao (0..1)"""
    if cap is None or len(cap) < 16000 * 3:
        return None, None
    a = envelope(cap); b = envelope(ref)
    a = a - a.mean(); b = b - b.mean()
    L = int(searchs * 100)
    best, bd = -2, 0
    for d in range(-L, L + 1):
        i0 = max(0, -d); i1 = min(len(a), len(b) - d)
        if i1 - i0 < 200:
            continue
        x = a[i0:i1]; y = b[i0 + d:i1 + d]
        c = float(np.dot(x, y) / (np.linalg.norm(x) * np.linalg.norm(y) + 1e-9))
        if c > best:
            best, bd = c, d
    return bd * 10.0, best

def run(name, codec, start, dur, method, extra):
    out = os.path.join(OUT, "cap_%s_%s_%d.%s" % (name, codec, start, "raw" if method == "cb" else "wav"))
    if os.path.exists(out):
        os.remove(out)
    b0 = server_bytes(codec)
    t0 = time.time()
    p = subprocess.run([BIN, URL % codec, str(start), str(dur), method, out] + extra, capture_output=True, text=True, timeout=400)
    wall = time.time() - t0
    b1 = server_bytes(codec)
    res = {"method": name, "codec": codec, "start_s": start, "win_s": dur, "wall_proc_s": round(wall, 1), "net_MB": round((b1 - b0) / 1e6, 1)}
    for ln in p.stderr.splitlines():
        if ln.startswith("RESULT "):
            res.update(json.loads(ln[7:]))
    ref = os.path.join(OUT, "ref_%s_%d.wav" % (codec, start))
    if not os.path.exists(ref):
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", str(start), "-t", str(dur), "-i", os.path.join(MEDIA, codec + ".mkv"), "-vn", "-ac", "1", "-ar", "16000", ref])
    cap = None
    if os.path.exists(out):
        cap = raw16k(out) if method == "cb" else to16k(out)
    r = to16k(ref)
    res["pcm_s"] = round(len(cap) / 16000, 2) if cap is not None else 0
    err, corr = start_error(cap, r) if cap is not None else (None, None)
    res["start_err_ms"] = err
    res["corr"] = None if corr is None else round(corr, 3)
    res["xRT"] = round(dur / res["wall_proc_s"], 2)
    print(json.dumps(res), flush=True)
    if not p.stderr.count("RESULT"):
        print("STDERR:", p.stderr[-800:], flush=True)
    return res


S1 = [
    ("cb", "cb", []),
    ("cb+nosync", "cb", ["--clock-synchro=0"]),
    ("cb+nosync+jit0", "cb", ["--clock-synchro=0", "--clock-jitter=0"]),
    ("cb+fastseek", "cb", [":input-fast-seek"]),
    ("cb+nosync+fast", "cb", ["--clock-synchro=0", "--clock-jitter=0", ":input-fast-seek"]),
    ("cb+rate2", "cb", [":rate=2"]),
    ("afile", "afile", []),
    ("afile+nosync+fast", "afile", ["--clock-synchro=0", "--clock-jitter=0", ":input-fast-seek"]),
]
if STAGE == "1":
    res = []
    for name, method, extra in S1:
        for start in (300, 900):
            res.append(run(name, "ac3", start, 30, method, extra))
    json.dump(res, open(os.path.join(OUT, "stage1.json"), "w"), indent=1)
else:
    best = json.load(open(os.path.join(OUT, "best.json")))
    res = []
    for codec in ["ac3", "eac3", "aac", "dts", "truehd", "flac"]:
        if not os.path.exists(os.path.join(MEDIA, codec + ".mkv")):
            continue
        for v in best:
            for start in (60, 600, 1200):
                res.append(run(v["name"], codec, start, 60, v["method"], v["extra"]))
    json.dump(res, open(os.path.join(OUT, "stage2.json"), "w"), indent=1)
