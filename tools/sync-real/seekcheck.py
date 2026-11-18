#!/usr/bin/env python3
"""Compara cada foto (video preto: texto = pixels claros embaixo) com o que a legenda CERTA mostraria naquele instante da midia.
A posicao vem das linhas 'pos N' do registro (interpolada pela hora). Uso: seekcheck.py DIR LOG CLOCK NOME"""
import re, sys, os, bisect
from PIL import Image
d, logf, clockf, name = sys.argv[1:5]
m = re.search(r"host=(\d+) dispositivo=(\d+)", open(clockf).read()); skew = int(m.group(1)) - int(m.group(2))
cues = []
for blk in re.split(r"\n\s*\n", open("/tmp/media/certa.srt", encoding="utf-8").read()):
    mm = re.search(r"(\d+):(\d+):(\d+),(\d+) --> (\d+):(\d+):(\d+),(\d+)", blk)
    if mm:
        g = list(map(int, mm.groups())); cues.append(((g[0]*3600+g[1]*60+g[2])*1000+g[3], (g[4]*3600+g[5]*60+g[6])*1000+g[7]))
ts, ps = [], []
for l in open(logf, errors="replace"):
    mm = re.match(r"\s*(\d+\.\d+).*GabiFlixPlayer\s*:\s*pos (\d+)", l)
    if mm: ts.append(float(mm.group(1)) + skew); ps.append(int(mm.group(2)))
def media(t):
    i = bisect.bisect_left(ts, t)
    if i <= 0 or i >= len(ts): return None
    if ps[i] - ps[i-1] > 5000 or ps[i] - ps[i-1] < 0: return None   # salto (seek) entre as duas amostras
    return ps[i-1] + (ps[i] - ps[i-1]) * (t - ts[i-1]) / max(1e-6, ts[i] - ts[i-1])
E = EV = FP = N = 0; miss = []
for l in open(os.path.join(d, "times.txt")):
    i, a, b = l.split(); t = (float(a) + float(b)) / 2
    mt = media(t)
    if mt is None: continue
    f = os.path.join(d, i + ".png")
    if not os.path.exists(f): continue
    im = Image.open(f).convert("L"); w, h = im.size
    c = im.crop((0, int(h * 0.55), w, h)); px = c.load()
    k = sum(1 for y in range(0, c.size[1], 2) for x in range(0, c.size[0], 2) if px[x, y] > 200)
    txt = k > 40
    exp = any(s0 + 300 <= mt <= e0 - 300 for s0, e0 in cues)
    if exp and not txt: miss.append(int(mt))
    N += 1; E += exp; EV += (exp and txt); FP += (txt and not exp)
print("%s %s: fotos=%d, com fala esperada=%d, vistas=%d (%.0f%%), texto sem fala esperada=%d" % (name, os.path.basename(d), N, E, EV, 100.0*EV/max(1, E), FP))
print("  sem texto mesmo devendo ter (posicoes ms): %s" % miss[:40])
