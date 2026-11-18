#!/bin/bash
# $1 = apk (só arm64-v8a). Emulador ARM64 de verdade (Android 11, instruções ARM emuladas): roda o Silero (ONNX Runtime) no processo :audiocap sobre 20 s de áudio
# e registra se funcionou (gancho gfCrashTest=silero). Muito lento (emulação de CPU): espera até 80 min.
PKG=io.github.gabrielxyz404.gabiflix
APK=$1
OUT=shots; mkdir -p $OUT
R=$OUT/arm-resultado.txt
adb install -r "$APK" || exit 1
{
  echo "modelo=$(adb shell getprop ro.product.model | tr -d '\r') api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "ro.product.cpu.abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "ro.product.cpu.abilist=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  echo "uname=$(adb shell uname -m | tr -d '\r')"
} > $R
adb shell settings put global hide_error_dialogs 1
adb logcat -G 16M >/dev/null 2>&1
adb logcat -c
T0=$(date +%s)
adb shell am start -n $PKG/.MainActivity --es gfCrashTest silero > /dev/null
for i in $(seq 1 480); do
  sleep 10
  adb logcat -d -s GFCrash:V 2>/dev/null | grep -q "teste silero" && break
done
echo "esperou $(( $(date +%s) - T0 )) s" >> $R
adb logcat -d -s GFCrash:V 2>/dev/null | tr -d '\r' | grep -E "teste silero|ABI:" >> $R
adb logcat -d 2>/dev/null | grep -E "GFCrash|AUDIOSYNC|Fatal signal|DEBUG|AndroidRuntime|onnxruntime|libc " | tail -200 > $OUT/arm-log.txt
adb exec-out screencap -p > $OUT/arm-tela.png
echo "processos do app: $(adb shell ps -A | grep gabiflix | tr -s ' ' | tr '\r\n' '  ')" >> $R
exit 0
