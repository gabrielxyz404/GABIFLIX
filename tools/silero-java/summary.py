#!/usr/bin/env python3
"""Junta os eval-<filme>.txt (film_eval.sh) numa tabela: por filme e legenda, o que cada VAD (java, ort, dsp) decidiu.
PRE = as 3 janelas iniciais de 90 s: e o que o app decide hoje (AudioSyncCore resolve uma vez so). FULL = hipotese: se o app atendesse os pedidos de mais
janelas do motor (ate AudioSyncSession.MAX_ROUNDS rodadas).
Veredito por legenda: mais3 deve ser ACEITA com raw ~ -3000 ms; menos2 aceita com raw ~ +2000 ms; certa nao pode ser mexida (aceita so se ~0 ms, ou recusada
por ja estar certa); outro (legenda de outro filme) tem de ser RECUSADA. Uso: summary.py DIR_COM_EVALS SAIDA.md"""
import glob, os, re, sys

d, out = sys.argv[1], sys.argv[2]
EXP = {"mais3": -3000, "menos2": 2000, "certa": 0}


def parse(line):
    line, _, motivo = line.partition(" motivo=")
    p = line.split()
    r = {"srt": p[0], "vad": p[1], "stage": p[2], "motivo": motivo.strip().replace("_", " ")}
    for k in ("accepted", "raw", "scale", "z", "inSync", "rodadas", "janelas", "ms", "margem", "ncc", "f1"):
        m = re.search(k + r"=(-?[\d.]+)", line)
        r[k] = float(m.group(1)) if m else None
    r["erro"] = "ERRO" in line
    return r


def verdict(r, ref=None):
    """ref = deslocamento (raw) que o MESMO VAD achou para a legenda certa: a 'certa' do alass pode estar uns centenas de ms fora de zero neste corte do filme"""
    if r is None:
        return "-"
    if r["erro"] or r["accepted"] is None:
        return "ERRO"
    acc = r["accepted"] == 1
    if r["srt"] == "outro":
        return "ok(recusou)" if not acc else "FALSO-ACEITE"
    if r["srt"] == "certa":
        return "ok(intacta)" if (not acc or abs(r["raw"]) < 400) else "moveu(%+.0f)" % r["raw"]
    if not acc:
        return "sem-confianca"
    base = ref if ref is not None else 0.0
    return "ok(corrigiu)" if abs(r["raw"] - (base + EXP[r["srt"]])) < 400 else "ERRADO"


def cell(r, ref=None):
    if r is None or r["z"] is None:
        return verdict(r)
    extra = " (%d rod, %d jan)" % (r["rodadas"], r["janelas"]) if r["stage"] == "FULL" and r["rodadas"] is not None else ""
    return "z=%.1f raw=%+.0f %s%s" % (r["z"], r["raw"], verdict(r, ref), extra)


films = {}
for f in sorted(glob.glob(os.path.join(d, "**", "eval-*.txt"), recursive=True)):
    slug = os.path.basename(f)[5:-4]
    rows = [parse(l) for l in open(f) if l.strip() and not l.startswith("#") and len(l.split()) >= 3]
    if rows:
        films[slug] = rows

L = ["# Silero em Java puro x ONNX Runtime x DSP, no corpus real (clipes de 20 min; mesmo AudioAligner do app; legendas PT)", "",
     "PRE = 3 janelas de 90 s (plano por densidade) = o que o app decide hoje; FINAL = hipotese, se o app atendesse os pedidos de mais janelas do motor.", "",
     "| filme | legenda | esperado | Java PRE (app) | ORT PRE | DSP PRE | Java FINAL (hipotese) | Java == ORT |",
     "|---|---|---|---|---|---|---|---|"]
tot = {st: {v: {} for v in ("java", "ort", "dsp")} for st in ("PRE", "FULL")}
same = {"PRE": [0, 0], "FULL": [0, 0]}
zd = {"PRE": [], "FULL": []}
zs = {"java": [], "dsp": []}
blocked = []
for slug, rows in films.items():
    by = {}
    for r in rows:
        by.setdefault(r["srt"], {}).setdefault(r["stage"], {})[r["vad"]] = r
    def refraw(st, e):
        c = by.get("certa", {}).get(st, {}).get(e)
        return c["raw"] if c and c["z"] is not None else None
    for srt in ("mais3", "menos2", "certa", "outro"):
        v = by.get(srt)
        if not v:
            continue
        for st in ("PRE", "FULL"):
            for e in ("java", "ort", "dsp"):
                r = v.get(st, {}).get(e)
                if r:
                    vd = verdict(r, refraw(st, e))
                    vk = "moveu" if vd.startswith("moveu") else vd
                    tot[st][e][vk] = tot[st][e].get(vk, 0) + 1
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
        L.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (slug, srt, exp, cell(v.get("PRE", {}).get("java"), refraw("PRE", "java")), cell(v.get("PRE", {}).get("ort"), refraw("PRE", "ort")),
                                                         cell(v.get("PRE", {}).get("dsp"), refraw("PRE", "dsp")), cell(v.get("FULL", {}).get("java"), refraw("FULL", "java")), "/".join(sorted(set(eq))) or "-"))
        jfull = v.get("PRE", {}).get("java")
        if srt in ("mais3", "menos2") and jfull and jfull["z"] is not None and jfull["accepted"] != 1:
            ref = refraw("PRE", "java")
            right = ref is not None and abs(jfull["raw"] - (ref + EXP[srt])) < 400
            blocked.append((slug, srt, jfull["z"], right, jfull["motivo"], jfull.get("margem"), jfull.get("ncc"), jfull.get("f1")))
L += ["", "## Totais por VAD (quantas legendas em cada veredito)", ""]
for st, nm in (("PRE", "3 janelas"), ("FULL", "FINAL (hipotese: com rodadas extras)")):
    for e in ("java", "ort", "dsp"):
        L.append("- %s, **%s**: %s" % (nm, e, ", ".join("%s=%d" % (k, v) for k, v in sorted(tot[st][e].items())) or "-"))
for st in ("PRE", "FULL"):
    if same[st][1]:
        L += ["", "Java == ORT em %s (mesma decisao, deslocamento +-1 ms, escala +-1e-5, z +-0,1): **%d de %d** legendas; maior |dz| = %.3f" % (st, same[st][0], same[st][1], max(zd[st]))]
if zs["java"]:
    n = len(zs["java"])
    L += ["", "z medio nas legendas que existem para aceitar (mais3, menos2, certa; FINAL): Java/Silero %.1f, DSP %.1f (n=%d); Silero > DSP em %d, DSP > Silero em %d" %
          (sum(zs["java"]) / n, sum(zs["dsp"]) / n, n, sum(1 for a, b in zip(zs["java"], zs["dsp"]) if a > b + 0.05), sum(1 for a, b in zip(zs["java"], zs["dsp"]) if b > a + 0.05))]
L += ["", "## Legendas deslocadas que o app RECUSA (Java, 3 janelas): por que?", "",
      "| filme | legenda | z | deslocamento certo? | motivo do motor |", "|---|---|---|---|---|"]
for slug, srt, z, right, why, mg, ncc, f1 in blocked:
    L.append("| %s | %s | %.1f | %s | %s |" % (slug, srt, z, "sim" if right else "nao", why or "-"))
hi = [b for b in blocked if b[2] >= 9]
L += ["", "Recusadas com z >= 9: %d de %d (deslocamento certo em %d); recusadas com z < 9: %d (deslocamento certo em %d)" %
      (len(hi), len(blocked), sum(1 for b in hi if b[3]), len(blocked) - len(hi), sum(1 for b in blocked if b[2] < 9 and b[3]))]
open(out, "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L))
