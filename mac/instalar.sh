#!/bin/bash
# Instala o FramePlay no Mac (chip Apple). Baixa pelo Terminal: assim o macOS não bloqueia a app.
URL="https://github.com/gabrielxyz404/GABIFLIX/releases/download/mac/FramePlay-Mac.zip"
ZIP="/tmp/FramePlay.zip"
echo "=== FramePlay: instalação ==="
if [ "$(uname -s)" != "Darwin" ]; then echo "ERRO: isto só funciona num Mac."; exit 1; fi
if [ "$(uname -m)" != "arm64" ]; then echo "ERRO: este Mac não tem chip Apple (é Intel). O FramePlay só funciona em Macs M1, M2, M3, M4."; exit 1; fi
echo "1/4 A baixar (35 MB)..."
rm -f "$ZIP"
if ! curl -fL --progress-bar "$URL" -o "$ZIP"; then echo "ERRO: não consegui baixar. Verifique a internet e tente de novo."; exit 1; fi
echo "2/4 A instalar na pasta Aplicativos..."
rm -rf /Applications/FramePlay.app 2>/dev/null
if ! ditto -x -k "$ZIP" /Applications 2>/dev/null; then
  echo "    (sem permissão na pasta Aplicativos: a instalar na sua pasta pessoal)"
  mkdir -p "$HOME/Applications"; rm -rf "$HOME/Applications/FramePlay.app"
  ditto -x -k "$ZIP" "$HOME/Applications" || { echo "ERRO: não consegui descompactar."; exit 1; }
  APP="$HOME/Applications/FramePlay.app"
else
  APP="/Applications/FramePlay.app"
fi
echo "3/4 A liberar a app no macOS..."
xattr -cr "$APP"
rm -f "$ZIP"
echo "4/4 A abrir..."
open "$APP" || { echo "ERRO: não consegui abrir. Abra o FramePlay pela pasta Aplicativos."; exit 1; }
echo "Pronto! O FramePlay está em: $APP"
