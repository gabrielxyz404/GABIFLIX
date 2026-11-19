#!/usr/bin/env python3
"""Filme de teste de 12 min: video 1080p, 2 faixas de audio AC3 5.1 (fala sintetizada nas mesmas horas das falas da legenda) e legenda .srt CERTA.
Uso: mk-media.py PASTA"""
import os, subprocess, sys, wave
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
tmp = os.path.join(out, '_w'); os.makedirs(tmp, exist_ok=True)
phrases = ["Não, você não está entendendo isso, eu já disse que não vai dar.", "Então porque você está aqui? Eu estou com muito medo, tá bem?",
           "Obrigado, mas isso não é tudo, ele também tem uma filha.", "Nós já fomos até lá, meu amigo, e foi muito bem, sim.",
           "Ela vai ficar aqui, minha casa é dela também, pra mim tudo bem.", "Isso são coisas que você tem que saber, porque nós não temos tempo."]
DUR = int(os.environ.get('DUR', 720))   # segundos do filme (o teste de estresse usa 2100)
BV = os.environ.get('BV', '8M')
cues = []
t = 18.0
i = 0
while t + 6 < DUR - 10:
    cues.append((t, phrases[i % len(phrases)])); t += 10.5; i += 1
pcm = {}
SR = None
for k, ph in enumerate(phrases):
    f = os.path.join(tmp, 'p%d.wav' % k)
    subprocess.run(['espeak-ng', '-v', 'pt-br', '-s', '150', '-w', f, ph], check=True)
    w = wave.open(f); SR = w.getframerate(); pcm[ph] = w.readframes(w.getnframes()); w.close()
buf = bytearray(b'\0\0' * SR * DUR)
for (t, ph) in cues:
    d = pcm[ph]; o = int(t * SR) * 2
    buf[o:o + len(d)] = d[:len(buf) - o]
w = wave.open(os.path.join(tmp, 'fala.wav'), 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(bytes(buf)); w.close()

def ts(x):
    h = int(x // 3600); m = int(x % 3600 // 60); s = x % 60
    return ('%02d:%02d:%06.3f' % (h, m, s)).replace('.', ',')

with open(os.path.join(out, 'film.srt'), 'w') as f:
    for n, (t, ph) in enumerate(cues, 1):
        dur = len(pcm[ph]) / 2 / SR
        f.write('%d\n%s --> %s\n%s\n\n' % (n, ts(t), ts(t + dur + 0.2), ph))
print(len(cues), 'falas')
fala = os.path.join(tmp, 'fala.wav')
subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=24', '-i', fala, '-i', fala,
                '-t', str(DUR), '-map', '0:v', '-map', '1:a', '-map', '2:a', '-c:v', 'libx264', '-preset', 'ultrafast', '-b:v', BV, '-maxrate', BV, '-bufsize', '2M' if BV != '8M' else '16M',
                '-pix_fmt', 'yuv420p', '-c:a', 'ac3', '-ac', '6', '-ar', '48000', '-b:a', '384k', os.path.join(out, 'film.mkv')], check=True)
subprocess.run(['rm', '-rf', tmp])
