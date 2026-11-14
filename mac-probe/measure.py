#!/usr/bin/env python3
"""mede texto branco nos quadros: measure.py <video.ts> <altura> t1 t2 ... ; imprime caixas de linhas em % da altura"""
import subprocess, sys, json
import numpy as np
path, H = sys.argv[1], int(sys.argv[2]); W = H*16//9
def frame(t):
    p = subprocess.run(["ffmpeg","-loglevel","error","-ss",str(t),"-i",path,"-frames:v","1","-f","rawvideo","-pix_fmt","gray","-"],capture_output=True)
    d = np.frombuffer(p.stdout, np.uint8)
    return d.reshape(-1, W) if d.size else None
def lines(img):
    mask = img > 215
    rows = np.where(mask.sum(1) >= 2)[0]
    if rows.size == 0: return []
    groups = []; s = p = rows[0]
    for r in rows[1:]:
        if r - p > max(3, H//90): groups.append((s, p)); s = r
        p = r
    groups.append((s, p))
    res = []
    for a, b in groups:
        cols = np.where(mask[a:b+1].sum(0) > 0)[0]
        res.append(dict(top=int(a), bot=int(b), h=int(b-a+1), left=int(cols[0]), right=int(cols[-1]), w=int(cols[-1]-cols[0]+1)))
    return res
out = {}
for t in sys.argv[3:]:
    img = frame(float(t))
    if img is None: out[t] = None; continue
    if img.shape[0] != H: out[t] = "altura %d" % img.shape[0]; continue
    L = lines(img)
    out[t] = dict(n=len(L), lines=L, pct=[round(100*l["h"]/H, 2) for l in L],
                  pitch_pct=[round(100*(L[i+1]["top"]-L[i]["top"])/H, 2) for i in range(len(L)-1)],
                  bottom_margin_pct=round(100*(H-1-L[-1]["bot"])/H, 2) if L else None)
print(json.dumps(out))
