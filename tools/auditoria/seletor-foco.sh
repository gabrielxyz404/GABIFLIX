#!/bin/bash
# Prova do salto de foco na janela de fontes da TV quando um addon lento responde (antes x depois da correção).
# uso: seletor-foco.sh <apk> <rótulo>   -> auditoria-out/seletor-<rótulo>.txt e fotos
APK=$1; LABEL=$2
PKG=io.github.gabrielxyz404.gabiflix
OUT=auditoria-out; mkdir -p $OUT
R=$OUT/seletor-$LABEL.txt; : > $R
focused() {   # limites e tipo do nó com foco na janela
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml /tmp/u.xml >/dev/null 2>&1
  python3 - <<'PY'
import re,sys
x=open('/tmp/u.xml',encoding='utf-8',errors='replace').read()
nodes=re.findall(r'<node [^>]*>',x)
foc=[n for n in nodes if 'focused="true"' in n]
rows=[n for n in nodes if 'focusable="true"' in n]
def b(n):
    m=re.search(r'bounds="([^"]+)"',n); return m.group(1) if m else '?'
def c(n):
    m=re.search(r'class="([^"]+)"',n); return m.group(1).split('.')[-1] if m else '?'
def t(n):
    m=re.search(r' text="([^"]*)"',n); return m.group(1) if m else ''
print('focado=' + ('; '.join(f'{c(n)} {b(n)} "{t(n)}"' for n in foc) if foc else 'NENHUM') + f' | focaveis={len(rows)}')
PY
}
adb uninstall $PKG >/dev/null 2>&1
adb install -r -g "$APK" >/dev/null || { echo "instalação falhou" | tee -a $R; exit 1; }
adb shell am force-stop $PKG
adb shell am start -n $PKG/.tv.TvHomeActivity --ez gfTestPicker true --ez gfTestPickerLate true >/dev/null
sleep 3
adb shell input keyevent DPAD_DOWN; sleep 0.5; adb shell input keyevent DPAD_DOWN; sleep 1
echo "t=4s (1 provedor, 2 descidas):    $(focused)" | tee -a $R
adb exec-out screencap -p > $OUT/seletor-$LABEL-1.png
sleep 2.5
echo "t=7s (depois da resposta de 5 s):  $(focused)" | tee -a $R
adb exec-out screencap -p > $OUT/seletor-$LABEL-2.png
sleep 4.5
echo "t=12s (depois da resposta de 9 s): $(focused)" | tee -a $R
adb exec-out screencap -p > $OUT/seletor-$LABEL-3.png
