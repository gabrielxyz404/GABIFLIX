#!/usr/bin/env python3
"""Resume /proc/PID/smaps por nome de mapeamento: Pss, Private_Dirty e Rss (KB), do maior para o menor, e o total por tipo (anon, arquivo, [heap], scudo...)."""
import re, sys, collections
cur = None
tot = collections.defaultdict(lambda: [0, 0, 0, 0])
for line in open(sys.argv[1], errors='replace'):
    m = re.match(r'^[0-9a-f]+-[0-9a-f]+ \S+ \S+ \S+ \S+ ?(.*)$', line)
    if m:
        name = m.group(1).strip() or '[anon]'
        name = re.sub(r'\.(\d+)$', '', name)
        cur = name; tot[cur][3] += 1
        continue
    m = re.match(r'^(Pss|Private_Dirty|Rss):\s+(\d+) kB', line)
    if m and cur:
        tot[cur][{'Pss': 0, 'Private_Dirty': 1, 'Rss': 2}[m.group(1)]] += int(m.group(2))
rows = sorted(tot.items(), key=lambda kv: -kv[1][0])
print('%10s %10s %10s %6s  %s' % ('Pss', 'PrivDirty', 'Rss', 'regs', 'mapeamento'))
for k, v in rows[:30]:
    print('%10d %10d %10d %6d  %s' % (v[0], v[1], v[2], v[3], k))
print('TOTAL Pss', sum(v[0] for v in tot.values()))
