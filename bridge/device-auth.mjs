import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function hash(value) { return crypto.createHash('sha256').update(String(value)).digest('hex'); }
function read(filePath) { try { return JSON.parse(fs.readFileSync(filePath, 'utf8')); } catch { return { devices: {}, pairings: {} }; } }
function write(filePath, data) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = filePath + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n');
  fs.renameSync(tmp, filePath);
}

export function createDeviceAuth({ filePath = path.join(os.homedir(), '.agentbridge', 'devices.json'), now = () => Date.now() } = {}) {
  let data = read(filePath);
  if (!data.devices) data.devices = {};
  if (!data.pairings) data.pairings = {};
  // Yanlış kod denemeleri. IP başına sınır yalnız o adresi durdurur; sahibin
  // eşleştirmesini kilitlemez. Genel eşik aşılırsa (dağıtık deneme) bütün açık
  // kodlar iptal edilir: kaba kuvvet bir koda ancak birkaç düzine deneme
  // harcayabilir, sahip yeni kod üretir.
  let failedPairingAttempts = [];
  const PER_ADDRESS_LIMIT = 10;
  const GLOBAL_LIMIT = 100;
  const persist = () => write(filePath, data);
  const cleanup = () => {
    for (const [codeHash, pairing] of Object.entries(data.pairings)) if (pairing.expiresAt <= now()) delete data.pairings[codeHash];
  };

  function authenticate(token) {
    const tokenHash = hash(token);
    const device = Object.values(data.devices).find(item => item.keyHash === tokenHash && !item.revokedAt);
    if (!device) return null;
    if (!device.lastSeenAt || now() - Date.parse(device.lastSeenAt) > 60_000) {
      device.lastSeenAt = new Date(now()).toISOString();
      try { persist(); } catch {}
    }
    return { id: device.id, name: device.name };
  }

  function startPairing() {
    cleanup();
    let code;
    do { code = String(crypto.randomInt(0, 1000000)).padStart(6, '0'); } while (data.pairings[hash(code)]);
    const expiresAt = now() + 5 * 60 * 1000;
    data.pairings[hash(code)] = { expiresAt };
    persist();
    return { ok: true, code, expiresAt: new Date(expiresAt).toISOString() };
  }

  function completePairing({ code, name, address = '' }) {
    cleanup();
    failedPairingAttempts = failedPairingAttempts.filter(f => now() - f.at < 60_000);
    if (failedPairingAttempts.filter(f => f.address === address).length >= PER_ADDRESS_LIMIT) {
      return { ok: false, error: 'pairing temporarily rate limited' };
    }
    const codeHash = hash(String(code || '').trim());
    const pairing = data.pairings[codeHash];
    if (!pairing) {
      failedPairingAttempts.push({ at: now(), address });
      if (failedPairingAttempts.length >= GLOBAL_LIMIT && Object.keys(data.pairings).length) {
        data.pairings = {};
        persist();
      }
      return { ok: false, error: 'invalid or expired pairing code' };
    }
    delete data.pairings[codeHash];
    const id = crypto.randomUUID();
    const key = 'abk_' + crypto.randomBytes(32).toString('base64url');
    data.devices[id] = { id, name: String(name || 'Android device').trim().slice(0, 80), keyHash: hash(key), createdAt: new Date(now()).toISOString(), lastSeenAt: null, revokedAt: null };
    persist();
    return { ok: true, deviceId: id, key };
  }

  function rotate(deviceId) {
    const device = data.devices[deviceId];
    if (!device || device.revokedAt) return { ok: false, error: 'device not found' };
    const key = 'abk_' + crypto.randomBytes(32).toString('base64url');
    device.keyHash = hash(key);
    device.rotatedAt = new Date(now()).toISOString();
    persist();
    return { ok: true, deviceId, key };
  }

  function revoke(deviceId) {
    const device = data.devices[deviceId];
    if (!device) return { ok: false, error: 'device not found' };
    if (!device.revokedAt) {
      device.revokedAt = new Date(now()).toISOString();
      persist();
    }
    return { ok: true, deviceId };
  }

  function list() { return Object.values(data.devices).map(({ keyHash, ...device }) => device); }
  return { authenticate, startPairing, completePairing, rotate, revoke, list };
}
