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

// ---------- VLC do app como player principal ----------
// Com o app novo (window.GabiFlixNative.playSession), todo vídeo com link direto toca no VLC do app:
// com legendas em português (o player busca no OpenSubtitles), começando de onde parou, e ao voltar o progresso
// é salvo no Stremio (Continuar assistindo). Trailers do YouTube continuam no player do site.
(function () {
    var N = window.GabiFlixNative;
    if (!N || typeof N.playSession !== 'function') return;
    document.documentElement.setAttribute('data-gf-vlc', '1');
    var SESSION = 'gf-vlc-session';

    function core() {
        var c = window.core && (window.core.transport || window.core);
        return c && typeof c.getState === 'function' ? c : null;
    }
    function b64ToBytes(s) {
        s = s.replace(/-/g, '+').replace(/_/g, '/');
        while (s.length % 4) s += '=';
        var bin = atob(s), out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }
    function decodeStream(part) {
        if (typeof DecompressionStream === 'undefined') return Promise.reject(new Error('sem DecompressionStream'));
        var ds = new Blob([b64ToBytes(decodeURIComponent(part))]).stream().pipeThrough(new DecompressionStream('deflate'));
        return new Response(ds).text().then(JSON.parse);
    }
    // "#/player/{stream}/{addon das fontes}/{addon do título}/{tipo}/{id}/{episódio}"
    function parseLink(link) {
        var m = String(link || '').match(/\/player\/([^?#]+)/);
        if (!m) return null;
        var p = m[1].split('/').map(function (x) { try { return decodeURIComponent(x); } catch (e) { return x; } });
        var pos = String(link).match(/[?&]position=(\d+(?:\.\d+)?)/); // segundos, vindo do Continuar assistindo
        return { rawStream: m[1].split('/')[0], streamBase: p[1] || null, metaBase: p[2] || null, type: p[3] || null, id: p[4] || null,
            videoId: p[5] || p[4] || null, position: pos ? Math.round(parseFloat(pos[1]) * 1000) : 0 };
    }

    // onde parou, segundo o Stremio (só se for o mesmo episódio)
    function startFor(id, videoId) {
        var c = core();
        if (!c) return Promise.resolve(0);
        function pick(lib) {
            var st = lib && lib.state;
            if (!st || !(st.timeOffset > 0)) return 0;
            if (st.video_id && videoId && st.video_id !== videoId) return 0;
            if (st.duration > 0 && st.timeOffset > st.duration * 0.95) return 0;
            return st.timeOffset;
        }
        return Promise.all([
            Promise.resolve(c.getState('meta_details')).catch(function () { return null; }),
            Promise.resolve(c.getState('continue_watching_preview')).catch(function () { return null; })
        ]).then(function (r) {
            var md = r[0], cw = r[1];
            if (md && md.libraryItem && md.libraryItem._id === id) { var a = pick(md.libraryItem); if (a) return a; }
            var it = cw && (cw.items || []).filter(function (x) { return x && x._id === id; })[0];
            return pick(it);
        }).catch(function () { return 0; });
    }

    var lastLaunch = 0;
    function launch(stream, info) {
        if (!stream || !/^https?:\/\//i.test(stream.url || '')) return false;
        // toques repetidos não abrem o player duas vezes
        if (Date.now() - lastLaunch < 4000) return true;
        lastLaunch = Date.now();
        try {
            localStorage.setItem(SESSION, JSON.stringify({ stream: stream, streamBase: info.streamBase, metaBase: info.metaBase,
                type: info.type, id: info.id, videoId: info.videoId, at: Date.now() }));
        } catch (e) {}
        // abre na hora: as legendas PT o próprio player busca enquanto o vídeo carrega
        Promise.resolve(info.position > 0 ? info.position : startFor(info.id, info.videoId)).catch(function () { return 0; }).then(function (start) {
            try { N.playSession(stream.url, '[]', String(info.type || ''), String(info.videoId || ''), String(Math.round(start || 0))); } catch (e) {}
        });
        return true;
    }

    // usado pela página do título: true = o VLC cuidou
    window.gfPlayNative = function (link, stream) {
        var info = parseLink(link);
        if (!info) return false;
        if (stream) return launch(stream, info);
        decodeStream(info.rawStream).then(function (s) { launch(s, info); }).catch(function () {});
        return true;
    };

    // o progresso volta do VLC: salva no Stremio como o player do site faria
    var saving = false, pendingRewind = {};
    function rewindNow(id) {
        var c = core();
        if (!c || !id) return;
        try {
            c.dispatch({ action: 'Ctx', args: { action: 'RewindLibraryItem', args: id } });
            c.dispatch({ action: 'Ctx', args: { action: 'DismissNotificationItem', args: id } });
        } catch (e) {}
    }
    // "Remover" do Continuar assistindo: se o progresso ainda estiver sendo salvo, remove de novo no fim
    window.gfRewind = function (id) {
        rewindNow(id);
        if (saving) pendingRewind[id] = true;
    };
    window.gfNativeProgress = function (time, length, ended, mediaUrl) {
        var s = null;
        try { s = JSON.parse(localStorage.getItem(SESSION) || 'null'); } catch (e) {}
        var c = core();
        if (!s || !c || saving || !(time > 0)) return;
        // o progresso tem que ser do mesmo vídeo que foi aberto
        if (mediaUrl && s.stream && s.stream.url && s.stream.url !== mediaUrl) return;
        saving = true;
        if (ended && length > 0) time = length;
        c.dispatch({ action: 'Load', args: { model: 'Player', args: {
            stream: s.stream,
            streamRequest: s.streamBase && s.type && s.videoId ? { base: s.streamBase, path: { resource: 'stream', type: s.type, id: s.videoId, extra: [] } } : null,
            metaRequest: s.metaBase && s.type && s.id ? { base: s.metaBase, path: { resource: 'meta', type: s.type, id: s.id, extra: [] } } : null,
            subtitlesPath: null
        } } }, 'player');
        var tries = 0;
        (function wait() {
            Promise.resolve(c.getState('player')).then(function (st) {
                // o item da biblioteca carregado é o que o Stremio atualiza
                var ready = st && st.libraryItem;
                if (!ready && ++tries < 40) { setTimeout(wait, 150); return; }
                var tc = { action: 'Player', args: { action: 'TimeChanged', args: { time: Math.round(time), duration: Math.round(length || 0), device: 'android' } } };
                c.dispatch(tc, 'player');
                if (ended) {
                    // terminou: marca como visto (filme) ou o episódio como visto (série)
                    c.dispatch({ action: 'Player', args: { action: 'Ended' } }, 'player');
                    var meta = st && st.metaItem && st.metaItem.content;
                    var vid = meta && (meta.videos || []).filter(function (v) { return v && v.id === s.videoId; })[0];
                    if (s.type === 'series' && vid) c.dispatch({ action: 'Player', args: { action: 'MarkVideoAsWatched', args: [vid, true] } }, 'player');
                    else if (s.type !== 'series') c.dispatch({ action: 'Ctx', args: { action: 'LibraryItemMarkAsWatched', args: { id: s.id, is_watched: true } } });
                }
                setTimeout(function () {
                    c.dispatch({ action: 'Player', args: { action: 'PausedChanged', args: { paused: true } } }, 'player');
                    setTimeout(function () {
                        c.dispatch({ action: 'Unload' }, 'player');
                        try { localStorage.removeItem(SESSION); } catch (e) {}
                        saving = false;
                        var again = Object.keys(pendingRewind);
                        pendingRewind = {};
                        if (again.length) setTimeout(function () { again.forEach(rewindNow); }, 300);
                        // o Stremio leva alguns segundos para atualizar a lista: confere de novo
                        [300, 2500].forEach(function (ms) {
                            setTimeout(function () { if (typeof window.gfRefreshContinue === 'function') window.gfRefreshContinue(); }, ms);
                        });
                    }, 250);
                }, 150);
            }).catch(function () { saving = false; });
        })();
    };

    // se algum botão abrir o player do site (ex.: Continuar assistindo), o vídeo vai para o VLC
    var busy = '';
    function check() {
        var m = location.hash.match(/^#\/player\//);
        if (!m || busy === location.hash) return;
        busy = location.hash;
        var info = parseLink(location.hash);
        decodeStream(info.rawStream).then(function (stream) {
            if (!/^https?:\/\//i.test((stream && stream.url) || '')) return; // trailer do YouTube: fica no site
            if (launch(stream, info)) history.back();
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
