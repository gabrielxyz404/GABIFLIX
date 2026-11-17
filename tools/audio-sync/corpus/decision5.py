#!/usr/bin/env python3
"""Tabela de decisão v5 (banda morta, 'deixada como está' separada de 'errada'), com varredura de portas.
Uso: decision5.py ROOT_res [csv=eval-o1.csv]   (ROOT_res = pasta com final-*/eval-o1.csv)"""
import csv, glob, os, re, sys, collections, statistics
root = sys.argv[1]; name = sys.argv[2] if len(sys.argv) > 2 else "eval-o1.csv"
COLS = "film,sub,vad,cfg,cat,param,x,acc,med,p90,off,scale,z,margin,nccm,nccmin,agree,valid,f1,conf,audioms,tookms,relmed,relp90,reason".split(",")
def fl(x):
    try: return float(x)
    except: return float("nan")
rows = []
for p in sorted(glob.glob(os.path.join(root, "**", name), recursive=True)):
    for r in csv.reader(open(p, encoding="utf-8")):
        if len(r) < 24: continue
        d = dict(zip(COLS, r)); d["acc"] = int(fl(d["acc"])) if d["acc"] else 0
        for k in ("med", "z", "relmed", "margin", "nccm", "f1"): d[k] = fl(d[k])
        m = re.search(r"before=(\d+)", d["reason"]); d["before"] = float(m.group(1)) if m else None
        m = re.search(r"insync=(\d) raw=(-?\d+) winmax=(\d+) wagree=(\d+)/(\d+)", d["reason"])
        d["insync"] = int(m.group(1)) if m else 0; d["raw"] = float(m.group(2)) if m else 0
        d["winmax"] = float(m.group(3)) if m else 0; d["wag"] = int(m.group(4)) if m else 0; d["wval"] = int(m.group(5)) if m else 0
        rows.append(d)

def acts(r, g):
    """a legenda é de fato alterada?"""
    if not r["acc"] or r["insync"] or r["z"] < g["z"]: return False
    if g["win"] and r["wval"] >= 2 and r["winmax"] > g["win"]: return False
    if g["wagree"] and r["wag"] < r["wval"]: return False
    return True

def table(g, vad, cfg):
    rs = [r for r in rows if r["vad"] == vad and r["cfg"] == cfg]
    d = [r for r in rs if r["cat"] in ("offset", "scale", "combo")]
    a = [r for r in d if acts(r, g)]
    ok250 = sum(0 <= r["relmed"] <= 250 for r in a); ok500 = sum(0 <= r["relmed"] <= 500 for r in a)
    absok = sum(0 <= r["med"] <= 500 for r in a)
    sf = [r for r in rs if r["cat"] == "safe"]; mv = sum(1 for r in sf if acts(r, g) and r["med"] > 150)
    nat = [r for r in rs if r["cat"] == "natural" and r["before"] is not None]
    w = sum(1 for r in nat if acts(r, g) and r["med"] > r["before"] + 150); b = sum(1 for r in nat if acts(r, g) and r["med"] < r["before"] - 150)
    ng = sum(1 for r in rs if r["cat"] == "neg" and acts(r, g))
    n = len(d)
    return "| %s | %s | z>=%d win<=%s wagree=%d | %d (%.1f%%) | %.1f%% | %d (%.1f%%) | %d (%.1f%%) | %d/%d | %d / %d (of %d) | %d |" % (
        vad, cfg, g["z"], g["win"] or "-", g["wagree"], len(a), 100.0 * len(a) / max(1, n), 100.0 * ok250 / max(1, n),
        len(a) - ok250, 100.0 * (len(a) - ok250) / max(1, n), len(a) - absok, 100.0 * (len(a) - absok) / max(1, n), mv, len(sf), w, b, len(nat), ng)
print("| VAD | windows | gate | acted | correct +-250 (% of all) | acted wrong rel>250 | acted abs-err>500 vs oracle | safe changed | natural worse / better | wrong-film acted |")
print("|---|---|---|---|---|---|---|---|---|---|")
for vad, cfg in sorted({(r["vad"], r["cfg"]) for r in rows}):
    for g in ({"z": 9, "win": 0, "wagree": 0}, {"z": 9, "win": 250, "wagree": 0}, {"z": 9, "win": 150, "wagree": 0}, {"z": 9, "win": 0, "wagree": 1}, {"z": 11, "win": 0, "wagree": 0}, {"z": 11, "win": 150, "wagree": 1}):
        print(table(g, vad, cfg))
