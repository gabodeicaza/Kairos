// Planes de Kairos: free, premium y socio. Todo lo que protege valor se decide AQUÍ, en el servidor.
//  - free: anuncios y avisos/noticias solo a 20 km.   - premium: $199/mes o $1,000/año.   - socio: empresas, $1,000/mes; ven estadísticas agregadas.
// El pago real aún no está conectado a un procesador: BILLING_MODE=test activa un cobro SIMULADO (para demostraciones); sin él no se puede subir de plan.

export const PRICES = { premium_month: { plan: 'premium', mxn: 199, days: 31 }, premium_year: { plan: 'premium', mxn: 1000, days: 366 }, socio_month: { plan: 'socio', mxn: 1000, days: 31 } };
export const FREE_RADIUS_KM = 20;

const list = (v) => String(v || '').split(',').map((x) => x.trim().toLowerCase()).filter(Boolean);
/** Solo las empresas autorizadas por el administrador (PARTNER_EMAILS) pueden ser socio. */
export const partnerEligible = (email) => list(process.env.PARTNER_EMAILS).includes(String(email).toLowerCase());
export const billingMode = () => (process.env.BILLING_MODE === 'test' ? 'test' : 'off');

/** Plan vigente de un usuario: el contratado mientras no haya vencido; si no, free. */
export function effectivePlan(u) {
  if (!u) return 'free';
  if ((u.plan === 'premium' || u.plan === 'socio') && u.planUntil && new Date(u.planUntil) > new Date()) {
    return u.plan === 'socio' && !partnerEligible(u.email) ? 'free' : u.plan;   // si le quitan la autorización de socio, deja de serlo
  }
  return 'free';
}
