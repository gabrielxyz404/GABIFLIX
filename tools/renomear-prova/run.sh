#!/bin/bash
# $1 = perfil (tel|tv). Provas do nome FramePlay: etiqueta no launcher, banner da TV, tela do app e atualização por cima da versão antiga.
# Usa: $OLD_APK (build do commit anterior, mesma chave) e $NEW_APK. Nunca publica nada.
set -x
PKG=io.github.gabrielxyz404.gabiflix
P=${1:-tel}; OUT=shots; mkdir -p $OUT
snap() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$P-$1.png"; }
key() { for k in "$@"; do adb shell input keyevent $k; sleep 0.8; done; }

# 1) atualização por cima: instala a versão ANTIGA (nome GabiFlix no código) e depois a NOVA com -r (sem desinstalar)
adb install -r "$OLD_APK" && echo "antiga instalada" >> $OUT/$P-update.txt
adb shell dumpsys package $PKG | grep -E "versionCode|versionName" >> $OUT/$P-update.txt
# dado do app antes da atualização (prova que o -r mantém os dados): grava um valor nas preferências do app
adb shell run-as $PKG sh -c 'mkdir -p shared_prefs; echo "<map><string name=\"marca\">antes</string></map>" > shared_prefs/prova.xml' 2>>$OUT/$P-update.txt || true
adb install -r "$NEW_APK" 2>&1 | tee -a $OUT/$P-update.txt
adb shell dumpsys package $PKG | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" >> $OUT/$P-update.txt
adb shell run-as $PKG cat shared_prefs/prova.xml >> $OUT/$P-update.txt 2>&1 || true

# 2) etiqueta que o launcher mostra
adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG >> $OUT/$P-launcher.txt 2>&1
adb shell pm dump $PKG | grep -iE "label|banner|icon" >> $OUT/$P-launcher.txt 2>&1
if [ "$P" = tel ]; then
  adb shell wm size 1080x2400; adb shell wm density 420
  key HOME; snap home 3
  adb shell input swipe 540 2000 540 600 300; snap gaveta 3        # gaveta de apps: o nome do app aparece embaixo do ícone
  adb shell am start -n $PKG/.MainActivity; snap app-abre 15; snap app-abre2 10
else
  key HOME; snap home 4
  key DPAD_DOWN DPAD_DOWN; snap home-baixo 2
  key DPAD_DOWN DPAD_DOWN DPAD_DOWN; snap home-apps 2
  adb shell monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1; snap app-tv 12
  key DPAD_RIGHT; snap app-tv2 3
fi
adb logcat -d | grep -iE "FATAL|AndroidRuntime" | head -20 >> $OUT/$P-erros.txt
