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

// App Android com o player embutido (window.GabiFlixNative): se algum botão abrir o player do site
// com um link direto (ex.: Continuar assistindo), o vídeo vai para o player embutido.
(function () {
    if (!window.GabiFlixNative) return;
    function b64ToBytes(s) {
        s = s.replace(/-/g, '+').replace(/_/g, '/');
        while (s.length % 4) s += '=';
        var bin = atob(s), out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }
    function decodeStream(part) {
        if (typeof DecompressionStream === 'undefined') return Promise.reject();
        var ds = new Blob([b64ToBytes(decodeURIComponent(part))]).stream().pipeThrough(new DecompressionStream('deflate'));
        return new Response(ds).text().then(JSON.parse);
    }
    var busy = '';
    function check() {
        var m = location.hash.match(/^#\/player\/([^/?]+)/);
        if (!m || busy === location.hash) return;
        busy = location.hash;
        decodeStream(m[1]).then(function (stream) {
            var url = stream && stream.url;
            if (!url || !/^https?:\/\//i.test(url)) return; // trailer do YouTube etc.: fica no player do site
            window.GabiFlixNative.play(url);
            history.back();
        }).catch(function () {}).then(function () { setTimeout(function () { busy = ''; }, 1500); });
    }
    window.addEventListener('hashchange', check);
    window.addEventListener('popstate', check);
    ['pushState', 'replaceState'].forEach(function (fn) {
        var orig = history[fn];
        history[fn] = function () { var r = orig.apply(this, arguments); setTimeout(check, 0); return r; };
    });
    check();
})();
