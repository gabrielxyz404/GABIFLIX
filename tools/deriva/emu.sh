#!/bin/bash
# $1 = perfil (tel|tv), $2 = apk. Prova da DERIVA de escala no emulador: video com a fala certa QUEIMADA no alto (clip.mkv) e a legenda deformada (24/23.976, +1,5 s) no player; fotos perto do inicio e do fim.
# certa (nao pode mudar) e de outro filme (tem de recusar). Fotos, logs com tempos e amostras de CPU/memoria/rede. Nunca publica nada.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
[ "$P" = tel ] && { adb shell wm size 1080x2400; adb shell wm density 420; }
adb shell wm size > $OUT/wm.txt
adb root >/dev/null 2>&1; sleep 3
N=0
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
snap() { N=$((N+1)); sleep "${2:-0.5}"; adb exec-out screencap -p > "$OUT/$(printf %02d $N)-$P-$1.png"; echo "$(printf %02d $N) $1" >> $OUT/passos.txt; }
pause_v() { adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1; }
play_v() { adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.7; done; }
W=2400; [ "$P" = tv ] && W=1920; H=1080
BASE=${B:-http://10.0.2.2:8000}
show_controls() { if [ "$P" = tel ]; then xy=$(dump | python3 $UI xy "Voltar"); [ -z "$xy" ] && adb shell input tap $((W/2)) $((H/3)); else key DPAD_DOWN; fi; sleep 1; }
open_subs() { if [ "$P" = tel ]; then click "Legendas"; else key DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 1; fi; }
click() {
  if [ "$P" = tel ]; then
    for t in 1 2 3; do xy=$(dump | python3 $UI xy "$1"); [ -n "$xy" ] && break; show_controls; done
    [ -n "$xy" ] && adb shell input tap $xy || echo "NAO ACHEI: $1" >> $OUT/passos.txt
  else
    for t in $(seq 1 14); do
      d=$(dump | python3 $UI nav "$1")
      if [ "$d" = HERE ]; then adb shell input keyevent DPAD_CENTER; sleep 1; return; fi
      if [ "$d" = NONE ]; then show_controls; continue; fi
      adb shell input keyevent $d; sleep 0.7
    done
    echo "TV: NAO CHEGUEI em '$1'" >> $OUT/passos.txt
  fi
  sleep 1
}
texts() { dump | python3 $UI texts > "$OUT/$1.txt"; }
# $1 = legenda (arquivo), resto = extras "--es chave valor"
launch() {
  local sub=$1; shift
  adb shell am force-stop $PKG; sleep 1; adb logcat -c
  if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/clip.mkv" --esa subs "$BASE/$sub" "$@"
  else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/clip.mkv" --es gfTestSubs "$BASE/$sub" "$@"; fi
}
glog() { adb logcat -v epoch -d -s GabiFlixPlayer 2>/dev/null | grep -v "^---" ; }
waitlog() {  # $1 = padrao, $2 = segundos (no maximo 150: se nada acontece, segue e o log mostra por que)
  local lim=$2; [ $lim -gt 150 ] && [ "$P_SLOW" != 1 ] && lim=150
  for i in $(seq 1 $(( lim / 2 ))); do glog | grep -q -E "$1" && return 0; sleep 2; done
  echo "TEMPO ESGOTADO esperando: $1" >> $OUT/passos.txt; return 1
}
save() { glog > "$OUT/log-$P-$1.txt"; }
cpu_start() { adb push tools/audio-integra/cpu.sh /data/local/tmp/cpu.sh >/dev/null; adb shell "rm -f /sdcard/cpu.txt; nohup sh /data/local/tmp/cpu.sh >> /sdcard/cpu.txt 2>&1 </dev/null &"; }
cpu_save() { adb shell cat /sdcard/cpu.txt > "$OUT/cpu-$P-$1.txt"; }
bytes_mark() { curl -s http://127.0.0.1:8000/__bytes > "$OUT/bytes-$P-$1.txt"; date +%s >> "$OUT/bytes-$P-$1.txt"; }
echo "host=$(date +%s) dispositivo=$(adb shell date +%s | tr -d '\r')" > $OUT/clock.txt
cp /tmp/server-bytes.log $OUT/ 2>/dev/null; : > /tmp/server-bytes.log
# modelo da legenda deformada: tempo_legenda = G * tempo_certo + O (G = 24/23.976, O = +1500 ms) => atraso certo no instante t (s) = (1 - G) * t - O
G=1.001001001; O=1500
dly() { python3 -c "print(int(round(((1-$G)*$1*1000 - $O)/10.0))*10)"; }
rx() { adb shell am broadcast -a io.github.gabrielxyz404.gabiflix.TEST --es cmd "$1" --es val "$2" >/dev/null 2>&1; sleep "${3:-2}"; }
# fotos: perto do inicio (130 s) e perto do fim (1080 s); a fala certa esta queimada no alto do video, a do player embaixo
shots() {  # $1 = prefixo
  rx seek 130 3; sleep 20; snap "$1-130s-a" 0.2; sleep 1.3; snap "$1-130s-b" 0.2; sleep 1.3; snap "$1-130s-c" 0.2; echo "pos130 $(glog | grep -E ' pos ' | tail -1)" >> $OUT/passos.txt
  rx seek 1080 3; sleep 20; snap "$1-1080s-a" 0.2; sleep 1.3; snap "$1-1080s-b" 0.2; sleep 1.3; snap "$1-1080s-c" 0.2; echo "pos1080 $(glog | grep -E ' pos ' | tail -1)" >> $OUT/passos.txt
}
echo "atraso certo em 130 s = $(dly 130) ms; em 1080 s = $(dly 1080) ms" >> $OUT/passos.txt
X="--es gfRx 1 --es gfTrace 1 --es gfNoReport true"

# ---- 1) ANTES: legenda deformada, automatico desligado, sem ajuste: desalinhada desde o inicio
launch deriva.srt $X --es gfAudioAuto false
sleep 25; shots 1-antes-sem-ajuste
save 1-antes

# ---- 2) ANTES (o caso do relato): o usuario acerta a mao perto do inicio e a legenda volta a desviar mais tarde
launch deriva.srt $X --es gfAudioAuto false --es gfSubDelay $(dly 130)
sleep 25; shots 2-antes-manual-no-inicio
save 2-antes-manual

adb shell pm clear $PKG >/dev/null; sleep 2
# ---- 3) NOVO: automatico (janelas espalhadas + prova de deriva): a escala 1,001 entra; certa perto do inicio e perto do fim
launch deriva.srt $X --es gfAudioStartSec 10
waitlog "AUDIOSYNC aplicado" 240 && snap 3-aviso-deriva 0.3
shots 3-novo-automatico
save 3-novo-automatico

adb shell pm clear $PKG >/dev/null; sleep 2
# ---- 4) NOVO com atraso manual ja presente (certo perto de 130 s): o manual vence, so a escala entra, ancorada no ponto (sem pulo)
launch deriva.srt $X --es gfSeekSec 120 --es gfSubDelay $(dly 130) --es gfAudioStartSec 15
waitlog "AUDIOSYNC (aplicado|o áudio sugere|escala .* achada)" 240 || true
sleep 2; snap 4-manual-presente-depois-da-analise 0.3
shots 4-novo-com-manual
save 4-novo-com-manual

adb shell pm clear $PKG >/dev/null; sleep 2
# ---- 5) NOVO: DOIS ajustes manuais a mais de 10 min um do outro (automatico desligado): o motor deduz a escala e avisa
launch deriva.srt $X --es gfAudioAuto false
sleep 12; rx seek 130 3; rx delay $(dly 130) 7; rx seek 1000 3; rx delay $(dly 1000) 1
for i in $(seq 1 40); do glog | grep -q "deriva pelos ajustes manuais" && break; sleep 0.5; done
snap 5-aviso-dois-pontos 0.2
sleep 5; shots 5-novo-dois-pontos
save 5-novo-dois-pontos

adb shell pm clear $PKG >/dev/null; sleep 2
# ---- 6) legenda CERTA: nada pode mudar
launch certa.srt $X --es gfAudioStartSec 10
waitlog "AUDIOSYNC resultado:" 240; sleep 3
shots 6-certa-intacta
save 6-certa

adb shell pm clear $PKG >/dev/null; sleep 2
# ---- 7) legenda de OUTRO filme: recusa, nada muda
launch outro.srt $X --es gfAudioStartSec 10
waitlog "AUDIOSYNC resultado:" 240; sleep 3
snap 7-outro-filme 0.3
save 7-outro

adb logcat -d | tail -400 > $OUT/logcat-final.txt
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|libc.*Fatal" | head -40 > $OUT/crash.txt
cat $OUT/passos.txt $OUT/crash.txt
exit 0
