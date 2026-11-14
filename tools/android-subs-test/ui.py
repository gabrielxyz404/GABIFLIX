#!/usr/bin/env python3
# Ajuda do teste: lê o dump do uiautomator (stdin) e responde.
#   ui.py xy "texto"    -> "x y" do centro do elemento (texto exato; senão contém) ou vazio
#   ui.py nav "texto"   -> HERE | tecla DPAD_* a apertar para chegar no elemento | NONE
#   ui.py focus         -> texto do elemento com foco
#   ui.py texts         -> todos os textos visíveis, um por linha
import sys, re
import xml.etree.ElementTree as ET
raw = sys.stdin.read()
i = raw.find('<?xml')
try:
    root = ET.fromstring(raw[i:raw.rfind('</hierarchy>') + 12])
except Exception:
    print('' if sys.argv[1] == 'xy' else 'NONE'); sys.exit(0)
nodes = []
for n in root.iter('node'):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
    if not m: continue
    x1, y1, x2, y2 = map(int, m.groups())
    nodes.append((n.get('text', '') or n.get('content-desc', ''), (x1 + x2) // 2, (y1 + y2) // 2, n.get('focused') == 'true', n.get('clickable') == 'true' or n.get('focusable') == 'true'))
mode = sys.argv[1]
def find(t):
    t = t.lower()
    ex = [n for n in nodes if n[0].lower() == t]
    if ex: return ex[0]
    co = [n for n in nodes if t in n[0].lower() and n[0]]
    return co[0] if co else None
if mode == 'texts':
    for n in nodes:
        if n[0]: print(n[0].replace('\n', ' / '))
elif mode == 'focus':
    f = [n for n in nodes if n[3]]
    print(f[-1][0].replace('\n', ' / ') if f else '')
elif mode == 'xy':
    n = find(sys.argv[2]); print('%d %d' % (n[1], n[2]) if n else '')
elif mode == 'nav':
    t = find(sys.argv[2])
    if not t: print('NONE'); sys.exit(0)
    f = [n for n in nodes if n[3]]
    if not f: print('DPAD_DOWN'); sys.exit(0)
    f = f[-1]
    if f[0].lower() == t[0].lower() and f[1] == t[1] and f[2] == t[2]: print('HERE'); sys.exit(0)
    dx, dy = t[1] - f[1], t[2] - f[2]
    if abs(dy) >= abs(dx) * 0.6 and abs(dy) > 8: print('DPAD_DOWN' if dy > 0 else 'DPAD_UP')
    else: print('DPAD_RIGHT' if dx > 0 else 'DPAD_LEFT')
