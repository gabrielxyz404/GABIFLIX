#!/bin/bash
# Compila o testador do motor de áudio (só o motor, sem VLCKit nem interface) com o swiftc do macOS. Uso: build-test.sh <saida> [caminho do onnxruntime.framework]
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$1"; ORT_FW="${2:-}"
SRC=(mac/Sources/AudioSync/AudioTypes.swift mac/Sources/AudioSync/AudioFft.swift mac/Sources/AudioSync/AudioCue.swift mac/Sources/AudioSync/AudioAligner.swift
     mac/Sources/AudioSync/AudioSyncSession.swift mac/Sources/AudioSync/AudioRecheck.swift mac/Sources/AudioSync/AudioDspVad.swift mac/Sources/AudioSync/AudioSileroVad.swift
     tools/mac-audio/swift/main.swift)
if [ -n "$ORT_FW" ]; then
  swiftc -O -target "$(uname -m)-apple-macos11.0" -import-objc-header mac/ort/ort_bridge.h -Xcc -I"$ORT_FW/Headers" "${SRC[@]}" -o "$OUT"
else
  swiftc -O -target "$(uname -m)-apple-macos11.0" -D FP_NO_ORT "${SRC[@]}" -o "$OUT"
fi
