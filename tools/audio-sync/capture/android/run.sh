#!/bin/bash
# Roda dentro do emulador (reactivecircus/android-emulator-runner): instala o APK de captura e mede o libVLC 3.6.0.
set -x
D=tools/audio-sync/capture/android
OUT=${OUT:-out-and}
mkdir -p $OUT
adb install -r $D/app/build/outputs/apk/debug/app-debug.apk
adb install -r $D/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell getprop ro.product.cpu.abi | tee $OUT/abi.txt
adb logcat -c
RUNNER=io.github.gabrielxyz404.audiocap.test/androidx.test.runner.AndroidJUnitRunner
inst() { timeout ${T:-1500} adb shell am instrument -w -e class io.github.gabrielxyz404.audiocap.CaptureBenchTest "$@" $RUNNER 2>&1 | tail -15; }
# A) variantes do método em ac3 (3 janelas de 90 s), com convivência com a reprodução
inst -e variants sout,sout+novideo -e codecs ac3 -e conc 1
T=900 inst -e variants afile -e codecs ac3 -e starts 120 -e win 60
# B) codecs com o método escolhido (sout) e a alternativa MediaExtractor
inst -e variants sout -e codecs eac3,aac,dts,truehd,flac
inst -e variants extractor -e codecs aac,ac3,flac -e starts 120,600 -e win 60
adb logcat -d -s CAPRES:I > $OUT/capres.log
cat $OUT/capres.log | head -80
mkdir -p $OUT/wav
adb pull /sdcard/Android/data/io.github.gabrielxyz404.audiocap/files/cap/. $OUT/wav/ || true
ls -la $OUT/wav | head -60
