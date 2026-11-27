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
# TV: o foco começa na legenda atual (a lista pode ter 3 linhas): anda para a direita/cima até o foco estar no botão
click_auto() {
  if [ "$P" = tel ]; then click "Sincronizar automaticamente"; return; fi
  for t in $(seq 1 12); do
    f=$(dump | python3 $UI focus)
    if [ "$f" = "Sincronizar automaticamente" ]; then adb shell input keyevent DPAD_CENTER; sleep 1; return; fi
    if [ $((t % 2)) = 1 ]; then adb shell input keyevent DPAD_RIGHT; else adb shell input keyevent DPAD_UP; fi
    sleep 0.7
  done
  echo "TV: NAO CHEGUEI em 'Sincronizar automaticamente' (foco: $f)" >> $OUT/passos.txt
  click "Sincronizar automaticamente"
}
tap_sync() { pause_v; show_controls; open_subs; sleep 1.5; texts "$1-painel"; click_auto; play_v; }

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
lastep() { glog | grep -E "$1" | tail -1 | awk '{print $1}'; }
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

# ====================================================================================================================================================
# Provas do motor (deriva com razão de fps conhecida + deslocamento grande) e do ajuste manual. Material extra: sub24.srt = a legenda certa com a escala
# 24/23,976 (x1,001) e 14 s adiantada (legenda de um filme a 24 fps num filme a 23,976 fps, como o relatório real do celular).
# ====================================================================================================================================================
HEREPY=tools/sincronizar
devnow() { adb shell date +%s | tr -d "\r"; }
mkman() {  # $1 = nome; passos manuais: +10,+5,+1,-1 (s) pelo painel; fotos do painel e da legenda (painel fechado); depois 30 s e depois de um salto
  local nome=$1
  pause_v; show_controls; open_subs; sleep 2
  texts "$nome-painel-0"; snap "$nome-m0-painel-antes" 0.3
  local T0=$(devnow)
  click "+10 s"; click "+5 s"; click "+1 s"; click "−1 s"
  sleep 1; local T1=$(devnow)
  sleep 1; texts "$nome-painel-1"; snap "$nome-m1-painel-depois-dos-toques" 0.3
  note "$nome: valor do painel: $(grep -E '^[+−-]?[0-9]+,[0-9] s$|^0,0 s$' $OUT/$P-$V-$nome-painel-1.txt | tr '\n' ' ')"
  key BACK; sleep 1; play_v; sleep 4
  snap "$nome-m2-legenda-logo-depois" 0.5
  sleep 30
  snap "$nome-m3-legenda-30s-depois" 0.5
  RX seek 3000; sleep 15; RX estado; snap "$nome-m4-legenda-depois-do-salto" 0.5
  sleep 20; snap "$nome-m5-legenda-20s-depois-do-salto" 0.5
  save "$nome-manual"
  python3 $HEREPY/android-manual-check.py "$OUT/log-$P-$V-$nome-manual.txt" --de "$T0" --ate "$T1" --passos 10000,5000,1000,-1000 --nome "$nome" | tee -a $OUT/conferencia.txt
  [ ${PIPESTATUS[0]} = 0 ] || { note "FALHA: o ajuste manual não ficou ($nome)"; FAIL=1; }
  # com +15 s a legenda de 14 s adiantada fica a ~1 s (mais a deriva do começo): a linha mostrada tem de bater com a fala (tolerância 2,5 s)
  python3 tools/sincronizar/android-check.py "$OUT/log-$P-$V-$nome-manual.txt" "$CERTA" --depois "$T1" --so-ate "$(devnow)" --tol 2500 --apos-salto 8 --nome "$nome (linhas depois do ajuste manual)" --min 0.8 | tee -a $OUT/conferencia.txt || true
}

# ------------------------------------------------------------------ A) deriva 24/23,976 + 14 s: o toque tem de aceitar e as linhas têm de bater com a fala em todo o filme
launch A-deriva film.mkv sub24.srt $BASE --es gfRx 1 --es gfAudioStartSec 900 --es gfNoReport true --es gfSeekSec 300
waitlog "AUDIOSYNC legenda ativa:" 180 || FAIL=1
sleep 10
snap A-deriva-1-antes 0.5
markres
tap_sync A-deriva
snap A-deriva-2-a-sincronizar 3
waitres $WAIT || FAIL=1
RESA=$(lastep "$RES_RE"); note "resultado A: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
snap A-deriva-3-depois-aviso 0.5
sleep 8
seeks A-deriva-4 1800 3600 5100
save A-deriva
check "A-deriva (depois do toque; 24 fps x1,001 + 14 s)" "$OUT/log-$P-$V-A-deriva.txt" "${RESA:-0}" || { note "FALHA: as linhas não batem com a fala (A-deriva)"; FAIL=1; }
glog | grep -E "AVISO na tela|sincronização|ANALISE|AUDIOSYNC (botão|resultado|aplicado)" | cut -c1-300 > $OUT/avisos-A-deriva.txt
glog | grep -E "GFCrash|janelas=|ajuste escala|motor aceita|motivo=" | cut -c1-400 > $OUT/relatorio-A-deriva.txt
alive A-deriva

# ------------------------------------------------------------------ B) ajuste manual numa legenda que ainda não foi sincronizada (sem análise por perto)
launch B-manual film.mkv sub24.srt $BASE --es gfRx 1 --es gfAudioStartSec 9000 --es gfNoReport true --es gfSeekSec 300
waitlog "AUDIOSYNC legenda ativa:" 180 || FAIL=1
sleep 10
snap B-manual-0-antes 0.5
mkman B-manual
alive B-manual

# ------------------------------------------------------------------ C) ajuste manual DEPOIS de o toque ter acertado (escala ativa): o manual vence e a escala continua
launch C-manual-escala film.mkv sub24.srt $BASE --es gfRx 1 --es gfAudioStartSec 900 --es gfNoReport true --es gfSeekSec 300
waitlog "AUDIOSYNC legenda ativa:" 180 || FAIL=1
sleep 10
markres
tap_sync C-manual-escala
waitres $WAIT || FAIL=1
note "resultado C: $(glog | grep -E "$RES_RE" | tail -1 | cut -c1-220)"
sleep 6
mkman C-manual-escala
alive C-manual-escala

adb logcat -d | grep -E "FATAL EXCEPTION|ANR in|Fatal signal" | head -20 > $OUT/crash.txt
NC=$(wc -l < $OUT/crash.txt | tr -d ' ')
note "quedas: $NC"
echo "quedas=$NC" >> $OUT/conferencia.txt
[ "$NC" = "0" ] || FAIL=1
adb logcat -d | grep -E "GFCrash" | cut -c1-600 | tail -40 > $OUT/relatorios-analise.txt
echo "falha=$FAIL" > $OUT/fim.txt
cat $OUT/conferencia.txt $OUT/fim.txt
exit 0
