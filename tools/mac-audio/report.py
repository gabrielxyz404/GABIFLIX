#!/usr/bin/env python3
"""Resume a prova no Mac: por cenario, as linhas do motor (AUDIOSYNC), tempos, CPU/memoria do processo e rede. Uso: report.py PASTA"""
import glob, os, re, sys
d = sys.argv[1]
out = []
def P(s=""):
    print(s); out.append(s)

def tstamp(line):
    m = re.match(r"(\d+\.\d+) (.*)", line)
    return (float(m.group(1)), m.group(2)) if m else (None, line)

def cpu_stats(name, t0, t1):
    f = os.path.join(d, "cpu-%s.txt" % name)
    if not os.path.exists(f) or t0 is None or t1 is None: return None
    rows = []
    for l in open(f):
        p = l.split()
        if len(p) == 3:
            try: rows.append((int(p[0]), float(p[1]), int(p[2])))
            except ValueError: pass
    base = [r for r in rows if t0 - 12 <= r[0] <= t0]
    win = [r for r in rows if t0 <= r[0] <= t1 + 1]
    if len(base) < 3 or len(win) < 2: return None
    rb = (base[-1][1] - base[0][1]) / max(1, base[-1][0] - base[0][0])
    rw = (win[-1][1] - win[0][1]) / max(1, win[-1][0] - win[0][0])
    return "CPU do processo: antes %.2f nucleos; durante a analise (%.0f s) %.2f nucleos; CPU extra %.1f s | RSS antes %.0f MB, maximo %.0f MB, depois %.0f MB" % (
        rb, t1 - t0, rw, (rw - rb) * (t1 - t0), base[-1][2] / 1024.0, max(r[2] for r in win) / 1024.0, win[-1][2] / 1024.0)

for f in ["aparelho.txt", "tamanho.txt", "pre-java.txt", "pre-swift.txt"]:
    p = os.path.join(d, f)
    if os.path.exists(p):
        P("## " + f); P(open(p, errors="replace").read().strip()); P()

for f in sorted(glob.glob(os.path.join(d, "log-*.txt"))):
    name = os.path.basename(f)[4:-4]
    P("\n## cenario %s" % name)
    L = [tstamp(l.rstrip("\n")) for l in open(f, errors="replace")]
    keys = ("AUDIOSYNC", "sincronização automática", "atraso da legenda", "clique no menu", "estado: faixa", "legendas=", "comando de teste")
    t0 = t1 = None
    for t, s in L:
        if "AUDIOSYNC começando" in s and t0 is None: t0 = t
        if ("AUDIOSYNC resultado" in s or "sincronização automática: status" in s) and t1 is None: t1 = t
        if any(k in s for k in keys[:5]) and not s.startswith("estado=") :
            P("  %s %s" % (("%.1f" % t) if t else "-", s[:420]))
    c = cpu_stats(name, t0, t1)
    if c: P("  " + c)
open(os.path.join(d, "resumo.txt"), "w").write("\n".join(out))
