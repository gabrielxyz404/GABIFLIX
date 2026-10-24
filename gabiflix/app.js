// GabiFlix: ajustes quando o site roda dentro do app Android.
// O app tem o VLC embutido (toca qualquer áudio: AC3, DTS, E-AC3...). O player do site
// não toca muitos desses áudios, então dentro do app todo vídeo vai para o VLC:
//  1) a configuração "reprodutor = VLC" é conferida sempre que o app abre;
//  2) se algum botão ainda abrir o player do site (ex.: Continuar assistindo),
//     o vídeo é mandado para o VLC e a página volta para onde estava.
(function () {
    var inApp = /GabiFlixApp\//.test(navigator.userAgent);
    if (!inApp) return;
    document.documentElement.setAttribute('data-gf-app', 'android');

    function core() {
        var c = window.core && (window.core.transport || window.core);
        return c && typeof c.getState === 'function' ? c : null;
    }

    // 1) reprodutor = VLC
    var tries = 0;
    (function setVlc() {
        var c = core();
        if (!c) { if (++tries < 60) setTimeout(setVlc, 500); return; }
        Promise.resolve(c.getState('ctx')).then(function (ctx) {
            var settings = ctx && ctx.profile && ctx.profile.settings;
            if (!settings) { if (++tries < 60) setTimeout(setVlc, 500); return; }
            if (settings.playerType !== 'vlc') {
                c.dispatch({ action: 'Ctx', args: { action: 'UpdateSettings', args: Object.assign({}, settings, { playerType: 'vlc' }) } });
            }
        }).catch(function () { if (++tries < 60) setTimeout(setVlc, 500); });
    })();

    // 2) player do site -> VLC do app
    function b64ToBytes(s) {
        s = s.replace(/-/g, '+').replace(/_/g, '/');
        while (s.length % 4) s += '=';
        var bin = atob(s), out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }
    function decodeStream(part) {
        var bytes = b64ToBytes(decodeURIComponent(part));
        if (typeof DecompressionStream === 'undefined') return Promise.reject();
        var ds = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate'));
        return new Response(ds).text().then(JSON.parse);
    }
    var handling = '';
    function check() {
        var m = location.hash.match(/^#\/player\/([^/?]+)/);
        if (!m || handling === location.hash) return;
        handling = location.hash;
        decodeStream(m[1]).then(function (stream) {
            var url = stream && stream.url;
            if (!url || !/^https?:\/\//i.test(url)) return; // trailer do YouTube, torrent sem link: fica no player do site
            location.href = 'vlc://' + url;          // o app abre o player embutido
            setTimeout(function () { if (/^#\/player\//.test(location.hash)) history.back(); }, 150);
        }).catch(function () {}).then(function () { setTimeout(function () { handling = ''; }, 1500); });
    }
    window.addEventListener('hashchange', check);
    window.addEventListener('popstate', check);
    ['pushState', 'replaceState'].forEach(function (fn) {
        var orig = history[fn];
        history[fn] = function () { var r = orig.apply(this, arguments); setTimeout(check, 0); return r; };
    });
    check();
})();
