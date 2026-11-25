#!/bin/bash
# Prova do botão "Sincronizar automaticamente" do FramePlay para Android (celular e TV) num emulador x86 (adb já ligado).
# Uso: android-prova.sh <tel|tv> <apk> <antes|depois>
# Material em /tmp/media (servido por tools/audio-integra/server.py: 8000 normal, 8001 trava as leituras longe do começo): ~90 min de um filme de domínio público
# a 23,976 fps (film.mkv), a legenda PT certa deformada para 25 fps + 4 s (sub25.srt) como legenda externa, a MESMA legenda embutida como faixa do MKV
# (film-emb.mkv), um vídeo só com música (musica.mkv, sem fala). Verdade-de-campo: certa.srt (alinhada ao áudio pelo alass).
# Cenários: 1 externa (antes/depois/segundo toque/toque durante a análise), 2 externa com a análise automática a correr quando se toca, 3 embutida,
# 4 sem fala (mensagem clara + ajuste manual), 5 rede travada (tempo limite).
# As fotos e os registros ficam em shots/. Nunca publica nada.
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}; V=${3:-depois}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
CERTA=/tmp/media/certa.srt
BASE=http://10.0.2.2:8000
SLOW=http://10.0.2.2:8001
W=2400; [ "$P" = tv ] && W=1920; H=1080
FAIL=0
N=0
: > $OUT/passos.txt; : > $OUT/conferencia.txt
note() { echo "$(date +%s) $*" | tee -a $OUT/passos.txt; }
adb install -r "$APK" >/dev/null 2>&1 || adb install -r "$APK"
[ "$P" = tel ] && { adb shell wm size 1080x2400; adb shell wm density 420; }
adb shell wm size > $OUT/wm.txt
adb root >/dev/null 2>&1; sleep 3
adb logcat -G 16M >/dev/null 2>&1
{ echo "perfil=$P versao=$V"; adb shell getprop ro.product.cpu.abi; adb shell getprop ro.build.version.release; adb shell nproc; adb shell grep MemTotal /proc/meminfo; adb shell cat /proc/uptime; ls -la /tmp/media; } 2>&1 | tr -d '\r' > $OUT/aparelho.txt

dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
snap() { N=$((N+1)); sleep "${2:-0.5}"; adb exec-out screencap -p > "$OUT/$(printf %02d $N)-$P-$V-$1.png"; note "foto $(printf %02d $N) $1"; }
texts() { dump | python3 $UI texts > "$OUT/$P-$V-$1.txt"; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.7; done; }
pause_v() { adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1; }
play_v() { adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1; }
RX() { adb shell am broadcast -a io.github.gabrielxyz404.gabiflix.TEST -p $PKG --es cmd "$1" --es val "$2" >/dev/null 2>&1; sleep 1; }
glog() { adb logcat -v epoch -d -s GabiFlixPlayer 2>/dev/null | grep -v "^---"; }
save() { glog > "$OUT/log-$P-$V-$1.txt"; }

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
# toque de verdade no botão do painel de legendas (o mesmo caminho do usuário)
tap_sync() { pause_v; show_controls; open_subs; sleep 1.5; texts "$1-painel"; click "Sincronizar automaticamente"; play_v; }

# a resposta ao toque: o aviso na tela (versão nova) ou o registro da sincronização (a antiga nem sempre mostra aviso)
RES_RE='AVISO na tela: (Legenda sincronizada|Legenda acertada|Legenda já estava certa|Já estava certa|Não consegui|Ajuste manual feito|A legenda mudou)|sincronização automática: status='
nres() { glog | grep -c -E "$RES_RE"; }
markres() { RESBASE=$(nres); }
waitres() {  # $1 = segundos; espera uma resposta NOVA ao toque (depois de markres)
  local n=$(( $1 / 3 ))
  for i in $(seq 1 $n); do [ "$(nres)" -gt "$RESBASE" ] && return 0; sleep 3; done
  note "TEMPO ESGOTADO esperando a resposta ao toque ($1 s)"; return 1
}
waitlog() {  # $1 = padrão, $2 = segundos
  local n=$(( $2 / 2 ))
  for i in $(seq 1 $n); do glog | grep -q -E "$1" && return 0; sleep 2; done
  note "TEMPO ESGOTADO esperando: $1"; return 1
}
lastep() { glog | grep -E "$1" | tail -1 | cut -d' ' -f1; }
launch() {  # $1 = nome, $2 = vídeo, $3 = legenda externa ("none" = nenhuma), $4 = base, resto = extras "--es chave valor"
  local name=$1 video=$2 sub=$3 base=$4; shift 4
  adb shell am force-stop $PKG; sleep 1; adb shell pm clear $PKG >/dev/null 2>&1; sleep 2
  adb logcat -c
  local surl="none"; [ "$sub" != none ] && surl="$base/$sub"
  note "=== $name: $video legenda=$sub base=$base ($*) uptime=$(adb shell cat /proc/uptime | tr -d '\r' | cut -d' ' -f1)"
  if [ "$P" = tel ]; then adb shell am start -n $PKG/.PlayerActivity --es url "$base/$video" --esa subs "$surl" "$@"
  else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$base/$video" --es gfTestSubs "$surl" "$@"; fi
}
alive() { if [ -n "$(adb shell pidof $PKG | tr -d '\r')" ]; then note "app vivo ($1)"; else note "FALHA: o app não está rodando ($1)"; FAIL=1; fi; }
check() {  # $1 = nome, $2 = log, $3 = depois (epoch), resto = opções do android-check.py
  local nome=$1 lg=$2 dep=$3; shift 3
  python3 tools/sincronizar/android-check.py "$lg" "$CERTA" --depois "$dep" --nome "$nome" "$@" | tee -a $OUT/conferencia.txt
  return ${PIPESTATUS[0]}
}
seeks() {  # $1 = prefixo das fotos, resto = posições em segundos
  local pre=$1; shift
  for pos in "$@"; do RX seek $pos; sleep 15; RX estado; snap "$pre-$((pos / 60))min" 0.3; done
}
# quanto esperar pela resposta: a versão antiga pode nunca responder (botão desligado na embutida, tempo que não acaba)
WAIT=330; [ "$V" = antes ] && WAIT=330
FIM=OK

# ------------------------------------------------------------------ 1) externa, a análise automática só começaria daqui a 900 s
launch 1-ext film.mkv sub25.srt $BASE --es gfRx 1 --es gfAudioStartSec 900 --es gfNoReport true --es gfSeekSec 300
waitlog "AUDIOSYNC legenda ativa:" 180 || FAIL=1
sleep 10
snap 1-ext-1-antes 0.5
RX estado
markres
tap_sync 1-ext
snap 1-ext-2-a-sincronizar 3
texts 1-ext-a-sincronizar
waitres $WAIT || FAIL=1
RES1=$(lastep "$RES_RE"); note "resultado 1: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
snap 1-ext-3-depois-aviso 0.5
texts 1-ext-aviso
sleep 8
seeks 1-ext-4 1800 3600 5100
save 1-ext
check "1-ext (depois do 1º toque)" "$OUT/log-$P-$V-1-ext.txt" "${RES1:-0}" || { note "FALHA: as linhas não batem com a fala (1-ext)"; FAIL=1; }
# 2º toque: tem de analisar de novo (e responder), não ficar sem fazer nada; um 3º toque durante a análise tem de responder "Já estou a sincronizar"
N0=$(glog | grep -c "GATILHO motivo=botão-sincronizar")
markres
tap_sync 1-ext-2o
sleep 3
RX panelstate x
RX sync x      # 3º toque durante a análise (o mesmo toque do botão, sem passar pelo painel)
waitlog "AVISO na tela: Já estou a sincronizar" 8 && note "ok: o toque durante a análise respondeu" || note "o toque durante a análise não respondeu (versão antiga?)"
snap 1-ext-5-segundo-toque 0.5
texts 1-ext-segundo-toque
waitres $WAIT || FAIL=1
sleep 6
N1=$(glog | grep -c "GATILHO motivo=botão-sincronizar")
note "análises do botão: $N0 -> $N1 (o 2º toque tem de somar 1)"
echo "analises_botao_antes=$N0 depois=$N1" >> $OUT/conferencia.txt
[ "$N1" -gt "$N0" ] || { note "FALHA: o 2º toque não analisou de novo"; FAIL=1; }
glog | grep -E "AVISO na tela|sincronização|GATILHO motivo=botão|AUDIOSYNC botão|ANALISE|corpo de ANALISE" | cut -c1-300 > $OUT/avisos-1-ext.txt
snap 1-ext-6-depois-segundo-toque 0.5
save 1-ext-b
alive 1-ext

# ------------------------------------------------------------------ 2) externa com a análise automática A CORRER quando se toca (o botão cancela uma análise em andamento)
launch 2-corrida film.mkv sub25.srt $BASE --es gfRx 1 --es gfAudioStartSec 3 --es gfNoReport true --es gfSeekSec 300
waitlog "AUDIOSYNC legenda ativa:" 180 || FAIL=1
waitlog "AUDIOSYNC GATILHO motivo=início.*decisão=análise" 90 && note "a análise automática começou" || note "a análise automática não começou"
sleep 3
snap 2-corrida-0-antes 0.3
markres
tap_sync 2-corrida
snap 2-corrida-1-a-sincronizar 3
waitres $WAIT || FAIL=1
RES2=$(lastep "$RES_RE"); note "resultado 2: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
sleep 12
snap 2-corrida-2-depois 0.5
seeks 2-corrida-3 1800 3600 5100
save 2-corrida
check "2-corrida (toque com a análise automática em andamento)" "$OUT/log-$P-$V-2-corrida.txt" "${RES2:-0}" || { note "FALHA: as linhas não batem com a fala (2-corrida)"; FAIL=1; }
glog | grep -E "AUDIOSYNC (GATILHO|resultado|botão|aplicado|marcas|estado)|AVISO na tela|sincronização" | cut -c1-300 > $OUT/avisos-2-corrida.txt
alive 2-corrida

# ------------------------------------------------------------------ 3) legenda EMBUTIDA no MKV (o app lê o texto pelo índice e passa a desenhá-la)
launch 3-emb film-emb.mkv none $BASE --es gfRx 1 --es gfAudioStartSec 900 --es gfNoReport true --es gfSeekSec 300
waitlog "legenda PT embutida: faixa" 180 || FAIL=1
sleep 10
snap 3-emb-1-antes 0.5
markres
tap_sync 3-emb
snap 3-emb-2-a-ler-a-legenda 3
texts 3-emb-a-ler
WE=$WAIT; [ "$V" = antes ] && WE=90
waitres $WE || FAIL=1
RES3=$(lastep "$RES_RE"); note "resultado 3: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
RX embstate x
snap 3-emb-3-depois-aviso 0.5
sleep 10
seeks 3-emb-4 1800 3600 5100
save 3-emb
check "3-emb (legenda embutida)" "$OUT/log-$P-$V-3-emb.txt" "${RES3:-0}" || { note "FALHA: as linhas não batem com a fala (3-emb)"; FAIL=1; }
markres
tap_sync 3-emb-2o
waitres $WE || FAIL=1
snap 3-emb-5-segundo-toque 0.5
glog | grep -E "EMBUTIDA|legenda embutida|AVISO na tela|sincronização|GATILHO motivo=botão" | cut -c1-300 > $OUT/avisos-3-emb.txt
alive 3-emb

# ------------------------------------------------------------------ 4) sem fala (só música): mensagem clara + painel do ajuste manual aberto com o motivo
launch 4-musica musica.mkv sub25.srt $BASE --es gfRx 1 --es gfAudioStartSec 900 --es gfNoReport true --es gfSeekSec 300
waitlog "AUDIOSYNC legenda ativa:" 180 || FAIL=1
sleep 8
markres
tap_sync 4-musica
snap 4-musica-0-a-sincronizar 3
waitres $WAIT || FAIL=1
note "resultado 4: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
glog | grep -q "AVISO na tela: Não consegui confirmar pelo áudio" && note "ok: mensagem clara" || { note "FALHA: sem a mensagem 'Não consegui confirmar pelo áudio'"; FAIL=1; }
glog | grep -q "AVISO na tela: Não consegui confirmar pelo áudio — ajusta à mão" || { note "FALHA: sem '— ajusta à mão' na mensagem"; FAIL=1; }
sleep 2
RX panelstate x
snap 4-musica-1-mensagem-e-ajuste-manual 0.5
texts 4-musica-painel
glog | grep -q "painel do ajuste manual: aberto=true" || { note "FALHA: o painel do ajuste manual não abriu sozinho"; FAIL=1; }
# o botão +1 s do painel de verdade (celular: toque; TV: setas)
click "+1 s"
sleep 1
snap 4-musica-2-depois-do-ajuste 0.5
glog | grep -E "AVISO na tela|sincronização|painel do ajuste|atraso da legenda:|ANALISE|corpo de ANALISE" | cut -c1-300 > $OUT/avisos-4-musica.txt
save 4-musica
alive 4-musica

# ------------------------------------------------------------------ 5) rede que trava longe do começo: tem de desistir no tempo limite (aqui 45 s) com a mensagem e o ajuste manual
launch 5-lento film.mkv sub25.srt $SLOW --es gfRx 1 --es gfAudioStartSec 900 --es gfNoReport true --es gfSeekSec 200 --es gfSyncBudgetSec 45
waitlog "AUDIOSYNC legenda ativa:" 240 || FAIL=1
sleep 8
T5=$(date +%s)
markres
tap_sync 5-lento
sleep 6
RX sync x      # toque durante a análise
waitlog "AVISO na tela: Já estou a sincronizar" 8 && note "ok: respondeu durante a análise" || note "sem resposta durante a análise (versão antiga?)"
snap 5-lento-1-a-sincronizar 0.5
texts 5-lento-a-sincronizar
waitres 150 || { note "FALHA: o toque não terminou numa resposta (rede travada)"; FAIL=1; }
T5E=$(date +%s)
note "resultado 5 após $((T5E - T5)) s: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
echo "lento_resposta_em_s=$((T5E - T5))" >> $OUT/conferencia.txt
sleep 2
RX panelstate x
snap 5-lento-2-tempo-esgotado 0.5
texts 5-lento-painel
RX estado x
alive "5-lento (continua respondendo)"
glog | grep -E "AVISO na tela|sincronização|painel do ajuste|ANALISE|corpo de ANALISE" | cut -c1-300 > $OUT/avisos-5-lento.txt
save 5-lento

# ------------------------------------------------------------------ quedas
adb logcat -d | grep -E "FATAL EXCEPTION|ANR in|Fatal signal" | head -20 > $OUT/crash.txt
NC=$(wc -l < $OUT/crash.txt | tr -d ' ')
note "quedas: $NC"
echo "quedas=$NC" >> $OUT/conferencia.txt
[ "$NC" = "0" ] || FAIL=1
adb logcat -d | grep -E "GFCrash" | cut -c1-400 | tail -40 > $OUT/relatorios-analise.txt
echo "falha=$FAIL" > $OUT/fim.txt
cat $OUT/conferencia.txt $OUT/fim.txt
exit 0
