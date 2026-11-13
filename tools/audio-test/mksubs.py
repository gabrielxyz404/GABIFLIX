#!/usr/bin/env python3
"""Gera legendas de teste a partir do SRT oficial: wrong.srt (mesmas durações/pausas em outra ordem = "outro filme") e scaled.srt (tempos x 23.976/25, como a legenda de outro fps)."""
import re, random, sys
src, outdir, pre = sys.argv[1], sys.argv[2], sys.argv[3]
t = open(src, encoding='utf-8-sig').read().replace('\r\n', '\n')
blocks = [b for b in re.split(r'\n\s*\n', t.strip()) if '-->' in b]
def ms(s):
    h, m, r = s.split(':'); sec, f = r.split(',')
    return ((int(h) * 60 + int(m)) * 60 + int(sec)) * 1000 + int(f)
def ts(n):
    n = max(0, int(round(n)))
    return '%02d:%02d:%02d,%03d' % (n // 3600000, n // 60000 % 60, n // 1000 % 60, n % 1000)
cues = []
for b in blocks:
    ls = b.split('\n')
    i = [k for k, l in enumerate(ls) if '-->' in l][0]
    a, c = [x.strip() for x in ls[i].split('-->')]
    cues.append((ms(a), ms(c), '\n'.join(ls[i + 1:])))
print('falas:', len(cues), 'última termina em', cues[-1][1] / 1000, 's')
def write(name, cs):
    with open(outdir + '/' + name, 'w', encoding='utf-8') as f:
        for k, (a, b, tx) in enumerate(cs, 1):
            f.write('%d\n%s --> %s\n%s\n\n' % (k, ts(a), ts(b), tx))
# scaled: tempo na legenda = tempo do vídeo * 23.976/25
k = 23.976 / 25
write('scaled-' + pre + '.srt', [(a * k, b * k, tx) for a, b, tx in cues])
# wrong: durações e pausas embaralhadas
rnd = random.Random(12345)
durs = [b - a for a, b, _ in cues]
gaps = [cues[i + 1][0] - cues[i][1] for i in range(len(cues) - 1)]
rnd.shuffle(durs); rnd.shuffle(gaps)
t0 = cues[0][0]
w = []
cur = t0
for i, d in enumerate(durs):
    w.append((cur, cur + d, cues[i][2]))
    cur += d + (gaps[i] if i < len(gaps) else 0)
pass
