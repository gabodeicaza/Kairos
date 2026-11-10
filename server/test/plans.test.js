import test from 'node:test';
import assert from 'node:assert/strict';
import { effectivePlan, partnerEligible, billingMode, PRICES } from '../plans.js';

const future = new Date(Date.now() + 86400_000), past = new Date(Date.now() - 86400_000);

test('sin usuario o sin plan contratado es free', () => {
  assert.equal(effectivePlan(null), 'free');
  assert.equal(effectivePlan({ email: 'a@b.mx' }), 'free');
});

test('premium vigente se respeta y vencido vuelve a free', () => {
  assert.equal(effectivePlan({ plan: 'premium', planUntil: future }), 'premium');
  assert.equal(effectivePlan({ plan: 'premium', planUntil: past }), 'free');
  assert.equal(effectivePlan({ plan: 'premium' }), 'free');
});

test('socio solo vale si el correo sigue autorizado en PARTNER_EMAILS', () => {
  process.env.PARTNER_EMAILS = ' Empresa@Mx.com , otra@mx.com ';
  assert.equal(partnerEligible('empresa@mx.com'), true);
  assert.equal(effectivePlan({ plan: 'socio', planUntil: future, email: 'EMPRESA@mx.com' }), 'socio');
  assert.equal(effectivePlan({ plan: 'socio', planUntil: future, email: 'nadie@mx.com' }), 'free');
  delete process.env.PARTNER_EMAILS;
  assert.equal(partnerEligible('empresa@mx.com'), false);
});

test('billingMode solo es test con BILLING_MODE=test', () => {
  delete process.env.BILLING_MODE;
  assert.equal(billingMode(), 'off');
  process.env.BILLING_MODE = 'test';
  assert.equal(billingMode(), 'test');
  process.env.BILLING_MODE = 'live';
  assert.equal(billingMode(), 'off');
  delete process.env.BILLING_MODE;
});

test('los precios definen plan y días', () => {
  assert.equal(PRICES.premium_month.mxn, 199);
  assert.equal(PRICES.premium_year.plan, 'premium');
  assert.equal(PRICES.socio_month.plan, 'socio');
});
