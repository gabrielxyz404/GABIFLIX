// GabiFlix: tela inicial com botões de serviços de streaming.
// Substitui os catálogos padrão da tela inicial.
(function () {
    // color: cor do nome no botão, inspirada na cor de cada serviço
    var SERVICES = [
        { id: 'netflix', name: 'Netflix', color: '#e50914' },
        { id: 'disney', name: 'Disney+', color: '#3aa3ff' },
        { id: 'prime', name: 'Prime Video', color: '#1ba8e0' },
        { id: 'hbo', name: 'HBO Max', color: '#a36bff' },
        { id: 'appletv', name: 'Apple TV+', color: '#f5f5f7' },
        { id: 'paramount', name: 'Paramount+', color: '#2f7dff' },
        { id: 'globoplay', name: 'Globoplay', color: '#ff4a6e' },
        { id: 'clarovideo', name: 'Claro Video', color: '#ff2a2a' }
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
            // Logo opcional: coloque gabiflix/logos/<id>.png no repositório.
            var logo = el('img', 'gf-card-logo');
            logo.alt = '';
            logo.decoding = 'async';
            logo.onload = function () { b.classList.add('has-logo'); };
            logo.onerror = function () { logo.remove(); };
            logo.src = 'gabiflix/logos/' + s.id + '.png?v=' + LOGO_VERSION;
            b.appendChild(logo);
            b.appendChild(el('span', 'gf-card-name', s.name));
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

    // Fileiras que aparecem em cada aba
    function rowsFor(tab) {
        switch (tab) {
            case 'Início': return ['Em alta', 'Lançamentos', 'Continue assistindo', 'Recomendados para você'];
            case 'Séries': return ['Séries em alta', 'Novas temporadas', 'Séries premiadas'];
            case 'Filmes': return ['Filmes em alta', 'Lançamentos', 'Ação e aventura', 'Comédia'];
            case 'Bombando': return ['Top 10 de hoje', 'Mais assistidos da semana'];
            case 'Originais': return ['Originais em destaque', 'Novos originais'];
            case 'Esportes': return ['Ao vivo agora', 'Próximos jogos', 'Melhores momentos'];
            case 'Infantil': return ['Para os pequenos', 'Animações', 'Família'];
            case 'Novelas': return ['Novelas no ar', 'Clássicas', 'Para maratonar'];
            case 'Ao vivo': return ['Canais ao vivo', 'Programação de hoje'];
            default: return [];
        }
    }

    var currentTab = 'Início';

    function backButton() {
        var back = el('button', 'gf-back');
        back.type = 'button';
        back.setAttribute('aria-label', 'Voltar');
        back.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        back.addEventListener('click', function () { history.back(); });
        return back;
    }

    function renderCatalog(root, s) {
        root.innerHTML = '';
        var page = el('div', 'gf-svc');
        page.style.setProperty('--gf-brand', s.color);

        var head = el('div', 'gf-svc-head');
        head.appendChild(backButton());
        head.appendChild(el('h1', 'gf-svc-name', s.name));
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
            var hero = el('div', 'gf-hero');
            hero.appendChild(el('span', 'gf-hero-tag', currentTab === 'Início' ? 'Em destaque' : currentTab));
            hero.appendChild(el('div', 'gf-hero-title', 'Nenhum título ainda'));
            hero.appendChild(el('div', 'gf-hero-text', 'O destaque de ' + s.name + ' vai aparecer aqui.'));
            var actions = el('div', 'gf-hero-actions');
            var play = el('span', 'gf-hero-btn is-primary', '▶ Assistir');
            var info = el('span', 'gf-hero-btn', 'Mais informações');
            actions.appendChild(play);
            actions.appendChild(info);
            hero.appendChild(actions);
            body.appendChild(hero);

            var wide = currentTab === 'Ao vivo';
            rowsFor(currentTab).forEach(function (title) {
                var row = el('section', 'gf-row');
                row.appendChild(el('h2', 'gf-row-title', title));
                var track = el('div', 'gf-row-track' + (wide ? ' is-wide' : ''));
                for (var i = 0; i < 8; i++) track.appendChild(el('div', 'gf-poster'));
                row.appendChild(track);
                body.appendChild(row);
            });
        }
        page.appendChild(body);
        root.appendChild(page);
    }

    function find(id) {
        for (var i = 0; i < SERVICES.length; i++) if (SERVICES[i].id === id) return SERVICES[i];
        return null;
    }

    function render() {
        var root = document.getElementById('gf-home');
        if (!root) return;
        var s = current && find(current);
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
        if (id !== current) { current = id || null; render(); }
    });

    function mount() {
        var content = document.querySelector('div[class^="board-content-"]:not([class*="container"])');
        if (!content || document.getElementById('gf-home')) return;
        var root = el('div');
        root.id = 'gf-home';
        content.insertBefore(root, content.firstChild);
        current = (history.state && history.state.gfService) || null;
        render();
    }

    new MutationObserver(mount).observe(document.documentElement, { childList: true, subtree: true });
    mount();
})();
