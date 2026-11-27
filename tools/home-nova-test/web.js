// Prova da Início nova no site (Mac e celular) com Chromium: fotos, cada serviço abre o catálogo certo, Retomar abre o título certo, teclas no Mac, erros da consola.
// Uso: BASE=http://localhost:8099/index.html OUT=out LABEL=antes|depois node web.js
const pw = require('playwright');
const fs = require('fs');
const BASE = process.env.BASE, OUT = process.env.OUT || 'out', ENGINE = process.env.ENGINE || 'chromium', LABEL = (process.env.LABEL || 'depois') + (ENGINE === 'chromium' ? '' : '-' + ENGINE);
const UA_MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15 GabiFlixApp/mac/1.0';
const UA_AND = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 GabiFlixApp/android/1.0';
const PROFILES = { mac1440: { w: 1440, h: 900, mac: 1 }, mac1024: { w: 1024, h: 700, mac: 1 }, and390: { w: 390, h: 844 } };
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
  const n = await page.evaluate(() => document.querySelectorAll('[data-service]').length);
  log(tag, 'serviços na página:', n, n === 9 ? 'OK' : 'ERRO');
  const nova = await page.evaluate(() => !!document.querySelector('.gf-nova'));
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
  if (nova && mode === 'user') {
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
