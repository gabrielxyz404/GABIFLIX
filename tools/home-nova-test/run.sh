#!/bin/bash
# $1 = perfil (tel|tv)  $2 = apk  $3 = rótulo (antes|depois)
# Fotos reais da Início no emulador. TV: navegação só com as setas (foco passa por todos os serviços e entra num). Celular: abre o site da branch (servido em 10.0.2.2:8099) e toca num serviço.
# Mede também os quadros lentos (gfxinfo) durante a navegação. Nunca publica nada.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2}; L=${3:-depois}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 600000
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
snap() { sleep "${2:-1}"; adb exec-out screencap -p > "$OUT/$L-$P-$1.png"; }
focus() { adb shell uiautomator dump /sdcard/f.xml >/dev/null 2>&1; echo "$1: $(adb shell cat /sdcard/f.xml 2>/dev/null | python3 $UI focus)" >> $OUT/$L-$P-foco.txt; }
topact() { adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -1 | tr -s ' '; }
adb logcat -c
if [ "$P" = tv ]; then
  adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1
  snap 01-inicio 14; focus inicio
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent DPAD_RIGHT; sleep 0.9; snap 02-direita-$i 0.4; focus direita-$i; done
  adb shell input keyevent DPAD_RIGHT; sleep 0.7; focus fim-extra
  for i in 1 2 3 4 5 6 7 8 9 10; do adb shell input keyevent DPAD_LEFT; sleep 0.6; done
  snap 03-volta-ao-primeiro 0.5; focus volta-primeiro
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18; do adb shell input keyevent DPAD_RIGHT; adb shell input keyevent DPAD_LEFT; done  # rajada rápida: fluidez
  sleep 1; adb shell dumpsys gfxinfo $PKG > $OUT/$L-$P-gfxinfo.txt
  adb shell input keyevent DPAD_UP; sleep 0.8; snap 04-icones-topo 0.3; focus topo
  adb shell input keyevent DPAD_DOWN; sleep 0.8; snap 05-volta-aos-servicos 0.3; focus voltou-servico
  adb shell input keyevent DPAD_RIGHT; sleep 1
  adb shell input keyevent DPAD_CENTER; snap 06-entrou-no-servico 8; focus entrou; echo "entrou: $(topact)" >> $OUT/$L-$P-foco.txt
  adb shell input keyevent BACK; snap 07-voltou 3; focus voltou; echo "voltou: $(topact)" >> $OUT/$L-$P-foco.txt
  # cada serviço abre o catálogo certo
  for i in 0 1 2 3 4 5 6 7 8; do
    adb shell am force-stop $PKG; sleep 1
    adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1; sleep 7
    for k in $(seq 1 $i); do adb shell input keyevent DPAD_RIGHT; sleep 0.5; done
    adb shell input keyevent DPAD_CENTER; sleep 6
    echo "servico-$i: $(topact)" >> $OUT/$L-$P-foco.txt
    adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; echo "servico-$i titulo: $(adb shell cat /sdcard/u.xml 2>/dev/null | python3 $UI texts | head -3 | tr '\n' '|')" >> $OUT/$L-$P-foco.txt
    [ $i = 0 ] || [ $i = 8 ] && snap 08-catalogo-$i 0.3
  done
else
  adb shell am start -n $PKG/.MainActivity --es gfTestWeb "http://10.0.2.2:8099/index.html"
  snap 01-abertura 4
  snap 02-inicio 25
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null | python3 $UI texts > $OUT/$L-$P-textos-inicio.txt
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4; do adb shell input swipe 900 1700 900 900 200; sleep 0.5; adb shell input swipe 900 900 900 1700 200; sleep 0.5; done
  adb shell dumpsys gfxinfo $PKG > $OUT/$L-$P-gfxinfo.txt
  snap 03-depois-rolar 1
  # toca num serviço (antes: o cartão grande; depois: o círculo) e fotografa o catálogo
  xy=$(adb shell cat /sdcard/u.xml | python3 $UI xy "Netflix")
  echo "toque Netflix em: $xy" >> $OUT/$L-$P-foco.txt
  if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell input tap 540 1100; fi
  snap 04-catalogo 10
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null | python3 $UI texts > $OUT/$L-$P-textos-catalogo.txt
  adb shell input keyevent BACK; snap 05-voltou 3
fi
adb logcat -d | grep -E "FATAL|AndroidRuntime: |E/AndroidRuntime" | grep -v "RuntimeInit\|boot image\|lock profiling\|Calling main\|Shutting down\|VM exiting" | head -40 > $OUT/$L-$P-crash.txt
adb logcat -d | grep -iE "chromium.*(CONSOLE|Uncaught)|TypeError|ReferenceError" | head -40 > $OUT/$L-$P-consola.txt
cat $OUT/$L-$P-foco.txt $OUT/$L-$P-crash.txt $OUT/$L-$P-consola.txt 2>/dev/null
exit 0
