#!/bin/bash
# $1 = perfil (tel|tv)  $2 = apk  $3 = rótulo (antes|depois)
# Para cada vídeo (2,39:1 gravado num quadro 16:9, padrão de círculo/quadrado gravado num 16:9, padrão 2,39:1 de verdade):
# o app troca sozinho o modo de tela (Preencher -> Normal -> Preencher, por gfOvScript) e a tela é GRAVADA (screenrecord) para
# se ver cada quadro da troca. Fotos paradas de cada estado servem para medir se círculo/quadrado ficam redondo/quadrado.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}; L=${3:-depois}
OUT=shots; mkdir -p $OUT
adb install -r "$APK"
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; SZ=1280x576; else SZ=1280x720; fi
adb shell wm size > $OUT/$P-wm.txt
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
[ "$P" = tel ] && adb root >/dev/null && sleep 3
nmark() { adb logcat -d | grep -c "tela: modo"; }
abrir() {
  adb shell am force-stop $PKG; adb shell pm clear $PKG >/dev/null 2>&1; sleep 1
  X="--es gfOvScript tela:1@16000+tela:0@28000+tela:1@40000"
  if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/$1" --esa subs "http://10.0.2.2:8000/vazia.srt" --es type movie --es id tt0000001 $X
  else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$1" --es gfTestSubs "http://10.0.2.2:8000/vazia.srt" $X; fi
}
esperar() {  # $1 = trecho do log, $2 = máximo de segundos
  for i in $(seq 1 $2); do adb logcat -d | grep -q "$1" && return 0; sleep 1; done; return 1
}
foto() { sleep "${2:-0.3}"; adb exec-out screencap -p > "$OUT/$L-$P-$1.png"; }
for v in barra circ circwide; do
  adb logcat -c
  abrir $v.mkv
  esperar "ciclo: evento Vout" 120 || echo "FALHA: video $v nao tocou" >> $OUT/passos.txt
  sleep 6
  foto $v-0-normal
  # grava a tela durante as 3 trocas (a primeira é 16 s depois do início da reprodução)
  adb shell date +%s.%N | tr -d '\r' > $OUT/$L-$P-$v.inicio
  ( adb shell screenrecord --size $SZ --bit-rate 1500000 --time-limit 46 /sdcard/rec.mp4 ) &
  RP=$!
  for i in $(seq 1 60); do [ "$(nmark)" -ge 1 ] && break; sleep 1; done; sleep 8; foto $v-1-preencher
  for i in $(seq 1 60); do [ "$(nmark)" -ge 2 ] && break; sleep 1; done; sleep 8; foto $v-2-normal
  for i in $(seq 1 60); do [ "$(nmark)" -ge 3 ] && break; sleep 1; done; sleep 6; foto $v-3-preencher
  wait $RP; sleep 2
  adb pull /sdcard/rec.mp4 "$OUT/$L-$P-$v.mp4"; adb shell rm -f /sdcard/rec.mp4
  adb logcat -v epoch -d | grep -E "tela: modo|zoom Preencher|barras:" > $OUT/$L-$P-$v-log.txt
done
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head -40 > $OUT/$P-crash.txt
exit 0
