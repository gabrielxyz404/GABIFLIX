// Botão Voltar do app (window.gfBack): fecha janelas abertas antes de voltar de página.
(function () {
    // rápido: só o tamanho na tela (escondido = tamanho zero). Nada de getComputedStyle, que pesa no Fire TV.
    function visible(el) {
        if (!el || !el.isConnected) return false;
        var r = el.getBoundingClientRect();
        return r.width >= 4 && r.height >= 4;
    }

    function tvRefocus() {}
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
})();
