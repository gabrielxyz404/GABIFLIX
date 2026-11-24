#!/usr/bin/env python3
"""Substituto realista do filme (Taken 2 não pode ser usado: tem direitos de autor). Roda só no CI (ubuntu).
Um filme de domínio público de ~90 min (archive.org) com fala real, a 23,976 fps, e a legenda PT CERTA (a do addon OpenSubtitles alinhada ao áudio pelo alass,
um alinhador independente do nosso motor: serve de verdade-de-campo).
Uso: prep_film.py SLUG1,SLUG2,... OUT_DIR     (tenta cada filme até um ter legenda PT)
Saída: OUT_DIR/film.mkv (sem legenda), OUT_DIR/certa.srt, OUT_DIR/info.json"""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "audio-integra", "corpus"))
import corpus as C

slugs, out = sys.argv[1].split(","), sys.argv[2]
TARGET_S = int(os.environ.get("TARGET_S") or 5400)   # ~90 min
os.makedirs(out, exist_ok=True)
work = os.path.join(out, "_work"); os.makedirs(work, exist_ok=True)
F = {f["slug"]: f for f in C.FILMS}


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


slug = f = cands = None
for sl in slugs:
    try:
        cs = addon_subs(F[sl], 6)
    except Exception as e:  # noqa
        print("filme", sl, "falhou na busca:", e, flush=True); continue
    print("filme", sl, "legendas PT:", len(cs), flush=True)
    if cs:
        slug, f, cands = sl, F[sl], cs
        break
if f is None:
    print("nenhum filme com legenda PT"); sys.exit(5)

src, info = C.acquire_video(f, work)
print("fonte:", info, flush=True)
dur = C.probe_duration(src)
clip_s = int(min(TARGET_S, dur - 20))
print("duração da fonte %.0f s; uso %d s" % (dur, clip_s), flush=True)
film = os.path.join(out, "film.mkv")
wav = os.path.join(work, "audio16k.wav")
# vídeo pequeno (a imagem não importa: o runner não tem placa gráfica), 23,976 fps, áudio como veio (aac estéreo)
C.sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-t", str(clip_s), "-i", src, "-map", "0:v:0", "-map", "0:a:0",
      "-vf", "scale=320:-2", "-r", "24000/1001", "-c:v", "libx264", "-preset", "veryfast", "-crf", "36", "-c:a", "aac", "-b:a", "64k", "-ac", "2", film])
C.sh(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", film, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", wav])
os.remove(src)
print("filme: %.1f s, %d bytes" % (C.probe_duration(film), os.path.getsize(film)), flush=True)

alass = os.path.join(work, "alass")
C.download("https://github.com/kaegi/alass/releases/download/v2.0.0/alass-linux64", alass)
os.chmod(alass, 0o755)

good = None
fb = None
oracle = []
for idx, (sid, cues) in enumerate(cands):
    trimmed = [c for c in cues if c[1] <= clip_s * 1000]
    if len(trimmed) < 300:
        oracle.append({"idx": idx, "id": sid, "skip": "poucas falas no trecho (%d)" % len(trimmed)}); continue
    tin = os.path.join(work, "c%d.srt" % idx); tout = os.path.join(work, "c%d.alass.srt" % idx)
    C.write_srt(trimmed, tin)
    ok, _ = C.run_alass(alass, wav, tin, tout)
    if not ok:
        oracle.append({"idx": idx, "id": sid, "skip": "alass falhou"}); continue
    aligned = C.parse_srt(open(tout, encoding="utf-8").read())
    pairs = C.cue_map(trimmed, aligned)
    ds = sorted(b - a for a, b in pairs)
    if len(ds) < 150:
        oracle.append({"idx": idx, "id": sid, "skip": "poucos pares"}); continue
    med = ds[len(ds) // 2]
    dev = sorted(abs(d - med) for d in ds)
    rec = {"idx": idx, "id": sid, "cues": len(trimmed), "alass_mediana_ms": med, "alass_p90_desvio_ms": dev[int(len(dev) * .9)]}
    oracle.append(rec)
    print(rec, flush=True)
    # a verdade-de-campo: a versão alinhada pelo alass (sempre casa com o áudio; a original pode ser de outro corte)
    if fb is None or rec["alass_p90_desvio_ms"] < fb[2]:
        fb = (idx, aligned, rec["alass_p90_desvio_ms"])
    if good is None and abs(med) <= 300 and rec["alass_p90_desvio_ms"] <= 1000:
        good = (idx, cues)
json.dump(oracle, open(os.path.join(out, "oracle.json"), "w"), indent=1)
if fb is None:
    print("sem legenda alinhável"); sys.exit(3)
# a "certa" é sempre a alinhada pelo alass (todos os tempos conferidos com o áudio do trecho)
cues = fb[1]
C.write_srt(cues, os.path.join(out, "certa.srt"))
json.dump({"slug": slug, "titulo_corpus": f["title"], "clip_s": clip_s, "legenda_idx": fb[0], "legenda_original_ja_casava": good is not None, "fonte": info}, open(os.path.join(out, "info.json"), "w"), indent=1)
print("pronto", flush=True)
