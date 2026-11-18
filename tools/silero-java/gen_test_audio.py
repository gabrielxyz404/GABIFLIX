#!/usr/bin/env python3
"""Gera um áudio de teste (16 kHz mono s16) para a paridade: fala sintética (flite, 4 vozes), silêncio, ruído branco, ruído rosa em rajadas,
tons/"música", fala velha (passa-banda, chiado, estalos), fala baixinha e fala estourada. Uso: gen_test_audio.py saida.wav [segundos]"""
import subprocess, sys, wave, tempfile, os
import numpy as np

out = sys.argv[1]
target_s = float(sys.argv[2]) if len(sys.argv) > 2 else 120.0
SR = 16000
rng = np.random.default_rng(1234)
TEXTS = [
    "the quick brown fox jumps over the lazy dog, and then he sat down to think about what he had just done.",
    "good evening ladies and gentlemen, tonight we bring you a story of love and betrayal in the old city.",
    "i told you not to go there, but you never listen to me, do you? well, now it is too late for that.",
    "one two three four five six seven eight nine ten, and after ten comes eleven twelve thirteen fourteen.",
    "the detective lit a cigarette and looked out of the window at the rain falling on the empty street below.",
]
VOICES = ["slt", "awb", "kal16", "rms"]


def flite(text, voice):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        p = f.name
    subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "flite=text='%s':voice=%s" % (text, voice),
                    "-ar", str(SR), "-ac", "1", "-c:a", "pcm_s16le", p], check=True)
    w = wave.open(p); a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32); w.close(); os.remove(p)
    return a


def bandpass_old(a):
    """som de filme antigo: passa-banda 300-3400 Hz, chiado, estalos"""
    n = len(a); sp = np.fft.rfft(a); f = np.fft.rfftfreq(n, 1 / SR)
    sp[(f < 300) | (f > 3400)] *= 0.05
    y = np.fft.irfft(sp, n).astype(np.float32)
    y += rng.normal(0, 600, n).astype(np.float32)
    for _ in range(max(1, n // 8000)):
        i = rng.integers(0, n - 40); y[i:i + 30] += rng.normal(0, 5000, 30)
    return y


def pink(n, amp):
    w = rng.normal(0, 1, n); sp = np.fft.rfft(w); f = np.fft.rfftfreq(n, 1 / SR); f[0] = 1
    y = np.fft.irfft(sp / np.sqrt(f), n); return (y / np.abs(y).max() * amp).astype(np.float32)


segs = []
t = 0.0
i = 0
while t < target_s:
    kind = i % 9
    text, voice = TEXTS[i % len(TEXTS)], VOICES[i % len(VOICES)]
    if kind == 0: s = flite(text, voice) * 0.9
    elif kind == 1: s = np.zeros(int(SR * rng.uniform(1, 3)), np.float32) + rng.normal(0, 3, int(SR * 2)).astype(np.float32)[:1].repeat(1)[0]
    elif kind == 2: s = bandpass_old(flite(text, voice))
    elif kind == 3: s = rng.normal(0, 4000, int(SR * rng.uniform(1.5, 4))).astype(np.float32)
    elif kind == 4: s = flite(text, voice) * 0.08
    elif kind == 5:
        n = int(SR * 3); tt = np.arange(n) / SR
        s = (6000 * np.sin(2 * np.pi * 220 * tt) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.7 * tt)) + 3000 * np.sin(2 * np.pi * 330 * tt)).astype(np.float32)
    elif kind == 6: s = np.clip(flite(text, voice) * 6.0, -32768, 32767)
    elif kind == 7: s = pink(int(SR * 3), 9000) * (np.arange(int(SR * 3)) % 8000 < 3000)
    else: s = flite(text, voice) + pink(len(flite(text, voice)), 2500)
    segs.append(s.astype(np.float32)); t += len(s) / SR; i += 1
a = np.concatenate(segs)[: int(target_s * SR)]
a = np.clip(a, -32768, 32767).astype("<i2")
w = wave.open(out, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(a.tobytes()); w.close()
print("gerado %s: %.1f s" % (out, len(a) / SR))
