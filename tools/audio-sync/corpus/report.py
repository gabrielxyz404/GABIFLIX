#!/usr/bin/env python3
"""Agrega os CSVs de avaliação (res/*/eval.csv) em tabelas markdown. Uso: report.py ROOT OUT_MD [limiar_ms=250]"""
import csv, glob, os, sys, collections, statistics

COLS = "film,sub,vad,cfg,cat,param,x,acc,med,p90,off,scale,z,margin,nccm,nccmin,agree,valid,f1,conf,audioms,tookms,reason".split(",")


def load(root):
    rows = []
    for p in sorted(glob.glob(os.path.join(root, "**", "eval*.csv"), recursive=True)):
        with open(p, newline="", encoding="utf-8") as f:
            for r in csv.reader(f):
                if len(r) < 22:
                    continue
                d = dict(zip(COLS, r))
                def num(v):
                    try:
                        return float(v)
                    except ValueError:
                        return float("nan")
                for k in ("acc", "agree", "valid", "audioms", "tookms"):
                    d[k] = int(num(d[k])) if num(d[k]) == num(d[k]) else 0
                for k in ("med", "p90", "off", "scale", "z", "margin", "nccm", "nccmin", "f1", "conf"):
                    d[k] = num(d[k])
                rows.append(d)
    return rows


def main():
    root, out = sys.argv[1], sys.argv[2]
    tol = float(sys.argv[3]) if len(sys.argv) > 3 else 250
    rows = load(root)
    md = ["# Resultados do motor de áudio (limiar %d ms)\n" % tol, "casos: %d, filmes: %d\n" % (len(rows), len({r['film'] for r in rows}))]
    groups = collections.defaultdict(list)
    for r in rows:
        groups[(r["vad"], r["cfg"])].append(r)
    for (vad, cfg), rs in sorted(groups.items()):
        bycat = collections.defaultdict(list)
        for r in rs:
            bycat[r["cat"]].append(r)
        md.append("\n## VAD `%s`, janelas %s\n" % (vad, cfg))
        md.append("| categoria | n | aceitas | acertos (aceita e erro<=%dms) | aceitas erradas | recusadas | erro mediano (ms) | p90 do erro | tempo médio (ms) |" % tol)
        md.append("|---|---|---|---|---|---|---|---|---|")
        for cat in ["offset", "scale", "combo", "piece1", "piece2", "natural", "natural_nt", "neg"]:
            c = bycat.get(cat)
            if not c:
                continue
            n = len(c)
            acc = sum(r["acc"] for r in c)
            if cat == "neg" or cat == "natural_nt":
                md.append("| %s | %d | %d (%.1f%%) | - | %d | %d | - | - | %.0f |" % (cat, n, acc, 100 * acc / n, acc if cat == "neg" else 0, n - acc, statistics.mean(r["tookms"] for r in c)))
                continue
            ok = sum(1 for r in c if r["acc"] and r["med"] <= tol)
            wrong = sum(1 for r in c if r["acc"] and r["med"] > tol)
            meds = sorted(r["med"] for r in c if r["acc"] and r["med"] >= 0)
            p90s = sorted(r["p90"] for r in c if r["acc"] and r["p90"] >= 0)
            md.append("| %s | %d | %d (%.1f%%) | %d (%.1f%%) | %d | %d | %s | %s | %.0f |" % (
                cat, n, acc, 100 * acc / n, ok, 100 * ok / n, wrong, n - acc,
                "%.0f" % statistics.median(meds) if meds else "-", "%.0f" % statistics.median(p90s) if p90s else "-", statistics.mean(r["tookms"] for r in c)))
        tot = [r for r in rs if r["cat"] in ("offset", "scale", "combo")]
        if tot:
            ok = sum(1 for r in tot if r["acc"] and r["med"] <= tol)
            md.append("\n**offset+scale+combo: %d/%d = %.1f%% dentro de %d ms**\n" % (ok, len(tot), 100 * ok / len(tot), tol))
    # filmes
    md.append("\n## Por filme (offset+scale+combo, melhor VAD/cfg de cada linha)\n")
    open(out, "w", encoding="utf-8").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
