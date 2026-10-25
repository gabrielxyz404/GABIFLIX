// GabiFlix: página de filme/série no estilo dos apps de streaming.
// Monta um cabeçalho próprio (imagem, título, informações, Assistir, Mais Tarde, Trailer, Visto)
// no lugar da página do Stremio; as fontes abrem numa janela e os episódios aparecem numa lista própria.
(function () {
    var SERVICES = [
        ['netflix', 'Netflix', '#e50914'], ['disney', 'Disney+', '#3aa3ff'], ['prime', 'Prime Video', '#1ba8e0'],
        ['hbo', 'HBO Max', '#a36bff'], ['appletv', 'Apple TV+', '#f5f5f7'], ['paramount', 'Paramount+', '#2f7dff'],
        ['globoplay', 'Globoplay', '#c8d23a'], ['clarovideo', 'Claro Video', '#ff2a2a']
    ];
    var DATA_VERSION = Math.floor(Date.now() / 3600000);
    var idsCache = null, timer = null, lastSig = '', expanded = false, lastMeta = null;

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }
    function core() {
        var c = window.core && (window.core.transport || window.core);
        return c && typeof c.getState === 'function' ? c : null;
    }
    function onDetail() { return /^#\/detail\//.test(location.hash); }

    // em quais serviços o título está (usa as listas de códigos geradas todo dia)
    function serviceIds() {
        if (!idsCache) {
            idsCache = Promise.all(SERVICES.map(function (s) {
                return fetch('data/' + s[0] + '-ids.json?v=' + DATA_VERSION)
                    .then(function (r) { return r.ok ? r.json() : []; })
                    .then(function (ids) { var o = {}; ids.forEach(function (i) { o[i] = true; }); return o; })
                    .catch(function () { return {}; });
            }));
        }
        return idsCache;
    }
    // títulos que já estão nas listas do próprio CineTime
    var cineCache = null;
    function cineIds() {
        if (!cineCache) {
            cineCache = Promise.all(['cine-cinemas', 'cine-classicos', 'cine-movie', 'cine-series'].map(function (f) {
                return fetch('data/' + f + '.json?v=' + DATA_VERSION).then(function (r) { return r.ok ? r.json() : []; }).catch(function () { return []; });
            })).then(function (lists) {
                var o = {};
                lists.forEach(function (l) { ((l && l.metas) || l || []).forEach(function (m) { if (m && m.id) o[m.id] = true; }); });
                return o;
            });
        }
        return cineCache;
    }
    var servicesFor = {}, servicesDone = {};
    // títulos que não estão em nenhum serviço vão para o CineTime (lista guardada neste aparelho)
    var FOUND_KEY = 'gf-cinetime-found';
    function addToCineTime(meta) {
        if (!meta || !meta.id || !/^tt\d+/.test(meta.id) || (meta.type !== 'movie' && meta.type !== 'series')) return;
        try {
            var list = JSON.parse(localStorage.getItem(FOUND_KEY) || '[]').filter(function (m) { return m.id !== meta.id; });
            list.unshift({ id: meta.id, type: meta.type, name: meta.name, poster: meta.poster || ('https://images.metahub.space/poster/medium/' + meta.id + '/img'),
                background: meta.background || undefined, releaseInfo: meta.releaseInfo || undefined, addedAt: Date.now() });
            localStorage.setItem(FOUND_KEY, JSON.stringify(list.slice(0, 120)));
        } catch (e) {}
    }
    function whereIs(id) {
        if (servicesFor[id]) return;
        servicesFor[id] = [];
        serviceIds().then(function (sets) {
            servicesFor[id] = SERVICES.filter(function (s, i) { return sets[i][id]; });
            if (servicesFor[id].length) { servicesDone[id] = true; lastSig = ''; tick(); return; }
            var meta = lastMeta && lastMeta.id === id ? lastMeta : null;
            cineIds().then(function (cine) {
                servicesDone[id] = true;
                if (!cine[id]) addToCineTime(meta);
                lastSig = ''; tick();
            });
        });
    }

    function links(meta, cat) {
        return (meta.links || []).filter(function (l) { return l.category === cat; }).map(function (l) { return l.name; });
    }
    function readyStreams(state) {
        var out = [];
        (state.streams || []).forEach(function (g) {
            if (g && g.content && g.content.type === 'Ready' && Array.isArray(g.content.content)) out = out.concat(g.content.content);
        });
        // fontes com link direto (ex.: Real-Debrid) primeiro; torrents puros não tocam no celular
        return out.map(function (st, i) { return { st: st, i: i }; }).sort(function (a, b) {
            return (isTorrent(a.st) - isTorrent(b.st)) || (a.i - b.i);
        }).map(function (x) { return x.st; });
    }
    function isTorrent(st) { return st && !st.url && !st.ytId && !st.externalUrl && !!st.infoHash ? 1 : 0; }
    var HEAVY_AUDIO = /\b(DTS|TrueHD|E-?AC-?3|AC-?3|DDP?\+?(\s?[257]\.[01])?|Dolby\s?Digital|Atmos)\b/i;
    function streamsLoading(state) {
        return (state.streams || []).some(function (g) { return g && g.content && g.content.type === 'Loading'; });
    }
    function epLabel(v) { return v && v.season != null && v.episode != null ? 'T' + v.season + ':E' + v.episode : ''; }

    function fmtRuntime(r) {
        var m = r && String(r).match(/(\d+)\s*min/);
        if (!m) return r || '';
        var n = +m[1];
        return n >= 60 ? Math.floor(n / 60) + 'h ' + (n % 60 ? (n % 60) + 'min' : '') : n + 'min';
    }

    function dispatch(action) { var c = core(); if (c) try { c.dispatch(action); } catch (e) {} }

    // ---------- desenho ----------
    function build(state) {
        var meta = state.metaItem && state.metaItem.content && state.metaItem.content.type === 'Ready' ? state.metaItem.content.content : null;
        if (!meta) return null;
        var sel = state.selected || {};
        var isSeries = meta.type === 'series' || (meta.videos && meta.videos.length > 1);
        var lib = state.libraryItem;
        var video = null, urlVid = urlVideoId();
        if (urlVid && urlVid !== meta.id && meta.videos) video = meta.videos.filter(function (v) { return v.id === urlVid; })[0] || null;
        var episodeView = isSeries && !!video;
        loadPt(meta.id);
        lastMeta = meta;
        whereIs(meta.id);

        var box = el('div', 'gf-detail');
        var hero = el('div', 'gf-d-hero');
        var bgSrc = (episodeView && video.thumbnail) || meta.background || ('https://images.metahub.space/background/medium/' + encodeURIComponent(meta.id) + '/img');
        var img = el('img', 'gf-d-hero-img');
        img.alt = '';
        img.decoding = 'async';
        img.setAttribute('fetchpriority', 'high');
        img.onload = function () { img.classList.add('is-ready'); };
        img.onerror = function () { img.remove(); };
        img.src = bgSrc;
        hero.appendChild(img);
        box.appendChild(hero);

        var body = el('div', 'gf-d-body');
        var where = servicesFor[meta.id] || [];
        if (where.length) {
            var tag = el('div', 'gf-d-service');
            where.slice(0, 2).forEach(function (s, i) {
                if (i) tag.appendChild(el('span', 'gf-d-dot', '·'));
                var n = el('span', null, s[1]);
                n.style.color = s[2];
                tag.appendChild(n);
            });
            body.appendChild(tag);
        } else if (servicesDone[meta.id]) {
            var ct = el('div', 'gf-d-service');
            var cn = el('span', null, 'CineTime');
            cn.style.color = '#e9c46a';
            ct.appendChild(cn);
            body.appendChild(ct);
        }
        body.appendChild(el('h1', 'gf-d-title', meta.name || ''));

        var info = el('div', 'gf-d-info');
        var bits = [];
        if (meta.releaseInfo) bits.push(meta.releaseInfo);
        if (!isSeries && meta.runtime) bits.push(fmtRuntime(meta.runtime));
        if (isSeries && meta.videos) {
            var seasons = {};
            meta.videos.forEach(function (v) { if (v.season > 0) seasons[v.season] = 1; });
            var ns = Object.keys(seasons).length;
            if (ns) bits.push(ns + (ns > 1 ? ' temporadas' : ' temporada'));
        }
        bits.forEach(function (b) { info.appendChild(el('span', null, b)); });
        var imdb = links(meta, 'imdb')[0];
        if (imdb) {
            var r = el('span', 'gf-d-imdb');
            r.appendChild(el('b', null, 'IMDb'));
            r.appendChild(document.createTextNode(' ' + imdb));
            info.appendChild(r);
        }
        body.appendChild(info);

        if (episodeView) body.appendChild(el('div', 'gf-d-episode', epLabel(video) + (video.title ? ' · ' + video.title : '')));

        // botão principal
        var streams = readyStreams(state);
        var play = el('button', 'gf-d-play');
        play.type = 'button';
        var st = lib && lib.state;
        var label = '▶  Assistir', target = null, eps = [];
        if (!isSeries || episodeView) {
            var resumable = st && st.timeOffset > 0 && st.duration > 0 && (!isSeries || st.video_id === (video && video.id)) && st.timeOffset < st.duration * 0.95;
            if (resumable) label = '▶  Continuar';
            if (!episodeView && !streams.length) label = streamsLoading(state) ? 'Procurando fontes…' : 'Nenhuma fonte encontrada';
            play.disabled = !episodeView && !streams.length;
            play.addEventListener('click', function () {
                if (episodeView) openSheet(video.id, true); else if (streams[0]) playStream(streams[0]);
            });
            if (resumable) {
                var bar = el('div', 'gf-d-progress');
                var fill = el('span');
                fill.style.width = Math.min(100, st.timeOffset / st.duration * 100).toFixed(1) + '%';
                bar.appendChild(fill);
                play.appendChild(document.createTextNode(label));
                body.appendChild(play);
                body.appendChild(bar);
            } else {
                play.textContent = label;
                body.appendChild(play);
            }
        } else {
            eps = (meta.videos || []).filter(function (v) { return v.season > 0; })
                .sort(function (a, b) { return a.season - b.season || a.episode - b.episode; });
            if (!eps.length) eps = meta.videos || [];
            var last = st && st.video_id ? eps.filter(function (v) { return v.id === st.video_id; })[0] : null;
            target = last || eps[0] || null;
            label = target ? (last ? '▶  Continuar ' : '▶  Assistir ') + epLabel(target) : '▶  Assistir';
            play.textContent = label;
            play.disabled = !target;
            play.addEventListener('click', function () { if (target) openSheet(target.id, true); });
            body.appendChild(play);
        }

        // segundo botão: escolher a fonte
        var choose = el('button', 'gf-d-choose');
        choose.type = 'button';
        if (isSeries && !episodeView) {
            choose.textContent = target ? 'Escolher fonte · ' + epLabel(target) : 'Escolher fonte';
            choose.disabled = !target;
            choose.addEventListener('click', function () { if (target) openSheet(target.id, false); });
        } else {
            choose.textContent = 'Escolher fonte';
            choose.addEventListener('click', function () { openSheet(episodeView ? video.id : meta.id, false); });
        }
        body.appendChild(choose);

        // resumo (3 linhas, toque para ver tudo)
        var desc = (episodeView && video.overview) || ptDesc[meta.id] || meta.description;
        if (desc) {
            var d = el('p', 'gf-d-desc' + (expanded ? ' is-open' : ''), desc);
            d.addEventListener('click', function () { expanded = !expanded; d.classList.toggle('is-open', expanded); });
            body.appendChild(d);
        }
        var cast = links(meta, 'Cast'), dir = links(meta, 'Directors'), genres = links(meta, 'Genres');
        if (genres.length) body.appendChild(line('Gêneros', genres.slice(0, 4).map(ptGenre).join(', ')));
        if (cast.length) body.appendChild(line('Elenco', cast.slice(0, 4).join(', ')));
        if (dir.length) body.appendChild(line('Direção', dir.slice(0, 2).join(', ')));

        // ícones: Mais Tarde, Trailer, Visto
        var acts = el('div', 'gf-d-actions');
        acts.appendChild(action(meta.inLibrary ? 'check' : 'plus', meta.inLibrary ? 'Na lista' : 'Mais Tarde', function () {
            if (meta.inLibrary) dispatch({ action: 'Ctx', args: { action: 'RemoveFromLibrary', args: meta.id } });
            else dispatch({ action: 'Ctx', args: { action: 'AddToLibrary', args: meta } });
            lastSig = ''; setTimeout(tick, 300);
        }, meta.inLibrary));
        var tr = (meta.trailerStreams || [])[0];
        var trLink = tr && tr.deepLinks && (tr.deepLinks.player || (tr.deepLinks.externalPlayer && tr.deepLinks.externalPlayer.web));
        if (trLink) acts.appendChild(action('film', 'Trailer', function () {
            if (/^https?:/.test(trLink)) window.open(trLink, '_blank'); else window.gfGo(trLink);
        }));
        if (!isSeries || episodeView) {
            acts.appendChild(action(meta.watched ? 'eye-on' : 'eye', meta.watched ? 'Visto' : 'Marcar visto', function () {
                dispatch({ action: 'MetaDetails', args: { action: 'MarkAsWatched', args: !meta.watched } });
                lastSig = ''; setTimeout(tick, 300);
            }, meta.watched));
        }
        body.appendChild(acts);

        // título da seção de baixo
        if (isSeries && !episodeView) {
            var sec = el('div', 'gf-d-section');
            sec.appendChild(el('span', 'gf-d-section-tab', 'Episódios'));
            body.appendChild(sec);
            body.appendChild(episodesList(meta, target, lib));
        }
        box.appendChild(body);
        return box;
    }

    function urlVideoId() {
        var m = location.hash.match(/^#\/(?:detail|metadetails)\/[^/]+\/[^/?]+\/([^/?]+)/);
        return m ? decodeURIComponent(m[1]) : null;
    }

    // sinopse em português (gerada todo dia a partir do JustWatch, Brasil e Portugal)
    var ptDesc = {}, ptShard = {};
    function loadPt(id) {
        var k = (id.match(/(\d\d)$/) || [])[1];
        if (!k || ptShard[k]) return;
        ptShard[k] = 1;
        fetch('data/desc/' + k + '.json?v=' + DATA_VERSION).then(function (r) { return r.ok ? r.json() : {}; })
            .then(function (d) { Object.assign(ptDesc, d); lastSig = ''; tick(); }).catch(function () {});
    }
    var GENRES = { Action: 'Ação', Adventure: 'Aventura', Animation: 'Animação', Biography: 'Biografia', Comedy: 'Comédia', Crime: 'Crime',
        Documentary: 'Documentário', Drama: 'Drama', Family: 'Família', Fantasy: 'Fantasia', History: 'História', Horror: 'Terror',
        Music: 'Música', Musical: 'Musical', Mystery: 'Mistério', Romance: 'Romance', 'Sci-Fi': 'Ficção científica', Sport: 'Esporte',
        Thriller: 'Suspense', War: 'Guerra', Western: 'Faroeste', 'Reality-TV': 'Reality show', 'Talk-Show': 'Talk show', News: 'Notícias',
        'Game-Show': 'Game show', Short: 'Curta' };
    function ptGenre(g) { return GENRES[g] || g; }

    // ---------- janela de fontes ----------
    var sheet = null; // { id, autoplay, label, since }
    function platformName() {
        var ua = navigator.userAgent;
        return /Android/i.test(ua) ? 'android' : /iPhone|iPad|iPod/i.test(ua) ? 'ios' : /Mac/i.test(ua) ? 'macos' : /Win/i.test(ua) ? 'windows' : 'linux';
    }
    // abre a fonte do mesmo jeito que a lista do app abriria (player do app, VLC, etc.)
    function playStream(stream) {
        var dl = stream && stream.deepLinks;
        if (!dl) return;
        // app Android: o VLC do app é o player (som em qualquer fonte, legendas PT, Continuar assistindo)
        if (window.gfPlayNative && dl.player && /^https?:\/\//i.test(stream.url || '')) {
            closeSheet();
            if (window.gfPlayNative(dl.player, stream)) return;
        }
        var ext = dl.externalPlayer, href = null;
        if (ext) href = ext.web || (ext.openPlayer && (ext.openPlayer[platformName()] || ext.playlist)) || dl.player;
        else href = dl.player;
        if (!href) return;
        closeSheet();
        if (href.charAt(0) === '#') window.gfGo(href);
        else if (ext && href === ext.web) window.open(href, '_blank');
        else if (/^data:/.test(href)) { var a = el('a'); a.href = href; a.download = ext.fileName || 'video.m3u'; document.body.appendChild(a); a.click(); a.remove(); }
        else location.href = href;
    }
    function openSheet(videoId, autoplay, v) {
        var c = core();
        if (!c) return;
        Promise.resolve(c.getState('meta_details')).then(function (state) {
            var sel = state && state.selected;
            if (!sel || !sel.metaPath) return;
            var meta = state.metaItem && state.metaItem.content && state.metaItem.content.content;
            var vid = v || (meta && meta.videos || []).filter(function (x) { return x.id === videoId; })[0];
            sheet = { id: videoId, autoplay: !!autoplay, since: Date.now(), label: vid && vid.season != null ? epLabel(vid) + (realName(vid) ? ' · ' + realName(vid) : '') : '' };
            // carrega as fontes desse episódio sem trocar de página
            if (!sel.streamPath || sel.streamPath.id !== videoId) {
                c.dispatch({ action: 'Load', args: { model: 'MetaDetails', args: {
                    metaPath: sel.metaPath,
                    streamPath: { resource: 'stream', type: sel.metaPath.type, id: videoId, extra: [] },
                    guessStream: false
                } } }, 'meta_details');
            }
            renderSheet(null);
            setTimeout(tick, 60);
        });
    }
    function closeSheet() {
        sheet = null;
        var s0 = document.getElementById('gf-sheet');
        if (s0) { s0.classList.add('is-closing'); setTimeout(function () { s0.remove(); }, 180); }
    }
    var sheetSig = '';
    function renderSheet(state) {
        if (!sheet) return;
        var ready = state && state.selected && state.selected.streamPath && state.selected.streamPath.id === sheet.id;
        var streams = ready ? readyStreams(state) : [];
        var loading = !ready || streamsLoading(state);
        // "Assistir": toca a primeira fonte assim que todas responderem (ou depois de 6 s, se já houver alguma)
        if (sheet.autoplay && ready && streams.length && (!loading || Date.now() - sheet.since > 6000)) { playStream(streams[0]); return; }
        var sig = [sheet.id, sheet.autoplay, streams.length, loading].join('|');
        var root = document.getElementById('gf-sheet');
        if (root && sig === sheetSig) return;
        sheetSig = sig;
        var fresh = !root;
        if (!root) {
            root = el('div', 'gf-sheet');
            root.id = 'gf-sheet';
            root.addEventListener('click', function (e) { if (e.target === root) closeSheet(); });
            document.body.appendChild(root);
        }
        root.innerHTML = '';
        var card = el('div', 'gf-sheet-card' + (fresh ? '' : ' no-anim'));
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-label', 'Fontes');
        var head = el('div', 'gf-sheet-head');
        var tt = el('div', 'gf-sheet-titles');
        tt.appendChild(el('div', 'gf-sheet-title', sheet.autoplay ? 'Preparando…' : 'Escolher fonte'));
        if (sheet.label) tt.appendChild(el('div', 'gf-sheet-sub', sheet.label));
        head.appendChild(tt);
        var x = el('button', 'gf-sheet-close', '✕');
        x.type = 'button';
        x.setAttribute('aria-label', 'Fechar');
        x.addEventListener('click', closeSheet);
        head.appendChild(x);
        card.appendChild(head);
        var list = el('div', 'gf-sheet-list');
        if (streams.length && !sheet.autoplay) {
            streams.forEach(function (st) {
                var b = el('button', 'gf-sheet-item');
                b.type = 'button';
                var name = String(st.name || (st.addon && st.addon.manifest && st.addon.manifest.name) || 'Fonte').trim();
                b.appendChild(el('span', 'gf-sheet-q', name.replace(/\s*\n\s*/g, ' · ')));
                var d = String(st.description || st.title || '').trim();
                if (d) b.appendChild(el('span', 'gf-sheet-d', d));
                var heavy = !window.gfPlayNative && HEAVY_AUDIO.test(name + ' ' + d);
                var warn = isTorrent(st) ? 'Torrent: só toca com Real-Debrid no addon' : (heavy ? 'O áudio pode não tocar no celular' : '');
                if (warn) { b.appendChild(el('span', 'gf-sheet-warn', warn)); if (isTorrent(st)) b.classList.add('is-torrent'); }
                b.addEventListener('click', function () { playStream(st); });
                list.appendChild(b);
            });
        }
        if (loading) {
            var ld = el('div', 'gf-sheet-loading');
            ld.appendChild(el('span', 'gf-sheet-spin'));
            ld.appendChild(document.createTextNode(streams.length ? 'Procurando mais fontes…' : 'Procurando fontes…'));
            list.appendChild(ld);
        } else if (!streams.length) {
            var em = el('div', 'gf-sheet-empty');
            em.appendChild(el('div', null, 'Nenhuma fonte encontrada.'));
            em.appendChild(el('div', 'gf-sheet-empty-sub', 'As fontes vêm dos seus addons.'));
            var go = el('button', 'gf-sheet-addons', 'Abrir Addons');
            go.type = 'button';
            go.addEventListener('click', function () { closeSheet(); window.gfGo('#/addons'); });
            em.appendChild(go);
            list.appendChild(em);
        }
        card.appendChild(list);
        root.appendChild(card);
    }


    // nomes genéricos ("Episode 3", "Chapter 6", "Capítulo 2"...) não ajudam: nesses casos fica só "Episódio N"
    function realName(v) {
        var t = (v.title || v.name || '').trim();
        if (!t) return '';
        if (/^(episode|episódio|episodio|ep\.?|chapter|capítulo|capitulo|part|parte)\s*\d+$/i.test(t)) return '';
        if (/^\d+$/.test(t)) return '';
        return t;
    }
    function fmtDate(d) {
        var x = new Date(d);
        return isNaN(x) ? '' : x.toLocaleDateString('pt-BR', { day: 'numeric', month: 'short', year: 'numeric' });
    }

    var seasonPick = {}; // temporada escolhida em cada série
    function episodesList(meta, target, lib) {
        var wrap = el('div', 'gf-d-episodes');
        var bySeason = {};
        (meta.videos || []).forEach(function (v) {
            var s = v.season == null ? 1 : v.season;
            (bySeason[s] = bySeason[s] || []).push(v);
        });
        var seasons = Object.keys(bySeason).map(Number).sort(function (a, b) {
            if (a === 0) return 1; if (b === 0) return -1; return a - b; // especiais no fim
        });
        if (!seasons.length) { wrap.appendChild(el('p', 'gf-d-line', 'Nenhum episódio encontrado.')); return wrap; }
        var cur = seasonPick[meta.id];
        if (cur == null || !bySeason[cur]) cur = target && bySeason[target.season] ? target.season : seasons[0];

        if (seasons.length > 1) {
            var chips = el('div', 'gf-d-seasons');
            seasons.forEach(function (n) {
                var c = el('button', 'gf-d-season' + (n === cur ? ' is-active' : ''), n === 0 ? 'Especiais' : 'Temporada ' + n);
                c.type = 'button';
                c.addEventListener('click', function () {
                    seasonPick[meta.id] = n;
                    lastSig = ''; tick();
                });
                chips.appendChild(c);
            });
            wrap.appendChild(chips);
            requestAnimationFrame(function () {
                var a = chips.querySelector('.is-active');
                if (a) chips.scrollLeft = a.offsetLeft - 16;
            });
        }

        var list = bySeason[cur].slice().sort(function (a, b) { return (a.episode || 0) - (b.episode || 0); });
        var st = lib && lib.state;
        list.forEach(function (v, i) {
            var n = v.episode || i + 1;
            var row = el('button', 'gf-d-ep' + (target && v.id === target.id ? ' is-next' : ''));
            row.type = 'button';
            var th = el('span', 'gf-d-ep-thumb');
            if (v.thumbnail) {
                var im = el('img');
                im.alt = ''; im.loading = 'lazy'; im.decoding = 'async';
                im.onerror = function () { im.remove(); };
                im.src = v.thumbnail;
                th.appendChild(im);
            }
            th.appendChild(el('span', 'gf-d-ep-play', '▶'));
            if (st && st.video_id === v.id && st.duration > 0 && st.timeOffset > 0) {
                var pb = el('span', 'gf-d-ep-progress'); var pf = el('span');
                pf.style.width = Math.min(100, st.timeOffset / st.duration * 100).toFixed(1) + '%';
                pb.appendChild(pf); th.appendChild(pb);
            }
            row.appendChild(th);
            var tx = el('span', 'gf-d-ep-text');
            tx.appendChild(el('span', 'gf-d-ep-num', (cur === 0 ? 'Especial ' : 'Episódio ') + n));
            var nm = realName(v);
            if (nm) tx.appendChild(el('span', 'gf-d-ep-name', nm));
            var dt = v.released ? fmtDate(v.released) : '';
            if (dt) tx.appendChild(el('span', 'gf-d-ep-date', dt));
            if (v.watched) tx.appendChild(el('span', 'gf-d-ep-seen', '✓ Visto'));
            row.appendChild(tx);
            row.addEventListener('click', function () { openSheet(v.id, false, v); });
            wrap.appendChild(row);
        });
        return wrap;
    }

    function skeleton() {
        var box = el('div', 'gf-detail is-loading');
        box.id = 'gf-detail';
        box.setAttribute('data-id', location.hash);
        box.appendChild(el('div', 'gf-d-hero'));
        var body = el('div', 'gf-d-body');
        ['t', 'i', 'p', 'c', 'd'].forEach(function (k) { body.appendChild(el('div', 'gf-d-sk gf-d-sk-' + k)); });
        box.appendChild(body);
        return box;
    }

    function line(label, text) {
        var p = el('p', 'gf-d-line');
        p.appendChild(el('span', null, label + ': '));
        p.appendChild(document.createTextNode(text));
        return p;
    }

    var ICONS = {
        plus: '<path d="M12 5v14M5 12h14"/>',
        check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
        film: '<rect x="3.5" y="5" width="17" height="14" rx="2.5"/><path d="M10 9.2v5.6l4.8-2.8z" fill="currentColor" stroke="none"/>',
        eye: '<path d="M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
        'eye-on': '<path d="M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3" fill="currentColor"/>'
    };
    function action(icon, label, onClick, on) {
        var b = el('button', 'gf-d-action' + (on ? ' is-on' : ''));
        b.type = 'button';
        b.innerHTML = '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + ICONS[icon] + '</svg>';
        b.appendChild(el('span', null, label));
        b.addEventListener('click', onClick);
        return b;
    }

    // ---------- ciclo ----------
    // pergunta ao Stremio a cada 0,4 s só enquanto algo está carregando (página nova, janela de fontes, clique);
    // depois, a cada 2 s — ler a página inteira do título toda hora pesa no Fire TV e em celulares simples
    var busyUntil = 0, slowCount = 0;
    function slowTick() {
        if (sheet || Date.now() < busyUntil || ++slowCount % 5 === 0) tick();
    }
    document.addEventListener('click', function () { busyUntil = Date.now() + 5000; }, true);
    document.addEventListener('keydown', function (e) { if (e.key === 'Enter') busyUntil = Date.now() + 5000; }, true);

    function tick() {
        var html = document.documentElement;
        if (!onDetail()) {
            html.removeAttribute('data-gf-detail');
            html.removeAttribute('data-gf-detail-view');
            var old = document.getElementById('gf-detail'); if (old) old.remove();
            lastSig = ''; expanded = false;
            if (sheet || document.getElementById('gf-sheet')) { sheet = null; var s1 = document.getElementById('gf-sheet'); if (s1) s1.remove(); }
            return;
        }
        var c = core();
        var content = document.querySelector('[class*="metadetails-content"]');
        if (!c || !content) return;
        Promise.resolve(c.getState('meta_details')).then(function (state) {
            if (!state || !onDetail()) return;
            renderSheet(state);
            var meta = state.metaItem && state.metaItem.content && state.metaItem.content.type === 'Ready' ? state.metaItem.content.content : null;
            if (!meta) {
                var r0 = document.getElementById('gf-detail');
                if (!r0 || r0.parentNode !== content || r0.getAttribute('data-id') !== location.hash) {
                    var sk = skeleton();
                    if (r0) r0.remove();
                    content.insertBefore(sk, content.firstChild);
                    lastSig = '';
                }
                return;
            }
            var lib = state.libraryItem && state.libraryItem.state;
            var sig = [location.hash, meta.id, meta.inLibrary, meta.watched, readyStreams(state).length, streamsLoading(state),
                lib && lib.video_id, lib && Math.round(lib.timeOffset / 10000), (servicesFor[meta.id] || []).length, !!servicesDone[meta.id], (meta.videos || []).length, (meta.videos || []).filter(function (v) { return v.watched; }).length, lib && lib.timesWatched, seasonPick[meta.id], !!ptDesc[meta.id]].join('|');
            var root = document.getElementById('gf-detail');
            if (sig === lastSig && root && root.parentNode === content) return;
            var box = build(state);
            if (!box) return;
            lastSig = sig;
            var fresh = !root || root.getAttribute('data-id') !== location.hash;
            box.id = 'gf-detail';
            box.setAttribute('data-id', location.hash);
            if (!fresh) box.classList.add('no-anim');
            if (root && root.parentNode) root.replaceWith(box); else content.insertBefore(box, content.firstChild);
            html.setAttribute('data-gf-detail', meta.type === 'series' ? 'series' : 'movie');
            var uv = urlVideoId();
            html.setAttribute('data-gf-detail-view', (meta.type === 'series' && !(uv && uv !== meta.id)) ? 'episodes' : 'sources');
        }).catch(function () {});
    }

    function update() {
        var on = onDetail();
        if (on && !timer) { busyUntil = Date.now() + 8000; tick(); timer = setInterval(slowTick, 400); }
        if (!on && timer) { clearInterval(timer); timer = null; tick(); }
    }
    var queued = false;
    new MutationObserver(function () {
        if (queued || !onDetail()) return;
        queued = true;
        requestAnimationFrame(function () {
            queued = false;
            var content = document.querySelector('[class*="metadetails-content"]');
            var root = document.getElementById('gf-detail');
            if (content && (!root || root.parentNode !== content || root.getAttribute('data-id') !== location.hash)) { lastSig = ''; tick(); }
            update();
        });
    }).observe(document.documentElement, { childList: true, subtree: true });
    // mudanças de página feitas pelo app sem aviso (pushState) também
    ['pushState', 'replaceState'].forEach(function (fn) {
        var orig = history[fn];
        history[fn] = function () { var r = orig.apply(this, arguments); setTimeout(update, 0); return r; };
    });
    window.addEventListener('hashchange', function () { busyUntil = Date.now() + 8000; expanded = false; if (sheet) { sheet = null; var s2 = document.getElementById('gf-sheet'); if (s2) s2.remove(); } update(); });
    window.addEventListener('popstate', update);
    setInterval(update, 1000);
    update();
})();
