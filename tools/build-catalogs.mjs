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
    { country: 'BR', language: 'pt-BR' }, // nomes usados no Brasil
    { country: 'PT', language: 'pt' }
];
const PER_SERVICE = 2000;  // títulos pedidos ao JustWatch por serviço, tipo e país (antes de juntar)
// TV e Mac continuam com o catálogo de antes (arquivos <serviço>-movie.json e -series.json):
const LEGACY_PER_SERVICE = 1000; // por país
const MERGED_CAP = 1500;   // máximo por serviço e tipo depois de juntar Brasil + Portugal
// Celular Android: os mesmos arquivos + <serviço>-movie-extra.json / -series-extra.json com os títulos a mais, até este total
const PHONE_CAP = 2000;
const HOME_ROW = 30;       // títulos por fileira na aba Início
// Fileiras de gênero na aba Início (códigos do JustWatch)
const GENRES = [
    ['act', 'Ação e aventura'], ['cmy', 'Comédia'], ['drm', 'Drama'], ['trl', 'Suspense e mistério'],
    ['hrr', 'Terror'], ['scf', 'Ficção científica'], ['rma', 'Romance'], ['crm', 'Crime'],
    ['ani', 'Animação'], ['fml', 'Família'], ['doc', 'Documentários'], ['rly', 'Reality shows']
];
const CINE_POOL = 1000;    // títulos populares analisados para a Cinemateca
const STREAMING_TYPES = new Set(['FLATRATE', 'FREE', 'ADS', 'FLATRATE_AND_BUY']);

// Como achar cada serviço na lista de pacotes do JustWatch de cada país
// Serviços que só existem no Brasil: não buscar em Portugal
const ONLY_BR = new Set(['globoplay', 'clarovideo']);

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

const sleep = ms => new Promise(res => setTimeout(res, ms));
let lastCall = 0;

async function gql(query, variables, tries = 6) {
    for (let i = 1; ; i++) {
        // espaça as buscas para não ser bloqueado
        const wait = lastCall + 900 - Date.now();
        if (wait > 0) await sleep(wait);
        lastCall = Date.now();
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
            await sleep(8000 * i);
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
        originalTitle
        shortDescription
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
        _cinema: [...(node.offersBR || []), ...(node.offersPT || [])].some(o => o.monetizationType === 'CINEMA'),
        _desc: c.shortDescription || null,
        _orig: c.originalTitle || null
    };
}

async function popular({ country, language }, objectType, packages, total, sortBy = 'TRENDING', extra = {}) {
    const out = [];
    let after = null;
    while (out.length < total) {
        const d = await gql(POPULAR_Q, {
            country, language, first: Math.min(100, total - out.length), after, sortBy,
            filter: Object.assign({ objectTypes: Array.isArray(objectType) ? objectType : [objectType], packages: packages || [], excludeIrrelevantTitles: false }, extra)
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
    // a primeira lista (Brasil) define o nome e a capa quando o título aparece em mais de uma
    const best = new Map();
    for (const l of lists) for (const m of l) if (!best.has(m.id)) best.set(m.id, m);
    const seen = new Set(), out = [];
    const max = Math.max(0, ...lists.map(l => l.length));
    for (let i = 0; i < max; i++) for (const l of lists) {
        const m = l[i];
        if (m && !seen.has(m.id)) { seen.add(m.id); out.push(best.get(m.id)); }
    }
    return out;
}

const DESC = new Map();
function clean(metas) {
    return metas.map(({ _date, _year, _streaming, _cinema, _desc, _orig, ...m }) => {
        if (_desc && !DESC.has(m.id)) DESC.set(m.id, _desc.trim());
        return m;
    });
}

// sinopses em português divididas em 100 arquivos pequenos (pelos 2 últimos dígitos do código IMDb)
async function saveDescriptions(info) {
    await mkdir('data/desc', { recursive: true });
    const shards = {};
    for (const [id, text] of DESC) {
        const k = id.slice(-2);
        (shards[k] = shards[k] || {})[id] = text;
    }
    for (let i = 0; i < 100; i++) {
        const k = String(i).padStart(2, '0');
        await writeFile(`data/desc/${k}.json`, JSON.stringify(shards[k] || {}));
    }
    info.counts.descriptions = DESC.size;
    console.log(`  sinopses em português: ${DESC.size}`);
}

async function save(name, metas, opts = {}) {
    let out = clean(metas);
    if (opts.noBackground) out = out.map(({ background, ...m }) => m);
    await writeFile(`data/${name}.json`, JSON.stringify({ metas: out }));
    console.log(`  data/${name}.json: ${metas.length} títulos`);
    return metas.length;
}

// ---------- Disney+: separar por marca (Disney, Pixar, Marvel, Star Wars, National Geographic) ----------
// A marca de cada título vem do Wikidata (estúdio, canal ou franquia), cruzada pelo código do IMDb.
const BRAND_ORDER = ['pixar', 'marvel', 'starwars', 'natgeo', 'disney']; // prioridade quando um título tem várias
const BRAND_LABELS = {
    pixar: ['Pixar', 'Pixar Animation Studios'],
    marvel: ['Marvel Studios', 'Marvel Television', 'Marvel Animation', 'Marvel Entertainment', 'Marvel Cinematic Universe', 'Marvel Comics'],
    starwars: ['Star Wars', 'Lucasfilm Animation'],
    natgeo: ['National Geographic', 'National Geographic Channel', 'National Geographic Partners', 'National Geographic Society',
             'National Geographic Wild', 'Nat Geo Wild', 'National Geographic Documentary Films', 'National Geographic Films'],
    disney: ['Walt Disney Pictures', 'Walt Disney Animation Studios', 'Walt Disney Productions', 'Walt Disney Feature Animation',
             'Disney Television Animation', 'Disney Channel', 'Disney Junior', 'Disney XD', 'Disneynature',
             'Disney Branded Television', 'Disney Channel Original Movie']
};

async function sparql(q, tries = 4) {
    for (let i = 1; ; i++) {
        try {
            const r = await fetch('https://query.wikidata.org/sparql', {
                method: 'POST',
                headers: {
                    'content-type': 'application/x-www-form-urlencoded',
                    'accept': 'application/sparql-results+json',
                    'user-agent': 'GabiFlixCatalogBuilder/1.0 (https://github.com/gabrielxyz404/GABIFLIX)'
                },
                body: 'format=json&query=' + encodeURIComponent(q)
            });
            if (!r.ok) throw new Error('Wikidata HTTP ' + r.status);
            return JSON.parse((await r.text()).replace(/[\u0000-\u001f]+/g, ' ')).results.bindings;
        } catch (e) {
            if (i >= tries) throw e;
            await sleep(5000 * i);
        }
    }
}

async function wikidataBrands() {
    const map = new Map(); // imdb -> Set(marcas)
    for (const [brand, labels] of Object.entries(BRAND_LABELS)) {
        // uma consulta por nome, para não estourar o tempo do Wikidata
        for (const l of labels) {
            const props = brand === 'disney'
                ? 'wdt:P272|wdt:P449|wdt:P8345|wdt:P179|wdt:P1434'
                : 'wdt:P272|wdt:P449|wdt:P750|wdt:P8345|wdt:P179|wdt:P1434';
            const q = `SELECT DISTINCT ?imdb WHERE {
  ?org rdfs:label "${l.replace(/"/g, '\\"')}"@en .
  ?item (${props}) ?org .
  ?item wdt:P345 ?imdb .
}`;
            const rows = await sparql(q);
            for (const b of rows) {
                const id = b.imdb.value;
                if (!map.has(id)) map.set(id, new Set());
                map.get(id).add(brand);
            }
            await sleep(400);
        }
    }
    return map;
}

// ---------- Marvel: ordem cronológica (acontecimentos da história do MCU) ----------
// [título em inglês no Wikidata, ano de lançamento]
const MCU_TIMELINE = [
    ['Captain America: The First Avenger', 2011], ['Agent Carter', 2015], ['Captain Marvel', 2019],
    ['Iron Man', 2008], ['Iron Man 2', 2010], ['The Incredible Hulk', 2008], ['Thor', 2011],
    ['The Avengers', 2012], ['Thor: The Dark World', 2013], ['Iron Man 3', 2013],
    ['Captain America: The Winter Soldier', 2014], ['Guardians of the Galaxy', 2014],
    ['Guardians of the Galaxy Vol. 2', 2017], ['Avengers: Age of Ultron', 2015], ['Ant-Man', 2015],
    ['Captain America: Civil War', 2016], ['Black Widow', 2021], ['Black Panther', 2018],
    ['Spider-Man: Homecoming', 2017], ['Doctor Strange', 2016], ['Thor: Ragnarok', 2017],
    ['Ant-Man and the Wasp', 2018], ['Avengers: Infinity War', 2018], ['Avengers: Endgame', 2019],
    ['Loki', 2021], ['WandaVision', 2021], ['The Falcon and the Winter Soldier', 2021],
    ['Shang-Chi and the Legend of the Ten Rings', 2021], ['Eternals', 2021],
    ['Spider-Man: Far From Home', 2019], ['Spider-Man: No Way Home', 2021],
    ['Doctor Strange in the Multiverse of Madness', 2022], ['Hawkeye', 2021], ['Moon Knight', 2022],
    ['Black Panther: Wakanda Forever', 2022], ['Echo', 2024], ['She-Hulk: Attorney at Law', 2022],
    ['Ms. Marvel', 2022], ['Thor: Love and Thunder', 2022], ['Werewolf by Night', 2022],
    ['The Guardians of the Galaxy Holiday Special', 2022], ['Ant-Man and the Wasp: Quantumania', 2023],
    ['Guardians of the Galaxy Vol. 3', 2023], ['Secret Invasion', 2023], ['The Marvels', 2023],
    ['Deadpool & Wolverine', 2024], ['Agatha All Along', 2024], ['Daredevil: Born Again', 2025],
    ['Captain America: Brave New World', 2025], ['Thunderbolts*', 2025], ['Ironheart', 2025],
    ['The Fantastic Four: First Steps', 2025]
];

// IMDb dos títulos que o Wikidata nomeia de outro jeito
const MCU_IMDB = {
    'Iron Man': 'tt0371746', 'Avengers: Infinity War': 'tt4154756',
    'Avengers: Endgame': 'tt4154796', 'Moon Knight': 'tt10234724'
};

const norm = t => String(t || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]/g, '');

async function marvelTimeline(info, lists) {
    const q = `SELECT DISTINCT ?item ?imdb ?en ?pt ?ptbr ?year ?isShow WHERE {
  VALUES ?mcu { wd:Q642878 }
  { ?item wdt:P1434 ?mcu } UNION { ?item wdt:P8345 ?mcu } UNION { ?item wdt:P179 ?mcu }
  ?item wdt:P345 ?imdb .
  OPTIONAL { ?item rdfs:label ?en FILTER(LANG(?en) = "en") }
  OPTIONAL { ?item rdfs:label ?pt FILTER(LANG(?pt) = "pt") }
  OPTIONAL { ?item rdfs:label ?ptbr FILTER(LANG(?ptbr) = "pt-br") }
  OPTIONAL { ?item wdt:P577 ?date . BIND(YEAR(?date) AS ?year) }
  BIND(EXISTS { ?item wdt:P31/wdt:P279* wd:Q5398426 } AS ?isShow)
}`;
    const rows = await sparql(q);
    // títulos do Disney+ (nome e capa já em português)
    const onDisney = new Map();
    for (const m of [...lists.movie, ...lists.series]) onDisney.set(m.id, m);
    const out = [], missing = [];
    MCU_TIMELINE.forEach(([title, year], i) => {
        const cands = rows.filter(b => b.en && norm(b.en.value) === norm(title));
        let pick = cands.find(b => b.year && Math.abs(Number(b.year.value) - year) <= 1) || cands[0];
        if (!pick && MCU_IMDB[title]) pick = rows.find(b => b.imdb.value === MCU_IMDB[title]) || { imdb: { value: MCU_IMDB[title] }, en: { value: title } };
        if (!pick) { missing.push(title); return; }
        const id = pick.imdb.value;
        const base = onDisney.get(id);
        const type = base ? base.type : (pick.isShow && pick.isShow.value === 'true' ? 'series' : 'movie');
        out.push(Object.assign({}, base || {
            id, type,
            name: (pick.ptbr || pick.pt || pick.en).value,
            poster: `https://images.metahub.space/poster/medium/${id}/img`,
            background: `https://images.metahub.space/background/medium/${id}/img`,
            releaseInfo: String(year)
        }, { order: i + 1 }));
    });
    info.marvelMissing = missing;
    await writeFile('data/disney-marvel-cronologia.json', JSON.stringify({ metas: out }));
    console.log(`  data/disney-marvel-cronologia.json: ${out.length} títulos (faltaram: ${missing.join(', ') || 'nenhum'})`);
    return out.length;
}

async function disneyBrands(pk, info, ERRORS) {
    // Catálogo maior do Disney+ (Brasil + Portugal), filmes e séries
    const lists = { movie: [], series: [] };
    for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
        const per = [];
        for (const c of COUNTRIES) {
            const codes = pk[c.country].disney;
            if (!codes || !codes.length) continue;
            try { per.push(await popular(c, objectType, codes, 1000, 'POPULAR')); }
            catch (e) { ERRORS.push(`Erro no Disney+ completo ${type}/${c.country}: ${e.message}`); }
        }
        lists[type] = interleave(per);
    }
    let brands;
    try { brands = await wikidataBrands(); }
    catch (e) { ERRORS.push('Erro no Wikidata: ' + e.message); return; }
    const out = {}; BRAND_ORDER.forEach(b => out[b] = []);
    // intercala filmes e séries para cada aba ter os dois
    for (const m of interleave([lists.movie, lists.series])) {
        const set = brands.get(m.id);
        if (!set) continue;
        const brand = BRAND_ORDER.find(b => set.has(b));
        if (brand) out[brand].push(m);
    }
    for (const b of BRAND_ORDER) {
        info.counts[`disney-${b}`] = await save(`disney-${b}`, out[b].slice(0, 400));
        for (const t of ['movie', 'series']) info.counts[`disney-${b}-${t}`] = await save(`disney-${b}-${t}`, out[b].filter(m => m.type === t).slice(0, 300));
    }

    // Marvel: ordem cronológica dos acontecimentos do MCU
    try { info.counts['disney-marvel-cronologia'] = await marvelTimeline(info, lists); }
    catch (e) { ERRORS.push('Erro na cronologia Marvel: ' + e.message); }
}

// ---------- Netflix: Top 10 do dia (Brasil e Portugal) ----------
const CHARTS_Q = `query Charts($country: Country!, $language: Language!, $first: Int!, $filter: StreamingChartsFilter) {
  streamingCharts(country: $country, first: $first, filter: $filter) {
    edges {
      streamingChartInfo { rank }
      node {
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
      }
    }
  }
}`;

async function netflixTop10(pk, info, ERRORS) {
    for (const c of COUNTRIES) {
        const codes = pk[c.country].netflix;
        if (!codes || !codes.length) continue;
        for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
            const name = `netflix-top10-${c.country.toLowerCase()}-${type}`;
            let metas = [], source = 'ranking diário';
            try {
                const d = await gql(CHARTS_Q, {
                    country: c.country, language: c.language, first: 10,
                    filter: { category: 'DAILY_POPULARITY_SAME_CONTENT_TYPE', objectType, packages: codes }
                }, 2);
                metas = d.streamingCharts.edges.map(e => toMeta(e.node)).filter(Boolean);
            } catch (e) {
                ERRORS.push(`Ranking diário ${name}: ${e.message}`);
            }
            if (!metas.length) {
                // reserva: tendências do dia no JustWatch
                source = 'tendências do dia';
                try { metas = (await popular(c, objectType, codes, 10, 'TRENDING')).slice(0, 10); }
                catch (e) { ERRORS.push(`Tendências ${name}: ${e.message}`); }
            }
            info.top10Source = info.top10Source || {};
            info.top10Source[name] = source;
            info.counts[name] = await save(name, metas.slice(0, 10));
        }
    }
}

async function main() {
    await mkdir('data', { recursive: true });
    const ERRORS = [];
    const info = { errors: ERRORS, updatedAt: new Date().toISOString(), countries: COUNTRIES.map(c => c.country), counts: {}, packages: {} };

    // 1) Descobre os códigos de cada serviço em cada país
    const pk = {};
    for (const c of COUNTRIES) {
        const d = await gql(PACKAGES_Q, { country: c.country });
        info['claro' + c.country] = d.packages.filter(p => /claro|net\s*now/i.test(p.clearName)).map(p => p.shortName + ':' + p.clearName);
        pk[c.country] = {};
        for (const [svc, patterns] of Object.entries(SERVICES)) {
            const codes = d.packages.filter(p => patterns.some(re => re.test(p.clearName.trim()))).map(p => p.shortName);
            // Claro Video pode não aparecer na lista do JustWatch; tenta o código conhecido 'clv'
            if (!codes.length && svc === 'clarovideo' && c.country === 'BR') codes.push('clv');
            pk[c.country][svc] = codes;
        }
    }
    info.packages = pk;
    console.log('Pacotes encontrados:', JSON.stringify(pk));

    // 2) Catálogo de cada serviço (Brasil + Portugal juntos)
    for (const svc of Object.keys(SERVICES)) {
        const countries = COUNTRIES.filter(c => !(ONLY_BR.has(svc) && c.country !== 'BR') && pk[c.country][svc].length);
        const byType = {}, extraByType = {};
        for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
            const lists = [];
            for (const c of countries) {
                try { lists.push(await popular(c, objectType, pk[c.country][svc], PER_SERVICE)); }
                catch (e) { console.error(`Erro em ${svc}/${type}/${c.country}:`, e.message); ERRORS.push(`Erro em ${svc}/${type}/${c.country}: ${e.message}`); }
            }
            // lista de sempre (TV e Mac): só os primeiros 1000 de cada país, como antes
            byType[type] = interleave(lists.map(l => l.slice(0, LEGACY_PER_SERVICE))).slice(0, MERGED_CAP);
            info.counts[`${svc}-${type}`] = await save(`${svc}-${type}`, byType[type], { noBackground: true });
            // celular Android: o que passa da lista de sempre, até PHONE_CAP no total
            const have = new Set(byType[type].map(m => m.id));
            extraByType[type] = interleave(lists).filter(m => !have.has(m.id)).slice(0, Math.max(0, PHONE_CAP - byType[type].length));
            info.counts[`${svc}-${type}-extra`] = await save(`${svc}-${type}-extra`, extraByType[type], { noBackground: true });
        }
        // códigos de todos os títulos do serviço (para o "Continuar assistindo" saber de onde é cada um)
        await writeFile(`data/${svc}-ids.json`, JSON.stringify([...byType.movie, ...byType.series, ...extraByType.movie, ...extraByType.series].map(m => m.id)));

        // aba Início: destaque, top 10, em alta e fileiras por gênero (arquivo pequeno)
        const home = {
            top10: interleave([byType.movie.slice(0, 10), byType.series.slice(0, 10)]).slice(0, 10),
            movie: byType.movie.slice(0, HOME_ROW),
            series: byType.series.slice(0, HOME_ROW),
            genres: []
        };
        for (const [code, title] of GENRES) {
            const lists = [];
            for (const c of countries) {
                try { lists.push(await popular(c, ['MOVIE', 'SHOW'], pk[c.country][svc], HOME_ROW, 'TRENDING', { genres: [code] })); }
                catch (e) { ERRORS.push(`Gênero ${code} em ${svc}/${c.country}: ${e.message}`); }
            }
            const metas = interleave(lists).slice(0, HOME_ROW);
            if (metas.length >= 8) home.genres.push({ code, title, metas });
        }
        // produções brasileiras do serviço (filmes e séries), só no catálogo do Brasil
        const br = { movie: [], series: [] };
        if (pk.BR && pk.BR[svc] && pk.BR[svc].length) {
            for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
                try { br[type] = await popular(COUNTRIES[0], objectType, pk.BR[svc], HOME_ROW, 'TRENDING', { productionCountries: ['BR'] }); }
                catch (e) { ERRORS.push(`Brasileiros ${type} em ${svc}: ${e.message}`); }
            }
        }
        // produção brasileira: o nome original já é o nome em português (o JustWatch às vezes dá o nome em inglês)
        for (const l of [br.movie, br.series]) for (const m of l) if (m._orig) m.name = m._orig;
        info.counts[`${svc}-br`] = br.movie.length + '/' + br.series.length;
        const slim = list => clean(list).map(({ background, ...m }) => m);
        await writeFile(`data/${svc}-home.json`, JSON.stringify({
            top10: clean(home.top10),
            movie: clean(home.movie.slice(0, 5)).concat(slim(home.movie.slice(5))),
            series: clean(home.series.slice(0, 5)).concat(slim(home.series.slice(5))),
            genres: home.genres.map(g => ({ code: g.code, title: g.title, metas: slim(g.metas) })),
            brMovie: br.movie.length >= 6 ? slim(br.movie) : [],
            brSeries: br.series.length >= 6 ? slim(br.series) : []
        }));
        info.counts[`${svc}-home-genres`] = home.genres.length;
        console.log(`  ${svc}: ${byType.movie.length} filmes, ${byType.series.length} séries (+${extraByType.movie.length}/${extraByType.series.length} no celular), ${home.genres.length} gêneros`);
    }

    // 3) Cinemateca: populares que não estão em nenhum streaming no Brasil nem em Portugal
    const pool = { movie: [], series: [] };
    for (const [objectType, type] of [['MOVIE', 'movie'], ['SHOW', 'series']]) {
        const lists = [];
        for (const c of COUNTRIES) {
            try { lists.push(await popular(c, objectType, null, CINE_POOL, 'POPULAR')); }
            catch (e) { console.error(`Erro no conjunto da Cinemateca ${type}/${c.country}:`, e.message); ERRORS.push(`Erro no conjunto da Cinemateca ${type}/${c.country}:` + ' ' + e.message); }
        }
        pool[type] = interleave(lists).filter(m => !m._streaming);
    }
    // Clássicos: busca dedicada a filmes até 1989
    const classLists = [];
    for (const c of COUNTRIES) {
        try { classLists.push(await popular(c, 'MOVIE', null, CINE_POOL, 'POPULAR', { releaseYear: { max: 1989 } })); }
        catch (e) { console.error(`Erro nos clássicos ${c.country}:`, e.message); ERRORS.push(`Erro nos clássicos ${c.country}:` + ' ' + e.message); }
    }
    // Séries: amplia a busca, já que quase todas as séries populares estão em algum streaming
    if (pool.series.length < 60) {
        const more = [];
        for (const c of COUNTRIES) {
            try { more.push(await popular(c, 'SHOW', null, 1500, 'POPULAR')); }
            catch (e) { console.error(`Erro nas séries ${c.country}:`, e.message); ERRORS.push(`Erro nas séries ${c.country}:` + ' ' + e.message); }
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

    // 5) Netflix: Top 10 do dia
    await netflixTop10(pk, info, ERRORS);

    // 4) Disney+ separado por marca
    await disneyBrands(pk, info, ERRORS);

    await saveDescriptions(info);
    await writeFile('data/info.json', JSON.stringify(info, null, 2));
    console.log('Pronto:', JSON.stringify(info.counts));
}

main().catch(e => { console.error(e); process.exit(1); });
