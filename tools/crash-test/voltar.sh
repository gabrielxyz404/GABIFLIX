#!/bin/bash
# $1 = perfil (tel|tv)  $2 = apk  $3 = rótulo (antes|depois). Filme tocando, HOME, espera, volta PELO ÍCONE DO LANÇADOR (a intent de verdade: MAIN/LAUNCHER, NEW_TASK|RESET_TASK_IF_NEEDED)
# e registra onde o app cai (player ou página inicial) e a pilha de telas. Cenários: ícone logo a seguir, ícone depois de 2 min, e tarefa criada sem a categoria LAUNCHER.
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=$2; L=${3:-depois}
OUT=shots; mkdir -p $OUT
adb install -r "$APK" || exit 1
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 600000
[ "$P" = tel ] && { adb shell wm size 1080x2400; adb shell wm density 420; }
adb root >/dev/null 2>&1; sleep 3
snap() { sleep "${2:-0.5}"; adb exec-out screencap -p > "$OUT/$L-$P-$1.png"; }
topact() { adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -1 | tr -s ' ' | tr -d '\r'; }
pilha() { adb shell dumpsys activity activities 2>/dev/null | grep -E "Task\{|\* Hist|Hist #|realActivity=|mLastPausedActivity" | grep -i -E "gabiflix|Task\{" | head -20 | tr -s ' ' | tr -d '\r'; }
BASE=http://10.0.2.2:8000
abrir() {   # $1 = como a tarefa nasce: launcher | simples
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  if [ "$P" = tel ]; then
    if [ "$1" = launcher ]; then adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $PKG/.MainActivity
    else adb shell am start -n $PKG/.MainActivity; fi
    sleep 8
    adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/wide.mkv" --esa subs "$BASE/teste.srt" --es type movie --es id tt0000001 --es gfAudioAuto false
  else
    if [ "$1" = launcher ]; then adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/wide.mkv" --es gfTestSubs "$BASE/teste.srt" --es gfAudioAuto false
    else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/wide.mkv" --es gfTestSubs "$BASE/teste.srt" --es gfAudioAuto false; fi
  fi
  sleep 20
}
volta_icone() {
  if [ "$P" = tel ]; then adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $PKG/.MainActivity
  else adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n $PKG/.tv.TvHomeActivity; fi
}
volta_monkey() {
  if [ "$P" = tel ]; then adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  else adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; fi
}
cen() {  # $1 nome  $2 nascimento (launcher|simples)  $3 segundos fora  $4 forma de voltar (icone|monkey)
  local n=$1
  echo "=== $n ===" >> $OUT/$L-$P-resultado.txt
  abrir $2; snap $n-0-tocando 0.3
  echo "$n antes de sair: $(topact)" >> $OUT/$L-$P-resultado.txt; pilha >> $OUT/$L-$P-resultado.txt
  adb shell input keyevent 3; sleep $3
  # morte em segundo plano como a do sistema com pouca memória: am kill só mata processos em segundo plano; kill9 mata o pid
  if [ "$5" = amkill ]; then adb shell am kill $PKG; sleep 2; echo "$n: processo depois do am kill: '$(adb shell pidof $PKG | tr -d '\r')'" >> $OUT/$L-$P-resultado.txt; fi
  if [ "$5" = kill9 ]; then adb shell "kill -9 $(adb shell pidof $PKG | tr -d '\r' | awk '{print $1}')"; sleep 2; echo "$n: processo depois do kill -9: '$(adb shell pidof $PKG | tr -d '\r')'" >> $OUT/$L-$P-resultado.txt; fi
  echo "$n fora: $(topact)" >> $OUT/$L-$P-resultado.txt
  if [ "$4" = monkey ]; then volta_monkey; else volta_icone; fi
  sleep 4; snap $n-1-voltou 0.5
  echo "$n voltou: $(topact)" >> $OUT/$L-$P-resultado.txt; pilha >> $OUT/$L-$P-resultado.txt
  if topact | grep -q PlayerActivity; then echo "$n => VOLTOU AO FILME" >> $OUT/$L-$P-resultado.txt; else echo "$n => NAO VOLTOU AO FILME" >> $OUT/$L-$P-resultado.txt; fi
  adb logcat -d -s GabiFlixPlayer GFCrash | grep -E "ciclo|criada|destruída|finish|GFCrash" | tail -25 >> $OUT/$L-$P-resultado.txt
  adb logcat -d | grep -E "FATAL EXCEPTION|Fatal signal" | head -5 >> $OUT/$L-$P-resultado.txt
}
# aquecimento: a primeira abertura do app no emulador é lenta e atrapalhava o primeiro cenário
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.MainActivity >/dev/null 2>&1 || true; sleep 40; adb shell am force-stop $PKG
cen icone-logo launcher 5 icone
cen icone-2min launcher 120 icone
cen monkey-logo launcher 5 monkey
cen simples-icone simples 5 icone
cen morto-amkill launcher 5 icone amkill
cen morto-kill9 launcher 5 icone kill9
exit 0
