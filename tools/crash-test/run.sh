#!/bin/bash
# $1 = apk  $2 = rótulo (api30|api28). Prova o relatório de erros: envio ao ntfy (HTTP 2xx), queda Java, queda nativa e o aviso do arranque seguinte.
PKG=io.github.gabrielxyz404.gabiflix
APK=$1; L=${2:-x}
OUT=shots; mkdir -p $OUT
adb install -r "$APK" || exit 1
adb shell settings put global hide_error_dialogs 1
adb root >/dev/null 2>&1; sleep 3
snap() { adb exec-out screencap -p > "$OUT/$L-$1.png"; }
gc() { adb logcat -d -s GFCrash:V 2>/dev/null | tr -d '\r'; }
caso() {   # $1 nome  $2 valor de gfCrashTest  $3 segundos até relançar
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  adb shell am start -n $PKG/.MainActivity --es gfCrashTest "$2" > /dev/null; sleep "$3"
  echo "== $1 (antes de relançar)" >> $OUT/$L-resultado.txt; gc >> $OUT/$L-resultado.txt
  if [ "$2" != post ]; then
    adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 7; snap "$1-aviso"; sleep 10
    echo "== $1 (depois de relançar)" >> $OUT/$L-resultado.txt; gc >> $OUT/$L-resultado.txt
  fi
  adb logcat -d | grep -E "FATAL EXCEPTION|Fatal signal|am_crash|am_proc_died" | head -10 >> $OUT/$L-resultado.txt
}
echo "aparelho: $(adb shell getprop ro.product.model | tr -d '\r') api $(adb shell getprop ro.build.version.sdk | tr -d '\r')" > $OUT/$L-resultado.txt
caso post post 12
caso java java 8
caso native native 8
caso evento event 12
adb shell run-as $PKG ls -R files/crash > $OUT/$L-ficheiros.txt 2>&1
grep -c "HTTP 2" $OUT/$L-resultado.txt | sed "s/^/envios HTTP 2xx: /" >> $OUT/$L-resultado.txt
exit 0
