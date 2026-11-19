#!/usr/bin/env python3
"""Sonda de rede/formatos (roda so no CI): o que archive.org, Cinemeta, o addon OpenSubtitles e a Wikimedia Commons devolvem de verdade,
para a bancada dos 300 titulos nao depender de suposicoes. Imprime amostras curtas."""
import json, re, sys, time, urllib.parse, urllib.request

UA = {"User-Agent": "FramePlayBench300/1.0 (bancada de legendas; contato: github.com/gabrielxyz404)"}


def get(url, binary=False, timeout=60, rng=None):
    h = dict(UA)
    if rng:
        h["Range"] = rng
    t0 = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
            b = r.read()
            return (b if binary else b.decode("utf-8", "replace")), r.status, time.time() - t0, dict(r.headers)
    except Exception as e:
        return None, str(e)[:200], time.time() - t0, {}


def show(tag, r, n=700):
    b, st, dt, h = r
    print("=== %s: status=%s %.1fs bytes=%s" % (tag, st, dt, len(b) if b else None))
    if b and isinstance(b, str) and '"docs"' in b:
        try:
            j = json.loads(b)
            print("numFound=%s" % j["response"]["numFound"])
            for d in j["response"]["docs"]:
                print("   ", json.dumps(d, ensure_ascii=False)[:300])
            return
        except Exception as e:
            print("json?", e)
    if b:
        print((b if isinstance(b, str) else repr(b[:200]))[:n])


def ia_search(q, rows=5, sort="downloads desc", fl=("identifier", "title", "year", "downloads", "runtime", "licenseurl", "creator")):
    p = [("q", q), ("rows", str(rows)), ("page", "1"), ("output", "json"), ("sort[]", sort)] + [("fl[]", f) for f in fl]
    return get("https://archive.org/advancedsearch.php?" + urllib.parse.urlencode(p))


show("IA feature_films top", ia_search("collection:feature_films AND mediatype:movies"))
for col in ("film_noir", "Film_Noir", "filmnoir", "Comedy_Films", "silent_films", "Action_Adventure", "Horror", "Drama", "classic_tv_1950s", "prelinger", "opensource_movies"):
    b, st, dt, h = ia_search("collection:%s AND mediatype:movies" % col, 1)
    try:
        print("colecao %s: numFound=%s" % (col, json.loads(b)["response"]["numFound"]))
    except Exception:
        print("colecao", col, st)
show("IA classic_tv", ia_search("collection:classic_tv AND mediatype:movies", 5))
show("IA SciFi_Horror", ia_search("collection:SciFi_Horror AND mediatype:movies", 3))
show("IA modern com SRT", ia_search("mediatype:movies AND format:SubRip AND year:[2005 TO 2025] AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*)", 8), 1500)
show("IA nasa com SRT/VTT", ia_search("mediatype:movies AND (format:SubRip OR format:WebVTT) AND collection:nasa", 5), 1200)
r = ia_search("collection:feature_films AND mediatype:movies AND format:SubRip", 5)
show("IA feature_films com SRT", r, 1200)

b, st, dt, h = ia_search("collection:feature_films AND mediatype:movies AND title:(night of the living dead)", 5)
show("IA NOTLD", (b, st, dt, h))
ident = "night_of_the_living_dead"
try:
    ident = json.loads(b)["response"]["docs"][0]["identifier"]
except Exception:
    pass
b, st, dt, h = get("https://archive.org/metadata/" + ident)
md = json.loads(b) if b else {}
if md.get("metadata"):
    print("=== metadata night_of_the_living_dead: files=%d keys=%s" % (len(md.get("files", [])), list(md.get("metadata", {}).keys())[:25]))
    print("license:", md["metadata"].get("licenseurl"), "| collection:", str(md["metadata"].get("collection"))[:150])
    for f in md["files"]:
        if re.search(r"\.(mp4|ogv|mkv|avi|mpg|m4v|webm|srt|vtt)$", f["name"], re.I):
            print("  ", f["name"], f.get("format"), f.get("size"), f.get("length"), f.get("source"))
else:
    print("metadata falhou", st)

for q in ("Night of the Living Dead", "Charade", "Sintel", "Cosmos Laundromat"):
    show("cinemeta " + q, get("https://v3-cinemeta.strem.io/catalog/movie/top/search=%s.json" % urllib.parse.quote(q)), 400)
show("cinemeta series Dragnet", get("https://v3-cinemeta.strem.io/catalog/series/top/search=Dragnet.json"), 500)
show("cinemeta meta series (videos)", get("https://v3-cinemeta.strem.io/meta/series/tt0044243.json"), 600)
b, st, dt, h = get("https://opensubtitles-v3.strem.io/subtitles/movie/tt0063350.json")
if b:
    items = json.loads(b).get("subtitles", [])
    from collections import Counter
    print("=== addon NOTLD: %d itens, linguas=%s" % (len(items), Counter(i.get("lang") for i in items).most_common(12)))
    print(json.dumps(items[:2], ensure_ascii=False)[:600])
    pt = [i for i in items if i.get("lang") in ("por", "pob")]
    if pt:
        s = get(pt[0]["url"], binary=True)
        print("download PT: status=%s bytes=%s head=%r" % (s[1], len(s[0]) if s[0] else None, (s[0] or b"")[:120]))
else:
    print("addon falhou", st)
show("addon series (ep)", get("https://opensubtitles-v3.strem.io/subtitles/series/tt0044243:1:1.json"), 400)

show("commons search Sintel", get("https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=Sintel+webm&srnamespace=6&format=json&srlimit=5"), 700)
show("commons timedtext", get("https://commons.wikimedia.org/w/index.php?title=TimedText:Sintel_movie_4K.webm.en.srt&action=raw"), 300)
show("blender HEAD-ish (range 0-1)", get("https://download.blender.org/durian/subs/sintel_en.srt"), 300)
show("alass release", get("https://github.com/kaegi/alass/releases/download/v2.0.0/alass-linux64", binary=True, rng="bytes=0-100"), 100)

# velocidade de download do archive.org (50 MB do Night of the Living Dead)
b, st, dt, h = get("https://archive.org/download/%s/%s_512kb.mp4" % (ident, ident), binary=True, timeout=120, rng="bytes=0-52428799")
print("=== velocidade IA: status=%s bytes=%s %.1fs -> %.1f MB/s" % (st, len(b) if b else None, dt, (len(b) / 1e6 / dt) if b else 0))
print("headers:", {k: v for k, v in h.items() if k.lower() in ("content-range", "content-length", "server", "x-ratelimit-remaining", "retry-after")})
