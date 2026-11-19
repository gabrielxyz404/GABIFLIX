#!/bin/bash
# Testes de legendas na JVM (sem emulador): compila SubtitleOverlay.java e SubtitleText.java de verdade com stubs mínimos do Android.
# uso: tools/bug-sweep/run-jvm.sh   (da raiz do repositório)
set -e
cd "$(dirname "$0")/../.."
SRC=android/app/src/main/java/io/github/gabrielxyz404/gabiflix
OUT=$(mktemp -d)
javac -encoding UTF-8 -d "$OUT" $(find tools/bug-sweep/stubs -name '*.java') "$SRC/SubtitleOverlay.java" "$SRC/SubtitleText.java" tools/bug-sweep/io/github/gabrielxyz404/gabiflix/SweepTest.java
java -Dfile.encoding=UTF-8 -cp "$OUT" io.github.gabrielxyz404.gabiflix.SweepTest
