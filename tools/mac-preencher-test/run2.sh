#!/bin/bash
# $1 = FramePlay.app  $2 = pasta de saída. Sem placa gráfica não há imagem no GitHub: o "VLC" que dá as fotografias é simulado (simsnap.py)
# e o app corre de verdade (temporizador, análise, recorte calculado, janela). Janela de 16:10 (960x600 e 800x500).
APPDIR="$1"; OUT="$2"
mkdir -p "$OUT"
xattr -cr "$APPDIR"
APP="$APPDIR/Contents/MacOS/FramePlay"
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-2.5}"; }
run() { # video mundo [subs]
  V=$1; W=$2; S=${3:-1}; N="$V-$W-subs$S"
  echo "=========== $N"
  rm -rf /tmp/sim; mkdir -p /tmp/sim
  rm -f /tmp/frameplay-player.log /tmp/frameplay-snap-*.png
  SUBS=$S python3 tools/mac-preencher-test/simsnap.py /tmp/sim /tmp/srv/$V.mkv $W /tmp/srv/leg.srt > "$OUT/$N-sim.log" 2>&1 &
  SIM=$!
  FP_FILL_WAIT=20 FP_SNAP_SIM=/tmp/sim $APP --gf-test-play http://127.0.0.1:8765/$V.mkv - --gf-test-sub http://127.0.0.1:8765/leg.srt > "$OUT/$N-app.log" 2>&1 &
  sleep 34
  cmd "resize 960 600"; sleep 2
  cmd "stats"
  cmd "scale 1" 30
  cmd "stats"
  cmd "resize 800 500" 12
  cmd "stats"
  # perfis de janela (a janela do Mac nunca passa do tamanho da tela do computador: o log "tela:" diz o tamanho que valeu): MacBook 13" 16:10 e ultrawide 21:9
  cmd "resize 1440 900" 12
  cmd "stats"
  cmd "resize 2520 1080" 12
  cmd "stats"
  cmd "scale 0" 4
  cmd "scale 1" 12
  cmd "stats"
  if [ "$V" = hd ] && [ "$W" = B ]; then
    cmd "menudump"; cmd "menuclick Sincronizar automaticamente" 6; cmd "state"; cmd "audiostate"; cmd "key 49" 1; cmd "key 49" 3; cmd "stats"
  fi
  pkill -x FramePlay || true
  kill $SIM 2>/dev/null
  sleep 3
  cp /tmp/frameplay-player.log "$OUT/$N-player.log" 2>/dev/null || true
  for f in /tmp/frameplay-snap-*.png; do [ -f "$f" ] && cp "$f" "$OUT/$N-$(basename "$f")"; done
  grep -E "tela:|barras:|stats:" "$OUT/$N-player.log" | cut -c1-300 | tail -14
}
for V in barra wide hd pilar; do run $V B 1; done
run barra A 1
run pilar A 0
# sem simulação e sem saída de vídeo (como no GitHub): não pode fechar o app
echo "=========== sem-vout"
rm -f /tmp/frameplay-player.log
$APP --gf-test-play http://127.0.0.1:8765/barra.mkv - --gf-test-sub http://127.0.0.1:8765/leg.srt > "$OUT/sem-vout-app.log" 2>&1 &
sleep 34; cmd "scale 1" 14; cmd "stats"
pgrep -x FramePlay > /dev/null && echo "sem-vout: app vivo" || echo "sem-vout: APP FECHOU"
pkill -x FramePlay || true; sleep 2
cp /tmp/frameplay-player.log "$OUT/sem-vout-player.log" 2>/dev/null || true
grep -E "barras:|tela:" "$OUT/sem-vout-player.log" | cut -c1-200 | tail -6
