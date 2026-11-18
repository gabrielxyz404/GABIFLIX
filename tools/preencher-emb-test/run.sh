#!/bin/bash
# $1 = apk. TV: legenda EMBUTIDA no arquivo (o libVLC a desenha dentro do quadro; sem legenda baixada), linhas de varios tamanhos, em Normal e em Preencher.
# Mede, em cada foto, a largura da linha mais larga (em % do quadro de 1920 px) e se o texto encosta nas bordas da tela (= cortado).
set -x
PKG=io.github.gabrielxyz404.gabiflix
APK=${1:-android/app/build/outputs/apk/release/app-release.apk}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
adb shell wm size > $OUT/wm.txt
adb shell settings put global hide_error_dialogs 1
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
tela_click() {
  for t in $(seq 1 10); do
    f=$(dump | python3 $UI focus)
    case "$f" in Tela*) adb shell input keyevent DPAD_CENTER; return;; Legendas*|Áudio*|Audio*) adb shell input keyevent DPAD_RIGHT;; *) adb shell input keyevent DPAD_DOWN;; esac
    sleep 0.7
  done
}
CUES=${CUES:-8}
for modo in normal preencher; do
  adb shell am force-stop $PKG; adb shell pm clear $PKG >/dev/null 2>&1; sleep 1; adb logcat -c
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/emb2.mkv" --es gfTestSubs "http://10.0.2.2:8000/nenhuma.srt"
  sleep 14
  if [ $modo = preencher ]; then
    adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5
    adb shell input keyevent DPAD_DOWN; sleep 1.5; tela_click; adb shell input keyevent BACK; sleep 1.5
    adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1; adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 10
  fi
  # uma fala nova a cada 10 s; a foto sai no meio de cada fala
  for i in $(seq 1 $CUES); do
    adb exec-out screencap -p > $OUT/$modo-$i.png
    sleep 10
  done
  adb logcat -d | grep -E "GabiFlixPlayer" > $OUT/log-$modo.txt
done
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head -20 > $OUT/crash.txt
python3 -c "import PIL" 2>/dev/null || pip install --user -q pillow
python3 tools/preencher-emb-test/medir.py $OUT > $OUT/medidas.txt 2>&1
cat $OUT/medidas.txt
exit 0
