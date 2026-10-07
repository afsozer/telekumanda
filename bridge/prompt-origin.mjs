// Bir turu HANGİ cihaz başlattı? — bitiş bildirimini yalnız o cihaza yollamak için.
//
// Kullanıcı şikayeti (23.08.2026): "promptu hangi cihazda verdiysem iş bitiş
// bildirimini yalnız o cihaza göndersen olur mu? ben burada senle
// konuşurken yandaki telefonum dat dat dat ötüp duruyor". Haklı: operasyon
// olayları o güne dek HEDEFSİZ, yani her cihaza gidiyordu.
//
// KİMLİK `Build.MODEL` (X-Device-Model başlığı; istemci her isteğe koyuyor).
// Bildirim kuyruğu cihazları kurulum UUID'siyle tutar ve kayıtta modeli de
// saklar; server.mjs modeli `notificationDelivery.deviceIdsForModel` ile
// kayıtlı cihaz(lar)a çevirir. Prompt isteğinin kendisi UUID taşımadığı için
// ortak alan yine model.
//
// AYNI MODELDEN İKİ CİHAZ olursa eşleşme çoklu çıkar; çağıran (server.mjs) o
// durumda ikisine de yollar. Yanlış cihazı susturmaktansa fazladan bir cihazı
// öttürmek daha az zararlı.
//
// KALICI DEĞİL (bellekte): köprü yeniden başlarsa eşleme kaybolur ve bildirim
// eski davranışa, yani tüm cihazlara döner. Bunun için diske yazmaya değmez —
// kaybın bedeli bir fazladan bildirim, kazancı ise yarım yazılmış bir JSON'un
// hiç olmaması.
const VARSAYILAN_TTL_MS = 6 * 60 * 60 * 1000;
const VARSAYILAN_TAVAN = 500;

export function createPromptOrigin({
  ttlMs = VARSAYILAN_TTL_MS,
  max = VARSAYILAN_TAVAN,
  now = () => Date.now(),
} = {}) {
  // anahtar: "backend:sessionId" → { model, at }
  const kayitlar = new Map();

  const anahtar = (backend, sessionId) => {
    const b = String(backend || '').trim();
    const s = String(sessionId || '').trim();
    return b && s ? `${b}:${s}` : '';
  };

  // Süresi dolanlar ve tavanı aşan en eskiler atılır. Map ekleme sırasını
  // koruduğu için "en eski" ilk anahtardır.
  function budu() {
    const simdi = now();
    for (const [k, v] of kayitlar) {
      if (simdi - v.at > ttlMs) kayitlar.delete(k);
    }
    while (kayitlar.size > max) {
      const ilk = kayitlar.keys().next();
      if (ilk.done) break;
      kayitlar.delete(ilk.value);
    }
  }

  // Model boşsa (eski istemci, web arayüzü, curl) kayıt TUTULMAZ ve arama boş
  // döner — çağıran eski davranışa, tüm cihazlara yollamaya düşer.
  function remember(backend, sessionId, model) {
    const k = anahtar(backend, sessionId);
    const m = String(model || '').trim().slice(0, 120);
    if (!k || !m) return false;
    // Yeniden ekleme sırayı da tazeler: aynı oturuma başka cihazdan prompt
    // verilirse son veren kazanır ve budamada en yeni sayılır.
    kayitlar.delete(k);
    kayitlar.set(k, { model: m, at: now() });
    budu();
    return true;
  }

  // Olay canlı kabuk kimliğiyle de (sessionId) kalıcı kimlikle de (diskId)
  // gelebiliyor; ikisi de denenir. Kabuk yeniden doğduğunda sessionId değişir,
  // diskId kalır.
  function lookup(backend, sessionId, diskId = '') {
    budu();
    for (const aday of [sessionId, diskId]) {
      const k = anahtar(backend, aday);
      if (!k) continue;
      const kayit = kayitlar.get(k);
      if (kayit) return kayit.model;
    }
    return '';
  }

  function forget(backend, sessionId) {
    const k = anahtar(backend, sessionId);
    if (k) kayitlar.delete(k);
  }

  return { remember, lookup, forget, size: () => kayitlar.size };
}
