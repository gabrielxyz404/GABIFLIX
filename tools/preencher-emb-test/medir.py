import sys, glob, os
from PIL import Image

out = sys.argv[1]
# texto branco puro (o fundo do vídeo é preto): caixa do texto = pixels com R,G,B >= 235
print("foto | caixa do texto (x0..x1, y0..y1) | largura em % de 1920 | encosta na borda?")
for modo in ("normal", "preencher"):
    for f in sorted(glob.glob(os.path.join(out, modo + "-*.png")), key=lambda p: int(p.rsplit("-", 1)[1].split(".")[0])):
        im = Image.open(f).convert("RGB")
        w, h = im.size
        px = im.load()
        x0, x1, y0, y1 = w, -1, h, -1
        cnt = 0
        for y in range(0, h, 2):
            for x in range(0, w, 2):
                r, g, b = px[x, y]
                if r >= 235 and g >= 235 and b >= 235:
                    cnt += 1
                    x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        if cnt < 30:
            print(os.path.basename(f), "| sem texto branco (entre falas?)")
            continue
        borda = "SIM (cortado)" if x0 <= 3 or x1 >= w - 4 else "não"
        print(os.path.basename(f), "| %d..%d, %d..%d | %.1f%% | %s" % (x0, x1, y0, y1, 100.0 * (x1 - x0) / w, borda))
