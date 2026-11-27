#!/bin/bash
# fecha a medição de uma versão: custo de memLine() na thread principal e o resumo (mediana de cada número)
# uso: tv-medir-final.sh <apk> <rótulo>
APK=$1; LABEL=$2
PKG=io.github.gabrielxyz404.gabiflix
OUT=auditoria-out; mkdir -p $OUT
adb uninstall $PKG >/dev/null 2>&1
adb install -r -g "$APK" >/dev/null || { echo "instalação falhou"; exit 1; }
# custo de memLine() na thread principal (só o APK novo tem o gancho; o do base ignora o extra)
adb shell am force-stop $PKG; adb logcat -c
adb shell am start -n $PKG/.MainActivity --es gfCrashTest memline >/dev/null; sleep 6
adb logcat -d -s GFAudit:I | tail -3 > $OUT/$LABEL-memline.txt

# resumo: média de cada número
python3 - "$OUT" "$LABEL" <<'PY'
import re,sys,glob,collections
out,label=sys.argv[1:3]
acc=collections.defaultdict(list)
for f in sorted(glob.glob(f"{out}/{label}-[0-9]*.txt")):
    sec=''
    for l in open(f):
        l=l.rstrip()
        if l.startswith('== '): sec=l[3:]; continue
        m=re.match(r'(\w+): (\d+)',l)            # arranque_TotalTime: 123
        if m and not sec: acc[m.group(1)].append(float(m.group(2))); continue
        m=re.match(r'Total frames rendered: (\d+)',l)
        if m: acc[sec+'.quadros'].append(float(m.group(1)))
        m=re.match(r'Janky frames: (\d+) \(([\d.]+)%\)',l)
        if m: acc[sec+'.jank_pct'].append(float(m.group(2)))
        m=re.match(r'(\d+)th percentile: (\d+)ms',l)
        if m: acc[sec+f'.p{m.group(1)}_ms'].append(float(m.group(2)))
        m=re.match(r'\s*(Java Heap|Native Heap|Graphics|TOTAL PSS|TOTAL):\s+(\d+)',l)
        if m: acc[sec+'.'+m.group(1).replace(' ','_')+'_kb'].append(float(m.group(2)))
with open(f"{out}/resumo-{label}.txt",'w') as o:
    for k,v in sorted(acc.items()):
        v2=sorted(v); o.write(f"{k}\tmediana={v2[len(v2)//2]:g}\tn={len(v)}\tvalores={','.join(f'{x:g}' for x in v)}\n")
print(open(f"{out}/resumo-{label}.txt").read())
PY
