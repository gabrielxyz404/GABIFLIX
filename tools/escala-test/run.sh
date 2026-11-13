#!/bin/bash
# $1 = rótulo (antes/depois)  $2 = perfil (tel|tv)
# Abre o PlayerActivity com vídeo 16:9 + legenda e tira fotos nos 3 modos de "Tela".
set -x
PKG=io.github.gabrielxyz404.gabiflix
L=${1:-antes}; P=${2:-tel}
mkdir -p shots
adb install -r android/app/build/outputs/apk/release/app-release.apk
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
adb shell wm size > shots/$P-wm.txt
snap() { adb exec-out screencap -p > "shots/escala-$L-$P-$1.png"; }
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
tela_xy() { # centro do botão "Tela: ..."
  dump | python3 -c "
import sys,re
x=sys.stdin.read()
m=re.search(r'text=\"(Tela: [^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x)
print('%d %d %s'%((int(m.group(2))+int(m.group(4)))//2,(int(m.group(3))+int(m.group(5)))//2,m.group(1).replace(' ','_')) if m else '')"
}
adb shell am force-stop $PKG
adb logcat -c
adb root; sleep 3
if adb shell id | grep -q uid=0; then
  adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/teste.mkv" --esa subs "http://10.0.2.2:8000/teste.srt" --es type movie --es id tt0000001
else
  echo "sem root: usa TvHomeActivity + legenda embutida"
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/emb.mkv"
fi
sleep 25
snap 0-inicio
for i in 1 2 3; do
  xy=$(tela_xy)
  if [ -z "$xy" ]; then
    adb shell input tap 600 300; sleep 1; xy=$(tela_xy)
  fi
  echo "tela_xy=$xy"
  snap $i-controles-antes
  set -- $xy
  adb shell input tap $1 $2
  sleep 1
  snap $i-controles-depois-$3
  sleep 8
  snap $i-modo
done
adb logcat -d | grep -iE "vlc|PlayerActivity|gabiflix|AndroidRuntime" | tail -80 > shots/$P-log.txt
exit 0
