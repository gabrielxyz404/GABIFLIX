#!/usr/bin/env python3
"""Tabelas finais para o DESIGN.md a partir dos CSVs do eval, aplicando a regra de aceitação final (z >= ACCEPT_Z para ajustes globais; partes mantêm a decisão do motor).
Uso: final_tables.py ROOT [ACCEPT_Z=9] [tol=250]"""
import sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report
root = sys.argv[1]; Z = float(sys.argv[2]) if len(sys.argv) > 2 else 9.0; tol = float(sys.argv[3]) if len(sys.argv) > 3 else 250
rows = report.load(root)

def accepted(r):
    if not r["acc"]: return False
    if r["cat"] in ("piece1", "piece2"): return True
    if "segs=" in r["reason"] and "segs=1" not in r["reason"]: return True
    return r["z"] >= Z

def table(vad, cfg):
    rs = [r for r in rows if r["vad"] == vad and r["cfg"] == cfg]
    out = ["| categoria | n | aceitas | certas (±%d ms) | precisão entre aceitas | recusadas | erros aceitos |" % tol, "|---|---|---|---|---|---|---|"]
    tot = [0, 0, 0]
    for cat in ["offset", "scale", "combo", "piece1", "piece2", "natural", "neg"]:
        c = [r for r in rs if r["cat"] == cat]
        if not c: continue
        a = [r for r in c if accepted(r)]
        ok = sum(1 for r in a if report.rel_ok(r, tol)) if cat != "neg" else 0
        if cat == "neg":
            out.append("| negativos (outro filme) | %d | %d | - | - | %d | %d falsos aceites |" % (len(c), len(a), len(c) - len(a), len(a)))
        else:
            out.append("| %s | %d | %d (%.0f%%) | %d (%.0f%%) | %.0f%% | %d | %d |" % (cat, len(c), len(a), 100.0 * len(a) / len(c), ok, 100.0 * ok / len(c), 100.0 * ok / max(1, len(a)), len(c) - len(a), len(a) - ok))
        if cat in ("offset", "scale", "combo"):
            tot[0] += len(c); tot[1] += len(a); tot[2] += ok
    out.append("| **offset+escala+combo** | %d | %d (%.0f%%) | %d (%.0f%%) | %.0f%% | %d | %d |" % (tot[0], tot[1], 100.0 * tot[1] / max(1, tot[0]), tot[2], 100.0 * tot[2] / max(1, tot[0]), 100.0 * tot[2] / max(1, tot[1]), tot[0] - tot[1], tot[1] - tot[2]))
    return "\n".join(out)

for vad, cfg in [("dsp", "3x90d"), ("dsp", "3x90"), ("silero", "3x90d"), ("dsp", "3x60"), ("pywebrtc3", "3x60"), ("webrtc3", "3x60"), ("silero", "3x60")]:
    print("\n#### VAD %s, janelas %s (z>=%.0f)\n" % (vad, cfg, Z)); print(table(vad, cfg))
print("\n#### Acurácia x áudio (offset+escala+combo, z>=%.0f)\n" % Z)
print("| VAD | janelas | áudio (s) | aceitas | certas (% de todos) | precisão | negativos aceitos |"); print("|---|---|---|---|---|---|---|")
g = collections.defaultdict(list)
for r in rows: g[(r["vad"], r["cfg"])].append(r)
lines = []
for (vad, cfg), rs in g.items():
    if vad not in ("dsp", "silero") or cfg.startswith("rck"): continue
    p = [r for r in rs if r["cat"] in ("offset", "scale", "combo")]; n = [r for r in rs if r["cat"] == "neg"]
    if not p: continue
    a = [r for r in p if accepted(r)]; ok = sum(1 for r in a if report.rel_ok(r, tol))
    aud = sorted(r["audioms"] for r in p)[len(p) // 2] / 1000.0
    lines.append((vad, aud, "| %s | %s | %.0f | %.0f%% | %.1f%% | %.1f%% | %d/%d |" % (vad, cfg, aud, 100.0 * len(a) / len(p), 100.0 * ok / len(p), 100.0 * ok / max(1, len(a)), sum(1 for r in n if accepted(r)), len(n))))
for l in sorted(lines): print(l[2])
