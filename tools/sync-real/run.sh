#!/bin/bash
# (sync-real) $1 = perfil (tel|tv), $2 = apk. Prova da sincronizacao da legenda pelo audio no emulador: legenda deslocada +3 s (automatico), -2 s (botao),
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
  if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/${CLIP:-clip.mkv}" --esa subs "$BASE/$sub" "$@"
  else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/${CLIP:-clip.mkv}" --es gfTestSubs "$BASE/$sub" "$@"; fi
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
ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r'); echo "abi=$ABI cpus=$(adb shell nproc | tr -d '\r') mem=$(adb shell grep MemTotal /proc/meminfo | tr -d '\r')" > $OUT/aparelho.txt
cp /tmp/server-bytes.log $OUT/ 2>/dev/null; : > /tmp/server-bytes.log


P_SLOW=1
DONE='AUDIOSYNC (aplicado|resultado:|desisti|botão:|o áudio não tem|pulado|já foi vista)'
panel_texts() { pause_v; show_controls; open_subs; sleep 1.5; snap "$1" 0.5; texts "$1"; adb shell input keyevent KEYCODE_BACK; sleep 1; play_v; }

if [ -f tools/sync-real/ONLY_G ]; then
  # (G) QUANTAS VEZES O MOTOR ANALISA A LEGENDA (pedido: "o motor fica a verificar várias vezes as legendas"). Material real: o trecho de 20 min de filme
  # com as legendas certa/+3/-2/outro. Contagem pelo registro: linhas "AUDIOSYNC GATILHO ... decisão=análise" (cada início de análise, com o motivo) e,
  # para comparar com a versão antiga (que não tem GATILHO), "AUDIOSYNC começando" + "AUDIOSYNC reverificando".
  # TIMERS ENCURTADOS NO TESTE (ganchos): gfAudioRetrySec=60 (a segunda tentativa depois de "sem confiança" seria 20 min) e gfAudioStartSec=10.
  # O soak do caso confiante usa o tempo REAL (11 min tocando depois do resultado, mais que os 10 min do monitor antigo) com os tempos de fábrica.
  CLIP=clip.mkv
  adb logcat -G 16M >/dev/null 2>&1
  SOAK=${G_SOAK:-660}
  RES_G=$OUT/G-resumo-$P.txt; : > $RES_G
  RX() { adb shell am broadcast -a io.github.gabrielxyz404.gabiflix.TEST -p $PKG --es cmd "$1" --es val "$2" >/dev/null 2>&1; sleep 1; }
  resumed() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -s ' ' | tr -d '\r'; }
  volta() {  # HOME e volta pelo ícone do lançador
    adb shell input keyevent KEYCODE_HOME; sleep 6
    if [ "$P" = tel ]; then adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $PKG/.MainActivity >/dev/null 2>&1
    else adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -f 0x10200000 -n $PKG/.tv.TvHomeActivity >/dev/null 2>&1; fi
    sleep 6
    resumed | grep -q PlayerActivity || { adb shell am start -n $PKG/.PlayerActivity -f 0x20000 >/dev/null 2>&1; sleep 6; }
    echo "volta: $(resumed)" >> $RES_G
  }
  conta() {
    echo "  análises iniciadas (GATILHO decisão=análise) = $(grep -c 'AUDIOSYNC GATILHO .*decisão=análise' $1)"
    echo "  resultados terminados (AUDIOSYNC resultado:) = $(grep -c 'AUDIOSYNC resultado:' $1)"
    echo "  [versão antiga e nova] começando=$(grep -c 'AUDIOSYNC começando' $1) reverificando=$(grep -c 'AUDIOSYNC reverificando' $1)"
    echo "  avisos na tela = $(grep -c 'AVISO na tela' $1) (só os de legenda: $(grep 'AVISO na tela' $1 | grep -c -i 'legenda'))"
    echo "  voltou ao app (onResume) = $(grep -c 'ciclo: onResume' $1)"
  }
  fase() {  # $1 = nome da fase
    save G-$1
    { echo "=== $1 ($P) $(date -u +%H:%M:%S) ==="; conta $OUT/log-$P-G-$1.txt
      grep -E 'AUDIOSYNC (GATILHO|salto|legenda ativa|pausa da tela|resultado:|aplicado|pronto|captura falhou|a captura derrubou|captura recuperada|tentativa)|AVISO na tela|teste: ação' $OUT/log-$P-G-$1.txt | sed -E 's/^[0-9.]+ +[0-9]+ +[0-9]+ [A-Z] [A-Za-z]+ *: //' | cut -c1-330
    } >> $RES_G
  }
  espera_n() {  # $1 = padrão, $2 = quantas vezes, $3 = segundos no máximo
    for i in $(seq 1 $(( $3 / 4 ))); do [ "$(glog | grep -c "$1")" -ge "$2" ] && return 0; sleep 4; done
    echo "TEMPO ESGOTADO esperando $2x: $1" >> $RES_G; return 1
  }
  EXTRAS="--es gfAudioStartSec 10 --es gfAudioRetrySec 60 --es gfRx 1"

  # G1) resultado CONFIANTE (+3 s => aplicado): nada de nova análise por tempo, por voltar ao app, por saltar, nem por reabrir o filme
  adb shell pm clear $PKG >/dev/null; sleep 2
  launch mais3.srt $EXTRAS
  waitlog "AUDIOSYNC (aplicado|resultado:)" 420; sleep 6
  snap G1-resultado 0.3
  fase G1a-resultado
  volta; sleep 20; volta; sleep 20                       # duas idas à tela inicial e voltas
  fase G1b-home-e-volta
  sleep $SOAK                                            # tocando, sem mexer em nada
  fase G1c-soak
  RX seek 60; sleep 45                                   # salto grande SEM mexer no atraso: não pode analisar
  fase G1d-salto-sem-ajuste
  launch mais3.srt $EXTRAS; sleep 100                    # reabrir o mesmo filme (dados mantidos): não pode analisar
  snap G1-reaberto 0.3
  fase G1e-reabrir
  RX delay 1500; sleep 4; RX seek 900                    # ajuste manual E salto de ~14 min: UMA nova análise (regra do pedido)
  waitlog "AUDIOSYNC (aplicado|resultado:)" 420; sleep 60
  fase G1f-ajuste-manual-e-salto

  # G1b) resultado confiante "já estava certa" (legenda certa): silencioso (aviso só na primeira conferência do filme)
  adb shell pm clear $PKG >/dev/null; sleep 2
  launch certa.srt $EXTRAS
  waitlog "AUDIOSYNC (aplicado|resultado:)" 420; sleep 90
  snap G1-certa 0.3
  fase G1g-certa
  launch certa.srt $EXTRAS; sleep 90                     # reabrir: nenhuma análise e nenhum aviso
  fase G1h-certa-reabrir

  # G2) resultado SEM confiança (legenda de outro filme): no máximo UMA nova tentativa (aqui 60 s depois, em vez de 20 min) e para
  adb shell pm clear $PKG >/dev/null; sleep 2
  launch outro.srt $EXTRAS
  espera_n "AUDIOSYNC resultado:" 2 600; sleep 240        # depois da segunda: 4 min sem terceira
  fase G2-sem-confianca

  # G3) a captura cai (processo :audiocap morre): recupera na hora e conta como UMA tentativa
  adb shell pm clear $PKG >/dev/null; sleep 2
  launch mais3.srt $EXTRAS --es gfAudioCrash capture
  waitlog "AUDIOSYNC (aplicado|resultado:)" 420; sleep 120
  fase G3a-queda-recuperada
  # G3b) cai em todas as configurações: erro => no máximo uma segunda tentativa
  adb shell pm clear $PKG >/dev/null; sleep 2
  launch mais3.srt $EXTRAS --es gfAudioCrash capture-all
  espera_n "AUDIOSYNC resultado:" 2 600; sleep 200
  fase G3b-queda-total

  adb logcat -d | grep -E "FATAL EXCEPTION" | head -20 > $OUT/crash.txt
  cat $RES_G; cat $OUT/passos.txt 2>/dev/null; exit 0
fi
if [ -f tools/sync-real/ONLY_F ]; then
  # condicoes reais: video por HTTP lento (rede limitada), legenda por URL (addSlave), sincronizacao automatica ja aplicada E uma nova analise
  # (re-checagem) capturando audio durante os seeks; seeks arrastando a linha do tempo, varios seguidos e rapidos, inclusive durante o carregamento
  python3 -c "import PIL" 2>/dev/null || pip install --user -q pillow
  CLIP=blk.mkv
  LOG=/tmp/server-bytes-f.log THROTTLE_KBPS=${F_KBPS:-70} nohup python3 tools/audio-integra/server.py /tmp/media 8002 >/tmp/server-f.out 2>&1 &
  sleep 2; BASE=http://10.0.2.2:8002
  for modo in 0 1; do
    N=m$modo-mais3
    adb shell pm clear $PKG >/dev/null; sleep 2
    launch mais3.srt --es gfSeekSec 240 --es gfAudioStartSec 240 --es gfAudioRecheckSec 25 --es gfScale $modo --es gfTrace 1
    P_SLOW=1 waitlog "AUDIOSYNC (aplicado|resultado:)" 300; sleep 2
    adb exec-out screencap -p > $OUT/foto-F-$N-aplicado.png
    mkdir -p $OUT/F-$N
    ( for i in $(seq 1 70); do a=$(date +%s.%N); adb exec-out screencap -p > $OUT/F-$N/$(printf %03d $i).png; b=$(date +%s.%N); echo "$(printf %03d $i) $a $b" >> $OUT/F-$N/times.txt; done ) &
    SH=$!
    sleep 3
    drag() { # $1 = fracao de partida, $2 = fracao de chegada
      xy=$(dump | python3 $UI xy "Linha do tempo")
      if [ -z "$xy" ]; then adb shell input tap $((W/2)) $((H/3)); sleep 0.6; xy=$(dump | python3 $UI xy "Linha do tempo"); fi
      [ -z "$xy" ] && { echo "F: sem linha do tempo" >> $OUT/passos.txt; return; }
      cx=${xy% *}; cy=${xy#* }; half=$((W/2-250))
      adb shell input swipe $((cx + (${1} - 50) * half / 50)) $cy $((cx + (${2} - 50) * half / 50)) $cy 250
    }
    drag 20 27; sleep 1.5; drag 27 24; sleep 1; drag 24 31; drag 31 22; sleep 6
    drag 22 29; sleep 5
    wait $SH
    save F-$N
    for i in 1; do python3 tools/sync-real/seekcheck.py $OUT/F-$N $OUT/log-$P-F-$N.txt $OUT/clock.txt F >> $OUT/seek-resumo.txt 2>&1; done
    ls $OUT/F-$N/*.png | awk 'NR%5!=1' | xargs rm -f
    adb exec-out screencap -p > $OUT/foto-F-$N-final.png
  done
  cat $OUT/seek-resumo.txt; exit 0
fi
if [ -f tools/sync-real/ONLY_E ]; then
  # a legenda some depois de avancar/voltar na linha do tempo? video preto (so o texto da legenda e claro). Atraso posto por gancho de teste
  # (positivo e negativo), Normal e Preencher; compara cada foto com o que a legenda certa deveria mostrar naquele instante da midia
  python3 -c "import PIL" 2>/dev/null || pip install --user -q pillow
  CLIP=blk.mkv
  phase() { # $1 = nome; fotos com hora; depois mantem so 1 de cada 6
    mkdir -p $OUT/E-$1; for i in $(seq 1 ${2:-30}); do a=$(date +%s.%N); adb exec-out screencap -p > $OUT/E-$1/$(printf %03d $i).png; b=$(date +%s.%N); echo "$(printf %03d $i) $a $b" >> $OUT/E-$1/times.txt; done
  }
  for modo in 0 1; do
    for caso in "certa:0" "mais3:-3000" "menos2:2000"; do
      n=${caso%%:*}; d=${caso#*:}; N=m$modo-$n
      adb shell pm clear $PKG >/dev/null; sleep 2
      launch $n.srt --es gfAudioAuto false --es gfSeekSec 240 --es gfSubDelay $d --es gfScale $modo --es gfTrace 1
      sleep 18
      phase $N-0-antes 24
      adb shell input keyevent KEYCODE_MEDIA_FAST_FORWARD; sleep 7; phase $N-1-avancar 24
      sleep 9; adb shell input keyevent KEYCODE_MEDIA_REWIND; sleep 4; phase $N-2-voltar 30
      save E-$N
      for ph in 0-antes 1-avancar 2-voltar; do python3 tools/sync-real/seekcheck.py $OUT/E-$N-$ph $OUT/log-$P-E-$N.txt $OUT/clock.txt $n >> $OUT/seek-resumo.txt 2>&1; ls $OUT/E-$N-$ph/*.png | awk 'NR%8!=1' | xargs rm -f; done
      adb exec-out screencap -p > $OUT/foto-$N-final.png
    done
  done
  cat $OUT/seek-resumo.txt; exit 0
fi
if [ -f tools/sync-real/ONLY_D ]; then
  # a legenda some depois que o motor aplica o atraso? rajada de fotos (video preto: so o texto da legenda e claro) para cada estrategia
  python3 -c "import PIL" 2>/dev/null || pip install --user -q pillow
  CLIP=blk.mkv
  for st in ${STRATS:-plain ramp readd}; do
    for sc in mais3 menos2 mais12; do
      adb shell pm clear $PKG >/dev/null; sleep 2
      launch $sc.srt --es gfSeekSec 250 --es gfAudioStartSec 8 --es gfDelayStrategy $st
      waitlog "AUDIOSYNC legenda: atraso" 200
      D=$OUT/D-$st-$sc; mkdir -p $D
      for i in $(seq 1 100); do
        a=$(date +%s.%N); adb exec-out screencap -p > $D/$(printf %03d $i).png; b=$(date +%s.%N)
        echo "$(printf %03d $i) $a $b" >> $D/times.txt
      done
      save D-$st-$sc
      python3 tools/sync-real/burst.py $D $OUT/log-$P-D-$st-$sc.txt $OUT/clock.txt $sc >> $OUT/burst-resumo.txt 2>&1
      ls $D/*.png | awk 'NR%8!=1' | xargs rm -f
    done
  done
  cat $OUT/burst-resumo.txt; exit 0
fi
if [ -f tools/sync-real/ONLY_B ]; then
  for c in aac ac3b eac3 dts truehd; do
    CLIP=real-$c.mkv
    adb shell pm clear $PKG >/dev/null; sleep 2
    for sc in "mais3:mais3.srt" "certa:certa.srt" "outro:outro.srt"; do
      n=${sc%%:*}; f=${sc#*:}
      launch $f --es gfAudioStartSec 10
      waitlog "$DONE" 300; sleep 2
      save B-$c-$n
      adb shell pm clear $PKG >/dev/null; sleep 2
    done
  done
  adb logcat -d | grep -E "FATAL EXCEPTION" | head -20 > $OUT/crash.txt; cat $OUT/passos.txt; exit 0
fi
# ---- C) fechar o painel logo depois de apertar "Sincronizar automaticamente": a analise segue e o resultado aparece como aviso sobre o filme
CLIP=clip.mkv
for sc in "C1:menos2.srt" "C2:certa.srt"; do
  n=${sc%%:*}; f=${sc#*:}
  launch $f --es gfAudioAuto false
  sleep 22; pause_v; show_controls; open_subs; sleep 1.5
  click "Sincronizar automaticamente"
  sleep 1.6; snap $n-painel-fechou-aviso-sincronizando 0.2; texts $n-depois-de-apertar
  play_v
  waitlog "AUDIOSYNC botão:" 200; sleep 1.2
  snap $n-aviso-do-resultado 0.1; texts $n-aviso
  sleep 6; pause_v; show_controls; open_subs; sleep 1.5; snap $n-painel-reaberto 0.5; texts $n-painel-reaberto
  save $n
done

if [ -f tools/sync-real/ONLY_C ]; then adb logcat -d | grep -E "FATAL EXCEPTION" | head -20 > $OUT/crash.txt; cat $OUT/passos.txt; exit 0; fi
# ---- A) 20 Mbit/s, AC3 5.1 + AAC (dublagem). A1: +3 s automatico, rede sem limite
CLIP=real-ac3.mkv
cpu_start; bytes_mark A1a
launch mais3.srt --es gfAudioStartSec 10 --es gfAudioRecheckSec 60
waitlog "$DONE" 400; sleep 3; snap A1-depois-da-analise 0.3
bytes_mark A1b
panel_texts A1-painel
save A1-mais3-auto; cpu_save A1

# A2: -2 s pelo botao (automatico desligado)
launch menos2.srt --es gfAudioAuto false
sleep 25; bytes_mark A2a; pause_v; show_controls; open_subs; sleep 1.5; snap A2-painel-antes 0.5
click "Sincronizar automaticamente"; snap A2-botao-sincronizando 0.3
sleep 20; snap A2-botao-20s 0.3; texts A2-painel-20s
waitlog "AUDIOSYNC botão:" 420; sleep 3
snap A2-botao-final 0.5; texts A2-painel-final; bytes_mark A2b
save A2-menos2-botao

# A3: legenda certa, automatico: nada pode mudar
launch certa.srt --es gfAudioStartSec 10 --es gfAudioRecheckSec 60
waitlog "$DONE" 400; sleep 3; snap A3-certa 0.3
panel_texts A3-painel
save A3-certa

# A4: legenda de outro filme: recusar, mas dizer por que
launch outro.srt --es gfAudioStartSec 10
waitlog "$DONE" 400; sleep 3; snap A4-outro 0.3
panel_texts A4-painel
save A4-outro

# A5: +3 s com a rede limitada a 40 Mbit/s no total (a reproducao ja usa 20)
LOG=/tmp/server-bytes-lenta.log THROTTLE_KBPS=5120 nohup python3 tools/audio-integra/server.py /tmp/media 8001 >/tmp/server-lenta.out 2>&1 &
sleep 2
BASE=http://10.0.2.2:8001
adb shell pm clear $PKG >/dev/null; sleep 2
launch mais3.srt --es gfAudioStartSec 20
P_SLOW=1 waitlog "$DONE" 700; sleep 3; snap A5-rede-40mbit 0.3
panel_texts A5-painel
save A5-lenta40
BASE=http://10.0.2.2:8000

# ---- B) so muda o codec do audio (5.1)
adb shell pm clear $PKG >/dev/null; sleep 2
for c in eac3 dts truehd; do
  CLIP=real-$c.mkv
  launch mais3.srt --es gfAudioStartSec 10
  waitlog "$DONE" 300; sleep 3; snap B-$c 0.3
  save B-$c
  adb shell pm clear $PKG >/dev/null; sleep 2
done

adb logcat -d | tail -400 > $OUT/logcat-final.txt
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|libc.*Fatal" | head -40 > $OUT/crash.txt
cp /tmp/server-bytes.log $OUT/server-bytes.log 2>/dev/null
cp /tmp/server-bytes-lenta.log $OUT/ 2>/dev/null
cat $OUT/passos.txt $OUT/crash.txt
exit 0
