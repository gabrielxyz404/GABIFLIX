#!/usr/bin/env python3
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nextversion import next_version, next_of

cases = [
    (next_version("Versão 2.0\n<!-- build 1.0.120 -->"), "2.1"),
    (next_version("Versão 2.8"), "2.9"),
    (next_version("Versão 2.9"), "3.0"),
    (next_version("Versão 3.9"), "4.0"),
    (next_version("Versão 1.0.110\nBaixe o FramePlay.apk"), "2.0"),
    (next_version(""), "2.0"),
    (next_version(None), "2.0"),
    (next_of("Versão 1.0.110", "Versão 1.0.112"), "2.0"),
    (next_of("Versão 2.1", "Versão 2.3"), "2.4"),
    (next_of("", "Versão 2.9"), "3.0"),
]
fails = 0
for got, want in cases:
    print(("ok    " if got == want else "FALHA ") + got + ("" if got == want else " (esperado " + want + ")"))
    fails += got != want
sys.exit(1 if fails else 0)
