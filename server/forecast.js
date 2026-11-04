// Pronóstico de calor y lluvia (Open-Meteo, https://open-meteo.com, datos CC BY 4.0, uso no comercial).
// Las coordenadas son fijas por región piloto: el cliente nunca envía coordenadas.

const TTL_MS = 30 * 60 * 1000;
const PLACES = {
  'sw-guerrero': { name: 'Acapulco', lat: 16.86, lon: -99.88 },
  'ne-tamaulipas-nl': { name: 'Monterrey', lat: 25.67, lon: -100.31 },
};
const cache = new Map(); // regionId -> { at, data }

export const isKnownRegion = (id) => Object.hasOwn(PLACES, id);

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

export async function getForecast(regionId) {
  const place = PLACES[regionId];
  const hit = cache.get(regionId);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.data;
  try {
    const url = 'https://api.open-meteo.com/v1/forecast?' + new URLSearchParams({
      latitude: place.lat, longitude: place.lon, timezone: 'America/Mexico_City', forecast_days: '5',
      current: 'temperature_2m,apparent_temperature,weather_code',
      daily: 'weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,precipitation_sum,precipitation_probability_max',
    });
    const res = await fetch(url, { headers: { 'User-Agent': 'KairosNewsBot/1.0 (+https://github.com/gabodeicaza/Kairos)' }, signal: AbortSignal.timeout(12_000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const j = await res.json();
    const d = j.daily || {};
    const days = (d.time || []).map((date, i) => ({
      date, code: num(d.weather_code?.[i]), max: num(d.temperature_2m_max?.[i]), min: num(d.temperature_2m_min?.[i]),
      feelsMax: num(d.apparent_temperature_max?.[i]), rain: num(d.precipitation_sum?.[i]), prob: num(d.precipitation_probability_max?.[i]),
    }));
    if (!days.length) throw new Error('respuesta sin datos');
    const data = {
      place: place.name, updated: new Date().toISOString(),
      current: { temp: num(j.current?.temperature_2m), feels: num(j.current?.apparent_temperature), code: num(j.current?.weather_code) },
      days, source: 'Open-Meteo.com',
    };
    cache.set(regionId, { at: Date.now(), data });
    return data;
  } catch (e) {
    if (hit) return hit.data; // último dato bueno si la fuente falla
    throw e;
  }
}
