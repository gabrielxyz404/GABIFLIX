#!/bin/bash
# Compara a Início da TV antes (main) e depois (este commit) NO MESMO emulador e na mesma corrida, alternando as versões.
# $1 = apk antes  $2 = apk depois  $3 = repetições (padrão 3)
# Mede: tempo até ao primeiro ecrã (am start -W), memória (PSS total e gráficos), quadros (gfxinfo) a navegar com as setas.
set -x
PKG=io.github.gabrielxyz404.gabiflix
A=$1; D=$2; N=${3:-3}
OUT=shots; mkdir -p $OUT
R=$OUT/comparacao.txt
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 600000
nav() {  # a mesma sequência para as duas versões: 8 → e 8 ←, duas vezes, um toque a cada 0,3 s
  for r in 1 2; do
    for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent DPAD_RIGHT; sleep 0.3; done
    for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent DPAD_LEFT; sleep 0.3; done
  done
}
for rep in $(seq 1 $N); do
  for V in antes depois; do
    APK=$A; [ $V = depois ] && APK=$D
    adb uninstall $PKG >/dev/null 2>&1
    adb install -r -d "$APK" >/dev/null
    # aquecimento (primeira abertura baixa os catálogos), depois a medida a frio do processo
    adb shell am start -W -n $PKG/.tv.TvHomeActivity >/dev/null; sleep 12
    adb shell am force-stop $PKG; sleep 2
    T=$(adb shell am start -W -n $PKG/.tv.TvHomeActivity | grep -E "TotalTime" | tr -dc '0-9')
    sleep 12
    adb exec-out screencap -p > "$OUT/cmp-$V-$rep.png"
    M=$(adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS:|TOTAL:" | head -1 | tr -s ' ')
    G=$(adb shell dumpsys meminfo $PKG | grep -E "^ *Graphics:" | head -1 | tr -s ' ')
    adb shell dumpsys gfxinfo $PKG reset >/dev/null
    nav
    sleep 1
    F=$(adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|99th percentile" | head -5 | tr -s ' ' | tr '\n' ';')
    M2=$(adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS:|TOTAL:" | head -1 | tr -s ' ')
    echo "rep=$rep $V | abertura_ms=$T | mem_inicio=[$M] graficos=[$G] | mem_apos_navegar=[$M2] | quadros=[$F]" >> $R
  done
done
cat $R
exit 0
