#!/usr/bin/env python3
"""Tabela de decisão final. Uso: decision.py ROOT [saida.md]
ROOT = pasta com res/final-*/eval-o1.csv, eval-o2.csv, oracle2.json. Para cada (VAD, janelas):
  corretas (offset+escala+combo, erro relativo <= 250 e <= 500 ms), recusadas, aceitas erradas;
  natural (antes x depois, verdade v1 e v2): piorou (erro depois > antes + 150 ms), melhorou, igual; acerto absoluto @250/@500;
  safe (legenda já certa): quantas mexidas > 150 ms; negativos aceitos."""
import csv, glob, os, re, statistics, sys, json, collections
COLS = "film,sub,vad,cfg,cat,param,x,acc,med,p90,off,scale,z,margin,nccm,nccmin,agree,valid,f1,conf,audioms,tookms,relmed,relp90,reason".split(",")


def load(root, name):
    rows = []
    for p in sorted(glob.glob(os.path.join(root, "**", name), recursive=True)):
        for r in csv.reader(open(p, encoding="utf-8")):
            if len(r) < 24: continue
            d = dict(zip(COLS, r))
            for k in ("acc", "agree", "valid"): d[k] = int(float(d[k])) if d[k] not in ("", "-") else 0
            for k in ("med", "p90", "z", "relmed", "relp90", "audioms"):
                try: d[k] = float(d[k])
                except ValueError: d[k] = float("nan")
            m = re.search(r"before=(\d+)", d["reason"]); d["before"] = float(m.group(1)) if m else None
            rows.append(d)
    return rows


def pct(a, b): return "%.1f%%" % (100.0 * a / b) if b else "-"


def main():
    root = sys.argv[1]; out = sys.argv[2] if len(sys.argv) > 2 else None
    o1 = load(root, "eval-o1.csv"); o2 = load(root, "eval-o2.csv")
    md = []
    keys = sorted({(r["vad"], r["cfg"]) for r in o1})
    md.append("### Tabela de decisão (clipes de 20 min, plano por densidade, regra final z>=9; %% sobre todos os casos)\n")
    md.append("| VAD | janelas | áudio | corretas ±250 | corretas ±500 | recusadas (legenda intacta) | aceitas erradas | natural: piorou | natural: melhorou | safe: mexidas >150 ms | piorou total (safe+natural) | negativos aceitos |")
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    detail = []
    for vad, cfg in keys:
        rs = [r for r in o1 if r["vad"] == vad and r["cfg"] == cfg]
        d = [r for r in rs if r["cat"] in ("offset", "scale", "combo")]
        ok = lambda r, t: r["acc"] and 0 <= r["relmed"] <= t
        n = len(d); c250 = sum(ok(r, 250) for r in d); c500 = sum(ok(r, 500) for r in d)
        ref = sum(1 for r in d if not r["acc"]); aw = sum(1 for r in d if r["acc"] and not ok(r, 250))
        nat = [r for r in rs if r["cat"] == "natural" and r["before"] is not None]
        worse = sum(1 for r in nat if r["acc"] and r["med"] > r["before"] + 150)
        better = sum(1 for r in nat if r["acc"] and r["med"] < r["before"] - 150)
        sf = [r for r in rs if r["cat"] == "safe"]; ch = sum(1 for r in sf if r["acc"] and r["med"] > 150)
        ng = [r for r in rs if r["cat"] == "neg"]; na = sum(1 for r in ng if r["acc"])
        aud = statistics.median([r["audioms"] for r in d]) / 1000 if d else 0
        md.append("| %s | %s | %.0f s | %s (%d/%d) | %s | %s | %s (%d) | %d de %d | %d de %d | %d de %d | %d de %d | %d/%d |" % (
            vad, cfg, aud, pct(c250, n), c250, n, pct(c500, n), pct(ref, n), pct(aw, n), aw, worse, len(nat), better, len(nat), ch, len(sf), worse + ch, len(nat) + len(sf), na, len(ng)))
        # por categoria
        for cat in ("offset", "scale", "combo"):
            cc = [r for r in d if r["cat"] == cat]
            detail.append("| %s | %s | %s | %d | %s | %s | %s |" % (vad, cfg, cat, len(cc), pct(sum(ok(r, 250) for r in cc), len(cc)), pct(sum(ok(r, 500) for r in cc), len(cc)), pct(sum(1 for r in cc if not r["acc"]), len(cc))))
    md.append("\n#### Por categoria (relativo ao motor sem distorção)\n\n| VAD | janelas | categoria | n | ±250 | ±500 | recusadas |\n|---|---|---|---|---|---|---|")
    md += detail
    # natural/safe com verdade v1 x v2
    md.append("\n#### Natural e safe com a verdade v1 (3 oráculos, melhor par) x v2 (mediana de ffsubsync, auditok, alass e Silero global)\n")
    md.append("| VAD | janelas | verdade | natural n | aceitas | acerto abs ±250 | ±500 | erro mediano antes | erro mediano depois | piorou | melhorou | safe n | safe aceitas | safe mexidas >150 | safe deslocamento mediano (ms) |")
    md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for lab, rows in (("v1", o1), ("v2", o2)):
        for vad, cfg in keys:
            nat = [r for r in rows if r["vad"] == vad and r["cfg"] == cfg and r["cat"] == "natural" and r["before"] is not None]
            sf = [r for r in rows if r["vad"] == vad and r["cfg"] == cfg and r["cat"] == "safe"]
            a = [r for r in nat if r["acc"]]
            med = lambda xs: "%.0f" % statistics.median(xs) if xs else "-"
            md.append("| %s | %s | %s | %d | %d | %s | %s | %s | %s | %d | %d | %d | %d | %d | %s |" % (
                vad, cfg, lab, len(nat), len(a), pct(sum(1 for r in a if r["med"] <= 250), len(nat)), pct(sum(1 for r in a if r["med"] <= 500), len(nat)),
                med([r["before"] for r in a]), med([r["med"] for r in a]), sum(1 for r in a if r["med"] > r["before"] + 150), sum(1 for r in a if r["med"] < r["before"] - 150),
                len(sf), sum(1 for r in sf if r["acc"]), sum(1 for r in sf if r["acc"] and r["med"] > 150), med([r["med"] for r in sf if r["acc"]])))
    # viés do oráculo
    bi = []
    for p in glob.glob(os.path.join(root, "**", "oracle2.json"), recursive=True):
        for n, o in json.load(open(p)).items():
            if o.get("ok") and o.get("v1_minus_v2_med") is not None: bi.append((o["v1_minus_v2_med"], o.get("v1_minus_v2_p90abs"), o.get("used", []), o.get("bias", {})))
    if bi:
        md.append("\n#### Oráculo v2\n")
        md.append("legendas com verdade v2 (≥2 oráculos concordando): %d; v1-v2: mediana do |deslocamento| %.0f ms, p90 %.0f ms" % (
            len(bi), statistics.median(abs(b[0]) for b in bi), statistics.median(b[1] for b in bi if b[1] is not None)))
        agg = collections.defaultdict(list)
        for b in bi:
            for k, v in b[3].items(): agg[k].append(v["med"])
        md.append("\nviés mediano de cada oráculo contra a verdade v2 (ms): " + ", ".join("%s %+.0f (n=%d)" % (k, statistics.median(v), len(v)) for k, v in sorted(agg.items())))
    s = "\n".join(md)
    print(s)
    if out: open(out, "w", encoding="utf-8").write(s + "\n")


if __name__ == "__main__":
    main()
