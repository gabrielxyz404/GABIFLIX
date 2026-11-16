#!/usr/bin/env python3
"""Áudio de teste de ~90 s: frases do espeak-ng (fala sintética) + pausas + ruído, 16 kHz mono. Uso: mktestaudio.py saida.wav"""
import subprocess, sys, wave, numpy as np, tempfile, os
phr = ["The quick brown fox jumps over the lazy dog.", "I told you we should never have come to this place.", "Where were you last night when the lights went out?",
       "It is a long way to the station and the rain has not stopped.", "Give me the keys and nobody will get hurt.", "She said the answer was in the letter all along.",
       "We have to leave before the sun comes up.", "You do not understand what is at stake here."]
rng = np.random.default_rng(3); chunks = []
for k in range(14):
    p = phr[k % len(phr)]
    fd, tmp = tempfile.mkstemp(suffix=".wav"); os.close(fd)
    subprocess.run(["espeak-ng", "-v", "en-us", "-s", str(130 + 12 * (k % 4)), "-w", tmp, p], check=True)
    r = tmp + ".16.wav"
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", tmp, "-ac", "1", "-ar", "16000", r], check=True)
    w = wave.open(r); x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32); w.close()
    chunks.append(x + rng.normal(0, 150, len(x)))
    chunks.append(rng.normal(0, 400 if k % 3 == 0 else 120, int(16000 * (1.5 + 3 * rng.random()))))
y = np.concatenate(chunks)
y = np.clip(y, -32768, 32767).astype("<i2")
w = wave.open(sys.argv[1], "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(y.tobytes()); w.close()
print("duração %.1f s" % (len(y) / 16000))
