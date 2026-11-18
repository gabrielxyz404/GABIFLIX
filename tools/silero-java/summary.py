#!/usr/bin/env python3
"""Junta os eval-<filme>.txt (film_eval.sh) numa tabela: por filme e legenda, o que cada VAD (java, ort, dsp) decidiu.
PRE = so as 3 janelas iniciais de 90 s; FULL = o laco do app (o motor pede mais janelas ate AudioSyncSession.MAX_ROUNDS rodadas).
Veredito por legenda: mais3 deve ser ACEITA com raw ~ -3000 ms; menos2 aceita com raw ~ +2000 ms; certa nao pode ser mexida (aceita so se ~0 ms, ou recusada
por ja estar certa); outro (legenda de outro filme) tem de ser RECUSADA. Uso: summary.py DIR_COM_EVALS SAIDA.md"""
import glob, os, re, sys

d, out = sys.argv[1], sys.argv[2]
EXP = {"mais3": -3000, "menos2": 2000, "certa": 0}


def parse(line):
    p = line.split()
    r = {"srt": p[0], "vad": p[1], "stage": p[2]}
    for k in ("accepted", "raw", "scale", "z", "inSync", "rodadas", "janelas", "ms"):
        m = re.search(k + r"=(-?[\d.]+)", line)
        r[k] = float(m.group(1)) if m else None
    r["erro"] = "ERRO" in line
    return r


def verdict(r):
    if r is None:
        return "-"
    if r["erro"] or r["accepted"] is None:
        return "ERRO"
    acc = r["accepted"] == 1
    if r["srt"] == "outro":
        return "ok(recusou)" if not acc else "FALSO-ACEITE"
    if r["srt"] == "certa":
        return "ok(intacta)" if (not acc or abs(r["raw"]) < 400) else "ESTRAGOU"
    if not acc:
        return "sem-confianca"
    return "ok(corrigiu)" if abs(r["raw"] - EXP[r["srt"]]) < 400 else "ERRADO"


def cell(r):
    if r is None or r["z"] is None:
        return verdict(r)
    extra = " (%d rod, %d jan)" % (r["rodadas"], r["janelas"]) if r["stage"] == "FULL" and r["rodadas"] is not None else ""
    return "z=%.1f raw=%+.0f %s%s" % (r["z"], r["raw"], verdict(r), extra)


films = {}
for f in sorted(glob.glob(os.path.join(d, "**", "eval-*.txt"), recursive=True)):
    slug = os.path.basename(f)[5:-4]
    rows = [parse(l) for l in open(f) if l.strip() and not l.startswith("#") and len(l.split()) >= 3]
    if rows:
        films[slug] = rows

L = ["# Silero em Java puro x ONNX Runtime x DSP, no corpus real (clipes de 20 min; mesmo AudioAligner do app; legendas PT)", "",
     "PRE = 3 janelas de 90 s (plano por densidade); FINAL = com as rodadas extras que o motor pede (como o app).", "",
     "| filme | legenda | esperado | Java PRE | Java FINAL | ORT FINAL | DSP FINAL | Java == ORT |",
     "|---|---|---|---|---|---|---|---|"]
tot = {st: {v: {} for v in ("java", "ort", "dsp")} for st in ("PRE", "FULL")}
same = {"PRE": [0, 0], "FULL": [0, 0]}
zd = {"PRE": [], "FULL": []}
zs = {"java": [], "dsp": []}
for slug, rows in films.items():
    by = {}
    for r in rows:
        by.setdefault(r["srt"], {}).setdefault(r["stage"], {})[r["vad"]] = r
    for srt in ("mais3", "menos2", "certa", "outro"):
        v = by.get(srt)
        if not v:
            continue
        for st in ("PRE", "FULL"):
            for e in ("java", "ort", "dsp"):
                r = v.get(st, {}).get(e)
                if r:
                    vd = verdict(r)
                    tot[st][e][vd] = tot[st][e].get(vd, 0) + 1
        eq = []
        for st in ("PRE", "FULL"):
            j, o = v.get(st, {}).get("java"), v.get(st, {}).get("ort")
            if j and o and j["z"] is not None and o["z"] is not None:
                same[st][1] += 1
                ok = j["accepted"] == o["accepted"] and abs(j["raw"] - o["raw"]) <= 1 and abs(j["scale"] - o["scale"]) < 1e-5 and abs(j["z"] - o["z"]) <= 0.1
                same[st][0] += 1 if ok else 0
                zd[st].append(abs(j["z"] - o["z"]))
                eq.append("sim" if ok else "NAO(%s dz=%.2f draw=%.0f)" % (st, j["z"] - o["z"], j["raw"] - o["raw"]))
        jf, df = v.get("FULL", {}).get("java"), v.get("FULL", {}).get("dsp")
        if srt in ("mais3", "menos2", "certa") and jf and df and jf["z"] is not None and df["z"] is not None:
            zs["java"].append(jf["z"]); zs["dsp"].append(df["z"])
        exp = {"mais3": "aceitar, raw -3000", "menos2": "aceitar, raw +2000", "certa": "nao mexer", "outro": "recusar"}[srt]
        L.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (slug, srt, exp, cell(v.get("PRE", {}).get("java")), cell(v.get("FULL", {}).get("java")),
                                                         cell(v.get("FULL", {}).get("ort")), cell(v.get("FULL", {}).get("dsp")), "/".join(sorted(set(eq))) or "-"))
L += ["", "## Totais por VAD (quantas legendas em cada veredito)", ""]
for st, nm in (("PRE", "3 janelas"), ("FULL", "FINAL (com rodadas extras)")):
    for e in ("java", "ort", "dsp"):
        L.append("- %s, **%s**: %s" % (nm, e, ", ".join("%s=%d" % (k, v) for k, v in sorted(tot[st][e].items())) or "-"))
for st in ("PRE", "FULL"):
    if same[st][1]:
        L += ["", "Java == ORT em %s (mesma decisao, deslocamento +-1 ms, escala +-1e-5, z +-0,1): **%d de %d** legendas; maior |dz| = %.3f" % (st, same[st][0], same[st][1], max(zd[st]))]
if zs["java"]:
    n = len(zs["java"])
    L += ["", "z medio nas legendas que existem para aceitar (mais3, menos2, certa; FINAL): Java/Silero %.1f, DSP %.1f (n=%d); Silero > DSP em %d, DSP > Silero em %d" %
          (sum(zs["java"]) / n, sum(zs["dsp"]) / n, n, sum(1 for a, b in zip(zs["java"], zs["dsp"]) if a > b + 0.05), sum(1 for a, b in zip(zs["java"], zs["dsp"]) if b > a + 0.05))]
open(out, "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L))
