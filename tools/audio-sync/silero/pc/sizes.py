#!/usr/bin/env python3
"""Tamanhos dos APKs release (sem ORT x com ORT por ABI). Uso: sizes.py DIR"""
import os, sys, zipfile
d = sys.argv[1]
b = os.path.getsize(os.path.join(d, "base.apk"))
print("base (sem ORT): %.1f MB" % (b / 1e6))
for n in ("ort-arm64-armv7", "ort-arm64", "ort-armv7"):
    p = os.path.join(d, n + ".apk")
    if not os.path.exists(p): print(n, "ausente"); continue
    s = os.path.getsize(p)
    z = zipfile.ZipFile(p)
    so = {i.filename: (i.file_size, i.compress_size) for i in z.infolist() if i.filename.endswith(".so")}
    print("%s: APK %.1f MB, +%.1f MB sobre o APK sem ORT; .so: %s" % (n, s / 1e6, (s - b) / 1e6, ", ".join("%s %.1f MB (comprimido %.1f MB)" % (k, v[0] / 1e6, v[1] / 1e6) for k, v in so.items())))
