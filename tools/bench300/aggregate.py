#!/usr/bin/env python3
"""Junta os resultados dos shards (title.json + main.jsonl + drift.jsonl + subfmt.jsonl por titulo) em resumo-300.md e graficos.
Uso: aggregate.py DIR_RESULTADOS DIR_SAIDA [--tiers A|AB]      (DIR_RESULTADOS: qualquer arvore com <id>/title.json)"""
import glob, json, math, os, re, sys
from collections import Counter, defaultdict

src, out = sys.argv[1], sys.argv[2]
TIERS = "AB" if "--tiers" in sys.argv and sys.argv[sys.argv.index("--tiers") + 1] == "AB" else "A"
os.makedirs(os.path.join(out, "plots"), exist_ok=True)
ENG = ["main", "drift"]
ENG_NAME = {"main": "origin/main (hoje)", "drift": "ramo da deriva"}


def load_jsonl(p):
    rows = []
    try:
        for l in open(p, encoding="utf-8", errors="replace"):
            l = l.strip()
            if l.startswith("{"):
                try:
                    rows.append(json.loads(l))
                except Exception:
                    pass
    except FileNotFoundError:
        pass
    return rows


titles = {}
for tj in glob.glob(os.path.join(src, "**", "title.json"), recursive=True):
    d = os.path.dirname(tj)
    try:
        t = json.load(open(tj, encoding="utf-8"))
    except Exception:
        continue
    t["_dir"] = d
    titles[t["id"]] = t

ok_titles = {k: t for k, t in titles.items() if t.get("status") == "ok" and (t.get("ref") or {}).get("tier") in TIERS}
V = []        # variantes (faixa 1)
V2 = []       # faixa 2 de audio
META = {}
for k, t in ok_titles.items():
    for eng in ENG:
        for r in load_jsonl(os.path.join(t["_dir"], eng + ".jsonl")):
            if r.get("rec") == "var":
                r["kind_t"] = t["kind"]
                r["decade"] = t.get("decade")
                V.append(r)
            elif r.get("rec") in ("abort",):
                META.setdefault("abort", []).append(r)
        for r in load_jsonl(os.path.join(t["_dir"], eng + "_t2.jsonl")):
            if r.get("rec") == "var":
                r["kind_t"] = t["kind"]
                V2.append(r)

GROUPS = [("todos", lambda t: True), ("antigos (filmes)", lambda t: t == "old"), ("antigos (TV)", lambda t: t == "old-tv"), ("novos", lambda t: t == "new")]
FAMS_S = ["deslocamento +3 s", "deslocamento -2 s", "deslocamento +7,5 s", "escala 1.001 (24/23.976)", "escala 0.999 (23.976/24)", "escala 25/23.976", "escala 23.976/25"]


def sel(eng, typ=None, fam=None, grp=None):
    return [r for r in V if r["engine"] == eng and (typ is None or r["type"] == typ) and (fam is None or r["fam"] == fam) and (grp is None or grp(r["kind_t"]))]


def rate(a, b):
    return "%.1f%%" % (100.0 * a / b) if b else "-"


def cnt(rs, f):
    return sum(1 for r in rs if f(r))


def qs(v, qq=(0.5, 0.9, 0.99)):
    v = sorted(x for x in v if x is not None)
    return [v[min(len(v) - 1, int(len(v) * q))] if v else None for q in qq]


def f1(x, n=1):
    return "-" if x is None else ("%." + str(n) + "f") % x


L = []
P = L.append
n_ok = len(ok_titles)
kinds = Counter(t["kind"] for t in ok_titles.values())
P("# Bancada dos 300 titulos: o motor de sincronia de legendas pelo audio\n")
P("Medido com o laco real do app (guardas, plano de janelas, VAD Silero em Java puro, `AudioAligner` e a regra de decisao `fill`), sem alterar o motor.")
P("Motores: **main** = `origin/main` (3 janelas densas, uma resolucao); **drift** = ramo `run-deriva` (3 densas + 6 espalhadas + ate 2 rodadas extras, prova de deriva).\n")

# ------------------------------------------------------------------ 1. titulos
P("## 1. Titulos usados\n")
allst = Counter(t.get("status", "?").split("(")[0].split(":")[0].strip() for t in titles.values())
P("Titulos tentados: **%d**. Com resultado valido (referencia %s): **%d** (antigos filmes %d, antigos TV %d, novos %d)." % (len(titles), "nivel " + "/".join(TIERS), n_ok, kinds.get("old", 0), kinds.get("old-tv", 0), kinds.get("new", 0)))
if n_ok:
    P("Proporcao antigos/novos: %.0f%% / %.0f%%.\n" % (100.0 * (kinds.get("old", 0) + kinds.get("old-tv", 0)) / n_ok, 100.0 * kinds.get("new", 0) / n_ok))
P("\nDestino de cada titulo tentado:\n")
P("| destino | titulos |\n|---|---|")
for k, v in allst.most_common():
    P("| %s | %d |" % (k, v))
# tiers
tc = Counter((t.get("ref") or {}).get("tier", "-") for t in titles.values() if t.get("status") == "ok" or (t.get("ref")))
P("\nQualidade da referencia (legenda alinhada ao audio pelo alass; 2o oraculo = ffsubsync sobre a referencia): nivel A = desvio p90 do alass <= 300 ms e ffsubsync <= 300 ms; B = p90 ate 1000 ms (ampliado); X = excluida.")
P("Referencias por nivel entre os titulos que chegaram a ter uma: " + ", ".join("%s=%d" % kv for kv in sorted(tc.items())) + ".\n")
P("| origem da legenda de referencia | titulos |\n|---|---|")
for k, v in Counter(((t.get("ref") or {}).get("src", "?") + " - " + ("PT (parser do app)" if (t.get("ref") or {}).get("pt_app") else "outro idioma (" + str((t.get("ref") or {}).get("text_lang")) + ")")) for t in ok_titles.values()).most_common():
    P("| %s | %d |" % (k, v))
P("\n| decada | titulos |\n|---|---|")
for k, v in sorted(Counter(t.get("decade") for t in ok_titles.values() if t.get("decade")).items()):
    P("| %ds | %d |" % (k, v))
P("\n| fonte da midia / licenca declarada | titulos |\n|---|---|")
for k, v in Counter(((t.get("license") or "sem licenca declarada (colecao de dominio publico do IA)")[:70] + " / " + ",".join((t.get("collection") or [])[:2])[:50]) for t in ok_titles.values()).most_common(14):
    P("| %s | %d |" % (k, v))

# ------------------------------------------------------------------ 2. numeros principais
P("\n## 2. Numeros principais (todas as variantes, por tipo e por grupo)\n")
P("Definicoes: **aceita** = o app aplicaria (FIX) ou diria \"ja esta certa\" (OK); **certa** = aceita e erro p95 <= 400 ms e max <= 800 ms em todas as falas (60 s .. fim-60 s) contra a referencia; "
  "**aceite errado** = aceita mas fora disso; **aceite falso** = aceitou a legenda de OUTRO titulo; **certa mexida** = a legenda ja certa recebeu um ajuste (FIX).\n")
P("| grupo | motor | variantes S (devem ser corrigidas) | aceitas | certas | ACEITES ERRADOS | recusadas | legenda ja certa: mexidas | outro titulo: ACEITES FALSOS |")
P("|---|---|---|---|---|---|---|---|---|")
for gn, gf in GROUPS:
    for eng in ENG:
        S, C, X = sel(eng, "S", grp=gf), sel(eng, "C", grp=gf), sel(eng, "X", grp=gf)
        if not S and not C:
            continue
        P("| %s | %s | %d | %s | %s | %d | %s | %d/%d | %d/%d |" % (gn, eng, len(S), rate(cnt(S, lambda r: r.get("acc") == 1), len(S)), rate(cnt(S, lambda r: r.get("cls") == "certo"), len(S)), cnt(S, lambda r: r.get("cls") == "ERRADO"),
                                                                    rate(cnt(S, lambda r: r.get("cls") == "recusou"), len(S)), cnt(C, lambda r: r.get("cls") == "MEXIDA"), len(C), cnt(X, lambda r: r.get("acc") == 1), len(X)))

P("\n### Por familia de variante\n")
for gn, gf in GROUPS:
    rows = []
    for fam in FAMS_S + ["ja certa", "outro titulo", "outro titulo (com escala)"]:
        for eng in ENG:
            rs = [r for r in V if r["engine"] == eng and r["fam"] == fam and gf(r["kind_t"])]
            if not rs:
                continue
            n = len(rs)
            acc = cnt(rs, lambda r: r.get("acc") == 1)
            if fam == "ja certa":
                rows.append("| %s | %s | %d | %s | intactas %d | MEXIDAS %d | - | z mediana %s |" % (fam, eng, n, rate(acc, n), cnt(rs, lambda r: r.get("cls") == "intacta"), cnt(rs, lambda r: r.get("cls") == "MEXIDA"), f1(qs([r.get("z") for r in rs])[0])))
            elif fam.startswith("outro"):
                rows.append("| %s | %s | %d | %s | recusou %d | ACEITES FALSOS %d | - | z mediana %s |" % (fam, eng, n, rate(acc, n), cnt(rs, lambda r: r.get("cls") == "recusou"), acc, f1(qs([r.get("z") for r in rs])[0])))
            else:
                rows.append("| %s | %s | %d | %s | certas %s (%d) | ERRADAS %d | recusadas %d | z mediana %s |" % (fam, eng, n, rate(acc, n), rate(cnt(rs, lambda r: r.get("cls") == "certo"), n), cnt(rs, lambda r: r.get("cls") == "certo"),
                                                                                                         cnt(rs, lambda r: r.get("cls") == "ERRADO"), cnt(rs, lambda r: r.get("cls") == "recusou"), f1(qs([r.get("z") for r in rs])[0])))
    if rows:
        P("\n**%s**\n" % gn)
        P("| familia | motor | n | aceita | certas | errados / falsos | recusadas | z |\n|---|---|---|---|---|---|---|---|")
        L.extend(rows)

# ------------------------------------------------------------------ 3. main x drift
P("\n## 3. Cabeca a cabeca: main x deriva (mesma variante, mesmo titulo)\n")
byk = defaultdict(dict)
for r in V:
    byk[(r["title"], r["var"])][r["engine"]] = r


def state(r):
    if r is None:
        return "?"
    c = r.get("cls")
    return {"certo": "certa", "ERRADO": "ERRADA", "recusou": "recusou", "intacta": "intacta", "MEXIDA": "MEXIDA", "FALSO": "FALSA", "FALSO-ok": "FALSA", "pulado": "pulada"}.get(c, r.get("kind", "?"))


trans = Counter()
reg = []
for (tt, vv), d in byk.items():
    if "main" in d and "drift" in d:
        a, b = state(d["main"]), state(d["drift"])
        fam = d["main"]["fam"]
        trans[(fam, a, b)] += 1
        bad = {"ERRADA", "MEXIDA", "FALSA"}
        if (b in bad and a not in bad) or (a == "certa" and b == "recusou"):
            reg.append((tt, vv, fam, a, b, d["drift"]))
P("| familia | main | deriva | n |\n|---|---|---|---|")
for (fam, a, b), n in sorted(trans.items(), key=lambda x: (x[0][0], -x[1])):
    if a != b:
        P("| %s | %s | %s | %d |" % (fam, a, b, n))
same = sum(n for (f, a, b), n in trans.items() if a == b)
P("\nIguais nos dois motores: %d de %d variantes.\n" % (same, sum(trans.values())))
P("\n**Regressoes da deriva (certa/intacta no main que passou a errada/mexida/recusada):** %d\n" % len(reg))
for tt, vv, fam, a, b, r in reg[:60]:
    P("- `%s` `%s` (%s): main=%s -> deriva=%s; z=%s janelas=%s motivo=%s | %s" % (tt, vv, fam, a, b, f1(r.get("z")), r.get("win"), (r.get("reason") or "")[:80], (r.get("diag") or "")[:300]))

# ------------------------------------------------------------------ 4. z
P("\n## 4. Distribuicao do z (confianca do alinhador)\n")
P("| motor | classe | n | z p50 | p90 | p99 | min |\n|---|---|---|---|---|---|---|")
for eng in ENG:
    for cls, nm in (("certo", "corrigida certa"), ("ERRADO", "ACEITE ERRADO"), ("FALSO", "ACEITE FALSO"), ("recusou", "recusada (S)"), ("intacta", "ja certa intacta")):
        rs = [r for r in V if r["engine"] == eng and (r.get("cls") == cls or (cls == "FALSO" and r.get("cls") == "FALSO-ok")) and r.get("z") is not None]
        if rs:
            z = [r["z"] for r in rs]
            P("| %s | %s | %d | %s | %s | %s | %s |" % (eng, nm, len(rs), *[f1(x) for x in qs(z)], f1(min(z))))

# ------------------------------------------------------------------ 5. tempo
P("\n## 5. Tempo de analise (CPU do runner do GitHub, 2 fios de VAD; NAO e o tempo no celular/TV)\n")
P("| motor | janelas (media) | VAD p50 / p95 (s) | alinhador p50 / p95 (s) | total p50 / p95 (s) |\n|---|---|---|---|---|")
for eng in ENG:
    rs = [r for r in V if r["engine"] == eng and r.get("ms") is not None]
    if rs:
        w = sum(r.get("win") or 0 for r in rs) / len(rs)
        vq, sq, tq = qs([r["vadMs"] / 1000 for r in rs], (0.5, 0.95)), qs([r["solveMs"] / 1000 for r in rs], (0.5, 0.95)), qs([r["ms"] / 1000 for r in rs], (0.5, 0.95))
        P("| %s | %.1f | %s / %s | %s / %s | %s / %s |" % (eng, w, f1(vq[0]), f1(vq[1]), f1(sq[0]), f1(sq[1]), f1(tq[0]), f1(tq[1])))

# ------------------------------------------------------------------ 6. erros do alinhador
def top_reasons(eng, n=20):
    c, ex = Counter(), {}
    for r in V:
        if r["engine"] != eng or r.get("cls") != "recusou" or r["type"] == "X":
            continue
        reason = (r.get("reason") or "").strip().strip(";")
        for cl in [x.strip() for x in reason.split(";") if x.strip()]:
            k = re.sub(r"\d+([.,]\d+)?", "N", cl)
            c[k] += 1
            ex.setdefault(k, []).append("%s/%s (z=%s)" % (r["title"], r["var"], f1(r.get("z"))))
    return c.most_common(n), ex


P("\n## 6. As 20 razoes de recusa mais comuns (variantes S recusadas: deviam ter sido corrigidas)\n")
P("Cada recusa pode ter varias clausulas (o motor junta os motivos); conta-se cada clausula.\n")
for eng in ENG:
    tr, ex = top_reasons(eng)
    nrec = cnt([r for r in V if r["engine"] == eng and r["type"] == "S"], lambda r: r.get("cls") == "recusou")
    P("\n**%s** (%d variantes S recusadas)\n" % (eng, nrec))
    P("| # | clausula | vezes | exemplos |\n|---|---|---|---|")
    for i, (k, n) in enumerate(tr, 1):
        P("| %d | %s | %d | %s |" % (i, k, n, "; ".join(ex[k][:3])))
# razao principal pela recusa completa
P("\n**Motivo completo (todas as clausulas juntas) mais comum, so S recusadas**\n")
for eng in ENG:
    c = Counter()
    for r in V:
        if r["engine"] == eng and r.get("cls") == "recusou" and r["type"] == "S":
            c[re.sub(r"\d+([.,]\d+)?", "N", (r.get("reason") or "").strip())] += 1
    P("\n%s:\n" % eng)
    for k, n in c.most_common(8):
        P("- %d x %s" % (n, k or "(sem motivo)"))

# ------------------------------------------------------------------ 7. aceites errados / falsos / mexidas (lista completa)
P("\n## 7. TODOS os aceites errados, aceites falsos e legendas certas mexidas\n")
for eng in ENG:
    bad = [r for r in V if r["engine"] == eng and r.get("cls") in ("ERRADO", "FALSO", "FALSO-ok", "MEXIDA")]
    P("\n### %s: %d casos\n" % (eng, len(bad)))
    if bad:
        P("| titulo | tipo | variante | classe | kind | escala | desl. aplicado (ms) | z | janelas conc. | erro max / p95 (ms) | motivo |\n|---|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted(bad, key=lambda r: (r["cls"], r["title"], r["var"])):
        t = titles.get(r["title"], {})
        P("| %s (%s, %s) | %s | `%s` | %s | %s | %s | %s | %s | %s/%s | %s / %s | %s |" % (r["title"], t.get("kind"), t.get("year"), r["type"], r["var"], r["cls"], r.get("kind"), f1(r.get("scale"), 5), f1(r.get("raw"), 0), f1(r.get("z")),
                                                                                           r.get("wa"), r.get("wv"), f1(r.get("eMax"), 0), f1(r.get("eP95"), 0), (r.get("reason") or "")[:60]))
    for r in sorted(bad, key=lambda r: (r["cls"], r["title"], r["var"])):
        P("\n- **%s / %s / %s** diagnostico completo: `%s`" % (r["title"], r["var"], r["cls"], (r.get("diag") or "")[:900]))

# ------------------------------------------------------------------ 8. bugs
P("\n## 8. Bugs e comportamentos estranhos achados na execucao\n")
exc = [r for r in V + V2 if r.get("kind") in ("ERROR", "TIMEOUT")]
P("- Excecoes / timeouts do alinhador: **%d** variantes (de %d)." % (len(exc), len(V) + len(V2)))
for r in exc[:25]:
    P("  - %s/%s/%s: %s `%s`" % (r["engine"], r["title"], r["var"], r["kind"], (r.get("exc") or "")[:300]))
nanr = [r for r in V + V2 if r.get("nan")]
P("- NaN/Infinity em campos do resultado: **%d** variantes." % len(nanr))
for r in nanr[:10]:
    P("  - %s/%s/%s: %s" % (r["engine"], r["title"], r["var"], r["nan"]))
skipped = [r for r in V if r.get("kind") == "SKIPPED"]
P("- Variantes que o app nem analisaria (guarda: poucas falas ou video < 8 min): %d" % len(skipped))
eng_fail = [(t["id"], e, v) for t in titles.values() for e, v in (t.get("engines") or {}).items() if v.get("rc") not in (0, None)]
P("- Processos Java dos motores que terminaram com erro: **%d**" % len(eng_fail))
for tid, e, v in eng_fail[:10]:
    P("  - %s %s rc=%s: %s" % (tid, e, v.get("rc"), (v.get("tail") or "")[-200:].replace("\n", " | ")))

# parser de legendas
fm = []
for t in titles.values():
    for r in load_jsonl(os.path.join(t["_dir"], "subfmt.jsonl")):
        fm.append(r)
if fm:
    P("\n### Parser de legendas do app (SubtitleText.decode + Cue.parseSrt) sobre a referencia reescrita em varios formatos\n")
    byf = defaultdict(lambda: [0, 0, 0, []])
    for r in fm:
        x = byf[r["fmt"]]
        x[0] += 1
        x[1] += 1 if r.get("ok") == 1 else 0
        x[2] += 1 if r.get("ok") == 2 else 0
        if r.get("ok") != 1 and len(x[3]) < 2:
            x[3].append("%s: %s" % (r["title"], r.get("problem")))
    P("| formato | titulos | ok | tempos certos mas texto diferente | exemplo do problema |\n|---|---|---|---|---|")
    for k, (n, ok, ok2, ex) in byf.items():
        P("| %s | %d | %d | %d | %s |" % (k, n, ok, ok2, "; ".join(ex)[:200]))
# legendas reais baixadas
real = []
for t in titles.values():
    for c in t.get("sub_candidates") or []:
        c["_t"] = t["id"]
        real.append(c)
if real:
    P("\n### Legendas reais baixadas (%d arquivos de %d titulos) e o parser do app\n" % (len(real), len(titles)))
    dec0 = [c for c in real if c.get("app") and not c["app"].get("decoded")]
    pb = [c for c in real if c.get("parser_bug")]
    pdiff = [c for c in real if c.get("parser_diff")]
    uns = [c for c in real if (c.get("app") or {}).get("unsorted", 0) > 0]
    ovl = [c for c in real if (c.get("app") or {}).get("overlap", 0) > 5]
    moj = [c for c in real if (c.get("app") or {}).get("mojibake")]
    vtt = [c for c in real if (c.get("app") or {}).get("vtt")]
    nodur = [c for c in real if (c.get("app") or {}).get("nodur", 0) > 3]
    P("- decode do app devolveu null (descarta): %d; destes, o python leu >= 100 falas (legenda valida que o app descartaria): %d" % (len(dec0), len(pb)))
    for c in pb[:10]:
        P("  - %s `%s` (%s, %d bytes): %s" % (c["_t"], c.get("id"), c.get("src"), c.get("bytes", 0), c["parser_bug"]))
    P("- numero de falas do app diferente do python em >3%%: %d" % len(pdiff))
    for c in pdiff[:10]:
        P("  - %s `%s`: %s" % (c["_t"], c.get("id"), c["parser_diff"]))
    P("- legendas com falas fora de ordem no arquivo: %d; com sobreposicao > 200 ms em mais de 5 falas: %d; com fim <= inicio em mais de 3 falas: %d; mojibake que sobrou: %d; VTT: %d" % (len(uns), len(ovl), len(nodur), len(moj), len(vtt)))
    for c in uns[:8]:
        P("  - fora de ordem: %s `%s` (%d falas fora de ordem)" % (c["_t"], c.get("id"), c["app"]["unsorted"]))
    langs = Counter(("PT (parser do app)" if (c.get("app") or {}).get("pt") else "nao-PT (" + str(c.get("text_lang")) + ")") for c in real if c.get("cues") is None and c.get("app", {}).get("decoded"))
    P("- idioma das legendas decodificadas segundo o filtro do app (looksPortuguese): " + ", ".join("%s=%d" % kv for kv in langs.most_common()))

# casos de midia
P("\n### Casos especiais de midia\n")
tl = [t for t in titles.values() if t.get("status") == "ok"]
m2 = [t for t in tl if (t.get("probe") or {}).get("n_audio", 1) >= 2]
m51 = [t for t in tl if any((a.get("ch") or 0) >= 6 for a in (t.get("probe") or {}).get("audio", [])[:1])]
mlong = [t for t in tl if t.get("long")]
msil = [t for t in tl if (t.get("silent_open_s") or 0) >= 60]
P("- titulos com 2+ faixas de audio: %d; com 5.1 (6+ canais) na faixa 1: %d; filmes de mais de 3 h: %d; com abertura silenciosa >= 60 s: %d" % (len(m2), len(m51), len(mlong), len(msil)))


def outcome(ts, eng="drift"):
    ids = {t["id"] for t in ts}
    rs = [r for r in V if r["engine"] == eng and r["title"] in ids and r["type"] == "S"]
    return "S certas %s, erradas %d (n=%d)" % (rate(cnt(rs, lambda r: r.get("cls") == "certo"), len(rs)), cnt(rs, lambda r: r.get("cls") == "ERRADO"), len(rs))


for nm, ts in (("2+ faixas", m2), ("5.1", m51), ("> 3 h", mlong), ("abertura silenciosa >= 60 s", msil)):
    if ts:
        P("  - %s (%s): main: %s | deriva: %s" % (nm, ", ".join(t["id"] for t in ts[:6]), outcome(ts, "main"), outcome(ts, "drift")))
if V2:
    P("  - faixa 2 de audio (variantes certa/+3/-2/1.001): " + "; ".join("%s: %s" % (e, "certas %d de %d" % (cnt([r for r in V2 if r["engine"] == e and r["type"] == "S"], lambda r: r.get("cls") == "certo"), len([r for r in V2 if r["engine"] == e and r["type"] == "S"]))) for e in ENG))

# ------------------------------------------------------------------ dados e graficos
rows = [{k: v for k, v in r.items() if k not in ("diag",)} for r in V]
json.dump(rows, open(os.path.join(out, "variantes.json"), "w"), ensure_ascii=False)
json.dump([{k: v for k, v in t.items() if not k.startswith("_") and k not in ("sub_candidates",)} for t in titles.values()], open(os.path.join(out, "titulos.json"), "w"), ensure_ascii=False, indent=0)
open(os.path.join(out, "resumo-300.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    COL = {"main": "#6b7280", "drift": "#2563eb"}
    # a) barras aceita/certa, antigos x novos, main x deriva (variantes S)
    fig, ax = plt.subplots(1, 2, figsize=(11, 4), sharey=True)
    for i, (nm, f) in enumerate((("aceita", lambda r: r.get("acc") == 1), ("certa", lambda r: r.get("cls") == "certo"))):
        xs = []
        for gi, (gn, gf) in enumerate(GROUPS[1:]):
            for ei, eng in enumerate(ENG):
                S = sel(eng, "S", grp=gf)
                v = 100.0 * cnt(S, f) / len(S) if S else 0
                ax[i].bar(gi * 3 + ei, v, color=COL[eng], label=ENG_NAME[eng] if gi == 0 else None)
                ax[i].text(gi * 3 + ei, v + 1, "%.0f" % v, ha="center", fontsize=8)
        ax[i].set_xticks([0.5, 3.5, 6.5])
        ax[i].set_xticklabels([g[0] for g in GROUPS[1:]], fontsize=8)
        ax[i].set_title("variantes deslocadas/deformadas: %s (%%)" % nm, fontsize=10)
    ax[0].legend(fontsize=8)
    plt.tight_layout()
    plt.savefig(os.path.join(out, "plots", "aceita_certa_antigos_novos.png"), dpi=130)
    plt.close()
    # b) por familia
    fams = FAMS_S
    fig, ax = plt.subplots(figsize=(11, 4.2))
    w = 0.38
    for ei, eng in enumerate(ENG):
        vals = []
        for fam in fams:
            rs = [r for r in V if r["engine"] == eng and r["fam"] == fam]
            vals.append(100.0 * cnt(rs, lambda r: r.get("cls") == "certo") / len(rs) if rs else 0)
        ax.bar([i + ei * w for i in range(len(fams))], vals, w, color=COL[eng], label=ENG_NAME[eng])
    ax.set_xticks([i + w / 2 for i in range(len(fams))])
    ax.set_xticklabels([f.replace(" (", "\n(") for f in fams], fontsize=7)
    ax.set_ylabel("% corrigidas certas")
    ax.legend(fontsize=8)
    plt.tight_layout()
    plt.savefig(os.path.join(out, "plots", "certas_por_familia.png"), dpi=130)
    plt.close()
    # c) histogramas de z
    fig, ax = plt.subplots(1, 2, figsize=(11, 4), sharey=False)
    for i, eng in enumerate(ENG):
        bins = [x * 1.0 for x in range(0, 41, 1)]
        for cls, col, nm in (("certo", "#16a34a", "corrigida certa"), ("recusou", "#9ca3af", "recusada (S)"), ("ERRADO", "#dc2626", "aceite errado"), ("FALSO", "#f59e0b", "aceite falso")):
            z = [min(40.0, r["z"]) for r in V if r["engine"] == eng and r.get("z") is not None and (r.get("cls") == cls or (cls == "FALSO" and r.get("cls") == "FALSO-ok"))]
            if z:
                ax[i].hist(z, bins=bins, alpha=0.6, color=col, label="%s (%d)" % (nm, len(z)))
        ax[i].axvline(9, color="k", ls="--", lw=0.8)
        ax[i].set_title("z, %s (linha = porta z>=9)" % ENG_NAME[eng], fontsize=9)
        ax[i].set_xlabel("z")
        ax[i].legend(fontsize=7)
    plt.tight_layout()
    plt.savefig(os.path.join(out, "plots", "z_histograma.png"), dpi=130)
    plt.close()
    # d) tempo
    fig, ax = plt.subplots(figsize=(7, 3.8))
    for eng in ENG:
        t = [min(r["ms"] / 1000, 60) for r in V if r["engine"] == eng and r.get("ms") is not None]
        if t:
            ax.hist(t, bins=40, alpha=0.6, color=COL[eng], label=ENG_NAME[eng])
    ax.set_xlabel("tempo de analise (s, CPU do runner)")
    ax.legend(fontsize=8)
    plt.tight_layout()
    plt.savefig(os.path.join(out, "plots", "tempo_analise.png"), dpi=130)
    plt.close()
except Exception as e:
    print("graficos falharam:", e)
print("resumo:", os.path.join(out, "resumo-300.md"), "linhas", len(L))
