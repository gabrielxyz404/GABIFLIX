// Prova do erro da página do título quando a rede falha por uns segundos (antes x depois).
// Uso: SITE_DIR=<pasta do site> PORT=8103 node detalhe-rede.js   (imprime JSON)
// Cenário: abre a página de um filme que ESTÁ na Netflix com os 8 arquivos data/*-ids.json bloqueados nos primeiros 9 s (rede caída no arranque).
//  - versão com o erro: a página mostra "CineTime" e grava o título na lista do CineTime do aparelho (localStorage), mesmo depois de a rede voltar
//  - versão corrigida: não conclui nada enquanto não há dados, tenta de novo e mostra "Netflix"; nada é gravado
const pw = require('playwright');
const fs = require('fs');
const http = require('http');
const path = require('path');
const zlib = require('zlib');

const SITE_DIR = process.env.SITE_DIR || process.cwd();
const PORT = +(process.env.PORT || 8103);
const OUTAGE_MS = +(process.env.OUTAGE_MS || 9000);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.svg': 'image/svg+xml' };

// um filme que está na Netflix e NÃO está nas listas do CineTime
const netflix = JSON.parse(fs.readFileSync(path.join(SITE_DIR, 'data/netflix-movie.json'))).metas;
const cine = new Set();
['cine-cinemas', 'cine-classicos', 'cine-movie', 'cine-series'].forEach(f => { try { const j = JSON.parse(fs.readFileSync(path.join(SITE_DIR, 'data', f + '.json'))); ((j && j.metas) || j || []).forEach(m => cine.add(m.id)); } catch (e) {} });
const pick = netflix.find(m => m.type === 'movie' && /^tt\d+$/.test(m.id) && !cine.has(m.id)) || netflix[0];

const srv = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p.endsWith('/')) p += 'index.html';
  const f = path.join(SITE_DIR, p);
  if (!f.startsWith(SITE_DIR) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end('nf'); }
  let body = fs.readFileSync(f);
  const h = { 'content-type': MIME[path.extname(f).toLowerCase()] || 'application/octet-stream', 'cache-control': 'no-cache' };
  if (/gzip/.test(req.headers['accept-encoding'] || '') && /\.(html|js|css|json|wasm)$/.test(f)) { body = zlib.gzipSync(body); h['content-encoding'] = 'gzip'; }
  h['content-length'] = body.length;
  res.writeHead(200, h); res.end(body);
});

(async () => {
  await new Promise(r => srv.listen(PORT, '127.0.0.1', r));
  const browser = await pw.chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, isMobile: true, hasTouch: true, locale: 'pt-BR',
    userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 GabiFlixApp/android/1.0' });
  await ctx.addInitScript(() => { window.GabiFlixNative = { playSession() {}, version() { return 4; } }; });
  const t0 = Date.now();
  let blocked = 0;
  await ctx.route(/\/data\/[a-z]+-ids\.json/, r => { if (Date.now() - t0 < OUTAGE_MS) { blocked++; r.abort('internetdisconnected'); } else r.continue(); });
  await ctx.route(/justwatch\.com|metahub\.space/, r => r.fulfill({ status: 200, contentType: 'image/jpeg', body: fs.readFileSync(path.join(__dirname, 'poster.jpg')) }));
  const page = await ctx.newPage();
  await page.goto(`http://127.0.0.1:${PORT}/index.html#/detail/${pick.type}/${pick.id}`, { waitUntil: 'load' });
  await page.waitForSelector('.gf-d-title', { timeout: 60000 }).catch(() => {});
  const read = () => page.evaluate(() => ({ servico: (document.querySelector('.gf-d-service') || {}).textContent || '(sem etiqueta)', found: JSON.parse(localStorage.getItem('gf-cinetime-found') || '[]').map(m => m.id) }));
  await sleep(2500);
  const durante = await read();
  await sleep(Math.max(0, OUTAGE_MS + 14000 - (Date.now() - t0)));   // a rede voltou há uns 14 s
  const depois = await read();
  console.log(JSON.stringify({ titulo: pick.name, id: pick.id, bloqueados: blocked, durante, depois }, null, 1));
  await browser.close(); srv.close();
})().catch(e => { console.log(JSON.stringify({ erro: String(e) })); process.exit(0); });
