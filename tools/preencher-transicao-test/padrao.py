#!/usr/bin/env python3
# Imagem de teste 1920x804 (2,39:1): círculo vermelho e quadrado verde do mesmo tamanho (360 px) sobre cinza, com grade fina.
# Se algum caminho esticar a imagem, o círculo deixa de ser redondo e o quadrado deixa de ser quadrado.
import sys
from PIL import Image, ImageDraw
im = Image.new("RGB", (1920, 804), (96, 96, 120))
d = ImageDraw.Draw(im)
for x in range(0, 1920, 120): d.line([(x, 0), (x, 803)], fill=(150, 150, 170), width=2)
for y in range(0, 804, 134): d.line([(0, y), (1919, y)], fill=(150, 150, 170), width=2)
d.ellipse([640 - 180, 402 - 180, 640 + 180, 402 + 180], fill=(255, 0, 0))
d.rectangle([1280 - 180, 402 - 180, 1280 + 180, 402 + 180], fill=(0, 255, 0))
im.save(sys.argv[1])
