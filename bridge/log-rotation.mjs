// Boyut-tabanlı log rotasyonu (madde 14).
//
// Köprü bridge.log dosyası `run-bridge.cmd` içindeki `>> bridge.log 2>&1` CMD
// append yönlendirmesiyle büyür; rotasyon yoktu ve dosya 44,8 MB'a ulaşmıştı.
// Bu modül saf bir rotasyon fonksiyonu sunar: dosya boyutu eşiği aşınca en eski
// nesil silinir, kalanlar bir kaydırılır ve aktif dosya sıfırlanır.
//
// Nesil şeması (maxGenerations=3 varsayılan):
//   bridge.log   → bridge.log.1
//   bridge.log.1 → bridge.log.2
//   bridge.log.2 → bridge.log.3  (en eski nesil; bir sonraki rotasyonda silinir)
// En fazla (maxGenerations - 1) arşiv nesli tutulur; tavan ~15 MB.

import fs from 'node:fs';

export const ROTATION_DEFAULTS = Object.freeze({
  // 5 MB — üstüne çıkınca rotasyon tetiklenir.
  maxBytes: 5 * 1024 * 1024,
  // Aktif dosya dahil tutulacak nesil sayısı. 3 → aktif + .1 + .2.
  maxGenerations: 3,
  // Periyodik kontrol aralığı (server.mjs setInterval'i için referans).
  intervalMs: 10 * 60 * 1000,
});

/**
 * `filePath` boyutu `maxBytes` eşiğini aştıysa rotasyon yapar.
 *
 * Taşıma: mevcut .N dosyaları bir ileri kaydırılır (en yüksek N'ten başlanır),
 * aktif dosya `.1` olarak yeniden adlandırılır ve yeni boş aktif dosya oluşturulur.
 * maxGenerations tavanına ulaşmış en eski nesil silinir.
 *
 * Dosya yoksa veya eşik altındaysa hiçbir şey yapılmaz; hata fırlatılmaz.
 *
 * @param {string} filePath - Aktif log dosyasının mutlak yolu (örn. ".../bridge.log").
 * @param {{maxBytes?: number, maxGenerations?: number}} [opts]
 * @returns {{rotated: boolean, archived?: string, dropped?: string}}
 *   rotated: rotasyon yapıldıysa true. archived: yeni oluşturulan .1 yolu.
 *   dropped: tavan nedeniyle silinen en eski nesil yolu (varsa).
 */
export function rotateLogIfNeeded(filePath, opts = {}) {
  const maxBytes = opts.maxBytes ?? ROTATION_DEFAULTS.maxBytes;
  const maxGenerations = opts.maxGenerations ?? ROTATION_DEFAULTS.maxGenerations;

  // Dosya yoksa dokunma.
  let stat;
  try {
    stat = fs.statSync(filePath);
  } catch {
    return { rotated: false };
  }

  if (stat.size < maxBytes) {
    return { rotated: false };
  }

  // maxGenerations=1 → arşiv tutma, yalnızca aktif dosyayı sıfırla.
  if (maxGenerations <= 1) {
    fs.writeFileSync(filePath, '');
    return { rotated: true };
  }

  // En yüksek numaralı nesilden başlayarak bir ileri kaydır.
  // Arşiv nesilleri: .1 .. .(maxGenerations-1). En eski = .(maxGenerations-1).
  const oldestGen = maxGenerations - 1; // örn. 3 → .2 en eski arşiv
  let dropped;
  // Tavan dışı kalmış kalıntı nesilleri (.N, N > oldestGen) önce sil.
  // Geçmişte daha yüksek maxGenerations'la oluşturulmuş arşivler birikmesin.
  for (let n = oldestGen + 1; n <= oldestGen + 20; n++) {
    const stale = `${filePath}.${n}`;
    if (fs.existsSync(stale)) {
      fs.unlinkSync(stale);
      dropped = stale;
    }
  }
  // En yüksek numaralı tutulabilir nesilden başla, bir ileri kaydır.
  for (let n = oldestGen; n >= 1; n--) {
    const src = `${filePath}.${n}`;
    if (!fs.existsSync(src)) continue;
    if (n === oldestGen) {
      // Tavandaki en eski nesil silinir (üzerine yazılmaz).
      fs.unlinkSync(src);
      dropped = src;
    } else {
      fs.renameSync(src, `${filePath}.${n + 1}`);
    }
  }

  // Aktif dosyayı .1'e taşı, ardından yeni boş aktif dosya oluştur.
  // Önce rename (içerik .1'de korunur), sonra writeFileSync yeni boş dosya.
  const archived = `${filePath}.1`;
  fs.renameSync(filePath, archived);
  fs.writeFileSync(filePath, '');
  return { rotated: true, archived, dropped };
}
