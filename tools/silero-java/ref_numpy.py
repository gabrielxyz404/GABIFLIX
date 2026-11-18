#!/usr/bin/env python3
"""Referência em numpy do Silero v5 (16 kHz), escrita a partir dos tensores ORIGINAIS do ONNX (disposição [saída][entrada][tap], conv do ONNX,
portas do PyTorch). Serve para provar, sem o ONNX Runtime, que a disposição exportada e o Java fazem a mesma conta; no CI a prova de verdade é
contra o ONNX Runtime (compare.py).

Uso: ref_numpy.py silero_vad.onnx audio16k.wav saida.f32     (probabilidades de 32 ms, uma por bloco de 512 amostras)
"""
import sys, os, wave
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_weights as ew


def conv1d(x, w, b, stride, pad):
    """x [Cin][T], w [Cout][Cin][K]"""
    xp = np.pad(x, ((0, 0), (pad, pad)))
    k = w.shape[2]
    tout = (xp.shape[1] - k) // stride + 1
    y = np.zeros((w.shape[0], tout), dtype=np.float32)
    for t in range(tout):
        seg = xp[:, t * stride:t * stride + k]
        y[:, t] = np.einsum("oik,ik->o", w, seg) + b
    return y


def sigmoid(v):
    return 1.0 / (1.0 + np.exp(-v))


def run(pcm_i16, t):
    x = pcm_i16.astype(np.float32) / 32768.0
    nch = len(x) // 512
    h = np.zeros(128, np.float32); c = np.zeros(128, np.float32)
    ctx = np.zeros(64, np.float32)
    out = np.zeros(nch, np.float32)
    basis = t["stft.forward_basis_buffer"]   # [258][1][256]
    for i in range(nch):
        chunk = x[i * 512:(i + 1) * 512]
        inp = np.concatenate([ctx, chunk])
        pad = np.pad(inp, (0, 64), mode="reflect")
        st = conv1d(pad[None, :], basis, np.zeros(258, np.float32), 128, 0)   # [258][4]
        mag = np.sqrt(st[:129] ** 2 + st[129:] ** 2)
        a = mag
        for l, (s, p) in enumerate([(1, 1), (2, 1), (2, 1), (1, 1)]):
            a = np.maximum(conv1d(a, t["encoder.%d.reparam_conv.weight" % l], t["encoder.%d.reparam_conv.bias" % l], s, p), 0)
        e = a[:, 0]
        g = t["decoder.rnn.weight_ih"] @ e + t["decoder.rnn.weight_hh"] @ h + t["decoder.rnn.bias_ih"] + t["decoder.rnn.bias_hh"]
        ig, fg, gg, og = sigmoid(g[:128]), sigmoid(g[128:256]), np.tanh(g[256:384]), sigmoid(g[384:])
        c = fg * c + ig * gg
        h = og * np.tanh(c)
        y = float(np.maximum(h, 0) @ t["decoder.decoder.2.weight"].reshape(128) + t["decoder.decoder.2.bias"][0])
        out[i] = sigmoid(y)
        ctx = chunk[-64:]
    return out


def read_wav(p):
    w = wave.open(p)
    assert w.getframerate() == 16000 and w.getnchannels() == 1
    return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)


if __name__ == "__main__":
    t = ew.load_branch(sys.argv[1])
    pr = run(read_wav(sys.argv[2]), t)
    pr.astype("<f4").tofile(sys.argv[3])
    print("blocos", len(pr), "média", float(pr.mean()), ">0.5:", int((pr > 0.5).sum()))
