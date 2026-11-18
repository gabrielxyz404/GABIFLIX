#!/bin/bash
# $1 = apk. Prova que o filme ABRE e CONTINUA tocando (TV com pouca memoria): Normal e Preencher, com a sincronizacao pelo audio rodando ate o fim da analise
# e, no APK novo, com o processo de captura morto de proposito. Nunca publica o app.
PKG=io.github.gabrielxyz404.gabiflix
APK=$1
OUT=shots; mkdir -p $OUT
BASE=http://10.0.2.2:8000
adb install -r "$APK" || exit 1
adb root >/dev/null 2>&1; sleep 3
{ echo "mem=$(adb shell grep MemTotal /proc/meminfo | tr -d '\r')"; echo "abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"; adb shell wm size | tr -d '\r'; } > $OUT/aparelho.txt
# suprime ANR (o emulador sem aceleracao e lento: um ANR falso fecha o app)
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0 2>/dev/null
glog() { adb logcat -v epoch -d 2>/dev/null; }
resumed() { adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|ResumedActivity" | tr -d '\r'; }
mainpid() { adb shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
snap() { adb exec-out screencap -p > "$OUT/$1.png"; }
fatal() { glog | grep -E "FATAL EXCEPTION|Fatal signal|backtrace:|ANR in|am_crash|am_proc_died|has died" | head -20; }
FAILS=0
# $1 = nome, $2 = segundos de espera maxima pelo fim da analise, $3 = segundos de sobra depois, resto = extras da intent
run_case() {
  local name=$1 maxwait=$2 extra=$3; shift 3
  adb shell am force-stop $PKG; sleep 1; adb shell pm clear $PKG >/dev/null; adb logcat -c
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/film.mkv" --es gfTestSubs "$BASE/film.srt" "$@" > $OUT/am-$name.txt 2>&1
  local t0=$(date +%s) pid0="" done=0 alive=1 why=""
  sleep 12; pid0=$(mainpid); snap "$name-1-abriu"
  while [ $(( $(date +%s) - t0 )) -lt $maxwait ]; do
    sleep 5
    if ! resumed | grep -q PlayerActivity; then alive=0; why="saiu do player: $(resumed)"; break; fi
    if [ "$(mainpid)" != "$pid0" ]; then alive=0; why="o processo do app mudou/caiu"; break; fi
    cp=$(adb shell pidof $PKG:audiocap | tr -d '\r' | awk '{print $1}')
    [ -n "$cp" ] && adb shell dumpsys meminfo $cp 2>/dev/null | grep -E "TOTAL PSS|TOTAL  " | head -1 | tr -s ' ' | sed "s/^/$name audiocap pid=$cp: /" >> $OUT/memoria.txt
    if glog | grep -q "AUDIOSYNC resultado:"; then done=1; break; fi
  done
  if [ $alive = 1 ]; then
    local e=$(( $(date +%s) + extra ))
    while [ $(date +%s) -lt $e ]; do
      sleep 5
      if ! resumed | grep -q PlayerActivity; then alive=0; why="saiu do player depois da analise: $(resumed)"; break; fi
      if [ "$(mainpid)" != "$pid0" ]; then alive=0; why="o processo do app caiu depois da analise"; break; fi
    done
  fi
  sleep 8
  local sobra=$(adb shell pidof $PKG:audiocap | tr -d '\r')
  echo "$name: processo de captura depois da analise: '${sobra:-nenhum}'" >> $OUT/resultado.txt
  snap "$name-2-fim"
  glog | grep -E "GabiFlixPlayer|AUDIOSYNC|vlc|libc|DEBUG|AndroidRuntime|am_crash|am_proc_died" | tail -200 > "$OUT/log-$name.txt"
  fatal > "$OUT/fatal-$name.txt"
  adb shell ls /data/tombstones > "$OUT/tombstones-$name.txt" 2>&1
  echo "$name: vivo=$alive analise_terminou=$done $why | linhas fatais=$(wc -l < $OUT/fatal-$name.txt) | $(glog | grep -o 'pos [0-9]*' | tail -1)" | tee -a $OUT/resultado.txt
  [ $alive = 1 ] || FAILS=$((FAILS+1))
  [ $done = 1 ] || FAILS=$((FAILS+1))
}
# 1) caminho real do usuario: Normal, sem nenhum gancho de teste (o automatico espera 30 s tocando + 25 s com a legenda)
run_case normal-padrao 330 20
# 2) Normal com a analise forcada a comecar cedo (e a passar do filtro de pouca memoria): espera o fim da analise
run_case normal-forcado 330 25 --es gfAudioStartSec 5 --es gfTrace 1
# 3) Preencher
run_case preencher-forcado 330 25 --es gfAudioStartSec 5 --es gfScale 1 --es gfTrace 1
# 4) o processo de captura morre no meio da analise: o filme tem de continuar (so no APK com processo separado)
adb shell am force-stop $PKG; sleep 1; adb shell pm clear $PKG >/dev/null; adb logcat -c
adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/film.mkv" --es gfTestSubs "$BASE/film.srt" --es gfAudioStartSec 5 > $OUT/am-morte.txt 2>&1
sleep 14; pid0=$(mainpid); killed=0
for i in $(seq 1 40); do
  cp=$(adb shell pidof $PKG:audiocap | tr -d '\r' | awk '{print $1}')
  if [ -n "$cp" ]; then sleep 6; adb shell "kill -11 $cp"; killed=1; echo "matei o processo de captura $cp (SIGSEGV)" >> $OUT/resultado.txt; break; fi
  sleep 3
done
sleep 40
a4=1; resumed | grep -q PlayerActivity || a4=0; [ "$(mainpid)" = "$pid0" ] || a4=0
snap morte-captura
glog | grep -E "GabiFlixPlayer|AUDIOSYNC|libc|DEBUG|am_crash|am_proc_died|AndroidRuntime" | tail -120 > $OUT/log-morte-captura.txt
echo "morte-captura: captura_existia=$killed vivo=$a4 $(resumed)" | tee -a $OUT/resultado.txt
[ $killed = 1 ] && [ $a4 = 0 ] && FAILS=$((FAILS+1))
echo "FALHAS=$FAILS" | tee -a $OUT/resultado.txt
exit 0
