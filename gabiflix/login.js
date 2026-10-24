// GabiFlix: sem conta conectada, o app abre direto na tela de entrada (conta do Stremio).
(function () {
    if (window.__gfTestAnon) return; // usado só nos testes automáticos
    var tries = 0;
    function check() {
        var core = window.core && (window.core.transport || window.core);
        if (!core || typeof core.getState !== 'function') { if (++tries < 80) setTimeout(check, 250); return; }
        Promise.resolve(core.getState('ctx')).then(function (ctx) {
            if (!ctx || !ctx.profile) { if (++tries < 80) setTimeout(check, 250); return; }
            if (!ctx.profile.auth && !/^#\/intro/.test(location.hash)) location.replace('#/intro');
        }).catch(function () { if (++tries < 80) setTimeout(check, 250); });
    }
    check();
    // depois de sair da conta, volta para a entrada também
    window.addEventListener('hashchange', function () { tries = 0; setTimeout(check, 400); });
})();
