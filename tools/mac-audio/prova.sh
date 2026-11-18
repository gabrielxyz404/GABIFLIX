#!/bin/bash
# Prova da sincronizacao da legenda pelo audio no FramePlay para Mac (runner macOS, sem GPU: o video sai preto, a legenda e os avisos aparecem).
# Uso: prova.sh <FramePlay.app> <pasta com clip.mkv + srt> <pasta de saida>
# Cenarios: +3 s automatico (tempos padrao: comeca 25 s depois), +3 s com reverificacao curta, -2 s pelo item do menu, certa (nao pode mudar; menu: "Ja estava certa"),
# outro filme (recusa), VAD por DSP, atraso manual vence (antes e durante a analise), troca de legenda e fechar a janela cancelam, rede lenta.
set -u
APP="$1"; MEDIA="$2"; OUT="$3"
mkdir -p "$OUT"
BIN="$APP/Contents/MacOS/FramePlay"
LOG=/tmp/frameplay-player.log
BASE=http://127.0.0.1:8000
SLOW=http://127.0.0.1:8001
PID=""
note() { echo "$(date +%s) $*" | tee -a "$OUT/passos.txt"; }
shot() { sleep "${2:-0.3}"; screencapture -x "$OUT/$1.png" || true; note "foto $1"; }
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-1.5}"; }
waitlog() {  # $1 = padrao (egrep), $2 = segundos
  local n=$(( $2 * 2 ))
  for i in $(seq 1 $n); do grep -q -E "$1" "$LOG" 2>/dev/null && return 0; sleep 0.5; done
  note "TEMPO ESGOTADO esperando: $1"; return 1
}
sampler() {  # amostra a cada 1 s: hora, CPU acumulada do processo (s) e memoria residente (KB)
  while kill -0 "$PID" 2>/dev/null; do
    t=$(ps -o time= -p "$PID" 2>/dev/null | tr -d ' ')
    r=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' ')
    c=$(echo "$t" | awk -F'[:.]' '{ if (NF==3) print $1*60+$2+$3/100; else if (NF==4) print $1*3600+$2*60+$3+$4/100; else print 0 }')
    echo "$(date +%s) $c $r" >> "$OUT/cpu-$1.txt"
    sleep 1
  done
}
launch() {  # $1 = nome, $2 = legenda, $3 = base (servidor), resto = variaveis de ambiente VAR=valor
  local name=$1 sub=$2 base=$3; shift 3
  pkill -x FramePlay 2>/dev/null; sleep 2
  rm -f "$LOG" /tmp/gf-cmd "$OUT/cpu-$name.txt"
  if [ "${KEEP_DEFAULTS:-0}" != 1 ]; then defaults delete io.github.gabrielxyz404.gabiflix 2>/dev/null; defaults delete FramePlay 2>/dev/null; fi
  note "=== $name: legenda $sub ($*)"
  env "$@" "$BIN" --gf-test-play "$base/clip.mkv" - --gf-test-sub "$base/$sub" > "$OUT/app-$name.log" 2>&1 &
  PID=$!
  sampler "$name" &
  # espera o player abrir e a legenda ficar ativa
  waitlog "legenda ativa:|AUDIOSYNC legenda ativa" 120 || true
}
save() { cp "$LOG" "$OUT/log-$1.txt" 2>/dev/null; }
finish() { pkill -x FramePlay 2>/dev/null; sleep 2; }
bytes_mark() { curl -s http://127.0.0.1:8000/__bytes > "$OUT/bytes-$1.txt"; date +%s >> "$OUT/bytes-$1.txt"; }

: > "$OUT/passos.txt"
sw_vers > "$OUT/aparelho.txt"; sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize >> "$OUT/aparelho.txt" 2>&1
du -sh "$APP" "$APP/Contents/Frameworks/"* "$APP/Contents/Resources/silero_vad.onnx" > "$OUT/tamanho-app.txt" 2>&1
ls -la "$MEDIA" > "$OUT/media.txt"
cp /tmp/server-bytes.log "$OUT/" 2>/dev/null; : > /tmp/server-bytes.log

# ---- 0) referencia: reproducao sozinha (automatico desligado), 60 s
launch 0-base certa.srt $BASE FP_AUDIO_AUTO=false
sleep 60; cmd "audiohealth"; cmd "state"; save 0-base; finish

# ---- 1) +3 s, tempos padrao (comeca 25 s depois da legenda ativa): aviso, atraso -3,0 s
bytes_mark 1a
launch 1-mais3 mais3.srt $BASE
waitlog "AUDIOSYNC aplicado" 240 && shot 1-aviso-legenda-acertada-pelo-audio 0.3
bytes_mark 1b
cmd "state"; cmd "audiohealth"; cmd "audiostate"; save 1-mais3
finish

# ---- 1b) +3 s, resultado CONFIANTE: nada de nova analise por tempo nem por salto sozinho; ajuste manual + salto grande = UMA nova analise
# TIMERS ENCURTADOS NO TESTE: FP_AUDIO_START_SEC=10 e FP_AUDIO_RETRY_SEC=60 (a segunda tentativa depois de "sem confianca" seria 20 min)
launch 1b-mais3-confiante mais3.srt $BASE FP_AUDIO_START_SEC=10 FP_AUDIO_RETRY_SEC=60
waitlog "AUDIOSYNC aplicado" 240 && shot 1b-aviso 0.3
sleep ${MAC_SOAK:-660}                                  # tocando 11 min sem mexer em nada (o monitor antigo reverificava a cada 10 min)
cmd "state"; cmd "audiostate"; save 1b-soak
cmd "seek 60000"; sleep 45                              # salto grande (de ~12 min para 1 min) SEM mexer no atraso: nao pode analisar
cmd "audiostate"; save 1b-salto-sem-ajuste; finish

# ---- 1c) reabrir o mesmo filme (KEEP_DEFAULTS=1 mantem as preferencias): a marca guardada evita nova captura e o atraso volta sozinho
KEEP_DEFAULTS=1 launch 1c-reabrir mais3.srt $BASE FP_AUDIO_START_SEC=10 FP_AUDIO_RETRY_SEC=60
sleep 60; cmd "state"; cmd "audiostate"; save 1c-reabrir

# ---- 1d) ainda nessa sessao: ajuste manual E salto de ~14 min => UMA nova analise (regra do pedido)
cmd "delay 1500"; sleep 4; cmd "seek 900000"
waitlog "AUDIOSYNC resultado:" 240 || true
sleep 60; cmd "audiostate"; shot 1d-depois-ajuste-e-salto 0.3; save 1d-ajuste-e-salto; finish

# ---- 2) -2 s, item do menu "Sincronizar automaticamente" (automatico desligado)
launch 2-menos2 menos2.srt $BASE FP_AUDIO_AUTO=false
sleep 15; cmd "menudump"; shot 2-menu-antes 0.3
cmd "menuclick Sincronizar automaticamente" 1; shot 2-sincronizando 0.5
waitlog "sincronização automática: status=" 240; shot 2-aviso-final 0.4
cmd "state"; save 2-menos2; finish

# ---- 3) certa: automatico; resultado confiante "ja estava certa": nada muda, silencioso, e nao reverifica
launch 3-certa certa.srt $BASE FP_AUDIO_START_SEC=10 FP_AUDIO_RETRY_SEC=60
waitlog "AUDIOSYNC resultado:" 240; sleep 2; shot 3-certa-depois-da-analise 0.3
sleep 150
cmd "state"; cmd "audiostate"; shot 3-certa-150s-depois 0.3; save 3-certa; finish

# ---- 3b) certa pelo item do menu: "Ja estava certa"
launch 3b-certa-menu certa.srt $BASE FP_AUDIO_AUTO=false
sleep 15
cmd "menuclick Sincronizar automaticamente" 1
waitlog "sincronização automática: status=" 240; shot 3b-aviso-ja-estava-certa 0.4
cmd "state"; save 3b-certa-menu; finish

# ---- 4) outro filme: tem de recusar
launch 4-outro outro.srt $BASE FP_AUDIO_START_SEC=10 FP_AUDIO_RETRY_SEC=60
waitlog "AUDIOSYNC resultado:" 240; sleep 2; shot 4-outro-filme 0.3
for i in $(seq 1 150); do [ "$(grep -c 'AUDIOSYNC resultado:' "$LOG" 2>/dev/null)" -ge 2 ] && break; sleep 2; done   # a segunda (e ultima) tentativa, 60 s depois
sleep 240                                                # 4 min depois dela: nao pode haver terceira
cmd "state"; save 4-outro; finish

# ---- 5) VAD de reserva (DSP) com a +3 s: so informativo
launch 5-dsp mais3.srt $BASE FP_AUDIO_START_SEC=10 FP_AUDIO_DSP=true
waitlog "AUDIOSYNC resultado:" 240; sleep 2; shot 5-dsp 0.3
cmd "state"; save 5-dsp; finish

# ---- 6) atraso manual vence: guardado antes de a analise comecar
launch 6-manual-antes mais3.srt $BASE FP_AUDIO_START_SEC=15
sleep 4; cmd "delay 1500"; sleep 30; cmd "state"; cmd "audiostate"; save 6-manual-antes; finish

# ---- 7) rede lenta (900 KB/s no total): a reproducao nao pode parar; depois, atraso manual DURANTE a analise descarta o resultado
LOG=/tmp/server-bytes-lenta.log THROTTLE_KBPS=900 nohup python3 "$(dirname "$0")/../audio-integra/server.py" "$MEDIA" 8001 >/tmp/server-lenta.out 2>&1 &
for i in $(seq 1 30); do curl -s -o /dev/null http://127.0.0.1:8001/mais3.srt && break; sleep 1; done
launch 7-rede-lenta mais3.srt $SLOW FP_AUDIO_START_SEC=20
waitlog "AUDIOSYNC resultado:" 300; sleep 2; shot 7-rede-lenta-depois 0.3
cmd "state"; cmd "audiohealth"; save 7-rede-lenta; finish

launch 8-manual-durante mais3.srt $SLOW FP_AUDIO_START_SEC=10
waitlog "AUDIOSYNC começando" 60; sleep 12; cmd "delay 700"; note "atraso manual durante a analise"
waitlog "AUDIOSYNC resultado" 900; sleep 3; cmd "state"; save 8-manual-durante; finish

# ---- 9) trocar de legenda durante a analise cancela; depois, fechar a janela durante outra analise cancela
launch 9-trocar-e-fechar mais3.srt $SLOW FP_AUDIO_START_SEC=10
waitlog "AUDIOSYNC começando" 60; sleep 10; cmd "track -1"; sleep 5; cmd "state"; cmd "audiostate"
note "reativa a legenda: a analise recomeca; fecha a janela durante ela"
cmd "menuclick Português 1" 1
for i in $(seq 1 120); do [ "$(grep -c 'AUDIOSYNC começando' "$LOG" 2>/dev/null)" -ge 2 ] && break; sleep 1; done
sleep 6; cmd "closeplayer" 1; sleep 10
save 9-trocar-e-fechar; finish
cp /tmp/server-bytes.log "$OUT/server-bytes-rapida.log" 2>/dev/null
cp /tmp/server-bytes-lenta.log "$OUT/" 2>/dev/null
{
  for f in "$OUT"/log-*.txt; do
    echo "=== $(basename "$f")"
    echo "  analises iniciadas (GATILHO decisao=analise) = $(grep -c 'AUDIOSYNC GATILHO .*decisão=análise' "$f")"
    echo "  resultados (AUDIOSYNC resultado:) = $(grep -c 'AUDIOSYNC resultado:' "$f")"
    echo "  avisos na tela = $(grep -c 'AVISO na tela' "$f")"
    grep -E 'AUDIOSYNC (GATILHO|salto)|AVISO na tela' "$f" | cut -c1-300
  done
} > "$OUT/G-resumo-mac.txt"
echo ok > "$OUT/fim.txt"
