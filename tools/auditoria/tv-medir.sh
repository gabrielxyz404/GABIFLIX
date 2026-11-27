#!/bin/bash
# Mede o app nativo da TV num emulador: arranque a frio, fluidez (gfxinfo) ao navegar com o controle e memória.
# uso: tv-medir.sh <apk> <rótulo> "<números das repetições, ex.: 1 2 3>"   -> resultados em auditoria-out/<rótulo>-<n>.txt e auditoria-out/resumo-<rótulo>.txt
# A tela do emulador é desenhada pelo processador (swiftshader), então os números servem para COMPARAR duas versões no mesmo emulador, não para
# dizer quantos quadros o Fire TV de verdade faz.
APK=$1; LABEL=$2; REPS=$3
PKG=io.github.gabrielxyz404.gabiflix
OUT=auditoria-out; mkdir -p $OUT
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.28; done; }
rep() { local n=$1; shift; for i in $(seq 1 $n); do key "$@"; done; }
gfx() { adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands" ; }
mem() { adb shell dumpsys meminfo $PKG | grep -E "Java Heap:|Native Heap:|Graphics:|TOTAL PSS:|TOTAL:|Views:|Activities:" | head -8; }

adb uninstall $PKG >/dev/null 2>&1
adb install -r -g "$APK" >/dev/null || { echo "instalação falhou"; exit 1; }
adb shell settings put global window_animation_scale 1; adb shell settings put global transition_animation_scale 1; adb shell settings put global animator_duration_scale 1
ls -la "$APK" | awk '{print "apk_bytes=" $5}' > $OUT/$LABEL-apk.txt
unzip -l "$APK" | awk '/classes[0-9]*\.dex/ {s+=$1} END {print "dex_bytes=" s}' >> $OUT/$LABEL-apk.txt

for r in $REPS; do
  F=$OUT/$LABEL-$r.txt; : > $F
  adb shell am force-stop $PKG; adb shell pm clear $PKG >/dev/null; sleep 2
  adb logcat -c
  # 1) arranque a frio até a primeira tela da TV desenhada
  adb shell am start -W -n $PKG/.tv.TvHomeActivity | grep -E "ThisTime|TotalTime|WaitTime" | sed 's/^/arranque_/' >> $F
  sleep 9
  # 2) fluidez na tela inicial: setas para os lados (os cartões dos serviços deslizam)
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  rep 3 DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT
  echo "== fluidez_home" >> $F; gfx >> $F
  # 3) abrir o catálogo (Netflix é o cartão selecionado) e esperar os dados
  key DPAD_CENTER; sleep 14
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  # fileiras: baixo, direita (o título selecionado abre em banner), cima
  rep 3 DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_UP DPAD_LEFT DPAD_LEFT DPAD_LEFT
  echo "== fluidez_catalogo_fileiras" >> $F; gfx >> $F
  echo "== memoria_catalogo" >> $F; mem >> $F
  # 4) aba Filmes (grade de 2000 títulos): segurar baixo / direita
  key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP; key DPAD_RIGHT DPAD_CENTER; sleep 12
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  rep 4 DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_UP DPAD_UP DPAD_UP
  echo "== fluidez_grade" >> $F; gfx >> $F
  echo "== memoria_grade" >> $F; mem >> $F
  # 5) abrir um título (página de detalhes)
  key DPAD_CENTER; sleep 8
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  key DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_UP
  echo "== fluidez_detalhe" >> $F; gfx >> $F
  # 6) quedas / erros do app durante o roteiro
  echo "== logcat_erros" >> $F
  adb logcat -d -s AndroidRuntime:E GFCrash:I ActivityManager:E 2>/dev/null | grep -iE "FATAL|ANR in|Process.*died|GFCrash" | head -5 >> $F
  adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png $OUT/$LABEL-$r-fim.png >/dev/null 2>&1
done

