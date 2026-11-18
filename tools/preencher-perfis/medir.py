#!/usr/bin/env python3
"""Mede nas fotos (Normal e Preencher) o tamanho real da imagem na tela e compara com o modelo.
uso: medir.py PASTA PERFIL   (fotos: depois-PERFIL-VIDEO-normal.png / -preencher.png)
Só usa o tamanho da própria foto: nenhum tamanho de aparelho no código."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image
import modelo

pasta, perfil = sys.argv[1], sys.argv[2]
ZOOM = 1.10
ASP = {"barra": (16 / 9, 1.0, 804 / 1080), "wide": (1920 / 804, 1.0, 1.0), "hd": (16 / 9, 1.0, 1.0), "pilar": (16 / 9, 0.75, 1.0), "c185": (1920 / 1038, 1.0, 1.0)}


def caixa(path):
    im = Image.open(path).convert("L")
    w, h = im.size
    px = im.load()
    rows = [y for y in range(h) if max(px[x, y] for x in range(0, w, 3)) > 30]
    cols = [x for x in range(w) if max(px[x, y] for y in range(0, h, 3)) > 30]
    if not rows or not cols:
        return w, h, None
    return w, h, (cols[0], cols[-1] + 1, rows[0], rows[-1] + 1)


def pct(a, b): return 100.0 * a / b


print("perfil %s: foto | tela | Normal: barras por lado (cima/baixo, esq/dir, %% da tela) | Preencher: barras por lado | zoom medido | modelo (zoom, barras)" % perfil)
for v in ("barra", "wide", "hd", "pilar", "c185"):
    n = os.path.join(pasta, "depois-%s-%s-normal.png" % (perfil, v))
    p = os.path.join(pasta, "depois-%s-%s-preencher.png" % (perfil, v))
    if not (os.path.exists(n) and os.path.exists(p)):
        print(v, "| sem fotos"); continue
    w, h, bn = caixa(n)
    _, _, bp = caixa(p)
    if bn is None or bp is None:
        print(v, "| sem imagem medida"); continue
    def barras(b):
        x0, x1, y0, y1 = b
        return (pct(y0, h), pct(h - y1, h), pct(x0, w), pct(w - x1, w))
    bn_, bp_ = barras(bn), barras(bp)
    # zoom medido: quando sobra barra em cima/baixo a altura da imagem mostra o zoom; senão, a largura (a imagem cobre a tela e foi cortada: usa o modelo)
    alt_n, alt_p = bn[3] - bn[2], bp[3] - bp[2]
    larg_n, larg_p = bn[1] - bn[0], bp[1] - bp[0]
    if alt_p < h - 4 and alt_n < h - 4: zm = alt_p / alt_n
    elif larg_p < w - 4 and larg_n < w - 4: zm = larg_p / larg_n
    else: zm = None
    f, v0, h0, v1, h1, ch, cv = modelo.linha(w, h, ASP[v][0], ASP[v][1], ASP[v][2], ZOOM)
    print("%s | %dx%d | N: %.1f%%/%.1f%% %.1f%%/%.1f%% | P: %.1f%%/%.1f%% %.1f%%/%.1f%% | zoom medido %s | modelo %.3fx barras %.1f%% (cima/baixo) %.1f%% (lados) corte %.1f%% lados %.1f%% cima/baixo" % (
        v, w, h, bn_[0], bn_[1], bn_[2], bn_[3], bp_[0], bp_[1], bp_[2], bp_[3], ("%.3fx" % zm) if zm else "n/d (imagem cobre a tela)", f, v1, h1, ch, cv))
