// Estadísticas para socios: qué reporta la gente en cada zona. Solo agregados, solo de quien aceptó participar,
// y NUNCA grupos pequeños: cada cifra necesita al menos STATS_MIN_GROUP personas (10 por defecto) o se suprime.
// No salen correos, contactos, puntos de reunión ni respuestas individuales.
import { sameState } from './geo.js';

const MIN = () => Math.max(1, Number(process.env.STATS_MIN_GROUP) || 10);
export const QUESTIONS = [
  ['f-route', 'Inundación', 'No conoce su ruta a zona alta', 'no'], ['f-docs', 'Inundación', 'Documentos sin proteger del agua', 'no'],
  ['f-flooded', 'Inundación', 'Su calle o colonia ya se ha inundado', 'yes'], ['f-power', 'Inundación', 'No sabe cortar luz y gas', 'no'],
  ['h-windows', 'Ciclones', 'Ventanas sin protección', 'no'], ['h-shelter', 'Ciclones', 'No conoce su refugio', 'no'],
  ['h-roof', 'Ciclones', 'Techo de lámina o material ligero', 'yes'], ['h-radio', 'Ciclones', 'Sin radio para recibir avisos', 'no'],
  ['e-cool', 'Calor', 'Sin ventilador, clima ni lugar fresco', 'no'], ['e-health', 'Calor', 'Alguien con condición que agrava el calor', 'yes'],
  ['e-outdoor', 'Calor', 'Trabaja o estudia al sol de 11 a 17 h', 'yes'],
  ['w-tank', 'Sequía', 'Sin tinaco, cisterna o recipientes', 'no'], ['w-reserve', 'Sequía', 'Reserva de agua menor a 3 días', 'no'], ['w-tandeo', 'Sequía', 'Agua por tandeo o pipas', 'yes'],
  ['s-secure', 'Sismos', 'Muebles y boiler sin fijar', 'no'], ['s-zones', 'Sismos', 'Sin zonas seguras ni ruta de salida', 'no'],
  ['s-drill', 'Sismos', 'Sin simulacro en el último año', 'no'], ['s-gas', 'Sismos', 'No sabe cerrar gas y luz', 'no'], ['s-cracks', 'Sismos', 'Vivienda con grietas o sin revisión estructural', 'yes'],
  ['s-gasleak', 'Sismos', 'Sin revisar fugas de gas ni flama', 'no'], ['s-elec', 'Sismos', 'Instalación eléctrica sin revisar', 'no'], ['s-plan', 'Sismos', 'Sin plan familiar escrito', 'no'], ['s-extinguisher', 'Sismos', 'Sin extintor ni detectores de humo', 'no'],
  ['f-drain', 'Inundación', 'Coladeras sin limpiar', 'no'],
];
// Centro aproximado de cada estado, para el mapa de calor. Nunca hay coordenadas de personas, solo de zonas.
const CENTROS = { 'Aguascalientes': [21.88, -102.29], 'Baja California': [30.0, -115.1], 'Baja California Sur': [25.8, -111.9], 'Campeche': [18.8, -90.4], 'Chiapas': [16.5, -92.5],
  'Chihuahua': [28.9, -106.4], 'Coahuila': [27.3, -102.0], 'Colima': [19.1, -103.9], 'Ciudad de México': [19.33, -99.13], 'Durango': [24.9, -104.9], 'Guanajuato': [21.0, -101.0],
  'Guerrero': [17.6, -100.0], 'Hidalgo': [20.5, -98.9], 'Jalisco': [20.6, -103.6], 'Estado de México': [19.4, -99.6], 'Michoacán': [19.2, -101.9], 'Morelos': [18.7, -99.1],
  'Nayarit': [21.8, -104.9], 'Nuevo León': [25.6, -99.9], 'Oaxaca': [17.0, -96.5], 'Puebla': [19.0, -97.9], 'Querétaro': [20.8, -100.0], 'Quintana Roo': [19.6, -88.0],
  'San Luis Potosí': [22.6, -100.4], 'Sinaloa': [25.0, -107.5], 'Sonora': [29.6, -110.9], 'Tabasco': [17.9, -92.6], 'Tamaulipas': [24.3, -98.7], 'Tlaxcala': [19.4, -98.2],
  'Veracruz': [19.4, -96.7], 'Yucatán': [20.8, -89.0], 'Zacatecas': [23.2, -102.7] };
export function centroDe(estado) {
  const k = Object.keys(CENTROS).find((n) => sameState(estado, n));
  return k ? { lat: CENTROS[k][0], lon: CENTROS[k][1] } : null;
}
const pct = (n, d) => (d ? Math.round((n / d) * 100) : null);

/** docs: [{ data: { profile, done, zone } }] ya filtrados a quienes aceptaron. level: 'estado' | 'municipio'. */
export function aggregate(docs, level = 'estado') {
  const min = MIN(), includeDemo = process.env.STATS_INCLUDE_DEMO === '1';
  const groups = new Map();
  let excluded = 0;
  for (const d of docs) {
    const z = d.data && d.data.zone, p = d.data && d.data.profile;
    if (!z || !p || !z.estado || (z.mode === 'demo' && !includeDemo)) { excluded++; continue; }
    const key = level === 'municipio' ? `${z.municipio}, ${z.estado}` : z.estado;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(d.data);
  }
  const out = [], hidden = { groups: 0, people: 0 };
  for (const [name, arr] of [...groups].sort((a, b) => b[1].length - a[1].length)) {
    if (arr.length < min) { hidden.groups++; hidden.people += arr.length; continue; }
    const n = arr.length, kitSize = 8;
    const prof = (f) => arr.filter((x) => f(x.profile)).length;
    const gaps = QUESTIONS.map(([id, hazard, label, gap]) => {
      let answered = 0, hit = 0;
      for (const x of arr) { const a = x.profile.answers && x.profile.answers[id]; if (a === 'yes' || a === 'no') { answered++; if (a === gap) hit++; } }
      return answered >= min ? { id, hazard, label, answered, pct: pct(hit, answered) } : null;   // sin suficientes respuestas, no se publica
    }).filter(Boolean).sort((a, b) => b.pct - a.pct);
    const kitAvg = Math.round(arr.reduce((s, x) => s + Object.keys(x.profile.kit || {}).length, 0) / n / kitSize * 100);
    const steps = arr.reduce((s, x) => s + Object.keys(x.done || {}).filter((k) => k.startsWith('zone:')).length, 0);
    const indice = gaps.length ? Math.round(gaps.reduce((t, c) => t + c.pct, 0) / gaps.length) : null;   // 0-100: qué tan desprotegida está la zona (promedio de sus carencias)
    out.push({
      zona: name, estado: arr[0].zone.estado, centro: centroDe(arr[0].zone.estado), indice, personas: n,
      hogares: { conNinos: pct(prof((p) => p.children > 0), n), conMayores: pct(prof((p) => p.elderly > 0), n), conMascotas: pct(prof((p) => p.pets > 0), n),
        movilidadReducida: pct(prof((p) => p.mobility), n), plantaBaja: pct(prof((p) => String(p.floor) === '0'), n) },
      botiquinKitPromedioPct: kitAvg, pasosPromedioDelPlan: Math.round((steps / n) * 10) / 10, carencias: gaps,
    });
  }
  return { nivel: level, minimoPorGrupo: min, personasVisibles: out.reduce((t, g) => t + g.personas, 0), grupos: out, suprimidos: hidden, sinZona: excluded };
}
