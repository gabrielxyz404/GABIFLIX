#!/bin/bash
# $1 = perfil (tel|tv), $2 = apk. Painel de legendas: barra sem "Sincronizar", painel aberto, sincronização automática, ajuste manual,
# foco do controle na TV, legenda acima dos botões. Nunca publica nada.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; APK=${2:-android/app/build/outputs/apk/release/app-release.apk}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
adb install -r "$APK"
if [ "$P" = tel ]; then adb shell wm size 1080x2400; adb shell wm density 420; fi
adb shell wm size > $OUT/wm.txt
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
BAD=0
N=0
snap() { N=$((N+1)); sleep "${2:-0.6}"; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  if dump | grep -qiE "isn't responding|não está respondendo|Close app|Fechar app"; then echo "FALHA: dialogo ANR na foto $N ($1)" | tee -a $OUT/passos.txt; BAD=1; adb shell input keyevent KEYCODE_BACK; sleep 1; fi; adb exec-out screencap -p > "$OUT/$(printf %02d $N)-$P-$1.png"; echo "$(printf %02d $N) $1: foco=$(dump | python3 $UI focus)" >> $OUT/passos.txt; }
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.7; done; }
W=2400; [ "$P" = tv ] && W=1920; H=1080
BASE=http://10.0.2.2:8000
show_controls() {
  if [ "$P" = tel ]; then xy=$(dump | python3 $UI xy "Voltar"); [ -z "$xy" ] && adb shell input tap $((W/2)) $((H/3)); else key DPAD_DOWN; fi
  sleep 1
}
open_subs() {  # abre o painel (a barra de baixo não aparece no dump da TV: vai por teclas)
  if [ "$P" = tel ]; then click "Legendas"; else key DPAD_DOWN DPAD_DOWN DPAD_CENTER; sleep 1; fi
}
click() {  # texto exato na tela: toque (celular) ou navegação do controle (TV)
  if [ "$P" = tel ]; then
    for t in 1 2 3; do xy=$(dump | python3 $UI xy "$1"); [ -n "$xy" ] && break; show_controls; done
    [ -n "$xy" ] && adb shell input tap $xy || echo "NÃO ACHEI: $1" >> $OUT/passos.txt
  else
    for t in $(seq 1 14); do
      d=$(dump | python3 $UI nav "$1")
      if [ "$d" = HERE ]; then adb shell input keyevent DPAD_CENTER; sleep 1; return; fi
      if [ "$d" = NONE ]; then show_controls; continue; fi
      adb shell input keyevent $d; sleep 0.7
    done
    echo "TV: NÃO CHEGUEI em '$1'" >> $OUT/passos.txt
  fi
  sleep 1
}
launch() {  # $1 = vídeo (nome ou URL), $2 = legendas (vírgula), $3 = referências (vírgula, opcional)
  adb shell am force-stop $PKG; sleep 1
  local u="$1" subs="" refs=""
  case "$u" in http*) ;; *) u="$BASE/$u";; esac
  for s in ${2//,/ }; do subs="$subs,$BASE/$s"; done; subs=${subs#,}
  for s in ${3//,/ }; do refs="$refs,$BASE/$s"; done; refs=${refs#,}
  if [ "$P" = tel ]; then
    local a=(--es url "$u"); [ -n "$subs" ] && a+=(--esa subs "$subs"); [ -n "$refs" ] && a+=(--esa gfTestRefs "$refs")
    adb shell am start -n $PKG/.PlayerActivity "${a[@]}"
  else
    local a=(--es gfTestUrl "$u" --es gfTestSubs "${subs:-$BASE/a1.srt}"); [ -n "$refs" ] && a+=(--es gfTestRefs "$refs")
    adb shell am start -n $PKG/.tv.TvHomeActivity "${a[@]}"
  fi
}
adb logcat -c
[ "$P" = tel ] && { adb root; sleep 3; }

# 0) carregando: só seta, título e círculo; toque/tecla não traz os controles; depois do primeiro quadro eles aparecem e somem
launch http://10.0.2.2:8001/teste.mkv "a1.srt"
sleep 3; adb exec-out screencap -p > "$OUT/00-$P-carregando-1.png"
if [ "$P" = tel ]; then adb shell input tap $((W/2)) $((H/2)); else key DPAD_DOWN; fi
sleep 1; adb exec-out screencap -p > "$OUT/00-$P-carregando-2-depois-de-tocar.png"
# rajada de fotos: o fim do carregamento (controles aparecem uma vez e somem) cai dentro dela
mkdir -p $OUT/seq
big=0
for i in $(seq 1 150); do
  f=$OUT/seq/$P-$(printf %03d $i).png; adb exec-out screencap -p > $f; sz=$(stat -c%s $f)
  if [ $sz -gt 200000 ]; then big=$((big+1)); elif [ $big = 0 ] && [ $i -gt 2 ]; then rm -f $f; fi   # carregando: guarda só as 2 primeiras
  [ $big -ge 12 ] && break
done

# 1) com referência de tempo: lista, automático ("já estava certa"), manual, automático de novo (corrige)
launch teste.mkv "a1.srt,a2.srt,b.srt" "ref.srt"
sleep 45
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
snap legenda-normal-sem-controles 1
show_controls; snap barra-sem-sincronizar 1
snap legenda-subida-com-controles 0.5
open_subs; sleep 1.5
snap painel-aberto 1
click "Sincronizar automaticamente"
snap auto-a-sincronizar 0.3
sleep 4
snap auto-ja-estava-certa 0.5
click "+10 s"; click "+10 s"
snap manual-mais-20 0.8
click "Sincronizar automaticamente"; sleep 5
snap auto-acertada 0.5
click "−5 s"; click "−1 s"
snap manual-menos-6 0.8
click "Repor"
snap manual-repor 0.8
if [ "$P" = tv ]; then
  key DPAD_UP DPAD_UP; snap tv-foco-subindo 0.5
  key DPAD_LEFT; snap tv-foco-lista 0.5
  key DPAD_DOWN; snap tv-foco-lista-baixo 0.5
fi
dump > $OUT/painel-$P.xml
python3 - $OUT/painel-$P.xml > $OUT/painel-$P-bounds.txt <<'PY'
import sys,re
import xml.etree.ElementTree as ET
raw=open(sys.argv[1]).read(); i=raw.find('<?xml')
r=ET.fromstring(raw[i:raw.rfind('</hierarchy>')+12])
for n in r.iter('node'):
    t=n.get('text','')
    if t or 'ScrollView' in n.get('class',''):
        print(n.get('class','').split('.')[-1],repr(t),n.get('bounds'),'scrollable='+n.get('scrollable',''),'focused='+n.get('focused',''))
PY
adb shell input keyevent KEYCODE_BACK; sleep 1.5
snap voltar-fecha-painel 0.5
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumed" | head -2 >> $OUT/passos.txt

# 2) sem referência: o automático não consegue
launch teste.mkv "a1.srt,a2.srt,b.srt"
sleep 40
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
show_controls; open_subs; sleep 1.5
click "Sincronizar automaticamente"; sleep 4
snap auto-nao-consegui 0.5
adb shell input keyevent KEYCODE_BACK; sleep 1

# 3) legenda embutida: automático desligado com explicação
launch emb.mkv "a1.srt"
sleep 40
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
show_controls; open_subs; sleep 1.5
snap embutida-auto-desligado 0.5
adb shell input keyevent KEYCODE_BACK; sleep 1
# 4) faixas embutidas: rótulos
launch emb3.mkv "a1.srt"
sleep 40
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
show_controls; open_subs; sleep 1.5
snap embutidas-rotulos 0.5
adb shell input keyevent KEYCODE_BACK; sleep 1
adb logcat -d | grep -E "GabiFlixPlayer|GFPlayer" | grep -E "painel|sincroniza|atraso|embutida|vistas" > $OUT/log.txt
adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head -40 > $OUT/crash.txt
cat $OUT/passos.txt $OUT/crash.txt
[ $BAD = 1 ] && exit 1
exit 0
