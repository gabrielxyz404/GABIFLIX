#!/bin/bash
# Prova da fase 3 no emulador com filmes abertos reais (Tears of Steel e Sintel) e as legendas oficiais deslocadas de propósito.
set -x
PKG=io.github.gabrielxyz404.gabiflix
mkdir -p shots
adb install -r android/app/build/outputs/apk/release/app-release.apk
B64() { base64 -w0 "/tmp/media/$1"; }
echo "| filme/caso | esperado | recuperado | tempo | bytes | modo | buffering | |" > shots/resultado.md
echo "|---|---|---|---|---|---|---|---|" >> shots/resultado.md
FAILS=0
snap() { adb exec-out screencap -p > "shots/$1.png"; }
bytes() { curl -s http://127.0.0.1:8000/__bytes | awk -v f="$1" '$1==f{print $2}' ; }
D0=0

# $1 nome  $2 vídeo  $3 srt(arquivo)  $4 atraso injetado (ms)  $5 esperado ("base" = mede; "neg" = rejeitar; "rel" = D0 - atraso)  $6 escala|-  x
runcase() {
  local name=$1 vid=$2 srtf=$3 shift=$4 mode=$5 sc=$6 extra=$7 tmo=${8:-480}
  local size; size=$(stat -c %s /tmp/media/$vid)
  adb shell am force-stop $PKG; sleep 2
  adb logcat -c
  local b0; b0=$(bytes "$vid"); b0=${b0:-0}
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$vid" --es gfAudioSrt "$(B64 $srtf)" --el gfAudioShift "$shift" --el gfAudioSize "$size" $extra
  local t=0
  while [ $t -lt $tmo ]; do
    sleep 5; t=$((t+5))
    [ $t -eq 30 ] && snap "$name-30s"
    adb logcat -d -s GabiFlixPlayer:I | grep -q "AUDIO_RESULT" && break
  done
  sleep 6; snap "$name-resultado"; sleep 4; snap "$name-menu"
  adb logcat -d -s GabiFlixPlayer:I > "shots/$name.log"
  adb logcat -d | grep -E "VLC|libvlc" | grep -iE "audiofile|afile|stop-time|start-time|error|fail|cannot" | head -80 > "shots/$name.vlc.log"
  local b1; b1=$(bytes "$vid"); b1=${b1:-0}
  echo "servidor enviou $((b1-b0)) bytes de $vid durante $name" >> "shots/$name.log"
  local exp
  case $mode in
    base) exp=base;;
    neg) exp=rejeitar;;
    rel) exp=$((D0 - shift));;
  esac
  if [ "$mode" = base ]; then
    local off; off=$(grep AUDIO_RESULT "shots/$name.log" | tail -1 | grep -o "accepted=true offset=-\?[0-9]*" | grep -o "\-\?[0-9]*$")
    D0=${off:-0}; exp=$D0
    echo "deslocamento de base de $name: $D0" >> "shots/$name.log"
  fi
  python3 tools/audio-test/check.py "shots/$name.log" "$name" "$exp" "$sc" >> shots/resultado.md || FAILS=$((FAILS+1))
  tail -1 shots/resultado.md
  adb shell am force-stop $PKG
}

runcase tos-base      tos.mkv    TOS-en.srt        0      base -  ""
runcase tos-mais17    tos.mkv    TOS-en.srt        17000  rel  -  "--ez gfAudioDialog true"
runcase tos-menos42   tos.mkv    TOS-en.srt        -42000 rel  -  ""
runcase tos-escala    tos.mkv    scaled-tos.srt    0      rel  1.0427 ""
runcase tos-neg       tos.mkv    sintel_en.srt     0      neg  -  ""
D0=0
runcase sintel-base   sintel.mkv  sintel_en.srt    0      base -  ""
runcase sintel-mais17 sintel.mkv  sintel_en.srt    17000  rel  -  ""
runcase sintel-mais150 sintel.mkv sintel_en.srt    150000 rel  -  ""
runcase sintel-neg    sintel.mkv  TOS-en.srt       0      neg  -  ""
echo "falhas: $FAILS" >> shots/resultado.md
cat shots/resultado.md
exit 0
