#!/usr/bin/env python3
"""Catalogo dos titulos da bancada (roda so no CI; precisa de rede): sorteia (semente fixa) titulos LEGAIS e acha a legenda de cada um.

  catalog.py descobrir SAIDA.json [--semente 300] [--antigos 330] [--novos 190] [--tv 70] [--pool 1000] [--limite-s 3300]

ANTIGOS: dominio publico do archive.org (colecoes feature_films, Film_Noir, SciFi_Horror, Comedy_Films), 1927-1979, sorteio estratificado por decada.
         Episodios de TV antigos (classic_tv) entram de proposito: a falha real do usuario e uma serie antiga com deriva gradual.
NOVOS:   filmes abertos/CC com mixagem moderna: lista curada (Blender: Sintel, Tears of Steel, Cosmos Laundromat, Spring, Agent 327, Sprite Fright, Charge...,
         Sita Sings the Blues, Star Wreck, ...), itens do archive.org com licenca Creative Commons/dominio publico E legenda propria, videos da NASA (governo dos EUA).
LEGENDAS (em ordem): addon OpenSubtitles do Stremio pelo IMDb (PT primeiro, depois EN e outras), .srt/.vtt do proprio item do archive.org (as transcricoes
         automaticas *.asr.srt do IA NAO entram: o tempo delas vem do proprio audio e viciaria a medida), TimedText da Wikimedia Commons.
So entram titulos com ao menos 1 legenda candidata; o alinhamento ao audio (alass) e a qualidade da referencia sao medidos depois, no shard.
Cada entrada leva a licenca declarada. Nada de filme comercial/pirata."""
import json, os, random, re, sys, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

IA = "https://archive.org"
OLD_COLLECTIONS = ["feature_films", "Film_Noir", "SciFi_Horror", "Comedy_Films"]
TV_COLLECTIONS = ["classic_tv"]

# curados: filmes abertos modernos (titulo, ano, consulta no IA, imdb opcional)
NEW_CURATED = [
    ("Sintel", 2010, "Sintel", "tt1727587"), ("Tears of Steel", 2012, "Tears of Steel", "tt2285752"), ("Elephants Dream", 2006, "Elephants Dream", "tt0807758"),
    ("Cosmos Laundromat: First Cycle", 2015, "Cosmos Laundromat", None), ("Spring", 2019, "Spring Blender", None), ("Agent 327: Operation Barbershop", 2017, "Agent 327 Operation Barbershop", None),
    ("Sprite Fright", 2021, "Sprite Fright", None), ("Charge", 2022, "Charge Blender", None), ("Coffee Run", 2020, "Coffee Run Blender", None), ("Hero", 2018, "Hero Blender Animation", None),
    ("Wing It!", 2023, "Wing It Blender", None), ("Big Buck Bunny", 2008, "Big Buck Bunny", "tt1254207"), ("Caminandes 3: Llamigos", 2016, "Caminandes Llamigos", None),
    ("Sita Sings the Blues", 2008, "Sita Sings the Blues", "tt1172991"), ("Valkaama", 2010, "Valkaama", None), ("Star Wreck: In the Pirkinning", 2005, "Star Wreck Pirkinning", "tt0449994"),
    ("The Internet's Own Boy", 2014, "The Internet's Own Boy", "tt3268458"), ("RiP: A Remix Manifesto", 2008, "RiP A Remix Manifesto", "tt1138527"),
    ("Pioneer One", 2010, "Pioneer One", None),
]

NOT_FILM = re.compile(r"\b(lecture|lectures|course|opencourseware|tutorial|webinar|conference|symposium|workshop|class|seminar|keynote|podcast|livestream|gameplay|walkthrough|trailer|clip|compilation)\b", re.I)
NEW_QUERIES = [
    ("cc-srt", "mediatype:movies AND format:SubRip AND year:[2003 TO 2025] AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*) AND -creator:(MIT OpenCourseWare)"),
    ("cc-vtt", "mediatype:movies AND format:WebVTT AND year:[2003 TO 2025] AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*) AND -creator:(MIT OpenCourseWare)"),
    ("nasa", "mediatype:movies AND (format:SubRip OR format:WebVTT) AND collection:nasa AND year:[2000 TO 2025]"),
]

DECADE_SHARE = {1920: 0.04, 1930: 0.15, 1940: 0.26, 1950: 0.26, 1960: 0.20, 1970: 0.09}
FL = ("identifier", "title", "year", "downloads", "runtime", "licenseurl", "creator", "date")


def ia_search(q, rows=1000, page=1, sort="downloads desc"):
    p = [("q", q), ("rows", str(rows)), ("page", str(page)), ("output", "json"), ("sort[]", sort)] + [("fl[]", f) for f in FL]
    j = http_json(IA + "/advancedsearch.php?" + urllib.parse.urlencode(p), timeout=120)
    try:
        return j["response"]["docs"], j["response"].get("numFound", 0)
    except Exception:
        return [], 0


def as_list(v):
    return v if isinstance(v, list) else ([] if v is None else [v])


def first(v):
    l = as_list(v)
    return str(l[0]) if l else ""


def year_of(d):
    for k in ("year", "date"):
        m = re.search(r"(1[89]\d\d|20[0-2]\d)", first(d.get(k)))
        if m:
            return int(m.group(1))
    for k in ("title", "identifier"):
        m = re.search(r"\b(19[2-7]\d|20[0-2]\d)\b", first(d.get(k)))
        if m:
            return int(m.group(1))
    return None


def runtime_s(d):
    r = first(d.get("runtime"))
    m = re.match(r"^(?:(\d+):)?(\d+):(\d+)$", r.strip())
    if m:
        return int(m.group(1) or 0) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
    return None


JUNK = re.compile(r"\b(full movie|full film|hd|1080p|720p|480p|restored|colorized|colourised|public domain|classic|film noir|remastered|dvd|rip|subtitled|english|portuguese|legendado|dublado|complete series|complete season|tv show|tv series|the complete|collection)\b", re.I)


def clean_title(t):
    t = re.sub(r"\[[^\]]*\]|\([^)]*\)", " ", t)
    t = JUNK.sub(" ", t)
    t = re.sub(r"[_|]+", " ", t)
    return re.sub(r"\s+", " ", t).strip(" -:,.")


def title_queries(t):
    base = clean_title(t)
    out = [base]
    for sep in (" - ", ": ", " – "):
        if sep in base:
            out.append(base.split(sep)[0].strip())
    m = re.match(r"^(.*), (The|A|An)$", base)
    if m:
        out.append(m.group(2) + " " + m.group(1))
    seen, res = set(), []
    for q in out:
        if q and q.lower() not in seen and len(q) >= 3:
            seen.add(q.lower())
            res.append(q)
    return res[:3]


def cinemeta(kind, q):
    j = http_json("https://v3-cinemeta.strem.io/catalog/%s/top/search=%s.json" % (kind, urllib.parse.quote(q)))
    return (j or {}).get("metas", []) or []


def match_imdb(kind, title, year):
    req = [t for t in norm_tokens(clean_title(title)) if t not in STOP]
    if not req:
        return None, None, None
    for q in title_queries(title):
        for m in cinemeta(kind, q):
            nm = norm_tokens(m.get("name", ""))
            yr = str(m.get("releaseInfo", "") or m.get("year", ""))[:4]
            ok_year = True if (kind != "movie" or not year) else (yr.isdigit() and abs(int(yr) - year) <= 1)
            if ok_year and all(r in nm or any(r in n for n in nm) for r in req) and len(nm) <= len(req) + 3:
                return m["id"], m.get("name"), yr
    return None, None, None


def addon_subs(path, maxpt=3, maxen=3, maxother=2):
    j = http_json("https://opensubtitles-v3.strem.io/subtitles/%s.json" % path)
    items = (j or {}).get("subtitles", []) if j else []
    pt, en, ot = [], [], []
    for it in items:
        lg = str(it.get("lang", "")).lower()
        u = it.get("url")
        if not u:
            continue
        rec = {"lang": lg, "url": u, "src": "opensubtitles", "id": str(it.get("id", "")), "enc": it.get("SubEncoding"), "fps": it.get("fpsMilli"), "name": str(it.get("subtitleFileName", ""))[:80]}
        if is_pt(lg) and len(pt) < maxpt:
            pt.append(rec)
        elif is_en(lg) and len(en) < maxen:
            en.append(rec)
        elif lg in ("spa", "fre", "fra", "ita", "ger", "deu", "dut", "nld", "cat", "glg") and len(ot) < maxother:
            ot.append(rec)
    return pt + en + ot, len(items)


VID = re.compile(r"\.(mp4|m4v|ogv|mkv|avi|mpg|mpeg|webm|mov)$", re.I)
SUBF = re.compile(r"\.(srt|vtt)$", re.I)
LANGTAGS = {"en": "eng", "eng": "eng", "english": "eng", "pt": "por", "por": "por", "pob": "pob", "ptbr": "pob", "pt-br": "pob", "portuguese": "por", "es": "spa", "spa": "spa", "spanish": "spa",
            "fr": "fre", "fre": "fre", "fra": "fre", "french": "fre", "de": "ger", "ger": "ger", "german": "ger", "it": "ita", "ita": "ita", "italian": "ita"}


def lang_from_name(n):
    toks = re.split(r"[^a-z]+", n.lower().rsplit(".", 1)[0])
    for t in reversed(toks):
        if t in LANGTAGS:
            return LANGTAGS[t]
    return "und"


def stem_of(name):
    s = re.sub(r"\.[A-Za-z0-9]{2,4}$", "", name)
    s = re.sub(r"[_.\- ](512kb|ipod|iphone|psp|ps3|dvd5|dvd9|720p|1080p|480p|360p|h264|hd|sd|mp4|ogv|web)$", "", s, flags=re.I)
    return s


def ia_item(ident):
    """/metadata/ID -> (lista de videos candidatos [um por 'stem': o menor derivado], subs do proprio item, meta). Duracao conhecida (8 min .. 5 h)."""
    md = http_json(IA + "/metadata/" + urllib.parse.quote(ident), timeout=90)
    if not md or "files" not in md:
        return [], [], {}
    stems = {}
    for f in md["files"]:
        n = f.get("name", "")
        if not VID.search(n):
            continue
        try:
            size = int(f.get("size", 0) or 0)
            length = float(f.get("length", 0) or 0)
        except ValueError:
            continue
        if size < 20_000_000 or size > 3_000_000_000 or not (480 <= length <= 5 * 3600):
            continue
        k = stem_of(n)
        cur = stems.get(k)
        if cur is None or size < cur["size"]:
            stems[k] = {"url": "%s/download/%s/%s" % (IA, urllib.parse.quote(ident), urllib.parse.quote(n)), "file": n, "size": size, "length": length, "format": f.get("format", ""), "stem": k}
    subs, n_asr = [], 0
    for f in md["files"]:
        n = f.get("name", "")
        if SUBF.search(n):
            if ".asr." in n.lower() or n.lower().endswith("asr.srt"):
                n_asr += 1
                continue
            try:
                sz = int(f.get("size", 0) or 0)
            except ValueError:
                sz = 0
            if 3_000 < sz < 3_000_000:
                subs.append({"lang": lang_from_name(n), "url": "%s/download/%s/%s" % (IA, urllib.parse.quote(ident), urllib.parse.quote(n)), "src": "ia-item", "id": n, "stem": stem_of(re.sub(r"\.[a-z]{2,3}\.(srt|vtt)$", "", n))})
    m = md.get("metadata", {})
    meta = {"license": first(m.get("licenseurl")) or first(m.get("rights"))[:100] or "", "creator": first(m.get("creator"))[:80], "title": first(m.get("title"))[:100], "asr_subs_ignored": n_asr}
    return sorted(stems.values(), key=lambda s: -s["length"]), subs, meta


EPI = [re.compile(r"[Ss](\d{1,2})\s*[-_. ]?[Ee](\d{1,3})"), re.compile(r"\b(\d{1,2})x(\d{1,3})\b"), re.compile(r"[Ss]eason\s*(\d{1,2}).{0,12}?[Ee]pisode\s*(\d{1,3})", re.I)]


def parse_episode(*strings):
    for s in strings:
        for rx in EPI:
            m = rx.search(s)
            if m:
                return int(m.group(1)), int(m.group(2)), s[:m.start()]
    return None


_series_cache = {}


def series_meta(sid):
    if sid not in _series_cache:
        j = http_json("https://v3-cinemeta.strem.io/meta/series/%s.json" % sid)
        _series_cache[sid] = ((j or {}).get("meta") or {}).get("videos") or []
    return _series_cache[sid]


def find_series(title):
    """nome da serie no titulo do item -> (id, nome). Tenta o titulo limpo e prefixos."""
    base = clean_title(title)
    cands = [base]
    for sep in (" - ", ": ", " – ", " S0", " s0", " Season", " season"):
        if sep in base:
            cands.append(base.split(sep)[0].strip())
    seen = set()
    for q in cands:
        q = q.strip()
        if len(q) < 3 or q.lower() in seen:
            continue
        seen.add(q.lower())
        req = [t for t in norm_tokens(q) if t not in STOP]
        for m in cinemeta("series", q):
            nm = norm_tokens(m.get("name", ""))
            if req and all(r in nm for r in req) and len(nm) <= len(req) + 2:
                return m["id"], m.get("name")
    return None, None


def episode_from_names(sid, text):
    """acha (S, E) pelo nome do episodio dentro do texto (nome do arquivo)"""
    tt = set(norm_tokens(text))
    best = None
    for v in series_meta(sid):
        nm = [t for t in norm_tokens(v.get("name", "")) if t not in STOP]
        if len(nm) < 2 or not v.get("season"):
            continue
        hit = sum(1 for t in nm if t in tt) / len(nm)
        if hit >= 0.99 and (best is None or len(nm) > best[0]):
            best = (len(nm), int(v["season"]), int(v["number"]))
    return (best[1], best[2]) if best else None


def pick_year(d, default=None):
    return year_of(d) or default


def base_entry(ident, title, year, kind, stem, meta, subs, imdb=None, series=None, tag=None):
    sid = slugify(ident + ("_" + stem if stem and kind == "old-tv" else ""), 60)
    return {"id": sid, "title": title if kind != "old-tv" else "%s [%s]" % (title, stem[:50]), "year": year, "decade": (year // 10 * 10) if year else None, "kind": kind, "imdb": imdb, "series": series,
            "media": None, "ia": ident, "meta": meta, "subs": subs, "tag": tag}


def resolve_old(d):
    """filme do IA (documento do advancedsearch) -> (entrada | None, motivo)"""
    ident, title = d["identifier"], first(d.get("title")) or d["identifier"]
    year = year_of(d)
    if not year or year < 1927 or year > 1979:
        return None, "ano fora de 1927-1979"
    if NOT_FILM.search(title):
        return None, "nao parece filme"
    rt = runtime_s(d)
    if rt is not None and not (40 * 60 <= rt <= 4.5 * 3600):
        return None, "duracao fora de 40 min..4,5 h"
    imdb, nm, yr = match_imdb("movie", title, year)
    subs = addon_subs("movie/" + imdb)[0] if imdb else []
    stems, isubs, meta = ia_item(ident)
    subs = subs + [s for s in isubs]
    if not stems:
        return None, "sem derivado de video com duracao"
    feat = [s for s in stems if s["length"] >= 40 * 60]
    if not feat:
        return None, "nenhum arquivo >= 40 min"
    if not subs:
        return None, "sem legenda"
    e = base_entry(ident, title, year, "old", None, meta, subs, imdb=imdb)
    e["media"] = feat[0]
    return e, "ok"


def resolve_tv(d):
    """item de classic_tv -> lista de entradas (episodios) ou ([], motivo)"""
    ident, title = d["identifier"], first(d.get("title")) or d["identifier"]
    stems, isubs, meta = ia_item(ident)
    eps = [s for s in stems if 8 * 60 <= s["length"] <= 100 * 60]
    if not eps:
        return [], "sem episodio de 8-100 min"
    sid, sname = find_series(title)
    out = []
    random.Random(zlib_crc(ident)).shuffle(eps)
    for s in eps[:6]:
        ep = parse_episode(s["stem"], title, ident)
        se = (ep[0], ep[1]) if ep else None
        if not se and sid:
            se = episode_from_names(sid, s["stem"] + " " + title)
        subs = []
        imdb = sid
        if se and sid:
            subs = addon_subs("series/%s:%d:%d" % (sid, se[0], se[1]))[0]
        own = [x for x in isubs if x.get("stem") == s["stem"] or len(stems) == 1]
        subs = subs + own
        if not subs:
            continue
        e = base_entry(ident, title, year_of(d), "old-tv", s["stem"], meta, subs, imdb=imdb, series={"name": sname, "season": se[0] if se else None, "episode": se[1] if se else None})
        e["media"] = s
        out.append(e)
        if len(out) >= 2:
            break
    return out, ("ok" if out else "sem legenda (serie=%s)" % sid)


def zlib_crc(s):
    import zlib
    return zlib.crc32(s.encode())


def resolve_new_ia(d, tag):
    ident, title = d["identifier"], first(d.get("title")) or d["identifier"]
    if NOT_FILM.search(title):
        return None, "nao parece filme"
    stems, isubs, meta = ia_item(ident)
    if not stems:
        return None, "sem derivado de video"
    lic = meta.get("license", "")
    if not ("creativecommons" in lic or "publicdomain" in lic or tag == "nasa"):
        return None, "licenca nao livre"
    if not isubs:
        return None, "sem legenda"
    e = base_entry(ident, title, year_of(d), "new", None, meta, isubs, tag=tag)
    e["media"] = stems[0]
    return e, "ok"


def commons_subs(query):
    """Wikimedia Commons: video por busca + TimedText (en/pt) -> lista de subs"""
    j = http_json("https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=%s&srnamespace=6&srlimit=5&format=json" % urllib.parse.quote(query + " webm"))
    subs = []
    for h in ((j or {}).get("query", {}) or {}).get("search", [])[:3]:
        fn = h["title"].replace("File:", "", 1)
        if not re.search(r"\.(webm|ogv|mp4)$", fn, re.I):
            continue
        for lg, code in (("por", "pt-br"), ("por", "pt"), ("eng", "en")):
            u = "https://commons.wikimedia.org/w/index.php?title=TimedText:%s.%s.srt&action=raw" % (urllib.parse.quote(fn.replace(" ", "_")), code)
            b = http_get(u, tries=1)
            if b and b"-->" in b:
                subs.append({"lang": lg, "url": u, "src": "commons-timedtext", "id": fn + "." + code})
        if subs:
            break
    return subs


def resolve_curated(item):
    title, year, q, imdb = item
    docs, _ = ia_search('title:("%s") AND mediatype:movies' % q.replace('"', ""), rows=8, sort="downloads desc")
    req = [t for t in norm_tokens(title) if t not in STOP][:4]
    pick = None
    for d in docs:
        nm = norm_tokens(first(d.get("title")) + " " + d["identifier"])
        if all(r in nm or any(r in n for n in nm) for r in req):
            stems, isubs, meta = ia_item(d["identifier"])
            if stems:
                pick = (d, stems, isubs, meta)
                break
    if not pick:
        return None, "nao achado no IA"
    d, stems, isubs, meta = pick
    subs = list(isubs)
    if not imdb:
        imdb, _, _ = match_imdb("movie", title, year)
    if imdb:
        subs = addon_subs("movie/" + imdb)[0] + subs
    if not subs:
        subs = commons_subs(title)
    if not subs:
        return None, "sem legenda"
    e = base_entry(d["identifier"], title, year, "new", None, meta, subs, imdb=imdb, tag="curado")
    e["id"] = slugify(title)
    e["media"] = stems[0]
    return e, "ok"


def main():
    args = sys.argv[1:]
    out = args[0]
    opt = {"--semente": "300", "--antigos": "330", "--novos": "190", "--limite-s": "3300", "--pool": "1000", "--tv": "70"}
    for i in range(1, len(args) - 1, 2):
        opt[args[i]] = args[i + 1]
    seed, n_old, n_new, limit, pool, n_tv = int(opt["--semente"]), int(opt["--antigos"]), int(opt["--novos"]), int(opt["--limite-s"]), int(opt["--pool"]), int(opt["--tv"])
    rnd = random.Random(seed)
    t_end = time.time() + limit
    stats = {"descartes": {}, "pool": {}}

    def drop(k):
        stats["descartes"][k] = stats["descartes"].get(k, 0) + 1

    old_pool, tv_pool = [], []
    for col in OLD_COLLECTIONS:
        docs, n = ia_search("collection:%s AND mediatype:movies AND year:[1927 TO 1979]" % col, rows=min(pool, 1000))
        stats["pool"][col] = [len(docs), n]
        log("colecao", col, len(docs), "de", n)
        old_pool += docs
    for col in TV_COLLECTIONS:
        docs, n = ia_search("collection:%s AND mediatype:movies" % col, rows=min(pool, 1000))
        stats["pool"][col] = [len(docs), n]
        log("colecao", col, len(docs), "de", n)
        tv_pool += docs
    seen = set()
    old_pool = [d for d in old_pool if not (d["identifier"] in seen or seen.add(d["identifier"]))]
    tv_pool = [d for d in tv_pool if not (d["identifier"] in seen or seen.add(d["identifier"]))]
    bydec = {}
    for d in old_pool:
        y = year_of(d)
        if y and 1927 <= y <= 1979:
            bydec.setdefault(y // 10 * 10, []).append(d)
    for k in bydec:
        rnd.shuffle(bydec[k])
    rnd.shuffle(tv_pool)
    log("pool antigo por decada:", {k: len(v) for k, v in sorted(bydec.items())}, "tv:", len(tv_pool))

    results, lock = {"old": [], "old-tv": [], "new": []}, threading.Lock()
    quota_dec = {k: max(1, round(n_old * v)) for k, v in DECADE_SHARE.items()}

    def add(es, why):
        with lock:
            if es:
                for e in es:
                    results[e["kind"]].append(e)
                    log("OK  %-7s %s (%s) langs=%s" % (e["kind"], e["title"][:55], e.get("year"), ",".join(sorted(set(s["lang"] for s in e["subs"])))))
            else:
                drop(why.split("(")[0].strip())

    def work(kind, d, tag=None):
        if time.time() > t_end:
            return
        try:
            if kind == "new-ia":
                e, why = resolve_new_ia(d, tag)
                add([e] if e else [], why)
            elif kind == "curado":
                e, why = resolve_curated(d)
                add([e] if e else [], why)
            elif kind == "tv":
                es, why = resolve_tv(d)
                add(es, why)
            else:
                e, why = resolve_old(d)
                add([e] if e else [], why)
        except Exception as ex:
            add([], "excecao " + repr(ex)[:80])

    ex = ThreadPoolExecutor(max_workers=5)
    futs = []
    for it in NEW_CURATED:
        futs.append(ex.submit(work, "curado", it))
    for tag, q in NEW_QUERIES:
        docs, n = ia_search(q, rows=400, sort="downloads desc")
        stats["pool"]["novo:" + tag] = [len(docs), n]
        log("novos", tag, len(docs), "de", n)
        rnd.shuffle(docs)
        for d in docs[:int(n_new * 1.2 / len(NEW_QUERIES)) + 10]:
            futs.append(ex.submit(work, "new-ia", d, tag))
    # TV primeiro (o caso real do usuario), depois filmes por decada
    for d in tv_pool[:max(40, n_tv * 3)]:
        futs.append(ex.submit(work, "tv", d))
    mx = max(len(v) for v in bydec.values()) if bydec else 0
    for i in range(0, mx):
        for k, docs in bydec.items():
            if i < int(quota_dec.get(k, 5) * 4.5) and i < len(docs):
                futs.append(ex.submit(work, "old", docs[i]))
    for f in as_completed(futs):
        pass
    ex.shutdown()

    for k in results:
        log(k, len(results[k]))

    def pick(lst, n):
        rnd.shuffle(lst)
        return lst[:n]

    sel_old = []
    bd = {}
    for e in results["old"]:
        bd.setdefault(e["decade"], []).append(e)
    for dec, lst in bd.items():
        sel_old += pick(lst, int(quota_dec.get(dec, 5) * 1.5) + 2)
    rnd.shuffle(sel_old)
    sel_tv = pick(results["old-tv"], int(n_tv * 1.6))
    sel_new = pick(results["new"], int(n_new * 1.6))
    cat = {"semente": seed, "gerado_em": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "stats": stats,
           "contagem": {"old": len(sel_old), "old-tv": len(sel_tv), "new": len(sel_new), "achados": {k: len(v) for k, v in results.items()}}, "titulos": sel_old + sel_tv + sel_new}
    json.dump(cat, open(out, "w"), indent=1, ensure_ascii=False)
    log("catalogo:", cat["contagem"], "descartes:", stats["descartes"])


if __name__ == "__main__":
    if sys.argv[1] == "descobrir":
        sys.argv = sys.argv[1:]
        main()
