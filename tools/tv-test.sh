#!/bin/bash
# Testa o app nativo da TV num emulador: abre, navega com o controle e tira fotos.
set -x
mkdir -p shots
adb install -r android/app/build/outputs/apk/release/app-release.apk
n=0
shot() { sleep "${2:-3}"; n=$((n+1)); f=$(printf "%02d-%s" $n "$1"); adb exec-out screencap -p > "shots/$f.png"; echo "$f: $(adb shell dumpsys activity activities | grep -m1 mResumedActivity)" >> shots/log.txt; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.8; done; }
adb logcat -c
adb shell monkey -p io.github.gabrielxyz404.gabiflix -c android.intent.category.LEANBACK_LAUNCHER 1
shot inicio 8
key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT; shot inicio-meio
key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT; shot inicio-direita
key DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT; shot inicio-esquerda
key DPAD_RIGHT DPAD_RIGHT
key DPAD_CENTER;                 shot netflix 9
key DPAD_DOWN;                   shot fileira1
key DPAD_DOWN DPAD_RIGHT DPAD_RIGHT; shot fileira2-pos3
key DPAD_DOWN DPAD_DOWN DPAD_DOWN;   shot desce3
key DPAD_UP DPAD_UP;             shot sobe2
key DPAD_CENTER;                 shot titulo 8
key BACK;                        shot volta-titulo
key BACK;                        shot foco-aba
key DPAD_RIGHT DPAD_RIGHT DPAD_CENTER; shot aba-filmes 8
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT; shot grade
key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP; shot grade-topo
key BACK;                        shot volta-inicio-netflix
key BACK;                        shot home
key DPAD_RIGHT DPAD_CENTER;      shot disney 9
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot disney-desce
key DPAD_CENTER;                 shot disney-titulo 8
key DPAD_DOWN DPAD_DOWN;         shot episodios
key DPAD_RIGHT DPAD_RIGHT;       shot episodios-direita
key DPAD_UP DPAD_UP DPAD_UP;     shot episodios-sobe
key DPAD_CENTER;                 shot assistir-sem-conta 4
key BACK BACK BACK BACK;         shot home2
key DPAD_UP;                     shot topo
key DPAD_CENTER;                 shot pesquisa-aberta 4
adb shell input text "matrix"
key BACK
shot pesquisa 8
key DPAD_DOWN;                   shot pesquisa-desce
key BACK BACK;                   shot home3
key DPAD_UP DPAD_RIGHT DPAD_CENTER; shot login 4
key BACK BACK;                   shot fim
adb logcat -d | grep -E "FATAL|AndroidRuntime: |E/AndroidRuntime|Exception" | grep -v "RuntimeInit\|boot image\|lock profiling\|Calling main\|Shutting down\|VM exiting" | head -60 > shots/crash.txt
cat shots/crash.txt shots/log.txt
exit 0
