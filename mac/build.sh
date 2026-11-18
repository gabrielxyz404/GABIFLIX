#!/bin/bash
# Monta o FramePlay.app para Mac (Apple Silicon) — roda no GitHub Actions (macOS).
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${FRAMEPLAY_VERSION:-1.0.0}"
BUILD="${FRAMEPLAY_BUILD:-1}"
WORK="$PWD/build"
rm -rf "$WORK"; mkdir -p "$WORK"

# 1) VLCKit (o VLC para apps de Mac), da mesma fonte que o CocoaPods usa
VER=$(curl -fsSL https://cdn.cocoapods.org/all_pods_versions_5_f_4.txt | grep '^VLCKit/' | tr '/' '\n' | grep -E '^3\.[0-9]+\.[0-9]+$' | sort -V | tail -1)
echo "VLCKit $VER"
SPEC=$(curl -fsSL "https://cdn.cocoapods.org/Specs/5/f/4/VLCKit/$VER/VLCKit.podspec.json")
SRC=$(echo "$SPEC" | python3 -c 'import json,sys; print(json.load(sys.stdin)["source"]["http"])')
echo "baixando $SRC"
curl -fsSL "$SRC" -o "$WORK/vlckit.archive"
mkdir -p "$WORK/vlckit"
case "$SRC" in
  *.zip) unzip -q "$WORK/vlckit.archive" -d "$WORK/vlckit" ;;
  *) tar -xf "$WORK/vlckit.archive" -C "$WORK/vlckit" ;;
esac
FW=$(find "$WORK/vlckit" -type d -name 'VLCKit.framework' -path '*macos*' | head -1)
[ -z "$FW" ] && FW=$(find "$WORK/vlckit" -type d -name 'VLCKit.framework' | head -1)
echo "framework: $FW"
ls "$FW"
mkdir -p "$WORK/fw"
cp -R "$FW" "$WORK/fw/"
FW="$WORK/fw/VLCKit.framework"
# módulo para o Swift enxergar o VLCKit, se não vier pronto
if [ ! -f "$FW/Modules/module.modulemap" ]; then
  mkdir -p "$FW/Modules"
  printf 'framework module VLCKit {\n  umbrella header "VLCKit.h"\n  export *\n  module * { export * }\n}\n' > "$FW/Modules/module.modulemap"
fi
lipo -info "$FW/VLCKit" || true

# 1b) ONNX Runtime (oficial da Microsoft, pelo SwiftPM só para baixar) para o VAD Silero da sincronização pelo áudio.
# O app NÃO liga o ONNX Runtime na compilação: carrega com dlopen; se faltar ou não abrir, usa o VAD por DSP.
ARCH="${FRAMEPLAY_ARCH:-arm64}"
ORT_OK=0
ORT_FW=""
if [ "${FRAMEPLAY_NO_ORT:-0}" != "1" ]; then
  for TRY in 1 2 3 4; do   # o download pode falhar de vez em quando (rede do GitHub): tenta de novo
    ORT_FW=$(./ort/fetch.sh "$WORK" "$ARCH" | tail -1 || true)
    if [ -n "$ORT_FW" ] && [ -d "$ORT_FW" ]; then ORT_OK=1; break; fi
    echo "onnxruntime: tentativa $TRY falhou" >&2; sleep 20
  done
  if [ "$ORT_OK" != "1" ]; then
    echo "::warning::sem o onnxruntime: o app usa o VAD por DSP"
    if [ "${FRAMEPLAY_REQUIRE_ORT:-0}" = "1" ]; then echo "::error::onnxruntime obrigatório e não baixou"; exit 1; fi
  fi
fi
echo "ORT_OK=$ORT_OK"

# 1c) pequeno ajudante em Objective-C: apanha a exceção do VLCKit ao pedir uma fotografia sem saída de vídeo (ver shim/trycatch.m)
clang -c -fobjc-arc -O2 -target "${FRAMEPLAY_ARCH:-arm64}-apple-macos11.0" shim/trycatch.m -o "$WORK/trycatch.o"

# 2) compila o app
SWIFT_COMMON=(-O -target "$ARCH-apple-macos11.0"
  -F "$WORK/fw" -framework VLCKit -framework WebKit -framework Cocoa
  -Xlinker -rpath -Xlinker @executable_path/../Frameworks)
SWIFT_SRCS=(Sources/*.swift Sources/AudioSync/*.swift "$WORK/trycatch.o")
if [ "$ORT_OK" = "1" ]; then
  if ! swiftc "${SWIFT_COMMON[@]}" -import-objc-header ort/ort_bridge.h -Xcc -I"$ORT_FW/Headers" "${SWIFT_SRCS[@]}" -o "$WORK/FramePlay"; then
    echo "::warning::não compilou com o onnxruntime: refazendo sem ele (VAD por DSP)"
    ORT_OK=0
  fi
fi
if [ "$ORT_OK" != "1" ]; then
  swiftc "${SWIFT_COMMON[@]}" -D FP_NO_ORT "${SWIFT_SRCS[@]}" -o "$WORK/FramePlay"
fi

# 3) monta o pacote .app
APP="$WORK/FramePlay.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Frameworks" "$APP/Contents/Resources"
cp "$WORK/FramePlay" "$APP/Contents/MacOS/FramePlay"
cp -R "$FW" "$APP/Contents/Frameworks/"
if [ "$ORT_OK" = "1" ]; then
  cp -R "$ORT_FW" "$APP/Contents/Frameworks/"
  cp ../android/app/src/main/assets/silero_vad.onnx "$APP/Contents/Resources/silero_vad.onnx"
  du -sh "$APP/Contents/Frameworks/onnxruntime.framework" "$APP/Contents/Resources/silero_vad.onnx"
fi
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" Info.plist > "$APP/Contents/Info.plist"

# ícone a partir do ícone do FramePlay
ICON_SRC=../images/icon_512x512.png
ICONSET="$WORK/AppIcon.iconset"
mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z $s $s "$ICON_SRC" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  d=$((s*2)); sips -z $d $d "$ICON_SRC" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

# 4) assinatura local (sem conta de desenvolvedor da Apple)
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP" && echo "assinatura ok"

# 5) zip para download
mkdir -p ../out
rm -f ../out/FramePlay-Mac.zip
ditto -c -k --sequesterRsrc --keepParent "$APP" ../out/FramePlay-Mac.zip
ls -la ../out
