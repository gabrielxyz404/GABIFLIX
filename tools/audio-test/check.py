#!/usr/bin/env python3
"""Lê o log de um caso e confere o AUDIO_RESULT. uso: check.py log nome esperado_ms|rejeitar [escala] [tol_ms]"""
import re, sys
log, name, exp = sys.argv[1], sys.argv[2], sys.argv[3]
scale = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] != '-' else None
tol = int(sys.argv[5]) if len(sys.argv) > 5 else 300
line = None
for l in open(log, encoding='utf-8', errors='replace'):
    if 'AUDIO_RESULT' in l: line = l
if not line:
    print('| %s | sem AUDIO_RESULT | - | - | - | - | FALHA |' % name); sys.exit(1)
g = dict(re.findall(r'(\w+)=(\S+)', line.split('AUDIO_RESULT', 1)[1]))
acc = g.get('accepted') == 'true'
off = int(g.get('offset', 0)); sc = float(g.get('scale', 1))
ok = False
if exp == 'rejeitar': ok = not acc
else:
    ok = acc and abs(off - int(exp)) <= tol and (scale is None or abs(sc - scale) < 1e-3)
rb = int(g.get('readBytes', 0)); eb = int(g.get('estBytes', 0))
print('| %s | esperado %s | recuperado %s (escala %s, %s/%s janelas) | %s s | lido %s MB (est. %s MB) | %s | buffering %s | %s |' % (
    name, exp, ('%+d ms' % off) if acc else 'desistiu', g.get('scale'), g.get('agree'), g.get('used'), int(g.get('tookMs', 0)) // 1000,
    rb // 1048576, eb // 1048576, g.get('mode'), g.get('bufferEvents'), 'OK' if ok else 'FALHA'))
sys.exit(0 if ok else 1)
