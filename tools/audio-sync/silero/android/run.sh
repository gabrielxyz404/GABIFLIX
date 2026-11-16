#!/bin/bash
# Dentro do emulador: instala o APK e roda o teste do Silero (ONNX Runtime Android, x86). Saidas em out-sil/.
set -x
D=tools/audio-sync/silero/android
mkdir -p out-sil
adb install -r $D/app/build/outputs/apk/debug/app-debug.apk
adb install -r $D/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell getprop ro.product.cpu.abi | tee out-sil/abi.txt
adb logcat -c
timeout 900 adb shell am instrument -w -e class io.github.gabrielxyz404.sileroprobe.SileroTest io.github.gabrielxyz404.sileroprobe.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tail -15
adb logcat -d -s SILERO:I | tee out-sil/silero.log
adb exec-out run-as io.github.gabrielxyz404.sileroprobe cat files/silero.f32 > out-sil/silero.f32
ls -la out-sil
