#!/bin/bash
# Prova da versão 2.0 no emulador: um app instalado (versionCode 110) lê a release falsa no formato novo
# ("Versão 2.0" + <!-- build 1.0.121 -->) e oferece a atualização.
# $1 = APK do código ANTIGO (main, 1.0.110)   $2 = APK do código NOVO (este branch, versionCode 110, versionName 2.0)
set -x
PKG=io.github.gabrielxyz404.gabiflix
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
RES=$OUT/resultado.txt; : > $RES
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
snap() { sleep "${2:-0.5}"; adb exec-out screencap -p > "$OUT/$1.png"; }
topact() { adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -2 | tr -s ' '; }
tem() { dump | python3 $UI texts | grep -qi "$1"; }
esperar() { for i in $(seq 1 $2); do tem "$1" && return 0; sleep 2; done; return 1; }
ok() { echo "$1: $2" | tee -a $RES; }
abrir() { adb shell am force-stop $PKG; sleep 1; adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.MainActivity --es gfTestRelease http://10.0.2.2:8000/test-release/ >/dev/null; }

adb wait-for-device; for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell wm size 1080x2400; adb shell wm density 420
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 600000
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
sleep 20

caso() { # $1 nome  $2 apk  $3 texto esperado no diálogo
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r "$2"
  adb shell dumpsys package $PKG | grep -E "versionCode|versionName" | head -2 | tee -a $OUT/$1-versao.txt
  for tentativa in 1 2 3; do
    adb shell pm clear $PKG >/dev/null 2>&1; adb shell appops set $PKG REQUEST_INSTALL_PACKAGES deny >/dev/null 2>&1
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
    adb logcat -c
    abrir
    esperar "Nova versão do FramePlay" 15 && break
  done
  snap "$1-dialogo" 0.5
  dump | python3 $UI texts > $OUT/$1-textos.txt
  adb logcat -d | grep -E "GFUpdate" > $OUT/$1-gfupdate.txt
  if tem "Nova versão do FramePlay"; then ok "$1-dialogo" SIM; else ok "$1-dialogo NAO" "$(topact)"; fi
  if grep -q "$3" $OUT/$1-textos.txt; then ok "$1-texto \"$3\"" SIM; else ok "$1-texto \"$3\"" NAO; fi
  grep -q "release=121 " $OUT/$1-gfupdate.txt && ok "$1-leu-build-121" SIM || ok "$1-leu-build-121" NAO
}
caso antigo-1.0.110 "$1" "Versão 1.0.121 disponível"
caso novo "$2" "Versão 2.0 disponível."
adb logcat -d | grep -E "FATAL EXCEPTION" | head -40 > $OUT/crash.txt
cat $RES
grep -q "NAO" $RES && exit 1
exit 0
