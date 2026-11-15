// Explora o que o site guarda no navegador (visitante) para montar um usuário falso nos testes.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
  const p = await ctx.newPage();
  await p.goto(process.env.BASE || 'http://localhost:8099/index.html', { waitUntil: 'load' });
  await new Promise(r => setTimeout(r, 9000));
  const ls = await p.evaluate(() => { const o = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); o[k] = localStorage.getItem(k).slice(0, 6000); } return o; });
  const out = process.env.OUT || 'out'; fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(out + '/localstorage.json', JSON.stringify(ls, null, 1));
  await b.close();
})();
