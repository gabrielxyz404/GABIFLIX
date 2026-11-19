#!/bin/bash
# Memoria entre filmes: abre o filme, toca ~100 s (a sincronizacao pelo audio faz a analise e le os "quadros perdidos"), fecha o player com Voltar e
# mede a memoria NATIVA do processo (que continua vivo). Repete N vezes. Se cada filme deixa lixo para tras (por exemplo uma instancia inteira do libVLC
# que nunca e liberada), o numero sobe a cada ciclo; sem lixo ele fica parado.
# $1 = apk, $2 = phone|tv, $3 = ciclos (padrao 5), $4 = segundos tocando por ciclo (padrao 100), $5 = extras da intent.
PKG=io.github.gabrielxyz404.gabiflix
APK=$1; MODE=${2:-phone}; N=${3:-5}; SECS=${4:-100}; EXTRA=${5:-}
OUT=shots; mkdir -p $OUT
BASE=http://10.0.2.2:8000
adb install -r "$APK" || exit 1
adb root >/dev/null 2>&1; sleep 3
adb shell settings put global hide_error_dialogs 1
adb logcat -G 16M >/dev/null 2>&1
echo "modo=$MODE api=$(adb shell getprop ro.build.version.sdk | tr -d '\r') ciclos=$N segundos=$SECS extra=$EXTRA" > $OUT/aparelho.txt
echo "ciclo,pid,native_pss_kb,total_pss_kb,chamadas_saude" > $OUT/ciclos.csv
mainpid() { adb shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
adb shell am force-stop $PKG; adb shell pm clear $PKG >/dev/null; adb logcat -c
for c in $(seq 1 $N); do
  adb logcat -c
  if [ $MODE = tv ]; then
    adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/film.mkv" --es gfTestSubs "$BASE/film.srt" --es gfRx 1 $EXTRA > $OUT/am.txt 2>&1
  else
    adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/film.mkv" --esa subs "$BASE/film.srt" --es type movie --es id tt0000001 --es gfRx 1 $EXTRA > $OUT/am.txt 2>&1
  fi
  sleep $SECS
  adb logcat -d > /tmp/lc.txt 2>/dev/null
  H=$(grep -c "saúde da reprodução" /tmp/lc.txt)
  adb shell input keyevent KEYCODE_BACK
  sleep 12
  adb shell input keyevent KEYCODE_HOME; sleep 3
  pid=$(mainpid)
  if [ -z "$pid" ]; then echo "$c,,0,0,$H" >> $OUT/ciclos.csv; continue; fi
  adb shell dumpsys meminfo $pid > /tmp/mi.txt 2>/dev/null
  nat=$(awk '/^ *Native Heap:/{print $3; exit}' /tmp/mi.txt); [ -z "$nat" ] && nat=$(awk '/^ *Native Heap/{print $3; exit}' /tmp/mi.txt)
  tot=$(awk '/^ *TOTAL PSS:/{print $3; exit}' /tmp/mi.txt); [ -z "$tot" ] && tot=$(awk '/^ *TOTAL/{print $2; exit}' /tmp/mi.txt)
  echo "$c,$pid,${nat:-0},${tot:-0},$H" | tee -a $OUT/ciclos.csv
  cp /tmp/mi.txt $OUT/meminfo-ciclo-$c.txt
done
python3 -I - <<'EOF' | tee $OUT/resultado.txt
import csv
rows = list(csv.DictReader(open('shots/ciclos.csv')))
nat = [int(r['native_pss_kb']) for r in rows if r['native_pss_kb'] not in ('', '0')]
if len(nat) >= 2:
    print('native depois de fechar o player, por ciclo (MB): ' + ' '.join('%.0f' % (x / 1024) for x in nat))
    print('RESULTADO ciclos=%d primeiro=%.1f MB ultimo=%.1f MB; crescimento por filme = %.1f MB' % (len(nat), nat[0] / 1024, nat[-1] / 1024, (nat[-1] - nat[0]) / 1024 / (len(nat) - 1)))
else:
    print('RESULTADO sem dados (o processo morreu?)')
EOF
exit 0
