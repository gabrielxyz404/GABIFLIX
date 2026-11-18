# "VLC" simulado para o teste sem placa gráfica: o app escreve o recorte atual em pedido.txt; aqui sai a fotografia (192 px) com o ffmpeg.
# uso: simsnap.py PASTA VIDEO MUNDO(A|B) LEGENDA.srt   (A = fotografia do quadro inteiro; B = só da parte recortada). SUBS=0 tira as legendas.
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image
import subburn
d, video, world, srt = sys.argv[1:5]
subs = os.environ.get("SUBS", "1") == "1"
k = 0
req, resp, tmp, full = d + "/pedido.txt", d + "/resposta.png", d + "/tmp.png", d + "/full.png"
while True:
    if not os.path.exists(req):
        time.sleep(0.05); continue
    time.sleep(0.05)
    crop = open(req).read().strip(); os.remove(req)
    t = 10 + 2.5 * k; k += 1
    vf = []
    if world == "B" and crop != "none":
        if "x" in crop and "+" in crop:
            p = crop.split("+")
            w, h = p[0].split("x"); vf.append("crop=%s:%s:%s:%s" % (w, h, p[1], p[2]))
        else:
            a, b = crop.split(":"); vf.append("crop=w='min(iw,ih*%s/%s)':h='min(ih,iw*%s/%s)'" % (a, b, b, a))
    vf.append("scale=iw:ih")
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(t), "-i", video, "-vf", ",".join(vf), "-frames:v", "1", "-update", "1", full],
                       capture_output=True, text=True)
    if r.returncode != 0: print("ffmpeg:", r.stderr[:300], flush=True); continue
    img = Image.open(full).convert("RGB")
    if subs: img, _ = subburn.burn(img, subburn.cue_at(srt, t))
    img = img.resize((192, max(2, round(192 * img.size[1] / img.size[0]))), Image.LANCZOS)
    img.save(tmp); os.rename(tmp, resp)
    print("pedido", crop, "t=%.1f" % t, flush=True)
