#!/usr/bin/env python3
# Sequência do player do Mac (iniciar, fotografias das barras, recorte por bordas, trocar o recorte, Normal/Preencher, mudar o tamanho da janela)
# sobre o libvlc 3.0.x com a saída OpenGL (--vout=gl) num Xvfb com mesa llvmpipe. Uso: harness.py <video> <saida> <antes|depois>
#   antes  = como o app 1.0.37 (fotografia com miniatura e OSD)   depois = com --no-snapshot-preview --no-osd --no-video-title-show
# Tira uma foto da tela (import -window root) em cada passo e mede: nº de manchas vermelhas (o círculo; 2 = há uma cópia), caixa do círculo e
# presença de texto/miniatura no canto superior esquerdo (pixels claros acima do topo da imagem).
import sys, os, time, subprocess, json, ctypes
import vlc
from PIL import Image
import numpy as np
from scipy import ndimage

video, out, modo = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out, exist_ok=True)
args = ["--vout=gl", "--no-audio", "--avcodec-hw=none", "--no-video-title-show" if modo == "depois" else "--video-title-show", "--quiet"]
if modo == "depois": args += ["--no-snapshot-preview", "--no-osd"]
inst = vlc.Instance(*args)
mp = inst.media_player_new()
mp.set_media(inst.media_new(video))
mp.play()
time.sleep(5)
log = []

def shot(nome):
    f = os.path.join(out, f"{modo}-{nome}.png")
    subprocess.run(["import", "-window", "root", f], check=False)
    a = np.asarray(Image.open(f).convert("RGB")).astype(int)
    red = (a[..., 0] > 200) & (a[..., 1] < 60) & (a[..., 2] < 60)
    lab, n = ndimage.label(red)
    sizes = ndimage.sum(red, lab, range(1, n + 1)) if n else []
    big = [i + 1 for i, s_ in enumerate(sizes) if s_ > 40]
    boxes = []
    for i in big:
        ys, xs = np.where(lab == i)
        boxes.append((int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())))
    # texto claro (branco) fora da imagem de teste (que é cinza-azulado escuro): pixels quase brancos
    white = (a[..., 0] > 235) & (a[..., 1] > 235) & (a[..., 2] > 235)
    r = dict(passo=nome, tela=f"{a.shape[1]}x{a.shape[0]}", manchas_vermelhas=len(big), caixas=boxes, pixels_brancos=int(white.sum()))
    log.append(r); print(json.dumps(r), flush=True)

def xdo(*c): subprocess.run(["xdotool", *c], check=False)
def win():
    r = subprocess.run(["xdotool", "search", "--onlyvisible", "--class", "vlc"], capture_output=True, text=True).stdout.split()
    return r[-1] if r else None
def snap(i):
    p = os.path.join(out, f"{modo}-fotografia-{i}.png")
    mp.video_take_snapshot(0, p, 192, 0)

W = win(); print("janela", W)
if W: xdo("windowmove", W, "0", "0", "windowsize", W, "960", "600")
time.sleep(2)
shot("01-tocando")
snap(1); time.sleep(0.8); shot("02-logo-apos-fotografia")
time.sleep(5); shot("03-5s-apos-fotografia")
# Preencher: recorte por bordas (esq+topo+dir+baixo), como o app 1.0.36+
mp.video_set_crop_geometry("46+0+45+0"); time.sleep(1.5); shot("04-recorte-1")
snap(2); time.sleep(0.8); shot("05-fotografia-com-recorte")
mp.video_set_crop_geometry("0+90+0+90"); time.sleep(1.5); shot("06-recorte-2")
mp.video_set_crop_geometry("91+0+0+0"); time.sleep(1.5); shot("07-recorte-3")
mp.video_set_crop_geometry(""); time.sleep(1.5); shot("08-normal")
mp.video_set_crop_geometry("46+0+45+0"); time.sleep(1.0); shot("09-preencher-de-novo")
if W:
    for (w, h) in ((1280, 720), (800, 500), (1280, 800)):
        xdo("windowsize", W, str(w), str(h)); time.sleep(0.2)
        mp.video_set_crop_geometry("46+0+45+0"); time.sleep(1.5); shot(f"10-janela-{w}x{h}")
snap(3); time.sleep(0.8); shot("11-fim")
mp.stop()
json.dump(log, open(os.path.join(out, f"{modo}-medidas.json"), "w"), indent=1)
