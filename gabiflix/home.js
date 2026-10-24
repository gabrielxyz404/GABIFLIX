// GabiFlix: tela inicial com botões de serviços de streaming.
// Substitui os catálogos padrão da tela inicial.
(function () {
    // color: cor do nome no botão, inspirada na cor de cada serviço
    // bg: degradê do fundo dentro do catálogo [início, fim]; null mantém o tema padrão
    var SERVICES = [
        { id: 'netflix', bg: ['#000000', '#4a0710'], cat: 'nfx', name: 'Netflix', color: '#e50914' },
        { id: 'disney', bg: ['#000000', '#0a1d52'], cat: 'dnp', name: 'Disney+', color: '#3aa3ff' },
        { id: 'prime', bg: ['#000000', '#0b3b55'], cat: 'amp', name: 'Prime Video', color: '#1ba8e0' },
        { id: 'hbo', bg: ['#000000', '#2c0b4f'], cat: 'hbm', name: 'HBO Max', color: '#a36bff' },
        { id: 'appletv', bg: ['#000000', '#2d2d31'], cat: 'atp', name: 'Apple TV+', color: '#f5f5f7' },
        { id: 'paramount', bg: ['#000000', '#0a1d52'], cat: 'pmp', name: 'Paramount+', color: '#2f7dff' },
        { id: 'globoplay', bg: ['#0b2a12', '#4d3d05'], cat: 'gop', name: 'Globoplay', color: '#c8d23a', grad: ['#f5c518', '#3ecf6b'] },
        { id: 'clarovideo', bg: ['#000000', '#4f2205'], cat: 'clv', name: 'Claro Video', color: '#ff2a2a' },
        // Filmes e séries fora dos streamings: cinema, lançamentos e clássicos
        { id: 'cinemateca', bg: ['#0b0906', '#3b3020'], name: 'Cinemateca', color: '#e9c46a', wide: true, hint: 'Cinema, clássicos e o que não está nos streamings' }
    ];

    var current = null; // serviço aberto, ou null para a grade
    var LOGO_VERSION = 1; // aumente para forçar o celular a recarregar os logos

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text) e.textContent = text;
        return e;
    }

    function renderGrid(root) {
        root.innerHTML = '';
        root.appendChild(el('h1', 'gf-title', 'Escolha um serviço'));
        var grid = el('div', 'gf-grid');
        SERVICES.forEach(function (s) {
            var b = el('button', 'gf-card' + (s.wide ? ' is-wide' : ''));
            b.type = 'button';
            b.setAttribute('data-service', s.id);
            b.style.setProperty('--gf-brand', s.color);
            if (s.grad) b.style.setProperty('--gf-grad', 'linear-gradient(90deg, ' + s.grad.join(', ') + ')');
            // Logo opcional: coloque gabiflix/logos/<id>.png no repositório.
            var logo = el('img', 'gf-card-logo');
            logo.alt = '';
            logo.decoding = 'async';
            logo.onload = function () { b.classList.add('has-logo'); };
            logo.onerror = function () { logo.remove(); };
            logo.src = 'gabiflix/logos/' + s.id + '.png?v=' + LOGO_VERSION;
            b.appendChild(logo);
            b.appendChild(el('span', 'gf-card-name' + (s.grad ? ' gf-grad-text' : ''), s.name));
            b.appendChild(el('span', 'gf-card-hint', s.hint || 'Ver catálogo'));
            b.addEventListener('click', function () { open(s.id, true); });
            grid.appendChild(b);
        });
        root.appendChild(grid);
    }

    // Abas de cada serviço (categorias típicas de cada app)
    var TABS = {
        netflix:    ['Início', 'Séries', 'Filmes', 'Bombando'],
        disney:     ['Início', 'Filmes', 'Séries', 'Disney', 'Pixar', 'Marvel', 'Star Wars', 'National Geographic'],
        prime:      ['Início', 'Filmes', 'Séries'],
        hbo:        ['Início', 'Séries', 'Filmes'],
        appletv:    ['Início', 'Filmes', 'Séries'],
        paramount:  ['Início', 'Filmes', 'Séries'],
        globoplay:  ['Início', 'Séries', 'Filmes'],
        clarovideo: ['Início', 'Filmes', 'Séries'],
        cinemateca: ['Início', 'Nos cinemas', 'Clássicos', 'Filmes', 'Séries']
    };

    var currentTab = 'Início';

    function backButton() {
        var back = el('button', 'gf-back');
        back.type = 'button';
        back.setAttribute('aria-label', 'Voltar');
        back.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        back.addEventListener('click', function () { history.back(); });
        return back;
    }

    // ---------- Dados dos catálogos ----------
    // Principal: data/*.json, gerado todo dia pelo GitHub (tools/build-catalogs.mjs, Brasil + Portugal).
    // Reserva: addon aberto "Streaming Catalogs" (github.com/rleroi/Stremio-Streaming-Catalogs-Addon).
    var CATALOG_URL = 'https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club';
    var DATA_VERSION = Math.floor(Date.now() / 3600000); // renova a cada hora
    var cache = {};

    function getJSON(url) {
        return fetch(url).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        }).then(function (d) { return (d && d.metas) || []; });
    }

    // sec: { file } para dados próprios e/ou { type, id } para o addon
    function loadCatalog(sec) {
        var key = (sec.file || '') + '|' + (sec.type || '') + '/' + (sec.id || '');
        if (!cache[key]) {
            var addon = function () {
                if (!sec.id) return [];
                return getJSON(CATALOG_URL + '/catalog/' + sec.type + '/' + encodeURIComponent(sec.id) + '.json');
            };
            var p = sec.file
                ? getJSON('data/' + sec.file + '.json?v=' + DATA_VERSION)
                    .then(function (m) { return m.length ? m : addon(); }, addon)
                : addon();
            cache[key] = p.catch(function (e) { delete cache[key]; throw e; });
        }
        return cache[key];
    }

    // O que cada aba mostra: lista de fileiras
    function sectionsFor(s, tab) {
        if (s.id === 'cinemateca') {
            switch (tab) {
                case 'Início': return [
                    { title: 'Nos cinemas e lançamentos', file: 'cine-cinemas' },
                    { title: 'Clássicos', file: 'cine-classicos' },
                    { title: 'Filmes fora dos streamings', file: 'cine-movie' },
                    { title: 'Séries fora dos streamings', file: 'cine-series' }
                ];
                case 'Nos cinemas': return [{ title: 'Nos cinemas e lançamentos', file: 'cine-cinemas', grid: true }];
                case 'Clássicos': return [{ title: 'Clássicos', file: 'cine-classicos', grid: true }];
                case 'Filmes': return [{ title: 'Filmes fora dos streamings', file: 'cine-movie', grid: true }];
                case 'Séries': return [{ title: 'Séries fora dos streamings', file: 'cine-series', grid: true }];
                default: return [{ title: tab, soon: true }];
            }
        }
        // Disney+: abas por marca (data/disney-<marca>.json)
        var DISNEY_BRANDS = [
            ['Disney', 'disney'], ['Pixar', 'pixar'], ['Marvel', 'marvel'],
            ['Star Wars', 'starwars'], ['National Geographic', 'natgeo']
        ];
        if (s.id === 'disney') {
            for (var i = 0; i < DISNEY_BRANDS.length; i++) {
                if (tab === DISNEY_BRANDS[i][0]) {
                    var f = 'disney-' + DISNEY_BRANDS[i][1];
                    var secs = [
                        { title: 'Filmes', file: f + '-movie', grid: true },
                        { title: 'Séries', file: f + '-series', grid: true }
                    ];
                    if (tab === 'Marvel') secs.unshift({ title: 'Ordem cronológica', file: 'disney-marvel-cronologia', numbered: true });
                    return secs;
                }
            }
            if (tab === 'Início') return [
                { title: 'Filmes em alta', file: 'disney-movie', type: 'movie', id: s.cat },
                { title: 'Séries em alta', file: 'disney-series', type: 'series', id: s.cat }
            ].concat(DISNEY_BRANDS.map(function (b) { return { title: b[0], file: 'disney-' + b[1] }; }));
        }
        var c = s.cat;
        switch (tab) {
            case 'Início': return [
                { title: 'Filmes em alta', file: s.id + '-movie', type: 'movie', id: c },
                { title: 'Séries em alta', file: s.id + '-series', type: 'series', id: c }
            ];
            case 'Filmes': return [{ title: 'Todos os filmes', file: s.id + '-movie', type: 'movie', id: c, grid: true }];
            case 'Séries': return [{ title: 'Todas as séries', file: s.id + '-series', type: 'series', id: c, grid: true }];
            case 'Bombando': return [
                { title: 'Top 10 filmes hoje no Brasil', file: 'netflix-top10-br-movie', type: 'movie', id: 'netflix-top10-BR', numbered: true },
                { title: 'Top 10 séries hoje no Brasil', file: 'netflix-top10-br-series', type: 'series', id: 'netflix-top10-BR', numbered: true },
                { title: 'Top 10 filmes hoje em Portugal', file: 'netflix-top10-pt-movie', type: 'movie', id: 'netflix-top10-PT', numbered: true },
                { title: 'Top 10 séries hoje em Portugal', file: 'netflix-top10-pt-series', type: 'series', id: 'netflix-top10-PT', numbered: true }
            ];
            default: return [{ title: tab, soon: true }];
        }
    }

    function openTitle(meta) {
        if (!meta || !meta.id || !meta.type) return;
        location.hash = '#/detail/' + encodeURIComponent(meta.type) + '/' + encodeURIComponent(meta.id);
    }

    function posterEl(meta, wide, numbered) {
        var b = el('button', 'gf-poster is-filled');
        b.type = 'button';
        b.setAttribute('aria-label', meta.name || 'Título');
        var img = el('img');
        img.alt = '';
        img.loading = 'lazy';
        img.decoding = 'async';
        img.onerror = function () { img.remove(); b.classList.add('no-img'); };
        if (meta.poster) img.src = meta.poster; else b.classList.add('no-img');
        b.appendChild(img);
        b.appendChild(el('span', 'gf-poster-name', meta.name || ''));
        if (numbered && meta.order) b.appendChild(el('span', 'gf-order', String(meta.order)));
        b.addEventListener('click', function () { openTitle(meta); });
        return b;
    }

    function fillHero(hero, meta, s) {
        hero.innerHTML = '';
        if (meta) {
            var bg = el('img', 'gf-hero-bg');
            bg.alt = '';
            bg.onerror = function () { bg.remove(); };
            bg.src = meta.background || ('https://images.metahub.space/background/medium/' + encodeURIComponent(meta.id) + '/img');
            hero.appendChild(bg);
        }
        var inner = el('div', 'gf-hero-inner');
        inner.appendChild(el('span', 'gf-hero-tag', 'Em destaque'));
        inner.appendChild(el('div', 'gf-hero-title', meta ? meta.name : (meta === false ? 'Destaque indisponível' : 'Carregando…')));
        if (meta && meta.releaseInfo) inner.appendChild(el('div', 'gf-hero-text', meta.releaseInfo));
        var actions = el('div', 'gf-hero-actions');
        var play = el('button', 'gf-hero-btn is-primary', '▶ Assistir');
        var info = el('button', 'gf-hero-btn', 'Mais informações');
        play.type = info.type = 'button';
        if (meta) {
            play.addEventListener('click', function () { openTitle(meta); });
            info.addEventListener('click', function () { openTitle(meta); });
        } else { play.disabled = info.disabled = true; }
        actions.appendChild(play);
        actions.appendChild(info);
        inner.appendChild(actions);
        hero.appendChild(inner);
    }

    function renderSection(sec, s, onFirst) {
        var row = el('section', 'gf-row');
        row.appendChild(el('h2', 'gf-row-title', sec.title));
        var track = el('div', sec.grid ? 'gf-poster-grid' : 'gf-row-track');
        row.appendChild(track);
        if (sec.soon) {
            var soon = el('div', 'gf-empty');
            soon.appendChild(el('div', 'gf-empty-title', 'Em breve'));
            soon.appendChild(el('div', 'gf-empty-text', 'A seção ' + sec.title + ' de ' + s.name + ' ainda não tem títulos.'));
            row.replaceChild(soon, track);
            return row;
        }
        for (var i = 0; i < (sec.grid ? 12 : 8); i++) track.appendChild(el('div', 'gf-poster'));
        loadCatalog(sec).then(function (metas) {
            track.innerHTML = '';
            if (!metas.length) {
                row.replaceChild(el('div', 'gf-row-msg', 'Nenhum título encontrado.'), track);
                onFirst && onFirst(false);
                return;
            }
            metas.slice(0, sec.grid ? 300 : (sec.numbered ? 100 : 30)).forEach(function (m, i) {
                if (sec.numbered && !m.order) m = Object.assign({}, m, { order: i + 1 });
                track.appendChild(posterEl(m, false, sec.numbered));
            });
            onFirst && onFirst(metas[0]);
        }).catch(function () {
            var msg = el('div', 'gf-row-msg');
            msg.appendChild(document.createTextNode('Não foi possível carregar. '));
            var retry = el('button', 'gf-link', 'Tentar de novo');
            retry.type = 'button';
            retry.addEventListener('click', function () { row.replaceWith(renderSection(sec, s, onFirst)); });
            msg.appendChild(retry);
            row.replaceChild(msg, track);
            onFirst && onFirst(false);
        });
        return row;
    }

    // ---------- Continuar assistindo (por serviço) ----------
    // O progresso vem do próprio app (window.core). Cada título aparece no
    // catálogo do serviço onde ele está; o que não está em nenhum vai para a Cinemateca.
    var ALL_FILES = ['netflix', 'disney', 'prime', 'hbo', 'appletv', 'paramount', 'globoplay', 'clarovideo']
        .reduce(function (acc, id) { return acc.concat([id + '-movie', id + '-series']); }, [])
        .concat(['disney-disney', 'disney-pixar', 'disney-marvel', 'disney-starwars', 'disney-natgeo',
                 'cine-cinemas', 'cine-classicos', 'cine-movie', 'cine-series']);

    function filesFor(s) {
        if (s.id === 'cinemateca') return ['cine-cinemas', 'cine-classicos', 'cine-movie', 'cine-series'];
        var f = [s.id + '-movie', s.id + '-series'];
        if (s.id === 'disney') f = f.concat(['disney-disney', 'disney-pixar', 'disney-marvel', 'disney-starwars', 'disney-natgeo']);
        return f;
    }

    function idsOf(files) {
        return Promise.all(files.map(function (f) {
            return loadCatalog({ file: f }).catch(function () { return []; });
        })).then(function (lists) {
            var set = {};
            lists.forEach(function (l) { l.forEach(function (m) { set[m.id] = true; }); });
            return set;
        });
    }

    function getContinueItems() {
        var core = window.core && (window.core.transport || window.core);
        if (!core || typeof core.getState !== 'function') return Promise.resolve([]);
        return Promise.resolve(core.getState('continue_watching_preview'))
            .then(function (st) { return (st && st.items) || []; })
            .catch(function () { return []; });
    }

    function continuePoster(item) {
        var meta = { id: item._id, type: item.type, name: item.name, poster: item.poster };
        var b = posterEl(meta, false, false);
        var st = item.state || {};
        if (st.duration > 0 && st.timeOffset > 0) {
            var bar = el('span', 'gf-progress');
            var fill = el('span', 'gf-progress-fill');
            fill.style.width = Math.min(100, Math.max(3, (st.timeOffset / st.duration) * 100)).toFixed(1) + '%';
            bar.appendChild(fill);
            b.appendChild(bar);
        }
        // tocar retoma de onde parou, quando o app tiver esse atalho
        var link = item.deepLinks && (item.deepLinks.player || item.deepLinks.metaDetailsStreams || item.deepLinks.metaDetailsVideos);
        if (link) {
            b.addEventListener('click', function (e) {
                e.stopImmediatePropagation();
                location.hash = link.replace(/^#/, '');
            }, true);
        }
        return b;
    }

    function renderContinue(s) {
        var row = el('section', 'gf-row');
        row.hidden = true;
        row.appendChild(el('h2', 'gf-row-title', 'Continuar assistindo'));
        var track = el('div', 'gf-row-track');
        row.appendChild(track);
        Promise.all([getContinueItems(), idsOf(filesFor(s)), s.id === 'cinemateca' ? idsOf(ALL_FILES.filter(function (f) { return f.indexOf('cine-') !== 0; })) : null])
            .then(function (r) {
                var items = r[0], mine = r[1], onServices = r[2];
                var list = items.filter(function (it) {
                    if (!it || !it._id) return false;
                    if (mine[it._id]) return true;
                    // Cinemateca também recebe o que não está em nenhum serviço
                    return s.id === 'cinemateca' && onServices && !onServices[it._id];
                });
                if (!list.length) return;
                list.forEach(function (it) { track.appendChild(continuePoster(it)); });
                row.hidden = false;
            });
        return row;
    }

    function renderCatalog(root, s) {
        root.innerHTML = '';
        var page = el('div', 'gf-svc');
        page.style.setProperty('--gf-brand', s.color);
        page.style.setProperty('--gf-on-brand', (s.id === 'appletv' || s.id === 'globoplay' || s.id === 'cinemateca') ? '#000' : '#fff');
        if (s.grad) page.style.setProperty('--gf-grad', 'linear-gradient(90deg, ' + s.grad.join(', ') + ')');

        var head = el('div', 'gf-svc-head');
        head.appendChild(backButton());
        head.appendChild(el('h1', 'gf-svc-name' + (s.grad ? ' gf-grad-text' : ''), s.name));
        page.appendChild(head);

        var tabs = TABS[s.id] || ['Início'];
        if (tabs.indexOf(currentTab) < 0) currentTab = tabs[0];
        var bar = el('div', 'gf-tabs');
        bar.setAttribute('role', 'tablist');
        tabs.forEach(function (t) {
            var b = el('button', 'gf-tab' + (t === currentTab ? ' is-active' : ''), t);
            b.type = 'button';
            b.setAttribute('role', 'tab');
            b.setAttribute('aria-selected', t === currentTab ? 'true' : 'false');
            b.addEventListener('click', function () {
                if (t === currentTab) return;
                currentTab = t;
                try { history.replaceState(Object.assign({}, history.state, { gfService: s.id, gfTab: t }), '', location.href); } catch (e) {}
                renderCatalog(root, s);
            });
            bar.appendChild(b);
        });
        page.appendChild(bar);

        var body = el('div', 'gf-tab-body');
        if (currentTab === 'Minha lista') {
            var empty = el('div', 'gf-empty');
            empty.appendChild(el('div', 'gf-empty-title', 'Sua lista está vazia'));
            empty.appendChild(el('div', 'gf-empty-text', 'Os títulos que você salvar em ' + s.name + ' vão aparecer aqui.'));
            body.appendChild(empty);
        } else {
            var hero = null;
            if (currentTab === 'Início') {
                hero = el('div', 'gf-hero');
                fillHero(hero, null, s);
                body.appendChild(hero);
                body.appendChild(renderContinue(s));
            }
            var heroDone = false;
            sectionsFor(s, currentTab).forEach(function (sec) {
                body.appendChild(renderSection(sec, s, hero && function (m) {
                    if (heroDone) return;
                    if (m) heroDone = true;
                    fillHero(hero, m, s);
                }));
            });
        }
        page.appendChild(body);
        root.appendChild(page);
    }

    function find(id) {
        for (var i = 0; i < SERVICES.length; i++) if (SERVICES[i].id === id) return SERVICES[i];
        return null;
    }

    // Troca o fundo do app enquanto um catálogo está aberto
    var themed = false;
    function applyTheme(s) {
        var st = document.documentElement.style;
        if (s && s.bg) {
            st.setProperty('--primary-background-color', s.bg[0]);
            st.setProperty('--secondary-background-color', s.bg[1]);
            themed = true;
        } else if (themed) {
            st.removeProperty('--primary-background-color');
            st.removeProperty('--secondary-background-color');
            themed = false;
        }
    }

    function render() {
        var root = document.getElementById('gf-home');
        if (!root) return;
        var s = current && find(current);
        applyTheme(s);
        if (s) renderCatalog(root, s); else renderGrid(root);
        root.parentNode && (root.parentNode.scrollTop = 0);
    }

    function open(id, push) {
        current = id;
        currentTab = 'Início';
        if (push) history.pushState({ gfService: id }, '', location.href);
        render();
    }

    window.addEventListener('popstate', function (e) {
        var id = e.state && e.state.gfService;
        if (id !== current) { current = id || null; currentTab = (e.state && e.state.gfTab) || 'Início'; render(); }
    });

    function mount() {
        var content = document.querySelector('div[class^="board-content-"]:not([class*="container"])');
        if (!document.getElementById('gf-home')) applyTheme(null);
        if (!content || document.getElementById('gf-home')) return;
        var root = el('div');
        root.id = 'gf-home';
        content.insertBefore(root, content.firstChild);
        current = (history.state && history.state.gfService) || null;
        currentTab = (history.state && history.state.gfTab) || 'Início';
        render();
    }

    new MutationObserver(mount).observe(document.documentElement, { childList: true, subtree: true });
    mount();
})();
