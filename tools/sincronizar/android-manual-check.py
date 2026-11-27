#!/usr/bin/env python3
"""Android: confere o AJUSTE MANUAL pelo registro do player (adb logcat -v epoch).
Uso: android-manual-check.py REGISTRO --de EPOCH --ate EPOCH --passos +10000,+5000,+1000,-1000 [--depois EPOCH] [--tol 400] [--nome X]
 - entre --de e --ate (os toques), cada linha "atraso da legenda: N ms" tem de ser o valor anterior + o passo (a partir do último atraso mostrado antes);
 - depois de --ate (e até o fim do registro, ou até --depois-ate) toda linha mostrada ("legenda do app: tempo T ms, atraso N ms -> ...") tem de
   ficar a menos de --tol do último valor tocado: o atraso manual não é desfeito por nada (análise, acompanhamento da escala, cache, salto no filme).
Saída: PASS/FAIL e o resumo; código 0 se passou."""
import argparse, re, sys

ap = argparse.ArgumentParser()
ap.add_argument("log")
ap.add_argument("--de", type=float, required=True)
ap.add_argument("--ate", type=float, required=True)
ap.add_argument("--passos", required=True)
ap.add_argument("--depois-ate", type=float, default=1e18)
ap.add_argument("--tol", type=int, default=400)
ap.add_argument("--nome", default="")
ap.add_argument("--inclinacao", type=float, default=0.0, help="com escala ativa o atraso acompanha o filme: ms de atraso por ms de filme (ex.: -0.001001)")
a = ap.parse_args()
steps = [int(x) for x in a.passos.split(",") if x]
PRE = r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[A-Z]\s+\S+\s*:\s*"
shown, taps = [], []
for ln in open(a.log, encoding="utf-8", errors="replace"):
    m = re.match(PRE + r"legenda do app: tempo (-?\d+) ms, atraso (-?\d+) ms ->", ln)
    if m:
        shown.append((float(m.group(1)), int(m.group(2)), int(m.group(3))))
        continue
    m = re.match(PRE + r"atraso da legenda: (-?\d+) ms", ln)
    if m:
        taps.append((float(m.group(1)), int(m.group(2))))
prev = [(ep, d) for ep, t, d in shown if ep < a.de] + [(ep, d) for ep, d in taps if ep < a.de]
d0 = max(prev)[1] if prev else 0
mine = [(ep, d) for ep, d in taps if a.de <= ep <= a.ate]
exp, cur = [], d0
for s in steps:
    cur += s
    exp.append(cur)
got = [d for ep, d in mine]
ok_seq = len(got) >= len(exp) and got[-len(exp):] == exp if exp else True
final = exp[-1] if exp else d0
after = [(ep, t, d) for ep, t, d in shown if ep > a.ate and ep <= a.depois_ate]
# com escala ativa o atraso certo anda com o filme (-0,1% por segundo): a referência é o tempo da primeira fala mostrada depois dos toques
tref = after[0][1] if after else 0
bad = [(ep, t, d) for ep, t, d in after if abs(d - (final + a.inclinacao * (t - tref))) > a.tol]
res = "PASS" if ok_seq and not bad else "FAIL"
print("%s %s: atraso inicial %d ms; esperado %s; registrado %s; linhas mostradas depois dos toques: %d, fora de %d ms do valor final (%d ms): %d" % (
    res, a.nome, d0, exp, got[-max(len(exp), 1):], len(after), a.tol, final, len(bad)))
for ep, t, d in bad[:8]:
    print("  tempo %ds atraso mostrado %d ms (esperado %d)" % (t // 1000, d, final + a.inclinacao * (t - tref)))
sys.exit(0 if res == "PASS" else 1)
