#!/usr/bin/env python3
"""Confere que o leitor mínimo de ONNX (onnx_mini.py, só numpy) lê EXATAMENTE os mesmos tensores que a biblioteca oficial `onnx`, e que o binário
exportado (silero_v5_16k.bin) é o que export_weights.py gera hoje a partir do silero_vad.onnx. Uso: check_reader.py silero_vad.onnx silero_v5_16k.bin"""
import sys, os
import numpy as np
import onnx
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_weights as ew

model_path, bin_path = sys.argv[1:3]
mine = ew.load_branch(model_path)
m = onnx.load(model_path)
top = [n for n in m.graph.node if n.op_type == "If"][0]
then = [a.g for a in top.attribute if a.name == "then_branch"][0]
ref = {}
for n in then.node:
    if n.op_type != "Constant":
        continue
    for nm in ew.NAMES:
        if n.output[0].endswith("__" + nm):
            ref[nm] = numpy_helper.to_array(n.attribute[0].t).astype(np.float32)
bad = 0
for nm in ew.NAMES:
    ok = nm in ref and ref[nm].shape == mine[nm].shape and np.array_equal(ref[nm], mine[nm])
    print("%-34s %s %s" % (nm, mine[nm].shape, "IGUAL" if ok else "DIFERENTE"))
    bad += 0 if ok else 1
# o binário do repositório == exportação de agora
tmp = bin_path + ".novo"
os.system("%s %s %s %s > /dev/null" % (sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "export_weights.py"), model_path, tmp))
same = open(tmp, "rb").read() == open(bin_path, "rb").read()
os.remove(tmp)
print("silero_v5_16k.bin do repositorio == exportacao atual:", "SIM" if same else "NAO")
sys.exit(0 if bad == 0 and same else 1)
