#!/usr/bin/env python3
"""Áudio de teste de tempo: um bipe de 200 ms a cada segundo; frequência = 400 + 100 * (segundo mod 60)."""
import numpy as np, wave, sys
sr = 48000; dur = 800
x = np.zeros(sr * dur, dtype=np.float32)
t = np.arange(int(0.2 * sr)) / sr
env = np.minimum(1, np.minimum(t, 0.2 - t) / 0.005)
for s in range(dur):
    f = 400 + 50 * (s % 60)
    x[s * sr:s * sr + len(t)] = 0.8 * env * np.sin(2 * np.pi * f * t)
x += np.random.default_rng(1).normal(0, 0.003, x.shape).astype(np.float32)
w = wave.open(sys.argv[1], 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()); w.close()
