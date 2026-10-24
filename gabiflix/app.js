// GabiFlix: ajustes quando o site roda dentro do app Android.
// Os vídeos tocam no player do próprio Stremio. Versões antigas do GabiFlix ligavam o
// "reprodutor externo = VLC"; aqui isso é desfeito uma vez, voltando ao player do Stremio.
(function () {
    var inApp = /GabiFlixApp\//.test(navigator.userAgent);
    if (!inApp) return;
    document.documentElement.setAttribute('data-gf-app', 'android');

    var KEY = 'gf-app-player-stremio';
    try { if (localStorage.getItem(KEY)) return; } catch (e) {}

    var tries = 0;
    (function usePlayerStremio() {
        var core = window.core && (window.core.transport || window.core);
        if (!core || typeof core.getState !== 'function') { if (++tries < 60) setTimeout(usePlayerStremio, 500); return; }
        Promise.resolve(core.getState('ctx')).then(function (ctx) {
            var settings = ctx && ctx.profile && ctx.profile.settings;
            if (!settings) { if (++tries < 60) setTimeout(usePlayerStremio, 500); return; }
            if (settings.playerType === 'vlc') {
                core.dispatch({ action: 'Ctx', args: { action: 'UpdateSettings', args: Object.assign({}, settings, { playerType: null }) } });
            }
            try { localStorage.setItem(KEY, '1'); } catch (e) {}
        }).catch(function () { if (++tries < 60) setTimeout(usePlayerStremio, 500); });
    })();
})();
