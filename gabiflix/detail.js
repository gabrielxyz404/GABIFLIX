// GabiFlix: página de filme/série no estilo dos apps de streaming.
// Monta um cabeçalho próprio (imagem, título, informações, Assistir, Mais Tarde, Trailer, Visto)
// no lugar do cabeçalho do Stremio. A lista de fontes / episódios do Stremio continua embaixo.
(function () {
    var SERVICES = [
        ['netflix', 'Netflix', '#e50914'], ['disney', 'Disney+', '#3aa3ff'], ['prime', 'Prime Video', '#1ba8e0'],
        ['hbo', 'HBO Max', '#a36bff'], ['appletv', 'Apple TV+', '#f5f5f7'], ['paramount', 'Paramount+', '#2f7dff'],
        ['globoplay', 'Globoplay', '#c8d23a'], ['clarovideo', 'Claro Video', '#ff2a2a']
    ];
    var DATA_VERSION = Math.floor(Date.now() / 3600000);
    var idsCache = null, timer = null, lastSig = '', expanded = false;

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
    var servicesFor = {};
    function whereIs(id) {
        if (servicesFor[id]) return;
        servicesFor[id] = [];
        serviceIds().then(function (sets) {
            servicesFor[id] = SERVICES.filter(function (s, i) { return sets[i][id]; });
            lastSig = ''; tick();
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
        return out;
    }
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
        var streamPath = sel.streamPath;
        var isSeries = meta.type === 'series' || (meta.videos && meta.videos.length > 1);
        var lib = state.libraryItem;
        var video = null;
        if (streamPath && meta.videos) video = meta.videos.filter(function (v) { return v.id === streamPath.id; })[0] || null;
        var episodeView = isSeries && !!video;
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
            if (!streams.length) label = streamsLoading(state) ? 'Procurando fontes…' : 'Nenhuma fonte encontrada';
            play.disabled = !streams.length;
            play.addEventListener('click', playFirst);
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
            play.addEventListener('click', function () { openEpisode(target); });
            body.appendChild(play);
        }

        // segundo botão: escolher a fonte
        var choose = el('button', 'gf-d-choose');
        choose.type = 'button';
        if (isSeries && !episodeView) {
            choose.textContent = target ? 'Escolher fonte · ' + epLabel(target) : 'Escolher fonte';
            choose.disabled = !target;
            choose.addEventListener('click', function () { openEpisode(target); });
        } else {
            choose.textContent = 'Escolher fonte';
            choose.disabled = !streams.length;
            choose.addEventListener('click', function () {
                var list = document.querySelector('[class*="metadetails-content"] [class*="streams-list"]');
                if (list) list.scrollIntoView({ behavior: 'smooth', block: 'start' });
            });
        }
        body.appendChild(choose);

        // resumo (3 linhas, toque para ver tudo)
        var desc = (episodeView && video.overview) || meta.description;
        if (desc) {
            var d = el('p', 'gf-d-desc' + (expanded ? ' is-open' : ''), desc);
            d.addEventListener('click', function () { expanded = !expanded; d.classList.toggle('is-open', expanded); });
            body.appendChild(d);
        }
        var cast = links(meta, 'Cast'), dir = links(meta, 'Directors'), genres = links(meta, 'Genres');
        if (genres.length) body.appendChild(line('Gêneros', genres.slice(0, 4).join(', ')));
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
        var sec = el('div', 'gf-d-section');
        sec.appendChild(el('span', 'gf-d-section-tab', (isSeries && !episodeView) ? 'Episódios' : 'Fontes'));
        if (!isSeries || episodeView) sec.appendChild(el('span', 'gf-d-section-hint', streams.length ? streams.length + (streams.length > 1 ? ' opções' : ' opção') : ''));
        body.appendChild(sec);
        if (isSeries && !episodeView) body.appendChild(episodesList(meta, target, lib));
        box.appendChild(body);
        return box;
    }

    function openEpisode(v) {
        var dl = v && v.deepLinks && (v.deepLinks.metaDetailsStreams || v.deepLinks.player);
        if (dl) window.gfGo(dl);
        else if (v) window.gfGo('#/detail/series/' + encodeURIComponent(v.id.split(':')[0]) + '/' + encodeURIComponent(v.id));
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
            row.addEventListener('click', function () { openEpisode(v); });
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

    // toca a primeira fonte da lista (a mesma que aparece no topo de "Fontes")
    function playFirst() {
        var first = document.querySelector('[class*="metadetails-content"] [class*="streams-container"] a[class*="stream-container"]');
        if (first) first.click();
        else {
            var list = document.querySelector('[class*="metadetails-content"] [class*="streams-list"]');
            if (list) list.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }

    // ---------- ciclo ----------
    function tick() {
        var html = document.documentElement;
        if (!onDetail()) {
            html.removeAttribute('data-gf-detail');
            html.removeAttribute('data-gf-detail-view');
            var old = document.getElementById('gf-detail'); if (old) old.remove();
            lastSig = ''; expanded = false;
            return;
        }
        var c = core();
        var content = document.querySelector('[class*="metadetails-content"]');
        if (!c || !content) return;
        Promise.resolve(c.getState('meta_details')).then(function (state) {
            if (!state || !onDetail()) return;
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
                lib && lib.video_id, lib && Math.round(lib.timeOffset / 10000), (servicesFor[meta.id] || []).length, (meta.videos || []).length, seasonPick[meta.id]].join('|');
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
            var sp = state.selected && state.selected.streamPath;
            html.setAttribute('data-gf-detail', meta.type === 'series' ? 'series' : 'movie');
            html.setAttribute('data-gf-detail-view', (meta.type === 'series' && !(sp && sp.id !== meta.id)) ? 'episodes' : 'sources');
        }).catch(function () {});
    }

    function update() {
        var on = onDetail();
        if (on && !timer) { tick(); timer = setInterval(tick, 400); }
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
    window.addEventListener('hashchange', function () { expanded = false; update(); });
    window.addEventListener('popstate', update);
    setInterval(update, 1000);
    update();
})();
