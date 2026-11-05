// Cuentas de Kairos: registro, sesión por cookie HttpOnly y perfil por usuario.
// La protección vive aquí: contraseñas con scrypt, sesiones aleatorias guardadas como hash, y cada consulta
// se filtra SIEMPRE por el usuario de la sesión (nunca por un identificador que mande el cliente).
import { randomBytes, randomUUID, scrypt, timingSafeEqual, createHash, createVerify, createPublicKey } from 'node:crypto';
import { promisify } from 'node:util';
import { effectivePlan, partnerEligible, billingMode } from './plans.js';

const scryptAsync = promisify(scrypt);
const N = 32768, R = 8, P = 1, KEYLEN = 64, MAXMEM = 96 * 1024 * 1024;
const COOKIE = 'kairos_sid';
const SESSION_MS = 30 * 24 * 3600 * 1000;
const JSON_H = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };

const sha256 = (s) => createHash('sha256').update(s).digest('hex');

async function hashPassword(pw) {
  const salt = randomBytes(16);
  const key = await scryptAsync(pw, salt, KEYLEN, { N, r: R, p: P, maxmem: MAXMEM });
  return `scrypt$${N}$${R}$${P}$${salt.toString('base64')}$${key.toString('base64')}`;
}
async function verifyPassword(pw, stored) {
  const [alg, n, r, p, salt, key] = String(stored).split('$');
  if (alg !== 'scrypt') return false;
  const expected = Buffer.from(key, 'base64');
  const got = await scryptAsync(pw, Buffer.from(salt, 'base64'), expected.length, { N: +n, r: +r, p: +p, maxmem: MAXMEM });
  return got.length === expected.length && timingSafeEqual(got, expected);
}
// Hash de un usuario inexistente: se compara igual para que el tiempo de respuesta no revele qué correos existen.
const DUMMY = hashPassword('kairos-dummy-password');

/* ---------- Inicio de sesión con Google: el token (JWT) se verifica aquí, con las llaves públicas de Google ---------- */
let jwks = { at: 0, keys: [] };
async function googleKeys() {
  if (jwks.keys.length && Date.now() - jwks.at < 3600_000) return jwks.keys;
  const res = await fetch('https://www.googleapis.com/oauth2/v3/certs', { signal: AbortSignal.timeout(8000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  jwks = { at: Date.now(), keys: (await res.json()).keys || [] };
  return jwks.keys;
}
/** Devuelve { email } si el token es de Google, está firmado, vigente y emitido para NUESTRO client id; si no, null. */
export async function verifyGoogleToken(token, clientId, getKeys = googleKeys) {
  const parts = String(token || '').split('.');
  if (parts.length !== 3 || String(token).length > 4096 || !clientId) return null;
  try {
    const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString()), payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
    if (header.alg !== 'RS256') return null;
    const jwk = (await getKeys()).find((k) => k.kid === header.kid);
    if (!jwk) return null;
    const ok = createVerify('RSA-SHA256').update(`${parts[0]}.${parts[1]}`).verify(createPublicKey({ key: jwk, format: 'jwk' }), Buffer.from(parts[2], 'base64url'));
    if (!ok) return null;
    if (payload.aud !== clientId || !['accounts.google.com', 'https://accounts.google.com'].includes(payload.iss)) return null;
    if (!(payload.exp > Date.now() / 1000) || payload.email_verified !== true || typeof payload.email !== 'string') return null;
    return { email: payload.email.trim().toLowerCase() };
  } catch { return null; }
}

/* ---------- Límite de intentos (en memoria; basta para una sola instancia) ---------- */
const hits = new Map();
function limited(key, max, windowMs) {
  const now = Date.now(); let h = hits.get(key);
  if (!h || h.reset < now) { h = { n: 0, reset: now + windowMs }; hits.set(key, h); }
  h.n += 1;
  if (hits.size > 5000) for (const [k, v] of hits) if (v.reset < now) hits.delete(k);
  return h.n > max;
}
const clientIp = (req) => String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() || req.socket.remoteAddress || '?';

/* ---------- Utilidades de petición ---------- */
const send = (res, code, obj, headers = {}) => { res.writeHead(code, { 'X-Content-Type-Options': 'nosniff', ...JSON_H, ...headers }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
// Lee el cuerpo con tope. Si se pasa, deja de acumular y rechaza; el manejador responde 413 y cierra la conexión.
const readBody = (req, max) => new Promise((ok, fail) => {
  let size = 0, over = false; const chunks = [];
  req.on('data', (c) => { size += c.length; if (size > max) { if (!over) { over = true; fail(Object.assign(new Error('too big'), { tooBig: true })); } } else if (!over) chunks.push(c); });
  req.on('end', () => { if (!over) ok(Buffer.concat(chunks).toString('utf8')); });
  req.on('error', fail);
});
// Devuelve el JSON del cuerpo, o null después de haber respondido el error (400 o 413).
export async function jsonBody(req, res, max) {
  try { return JSON.parse(await readBody(req, max)); }
  catch (e) { send(res, e && e.tooBig ? 413 : 400, { error: e && e.tooBig ? 'cuerpo demasiado grande' : 'cuerpo inválido' }, e && e.tooBig ? { Connection: 'close' } : {}); return null; }
}
const isHttps = (req) => String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim() === 'https';
// Defensa CSRF: además de SameSite=Strict, una petición que cambia datos debe venir del mismo sitio.
export const sameSite = (req) => { const o = req.headers.origin; if (!o) return true; try { return new URL(o).host === req.headers.host; } catch { return false; } };
const cookieOf = (req, name) => { for (const part of String(req.headers.cookie || '').split(';')) { const [k, ...v] = part.trim().split('='); if (k === name) return v.join('='); } return null; };
const setCookie = (req, value, maxAge) => `${COOKIE}=${value}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}${isHttps(req) ? '; Secure' : ''}`;

async function startSession(store, req, res, userId, email, status = 200) {
  const token = randomBytes(32).toString('base64url');
  await store.createSession({ _id: sha256(token), userId, expires: new Date(Date.now() + SESSION_MS) }); // se guarda el hash, no el token
  send(res, status, { email }, { 'Set-Cookie': setCookie(req, token, SESSION_MS / 1000) });
}
export async function currentUser(store, req) {
  const token = cookieOf(req, COOKIE);
  if (!token || token.length > 100) return null;
  const s = await store.session(sha256(token));
  if (!s) return null;
  const u = await store.userById(s.userId);
  return u ? { id: u._id, email: u.email, tokenHash: s._id, plan: effectivePlan(u), planUntil: u.planUntil || null } : null;
}

/** Plan de quien hace la petición. Sin cuentas en el servidor (sin MongoDB) todo queda abierto como premium. */
export async function sessionInfo(getStore, req) {
  let store; try { store = await getStore(); } catch { store = null; }
  if (!store) return { open: true, plan: 'premium', user: null };
  const user = await currentUser(store, req);
  return { open: false, plan: user ? user.plan : 'free', user };
}

/* ---------- Validación del perfil: el servidor no confía en lo que manda el navegador ---------- */
const MAX_PROFILE_BYTES = 20_000;
function cleanData(d) {
  if (!d || typeof d !== 'object' || Array.isArray(d)) return null;
  const profile = d.profile, done = d.done;
  if (!profile || typeof profile !== 'object' || Array.isArray(profile)) return null;
  const out = { profile, done: {}, region: typeof d.region === 'string' ? d.region.slice(0, 40) : undefined };
  // Zona (solo municipio y estado, nunca coordenadas): sirve a las estadísticas agregadas de quien aceptó participar.
  const z = d.zone;
  if (z && typeof z === 'object' && typeof z.municipio === 'string' && typeof z.estado === 'string') {
    out.zone = { municipio: z.municipio.slice(0, 80), estado: z.estado.slice(0, 60), mode: z.mode === 'demo' ? 'demo' : 'gps' };
  }
  if (done && typeof done === 'object' && !Array.isArray(done)) {
    for (const [k, v] of Object.entries(done).slice(0, 500)) if (v === true && k.length <= 120) out.done[k] = true;
  }
  return JSON.stringify(out).length <= MAX_PROFILE_BYTES ? out : null;
}

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const unavailable = (res) => send(res, 503, { error: 'cuentas no disponibles' });

/** Atiende las rutas de cuenta. Devuelve true si la ruta era de cuentas. */
export async function handleAccounts(req, res, pathname, getStore) {
  const route = { '/api/auth/google': 'POST', '/api/auth/signup': 'POST', '/api/auth/login': 'POST', '/api/auth/logout': 'POST', '/api/me': 'GET', '/api/profile': 'PUT', '/api/account': 'DELETE' }[pathname];
  if (!route) return false;
  if (req.method !== route) { send(res, 405, { error: 'método no permitido' }, { Allow: route }); return true; }
  if (route !== 'GET' && !sameSite(req)) { send(res, 403, { error: 'origen no permitido' }); return true; }

  let store;
  try { store = await getStore(); } catch { store = null; }
  if (!store) { unavailable(res); return true; }

  try {
    if (pathname === '/api/auth/google') {
      const clientId = process.env.GOOGLE_CLIENT_ID;
      if (!clientId) { send(res, 503, { error: 'Google no está configurado' }); return true; }
      const body = await jsonBody(req, res, 6000);
      if (!body) return true;
      if (limited(`google:${clientIp(req)}`, 30, 600_000)) { send(res, 429, { error: 'Demasiados intentos. Espera unos minutos.' }); return true; }
      const info = await verifyGoogleToken(body.credential, clientId);
      if (!info || info.email.length > 254) { send(res, 401, { error: 'No se pudo verificar tu cuenta de Google.' }); return true; }
      // Google ya verificó el correo: si existe una cuenta con él, se entra a ella; si no, se crea (sin contraseña: hash "google" nunca coincide).
      let u = await store.userByEmail(info.email);
      if (!u) {
        await store.createUser({ _id: randomUUID(), email: info.email, hash: 'google', createdAt: new Date() });
        u = await store.userByEmail(info.email);
      }
      await startSession(store, req, res, u._id, info.email);
      return true;
    }
    if (pathname === '/api/auth/signup' || pathname === '/api/auth/login') {
      const body = await jsonBody(req, res, 2048);
      if (!body) return true;
      const email = typeof body.email === 'string' ? body.email.trim().toLowerCase() : '';
      const pw = typeof body.password === 'string' ? body.password : '';
      if (!EMAIL_RE.test(email) || email.length > 254) { send(res, 400, { error: 'Escribe un correo válido.' }); return true; }
      if (pw.length < 8 || pw.length > 200) { send(res, 400, { error: 'La contraseña debe tener entre 8 y 200 caracteres.' }); return true; }
      const ip = clientIp(req);

      if (pathname === '/api/auth/signup') {
        if (limited(`signup:${ip}`, 10, 3600_000)) { send(res, 429, { error: 'Demasiados intentos. Espera un rato.' }); return true; }
        const ok = await store.createUser({ _id: randomUUID(), email, hash: await hashPassword(pw), createdAt: new Date() });
        if (!ok) { send(res, 409, { error: 'Ese correo ya tiene cuenta. Inicia sesión.' }); return true; }
        const u = await store.userByEmail(email);
        await startSession(store, req, res, u._id, email, 201);
        return true;
      }
      if (limited(`login:${ip}`, 20, 600_000) || limited(`loginmail:${email}`, 8, 600_000)) { send(res, 429, { error: 'Demasiados intentos. Espera unos minutos.' }); return true; }
      const u = await store.userByEmail(email);
      const good = u ? await verifyPassword(pw, u.hash) : (await verifyPassword(pw, await DUMMY), false);
      if (!good) { send(res, 401, { error: 'Correo o contraseña incorrectos.' }); return true; }
      await startSession(store, req, res, u._id, email);
      return true;
    }

    const user = await currentUser(store, req);
    if (pathname === '/api/auth/logout') {
      const token = cookieOf(req, COOKIE);
      if (token && token.length <= 100) await store.deleteSession(sha256(token));
      send(res, 200, {}, { 'Set-Cookie': setCookie(req, '', 0) });
      return true;
    }
    if (!user) { send(res, 401, {}); return true; }

    if (pathname === '/api/me') {
      const p = await store.profile(user.id);
      const env = process.env;
      send(res, 200, { email: user.email, plan: user.plan, planUntil: user.planUntil, partnerEligible: partnerEligible(user.email), billing: billingMode(),
        // La clave del mapa solo se entrega a quien tiene Premium.
        maps: user.plan === 'premium' && env.GOOGLE_MAPS_KEY ? { key: env.GOOGLE_MAPS_KEY } : null, data: (p && p.data) || null });
      return true;
    }
    if (pathname === '/api/profile') {
      const body = await jsonBody(req, res, MAX_PROFILE_BYTES + 1024);
      if (!body) return true;
      const data = cleanData(body && body.data);
      if (!data) { send(res, 400, { error: 'datos inválidos' }); return true; }
      await store.saveProfile(user.id, data);     // siempre el usuario de la sesión, nunca un id del cliente
      send(res, 200, {});
      return true;
    }
    if (pathname === '/api/account') {
      await store.deleteUser(user.id);
      send(res, 200, {}, { 'Set-Cookie': setCookie(req, '', 0) });
      return true;
    }
  } catch (e) {
    console.error('accounts:', e.message);
    send(res, 500, { error: 'error interno' });
    return true;
  }
  return true;
}

export const _test = { hashPassword, verifyPassword, cleanData, verifyGoogleToken };
