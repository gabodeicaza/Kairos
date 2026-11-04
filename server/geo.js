// Etiquetado geográfico de noticias: a qué estados de México y a qué cuenca (Pacífico/Atlántico) se refiere un texto.

export const STATES = ['Aguascalientes', 'Baja California Sur', 'Baja California', 'Campeche', 'Chiapas', 'Chihuahua', 'Coahuila', 'Colima',
  'Ciudad de México', 'Durango', 'Guanajuato', 'Guerrero', 'Hidalgo', 'Jalisco', 'Estado de México', 'Michoacán', 'Morelos', 'Nayarit',
  'Nuevo León', 'Oaxaca', 'Puebla', 'Querétaro', 'Quintana Roo', 'San Luis Potosí', 'Sinaloa', 'Sonora', 'Tabasco', 'Tamaulipas',
  'Tlaxcala', 'Veracruz', 'Yucatán', 'Zacatecas'];

export const fold = (s) => String(s).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

// Los nombres más largos primero: "Baja California Sur" se consume antes que "Baja California".
const PATTERNS = [...STATES].sort((a, b) => b.length - a.length).map((name) => ({ name, re: new RegExp(`(^|[^a-z])${fold(name)}(?![a-z])`, 'g') }));
const ALIASES = [{ name: 'Ciudad de México', re: /(^|[^a-z])(cdmx|distrito federal)(?![a-z])/g }, { name: 'Estado de México', re: /(^|[^a-z])(edomex|edo\. de mexico)(?![a-z])/g }];

/** Estados mencionados. "México" a secas es el país y no cuenta; "Estado de México" y "Ciudad de México" sí. */
export function findStates(text) {
  let t = fold(text);
  const found = new Set();
  for (const { name, re } of [...PATTERNS, ...ALIASES]) {
    re.lastIndex = 0;
    if (re.test(t)) { found.add(name); re.lastIndex = 0; t = t.replace(re, ' '); }
  }
  return [...found];
}

const PACIFIC = /pac[ií]fico|pacific/i;
const ATLANTIC = /atl[aá]ntico|atlantic|golfo de m[eé]xico|gulf of mexico|caribe|caribbean/i;
/** Cuencas ciclónicas mencionadas. */
export function findBasins(text) {
  const out = [];
  if (PACIFIC.test(text)) out.push('pacific');
  if (ATLANTIC.test(text)) out.push('atlantic');
  return out;
}

/** Versión estricta para noticias globales (NASA): "Atlantic" o "Pacific" a secas no bastan; debe nombrar el Golfo, el Caribe o el Pacífico oriental. */
export function findBasinsStrict(text) {
  const out = [];
  if (/eastern (north )?pacific|east pacific|pac[ií]fico oriental|pac[ií]fico mexicano/i.test(text)) out.push('pacific');
  if (/gulf of mexico|golfo de m[eé]xico|caribbean|caribe/i.test(text)) out.push('atlantic');
  return out;
}

// Regiones piloto (mismos recuadros que usa la web para NASA EONET).
export const REGION_BOXES = {
  'sw-guerrero': { minLon: -106, minLat: 14, maxLon: -96, maxLat: 20 },
  'ne-tamaulipas-nl': { minLon: -101.5, minLat: 22, maxLon: -93, maxLat: 28 },
};
export const regionsOf = (lon, lat) => Object.entries(REGION_BOXES)
  .filter(([, b]) => lon >= b.minLon && lon <= b.maxLon && lat >= b.minLat && lat <= b.maxLat).map(([id]) => id);
