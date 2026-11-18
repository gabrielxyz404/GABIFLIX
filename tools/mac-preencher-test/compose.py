# Fotografias "simuladas" do que a janela 16:10 mostra (o GitHub não tem placa gráfica, o VLC não desenha imagem lá):
# o ffmpeg aplica o recorte que o app calculou (lido do log) ao vídeo de teste, ajusta à janela e a legenda é desenhada por cima,
# como o VLC faz (legenda desenhada na imagem já recortada). "antes" = recorte só pela proporção da janela sobre o quadro inteiro (app 1.0.30).
# uso: compose.py LOGS VIDEOS SAIDA
import os, re, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image
import subburn
logs, vids, out = sys.argv[1:4]
os.makedirs(out, exist_ok=True)
WW, WH = 960, 600
SRT = vids + "/leg.srt"
T = 17.0   # dentro da 3.ª legenda (15 s a 20,2 s)
frames = {"barra": (1920, 1080), "wide": (1920, 800), "hd": (1920, 1080), "pilar": (1920, 1080)}
active = {"barra": (1920, 804), "wide": (1920, 800), "hd": (1920, 1080), "pilar": (1440, 1080)}   # imagem real, sem barras gravadas
def grab(video, vf, dst):
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(T), "-i", video, "-vf", vf, "-frames:v", "1", "-update", "1", dst], capture_output=True, text=True)
    if r.returncode: print("ffmpeg:", r.stderr[:400])
    return Image.open(dst).convert("RGB")
lines = []
for v in ["barra", "wide", "hd", "pilar"]:
    video = "%s/%s.mkv" % (vids, v)
    fw, fh = frames[v]; aw, ah = active[v]
    crop = None; bars = ""
    try:
        for l in open("%s/%s-B-subs1-player.log" % (logs, v), encoding="utf-8", errors="replace"):
            m = re.search(r"tela: Preencher 960:600 recorte=(\S+) barras=(\S+)", l)
            if m: crop, bars = m.group(1), m.group(2)
    except Exception as e: print(e)
    tmp = "%s/tmp-%s.png" % (out, v)
    text = subburn.cue_at(SRT, T)
    # Normal: o quadro inteiro ajustado à janela (a legenda fica dentro da imagem, não nas barras da janela)
    img = grab(video, "scale=%d:-2" % WW, tmp); img, _ = subburn.burn(img, text)
    canvas = Image.new("RGB", (WW, WH), (0, 0, 0)); canvas.paste(img, (0, (WH - img.size[1]) // 2)); canvas.save("%s/%s-1-normal.png" % (out, v))
    # antes (1.0.30): recorte centrado sobre o quadro inteiro, só pela proporção da janela
    img = grab(video, "crop=w='min(iw,ih*%d/%d)':h='min(ih,iw*%d/%d)',scale=%d:%d" % (WW, WH, WH, WW, WW, WH), tmp)
    img, _ = subburn.burn(img, text); img.save("%s/%s-2-preencher-antes.png" % (out, v))
    sn = min(WW / fw, WH / fh)
    if crop and "x" in crop and "+" in crop:
        p = crop.split("+"); w, h = [int(x) for x in p[0].split("x")]
        img = grab(video, "crop=%d:%d:%s:%s,scale=%d:%d" % (w, h, p[1], p[2], WW, WH), tmp)
        img, box = subburn.burn(img, text); img.save("%s/%s-3-preencher-depois.png" % (out, v))
        zd = (WW / w) / sn
        zb = (WW / min(fw, fh * WW / WH)) / sn
        lines.append("%s: quadro %dx%d, imagem real %dx%d, barras medidas (cima/baixo/esq/dir) %s, recorte %s, zoom depois %.3f (antes %.3f) vs Normal; legenda %s dentro de %dx%d" % (v, fw, fh, aw, ah, bars, crop, zd, zb, box, WW, WH))
    else:
        lines.append("%s: sem recorte no log (%s)" % (v, crop))
    os.remove(tmp)
open(out + "/resumo.txt", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
