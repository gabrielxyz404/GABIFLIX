#!/usr/bin/env python3
"""Mede, numa rajada de fotos depois que o motor aplicou o atraso, quando a legenda aparece. Video preto: texto = pixels claros embaixo.
Uso: burst.py DIR LOG CLOCK NOME. Imprime, para cada foto, se havia fala esperada (legenda certa, tempo da midia) e se havia texto na tela."""
import re, sys, os, glob
from PIL import Image
d, logf, clockf, name = sys.argv[1:5]
clk = open(clockf).read(); m = re.search(r"host=(\d+) dispositivo=(\d+)", clk); skew = int(m.group(1)) - int(m.group(2))
cues = []
for blk in re.split(r"\n\s*\n", open("/tmp/media/certa.srt", encoding="utf-8").read()):
    mm = re.search(r"(\d+):(\d+):(\d+),(\d+) --> (\d+):(\d+):(\d+),(\d+)", blk)
    if mm:
        g = list(map(int, mm.groups())); cues.append(((g[0] * 3600 + g[1] * 60 + g[2]) * 1000 + g[3], (g[4] * 3600 + g[5] * 60 + g[6]) * 1000 + g[7]))
tA = pA = None
for l in open(logf, errors="replace"):
    mm = re.match(r"\s*(\d+\.\d+).*AUDIOSYNC legenda: atraso (-?\d+) -> (-?\d+) ms.*posição (\d+) ms", l)
    if mm: tA = float(mm.group(1)) + skew; pA = int(mm.group(4)); delay = (int(mm.group(2)), int(mm.group(3)))
if tA is None: print(name, "sem registro de aplicacao"); sys.exit(0)
rows = []
for l in open(os.path.join(d, "times.txt")):
    i, a, b = l.split(); t = (float(a) + float(b)) / 2
    f = os.path.join(d, i + ".png")
    im = Image.open(f).convert("L"); w, h = im.size
    crop = im.crop((0, int(h * 0.6), w, h)); px = crop.load()
    n = sum(1 for y in range(0, crop.size[1], 2) for x in range(0, crop.size[0], 2) if px[x, y] > 200)
    media = pA + (t - tA) * 1000
    exp = any(s0 <= media <= e0 for s0, e0 in cues)
    rows.append((t - tA, media / 1000, exp, n > 40))
ex = [r for r in rows if r[2] and r[0] >= 0]
seen = [r for r in ex if r[3]]
first = next((r[0] for r in ex if r[3]), None)
gap = next((r[0] for r in rows if r[2] and r[0] >= 0), None)
print("%s: atraso %d->%d ms; fotos com fala esperada=%d, com texto visivel=%d (%.0f%%); falsos positivos (texto sem fala esperada)=%d; primeira fala esperada em +%.1f s, primeira vista em +%s s" % (
    name, delay[0], delay[1], len(ex), len(seen), 100.0 * len(seen) / max(1, len(ex)), sum(1 for r in rows if r[3] and not r[2]), gap if gap is not None else -1, "%.1f" % first if first is not None else "nunca"))
print("  linha do tempo (s apos aplicar: E=fala esperada, T=texto): " + " ".join("%+.0f%s%s" % (r[0], "E" if r[2] else "-", "T" if r[3] else "-") for r in rows))
