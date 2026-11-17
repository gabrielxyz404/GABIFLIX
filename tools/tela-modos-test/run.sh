#!/bin/bash
# $1 = perfil (tel|tv)  $2 = apk  $3 = rótulo (antes|depois)  $4 = quantos modos o botão "Tela" tem (3 antes, 2 depois)
# Para cada vídeo (2,39:1, 16:9, 4:3) com legenda de 2 linhas: fotos em cada modo de "Tela", sem controles e com controles. Nunca publica nada.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}; L=${3:-depois}; NM=${4:-2}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
adb shell wm size > $OUT/$P-wm.txt
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
BAD=0; N=0
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
snap() { N=$((N+1)); sleep "${2:-0.6}"; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  if dump | grep -qiE "isn't responding|não está respondendo|Close app|Fechar app"; then echo "FALHA: dialogo ANR na foto $N ($1)" | tee -a $OUT/passos.txt; BAD=1; adb shell input keyevent KEYCODE_BACK; sleep 1; fi
  adb exec-out screencap -p > "$OUT/$L-$P-$1.png"; }
label() { dump | python3 -c "
import sys,re
x=sys.stdin.read()
m=re.search(r'(?:text|content-desc)=\"(Tela: [^\"]*)\"',x)
print(m.group(1).replace(' ','').replace(':','-') if m else 'semrotulo')"; }
showc() { if [ "$P" = tel ]; then
    for t in 1 2 3 4; do dump | grep -q "Tela" && break; adb shell input tap 1200 360; sleep 1.5; done
  else adb shell input keyevent DPAD_DOWN; fi; sleep 1.5; }
hidec() { if [ "$P" = tel ]; then
    for t in 1 2 3; do dump | grep -q "Tela" || break; adb shell input tap 1200 300; sleep 1.5; done
  else adb shell input keyevent BACK; fi; sleep 1.5; }
tela_click() {
  if [ "$P" = tel ]; then
    xy=$(dump | python3 $UI xy "Tela")
    for t in 1 2 3; do [ -n "$xy" ] && break; adb shell input tap 1200 360; sleep 1.5; xy=$(dump | python3 $UI xy "Tela"); done
    [ -n "$xy" ] && adb shell input tap $xy || echo "FALHA: sem botao Tela" >> $OUT/passos.txt
  else
    for t in $(seq 1 10); do
      f=$(dump | python3 $UI focus)
      case "$f" in Tela*) adb shell input keyevent DPAD_CENTER; return;; Legendas*|Áudio*|Audio*) adb shell input keyevent DPAD_RIGHT;; *) adb shell input keyevent DPAD_DOWN;; esac
      sleep 0.7
    done
    echo "FALHA: TV nao chegou no botao Tela" >> $OUT/passos.txt
  fi
  sleep 1.2
}
abrir() {  # $1 = arquivo de vídeo
  adb shell am force-stop $PKG; sleep 1
  if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/$1" --esa subs "http://10.0.2.2:8000/teste.srt" --es type movie --es id tt0000001
  else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$1" --es gfTestSubs "http://10.0.2.2:8000/teste.srt"; fi
}
[ "$P" = tel ] && adb root >/dev/null && sleep 3
adb logcat -c
for v in wide hd sd; do
  abrir $v.mkv
  sleep 28
  adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5
  snap $v-0-inicio 0.5
  showc; snap $v-0-inicio-controles 0.3; hidec
  for m in $(seq 1 $NM); do
    showc; tela_click
    lb=$(label)
    snap $v-$m-$lb-controles 0.4
    hidec; snap $v-$m-$lb 0.4
  done
  if [ "$NM" -ge 2 ]; then showc; tela_click; snap $v-9-ciclo-controles 0.4; hidec; snap $v-9-ciclo 0.4; fi
done
adb logcat -d | grep -E "GFPlayer|zoom Preencher|escala" > $OUT/$P-log.txt
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head -40 > $OUT/$P-crash.txt
[ "$BAD" = 1 ] && { echo "ANR detectado" ; exit 1; }
exit 0
