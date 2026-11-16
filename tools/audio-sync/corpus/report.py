#!/usr/bin/env python3
"""Agrega os CSVs de avaliação (res/*/eval.csv) em tabelas markdown. Uso: report.py ROOT OUT_MD [limiar_ms=250]"""
import csv, glob, os, sys, collections, statistics

COLS = "film,sub,vad,cfg,cat,param,x,acc,med,p90,off,scale,z,margin,nccm,nccmin,agree,valid,f1,conf,audioms,tookms,relmed,relp90,reason".split(",")


def load(root):
    rows = []
    for p in sorted(glob.glob(os.path.join(root, "**", "eval*.csv"), recursive=True)):
        with open(p, newline="", encoding="utf-8") as f:
            for r in csv.reader(f):
                if len(r) < 24:
                    continue
                d = dict(zip(COLS, r))
                def num(v):
                    try:
                        return float(v)
                    except ValueError:
                        return float("nan")
                for k in ("acc", "agree", "valid", "audioms", "tookms"):
                    d[k] = int(num(d[k])) if num(d[k]) == num(d[k]) else 0
                for k in ("med", "p90", "off", "scale", "z", "margin", "nccm", "nccmin", "f1", "conf", "relmed", "relp90"):
                    d[k] = num(d[k])
                rows.append(d)
    return rows


def rel_ok(r, tol):
    """acerto: aceita e erro <= tol. Distorções: erro relativo ao resultado do próprio motor sem distorção (tira o viés do oráculo);
    cortes: p90 (>= 90% das falas certas); natural: erro absoluto contra o oráculo."""
    if not r["acc"]:
        return False
    if r["cat"] == "natural":
        return 0 <= r["med"] <= tol
    if r["cat"] in ("piece1", "piece2"):
        return 0 <= r["relp90"] <= tol
    return 0 <= r["relmed"] <= tol


def main():
    root, out = sys.argv[1], sys.argv[2]
    tol = float(sys.argv[3]) if len(sys.argv) > 3 else 250
    rows = load(root)
    md = ["# Resultados do motor de áudio (limiar %d ms)\n" % tol, "casos: %d, filmes: %d\n" % (len(rows), len({r['film'] for r in rows})),
          "Acerto = aceita E erro <= limiar. Erro das distorções = relativo ao resultado do próprio motor com a legenda sem distorção (o oráculo tem viés de ~100-200 ms); "
          "cortes (piece) = p90; natural = absoluto contra o oráculo. Recusa conta como não-acerto.\n"]
    groups = collections.defaultdict(list)
    for r in rows:
        groups[(r["vad"], r["cfg"])].append(r)
    summary = []
    for (vad, cfg), rs in sorted(groups.items()):
        bycat = collections.defaultdict(list)
        for r in rs:
            bycat[r["cat"]].append(r)
        md.append("\n## VAD `%s`, janelas %s\n" % (vad, cfg))
        md.append("| categoria | n | aceitas | acertos | aceitas erradas | recusadas | erro rel. mediano (ms) | erro abs. mediano (ms) | tempo médio (ms) |")
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
            ok = sum(1 for r in c if rel_ok(r, tol))
            wrong = acc - ok
            mr = sorted(r["relmed"] for r in c if r["acc"] and r["relmed"] >= 0)
            ma = sorted(r["med"] for r in c if r["acc"] and r["med"] >= 0)
            md.append("| %s | %d | %d (%.1f%%) | %d (%.1f%%) | %d | %d | %s | %s | %.0f |" % (
                cat, n, acc, 100 * acc / n, ok, 100 * ok / n, wrong, n - acc,
                "%.0f" % statistics.median(mr) if mr else "-", "%.0f" % statistics.median(ma) if ma else "-", statistics.mean(r["tookms"] for r in c)))
        tot = [r for r in rs if r["cat"] in ("offset", "scale", "combo")]
        if tot:
            ok = sum(1 for r in tot if rel_ok(r, tol))
            acc = sum(r["acc"] for r in tot)
            md.append("\n**offset+scale+combo: %d/%d = %.1f%% certos (%.1f%% entre as aceitas); falsos aceites em negativos: %d/%d**\n" % (
                ok, len(tot), 100 * ok / len(tot), 100.0 * ok / max(1, acc), sum(r["acc"] for r in bycat.get("neg", [])), len(bycat.get("neg", []))))
            summary.append((vad, cfg, len(tot), ok, acc, sum(r["acc"] for r in bycat.get("neg", [])), len(bycat.get("neg", [])),
                            statistics.mean(r["audioms"] for r in tot) / 1000.0))
    md.append("\n## Quanto áudio (offset+scale+combo)\n")
    md.append("| VAD | janelas | áudio (s) | n | acertos | % certos | % certos entre aceitas | negativos aceitos |")
    md.append("|---|---|---|---|---|---|---|---|")
    for vad, cfg, n, ok, acc, nf, nn, aud in sorted(summary, key=lambda x: (x[0], x[7])):
        md.append("| %s | %s | %.0f | %d | %d | %.1f%% | %.1f%% | %d/%d |" % (vad, cfg, aud, n, ok, 100.0 * ok / n, 100.0 * ok / max(1, acc), nf, nn))
    # por filme (dsp, 3x60)
    md.append("\n## Por filme (VAD dsp, offset+scale+combo, todas as janelas somadas)\n")
    md.append("| filme | n | % certos | aceitas erradas |")
    md.append("|---|---|---|---|")
    byf = collections.defaultdict(list)
    for r in rows:
        if r["vad"] == "dsp" and r["cat"] in ("offset", "scale", "combo") and r["cfg"] == "3x60":
            byf[r["film"]].append(r)
    for fl, rs in sorted(byf.items()):
        ok = sum(1 for r in rs if rel_ok(r, tol))
        md.append("| %s | %d | %.0f%% | %d |" % (fl, len(rs), 100.0 * ok / len(rs), sum(1 for r in rs if r["acc"]) - ok))
    open(out, "w", encoding="utf-8").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
