#!/usr/bin/env python3
"""Silero v5 no ONNX Runtime (Python), igual ao SileroVad antigo do app e ao corpus.py: blocos de 512 amostras com 64 de contexto e estado zerado.
Grava a probabilidade de cada bloco (31,25 Hz) e a versão de 100 Hz (np.interp, como o corpus). Uso: ort_raw.py modelo.onnx audio16k.wav bloco.f32 100hz.f32"""
import sys, wave
import numpy as np
import onnxruntime as ort

mp, wp, out_raw, out_100 = sys.argv[1:5]
w = wave.open(wp)
assert w.getframerate() == 16000 and w.getnchannels() == 1
pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
so = ort.SessionOptions(); so.inter_op_num_threads = 1; so.intra_op_num_threads = 1
sess = ort.InferenceSession(mp, so, providers=["CPUExecutionProvider"])
names = [i.name for i in sess.get_inputs()]
x = pcm.astype(np.float32) / 32768.0
nch = len(x) // 512
state = np.zeros((2, 1, 128), dtype=np.float32); ctx = np.zeros(64, dtype=np.float32); sr = np.array(16000, dtype=np.int64)
probs = np.zeros(nch, dtype=np.float32)
for i in range(nch):
    chunk = x[i * 512:(i + 1) * 512]
    o, state = sess.run(None, {names[0]: np.concatenate([ctx, chunk])[None, :], names[1]: state, names[2]: sr})
    ctx = chunk[-64:]
    probs[i] = o[0][0]
n10 = len(pcm) // 160
t = (np.arange(n10) * 0.01) / 0.032
np.clip(np.interp(t, np.arange(nch) + 0.5, probs), 0, 1).astype("<f4").tofile(out_100)
probs.astype("<f4").tofile(out_raw)
print("blocos", nch, "quadros100", n10, "fala>0.5:", int((probs > 0.5).sum()))
