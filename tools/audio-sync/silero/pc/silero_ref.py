#!/usr/bin/env python3
"""Referência Python do Silero (igual ao corpus.py) -> float32 100 Hz. Uso: silero_ref.py modelo.onnx audio.wav saida.f32"""
import sys, wave, numpy as np, onnxruntime as ort
mp, wp, out = sys.argv[1:4]
w = wave.open(wp); assert w.getframerate() == 16000 and w.getnchannels() == 1
pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
n10 = len(pcm) // 160
so = ort.SessionOptions(); so.inter_op_num_threads = 1; so.intra_op_num_threads = 1
sess = ort.InferenceSession(mp, so, providers=["CPUExecutionProvider"])
names = [i.name for i in sess.get_inputs()]
print("entradas:", [(i.name, i.shape, i.type) for i in sess.get_inputs()], "saidas:", [o.name for o in sess.get_outputs()])
x = pcm.astype(np.float32) / 32768.0
nch = len(x) // 512
state = np.zeros((2, 1, 128), dtype=np.float32); ctx = np.zeros(64, dtype=np.float32); sr = np.array(16000, dtype=np.int64)
probs = np.zeros(nch, dtype=np.float32)
for i in range(nch):
    chunk = x[i * 512:(i + 1) * 512]
    o, state = sess.run(None, {names[0]: np.concatenate([ctx, chunk])[None, :], names[1]: state, names[2]: sr})
    ctx = chunk[-64:]; probs[i] = o[0][0]
t = (np.arange(n10) * 0.01) / 0.032
np.clip(np.interp(t, np.arange(nch) + 0.5, probs), 0, 1).astype("<f4").tofile(out)
print("frames", n10, "falantes>0.5", int((np.interp(t, np.arange(nch) + 0.5, probs) > 0.5).sum()))
