#!/usr/bin/env python3
"""Prepara o material da prova no emulador (roda so no CI): um trecho de 20 min de um filme de dominio publico (archive.org) com fala real,
e as legendas: a CERTA (confirmada por um alinhador independente, o alass, contra o audio do trecho), a mesma deslocada (+3 s e -2 s)
e a de OUTRO filme. Uso: prep.py SLUG OUTRO_SLUG OUT_DIR"""
import json, os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "corpus"))
import corpus as C

slugs, others, out = sys.argv[1].split(","), sys.argv[2].split(","), sys.argv[3]
CLIP_S = 1200
os.makedirs(out, exist_ok=True)
work = os.path.join(out, "_work"); os.makedirs(work, exist_ok=True)
F = {f["slug"]: f for f in C.FILMS}


def addon_subs(f, maxn, pt=True):
    imdb = C.resolve_imdb(f)
    raw = C.http_get("https://opensubtitles-v3.strem.io/subtitles/movie/%s.json" % imdb, binary=False)
    items = json.loads(raw).get("subtitles", []) if raw else []
    got = []
    for it in items:
        lg = str(it.get("lang", "")).lower()
        if not (C.is_pt(lg) if pt else C.is_en(lg)) or not it.get("url"):
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


# o app so aceita legendas em portugues: escolhe o primeiro filme com legenda PT
slug = f = cands = None
for sl in slugs:
    cs = addon_subs(F[sl], 6)
    print("filme", sl, "legendas PT:", len(cs), flush=True)
    if cs:
        slug, f, cands = sl, F[sl], cs
        break
if f is None:
    print("nenhum filme com legenda PT"); sys.exit(5)
clip = os.path.join(out, "clip.mkv")
wav = os.path.join(work, "audio16k.wav")
if not os.path.exists(clip):
    src, info = C.acquire_video(f, work)
    print("fonte:", info, flush=True)
    C.sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", "0", "-t", str(CLIP_S), "-i", src, "-map", "0:v:0", "-map", "0:a:0",
          "-vf", "scale=640:-2", "-r", "24", "-c:v", "libx264", "-preset", "veryfast", "-crf", "30", "-c:a", "aac", "-b:a", "96k", "-ac", "2", clip])
    C.sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", clip, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", wav])
    os.remove(src)
if not os.path.exists(wav):
    C.sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", clip, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", wav])
print("clip: %.1f s, %d bytes" % (C.probe_duration(clip), os.path.getsize(clip)), flush=True)

alass = os.path.join(work, "alass")
if not os.path.exists(alass):
    C.download("https://github.com/kaegi/alass/releases/download/v2.0.0/alass-linux64", alass)
    os.chmod(alass, 0o755)

print("candidatas PT:", len(cands), flush=True)
oracle = []
good = None
for idx, (sid, cues) in enumerate(cands):
    trimmed = [c for c in cues if c[1] <= CLIP_S * 1000]
    if len(trimmed) < 150:
        oracle.append({"idx": idx, "id": sid, "skip": "poucas falas no trecho (%d)" % len(trimmed)}); continue
    tin = os.path.join(work, "c%d.srt" % idx); tout = os.path.join(work, "c%d.alass.srt" % idx)
    C.write_srt(trimmed, tin)
    ok, _ = C.run_alass(alass, wav, tin, tout)
    if not ok:
        oracle.append({"idx": idx, "id": sid, "skip": "alass falhou"}); continue
    pairs = C.cue_map(trimmed, C.parse_srt(open(tout, encoding="utf-8").read()))
    ds = sorted(b - a for a, b in pairs)
    if len(ds) < 100:
        oracle.append({"idx": idx, "id": sid, "skip": "poucos pares"}); continue
    med = ds[len(ds) // 2]
    dev = sorted(abs(d - med) for d in ds)
    rec = {"idx": idx, "id": sid, "cues_trecho": len(trimmed), "alass_mediana_ms": med, "alass_p75_desvio_ms": dev[int(len(dev) * .75)], "alass_p90_desvio_ms": dev[int(len(dev) * .9)]}
    oracle.append(rec)
    print(rec, flush=True)
    if good is None and abs(med) <= 300 and rec["alass_p90_desvio_ms"] <= 1000:
        good = (idx, cues)
    best_rec = rec
    if "alass_p90_desvio_ms" in rec and (not locals().get("fb") or rec["alass_p90_desvio_ms"] < fb[2]):
        fb = (idx, C.parse_srt(open(tout, encoding="utf-8").read()), rec["alass_p90_desvio_ms"])
json.dump(oracle, open(os.path.join(out, "oracle.json"), "w"), indent=1)
how = "original confirmada pelo alass"
if good is None:
    # nenhuma legenda baixada casa com este corte do filme: a "certa" e a versao que o alass (independente do nosso motor) alinhou ao audio
    fb = locals().get("fb")
    if os.environ.get("FALLBACK") != "1":
        print("nenhuma legenda original confirmada pelo alass neste filme"); sys.exit(3)
    if fb is None:
        print("sem legenda alinhavel"); sys.exit(3)
    good = (fb[0], fb[1]); how = "alinhada ao audio pelo alass (nenhuma original casava)"
    print("usando a legenda %d alinhada pelo alass" % fb[0], flush=True)
cues = good[1]
C.write_srt(cues, os.path.join(out, "certa.srt"))
C.write_srt([[a + 3000, b + 3000, t] for a, b, t in cues], os.path.join(out, "mais3.srt"))
C.write_srt([[max(0, a - 2000), max(0, b - 2000), t] for a, b, t in cues], os.path.join(out, "menos2.srt"))
wrong = []
for o in others:
    wrong = addon_subs(F[o], 1)
    if wrong:
        other = o
        break
if not wrong:
    print("sem legenda do outro filme"); sys.exit(4)
C.write_srt(wrong[0][1], os.path.join(out, "outro.srt"))
json.dump({"slug": slug, "outro": other, "legenda_certa_idx": good[0], "clip_s": CLIP_S, "certa": how}, open(os.path.join(out, "info.json"), "w"), indent=1)
print("pronto", flush=True)
