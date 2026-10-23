// GabiFlix: tela inicial com botões de serviços de streaming.
// Substitui os catálogos padrão da tela inicial.
(function () {
    // color: cor do nome no botão, inspirada na cor de cada serviço
    // bg: degradê do fundo dentro do catálogo [início, fim]; null mantém o tema padrão
    var SERVICES = [
        { id: 'netflix', bg: null, cat: 'nfx', name: 'Netflix', color: '#e50914' },
        { id: 'disney', bg: ['#000000', '#0a1d52'], cat: 'dnp', name: 'Disney+', color: '#3aa3ff' },
        { id: 'prime', bg: ['#000000', '#0b3b55'], cat: 'amp', name: 'Prime Video', color: '#1ba8e0' },
        { id: 'hbo', bg: ['#000000', '#2c0b4f'], cat: 'hbm', name: 'HBO Max', color: '#a36bff' },
        { id: 'appletv', bg: ['#000000', '#2d2d31'], cat: 'atp', name: 'Apple TV+', color: '#f5f5f7' },
        { id: 'paramount', bg: ['#000000', '#0a1d52'], cat: 'pmp', name: 'Paramount+', color: '#2f7dff' },
        { id: 'globoplay', bg: ['#0b2a12', '#4d3d05'], cat: 'gop', name: 'Globoplay', color: '#c8d23a', grad: ['#f5c518', '#3ecf6b'] },
        { id: 'clarovideo', bg: ['#000000', '#4f2205'], cat: 'clv', name: 'Claro Video', color: '#ff2a2a' }
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
            var b = el('button', 'gf-card');
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
            b.appendChild(el('span', 'gf-card-hint', 'Ver catálogo'));
            b.addEventListener('click', function () { open(s.id, true); });
            grid.appendChild(b);
        });
        root.appendChild(grid);
    }

    // Abas de cada serviço (categorias típicas de cada app)
    var TABS = {
        netflix:    ['Início', 'Séries', 'Filmes', 'Bombando', 'Minha lista'],
        disney:     ['Início', 'Filmes', 'Séries', 'Originais', 'Minha lista'],
        prime:      ['Início', 'Filmes', 'Séries', 'Esportes', 'Minha lista'],
        hbo:        ['Início', 'Séries', 'Filmes', 'Infantil', 'Minha lista'],
        appletv:    ['Início', 'Originais', 'Filmes', 'Séries', 'Minha lista'],
        paramount:  ['Início', 'Ao vivo', 'Filmes', 'Séries', 'Minha lista'],
        globoplay:  ['Início', 'Novelas', 'Séries', 'Filmes', 'Ao vivo', 'Minha lista'],
        clarovideo: ['Início', 'Ao vivo', 'Filmes', 'Séries', 'Infantil', 'Minha lista']
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
    // Fonte: addon aberto "Streaming Catalogs" (github.com/rleroi/Stremio-Streaming-Catalogs-Addon)
    var CATALOG_URL = 'https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club';
    var cache = {};

    function loadCatalog(type, id) {
        var key = type + '/' + id;
        if (!cache[key]) {
            cache[key] = fetch(CATALOG_URL + '/catalog/' + type + '/' + encodeURIComponent(id) + '.json')
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .then(function (d) { return (d && d.metas) || []; })
                .catch(function (e) { delete cache[key]; throw e; });
        }
        return cache[key];
    }

    // O que cada aba mostra: lista de fileiras { title, type, id } ou { title, soon: true }
    function sectionsFor(s, tab) {
        var c = s.cat;
        switch (tab) {
            case 'Início': return [
                { title: 'Filmes em alta', type: 'movie', id: c },
                { title: 'Séries em alta', type: 'series', id: c }
            ];
            case 'Filmes': return [{ title: 'Todos os filmes', type: 'movie', id: c, grid: true }];
            case 'Séries': return [{ title: 'Todas as séries', type: 'series', id: c, grid: true }];
            case 'Bombando': return [
                { title: 'Top 10 filmes no Brasil', type: 'movie', id: 'netflix-top10-BR' },
                { title: 'Top 10 séries no Brasil', type: 'series', id: 'netflix-top10-BR' }
            ];
            default: return [{ title: tab, soon: true }];
        }
    }

    function openTitle(meta) {
        if (!meta || !meta.id || !meta.type) return;
        location.hash = '#/detail/' + encodeURIComponent(meta.type) + '/' + encodeURIComponent(meta.id);
    }

    function posterEl(meta, wide) {
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
        loadCatalog(sec.type, sec.id).then(function (metas) {
            track.innerHTML = '';
            if (!metas.length) {
                row.replaceChild(el('div', 'gf-row-msg', 'Nenhum título encontrado.'), track);
                onFirst && onFirst(false);
                return;
            }
            metas.slice(0, sec.grid ? 100 : 30).forEach(function (m) { track.appendChild(posterEl(m)); });
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

    function renderCatalog(root, s) {
        root.innerHTML = '';
        var page = el('div', 'gf-svc');
        page.style.setProperty('--gf-brand', s.color);
        page.style.setProperty('--gf-on-brand', (s.id === 'appletv' || s.id === 'globoplay') ? '#000' : '#fff');
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
