#!/bin/bash
# Prova do Sincronizar numa rede LENTA realista (macOS, VLCKit real, sem placa gráfica: o vídeo sai preto, a legenda do app e os avisos aparecem).
# Uso: prova-lento.sh <FramePlay.app> <pasta de saída>
# O filme (~90 min a 23,976 fps, fala real, com uma faixa de áudio grande a mais para o arquivo ter o tamanho de um filme de verdade) vem de lento.py
# em http://127.0.0.1:11470/film.mkv: redirecionamento 302, 1-3 s de latência por pedido e RATE_KBPS kB/s para tudo junto. A legenda é a 25 fps + 4 s.
# Cenários: botao (toque em Sincronizar, a análise automática desligada) e auto (nada tocado: a análise que começa sozinha ao abrir o filme).
# Mede: segundos até a resposta VISÍVEL, % das linhas mostradas que batem com a fala, relatório ANALISE recebido.
set -u
APP="$1"; OUT="$2"
mkdir -p "$OUT"
BIN="$APP/Contents/MacOS/FramePlay"
LOG=/tmp/frameplay-player.log
BASE=http://127.0.0.1:11470
HERE="$(cd "$(dirname "$0")" && pwd)"
CERTA=/tmp/media/certa.srt
REPORTS=/tmp/reports
NEWVER="${NEWVER:-1}"      # 1 = a versão nova (FP_REPORT_URL local); 0 = a antiga (relatório só no registro, para não mandar ao ntfy de verdade)
FAIL=0
PID=""
RESULT_RE='AVISO na tela: (Legenda sincronizada|Legenda acertada|Legenda já estava certa|Já estava certa|Não consegui|A rede está lenta|Ajuste manual feito|Escolhe uma legenda|A legenda mudou)'
note() { echo "$(date +%s) $*" | tee -a "$OUT/passos.txt"; }
shot() { sleep "${2:-0.5}"; screencapture -x "$OUT/$1.png" || true; note "foto $1"; }
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-1.5}"; }
waitlog() {  # $1 = padrão (egrep), $2 = segundos
  local n=$(( $2 * 2 ))
  for i in $(seq 1 $n); do grep -q -E "$1" "$LOG" 2>/dev/null && return 0; sleep 0.5; done
  note "TEMPO ESGOTADO esperando: $1"; return 1
}
dif() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.0f", a-b}'; }
lastep() { grep -E "$1" "$LOG" | tail -1 | cut -d' ' -f1; }
nres() { grep -c -E "$RESULT_RE" "$LOG" 2>/dev/null || true; }
launch() {  # $1 = nome, $2 = legenda externa, resto = variáveis de ambiente VAR=valor
  local name=$1 sub=$2; shift 2
  pkill -x FramePlay 2>/dev/null; sleep 2
  rm -f "$LOG" /tmp/gf-cmd
  rm -rf "$REPORTS"; mkdir -p "$REPORTS"
  : > /tmp/lento.log
  defaults delete io.github.gabrielxyz404.gabiflix 2>/dev/null; defaults delete FramePlay 2>/dev/null
  local rep
  if [ "$NEWVER" = 1 ]; then rep="FP_REPORT_URL=http://127.0.0.1:11481/frameplay-crash"; else rep="FP_NO_REPORT=1"; fi
  note "=== $name: filme lento + legenda $sub ($*) $rep"
  env "$rep" "$@" "$BIN" --gf-test-play "$BASE/film.mkv" - --gf-test-sub "$BASE/$sub" > "$OUT/app-$name.log" 2>&1 &
  PID=$!
}
save() { cp "$LOG" "$OUT/log-$1.txt" 2>/dev/null; cp /tmp/lento.log "$OUT/rede-$1.txt" 2>/dev/null; mkdir -p "$OUT/relatorios-$1"; cp "$REPORTS"/* "$OUT/relatorios-$1/" 2>/dev/null; }
finish() { if kill -0 "$PID" 2>/dev/null; then note "app vivo ($1)"; else note "FALHA: o app morreu ($1)"; FAIL=1; fi; pkill -x FramePlay 2>/dev/null; sleep 2; }
medida() { echo "$*" | tee -a "$OUT/medidas.txt"; }
wait_ext() { waitlog "AUDIOSYNC legenda ativa:" 240; }
# linhas mostradas que batem com a fala, depois do instante $2 (epoch); seis saltos no filme, 25 s em cada
conferir() {  # $1 = nome, $2 = epoch depois
  local pos
  for pos in 600000 1500000 2400000 3300000 4200000 5000000; do
    cmd "seek $pos" 2; sleep 25
  done
  cmd "state" 0.5
  cp "$LOG" "$OUT/log-$1-conf.txt"
  python3 "$HERE/check.py" "$OUT/log-$1-conf.txt" "$CERTA" --depois "$2" --nome "$1" --min 0.95 | tee -a "$OUT/conferencia.txt"
  return ${PIPESTATUS[0]}
}
relatorios() {  # $1 = nome, $2 = epoch do começo: quantos relatórios chegaram ao servidor local (versão nova) ou foram produzidos (antiga)
  local n=0
  if [ "$NEWVER" = 1 ]; then n=$(ls "$REPORTS" 2>/dev/null | wc -l | tr -d ' '); else n=$(grep -c "ANALISE (teste: não enviada)" "$LOG" 2>/dev/null || true); fi
  medida "cenario=$1 relatorios=$n"
  echo "$n"
}

: > "$OUT/passos.txt"; : > "$OUT/conferencia.txt"; : > "$OUT/medidas.txt"
sw_vers > "$OUT/aparelho.txt"; sysctl -n machdep.cpu.brand_string hw.ncpu hw.memsize >> "$OUT/aparelho.txt" 2>&1
ls -la /tmp/media > "$OUT/media.txt"
rm -f ~/Library/Logs/DiagnosticReports/FramePlay* 2>/dev/null

# ------------------------------------------------------------------ 1) botão Sincronizar numa rede lenta
launch botao sub25.srt FP_AUDIO_START_SEC=3000
wait_ext || FAIL=1
cmd "seek 300000" 8
sleep 8
cmd "state" 0.5
T0=$(date +%s)
note "toque em Sincronizar (t0=$T0)"
cmd "pill" 1
shot botao-1-a-sincronizar 10
cmd "menu" 1                       # abre o menu de legendas: a linha do estado tem de dizer "A sincronizar… N s"
cmd "menudump" 1
grep -E "menu:|A sincronizar|FramePlay 1\." "$LOG" | tail -6 | cut -c1-200 > "$OUT/menu-botao.txt"
N0=$(nres)
for i in $(seq 1 330); do [ "$(nres)" -gt 0 ] && break; sleep 1; done
RES1=$(lastep "$RESULT_RE")
if [ -n "$RES1" ]; then
  DT=$(dif "$RES1" "$T0"); medida "cenario=botao resposta_s=$DT resposta=\"$(grep -E "$RESULT_RE" "$LOG" | tail -1 | cut -c1-160)\""
else
  DT=-1; medida "cenario=botao resposta_s=SEM_RESPOSTA"; FAIL=1; RES1=$(date +%s)
fi
shot botao-2-resposta 1
sleep 8
grep -E "captura|rede:|janelas usadas|AUDIOSYNC botão|sincronização:" "$LOG" | cut -c1-230 > "$OUT/motor-botao.txt"
if conferir botao "$RES1"; then medida "cenario=botao conferencia=OK"; else medida "cenario=botao conferencia=FALHOU"; fi
sleep 20
relatorios botao "$T0" > /dev/null
save botao
finish botao

# ------------------------------------------------------------------ 2) automático ao abrir (nada tocado)
launch auto sub25.srt FP_AUDIO_START_SEC=25
wait_ext || FAIL=1
TA=$(lastep "AUDIOSYNC legenda ativa:")
note "legenda ativa em $TA; esperando a análise automática (até 420 s)"
ANS=""
for i in $(seq 1 420); do
  if grep -q -E "$RESULT_RE|AUDIOSYNC aplicado|AUDIOSYNC a legenda já estava certa|AUDIOSYNC o áudio não tem certeza|ANALISE" "$LOG"; then ANS=$(grep -E "$RESULT_RE|AUDIOSYNC aplicado|AUDIOSYNC a legenda já estava certa|AUDIOSYNC o áudio não tem certeza|ANALISE" "$LOG" | head -1 | cut -d' ' -f1); break; fi
  sleep 1
done
if [ -n "$ANS" ]; then
  medida "cenario=auto resposta_s=$(dif "$ANS" "$TA") (desde a legenda ativa; a análise espera 25 s tocando) primeira_linha=\"$(grep -E "$RESULT_RE|AUDIOSYNC aplicado|AUDIOSYNC a legenda já estava certa|AUDIOSYNC o áudio não tem certeza|ANALISE" "$LOG" | head -1 | cut -c1-160)\""
else
  medida "cenario=auto resposta_s=SEM_RESPOSTA"; ANS=$(date +%s)
fi
shot auto-1-resposta 1
sleep 10
grep -E "captura|rede:|janelas usadas|AUDIOSYNC (GATILHO|aplicado|resultado|botão)|ANALISE" "$LOG" | cut -c1-230 > "$OUT/motor-auto.txt"
if conferir auto "$ANS"; then medida "cenario=auto conferencia=OK"; else medida "cenario=auto conferencia=FALHOU"; fi
relatorios auto "$TA" > /dev/null
save auto
finish auto

NC=$(ls ~/Library/Logs/DiagnosticReports/FramePlay* 2>/dev/null | wc -l | tr -d ' ')
note "relatórios de queda do FramePlay: $NC"
echo "quedas=$NC" >> "$OUT/conferencia.txt"
[ "$NC" = "0" ] || { FAIL=1; cp ~/Library/Logs/DiagnosticReports/FramePlay* "$OUT/" 2>/dev/null; }
echo "falha=$FAIL" > "$OUT/fim.txt"
exit 0
