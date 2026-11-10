import test from 'node:test';
import assert from 'node:assert/strict';
import { aggregate } from '../stats.js';

const person = (estado, answers = {}, extra = {}) => ({
  data: { profile: { children: 1, answers, kit: { a: 1, b: 1 }, ...extra.profile }, done: {}, zone: { municipio: 'X', estado, mode: 'gps', ...extra.zone } },
});

test('un grupo menor al mínimo se suprime', () => {
  delete process.env.STATS_MIN_GROUP;
  const docs = Array.from({ length: 9 }, () => person('Guerrero'));
  const r = aggregate(docs);
  assert.equal(r.grupos.length, 0);
  assert.deepEqual(r.suprimidos, { groups: 1, people: 9 });
});

test('con 10 personas el grupo sale con porcentajes', () => {
  const docs = Array.from({ length: 10 }, (_, i) => person('Guerrero', { 'f-route': i < 4 ? 'no' : 'yes' }));
  const r = aggregate(docs);
  assert.equal(r.grupos.length, 1);
  const g = r.grupos[0];
  assert.equal(g.zona, 'Guerrero');
  assert.equal(g.personas, 10);
  assert.equal(g.hogares.conNinos, 100);
  assert.equal(g.carencias.find((c) => c.id === 'f-route').pct, 40);
});

test('una carencia con menos respuestas que el mínimo no se publica', () => {
  const docs = Array.from({ length: 10 }, (_, i) => person('Guerrero', i < 3 ? { 'h-radio': 'no' } : {}));
  assert.equal(aggregate(docs).grupos[0].carencias.some((c) => c.id === 'h-radio'), false);
});

test('los perfiles demo y sin zona no cuentan salvo STATS_INCLUDE_DEMO=1', () => {
  const docs = [...Array.from({ length: 10 }, () => person('Oaxaca', {}, { zone: { mode: 'demo' } })), { data: { profile: {}, done: {} } }];
  const r = aggregate(docs);
  assert.equal(r.grupos.length, 0);
  assert.equal(r.sinZona, 11);
  process.env.STATS_INCLUDE_DEMO = '1';
  assert.equal(aggregate(docs).grupos.length, 1);
  delete process.env.STATS_INCLUDE_DEMO;
});

test('el nivel municipio agrupa por municipio y estado', () => {
  const docs = Array.from({ length: 10 }, () => person('Guerrero', {}, { zone: { municipio: 'Acapulco' } }));
  assert.equal(aggregate(docs, 'municipio').grupos[0].zona, 'Acapulco, Guerrero');
});
