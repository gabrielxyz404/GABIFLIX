#!/bin/bash
# $1 = FramePlay.app  $2 = rótulo (antes|depois)  $3 = pasta das fotos. Janela de 16:10 (como um MacBook); Normal e Preencher com os 4 vídeos.
APPDIR="$1"; L="$2"; OUT="$3"
mkdir -p "$OUT"
xattr -cr "$APPDIR"
APP="$APPDIR/Contents/MacOS/FramePlay"
shot() { screencapture -x "$OUT/$1.png" || true; }
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-2.5}"; }
for V in ${VIDEOS:-barra wide hd pilar}; do
  echo "=========== $L / $V"
  defaults delete FramePlay 2>/dev/null || true
  rm -f /tmp/frameplay-player.log /tmp/frameplay-snap-*.png
  $APP --gf-test-play http://127.0.0.1:8765/$V.mkv - --gf-test-sub http://127.0.0.1:8765/leg.srt > "$OUT/$V-app.log" 2>&1 &
  sleep 34
  cmd "resize 1280 800"; sleep 3
  cmd "scale 0"; sleep 6
  cmd "stats"
  shot "$V-1-normal"
  cmd "scale 1" 1.5
  shot "$V-2-preencher-inicio"
  sleep 14; shot "$V-3-preencher"
  cmd "stats"
  sleep 14; shot "$V-4-preencher-depois"
  cmd "stats"
  # a janela muda de forma: o recorte acompanha
  cmd "resize 1100 800"; sleep 3; shot "$V-5-preencher-1100x800"
  cmd "resize 1280 800"; sleep 2
  cmd "scale 0" 4; shot "$V-6-normal-de-volta"
  cmd "scale 1" 4
  if [ "$V" = hd ]; then
    # o menu Legendas, a sincronização automática e o resto continuam a funcionar
    cmd "menudump"
    echo "menu 4" > /tmp/gf-cmd; sleep 3; shot "$V-7-menu-legendas"; sleep 4
    cmd "menuclick Sincronizar automaticamente" 4; shot "$V-8-sincronizar"
    cmd "state"; cmd "audiostate"; cmd "key 49" 1; cmd "key 49" 3
    cmd "stats"
  fi
  sleep 20; cmd "stats"
  pkill -x FramePlay || true
  sleep 3
  cp /tmp/frameplay-player.log "$OUT/$V-player.log" 2>/dev/null || true
  for f in /tmp/frameplay-snap-*.png; do [ -f "$f" ] && cp "$f" "$OUT/$V-$(basename "$f")"; done
  grep -E "tela:|barras:|stats:" "$OUT/$V-player.log" | cut -c1-330 || true
done
