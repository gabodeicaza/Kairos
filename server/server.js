// Servidor de Kairos para Render: sirve la web (carpeta web/) y expone /api/news.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { dirname, extname, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { getNews } from './news.js';
import { getForecast, isKnownRegion } from './forecast.js';
import { lookupHazards, MX_BOX } from './hazards.js';

const WEB_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'web');
const PORT = process.env.PORT || 3000;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webp': 'image/webp' };

const readBody = (req, max) => new Promise((ok, fail) => {
  let size = 0; const chunks = [];
  req.on('data', (c) => { size += c.length; if (size > max) { fail(new Error('cuerpo demasiado grande')); req.destroy(); } else chunks.push(c); });
  req.on('end', () => ok(Buffer.concat(chunks).toString('utf8')));
  req.on('error', fail);
});

const send = (res, code, body, headers = {}) => { res.writeHead(code, { 'X-Content-Type-Options': 'nosniff', ...headers }); res.end(body); };

http.createServer(async (req, res) => {
  const { pathname, searchParams } = new URL(req.url, 'http://localhost');
  // Solo /api/hazards acepta POST: las coordenadas viajan en el cuerpo y no en la URL, que las plataformas suelen registrar.
  const isHazards = pathname === '/api/hazards';
  if (isHazards ? req.method !== 'POST' : (req.method !== 'GET' && req.method !== 'HEAD')) return send(res, 405, 'Method Not Allowed', { Allow: isHazards ? 'POST' : 'GET, HEAD' });
  try {
    if (pathname === '/healthz') return send(res, 200, 'ok');
    if (pathname === '/api/news') {
      const data = await getNews();
      return send(res, 200, JSON.stringify(data), { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=300' });
    }
    if (pathname === '/api/forecast') {
      const region = searchParams.get('region');
      if (!isKnownRegion(region)) return send(res, 400, JSON.stringify({ error: 'región desconocida' }), { 'Content-Type': 'application/json; charset=utf-8' });
      try {
        return send(res, 200, JSON.stringify(await getForecast(region)), { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=600' });
      } catch (e) {
        console.error('forecast:', e.message);
        return send(res, 502, JSON.stringify({ error: 'pronóstico no disponible' }), { 'Content-Type': 'application/json; charset=utf-8' });
      }
    }
    if (pathname === '/api/hazards') {
      const json = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
      let body;
      try { body = JSON.parse(await readBody(req, 1024)); } catch { return send(res, 400, JSON.stringify({ error: 'cuerpo JSON inválido' }), json); }
      const lat = body && body.lat, lon = body && body.lon;
      if (typeof lat !== 'number' || typeof lon !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon)) {
        return send(res, 400, JSON.stringify({ error: 'lat y lon son obligatorios (números)' }), json);
      }
      if (lon < MX_BOX.minLon || lon > MX_BOX.maxLon || lat < MX_BOX.minLat || lat > MX_BOX.maxLat) {
        return send(res, 404, JSON.stringify({ error: 'fuera de cobertura (solo México)' }), json);
      }
      // Peligro a nivel municipal: ~1 km de precisión basta y evita procesar ubicaciones exactas. No se registran coordenadas.
      const r2 = (v) => Math.round(v * 100) / 100;
      try {
        const data = await lookupHazards(r2(lon), r2(lat));
        if (!data) return send(res, 404, JSON.stringify({ error: 'fuera de cobertura (sin municipio)' }), json);
        return send(res, 200, JSON.stringify(data), json);
      } catch (e) {
        console.error('hazards:', e.message);
        return send(res, 502, JSON.stringify({ error: 'mapas de riesgo no disponibles' }), json);
      }
    }
    // Estático: se resuelve dentro de web/ y se rechaza cualquier ruta que se salga de ahí.
    const rel = normalize(decodeURIComponent(pathname)).replace(/^([/\\])+/, '');
    const file = resolve(WEB_DIR, rel === '' ? 'index.html' : rel);
    if (file !== WEB_DIR && !file.startsWith(WEB_DIR + sep)) return send(res, 403, 'Forbidden');
    const body = await readFile(file);
    return send(res, 200, body, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
  } catch (e) {
    if (e.code === 'ENOENT' || e.code === 'EISDIR') return send(res, 404, 'Not Found');
    console.error(e);
    return send(res, 500, 'Error interno');
  }
}).listen(PORT, '0.0.0.0', () => console.log(`Kairos escuchando en :${PORT}`));

