// GabiFlix: botão "Atualizar" do menu.
// Apaga o cache do app, baixa de novo os arquivos principais e recarrega com a versão mais nova.
(function () {
    // depois de atualizar, tira o marcador "?u=" do endereço
    if (/[?&]u=\d+/.test(location.search)) {
        try { history.replaceState(history.state, '', location.pathname + location.hash); } catch (e) {}
    }

    function overlay() {
        var o = document.createElement('div');
        o.className = 'gf-updating';
        o.setAttribute('role', 'status');
        o.innerHTML = '<div class="gf-updating-box"><span class="gf-updating-spin" aria-hidden="true"></span><span>Atualizando o GabiFlix…</span></div>';
        document.body.appendChild(o);
    }

    window.gfUpdateApp = function () {
        if (window.__gfUpdating) return;
        window.__gfUpdating = true;
        overlay();
        var tasks = [];
        if ('serviceWorker' in navigator) {
            tasks.push(navigator.serviceWorker.getRegistrations().then(function (regs) {
                return Promise.all(regs.map(function (r) { return r.unregister(); }));
            }).catch(function () {}));
        }
        if (window.caches && caches.keys) {
            tasks.push(caches.keys().then(function (keys) {
                return Promise.all(keys.map(function (k) { return caches.delete(k); }));
            }).catch(function () {}));
        }
        // força o navegador a buscar a versão nova dos arquivos principais
        var files = ['index.html', 'manifest.json', 'service-worker.js'];
        [].forEach.call(document.querySelectorAll('script[src], link[rel="stylesheet"][href]'), function (n) {
            var u = n.getAttribute('src') || n.getAttribute('href');
            if (u && !/^(https?:)?\/\//.test(u)) files.push(u);
        });
        files.forEach(function (f) { tasks.push(fetch(f, { cache: 'reload' }).catch(function () {})); });
        var done = false;
        function go() {
            if (done) return; done = true;
            location.replace(location.pathname + '?u=' + Date.now() + location.hash);
        }
        Promise.all(tasks).then(go, go);
        setTimeout(go, 8000); // não fica preso se a internet estiver lenta
    };
})();

// Tela de entrada: botão "Agora não" para voltar à Home sem conectar
(function () {
    function addSkip() {
        if (!/^#\/intro/.test(location.hash)) return;
        var box = document.querySelector('[class*="intro-container"] [class*="content-container"]');
        if (!box || box.querySelector('.gf-skip-login')) return;
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'gf-skip-login';
        b.textContent = 'Agora não';
        b.addEventListener('click', function () { if (window.gfGo) window.gfGo('/'); else location.hash = '#/'; });
        box.appendChild(b);
    }
    var queued = false;
    new MutationObserver(function () {
        if (queued || !/^#\/intro/.test(location.hash)) return;
        queued = true;
        requestAnimationFrame(function () { queued = false; addSkip(); });
    }).observe(document.documentElement, { childList: true, subtree: true });
    window.addEventListener('hashchange', addSkip);
    addSkip();
})();

// Navegação que o app entende: assim a setinha de voltar do app volta para a tela anterior
// (abrir uma página com location.hash fazia a setinha cair na Home ou na página antiga).
window.gfGo = function (hash) {
    hash = '#' + String(hash || '/').replace(/^#/, '');
    if (location.hash === hash) return;
    var st = history.state || {};
    var idx = typeof st.idx === 'number' ? st.idx : 0;
    try {
        history.pushState({ usr: null, key: Math.random().toString(36).slice(2, 10), idx: idx + 1 }, '', location.pathname + location.search + hash);
        window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
    } catch (e) {
        location.hash = hash;
    }
};
