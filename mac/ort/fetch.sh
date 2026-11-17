#!/bin/bash
# Baixa o ONNX Runtime (xcframework oficial da Microsoft, pelo SwiftPM) e deixa só a arquitetura pedida.
# Uso: fetch.sh <pasta de trabalho> <arquitetura>   -> imprime na última linha o caminho do onnxruntime.framework (nada se falhar). Mensagens vão para o stderr.
set -uo pipefail
cd "$(dirname "$0")"
WORK="$1"; ARCH="$2"
mkdir -p "$WORK"
rm -rf "$WORK/ortfw" && mkdir -p "$WORK/ortfw"
FW="$WORK/ortfw/onnxruntime.framework"
# 1) pacote oficial da Microsoft no NuGet (dylib + cabecalhos); o github.com/microsoft nao responde mais
TGZ="$WORK/ort.nupkg"
if curl -fsSL --retry 3 --retry-delay 5 -o "$TGZ" "https://globalcdn.nuget.org/packages/microsoft.ml.onnxruntime.1.20.0.nupkg" && unzip -tq "$TGZ" >/dev/null 2>&1; then
  rm -rf "$WORK/ortx" && mkdir -p "$WORK/ortx" && unzip -oq "$TGZ" -d "$WORK/ortx"
  H=$(find "$WORK/ortx" -type f -name onnxruntime_c_api.h | head -1)
  D=$(find "$WORK/ortx" -type f -name 'libonnxruntime*.dylib' -path "*osx*$ARCH*" | head -1)
  [ -n "$D" ] || D=$(find "$WORK/ortx" -type f -name 'libonnxruntime*.dylib' | head -1)
  find "$WORK/ortx" -type f | grep -i "dylib\|onnxruntime_c_api.h" | sed "s|$WORK/ortx/||" >&2
  if [ -n "$H" ] && [ -n "$D" ]; then
    # layout de framework do macOS (Versions/A + Info.plist), para o codesign aceitar
    V="$FW/Versions/A"
    mkdir -p "$V/Headers" "$V/Resources" && cp "$(dirname "$H")"/*.h "$V/Headers/" && cp "$D" "$V/onnxruntime"
    cat > "$V/Resources/Info.plist" <<'PL'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>onnxruntime</string>
<key>CFBundleIdentifier</key><string>com.microsoft.onnxruntime</string>
<key>CFBundleName</key><string>onnxruntime</string>
<key>CFBundlePackageType</key><string>FMWK</string>
<key>CFBundleShortVersionString</key><string>1.20.0</string>
<key>CFBundleVersion</key><string>1.20.0</string>
</dict></plist>
PL
    ln -s A "$FW/Versions/Current"; ln -s Versions/Current/onnxruntime "$FW/onnxruntime"
    ln -s Versions/Current/Headers "$FW/Headers"; ln -s Versions/Current/Resources "$FW/Resources"
    echo "onnxruntime: pacote oficial do NuGet 1.20.0 ($D)" >&2
  fi
else
  echo "onnxruntime: nao baixou o pacote do NuGet" >&2
fi
# 2) reserva: xcframework do SwiftPM (cabecalhos) + dylib do wheel do PyPI
if [ ! -f "$FW/onnxruntime" ]; then
  rm -rf "$FW"
  OKP=0
  for TRY in 1; do
    if swift package resolve > "$WORK/ort-resolve.log" 2>&1; then OKP=1; break; fi
    tail -3 "$WORK/ort-resolve.log" >&2; sleep 15
  done
  [ "$OKP" = 1 ] || { echo "::warning::não baixou o onnxruntime" >&2; exit 1; }
  X=$(find .build -type d -name onnxruntime.xcframework 2>/dev/null | head -1)
  S=$(find "$X" -maxdepth 1 -type d -name 'macos*' 2>/dev/null | head -1)
  F=$(find "$S" -maxdepth 1 -type d -name onnxruntime.framework 2>/dev/null | head -1)
  if [ -z "$F" ] || [ ! -f "$F/Headers/onnxruntime_c_api.h" ]; then echo "::warning::framework do onnxruntime não encontrado" >&2; exit 1; fi
  cp -R "$F" "$WORK/ortfw/"
fi
B=$(find "$FW" -type f -name onnxruntime | head -1)  # arquivo real (Versions/A/onnxruntime no layout novo)
# O framework do SwiftPM e estatico (nao abre com dlopen): troca o binario pela dylib oficial do wheel do PyPI (mesma versao).
if ! file "$B" | grep -q 'dynamically linked'; then
  echo "onnxruntime do SwiftPM e estatico ($(file -b "$B" | cut -c1-40)); pegando a dylib do wheel" >&2
  rm -rf "$WORK/ortwhl" && mkdir -p "$WORK/ortwhl"
  python3 -m pip download onnxruntime==1.20.0 --no-deps --only-binary=:all: --python-version 3.12 --implementation cp --abi cp312 --platform macosx_11_0_universal2 --platform macosx_13_0_universal2 --platform macosx_14_0_arm64 --platform macosx_11_0_arm64 --platform macosx_10_15_x86_64 -d "$WORK/ortwhl" >&2 || { echo "::warning::pip nao baixou o wheel do onnxruntime" >&2; exit 1; }
  W=$(ls "$WORK"/ortwhl/*.whl | head -1)
  (cd "$WORK/ortwhl" && unzip -oq "$W" '*libonnxruntime*.dylib') || { echo "::warning::wheel sem dylib" >&2; exit 1; }
  D=$(find "$WORK/ortwhl" -type f -name 'libonnxruntime*.dylib' | head -1)
  [ -n "$D" ] || { echo "::warning::dylib nao encontrada" >&2; exit 1; }
  cp "$D" "$B"
fi
ARCHS=$(lipo -archs "$B" 2>/dev/null || true)
echo "onnxruntime: $B ($ARCHS)" >&2
case " $ARCHS " in
  *" $ARCH "*)
    if [ "$(echo $ARCHS | wc -w)" -gt 1 ]; then lipo -thin "$ARCH" "$B" -output "$B.thin" && mv "$B.thin" "$B"; fi ;;
  *) echo "::warning::onnxruntime sem a arquitetura $ARCH" >&2; exit 1 ;;
esac
echo "$FW"
