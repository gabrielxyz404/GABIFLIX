#!/usr/bin/env python3
"""Fracao das fotos de uma pasta com texto de legenda visivel (video preto: pixels claros na parte de baixo). Uso: textrate.py DIR"""
import sys, glob, os
from PIL import Image
d = sys.argv[1]
fs = sorted(glob.glob(os.path.join(d, "*.png")))
n = t = 0
for f in fs:
    im = Image.open(f).convert("L"); w, h = im.size
    c = im.crop((0, int(h * 0.55), w, h)); px = c.load()
    k = sum(1 for y in range(0, c.size[1], 2) for x in range(0, c.size[0], 2) if px[x, y] > 200)
    n += 1; t += 1 if k > 40 else 0
print("%s: %d/%d fotos com legenda visivel" % (os.path.basename(d), t, n))
