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
        { id: 'cinemateca', bg: ['#0b0906', '#3b3020'], name: 'CineTime', color: '#e9c46a', wide: true, hint: 'Cinema, clássicos e o que não está nos streamings' }
    ];

    var current = null; // serviço aberto, ou null para a grade
    var gridIndex = 0; // apps: serviço que estava na tela da grade, para voltar a ele
    var gridKeyHandler = null; // Mac: tecla ← → Enter na tela dos serviços (um só ouvinte por vez)

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text) e.textContent = text;
        return e;
    }

    function renderGrid(root) {
        root.innerHTML = '';
        // apps (celular Android e Mac): os botões viram um carrossel, um serviço por vez
        var appKind = document.documentElement.getAttribute('data-gf-app');
        var android = appKind === 'android' || appKind === 'mac'; // "carrossel de app" (nome antigo da variável)
        var mac = appKind === 'mac'; // Mac: janela grande, mouse e teclado (setas nas laterais, ← →, Enter)
        var grid = el('div', android ? 'gf-grid gf-pager' : 'gf-grid');
        // cria o botão de um serviço
        function makeCard(s) {
            var b = el('button', 'gf-card' + (s.wide ? ' is-wide' : ''));
            b.type = 'button';
            b.setAttribute('data-service', s.id);
            b.style.setProperty('--gf-brand', s.color);
            if (s.grad) b.style.setProperty('--gf-grad', 'linear-gradient(90deg, ' + s.grad.join(', ') + ')');
            // tons do fundo suave do botão (Globoplay usa as duas cores do degradê)
            b.style.setProperty('--gf-tint1', s.grad ? s.grad[0] : s.color);
            b.style.setProperty('--gf-tint2', s.grad ? s.grad[1] : s.color);
            // celular Android: o cartão ocupa a tela toda, com o degradê de fundo do serviço (a cápsula de baixo e de cima ficam sobre ele)
            if (android && s.bg) {
                b.style.setProperty('--gf-bg0', s.bg[0]);
                b.style.setProperty('--gf-bg1', s.bg[1]);
            }
            b.appendChild(el('span', 'gf-card-name' + (s.grad ? ' gf-grad-text' : ''), s.name));
            if (!android) b.appendChild(el('span', 'gf-card-hint', s.hint || 'Ver catálogo')); // celular Android: só o nome
            b.addEventListener('pointerdown', function () { prefetch(s); }, { passive: true });
            b.addEventListener('click', function () { open(s.id, true); });
            return b;
        }
        SERVICES.forEach(function (s) { grid.appendChild(makeCard(s)); });
        // celular Android: carrossel sem fim. Antes e depois dos serviços entram mais duas levas iguais; a do meio é a original
        if (android) {
            var firstCard = grid.firstChild;
            SERVICES.forEach(function (s) { grid.insertBefore(makeCard(s), firstCard); });
            SERVICES.forEach(function (s) { grid.appendChild(makeCard(s)); });
        }
        root.appendChild(grid);
        if (!android) return;
        if (gridKeyHandler) { document.removeEventListener('keydown', gridKeyHandler, true); gridKeyHandler = null; }

        // celular Android: as bolinhas embaixo mostram em qual serviço está; tocar numa leva até ele
        var N = SERVICES.length; // cada leva tem N botões (um por serviço)
        var cards = grid.children; // 3 * N botões: leva de antes, leva original, leva de depois
        var dots = el('div', 'gf-dots');
        var raf = 0, settleTimer = 0;
        var hasScrollEnd = 'onscrollend' in window;

        // largura de uma tela: distância entre o primeiro e o segundo botão (inclui o espaço entre eles)
        function step() {
            var d = cards.length > 1 ? cards[1].offsetLeft - cards[0].offsetLeft : 0;
            return d > 0 ? d : (grid.clientWidth || 1);
        }
        // posição do botão na tela contando as três levas (0 a 3 * N - 1)
        function rawIndex() {
            var i = Math.round(grid.scrollLeft / step());
            return Math.max(0, Math.min(cards.length - 1, i));
        }
        // número do serviço que está na tela agora (0 a N - 1)
        function indexNow() {
            return rawIndex() % N;
        }
        // as levas são iguais: se a rolagem saiu da leva do meio, pula de volta para ela sem mudar o que aparece
        function recenter() {
            var r = rawIndex();
            if (r >= N && r < 2 * N) return;
            var shift = (r < N ? 1 : -1) * N * step();
            // sem o encaixe durante o pulo, para o navegador não brigar com ele
            grid.style.scrollSnapType = 'none';
            grid.scrollLeft = grid.scrollLeft + shift;
            requestAnimationFrame(function () { grid.style.scrollSnapType = ''; });
        }
        function showDot(i) {
            // Mac: só o serviço do meio fica em destaque; os vizinhos que aparecem nas pontas ficam mais apagados
            if (mac) {
                var cr = rawIndex();
                for (var c = 0; c < cards.length; c++) cards[c].classList.toggle('is-center', c === cr);
            }
            for (var k = 0; k < dots.children.length; k++) {
                if (k === i) dots.children[k].classList.add('is-on');
                else dots.children[k].classList.remove('is-on');
            }
        }
        function goTo(i) {
            // vai pelo caminho mais curto: pode ser pela leva de antes, pela do meio ou pela de depois
            var r = rawIndex(), t = i;
            [i + N, i + 2 * N].forEach(function (c) { if (Math.abs(c - r) < Math.abs(t - r)) t = c; });
            var x = t * step();
            // rolagem suave só onde o navegador entende as opções; no resto, pula direto
            if (typeof grid.scrollTo === 'function' && 'scrollBehavior' in document.documentElement.style) {
                grid.scrollTo({ left: x, behavior: 'smooth' });
            } else {
                grid.scrollLeft = x;
            }
        }

        SERVICES.forEach(function (s, i) {
            var d = el('button', 'gf-dot' + (i === gridIndex ? ' is-on' : ''));
            d.type = 'button';
            d.setAttribute('aria-label', s.name);
            d.addEventListener('click', function () { goTo(i); });
            dots.appendChild(d);
        });
        root.appendChild(dots);

        if (mac) {
            // setas dos lados: serviço anterior / próximo (depois do último volta ao primeiro)
            var lastNav = 0;
            function nav(dir) {
                var now = Date.now();
                if (now - lastNav < 180) return; // evita empilhar animações com cliques/rolagens muito rápidos
                lastNav = now;
                goTo((indexNow() + dir + N) % N);
            }
            [['prev', -1, 'Serviço anterior', 'M15 5l-7 7 7 7'], ['next', 1, 'Próximo serviço', 'M9 5l7 7-7 7']].forEach(function (a) {
                var btn = el('button', 'gf-pager-arrow is-' + a[0]);
                btn.type = 'button';
                btn.setAttribute('aria-label', a[2]);
                btn.innerHTML = '<svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true"><path d="' + a[3] + '" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
                btn.addEventListener('click', function () { nav(a[1]); });
                root.appendChild(btn);
            });
            // teclado: ← → trocam de serviço, Enter abre o serviço em destaque
            gridKeyHandler = function (e) {
                if (!document.body.contains(grid) || e.metaKey || e.ctrlKey || e.altKey) return;
                var de = document.documentElement;
                if (de.getAttribute('data-gf-route') !== 'home' || de.getAttribute('data-gf-view') !== 'grid') return;
                var t = e.target, tag = t && t.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return;
                if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                    e.preventDefault();
                    nav(e.key === 'ArrowRight' ? 1 : -1);
                } else if (e.key === 'Enter' && (tag === 'BODY' || tag === 'HTML' || !t || t === document.body)) {
                    e.preventDefault();
                    open(SERVICES[indexNow()].id, true);
                }
            };
            document.addEventListener('keydown', gridKeyHandler, true);
            // mouse: shift + roda do mouse troca de serviço (o trackpad na horizontal já rola o carrossel sozinho)
            grid.addEventListener('wheel', function (e) {
                if (e.shiftKey && Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
                    e.preventDefault();
                    nav(e.deltaY > 0 ? 1 : -1);
                }
            }, { passive: false });
        }

        // ao rolar, guarda o serviço da tela e atualiza as bolinhas (no máximo uma vez por quadro)
        grid.addEventListener('scroll', function () {
            // sem o evento de fim de rolagem, espera 150 ms sem rolar para voltar à leva do meio
            if (!hasScrollEnd) { clearTimeout(settleTimer); settleTimer = setTimeout(recenter, 150); }
            if (raf) return;
            raf = requestAnimationFrame(function () {
                raf = 0;
                // o usuário deu uma volta muito grande de uma vez: não espera a rolagem parar
                var r = rawIndex();
                if (r <= 1 || r >= cards.length - 2) recenter();
                gridIndex = indexNow();
                showDot(gridIndex);
            });
        }, { passive: true });
        // quando a rolagem para, volta para a leva do meio (se o navegador avisa o fim da rolagem)
        if (hasScrollEnd) grid.addEventListener('scrollend', recenter);

        // volta para o serviço em que o usuário estava (ex.: ao fechar um catálogo), sem animação; começa na leva do meio
        requestAnimationFrame(function () {
            gridIndex = gridIndex >= 0 && gridIndex < N ? gridIndex : 0;
            grid.scrollLeft = (N + gridIndex) * step();
            showDot(gridIndex);
        });
    }

    // Abas de cada serviço (categorias típicas de cada app)
    var TABS = {
        netflix:    ['Início', 'Filmes', 'Séries', 'Bombando'],
        disney:     ['Início', 'Filmes', 'Séries', 'Disney', 'Pixar', 'Marvel', 'Star Wars', 'National Geographic'],
        prime:      ['Início', 'Filmes', 'Séries'],
        hbo:        ['Início', 'Filmes', 'Séries'],
        appletv:    ['Início', 'Filmes', 'Séries'],
        paramount:  ['Início', 'Filmes', 'Séries'],
        globoplay:  ['Início', 'Filmes', 'Séries'],
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

    // Arquivo pequeno da aba Início: { top10, movie, series, genres: [{ code, title, metas }] }
    var homeCache = {};
    function loadHome(svc) {
        if (!homeCache[svc]) {
            homeCache[svc] = fetch('data/' + svc + '-home.json?v=' + DATA_VERSION)
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .catch(function () {
                    // reserva (antes da primeira atualização): monta a Início a partir das listas completas
                    return Promise.all([
                        loadCatalog({ file: svc + '-movie' }).catch(function () { return []; }),
                        loadCatalog({ file: svc + '-series' }).catch(function () { return []; })
                    ]).then(function (r) {
                        var top = [];
                        for (var i = 0; i < 10; i++) { if (r[0][i]) top.push(r[0][i]); if (r[1][i]) top.push(r[1][i]); }
                        return { top10: top.slice(0, 10), movie: r[0].slice(0, 30), series: r[1].slice(0, 30), genres: [] };
                    });
                });
            homeCache[svc].catch(function () { delete homeCache[svc]; });
        }
        return homeCache[svc];
    }

    // sec: { home, key } para a Início, { file } para dados próprios e/ou { type, id } para o addon
    // títulos encontrados pela busca que não estão em nenhum serviço (guardados neste aparelho pela página do título)
    function foundInSearch(type) {
        try {
            var l = JSON.parse(localStorage.getItem('gf-cinetime-found') || '[]');
            return type && type !== 'all' ? l.filter(function (m) { return m.type === type; }) : l;
        } catch (e) { return []; }
    }

    // apps (celular Android e Mac): os catálogos dos serviços têm até 2000 títulos (lista de sempre + data/<arquivo>-extra.json); TV e navegador usam só a lista de sempre
    var EXTRA_FILE = /^(netflix|disney|prime|hbo|appletv|paramount|globoplay|clarovideo)-(movie|series)$/;
    function withExtra(file, metas) {
        if (!metas.length || !EXTRA_FILE.test(file || '') || ['android', 'mac'].indexOf(document.documentElement.getAttribute('data-gf-app')) < 0) return metas;
        return getJSON('data/' + file + '-extra.json?v=' + DATA_VERSION).catch(function () { return []; }).then(function (more) {
            var seen = {};
            metas.forEach(function (m) { seen[m.id] = 1; });
            return metas.concat(more.filter(function (m) { return !seen[m.id]; }));
        });
    }
    function loadCatalog(sec) {
        if (sec.local) return Promise.resolve(foundInSearch(sec.local));
        if (sec.withLocal) {
            var base = Object.assign({}, sec, { withLocal: null });
            return loadCatalog(base).catch(function () { return []; }).then(function (metas) {
                var mine = foundInSearch(sec.withLocal), seen = {};
                return mine.concat(metas).filter(function (m) { if (seen[m.id]) return false; seen[m.id] = 1; return true; });
            });
        }
        if (sec.home) {
            return loadHome(sec.home).then(function (h) {
                if (sec.key === 'genre') {
                    var g = (h.genres || []).filter(function (x) { return x.code === sec.code; })[0];
                    return (g && g.metas) || [];
                }
                return h[sec.key] || [];
            });
        }
        var key = (sec.file || '') + '|' + (sec.type || '') + '/' + (sec.id || '');
        if (!cache[key]) {
            var addon = function () {
                if (!sec.id) return [];
                return getJSON(CATALOG_URL + '/catalog/' + sec.type + '/' + encodeURIComponent(sec.id) + '.json');
            };
            var p = sec.file
                ? getJSON('data/' + sec.file + '.json?v=' + DATA_VERSION)
                    .then(function (m) { return withExtra(sec.file, m); })
                    .then(function (m) { return m.length ? m : addon(); }, addon)
                : addon();
            cache[key] = p.catch(function (e) { delete cache[key]; throw e; });
        }
        return cache[key];
    }

    // Aba Início, no estilo dos apps de streaming:
    // Top 10, em alta, (extras do serviço) e uma fileira por gênero
    function homeSections(s, extras) {
        var top = s.id === 'netflix'
            ? [
                { title: 'Top 10 filmes hoje', file: 'netflix-top10-br-movie', type: 'movie', id: 'netflix-top10-BR', top10: true },
                { title: 'Top 10 séries hoje', file: 'netflix-top10-br-series', type: 'series', id: 'netflix-top10-BR', top10: true }
            ]
            : [{ title: 'Top 10 em alta', home: s.id, key: 'top10', top10: true }];
        return top.concat([
            { title: 'Filmes em alta', home: s.id, key: 'movie' },
            { title: 'Séries em alta', home: s.id, key: 'series' },
            { title: 'Filmes brasileiros', home: s.id, key: 'brMovie', hideEmpty: true },
            { title: 'Séries brasileiras', home: s.id, key: 'brSeries', hideEmpty: true }
        ], extras, [{ genresOf: s.id }]);
    }

    // O que cada aba mostra: lista de fileiras
    function sectionsFor(s, tab) {
        if (s.id === 'cinemateca') {
            switch (tab) {
                case 'Início': return [
                    { title: 'Encontrados na busca', local: 'all', hideEmpty: true },
                    { title: 'Nos cinemas e lançamentos', file: 'cine-cinemas' },
                    { title: 'Clássicos', file: 'cine-classicos' },
                    { title: 'Filmes fora dos streamings', file: 'cine-movie' },
                    { title: 'Séries fora dos streamings', file: 'cine-series' }
                ];
                case 'Nos cinemas': return [{ title: 'Nos cinemas e lançamentos', file: 'cine-cinemas', grid: true }];
                case 'Clássicos': return [{ title: 'Clássicos', file: 'cine-classicos', grid: true }];
                case 'Filmes': return [{ title: 'Filmes fora dos streamings', file: 'cine-movie', grid: true, withLocal: 'movie' }];
                case 'Séries': return [{ title: 'Séries fora dos streamings', file: 'cine-series', grid: true, withLocal: 'series' }];
                default: return [];
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
            if (tab === 'Início') return homeSections(s, DISNEY_BRANDS.map(function (b) { return { title: b[0], file: 'disney-' + b[1] }; }));
        }
        var c = s.cat;
        switch (tab) {
            case 'Início': return homeSections(s, []);
            case 'Filmes': return [{ title: 'Todos os filmes', file: s.id + '-movie', type: 'movie', id: c, grid: true }];
            case 'Séries': return [{ title: 'Todas as séries', file: s.id + '-series', type: 'series', id: c, grid: true }];
            case 'Bombando': return [
                { title: 'Top 10 filmes hoje no Brasil', file: 'netflix-top10-br-movie', type: 'movie', id: 'netflix-top10-BR', numbered: true },
                { title: 'Top 10 séries hoje no Brasil', file: 'netflix-top10-br-series', type: 'series', id: 'netflix-top10-BR', numbered: true },
                { title: 'Top 10 filmes hoje em Portugal', file: 'netflix-top10-pt-movie', type: 'movie', id: 'netflix-top10-PT', numbered: true },
                { title: 'Top 10 séries hoje em Portugal', file: 'netflix-top10-pt-series', type: 'series', id: 'netflix-top10-PT', numbered: true }
            ];
            default: return [];
        }
    }

    // ---------- Posição da rolagem: ao voltar de um título, a lista continua onde estava ----------
    var pendingScroll = 0, lastScroll = 0;

    // devolve a lista para uma posição, confirmando algumas vezes (o app às vezes zera a rolagem)
    function scrollBackTo(target) {
        if (!target) return;
        var ok = 0, tries = 0;
        (function retry() {
            var sc = scroller();
            if (sc && sc.clientHeight > 0) {
                if (Math.abs(sc.scrollTop - target) > 2) { sc.scrollTop = target; ok = 0; } else ok++;
            }
            if (ok < 3 && ++tries < 30) setTimeout(retry, 100);
        })();
    }
    function scroller() {
        var root = document.getElementById('gf-home');
        return root && root.parentNode;
    }
    function saveScroll() {
        var sc = scroller();
        if (!sc || !current) return;
        lastScroll = sc.scrollTop;
        try { history.replaceState(Object.assign({}, history.state, { gfScroll: sc.scrollTop }), '', location.href); } catch (e) {}
    }
    function restoreScroll() {
        if (!pendingScroll) return;
        var sc = scroller();
        if (!sc) return;
        sc.scrollTop = pendingScroll;
        // só termina quando a página já tem altura suficiente
        if (Math.abs(sc.scrollTop - pendingScroll) < 2) pendingScroll = 0;
    }

    function openTitle(meta) {
        if (!meta || !meta.id || !meta.type) return;
        saveScroll();
        window.gfGo('#/detail/' + encodeURIComponent(meta.type) + '/' + encodeURIComponent(meta.id));
    }

    function posterEl(meta, wide, numbered) {
        var b = el('button', 'gf-poster is-filled');
        b.type = 'button';
        b.setAttribute('aria-label', meta.name || 'Título');
        var img = el('img');
        img.alt = '';
        img.loading = 'lazy';
        img.decoding = 'async';
        img.onload = function () { b.classList.add('img-ready'); };
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
            bg.onload = function () { bg.classList.add('is-ready'); };
            bg.setAttribute('fetchpriority', 'high');
            bg.decoding = 'async';
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

    // fileiras de gênero: vêm do arquivo da Início e só aparecem as que têm títulos
    function renderGenres(sec, s) {
        var box = el('div', 'gf-genres');
        loadHome(sec.genresOf).then(function (h) {
            (h.genres || []).slice(0, GENRES_MAX).forEach(function (g) {
                box.appendChild(renderSection({ title: g.title, home: sec.genresOf, key: 'genre', code: g.code }, s));
            });
            restoreScroll();
        }).catch(function () {});
        return box;
    }

    var ROW_MAX = 30, GENRES_MAX = 99;

    // grades longas: desenha 60 por vez, conforme a rolagem se aproxima do fim
    var GRID_BATCH = 60;
    function fillGrid(track, metas, sec) {
        var shown = 0;
        var sentinel = el('div', 'gf-grid-more');
        function more() {
            var frag = document.createDocumentFragment();
            metas.slice(shown, shown + GRID_BATCH).forEach(function (m) { frag.appendChild(posterEl(m, false, sec.numbered)); });
            shown = Math.min(metas.length, shown + GRID_BATCH);
            track.appendChild(frag);
            if (shown >= metas.length) { sentinel.remove(); if (io) io.disconnect(); }
            else track.parentNode && track.parentNode.appendChild(sentinel);
        }
        var io = 'IntersectionObserver' in window ? new IntersectionObserver(function (es) {
            if (es.some(function (e) { return e.isIntersecting; })) more();
        }, { rootMargin: '1200px 0px' }) : null;
        more();
        if (io && shown < metas.length) io.observe(sentinel);
        else if (!io) while (shown < metas.length) more();
        // voltar de um título longe na lista: desenha o suficiente para chegar à posição guardada
        if (pendingScroll) {
            var guard = 0;
            while (shown < metas.length && track.scrollHeight < pendingScroll + 1500 && guard++ < 40) more();
        }
    }

    function renderSection(sec, s, onFirst) {
        if (sec.genresOf) return renderGenres(sec, s);
        var row = el('section', 'gf-row' + (sec.top10 ? ' gf-row-top10' : ''));
        row.appendChild(el('h2', 'gf-row-title', sec.title));
        var track = el('div', sec.grid ? 'gf-poster-grid' : 'gf-row-track');
        row.appendChild(track);
        for (var i = 0; i < (sec.grid ? 12 : 8); i++) track.appendChild(el('div', 'gf-poster'));
        fill();
        return row;

        function fill() {
        loadCatalog(sec).then(function (metas) {
            track.innerHTML = '';
            if (!metas.length && sec.hideEmpty) { row.remove(); return; }
            if (!metas.length) {
                row.replaceChild(el('div', 'gf-row-msg', 'Nenhum título encontrado.'), track);
                onFirst && onFirst(false);
                return;
            }
            if (sec.grid) {
                fillGrid(track, metas, sec);
            } else {
                var frag = document.createDocumentFragment();
                metas.slice(0, sec.top10 ? 10 : (sec.numbered ? 100 : ROW_MAX)).forEach(function (m, i) {
                    if ((sec.numbered || sec.top10) && !m.order) m = Object.assign({}, m, { order: i + 1 });
                    var p = posterEl(m, false, sec.numbered || sec.top10);
                    if (sec.top10) {
                        // número grande ao lado da capa
                        var wrap = el('div', 'gf-top10-item');
                        wrap.appendChild(el('span', 'gf-top10-num', String(i + 1)));
                        wrap.appendChild(p);
                        frag.appendChild(wrap);
                    } else frag.appendChild(p);
                });
                track.appendChild(frag);
            }
            onFirst && onFirst(metas[0]);
            restoreScroll();
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
        }
    }

    // ---------- Continuar assistindo (por serviço) ----------
    // O progresso vem do próprio app (window.core). Cada título aparece no
    // catálogo do serviço onde ele está; o que não está em nenhum vai para o CineTime.
    var SERVICE_IDS = ['netflix', 'disney', 'prime', 'hbo', 'appletv', 'paramount', 'globoplay', 'clarovideo'];
    var idsCache = {};
    // data/<svc>-ids.json: só os códigos dos títulos do serviço (bem menor que as listas completas)
    function loadIds(svc) {
        if (!idsCache[svc]) {
            idsCache[svc] = fetch('data/' + svc + '-ids.json?v=' + DATA_VERSION)
                .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
                .catch(function () {
                    return Promise.all([svc + '-movie', svc + '-series'].map(function (f) {
                        return loadCatalog({ file: f }).catch(function () { return []; });
                    })).then(function (r) { return r[0].concat(r[1]).map(function (m) { return m.id; }); });
                });
        }
        return idsCache[svc];
    }

    function prefetch(s) {
        if (s.id === 'cinemateca') {
            sectionsFor(s, 'Início').slice(0, 2).forEach(function (sec) { loadCatalog(sec).catch(function () {}); });
        } else {
            loadHome(s.id).catch(function () {});
            if (s.id === 'netflix') loadCatalog({ file: 'netflix-top10-br-movie' }).catch(function () {});
        }
    }

    function toSet(lists) {
        var set = {};
        lists.forEach(function (l) { (l || []).forEach(function (x) { set[typeof x === 'string' ? x : x.id] = true; }); });
        return set;
    }

    // códigos dos títulos de um serviço; o CineTime usa os seus próprios arquivos
    function idsOf(s) {
        if (s.id === 'cinemateca') {
            return Promise.all(['cine-cinemas', 'cine-classicos', 'cine-movie', 'cine-series'].map(function (f) {
                return loadCatalog({ file: f }).catch(function () { return []; });
            }).concat([Promise.resolve(foundInSearch('all'))])).then(toSet);
        }
        var extra = s.id === 'disney' ? ['disney-disney', 'disney-pixar', 'disney-marvel', 'disney-starwars', 'disney-natgeo'].map(function (f) {
            return loadCatalog({ file: f }).catch(function () { return []; });
        }) : [];
        return Promise.all([loadIds(s.id)].concat(extra)).then(toSet);
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
        var pct = typeof item.progress === 'number' ? item.progress : (st.duration > 0 && st.timeOffset > 0 ? st.timeOffset / st.duration * 100 : 0);
        if (pct > 0) {
            var bar = el('span', 'gf-progress');
            var fill = el('span', 'gf-progress-fill');
            fill.style.width = Math.min(100, Math.max(3, pct)).toFixed(1) + '%';
            bar.appendChild(fill);
            b.appendChild(bar);
        }
        // segurar o dedo no pôster abre a opção de remover (e zera a linha do tempo)
        var pressed = false;
        b._gfItem = item;
        onLongPress(b, function () { pressed = true; continueMenu(item, b); });
        // tocar retoma de onde parou, quando o app tiver esse atalho
        var link = item.deepLinks && (item.deepLinks.player || item.deepLinks.metaDetailsStreams || item.deepLinks.metaDetailsVideos);
        b.addEventListener('click', function (e) {
            if (pressed) { pressed = false; e.preventDefault(); e.stopImmediatePropagation(); return; }
            if (!link) return;
            e.stopImmediatePropagation();
            // app com VLC: abre direto no player, sem passar pela tela do player do site
            if (window.gfPlayNative && /^#?\/player\//.test(link) && window.gfPlayNative(link)) return;
            saveScroll();
            window.gfGo(link);
        }, true);
        return b;
    }

    function onLongPress(node, fn) {
        var timer = null, x = 0, y = 0;
        function cancel() { clearTimeout(timer); timer = null; }
        node.addEventListener('pointerdown', function (e) {
            if (e.button) return;
            x = e.clientX; y = e.clientY;
            cancel();
            timer = setTimeout(function () { timer = null; if (navigator.vibrate) try { navigator.vibrate(15); } catch (er) {} fn(); }, 500);
        });
        node.addEventListener('pointermove', function (e) { if (timer && Math.abs(e.clientX - x) + Math.abs(e.clientY - y) > 12) cancel(); });
        ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) { node.addEventListener(ev, cancel); });
        // o menu do sistema (copiar imagem etc.) não aparece
        node.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    }

    function continueMenu(item, poster) {
        var old = document.getElementById('gf-cw-menu');
        if (old) old.remove();
        var root = el('div', 'gf-sheet');
        root.id = 'gf-cw-menu';
        function close() { root.classList.add('is-closing'); setTimeout(function () { root.remove(); }, 180); }
        root.addEventListener('click', function (e) { if (e.target === root) close(); });
        var card = el('div', 'gf-sheet-card gf-cw-card');
        card.setAttribute('role', 'dialog');
        var head = el('div', 'gf-sheet-head');
        var tt = el('div', 'gf-sheet-titles');
        tt.appendChild(el('div', 'gf-sheet-title', item.name || 'Título'));
        tt.appendChild(el('div', 'gf-sheet-sub', 'Continuar assistindo'));
        head.appendChild(tt);
        var x = el('button', 'gf-sheet-close', '✕');
        x.type = 'button'; x.setAttribute('aria-label', 'Fechar');
        x.addEventListener('click', close);
        head.appendChild(x);
        card.appendChild(head);
        var list = el('div', 'gf-sheet-list');
        var rm = el('button', 'gf-sheet-item gf-cw-remove');
        rm.type = 'button';
        rm.appendChild(el('span', 'gf-sheet-q', 'Remover de Continuar assistindo'));
        rm.appendChild(el('span', 'gf-sheet-d', 'A linha do tempo volta para o início.'));
        rm.addEventListener('click', function () {
            var c = window.core && (window.core.transport || window.core);
            if (window.gfRewind) window.gfRewind(item._id);
            else if (c && c.dispatch) {
                try {
                    c.dispatch({ action: 'Ctx', args: { action: 'RewindLibraryItem', args: item._id } });
                    c.dispatch({ action: 'Ctx', args: { action: 'DismissNotificationItem', args: item._id } });
                } catch (e) {}
            }
            close();
            var row = poster.closest('.gf-row');
            poster.remove();
            if (row && !row.querySelector('.gf-poster')) row.hidden = true;
        });
        list.appendChild(rm);
        var cancel = el('button', 'gf-sheet-item gf-cw-cancel');
        cancel.type = 'button';
        cancel.appendChild(el('span', 'gf-sheet-q', 'Cancelar'));
        cancel.addEventListener('click', close);
        list.appendChild(cancel);
        card.appendChild(list);
        root.appendChild(card);
        document.body.appendChild(root);
    }

    function renderContinue(s) {
        var row = el('section', 'gf-row');
        row.hidden = true;
        row.appendChild(el('h2', 'gf-row-title', 'Continuar assistindo'));
        var track = el('div', 'gf-row-track');
        row.appendChild(track);
        getContinueItems().then(function (items) {
            if (!items.length) return null; // nada em andamento: não baixa nenhuma lista
            return Promise.all([items, idsOf(s), s.id === 'cinemateca'
                ? Promise.all(SERVICE_IDS.map(loadIds)).then(toSet)
                : null]);
        }).then(function (r) {
                if (!r) return;
                var items = r[0], mine = r[1], onServices = r[2];
                var list = items.filter(function (it) {
                    if (!it || !it._id) return false;
                    if (mine[it._id]) return true;
                    // CineTime também recebe o que não está em nenhum serviço
                    return s.id === 'cinemateca' && onServices && !onServices[it._id];
                });
                if (!list.length) return;
                list.forEach(function (it) { track.appendChild(continuePoster(it)); });
                row.hidden = false;
            });
        return row;
    }

    // o VLC do app salvou o progresso: atualiza a fileira Continuar assistindo que estiver na tela
    window.gfRefreshContinue = function () {
        var root = document.getElementById('gf-home');
        var s = current && find(current);
        if (!root || !s) return;
        var old = [].filter.call(root.querySelectorAll('.gf-row'), function (r) {
            var t = r.querySelector('.gf-row-title');
            return t && t.textContent === 'Continuar assistindo';
        })[0];
        if (old) setTimeout(function () { old.replaceWith(renderContinue(s)); }, 300);
    };

    function renderCatalog(root, s, tabSwitch) {
        // mantém a barra de abas na mesma posição lateral ao trocar de aba
        var oldTabs = tabSwitch && root.querySelector('.gf-tabs');
        var tabsScroll = oldTabs ? oldTabs.scrollLeft : 0;
        root.innerHTML = '';
        var page = el('div', 'gf-svc' + (tabSwitch ? ' is-tab-switch' : ''));
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
                renderCatalog(root, s, true);
            });
            bar.appendChild(b);
        });
        page.appendChild(bar);
        if (tabsScroll) requestAnimationFrame(function () { bar.scrollLeft = tabsScroll; });

        var body = el('div', 'gf-tab-body');
        {
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

    // a cor do serviço só vale enquanto a Home (com o catálogo aberto) está na tela
    function onHome() { return /^#?\/?$/.test(location.hash); }
    var wasHome = true;
    function markRoute() {
        var search = /^#\/search/.test(location.hash);
        var prev = document.documentElement.getAttribute('data-gf-route');
        var m = location.hash.match(/^#\/([a-z]+)/);
        var now = search ? 'search' : (onHome() ? 'home' : (m ? m[1] : 'other'));
        document.documentElement.setAttribute('data-gf-route', now);
        // ao abrir a busca pelo botão de baixo, já deixa o teclado pronto
        if (search && prev !== 'search') setTimeout(function () {
            var input = document.querySelector('[class*="search-bar-container"] input');
            if (input) input.focus();
        }, 250);
    }
    markRoute();
    function markView() {
        document.documentElement.setAttribute('data-gf-view', current ? 'catalog' : 'grid');
    }
    function syncTheme() {
        markRoute();
        markView();
        var home = onHome();
        applyTheme(home && current ? find(current) : null);
        if (home && !wasHome && current && lastScroll) scrollBackTo(lastScroll);
        wasHome = home;
    }
    window.addEventListener('hashchange', syncTheme);
    window.addEventListener('popstate', syncTheme);
    // a setinha de voltar do app troca de página sem avisar (pushState); por isso escutamos também
    ['pushState', 'replaceState'].forEach(function (fn) {
        var orig = history[fn];
        history[fn] = function () {
            var r = orig.apply(this, arguments);
            try { syncTheme(); } catch (e) {}
            return r;
        };
    });

    // tocar em "Home" na barra, com um catálogo aberto, volta para a tela dos serviços
    document.addEventListener('click', function (e) {
        var a = e.target && e.target.closest && e.target.closest('a[href="#/"]');
        if (!a || !current || !onHome()) return;
        if (!a.closest('[class*="vertical-nav-bar"]')) return;
        e.preventDefault();
        current = null;
        currentTab = 'Início';
        pendingScroll = 0;
        try { history.replaceState({}, '', location.href); } catch (err) {}
        render();
    }, true);

    function render() {
        var root = document.getElementById('gf-home');
        if (!root) return;
        var s = current && find(current);
        applyTheme(s);
        markView();
        if (s) renderCatalog(root, s); else renderGrid(root);
        if (!pendingScroll && root.parentNode) root.parentNode.scrollTop = 0;
    }

    function open(id, push) {
        current = id;
        currentTab = 'Início';
        if (push) history.pushState({ gfService: id }, '', location.href);
        render();
    }

    window.addEventListener('popstate', function (e) {
        // só reage na Home; abrir um título (outra rota) não deve recriar o catálogo
        if (!/^#?\/?$/.test(location.hash)) return;
        var id = e.state && e.state.gfService;
        if (id !== current) { current = id || null; currentTab = (e.state && e.state.gfTab) || 'Início'; render(); }
        else if (id && e.state && e.state.gfScroll) {
            // voltou de um título para o mesmo catálogo: devolve a lista para onde estava
            scrollBackTo(e.state.gfScroll);
        }
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
        pendingScroll = (current && history.state && history.state.gfScroll) || 0;
        render();
        restoreScroll();
    }

    var mountQueued = false;
    new MutationObserver(function () {
        if (mountQueued) return;
        mountQueued = true;
        requestAnimationFrame(function () { mountQueued = false; mount(); });
    }).observe(document.documentElement, { childList: true, subtree: true });
    mount();
})();
