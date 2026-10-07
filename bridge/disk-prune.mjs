import fs from 'node:fs';
import path from 'node:path';

// Diskte biriken GECICI uretimleri yas'a gore budar.
//
// Neden ayri modul: iki ayri yerde ayni sey birikiyordu ve ikisi de sessizce
// buyudu — paylasilan dosyalarin kopyasi (`~/.agentbridge/share-notes`) ve her
// cikarimin arkasinda biraktigi agy konusmasi (`~/.gemini/<home>/brain` +
// `conversations`). Ikincisi 19.08.2026'da 239 MB olculdu; kimse bakmiyordu
// cunku kullanicinin gordugu bir yerde degil.
//
// Olcut mtime: "en son ne zaman dokunuldu". Dosya adindaki zaman damgasina
// GUVENILMEZ — paylasilan dosyanin adi kullanicidan geliyor.

/**
 * `dir` altindaki, `maxAgeMs`'ten daha eski girdileri siler. Dosya da klasor de
 * olabilir (agy konusmalari klasor, paylasim kopyalari dosya).
 *
 * Klasor YOKSA hata degil: budama tetiklendiginde henuz hic uretim olmamis
 * olabilir. Tek tek silme hatalari da yutulur (Windows'ta dosya kilitli
 * olabilir) — bir sonraki turda yeniden denenir; budama kritik yol degil.
 */
export function pruneOlderThan({ dir, maxAgeMs, now = () => Date.now(), log = () => {} } = {}) {
  const sonuc = { removed: 0, kept: 0, freedBytes: 0, failed: 0 };
  if (!dir || !Number.isFinite(maxAgeMs) || maxAgeMs <= 0) return sonuc;
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return sonuc;
  }
  const simdi = now();
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    let st;
    try { st = fs.statSync(full); } catch { continue; }
    if (simdi - st.mtimeMs <= maxAgeMs) { sonuc.kept += 1; continue; }
    // Boyut silmeden ONCE olculur; klasorde ozyinelemeli toplanir ki loglanan
    // rakam gercek kazanci soylesin.
    const boyut = entry.isDirectory() ? dirSize(full) : (st.size || 0);
    try {
      fs.rmSync(full, { recursive: true, force: true });
      sonuc.removed += 1;
      sonuc.freedBytes += boyut;
    } catch (e) {
      sonuc.failed += 1;
      log(`budama: ${full} silinemedi: ${String(e?.message || e).slice(0, 120)}`);
    }
  }
  return sonuc;
}

function dirSize(dir) {
  let toplam = 0;
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return 0; }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) { toplam += dirSize(full); continue; }
    try { toplam += fs.statSync(full).size || 0; } catch {}
  }
  return toplam;
}

/** Birden fazla klasoru tek turda budar, toplam raporlar. */
export function pruneAll(dirs, { maxAgeMs, now, log = () => {} } = {}) {
  const toplam = { removed: 0, kept: 0, freedBytes: 0, failed: 0 };
  for (const dir of dirs) {
    const r = pruneOlderThan({ dir, maxAgeMs, now, log });
    toplam.removed += r.removed;
    toplam.kept += r.kept;
    toplam.freedBytes += r.freedBytes;
    toplam.failed += r.failed;
  }
  return toplam;
}
