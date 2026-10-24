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
