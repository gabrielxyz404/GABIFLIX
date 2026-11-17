#!/usr/bin/env python3
"""Resume as medidas da prova: tempos (captura, VAD, solve), CPU do processo durante a analise contra a reproducao sozinha, memoria, rede e paradas.
Uso: report.py SHOTS_DIR PERFIL"""
import re, sys, os, glob
d, perfil = sys.argv[1], sys.argv[2]
clk = open(os.path.join(d, "clock.txt")).read() if os.path.exists(os.path.join(d, "clock.txt")) else ""
m = re.search(r"host=(\d+) dispositivo=(\d+)", clk)
skew = (int(m.group(1)) - int(m.group(2))) if m else 0   # somar ao relogio do dispositivo para ter o do host
out = []
def P(s=""):
    print(s); out.append(s)

def lines(f):
    r = []
    if os.path.exists(f):
        for l in open(f, errors="replace"):
            m = re.match(r"\s*(\d+\.\d+)\s+.*?GabiFlixPlayer\s*:\s*(.*)", l)
            if m: r.append((float(m.group(1)) + skew, m.group(2).strip()))
    return r

cpu = []
cf = os.path.join(d, "cpu-%s-1.txt" % perfil)
if os.path.exists(cf):
    for l in open(cf):
        p = l.split()
        if len(p) >= 3 and p[0].isdigit(): cpu.append((int(p[0]) + skew, int(p[1]) / 100.0, int(p[2]) if p[2].isdigit() else 0))
def readsrv(fn):
    r = []
    sf = os.path.join(d, fn)
    if os.path.exists(sf):
        for l in open(sf):
            m = re.match(r"([\d.]+) \S+ range=(\d+)-(\d+) sent=(\d+)", l)
            if m: r.append((float(m.group(1)), int(m.group(2)), int(m.group(4))))
    return r
srvs = {"fast": readsrv("server-bytes.log"), "slow": readsrv("server-bytes-lenta.log")}

P("# Medidas (%s) - aparelho: %s" % (perfil, open(os.path.join(d, "aparelho.txt")).read().strip() if os.path.exists(os.path.join(d, "aparelho.txt")) else "?"))
for f in sorted(glob.glob(os.path.join(d, "log-%s-*.txt" % perfil))):
    name = os.path.basename(f)[len("log-%s-" % perfil):-4]
    L = lines(f)
    P("\n## cenario %s" % name)
    for t, s in L:
        if not s.startswith("AUDIOSYNC"): continue
        if s.startswith("AUDIOSYNC") and not s.startswith("AUDIOSYNC captura"):
            P("  %.1f %s" % (t, s[:400]))
        elif s.startswith("AUDIOSYNC captura"):
            P("  %.1f %s" % (t, s))
    t0 = next((t for t, s in L if "AUDIOSYNC começando" in s or "AUDIOSYNC botão" in s), None)
    t1 = next((t for t, s in L if "AUDIOSYNC resultado" in s or "AUDIOSYNC botão" in s), None)
    if name.startswith("1-") and t0 and t1 and cpu:
        base = [c for c in cpu if t0 - 12 <= c[0] <= t0]
        win = [c for c in cpu if t0 <= c[0] <= t1 + 1]
        if len(base) >= 3 and len(win) >= 2:
            rb = (base[-1][1] - base[0][1]) / max(1, base[-1][0] - base[0][0])
            rw = (win[-1][1] - win[0][1]) / max(1, win[-1][0] - win[0][0])
            P("  CPU do processo: reproducao sozinha %.2f nucleos; durante a analise (%.0f s) %.2f nucleos; extra %.1f s de CPU" % (rb, t1 - t0, rw, (rw - rb) * (t1 - t0)))
            P("  PSS: antes %.0f MB, maximo durante a analise %.0f MB, depois %.0f MB" % (base[-1][2] / 1024.0, max(c[2] for c in win) / 1024.0, win[-1][2] / 1024.0))
    if t0 and t1:
        srv = srvs["slow" if name.startswith("6-") else "fast"]
        sb = sum(n for t, a, n in srv if t0 - 1 <= t <= t1 + 1)
        ps = sum(n for t, a, n in srv if t0 - 1 <= t <= t1 + 1 and a < 3_000_000)
        P("  rede (servidor): %.1f MB enviados entre o comeco e o fim da analise (%.0f s), incluindo a reproducao (~0,24 MB/s)" % (sb / 1e6, t1 - t0))
        P("  estimativa so da captura: %.1f MB" % (max(0, sb - 0.24e6 * (t1 - t0)) / 1e6))
open(os.path.join(d, "medidas-%s.txt" % perfil), "w").write("\n".join(out))
