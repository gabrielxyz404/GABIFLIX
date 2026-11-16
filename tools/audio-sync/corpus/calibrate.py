#!/usr/bin/env python3
"""Calibra o limiar de decisão (aceita/recusa) com validação cruzada deixando um filme de fora.
Uso: calibrate.py ROOT VAD CFG [tol_ms=250] [max_neg_fp=0]
Positivos: offset/scale/combo/natural com verdade; negativos: legendas de outros filmes. A regra é o 'e' de limiares nos números que o motor já grava
(z, concordância, NCC média, margem, F1). Escolhe, só com os OUTROS filmes, a regra que aceita mais acertos com no máximo max_neg_fp falsos aceites
nos negativos; mede no filme de fora. Imprime a regra global (todos os filmes) e a taxa honesta da validação cruzada."""
import itertools, math, os, sys, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import report

GRID = dict(z=[5, 6, 7, 8, 9, 10, 12], agree=[0.34, 0.5, 0.6, 0.7, 0.8], nccm=[0.05, 0.1, 0.15, 0.2, 0.3], margin=[-1, 0.0, 0.05, 0.1, 0.15, 0.2, 0.3], f1=[0, 0.5, 0.6, 0.7])

def feats(r):
    v = max(r["valid"], 1)
    return (r["z"], r["agree"] / v, r["nccm"], r["margin"], r["f1"])

def accepts(f, th):
    return f[0] >= th[0] and f[1] >= th[1] and f[2] >= th[2] and f[3] >= th[3] and f[4] >= th[4]

def main():
    root, vad, cfg = sys.argv[1], sys.argv[2], sys.argv[3]
    tol = float(sys.argv[4]) if len(sys.argv) > 4 else 250
    maxfp = int(sys.argv[5]) if len(sys.argv) > 5 else 0
    rows = [r for r in report.load(root) if r["vad"] == vad and r["cfg"] == cfg]
    pos = [(r["film"], feats(r), r["med"] <= tol and r["med"] >= 0) for r in rows if r["cat"] in ("offset", "scale", "combo", "natural")]
    neg = [(r["film"], feats(r)) for r in rows if r["cat"] == "neg"]
    films = sorted({p[0] for p in pos})
    if not pos or not neg:
        print("sem dados"); return
    grid = list(itertools.product(GRID["z"], GRID["agree"], GRID["nccm"], GRID["margin"], GRID["f1"]))

    def best_rule(pset, nset):
        best, bs = None, (-1, 0)
        for th in grid:
            fp = sum(1 for _, f in nset if accepts(f, th))
            if fp > maxfp * max(1, len(nset) // 1000):  # tolerância proporcional (0 por padrão)
                continue
            good = sum(1 for _, f, ok in pset if ok and accepts(f, th))
            bad = sum(1 for _, f, ok in pset if not ok and accepts(f, th))
            sc = (good - 3 * bad, -sum(th[:1]))
            if sc > bs:
                bs, best = sc, th
        return best

    glob = best_rule(pos, neg)
    print("regra global:", dict(zip(("z", "agree", "nccm", "margin", "f1"), glob)))
    tot = collections.Counter()
    for fl in films:
        th = best_rule([p for p in pos if p[0] != fl], [n for n in neg if n[0] != fl])
        if th is None:
            tot["sem_regra"] += 1; continue
        for f, feat, ok in [(p[0], p[1], p[2]) for p in pos if p[0] == fl]:
            tot["n"] += 1
            if accepts(feat, th):
                tot["acerto" if ok else "errado"] += 1
            else:
                tot["recusa_boa" if ok else "recusa_ruim"] += 1
        for n in [n for n in neg if n[0] == fl]:
            tot["neg"] += 1
            if accepts(n[1], th): tot["neg_aceita"] += 1
    n = max(1, tot["n"])
    print("validação cruzada por filme (%d filmes): positivos %d: aceitos certos %.1f%%, aceitos errados %.2f%%, recusados %.1f%% (dos quais certos %.1f%%); negativos aceitos %d/%d" % (
        len(films), tot["n"], 100.0 * tot["acerto"] / n, 100.0 * tot["errado"] / n, 100.0 * (tot["recusa_boa"] + tot["recusa_ruim"]) / n, 100.0 * tot["recusa_boa"] / n, tot["neg_aceita"], tot["neg"]))
    # por limiar global, no conjunto inteiro (otimista)
    g_good = sum(1 for _, f, ok in pos if ok and accepts(f, glob)); g_bad = sum(1 for _, f, ok in pos if not ok and accepts(f, glob)); g_fp = sum(1 for _, f in neg if accepts(f, glob))
    print("global (otimista): certos %d/%d, errados %d, negativos aceitos %d/%d" % (g_good, len(pos), g_bad, g_fp, len(neg)))

if __name__ == "__main__":
    main()
