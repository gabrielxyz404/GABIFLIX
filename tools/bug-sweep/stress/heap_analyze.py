#!/usr/bin/env python3
"""Le o trace do heapprofd (Perfetto) e diz ONDE a memoria nativa fica: por chamada (callsite), liquido = alocado - liberado.
Uso: heap_analyze.py heap.trace [pasta-com-as-libs-x86 (libvlc.so, libvlcjni.so)]  -> texto em stdout.
Colunas: liquido (bytes ainda vivos), contagem liquida, tamanho medio de cada alocacao, e o rastro (pilha) da chamada, do topo para baixo."""
import os, subprocess, sys
from perfetto.trace_processor import TraceProcessor

trace = sys.argv[1]
libdir = sys.argv[2] if len(sys.argv) > 2 else None
tp = TraceProcessor(trace=trace)

def q(sql):
    return list(tp.query(sql))

print('== processos com amostras')
for r in q("select p.upid, p.pid, p.name, count(*) n, sum(a.size) liquido, min(a.ts) t0, max(a.ts) t1 from heap_profile_allocation a join process p using(upid) group by p.upid"):
    print('upid=%s pid=%s %s: %d linhas, liquido=%.1f MB, %.0f s de perfil' % (r.upid, r.pid, r.name, r.n, (r.liquido or 0) / 1048576, ((r.t1 or 0) - (r.t0 or 0)) / 1e9))

print('\n== estatisticas do heapprofd / unwinding')
for r in q("select name, value from stats where (name like 'heap%' or name like '%unwind%' or name like '%stack_profile%') and value != 0"):
    print('%s = %s' % (r.name, r.value))

print('\n== liquido acumulado por dump (todos os processos)')
dumps = q("select ts, sum(size) s, sum(case when size>0 then size else 0 end) al, sum(case when size<0 then size else 0 end) fr from heap_profile_allocation group by ts order by ts")
cum = 0; t0 = dumps[0].ts if dumps else 0
for d in dumps:
    cum += d.s or 0
    print('t=%5.0f s  liquido acumulado=%8.1f MB   (neste intervalo: alocou %.1f MB, liberou %.1f MB)' % ((d.ts - t0) / 1e9, cum / 1048576, (d.al or 0) / 1048576, -(d.fr or 0) / 1048576))

# frames / mapeamentos
frames = {}
for r in q("select f.id, f.name, f.rel_pc, m.name mname, m.build_id from stack_profile_frame f left join stack_profile_mapping m on f.mapping = m.id"):
    frames[r.id] = (r.name, r.rel_pc, os.path.basename(r.mname or '?'))
callsite = {}
for r in q("select id, parent_id, frame_id, depth from stack_profile_callsite"):
    callsite[r.id] = (r.parent_id, r.frame_id)

def stack(cid, maxd=18):
    out = []
    while cid is not None and len(out) < maxd:
        p, f = callsite[cid]
        out.append(frames.get(f, (None, 0, '?')))
        cid = p
    return out

def sym(lib, pc):
    """nome da funcao pelo endereco no .so (addr2line precisa de simbolos; sem eles tenta o dynsym mais proximo via nm)"""
    if not libdir or not lib: return None
    path = os.path.join(libdir, lib)
    if not os.path.exists(path): return None
    try:
        s = subprocess.run(['addr2line', '-f', '-C', '-e', path, hex(pc)], capture_output=True, text=True, timeout=30).stdout.split('\n')[0].strip()
        if s and s != '??': return s
    except Exception: pass
    return None

_nm = {}
def nearest(lib, pc):
    if not libdir or not lib: return None
    path = os.path.join(libdir, lib)
    if not os.path.exists(path): return None
    if lib not in _nm:
        try:
            out = subprocess.run(['nm', '-D', '--defined-only', '-C', path], capture_output=True, text=True, timeout=60).stdout
            tab = []
            for ln in out.splitlines():
                parts = ln.split(None, 2)
                if len(parts) == 3:
                    try: tab.append((int(parts[0], 16), parts[2]))
                    except ValueError: pass
            tab.sort(); _nm[lib] = tab
        except Exception: _nm[lib] = []
    tab = _nm[lib]; best = None
    for a, n in tab:
        if a <= pc: best = (a, n)
        else: break
    return '%s+0x%x' % (best[1], pc - best[0]) if best else None

def show(cid):
    for name, pc, lib in stack(cid):
        n = name
        if not n: n = sym(lib, pc or 0) or nearest(lib, pc or 0)
        print('      %-18s +0x%-8x %s' % (lib[:18], pc or 0, n or '?'))

heaps = q("select distinct heap_name from heap_profile_allocation")
for h in heaps:
    hn = h.heap_name
    print('\n==================== heap "%s": maiores chamadas por memoria LIQUIDA' % hn)
    rows = q("""select callsite_id, sum(size) liq, sum(count) cnt,
                       sum(case when size>0 then size else 0 end) al, sum(case when count>0 then count else 0 end) ac
                from heap_profile_allocation where heap_name='%s' group by callsite_id order by liq desc limit 14""" % hn)
    tot = q("select sum(size) s from heap_profile_allocation where heap_name='%s'" % hn)[0].s or 0
    print('total liquido deste heap: %.1f MB' % (tot / 1048576))
    for r in rows:
        mean = (r.al / r.ac) if r.ac else 0
        print('\n  liquido=%.2f MB  contagem liquida=%d  alocou %d vezes, tamanho medio %.0f B  (callsite %d)' % (r.liq / 1048576, r.cnt, r.ac, mean, r.callsite_id))
        show(r.callsite_id)

print('\n== soma do liquido pela PRIMEIRA funcao fora de libc/libc++/linker (quem pediu a memoria)')
agg = {}
rows = q("select callsite_id, sum(size) liq from heap_profile_allocation group by callsite_id having liq > 0")
for r in rows:
    for name, pc, lib in stack(r.callsite_id, 40):
        if lib.startswith(('libc.so', 'libc++', 'libc_malloc', 'libdl', 'linker', 'libheapprofd')) or (name or '').startswith(('malloc', 'calloc', 'realloc', 'operator new', 'posix_memalign', 'memalign', 'je_', 'scudo', '__')):
            continue
        key = '%s %s' % (lib, (name or sym(lib, pc or 0) or nearest(lib, pc or 0) or '+0x%x' % (pc or 0)))
        agg[key] = agg.get(key, 0) + r.liq
        break
for k, v in sorted(agg.items(), key=lambda kv: -kv[1])[:25]:
    print('  %8.2f MB  %s' % (v / 1048576, k))
