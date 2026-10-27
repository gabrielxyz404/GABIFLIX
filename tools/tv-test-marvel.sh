#!/bin/bash
# Teste curto: Disney+ > Marvel (e outras abas) — título da primeira fileira visível ao descer/subir.
set -x
mkdir -p shots
adb install -r android/app/build/outputs/apk/release/app-release.apk
n=0
shot() { sleep "${2:-3}"; n=$((n+1)); f=$(printf "%02d-%s" $n "$1"); adb exec-out screencap -p > "shots/$f.png"; echo "$f: $(adb shell dumpsys activity activities | grep -m1 mResumedActivity)" >> shots/log.txt; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 1.2; done; }
adb logcat -c
adb shell monkey -p io.github.gabrielxyz404.gabiflix -c android.intent.category.LEANBACK_LAUNCHER 1
sleep 8
key DPAD_RIGHT DPAD_CENTER;      shot disney 10
key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER; shot marvel-aba 8
key DPAD_DOWN;                   shot marvel-fileira1 2
key DPAD_DOWN;                   shot marvel-fileira2-filmes 2
key DPAD_DOWN;                   shot marvel-fileira3-series 2
key DPAD_UP;                     shot marvel-sobe-filmes 2
key DPAD_UP;                     shot marvel-sobe-fileira1 2
key DPAD_DOWN DPAD_DOWN;         shot marvel-desce2 2
key DPAD_UP DPAD_UP DPAD_UP;     shot marvel-aba-de-novo 2
# outras abas
key DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_CENTER; shot disney-aba 6
key DPAD_DOWN;                   shot disney-fileira1 2
key DPAD_DOWN;                   shot disney-fileira2 2
key DPAD_UP;                     shot disney-sobe 2
key DPAD_UP DPAD_RIGHT DPAD_CENTER; shot pixar 6
key DPAD_DOWN;                   shot pixar-fileira1 2
key DPAD_DOWN;                   shot pixar-fileira2 2
key DPAD_UP DPAD_UP DPAD_RIGHT DPAD_RIGHT DPAD_CENTER; shot starwars 6
key DPAD_DOWN DPAD_DOWN DPAD_UP; shot starwars-sobe 2
key BACK BACK BACK;              shot home
key DPAD_LEFT DPAD_CENTER;       shot netflix 9
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot netflix-desce4 2
key DPAD_UP DPAD_UP DPAD_UP DPAD_UP; shot netflix-sobe4 2
adb logcat -d | grep -E "FATAL|AndroidRuntime: " | head -40 > shots/crash.txt
cat shots/crash.txt shots/log.txt
exit 0
