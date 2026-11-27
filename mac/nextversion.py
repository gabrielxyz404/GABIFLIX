#!/usr/bin/env python3
"""Próxima versão de marketing do FramePlay para Mac.

Uso: nextversion.py [CORPO_DA_RELEASE]   (ou o corpo no stdin se não houver argumento)
Lê a linha "Versão X.Y" do corpo da release `mac` já publicada e imprime a seguinte:
cada publicação sobe 0.1 (2.0, 2.1, ... 2.9, 3.0 — nunca 2.10).
Sem versão no corpo, ou release antiga ("Versão 1.0.45"), a próxima é 2.0.
"""
import re
import sys

BASE = "2.0"


def next_version(body):
    m = re.search(r"Vers[ãa]o\s+(\d+)\.(\d+)(?![\d.])", body or "")
    if not m or int(m.group(1)) < 2:
        return BASE
    major, minor = int(m.group(1)), int(m.group(2)) + 1
    if minor > 9:
        major, minor = major + 1, 0
    return "%d.%d" % (major, minor)


if __name__ == "__main__":
    body = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()
    print(next_version(body))
