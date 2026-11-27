#!/usr/bin/env python3
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from nextversion import next_version

cases = [
    ("Versão 2.0 — Mac com chip Apple", "2.1"),
    ("Versão 2.8 — x", "2.9"),
    ("Versão 2.9 — x", "3.0"),
    ("Versão 3.9 — x", "4.0"),
    ("Versão 2.3 — x\n<!-- build 1.0.50 -->", "2.4"),
    ("Versão 1.0.45 — Mac com chip Apple", "2.0"),
    ("", "2.0"),
    (None, "2.0"),
    ("texto sem versão", "2.0"),
    ("Versão 1.9 — x", "2.0"),
]
fails = 0
for body, want in cases:
    got = next_version(body)
    print(("ok    " if got == want else "FALHA ") + repr(body) + " -> " + got + (" (esperado " + want + ")" if got != want else ""))
    fails += got != want
sys.exit(1 if fails else 0)
