#!/usr/bin/env python3
"""Monta as folhas de contato das trocas de modo.
uso: folha.py <pasta do CI> <rotulo> <perfil> <video> <pasta de saida>
Para cada troca achada por analisa.py (fim de um patamar) salva 24 quadros (10 por segundo, de 0,3 s antes até 2 s depois) e uma folha
com eles (montage do ImageMagick); da primeira troca também uma folha "longa" (um quadro a cada 0,5 s, até 8 s depois)."""
import sys, os, json, subprocess, glob

d, rot, perfil, video, out = sys.argv[1:6]
pref = f"{d}/{rot}-{perfil}-{video}"
a = json.load(open(pref + "-analise.json"))
os.makedirs(out, exist_ok=True)
pats = a["patamares"]
W = 320 if perfil == "tel" else 320


def extrai(t0, dur, fps, pasta):
    # mesma contagem de quadros da análise (fps=10 desde o começo): o quadro n é o instante n/10 s
    os.makedirs(pasta, exist_ok=True)
    for f in glob.glob(pasta + "/q*.png"): os.remove(f)
    a0 = max(0, int(round(t0 * 10))); a1 = a0 + int(round(dur * 10)) - 1
    passo = 10 // fps
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", pref + ".mp4", "-vf", f"fps=10,select='between(n\\,{a0}\\,{a1})*not(mod(n-{a0}\\,{passo}))',scale=960:-1", "-vsync", "vfr", pasta + "/q%02d.png"], check=True)
    return sorted(glob.glob(pasta + "/q*.png"))


def folha(frames, t0, passo, destino):
    labs = []
    for i, f in enumerate(frames):
        lab = f"{t0 + i * passo:+.1f}s"
        labs += ["-label", lab, f]
    subprocess.run(["montage"] + labs + ["-tile", "8x", "-geometry", f"{W}x+3+3", "-background", "#333", "-fill", "white", "-pointsize", "14", destino], check=True)


n = 0
for i in range(len(pats) - 1):
    b = pats[i]["ate_s"]
    espessura_antes = sum(pats[i]["cima_baixo_esq_dir"][:2]); espessura_depois = sum(pats[i + 1]["cima_baixo_esq_dir"][:2])
    modo = "preencher" if espessura_depois < espessura_antes else "normal"
    n += 1
    base = f"{perfil}-{video}-troca{n}-{modo}"
    fr = extrai(b - 0.5, 2.5, 10, f"{out}/{base}-quadros")
    folha(fr, -0.5, 0.1, f"{out}/contato-{base}.png")
    if n == 1:
        fl = extrai(b - 0.5, 8.5, 2, f"{out}/{base}-longo")
        folha(fl, -0.5, 0.5, f"{out}/contato-{base}-longo.png")
print("ok", n, "trocas")
