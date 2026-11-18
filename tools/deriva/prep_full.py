#!/usr/bin/env python3
"""Material da prova de DERIVA (escala de fps) no filme INTEIRO (roda so no CI): audio 16 kHz mono do filme completo (ou dos primeiros CLIP_S segundos,
para repetir os recortes de 20 min das provas antigas), a legenda CERTA (a PT do addon alinhada ao audio pelo alass, um alinhador independente do nosso motor,
com divisao em trechos: serve de verdade-de-campo) e legendas de OUTROS filmes (para medir falsos aceites).
Uso: prep_full.py SLUG OUTROS(csv) OUT_DIR   (env CLIP_S=1200 para recortar)"""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "audio-integra", "corpus"))
import corpus as C

slug, others, out = sys.argv[1], sys.argv[2].split(","), sys.argv[3]
CLIP_S = int(os.environ.get("CLIP_S", "0"))
os.makedirs(out, exist_ok=True)
work = os.path.join(out, "_work"); os.makedirs(work, exist_ok=True)
F = {f["slug"]: f for f in C.FILMS}
f = F[slug]


def addon_subs(fl, maxn):
    imdb = C.resolve_imdb(fl)
    raw = C.http_get("https://opensubtitles-v3.strem.io/subtitles/movie/%s.json" % imdb, binary=False)
    items = json.loads(raw).get("subtitles", []) if raw else []
    got = []
    for it in items:
        if not C.is_pt(str(it.get("lang", "")).lower()) or not it.get("url"):
            continue
        b = C.http_get(it["url"])
        if not b or len(b) < 200:
            continue
        cues = C.parse_srt(C.decode_text(b))
        if len(cues) < 200:
            continue
        got.append((it.get("id", ""), cues))
        if len(got) >= maxn:
            break
    return got


cands = addon_subs(f, 6)
print("filme", slug, "legendas PT:", len(cands), flush=True)
if not cands:
    print("sem legenda PT"); sys.exit(5)

wav = os.path.join(out, "audio16k.wav")
src, info = C.acquire_video(f, work)
print("fonte:", info, flush=True)
cmd = ["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", src, "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le"]
if CLIP_S:
    cmd += ["-t", str(CLIP_S)]
C.sh(cmd + [wav])
os.remove(src)
dur_s = C.probe_duration(wav)
print("audio: %.1f s" % dur_s, flush=True)

alass = os.path.join(work, "alass")
C.download("https://github.com/kaegi/alass/releases/download/v2.0.0/alass-linux64", alass)
os.chmod(alass, 0o755)

best = None
oracle = []
for idx, (sid, cues) in enumerate(cands):
    trimmed = [c for c in cues if c[1] <= dur_s * 1000]
    if len(trimmed) < 150:
        oracle.append({"idx": idx, "id": sid, "skip": "poucas falas (%d)" % len(trimmed)}); continue
    tin = os.path.join(work, "c%d.srt" % idx); tout = os.path.join(work, "c%d.alass.srt" % idx)
    C.write_srt(trimmed, tin)
    ok, _ = C.run_alass(alass, wav, tin, tout)
    if not ok:
        oracle.append({"idx": idx, "id": sid, "skip": "alass falhou"}); continue
    aligned = C.parse_srt(open(tout, encoding="utf-8").read())
    pairs = C.cue_map(trimmed, aligned)
    ds = sorted(b - a for a, b in pairs)
    if len(ds) < 100:
        oracle.append({"idx": idx, "id": sid, "skip": "poucos pares"}); continue
    med = ds[len(ds) // 2]
    dev = sorted(abs(d - med) for d in ds)
    rec = {"idx": idx, "id": sid, "cues": len(trimmed), "alass_mediana_ms": med, "alass_p90_desvio_ms": dev[int(len(dev) * .9)], "alass_max_desvio_ms": dev[-1]}
    oracle.append(rec)
    print(rec, flush=True)
    # a melhor candidata: a que o alass mexeu MENOS (a original ja casa melhor com este corte do filme)
    score = rec["alass_p90_desvio_ms"] + abs(med)
    if best is None or score < best[0]:
        best = (score, idx, aligned, rec)
json.dump(oracle, open(os.path.join(out, "oracle.json"), "w"), indent=1)
if best is None:
    print("sem legenda alinhavel"); sys.exit(3)
C.write_srt(best[2], os.path.join(out, "certa.srt"))
n = 0
for o in others:
    if o == slug or n >= 2:
        continue
    w = addon_subs(F[o], 1)
    if w:
        n += 1
        C.write_srt(w[0][1], os.path.join(out, "outro%d.srt" % n))
        print("outro%d = %s" % (n, o), flush=True)
if n == 0:
    print("sem legenda de outro filme"); sys.exit(4)
json.dump({"slug": slug, "audio_s": dur_s, "clip_s": CLIP_S, "fonte": {k: info.get(k) for k in ("ia", "file", "length")}, "certa": best[3], "outros": n}, open(os.path.join(out, "info.json"), "w"), indent=1)
os.system("rm -rf %s" % work)
print("pronto", flush=True)
