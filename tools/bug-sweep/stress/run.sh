#!/bin/bash
# Teste de estresse no emulador (celular ou TV). $1 = apk, $2 = phone|tv, $3 = segundos de reproducao continua (padrao 1800).
# Roteiro: abre o filme -> espera a legenda -> pula +-10 min varias vezes -> Preencher liga/desliga -> troca a faixa de legenda -> troca a faixa de audio
#          -> casa/volta x5 -> gira (celular) -> o sistema mata o processo em segundo plano e o filme volta do mesmo ponto -> voltar (back)
#          -> abre outro filme -> reproducao continua com registro de memoria.
# Em cada passo: sem queda/ANR, legenda visivel, o video nao esta preto. No fim: crescimento de memoria < 50 MB. Nunca publica o app.
PKG=io.github.gabrielxyz404.gabiflix
APK=$1; MODE=${2:-phone}; PLAY=${3:-1800}; EXTRA=${4:-}   # EXTRA: extras da intent (ex.: "--es gfAudioAuto false" para isolar a sincronizacao pelo audio)
LIMIT=${5:-50}; INFO=${6:-}   # LIMIT: crescimento maximo de memoria (MB) na reproducao continua; INFO=info: acima do limite so avisa (nao reprova)
# Sobre a memoria: no emulador, o libVLC com saida de video OpenGL ES (gles2: sempre na TV/TextureView, e no celular quando decodifica por software)
# faz o libEGL_emulation.so do EMULADOR vazar ~33 KB por eglMakeCurrent (heapprofd: getGLString = ~97% do crescimento; ver heap-emulador.yml).
# Num aparelho de verdade esse codigo nao existe. A configuracao "--es gfVlcOpt --vout=android_display" nao usa GL e mostra a memoria do app sem esse vazamento.
OUT=shots; mkdir -p $OUT
BASE=http://10.0.2.2:8000
HERE=$(dirname "$0")
adb install -r "$APK" || exit 1
adb root >/dev/null 2>&1; sleep 3
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0 2>/dev/null
{ echo "modo=$MODE api=$(adb shell getprop ro.build.version.sdk | tr -d '\r') abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"; adb shell grep MemTotal /proc/meminfo | tr -d '\r'; adb shell wm size | tr -d '\r'; } > $OUT/aparelho.txt
R=$OUT/resultado.txt; : > $R; : > $OUT/fatal.txt; : > $OUT/log-all.txt; : > $OUT/memoria.csv
FAILS=0
ok()   { echo "OK      $1 $2" | tee -a $R; }
fail() { echo "FALHOU  $1 $2" | tee -a $R; FAILS=$((FAILS+1)); }
snap() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
resumed() { adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|ResumedActivity" | tr -d '\r'; }
mainpid() { adb shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
# guarda o log e procura queda / ANR; limpa o log para a proxima leitura ser so do que vem agora
harvest() {
  adb logcat -d 2>/dev/null > /tmp/lc.txt
  grep -E "GabiFlixPlayer|AUDIOSYNC|ESTADO|GFCrash" /tmp/lc.txt >> $OUT/log-all.txt
  # so o que e do app: queda/ANR dele, ou a morte do processo principal (a do :audiocap, que acaba ao fim da analise, e normal; as dos apps do sistema nao contam)
  grep -E "FATAL EXCEPTION|Fatal signal|ANR in io.github|am_crash|am_anr|Process io.github.gabrielxyz404.gabiflix \(pid [0-9]+\) has died" /tmp/lc.txt | grep -v "audiocap" >> $OUT/fatal.txt
  adb logcat -c
}
tell() { adb shell am broadcast -a io.github.gabrielxyz404.gabiflix.TEST --es cmd "$1" --es val "${2:-0}" >/dev/null 2>&1; }
LAST=""
estado() {  # guarda e imprime a ultima linha ESTADO
  harvest; tell estado; sleep 1.2
  LAST=$(adb logcat -d 2>/dev/null | grep "ESTADO" | tail -1)
  echo "$LAST"
}
field() { echo "$LAST" | grep -o "$1=[^ ]*" | head -1 | cut -d= -f2; }
black_check() {  # $1 = nome do passo; passa se o meio da tela tem imagem
  local v i
  for i in 1 2 3 4 5 6; do
    v=$(adb exec-out screencap 2>/dev/null | python3 -I $HERE/pixels.py)
    awk "BEGIN{exit !(${v%% *} >= 0.30)}" && { ok "$1" "video com imagem ($v)"; return 0; }
    [ $i = 1 ] && snap "preto-$1-a"
    sleep 2
  done
  snap "preto-$1"; fail "$1" "VIDEO PRETO ($v)"; return 1
}
sub_check() {  # $1 = nome do passo; espera ate 30 s por uma fala na tela (as falas duram ~3 s a cada 10,5 s)
  local i
  for i in $(seq 1 22); do
    estado >/dev/null
    if [ "$(field legenda)" = visivel ]; then ok "$1" "legenda visivel (tempo $(field tempo), faixa $(field spu))"; return 0; fi
    sleep 0.6
  done
  snap "semlegenda-$1"; fail "$1" "SEM LEGENDA em 30 s: $LAST"; return 1
}
alive_check() {  # $1 = nome do passo
  harvest
  if [ "$(wc -l < $OUT/fatal.txt)" != "${FATAL_SEEN:-0}" ]; then fail "$1" "queda/ANR no log: $(tail -2 $OUT/fatal.txt | cut -c1-200 | tr '\n' '|')"; FATAL_SEEN=$(wc -l < $OUT/fatal.txt); return 1; fi
  if ! resumed | grep -q PlayerActivity; then fail "$1" "saiu do player: $(resumed)"; return 1; fi
  if [ -n "$PID0" ] && [ "$(mainpid)" != "$PID0" ]; then fail "$1" "o processo do app mudou: $PID0 -> $(mainpid)"; PID0=$(mainpid); return 1; fi
  return 0
}
mem_sample() {  # $1 = rotulo; imprime o PSS (KB) do processo do app
  local pid cp pss=0 cpss=0
  pid=$(mainpid)
  [ -n "$pid" ] && pss=$(adb shell dumpsys meminfo $pid 2>/dev/null | awk '/^ *TOTAL/{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/){print $i; exit}}')
  cp=$(adb shell pidof $PKG:audiocap | tr -d '\r' | awk '{print $1}')
  [ -n "$cp" ] && cpss=$(adb shell dumpsys meminfo $cp 2>/dev/null | awk '/^ *TOTAL/{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/){print $i; exit}}')
  echo "$(date +%s),$1,${pss:-0},${cpss:-0}" >> $OUT/memoria.csv
  echo "${pss:-0}"
}
open_film() {  # $1 = arquivo do filme, $2 = numero (id do filme no celular)
  adb logcat -c
  if [ $MODE = tv ]; then
    adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/$1.mkv" --es gfTestSubs "$BASE/$1.srt" --es gfRx 1 $EXTRA > $OUT/am-$1.txt 2>&1
  else
    adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/$1.mkv" --esa subs "$BASE/$1.srt" --es type movie --es id tt000000$2 --es gfRx 1 $EXTRA > $OUT/am-$1.txt 2>&1
  fi
}
volta() {  # traz o player de volta (como o seletor de apps)
  local i
  for i in 1 2 3; do adb shell am start -n $PKG/.PlayerActivity -f 0x20000 >/dev/null 2>&1; sleep 2; resumed | grep -q PlayerActivity && return 0; done
  # a TV nem sempre traz o player por esse caminho: o icone do app (leanback) traz a tarefa de volta
  echo "AVISO   volta: am start nao trouxe o player ($(resumed)); tenta o icone" >> $R
  if [ $MODE = tv ]; then adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; else adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; fi
  sleep 4; resumed | grep -q PlayerActivity && return 0
  return 1
}

adb shell am force-stop $PKG; adb shell pm clear $PKG >/dev/null; adb logcat -c
# ---- 1) abre o filme e espera a legenda
open_film film 1
sleep 15; PID0=$(mainpid); snap 01-abriu
alive_check "01-abre" && ok "01-abre" "player aberto, pid $PID0"
sub_check "01-legenda-inicial"; black_check "01-video"
M0=$(mem_sample inicio)

# ---- 2) pula +-10 min varias vezes
n=0
for t in 630 30 600 25 560 40 610 20; do
  n=$((n+1)); tell seek $t; sleep 5
  alive_check "02-seek-$n-$t" && sub_check "02-seek-$n-$t" && black_check "02-seek-$n-$t"
done
snap 02-depois-seeks

# ---- 3) Preencher liga/desliga
for m in 1 0 1 0; do
  tell tela $m; sleep 6; nome="03-tela-$m"
  alive_check "$nome" && sub_check "$nome" && black_check "$nome"
done
tell tela 1; sleep 5; snap 03-preencher
nome=03-preencher-com-seek; tell seek 300; sleep 6; alive_check $nome && sub_check $nome && black_check $nome
tell tela 0; sleep 4

# ---- 4) troca a faixa de legenda e a de audio
for k in 1 2; do
  tell spunext; sleep 5; nome="04-faixa-legenda-$k"
  alive_check "$nome" && sub_check "$nome"
done
for k in 1 2 3; do
  tell audionext; sleep 5; nome="05-faixa-audio-$k"
  alive_check "$nome" && sub_check "$nome" && black_check "$nome"
done

# ---- 5) casa/volta x5
for k in 1 2 3 4 5; do
  estado >/dev/null; T1=$(field tempo)
  adb shell input keyevent KEYCODE_HOME; sleep 3
  volta || fail "06-casa-$k" "nao voltou ao player: $(resumed)"
  sleep 4; nome="06-casa-volta-$k"
  alive_check "$nome" && { black_check "$nome"; sub_check "$nome"; }
  estado >/dev/null; T2=$(field tempo)
  if [ "${T2:-0}" -gt "${T1:-0}" ] 2>/dev/null; then ok "$nome-tempo" "o filme andou ($T1 -> $T2)"; else fail "$nome-tempo" "o filme nao andou ($T1 -> $T2)"; fi
done
snap 06-depois-casa

# ---- 6) gira (so celular)
if [ $MODE = phone ]; then
  adb shell settings put system accelerometer_rotation 0
  for r in 3 1 3 1; do
    adb shell settings put system user_rotation $r; sleep 4; nome="07-gira-$r"
    alive_check "$nome" && sub_check "$nome" && black_check "$nome"
  done
  adb shell settings put system user_rotation 1
fi

# ---- 7) o sistema mata o processo em segundo plano: o filme tem de voltar do mesmo ponto
tell seek 420; sleep 5; estado >/dev/null; TP=$(field tempo); PIDA=$(mainpid)
adb shell input keyevent KEYCODE_HOME; sleep 4
adb shell am kill $PKG; sleep 2
harvest; FATAL_SEEN=$(wc -l < $OUT/fatal.txt)   # a morte do processo e de proposito
if [ -z "$(mainpid)" ] || [ "$(mainpid)" != "$PIDA" ]; then
  PID0=""
  volta; sleep 15; PID0=$(mainpid)
  estado >/dev/null; T3=$(field tempo)
  if grep -q "processo recriado pelo sistema" $OUT/log-all.txt; then ok "08-morte-processo-restaura" "log do app confirma a restauracao"; else echo "AVISO   08 sem a linha de restauracao no log (o sistema pode ter recriado sem estado)" | tee -a $R; fi
  if [ -n "$T3" ] && [ "$T3" -ge $((TP - 8000)) ] 2>/dev/null; then ok "08-morte-processo-ponto" "voltou perto de onde estava (antes $TP ms, depois $T3 ms)"
  else fail "08-morte-processo-ponto" "VOLTOU DO COMECO ou sem filme: antes $TP ms, depois '$T3' ($LAST)"; fi
  sub_check "08-morte-processo-legenda"; black_check "08-morte-processo-video"
else
  echo "AVISO   08 o am kill nao matou o processo (ainda em primeiro plano?); passo pulado" | tee -a $R
fi

# ---- 8) voltar (back) e abre outro filme
adb shell input keyevent KEYCODE_BACK; sleep 4
harvest; echo "back: $(resumed)" >> $R
open_film film2 2; sleep 15; PID0=$(mainpid); snap 09-outro-filme
alive_check "09-outro-filme" && sub_check "09-outro-legenda" && black_check "09-outro-video"

# ---- 9) reproducao continua com registro de memoria
tell seek 20; sleep 10
alive_check "10-play-inicio" && sub_check "10-play-inicio"
sleep 90   # aquecimento: a memoria so conta daqui
MA=$(mem_sample play-0)
adb shell dumpsys meminfo $(mainpid) > $OUT/meminfo-play-0.txt 2>&1
END=$(( $(date +%s) + PLAY )); k=0; nextblack=$(( $(date +%s) + 300 ))
while [ $(date +%s) -lt $END ]; do
  sleep 30
  MS=$(mem_sample play); k=$((k+1))
  if [ $((k % 4)) = 0 ]; then { estado; echo "  pss=${MS}k"; } >> $R; fi
  alive_check "10-play-$k" || { resumed | grep -q PlayerActivity || break; }
  if [ $(date +%s) -ge $nextblack ]; then black_check "10-play-video-$k"; sub_check "10-play-legenda-$k"; nextblack=$(( $(date +%s) + 300 )); fi
done
if alive_check "10-play-fim"; then sub_check "10-play-fim-legenda"; black_check "10-play-fim-video"; fi
MB=$(mem_sample play-fim); snap 10-fim
adb shell dumpsys meminfo $(mainpid) > $OUT/meminfo-play-fim.txt 2>&1
GROW=$(( (MB - MA) / 1024 )); TOT=$(( (MB - M0) / 1024 ))
echo "MEMORIA pss do app: inicio=$((M0/1024)) MB, depois do aquecimento=$((MA/1024)) MB, fim=$((MB/1024)) MB; crescimento na reproducao continua=${GROW} MB; desde o inicio=${TOT} MB" | tee -a $R
if [ "$GROW" -lt "$LIMIT" ] 2>/dev/null; then ok "11-memoria" "crescimento ${GROW} MB (limite $LIMIT)"
elif [ "$INFO" = info ]; then echo "AVISO   11-memoria crescimento ${GROW} MB (acima de $LIMIT; so informa: o EGL do emulador vaza com a saida OpenGL ES do libVLC, ver o cabecalho)" | tee -a $R
else fail "11-memoria" "CRESCEU ${GROW} MB (limite $LIMIT)"; fi
# telemetria MEMORIA (so TV; nos testes "gfMemTelMs" encurta o intervalo e so escreve no log, sem postar): tem de ter saido pelo menos um relatorio
if echo "$EXTRA" | grep -q gfMemTelMs; then
  NT=$(grep -c "MEMORIA (teste, nao enviado)\|MEMORIA (teste, não enviado)" $OUT/log-all.txt)
  if [ "${NT:-0}" -ge 1 ]; then ok "12-telemetria" "$NT relatorio(s) MEMORIA gerados no teste"; grep -E "decodificador:|PSS total|availMem|desde o 1" $OUT/log-all.txt | tail -4 | cut -c1-260 >> $R
  else fail "12-telemetria" "nenhum relatorio MEMORIA saiu em $PLAY s de reproducao"; fi
fi
harvest; adb shell ls /data/tombstones > $OUT/tombstones.txt 2>&1
echo "FALHAS=$FAILS" | tee -a $R
exit 0
