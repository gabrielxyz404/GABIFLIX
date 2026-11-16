#!/usr/bin/env python3
"""Mede métodos de captura de áudio do VLC (linha de comando = mesmo núcleo do libVLC) em janelas do vídeo servido por HTTP.
Uso: matrix.py MEDIA_DIR OUT_DIR stage(1|2)
Mede por captura: tempo de parede, CPU (usuário+sistema), bytes lidos da rede, duração do PCM, erro de início (correlação com o PCM de referência do ffmpeg)."""
import json, os, re, subprocess, sys, time, shutil, resource
import numpy as np

MEDIA, OUT, STAGE = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
URL = "http://127.0.0.1:8000/%s.mkv"
BASE = ["cvlc", "-I", "dummy", "--no-video", "--no-spu", "--no-sub-autodetect-file", "--no-lua", "--no-osd", "--http-reconnect", "-q"]


def server_bytes(name):
    import urllib.request
    try:
        txt = urllib.request.urlopen("http://127.0.0.1:8000/__bytes", timeout=5).read().decode()
        for ln in txt.splitlines():
            n, b = ln.split()
            if n == name + ".mkv":
                return int(b)
    except Exception:
        pass
    return 0


def read_wav(path):
    """-> (rate, mono float32) via ffmpeg para 16 kHz mono"""
    tmp = path + ".16k.raw"
    r = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", path, "-ac", "1", "-ar", "16000", "-f", "s16le", tmp], capture_output=True)
    if r.returncode != 0 or not os.path.exists(tmp):
        return None
    x = np.fromfile(tmp, dtype="<i2").astype(np.float32) / 32768
    os.remove(tmp)
    return x


def envelope(x, hop=160):
    n = len(x) // hop
    return np.log10(np.sqrt((x[:n * hop].reshape(n, hop) ** 2).mean(1)) + 1e-5)


def start_error(cap, ref, searchs=6.0):
    """atraso (ms) do PCM capturado em relação à referência (positivo = capturado começa mais tarde no conteúdo) e correlação"""
    if cap is None or len(cap) < 16000 * 3:
        return None, None
    a = envelope(cap); b = envelope(ref)
    a = a - a.mean(); b = b - b.mean()
    L = int(searchs * 100)
    best, bl = -2, 0
    n = min(len(a), len(b)) - L - 1
    if n < 100:
        return None, None
    seg = a[L:L + n]
    for lag in range(-L, L + 1):
        s = b[L + lag:L + lag + n]
        c = float(np.dot(seg, s) / (np.linalg.norm(seg) * np.linalg.norm(s) + 1e-9))
        if c > best:
            best, bl = c, lag
    return -bl * 10.0, best


def run_variant(name, codec, start, dur, extra_opts, sout=None, rate=None):
    out = os.path.join(OUT, "cap_%s_%s_%d.wav" % (name, codec, start))
    if os.path.exists(out):
        os.remove(out)
    opts = list(BASE) + ["--start-time=%d" % start, "--stop-time=%d" % (start + dur)] + extra_opts
    if sout:
        opts += ["--sout", sout.replace("@OUT@", out)]
    else:
        opts += ["--aout=afile", "--audiofile-file=" + out, "--audiofile-wav", "--audiofile-channels=1"]
    if rate:
        opts += ["--rate=%s" % rate]
    cmd = opts + [URL % codec, "vlc://quit"]
    b0 = server_bytes(codec)
    t0 = time.time()
    r0 = resource.getrusage(resource.RUSAGE_CHILDREN)
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
        to = False
    except subprocess.TimeoutExpired:
        to = True
    wall = time.time() - t0
    r1 = resource.getrusage(resource.RUSAGE_CHILDREN)
    cpu = (r1.ru_utime - r0.ru_utime) + (r1.ru_stime - r0.ru_stime)
    b1 = server_bytes(codec)
    ref_wav = os.path.join(OUT, "ref_%s_%d.wav" % (codec, start))
    if not os.path.exists(ref_wav):
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", str(start), "-t", str(dur), "-i", os.path.join(MEDIA, codec + ".mkv"), "-vn", "-ac", "1", "-ar", "16000", ref_wav])
    cap = read_wav(out) if os.path.exists(out) else None
    ref = read_wav(ref_wav)
    capdur = len(cap) / 16000 if cap is not None else 0
    # com --rate!=1 o conteúdo vem comprimido no tempo; só informa a duração
    err, corr = (None, None)
    if cap is not None and not rate:
        err, corr = start_error(cap, ref)
    res = {"method": name, "codec": codec, "start_s": start, "win_s": dur, "wall_s": round(wall, 1), "cpu_s": round(cpu, 1), "net_MB": round((b1 - b0) / 1e6, 1),
           "pcm_s": round(capdur, 2), "start_err_ms": err, "corr": None if corr is None else round(corr, 3), "timeout": to,
           "xRT": round(dur / wall, 2) if wall > 0 else 0}
    print(json.dumps(res), flush=True)
    return res


S1 = [
    ("afile", [], None, None),
    ("afile+fastseek", ["--input-fast-seek"], None, None),
    ("afile+nosync", ["--clock-synchro=0"], None, None),
    ("afile+nosync+jitter0", ["--clock-synchro=0", "--clock-jitter=0"], None, None),
    ("afile+nosync+fast+jit0", ["--clock-synchro=0", "--clock-jitter=0", "--input-fast-seek"], None, None),
    ("afile+rate2", ["--no-audio-time-stretch"], None, "2"),
    ("afile+rate2stretch", [], None, "2"),
    ("afile+rate4", ["--no-audio-time-stretch"], None, "4"),
    ("afile+rate8", ["--no-audio-time-stretch"], None, "8"),
    ("sout-wav", [], "#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=@OUT@}", None),
    ("sout-wav+fastseek", ["--input-fast-seek"], "#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=@OUT@}", None),
    ("sout-wav+nosync", ["--clock-synchro=0"], "#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=@OUT@}", None),
    ("sout-wav-noaudsync", ["--no-sout-all", "--sout-keep"], "#transcode{acodec=s16l,channels=1,samplerate=16000}:std{access=file,mux=wav,dst=@OUT@}", None),
]

if STAGE == "1":
    res = []
    for name, opts, sout, rate in S1:
        for start in (300, 900):
            res.append(run_variant(name, "ac3", start, 30, opts, sout, rate))
    json.dump(res, open(os.path.join(OUT, "stage1.json"), "w"), indent=1)
else:
    best = json.load(open(os.path.join(OUT, "best.json")))  # lista de variantes escolhidas {name,opts,sout,rate}
    res = []
    for codec in ["ac3", "eac3", "aac", "dts", "truehd", "flac"]:
        if not os.path.exists(os.path.join(MEDIA, codec + ".mkv")):
            continue
        for v in best:
            for start in (60, 600, 1200):
                res.append(run_variant(v["name"], codec, start, 60, v["opts"], v.get("sout"), v.get("rate")))
    json.dump(res, open(os.path.join(OUT, "stage2.json"), "w"), indent=1)
