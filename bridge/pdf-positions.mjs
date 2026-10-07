// Belge başına "kaldığı yer" — CİHAZLAR ARASI ortak kayıt.
//
// Konum eskiden yalnız telefonun SharedPreferences'ındaydı; tablette aynı
// kitabı açınca baştan başlıyordu. Köprü ikisinin de gördüğü tek yer olduğu
// için kayıt buraya taşındı. Cihaz yine kendi kopyasını tutuyor (çevrimdışıyken
// ve pager'ın ilk kompozisyonunda senkron okunabilmesi için); burası hakem.
//
// Çakışma kuralı SON YAZAN KAZANIR: gelen kaydın `savedAt`i dosyadakinden
// eskiyse yazılmaz ve dosyadaki geri döner. Böylece uzun süre kapalı kalmış bir
// cihaz açılışta bayat konumunu diğerinin üstüne yazamaz.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

// Yol testte değiştirilebilir: rota testi gerçek kaydın üstüne yazmasın.
const DEFAULT_FILE = process.env.AGENTBRIDGE_PDF_POSITIONS
  || path.join(path.dirname(fileURLToPath(import.meta.url)), 'pdf-positions.json');

// Tavan: her açılan belge tek satır bırakıyor, sınırsız büyümesin. Aşınca en
// eski (küçük savedAt) kayıtlar düşer — Android tarafındaki LRU ile aynı kural.
export const MAX_ENTRIES = 500;

/**
 * Yol anahtarı. Android'deki `normalizedPathKey` ile AYNI kuralı uygular:
 * ters bölü → bölü, sondaki bölüler atılır, küçük harfe indirilir. Aynı belgeye
 * `C:\...` ve `c:/...` diye ulaşan iki cihaz tek kayıt paylaşsın diye.
 */
export function positionKey(p) {
  return String(p || '').trim().replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
}

function sanitize(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const page = Number.parseInt(raw.page, 10);
  if (!Number.isFinite(page) || page < 1) return null;
  return {
    page,
    ri: Math.max(0, Number.parseInt(raw.ri, 10) || 0),
    ro: Math.max(0, Number.parseInt(raw.ro, 10) || 0),
    // Yalnız iki kip var: "p" sayfa görünümü, "r" okuma modu.
    mode: raw.mode === 'r' ? 'r' : 'p',
    savedAt: Math.max(0, Number.parseInt(raw.savedAt, 10) || 0),
  };
}

function readAll(file) {
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    // Dosya yok ya da bozuk: konum hatırlamamak bir hata değil, sıfırdan başlar.
    return {};
  }
}

function writeAll(file, map) {
  const entries = Object.entries(map);
  const kept = entries.length <= MAX_ENTRIES
    ? entries
    : entries.sort((a, b) => (b[1]?.savedAt || 0) - (a[1]?.savedAt || 0)).slice(0, MAX_ENTRIES);
  const out = Object.fromEntries(kept);
  try {
    const tmp = `${file}.tmp-${process.pid}`;
    fs.writeFileSync(tmp, JSON.stringify(out));
    fs.renameSync(tmp, file);
  } catch {
    // Yazamamak özelliği durdurmaz; cihazdaki yerel kopya ayakta.
  }
  return out;
}

/** Kayıtlı konum ya da null. */
export function getPosition(docPath, { file = DEFAULT_FILE } = {}) {
  const key = positionKey(docPath);
  if (!key) return null;
  return sanitize(readAll(file)[key]);
}

/**
 * Konumu yazar. Dönen değer KAZANAN kayıttır: gelen bayatsa dosyadaki döner,
 * böylece çağıran cihaz kendi kopyasını düzeltebilir.
 */
export function putPosition(docPath, position, { file = DEFAULT_FILE } = {}) {
  const key = positionKey(docPath);
  if (!key) return { ok: false, reason: 'yol boş' };
  const incoming = sanitize(position);
  if (!incoming) return { ok: false, reason: 'konum geçersiz' };
  const map = readAll(file);
  const current = sanitize(map[key]);
  if (current && current.savedAt > incoming.savedAt) return { ok: true, position: current, stored: false };
  map[key] = incoming;
  writeAll(file, map);
  return { ok: true, position: incoming, stored: true };
}

export const _internals = { DEFAULT_FILE, sanitize, readAll, writeAll };
