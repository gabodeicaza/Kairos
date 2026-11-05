// Servidor de Kairos para Render: sirve la web (carpeta web/) y expone /api/news.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { dirname, extname, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { getNews, getNewsFor } from './news.js';
import { getForecast, getForecastAt, isKnownRegion } from './forecast.js';
import { lookupHazards, MX_BOX } from './hazards.js';
import { handleAccounts, sessionInfo } from './accounts.js';
import { handleBilling } from './billing.js';
import { FREE_RADIUS_KM, billingMode } from './plans.js';
import { km } from './geo.js';
import { handlePush, startPushLoop, pushConfigured, pushPublicKey } from './push.js';
import { getStore, accountsConfigured } from './store.js';

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
  // /api/hazards solo acepta POST y /api/news acepta POST además de GET: los datos de ubicación viajan en el cuerpo
  // y no en la URL, que las plataformas suelen registrar.
  if (await handleAccounts(req, res, pathname, getStore)) return;   // /api/auth/*, /api/me, /api/profile, /api/account
  if (await handlePush(req, res, pathname, getStore)) return;       // /api/push/*
  if (await handleBilling(req, res, pathname, getStore, searchParams)) return;   // /api/plan/*, /api/partner/stats
  const allowed = pathname === '/api/hazards' ? ['POST'] : pathname === '/api/news' || pathname === '/api/forecast' ? ['GET', 'HEAD', 'POST'] : ['GET', 'HEAD'];
  if (!allowed.includes(req.method)) return send(res, 405, 'Method Not Allowed', { Allow: allowed.join(', ') });
  try {
    if (pathname === '/healthz') return send(res, 200, 'ok');
    if (pathname === '/api/config') {
      // Solo indica si hay cuentas; la cadena de conexión a MongoDB nunca sale del servidor.
      // Solo valores públicos por diseño (client ids); los secretos y la cadena de MongoDB nunca salen del servidor.
      const env = process.env;
      return send(res, 200, JSON.stringify({
        accounts: accountsConfigured(),
        google: env.GOOGLE_CLIENT_ID ? { clientId: env.GOOGLE_CLIENT_ID } : null,
        // Con cuentas, la clave del mapa va en /api/me y solo para Premium; sin cuentas (demo abierta) va aquí.
        maps: !accountsConfigured() && env.GOOGLE_MAPS_KEY ? { key: env.GOOGLE_MAPS_KEY } : null,   // clave de navegador: restríngela por sitio web en Google Cloud
        billing: billingMode(),
        push: pushConfigured() ? { publicKey: pushPublicKey() } : null,
        adsense: env.ADSENSE_CLIENT && env.ADSENSE_SLOT ? { client: env.ADSENSE_CLIENT, slot: env.ADSENSE_SLOT } : null,
        gpay: { env: env.GPAY_ENV === 'PRODUCTION' ? 'PRODUCTION' : 'TEST', merchantId: env.GPAY_MERCHANT_ID || null,
          gateway: env.GPAY_GATEWAY || null, gatewayMerchantId: env.GPAY_GATEWAY_MERCHANT_ID || null },
      }), { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    }
    if (pathname === '/ads.txt') {   // Google AdSense lo exige para verificar al editor: "ca-pub-123" -> "pub-123"
      const pub = String(process.env.ADSENSE_CLIENT || '').replace(/^ca-/, '');
      if (!/^pub-\d+$/.test(pub)) return send(res, 404, 'Not Found');
      return send(res, 200, `google.com, ${pub}, DIRECT, f08c47fec0942fa0\n`, { 'Content-Type': 'text/plain; charset=utf-8' });
    }
    if (pathname === '/api/news') {
      const who = await sessionInfo(getStore, req);
      if (req.method !== 'POST' && who.plan === 'free') return send(res, 403, JSON.stringify({ error: 'Con el plan gratuito pide las noticias de tu zona (a 20 km) desde la app.' }), { 'Content-Type': 'application/json; charset=utf-8' });
      if (who.plan === 'socio') return send(res, 403, JSON.stringify({ error: 'las cuentas de socio no usan las noticias de zona' }), { 'Content-Type': 'application/json; charset=utf-8' });
      if (req.method === 'POST') {
        let body;
        try { body = JSON.parse(await readBody(req, 1024)); } catch { return send(res, 400, JSON.stringify({ error: 'cuerpo JSON inválido' }), { 'Content-Type': 'application/json; charset=utf-8' }); }
        const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '');
        let data = await getNewsFor({ muni: str(body && body.muni, 80), estado: str(body && body.estado, 60) });
        if (who.plan === 'free') {
          // Plan gratuito: solo lo que ocurre a 20 km de ti (por coordenadas) o nombra tu municipio. Sin ubicación no hay noticias.
          const lat = body && body.lat, lon = body && body.lon, ok = typeof lat === 'number' && typeof lon === 'number' && Number.isFinite(lat) && Number.isFinite(lon);
          data = { ...data, items: data.items.filter((i) => i.muni === true || (ok && i.geo && km({ lat, lon }, i.geo) <= FREE_RADIUS_KM)), limited: FREE_RADIUS_KM };
        }
        return send(res, 200, JSON.stringify(data), { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      }
      const data = await getNews();
      return send(res, 200, JSON.stringify(data), { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=300' });
    }
    if (pathname === '/api/forecast') {
      const json = { 'Content-Type': 'application/json; charset=utf-8' };
      if ((await sessionInfo(getStore, req)).plan !== 'premium') return send(res, 402, JSON.stringify({ error: 'El pronóstico es parte del plan Premium.' }), json);
      let load;
      if (req.method === 'POST') {
        let body;
        try { body = JSON.parse(await readBody(req, 1024)); } catch { return send(res, 400, JSON.stringify({ error: 'cuerpo JSON inválido' }), json); }
        const lat = body && body.lat, lon = body && body.lon;
        if (typeof lat !== 'number' || typeof lon !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon)
          || lon < MX_BOX.minLon || lon > MX_BOX.maxLon || lat < MX_BOX.minLat || lat > MX_BOX.maxLat) {
          return send(res, 400, JSON.stringify({ error: 'lat y lon deben estar dentro de México' }), json);
        }
        load = () => getForecastAt(lat, lon, typeof body.name === 'string' ? body.name : '');
        json['Cache-Control'] = 'no-store';
      } else {
        const region = searchParams.get('region');
        if (!isKnownRegion(region)) return send(res, 400, JSON.stringify({ error: 'región desconocida' }), json);
        load = () => getForecast(region);
        json['Cache-Control'] = 'public, max-age=600';
      }
      try {
        return send(res, 200, JSON.stringify(await load()), json);
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
}).listen(PORT, '0.0.0.0', () => { console.log(`Kairos escuchando en :${PORT}`); startPushLoop(getStore); });

