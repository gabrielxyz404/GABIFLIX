#!/usr/bin/env python3
"""Junta os eval-<filme>.txt (film_eval.sh) numa tabela: por filme e legenda, o que cada VAD (java, ort, dsp) decidiu.
Veredito por legenda: mais3 deve ser ACEITA com raw ~ -3000 ms; menos2 aceita com raw ~ +2000 ms; certa nao pode ser mexida (aceita so se ~0 ms, ou recusada
por ja estar certa); outro (legenda de outro filme) tem de ser RECUSADA. Uso: summary.py DIR_COM_EVALS SAIDA.md"""
import glob, os, re, sys

d, out = sys.argv[1], sys.argv[2]
EXP = {"mais3": -3000, "menos2": 2000, "certa": 0}


def parse(line):
    p = line.split()
    r = {"srt": p[0], "vad": p[1]}
    for k in ("accepted", "raw", "scale", "z", "inSync"):
        m = re.search(k + r"=(-?[\d.]+)", line)
        r[k] = float(m.group(1)) if m else None
    m = re.search(r"wins=(\d+)/(\d+)", line)
    r["wins"] = "%s/%s" % (m.group(1), m.group(2)) if m else "?"
    m = re.search(r"ms=(\d+)", line)
    r["ms"] = int(m.group(1)) if m else None
    r["erro"] = "ERRO" in line
    return r


def verdict(r):
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


films = {}
for f in sorted(glob.glob(os.path.join(d, "**", "eval-*.txt"), recursive=True)):
    slug = os.path.basename(f)[5:-4]
    rows = [parse(l) for l in open(f) if l.strip() and not l.startswith("#")]
    if rows:
        films[slug] = rows

L = ["# Silero em Java puro x ONNX Runtime x DSP, no corpus real (clipes de 20 min, 3x90 s por densidade, mesmo AudioAligner do app)", ""]
L.append("| filme | legenda | esperado | Java: z / decisao | ORT: z / decisao | DSP: z / decisao | Java == ORT |")
L.append("|---|---|---|---|---|---|---|")
tot = {"java": {}, "ort": {}, "dsp": {}}
same = [0, 0]
zd = []
for slug, rows in films.items():
    by = {}
    for r in rows:
        by.setdefault(r["srt"], {})[r["vad"]] = r
    for srt in ("mais3", "menos2", "certa", "outro"):
        v = by.get(srt)
        if not v:
            continue
        cells = []
        for e in ("java", "ort", "dsp"):
            r = v.get(e)
            if not r:
                cells.append("-")
                continue
            vd = verdict(r)
            tot[e][vd] = tot[e].get(vd, 0) + 1
            cells.append("z=%.1f raw=%+.0f %s" % (r["z"], r["raw"], vd) if r["z"] is not None else vd)
        eq = ""
        j, o = v.get("java"), v.get("ort")
        if j and o and j["z"] is not None and o["z"] is not None:
            same[1] += 1
            ok = j["accepted"] == o["accepted"] and abs(j["raw"] - o["raw"]) <= 1 and abs(j["scale"] - o["scale"]) < 1e-5 and abs(j["z"] - o["z"]) <= 0.1
            same[0] += 1 if ok else 0
            zd.append(abs(j["z"] - o["z"]))
            eq = "sim" if ok else "NAO (dz=%.2f draw=%.0f)" % (j["z"] - o["z"], j["raw"] - o["raw"])
        exp = {"mais3": "aceitar, raw -3000", "menos2": "aceitar, raw +2000", "certa": "nao mexer", "outro": "recusar"}[srt]
        L.append("| %s | %s | %s | %s | %s | %s | %s |" % (slug, srt, exp, cells[0], cells[1], cells[2], eq))
L += ["", "## Totais por VAD (quantas legendas em cada veredito)", ""]
for e in ("java", "ort", "dsp"):
    L.append("- **%s**: %s" % (e, ", ".join("%s=%d" % (k, v) for k, v in sorted(tot[e].items())) or "-"))
if same[1]:
    L += ["", "Java == ORT (mesma decisao, deslocamento +-1 ms, escala +-1e-5, z +-0,1): **%d de %d** legendas; maior |dz| = %.3f" % (same[0], same[1], max(zd))]
open(out, "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L))
