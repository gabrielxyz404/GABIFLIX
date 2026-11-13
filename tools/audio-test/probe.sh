#!/bin/bash
# Só a sondagem do tempo de captura (vídeo "varredura": um bipe por segundo com frequência que codifica o segundo)
set -x
PKG=io.github.gabrielxyz404.gabiflix
mkdir -p shots
adb install -r android/app/build/outputs/apk/release/app-release.apk
for st in 300000 60000; do
adb shell am force-stop $PKG; sleep 2; adb logcat -c
adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/sweep.mkv" --ez gfAudioProbe true --el gfProbeStart $st
for i in $(seq 1 90); do sleep 5; adb logcat -d -s GabiFlixPlayer:I | grep -q "PROBE fim" && break; done
adb logcat -d -s GabiFlixPlayer:I > shots/probe-$st.log
adb logcat -d | grep -E "VLC|libvlc" | grep -iE "sout|transcode|mux|seek|start-time|stop-time|error|fail" | head -100 > shots/probe-$st.vlc.log
grep PROBE shots/probe-$st.log | cut -c1-700
done
exit 0
