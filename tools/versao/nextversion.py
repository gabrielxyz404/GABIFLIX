#!/usr/bin/env python3
"""Próxima versão X.Y do app Android (celular + TV: o mesmo APK).

Uso: nextversion.py FICHEIRO_CORPO [FICHEIRO_CORPO ...]   (corpos das releases android e tv; ficheiro vazio = sem release)
Cada publicação sobe 0.1 (2.0 ... 2.9, 3.0 — nunca 2.10) a partir da MAIOR versão "Versão X.Y" publicada.
Sem versão X.Y (release antiga "Versão 1.0.110") a próxima é 2.0.
Mesma regra de ReleaseVersion.nextOf (Java), testada em tools/versao/test_nextversion.py e no ReleaseVersionTest.
"""
import re
import sys


def next_version(body):
    m = re.search(r"Vers[ãa]o\s+(\d+)\.(\d+)(?![\d.])", body or "")
    if not m or int(m.group(1)) < 2:
        return "2.0"
    major, minor = int(m.group(1)), int(m.group(2)) + 1
    if minor > 9:
        major, minor = major + 1, 0
    return "%d.%d" % (major, minor)


def next_of(*bodies):
    best = (2, 0)
    for b in bodies:
        v = tuple(int(x) for x in next_version(b).split("."))
        best = max(best, v)
    return "%d.%d" % best


if __name__ == "__main__":
    bodies = []
    for p in sys.argv[1:]:
        with open(p, encoding="utf-8") as f:
            bodies.append(f.read())
    print(next_of(*bodies))
