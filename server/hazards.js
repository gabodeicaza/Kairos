// Peligro por municipio según el Atlas Nacional de Riesgos (CENAPRED, servicio ArcGIS público).
// Recibe un punto (lat/lon), lo cruza con 4 capas y devuelve el grado de peligro de su municipio.
// No guarda ni registra coordenadas: se usan solo para la consulta.

const BASE = 'http://servicios1.cenapred.unam.mx:6080/arcgis/rest/services/ANR/Fenomenos_Hidrometeorologicos/MapServer';
const TIMEOUT_MS = 15_000;

// Una capa por amenaza de Kairos. "field" es el campo con el grado de peligro.
const LAYERS = {
  FLOOD:          { layer: 59, field: 'PELIGRO_IN',  name: ['NOM_MUN', 'MUNICIPIO'], state: ['IVI__ESTAD'] },   // Índice de peligro por inundación (CENAPRED, 2016)
  HURRICANE:      { layer: 76, field: 'GPCT',        name: ['MunIslas'],             state: ['NomEstado'] },    // Grado de peligro por ciclones tropicales (1949-2020)
  EXTREME_HEAT:   { layer: 69, field: 'F17_GP_OC_1', name: ['NOM_MUN'],              state: ['NOM_ENT'] },      // Peligro por ondas cálidas (2019)
  WATER_SCARCITY: { layer: 7,  field: 'Amenaza',     name: ['NOM_MUN'],              state: [] },               // Grado de peligro por sequía (CONAGUA, 2020)
};

// Cobertura del Atlas: territorio mexicano.
export const MX_BOX = { minLon: -118.5, maxLon: -86, minLat: 14, maxLat: 33 };

const GRADES = ['Muy bajo', 'Bajo', 'Medio', 'Alto', 'Muy alto'];
const strip = (s) => String(s).normalize('NFD').replace(/[̀-ͯ]/g, '').trim().toLowerCase();
/** Las capas usan vocabularios distintos ("Media", "Alta", "Muy alto"...): se normalizan a 5 niveles. */
export function normalizeGrade(raw) {
  if (raw == null) return null;
  const t = strip(raw).replace(/\b(a)\b/, 'a');
  const very = t.startsWith('muy');
  const base = t.replace(/^muy\s+/, '');
  const level = /^baj/.test(base) ? 1 : /^medi/.test(base) ? 2 : /^alt/.test(base) ? 3 : null;
  if (level === null) return null;
  const idx = very ? (level === 1 ? 0 : level === 3 ? 4 : null) : level;
  return idx === null ? null : { grade: GRADES[idx], score: idx, high: idx >= 3 };
}

const titleCase = (s) => String(s).toLowerCase().replace(/(^|\s)(\p{L})/gu, (_, a, b) => a + b.toUpperCase()).replace(/\b(De|Del|La|Los|Las|Y)\b/g, (m) => m.toLowerCase());

async function queryLayer(def, lon, lat) {
  const url = `${BASE}/${def.layer}/query?` + new URLSearchParams({
    geometry: `${lon},${lat}`, geometryType: 'esriGeometryPoint', inSR: '4326', spatialRel: 'esriSpatialRelIntersects',
    outFields: '*', returnGeometry: 'false', f: 'json',
  });
  const res = await fetch(url, { headers: { 'User-Agent': 'KairosNewsBot/1.0 (+https://github.com/gabodeicaza/Kairos)' }, signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const j = await res.json();
  if (j.error) throw new Error(j.error.message || 'error ArcGIS');
  return (j.features && j.features[0] && j.features[0].attributes) || null;
}

const pick = (attrs, keys) => { for (const k of keys) if (attrs && attrs[k]) return attrs[k]; return null; };

export async function lookupHazards(lon, lat) {
  const entries = Object.entries(LAYERS);
  const results = await Promise.allSettled(entries.map(([, def]) => queryLayer(def, lon, lat)));
  const hazards = {};
  let municipio = null, estado = null, answered = 0, failed = 0;
  results.forEach((r, i) => {
    const [key, def] = entries[i];
    if (r.status === 'rejected') { failed++; hazards[key] = { grade: null, score: null, high: false }; return; }
    answered++;
    const attrs = r.value;
    const g = attrs ? normalizeGrade(attrs[def.field]) : null;
    hazards[key] = g || { grade: null, score: null, high: false };
    if (attrs) {
      municipio = municipio || pick(attrs, def.name);
      estado = estado || pick(attrs, def.state);
    }
  });
  if (answered === 0) throw new Error('El Atlas Nacional de Riesgos no respondió');
  if (!municipio) return null; // punto fuera de cobertura
  return { municipio: titleCase(municipio), estado: estado ? (/^distrito federal$/i.test(estado) ? 'Ciudad de México' : titleCase(estado)) : null, hazards, partial: failed > 0,
    source: 'CENAPRED · Atlas Nacional de Riesgos' };
}
