#!/usr/bin/env python3
"""Android: confere se a linha que o app mostra na tela bate com a FALA do filme (igual ao check.py do Mac, mas lê o logcat do Android).
Lê o registro do player (linhas "legenda do app: tempo <ms> ms, atraso <ms> ms -> "texto"", com `adb logcat -v epoch`) e a legenda CERTA
(verdade-de-campo, alinhada ao áudio pelo alass). Para cada linha mostrada depois do instante --depois (epoch, s), a linha tem de ser a de uma fala da
legenda certa que está ativa no tempo do filme (± tolerância). Ignora os primeiros segundos depois de um salto no filme (--apos-salto).
Uso: android-check.py REGISTRO CERTA.srt --depois EPOCH [--tol 1500] [--min 0.95] [--nome X] [--so-ate EPOCH]
Saída: resumo no stdout; código 0 se a fração de acertos >= --min, 1 se não, 2 se não há linhas para conferir."""
import argparse, re, sys

ap = argparse.ArgumentParser()
ap.add_argument("log"); ap.add_argument("certa")
ap.add_argument("--depois", type=float, default=0.0)
ap.add_argument("--so-ate", type=float, default=1e18)
ap.add_argument("--tol", type=int, default=1500)
ap.add_argument("--min", type=float, default=0.95)
ap.add_argument("--nome", default="")
ap.add_argument("--apos-salto", type=float, default=4.0)
a = ap.parse_args()


def ms(t):
    h, m, s, z = re.match(r"(\d+):(\d+):(\d+)[,.](\d+)", t).groups()
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(z.ljust(3, "0")[:3])


def norm(t):
    t = re.sub(r"<[^>]*>|\{[^}]*\}", "", t)
    t = t.replace("\\N", " ").replace("\n", " ").replace("/", " ")
    return re.sub(r"[^0-9a-zA-ZÀ-ÿ]+", " ", t).strip().lower()


cues = []
txt = open(a.certa, encoding="utf-8-sig").read().replace("\r\n", "\n")
for blk in re.split(r"\n\s*\n", txt):
    m = re.search(r"(\d+:\d+:\d+[,.]\d+)\s*-->\s*(\d+:\d+:\d+[,.]\d+)\s*\n(.*)", blk, re.S)
    if m:
        cues.append((ms(m.group(1)), ms(m.group(2)), norm(m.group(3))))
cues.sort()

PRE = r"^(\d+\.\d+)\s+\d+\s+\d+\s+[A-Z]\s+\S+\s*:\s*"
seeks = []
lines = []
for ln in open(a.log, encoding="utf-8", errors="replace"):
    ln = ln.rstrip("\n")
    m = re.match(PRE + r"teste: ação do usuário seek", ln)
    if m:
        seeks.append(float(m.group(1)))
        continue
    m = re.match(PRE + r'legenda do app: tempo (-?\d+) ms, atraso (-?\d+) ms -> (.*)$', ln)
    if not m:
        continue
    ep, t, d, rest = float(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
    if ep < a.depois or ep > a.so_ate:
        continue
    if any(0 <= ep - sk <= a.apos_salto for sk in seeks):
        continue   # os primeiros segundos depois de saltar no filme: o atraso da escala ainda acompanha o novo ponto
    if rest.strip() == "(nada)" or not rest.startswith('"'):
        continue
    lines.append((ep, t, d, norm(rest.strip('"'))))

ok = bad = 0
detail = []
for ep, t, d, shown in lines:
    key = shown[:25]
    hit = False
    for s, e, tx in cues:
        if s - a.tol <= t <= e + a.tol and key and tx.startswith(key[:20]):
            hit = True
            break
    if hit:
        ok += 1
    else:
        bad += 1
        near = [s for s, e, tx in cues if tx.startswith(key[:20])]
        off = (min(near, key=lambda s: abs(s - t)) - t) / 1000.0 if near else None
        detail.append("  t=%ds atraso=%dms mostrou %r; a fala certa está %s" % (t // 1000, d, shown[:40], ("%+.1f s do instante" % off) if off is not None else "em lugar nenhum"))
tot = ok + bad
print("%s: %d linhas conferidas, %d certas, %d erradas (%.0f%% certas; tolerância ±%d ms)" % (a.nome or a.log, tot, ok, bad, 100.0 * ok / tot if tot else 0, a.tol))
for d in detail[:12]:
    print(d)
if tot == 0:
    sys.exit(2)
sys.exit(0 if ok / tot >= a.min else 1)
