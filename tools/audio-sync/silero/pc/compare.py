#!/usr/bin/env python3
"""Compara dois arquivos float32 de probabilidades (referência Python x Mac/Android), sem numpy. Uso: compare.py ref.f32 outro.f32 [rotulo]"""
import array, sys
def rd(p):
    a = array.array("f"); a.frombytes(open(p, "rb").read()); return a
r, o = rd(sys.argv[1]), rd(sys.argv[2]); n = min(len(r), len(o))
d = [abs(r[i] - o[i]) for i in range(n)]
dis = sum(1 for i in range(n) if (r[i] > .5) != (o[i] > .5))
print("PARIDADE %s: quadros ref=%d outro=%d maxdiff=%.5f meandiff=%.6f discordam(>0.5)=%d de %d (falantes ref=%d)" % (
    sys.argv[3] if len(sys.argv) > 3 else "", len(r), len(o), max(d), sum(d) / n, dis, n, sum(1 for x in r if x > .5)))
