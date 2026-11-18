#!/bin/bash
# $1 = perfil (tel|tv)  $2 = apk  $3 = rótulo (antes|depois)  $4 = cenários (lista separada por vírgula; padrão todos)
# Voltar ao app com o filme tocando: sai (HOME / tela apagada / seletor de apps), espera, volta pelo ícone do app e fotografa.
# Antes de sair: modo Preencher ligado e atraso de legenda +1 s (para provar que continuam depois de voltar).
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}; L=${3:-depois}; CEN=${4:-home60,tela,outro}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system screen_off_timeout 600000
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
adb root >/dev/null 2>&1; sleep 4  # PlayerActivity não é exportada: só o shell root consegue abri-la
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
snap() { sleep "${2:-0.5}"; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  adb exec-out screencap -p > "$OUT/$L-$P-$1.png"; echo "foto $1 em $(date +%T)" >> $OUT/passos.txt; }
topact() { adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -2 | tr -s ' ' ; }
texts() { dump | python3 $UI texts > "$OUT/$L-$P-$1.txt"; }
W=2400; [ "$P" = tv ] && W=1920; H=1080
show_controls() { if [ "$P" = tel ]; then adb shell input tap $((W/2)) $((H/3)); else adb shell input keyevent DPAD_DOWN; fi; sleep 1.5; }
click() {
  if [ "$P" = tel ]; then
    for t in 1 2 3; do xy=$(dump | python3 $UI xy "$1"); [ -n "$xy" ] && break; show_controls; done
    if [ -n "$xy" ]; then adb shell input tap $xy; else echo "NAO ACHEI: $1" >> $OUT/passos.txt; sleep 1; return 1; fi
  else
    for t in $(seq 1 14); do
      d=$(dump | python3 $UI nav "$1")
      if [ "$d" = HERE ]; then adb shell input keyevent DPAD_CENTER; sleep 1; return 0; fi
      if [ "$d" = NONE ]; then show_controls; continue; fi
      adb shell input keyevent $d; sleep 0.7
    done
    echo "TV: NAO CHEGUEI em '$1'" >> $OUT/passos.txt; return 1
  fi
  sleep 1; return 0
}
tv_focus_click() {  # $1 = prefixo do botão; botões da barra de baixo: Legendas, Áudio, Tela (da esquerda para a direita)
  local alvo=$1 f
  for t in $(seq 1 12); do
    f=$(dump | python3 $UI focus)
    case "$f" in
      "$alvo"*) adb shell input keyevent DPAD_CENTER; sleep 1.2; return 0;;
      Legendas*|Áudio*|Audio*|Tela*)
        case "$alvo" in Tela) adb shell input keyevent DPAD_RIGHT;; *) adb shell input keyevent DPAD_LEFT;; esac;;
      *) adb shell input keyevent DPAD_DOWN;;
    esac
    sleep 0.7
  done
  echo "TV: nao cheguei em $alvo (foco: $f)" >> $OUT/passos.txt; return 1
}
abrir() {
  adb shell am force-stop $PKG; sleep 1
  if [ "$P" = tel ]; then
    adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.MainActivity; sleep 6
    adb shell am start -n $PKG/.PlayerActivity --es url "http://10.0.2.2:8000/$1" --esa subs "http://10.0.2.2:8000/teste.srt" --es type movie --es id tt0000001 --es gfAudioAuto false
  else adb shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n $PKG/.tv.TvHomeActivity --es gfTestUrl "http://10.0.2.2:8000/$1" --es gfTestSubs "http://10.0.2.2:8000/teste.srt" --es gfAudioAuto false; fi
}
voltar_pelo_icone() {  # traz a tarefa do app de volta à frente, sem criar nada novo (como o seletor de apps); o monkey/ícone abriria outra tela por cima do player
  for i in 1 2 3; do adb shell am start -n $PKG/.PlayerActivity -f 0x20000 >/dev/null 2>&1; sleep 1.5; topact | grep -q PlayerActivity && break; done
  if ! topact | grep -q PlayerActivity; then
    echo "voltar: am start nao trouxe o player ($(topact)), tenta o ícone" >> $OUT/passos.txt
    if [ "$P" = tel ]; then adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    else adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; fi
  fi
}
preparar() {  # Preencher + atraso +1 s
  sleep 25
  snap prep-a-tocando 0.2; echo "apos abrir: $(topact)" >> $OUT/passos.txt
  adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1.5
  show_controls
  snap prep-b-controles 0.2; dump | python3 $UI texts > $OUT/$L-$P-prep-textos.txt
  if [ "$P" = tel ]; then
    click "Tela"; sleep 1; show_controls
    if click "Legendas"; then
      sleep 1.5; snap prep-c-painel-legendas 0.2
      click "+1 s"; sleep 1
      adb shell input keyevent KEYCODE_BACK; sleep 1.5
    fi
  else
    tv_focus_click Tela; sleep 1
    adb shell input keyevent DPAD_DOWN; sleep 1
    if tv_focus_click Legendas; then
      sleep 1.5; snap prep-c-painel-legendas 0.2
      click "+1 s"; sleep 1
      adb shell input keyevent KEYCODE_BACK; sleep 1.5
    fi
  fi
  echo "apos preparar: $(topact)" >> $OUT/passos.txt
  adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1; adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 14
}
cenario() {  # $1 = nome  $2 = modo (home|tela|recentes)  $3 = segundos fora
  local n=$1 modo=$2 fora=$3
  echo "=== $n ($modo, $fora s) ===" >> $OUT/passos.txt
  adb logcat -c; abrir wide.mkv
  preparar
  snap $n-0-antes-de-sair 0.3
  case $modo in
    home) adb shell input keyevent 3 ;;
    tela) adb shell input keyevent KEYCODE_SLEEP ;;
    recentes) adb shell input keyevent KEYCODE_APP_SWITCH ;;
    outro) adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1 ;;  # outro app por cima (na TV não há como abrir a tela inicial e voltar)
  esac
  sleep 3
  echo "$n fora: $(topact)" >> $OUT/passos.txt
  [ "$modo" != tela ] && snap $n-1-fora 0.2
  [ $fora -gt 3 ] && sleep $((fora-3))
  case $modo in
    home) voltar_pelo_icone ;;
    outro) adb shell input keyevent KEYCODE_BACK; sleep 1 ;;
    tela) adb shell input keyevent KEYCODE_WAKEUP; sleep 1; adb shell wm dismiss-keyguard >/dev/null 2>&1 ;;
    recentes) adb shell input keyevent KEYCODE_APP_SWITCH; sleep 0.4; adb shell input keyevent KEYCODE_APP_SWITCH; sleep 1.5
              if ! topact | grep -q $PKG; then echo "$n: seletor de apps sem efeito, volta pelo ícone" >> $OUT/passos.txt; voltar_pelo_icone; fi ;;
  esac
  snap $n-2-voltou-1s 1.0
  echo "$n voltou: $(topact)" >> $OUT/passos.txt
  snap $n-3-voltou-5s 4.0
  show_controls; snap $n-4-controles 0.5; texts $n-controles
  sleep 10; snap $n-5-10s-depois 0.2
  adb logcat -v epoch -d -s GabiFlixPlayer | grep -E "ciclo|Vout|zoom Preencher|atraso da legenda|barras" > $OUT/$L-$P-log-$n.txt
  adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|vout|mediacodec|egl|surface" -i | head -80 > $OUT/$L-$P-logcat-$n.txt
}
IFS=, read -ra LISTA <<< "$CEN"
for c in "${LISTA[@]}"; do
  case $c in
    home5) cenario home5 home 5 ;;
    home60) cenario home60 home 60 ;;
    tela) cenario tela tela 6 ;;
    recentes) cenario recentes recentes 6 ;;
    outro) cenario outro outro 20 ;;
  esac
done
adb logcat -d | grep -E "FATAL EXCEPTION" | head -40 > $OUT/$L-$P-crash.txt
exit 0
