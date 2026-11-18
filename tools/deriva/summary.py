#!/usr/bin/env python3
"""Resume as linhas "R ..." do DerivaEval (uma por filme/variante/modo) em tabelas: taxa de aceite e acerto por familia de variante, falsos aceites,
legendas ja certas mexidas e os 4 casos antes recusados so pela regra 0,999/1,001. Uso: summary.py DIR_COM_TXT SAIDA.md"""
import glob, os, re, sys
from collections import defaultdict

src, out = sys.argv[1], sys.argv[2]
rows = []
for f in sorted(glob.glob(os.path.join(src, "**", "deriva-*.txt"), recursive=True)):
    tag = re.sub(r"-(old|novo)$", "", os.path.basename(f)[len("deriva-"):-4])
    for l in open(f, encoding="utf-8", errors="replace"):
        if not l.startswith("R "):
            continue
        head, _, diag = l.partition(" | ")
        kv = dict(re.findall(r"(\w+)=(\S+)", head))
        kv["diag"] = diag.strip()
        kv["tag"] = tag
        rows.append(kv)

def fam(v):
    if v.startswith("outro"):
        return "outro filme" + (" (com escala)" if "|g" in v else "")
    if v in ("certa", "certa+400"):
        return "ja certa"
    if v in ("mais3", "menos2"):
        return "so deslocamento"
    m = re.match(r"g([\d./]+)\|", v)
    return "escala " + m.group(1) if m else v

MODES = ["old", "dense", "spread", "spread6"]
NAMES = {"old": "ANTES (origin/main: 3 janelas densas, regra 0,999/1,001)", "dense": "NOVO com janelas densas (so a prova de deriva)", "spread": "NOVO (3 janelas espalhadas + prova de deriva + janelas extras)", "spread6": "NOVO com 6 janelas de 90 s espalhadas (+ extras ate 8)"}
o = ["# Deriva de escala (1,001 e afins): legendas deformadas a partir da certa, audio REAL (Silero em Java puro, o motor do app)\n"]
films = sorted(set(r["film"] for r in rows))
o.append("Filmes/recortes: %d (%s)\n" % (len(films), ", ".join(films)))
for m in MODES:
    rs = [r for r in rows if r.get("modo") == m]
    if not rs:
        continue
    o.append("\n## %s\n" % NAMES[m])
    o.append("| familia | n | aceitas | certas (p95<=400 ms, max<=800 ms) | ERRADAS | recusadas | mexidas/falsas | janelas (media) |")
    o.append("|---|---|---|---|---|---|---|---|")
    g = defaultdict(list)
    for r in rs:
        g[fam(r["var"])].append(r)
    for k in sorted(g, key=lambda x: (x.startswith("outro"), x != "ja certa", x)):
        L = g[k]
        n = len(L)
        acc = sum(1 for r in L if r.get("acc") == "1")
        certo = sum(1 for r in L if r.get("classe") == "certo")
        errado = sum(1 for r in L if r.get("classe") == "ERRADO")
        rec = sum(1 for r in L if r.get("classe") == "recusou")
        bad = sum(1 for r in L if r.get("classe") in ("MEXIDA", "FALSO"))
        if k == "ja certa":
            certo = sum(1 for r in L if r.get("classe") == "intacta")
        jan = sum(float(r.get("janelas", 0)) for r in L) / max(1, n)
        o.append("| %s | %d | %d | %d | %d | %d | %d | %.1f |" % (k, n, acc, certo, errado, rec, bad, jan))
    # casos ruins
    ruins = [r for r in rs if r.get("classe") in ("MEXIDA", "FALSO", "ERRADO")]
    if ruins:
        o.append("\n**Casos ruins (%s):**\n" % m)
        for r in ruins:
            o.append("- %s `%s` classe=%s escala=%s desl=%s erroMax=%s z=%s janelas=%s | %s" % (r["tag"], r["var"], r["classe"], r.get("escala"), r.get("desl"), r.get("erroMax"), r.get("z"), r.get("janelas"), r["diag"][:400]))

# os 4 casos antes recusados so pela regra
o.append("\n## Os 4 casos antes recusados so pela regra 0,999/1,001 (recorte de 20 min, hitch_hiker e house_on_haunted_hill, +3 s e -2 s)\n")
o.append("| recorte | legenda | modo | classe | escala | desl (ms) | erroMax (ms) | z | janelas |")
o.append("|---|---|---|---|---|---|---|---|---|")
for r in rows:
    if r["film"] in ("hitch_hiker", "house_on_haunted_hill") and r["tag"].endswith("-clip") and r["var"] in ("mais3", "menos2"):
        o.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (r["tag"], r["var"], r["modo"], r["classe"], r.get("escala"), r.get("desl"), r.get("erroMax"), r.get("z"), r.get("janelas")))

# por filme (so o modo novo)
o.append("\n## Por filme (modo novo: 6 janelas espalhadas)\n")
o.append("| filme | ja certas intactas | deriva 1,001/0,999 certas | 25/23,976 certas | so deslocamento certas | outro filme falsos |")
o.append("|---|---|---|---|---|---|")
for t in sorted(set(r["tag"] for r in rows)):
    rs = [r for r in rows if r["tag"] == t and r.get("modo") == "spread6"]
    if not rs:
        continue
    def cnt(pred, cls):
        L = [r for r in rs if pred(r["var"])]
        return "%d/%d" % (sum(1 for r in L if r.get("classe") in cls), len(L))
    o.append("| %s | %s | %s | %s | %s | %s |" % (t, cnt(lambda v: v in ("certa", "certa+400"), ("intacta",)),
             cnt(lambda v: v.startswith(("g1.001|", "g0.999|")), ("certo",)), cnt(lambda v: v.startswith(("g25/", "g23.976/")), ("certo",)),
             cnt(lambda v: v in ("mais3", "menos2"), ("certo",)), cnt(lambda v: v.startswith("outro"), ("FALSO",))))
open(out, "w", encoding="utf-8").write("\n".join(o) + "\n")
print("\n".join(o))
