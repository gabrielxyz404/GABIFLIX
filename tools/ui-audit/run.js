// Auditoria visual/funcional do site (Mac e celular). Uso: BASE=http://localhost:8099/index.html OUT=out PROFILES=mac1440,and390 node run.js
const { chromium } = require('playwright');
const fs = require('fs');
const BASE = process.env.BASE || 'http://localhost:8099/index.html';
const OUT = process.env.OUT || 'out';
const ONLY = (process.env.PROFILES || '').split(',').filter(Boolean);
const TAG = process.env.TAG || 'run';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15 GabiFlixApp/mac/1.0';
const UA_AND = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 GabiFlixApp/android/1.0';
const PROFILES = {
  mac1024: { w: 1024, h: 700, mac: 1 }, mac1280: { w: 1280, h: 800, mac: 1 }, mac1440: { w: 1440, h: 900, mac: 1 },
  mac1728: { w: 1728, h: 1117, mac: 1 }, mac2560: { w: 2560, h: 1440, mac: 1 },
  and390: { w: 390, h: 844 }, and412: { w: 412, h: 915 }, and915: { w: 915, h: 412 }
};
const MOVIE = 'tt0816692', SERIES = 'tt0944947';
const sleep = ms => new Promise(r => setTimeout(r, ms));

const CHECK = () => {
  const out = { overflowX: null, clipped: [], offscreen: [], broken: [], small: [], english: [], stremio: [], fonts: {} };
  const de = document.documentElement;
  if (de.scrollWidth > innerWidth + 1) out.overflowX = de.scrollWidth + ' > ' + innerWidth;
  const vis = e => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && +cs.opacity > 0.05; };
  const desc = e => (e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\s+/).slice(0, 2).join('.') : '') + ' "' + (e.textContent || '').trim().slice(0, 40) + '"');
  const inScroller = e => { for (let p = e.parentElement; p && p !== document.body; p = p.parentElement) { const cs = getComputedStyle(p); if (/(auto|scroll|hidden)/.test(cs.overflowX) && p.scrollWidth > p.clientWidth + 1) return true; } return false; };
  document.querySelectorAll('body *').forEach(e => {
    if (!vis(e)) return;
    const cs = getComputedStyle(e), r = e.getBoundingClientRect();
    if (e.children.length === 0 && (e.textContent || '').trim()) {
      if (e.scrollWidth > e.clientWidth + 2 && /(hidden|clip)/.test(cs.overflowX)) out.clipped.push(desc(e) + ' ' + e.scrollWidth + '>' + e.clientWidth + (cs.textOverflow === 'ellipsis' ? ' (ellipsis)' : ''));
      if (e.scrollHeight > e.clientHeight + 2 && /(hidden|clip)/.test(cs.overflowY) && cs.webkitLineClamp === 'none') out.clipped.push(desc(e) + ' H ' + e.scrollHeight + '>' + e.clientHeight);
    }
    if ((r.right > innerWidth + 2 || r.left < -2) && !inScroller(e) && r.width < innerWidth * 1.5 && cs.position !== 'fixed') out.offscreen.push(desc(e) + ' L' + Math.round(r.left) + ' R' + Math.round(r.right));
    if (e.tagName === 'IMG' && e.complete && e.naturalWidth === 0 && e.getAttribute('src')) out.broken.push(e.getAttribute('src').slice(0, 90));
    if (document.documentElement.getAttribute('data-gf-app') === 'android' && /^(BUTTON|A)$/.test(e.tagName) && (r.width < 36 || r.height < 36) && !/poster|dot/.test(e.className)) out.small.push(desc(e) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
  });
  const txt = document.body.innerText;
  const EN = /\b(Add to Library|Remove from Library|Continue Watching|Library|Discover|Board|Search|Play|Trailer|Cast|Loading|No results|Sign in|Log in|Log out|Seasons?|Episodes?|Watch|Watched|Settings|Addons?|Install|Uninstall|Calendar|Upcoming|Back|Close|Cancel|Save|Delete|Language|Subtitles|Streaming server|Notifications|Interface|Player|Shortcuts|Account|Email|Password|Register|Sign up|Skip|Next|Previous|Resume|Share|Details|Description|Genres?|Director|Writers?|Runtime|Released|Rating|Magnet|Source|Sources|Something went wrong|Not found|Error|Retry|Try again|Show more|Show less|Read more|See all|Reset|Default|Enabled|Disabled|On|Off|Yes|No|Add|Remove|Configure|Choose|Select|Sort|Filter|Type|Movies?|Series|Menu|Fullscreen|Exit)\b/g;
  const found = {}; (txt.match(EN) || []).forEach(w => found[w] = (found[w] || 0) + 1);
  out.english = Object.entries(found).map(([k, v]) => k + '×' + v);
  out.stremio = (txt.match(/stremio/gi) || []).length ? ['stremio×' + (txt.match(/stremio/gi) || []).length] : [];
  out.title = document.title; out.hash = location.hash;
  out.text = txt.slice(0, 1500);
  // famílias/tamanhos de fonte usados
  const fs = {}; document.querySelectorAll('body *').forEach(e => { if (e.children.length === 0 && (e.textContent || '').trim() && vis(e)) { const cs = getComputedStyle(e); const k = cs.fontSize; fs[k] = (fs[k] || 0) + 1; } });
  out.fonts = fs;
  return out;
};

async function run(name, P, browser) {
  const dir = `${OUT}/${name}`; fs.mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({
    viewport: { width: P.w, height: P.h }, userAgent: P.mac ? UA : UA_AND,
    deviceScaleFactor: P.mac ? 1 : 2, isMobile: !P.mac, hasTouch: !P.mac, locale: 'pt-BR'
  });
  const page = await ctx.newPage();
  const log = { name, console: [], failed: [], pageerrors: [], pages: {}, timing: {} };
  page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') log.console.push(m.type() + ': ' + m.text().slice(0, 220) + ' @' + page.url().split('#')[1]); });
  page.on('pageerror', e => log.pageerrors.push(String(e).slice(0, 220) + ' @' + (page.url().split('#')[1] || '')));
  page.on('requestfailed', r => { const u = r.url(); if (!/gstatic|127\.0\.0\.1:11470/.test(u)) log.failed.push(u.slice(0, 110) + ' ' + (r.failure() || {}).errorText); });
  page.on('response', r => { if (r.status() >= 400) log.failed.push(r.status() + ' ' + r.url().slice(0, 110)); });
  const snap = async (id, opts = {}) => {
    await page.screenshot({ path: `${dir}/${id}.png`, fullPage: !!opts.full });
    log.pages[id] = await page.evaluate(CHECK).catch(e => ({ err: String(e) }));
  };
  const go = async (hash, wait = 3500) => { await page.evaluate(h => { location.hash = h; }, hash); await sleep(wait); };
  const step = async (id, fn) => { try { await fn(); } catch (e) { log.pages[id] = Object.assign(log.pages[id] || {}, { stepError: String(e).slice(0, 200) }); } };

  const t0 = Date.now();
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForSelector('.gf-card', { timeout: 30000 }).catch(() => {});
  log.timing.homeCards = Date.now() - t0;
  await sleep(1500);
  await snap('01-home');
  log.timing.appKind = await page.evaluate(() => document.documentElement.getAttribute('data-gf-app'));

  // serviço
  await step('02', async () => {
    const t = Date.now();
    await page.evaluate(() => document.querySelector('.gf-mac-grid .gf-card[data-service=netflix], .gf-pager .gf-card[data-service=netflix]').click());
    await page.waitForSelector('.gf-row .gf-poster img', { timeout: 20000 }).catch(() => {});
    log.timing.servicePoster = Date.now() - t;
    await sleep(2500);
    await snap('02-servico-inicio');
    await page.evaluate(() => { const s = document.getElementById('gf-home').parentNode; s.scrollTop = 900; });
    await sleep(1500); await snap('03-servico-scroll');
    await page.evaluate(() => { const s = document.getElementById('gf-home').parentNode; s.scrollTop = 0; });
  });
  await step('04', async () => {
    const tabs = await page.evaluate(() => [...document.querySelectorAll('#gf-home [role=tab], #gf-home .gf-tab, #gf-home .gf-tabs button')].map(b => b.textContent.trim()));
    log.tabs = tabs;
    for (const label of ['Filmes', 'Bombando']) {
      const ok = await page.evaluate(l => { const b = [...document.querySelectorAll('#gf-home button, #gf-home a')].find(x => x.textContent.trim() === l); if (b) { b.click(); return true; } return false; }, label);
      if (ok) { await sleep(3500); await snap('04-aba-' + label.toLowerCase()); }
    }
    await page.evaluate(() => { const s = document.getElementById('gf-home').parentNode; s.scrollTop = 2500; }); await sleep(1500); await snap('05-grade-scroll');
  });
  await step('06', async () => {
    // abre o primeiro poster
    await page.evaluate(() => document.querySelector('#gf-home .gf-poster.is-filled, #gf-home .gf-poster[aria-label]').click());
    await sleep(4000); await snap('06-detalhe-do-catalogo');
  });
  // detalhes
  await step('07', async () => { const t = Date.now(); await go(`#/detail/movie/${MOVIE}`, 500); await page.waitForSelector('.gf-d-title, [class*=gf-detail] h1, [class*=gf-d-]', { timeout: 15000 }).catch(() => {}); log.timing.detailMovie = Date.now() - t; await sleep(3500); await snap('07-filme', {}); });
  await step('08', async () => { await page.evaluate(() => { const b = [...document.querySelectorAll('button')].find(x => /Assistir/.test(x.textContent)); if (b) b.click(); }); await sleep(4500); await snap('08-fontes'); await page.keyboard.press('Escape'); await sleep(500); });
  await step('09', async () => { const t = Date.now(); await go(`#/detail/series/${SERIES}`, 500); await page.waitForSelector('[class*=gf-d-]', { timeout: 15000 }).catch(() => {}); log.timing.detailSeries = Date.now() - t; await sleep(4500); await snap('09-serie'); await page.evaluate(() => window.scrollTo(0, 99999)); const sc = await page.evaluate(() => { const c = document.querySelector('[class*=gf-d-eps], [class*=episodes]'); if (c) { c.scrollIntoView(); return 1; } return 0; }); await sleep(800); await snap('10-serie-episodios'); });
  await step('11', async () => { await go('#/search', 2500); await snap('11-busca-vazia'); await go('#/search?search=matrix', 7000); await snap('12-busca-resultados'); await go('#/search?search=zzqqxxwwvv', 6000); await snap('13-busca-sem-resultado'); });
  await step('14', async () => { await go('#/library', 3500); await snap('14-mais-tarde'); await go('#/calendar', 4000); await snap('15-calendario'); });
  await step('16', async () => { await go('#/settings', 4000); await snap('16-config'); await snap('16b-config-full', { full: true }); await go('#/addons', 4000); await snap('17-addons'); await go('#/intro', 3000); await snap('18-intro'); });
  await step('19', async () => { await go('#/', 2500); const b = await page.$('[class*="menu-button"], [class*="header-bar"] button:last-child'); if (b) { await b.click(); await sleep(800); await snap('19-menu'); } });
  await step('20', async () => { await go('#/nao-existe', 2500); await snap('20-rota-inexistente'); });
  await fs.promises.writeFile(`${dir}/report.json`, JSON.stringify(log, null, 1));
  await ctx.close();
}
(async () => {
  const browser = await chromium.launch();
  const names = ONLY.length ? ONLY : Object.keys(PROFILES);
  for (const n of names) { try { await run(`${TAG}-${n}`, PROFILES[n], browser); console.log('ok', n); } catch (e) { console.log('FALHOU', n, e.message); } }
  await browser.close();
})();
