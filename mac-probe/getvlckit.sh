#!/bin/bash
# baixa o VLCKit (igual ao mac/build.sh) para $1/fw/VLCKit.framework
set -euo pipefail
WORK="$1"; mkdir -p "$WORK"
VER=$(curl -fsSL https://cdn.cocoapods.org/all_pods_versions_5_f_4.txt | grep '^VLCKit/' | tr '/' '\n' | grep -E '^3\.[0-9]+\.[0-9]+$' | sort -V | tail -1)
echo "VLCKit $VER"
SPEC=$(curl -fsSL "https://cdn.cocoapods.org/Specs/5/f/4/VLCKit/$VER/VLCKit.podspec.json")
SRC=$(echo "$SPEC" | python3 -c 'import json,sys; print(json.load(sys.stdin)["source"]["http"])')
curl -fsSL "$SRC" -o "$WORK/vlckit.archive"
mkdir -p "$WORK/vlckit"
case "$SRC" in *.zip) unzip -q "$WORK/vlckit.archive" -d "$WORK/vlckit" ;; *) tar -xf "$WORK/vlckit.archive" -C "$WORK/vlckit" ;; esac
FW=$(find "$WORK/vlckit" -type d -name 'VLCKit.framework' -path '*macos*' | head -1)
[ -z "$FW" ] && FW=$(find "$WORK/vlckit" -type d -name 'VLCKit.framework' | head -1)
mkdir -p "$WORK/fw"; cp -R "$FW" "$WORK/fw/"
FW="$WORK/fw/VLCKit.framework"
if [ ! -f "$FW/Modules/module.modulemap" ]; then
  mkdir -p "$FW/Modules"
  printf 'framework module VLCKit {\n  umbrella header "VLCKit.h"\n  export *\n  module * { export * }\n}\n' > "$FW/Modules/module.modulemap"
fi
echo "framework: $FW"; ls "$FW" "$FW/Headers" | head -80
