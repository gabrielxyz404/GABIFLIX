#!/usr/bin/env python3
"""Corpus de avaliação do motor de sincronia por áudio (roda só nos runners do GitHub Actions).

Uso:
  corpus.py subs  OUT_DIR                 baixa as legendas (addon OpenSubtitles do Stremio) de todos os filmes
  corpus.py film  SLUG SUBS_DIR FILM_DIR  baixa o filme (archive.org / Blender), extrai áudio 16 kHz mono,
                                          roda os oráculos (ffsubsync, alass), trilhas de VAD de referência (webrtcvad, Silero)

Nada disto é versionado: filmes e legendas ficam só no runner (cache).
"""
import json, os, re, subprocess, sys, time, urllib.parse, urllib.request, urllib.error, shutil, struct, math

HERE = os.path.dirname(os.path.abspath(__file__))
FILMS = json.load(open(os.path.join(HERE, "films.json"), encoding="utf-8"))
UA = {"User-Agent": "FramePlayAudioEval/1.0"}


def sh(cmd, check=True, timeout=None, quiet=False):
    if not quiet:
        print("+", cmd if isinstance(cmd, str) else " ".join(cmd), flush=True)
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:], flush=True)
        raise RuntimeError("falhou: %s" % (cmd if isinstance(cmd, str) else cmd[0]))
    return r


def http_get(url, tries=3, timeout=60, binary=True):
    last = None
    for a in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                b = r.read()
                return b if binary else b.decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                return None
        except Exception as e:
            last = e
        time.sleep(1.5 * (a + 1))
    print("  http falhou", url, last, flush=True)
    return None


# ---------------------------------------------------------------- SRT
TS = re.compile(r"(\d+):(\d\d):(\d\d)[,.](\d{1,3})\s*-->\s*(\d+):(\d\d):(\d\d)[,.](\d{1,3})")


def decode_text(b):
    if b[:3] == b"\xef\xbb\xbf":
        b = b[3:]
    for enc in ("utf-8", "cp1252", "latin-1"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            pass
    return b.decode("latin-1", "replace")


def parse_srt(text):
    """-> lista de [start_ms, end_ms, texto]"""
    cues = []
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    for blk in re.split(r"\n\s*\n", text):
        lines = [l for l in blk.split("\n")]
        for i, l in enumerate(lines[:3]):
            m = TS.search(l)
            if m:
                g = m.groups()
                def ms(h, mi, s, f):
                    return ((int(h) * 60 + int(mi)) * 60 + int(s)) * 1000 + int(f.ljust(3, "0")[:3])
                s0, s1 = ms(*g[:4]), ms(*g[4:])
                body = "\n".join(lines[i + 1:]).strip()
                if body:
                    cues.append([s0, s1, body])
                break
    return cues


def fmt_ms(v):
    v = max(0, int(round(v)))
    return "%02d:%02d:%02d,%03d" % (v // 3600000, v // 60000 % 60, v // 1000 % 60, v % 1000)


def write_srt(cues, path):
    with open(path, "w", encoding="utf-8") as f:
        for i, (a, b, t) in enumerate(cues, 1):
            f.write("%d\n%s --> %s\n%s\n\n" % (i, fmt_ms(a), fmt_ms(b), t))


def is_pt(l):
    return l in ("pob", "por", "pt", "pt-br", "ptb", "ptbr", "pt-pt")


def is_en(l):
    return l in ("eng", "en")


# ---------------------------------------------------------------- legendas
def resolve_imdb(f):
    q = urllib.parse.quote(f["title"])
    raw = http_get("https://v3-cinemeta.strem.io/catalog/movie/top/search=%s.json" % q, binary=False)
    try:
        metas = json.loads(raw).get("metas", [])
    except Exception:
        metas = []
    req = f.get("tok") or [t for t in norm_tokens(f["title"]) if t not in STOP]
    for m in metas:
        name = norm_tokens(m.get("name", ""))
        yr = str(m.get("releaseInfo", ""))[:4]
        if yr.isdigit() and abs(int(yr) - f["year"]) <= 1 and all(r in name or any(r in n for n in name) for r in req):
            return m["id"]
    return f["imdb"]


def cmd_subs(out, ptmax=12, enmax=6):
    os.makedirs(out, exist_ok=True)
    for f in FILMS:
        d = os.path.join(out, f["slug"])
        os.makedirs(d, exist_ok=True)
        imdb = resolve_imdb(f)
        if imdb != f["imdb"]:
            print("  imdb %s -> %s (cinemeta)" % (f["imdb"], imdb), flush=True)
        f["imdb"] = imdb
        meta = {"slug": f["slug"], "imdb": imdb, "subs": []}
        raw = http_get("https://opensubtitles-v3.strem.io/subtitles/movie/%s.json" % imdb, binary=False)
        items = []
        if raw:
            try:
                items = json.loads(raw).get("subtitles", [])
            except Exception as e:
                print("lista inválida", f["slug"], e)
        npt = nen = 0
        for it in items:
            lang = str(it.get("lang", "")).lower()
            url = it.get("url", "")
            if not url:
                continue
            kind = None
            if is_pt(lang) and npt < ptmax:
                kind = "pt-%d" % npt
            elif is_en(lang) and nen < enmax:
                kind = "en-%d" % nen
            if not kind:
                continue
            b = http_get(url)
            if not b or len(b) < 200:
                continue
            cues = parse_srt(decode_text(b))
            if len(cues) < 40:
                print("  ignorada (poucas falas)", f["slug"], kind, len(cues))
                continue
            write_srt(cues, os.path.join(d, kind + ".srt"))
            if kind.startswith("pt"):
                npt += 1
            else:
                nen += 1
            meta["subs"].append({"name": kind, "lang": lang, "cues": len(cues), "id": it.get("id", ""), "m": it.get("m", ""), "g": it.get("g", "")})
        b = f.get("blender")
        if b and b.get("sub_en"):
            urls = [b["sub_en"]] + (["https://durian.blender.org/wp-content/content/subtitles/sintel_en.srt"] if f["slug"] == "sintel" else [])
            for u in urls:
                bb = http_get(u)
                if bb:
                    cues = parse_srt(decode_text(bb))
                    if len(cues) > 20:
                        write_srt(cues, os.path.join(d, "en-official.srt"))
                        meta["subs"].append({"name": "en-official", "lang": "eng", "cues": len(cues), "id": "official", "m": "", "g": ""})
                        break
        json.dump(meta, open(os.path.join(d, "meta.json"), "w"), indent=1)
        print("%s %s: itens=%d PT=%d EN=%d total=%d" % (f["slug"], f["imdb"], len(items), npt, nen, len(meta["subs"])), flush=True)


# ---------------------------------------------------------------- filme
def ia_search(q):
    url = "https://archive.org/advancedsearch.php?" + urllib.parse.urlencode(
        [("q", "(%s) AND mediatype:movies" % q), ("fl[]", "identifier"), ("fl[]", "title"), ("fl[]", "downloads"),
         ("rows", "12"), ("sort[]", "downloads desc"), ("output", "json")])
    raw = http_get(url, binary=False)
    try:
        return [d["identifier"] for d in json.loads(raw)["response"]["docs"]]
    except Exception:
        return []


STOP = {"the", "a", "an", "of", "from", "and", "in", "on", "to", "it", "its", "s"}


def norm_tokens(s):
    return [t for t in re.split(r"[^a-z0-9]+", s.lower().replace("'", "")) if t]


def title_ok(f, md):
    req = f.get("tok") or [t for t in norm_tokens(f["title"]) if t not in STOP]
    meta = md.get("metadata", {})
    t = meta.get("title", "")
    if isinstance(t, list):
        t = " ".join(t)
    hay = set(norm_tokens(str(t) + " " + str(md.get("metadata", {}).get("identifier", ""))))
    if not all(r in hay or any(r in h for h in hay) for r in req):
        return False, str(t)
    return True, str(t)


def ia_pick(ident, expect_min, f=None):
    raw = http_get("https://archive.org/metadata/" + urllib.parse.quote(ident), binary=False)
    if not raw:
        return None
    try:
        md = json.loads(raw)
    except Exception:
        return None
    if f is not None:
        ok, t = title_ok(f, md)
        if not ok:
            print("  descartado %s (título '%s')" % (ident, t[:70]), flush=True)
            return None
    best = None
    for fl in md.get("files", []):
        name = fl.get("name", "")
        if not re.search(r"\.(mp4|m4v|ogv|mkv|avi|mpg|mpeg|webm|mov)$", name, re.I):
            continue
        size = int(fl.get("size", 0) or 0)
        if size < 40_000_000 or size > 2_500_000_000:
            continue
        try:
            length = float(fl.get("length", 0) or 0)
        except ValueError:
            length = 0
        if length:
            if not (expect_min * 60 * 0.85 <= length <= expect_min * 60 * 1.2):
                continue
        else:
            continue  # sem duração conhecida: não arrisca
        if best is None or size < best[1]:
            best = (name, size, length)
    return best


def download(url, dst):
    sh(["curl", "-fL", "-sS", "--retry", "6", "--retry-delay", "4", "--retry-all-errors", "-C", "-", "-o", dst, url], timeout=3600)


def acquire_video(f, wdir):
    log = []
    dst = os.path.join(wdir, "src_video")
    if f.get("blender"):
        for u in f["blender"]["video"]:
            try:
                download(u, dst)
                return dst, {"source": u}
            except Exception as e:
                log.append(str(e))
    cands = list(f.get("ia", []))
    for ident in ia_search(f["q"]):
        if ident not in cands:
            cands.append(ident)
    for ident in cands:
        pick = ia_pick(ident, f["min"], f)
        if not pick:
            continue
        name, size, length = pick
        url = "https://archive.org/download/%s/%s" % (urllib.parse.quote(ident), urllib.parse.quote(name))
        print("  escolhido %s / %s (%.0f MB, %.0f s)" % (ident, name, size / 1e6, length), flush=True)
        try:
            download(url, dst)
            return dst, {"source": url, "ia": ident, "file": name, "size": size, "length": length}
        except Exception as e:
            log.append(str(e))
    raise RuntimeError("sem filme para %s: %s" % (f["slug"], log[-1:]))


def probe_duration(path):
    r = sh(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], quiet=True)
    return float(r.stdout.strip())


# ---------------------------------------------------------------- oráculos
def cue_map(cues_in, cues_out):
    """casa cues entrada/saída pelo texto (n-ésima ocorrência); -> lista de (in_start, out_start)"""
    from collections import defaultdict
    occ = defaultdict(list)
    for a, b, t in cues_out:
        occ[t].append(a)
    used = defaultdict(int)
    pairs = []
    for a, b, t in cues_in:
        k = used[t]
        if k < len(occ[t]):
            pairs.append((a, occ[t][k]))
        used[t] += 1
    return pairs


def theil_sen(pairs, maxn=400):
    """ajuste robusto out = k*in + b -> (k, b, p90 do resíduo em ms)"""
    import random
    if len(pairs) < 10:
        return None
    rnd = random.Random(1)
    xs = [p[0] for p in pairs]
    ys = [p[1] for p in pairs]
    slopes = []
    n = len(pairs)
    for _ in range(4000):
        i, j = rnd.randrange(n), rnd.randrange(n)
        if abs(xs[i] - xs[j]) > 120000:
            slopes.append((ys[i] - ys[j]) / (xs[i] - xs[j]))
    if not slopes:
        return None
    slopes.sort()
    k = slopes[len(slopes) // 2]
    bs = sorted(ys[i] - k * xs[i] for i in range(n))
    b = bs[n // 2]
    res = sorted(abs(ys[i] - (k * xs[i] + b)) for i in range(n))
    return {"k": k, "off": b, "res_med": res[n // 2], "res_p90": res[int(n * 0.9)], "n": n}


def run_ffsubsync(wav, srt_in, srt_out, vad=None, gss=False):
    cmd = ["ffs", wav, "-i", srt_in, "-o", srt_out, "--max-offset-seconds", "600", "--encoding", "utf-8", "--output-encoding", "utf-8"]
    if vad:
        cmd += ["--vad", vad]
    if gss:
        cmd += ["--gss"]
    r = sh(cmd, check=False, timeout=1800, quiet=True)
    if r.returncode == 0 and os.path.exists(srt_out):
        return True, (r.stderr + r.stdout)[-600:]
    print("  ffs %s falhou: %s" % (vad or "", (r.stderr + r.stdout)[-300:].replace("\n", " | ")), flush=True)
    return False, ""


def run_alass(alass, wav, srt_in, srt_out):
    r = sh([alass, wav, srt_in, srt_out], check=False, timeout=1800, quiet=True)
    if r.returncode == 0 and os.path.exists(srt_out):
        return True, (r.stderr + r.stdout)[-400:]
    print("  alass falhou: %s" % (r.stderr + r.stdout)[-400:].replace("\n", " | "), flush=True)
    return False, ""


def agree(c_in, a_out, b_out):
    ma = {p[0]: p[1] for p in cue_map(c_in, a_out)}
    mb = {p[0]: p[1] for p in cue_map(c_in, b_out)}
    ds = sorted(abs(ma[k] - mb[k]) for k in ma if k in mb)
    if len(ds) < 10:
        return None
    return {"n": len(ds), "med": ds[len(ds) // 2], "p75": ds[int(len(ds) * 0.75)], "p90": ds[int(len(ds) * 0.9)], "p98": ds[int(len(ds) * 0.98)]}


# ---------------------------------------------------------------- trilhas de VAD de referência
def read_wav16(path):
    import numpy as np
    with open(path, "rb") as f:
        data = f.read()
    pos = 12
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        sz = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        if cid == b"data":
            n = min(sz, len(data) - pos - 8)
            return np.frombuffer(data, dtype="<i2", count=n // 2, offset=pos + 8)
        pos += 8 + sz + (sz & 1)
    raise RuntimeError("wav sem data")


def vad_tracks(wav, outdir):
    import numpy as np
    pcm = read_wav16(wav)
    n10 = len(pcm) // 160
    try:
        import webrtcvad
        for mode in (0, 1, 2, 3):
            v = webrtcvad.Vad(mode)
            tr = np.zeros(n10, dtype=np.uint8)
            b = pcm[:n10 * 160].tobytes()
            for i in range(n10):
                tr[i] = 255 if v.is_speech(b[i * 320:(i + 1) * 320], 16000) else 0
            tr.tofile(os.path.join(outdir, "vad_webrtc_m%d.u8" % mode))
        print("  webrtcvad ok", flush=True)
    except Exception as e:
        print("  webrtcvad falhou", e, flush=True)
    try:
        import onnxruntime as ort
        mp = os.environ.get("SILERO_ONNX", "")
        if not mp or not os.path.exists(mp):
            raise RuntimeError("SILERO_ONNX ausente")
        so = ort.SessionOptions()
        so.inter_op_num_threads = 1
        so.intra_op_num_threads = 1
        sess = ort.InferenceSession(mp, so, providers=["CPUExecutionProvider"])
        names = [i.name for i in sess.get_inputs()]
        x = pcm.astype(np.float32) / 32768.0
        nch = len(x) // 512
        state = np.zeros((2, 1, 128), dtype=np.float32)
        ctx = np.zeros(64, dtype=np.float32)
        sr = np.array(16000, dtype=np.int64)
        probs = np.zeros(nch, dtype=np.float32)
        t0 = time.time()
        for i in range(nch):
            chunk = x[i * 512:(i + 1) * 512]
            inp = np.concatenate([ctx, chunk])[None, :]
            out, state = sess.run(None, {names[0]: inp, names[1]: state, names[2]: sr})
            ctx = chunk[-64:]
            probs[i] = out[0][0]
        dt = time.time() - t0
        # 31,25 Hz -> 100 Hz por interpolação
        t = (np.arange(n10) * 0.01) / 0.032
        pr = np.interp(t, np.arange(nch) + 0.5, probs)
        (np.clip(pr, 0, 1) * 255).astype(np.uint8).tofile(os.path.join(outdir, "vad_silero.u8"))
        print("  silero ok: %.1f s de áudio em %.1f s (%.1fx tempo real, 1 thread)" % (len(x) / 16000, dt, len(x) / 16000 / max(dt, 1e-6)), flush=True)
        return {"silero_xrt": len(x) / 16000 / max(dt, 1e-6)}
    except Exception as e:
        print("  silero falhou", e, flush=True)
    return {}


# ---------------------------------------------------------------- filme
def cmd_film(slug, subs_dir, fdir):
    f = next(x for x in FILMS if x["slug"] == slug)
    os.makedirs(fdir, exist_ok=True)
    wdir = os.path.join(fdir, "_work")
    os.makedirs(wdir, exist_ok=True)
    wav = os.path.join(fdir, "audio16k.wav")
    meta = {"slug": slug, "imdb": f["imdb"], "title": f["title"]}
    if not os.path.exists(wav):
        src, info = acquire_video(f, wdir)
        meta.update(info)
        dur = probe_duration(src)
        meta["duration_s"] = dur
        print("  duração %.1f s (esperado %d min)" % (dur, f["min"]), flush=True)
        pr = sh(["ffprobe", "-v", "error", "-show_entries", "stream=index,codec_type,codec_name,channels,sample_rate:stream_tags=language", "-of", "csv=p=0", src], quiet=True)
        meta["streams"] = pr.stdout.strip().split("\n")
        sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", src, "-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-fflags", "+bitexact", "-flags:a", "+bitexact", wav])
        os.remove(src)
    else:
        if os.path.exists(os.path.join(fdir, "meta.json")):
            meta.update(json.load(open(os.path.join(fdir, "meta.json"))))
    meta["duration_s"] = probe_duration(wav)
    json.dump(meta, open(os.path.join(fdir, "meta.json"), "w"), indent=1)

    # VAD de referência (só se ainda não existe)
    if not os.path.exists(os.path.join(fdir, "vad_silero.u8")) or not os.path.exists(os.path.join(fdir, "vad_webrtc_m3.u8")):
        meta.update(vad_tracks(wav, fdir))

    # oráculos
    alass = os.path.join(HERE, "alass")
    if not os.path.exists(alass):
        try:
            download("https://github.com/kaegi/alass/releases/download/v2.0.0/alass-linux64", alass)
            os.chmod(alass, 0o755)
        except Exception as e:
            print("alass indisponível", e)
            alass = None
    sdir = os.path.join(subs_dir, slug)
    odir = os.path.join(fdir, "subs")
    os.makedirs(odir, exist_ok=True)
    oracle = {}
    sm = json.load(open(os.path.join(sdir, "meta.json")))
    have_silero_vad = [True]
    for s in sm["subs"]:
        name = s["name"]
        src = os.path.join(sdir, name + ".srt")
        if not os.path.exists(src):
            continue
        shutil.copy(src, os.path.join(odir, name + ".srt"))
        c_in = parse_srt(open(src, encoding="utf-8").read())
        res = {"name": name, "lang": s["lang"], "cues": len(c_in), "dur_ms": c_in[-1][1] if c_in else 0}
        outs = {}
        # oraculo 1: ffsubsync (VAD webrtc, razoes de fps discretas)
        t0 = time.time()
        o1 = os.path.join(odir, name + ".ffs.srt")
        ok, tail = run_ffsubsync(wav, src, o1)
        if ok:
            outs["ffs"] = parse_srt(open(o1, encoding="utf-8").read())
            res["ffs_tail"] = tail[-200:].replace("\n", " | ")
        res["ffs_s"] = round(time.time() - t0, 1)
        # oraculo 2: ffsubsync com VAD Silero (se esta versao tiver)
        if have_silero_vad[0]:
            o2 = os.path.join(odir, name + ".ffss.srt")
            ok, tail = run_ffsubsync(wav, src, o2, vad="silero")
            if ok:
                outs["ffss"] = parse_srt(open(o2, encoding="utf-8").read())
            elif "invalid choice" in tail or not tail:
                have_silero_vad[0] = False
        # oraculo 3: alass (aceita cortes)
        if alass:
            t0 = time.time()
            o3 = os.path.join(odir, name + ".alass.srt")
            ok, tail2 = run_alass(alass, wav, src, o3)
            res["alass_s"] = round(time.time() - t0, 1)
            if ok:
                outs["alass"] = parse_srt(open(o3, encoding="utf-8").read())
        fits = {}
        for k, c in outs.items():
            fits[k] = theil_sen(cue_map(c_in, c))
        res["fits"] = fits
        # acordos par a par; verdade = melhor par que concorda (mediana <= 250 ms e p75 <= 1 s)
        names = list(outs.keys())
        pairs_ag = {}
        best = None
        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                a = agree(c_in, outs[names[i]], outs[names[j]])
                pairs_ag["%s|%s" % (names[i], names[j])] = a
                if a and a["med"] <= 250 and a["p75"] <= 1000:
                    if best is None or a["med"] < best[2]["med"]:
                        best = (names[i], names[j], a)
        res["agree_all"] = pairs_ag
        res["truth"] = best is not None
        if best:
            res["truth_src"] = best[0] + "+" + best[1]
            res["agree"] = best[2]
            tc = outs[best[0]]
            write_srt(tc, os.path.join(odir, name + ".truth.srt"))
            fm = {a: b for a, b in cue_map(c_in, tc)}
            with open(os.path.join(odir, name + ".pairs.tsv"), "w") as pf:
                for a, b, t in c_in:
                    if a in fm:
                        pf.write("%d\t%d\n" % (a, fm[a]))
        oracle[name] = res
        print("  %s: truth=%s %s agree=%s" % (name, res["truth"], res.get("truth_src", ""), {k: (v["med"], v["p75"]) if v else None for k, v in pairs_ag.items()}), flush=True)
    json.dump(oracle, open(os.path.join(fdir, "oracle.json"), "w"), indent=1)
    with open(os.path.join(fdir, "truth.tsv"), "w") as tf:
        tf.write("name\tlang\ttruth\tcues\tsrc\tagree_med\tagree_p90\n")
        for n, o in oracle.items():
            ag = o.get("agree") or {}
            tf.write("%s\t%s\t%d\t%d\t%s\t%.1f\t%.1f\n" % (n, o["lang"], 1 if o["truth"] else 0, o["cues"], o.get("truth_src", "-"), ag.get("med", -1), ag.get("p90", -1)))
    # confere cruzada PT x EN (só tempos): distância mediana do início de cada fala EN ao início PT mais próximo
    cross = {}
    tr = {n: parse_srt(open(os.path.join(odir, n + ".truth.srt"), encoding="utf-8").read()) for n, o in oracle.items() if o["truth"]}
    for pn in [n for n in tr if n.startswith("pt")]:
        for en in [n for n in tr if n.startswith("en")]:
            ps = sorted(c[0] for c in tr[pn])
            import bisect
            ds = []
            for c in tr[en][::3]:
                i = bisect.bisect_left(ps, c[0])
                cand = [abs(ps[j] - c[0]) for j in (i - 1, i) if 0 <= j < len(ps)]
                ds.append(min(cand))
            ds.sort()
            cross["%s|%s" % (pn, en)] = ds[len(ds) // 2] if ds else None
    meta["cross_pt_en_med_ms"] = cross
    json.dump(meta, open(os.path.join(fdir, "meta.json"), "w"), indent=1)
    shutil.rmtree(wdir, ignore_errors=True)
    print("OK", slug, flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "subs":
        cmd_subs(sys.argv[2])
    elif sys.argv[1] == "film":
        cmd_film(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        raise SystemExit(__doc__)
