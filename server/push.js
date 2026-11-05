// Notificaciones push (Web Push con llaves VAPID; en Chrome/Android viajan por el servicio de Google, sin cuenta de Firebase).
// Para avisarte de eventos cercanos el servidor guarda, por dispositivo que activa los avisos, el endpoint de push
// y TU ZONA EN BAJA PRECISIÓN (1 decimal ≈ 11 km). Es opcional, se pide permiso aparte y se borra al desactivar o eliminar la cuenta.
import webpush from 'web-push';
import { createHash } from 'node:crypto';
import { currentUser, sameSite, jsonBody } from './accounts.js';
import { getNews } from './news.js';
import { km } from './geo.js';
import { effectivePlan, FREE_RADIUS_KM } from './plans.js';

const JSON_H = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
const send = (res, code, obj) => { res.writeHead(code, { 'X-Content-Type-Options': 'nosniff', ...JSON_H }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
const sha = (s) => createHash('sha256').update(s).digest('hex');

export const pushConfigured = () => !!(process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY);
export const pushPublicKey = () => process.env.VAPID_PUBLIC_KEY || null;
let ready = false;
function init() {
  if (ready || !pushConfigured()) return pushConfigured();
  webpush.setVapidDetails(process.env.VAPID_SUBJECT || 'https://github.com/gabodeicaza/Kairos', process.env.VAPID_PUBLIC_KEY, process.env.VAPID_PRIVATE_KEY);
  return (ready = true);
}

/* ---------- Distancia y reglas de qué evento merece un aviso ---------- */
const MAX_AGE_MS = 3 * 3600_000; // solo eventos recientes: un dispositivo nuevo no recibe un aluvión de noticias viejas

/** Devuelve { title, body, tag } si el evento merece avisar a alguien en `where` ({lat,lon}); si no, null. */
export function qualifies(item, where, now = Date.now(), maxKm = Infinity) {
  if (!item.geo || !where) return null;
  const t = Date.parse(item.date);
  if (isNaN(t) || now - t > MAX_AGE_MS) return null;
  const d = km(where, item.geo);
  if (d > maxKm) return null;   // plan free: solo eventos a 20 km
  if (item.kind === 'usgs') {
    const m = Number((String(item.title).match(/^Sismo M([\d.]+)/) || [])[1]);
    if (!m || d > 300 || (m < 5 && d > 150)) return null;
    return { title: `Sismo M${m.toFixed(1)} cerca de ti`, body: `A unos ${Math.round(d)} km de tu zona. Revisa tu plan y sigue las indicaciones de Protección Civil.`, tag: item.id };
  }
  if (item.kind === 'gdacs' || (item.kind === 'noaa' && String(item.id).startsWith('nhc-active:'))) {
    const cyclone = /^(Ciclón|Huracán|Tormenta|Depresión)/i.test(item.title), flood = /^Inundaci/i.test(item.title);
    if (!cyclone && !flood) return null;
    if (item.kind === 'gdacs' && !/naranja|roja/.test(item.title)) return null;   // los avisos verdes no ameritan interrumpirte
    if (d > (cyclone ? 600 : 300)) return null;
    return { title: cyclone ? 'Ciclón cercano' : 'Alerta de inundación cercana', body: `${item.title} · a unos ${Math.round(d)} km. Abre Kairos para ver tu plan.`, tag: item.id };
  }
  return null;
}

/* ---------- Envío ---------- */
async function deliver(store, sub, payload) {
  try {
    await webpush.sendNotification(sub.sub, JSON.stringify(payload), { TTL: 3600 });
    return true;
  } catch (e) {
    if (e && (e.statusCode === 404 || e.statusCode === 410)) await store.deletePushSubById(sub._id);   // el navegador ya no existe
    else console.error('push:', e && (e.statusCode || e.message));
    return false;
  }
}
let running = false;
export async function pushSweep(getStore) {
  if (running || !init()) return 0;
  running = true;
  try {
    const store = await getStore(); if (!store) return 0;
    const [subs, news] = await Promise.all([store.allPushSubs(), getNews()]);
    let sent = 0;
    const planOf = new Map();   // el plan se consulta al enviar: si bajó de plan, el radio baja también
    for (const sub of subs) {
      if (!planOf.has(sub.userId)) planOf.set(sub.userId, effectivePlan(await store.userById(sub.userId)));
      const plan = planOf.get(sub.userId);
      if (plan === 'socio') continue;
      const maxKm = plan === 'free' ? FREE_RADIUS_KM : Infinity;
      for (const item of news.items) {
        if ((sub.sent || []).includes(item.id)) continue;
        const msg = qualifies(item, { lat: sub.lat, lon: sub.lon }, Date.now(), maxKm);
        if (!msg) continue;
        await store.markPushSent(sub._id, item.id);   // primero se marca: si algo falla no se repite en bucle
        if (await deliver(store, sub, { ...msg, url: '/' })) sent++;
      }
    }
    return sent;
  } catch (e) { console.error('pushSweep:', e.message); return 0; } finally { running = false; }
}
export function startPushLoop(getStore) {
  if (!pushConfigured()) return;
  setTimeout(() => pushSweep(getStore), 30_000);
  setInterval(() => pushSweep(getStore), 10 * 60_000).unref();
}

/* ---------- Rutas ---------- */
const ROUTES = { '/api/push/subscribe': 'POST', '/api/push/unsubscribe': 'POST', '/api/push/test': 'POST' };
const r1 = (v) => Math.round(v * 10) / 10;

export async function handlePush(req, res, pathname, getStore) {
  const method = ROUTES[pathname];
  if (!method) return false;
  if (req.method !== method) { send(res, 405, { error: 'método no permitido' }); return true; }
  if (!sameSite(req)) { send(res, 403, { error: 'origen no permitido' }); return true; }
  if (!init()) { send(res, 503, { error: 'avisos no configurados' }); return true; }
  let store; try { store = await getStore(); } catch { store = null; }
  if (!store) { send(res, 503, { error: 'cuentas no disponibles' }); return true; }
  try {
    const user = await currentUser(store, req);
    if (!user) { send(res, 401, {}); return true; }
    if (user.plan === 'socio') { send(res, 403, { error: 'las cuentas de socio no reciben avisos' }); return true; }
    const body = await jsonBody(req, res, 4096);
    if (!body) return true;
    const sub = body.subscription;
    const okSub = sub && typeof sub.endpoint === 'string' && sub.endpoint.startsWith('https://') && sub.endpoint.length <= 600
      && sub.keys && typeof sub.keys.p256dh === 'string' && typeof sub.keys.auth === 'string';
    if (pathname === '/api/push/subscribe') {
      const { lat, lon } = body;
      if (!okSub || typeof lat !== 'number' || typeof lon !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
        send(res, 400, { error: 'datos inválidos' }); return true;
      }
      // Siempre se liga al usuario de la sesión; la ubicación se guarda con 1 decimal.
      await store.savePushSub({ _id: sha(sub.endpoint), userId: user.id, sub: { endpoint: sub.endpoint, keys: { p256dh: sub.keys.p256dh, auth: sub.keys.auth } }, lat: r1(lat), lon: r1(lon) });
      send(res, 200, {}); return true;
    }
    if (!okSub) { send(res, 400, { error: 'datos inválidos' }); return true; }
    if (pathname === '/api/push/unsubscribe') { await store.deletePushSub(user.id, sha(sub.endpoint)); send(res, 200, {}); return true; }
    // test: solo a la suscripción que la pide, y solo si es del usuario de la sesión
    const mine = await store.pushSub(user.id, sha(sub.endpoint));
    if (!mine) { send(res, 404, { error: 'este dispositivo no está suscrito' }); return true; }
    const ok = await deliver(store, mine, { title: 'Kairos: aviso de prueba', body: 'Así te avisaremos de sismos y ciclones cercanos a tu zona.', tag: 'prueba', url: '/' });
    send(res, ok ? 200 : 502, ok ? {} : { error: 'no se pudo entregar' }); return true;
  } catch (e) {
    console.error('push:', e.message);
    send(res, 500, { error: 'error interno' }); return true;
  }
}
