#!/usr/bin/env python3
"""mede texto branco nos quadros BGRA: measure.py <pasta> <largura> <altura> ; imprime JSON por alvo (ms) com linhas em % da altura; grava PNGs"""
import sys, json, os, glob
import numpy as np
d, W, H = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
def lines(img):
    mask = img > 215
    rows = np.where(mask.sum(1) >= 2)[0]
    if rows.size == 0: return []
    groups = []; s = p = rows[0]
    for r in rows[1:]:
        if r - p > 1: groups.append((s, p)); s = r
        p = r
    groups.append((s, p))
    res = []
    for a, b in groups:
        cols = np.where(mask[a:b+1].sum(0) > 0)[0]
        res.append(dict(top=int(a), bot=int(b), h=int(b-a+1), left=int(cols[0]), right=int(cols[-1]), w=int(cols[-1]-cols[0]+1)))
    return res
out = {}
for f in sorted(glob.glob(d + "/f*.bgra"), key=lambda x: int(os.path.basename(x)[1:-5])):
    t = int(os.path.basename(f)[1:-5])
    raw = np.fromfile(f, np.uint8)
    if raw.size != W*H*4: out[t/1000] = "tamanho %d" % raw.size; continue
    img = raw.reshape(H, W, 4)[:, :, :3].min(axis=2)
    L = lines(img)
    out[t/1000] = dict(n=len(L), pct=[round(100*l["h"]/H, 2) for l in L],
                  pitch_pct=[round(100*(L[i+1]["top"]-L[i]["top"])/H, 2) for i in range(len(L)-1)],
                  w_pct=[round(100*l["w"]/W, 1) for l in L],
                  bottom_margin_pct=round(100*(H-1-L[-1]["bot"])/H, 2) if L else None)
print(json.dumps(out))
