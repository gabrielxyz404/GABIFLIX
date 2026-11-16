// FramePlay para Mac: ajustes que só valem dentro do app do Mac (html[data-gf-app="mac"]).
// Fala com o app nativo pelo canal "gfmac" (arrastar a janela pelo cabeçalho, tela cheia) e mostra a versão do app do Mac.
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-gf-app') !== 'mac') return;

    var H = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.gfmac;
    function send(cmd) { try { if (H) H.postMessage({ cmd: cmd }); } catch (e) {} }

    // o app diz quando a janela entra/sai da tela cheia (sem os botões de fechar/minimizar o cabeçalho não precisa de recuo)
    window.gfMacFullscreen = function (on) { if (on) root.setAttribute('data-gf-fs', '1'); else root.removeAttribute('data-gf-fs'); };

    // ---------- arrastar a janela pelo cabeçalho (como a barra de título) e duplo clique = zoom ----------
    var INTERACTIVE = 'a,button,input,textarea,select,label,[tabindex],[role="button"],[class*="button-container"],[class*="search-bar"]';
    document.addEventListener('mousedown', function (e) {
        if (e.button !== 0 || !H) return;
        var t = e.target;
        if (!t || !t.closest) return;
        var bar = t.closest('[class*="horizontal-nav-bar-container"]');
        if (!bar || t.closest(INTERACTIVE)) return;
        send(e.detail >= 2 ? 'zoom' : 'drag');
    }, true);

    // ---------- botão de tela cheia do cabeçalho: usa a tela cheia do Mac ----------
    document.addEventListener('click', function (e) {
        var t = e.target;
        if (!t || !t.closest) return;
        var b = t.closest('[class*="horizontal-nav-bar-container"] [class*="button-container"][title]');
        if (!b || !/tela cheia|full ?screen/i.test(b.getAttribute('title') || '')) return;
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        send('fullscreen');
    }, true);

    // ---------- versão: mostra a do FramePlay para Mac, não a do Stremio ----------
    function macVersionText() { var v = window.__gfMacVersion; return v ? 'Mac ' + v : 'Mac'; }
    function fixVersion() {
        // rodapé do menu das Configurações: "Versão do App: 5.0.0-beta.43" e "Versão de Build: <código>"
        var foot = document.querySelectorAll('[class*="version-info-label"]');
        if (foot.length) {
            var first = foot[0];
            if (!first.getAttribute('data-gf-ver')) {
                first.setAttribute('data-gf-ver', '1');
                first.textContent = 'Versão do FramePlay: ' + macVersionText();
                for (var i = 1; i < foot.length; i++) foot[i].style.display = 'none'; // o código do Stremio por baixo do app não interessa
            }
        }
        // bloco "informações" (Versão do App / Versão de Build) dentro das Configurações
        var info = document.querySelector('[class*="info-"][class*="section-"]');
        if (info && !info.getAttribute('data-gf-ver')) {
            var opts = info.querySelectorAll('[class*="option-"]');
            var l0 = opts[0] && opts[0].querySelector('[class*="heading-"] [class*="label-"]');
            var v0 = opts[0] && opts[0].querySelector('[class*="content-"] [class*="label-"]');
            if (l0 && v0 && /vers/i.test(l0.textContent)) {
                info.setAttribute('data-gf-ver', '1');
                l0.textContent = 'Versão do FramePlay';
                v0.textContent = macVersionText();
                if (opts[1]) opts[1].style.display = 'none';
            }
        }
    }

    // ---------- janela de fontes: dica de que as abas continuam para o lado ----------
    function tabsHint(tabs) {
        if (tabs.__gfHint) return;
        tabs.__gfHint = true;
        function upd() {
            var more = tabs.scrollWidth > tabs.clientWidth + 2 && tabs.scrollLeft + tabs.clientWidth < tabs.scrollWidth - 2;
            var less = tabs.scrollLeft > 2;
            tabs.setAttribute('data-more', more ? '1' : '0');
            tabs.setAttribute('data-less', less ? '1' : '0');
        }
        tabs.addEventListener('scroll', upd, { passive: true });
        // roda do mouse (vertical) também anda para o lado
        tabs.addEventListener('wheel', function (e) {
            if (Math.abs(e.deltaY) > Math.abs(e.deltaX) && tabs.scrollWidth > tabs.clientWidth) { tabs.scrollLeft += e.deltaY; e.preventDefault(); }
        }, { passive: false });
        upd();
        setTimeout(upd, 300);
        window.addEventListener('resize', upd);
    }

    var pending = false;
    function scan() {
        pending = false;
        fixVersion();
        var tabs = document.querySelectorAll('.gf-src-tabs');
        for (var i = 0; i < tabs.length; i++) tabsHint(tabs[i]);
    }
    function schedule() { if (!pending) { pending = true; requestAnimationFrame(scan); } }
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    schedule();

    // o app pergunta se o site já desenhou algo (para tirar a tela de abertura)
    window.gfMacReady = function () {
        return !!document.querySelector('.gf-card, [class*="horizontal-nav-bar-container"]');
    };
})();
