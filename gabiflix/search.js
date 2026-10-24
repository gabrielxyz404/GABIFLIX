// GabiFlix: resultados da busca numa grade única (3 por linha no celular).
// Junta os resultados de todos os catálogos (filmes, séries...) sem repetir títulos.
(function () {
    var POLL_MS = 400;
    var timer = null;
    var lastQuery = null;
    var lastSig = '';
    var rangeAsked = '';

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text) e.textContent = text;
        return e;
    }

    function core() {
        var c = window.core && (window.core.transport || window.core);
        return c && typeof c.getState === 'function' ? c : null;
    }

    function queryFromHash() {
        var m = location.hash.match(/^#\/search(?:\?(.*))?$/);
        if (!m) return null;
        var params = new URLSearchParams(m[1] || '');
        var q = params.get('search') || params.get('query') || '';
        return q.trim() ? q.trim() : '';
    }

    function container() {
        return document.querySelector('[class*="search-content-"]');
    }

    function mountRoot() {
        var c = container();
        if (!c) return null;
        var root = document.getElementById('gf-search');
        if (!root || root.parentNode !== c) {
            if (root) root.remove();
            root = el('div');
            root.id = 'gf-search';
            c.insertBefore(root, c.firstChild);
        }
        return root;
    }

    // O app só guarda os 10 primeiros de cada catálogo; buscamos a lista completa direto no addon
    var full = {}; // url -> { items } | 'loading' | 'error'
    var SKIP_TYPES = { channel: true, tv: true }; // canais do YouTube e TV ao vivo não entram na grade

    function fullUrl(cat, q) {
        var dl = cat.deepLinks && cat.deepLinks.discover;
        var m = dl && dl.match(/^#\/discover\/([^/]+)\//);
        if (!m) return null;
        var base = decodeURIComponent(m[1]).replace(/manifest\.json$/, '');
        return base + 'catalog/' + encodeURIComponent(cat.type) + '/' + encodeURIComponent(cat.id) + '/search=' + encodeURIComponent(q) + '.json';
    }

    function loadFull(url) {
        if (full[url]) return;
        full[url] = 'loading';
        fetch(url).then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
            .then(function (d) { full[url] = { items: (d && d.metas) || [] }; lastSig = ''; settled = null; tick(); })
            .catch(function () { full[url] = 'error'; settled = null; });
    }

    // intercala os catálogos (1º filme, 1ª série, 2º filme...) para os mais relevantes ficarem no topo
    function collect(state, q) {
        var lists = [], loading = false;
        (state.catalogs || []).forEach(function (cat) {
            var content = cat && cat.content;
            if (!content || SKIP_TYPES[cat.type]) return;
            if (content.type === 'Loading') loading = true;
            if (content.type === 'Ready' && Array.isArray(content.content)) {
                var list = content.content;
                var url = list.length >= 10 ? fullUrl(cat, q) : null;
                if (url) {
                    loadFull(url);
                    var f = full[url];
                    if (f && f.items && f.items.length > list.length) list = f.items.map(function (m) {
                        return Object.assign({ type: cat.type }, m);
                    });
                }
                lists.push(list);
            }
        });
        var seen = {}, out = [];
        var max = Math.max.apply(null, [0].concat(lists.map(function (l) { return l.length; })));
        for (var i = 0; i < max; i++) {
            lists.forEach(function (l) {
                var m = l[i];
                if (m && m.id && !seen[m.id]) { seen[m.id] = true; out.push(m); }
            });
        }
        return { items: out, loading: loading, total: (state.catalogs || []).length };
    }

    function linkFor(m) {
        var dl = m.deepLinks || {};
        var href = dl.metaDetailsStreams || dl.metaDetailsVideos;
        if (href) return href.charAt(0) === '#' ? href : '#' + href;
        return '#/detail/' + encodeURIComponent(m.type) + '/' + encodeURIComponent(m.id);
    }

    function card(m) {
        var a = el('a', 'gf-s-item');
        a.href = linkFor(m);
        a.setAttribute('aria-label', m.name || 'Título');
        var box = el('span', 'gf-s-poster');
        var img = el('img');
        img.alt = '';
        img.loading = 'lazy';
        img.decoding = 'async';
        img.onload = function () { box.classList.add('img-ready'); };
        img.onerror = function () { img.remove(); box.classList.add('no-img'); };
        if (m.poster) img.src = m.poster; else box.classList.add('no-img');
        box.appendChild(img);
        box.appendChild(el('span', 'gf-s-fallback', m.name || ''));
        a.appendChild(box);
        a.appendChild(el('span', 'gf-s-name', m.name || ''));
        a.addEventListener('click', function (e) {
            if (!window.gfGo || e.ctrlKey || e.metaKey) return;
            e.preventDefault();
            window.gfGo(a.getAttribute('href'));
        });
        return a;
    }

    // Filtro: null = tudo, 'movie' = só filmes, 'series' = só séries (tocar de novo volta para tudo)
    var filter = null;
    try { filter = sessionStorage.getItem('gf-search-filter') || null; } catch (e) {}
    var lastData = null;

    function chips(root) {
        var bar = root.querySelector('.gf-s-filters');
        if (bar) return bar;
        bar = el('div', 'gf-s-filters');
        bar.setAttribute('role', 'group');
        bar.setAttribute('aria-label', 'Filtrar resultados');
        [['movie', 'Filmes'], ['series', 'Séries']].forEach(function (f) {
            var b = el('button', 'gf-s-chip', f[1]);
            b.type = 'button';
            b.setAttribute('data-filter', f[0]);
            b.addEventListener('click', function () {
                filter = filter === f[0] ? null : f[0];
                try { if (filter) sessionStorage.setItem('gf-search-filter', filter); else sessionStorage.removeItem('gf-search-filter'); } catch (e) {}
                lastSig = '';
                var sc = container(); if (sc) sc.scrollTop = 0;
                if (lastData) render(root, lastData);
            });
            bar.appendChild(b);
        });
        root.appendChild(bar);
        return bar;
    }

    function render(root, data) {
        lastData = data;
        var bar = chips(root);
        [].forEach.call(bar.children, function (b) {
            var on = b.getAttribute('data-filter') === filter;
            b.classList.toggle('is-active', on);
            b.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
        var items = filter ? data.items.filter(function (m) { return m.type === filter; }) : data.items;
        var sig = (filter || '*') + '|' + items.map(function (m) { return m.id; }).join(',') + '|' + (data.loading && !items.length ? 'L' : '');
        var body = root.querySelector('.gf-s-body');
        if (sig === lastSig && body) return;
        lastSig = sig;
        if (!body) { body = el('div', 'gf-s-body'); root.appendChild(body); }
        body.innerHTML = '';
        if (!items.length) {
            if (data.loading || !data.total) {
                var grid = el('div', 'gf-s-grid');
                for (var i = 0; i < 9; i++) grid.appendChild(el('div', 'gf-s-skel'));
                body.appendChild(grid);
            } else {
                body.appendChild(el('div', 'gf-s-empty', filter === 'movie' ? 'Nenhum filme encontrado.' : filter === 'series' ? 'Nenhuma série encontrada.' : 'Nenhum resultado encontrado.'));
            }
            return;
        }
        var g = el('div', 'gf-s-grid');
        var frag = document.createDocumentFragment();
        items.forEach(function (m) { frag.appendChild(card(m)); });
        g.appendChild(frag);
        body.appendChild(g);
    }

    // Tela de busca vazia: pesquisas recentes em botões de vidro (ou uma dica simples)
    var idleSig = null;
    function renderIdle(root, c) {
        Promise.resolve(c.getState('ctx')).then(function (ctx) {
            var items = ((ctx && ctx.searchHistory) || []).filter(function (h) { return h && h.query; }).slice(0, 10);
            var sig = items.map(function (h) { return h.query; }).join('\u0001');
            if (sig === idleSig && root.firstChild) return;
            idleSig = sig;
            root.innerHTML = '';
            var box = el('div', 'gf-s-idle');
            if (!items.length) {
                box.appendChild(el('div', 'gf-s-hint', 'Pesquise filmes e séries'));
                root.appendChild(box);
                return;
            }
            var head = el('div', 'gf-s-idle-head');
            head.appendChild(el('h2', 'gf-s-idle-title', 'Pesquisas recentes'));
            var clear = el('button', 'gf-s-clear', 'Limpar');
            clear.type = 'button';
            clear.addEventListener('click', function () {
                try { c.dispatch({ action: 'Ctx', args: { action: 'ClearSearchHistory' } }); } catch (e) {}
                idleSig = null;
                setTimeout(function () { renderIdle(root, c); }, 300);
            });
            head.appendChild(clear);
            box.appendChild(head);
            var list = el('div', 'gf-s-recent');
            items.forEach(function (h) {
                var a = el('a', 'gf-s-chip gf-s-recent-item', h.query);
                a.href = (h.deepLinks && h.deepLinks.search) || ('#/search?search=' + encodeURIComponent(h.query));
                a.addEventListener('click', function (e) { if (window.gfGo) { e.preventDefault(); window.gfGo(a.getAttribute('href')); } });
                list.appendChild(a);
            });
            box.appendChild(list);
            root.appendChild(box);
        }).catch(function () {});
    }

    // depois que os resultados chegam, para de perguntar ao app até a busca mudar
    var settled = null;

    function tick() {
        var q = queryFromHash();
        var html = document.documentElement;
        if (q === null) {
            html.removeAttribute('data-gf-search');
            var old = document.getElementById('gf-search');
            if (old) old.remove();
            lastSig = ''; idleSig = null; settled = null; lastQuery = null;
            return;
        }
        var c = core();
        var root = mountRoot();
        if (!c || !root) return;
        if (q === '') {
            html.setAttribute('data-gf-search', 'idle');
            if (lastQuery !== '') { lastQuery = ''; lastSig = ''; idleSig = null; root.innerHTML = ''; }
            if (settled !== '') { settled = ''; renderIdle(root, c); }
            return;
        }
        html.setAttribute('data-gf-search', 'results');
        if (q !== lastQuery) {
            lastQuery = q; lastSig = ''; rangeAsked = ''; full = {}; settled = null; idleSig = null;
            root.innerHTML = '';
            var sc = container(); if (sc) sc.scrollTop = 0;
        }
        if (settled === q && root.firstChild) return;
        Promise.resolve(c.getState('search')).then(function (state) {
            if (!state || queryFromHash() !== q) return;
            // pede para carregar todos os catálogos de uma vez (o app normal só carrega os visíveis)
            var n = (state.catalogs || []).length;
            var key = q + '#' + n;
            if (n && rangeAsked !== key && typeof c.dispatch === 'function') {
                rangeAsked = key;
                try { c.dispatch({ action: 'CatalogsWithExtra', args: { action: 'LoadRange', args: { start: 0, end: n - 1 } } }, 'search'); } catch (e) {}
            }
            var data = collect(state, q);
            render(root, data);
            var pending = Object.keys(full).some(function (k) { return full[k] === 'loading'; });
            if (n && !data.loading && !pending) settled = q;
        }).catch(function () {});
    }

    function update() {
        var onSearch = /^#\/search/.test(location.hash);
        if (onSearch && !timer) { tick(); timer = setInterval(tick, POLL_MS); }
        if (!onSearch && timer) { clearInterval(timer); timer = null; tick(); }
    }

    window.addEventListener('hashchange', update);
    window.addEventListener('popstate', update);
    setInterval(update, 1000); // o app às vezes troca de tela sem avisar
    update();
})();
