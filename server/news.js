// Agrega noticias de fuentes oficiales (gob.mx, NASA, NOAA/NHC). Sin dependencias: usa fetch de Node 18+.
// Solo consulta direcciones fijas de esta lista; nunca una URL recibida del cliente.

import { findStates, findBasins, findBasinsStrict, regionsOf, fold, sameState } from './geo.js';

const UA = 'KairosNewsBot/1.0 (+https://github.com/gabodeicaza/Kairos)';
const TTL_MS = 10 * 60 * 1000;
const TIMEOUT_MS = 12_000;
const ALLOWED_HOSTS = /(^|\.)(gob\.mx|nasa\.gov|noaa\.gov|gdacs\.org|usgs\.gov)$/i;
const round2 = (v) => Math.round(v * 100) / 100;

// Temas del producto: huracán/tormenta, inundación, calor extremo, escasez de agua + protección civil.
const MX_TOPICS = /hurac[aá]n|cicl[oó]n|tormenta|lluvia|inundaci|calor|sequ[ií]a|\bagua\b|sismo|protecci[oó]n civil|cnpc|cenapred|alerta|emergencia|autocuidado|temporada|oleaje|incendio|frente fr[ií]o|desastre|damnificad/i;
const EN_TOPICS = /hurricane|tropical|cyclone|storm|flood|rainfall|heat|drought|water|weather|disaster|earthquake|wildfire/i;

const NAMED = { aacute: 'á', eacute: 'é', iacute: 'í', oacute: 'ó', uacute: 'ú', ntilde: 'ñ', uuml: 'ü', Aacute: 'Á', Eacute: 'É', Iacute: 'Í',
  Oacute: 'Ó', Uacute: 'Ú', Ntilde: 'Ñ', iexcl: '¡', iquest: '¿', ordm: 'º', ordf: 'ª' };
const decode = (s) => s
  .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n))
  .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&nbsp;/g, ' ').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
  .replace(/&([A-Za-z]+);/g, (m, n) => NAMED[n] ?? m)
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const text = (s) => decode(decode(s).replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim();

async function get(url) {
  const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: '*/*' }, signal: AbortSignal.timeout(TIMEOUT_MS), redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

function safeLink(link, base) {
  try {
    const u = new URL(link, base);
    return u.protocol === 'https:' && ALLOWED_HOSTS.test(u.hostname) ? u.href : null;
  } catch { return null; }
}

/* ---------- RSS (NASA, NOAA) ---------- */
function parseRss(xml) {
  return [...xml.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/g)].map((m) => {
    const tag = (t) => { const x = m[1].match(new RegExp(`<${t}\\b[^>]*>([\\s\\S]*?)</${t}>`)); return x ? x[1] : ''; };
    const date = new Date(text(tag('pubDate')));
    return { title: text(tag('title')), link: text(tag('link')), date: isNaN(date) ? null : date.toISOString(), summary: text(tag('description')).slice(0, 220) };
  });
}

/* ---------- Listado de prensa de gob.mx (no ofrece RSS ni API) ---------- */
function parseGobMx(js, base) {
  // La respuesta es JS: $("#prensa").append("<html escapado>"); se decodifica cada literal como JSON.
  const chunks = [...js.matchAll(/\.append\("((?:[^"\\]|\\.)*)"\)/g)].map((m) => {
    try { return JSON.parse(`"${m[1]}"`); } catch { return ''; }
  });
  const out = [];
  for (const html of chunks) {
    for (const a of html.split('<article>').slice(1)) {
      const title = text((a.match(/<h2>([\s\S]*?)<\/h2>/) || [])[1] || '');
      const href = (a.match(/class="small-link"[^>]*href="([^"]+)"|href="([^"]+)"[^>]*class="small-link"/) || []).slice(1).find(Boolean);
      const dt = (a.match(/<time datetime="([^"]+)"/) || [])[1];
      const image = (a.match(/<img[^>]*\ssrc="([^"]+)"/) || [])[1];
      if (!title || !href || !dt) continue;
      // gob.mx publica hora de México (UTC-6, sin horario de verano).
      const date = new Date(dt.replace(' ', 'T') + '-06:00');
      out.push({ title, link: href, date: isNaN(date) ? null : date.toISOString(), image: image && safeLink(image, base) });
    }
  }
  return out;
}

const gob = (agency, pages) => async () => {
  const base = 'https://www.gob.mx';
  const lists = await Promise.all(Array.from({ length: pages }, (_, i) =>
    get(`${base}/${agency}/archivo/prensa?idiom=es&order=DESC&page=${i + 1}`).then((js) => parseGobMx(js, base))));
  return lists.flat();
};

/* ---------- GDACS (ONU + Comisión Europea): ciclones, inundaciones y sismos con semáforo de alerta ---------- */
// Recuadro que cubre México, el Pacífico oriental, el Golfo y el Caribe.
const REGION = { minLon: -125, maxLon: -60, minLat: 5, maxLat: 35 };
const inRegion = (lon, lat) => lon >= REGION.minLon && lon <= REGION.maxLon && lat >= REGION.minLat && lat <= REGION.maxLat;
const GDACS_TYPE = { TC: 'Ciclón tropical', FL: 'Inundación', EQ: 'Sismo' };
const GDACS_LEVEL = { Green: 'verde', Orange: 'naranja', Red: 'roja' };

async function loadGdacs() {
  const json = JSON.parse(await get('https://www.gdacs.org/gdacsapi/api/events/geteventlist/EVENTS4APP?eventlist=TC,FL,EQ&alertlevel=Green;Orange;Red'));
  return (json.features || []).flatMap((f) => {
    const p = f.properties || {};
    const [lon, lat] = (f.geometry && f.geometry.coordinates) || [];
    if (!GDACS_TYPE[p.eventtype] || String(p.iscurrent) !== 'true') return [];
    if (!(inRegion(lon, lat) || /m[eé]xico/i.test(p.country || ''))) return [];
    const date = new Date(`${p.datemodified || p.fromdate}Z`); // GDACS entrega UTC sin zona
    const name = p.eventtype === 'TC' && p.eventname ? ` ${p.eventname}` : '';
    return [{
      title: `${GDACS_TYPE[p.eventtype]}${name} · ${p.country || 'región'} · alerta ${GDACS_LEVEL[p.alertlevel] || p.alertlevel}`,
      link: p.url && p.url.report, date: isNaN(date) ? null : date.toISOString(),
      summary: String(p.htmldescription || p.description || '').slice(0, 220),
      regions: regionsOf(lon, lat), // regiones piloto que contienen el evento
      geo: { lat: round2(lat), lon: round2(lon) },
      areal: p.eventtype === 'TC', // un ciclón abarca cientos de km: se filtra por región, no por radio
    }];
  });
}

/* ---------- Sismos M4.5+ de la última semana (USGS), con epicentro ---------- */
async function loadUsgs() {
  const json = JSON.parse(await get('https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_week.geojson'));
  return (json.features || []).flatMap((f) => {
    const p = f.properties || {};
    const [lon, lat, depth] = (f.geometry && f.geometry.coordinates) || [];
    if (typeof p.mag !== 'number' || !Number.isFinite(lon) || !Number.isFinite(lat)) return [];
    if (lon < -122 || lon > -84 || lat < 12 || lat > 33) return []; // México y su margen marino
    return [{
      title: `Sismo M${p.mag.toFixed(1)} · ${p.place || 'México'}`, link: p.url, date: new Date(p.time).toISOString(),
      summary: Number.isFinite(depth) ? `Profundidad ${Math.round(depth)} km` : '',
      regions: regionsOf(lon, lat), geo: { lat: round2(lat), lon: round2(lon) },
    }];
  });
}

/* ---------- Tormentas activas del Centro Nacional de Huracanes (NOAA) ---------- */
const NHC_CLASS = { TD: 'Depresión tropical', STD: 'Depresión subtropical', TS: 'Tormenta tropical', STS: 'Tormenta subtropical', HU: 'Huracán', PTC: 'Ciclón postropical' };
async function loadNhcActive() {
  const json = JSON.parse(await get('https://www.nhc.noaa.gov/CurrentStorms.json'));
  return (json.activeStorms || []).flatMap((s) => {
    const lat = Number(s.latitudeNumeric ?? s.latitude), lon = Number(s.longitudeNumeric ?? s.longitude);
    if (Number.isFinite(lat) && Number.isFinite(lon) && !inRegion(lon, lat)) return [];
    const date = new Date(s.lastUpdate || Date.now());
    const wind = s.intensity ? ` · vientos de ${s.intensity} nudos` : '';
    return [{
      title: `${NHC_CLASS[s.classification] || 'Ciclón tropical'} ${s.name || ''}${wind}`.trim(),
      link: (s.publicAdvisory && s.publicAdvisory.url) || 'https://www.nhc.noaa.gov/',
      date: isNaN(date) ? new Date().toISOString() : date.toISOString(),
      summary: 'Tormenta activa reportada por el Centro Nacional de Huracanes.',
      // Los identificadores del NHC empiezan por la cuenca: al/AL = Atlántico, ep/EP y cp/CP = Pacífico.
      basins: /^(ep|cp)/i.test(s.id || '') ? ['pacific'] : /^al/i.test(s.id || '') ? ['atlantic'] : [],
      regions: Number.isFinite(lat) && Number.isFinite(lon) ? regionsOf(lon, lat) : [],
      geo: Number.isFinite(lat) && Number.isFinite(lon) ? { lat: round2(lat), lon: round2(lon) } : null,
      areal: true,
    }];
  });
}

const SOURCES = [
  { id: 'nhc-active', name: 'NOAA · Tormentas activas (NHC)', kind: 'noaa', lang: 'es', max: 5, base: 'https://www.nhc.noaa.gov', load: loadNhcActive, only: /./ },
  { id: 'gdacs', name: 'GDACS · ONU y Comisión Europea', kind: 'gdacs', lang: 'es', max: 8, base: 'https://www.gdacs.org', load: loadGdacs, only: /./ },
  { id: 'usgs', name: 'USGS · Servicio Geológico de EE. UU.', kind: 'usgs', lang: 'en', max: 6, base: 'https://earthquake.usgs.gov', load: loadUsgs, only: /./ },
  { id: 'cnpc', name: 'Protección Civil (CNPC)', kind: 'proteccion-civil', max: 16, body: true, topics: /cnpc|protecci[oó]n civil|cenapred|hurac[aá]n|cicl[oó]n|tormenta|lluvia|inundaci|sismo|calor|autocuidado|temporada|emergencia/i,
    // La CNPC publica sus comunicados dentro de la sección de la SSPC.
    load: gob('sspc', 3), only: /cnpc|protecci[oó]n civil|cenapred|hurac[aá]n|cicl[oó]n|tormenta|lluvia|inundaci|sismo|calor|autocuidado|temporada|oleaje/i },
  { id: 'conagua', name: 'Conagua · Gobierno de México', kind: 'gobierno', max: 10, body: true, load: gob('conagua', 2), only: MX_TOPICS },
  { id: 'segob', name: 'Segob · Gobierno de México', kind: 'gobierno', max: 6, body: true, load: gob('segob', 2),
    only: /protecci[oó]n civil|cnpc|cenapred|hurac[aá]n|cicl[oó]n|tormenta|lluvia|inundaci|sismo|desastre|damnificad/i },
  { id: 'nasa', name: 'NASA', kind: 'nasa', max: 5, base: 'https://www.nasa.gov', load: async () => parseRss(await get('https://www.nasa.gov/feed/')), only: EN_TOPICS },
  { id: 'nasa-eo', name: 'NASA · Earth Observatory', kind: 'nasa', max: 5, base: 'https://earthobservatory.nasa.gov',
    load: async () => parseRss(await get('https://earthobservatory.nasa.gov/feeds/natural-hazards.rss')), only: EN_TOPICS },
  { id: 'nhc-ep', name: 'NOAA · Centro Nacional de Huracanes (Pacífico)', kind: 'noaa', max: 2, areal: true, basins: ['pacific'], base: 'https://www.nhc.noaa.gov',
    load: async () => parseRss(await get('https://www.nhc.noaa.gov/index-ep.xml')), only: /./ },
  { id: 'nhc-at', name: 'NOAA · Centro Nacional de Huracanes (Atlántico)', kind: 'noaa', max: 2, areal: true, basins: ['atlantic'], base: 'https://www.nhc.noaa.gov',
    load: async () => parseRss(await get('https://www.nhc.noaa.gov/index-at.xml')), only: /./ },
];

let cache = { at: 0, data: null };
const stale = new Map(); // última lista buena por fuente, por si una falla

// Texto de una nota de gob.mx (solo el cuerpo del artículo), para saber qué estados menciona.
// Las notas no cambian una vez publicadas: el resultado se recuerda (acotado) y no se vuelve a pedir.
const bodyCache = new Map();
const haystacks = new Map(); // id de noticia -> texto plegado (sin acentos, minúsculas)
async function articleText(link) {
  if (bodyCache.has(link)) return bodyCache.get(link);
  let body = '';
  try {
    const html = await get(link);
    const i = html.indexOf('class="article-body"');
    body = i >= 0 ? text(html.slice(i, i + 9000)) : '';
  } catch { return ''; } // si falla, no se cachea: se reintenta luego y mientras tanto se etiqueta solo con el título
  if (bodyCache.size >= 400) bodyCache.delete(bodyCache.keys().next().value);
  bodyCache.set(link, body);
  return body;
}

async function loadSource(src) {
  try {
    const raw = await src.load();
    const base = src.base || 'https://www.gob.mx';
    const picked = raw
      .filter((i) => i.title && i.link && i.date && src.only.test(`${i.title} ${i.summary || ''}`))
      .map((i) => ({ ...i, link: safeLink(i.link, base) }))
      .filter((i) => i.link)
      .sort((a, b) => b.date.localeCompare(a.date))
      .slice(0, src.max);
    const items = await Promise.all(picked.map(async (i) => {
      const body = src.body ? await articleText(i.link) : '';
      const haystack = `${i.title} ${i.summary || ''} ${body}`;
      const item = { id: `${src.id}:${i.link}`, title: i.title, link: i.link, date: i.date, summary: i.summary || '', image: i.image || null,
        source: src.name, kind: src.kind, lang: src.lang || (src.kind === 'nasa' || src.kind === 'noaa' ? 'en' : 'es'),
        // Etiquetas geográficas para filtrar por la zona o el radio del usuario.
        states: findStates(haystack),
        basins: [...new Set([...(src.basins || []), ...(i.basins || []), ...(src.kind === 'nasa' ? findBasinsStrict(haystack) : findBasins(haystack))])],
        regions: i.regions || [],
        geo: i.geo || null,                 // punto (lat/lon) del evento, si lo tiene
        areal: !!(i.areal || src.areal) };  // fenómeno extenso (ciclón, aviso de cuenca): no tiene sentido medirlo en km
      if (src.body) { // texto plegado para buscar el municipio del usuario al responder (acotado)
        if (haystacks.size >= 400) haystacks.delete(haystacks.keys().next().value);
        haystacks.set(item.id, fold(haystack));
      }
      return item;
    }));
    stale.set(src.id, items);
    return { items };
  } catch (e) {
    return { items: stale.get(src.id) || [], error: { source: src.name, message: String(e.message || e) } };
  }
}

export async function getNews() {
  if (cache.data && Date.now() - cache.at < TTL_MS) return cache.data;
  const results = await Promise.all(SOURCES.map(loadSource));
  const seen = new Set();
  const all = results.flatMap((r) => r.items);
  // GDACS y el USGS reportan los mismos sismos: se conserva el del USGS (trae magnitud y lugar) si hay uno a menos de ~0.3°.
  const quakes = all.filter((i) => i.kind === 'usgs' && i.geo).map((i) => i.geo);
  const dupQuake = (i) => i.kind === 'gdacs' && /^Sismo/.test(i.title) && i.geo
    && quakes.some((g) => Math.abs(g.lat - i.geo.lat) < 0.3 && Math.abs(g.lon - i.geo.lon) < 0.3);
  const items = all
    .filter((i) => !dupQuake(i))
    .filter((i) => !/no tropical cyclones/i.test(i.title)) // aviso vacío del NHC: no aporta
    .sort((a, b) => b.date.localeCompare(a.date))
    .filter((i) => { const k = i.title.toLowerCase().replace(/\W+/g, ' ').trim(); return seen.has(k) ? false : (seen.add(k), true); })
    .slice(0, 60);
  const data = { updated: new Date().toISOString(), items, errors: results.flatMap((r) => (r.error ? [r.error] : [])) };
  // No se cachea una respuesta totalmente vacía por error de red; se reintenta en la siguiente petición.
  if (items.length) cache = { at: Date.now(), data };
  return data;
}

const GENERIC_MUNI = new Set(['centro', 'juarez', 'hidalgo', 'morelos', 'victoria', 'zaragoza', 'allende', 'guerrero', 'independencia', 'libertad',
  'reforma', 'progreso', 'union', 'norte', 'sur', 'este', 'oeste', 'benito juarez', 'la union']);

/**
 * Marca cada noticia con `muni: true` si menciona el municipio del usuario (y, si se da, su estado).
 * Se calcula al responder, sobre la lista en caché: el municipio llega en el cuerpo de un POST y no se guarda.
 */
export async function getNewsFor({ muni, estado } = {}) {
  const data = await getNews();
  const key = fold(String(muni || '')).split(' de ')[0].trim();
  if (key.length < 4 || key.length > 60) return data;
  const esc = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // Nombres genéricos ("Centro", "Juárez"...) chocan con frases comunes: se exige "municipio/alcaldía/ciudad de ...".
  const prefix = GENERIC_MUNI.has(key) ? '(?:municipio|alcaldia|ciudad) de ' : '';
  const re = new RegExp(`(^|[^a-z])${prefix}${esc}(?![a-z])`);
  const items = data.items.map((i) => {
    const hay = haystacks.get(i.id) ?? fold(i.title);
    const stateOk = !estado || i.states.some((s) => sameState(s, estado));
    return { ...i, muni: stateOk && re.test(hay) };
  });
  return { ...data, items };
}

export const _test = { parseRss, parseGobMx, text };
