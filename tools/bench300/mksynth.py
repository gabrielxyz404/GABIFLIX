#!/usr/bin/env python3
"""Titulo SINTETICO de controle (fala flite com tempos conhecidos + legenda exata): serve para provar a bancada/o motor sem rede.
Uso: mksynth.py SAIDA_DIR [minutos=15] [seed=7]  -> SAIDA_DIR/audio16k.wav, SAIDA_DIR/certa.srt"""
import os, random, subprocess, sys, tempfile, wave
import numpy as np

out = sys.argv[1]
minutes = float(sys.argv[2]) if len(sys.argv) > 2 else 15
seed = int(sys.argv[3]) if len(sys.argv) > 3 else 7
os.makedirs(out, exist_ok=True)
SR = 16000
rnd = random.Random(seed)
rng = np.random.default_rng(seed)
TEXTS = [
    "i told you not to go there but you never listen to me",
    "good evening ladies and gentlemen tonight we bring you a story",
    "the detective lit a cigarette and looked out of the window",
    "where were you on the night of the fourteenth",
    "that is not what i meant and you know it very well",
    "we have to leave this city before the morning comes",
    "please sit down and tell me everything from the beginning",
    "nobody in this town will ever believe a word you say",
    "i have been waiting for this moment for twenty long years",
    "what do you want from me after all that has happened",
    "he never came back and nobody ever asked why",
    "bring the car around to the front and keep the engine running",
]
VOICES = ["slt", "awb", "kal16", "rms"]


def flite(text, voice):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        p = f.name
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "flite=text='%s':voice=%s" % (text, voice), "-ar", str(SR), "-ac", "1", "-c:a", "pcm_s16le", p], check=True)
    w = wave.open(p)
    a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    w.close()
    os.remove(p)
    return a


clips = {(t, v): flite(t, v) for t in TEXTS for v in VOICES}
total = int(minutes * 60 * SR)
y = rng.normal(0, 40, total).astype(np.float32)   # chiado de fundo
t = rnd.uniform(20, 40)
cues = []
while True:
    txt = rnd.choice(TEXTS)
    c = clips[(txt, rnd.choice(VOICES))]
    s0 = int(t * SR)
    if s0 + len(c) >= total - 5 * SR:
        break
    y[s0:s0 + len(c)] += c * rnd.uniform(0.5, 1.0)
    cues.append((int(t * 1000), int((t + len(c) / SR) * 1000), txt))
    t += len(c) / SR + rnd.uniform(0.6, 6.0) + (rnd.uniform(15, 60) if rnd.random() < 0.08 else 0)
y = np.clip(y, -32768, 32767).astype(np.int16)
with wave.open(os.path.join(out, "audio16k.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(y.tobytes())


def fmt(v):
    return "%02d:%02d:%02d,%03d" % (v // 3600000, v // 60000 % 60, v // 1000 % 60, v % 1000)


with open(os.path.join(out, "certa.srt"), "w", encoding="utf-8") as f:
    for i, (a, b, tx) in enumerate(cues, 1):
        f.write("%d\n%s --> %s\n%s\n\n" % (i, fmt(a), fmt(b), tx))
print("sintetico: %d falas em %.1f min" % (len(cues), minutes))
