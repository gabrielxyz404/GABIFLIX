#!/usr/bin/env python3
"""Um shard da bancada (roda so no CI): para cada titulo do shard baixa a midia, extrai o audio, monta a legenda de referencia (alass + 2o oraculo),
gera as variantes e roda os dois motores (origin/main e ramo da deriva) com o laco real do app. Resultados em OUT/<id>/ (jsonl pequenos).

  shard.py CATALOGO.json SHARD NSHARDS OUT WORK --cp-main DIR --cp-drift DIR --weights silero_v5_16k.bin --alass BIN
           [--alvo-antigos 140 --alvo-tv 40 --alvo-novos 120 --minutos 300 --ffs 1]

Nada aqui muda o motor. Midia e audio ficam so no runner e sao apagados a cada titulo."""
import argparse, concurrent.futures, glob, gzip, json, math, os, random, re, shutil, subprocess, sys, threading, time, zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

APP_PKG = "io.github.gabrielxyz404.gabiflix"
T0 = time.time()
ARGS = None


# ---------------------------------------------------------------- utilidades
def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(len(v) * q))] if v else None


def curl(url, dst, tries=3, maxtime=3300):
    for a in range(tries):
        r = subprocess.run(["curl", "-fL", "-sS", "--retry", "4", "--retry-delay", "6", "--retry-all-errors", "--connect-timeout", "30", "--max-time", str(maxtime), "-A", UA["User-Agent"], "-o", dst, url],
                           capture_output=True, text=True)
        if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 100_000:
            return True
        log("  curl falhou (%d): %s" % (r.returncode, (r.stderr or "")[-200:].strip()))
        time.sleep(10 * (a + 1))
    return False


def ffprobe(path):
    r = sh(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path], check=False, timeout=120)
    try:
        return json.loads(r.stdout)
    except Exception:
        return None


def extract_wav(media, wav, track=0):
    r = sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", media, "-map", "0:a:%d" % track, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", wav], check=False, timeout=3600)
    return r.returncode == 0 and os.path.exists(wav) and os.path.getsize(wav) > 1_000_000, (r.stderr or "")[-300:]


def wav_seconds(wav):
    return max(0, (os.path.getsize(wav) - 44)) / 32000.0


def silent_open(wav):
    """segundos de silencio no comeco (silencedetect -45 dB nos primeiros 15 min); 0 = comeca com som"""
    r = sh(["ffmpeg", "-nostdin", "-hide_banner", "-t", "900", "-i", wav, "-af", "silencedetect=noise=-45dB:d=8", "-f", "null", "-"], check=False, timeout=300)
    m = re.search(r"silence_start: (-?[\d.]+)", r.stderr or "")
    e = re.search(r"silence_end: ([\d.]+)", r.stderr or "")
    if m and float(m.group(1)) <= 0.5 and e:
        return float(e.group(1))
    if m and float(m.group(1)) <= 0.5:
        return 900.0
    return 0.0


def java_cp(which):
    return ARGS.cp_main if which == "main" else ARGS.cp_drift


def app_decode(raw, out):
    """o parser do APP (SubtitleText.decode + Cue.parseSrt) sobre os bytes baixados -> (dict da linha F, texto decodificado ou None)"""
    r = sh(["java", "-cp", ARGS.cp_main, APP_PKG + ".SubFmt", "dec", raw, out], check=False, timeout=120)
    f = None
    for l in (r.stdout or "").splitlines():
        if l.startswith("F "):
            try:
                f = json.loads(l[2:])
            except Exception:
                pass
    if f and f.get("decoded") and os.path.exists(out):
        return f, open(out, encoding="utf-8").read()
    return f or {"decoded": 0, "exc": (r.stderr or "")[-200:]}, None


EN_W = set("the you and that what this with have are not but your i'm it's don't they there just know".split())
PT_W = set("não você vocês estou então isso são também muito porque pra obrigado tá nós meu minha ele dela já até bem tudo aqui foi vai tem sim uma".split())
ES_W = set("el los las pero usted esto estoy yo muy gracias señor tengo qué cómo hay soy eres nosotros".split())


def guess_lang(text):
    ws = re.findall(r"[\w']+", text[:300000].lower())
    c = {"eng": sum(w in EN_W for w in ws), "por": sum(w in PT_W for w in ws), "spa": sum(w in ES_W for w in ws)}
    k = max(c, key=c.get)
    return k if c[k] >= 8 else "und"


def py_decode(b):
    if b[:2] == b"\x1f\x8b":
        import gzip as gz
        try:
            b = gz.decompress(b)
        except Exception:
            return ""
    if b[:3] == b"\xef\xbb\xbf":
        b = b[3:]
    for enc in ("utf-8", "cp1252", "latin-1"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            pass
    return b.decode("latin-1", "replace")


# ---------------------------------------------------------------- legendas
def fetch_sub(sub, dst):
    b = http_get(sub["url"], tries=3, timeout=90)
    if not b or len(b) < 200:
        return False
    open(dst, "wb").write(b)
    return True


def sub_candidates(entry, wd, maxn=5):
    """baixa e decodifica (com o parser do app) as candidatas: PT primeiro, depois EN/outras. -> lista de dicts com 'cues' (python), 'text', 'info'"""
    out = []
    subs = sorted(entry["subs"], key=lambda s: (0 if is_pt(s["lang"]) else 1 if is_en(s["lang"]) else 2 if s["lang"] != "und" else 3))
    for k, s in enumerate(subs[:maxn + 2]):
        if len(out) >= maxn:
            break
        raw = os.path.join(wd, "raw%d.bin" % k)
        if not fetch_sub(s, raw):
            continue
        dec = os.path.join(wd, "dec%d.srt" % k)
        f, text = app_decode(raw, dec)
        rawb = open(raw, "rb").read()
        pytext = py_decode(rawb)
        pycues = parse_srt(pytext)
        rec = {"k": k, "lang": s["lang"], "src": s["src"], "id": s.get("id", ""), "bytes": len(rawb), "app": f, "py_cues": len(pycues)}
        if text is None:
            if len(pycues) >= 80:
                rec["parser_bug"] = "o app descartaria (decode=null) uma legenda que o python le (%d falas)" % len(pycues)
                text = pytext
            else:
                rec["skip"] = "nao e legenda"
                out.append(rec)
                continue
        else:
            ac = f.get("cues", 0)
            if abs(ac - len(pycues)) > max(5, 0.03 * max(ac, len(pycues))):
                rec["parser_diff"] = "falas app=%d python=%d" % (ac, len(pycues))
        cues = parse_srt(text)
        rec["cues"] = cues
        rec["text_lang"] = "por" if f and f.get("pt") else guess_lang(text)
        rec["pt_app"] = int(bool(f and f.get("pt")))
        if len(cues) < 80:
            rec["skip"] = "poucas falas (%d)" % len(cues)
        log("   legenda %d: %s/%s %d bytes -> app decodificou=%s falas=%s (python %d) PT=%s%s%s" % (k, s["lang"], s["src"], len(rawb), bool(f.get("decoded")), f.get("cues"), len(pycues), rec["pt_app"],
                                                                                           " SKIP: " + rec["skip"] if rec.get("skip") else "", " BUG-PARSER: " + rec["parser_bug"] if rec.get("parser_bug") else ""))
        out.append(rec)
    return out


def alass_align(wav, cues, dur_s, wd, tag):
    trimmed = [c for c in cues if c[1] <= dur_s * 1000]
    if len(trimmed) < 80:
        return None, "poucas falas no audio (%d)" % len(trimmed)
    tin, tout = os.path.join(wd, "a_%s.srt" % tag), os.path.join(wd, "a_%s.out.srt" % tag)
    write_srt(trimmed, tin)
    t0 = time.time()
    r = sh([ARGS.alass, wav, tin, tout], check=False, timeout=1500)
    if r.returncode != 0 or not os.path.exists(tout):
        return None, "alass falhou: " + ((r.stderr or r.stdout or "")[-160:]).replace("\n", " ")
    aligned = parse_srt(open(tout, encoding="utf-8").read())
    pairs = cue_map(trimmed, aligned)
    if len(pairs) < 60:
        return None, "poucos pares (%d)" % len(pairs)
    ds = sorted(b - a for a, b in pairs)
    med = ds[len(ds) // 2]
    dev = sorted(abs(d - med) for d in ds)
    return {"aligned": aligned, "med": med, "p90dev": dev[int(len(dev) * 0.9)], "maxdev": dev[-1], "pairs": len(pairs), "alass_s": round(time.time() - t0, 1)}, "ok"


def cue_map(cin, cout):
    """pares (inicio_entrada, inicio_saida): por INDICE quando a quantidade e o texto batem (o alass/ffs mantem ordem e falas); senao pelo texto (n-esima ocorrencia)"""
    if len(cin) == len(cout):
        same = sum(1 for a, b in zip(cin, cout) if a[2] == b[2])
        if same >= 0.9 * len(cin):
            return [(a[0], b[0]) for a, b in zip(cin, cout)]
    return _cue_map_text(cin, cout)


def _cue_map_text(cin, cout):
    from collections import defaultdict
    occ = defaultdict(list)
    for a, b, t in cout:
        occ[t].append(a)
    used = defaultdict(int)
    pairs = []
    for a, b, t in cin:
        k = used[t]
        if k < len(occ[t]):
            pairs.append((a, occ[t][k]))
        used[t] += 1
    return pairs


def ffs_offset(wav, ref_srt, wd):
    """2o oraculo independente: o ffsubsync sobre a referencia ja alinhada deve achar deslocamento ~0 (ms) -> |mediana| ou None se nao rodou"""
    if not ARGS.ffs:
        return None, "desligado"
    out = os.path.join(wd, "ffs.srt")
    r = sh(["ffs", wav, "-i", ref_srt, "-o", out, "--max-offset-seconds", "120", "--encoding", "utf-8", "--output-encoding", "utf-8"], check=False, timeout=900)
    if r.returncode != 0 or not os.path.exists(out):
        return None, "ffs falhou: " + ((r.stderr or r.stdout or "")[-120:]).replace("\n", " ")
    a, b = parse_srt(open(ref_srt, encoding="utf-8").read()), parse_srt(open(out, encoding="utf-8").read())
    pairs = cue_map(a, b)
    if len(pairs) < 50:
        return None, "ffs: poucos pares"
    ds = sorted(y - x for x, y in pairs)
    return ds[len(ds) // 2], "ok"


# ---------------------------------------------------------------- variantes
def variants(slug, other_paths):
    rnd = random.Random(zlib.crc32(slug.encode()))
    V = [("certa", "ja certa", "C", 1.0, 0.0, "-"), ("mais3", "deslocamento +3 s", "S", 1.0, 3000.0, "-"), ("menos2", "deslocamento -2 s", "S", 1.0, -2000.0, "-"),
         ("mais7.5", "deslocamento +7,5 s", "S", 1.0, 7500.0, "-")]
    offs = [1800.0, -2500.0, 4000.0, -900.0, 6200.0, -4300.0, 2600.0, -1300.0]
    rnd.shuffle(offs)
    D = [("1.001", "escala 1.001 (24/23.976)", 24 / 23.976), ("0.999", "escala 0.999 (23.976/24)", 23.976 / 24), ("25/23.976", "escala 25/23.976", 25 / 23.976), ("23.976/25", "escala 23.976/25", 23.976 / 25)]
    for i, (n, fam, g) in enumerate(D):
        for j in range(2):
            o = offs[(2 * i + j) % len(offs)]
            V.append(("g%s|o%+d" % (n, int(o)), fam, "S", g, o, "-"))
    for j, p in enumerate(other_paths[:2]):
        V.append(("outro%d" % (j + 1), "outro titulo", "X", 1.0, 0.0, p))
    if other_paths:
        V.append(("outro1|g25/23.976", "outro titulo (com escala)", "X", 25 / 23.976, -1500.0, other_paths[0]))
    return V


def write_variants(V, path, only=None):
    with open(path, "w", encoding="utf-8") as f:
        for n, fam, t, g, o, p in V:
            if only and n not in only:
                continue
            f.write("%s\t%s\t%s\t%r\t%r\t%s\n" % (n, fam, t, g, o, p))


def run_engines(slug, wav, ref_srt, vtsv, outdir, tag=""):
    procs = {}
    for eng in ("main", "drift"):
        o = os.path.join(outdir, "%s%s.jsonl" % (eng, tag))
        cmd = ["java", "-Xmx3g", "-cp", java_cp(eng), "Bench300", eng, wav, ARGS.weights, ref_srt, vtsv, o, slug + tag, "240"]
        env = dict(os.environ, VAD_THREADS="2")
        procs[eng] = (subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env), time.time())
    res = {}
    for eng, (p, t0) in procs.items():
        try:
            out, _ = p.communicate(timeout=3000)
        except subprocess.TimeoutExpired:
            p.kill()
            out = "TIMEOUT do processo (3000 s)"
        res[eng] = {"rc": p.returncode, "s": round(time.time() - t0, 1), "tail": "\n".join(l for l in (out or "").splitlines() if "JAVA_TOOL_OPTIONS" not in l)[-500:]}
    return res


# ---------------------------------------------------------------- um titulo
def process(entry, other_entries, wd, od, prefetch=None):
    os.makedirs(wd, exist_ok=True)
    os.makedirs(od, exist_ok=True)
    t_start = time.time()
    st = {"id": entry["id"], "title": entry["title"], "year": entry.get("year"), "decade": entry.get("decade"), "kind": entry["kind"], "imdb": entry.get("imdb"), "ia": entry.get("ia"),
          "series": entry.get("series"), "license": (entry.get("meta") or {}).get("license", ""), "collection": (entry.get("meta") or {}).get("collection"), "tag": entry.get("tag"),
          "media_file": (entry.get("media") or {}).get("file"), "media_size": (entry.get("media") or {}).get("size"), "notes": []}
    # 1) legendas candidatas (barato) antes de baixar o filme
    cands = sub_candidates(entry, wd)
    st["sub_candidates"] = [{k: v for k, v in c.items() if k not in ("cues", "text")} for c in cands]
    good = [c for c in cands if c.get("cues") and not c.get("skip")]
    json.dump(st["sub_candidates"], open(os.path.join(od, "subreal.json"), "w"))
    if not good:
        st["status"] = "sem legenda utilizavel"
        return st
    # 2) midia + audio
    media = os.path.join(wd, "media")
    t_dl = time.time()
    if prefetch is not None:
        ok = prefetch.result()
    else:
        ok = curl(entry["media"]["url"], media)
    st["download_s"] = round(time.time() - t_dl, 1)
    if ok and os.path.exists(media):
        log("   download: %.0f MB em %.0f s (%.1f MB/s)" % (os.path.getsize(media) / 1e6, st["download_s"], os.path.getsize(media) / 1e6 / max(1, st["download_s"])))
    if not ok or not os.path.exists(media):
        st["status"] = "download falhou"
        return st
    pr = ffprobe(media)
    if not pr:
        st["status"] = "ffprobe falhou"
        return st
    aud = [s for s in pr.get("streams", []) if s.get("codec_type") == "audio"]
    vid = [s for s in pr.get("streams", []) if s.get("codec_type") == "video"]
    st["probe"] = {"n_audio": len(aud), "n_video": len(vid), "audio": [{"codec": a.get("codec_name"), "ch": a.get("channels"), "layout": a.get("channel_layout"), "sr": a.get("sample_rate"),
                                                                       "lang": (a.get("tags") or {}).get("language"), "title": (a.get("tags") or {}).get("title")} for a in aud[:4]],
                   "dur_s": float(pr.get("format", {}).get("duration") or 0), "bitrate": pr.get("format", {}).get("bit_rate"), "container": pr.get("format", {}).get("format_name")}
    if not aud:
        st["status"] = "sem faixa de audio"
        return st
    wav = os.path.join(wd, "audio16k.wav")
    ok, err = extract_wav(media, wav, 0)
    if not ok:
        st["status"] = "extracao de audio falhou: %s (wav=%s bytes, midia=%s bytes, audio=%s)" % (err.strip()[-120:], os.path.getsize(wav) if os.path.exists(wav) else None, os.path.getsize(media), st["probe"]["audio"][:1])
        return st
    wav2 = None
    if len(aud) >= 2:
        w2 = os.path.join(wd, "audio16k_t2.wav")
        ok2, _ = extract_wav(media, w2, 1)
        if ok2:
            wav2 = w2
    os.remove(media)
    dur = wav_seconds(wav)
    st["audio_s"] = round(dur, 1)
    st["long"] = dur > 3 * 3600
    st["silent_open_s"] = silent_open(wav)
    if dur < 120:
        st["status"] = "audio curto demais"
        return st
    # 3) referencia: alass nas melhores candidatas (PT, depois EN...), depois o 2o oraculo na escolhida
    tried = []
    best = None
    for c in good[:3]:
        al, why = alass_align(wav, c["cues"], dur, wd, "c%d" % c["k"])
        rec = {"k": c["k"], "lang": c["lang"], "text_lang": c["text_lang"], "src": c["src"], "alass": why}
        log("   alass cand %d (%s, %d falas): %s%s" % (c["k"], c["lang"], len(c["cues"]), why, (" med=%d p90dev=%d max=%d pares=%d (%.0fs)" % (al["med"], al["p90dev"], al["maxdev"], al["pairs"], al["alass_s"])) if al else ""))
        if al:
            rec.update({"alass_med_ms": al["med"], "alass_p90dev_ms": al["p90dev"], "alass_maxdev_ms": al["maxdev"], "pairs": al["pairs"], "alass_s": al["alass_s"]})
            score = al["p90dev"] + abs(al["med"]) * 0.1
            pt = bool(c["pt_app"])
            cand = {"al": al, "c": c, "score": score, "pt": pt, "tier_ok": al["p90dev"] <= 1000}
            # PT vence se passar; senao a de menor desvio
            if best is None or (pt and not best["pt"] and cand["tier_ok"]) or (pt == best["pt"] and score < best["score"]):
                best = cand
        tried.append(rec)
        if best and best["pt"] and best["al"]["p90dev"] <= 300:
            break
    st["ref_tries"] = tried
    if not best:
        st["status"] = "nenhuma legenda alinhou (alass)"
        return st
    ref = os.path.join(wd, "certa.srt")
    write_srt(best["al"]["aligned"], ref)
    c = best["c"]
    ff, ffw = ffs_offset(wav, ref, wd)
    p90 = best["al"]["p90dev"]
    # A: alass quase so desloca (desvio p90 <= 300 ms) e o ffsubsync concorda (<= 300 ms). B (ampliado): alass alinhou por partes (p90 > 300 ms) mas o ffsubsync concorda no deslocamento global.
    ffok = ff is not None and abs(ff) <= 300
    tier = "A" if (p90 <= 300 and (ff is None or ffok)) else "B" if (ffok and p90 > 300 and best["al"]["maxdev"] <= 120000) else "X"
    log("   referencia: alass p90dev=%d ms med=%d ffs=%s -> nivel %s" % (p90, best["al"]["med"], ff, tier))
    st["ref"] = {"k": c["k"], "lang": c["lang"], "text_lang": c["text_lang"], "pt_app": c["pt_app"], "src": c["src"], "cues": len(best["al"]["aligned"]), "alass_p90dev_ms": p90, "alass_med_ms": best["al"]["med"],
                 "alass_maxdev_ms": best["al"]["maxdev"], "ffs_off_ms": ff, "ffs_note": ffw, "tier": tier}
    if tier == "X":
        st["status"] = "referencia ruim (alass p90=%d ms, ffs=%s)" % (p90, ff)
        return st
    gzip.open(os.path.join(od, "certa.srt.gz"), "wt", encoding="utf-8").write(open(ref, encoding="utf-8").read())
    # 4) outras legendas (de outros titulos) para os aceites falsos
    others = []
    for oe in other_entries[:3]:
        if len(others) >= 2:
            break
        owd = os.path.join(wd, "o%d" % len(others))
        os.makedirs(owd, exist_ok=True)
        oc = [x for x in sub_candidates(oe, owd, maxn=1) if x.get("cues") and not x.get("skip")]
        if oc:
            op = os.path.join(wd, "outro%d.srt" % (len(others) + 1))
            write_srt(oc[0]["cues"], op)
            others.append(op)
            st.setdefault("others", []).append({"id": oe["id"], "lang": oc[0]["lang"], "cues": len(oc[0]["cues"])})
    V = variants(entry["id"], others)
    vtsv = os.path.join(wd, "var.tsv")
    write_variants(V, vtsv)
    # 5) formato das legendas (parser do app) sobre a referencia
    r = sh(["java", "-cp", ARGS.cp_main, APP_PKG + ".SubFmt", "synth", ref, entry["id"]], check=False, timeout=300)
    open(os.path.join(od, "subfmt.jsonl"), "w").write("\n".join(l[2:] for l in (r.stdout or "").splitlines() if l.startswith("S ")))
    # 6) os dois motores, em paralelo
    t_run = time.time()
    st["engines"] = run_engines(entry["id"], wav, ref, vtsv, od)
    if wav2:
        sub = {"certa", "mais3", "menos2", [v for v in V if v[0].startswith("g1.001")][0][0]}
        v2 = os.path.join(wd, "var2.tsv")
        write_variants(V, v2, only=sub)
        st["engines_t2"] = run_engines(entry["id"], wav2, ref, v2, od, "_t2")
        st["t2"] = (st["probe"]["audio"][1] if len(st["probe"]["audio"]) > 1 else {})
    st["run_s"] = round(time.time() - t_run, 1)
    st["status"] = "ok"
    st["variants"] = len(V)
    st["total_s"] = round(time.time() - t_start, 1)
    return st


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("catalog")
    ap.add_argument("shard", type=int)
    ap.add_argument("nshards", type=int)
    ap.add_argument("out")
    ap.add_argument("work")
    ap.add_argument("--cp-main", required=True)
    ap.add_argument("--cp-drift", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--alass", required=True)
    ap.add_argument("--alvo-antigos", type=int, default=140)
    ap.add_argument("--alvo-tv", type=int, default=40)
    ap.add_argument("--alvo-novos", type=int, default=120)
    ap.add_argument("--minutos", type=int, default=300)
    ap.add_argument("--ffs", type=int, default=1)
    ap.add_argument("--max-titulos", type=int, default=0)
    ARGS = ap.parse_args()
    cat = json.load(open(ARGS.catalog, encoding="utf-8"))
    N, I = ARGS.nshards, ARGS.shard
    by = {"old": [], "old-tv": [], "new": []}
    for e in cat["titulos"]:
        by[e["kind"]].append(e)
    mine = {k: v[I::N] for k, v in by.items()}
    quota = {"old": math.ceil(ARGS.alvo_antigos / N), "old-tv": math.ceil(ARGS.alvo_tv / N), "new": math.ceil(ARGS.alvo_novos / N)}
    if ARGS.max_titulos:
        quota = {k: min(v, max(1, ARGS.max_titulos // 3)) for k, v in quota.items()}
    log("shard %d/%d: filas %s quotas %s" % (I, N, {k: len(v) for k, v in mine.items()}, quota))
    os.makedirs(ARGS.out, exist_ok=True)
    got = {k: 0 for k in quota}
    order = []
    # ordem: intercala os tipos enquanto ha cota
    idx = {k: 0 for k in mine}
    while True:
        prog = False
        for k in ("old", "new", "old-tv"):
            if idx[k] < len(mine[k]):
                order.append(mine[k][idx[k]])
                idx[k] += 1
                prog = True
        if not prog:
            break
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    summary = []
    deadline = T0 + ARGS.minutos * 60
    pending = list(order)
    nxt = None
    while pending:
        e = pending.pop(0)
        if got[e["kind"]] >= quota[e["kind"]]:
            continue
        if time.time() > deadline:
            log("prazo do shard esgotado")
            break
        wd = os.path.join(ARGS.work, e["id"])
        od = os.path.join(ARGS.out, e["id"])
        shutil.rmtree(wd, ignore_errors=True)
        log("=== [%d/%d %s] %s (%s) %s" % (sum(got.values()) + 1, sum(quota.values()), e["kind"], e["title"][:60], e.get("year"), e["id"]))
        others = [x for x in order if x["id"] != e["id"]]
        random.Random(zlib.crc32(e["id"].encode())).shuffle(others)
        try:
            st = process(e, others, wd, od)
        except Exception as ex:
            import traceback
            traceback.print_exc()
            st = {"id": e["id"], "title": e["title"], "kind": e["kind"], "status": "excecao: %r" % ex}
        st["shard"] = I
        os.makedirs(od, exist_ok=True)
        json.dump(st, open(os.path.join(od, "title.json"), "w"), ensure_ascii=False, indent=1, default=str)
        summary.append({k: st.get(k) for k in ("id", "title", "kind", "status", "total_s")})
        log("   -> %s (%s s)" % (st["status"], st.get("total_s")))
        if st["status"] == "ok":
            got[e["kind"]] += 1
        shutil.rmtree(wd, ignore_errors=True)
    json.dump({"shard": I, "got": got, "quota": quota, "summary": summary, "elapsed_min": round((time.time() - T0) / 60, 1)}, open(os.path.join(ARGS.out, "shard.json"), "w"), indent=1)
    log("shard terminado:", got)


if __name__ == "__main__":
    main()
