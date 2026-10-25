// GabiFlix na TV (Fire TV Stick): navegação com as setas do controle remoto.
// Liga sozinho dentro do app na TV (o app avisa com "GabiFlixTV" no navegador) ou com ?tv=1 para testar.
// Também define window.gfBack (botão Voltar do app): fecha janelas abertas antes de voltar de página.
(function () {
    var html = document.documentElement;
    var tv = /GabiFlixTV/.test(navigator.userAgent) || /[?&]tv=1/.test(location.search);
    try { if (localStorage.getItem('gf-tv') === '1') tv = true; } catch (e) {}

    // rápido: só o tamanho na tela (escondido = tamanho zero). Nada de getComputedStyle, que pesa no Fire TV.
    function visible(el) {
        if (!el || !el.isConnected) return false;
        var r = el.getBoundingClientRect();
        return r.width >= 4 && r.height >= 4;
    }

    // ---------- Voltar ----------
    function tvRefocus() { if (tv) refocus(); } // no celular não há foco para recuperar
    function openLayer() {
        return document.getElementById('gf-cw-menu') || document.getElementById('gf-sheet') ||
            document.querySelector('[class*="nav-menu-container"], [class*="modal-container"], [class*="menu-container"][class*="open"]');
    }
    window.gfBack = function () {
        var cw = document.getElementById('gf-cw-menu');
        if (cw) { cw.remove(); tvRefocus(); return true; }
        var sheet = document.getElementById('gf-sheet');
        if (sheet) { var x = sheet.querySelector('.gf-sheet-close'); if (x) x.click(); else sheet.remove(); setTimeout(tvRefocus, 250); return true; }
        var menu = document.querySelector('[class*="nav-menu-container"]');
        if (menu && visible(menu)) {
            var btn = document.querySelector('[class*="horizontal-nav-bar"] [class*="menu-button"], [class*="horizontal-nav-bar"] [title="Menu"]');
            document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
            if (btn) btn.click();
            setTimeout(tvRefocus, 200);
            return true;
        }
        var h = location.hash;
        var onHome = !h || h === '#' || h === '#/';
        if (onHome) {
            var back = document.querySelector('#gf-home .gf-back');
            if (back) { back.click(); setTimeout(tvRefocus, 300); return true; } // catálogo -> tela dos serviços
            return false; // tela inicial: o app fecha
        }
        // sem página anterior (o app abriu direto aqui): vai para a Home
        if (history.state && history.state.idx === 0) { location.hash = '#/'; return true; }
        history.back();
        return true;
    };

    if (!tv) return;
    html.setAttribute('data-gf-tv', '1');
    // a animação de troca de página tira uma "foto" da tela inteira: pesada demais no Fire TV
    try { document.startViewTransition = undefined; } catch (e) {}

    var SEL = 'a[href], button:not([disabled]), input:not([type="hidden"]):not([type="file"]), select, textarea, [tabindex]:not([tabindex="-1"]), [role="button"]';

    function scope() {
        var layer = openLayer();
        return layer && visible(layer) ? layer : document;
    }
    // botões do Stremio que não recebem foco pelo teclado (menu ☰ do topo e as opções dele): passam a receber
    var FIX = '[class*="horizontal-nav-bar"] [class*="nav-menu-popup-label"][tabindex="-1"], [class*="nav-menu-container"] [tabindex="-1"], [class*="modal-dialog"] [class*="button"][tabindex="-1"]';
    function fixTabindex() {
        [].forEach.call(document.querySelectorAll(FIX), function (el) { el.setAttribute('tabindex', '0'); });
    }

    // só o que está perto da tela (uma tela acima e duas abaixo); cada item já vem com posição e região
    function items() {
        var out = [], H = innerHeight, list = scope().querySelectorAll(SEL);
        for (var i = 0; i < list.length; i++) {
            var el = list[i], r = el.getBoundingClientRect();
            if (r.width < 4 || r.height < 4 || r.bottom < -H || r.top > H * 2.5) continue;
            out.push({ el: el, r: r, reg: null });
        }
        return out;
    }
    function regionOf(it) { if (!it.reg) it.reg = region(it.el); return it.reg; }
    function candidates() { return items().map(function (it) { return it.el; }); }
    function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

    // regiões: barra lateral (Home, Mais Tarde...), barra de cima e o conteúdo.
    // Só se passa de uma para outra na direção natural (esquerda = lateral, cima = barra de cima).
    function region(el) {
        if (el.closest('[class*="vertical-nav-bar"]')) return 'side';
        if (el.closest('[class*="horizontal-nav-bar"]')) return 'top';
        return 'main';
    }
    function allowed(from, to, dir) {
        if (from === to) return true;
        if (to === 'side') return dir === 'left';
        if (from === 'side') return dir === 'right' || (to === 'top' && dir === 'up');
        if (to === 'top') return dir === 'up';
        if (from === 'top') return dir === 'down';
        return true;
    }

    // list: itens de items() (com posição); devolve o elemento escolhido
    function best(from, dir, list) {
        var a = from.getBoundingClientRect(), ca = center(a), pick = null, score = Infinity;
        var rf = region(from);
        // esquerda/direita: fica na mesma linha se houver algo nela
        if (dir === 'left' || dir === 'right') {
            var row = list.filter(function (it) {
                var b = it.r;
                return it.el !== from && b.bottom > a.top + 6 && b.top < a.bottom - 6 && (dir === 'right' ? b.left >= a.right - 4 : b.right <= a.left + 4);
            });
            // nada na mesma linha: só sai para outra região (ex.: barra lateral), não pula de linha
            list = row.length ? row : list.filter(function (it) { return regionOf(it) !== rf; });
        }
        list.forEach(function (it) {
            var el = it.el;
            if (el === from || el.contains(from) || from.contains(el)) return;
            if (!allowed(rf, regionOf(it), dir)) return;
            var b = it.r, cb = center(b);
            var main, cross;
            if (dir === 'right') { if (b.left < a.right - 4 && cb.x <= ca.x + 4) return; main = b.left - a.right; cross = Math.abs(cb.y - ca.y); }
            if (dir === 'left') { if (b.right > a.left + 4 && cb.x >= ca.x - 4) return; main = a.left - b.right; cross = Math.abs(cb.y - ca.y); }
            if (dir === 'down') { if (b.top < a.bottom - 4 && cb.y <= ca.y + 4) return; main = b.top - a.bottom; cross = Math.abs(cb.x - ca.x); }
            if (dir === 'up') { if (b.bottom > a.top + 4 && cb.y >= ca.y - 4) return; main = a.top - b.bottom; cross = Math.abs(cb.x - ca.x); }
            var s = Math.max(0, main) + cross * (dir === 'left' || dir === 'right' ? 3 : 1.4);
            if (s < score) { score = s; pick = el; }
        });
        return pick;
    }

    function focusEl(el) {
        if (!el) return;
        try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
        // rolagem instantânea: a "suave" engasga no Fire TV
        try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e) { el.scrollIntoView(false); }
    }

    function firstInContent() {
        var list = candidates();
        var pref = list.filter(function (el) { return el.matches('.gf-hero-btn.is-primary, .gf-d-play, #gf-home .gf-grid .gf-card, .gf-sheet-item, .gf-cw-remove'); })[0];
        if (pref) return pref;
        var content = list.filter(function (el) { return !el.closest('[class*="vertical-nav-bar"], [class*="horizontal-nav-bar"]'); });
        // página ainda carregando: espera o conteúdo em vez de cair no menu de cima
        if (!content.length) return null;
        var pool = content.filter(function (el) {
            var r = el.getBoundingClientRect();
            return r.top >= 0 && r.top < innerHeight && r.left < innerWidth;
        });
        if (!pool.length) pool = content;
        pool.sort(function (x, y) {
            var a = x.getBoundingClientRect(), b = y.getBoundingClientRect();
            return (Math.round(a.top / 20) - Math.round(b.top / 20)) || (a.left - b.left);
        });
        return pool[0] || null;
    }

    function current() {
        var a = document.activeElement;
        return a && a !== document.body && a !== html && visible(a) && scope().contains(a) ? a : null;
    }

    // quando a tela é redesenhada (ex.: troca de aba), volta para o "mesmo" botão
    var lastSig = '';
    function sig(el) {
        return el.tagName + '|' + String(el.className).replace(/\bis-[a-z-]+/g, '').trim() + '|' + (el.getAttribute('data-service') || el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 40);
    }
    var routeSig = {};
    function routeKey() { return location.hash + '|' + (html.getAttribute('data-gf-view') || ''); }
    document.addEventListener('focusin', function (e) {
        if (!e.target || !e.target.getAttribute) return;
        lastSig = sig(e.target);
        if (!openLayer()) routeSig[routeKey()] = lastSig;
    });
    var retries = 0;
    var pageChangedAt = 0;
    function refocus(newPage) {
        if (current()) return;
        if (Date.now() - pageChangedAt < 2500) newPage = true;
        // página nova: volta para onde estava nela (se já visitada) ou vai para o começo do conteúdo
        var want = openLayer() ? lastSig : (newPage === true ? routeSig[routeKey()] : (routeSig[routeKey()] || lastSig));
        var same = want && candidates().filter(function (el) { return sig(el) === want; })[0];
        var el = same || firstInContent();
        if (el) { retries = 0; focusEl(el); }
        else if (retries++ < 12) setTimeout(function () { refocus(newPage); }, 400);
    }

    function isTyping(el) {
        return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') && !/button|checkbox|radio|range/.test(el.type || '');
    }

    var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
    window.addEventListener('keydown', function (e) {
        // no player do site (trailers) as setas são do próprio player
        if (/^#\/player\//.test(location.hash)) return;
        var dir = DIRS[e.key];
        var cur = current();
        if (dir) {
            // digitando: esquerda/direita mexem no texto; cima/baixo saem do campo
            if (isTyping(cur) && (dir === 'left' || dir === 'right')) return;
            e.preventDefault();
            e.stopPropagation();
            if (!cur) { focusEl(firstInContent()); return; }
            var list = items(), next = null;
            // entrando na barra lateral: vai para a página atual (Home, Mais Tarde...)
            if (dir === 'left' && region(cur) !== 'side') {
                var side = best(cur, dir, list);
                if (side && region(side) === 'side') {
                    next = list.filter(function (it) { return regionOf(it) === 'side' && /\bselected\b/.test(it.el.className + ' ' + (it.el.parentElement && it.el.parentElement.className)); }).map(function (it) { return it.el; })[0] || side;
                } else next = side;
            } else next = best(cur, dir, list);
            if (next) focusEl(next);
            else if (dir === 'down' || dir === 'up') {
                // nada mais nessa direção: rola um pouco a lista
                var sc = cur.closest('[class*="board-content"], [class*="metadetails-content"], [class*="content-container"]') || document.scrollingElement;
                if (sc) sc.scrollBy({ top: dir === 'down' ? innerHeight * 0.4 : -innerHeight * 0.4, behavior: 'smooth' });
            }
            return;
        }
        if (e.key === 'Enter' && cur && !isTyping(cur)) {
            // elementos que não são botões de verdade precisam do clique
            // os botões do próprio Stremio já reagem ao OK sozinhos (clicar de novo abriria e fecharia na hora)
            if (cur.tagName !== 'BUTTON' && cur.tagName !== 'A' && cur.tagName !== 'INPUT' && !/button-container/.test(String(cur.className))) {
                e.preventDefault();
                var r = cur.getBoundingClientRect(), o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0 };
                ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
                    try { cur.dispatchEvent(t.indexOf('pointer') === 0 ? new PointerEvent(t, o) : new MouseEvent(t, o)); } catch (er) {}
                });
            }
            return;
        }
        if (e.key === 'ContextMenu') { e.preventDefault(); window.gfTvMenu(); }
    }, true);

    // botão ☰ do controle: no Continuar assistindo, abre "Remover"
    window.gfTvMenu = function () {
        var cur = current();
        var p = cur && cur.closest && cur.closest('.gf-poster');
        if (p && typeof window.gfContinueMenuFor === 'function' && window.gfContinueMenuFor(p)) {
            setTimeout(function () { focusEl(document.querySelector('#gf-cw-menu .gf-cw-remove')); }, 60);
        }
    };

    // janelas que abrem (fontes, remover): o foco vai para dentro delas
    var obsQueued = false;
    new MutationObserver(function () {
        if (obsQueued) return;
        obsQueued = true;
        setTimeout(function () { obsQueued = false; onDomChange(); }, 120);
    }).observe(document.documentElement, { childList: true, subtree: true });
    function onDomChange() {
        fixTabindex();
        var layer = openLayer();
        // a lista de fontes chegou depois de abrir: o foco sai do "fechar" e vai para a primeira fonte
        var a = document.activeElement;
        if (layer && a && a.classList && a.classList.contains('gf-sheet-close') && !layer.__gfMoved) {
            var item = layer.querySelector('.gf-sheet-item');
            if (item) { layer.__gfMoved = true; setTimeout(function () { focusEl(item); }, 30); }
        }
        if (layer && visible(layer) && !layer.contains(document.activeElement)) {
            var first = layer.querySelector('.gf-sheet-item') || layer.querySelector('.gf-cw-remove') ||
                layer.querySelector('.gf-sheet-addons') || layer.querySelector('button, a[href], [tabindex]');
            if (first) setTimeout(function () { if (!layer.contains(document.activeElement)) focusEl(first); }, 30);
        } else if (!current()) {
            clearTimeout(window.__gfTvRf);
            window.__gfTvRf = setTimeout(refocus, 250);
        }
    }

    // página nova: o foco vai para o conteúdo (mesmo se estava no menu ou na barra lateral)
    var lastRoute = location.hash;
    function routeChanged() {
        if (location.hash === lastRoute) return;
        lastRoute = location.hash;
        pageChangedAt = Date.now();
        setTimeout(function () {
            var c = current();
            if (c && region(c) !== 'main' && !openLayer()) { try { c.blur(); } catch (e) {} }
            refocus(true);
        }, 450);
    }
    window.addEventListener('hashchange', routeChanged);
    window.addEventListener('popstate', routeChanged);
    setTimeout(refocus, 1200);
})();
