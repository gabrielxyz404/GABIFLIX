#!/usr/bin/env python3
"""Lê o screencap cru (stdin) e imprime a fração de pixels "acesos" no meio da tela (sem a faixa da legenda embaixo e sem as bordas).
Vídeo preto = perto de 0. O filme de teste (testsrc2) é colorido: passa de 0.30 com folga."""
import sys, struct
d = sys.stdin.buffer.read()
if len(d) < 16:
    print('0.000 vazio'); sys.exit(0)
w, h = struct.unpack('<II', d[:8])
hdr = len(d) - w * h * 4
if hdr < 12 or hdr > 64:
    print('0.000 formato'); sys.exit(0)
px = d[hdr:]
lit = tot = 0
for y in range(int(h * 0.15), int(h * 0.70), 6):
    row = y * w * 4
    for x in range(int(w * 0.10), int(w * 0.90), 6):
        o = row + x * 4
        if px[o] + px[o + 1] + px[o + 2] > 90:
            lit += 1
        tot += 1
print('%.3f %dx%d' % (lit / max(tot, 1), w, h))
