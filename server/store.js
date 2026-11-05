// Almacenamiento en MongoDB Atlas. La cadena de conexión (MONGODB_URI) solo existe en el servidor: nunca llega al navegador.
// Se conecta la primera vez que hace falta; si falla, se reintenta en la siguiente petición en vez de tumbar el servidor.
import { MongoClient } from 'mongodb';

const DB_NAME = process.env.MONGODB_DB || 'kairos';
let pending = null;

async function open(uri) {
  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 8000, appName: 'kairos' });
  await client.connect();
  const db = client.db(DB_NAME);
  const users = db.collection('users'), sessions = db.collection('sessions'), profiles = db.collection('profiles'), pushSubs = db.collection('pushSubs');
  await Promise.all([
    users.createIndex({ email: 1 }, { unique: true }),                 // un correo = una cuenta
    sessions.createIndex({ expires: 1 }, { expireAfterSeconds: 0 }),   // Mongo borra solo las sesiones vencidas
    sessions.createIndex({ userId: 1 }),
    pushSubs.createIndex({ userId: 1 }),
  ]);
  return {
    async userByEmail(email) { return users.findOne({ email }); },
    async userById(id) { return users.findOne({ _id: id }); },
    async createUser(doc) {
      try { await users.insertOne(doc); return true; } catch (e) { if (e && e.code === 11000) return false; throw e; }
    },
    async createSession(doc) { await sessions.insertOne(doc); },
    async session(tokenHash) { return sessions.findOne({ _id: tokenHash, expires: { $gt: new Date() } }); },
    async deleteSession(tokenHash) { await sessions.deleteOne({ _id: tokenHash }); },
    async profile(userId) { return profiles.findOne({ _id: userId }); },
    async saveProfile(userId, data) { await profiles.updateOne({ _id: userId }, { $set: { data, updatedAt: new Date() } }, { upsert: true }); },
    async setPlan(userId, plan, planUntil) { await users.updateOne({ _id: userId }, { $set: { plan, planUntil } }); },
    // Perfiles de quienes aceptaron participar en estadísticas agregadas (solo el contenido necesario).
    async consentedProfiles() { return profiles.find({ 'data.profile.consentStats': true }, { projection: { 'data.profile': 1, 'data.done': 1, 'data.zone': 1 } }).limit(100000).toArray(); },
    // Avisos push: un documento por dispositivo (_id = hash del endpoint). `sent` recuerda los últimos eventos avisados.
    async savePushSub(d) { await pushSubs.updateOne({ _id: d._id }, { $set: { userId: d.userId, sub: d.sub, lat: d.lat, lon: d.lon, updatedAt: new Date() }, $setOnInsert: { sent: [], createdAt: new Date() } }, { upsert: true }); },
    async pushSub(userId, id) { return pushSubs.findOne({ _id: id, userId }); },
    async deletePushSub(userId, id) { await pushSubs.deleteOne({ _id: id, userId }); },
    async deletePushSubById(id) { await pushSubs.deleteOne({ _id: id }); },
    async allPushSubs() { return pushSubs.find({}).limit(5000).toArray(); },
    async markPushSent(id, eventId) { await pushSubs.updateOne({ _id: id }, { $push: { sent: { $each: [eventId], $slice: -60 } } }); },
    // Borrado completo: usuario, sesiones, perfil y dispositivos con avisos.
    async deleteUser(userId) {
      await Promise.all([profiles.deleteOne({ _id: userId }), sessions.deleteMany({ userId }), pushSubs.deleteMany({ userId }), users.deleteOne({ _id: userId })]);
    },
  };
}

/** Devuelve el almacén, o null si no hay MONGODB_URI configurada o no se pudo conectar. */
export async function getStore() {
  const uri = process.env.MONGODB_URI;
  if (!uri) return null;
  if (!pending) pending = open(uri).catch((e) => { pending = null; console.error('mongodb:', e.message); throw e; });
  return pending;
}

export const accountsConfigured = () => !!process.env.MONGODB_URI;
