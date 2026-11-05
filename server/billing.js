// Rutas de planes y de estadísticas para socios.
import { currentUser, sameSite, jsonBody } from './accounts.js';
import { PRICES, partnerEligible, billingMode } from './plans.js';
import { aggregate } from './stats.js';

const JSON_H = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
const send = (res, code, obj) => { res.writeHead(code, { 'X-Content-Type-Options': 'nosniff', ...JSON_H }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
const ROUTES = { '/api/plan/checkout': 'POST', '/api/plan/cancel': 'POST', '/api/partner/stats': 'GET' };

export async function handleBilling(req, res, pathname, getStore, searchParams) {
  const method = ROUTES[pathname];
  if (!method) return false;
  if (req.method !== method) { send(res, 405, { error: 'método no permitido' }); return true; }
  if (method !== 'GET' && !sameSite(req)) { send(res, 403, { error: 'origen no permitido' }); return true; }
  let store; try { store = await getStore(); } catch { store = null; }
  if (!store) { send(res, 503, { error: 'cuentas no disponibles' }); return true; }
  try {
    const user = await currentUser(store, req);
    if (!user) { send(res, 401, {}); return true; }

    if (pathname === '/api/partner/stats') {
      if (user.plan !== 'socio') { send(res, 403, { error: 'solo para cuentas de socio con plan vigente' }); return true; }
      const level = searchParams.get('nivel') === 'municipio' ? 'municipio' : 'estado';
      send(res, 200, { ...aggregate(await store.consentedProfiles(), level), generado: new Date().toISOString() });
      return true;
    }
    if (pathname === '/api/plan/cancel') {
      await store.setPlan(user.id, 'free', null);
      send(res, 200, {}); return true;
    }
    // checkout
    const body = await jsonBody(req, res, 1024);
    if (!body) return true;
    const item = Object.hasOwn(PRICES, body.plan) ? PRICES[body.plan] : null;
    if (!item) { send(res, 400, { error: 'plan desconocido' }); return true; }
    if (item.plan === 'socio' && !partnerEligible(user.email)) { send(res, 403, { error: 'Tu empresa aún no está autorizada como socio. Escríbenos para darte de alta.' }); return true; }
    if (billingMode() !== 'test') { send(res, 503, { error: 'Los pagos reales aún no están conectados.' }); return true; }
    // MODO PRUEBA: no hay procesador de pagos; se activa el plan sin cobrar. Con un procesador real, aquí se verificaría el pago antes de activar.
    await store.setPlan(user.id, item.plan, new Date(Date.now() + item.days * 86400_000));
    send(res, 200, { plan: item.plan });
    return true;
  } catch (e) {
    console.error('billing:', e.message);
    send(res, 500, { error: 'error interno' }); return true;
  }
}
