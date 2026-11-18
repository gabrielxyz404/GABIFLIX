#!/bin/bash
# Prova da DERIVA de escala (1,001) no FramePlay para Mac: legenda deformada (24/23.976, +1,5 s) deve ser corrigida na escala, sem pulo, e com atraso manual; certa e outro filme nao mudam.
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
ls -la "$MEDIA" > "$OUT/media.txt"
# modelo da legenda deformada: tempo_legenda = G * tempo_certo + O  (G = 24/23.976, O = +1500 ms) => atraso certo no instante t (ms) = (1 - G) * t - O
G=1.001001001; O=1500
delay_at() { python3 -c "print(int(round(((1-$G)*$1 - $O)/10.0))*10)"; }

# ---- 1) deriva, tempos curtos, automatico: a analise acha a escala 1,001; o atraso acompanha o modelo (perto do inicio e perto do fim)
launch 1-deriva deriva.srt $BASE FP_AUDIO_START_SEC=10
waitlog "AUDIOSYNC aplicado" 300 && shot 1-aviso-deriva 0.3
cmd "seek 130000" 2; sleep 20; cmd "state"; cmd "audiostate"; shot 1-perto-do-inicio-130s 0.3
note "atraso esperado em 130 s: $(delay_at 130000) ms"
cmd "seek 1080000" 2; sleep 20; cmd "state"; cmd "audiostate"; shot 1-perto-do-fim-1080s 0.3
note "atraso esperado em 1080 s: $(delay_at 1080000) ms"
save 1-deriva; finish

# ---- 2) a mesma legenda com o automatico desligado (ANTES): o atraso fica onde esta, a deriva continua
launch 2-antes deriva.srt $BASE FP_AUDIO_AUTO=false
cmd "delay $(delay_at 130000)"; cmd "seek 130000" 2; sleep 3; cmd "state"; shot 2-antes-130s 0.3
cmd "seek 1080000" 2; sleep 3; cmd "state"; shot 2-antes-1080s 0.3
save 2-antes; finish

# ---- 3) com atraso manual JA presente (certo no ponto de ~30 s): a analise roda, o manual vence e so a escala entra, ancorada nesse ponto (sem pulo)
launch 3-manual-presente deriva.srt $BASE FP_AUDIO_START_SEC=15
sleep 3; cmd "seek 30000" 2; cmd "delay $(delay_at 30000)"; sleep 4; cmd "state"
waitlog "AUDIOSYNC (aplicado|o áudio sugere|escala .* achada)" 300 || true
sleep 3; cmd "state"; cmd "audiostate"; shot 3-manual-presente 0.3
cmd "seek 1080000" 2; sleep 20; cmd "state"; shot 3-manual-presente-1080s 0.3
save 3-manual-presente; finish

# ---- 4) DOIS ajustes manuais a mais de 10 min um do outro (automatico desligado): o motor deduz a escala 1,001 e avisa
launch 4-dois-pontos deriva.srt $BASE FP_AUDIO_AUTO=false
sleep 6; cmd "seek 130000" 2; cmd "delay $(delay_at 130000)"; sleep 7
cmd "seek 1000000" 2; cmd "delay $(delay_at 1000000)"; sleep 8
waitlog "deriva pelos ajustes manuais" 30 && shot 4-aviso-dois-pontos 0.3
cmd "seek 1100000" 2; sleep 20; cmd "state"; shot 4-depois-1100s 0.3
note "atraso esperado em 1100 s: $(delay_at 1100000) ms"
save 4-dois-pontos; finish

# ---- 5) certa: nada pode mudar
launch 5-certa certa.srt $BASE FP_AUDIO_START_SEC=10
waitlog "AUDIOSYNC resultado:" 300; sleep 3; cmd "state"; shot 5-certa 0.3
save 5-certa; finish

# ---- 6) outro filme: recusa, nada muda
launch 6-outro outro.srt $BASE FP_AUDIO_START_SEC=10
waitlog "AUDIOSYNC resultado:" 300; sleep 3; cmd "state"; shot 6-outro 0.3
save 6-outro; finish

{
  for f in "$OUT"/log-*.txt; do
    echo "=== $(basename "$f")"
    grep -E 'AUDIOSYNC (GATILHO|resultado|aplicado|ajuste manual|deriva|modelo|escala|o áudio)|AVISO na tela|state:|atraso' "$f" | cut -c1-400 | head -60
  done
} > "$OUT/G-resumo-deriva.txt"
echo ok > "$OUT/fim.txt"
