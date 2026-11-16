#!/usr/bin/env python3
"""Compara dois arquivos float32 de probabilidades (referência Python x Mac/Android). Uso: compare.py ref.f32 outro.f32 [rotulo]"""
import sys, numpy as np
r = np.fromfile(sys.argv[1], "<f4"); o = np.fromfile(sys.argv[2], "<f4"); n = min(len(r), len(o))
d = np.abs(r[:n] - o[:n])
print("PARIDADE %s: quadros ref=%d outro=%d maxdiff=%.5f meandiff=%.6f discordam(>0.5)=%d de %d (falantes ref=%d)" % (
    sys.argv[3] if len(sys.argv) > 3 else "", len(r), len(o), d.max(), d.mean(), int(((r[:n] > .5) != (o[:n] > .5)).sum()), n, int((r > .5).sum())))
