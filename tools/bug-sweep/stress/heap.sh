#!/bin/bash
# Perfil da memoria NATIVA do player no emulador. $1 = apk, $2 = phone|tv, $3 = segundos de reproducao continua, $4 = extras da intent,
# $5 = heapprofd: startup (anexa antes de o app abrir) | attach (anexa ao processo ja rodando, depois do aquecimento) | none (so medidas).
# Mede, a cada 30 s: PSS total, Native Heap, Dalvik, Code, Graphics (dumpsys meminfo) e o :audiocap. No fim guarda: smaps, threads, meminfo -d,
# o log do libVLC (-vv) e, com heapprofd, o trace do Perfetto (analise em heap_analyze.py). Nunca publica o app.
PKG=io.github.gabrielxyz404.gabiflix
APK=$1; MODE=${2:-tv}; PLAY=${3:-900}; EXTRA=${4:-}; HP=${5:-none}
OUT=shots; mkdir -p $OUT
BASE=http://10.0.2.2:8000
HERE=$(dirname "$0")
adb install -r "$APK" || exit 1
adb root >/dev/null 2>&1; sleep 3
adb shell settings put global hide_error_dialogs 1
adb logcat -G 16M >/dev/null 2>&1
{ echo "modo=$MODE api=$(adb shell getprop ro.build.version.sdk | tr -d '\r') abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r') hp=$HP extra=$EXTRA"
  adb shell getprop ro.build.type | tr -d '\r'; adb shell getprop ro.debuggable | tr -d '\r'; adb shell grep MemTotal /proc/meminfo | tr -d '\r'
  adb shell ls /system/bin/perfetto /system/bin/heapprofd 2>&1 | tr -d '\r'; } > $OUT/aparelho.txt
: > $OUT/memoria.csv
mainpid() { adb shell pidof $PKG | tr -d '\r' | awk '{print $1}'; }
tell() { adb shell am broadcast -a io.github.gabrielxyz404.gabiflix.TEST --es cmd "$1" --es val "${2:-0}" >/dev/null 2>&1; }
# uma linha por amostra: hora, rotulo, PSS total, Native Heap, Dalvik Heap, Code, Graphics, PSS do :audiocap (todos em KB)
sample() {
  local pid cp t nat dal code gfx cpss
  pid=$(mainpid); [ -z "$pid" ] && { echo "$(date +%s),$1,0,0,0,0,0,0" >> $OUT/memoria.csv; return; }
  adb shell dumpsys meminfo $pid > /tmp/mi.txt 2>/dev/null
  t=$(awk '/^ *TOTAL PSS:/{print $3; exit}' /tmp/mi.txt); [ -z "$t" ] && t=$(awk '/^ *TOTAL/{print $2; exit}' /tmp/mi.txt)
  nat=$(awk '/^ *Native Heap:/{print $3; exit}' /tmp/mi.txt)
  dal=$(awk '/^ *Java Heap:/{print $3; exit}' /tmp/mi.txt)
  code=$(awk '/^ *Code:/{print $2; exit}' /tmp/mi.txt)
  gfx=$(awk '/^ *Graphics:/{print $2; exit}' /tmp/mi.txt)
  cp=$(adb shell pidof $PKG:audiocap | tr -d '\r' | awk '{print $1}'); cpss=0
  [ -n "$cp" ] && cpss=$(adb shell dumpsys meminfo $cp 2>/dev/null | awk '/^ *TOTAL PSS:/{print $3; exit}')
  echo "$(date +%s),$1,${t:-0},${nat:-0},${dal:-0},${code:-0},${gfx:-0},${cpss:-0}" >> $OUT/memoria.csv
}
snapshot() {  # $1 = rotulo: smaps resumido por mapeamento, threads por nome, meminfo -d
  local pid; pid=$(mainpid); [ -z "$pid" ] && return
  # o app de teste e "debuggable": o run-as le o smaps do proprio processo (o shell nao le o de outro uid nos aparelhos "user")
  adb shell "run-as $PKG cat /proc/$pid/smaps" > /tmp/smaps.txt 2>/dev/null
  python3 -I $HERE/smaps_sum.py /tmp/smaps.txt > $OUT/smaps-$1.txt 2>&1
  adb shell "run-as $PKG cat /proc/$pid/smaps_rollup" > $OUT/rollup-$1.txt 2>&1
  adb shell "for d in /proc/$pid/task/*; do cat \$d/comm; done | sort | uniq -c | sort -rn | head -40" > $OUT/threads-$1.txt 2>&1
  adb shell dumpsys meminfo -d $pid > $OUT/meminfo-d-$1.txt 2>&1
}

adb shell am force-stop $PKG; adb shell pm clear $PKG >/dev/null; adb logcat -c

# ---- heapprofd
start_perfetto() {  # $1 = linha que escolhe o processo (pid: N ou process_cmdline: "...")
  local secs=$(( PLAY + 400 ))
  cat > /tmp/heap.cfg <<EOF
buffers { size_kb: 98304 fill_policy: DISCARD }
buffers { size_kb: 4096 fill_policy: DISCARD }
data_sources {
  config {
    name: "android.heapprofd"
    target_buffer: 0
    heapprofd_config {
      sampling_interval_bytes: 32768
      $1
      shmem_size_bytes: 16777216
      block_client: true
      continuous_dump_config { dump_phase_ms: 120000 dump_interval_ms: 60000 }
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { proc_stats_poll_ms: 5000 }
  }
}
duration_ms: $(( secs * 1000 ))
EOF
  # a configuracao entra pela entrada padrao (o perfetto do shell nao le /data/local/tmp nos aparelhos "user")
  adb shell "rm -f /data/misc/perfetto-traces/heap.trace" >/dev/null 2>&1
  adb shell "perfetto -c - --txt -o /data/misc/perfetto-traces/heap.trace --background" < /tmp/heap.cfg > $OUT/perfetto-start.txt 2>&1
  sleep 3; echo "perfetto: $(adb shell pidof perfetto | tr -d '\r')" >> $OUT/perfetto-start.txt
}
[ "$HP" = startup ] && start_perfetto "process_cmdline: \"$PKG\""

# ---- abre o filme e deixa aquecer
adb logcat -c
if [ $MODE = tv ]; then
  adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "$BASE/film.mkv" --es gfTestSubs "$BASE/film.srt" --es gfRx 1 $EXTRA > $OUT/am.txt 2>&1
else
  adb shell am start -n $PKG/.PlayerActivity --es url "$BASE/film.mkv" --esa subs "$BASE/film.srt" --es type movie --es id tt0000001 --es gfRx 1 $EXTRA > $OUT/am.txt 2>&1
fi
sleep 25
adb logcat -d > $OUT/logcat-inicio.txt 2>&1     # o libVLC (-vv) conta aqui qual decodificador e saida de video escolheu
adb logcat -c
sleep 35
PID=$(mainpid); echo "pid=$PID" >> $OUT/aparelho.txt
[ "$HP" = attach ] && start_perfetto "pid: $PID"
sleep 25
sample inicio; snapshot 0
START=$(date +%s)

# ---- reproducao continua
END=$(( START + PLAY )); k=0
while [ $(date +%s) -lt $END ]; do
  sleep 30; sample play; k=$((k+1))
  if [ -z "$(mainpid)" ]; then echo "PROCESSO MORREU" >> $OUT/aparelho.txt; break; fi
  # o filme de 35 min acaba cedo se o tempo passar de 33 min: volta ao comeco
  if [ $((k % 40)) = 0 ]; then tell seek 60; fi
done
sample fim; snapshot 1
adb exec-out screencap -p > $OUT/fim.png 2>/dev/null
echo "imagem no fim (fracao de pixels acesos no meio da tela; preto = 0): $(adb exec-out screencap 2>/dev/null | python3 -I $HERE/pixels.py)" > $OUT/imagem-fim.txt
adb logcat -d > $OUT/logcat-fim.txt 2>&1
grep -A12 "GFCrash" $OUT/logcat-fim.txt | grep -E "MEMORIA|Relatório|reprodução:|PSS total|availMem|heap Java|desde o 1|onTrimMemory" > $OUT/telemetria.txt 2>/dev/null
grep -E "pss=|nativo=" $OUT/logcat-fim.txt | tail -3 > $OUT/estado-fim.txt

# ---- fecha o trace e traz o arquivo
if [ "$HP" != none ]; then
  for i in $(seq 1 40); do [ -z "$(adb shell pidof perfetto | tr -d '\r')" ] && break; [ $i = 1 ] && adb shell 'kill -TERM $(pidof perfetto)'; sleep 3; done
  adb shell ls -la /data/misc/perfetto-traces/ > $OUT/perfetto-ls.txt 2>&1
  adb pull /data/misc/perfetto-traces/heap.trace $OUT/heap.trace > $OUT/perfetto-pull.txt 2>&1
fi
adb shell ls /data/tombstones > $OUT/tombstones.txt 2>&1
A=$(awk -F, '$2=="inicio"{print $3}' $OUT/memoria.csv); B=$(awk -F, '$2=="fim"{print $3}' $OUT/memoria.csv)
NA=$(awk -F, '$2=="inicio"{print $4}' $OUT/memoria.csv); NB=$(awk -F, '$2=="fim"{print $4}' $OUT/memoria.csv)
echo "MEMORIA pss inicio=$((A/1024)) MB fim=$((B/1024)) MB (+$(( (B-A)/1024 )) MB em ${PLAY} s); native inicio=$((NA/1024)) fim=$((NB/1024)) MB (+$(( (NB-NA)/1024 )))" | tee $OUT/resultado.txt
exit 0
