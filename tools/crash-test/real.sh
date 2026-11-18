#!/bin/bash
# $1 = perfil (tel|tv)  $2 = apk  $3 = rótulo. Filme com fala REAL (trecho de 20 min de filme de domínio público) e legenda PT de verdade, SEM ganchos de tempo:
# espera o resultado do automático (FIXED com mais3.srt, "já estava certa" com certa.srt) e depois vigia se o app continua no player.
# Celular: também HOME durante a análise e volta pelo ícone do lançador.
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tv}; APK=$2; L=${3:-x}
OUT=shots; mkdir -p $OUT
BASE=http://10.0.2.2:8000
adb install -r "$APK" || exit 1
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0 2>/dev/null
adb shell settings put system screen_off_timeout 600000
[ "$P" = tel ] && { adb shell wm size 1080x2400; adb shell wm density 420; }
adb root >/dev/null 2>&1; sleep 3
adb logcat -G 16M >/dev/null 2>&1
echo "aparelho: mem=$(adb shell grep MemTotal /proc/meminfo | tr -d '\r') api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')" > $OUT/$L-$P-resultado.txt
snap() { adb exec-out screencap -p > "$OUT/$L-$P-$1.png"; }
glog() { adb logcat -v epoch -d 2>/dev/null; }
resumed() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -s ' ' | tr -d '\r'; }
mainpid() { adb shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
pilha() { adb shell dumpsys activity activities 2>/dev/null | grep -E "Task\{|Hist #" | grep -i -E "gabiflix|Task\{" | head -12 | tr -s ' ' | tr -d '\r'; }
rep() { echo "$*" | tee -a $OUT/$L-$P-resultado.txt; }
abrir() {  # $1 = legenda
  adb shell am force-stop $PKG; sleep 1; adb shell pm clear $PKG >/dev/null; adb logcat -c
  if [ "$P" = tel ]; then
    adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $PKG/.MainActivity; sleep 8
    adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/clip.mkv" --esa subs "$BASE/$1" $EXTRA
  else
    adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/clip.mkv" --es gfTestSubs "$BASE/$1" $EXTRA
  fi
}
volta_icone() {
  if [ "$P" = tel ]; then adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $PKG/.MainActivity
  else adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n $PKG/.tv.TvHomeActivity; fi
}
RES='AUDIOSYNC (aplicado|resultado:|desisti|o áudio não tem|pulado|já foi vista)'
fim() {  # $1 = nome
  glog | grep -E "GabiFlixPlayer|GFCrash|AUDIOSYNC|libc|DEBUG|AndroidRuntime|am_crash|am_proc_died|am_kill|lowmemorykiller|ActivityTaskManager|ActivityManager" | tail -400 > "$OUT/$L-$P-log-$1.txt"
  glog | grep -E "FATAL EXCEPTION|Fatal signal|am_crash|am_proc_died|am_kill" | head -20 > "$OUT/$L-$P-fatal-$1.txt"
  adb shell ls -la /data/tombstones > "$OUT/$L-$P-tombstones-$1.txt" 2>&1
  rep "$1: linhas fatais=$(wc -l < $OUT/$L-$P-fatal-$1.txt) | $(glog | grep 'AUDIOSYNC resultado:' | tail -1 | cut -c1-260)"
}
# espera o resultado e depois vigia $3 s
caso_fg() {  # $1 nome  $2 legenda  $3 segundos de vigia depois do resultado
  local n=$1 t0=$(date +%s) pid0 viu=0 vivo=1 why=""
  abrir $2; sleep 15; pid0=$(mainpid); snap $n-1-abriu
  rep "$n: início $(resumed)"; pilha >> $OUT/$L-$P-resultado.txt
  while [ $(( $(date +%s) - t0 )) -lt 900 ]; do
    sleep 5
    resumed | grep -q PlayerActivity || { vivo=0; why="saiu do player: $(resumed)"; break; }
    [ "$(mainpid)" = "$pid0" ] || { vivo=0; why="o processo principal mudou ($pid0 -> $(mainpid))"; break; }
    glog | grep -q -E "$RES" && { viu=1; break; }
  done
  [ $viu = 1 ] && snap $n-2-resultado
  if [ $vivo = 1 ]; then
    local e=$(( $(date +%s) + $3 ))
    while [ $(date +%s) -lt $e ]; do
      sleep 4
      resumed | grep -q PlayerActivity || { vivo=0; why="saiu do player DEPOIS do resultado: $(resumed)"; break; }
      [ "$(mainpid)" = "$pid0" ] || { vivo=0; why="o processo caiu DEPOIS do resultado"; break; }
    done
  fi
  snap $n-3-fim
  rep "$n: resultado_visto=$viu vivo=$vivo $why"
  fim $n
}
# celular: HOME durante a análise, espera, volta pelo ícone
caso_home() {  # $1 nome  $2 legenda  $3 segundos até o HOME  $4 segundos fora
  local n=$1 pid0
  abrir $2; sleep $3; pid0=$(mainpid); snap $n-1-antes-do-home
  rep "$n: antes do HOME $(resumed) pid=$pid0"; pilha >> $OUT/$L-$P-resultado.txt
  adb shell input keyevent 3; sleep 4; snap $n-2-home
  local e=$(( $(date +%s) + $4 ))
  while [ $(date +%s) -lt $e ]; do sleep 5; glog | grep -q -E "$RES" && { rep "$n: resultado chegou com o app fora"; break; }; done
  rep "$n: fora, pid=$(mainpid) (era $pid0)"
  sleep $(( e - $(date +%s) > 0 ? e - $(date +%s) : 0 ))
  rep "$n: antes de voltar: pid=$(mainpid)"
  glog | grep -E " $pid0 |:$pid0|am_kill|am_proc_died|lowmemorykiller|lmkd|Process .* died|gabiflix|ApplicationExitInfo|exit-info" | grep -v "legenda do app" | cut -c1-300 > "$OUT/$L-$P-morte-$n.txt"
  adb shell dumpsys activity exit-info $PKG > "$OUT/$L-$P-exitinfo-$n.txt" 2>&1
  adb shell dumpsys meminfo | head -12 > "$OUT/$L-$P-meminfo-$n.txt" 2>&1
  volta_icone; sleep 5; snap $n-3-voltou
  rep "$n: voltou $(resumed) pid=$(mainpid)"; pilha >> $OUT/$L-$P-resultado.txt
  if resumed | grep -q PlayerActivity; then rep "$n => VOLTOU AO FILME"; else rep "$n => NAO VOLTOU AO FILME"; fi
  sleep 20; snap $n-4-depois
  fim $n
}
# queda nativa de propósito no processo :audiocap (ganchos de teste gfAudioCrash): a análise tem de se recuperar, o filme não pára, e o relatório sai na hora
queda() {  # $1 nome  $2 gancho (vad|capture|capture-all)  $3 legenda  $4 segundos de vigia depois do resultado
  local n=$1
  EXTRA="--es gfAudioCrash $2" caso_fg $n $3 $4
  # o filme com a legenda na tela, várias capturas seguidas (uma delas pega uma fala)
  for k in 1 2 3 4 5 6 7 8; do snap $n-4-filme-$k; sleep 3; done
  rep "$n: ===== PROVA (gancho $2) ====="
  glog | grep -E 'AUDIOSYNC (o processo de captura caiu|a captura derrubou|o Silero derrubou|captura recuperada|Silero indisponível|Silero desligado|captura falhou|aplicado|resultado:)' | cut -c1-330 >> $OUT/$L-$P-resultado.txt
  rep "$n: relatórios CAPTURA_CAIU enviados: $(glog | grep -c 'POST CAPTURA_CAIU parte') | respostas: $(glog | grep 'POST CAPTURA_CAIU parte' | grep -o 'HTTP [0-9]*' | sort | uniq -c | tr '\n' ' ')"
  glog | grep 'GFCrash' | grep -E 'corpo de CAPTURA_CAIU|CAPTURA_CAIU \| processo|Etapa em curso|Última etapa|Motivo da morte|Fatal signal|backtrace|#[0-9]+ +pc|Abort message|ABI:|Registro de queda|Registo de queda|TESTE: queda' | cut -c1-300 > $OUT/$L-$P-relatorio-$n.txt
  glog | grep 'GFCrash' > $OUT/$L-$P-relatorio-completo-$n.txt
  rep "$n: linhas de queda no relatório (Fatal signal/backtrace/#NN pc): $(grep -c -E 'Fatal signal|backtrace|#[0-9]+ +pc' $OUT/$L-$P-relatorio-$n.txt)"
  rep "$n: o app ainda está no player? $(resumed)"
}
case "$P" in
  tv)
    queda tv-queda-vad vad mais3.srt 40
    queda tv-queda-vad-certa vad certa.srt 40
    queda tv-queda-captura capture mais3.srt 40
    queda tv-queda-todas capture-all mais3.srt 40
    caso_fg tv-fixed mais3.srt 120
    caso_fg tv-certa certa.srt 120
    ;;
  tel)
    queda tel-queda-vad vad mais3.srt 40
    queda tel-queda-vad-certa vad certa.srt 40
    queda tel-queda-captura capture mais3.srt 40
    caso_fg tel-fixed mais3.srt 90
    caso_home tel-home-analise menos2.srt 50 150
    caso_home tel-home-2min certa.srt 20 120
    caso_home tel-home-2min-b certa.srt 20 120
    caso_home tel-home-2min-c certa.srt 20 120
    ;;
esac
exit 0
