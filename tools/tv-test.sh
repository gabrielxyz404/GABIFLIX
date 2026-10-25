#!/bin/bash
# Testa o app nativo da TV num emulador: abre, navega com o controle e tira fotos.
set -x
mkdir -p shots
adb root; sleep 3; adb wait-for-device
adb install -r android/app/build/outputs/apk/release/app-release.apk || adb install -r --abi x86 android/app/build/outputs/apk/release/app-release.apk
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "shots/$1.png"; adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" >> shots/log.txt; }
key() { adb shell input keyevent "$@"; sleep 1.2; }
adb logcat -c
adb shell monkey -p io.github.gabrielxyz404.gabiflix -c android.intent.category.LEANBACK_LAUNCHER 1
shot 01-inicio 8
key DPAD_CENTER          # Netflix
shot 02-netflix 10
key DPAD_DOWN DPAD_DOWN  # primeira fileira
shot 03-fileira 4
key DPAD_RIGHT DPAD_RIGHT DPAD_DOWN
shot 04-desce 4
key DPAD_CENTER          # abre o título
shot 05-titulo 10
key BACK
key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_RIGHT DPAD_RIGHT   # aba Filmes
key DPAD_CENTER
shot 06-filmes 10
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN
shot 07-grade 4
key BACK BACK BACK
key DPAD_RIGHT DPAD_RIGHT
key DPAD_CENTER          # Disney+
shot 08-disney 10
key BACK
# série
adb shell am start -n io.github.gabrielxyz404.gabiflix/.tv.TvDetailActivity --es meta '{"id":"tt0903747","type":"series","name":"Breaking Bad"}' || true
shot 09-serie 10
key DPAD_DOWN DPAD_DOWN
shot 10-episodios 3
key BACK
adb shell am start -n io.github.gabrielxyz404.gabiflix/.tv.TvSearchActivity || true
sleep 3
adb shell input text "matrix"
shot 11-pesquisa 8
adb shell am start -n io.github.gabrielxyz404.gabiflix/.tv.TvLoginActivity || true
shot 12-login 4
adb logcat -d | grep -E "FATAL|AndroidRuntime|gabiflix" | head -80 > shots/crash.txt
cat shots/crash.txt shots/log.txt
exit 0
