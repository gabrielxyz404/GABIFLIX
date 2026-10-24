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
            .then(function (d) { full[url] = { items: (d && d.metas) || [] }; lastSig = ''; tick(); })
            .catch(function () { full[url] = 'error'; });
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
        return a;
    }

    function render(root, data) {
        var sig = data.items.map(function (m) { return m.id; }).join(',') + '|' + (data.loading && !data.items.length ? 'L' : '');
        if (sig === lastSig && root.firstChild) return;
        lastSig = sig;
        root.innerHTML = '';
        if (!data.items.length) {
            if (data.loading || !data.total) {
                var grid = el('div', 'gf-s-grid');
                for (var i = 0; i < 9; i++) grid.appendChild(el('div', 'gf-s-skel'));
                root.appendChild(grid);
            } else {
                root.appendChild(el('div', 'gf-s-empty', 'Nenhum resultado encontrado.'));
            }
            return;
        }
        var g = el('div', 'gf-s-grid');
        var frag = document.createDocumentFragment();
        data.items.forEach(function (m) { frag.appendChild(card(m)); });
        g.appendChild(frag);
        root.appendChild(g);
    }

    function tick() {
        var q = queryFromHash();
        var html = document.documentElement;
        if (!q) {
            html.removeAttribute('data-gf-search');
            var old = document.getElementById('gf-search');
            if (old) old.remove();
            lastSig = '';
            return;
        }
        html.setAttribute('data-gf-search', 'results');
        var c = core();
        var root = mountRoot();
        if (!c || !root) return;
        if (q !== lastQuery) {
            lastQuery = q; lastSig = ''; rangeAsked = ''; full = {};
            var sc = container(); if (sc) sc.scrollTop = 0;
        }
        Promise.resolve(c.getState('search')).then(function (state) {
            if (!state || queryFromHash() !== q) return;
            // pede para carregar todos os catálogos de uma vez (o app normal só carrega os visíveis)
            var n = (state.catalogs || []).length;
            var key = q + '#' + n;
            if (n && rangeAsked !== key && typeof c.dispatch === 'function') {
                rangeAsked = key;
                try { c.dispatch({ action: 'CatalogsWithExtra', args: { action: 'LoadRange', args: { start: 0, end: n - 1 } } }, 'search'); } catch (e) {}
            }
            render(root, collect(state, q));
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
