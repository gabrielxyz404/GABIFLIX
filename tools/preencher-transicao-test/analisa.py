#!/usr/bin/env python3
"""Analisa as gravações do teste da transição do Preencher (sem depender de relógios: só do que aparece nos quadros).
uso: analisa.py <pasta> <antes|depois> <tel|tv> <video>
Cada quadro (10 por segundo) é medido: espessura das faixas pretas (fração da altura/largura) em cima, embaixo, à esquerda e à direita.
Os quadros são divididos em "patamares" (estados estáveis por pelo menos 0,8 s) e "quadros de troca" (os que ficam entre dois patamares).
Falha (código 1) se algum patamar tiver imagem deslocada (faixa de cima muito diferente da de baixo, ou esquerda da direita) ou se a troca
entre dois patamares precisar de mais de 3 quadros (0,3 s). Escreve <pasta>/<rotulo>-<perfil>-<video>-analise.json."""
import sys, subprocess, json
import numpy as np

W = 640
BLACK = 12
TOL = 0.012
MIN_RUN = 8
ASSIM = 0.04
MAX_TROCA = 3


def frames(mp4):
    pr = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", mp4], capture_output=True, text=True).stdout.strip().split(",")
    ow, oh = int(pr[0]), int(pr[1])
    H = int(round(W * oh / ow / 2.0)) * 2
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", mp4, "-vf", f"fps=10,scale={W}:{H},format=gray", "-f", "rawvideo", "-"], capture_output=True).stdout
    n = len(raw) // (W * H)
    return np.frombuffer(raw[: n * W * H], dtype=np.uint8).reshape(n, H, W)


def medir(f):
    H = f.shape[0]
    rows = f.mean(axis=1) < BLACK
    cols = f[H // 4: 3 * H // 4].mean(axis=0) < BLACK
    def lead(a):
        n = 0
        while n < len(a) and a[n]: n += 1
        return n
    return np.array([lead(rows) / H, lead(rows[::-1]) / H, lead(cols) / W, lead(cols[::-1]) / W])


def main():
    d, rot, perfil, video = sys.argv[1:5]
    pref = f"{d}/{rot}-{perfil}-{video}"
    fr = frames(pref + ".mp4")
    m = np.array([medir(f) for f in fr])
    # patamares: sequências de quadros com medida igual (dentro de TOL) à do começo da sequência
    runs = []; i = 0
    while i < len(m):
        j = i + 1
        while j < len(m) and np.abs(m[j] - m[i]).max() <= TOL: j += 1
        runs.append((i, j)); i = j
    plat = [(a, b) for a, b in runs if b - a >= MIN_RUN]
    # funde patamares vizinhos com o mesmo estado (um quadro solto no meio não os separa)
    merged = []
    for a, b in plat:
        if merged and np.abs(np.median(m[merged[-1][0]:merged[-1][1]], axis=0) - np.median(m[a:b], axis=0)).max() <= TOL: merged[-1] = (merged[-1][0], b)
        else: merged.append((a, b))
    out = {"rotulo": rot, "perfil": perfil, "video": video, "quadros": len(fr), "patamares": [], "trocas": []}
    ok = True
    for a, b in merged:
        s = np.median(m[a:b], axis=0)
        assim = abs(s[0] - s[1]) > ASSIM or abs(s[2] - s[3]) > ASSIM
        out["patamares"].append({"de_s": round(a / 10, 1), "ate_s": round(b / 10, 1), "cima_baixo_esq_dir": [round(float(x), 3) for x in s], "deslocado": bool(assim)})
        if assim: ok = False
    for (a1, b1), (a2, b2) in zip(merged, merged[1:]):
        gap = range(b1, a2)
        # quadros entre dois patamares, mais os quadros do começo do patamar seguinte que ainda não são o estado final
        s2 = np.median(m[a2:b2], axis=0)
        ini = b1
        n = a2 - b1
        t = {"depois_de_s": round(b1 / 10, 1), "quadros_de_troca": n, "assimetricos": int(sum(1 for k in gap if abs(m[k][0] - m[k][1]) > ASSIM or abs(m[k][2] - m[k][3]) > ASSIM)), "ok": n <= MAX_TROCA}
        if not t["ok"]: ok = False
        out["trocas"].append(t)
    out["ok"] = ok
    print(json.dumps(out, ensure_ascii=False, indent=1))
    json.dump(out, open(pref + "-analise.json", "w"), ensure_ascii=False, indent=1)
    sys.exit(0 if ok else 1)


main()
