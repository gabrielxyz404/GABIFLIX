// Gera os catálogos do GabiFlix a partir do JustWatch (Brasil + Portugal).
// Roda no GitHub Actions (Node 20+, sem dependências) e grava em data/*.json.
//
// Arquivos gerados (formato de catálogo Stremio: { metas: [...] }):
//   data/<serviço>-movie.json, data/<serviço>-series.json   para cada serviço
//   data/cine-cinemas.json   filmes em cartaz / lançamentos ainda fora do streaming
//   data/cine-classicos.json filmes antigos fora do streaming
//   data/cine-movie.json     filmes populares fora do streaming
//   data/cine-series.json    séries populares fora do streaming
//   data/info.json           data da atualização e contagens

import { writeFile, mkdir } from 'node:fs/promises';

const API = 'https://apis.justwatch.com/graphql';
const COUNTRIES = [
    { country: 'BR', language: 'pt' },
    { country: 'PT', language: 'pt' }
];
const PER_SERVICE = 200;   // títulos por serviço, tipo e país (antes de juntar)
const CINE_POOL = 1000;    // títulos populares analisados para a Cinemateca
const STREAMING_TYPES = new Set(['FLATRATE', 'FREE', 'ADS', 'FLATRATE_AND_BUY']);

// Como achar cada serviço na lista de pacotes do JustWatch de cada país
const SERVICES = {
    netflix:    [/^netflix$/i],
    disney:     [/^disney\s*(\+|plus)$/i],
    prime:      [/^amazon prime video$/i, /^prime video$/i],
    hbo:        [/^hbo max$/i, /^max$/i],
    appletv:    [/^apple tv\s*(\+|plus)$/i, /^apple tv$/i],
    paramount:  [/^paramount\s*(\+|plus)$/i],
    globoplay:  [/^globoplay$/i],
    clarovideo: [/^claro\s*video$/i, /^claro\s*tv\s*\+?$/i, /^claro\s*tv\s*plus$/i, /^claro\b/i]
};

async function gql(query, variables, tries = 4) {
    for (let i = 1; ; i++) {
        try {
            const r = await fetch(API, {
                method: 'POST',
                headers: { 'content-type': 'application/json', 'user-agent': 'Mozilla/5.0 GabiFlix catalog builder' },
                body: JSON.stringify({ query, variables })
            });
            const j = await r.json();
            if (j.errors) throw new Error(JSON.stringify(j.errors).slice(0, 500));
            return j.data;
        } catch (e) {
            if (i >= tries) throw e;
            await new Promise(res => setTimeout(res, 1500 * i));
        }
    }
}

const PACKAGES_Q = `query Pk($country: Country!) {
  packages(country: $country, platform: WEB) { shortName clearName technicalName }
}`;

const POPULAR_Q = `query Pop($country: Country!, $language: Language!, $first: Int!, $after: String, $filter: TitleFilter, $sortBy: PopularTitlesSorting!) {
  popularTitles(country: $country, filter: $filter, first: $first, after: $after, sortBy: $sortBy) {
    pageInfo { endCursor hasNextPage }
    edges { node {
      objectType
      content(country: $country, language: $language) {
        title
        originalReleaseYear
        originalReleaseDate
        externalIds { imdbId }
        posterUrl
        backdrops { backdropUrl }
      }
      offersBR: offers(country: BR, platform: WEB) { monetizationType }
      offersPT: offers(country: PT, platform: WEB) { monetizationType }
    } }
  }
}`;

function jwImage(url, kind, size) {
    // "/poster/123456/{profile}/titulo.{format}" -> https://images.justwatch.com/poster/123456/s332/img
    const m = url && url.match(new RegExp('/' + kind + '/(\\d+)/'));
    return m ? `https://images.justwatch.com/${kind}/${m[1]}/${size}/img` : null;
}

function toMeta(node) {
    const c = node.content || {};
    const imdb = c.externalIds && c.externalIds.imdbId;
    if (!imdb || !/^tt\d+$/.test(imdb)) return null;
    const type = node.objectType === 'SHOW' ? 'series' : 'movie';
    const backdrop = c.backdrops && c.backdrops[0] && c.backdrops[0].backdropUrl;
    return {
        id: imdb,
        type,
        name: c.title,
        poster: jwImage(c.posterUrl, 'poster', 's332') || `https://images.metahub.space/poster/medium/${imdb}/img`,
        background: jwImage(backdrop, 'backdrop', 's1440') || `https://images.metahub.space/background/medium/${imdb}/img`,
        releaseInfo: c.originalReleaseYear ? String(c.originalReleaseYear) : undefined,
        _date: c.originalReleaseDate || null,
        _year: c.originalReleaseYear || null,
        _streaming: [...(node.offersBR || []), ...(node.offersPT || [])].some(o => STREAMING_TYPES.has(o.monetizationType)),
        _cinema: [...(node.offersBR || []), ...(node.offersPT || [])].some(o => o.monetizationType === 'CINEMA')
    };
}

async function popular({ country, language }, objectType, packages, total, sortBy = 'TRENDING', extra = {}) {
    const out = [];
    let after = null;
    while (out.length < total) {
        const d = await gql(POPULAR_Q, {
            country, language, first: Math.min(100, total - out.length), after, sortBy,
            filter: Object.assign({ objectTypes: [objectType], packages: packages || [], excludeIrrelevantTitles: false }, extra)
        });
        const p = d.popularTitles;
        for (const e of p.edges) { const m = toMeta(e.node); if (m) out.push(m); }
        if (!p.pageInfo.hasNextPage) break;
        after = p.pageInfo.endCursor;
    }
    return out;
}

// Junta as listas de cada país intercalando por posição, sem repetir títulos
function interleave(lists) {
    const seen = new Set(), out = [];
    const max = Math.max(0, ...lists.map(l => l.length));
    for (let i = 0; i < max; i++) for (const l of lists) {
        const m = l[i];
        if (m && !seen.has(m.id)) { seen.add(m.id); out.push(m); }
    }
    return out;
}

function clean(metas) {
    return metas.map(({ _date, _year, _streaming, _cinema, ...m }) => m);
}

async function save(name, metas) {
    await writeFile(`data/${name}.json`, JSON.stringify({ metas: clean(metas) }));
    console.log(`  data/${name}.json: ${metas.length} títulos`);
    return metas.length;
}

async function main() {
    await mkdir('data', { recursive: true });
    const info = { updatedAt: new Date().toISOString(), countries: COUNTRIES.map(c => c.country), counts: {}, packages: {} };

    // 1) Descobre os códigos de cada serviço em cada país
    const pk = {};
    for (const c of COUNTRIES) {
        const d = await gql(PACKAGES_Q, { country: c.country });
        console.log(c.country, 'pacotes Claro:', JSON.stringify(d.packages.filter(p => /claro/i.test(p.clearName))));
        pk[c.country] = {};
        for (const [svc, patterns] of Object.entries(SERVICES)) {
            const codes = d.packages.filter(p => patterns.some(re => re.test(p.clearName.trim()))).map(p => p.shortName);
            pk[c.country][svc] = codes;
        }
    }
    info.packages = pk;
    console.log('Pacotes encontrados:', JSON.stringify(pk));

    // 2) Catálogo de cada serviço (Brasil + Portugal juntos)
    for (const svc of Object.keys(SERVICES)) {
        for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
            const lists = [];
            for (const c of COUNTRIES) {
                const codes = pk[c.country][svc];
                if (!codes.length) continue;
                try { lists.push(await popular(c, objectType, codes, PER_SERVICE)); }
                catch (e) { console.error(`Erro em ${svc}/${type}/${c.country}:`, e.message); }
            }
            info.counts[`${svc}-${type}`] = await save(`${svc}-${type}`, interleave(lists).slice(0, 300));
        }
    }

    // 3) Cinemateca: populares que não estão em nenhum streaming no Brasil nem em Portugal
    const pool = { movie: [], series: [] };
    for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
        const lists = [];
        for (const c of COUNTRIES) {
            try { lists.push(await popular(c, objectType, null, CINE_POOL, 'POPULAR')); }
            catch (e) { console.error(`Erro no conjunto da Cinemateca ${type}/${c.country}:`, e.message); }
        }
        pool[type] = interleave(lists).filter(m => !m._streaming);
    }
    // Clássicos: busca dedicada a filmes até 1989
    const classLists = [];
    for (const c of COUNTRIES) {
        try { classLists.push(await popular(c, 'MOVIE', null, CINE_POOL, 'POPULAR', { releaseYear: { max: 1989 } })); }
        catch (e) { console.error(`Erro nos clássicos ${c.country}:`, e.message); }
    }
    // Séries: amplia a busca, já que quase todas as séries populares estão em algum streaming
    if (pool.series.length < 60) {
        const more = [];
        for (const c of COUNTRIES) {
            try { more.push(await popular(c, 'SHOW', null, 3000, 'POPULAR')); }
            catch (e) { console.error(`Erro nas séries ${c.country}:`, e.message); }
        }
        pool.series = interleave([pool.series, interleave(more).filter(m => !m._streaming)]);
    }
    const year = new Date().getFullYear();
    const recentLimit = Date.now() - 1000 * 60 * 60 * 24 * 240; // ~8 meses
    const isRecent = m => m._cinema || (m._date ? Date.parse(m._date) >= recentLimit : (m._year || 0) >= year);
    const cinemas = pool.movie.filter(isRecent);
    const classicos = interleave([pool.movie.filter(m => m._year && m._year < 1990), interleave(classLists).filter(m => !m._streaming)]);
    const usados = new Set([...cinemas, ...classicos].map(m => m.id));
    const outros = pool.movie.filter(m => !usados.has(m.id));
    info.counts['cine-cinemas'] = await save('cine-cinemas', cinemas.slice(0, 200));
    info.counts['cine-classicos'] = await save('cine-classicos', classicos.slice(0, 300));
    info.counts['cine-movie'] = await save('cine-movie', outros.slice(0, 300));
    info.counts['cine-series'] = await save('cine-series', pool.series.slice(0, 300));

    await writeFile('data/info.json', JSON.stringify(info, null, 2));
    console.log('Pronto:', JSON.stringify(info.counts));
}

main().catch(e => { console.error(e); process.exit(1); });
