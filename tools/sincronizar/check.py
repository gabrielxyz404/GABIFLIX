#!/usr/bin/env python3
"""Confere se a linha que o app mostra na tela bate com a FALA do filme.
Lê o registro do player (linhas "legenda do app: t=<ms> atraso=<ms> "<texto>"") e a legenda CERTA (verdade-de-campo, alinhada ao áudio pelo alass).
Para cada linha mostrada depois do instante --depois (epoch, s), a linha tem de ser a de uma fala da legenda certa que está ativa em t (± tolerância).
Uso: check.py REGISTRO CERTA.srt --depois EPOCH [--tol 1500] [--min 0.9] [--nome X] [--so-ate EPOCH]
Saída: resumo no stdout; código 0 se a fração de acertos >= --min, 1 se não, 2 se não há linhas para conferir."""
import argparse, re, sys

ap = argparse.ArgumentParser()
ap.add_argument("log"); ap.add_argument("certa")
ap.add_argument("--depois", type=float, default=0.0)
ap.add_argument("--so-ate", type=float, default=1e18)
ap.add_argument("--tol", type=int, default=1500)
ap.add_argument("--min", type=float, default=0.9)
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

seeks = []
for ln in open(a.log, encoding="utf-8", errors="replace"):
    m = re.match(r"([\d.]+) seek: (\d+)", ln)
    if m:
        seeks.append(float(m.group(1)))
lines = []
for ln in open(a.log, encoding="utf-8", errors="replace"):
    m = re.match(r"([\d.]+) legenda do app: t=(-?\d+) atraso=(-?\d+) (.*)$", ln.rstrip("\n"))
    if not m:
        continue
    ep, t, d, rest = float(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
    if ep < a.depois or ep > a.so_ate:
        continue
    if any(0 <= ep - sk <= a.apos_salto for sk in seeks):
        continue   # os primeiros segundos depois de saltar no filme: o atraso da escala ainda acompanha o novo ponto
    if rest.strip() == "nada" or not rest.startswith('"'):
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
        # onde a fala dessa linha realmente está (para mostrar o erro em segundos)
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
