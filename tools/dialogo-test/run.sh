#!/bin/bash
# $1 = perfil (tel|tv). Abre o diálogo "Sincronizar legenda" do player e tira a foto + o dump da interface (bounds, ScrollView rolável).
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
adb shell wm size > $OUT/wm.txt
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.7; done; }
W=2400; [ "$P" = tv ] && W=1920; H=1080
show_controls() {
  if [ "$P" = tel ]; then xy=$(dump | python3 $UI xy "Voltar"); [ -z "$xy" ] && adb shell input tap $((W/2)) $((H/3)); else key DPAD_DOWN; fi
  sleep 1
}
click() {
  if [ "$P" = tel ]; then
    for t in 1 2 3 4; do xy=$(dump | python3 $UI xy "$1"); [ -n "$xy" ] && break; show_controls; done
    [ -n "$xy" ] && adb shell input tap $xy || echo "NÃO ACHEI: $1"
  else
    for t in $(seq 1 12); do
      d=$(dump | python3 $UI nav "$1")
      if [ "$d" = HERE ]; then adb shell input keyevent DPAD_CENTER; sleep 1; return; fi
      if [ "$d" = NONE ]; then show_controls; continue; fi
      adb shell input keyevent $d; sleep 0.6
    done
    echo "TV: NÃO CHEGUEI em '$1'"
  fi
  sleep 1
}
subs="http://10.0.2.2:8000/a1.srt,http://10.0.2.2:8000/a2.srt,http://10.0.2.2:8000/b.srt"
adb shell am force-stop $PKG; sleep 1
if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/teste.mkv" --esa subs "$subs"
else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/teste.mkv" --es gfTestSubs "$subs"; fi
sleep 40
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5
show_controls; click "Legendas"; sleep 1; click "Sincronizar legenda"; sleep 2
adb exec-out screencap -p > $OUT/dialogo-$P.png
dump > $OUT/dialogo-$P.xml
python3 - $OUT/dialogo-$P.xml > $OUT/dialogo-$P-bounds.txt <<'PY'
import sys,re
import xml.etree.ElementTree as ET
raw=open(sys.argv[1]).read(); i=raw.find('<?xml')
r=ET.fromstring(raw[i:raw.rfind('</hierarchy>')+12])
for n in r.iter('node'):
    t=n.get('text','')
    if t or 'ScrollView' in n.get('class',''):
        print(n.get('class','').split('.')[-1],repr(t),n.get('bounds'),'scrollable='+n.get('scrollable',''))
PY
cat $OUT/dialogo-$P-bounds.txt
adb logcat -d | grep -E "FATAL EXCEPTION" | head > $OUT/crash.txt
exit 0
