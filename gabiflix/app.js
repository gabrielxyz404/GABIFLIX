// GabiFlix: ajustes quando o site roda dentro do app Android.
// O app tem o VLC embutido; aqui ligamos o "reprodutor externo = VLC" uma vez,
// para todo vídeo abrir no player do app.
(function () {
    var inApp = /GabiFlixApp\//.test(navigator.userAgent);
    if (!inApp) return;
    document.documentElement.setAttribute('data-gf-app', 'android');

    var KEY = 'gf-app-vlc-ready';
    try { if (localStorage.getItem(KEY)) return; } catch (e) {}

    var tries = 0;
    (function setVlc() {
        var core = window.core && (window.core.transport || window.core);
        if (!core || typeof core.getState !== 'function') {
            if (++tries < 60) setTimeout(setVlc, 500);
            return;
        }
        Promise.resolve(core.getState('ctx')).then(function (ctx) {
            var settings = ctx && ctx.profile && ctx.profile.settings;
            if (!settings) { if (++tries < 60) setTimeout(setVlc, 500); return; }
            if (settings.playerType !== 'vlc') {
                core.dispatch({ action: 'Ctx', args: { action: 'UpdateSettings', args: Object.assign({}, settings, { playerType: 'vlc' }) } });
            }
            try { localStorage.setItem(KEY, '1'); } catch (e) {}
        }).catch(function () { if (++tries < 60) setTimeout(setVlc, 500); });
    })();
})();
