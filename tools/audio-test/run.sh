#!/bin/bash
# Prova da fase 3 no emulador: toca um filme aberto (Tears of Steel) com a legenda oficial deslocada de propósito
# e confere se o acerto pelo áudio recupera o deslocamento. Roda dentro do emulador (android-emulator-runner).
set -x
PKG=io.github.gabrielxyz404.gabiflix
mkdir -p shots
adb install -r android/app/build/outputs/apk/release/app-release.apk
SRT_EN=$(base64 -w0 /tmp/media/TOS-en.srt)
SRT_WRONG=$(base64 -w0 /tmp/media/wrong.srt)
SRT_SCALED=$(base64 -w0 /tmp/media/scaled.srt)
SZ=$(stat -c %s /tmp/media/tos.mkv)
SZ3=$(stat -c %s /tmp/media/tos_ac3.mkv)
echo "| caso | esperado | recuperado | tempo | bytes | modo | buffering | |" > shots/resultado.md
echo "|---|---|---|---|---|---|---|---|" >> shots/resultado.md
FAILS=0
snap() { adb exec-out screencap -p > "shots/$1.png"; }
bytes() { curl -s http://127.0.0.1:8000/__bytes | awk -v f="$1" '$1==f{print $2}' ; }

# $1 nome  $2 arquivo de vídeo  $3 srt base64  $4 atraso injetado (ms)  $5 tamanho  $6 esperado (ms|rejeitar)  $7 escala|-  $8 extras  $9 tempo máx (s)
runcase() {
  local name=$1 vid=$2 srt=$3 shift=$4 size=$5 exp=$6 sc=$7 extra=$8 tmo=${9:-420}
  adb shell am force-stop $PKG; sleep 2
  adb logcat -c
  local b0; b0=$(bytes "$vid"); b0=${b0:-0}
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$vid" --es gfAudioSrt "$srt" --el gfAudioShift "$shift" --el gfAudioSize "$size" $extra
  local t=0
  while [ $t -lt $tmo ]; do
    sleep 5; t=$((t+5))
    [ $t -eq 20 ] && snap "$name-20s"
    [ $t -eq 40 ] && snap "$name-40s"
    adb logcat -d -s GabiFlixPlayer:I | grep -q "AUDIO_RESULT" && break
  done
  sleep 6
  snap "$name-resultado"
  sleep 4
  snap "$name-menu"
  adb logcat -d -s GabiFlixPlayer:I > "shots/$name.log"
  adb logcat -d | grep -E "VLC|libvlc" | grep -iE "sout|transcode|wav|mux|audiofile|afile|stop-time|start-time|error|fail|cannot|no suitable" | head -150 > "shots/$name.vlc.log"
  local b1; b1=$(bytes "$vid"); b1=${b1:-0}
  echo "servidor enviou $((b1-b0)) bytes de $vid durante $name" >> "shots/$name.log"
  python3 tools/audio-test/check.py "shots/$name.log" "$name" "$exp" "$sc" >> shots/resultado.md || FAILS=$((FAILS+1))
  tail -1 shots/resultado.md
  adb shell am force-stop $PKG
}

# 0) sondagem dos modos de captura (sem legenda de teste)
adb shell am force-stop $PKG; sleep 2; adb logcat -c
adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/tos.mkv" --ez gfAudioProbe true
for i in $(seq 1 40); do sleep 5; adb logcat -d -s GabiFlixPlayer:I | grep -c "PROBE mode=" | grep -q "^3$" && break; done
snap probe
adb logcat -d -s GabiFlixPlayer:I > shots/probe.log
adb logcat -d | grep -E "VLC|libvlc" | grep -iE "sout|transcode|wav|mux|audiofile|afile|stop-time|start-time|error|fail|cannot|no suitable" | head -200 > shots/probe.vlc.log
grep "PROBE" shots/probe.log
adb shell am force-stop $PKG

runcase base      tos.mkv      "$SRT_EN"     0      $SZ  0       -       ""                                         420
runcase mais17    tos.mkv      "$SRT_EN"     17000  $SZ  -17000  -       "--ez gfAudioDialog true"                  420
runcase menos42   tos.mkv      "$SRT_EN"     -42000 $SZ  42000   -       "--ez gfAudioManual true --ez gfAudioDialog true" 420
runcase outrofilme tos.mkv     "$SRT_WRONG"  0      $SZ  rejeitar -     ""                                         480
runcase escala    tos.mkv      "$SRT_SCALED" 0      $SZ  0       1.0427  ""                                         420
runcase ac3mais17 tos_ac3.mkv  "$SRT_EN"     17000  $SZ3 -17000  -       ""                                         420
runcase mais150   tos.mkv      "$SRT_EN"     150000 $SZ  -150000 -       ""                                         420
echo "falhas: $FAILS" >> shots/resultado.md
cat shots/resultado.md
exit 0
