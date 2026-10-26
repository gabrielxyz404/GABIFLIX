// Mede quanto do catálogo real de cada serviço (Portugal e Brasil) o GabiFlix tem.
// Uso: node tools/coverage.mjs  -> escreve cobertura.json e cobertura.md
import { readFile, writeFile } from 'node:fs/promises';

const API = 'https://apis.justwatch.com/graphql';
const COUNTRIES = [{ country: 'PT', language: 'pt' }, { country: 'BR', language: 'pt-BR' }];
const ONLY_BR = new Set(['globoplay', 'clarovideo']);
const SERVICES = {
    netflix: [/^netflix$/i], disney: [/^disney\s*(\+|plus)$/i], prime: [/^amazon prime video$/i, /^prime video$/i],
    hbo: [/^hbo max$/i, /^max$/i], appletv: [/^apple tv\s*(\+|plus)$/i, /^apple tv$/i], paramount: [/^paramount\s*(\+|plus)$/i],
    globoplay: [/^globoplay$/i], clarovideo: [/^claro\s*video$/i, /^claro\s*tv\s*\+?$/i, /^claro\s*tv\s*plus$/i, /^claro\b/i]
};
const sleep = ms => new Promise(r => setTimeout(r, ms));
let last = 0;
async function gql(query, variables, tries = 6) {
    for (let i = 1; ; i++) {
        const w = last + 700 - Date.now(); if (w > 0) await sleep(w); last = Date.now();
        try {
            const r = await fetch(API, { method: 'POST', headers: { 'content-type': 'application/json', 'user-agent': 'Mozilla/5.0 GabiFlix coverage' }, body: JSON.stringify({ query, variables }) });
            const j = await r.json(); if (j.errors) throw new Error(JSON.stringify(j.errors).slice(0, 300)); return j.data;
        } catch (e) { if (i >= tries) throw e; await sleep(5000 * i); }
    }
}
const Q = `query Pop($country: Country!, $language: Language!, $first: Int!, $after: String, $filter: TitleFilter) {
  popularTitles(country: $country, filter: $filter, first: $first, after: $after, sortBy: POPULAR) {
    totalCount pageInfo { endCursor hasNextPage }
    edges { node { content(country: $country, language: $language) { externalIds { imdbId } } } }
  } }`;
const PK = `query Pk($country: Country!) { packages(country: $country, platform: WEB) { shortName clearName } }`;

async function all(c, objectType, packages) {
    const ids = []; let after = null, total = 0;
    for (let page = 0; page < 120; page++) {
        const d = await gql(Q, { country: c.country, language: c.language, first: 100, after, filter: { objectTypes: [objectType], packages, excludeIrrelevantTitles: false } });
        const p = d.popularTitles; total = p.totalCount;
        for (const e of p.edges) { const id = e.node.content && e.node.content.externalIds && e.node.content.externalIds.imdbId; ids.push(id || null); }
        if (!p.pageInfo.hasNextPage) break; after = p.pageInfo.endCursor;
    }
    return { total, ids };
}
async function mine(file) { try { return new Set(JSON.parse(await readFile(`data/${file}.json`, 'utf8')).metas.map(m => m.id)); } catch { return new Set(); } }

const out = [];
for (const c of COUNTRIES) {
    const d = await gql(PK, { country: c.country });
    for (const [svc, pats] of Object.entries(SERVICES)) {
        if (ONLY_BR.has(svc) && c.country !== 'BR') continue;
        const packages = d.packages.filter(p => pats.some(re => re.test(p.clearName.trim()))).map(p => p.shortName);
        if (!packages.length && svc === 'clarovideo') packages.push('clv');
        if (!packages.length) continue;
        for (const [ot, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
            const r = await all(c, ot, packages);
            const have = await mine(`${svc}-${type}`);
            const withImdb = r.ids.filter(Boolean);
            const got = withImdb.filter(id => have.has(id)).length;
            const row = { country: c.country, svc, type, total: r.total, withImdb: withImdb.length, inGabiflix: got, pct: withImdb.length ? Math.round(got * 1000 / withImdb.length) / 10 : 100 };
            out.push(row); console.log(JSON.stringify(row));
        }
    }
}
await writeFile('cobertura.json', JSON.stringify(out, null, 2));
const md = ['| País | Serviço | Tipo | Total no serviço | Com código IMDb | No GabiFlix | % |', '|---|---|---|---|---|---|---|']
    .concat(out.map(r => `| ${r.country} | ${r.svc} | ${r.type === 'movie' ? 'Filmes' : 'Séries'} | ${r.total} | ${r.withImdb} | ${r.inGabiflix} | ${r.pct}% |`));
await writeFile('cobertura.md', md.join('\n') + '\n');
