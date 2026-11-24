#!/bin/bash
# Prova do botão Sincronizar no FramePlay para Mac (runner macOS, VLCKit real, sem placa gráfica: o vídeo sai preto, a legenda do app e os avisos aparecem).
# Uso: prova.sh <FramePlay.app> <pasta com film.mkv film-emb.mkv musica.mkv sub25.srt certa.srt> <pasta de saída> <comando de toque: pill|autosync>
#   pill     = toque no botão Sincronizar da barra do player (a mesma ação do mouse) [versão nova]
#   autosync = a mesma função, chamada pelo comando de teste [versão antiga, que não tem o comando "pill"]
# Material: ~90 min de um filme de domínio público a 23,976 fps, servido por HTTP com Range (porta 11470, como o servidor de streaming do Stremio);
# a legenda PT certa deformada para 25 fps + 4 s (sub25.srt) como legenda externa, a MESMA legenda embutida como faixa do MKV (film-emb.mkv),
# um vídeo só com música (sem fala) e um servidor que trava as leituras longe do começo (sincronização que não termina).
# Cenários: 1 externa (antes/depois/segundo toque/toque durante a análise), 2 externa com a análise automática a correr quando se toca, 3 embutida,
# 4 sem fala (mensagem clara + ajuste manual), 5 rede travada (tempo limite).
set -u
APP="$1"; MEDIA="$2"; OUT="$3"; TAP="${4:-pill}"
mkdir -p "$OUT"
BIN="$APP/Contents/MacOS/FramePlay"
LOG=/tmp/frameplay-player.log
BASE=http://127.0.0.1:11470
SLOW=http://127.0.0.1:11471
PID=""
HERE="$(cd "$(dirname "$0")" && pwd)"
CERTA="$MEDIA/certa.srt"
FAIL=0
note() { echo "$(date +%s) $*" | tee -a "$OUT/passos.txt"; }
shot() { sleep "${2:-0.5}"; screencapture -x "$OUT/$1.png" || true; note "foto $1"; }
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-1.5}"; }
waitlog() {  # $1 = padrão (egrep), $2 = segundos
  local n=$(( $2 * 2 ))
  for i in $(seq 1 $n); do grep -q -E "$1" "$LOG" 2>/dev/null && return 0; sleep 0.5; done
  note "TEMPO ESGOTADO esperando: $1"; return 1
}
lastep() { grep -E "$1" "$LOG" | tail -1 | cut -d' ' -f1; }
launch() {  # $1 = nome, $2 = vídeo, $3 = legenda externa ("-" = nenhuma), $4 = base, resto = variáveis de ambiente VAR=valor
  local name=$1 video=$2 sub=$3 base=$4; shift 4
  pkill -x FramePlay 2>/dev/null; sleep 2
  rm -f "$LOG" /tmp/gf-cmd
  defaults delete io.github.gabrielxyz404.gabiflix 2>/dev/null; defaults delete FramePlay 2>/dev/null
  note "=== $name: $video legenda=$sub base=$base ($*)"
  if [ "$sub" = "-" ]; then
    env "$@" "$BIN" --gf-test-play "$base/$video" - > "$OUT/app-$name.log" 2>&1 &
  else
    env "$@" "$BIN" --gf-test-play "$base/$video" - --gf-test-sub "$base/$sub" > "$OUT/app-$name.log" 2>&1 &
  fi
  PID=$!
}
alive() { if kill -0 "$PID" 2>/dev/null; then note "app vivo ($1)"; else note "FALHA: o app morreu ($1)"; FAIL=1; fi; }
save() { cp "$LOG" "$OUT/log-$1.txt" 2>/dev/null; }
finish() { alive "$1"; pkill -x FramePlay 2>/dev/null; sleep 2; }
RESULT_RE='AVISO na tela: (Legenda sincronizada|Legenda acertada|Legenda já estava certa|Já estava certa|Não consegui|Ajuste manual feito|Escolha uma legenda|A legenda mudou)'
nres() { grep -c -E "$RESULT_RE" "$LOG" 2>/dev/null || true; }
markres() { RESBASE=$(nres); }
waitres() {  # espera uma resposta NOVA ao toque (depois da marca de markres), $1 = segundos
  local n=$(( $1 * 2 ))
  for i in $(seq 1 $n); do [ "$(nres)" -gt "$RESBASE" ] && return 0; sleep 0.5; done
  note "TEMPO ESGOTADO esperando a resposta ao toque"; return 1
}
# espera a legenda externa ficar ativa (o motor de áudio loga) e o filme tocar
wait_ext() { waitlog "AUDIOSYNC legenda ativa:" 180 && waitlog "legenda do app:|estado=3" 60; }
check() {  # $1 = nome, $2 = log, $3 = depois (epoch), resto = opções do check.py
  local nome=$1 lg=$2 dep=$3; shift 3
  python3 "$HERE/check.py" "$lg" "$CERTA" --depois "$dep" --nome "$nome" "$@" | tee -a "$OUT/conferencia.txt"
  return ${PIPESTATUS[0]}
}
seeks() {  # $1 = prefixo das fotos, resto = posições em ms
  local pre=$1; shift
  for pos in "$@"; do cmd "seek $pos" 3; sleep 13; cmd "state" 0.5; shot "$pre-$((pos / 60000))min" 0.3; done
}

: > "$OUT/passos.txt"; : > "$OUT/conferencia.txt"
sw_vers > "$OUT/aparelho.txt"; sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize >> "$OUT/aparelho.txt" 2>&1
ls -la "$MEDIA" > "$OUT/media.txt"
rm -f ~/Library/Logs/DiagnosticReports/FramePlay* 2>/dev/null

# ------------------------------------------------------------------ 1) externa, sem a análise automática por perto
launch 1-ext film.mkv sub25.srt $BASE FP_AUDIO_START_SEC=900 FP_NO_REPORT=1
wait_ext || FAIL=1
cmd "seek 300000" 7
shot 1-ext-1-antes 0.5
cmd "state" 0.5
markres
cmd "$TAP" 1
shot 1-ext-2-a-sincronizar 4
waitres 330 || FAIL=1
RES1=$(lastep "$RESULT_RE"); note "resultado 1: $(grep -E "$RESULT_RE" "$LOG" | tail -1 | cut -c1-200)"
shot 1-ext-3-depois-aviso 0.5
sleep 8
seeks 1-ext-4 1800000 3600000 5100000
save 1-ext
check "1-ext (depois do 1º toque)" "$OUT/log-1-ext.txt" "$RES1" || { note "FALHA: as linhas não batem com a fala (1-ext)"; FAIL=1; }
# 2º toque: tem de analisar de novo (e responder), não ficar sem fazer nada
N0=$(grep -c "GATILHO motivo=botão-sincronizar" "$LOG")
markres
cmd "$TAP" 1
sleep 2
cmd "$TAP" 1      # 3º toque durante a análise: "Já estou a sincronizar"
waitlog "Já estou a sincronizar" 5 && note "ok: o toque durante a análise respondeu" || { note "o toque durante a análise não respondeu (versão antiga?)"; }
shot 1-ext-5-segundo-toque 0.5
waitres 330 || FAIL=1
sleep 6
N1=$(grep -c "GATILHO motivo=botão-sincronizar" "$LOG")
note "análises do botão: $N0 -> $N1 (o 2º toque tem de somar 1)"
echo "analises_botao_antes=$N0 depois=$N1" >> "$OUT/conferencia.txt"
[ "$N1" -gt "$N0" ] || { note "FALHA: o 2º toque não analisou de novo"; FAIL=1; }
grep -E "AVISO na tela|sincronização:|GATILHO motivo=botão|AUDIOSYNC botão" "$LOG" | cut -c1-260 > "$OUT/avisos-1-ext.txt"
shot 1-ext-6-depois-segundo-toque 0.5
save 1-ext-b
finish 1-ext

# ------------------------------------------------------------------ 2) externa com a análise automática A CORRER quando se toca
launch 2-corrida film.mkv sub25.srt $BASE FP_AUDIO_START_SEC=3 FP_NO_REPORT=1
wait_ext || FAIL=1
waitlog "GATILHO motivo=início .*decisão=análise" 60 && note "a análise automática começou"
sleep 2
cmd "seek 300000" 2
markres
cmd "$TAP" 1
shot 2-corrida-1-a-sincronizar 3
waitres 330 || FAIL=1
RES2=$(lastep "$RESULT_RE"); note "resultado 2: $(grep -E "$RESULT_RE" "$LOG" | tail -1 | cut -c1-200)"
sleep 12
shot 2-corrida-2-depois 0.5
seeks 2-corrida-3 1800000 3600000 5100000
save 2-corrida
check "2-corrida (toque com a análise automática em andamento)" "$OUT/log-2-corrida.txt" "$RES2" || { note "FALHA: as linhas não batem com a fala (2-corrida)"; FAIL=1; }
grep -E "AUDIOSYNC (GATILHO|resultado descartado|botão|aplicado|marcas)|AVISO na tela|sincronização:" "$LOG" | cut -c1-260 > "$OUT/avisos-2-corrida.txt"
finish 2-corrida

# ------------------------------------------------------------------ 3) legenda EMBUTIDA no MKV (o app lê o texto pelo índice e passa a desenhá-la)
launch 3-emb film-emb.mkv - $BASE FP_AUDIO_START_SEC=900 FP_NO_REPORT=1
waitlog "legenda PT embutida: faixa" 180 || FAIL=1
waitlog "estado=3" 60
sleep 5
cmd "seek 300000" 7
shot 3-emb-1-antes 0.5
cmd "state" 0.5
markres
cmd "$TAP" 1
shot 3-emb-2-a-ler-a-legenda 3
waitres 330 || FAIL=1
RES3=$(lastep "$RESULT_RE"); note "resultado 3: $(grep -E "$RESULT_RE" "$LOG" | tail -1 | cut -c1-200)"
cmd "embstate" 0.5
shot 3-emb-3-depois-aviso 0.5
sleep 10
seeks 3-emb-4 1800000 3600000 5100000
save 3-emb
check "3-emb (legenda embutida)" "$OUT/log-3-emb.txt" "$RES3" || { note "FALHA: as linhas não batem com a fala (3-emb)"; FAIL=1; }
markres
cmd "$TAP" 1
waitres 330 || FAIL=1
shot 3-emb-5-segundo-toque 0.5
grep -E "EMBUTIDA|legenda embutida|AVISO na tela|sincronização:|GATILHO motivo=botão" "$LOG" | cut -c1-260 > "$OUT/avisos-3-emb.txt"
finish 3-emb

# ------------------------------------------------------------------ 4) sem fala (só música): mensagem clara + ajuste manual à mão
if [ "${REAL_REPORT:-0}" = 1 ]; then launch 4-musica musica.mkv sub25.srt $BASE FP_AUDIO_START_SEC=900
else launch 4-musica musica.mkv sub25.srt $BASE FP_AUDIO_START_SEC=900 FP_NO_REPORT=1; fi
wait_ext || FAIL=1
cmd "seek 300000" 6
markres
cmd "$TAP" 1
waitres 330 || FAIL=1
note "resultado 4: $(grep -E "$RESULT_RE" "$LOG" | tail -1 | cut -c1-200)"
grep -q "AVISO na tela: Não consegui confirmar pelo áudio" "$LOG" && note "ok: mensagem clara" || { note "FALHA: sem a mensagem clara de 'não consegui confirmar'"; FAIL=1; }
cmd "panelstate" 0.5
shot 4-musica-1-mensagem-e-ajuste-manual 0.5
cmd "panelclick 2 0" 1      # "+1 s" do painel
cmd "panelstate" 0.5
shot 4-musica-2-depois-do-ajuste 0.5
grep -E "painel do ajuste manual|painel:" "$LOG" | cut -c1-200 | tee "$OUT/painel-4.txt"
grep -q "painel do ajuste manual: aberto=true" "$LOG" || { note "FALHA: o painel do ajuste manual não abriu"; FAIL=1; }
grep -q "painel: toque em 2,0: atraso 0 -> 1000" "$LOG" || { note "FALHA: o botão +1 s do painel não mexeu no atraso"; FAIL=1; }
sleep 4
grep -E "ANALISE" "$LOG" | cut -c1-200 > "$OUT/ntfy-4.txt"
save 4-musica
finish 4-musica

# ------------------------------------------------------------------ 5) rede que trava longe do começo: tem de desistir no tempo limite (aqui 45 s) com a mensagem e o ajuste manual
launch 5-lento film.mkv sub25.srt $SLOW FP_AUDIO_START_SEC=900 FP_SYNC_BUDGET_SEC=45 FP_NO_REPORT=1
wait_ext || FAIL=1
cmd "seek 200000" 6
T5=$(date +%s)
markres
cmd "$TAP" 1
sleep 12
cmd "$TAP" 1      # toque durante a análise
waitlog "Já estou a sincronizar" 5 && note "ok: respondeu durante a análise" || note "sem resposta durante a análise (versão antiga?)"
shot 5-lento-1-a-sincronizar 0.5
waitres 120 || { note "FALHA: o toque não terminou numa resposta (rede travada)"; FAIL=1; }
T5E=$(date +%s)
note "resultado 5 após $((T5E - T5)) s: $(grep -E "$RESULT_RE" "$LOG" | tail -1 | cut -c1-200)"
cmd "panelstate" 0.5
shot 5-lento-2-tempo-esgotado 0.5
cmd "state" 0.5
alive "5-lento (continua respondendo)"
save 5-lento
finish 5-lento

# ------------------------------------------------------------------ quedas
NC=$(ls ~/Library/Logs/DiagnosticReports/FramePlay* 2>/dev/null | wc -l | tr -d ' ')
note "relatórios de queda do FramePlay: $NC"
echo "quedas=$NC" >> "$OUT/conferencia.txt"
[ "$NC" = "0" ] || { FAIL=1; cp ~/Library/Logs/DiagnosticReports/FramePlay* "$OUT/" 2>/dev/null; }
echo "falha=$FAIL" > "$OUT/fim.txt"
exit 0
