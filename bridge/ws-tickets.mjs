// Tarayıcı WebSocket'i için tek kullanımlık bilet.
//
// Tarayıcı WebSocket el sıkışmasına `Authorization` başlığı ekleyemiyor. Token'ı
// adrese (`?token=`) koymak onu proxy, tarayıcı geçmişi ve sunucu günlüklerine
// açık bırakıyordu. Bunun yerine istemci başlıkla kimliği doğrulanmış bir
// `POST /ws-ticket` ile kısa ömürlü bir bilet alır ve akışa `?ticket=` ile bağlanır.
// Bilet bir kez kullanılır, 30 saniyede düşer ve kendisini çıkaran kimliğe bağlıdır;
// adreste görünse bile tekrar kullanılamaz. Köprü biletin yalnız SHA-256 özetini tutar.

import crypto from 'node:crypto';

const hash = value => crypto.createHash('sha256').update(String(value)).digest('hex');

export function createWsTickets({ ttlMs = 30_000, maxLive = 1000, now = () => Date.now() } = {}) {
  const live = new Map();

  function prune() {
    const t = now();
    for (const [key, entry] of live) if (entry.expiresAt <= t) live.delete(key);
  }

  function issue(identity) {
    if (!identity) throw new Error('identity required');
    prune();
    if (live.size >= maxLive) live.delete(live.keys().next().value);
    const ticket = 'tkt_' + crypto.randomBytes(32).toString('base64url');
    const expiresAt = now() + ttlMs;
    live.set(hash(ticket), { identity, expiresAt });
    return { ticket, expiresAt: new Date(expiresAt).toISOString() };
  }

  function redeem(ticket) {
    if (!ticket) return null;
    const key = hash(ticket);
    const entry = live.get(key);
    if (!entry) return null;
    live.delete(key);
    return entry.expiresAt > now() ? entry.identity : null;
  }

  return { issue, redeem, size: () => live.size };
}
