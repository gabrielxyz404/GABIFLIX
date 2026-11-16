#!/usr/bin/env python3
"""Agrega os rank.csv (Rank.java) em tabelas markdown. Uso: rank_report.py ROOT"""
import csv, glob, os, sys, collections

def main():
    root = sys.argv[1]
    rows = []
    for p in sorted(glob.glob(os.path.join(root, "**", "rank.csv"), recursive=True)):
        for r in csv.reader(open(p, encoding="utf-8")):
            if len(r) >= 10:
                rows.append(r)
    print("\n## Ranking de candidatas: consenso sem referência (baseline) x áudio\n")
    if not rows:
        print("(sem linhas)")
        return
    # col: film,vad,cfg,nCands,nTruth,baseline,bName,audio,aName,anyGood,detail
    by = collections.defaultdict(list)
    for r in rows:
        by[(r[1], r[2], "en" if r[8].startswith("en") else "pt")].append(r)
    for (vad, cfg, lang), rs in sorted(by.items()):
        n = len(rs)
        b = collections.Counter(r[5] for r in rs)
        a = collections.Counter(r[7] for r in rs)
        anyg = sum(1 for r in rs if r[9] == "true")
        pct = lambda c, k: "%d (%.0f%%)" % (c[k], 100.0 * c[k] / n)
        print("\n### VAD `%s`, janelas %s, legendas %s: %d filmes (com >=2 candidatas e >=1 com verdade)\n" % (vad, cfg, lang, n))
        print("| método | certa | errada | sem consenso / recusou tudo | não verificável |")
        print("|---|---|---|---|---|")
        print("| consenso sem referência (app hoje) | %s | %s | %s | %s |" % (pct(b, "certa"), pct(b, "errada"), pct(b, "sem_consenso"), pct(b, "nao_verificavel")))
        print("| ranking por áudio + correção | %s | %s | %s | %s |" % (pct(a, "certa"), pct(a, "errada"), pct(a, "recusou_tudo"), pct(a, "nao_verificavel")))
        print("\nTeto (existe candidata com verdade que o motor acerta): %d/%d (%.0f%%)\n" % (anyg, n, 100.0 * anyg / n))

if __name__ == "__main__":
    main()
