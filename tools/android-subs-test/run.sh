#!/bin/bash
# $1 = perfil (tel|tv). Roda o player com legendas conhecidas e confere: legenda escolhida, atraso (aplica e restaura),
# diálogo de sincronização (toque no celular, controle remoto na TV), os 3 modos de Tela (16:9 e 2,39:1), rotação, PLAY/PAUSE.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}
OUT=shots; mkdir -p $OUT
LOG=$OUT/steps.txt
adb install -r android/app/build/outputs/apk/release/app-release.apk
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
adb shell wm size > $OUT/wm.txt
N=0
snap() { N=$((N+1)); adb exec-out screencap -p > "$OUT/$(printf %02d $N)-$P-$1.png"; echo "snap $N $1" >> $LOG; }
note() { echo "[$(date +%T)] $*" | tee -a $LOG; }
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.7; done; }
glog() { adb logcat -d -s GabiFlixPlayer:I | sed 's/^.*GabiFlixPlayer: //' ; }
mark() { adb shell log -t GabiFlixPlayer "=== $* ==="; }
W=2400; [ "$P" = tv ] && W=1920   # tela do player (celular em paisagem)
H=1080

launch() { # $1 = video, $2.. = legendas (nomes)
  v=$1; shift
  subs=""; for s in "$@"; do subs="$subs,http://10.0.2.2:8000/$s"; done; subs=${subs#,}
  adb shell am force-stop $PKG; sleep 1
  if [ "$P" = tel ]; then
    if [ -n "$subs" ]; then adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/$v" --esa subs "$subs"
    else adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/$v"; fi
  else
    if [ -n "$subs" ]; then adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$v" --es gfTestSubs "$subs"
    else adb shell am start -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$v"; fi
  fi
}
show_controls() {
  if [ "$P" = tel ]; then
    xy=$(dump | python3 tools/android-subs-test/ui.py xy "Voltar")
    [ -z "$xy" ] && adb shell input tap $((W/2)) $((H/3))
  else key DPAD_DOWN; fi
  sleep 1
}
pause() { adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5; }
play() { adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1; }
click() { # texto
  if [ "$P" = tel ]; then
    for t in 1 2 3 4; do
      xy=$(dump | python3 tools/android-subs-test/ui.py xy "$1"); [ -n "$xy" ] && break
      case "$1" in Português*) adb shell input swipe $((W/2)) $((H*2/3)) $((W/2)) $((H/4)) 300;; *) show_controls;; esac
    done
    [ -n "$xy" ] && adb shell input tap $xy || note "NÃO ACHEI: $1"
  else
    for t in $(seq 1 12); do
      d=$(dump | python3 tools/android-subs-test/ui.py nav "$1")
      if [ "$d" = HERE ]; then adb shell input keyevent DPAD_CENTER; note "TV: OK em '$1' após $t passos"; sleep 1; return; fi
      if [ "$d" = NONE ]; then show_controls; continue; fi
      adb shell input keyevent $d; sleep 0.6
    done
    note "TV: NÃO CHEGUEI em '$1'"
  fi
  sleep 1
}
texts() { dump | python3 tools/android-subs-test/ui.py texts > "$OUT/$(printf %02d $N)-$P-$1.txt"; }
wait_ready() { sleep ${1:-35}; }

# ================= 1. escolha da legenda (consenso: a1+a2 iguais, b deslocada) =================
adb logcat -c
if [ "$P" = tel ]; then adb root; sleep 3; fi
launch teste.mkv a1.srt a2.srt b.srt
wait_ready 40
pause
snap 1-inicio
note "log após anexar:"; glog | grep -E "ranking|escolhida|candidatas|válidas|consenso|atraso da legenda ativa|embutida" | tee -a $LOG
show_controls; click "Legendas"; sleep 1; snap 2-menu-legendas; texts menu
for i in 2 3 1; do
  click "Português $i"; sleep 2; pause; snap 3-escolhe-$i; mark "escolheu item $i"
  show_controls; click "Legendas"; sleep 1
done
glog | grep -E "atraso da legenda" | tee -a $LOG

# ================= 2. atraso: aplica, guarda =================
show_controls; click "Legendas"; sleep 1; click "Sincronizar legenda"; sleep 1; snap 4-sync-dialogo; texts sync
mark "atraso +5 +1 +1"
click "+5 s"; click "+1 s"; click "+1 s"; sleep 1; snap 5-sync-mais7; texts sync7
click "Fechar"; sleep 1
play; sleep 4; pause; snap 6-atraso-aplicado
glog | grep -E "atraso da legenda" | tail -5 | tee -a $LOG

# ================= 3. sincronizar pela fala =================
show_controls; click "Legendas"; sleep 1; click "Sincronizar legenda"; sleep 1; click "Sincronizar pela fala"; sleep 2; snap 7-fala-lista; texts fala
adb shell input keyevent DPAD_DOWN; adb shell input keyevent DPAD_DOWN; sleep 1
[ "$P" = tel ] && { xy=$(dump | python3 tools/android-subs-test/ui.py xy "Cancelar"); [ -n "$xy" ] && adb shell input tap $xy; } || key BACK
sleep 1
click "Fechar" ; sleep 1

# ================= 4. restaura o atraso depois de sair e voltar =================
mark "SAIU"
adb shell am force-stop $PKG; sleep 2
launch teste.mkv a1.srt a2.srt b.srt
mark "VOLTOU"
sleep 14; snap 8-restaura-aviso
wait_ready 25
pause; snap 9-restaura-pausado
glog | sed -n '/=== VOLTOU ===/,$p' | grep -E "atraso da legenda|escolhida" | tee -a $LOG

# ================= 5. embutida + baixadas (mapeamento das faixas com legenda embutida) =================
launch emb.mkv a1.srt a2.srt b.srt
mark "EMB+BAIXADAS"
wait_ready 40; pause; snap 10-emb-inicio
show_controls; click "Legendas"; sleep 1; snap 11-emb-menu; texts embmenu
click "Português 2"; sleep 2; pause; snap 12-emb-escolhe-2
glog | sed -n '/=== EMB+BAIXADAS ===/,$p' | grep -E "embutida|escolhida|atraso" | tee -a $LOG

# ================= 6. PLAY não pausa quando já está tocando =================
launch teste.mkv a1.srt a2.srt b.srt
mark "PLAYPLAY"
wait_ready 30
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1
snap 13-play-play-a
sleep 4
snap 13-play-play-b
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1; adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
snap 13-pause-pause-c
sleep 3
snap 13-pause-pause-d

# ================= 7. três modos de Tela (16:9 e 2,39:1) =================
scales() { # rótulo
  for i in 1 2 3; do
    show_controls; click "Tela:"; sleep 1; play; sleep 5; pause; snap 14-$1-modo$i
  done
}
scales 169
launch wide.mkv a1.srt a2.srt b.srt
wait_ready 35; pause; snap 15-wide-ajustar
scales wide

# ================= 8. rotação do celular (reverso) =================
if [ "$P" = tel ]; then
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 3; sleep 4; snap 16-gira-reverso
  adb shell settings put system user_rotation 1; sleep 4; snap 17-gira-normal
  adb shell settings put system user_rotation 0; sleep 4; snap 18-retrato-pedido
  adb shell dumpsys activity activities | grep -m3 -E "mResumedActivity|mCurrentOrientation" >> $LOG
  adb shell settings put system user_rotation 1
fi
note "fim dos cenários do player"
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: |ANR in|has died" | grep -v "RuntimeInit\|boot image\|lock profiling\|Calling main\|Shutting down" | head -40 > $OUT/crash.txt
glog > $OUT/player-full.txt
adb logcat -d | grep -E "ActivityManager.*(PlayerActivity|Displayed)" | tail -40 > $OUT/am.txt

# ================= 9. celular: site dentro do app (WebView), Voltar =================
if [ "$P" = tel ]; then
  adb shell am force-stop $PKG
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1; sleep 20; snap 19-webview
  adb shell settings put system user_rotation 1; sleep 4; snap 20-webview-gira
  adb shell settings put system user_rotation 0; sleep 3
  adb shell input keyevent BACK; sleep 2; snap 21-webview-voltar
  adb shell dumpsys activity activities | grep -m2 mResumedActivity >> $LOG
  adb logcat -d | grep -E "FATAL EXCEPTION" | head >> $OUT/crash.txt
fi
exit 0
