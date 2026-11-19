#!/usr/bin/env python3
"""Catalogo dos titulos da bancada (roda so no CI; precisa de rede): sorteia (semente fixa) titulos LEGAIS e acha a legenda de cada um.

  catalog.py descobrir SAIDA.json [--semente 300] [--antigos 330] [--novos 190] [--limite-s 3300]

ANTIGOS: dominio publico do archive.org (colecoes feature_films, film_noir, SciFi_Horror, classic_tv, Comedy_Films, ...), 1927-1979 (TV ate 1985),
         sorteio estratificado por decada; episodios de TV antigos entram de proposito (a falha real do usuario e uma serie antiga com deriva).
NOVOS:   filmes abertos/CC com mixagem moderna: lista curada (Blender: Sintel, Tears of Steel, Cosmos Laundromat, Agent 327, Sprite Fright, Charge, Spring...,
         Sita Sings the Blues, Valkaama, ...), itens do archive.org com licenca Creative Commons/dominio publico e legenda propria, filmes da NASA (governo dos EUA).
LEGENDAS (em ordem): addon OpenSubtitles do Stremio pelo IMDb (PT primeiro, depois EN e outras), arquivos .srt/.vtt do proprio item do archive.org,
         legendas oficiais da Blender, TimedText da Wikimedia Commons. So entram titulos com ao menos 1 legenda candidata; o alinhamento ao audio
         (alass) e a qualidade da referencia sao medidos depois, no shard.
Cada entrada leva de onde vem a midia e a licenca declarada. Nada de filme comercial/pirata.
"""
import json, os, random, re, sys, threading, time, urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import *

IA = "https://archive.org"
OLD_COLLECTIONS = ["feature_films", "film_noir", "SciFi_Horror", "Comedy_Films", "moviesandfilms"]
TV_COLLECTIONS = ["classic_tv"]
POOL = 1500

# curados: filmes abertos modernos (titulo, ano, consulta no IA/Commons, imdb opcional)
NEW_CURATED = [
    ("Sintel", 2010, "Sintel", "tt1727587"), ("Tears of Steel", 2012, "Tears of Steel", "tt2285752"), ("Elephants Dream", 2006, "Elephants Dream", "tt0807758"),
    ("Cosmos Laundromat", 2015, "Cosmos Laundromat", None), ("Spring", 2019, "Spring Blender", None), ("Agent 327: Operation Barbershop", 2017, "Agent 327 Operation Barbershop", None),
    ("Sprite Fright", 2021, "Sprite Fright", None), ("Charge", 2022, "Charge Blender", None), ("Coffee Run", 2020, "Coffee Run Blender", None), ("Hero", 2018, "Hero Blender Animation", None),
    ("Wing It!", 2023, "Wing It Blender", None), ("Big Buck Bunny", 2008, "Big Buck Bunny", "tt1254207"), ("Caminandes 3: Llamigos", 2016, "Caminandes Llamigos", None),
    ("Sita Sings the Blues", 2008, "Sita Sings the Blues", "tt1172991"), ("Valkaama", 2010, "Valkaama", None), ("Star Wreck: In the Pirkinning", 2005, "Star Wreck Pirkinning", "tt0449994"),
    ("The Internet's Own Boy", 2014, "The Internet's Own Boy", "tt3268458"), ("RiP: A Remix Manifesto", 2008, "RiP A Remix Manifesto", "tt1138527"),
    ("Pioneer One", 2010, "Pioneer One", None), ("Nasty Baby", 2015, "x", None),
]

# colecoes/consultas de material moderno com licenca livre (as consultas de IA exigem legenda no proprio item)
NEW_QUERIES = [
    ("cc-srt", "mediatype:movies AND format:SubRip AND year:[2003 TO 2025] AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*)"),
    ("cc-vtt", "mediatype:movies AND format:WebVTT AND year:[2003 TO 2025] AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*)"),
    ("nasa", "mediatype:movies AND (format:SubRip OR format:WebVTT) AND collection:nasa AND year:[2000 TO 2025]"),
    ("opensource-srt", "mediatype:movies AND format:SubRip AND collection:opensource_movies AND year:[2003 TO 2025]"),
]

DECADE_SHARE = {1920: 0.04, 1930: 0.15, 1940: 0.26, 1950: 0.26, 1960: 0.20, 1970: 0.09}
FL = ("identifier", "title", "year", "downloads", "runtime", "licenseurl", "collection", "creator", "date")


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


JUNK = re.compile(r"\b(full movie|full film|hd|1080p|720p|480p|restored|colorized|colourised|public domain|classic|film noir|remastered|dvd|rip|subtitled|english|portuguese|legendado|dublado)\b", re.I)


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
    for q in title_queries(title):
        for m in cinemeta(kind, q):
            nm = norm_tokens(m.get("name", ""))
            yr = str(m.get("releaseInfo", "") or m.get("year", ""))[:4]
            ok_year = (not year) or (yr.isdigit() and abs(int(yr) - year) <= 1) if kind == "movie" else True
            if ok_year and req and all(r in nm or any(r in n for n in nm) for r in req) and len(nm) <= len(req) + 3:
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
        rec = {"lang": lg, "url": u, "src": "opensubtitles", "id": str(it.get("id", ""))}
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


def ia_media_and_subs(ident):
    """-> (media, subs, meta) a partir de /metadata/ID: o menor derivado de video com duracao conhecida (8 min .. 5 h) e os .srt/.vtt do proprio item"""
    md = http_json(IA + "/metadata/" + urllib.parse.quote(ident), timeout=90)
    if not md or "files" not in md:
        return None, [], {}
    best = None
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
        if best is None or size < best["size"]:
            best = {"url": "%s/download/%s/%s" % (IA, urllib.parse.quote(ident), urllib.parse.quote(n)), "file": n, "size": size, "length": length, "format": f.get("format", "")}
    subs = []
    for f in md["files"]:
        n = f.get("name", "")
        if SUBF.search(n) and f.get("source") != "derivative" or SUBF.search(n):
            try:
                sz = int(f.get("size", 0) or 0)
            except ValueError:
                sz = 0
            if 3_000 < sz < 3_000_000:
                subs.append({"lang": lang_from_name(n), "url": "%s/download/%s/%s" % (IA, urllib.parse.quote(ident), urllib.parse.quote(n)), "src": "ia-item", "id": n})
    m = md.get("metadata", {})
    meta = {"license": first(m.get("licenseurl")) or first(m.get("rights")) or "", "creator": first(m.get("creator")), "collection": as_list(m.get("collection"))[:6], "title": first(m.get("title")),
            "subject": ",".join(as_list(m.get("subject")))[:120]}
    return best, subs, meta


EPI = [re.compile(r"[Ss](\d{1,2})\s*[-_. ]?[Ee](\d{1,3})"), re.compile(r"\b(\d{1,2})x(\d{1,3})\b"), re.compile(r"[Ss]eason\s*(\d{1,2}).{0,12}?[Ee]pisode\s*(\d{1,3})", re.I)]


def parse_episode(title, ident):
    for s in (title, ident):
        for rx in EPI:
            m = rx.search(s)
            if m:
                series = clean_title(s[:m.start()]).strip(" -_.")
                return series, int(m.group(1)), int(m.group(2))
    return None


def resolve_old(d, kind):
    """um candidato do IA (documento do advancedsearch) -> entrada do catalogo ou (None, motivo)"""
    ident, title = d["identifier"], first(d.get("title")) or d["identifier"]
    year = year_of(d)
    subs, imdb, series = [], None, None
    if kind == "tv":
        ep = parse_episode(title, ident)
        if ep:
            sname, s, e = ep
            if sname:
                imdb, nm, yr = match_imdb("series", sname, None)
                if imdb:
                    subs, n = addon_subs("series/%s:%d:%d" % (imdb, s, e))
                    series = {"name": nm, "season": s, "episode": e}
        else:
            return None, "tv sem SxxExx no titulo"
    else:
        if not year or year < 1927 or year > 1979:
            return None, "ano fora de 1927-1979"
        imdb, nm, yr = match_imdb("movie", title, year)
        if imdb:
            subs, n = addon_subs("movie/" + imdb)
    best, isubs, meta = ia_media_and_subs(ident)
    if not best:
        return None, "sem derivado de video com duracao"
    subs = subs + isubs
    if not subs:
        return None, "sem legenda (imdb=%s)" % imdb
    return {"id": slugify(ident), "title": title, "year": year, "decade": (year // 10 * 10) if year else None, "kind": "old-tv" if kind == "tv" else "old", "imdb": imdb, "series": series,
            "media": best, "ia": ident, "meta": meta, "subs": subs}, "ok"


def resolve_new_ia(d, tag):
    ident, title = d["identifier"], first(d.get("title")) or d["identifier"]
    best, isubs, meta = ia_media_and_subs(ident)
    if not best:
        return None, "sem derivado de video"
    lic = meta.get("license", "")
    if not ("creativecommons" in lic or "publicdomain" in lic or tag == "nasa"):
        return None, "licenca nao livre (%s)" % lic[:40]
    year = year_of(d)
    subs = list(isubs)
    imdb = None
    if not subs:
        return None, "sem legenda"
    return {"id": slugify(ident), "title": title, "year": year, "decade": (year // 10 * 10) if year else None, "kind": "new", "imdb": imdb, "series": None, "media": best, "ia": ident, "meta": meta,
            "subs": subs, "tag": tag}, "ok"


def resolve_curated(item):
    title, year, q, imdb = item
    if q == "x":
        return None, "placeholder"
    # 1) archive.org por titulo
    docs, _ = ia_search('title:("%s") AND mediatype:movies' % q.replace('"', ""), rows=8, sort="downloads desc")
    req = [t for t in norm_tokens(title) if t not in STOP][:4]
    pick = None
    for d in docs:
        nm = norm_tokens(first(d.get("title")) + " " + d["identifier"])
        if all(r in nm or any(r in n for n in nm) for r in req):
            best, isubs, meta = ia_media_and_subs(d["identifier"])
            if best:
                pick = (d, best, isubs, meta)
                break
    if not pick:
        return None, "nao achado no IA"
    d, best, isubs, meta = pick
    subs = list(isubs)
    if not imdb:
        imdb, _, _ = match_imdb("movie", title, year)
    if imdb:
        s2, n = addon_subs("movie/" + imdb)
        subs = s2 + subs
    if not subs:
        return None, "sem legenda"
    return {"id": slugify(title), "title": title, "year": year, "decade": year // 10 * 10, "kind": "new", "imdb": imdb, "series": None, "media": best, "ia": d["identifier"], "meta": meta, "subs": subs, "tag": "curado"}, "ok"


def main():
    args = sys.argv[1:]
    out = args[0]
    opt = {"--semente": "300", "--antigos": "330", "--novos": "190", "--limite-s": "3300", "--pool": str(POOL), "--tv": "70"}
    for i in range(1, len(args) - 1, 2):
        opt[args[i]] = args[i + 1]
    seed, n_old, n_new, limit, pool, n_tv = int(opt["--semente"]), int(opt["--antigos"]), int(opt["--novos"]), int(opt["--limite-s"]), int(opt["--pool"]), int(opt["--tv"])
    rnd = random.Random(seed)
    t_end = time.time() + limit
    stats = {"descartes": {}, "pool": {}}

    def drop(k):
        stats["descartes"][k] = stats["descartes"].get(k, 0) + 1

    # ---- pools
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
    # estratifica por decada (cada decada com sua cota), embaralha com a semente
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
    quota_dec = {k: max(1, round((n_old) * v)) for k, v in DECADE_SHARE.items()}

    def work(kind, d, tag=None):
        if time.time() > t_end:
            return
        try:
            if kind == "new-ia":
                e, why = resolve_new_ia(d, tag)
            elif kind == "curado":
                e, why = resolve_curated(d)
            else:
                e, why = resolve_old(d, "tv" if kind == "tv" else "film")
        except Exception as ex:
            e, why = None, "excecao " + repr(ex)[:80]
        with lock:
            if e:
                results[e["kind"]].append(e)
                log("OK  %-8s %s (%s) subs=%s" % (e["kind"], e["title"][:50], e.get("year"), ",".join(sorted(set(s["lang"] for s in e["subs"])))))
            else:
                drop(why.split("(")[0].strip())

    ex = ThreadPoolExecutor(max_workers=5)
    futs = []
    # curados e novos do IA primeiro (poucos)
    for it in NEW_CURATED:
        futs.append(ex.submit(work, "curado", it))
    for tag, q in NEW_QUERIES:
        docs, n = ia_search(q, rows=400, sort="downloads desc")
        stats["pool"]["novo:" + tag] = [len(docs), n]
        log("novos", tag, len(docs), "de", n)
        rnd.shuffle(docs)
        for d in docs[:int(n_new * 1.6 / len(NEW_QUERIES)) + 10]:
            futs.append(ex.submit(work, "new-ia", d, tag))
    # antigos: round-robin por decada ate ~1.6x a cota (reservas); TV: ate n_tv*3 candidatos
    for k, docs in bydec.items():
        for d in docs[:int(quota_dec.get(k, 5) * 4)]:
            futs.append(ex.submit(work, "old", d))
    for d in tv_pool[:n_tv * 6]:
        futs.append(ex.submit(work, "tv", d))
    for f in as_completed(futs):
        pass
    ex.shutdown()

    for k in results:
        log(k, len(results[k]))
    # ---- selecao: ordem sorteada, cotas por decada (antigos) e reservas ao final
    def pick(lst, n):
        rnd.shuffle(lst)
        return lst[:n]

    sel_old = []
    bd = {}
    for e in results["old"]:
        bd.setdefault(e["decade"], []).append(e)
    for dec, lst in bd.items():
        sel_old += pick(lst, int(quota_dec.get(dec, 5) * 1.5) + 2)
    sel_tv = pick(results["old-tv"], int(n_tv * 1.5))
    sel_new = pick(results["new"], int(n_new * 1.5))
    cat = {"semente": seed, "gerado_em": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "stats": stats, "contagem": {"old": len(sel_old), "old-tv": len(sel_tv), "new": len(sel_new)},
           "titulos": sel_old + sel_tv + sel_new}
    json.dump(cat, open(out, "w"), indent=1, ensure_ascii=False)
    log("catalogo:", cat["contagem"], "descartes:", stats["descartes"])


if __name__ == "__main__":
    if sys.argv[1] == "descobrir":
        sys.argv = sys.argv[1:]
        main()
