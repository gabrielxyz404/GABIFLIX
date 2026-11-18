#!/bin/bash
# Prova da legenda desenhada pelo app (sobreposição): o texto aparece no ecrã (a imagem do VLC não aparece no GitHub, mas a janela da
# legenda sim), nunca desaparece ao mudar o atraso, e volta logo depois de saltar para a frente e para trás. Cada fala dura 8 s (3 + 10*i).
# $1 = FramePlay.app  $2 = pasta de saída
APPDIR="$1"; OUT="$2"
mkdir -p "$OUT"
xattr -cr "$APPDIR"
APP="$APPDIR/Contents/MacOS/FramePlay"
shot() { screencapture -x "$OUT/$1.png" || true; }
cmd() { echo "$1" > /tmp/gf-cmd; sleep "${2:-2.5}"; }
rm -f /tmp/frameplay-player.log
$APP --gf-test-play http://127.0.0.1:8765/hd.mkv - --gf-test-sub http://127.0.0.1:8765/leg.srt > "$OUT/overlay-app.log" 2>&1 &
sleep 36
cmd "resize 960 600" 3
cmd "scale 0" 2
cmd "seek 16000" 1.5; shot 01-normal
cmd "delay -2000" 0.5; shot 02-normal-atraso-menos2s
cmd "delay 2000" 0.5; shot 03-normal-atraso-mais2s
cmd "delay 0" 1
cmd "seek 34000" 0.5; shot 04-normal-salto-frente
cmd "seek 16000" 0.5; shot 05-normal-salto-tras
cmd "scale 1" 3
cmd "seek 15000" 1.5; shot 06-preencher
cmd "delay -2000" 0.5; shot 07-preencher-atraso-menos2s
cmd "seek 54000" 0.5; shot 08-preencher-salto-frente
cmd "seek 14000" 0.5; shot 09-preencher-salto-tras
cmd "delay 0" 1
cmd "resize 800 500" 2; shot 10-preencher-800x500
cmd "showforce" 1; shot 11-controles-visiveis
cmd "state"; cmd "menudump"; cmd "stats"
sleep 8; cmd "stats"
pkill -x FramePlay || true
sleep 2
cp /tmp/frameplay-player.log "$OUT/overlay-player.log" 2>/dev/null || true
grep -E "legenda do app|seek:|atraso da legenda|stats:" "$OUT/overlay-player.log" | cut -c1-200 | head -60
