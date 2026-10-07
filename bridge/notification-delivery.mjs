import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

// Tek kanal: uygulamanin long-poll baglantisi. Cihaz = uygulama kurulum UUID'si
// (deviceId); /notifications/device ile kaydolur. Bekleyen mesajlar kopma ve
// yeniden baslatmada korunur; yalniz Android'in "gosterdim" onayi tamamlar.
const DEVICE_ID_RE = /^[a-zA-Z0-9-]{16,80}$/;
// Kaldirilan/yeniden kurulan uygulamanin eski UUID'si sonsuza dek kuyruk biriktirmesin.
const DEVICE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
// Her poll turunda yeniden kayit geliyor; lastSeen'i diske bu siklikla yaz.
const SEEN_SAVE_MS = 60 * 60 * 1000;

// Eski (ADB seri numarasina bagli) dosya: seri->deviceId eslemesi biliniyorsa
// mesaj tasinir, bilinmiyorsa atilir. Hatirlatmalar kaybolmaz; notta `pending`
// kaldiklari icin sonraki turda ayni deliveryId ile yeniden kuyruga girer.
function loadState(filePath, nowMs) {
  let raw = null;
  try {
    if (filePath && fs.existsSync(filePath)) raw = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    raw = null;
  }
  const state = { clients: {}, messages: [] };
  const serialToDevice = new Map();
  for (const c of Object.values(raw?.clients || {})) {
    if (!c || !DEVICE_ID_RE.test(c.deviceId || '')) continue;
    if (c.serial) serialToDevice.set(c.serial, c.deviceId);
    state.clients[c.deviceId] = {
      deviceId: c.deviceId,
      model: String(c.model || ''),
      lastSeen: Number.isFinite(c.lastSeen) ? c.lastSeen : nowMs,
    };
  }
  for (const m of Array.isArray(raw?.messages) ? raw.messages : []) {
    if (!m || !m.extras) continue;
    const deviceId = m.deviceId || serialToDevice.get(m.serial);
    if (!deviceId || !state.clients[deviceId]) continue;
    const eventId = String(m.extras.deliveryId || m.id);
    state.messages.push({ id: `${deviceId}:${eventId}`, deviceId, extras: m.extras, delivered: !!m.delivered, at: m.at || nowMs });
  }
  return state;
}

export function createNotificationDelivery({ filePath, now = Date.now, isValid = () => true }) {
  const state = loadState(filePath, now());
  function save() {
    // Teslim edilmemis hatirlatma asla atilmaz; tekrar denemeler icin yakin makbuzlar kalir.
    const receipts = state.messages.filter(m => m.delivered).slice(-2048);
    state.messages = [...state.messages.filter(m => !m.delivered), ...receipts];
    if (!filePath) return;
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    const tmp = `${filePath}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state), 'utf8');
    fs.renameSync(tmp, filePath);
  }
  function pruneStale() {
    const cutoff = now() - DEVICE_TTL_MS;
    const stale = Object.values(state.clients).filter(c => c.lastSeen < cutoff).map(c => c.deviceId);
    if (stale.length === 0) return false;
    for (const id of stale) delete state.clients[id];
    state.messages = state.messages.filter(m => state.clients[m.deviceId]);
    return true;
  }
  if (pruneStale()) save();

  function configure({ deviceId, model } = {}) {
    if (!DEVICE_ID_RE.test(deviceId || '')) return { ok: false, error: 'Geçersiz bildirim cihazı' };
    const m = String(model || '').trim().slice(0, 120);
    const existing = state.clients[deviceId];
    const t = now();
    if (!existing || (m && existing.model !== m) || t - existing.lastSeen > SEEN_SAVE_MS) {
      state.clients[deviceId] = { deviceId, model: m || existing?.model || '', lastSeen: t };
      pruneStale();
      save();
    }
    return { ok: true, deviceId };
  }
  // Turu baslatan cihaz yalniz model adiyla biliniyor (X-Device-Model). Ayni
  // modelden iki cihaz varsa ikisi de doner: susturmaktansa fazladan otmek.
  function deviceIdsForModel(model) {
    const m = String(model || '').trim();
    if (!m) return [];
    return Object.values(state.clients).filter(c => c.model === m).map(c => c.deviceId);
  }
  function retireInvalid() {
    let retired = false;
    for (const m of state.messages) {
      if (!m.delivered && !isValid(m.extras)) {
        m.delivered = true;
        retired = true;
      }
    }
    if (retired) save();
  }
  function snapshot(deviceId) {
    // Kayitsiz cihaz (eski APK, web) kuyrugu gormez; alan hic eklenmez.
    if (!state.clients[deviceId]) return {};
    retireInvalid();
    return {
      pushEvents: state.messages
        .filter(m => m.deviceId === deviceId && !m.delivered)
        .slice(0, 50).map(m => ({ ...m.extras, deliveryId: m.id })),
    };
  }
  function acknowledge(deviceId, ids) {
    if (!state.clients[deviceId] || !Array.isArray(ids) || ids.length > 50) return { ok: false, error: 'Geçersiz bildirim onayı' };
    for (const m of state.messages) {
      if (m.deviceId === deviceId && ids.includes(m.id)) m.delivered = true;
    }
    save();
    return { ok: true };
  }
  function outcome(m) {
    if (!m.delivered && !isValid(m.extras)) {
      m.delivered = true; // Kaynaginda silinmis/ertelenmis: eski hatirlatma gosterilmez.
    }
    return m.delivered ? { deviceId: m.deviceId, ok: true } : { deviceId: m.deviceId, ok: false, pending: true };
  }
  // targets: deviceId listesi. Hic biri kayitli degilse (ya da verilmediyse)
  // tum kayitli cihazlara gider.
  async function send(extras, opts = {}) {
    const all = Object.keys(state.clients);
    const wanted = Array.isArray(opts.targets) ? all.filter(d => opts.targets.includes(d)) : [];
    const targets = wanted.length ? wanted : all;
    const eventId = String(extras.deliveryId || randomUUID());
    const messages = targets.map(deviceId => {
      const id = `${deviceId}:${eventId}`;
      let m = state.messages.find(item => item.id === id);
      if (!m) {
        m = { id, deviceId, extras, delivered: false, at: now() };
        state.messages.push(m);
      }
      return m;
    });
    const results = messages.map(outcome);
    save();
    return results;
  }
  return { configure, snapshot, acknowledge, send, deviceIdsForModel };
}
