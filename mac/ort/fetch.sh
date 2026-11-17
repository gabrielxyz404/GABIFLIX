#!/bin/bash
# Baixa o ONNX Runtime (xcframework oficial da Microsoft, pelo SwiftPM) e deixa só a arquitetura pedida.
# Uso: fetch.sh <pasta de trabalho> <arquitetura>   -> imprime na última linha o caminho do onnxruntime.framework (nada se falhar). Mensagens vão para o stderr.
set -uo pipefail
cd "$(dirname "$0")"
WORK="$1"; ARCH="$2"
mkdir -p "$WORK"
rm -rf "$WORK/ortfw" && mkdir -p "$WORK/ortfw"
FW="$WORK/ortfw/onnxruntime.framework"
# 1) pacote oficial da Microsoft (dylib + cabecalhos) pela pagina de releases
TGZ="$WORK/ort.tgz"
if curl -fsSL --retry 3 --retry-delay 5 -o "$TGZ" "https://github.com/microsoft/onnxruntime/releases/download/v1.20.0/onnxruntime-osx-universal2-1.20.0.tgz" 2>/dev/null && tar -tzf "$TGZ" >/dev/null 2>&1; then
  rm -rf "$WORK/ortx" && mkdir -p "$WORK/ortx" && tar -xzf "$TGZ" -C "$WORK/ortx"
  H=$(find "$WORK/ortx" -type f -name onnxruntime_c_api.h | head -1)
  D=$(find "$WORK/ortx" -type f -name 'libonnxruntime*.dylib' ! -type l | head -1)
  if [ -n "$H" ] && [ -n "$D" ]; then
    mkdir -p "$FW/Headers" && cp "$(dirname "$H")"/*.h "$FW/Headers/" && cp "$D" "$FW/onnxruntime"
    echo "onnxruntime: pacote oficial (release 1.20.0)" >&2
  fi
fi
# 2) reserva: xcframework do SwiftPM (cabecalhos) + dylib do wheel do PyPI
if [ ! -f "$FW/onnxruntime" ]; then
  rm -rf "$FW"
  OKP=0
  for TRY in 1 2 3; do
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
B=$(find "$FW" -type f -name onnxruntime | head -1)
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
