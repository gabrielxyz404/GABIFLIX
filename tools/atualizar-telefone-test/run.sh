#!/bin/bash
# $1 = apk (versionCode baixo)
# Prova a atualização dentro do app no celular: diálogo automático, Instalar -> download -> permissão -> instalador do sistema,
# segunda abertura sem diálogo (menos de 6 h), e o botão Atualizar do menu do site.
set -x
PKG=io.github.gabrielxyz404.gabiflix
APK=${1:-android/app/build/outputs/apk/release/app-release.apk}
OUT=shots; mkdir -p $OUT
UI=tools/android-subs-test/ui.py
RES=$OUT/resultado.txt; : > $RES
adb install -r "$APK"
adb shell wm size 1080x2400; adb shell wm density 420
adb shell settings put global hide_error_dialogs 1
adb shell settings put system screen_off_timeout 600000
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
adb shell pm clear $PKG >/dev/null 2>&1
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES deny >/dev/null 2>&1
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml 2>/dev/null; }
snap() { sleep "${2:-0.5}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "foto $1 em $(date +%T)" >> $OUT/passos.txt; }
topact() { adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity" | head -2 | tr -s ' '; }
tem() { dump | python3 $UI texts | grep -qi "$1"; }
esperar() { for i in $(seq 1 $2); do tem "$1" && return 0; sleep 2; done; return 1; }
tocar() { local xy; xy=$(dump | python3 $UI xy "$1"); [ -n "$xy" ] && adb shell input tap $xy && return 0; echo "NAO ACHEI: $1" >> $RES; return 1; }
ok() { echo "$1: $2" | tee -a $RES; }
abrir() { adb shell am force-stop $PKG; sleep 1; adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.MainActivity --es gfTestRelease http://10.0.2.2:8000/test-release/ "$@" >/dev/null; }

# 1) primeira abertura: diálogo automático
# (o emulador acabou de arrancar: espera o sistema assentar e, se o app não ficar à frente, recomeça do zero)
adb wait-for-device; for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
sleep 20
for tentativa in 1 2 3; do
  adb shell pm clear $PKG >/dev/null 2>&1; adb shell appops set $PKG REQUEST_INSTALL_PACKAGES deny >/dev/null 2>&1
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  abrir
  sleep 4; snap 1a-4s 0
  esperar "Nova versão do FramePlay" 14 && break
  echo "1: sem diálogo na tentativa $tentativa: $(topact)" >> $OUT/passos.txt
done
if tem "Nova versão do FramePlay"; then ok 1-dialogo-automatico SIM; else ok 1-dialogo-automatico NAO; fi
snap 1-dialogo-automatico 0.5
dump | python3 $UI texts > $OUT/1-dialogo-textos.txt

# 2) Instalar: transferir -> sem permissão -> definição do sistema
tocar "Instalar"
snap 2-a-transferir 0.4
if esperar "Allow from this source" 60; then ok 3-pede-permissao SIM; else ok 3-pede-permissao NAO; fi
snap 3-permissao 0.5; topact >> $OUT/passos.txt
# 3) permissão dada na definição, volta ao app: continua e abre o instalador do sistema
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow
adb shell input keyevent KEYCODE_BACK
for i in $(seq 1 15); do topact | grep -qi "packageinstaller" && break; sleep 2; done
snap 4-instalador-do-sistema 1.5; topact | tee -a $OUT/passos.txt
dump | python3 $UI texts > $OUT/4-instalador-textos.txt
if topact | grep -qi "packageinstaller"; then ok 4-instalador SIM; else ok 4-instalador NAO; fi

# 4) segunda abertura, menos de 6 h depois: nenhum diálogo
adb shell am force-stop com.google.android.packageinstaller; adb shell am force-stop com.android.packageinstaller
sleep 4
for tentativa in 1 2 3; do
  abrir; sleep 20
  topact | grep -q "gabiflix/.MainActivity" && tem "Mais Tarde" && break
  echo "5: o app não ficou à frente (tentativa $tentativa): $(topact)" >> $OUT/passos.txt
done
sleep 10
snap 5-segunda-abertura-sem-dialogo 0.3
if ! topact | grep -q "gabiflix/.MainActivity"; then ok 5-segunda-abertura-sem-dialogo "NAO (o app não estava à frente)"
elif tem "Nova versão do FramePlay"; then ok 5-segunda-abertura-sem-dialogo "NAO (apareceu o diálogo)"; else ok 5-segunda-abertura-sem-dialogo SIM; fi

# 5) botão Atualizar do menu do site (cópia do site da branch, servida pelo runner)
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES deny
abrir --es gfTestWeb http://10.0.2.2:8000/
sleep 25
snap 6-site-aberto 0.3
dump | python3 $UI texts > $OUT/6-site-textos.txt
for xy in "980 140" "1000 190" "1010 120" "960 220"; do
  tem "Atualizar" && break
  adb shell input tap $xy; sleep 2
done
snap 7-menu-aberto 0.3
dump | python3 $UI texts > $OUT/7-menu-textos.txt
if tem "Atualizar" && tocar "Atualizar"; then
  if esperar "Nova versão do FramePlay" 10; then ok 8-menu-atualizar-dialogo SIM; else ok 8-menu-atualizar-dialogo NAO; fi
  snap 8-menu-atualizar-dialogo 0.3
  tocar "Depois"; sleep 12
  snap 9-depois-e-o-site-recarrega 0.3
  if tem "Nova versão do FramePlay"; then ok 9-depois-fecha-dialogo NAO; else ok 9-depois-fecha-dialogo SIM; fi
else ok 8-menu-atualizar-dialogo "NAO (botão Atualizar não encontrado)"; fi
adb logcat -d | grep -E "FATAL EXCEPTION" | head -40 > $OUT/crash.txt
adb logcat -d | grep -E "GFUpdate" > $OUT/gfupdate-log.txt
cat $RES
exit 0
