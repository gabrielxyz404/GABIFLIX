// GabiFlix: tela inicial com botões de serviços de streaming.
// Substitui os catálogos padrão da tela inicial.
(function () {
    var SERVICES = [
        { id: 'netflix', name: 'Netflix' },
        { id: 'disney', name: 'Disney+' },
        { id: 'prime', name: 'Prime Video' },
        { id: 'hbo', name: 'HBO Max' },
        { id: 'appletv', name: 'Apple TV+' },
        { id: 'paramount', name: 'Paramount+' },
        { id: 'globoplay', name: 'Globoplay' },
        { id: 'clarovideo', name: 'Claro Video' }
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

    function renderCatalog(root, s) {
        root.innerHTML = '';
        var head = el('div', 'gf-head');
        var back = el('button', 'gf-back');
        back.type = 'button';
        back.setAttribute('aria-label', 'Voltar');
        back.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        back.addEventListener('click', function () { history.back(); });
        head.appendChild(back);
        head.appendChild(el('h1', 'gf-title', s.name));
        root.appendChild(head);
        var empty = el('div', 'gf-empty');
        empty.appendChild(el('div', 'gf-empty-title', 'Catálogo vazio por enquanto'));
        empty.appendChild(el('div', 'gf-empty-text', 'Os títulos de ' + s.name + ' vão aparecer aqui.'));
        root.appendChild(empty);
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
