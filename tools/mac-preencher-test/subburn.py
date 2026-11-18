# Desenha a legenda (a que está ativa no instante t do leg.srt) na parte de baixo de uma imagem, como o VLC faz sobre a imagem já recortada
# (o ffmpeg do GitHub não traz o filtro "subtitles"). Texto a ~4,6 % da altura (--sub-text-scale=55 do app), branco com contorno preto,
# quebra de linha pela largura da imagem. Devolve também a caixa do texto (para ver se fica dentro da imagem).
import re, textwrap
from PIL import Image, ImageDraw, ImageFont

FONTS = ["/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/System/Library/Fonts/Helvetica.ttc", "/Library/Fonts/Arial.ttf",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]

def cue_at(srt, t):
    for blk in re.split(r"\n\s*\n", open(srt, encoding="utf-8").read().strip()):
        ls = blk.strip().split("\n")
        if len(ls) < 3: continue
        m = re.match(r"(\d+):(\d+):(\d+),(\d+) --> (\d+):(\d+):(\d+),(\d+)", ls[1])
        if not m: continue
        a = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + int(m[4]) / 1000
        b = int(m[5]) * 3600 + int(m[6]) * 60 + int(m[7]) + int(m[8]) / 1000
        if a <= t <= b: return ls[2:]
    return None

def font(size):
    for f in FONTS:
        try: return ImageFont.truetype(f, size)
        except Exception: pass
    return ImageFont.load_default()

def burn(img, text, bottom_frac=0.06):
    """img: PIL RGB. Devolve (img, caixa) com a legenda desenhada; caixa = (x0, y0, x1, y1) do texto ou None."""
    if not text: return img, None
    W, H = img.size
    size = max(8, round(H * 0.046)); f = font(size)
    maxw = W * 0.94
    lines = []
    for src in text:   # cada linha da legenda é quebrada à parte (as quebras do ficheiro ficam)
        cur = ""
        for w in src.split():
            t = (cur + " " + w).strip()
            if f.getlength(t) <= maxw: cur = t
            else: lines.append(cur); cur = w
        if cur: lines.append(cur)
    lh = round(size * 1.2)
    y = H - round(H * bottom_frac) - lh * len(lines)
    d = ImageDraw.Draw(img)
    x0, x1 = W, 0
    for i, l in enumerate(lines):
        w = f.getlength(l); x = (W - w) / 2
        x0, x1 = min(x0, x), max(x1, x + w)
        d.text((x, y + i * lh), l, font=f, fill=(255, 255, 255), stroke_width=max(1, size // 14), stroke_fill=(0, 0, 0))
    return img, (round(x0), y, round(x1), y + lh * len(lines))
