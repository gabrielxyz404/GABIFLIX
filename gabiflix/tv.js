// GabiFlix na TV (Fire TV Stick): navegação com as setas do controle remoto.
// Liga sozinho dentro do app na TV (o app avisa com "GabiFlixTV" no navegador) ou com ?tv=1 para testar.
// Também define window.gfBack (botão Voltar do app): fecha janelas abertas antes de voltar de página.
(function () {
    var html = document.documentElement;
    var tv = /GabiFlixTV/.test(navigator.userAgent) || /[?&]tv=1/.test(location.search);
    try { if (localStorage.getItem('gf-tv') === '1') tv = true; } catch (e) {}

    function visible(el) {
        if (!el || !el.isConnected) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 4 || r.height < 4) return false;
        if (r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) {
            // fora da tela, mas dentro de uma fileira/lista que rola: ainda vale
            var cs = getComputedStyle(el);
            if (cs.visibility === 'hidden' || cs.display === 'none') return false;
        }
        var s = getComputedStyle(el);
        return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05 && !el.closest('[hidden],[aria-hidden="true"]');
    }

    // ---------- Voltar ----------
    function openLayer() {
        return document.getElementById('gf-cw-menu') || document.getElementById('gf-sheet') ||
            document.querySelector('[class*="nav-menu-container"], [class*="modal-container"], [class*="menu-container"][class*="open"]');
    }
    window.gfBack = function () {
        var cw = document.getElementById('gf-cw-menu');
        if (cw) { cw.remove(); refocus(); return true; }
        var sheet = document.getElementById('gf-sheet');
        if (sheet) { var x = sheet.querySelector('.gf-sheet-close'); if (x) x.click(); else sheet.remove(); setTimeout(refocus, 250); return true; }
        var menu = document.querySelector('[class*="nav-menu-container"]');
        if (menu && visible(menu)) {
            var btn = document.querySelector('[class*="horizontal-nav-bar"] [class*="menu-button"], [class*="horizontal-nav-bar"] [title="Menu"]');
            document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
            if (btn) btn.click();
            setTimeout(refocus, 200);
            return true;
        }
        var h = location.hash;
        var onHome = !h || h === '#' || h === '#/';
        if (onHome) {
            var back = document.querySelector('#gf-home .gf-back');
            if (back) { back.click(); setTimeout(refocus, 300); return true; } // catálogo -> tela dos serviços
            return false; // tela inicial: o app fecha
        }
        // sem página anterior (o app abriu direto aqui): vai para a Home
        if (history.state && history.state.idx === 0) { location.hash = '#/'; return true; }
        history.back();
        return true;
    };

    if (!tv) return;
    html.setAttribute('data-gf-tv', '1');

    var SEL = 'a[href], button:not([disabled]), input:not([type="hidden"]), select, textarea, [tabindex]:not([tabindex="-1"]), [role="button"]';

    function scope() {
        var layer = openLayer();
        return layer && visible(layer) ? layer : document;
    }
    function candidates() {
        return [].filter.call(scope().querySelectorAll(SEL), visible);
    }
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

    function best(from, dir, list) {
        var a = from.getBoundingClientRect(), ca = center(a), pick = null, score = Infinity;
        var rf = region(from);
        list.forEach(function (el) {
            if (el === from || el.contains(from) || from.contains(el)) return;
            if (!allowed(rf, region(el), dir)) return;
            var b = el.getBoundingClientRect(), cb = center(b);
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
        try { el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(false); }
    }

    function firstInContent() {
        var list = candidates();
        var pref = list.filter(function (el) { return el.matches('.gf-hero-btn.is-primary, .gf-d-play, #gf-home .gf-grid .gf-card, .gf-sheet-item, .gf-cw-remove'); })[0];
        if (pref) return pref;
        var content = list.filter(function (el) { return !el.closest('[class*="vertical-nav-bar"], [class*="horizontal-nav-bar"]'); });
        var pool = (content.length ? content : list).filter(function (el) {
            var r = el.getBoundingClientRect();
            return r.top >= 0 && r.top < innerHeight && r.left < innerWidth;
        });
        if (!pool.length) pool = content.length ? content : list;
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
    function refocus() {
        if (current()) return;
        var want = openLayer() ? lastSig : (routeSig[routeKey()] || lastSig);
        var same = want && candidates().filter(function (el) { return sig(el) === want; })[0];
        focusEl(same || firstInContent());
    }

    function isTyping(el) {
        return el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') && !/button|checkbox|radio|range/.test(el.type || '');
    }

    var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
    window.addEventListener('keydown', function (e) {
        var dir = DIRS[e.key];
        var cur = current();
        if (dir) {
            // digitando: esquerda/direita mexem no texto; cima/baixo saem do campo
            if (isTyping(cur) && (dir === 'left' || dir === 'right')) return;
            e.preventDefault();
            e.stopPropagation();
            if (!cur) { focusEl(firstInContent()); return; }
            var next = best(cur, dir, candidates());
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
            if (cur.tagName !== 'BUTTON' && cur.tagName !== 'A' && cur.tagName !== 'INPUT') { e.preventDefault(); cur.click(); }
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
    new MutationObserver(function () {
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
            window.__gfTvRf = setTimeout(refocus, 350);
        }
    }).observe(document.documentElement, { childList: true, subtree: true });

    window.addEventListener('hashchange', function () { setTimeout(refocus, 450); });
    setTimeout(refocus, 1200);
})();
