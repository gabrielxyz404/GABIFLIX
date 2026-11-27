// Auditoria de desempenho do site (celular e Mac) num Chromium com o processador mais lento.
// Uso: BASE=http://127.0.0.1:8101/index.html OUT=out/head.json PROFILE=mac|and CAST_DELAY_MS=0 RUNS=3 node web-perf.js
// Serve o site de uma pasta (SITE_DIR) com gzip, como o GitHub Pages, para os tamanhos serem comparáveis.
// Mede: abertura (primeiro cartão), abrir um serviço, trocar de aba, rolar a grade de 2000 títulos (quadros), memória, DOM,
// custo dos MutationObserver do site e as funções que mais gastam CPU (perfil do V8).
const pw = require('playwright');
const fs = require('fs');
const http = require('http');
const path = require('path');
const zlib = require('zlib');

const SITE_DIR = process.env.SITE_DIR || process.cwd();
const PORT = +(process.env.PORT || 8101);
const OUT = process.env.OUT || 'web-perf.json';
const PROFILE = process.env.PROFILE || 'mac';
const CAST_DELAY_MS = +(process.env.CAST_DELAY_MS || 0);
const RUNS = +(process.env.RUNS || 3);
const THROTTLE = +(process.env.THROTTLE || (PROFILE === 'and' ? 4 : 2));
const POSTER = fs.readFileSync(path.join(__dirname, 'poster.jpg'));
const sleep = ms => new Promise(r => setTimeout(r, ms));

const UA_MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15 GabiFlixApp/mac/1.0';
const UA_AND = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 GabiFlixApp/android/1.0';
const PROFILES = {
  mac: { viewport: { width: 1440, height: 900 }, userAgent: UA_MAC, deviceScaleFactor: 2, isMobile: false, hasTouch: false },
  and: { viewport: { width: 412, height: 915 }, userAgent: UA_AND, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true },
};
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

function serve() {
  const srv = http.createServer((req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p.endsWith('/')) p += 'index.html';
    const f = path.join(SITE_DIR, p);
    if (!f.startsWith(SITE_DIR) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end('nf'); }
    const ext = path.extname(f).toLowerCase();
    let body = fs.readFileSync(f);
    const h = { 'content-type': MIME[ext] || 'application/octet-stream', 'cache-control': 'max-age=600', 'access-control-allow-origin': '*' };
    if (/\.(html|js|css|json|wasm|svg)$/.test(ext) && /gzip/.test(req.headers['accept-encoding'] || '')) { body = zlib.gzipSync(body, { level: 6 }); h['content-encoding'] = 'gzip'; }
    h['content-length'] = body.length;
    res.writeHead(200, h); res.end(body);
  });
  return new Promise(r => srv.listen(PORT, '127.0.0.1', () => r(srv)));
}

const INIT = () => {
  // custo dos MutationObserver (todos, do Stremio e do FramePlay) e tarefas longas
  window.__mo = { calls: 0, ms: 0, records: 0 };
  const MO = window.MutationObserver;
  window.MutationObserver = class extends MO {
    constructor(cb) { super(function (recs, obs) { const t = performance.now(); try { return cb.call(this, recs, obs); } finally { window.__mo.calls++; window.__mo.records += recs.length; window.__mo.ms += performance.now() - t; } }); }
  };
  window.__long = [];
  try { new PerformanceObserver(l => l.getEntries().forEach(e => window.__long.push(Math.round(e.duration)))).observe({ entryTypes: ['longtask'] }); } catch (e) {}
  window.__tCard = 0;
  (function poll() { if (!window.__tCard && document.querySelector('.gf-card')) window.__tCard = performance.now(); else if (!window.__tCard) requestAnimationFrame(poll); })();
};

function summarize(cpu, limit = 14) {
  const nodes = new Map(cpu.nodes.map(n => [n.id, n]));
  const self = new Map();
  cpu.samples.forEach((id, i) => self.set(id, (self.get(id) || 0) + (cpu.timeDeltas[i] || 0)));
  const byFn = new Map(); let total = 0;
  for (const [id, us] of self) {
    const n = nodes.get(id); if (!n) continue;
    const cf = n.callFrame; total += us;
    const file = (cf.url || '').split('/').slice(-2).join('/') || '(nativo)';
    const key = (cf.functionName || '(anônima)') + ' @ ' + file + ':' + cf.lineNumber;
    byFn.set(key, (byFn.get(key) || 0) + us);
  }
  const byFile = new Map();
  for (const [k, us] of byFn) { const f = k.split(' @ ')[1].replace(/:\d+$/, ''); byFile.set(f, (byFile.get(f) || 0) + us); }
  const top = a => [...a.entries()].sort((x, y) => y[1] - x[1]).slice(0, limit).map(([k, us]) => [k, Math.round(us / 1000)]);
  return { totalMs: Math.round(total / 1000), topFunctions: top(byFn), topFiles: top(byFile) };
}

async function once(browser, run) {
  const P = PROFILES[PROFILE];
  const ctx = await browser.newContext({ ...P, locale: 'pt-BR' });
  await ctx.addInitScript(INIT);
  await ctx.addInitScript(() => { window.GabiFlixNative = { playSession() {}, version() { return 4; } }; });
  const req = { cast: 0, castMs: null, images: 0, bytes: 0, count: 0 };
  await ctx.route(/justwatch\.com|metahub\.space|image\.tmdb|media-amazon|dvrcdn/, r => { req.images++; r.fulfill({ status: 200, contentType: 'image/jpeg', body: POSTER, headers: { 'access-control-allow-origin': '*', 'cache-control': 'max-age=600' } }); });
  await ctx.route(/gstatic\.com\/cv\/js\/sender/, async r => { req.cast++; req.castMs = Date.now(); if (CAST_DELAY_MS) await sleep(CAST_DELAY_MS); r.fulfill({ status: 200, contentType: 'application/javascript', body: 'window.__onGCastApiAvailable&&window.__onGCastApiAvailable(false);' }); });
  await ctx.route(/api\.strem\.io\/api\//, r => r.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*' }, body: JSON.stringify({ result: {} }) }));
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Performance.enable');
  await cdp.send('Profiler.enable');
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: THROTTLE });
  const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(m => [m.name, m.value]));
  const m0 = await metrics();
  const out = { run, profile: PROFILE, throttle: THROTTLE, castDelayMs: CAST_DELAY_MS, steps: {} };
  const errors = [];
  page.on('pageerror', e => errors.push(String(e).slice(0, 160)));

  await cdp.send('Profiler.start');
  const t0 = Date.now();
  await page.goto(process.env.BASE, { waitUntil: 'commit' });
  await page.waitForSelector('.gf-card', { timeout: 60000 }).catch(() => {});
  out.steps.homeCardsMs = Date.now() - t0;
  await page.waitForLoadState('load').catch(() => {});
  out.steps.loadMs = Date.now() - t0;
  const nav = await page.evaluate(() => { const n = performance.getEntriesByType('navigation')[0] || {}; return { dcl: Math.round(n.domContentLoadedEventEnd || 0), load: Math.round(n.loadEventEnd || 0), tCard: Math.round(window.__tCard || 0) }; });
  out.steps.nav = nav;
  await sleep(1500);

  // abrir um serviço (Netflix) até haver pôsteres
  let t = Date.now();
  await page.evaluate(() => { const c = document.querySelector('.gf-mac-grid .gf-card[data-service=netflix], .gf-pager .gf-card[data-service=netflix], .gf-card'); c && c.click(); });
  await page.waitForSelector('.gf-row .gf-poster img', { timeout: 60000 }).catch(() => {});
  out.steps.serviceOpenMs = Date.now() - t;
  await sleep(1200);

  // trocar para a aba Filmes (grade de até 2000 títulos)
  t = Date.now();
  const tabbed = await page.evaluate(() => { const b = [...document.querySelectorAll('#gf-home button')].find(x => x.textContent.trim() === 'Filmes'); if (b) { b.click(); return true; } return false; });
  if (tabbed) await page.waitForSelector('#gf-home .gf-grid .gf-card, #gf-home .gf-poster-grid .gf-poster', { timeout: 60000 }).catch(() => {});
  out.steps.gridOpenMs = Date.now() - t;
  await sleep(1500);
  out.steps.gridItems = await page.evaluate(() => document.querySelectorAll('#gf-home .gf-grid .gf-card, #gf-home .gf-poster-grid .gf-poster').length);
  const m1 = await metrics();

  // rolagem: 150 quadros, 600 px por quadro de 16 ms (o gesto de arrastar uma lista longa)
  const scroll = await page.evaluate(() => new Promise(res => {
    const sc = document.getElementById('gf-home') && document.getElementById('gf-home').parentNode;
    if (!sc) return res(null);
    const d = []; let last = performance.now(), n = 0, y = 0;
    (function step(ts) {
      d.push(ts - last); last = ts; y += 40; sc.scrollTop = y;
      if (++n < 150) requestAnimationFrame(step); else res(d.slice(1));
    })(performance.now());
  }));
  if (scroll) {
    const s = [...scroll].sort((a, b) => a - b);
    out.steps.scroll = { frames: s.length, avgMs: +(s.reduce((a, b) => a + b, 0) / s.length).toFixed(1), p95Ms: +s[Math.floor(s.length * 0.95)].toFixed(1), maxMs: +s[s.length - 1].toFixed(1), over33: s.filter(x => x > 33.4).length, over50: s.filter(x => x > 50).length };
  }
  const m2 = await metrics();

  // volta ao topo e a outra aba (custo de remontar)
  t = Date.now();
  await page.evaluate(() => { const b = [...document.querySelectorAll('#gf-home button')].find(x => x.textContent.trim() === 'Séries'); b && b.click(); });
  await sleep(2500);
  out.steps.afterTabsMs = Date.now() - t;

  const prof = await cdp.send('Profiler.stop');
  out.cpu = summarize(prof.profile);
  const m3 = await metrics();
  const heap = await page.evaluate(() => performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null);
  out.heapMb = heap;
  out.dom = { nodes: m3.Nodes, nodesAfterGrid: m1.Nodes, docs: m3.Documents, listeners: m3.JSEventListeners };
  out.work = {
    taskMs: Math.round((m3.TaskDuration - m0.TaskDuration) * 1000), scriptMs: Math.round((m3.ScriptDuration - m0.ScriptDuration) * 1000),
    layoutMs: Math.round((m3.LayoutDuration - m0.LayoutDuration) * 1000), styleMs: Math.round((m3.RecalcStyleDuration - m0.RecalcStyleDuration) * 1000),
    layouts: m3.LayoutCount - m0.LayoutCount, styleRecalcs: m3.RecalcStyleCount - m0.RecalcStyleCount,
    scrollLayoutMs: Math.round((m2.LayoutDuration - m1.LayoutDuration) * 1000), scrollStyleMs: Math.round((m2.RecalcStyleDuration - m1.RecalcStyleDuration) * 1000), scrollScriptMs: Math.round((m2.ScriptDuration - m1.ScriptDuration) * 1000),
  };
  out.mutationObservers = await page.evaluate(() => ({ calls: window.__mo.calls, records: window.__mo.records, ms: Math.round(window.__mo.ms) }));
  out.longTasks = await page.evaluate(() => ({ n: window.__long.length, totalMs: window.__long.reduce((a, b) => a + b, 0), maxMs: Math.max(0, ...window.__long) }));
  out.res = await page.evaluate(() => {
    const r = performance.getEntriesByType('resource');
    const by = {}; r.forEach(e => { const k = (e.name.match(/\.(js|css|wasm|json|jpg|png|ttf|woff2|svg)(\?|$)/) || [0, 'outro'])[1]; by[k] = by[k] || { n: 0, kb: 0 }; by[k].n++; by[k].kb += (e.encodedBodySize || 0) / 1024; });
    Object.values(by).forEach(v => v.kb = Math.round(v.kb));
    return { total: r.length, kb: Math.round(r.reduce((a, e) => a + (e.encodedBodySize || 0), 0) / 1024), by };
  });
  out.castRequested = req.cast;
  out.errors = errors.slice(0, 5);
  await ctx.close();
  return out;
}

const med = a => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };

(async () => {
  const srv = await serve();
  const browser = await pw.chromium.launch({ args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'] });
  const runs = [];
  for (let i = 0; i < RUNS; i++) { try { runs.push(await once(browser, i)); } catch (e) { console.log('run falhou', i, e.message); } }
  await browser.close(); srv.close();
  const pick = f => runs.map(f).filter(v => typeof v === 'number');
  const summary = runs.length ? {
    homeCardsMs: med(pick(r => r.steps.homeCardsMs)), loadMs: med(pick(r => r.steps.loadMs)), dclMs: med(pick(r => r.steps.nav.dcl)), tCardMs: med(pick(r => r.steps.nav.tCard)),
    serviceOpenMs: med(pick(r => r.steps.serviceOpenMs)), gridOpenMs: med(pick(r => r.steps.gridOpenMs)), gridItems: med(pick(r => r.steps.gridItems)),
    scrollAvgMs: med(pick(r => r.steps.scroll && r.steps.scroll.avgMs)), scrollP95Ms: med(pick(r => r.steps.scroll && r.steps.scroll.p95Ms)), scrollMaxMs: med(pick(r => r.steps.scroll && r.steps.scroll.maxMs)),
    scrollOver33: med(pick(r => r.steps.scroll && r.steps.scroll.over33)), scrollOver50: med(pick(r => r.steps.scroll && r.steps.scroll.over50)),
    heapMb: med(pick(r => r.heapMb)), nodes: med(pick(r => r.dom.nodes)), nodesAfterGrid: med(pick(r => r.dom.nodesAfterGrid)),
    moCalls: med(pick(r => r.mutationObservers.calls)), moMs: med(pick(r => r.mutationObservers.ms)), longTasks: med(pick(r => r.longTasks.n)), longTaskMs: med(pick(r => r.longTasks.totalMs)),
    taskMs: med(pick(r => r.work.taskMs)), scriptMs: med(pick(r => r.work.scriptMs)), layoutMs: med(pick(r => r.work.layoutMs)), styleMs: med(pick(r => r.work.styleMs)),
    resKb: med(pick(r => r.res.kb)), resCount: med(pick(r => r.res.total)), castRequested: runs[0].castRequested,
  } : null;
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ profile: PROFILE, throttle: THROTTLE, castDelayMs: CAST_DELAY_MS, summary, runs }, null, 1));
  console.log(JSON.stringify(summary, null, 1));
})();
