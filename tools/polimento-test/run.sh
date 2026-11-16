#!/bin/bash
# $1 = perfil (tel|tv), $2 = apk. Tira fotos da Home e do player (controles, linha do tempo) e mede abertura e fluidez. Nunca publica nada.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}
OUT=shots; mkdir -p $OUT
adb install -r "$APK"
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
snap() { sleep "${2:-1}"; adb exec-out screencap -p > "$OUT/$P-$1.png"; if [ "$P" = tv ]; then adb shell uiautomator dump /sdcard/f.xml >/dev/null 2>&1; echo "$1: $(adb shell cat /sdcard/f.xml 2>/dev/null | python3 tools/android-subs-test/ui.py focus)" >> $OUT/tv-foco.txt; fi; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.6; done; }
tlpos() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null | python3 -c "
import sys,re,xml.etree.ElementTree as ET
raw=sys.stdin.read(); i=raw.find('<?xml')
r=ET.fromstring(raw[i:raw.rfind('</hierarchy>')+12])
for n in r.iter('node'):
    cls=n.get('class',''); d=n.get('content-desc','')
    if 'SeekBar' in cls or d=='Linha do tempo':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
        y=(y1+y2)//2 if 'SeekBar' in cls else y2-58
        print(x1,x2,y); break
"; }
startms() { for i in 1 2 3; do adb shell am force-stop $PKG; sleep 2; adb shell am start -W -n "$1" | grep -E "TotalTime|WaitTime" | tr '\n' ' ' >> $OUT/$P-abertura.txt; echo >> $OUT/$P-abertura.txt; sleep 4; done; }
tapdesc() { adb shell uiautomator dump /sdcard/u2.xml >/dev/null 2>&1; xy=$(adb shell cat /sdcard/u2.xml 2>/dev/null | python3 tools/android-subs-test/ui.py xy "$1"); [ -n "$xy" ] && adb shell input tap $xy || echo "NAO ACHEI $1" >> $OUT/$P-escala.txt; }
escala() {  # video largo (2,39:1): fotos limpas (sem controles) dos 3 modos de tela, para ver que o video aparece e enche a tela como antes
  adb shell am force-stop $PKG; sleep 1
  if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/wide.mkv"
  else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/wide.mkv"; fi
  sleep 25
  [ "$P" = tel ] && adb shell input tap 1200 360 || key DPAD_DOWN
  sleep 1.5; snap escala-0-controles 0.2; sleep 5; snap escala-1-ajustar 0.5
  for m in 2-preencher 3-esticar; do
    [ "$P" = tel ] && adb shell input tap 1200 360 || key DPAD_DOWN
    sleep 1.5; tapdesc "Tela"; sleep 6; snap escala-$m 0.5
  done
}
adb logcat -c
if [ "$P" = tel ]; then
  adb shell am force-stop $PKG; sleep 1
  startms $PKG/.MainActivity
  adb shell am force-stop $PKG; sleep 1
  adb shell am start -n $PKG/.MainActivity
  snap abertura 1
  snap home 25
  # janelas de rolagem da home (fluidez)
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in 1 2 3 4; do adb shell input swipe 1000 600 300 600 180; sleep 0.5; done
  adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|50th|90th|95th|99th" > $OUT/$P-fluidez-home.txt
  adb root; sleep 3
  adb shell settings put secure immersive_mode_confirmations confirmed
  adb shell am force-stop $PKG; sleep 1
  adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/teste.mkv"
  snap player-carregando 2
  sleep 25
  snap player-sem-controles 1
  adb shell input tap 1200 360; snap player-controles 2
  read X1 X2 Y <<< "$(tlpos)"; echo "linha: $X1 $X2 $Y" > $OUT/$P-linha.txt
  if [ -n "$Y" ]; then
    XA=$((X1 + (X2 - X1) * 35 / 100)); XB=$((X1 + (X2 - X1) * 60 / 100))
    adb shell input motionevent DOWN $XA $Y; sleep 0.3
    adb shell input motionevent MOVE $XB $Y; sleep 0.5
    snap player-arrastando 0.3
    adb shell input motionevent UP $XB $Y; sleep 2
    snap player-depois-de-arrastar 1
  fi
  adb shell input tap 1900 540; sleep 0.12; adb shell input tap 1900 540
  snap player-toque-duplo 0.15
  sleep 4; snap player-sem-controles2 1
else
  adb shell am force-stop $PKG; sleep 1
  startms $PKG/.tv.TvHomeActivity
  adb shell am force-stop $PKG; sleep 1
  adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1
  snap abertura 0.5
  snap home 12
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for r in 1 2 3; do key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT; done
  snap home-depois 1
  adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|50th|90th|95th|99th" > $OUT/$P-fluidez-home.txt
  key DPAD_CENTER; snap catalogo 8
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for r in 1 2 3; do key DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_UP DPAD_LEFT DPAD_LEFT DPAD_LEFT; done
  snap catalogo-depois 1
  adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|50th|90th|95th|99th" > $OUT/$P-fluidez-catalogo.txt
  adb shell am force-stop $PKG; sleep 1
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/teste.mkv"
  snap player-carregando 3
  sleep 20
  snap player-sem-controles 1
  key DPAD_DOWN; snap player-controles 0.3
  adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1  # pausado, os controles não somem durante as fotos
  key DPAD_DOWN; snap player-foco-linha 0.8
  adb shell input keyevent DPAD_RIGHT; adb shell input keyevent DPAD_RIGHT; adb shell input keyevent DPAD_RIGHT
  snap player-correndo 0.1
  sleep 3; snap player-depois-de-correr 0.5
  key DPAD_DOWN; snap player-foco-opcoes 0.8
  key DPAD_RIGHT; snap player-foco-opcoes2 0.8
  key DPAD_UP DPAD_UP; snap player-foco-play 0.8
  sleep 6; snap player-sem-controles2 1
fi
escala
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head -40 > $OUT/crash.txt
cat $OUT/$P-abertura.txt $OUT/$P-fluidez-*.txt $OUT/crash.txt
exit 0
