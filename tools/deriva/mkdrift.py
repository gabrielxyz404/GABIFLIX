#!/usr/bin/env python3
"""Deforma uma legenda SRT: tempo_novo = g * tempo + o (a mesma transformacao da bancada DerivaEval). Uso: mkdrift.py ENTRADA.srt SAIDA.srt G O_MS
G pode ser razao (ex.: 24/23.976)."""
import re, sys

src, out, g, o = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
if "/" in g:
    a, b = g.split("/")
    g = float(a) / float(b)
else:
    g = float(g)


def ms(t):
    h, m, s, z = re.match(r"(\d+):(\d+):(\d+)[,.](\d+)", t).groups()
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(z.ljust(3, "0")[:3])


def fmt(v):
    v = max(0, int(round(v)))
    return "%02d:%02d:%02d,%03d" % (v // 3600000, v // 60000 % 60, v // 1000 % 60, v % 1000)


text = open(src, encoding="utf-8-sig").read()
text = re.sub(r"(\d+:\d+:\d+[,.]\d+)\s*-->\s*(\d+:\d+:\d+[,.]\d+)",
              lambda m: "%s --> %s" % (fmt(g * ms(m.group(1)) + o), fmt(g * ms(m.group(2)) + o)), text)
open(out, "w", encoding="utf-8").write(text)
