#!/bin/bash
# $1 = perfil (tel|tv), $2 = apk. Prova da sincronizacao da legenda pelo audio no emulador: legenda deslocada +3 s (automatico), -2 s (botao),
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
glog() { adb logcat -v epoch -d 2>/dev/null | grep -E "GabiFlixPlayer|GFPlayer" | grep -E "AUDIOSYNC|atraso|sincroniza" ; }
waitlog() {  # $1 = padrao, $2 = segundos
  for i in $(seq 1 $(( $2 / 2 ))); do glog | grep -q -E "$1" && return 0; sleep 2; done
  echo "TEMPO ESGOTADO esperando: $1" >> $OUT/passos.txt; return 1
}
save() { glog > "$OUT/log-$P-$1.txt"; }
cpu_start() { adb push tools/audio-integra/cpu.sh /data/local/tmp/cpu.sh >/dev/null; adb shell "rm -f /sdcard/cpu.txt; nohup sh /data/local/tmp/cpu.sh >> /sdcard/cpu.txt 2>&1 </dev/null &"; }
cpu_save() { adb shell cat /sdcard/cpu.txt > "$OUT/cpu-$P-$1.txt"; }
bytes_mark() { curl -s http://127.0.0.1:8000/__bytes > "$OUT/bytes-$P-$1.txt"; date +%s >> "$OUT/bytes-$P-$1.txt"; }
echo "host=$(date +%s) dispositivo=$(adb shell date +%s | tr -d '\r')" > $OUT/clock.txt
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r'); echo "abi=$ABI cpus=$(adb shell nproc | tr -d '\r') mem=$(adb shell grep MemTotal /proc/meminfo | tr -d '\r')" > $OUT/aparelho.txt
cp /tmp/server-bytes.log $OUT/ 2>/dev/null; : > /tmp/server-bytes.log

# ---- 1) legenda +3 s, automatico (sem apertar nada): aviso, atraso -3,0 s, depois reverificacao curta (45 s) que nao pode mexer
cpu_start; bytes_mark 1a
launch mais3.srt --es gfAudioStartSec 10 --es gfAudioRecheckSec 45
waitlog "AUDIOSYNC aplicado" 240 && snap 1-aviso-legenda-acertada-pelo-audio 0.3
bytes_mark 1b
sleep 4; show_controls; open_subs; sleep 1.5; snap 1-painel-atraso-aplicado 0.5; texts 1-painel
adb shell input keyevent KEYCODE_BACK; sleep 1
waitlog "AUDIOSYNC reverificação:" 150; sleep 3
save 1-mais3; cpu_save 1; bytes_mark 1c
snap 1-depois-da-reverificacao 0.3; adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1; snap 1-pausado-legenda 0.5

# ---- 2) legenda -2 s, botao "Sincronizar automaticamente" (automatico desligado)
launch menos2.srt --es gfAudioAuto false
sleep 22; show_controls; open_subs; sleep 1.5
snap 2-painel-antes 0.5
click "Sincronizar automaticamente"
snap 2-botao-sincronizando 0.3
waitlog "AUDIOSYNC botão:" 240; sleep 2
snap 2-botao-status-final 0.5; texts 2-painel-final
save 2-menos2

# ---- 3) legenda CERTA: automatico + reverificacao; nada pode mudar
launch certa.srt --es gfAudioStartSec 10 --es gfAudioRecheckSec 45
waitlog "AUDIOSYNC resultado:" 240; sleep 2
snap 3-certa-depois-da-analise 0.3
show_controls; open_subs; sleep 1.5; snap 3-certa-painel-atraso-zero 0.5; texts 3-painel
adb shell input keyevent KEYCODE_BACK; sleep 1
waitlog "AUDIOSYNC reverificação:" 150; sleep 3
snap 3-certa-depois-da-reverificacao 0.3
save 3-certa

# ---- 3b) legenda CERTA pelo botao: "Ja estava certa"
launch certa.srt --es gfAudioAuto false
sleep 22; show_controls; open_subs; sleep 1.5
click "Sincronizar automaticamente"
waitlog "AUDIOSYNC botão:" 240; sleep 2
snap 3b-certa-botao-ja-estava-certa 0.5; texts 3b-painel
save 3b-certa-botao

# ---- 4) legenda de OUTRO filme: tem de recusar e deixar como estava
launch outro.srt --es gfAudioStartSec 10
waitlog "AUDIOSYNC resultado:" 240; sleep 2
show_controls; open_subs; sleep 1.5; snap 4-outro-filme-painel-atraso-zero 0.5; texts 4-painel
save 4-outro

# ---- 5) VAD de reserva (DSP) com a +3 s: so informativo
launch mais3.srt --es gfAudioStartSec 10 --es gfAudioDsp true
waitlog "AUDIOSYNC resultado:" 240; sleep 2
snap 5-dsp-reserva 0.3
save 5-dsp

# ---- 6) rede lenta: 3 Mbit/s no total (a reproducao usa ~1,9 Mbit/s: sobram ~1,1 Mbit/s para a captura); a reproducao nao pode parar
cp /tmp/server-bytes.log $OUT/server-bytes-rapida.log 2>/dev/null
LOG=/tmp/server-bytes-lenta.log THROTTLE_KBPS=375 nohup python3 tools/audio-integra/server.py /tmp/media 8001 >/tmp/server-lenta.out 2>&1 &
sleep 2
BASE=http://10.0.2.2:8001
launch mais3.srt --es gfAudioStartSec 30
waitlog "AUDIOSYNC resultado:" 840; sleep 2
snap 6-rede-lenta-depois 0.3
save 6-rede-lenta
cp /tmp/server-bytes-lenta.log $OUT/server-bytes-lenta.log 2>/dev/null
BASE=http://10.0.2.2:8000

adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|libc.*Fatal" | head -40 > $OUT/crash.txt
adb logcat -d | grep -i -E "onnx|ORT|UnsatisfiedLink" | head -20 > $OUT/ort.txt
cp /tmp/server-bytes.log $OUT/server-bytes.log 2>/dev/null
cat $OUT/passos.txt $OUT/crash.txt
exit 0
