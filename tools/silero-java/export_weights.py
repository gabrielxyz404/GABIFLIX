#!/usr/bin/env python3
"""Exporta os pesos do Silero VAD v5 (ramo de 16 kHz do silero_vad.onnx) para um binário compacto que o Java puro lê.

Uso: export_weights.py silero_vad.onnx saida.bin

Só precisa de numpy (o ONNX é lido por onnx_mini.py, sem a biblioteca onnx). Formato (tudo float32 little-endian, sem alinhamento):
  cabeçalho: "SLV5" + int32 versão(1) + int32 nº de tensores
  para cada tensor, na ordem fixa abaixo: int32 nº de floats seguido dos floats
Ordem e disposição (já arrumadas para o Java: o eixo que se multiplica vem por último, contíguo):
  0 stft_basis   [258][256]      (129 reais + 129 imaginários; a janela já está embutida)
  1 enc0_w [128][3][129]  2 enc0_b [128]
  3 enc1_w [64][3][128]   4 enc1_b [64]
  5 enc2_w [64][3][64]    6 enc2_b [64]
  7 enc3_w [128][3][64]   8 enc3_b [128]
  9 lstm_wih [512][128]  10 lstm_whh [512][128]  11 lstm_b [512] (bias_ih + bias_hh)   (ordem das portas do PyTorch: i, f, g, o)
 12 dec_w [128]          13 dec_b [1]
O peso do conv do ONNX é [saída][entrada][tap]; aqui vai [saída][tap][entrada].
"""
import struct, sys, os
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import onnx_mini as om

NAMES = ["stft.forward_basis_buffer",
         "encoder.0.reparam_conv.weight", "encoder.0.reparam_conv.bias",
         "encoder.1.reparam_conv.weight", "encoder.1.reparam_conv.bias",
         "encoder.2.reparam_conv.weight", "encoder.2.reparam_conv.bias",
         "encoder.3.reparam_conv.weight", "encoder.3.reparam_conv.bias",
         "decoder.rnn.weight_ih", "decoder.rnn.weight_hh", "decoder.rnn.bias_ih", "decoder.rnn.bias_hh",
         "decoder.decoder.2.weight", "decoder.decoder.2.bias"]


def load_branch(path):
    """tensores do ramo 'then' (sr == 16000) do If de topo, por nome curto"""
    g = om.load(path)
    top = [n for n in g["nodes"] if n["op"] == "If"][0]
    then = top["attr"]["then_branch"]["g"]
    out = {}
    for n in then["nodes"]:
        if n["op"] != "Constant" or "value" not in n["attr"]:
            continue
        o = n["out"][0]
        for nm in NAMES:
            if o.endswith("__" + nm):
                out[nm] = n["attr"]["value"]["t"].astype(np.float32)
    miss = [nm for nm in NAMES if nm not in out]
    if miss:
        raise SystemExit("faltam tensores: %s" % miss)
    return out


def arranged(t):
    """lista de (nome, array float32 já na disposição do Java)"""
    conv = lambda w: np.ascontiguousarray(np.transpose(w, (0, 2, 1)))   # [out][in][tap] -> [out][tap][in]
    r = []
    r.append(("stft_basis", t["stft.forward_basis_buffer"].reshape(258, 256)))
    for i, (co, ci) in enumerate([(128, 129), (64, 128), (64, 64), (128, 64)]):
        w = t["encoder.%d.reparam_conv.weight" % i]
        assert w.shape == (co, ci, 3), w.shape
        r.append(("enc%d_w" % i, conv(w)))
        r.append(("enc%d_b" % i, t["encoder.%d.reparam_conv.bias" % i]))
    wih, whh = t["decoder.rnn.weight_ih"], t["decoder.rnn.weight_hh"]
    assert wih.shape == (512, 128) and whh.shape == (512, 128)
    r.append(("lstm_wih", wih))
    r.append(("lstm_whh", whh))
    r.append(("lstm_b", (t["decoder.rnn.bias_ih"] + t["decoder.rnn.bias_hh"]).astype(np.float32)))
    dw = t["decoder.decoder.2.weight"]
    assert dw.shape == (1, 128, 1)
    r.append(("dec_w", dw.reshape(128)))
    r.append(("dec_b", t["decoder.decoder.2.bias"].reshape(1)))
    return r


def main():
    model, out = sys.argv[1], sys.argv[2]
    arr = arranged(load_branch(model))
    with open(out, "wb") as f:
        f.write(b"SLV5" + struct.pack("<ii", 1, len(arr)))
        for name, a in arr:
            a = np.ascontiguousarray(a, dtype="<f4").reshape(-1)
            f.write(struct.pack("<i", a.size))
            f.write(a.tobytes())
            print("%-11s %8d floats" % (name, a.size))
    print("gravado %s (%d bytes)" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
