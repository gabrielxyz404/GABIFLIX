#!/bin/bash
# $1 = FramePlay.app  $2 = pasta de saída. Cada etapa abre o app de novo (uma exceção do VLCKit fecha-o).
APPDIR="$1"; OUT="$2"
mkdir -p "$OUT"
xattr -cr "$APPDIR"
APP="$APPDIR/Contents/MacOS/FramePlay"
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-2.5}"; }
stage() { # nome, comandos...
  N="$1"; shift
  echo "=========== $N"
  rm -f /tmp/frameplay-player.log /tmp/frameplay-probe-*.png
  FP_NO_BARS=1 $APP --gf-test-play http://127.0.0.1:8765/barra.mkv - --gf-test-sub http://127.0.0.1:8765/leg.srt > "$OUT/$N-app.log" 2>&1 &
  sleep 34
  for c in "$@"; do cmd "$c" 3; done
  sleep 2
  pgrep -x FramePlay > /dev/null && echo "$N: app vivo" || echo "$N: APP FECHOU"
  pkill -x FramePlay || true
  sleep 3
  cp /tmp/frameplay-player.log "$OUT/$N-player.log" 2>/dev/null || true
  for f in /tmp/frameplay-probe-*.png; do [ -f "$f" ] && cp "$f" "$OUT/$N-$(basename "$f")"; done
  grep -E "snap:|tela:" "$OUT/$N-player.log" | cut -c1-300 | tail -8
  grep -i "exception" "$OUT/$N-app.log" | head -3
}
stage a-main-nocrop "snap main"
stage b-bg-nocrop "snap bg"
stage c-main-crop "scale 1" "snap main"
stage d-bg-crop "scale 1" "snap bg"
stage e-bg-crop-depois "scale 1" "snap bg" "snap bg" "snap bg"
