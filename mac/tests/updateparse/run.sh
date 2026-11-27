#!/bin/bash
# Compila e corre o teste do leitor da release (precisa de swiftc; corre no macOS do CI)
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="${TMPDIR:-/tmp}/updateparse-test"
swiftc Sources/UpdateParse.swift tests/updateparse/main.swift -o "$OUT"
"$OUT"
