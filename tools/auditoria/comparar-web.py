#!/usr/bin/env python3
"""Junta os resultados de web-perf.js (base x novo) numa tabela em Markdown. Uso: comparar-web.py <pasta>"""
import json, glob, os, sys

pasta = sys.argv[1] if len(sys.argv) > 1 else 'out'
CAMPOS = [
    ('homeCardsMs', 'primeiro cartão da Home (ms)'), ('dclMs', 'DOMContentLoaded (ms)'), ('loadMs', 'evento load (ms)'),
    ('serviceOpenMs', 'abrir o serviço até haver pôsteres (ms)'), ('gridOpenMs', 'abrir a aba Filmes (ms)'),
    ('scrollAvgMs', 'rolagem: tempo médio por quadro (ms)'), ('scrollP95Ms', 'rolagem: quadro p95 (ms)'), ('scrollMaxMs', 'rolagem: pior quadro (ms)'),
    ('scrollOver33', 'rolagem: quadros > 33 ms (de 149)'), ('scrollOver50', 'rolagem: quadros > 50 ms'),
    ('heapMb', 'memória JS (MB)'), ('nodes', 'nós do DOM no fim'), ('moCalls', 'chamadas de MutationObserver'), ('moMs', 'tempo nos MutationObserver (ms)'),
    ('longTasks', 'tarefas longas (> 50 ms)'), ('longTaskMs', 'tempo em tarefas longas (ms)'), ('taskMs', 'CPU total da página (ms)'),
    ('scriptMs', 'JS (ms)'), ('layoutMs', 'layout (ms)'), ('styleMs', 'estilo (ms)'), ('resKb', 'transferido (KB, comprimido)'), ('resCount', 'pedidos'),
    ('castRequested', 'pediu o script do Chromecast'),
]
dados = {}
for f in sorted(glob.glob(os.path.join(pasta, 'web-*-cast*.json'))):
    nome = os.path.basename(f)[4:-5]            # mac-base-cast0
    perfil, versao, cast = nome.split('-')
    try:
        dados[(perfil, cast, versao)] = json.load(open(f))['summary'] or {}
    except Exception:
        dados[(perfil, cast, versao)] = {}

print('# Site (celular e Mac): base x novo\n')
print('Chromium com o processador atrasado (Mac 2x, celular 4x), 3 execuções, mediana. "cast1500" = o script do Chromecast demora 1,5 s a responder.\n')
for perfil in ('mac', 'and'):
    for cast in ('cast0', 'cast1500'):
        b, n = dados.get((perfil, cast, 'base')), dados.get((perfil, cast, 'novo'))
        if not b and not n:
            continue
        print(f'## {"Mac 1440x900" if perfil == "mac" else "Celular 412x915"} - {cast}\n')
        print('| medida | base | novo | diferença |')
        print('|---|---:|---:|---:|')
        for k, rot in CAMPOS:
            vb, vn = (b or {}).get(k), (n or {}).get(k)
            if vb is None and vn is None:
                continue
            d = ''
            if isinstance(vb, (int, float)) and isinstance(vn, (int, float)):
                d = f'{vn - vb:+.1f}' + (f' ({(vn - vb) / vb * 100:+.0f}%)' if vb else '')
            print(f'| {rot} | {vb} | {vn} | {d} |')
        print()

# onde o processador gasta (só o commit novo, Mac sem atraso do Chromecast)
for perfil in ('mac', 'and'):
    f = os.path.join(pasta, f'web-{perfil}-novo-cast0.json')
    if os.path.exists(f):
        try:
            r = json.load(open(f))['runs'][0]['cpu']
        except Exception:
            continue
        print(f'## CPU por arquivo ({perfil}, 1ª execução, total {r["totalMs"]} ms de amostras)\n')
        for k, ms in r['topFiles'][:10]:
            print(f'- {k}: {ms} ms')
        print(f'\n### Funções que mais gastam ({perfil})\n')
        for k, ms in r['topFunctions'][:12]:
            print(f'- {k}: {ms} ms')
        print()
