#!/system/bin/sh
# amostra a cada 1 s: hora, tempo de CPU do processo do app (ticks de 10 ms) e memoria PSS (kB)
while true; do
  p=$(pidof io.github.gabrielxyz404.gabiflix)
  if [ -n "$p" ]; then
    set -- $(cat /proc/$p/stat 2>/dev/null)
    t=$(( ${14} + ${15} ))
    pss=$(grep -m1 Pss: /proc/$p/smaps_rollup 2>/dev/null | tr -s ' ' | cut -d' ' -f2)
    echo $(date +%s) $t $pss
  fi
  sleep 1
done
