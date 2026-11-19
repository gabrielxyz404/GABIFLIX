#!/usr/bin/env python3
"""Utilitarios da bancada dos 300 titulos (so stdlib): HTTP com limite de taxa por servidor e novas tentativas, SRT, JSON."""
import json, os, re, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
UA = {"User-Agent": "FramePlayBench300/1.0 (bancada de testes de legendas; https://github.com/gabrielxyz404)"}

# intervalo minimo (s) entre pedidos ao mesmo servidor: respeita o archive.org e os addons do Stremio
MIN_INTERVAL = {"archive.org": 1.0, "v3-cinemeta.strem.io": 0.25, "opensubtitles-v3.strem.io": 0.35, "commons.wikimedia.org": 0.5, "download.blender.org": 0.5}
_last = {}
_lock = threading.Lock()


def log(*a):
    print(*a, flush=True)


def _wait(host):
    iv = MIN_INTERVAL.get(host, 0.3)
    with _lock:
        now = time.time()
        t = max(now, _last.get(host, 0) + iv)
        _last[host] = t
    if t > now:
        time.sleep(t - now)


def http_get(url, tries=4, timeout=60, binary=True, headers=None, quiet404=True):
    """-> bytes/str ou None. 404 = None sem insistir; 429/503 = espera crescente (respeita Retry-After)."""
    host = urllib.parse.urlparse(url).netloc.lower()
    last = None
    for a in range(tries):
        _wait(host.split(":")[0] if host.split(":")[0] in MIN_INTERVAL else host.split(":")[0])
        try:
            h = dict(UA)
            if headers:
                h.update(headers)
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
                b = r.read()
                return b if binary else b.decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (404, 410):
                return None
            ra = e.headers.get("Retry-After") if e.headers else None
            time.sleep(min(120, float(ra)) if ra and ra.isdigit() else 5 * (a + 1) * (3 if e.code in (429, 503) else 1))
        except Exception as e:
            last = e
            time.sleep(2 * (a + 1))
    log("  http falhou", url[:140], last)
    return None


def http_json(url, **kw):
    t = http_get(url, binary=False, **kw)
    if not t:
        return None
    try:
        return json.loads(t)
    except Exception:
        return None


def sh(cmd, check=True, timeout=None, quiet=True):
    if not quiet:
        log("+", cmd if isinstance(cmd, str) else " ".join(cmd))
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("falhou (%s): %s" % (cmd if isinstance(cmd, str) else cmd[0], (r.stderr or r.stdout)[-600:]))
    return r


# ---------------------------------------------------------------- SRT (so para a bancada; o parser do APP e o SubFmt/SubtitleText em Java)
TS = re.compile(r"(\d+):(\d\d):(\d\d)[,.](\d{1,3})\s*-->\s*(\d+):(\d\d):(\d\d)[,.](\d{1,3})")


def parse_srt(text):
    cues = []
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("﻿", "")
    for blk in re.split(r"\n\s*\n", text):
        lines = blk.split("\n")
        for i, l in enumerate(lines[:3]):
            m = TS.search(l)
            if m:
                g = m.groups()

                def ms(h, mi, s, f):
                    return ((int(h) * 60 + int(mi)) * 60 + int(s)) * 1000 + int(f.ljust(3, "0")[:3])

                body = "\n".join(lines[i + 1:]).strip()
                if body:
                    cues.append([ms(*g[:4]), ms(*g[4:]), body])
                break
    return cues


def fmt_ms(v):
    v = max(0, int(round(v)))
    return "%02d:%02d:%02d,%03d" % (v // 3600000, v // 60000 % 60, v // 1000 % 60, v % 1000)


def write_srt(cues, path):
    with open(path, "w", encoding="utf-8") as f:
        for i, (a, b, t) in enumerate(cues, 1):
            f.write("%d\n%s --> %s\n%s\n\n" % (i, fmt_ms(a), fmt_ms(b), t))


def slugify(s, n=48):
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:n]


def norm_tokens(s):
    return [t for t in re.split(r"[^a-z0-9]+", s.lower().replace("'", "")) if t]


STOP = {"the", "a", "an", "of", "from", "and", "in", "on", "to", "it", "its", "s", "le", "la"}


def is_pt(l):
    return str(l).lower() in ("pob", "por", "pt", "pt-br", "ptb", "ptbr", "pt-pt", "portuguese")


def is_en(l):
    return str(l).lower() in ("eng", "en", "english")
