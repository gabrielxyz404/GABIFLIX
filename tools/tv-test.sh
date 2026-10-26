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
key DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT; shot inicio-direita
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
# player (VLC) com um vídeo de teste
adb shell am force-stop io.github.gabrielxyz404.gabiflix
adb shell am start -n io.github.gabrielxyz404.gabiflix/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/teste.mkv"
shot player-abre 15
key DPAD_CENTER;   shot player-pausa 2
key DPAD_CENTER DPAD_RIGHT DPAD_RIGHT;  shot player-avanca 3
adb shell input keyevent DPAD_DOWN; sleep 0.3; adb shell input keyevent DPAD_DOWN; sleep 0.3
adb shell input keyevent DPAD_RIGHT; adb shell input keyevent DPAD_RIGHT; adb shell input keyevent DPAD_RIGHT
shot player-barra 0.2
sleep 2; shot player-barra-depois 0.5
adb shell input keyevent BACK; shot player-back1 1
key BACK;          shot player-sai 4
# botão Atualizar
adb shell am force-stop io.github.gabrielxyz404.gabiflix
adb shell monkey -p io.github.gabrielxyz404.gabiflix -c android.intent.category.LEANBACK_LAUNCHER 1
sleep 6
key DPAD_UP DPAD_RIGHT;          shot u-botao
key DPAD_CENTER;                 shot u-atualizar 6
key DPAD_CENTER;                 shot u-baixando 25
# janela das fontes (dados de exemplo)
adb shell am force-stop io.github.gabrielxyz404.gabiflix
adb shell am start -n io.github.gabrielxyz404.gabiflix/.tv.TvHomeActivity --ez gfTestPicker true
shot f-fontes 6
key DPAD_UP DPAD_RIGHT;          shot f-provedor2 2
key DPAD_DOWN;                   shot f-fonte 2
# pesquisa
adb shell am force-stop io.github.gabrielxyz404.gabiflix
adb shell monkey -p io.github.gabrielxyz404.gabiflix -c android.intent.category.LEANBACK_LAUNCHER 1
sleep 6
key DPAD_UP;                     shot s-topo
key DPAD_CENTER;                 shot s-pesquisa 3
adb shell input text "breaking%sbad"
adb shell input keyevent ENTER
shot s-resultados 8
key DPAD_DOWN;                   shot s-desce
key DPAD_CENTER;                 shot s-titulo 8
adb shell am force-stop io.github.gabrielxyz404.gabiflix
adb shell "am start -n io.github.gabrielxyz404.gabiflix/.tv.TvHomeActivity --es gfTestMeta '{\"id\":\"tt0903747\",\"type\":\"series\",\"name\":\"Breaking Bad\"}'"
shot d-serie 8
key DPAD_DOWN;                   shot d-foco-episodios
key DPAD_CENTER;                 shot d-painel 3
key DPAD_LEFT DPAD_DOWN DPAD_DOWN; shot d-temporada 3
key DPAD_RIGHT DPAD_DOWN;        shot d-episodio 2
key BACK;                        shot d-fecha 2
adb logcat -d | grep -iE "vlc|PlayerActivity|gabiflix" | grep -v "BroadcastQueue\|ConnectivityService\|NetworkFactory\|Ethernet" | tail -60 > shots/player.txt
adb logcat -d | grep -E "FATAL|AndroidRuntime: |E/AndroidRuntime|Exception" | grep -v "RuntimeInit\|boot image\|lock profiling\|Calling main\|Shutting down\|VM exiting" | head -60 > shots/crash.txt
cat shots/crash.txt shots/log.txt
exit 0
