#!/usr/bin/env python3
"""Compara duas listas de probabilidades float32 (referência x Java): diferença máxima e média absoluta, e quantos quadros mudam de lado em 0,5.
Uso: compare.py ref.f32 java.f32 [limite=1e-3] [rotulo]   (sai com 1 se a diferença máxima passar do limite)"""
import sys
import numpy as np

ref = np.fromfile(sys.argv[1], "<f4").astype(np.float64)
jv = np.fromfile(sys.argv[2], "<f4").astype(np.float64)
lim = float(sys.argv[3]) if len(sys.argv) > 3 else 1e-3
tag = sys.argv[4] if len(sys.argv) > 4 else ""
if len(ref) != len(jv):
    print("PARIDADE %s: tamanhos diferentes (%d x %d)" % (tag, len(ref), len(jv)))
    sys.exit(1)
d = np.abs(ref - jv)
flip = int(((ref > 0.5) != (jv > 0.5)).sum())
print("PARIDADE %s: n=%d max=%.3e media=%.3e p99=%.3e cruzam0.5=%d limite=%.0e -> %s" % (tag, len(d), d.max(), d.mean(), np.percentile(d, 99), flip, lim, "OK" if d.max() <= lim else "FALHOU"))
sys.exit(0 if d.max() <= lim else 1)
