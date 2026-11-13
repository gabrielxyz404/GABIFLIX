#!/usr/bin/env python3
"""Compara, registro a registro, a saída do Java (fonte da verdade) com a do Swift (porte para o Mac).
Uso: compara.py java.tsv swift.tsv [max_diffs]
Tolerâncias (só onde pedido): deslocamento (off/offs) ±10 ms; escala (scale) ±1e-5. Todo o resto tem de ser IDÊNTICO.
Saída 0 se não houver nenhuma diferença fora da tolerância."""
import sys, collections

def load(p):
    d = {}
    with open(p, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line:
                continue
            parts = line.split('\t')
            d[parts[0] + '\t' + parts[1]] = parts[2:]
    return d

def fields(vals):
    out = collections.OrderedDict()
    for v in vals:
        k, _, x = v.partition('=')
        out[k] = x
    return out

def cmp_field(name, a, b):
    """0 = idêntico, 1 = diferente mas dentro da tolerância, 2 = diferente"""
    if a == b:
        return 0
    try:
        if name == 'off':
            return 1 if abs(int(a) - int(b)) <= 10 else 2
        if name == 'offs' and a != '-' and b != '-':
            x, y = a.split(','), b.split(',')
            return 1 if len(x) == len(y) and all(abs(int(p) - int(q)) <= 10 for p, q in zip(x, y)) else 2
        if name == 'scale':
            return 1 if abs(float(a) - float(b)) <= 1e-5 else 2
    except ValueError:
        return 2
    return 2

def main():
    java, swift = load(sys.argv[1]), load(sys.argv[2])
    maxd = int(sys.argv[3]) if len(sys.argv) > 3 else 40
    stat = collections.defaultdict(lambda: [0, 0, 0, 0])  # total, idênticos, dentro da tolerância, diferentes
    diffs = []
    for key in sorted(set(java) | set(swift)):
        tag = key.split('\t')[0]
        s = stat[tag]
        s[0] += 1
        if key not in java or key not in swift:
            s[3] += 1
            diffs.append('%s: só no %s' % (key.replace('\t', ' '), 'Java' if key in java else 'Swift'))
            continue
        fa, fb = fields(java[key]), fields(swift[key])
        worst = 0
        why = []
        for name in list(fa) + [k for k in fb if k not in fa]:
            a, b = fa.get(name, '<falta>'), fb.get(name, '<falta>')
            r = cmp_field(name, a, b)
            if r:
                why.append('%s: java=%s swift=%s' % (name, a[:300], b[:300]))
            worst = max(worst, r)
        if worst == 0:
            s[1] += 1
        elif worst == 1:
            s[2] += 1
        else:
            s[3] += 1
            diffs.append('%s | %s' % (key.replace('\t', ' '), ' | '.join(why)))
    names = {'D': 'leitura/idioma/falas', 'F': 'alinhamento (Fit)', 'G': 'alinhamento sintético', 'GS': 'entradas sintéticas', 'P': 'pipeline (alignAll+rank)',
             'L': 'linhas de log do pipeline', 'S': 'busca no addon', 'N': 'parecença de nomes', 'K': 'consenso'}
    tot = [0, 0, 0, 0]
    print('## Paridade Java x Swift')
    for tag in sorted(stat):
        s = stat[tag]
        for i in range(4):
            tot[i] += s[i]
        print('- %-26s registros %6d | idênticos %6d | dentro da tolerância %5d | DIFERENTES %5d' % (names.get(tag, tag), s[0], s[1], s[2], s[3]))
    print('TOTAL: %d registros | idênticos %d | dentro da tolerância %d | DIFERENTES %d' % tuple(tot))
    if tot[0]:
        print('Idênticos: %.4f%%; idênticos ou dentro da tolerância: %.4f%%' % (100.0 * tot[1] / tot[0], 100.0 * (tot[1] + tot[2]) / tot[0]))
    for d in diffs[:maxd]:
        print('DIFERENÇA ' + d)
    if len(diffs) > maxd:
        print('... e mais %d diferenças' % (len(diffs) - maxd))
    sys.exit(1 if tot[3] else 0)

main()
