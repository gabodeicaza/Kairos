import test from 'node:test';
import assert from 'node:assert/strict';
import { qualifies } from '../push.js';

const now = Date.parse('2026-11-10T12:00:00Z');
const here = { lat: 16.86, lon: -99.88 };
const ago = (h) => new Date(now - h * 3600_000).toISOString();
const quake = (title, geo, h = 1) => ({ id: 'q1', kind: 'usgs', title, geo, date: ago(h) });

test('sin ubicación o sin fecha reciente no avisa', () => {
  assert.equal(qualifies(quake('Sismo M6.0', { lat: 16.9, lon: -99.9 }), null, now), null);
  assert.equal(qualifies({ id: 'x', kind: 'usgs', title: 'Sismo M6.0', date: ago(1) }, here, now), null);
  assert.equal(qualifies(quake('Sismo M6.0', { lat: 16.9, lon: -99.9 }, 4), here, now), null);
});

test('sismo fuerte cercano avisa; uno chico y lejano no', () => {
  const near = qualifies(quake('Sismo M6.1 - Guerrero', { lat: 17.0, lon: -100.0 }), here, now);
  assert.match(near.title, /M6\.1/);
  assert.equal(qualifies(quake('Sismo M4.5', { lat: 18.5, lon: -99.9 }), here, now), null);   // ~180 km y M<5
  assert.ok(qualifies(quake('Sismo M5.5', { lat: 18.5, lon: -99.9 }), here, now));
});

test('el plan gratuito solo recibe eventos a 20 km', () => {
  const q = quake('Sismo M6.0', { lat: 17.3, lon: -99.9 });   // ~50 km
  assert.ok(qualifies(q, here, now, Infinity));
  assert.equal(qualifies(q, here, now, 20), null);
});

test('GDACS verde no avisa; naranja de ciclón cercano sí', () => {
  const g = (title) => ({ id: 'g1', kind: 'gdacs', title, geo: { lat: 17.5, lon: -100 }, date: ago(1) });
  assert.equal(qualifies(g('Ciclón tropical alerta verde'), here, now), null);
  assert.equal(qualifies(g('Ciclón tropical alerta naranja'), here, now).title, 'Ciclón cercano');
  assert.equal(qualifies(g('Terremoto alerta roja'), here, now), null);
});
