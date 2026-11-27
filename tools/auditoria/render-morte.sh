#!/bin/bash
# Prova: o processo da página (WebView) morre com o app do telemóvel aberto. Antes: o app inteiro fecha. Depois: a página reabre.
# uso: render-morte.sh <apk> <rótulo>   -> auditoria-out/render-<rótulo>.txt e fotos (precisa de "adb root")
APK=$1; LABEL=$2
PKG=io.github.gabrielxyz404.gabiflix
OUT=auditoria-out; mkdir -p $OUT
R=$OUT/render-$LABEL.txt; : > $R
adb uninstall $PKG >/dev/null 2>&1
adb install -r -g "$APK" >/dev/null || { echo "instalação falhou" | tee -a $R; exit 1; }
adb logcat -c
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 30
adb exec-out screencap -p > $OUT/render-$LABEL-1-antes.png
P1=$(adb shell pidof $PKG | tr -d '\r')
RENDER=$(adb shell ps -A -o PID,NAME | grep -i sandboxed_process | awk '{print $1}' | tr '\n' ' ')
echo "app_pid=$P1 renderizadores=[$RENDER]" | tee -a $R
for p in $RENDER; do adb shell kill -9 $p; done
sleep 15
P2=$(adb shell pidof $PKG | tr -d '\r')
TOP=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -d '\r')
adb exec-out screencap -p > $OUT/render-$LABEL-2-depois.png
NOVOS=$(adb shell ps -A -o PID,NAME | grep -ci sandboxed_process)
echo "depois: app_pid=${P2:-MORTO} renderizadores_novos=$NOVOS" | tee -a $R
echo "atividade_em_frente: $TOP" | tee -a $R
if [ -n "$P2" ] && [ "$P2" = "$P1" ] && echo "$TOP" | grep -q "$PKG"; then echo "RESULTADO: o app continuou aberto" | tee -a $R
else echo "RESULTADO: o app fechou" | tee -a $R; fi
adb logcat -d | grep -iE "render process|renderer|FATAL|AndroidRuntime|has died|Process $PKG" | head -40 > $OUT/render-$LABEL-logcat.txt
