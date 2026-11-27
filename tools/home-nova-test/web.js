// Prova da Início nova no site (Mac e celular) com Chromium: fotos, cada serviço abre o catálogo certo, Retomar abre o título certo, teclas no Mac, erros da consola.
// Uso: BASE=http://localhost:8099/index.html OUT=out LABEL=antes|depois node web.js
const pw = require('playwright');
const fs = require('fs');
const BASE = process.env.BASE, OUT = process.env.OUT || 'out', ENGINE = process.env.ENGINE || 'chromium', LABEL = (process.env.LABEL || 'depois') + (ENGINE === 'chromium' ? '' : '-' + ENGINE);
const UA_MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15 GabiFlixApp/mac/1.0';
const UA_AND = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 GabiFlixApp/android/1.0';
const PROFILES = { mac1440: { w: 1440, h: 900, mac: 1 }, mac1024: { w: 1024, h: 768, mac: 1 }, and390: { w: 390, h: 844 } };
const SERVICES = ['Netflix', 'Disney+', 'Prime Video', 'HBO Max', 'Apple TV+', 'Paramount+', 'Globoplay', 'Claro Video', 'CineTime'];
const sleep = ms => new Promise(r => setTimeout(r, ms));
fs.mkdirSync(OUT, { recursive: true });
const report = [];
const log = (...a) => { const l = a.join(' '); console.log(l); report.push(l); };

async function fakeUser(ctx, page) {
  const iso = d => new Date(Date.now() - d * 86400000).toISOString();
  const it = (id, name, type, day) => ({ _id: id, name, type, poster: 'https://images.metahub.space/poster/small/' + id + '/img', posterShape: 'poster', removed: false, temp: false, _ctime: iso(day + 1), _mtime: iso(day),
    state: { lastWatched: iso(day), timeWatched: 3600000, timeOffset: 3600000, overallTimeWatched: 3600000, timesWatched: 0, flaggedWatched: 0, duration: 10140000, video_id: id, watched: null, noNotif: false },
    behaviorHints: { defaultVideoId: type === 'movie' ? id : null, featuredVideoId: null, hasScheduledVideos: false } });
  const items = [it('tt0816692', 'Interstellar', 'movie', 1), it('tt1375666', 'Inception', 'movie', 5)];
  const user = { _id: 'fake-user-1', email: 'tester@example.com', fbId: null, avatar: null, gdpr_consent: { tos: true, privacy: true, marketing: false, from: 'web' }, premium_expire: null, trakt: null, lastModified: iso(0), dateRegistered: iso(30), anonymousUser: false };
  let guestAddons = [];
  await ctx.route('https://api.strem.io/api/**', async route => {
    const req = route.request();
    const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'POST,GET,OPTIONS' };
    if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    const name = req.url().split('/api/')[1].split('?')[0];
    let result = {};
    if (name === 'getUser') result = user;
    else if (name === 'addonCollectionGet') result = { addons: guestAddons, lastModified: iso(0) };
    else if (name === 'datastoreGet') result = items;
    else if (name === 'datastoreMeta') result = items.map(i => [i._id, i._mtime]);
    else if (name === 'login' || name === 'register') result = { authKey: 'fake-auth-key', user };
    else result = { success: true };
    route.fulfill({ status: 200, headers: Object.assign({ 'content-type': 'application/json' }, cors), body: JSON.stringify({ result }) });
  });
  await page.goto(BASE, { waitUntil: 'load' }); await sleep(7000);
  guestAddons = await page.evaluate(() => JSON.parse(localStorage.getItem('profile')).addons);
  await page.evaluate(([user, items]) => {
    const prof = JSON.parse(localStorage.getItem('profile'));
    prof.auth = { key: 'fake-auth-key', user };
    localStorage.setItem('profile', JSON.stringify(prof));
    const m = {}; items.forEach(i => m[i._id] = i);
    localStorage.setItem('library', JSON.stringify({ uid: user._id, items: m }));
    localStorage.setItem('library_recent', JSON.stringify({ uid: user._id, items: m }));
  }, [user, items]);
}

async function run(name, P, browser, mode) {
  const tag = `${LABEL}-${name}-${mode}`;
  const ctx = await browser.newContext({ viewport: { width: P.w, height: P.h }, userAgent: P.mac ? UA_MAC : UA_AND, deviceScaleFactor: P.mac ? 1 : 2, isMobile: !P.mac, hasTouch: !P.mac, locale: 'pt-BR' });
  await ctx.addInitScript(() => { window.GabiFlixNative = { playSession() {}, version() { return 4; } }; });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('pageerror: ' + String(e).slice(0, 200)));
  page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource|net::ERR/.test(m.text())) errs.push('console: ' + m.text().slice(0, 200)); });
  if (mode === 'user') await fakeUser(ctx, page);
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForSelector('[data-service]', { timeout: 30000 }).catch(() => log(tag, 'SEM cartões de serviço'));
  await sleep(4000);
  await page.screenshot({ path: `${OUT}/${tag}-01-inicio.png` });
  const fit = await page.evaluate(() => { const sc = document.getElementById('gf-home').parentNode; const n = document.querySelector('.gf-nova'); let far = ''; let mx = 0; sc.querySelectorAll('*').forEach(e => { const b = e.getBoundingClientRect().bottom; if (b > mx && getComputedStyle(e).display !== 'none') { mx = b; far = (e.className && e.className.baseVal === undefined ? e.className : e.tagName) + ''; } }); return sc.scrollHeight + ' / ' + sc.clientHeight + (n ? ' (fim da página ' + Math.round(n.getBoundingClientRect().bottom) + ', mais baixo: ' + far.slice(0, 40) + ' ' + Math.round(mx) + ')' : ''); });
  log(tag, 'altura do conteúdo / janela:', fit);
  log(tag, 'rola até:', await page.evaluate(() => { const sc = document.getElementById('gf-home').parentNode; sc.scrollTop = 9999; const t = sc.scrollTop; sc.scrollTop = 0; return t + ' px; páginas .gf-nova: ' + document.querySelectorAll('.gf-nova').length + '; partes: ' + [...document.querySelector('.gf-nova').children].map(c => c.className.split(' ').pop() + ':' + Math.round(c.getBoundingClientRect().top) + '-' + Math.round(c.getBoundingClientRect().bottom)).join(' '); }));
  const n = await page.evaluate(() => document.querySelectorAll('[data-service]').length);
  log(tag, 'serviços na página:', n, n === 9 ? 'OK' : 'ERRO');
  const nova = await page.evaluate(() => !!document.querySelector('.gf-nova'));
  const v2 = await page.evaluate(() => !!document.querySelector('.gf-v2'));
  // cada serviço abre o catálogo com o nome certo e volta
  const ids = await page.evaluate(() => [...document.querySelectorAll('[data-service]')].map(b => b.getAttribute('data-service')));
  for (let i = 0; i < ids.length; i++) {
    await page.evaluate(id => document.querySelector(`[data-service="${id}"]`).click(), ids[i]);
    await page.waitForSelector('#gf-home .gf-svc-name', { timeout: 15000 }).catch(() => {});
    await sleep(1200);
    const title = await page.evaluate(() => { const h = document.querySelector('#gf-home .gf-svc-name'); return h ? h.textContent.trim() : ''; });
    log(tag, 'serviço', ids[i], '-> catálogo', JSON.stringify(title), title === SERVICES[i] ? 'OK' : 'ERRO');
    if (i === 0) await page.screenshot({ path: `${OUT}/${tag}-02-catalogo-netflix.png` });
    await page.evaluate(() => history.back());
    await page.waitForSelector('[data-service]', { timeout: 15000 }).catch(() => log(tag, 'não voltou à Início'));
    await sleep(500);
  }
  if (nova && P.mac) {
    // teclas: foco visível passa pelos serviços
    await page.evaluate(() => document.querySelector('[data-service]').focus());
    const seq = [];
    for (let i = 0; i < 9; i++) { seq.push(await page.evaluate(() => (document.activeElement.getAttribute('data-service') || document.activeElement.className))); await page.keyboard.press('ArrowRight'); await sleep(120); }
    log(tag, 'foco com ←→:', seq.join(','));
    await page.keyboard.press('Enter'); await sleep(2500);
    log(tag, 'Enter abre:', await page.evaluate(() => { const h = document.querySelector('#gf-home .gf-svc-name'); return h ? h.textContent.trim() : 'nada'; }));
    await page.evaluate(() => history.back()); await sleep(1500);
    await page.screenshot({ path: `${OUT}/${tag}-03-teclado.png` });
  }
  if (nova && !v2 && !P.mac) {
    const ok = await page.evaluate(() => { const b = document.querySelector('.gf-nv-trend .gf-nv-poster'); if (!b) return 'sem capas'; b.click(); return 'ok'; });
    await sleep(2500);
    const hash = await page.evaluate(() => location.hash);
    log(tag, 'capa em alta abriu:', ok, hash, /#\/detail\//.test(hash) ? 'OK' : 'ERRO');
    await page.evaluate(() => history.back()); await sleep(2000);
  }
  if (nova && v2 && P.mac) {
    // Mac V2: botão Ver legível, topo "Pesquisar" abre a pesquisa e encontra um título, menu ☰ com Mais Tarde e Configurações, barra lateral sem ícones
    const ver = await page.evaluate(() => { const b = document.querySelector('.gf-nv-hero .gf-nv-btn.is-main'); if (!b) return 'sem'; const cs = getComputedStyle(b); return cs.color + ' em ' + cs.backgroundColor; });
    log(tag, 'botão Ver (texto em fundo):', ver, /rgb\(11, 11, 15\)/.test(ver) ? 'OK' : 'ERRO');
    const side = await page.evaluate(() => { const v = document.querySelector('[class*="vertical-nav-bar-container"]'); return v ? [...v.children].filter(c => getComputedStyle(c).display !== 'none').length + ' itens visíveis, texto: ' + getComputedStyle(v, '::after').content : 'sem barra'; });
    log(tag, 'barra lateral:', side);
    await page.click('[class*="horizontal-nav-bar-container"] [class*="logo-container"]');
    await sleep(2500);
    log(tag, 'Pesquisar no topo abriu:', await page.evaluate(() => location.hash), /#\/search/.test(await page.evaluate(() => location.hash)) ? 'OK' : 'ERRO');
    await page.evaluate(() => { location.hash = '#/search?search=matrix'; }); await sleep(7000);
    const found = await page.evaluate(() => { const t = document.body.innerText; return /Matrix/i.test(t) ? 'encontrou Matrix' : 'NÃO encontrou'; });
    log(tag, 'pesquisa "matrix":', found, /encontrou Matrix/.test(found) ? 'OK' : 'ERRO');
    await page.screenshot({ path: `${OUT}/${tag}-08-pesquisa.png` });
    await page.evaluate(() => { location.hash = '#/'; }); await sleep(3000);
    const menuBtn = await page.$('[class*="horizontal-nav-bar-container"] [class*="menu-button"]');
    if (menuBtn) { await menuBtn.click(); await sleep(1000); }
    const opts = await page.evaluate(() => [...document.querySelectorAll('[class*="nav-menu-container"] a[href]')].map(a => a.textContent.trim() + '=' + a.getAttribute('href')).join(', '));
    log(tag, 'menu ☰:', opts, /Mais Tarde=#\/library/.test(opts) && /Configurações=#\/settings/.test(opts) ? 'OK' : 'ERRO');
    await page.screenshot({ path: `${OUT}/${tag}-09-menu.png` });
    for (const [h, nome] of [['#/library', 'Mais Tarde'], ['#/settings', 'Configurações']]) {
      if (!(await page.$('[class*="nav-menu-container"]')) && menuBtn) { await menuBtn.click(); await sleep(800); }
      await page.evaluate(x => { const a = document.querySelector('[class*="nav-menu-container"] a[href="' + x + '"]'); if (a) a.click(); }, h);
      await sleep(2500);
      const hh = await page.evaluate(() => location.hash);
      log(tag, 'menu ' + nome + ' abriu:', hh, hh.indexOf(h) === 0 ? 'OK' : 'ERRO');
      await page.evaluate(() => { const v = document.querySelector('[class*="vertical-nav-bar-container"]'); if (v) v.click(); }); await sleep(2500);
      log(tag, 'logótipo da barra lateral voltou a:', await page.evaluate(() => location.hash || '#/'));
    }
    await page.waitForSelector('.gf-nova', { timeout: 15000 }).catch(() => {});
    await sleep(2000);
  }
  if (nova && v2) {
    // destaque V2: Ver e i abrem o título do destaque; deslizar e a troca automática mudam de serviço
    const h0 = await page.evaluate(() => { const h = document.querySelector('.gf-nv-hero'); return { svc: h.getAttribute('data-hero-service'), id: h.getAttribute('data-hero-id'), tag: (h.querySelector('.gf-nv-tag') || {}).textContent }; });
    log(tag, 'destaque:', JSON.stringify(h0));
    for (const [nome, sel] of [['Ver', '.gf-nv-copy .gf-nv-btn.is-main'], [P.mac ? 'Detalhes' : 'i', '.gf-nv-copy .gf-nv-btn:not(.is-main)']]) {
      const id = await page.evaluate(() => document.querySelector('.gf-nv-hero').getAttribute('data-hero-id'));
      await page.evaluate(s => document.querySelector('.gf-nv-hero ' + s).click(), sel);
      await sleep(2500);
      const hash = await page.evaluate(() => location.hash);
      log(tag, 'destaque', nome, 'abriu:', hash, id && hash.indexOf(id) >= 0 ? 'OK' : 'ERRO');
      await page.evaluate(() => history.back()); await sleep(2500);
    }
    const before = await page.evaluate(() => document.querySelector('.gf-nv-hero').getAttribute('data-hero-service'));
    const box = await page.evaluate(() => { const r = document.querySelector('.gf-nv-hero').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 3 }; });
    if (P.mac) await page.evaluate(() => document.querySelector('.gf-nv-hero .gf-nv-arrow[aria-label="Destaque seguinte"]').click());
    else await page.evaluate(b => { const h = document.querySelector('.gf-nv-hero'); const o = { bubbles: true, pointerId: 1, pointerType: 'touch', isPrimary: true }; h.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ clientX: b.x + 120, clientY: b.y }, o))); h.dispatchEvent(new PointerEvent('pointerup', Object.assign({ clientX: b.x - 120, clientY: b.y + 5 }, o))); }, box);
    await sleep(2500);
    const after = await page.evaluate(() => document.querySelector('.gf-nv-hero').getAttribute('data-hero-service'));
    log(tag, P.mac ? 'seta seguinte:' : 'deslizar:', before, '->', after, before !== after ? 'OK' : 'ERRO');
    await page.screenshot({ path: `${OUT}/${tag}-05-destaque-deslizado.png` });
    await page.mouse.move(2, P.h - 2); await page.evaluate(() => document.activeElement && document.activeElement.blur());
    await sleep(9000);
    const auto = await page.evaluate(() => document.querySelector('.gf-nv-hero').getAttribute('data-hero-service'));
    log(tag, 'troca automática:', after, '->', auto, auto !== after ? 'OK' : 'ERRO');
    await page.screenshot({ path: `${OUT}/${tag}-06-destaque-automatico.png` });
    if (mode === 'user') {
      await page.screenshot({ path: `${OUT}/${tag}-07-continuar.png` });
      const cw = await page.evaluate(() => { const c = document.querySelector('.gf-nv-cw .gf-nv-wide'); if (!c) return 'sem cartão'; c.click(); return c.getAttribute('aria-label'); });
      await sleep(2500);
      const hash = await page.evaluate(() => location.hash);
      log(tag, 'Continuar a ver (' + cw + ') abriu:', hash, /tt0816692/.test(hash) ? 'OK' : 'ERRO');
      await page.evaluate(() => history.back()); await sleep(2000);
    }
  }
  if (nova && !v2 && P.mac && mode === 'user') {
    // Retomar abre o título certo (Interstellar)
    const label = await page.evaluate(() => { const b = document.querySelector('.gf-nv-hero .gf-nv-btn.is-main'); return b ? b.textContent.trim() + ' | ' + document.querySelector('.gf-nv-title').textContent : 'sem botão'; });
    log(tag, 'destaque:', label);
    await page.evaluate(() => { const b = document.querySelector('.gf-nv-hero .gf-nv-btn.is-main'); if (b) b.click(); });
    await sleep(3000);
    const hash = await page.evaluate(() => location.hash);
    log(tag, 'Retomar abriu:', hash, /tt0816692/.test(hash) ? 'OK' : 'ERRO');
    await page.screenshot({ path: `${OUT}/${tag}-04-retomar.png` });
  }
  log(tag, 'erros da consola:', errs.length, JSON.stringify(errs.slice(0, 5)));
  await ctx.close();
}
(async () => {
  const browser = await pw[ENGINE].launch();
  for (const mode of ['guest', 'user']) for (const n of Object.keys(PROFILES).filter(k => (process.env.PROFILES || '').split(',').filter(Boolean).concat(Object.keys(PROFILES)).indexOf(k) >= 0 && (!process.env.PROFILES || process.env.PROFILES.split(',').indexOf(k) >= 0))) { try { await run(n, PROFILES[n], browser, mode); } catch (e) { log(LABEL, n, mode, 'FALHOU', e.message.slice(0, 200)); } }
  await browser.close();
  fs.writeFileSync(`${OUT}/${LABEL}-relatorio.txt`, report.join('\n'));
})();
