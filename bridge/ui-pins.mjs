// Seçici sabitlemeleri (yıldızlar) için köprü taraflı depo.
//
// Bu ayar cihaza DEĞİL köprünün dosyasına yazılır: telefon ve tablet aynı
// köprüye bağlandığından yıldızlar aralarında otomatik paylaşılır. İki PC'deki
// iki köprünün depoları ayrıdır — bilinçli bir karar ("kendi köprüsü içinde
// sync"), eşitlenmezler.
//
// Dosya biçimi:
//   { "scopes": { "backend-model:opencode-app": ["opencode-go/glm-5.3"], ... } }
// Scope adları istemciden gelir (SelectorPinStore kapsamları):
//   backend-model:<backendId> · backend-agent:<backendId>
//
// Çakışma politikası: SON YAZAN KAZANIR. Her cihaz toggle'da kapsamın TAM
// setini gönderir; köprüdeki mevcut kapsam değeri ezilir. Aynı anda iki
// cihazda düzenleme beklenmediği için birleştirme karmaşası kurulmadı.
//
// config.json yerine AYRI dosya: config.json'da authToken gibi sırlar var;
// bu depoya dokunan kodun onlara ihtiyacı yok. Dosya git'e girmez
// (.gitignore → bridge/ui-pins.json); içeriği kişiseldir ama sır değildir.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

// Override yolu testler için: temp dosyaya yönlenip köprünün canlı
// ui-pins.json'una dokunmamak için (desen: AGENTBRIDGE_IMAGE_CATALOG).
function pinsFile() {
  const override = String(process.env.AGENTBRIDGE_UI_PINS || '').trim();
  // Her iki dal da URL NESNESI döndürsün: fs işlevleri URL kabul eder ama
  // href string'i ("file:/C:/…") path sanılıp cwd ile karıştırılıyor.
  if (override) return pathToFileURL(override);
  return new URL('./ui-pins.json', import.meta.url);
}

function readMap() {
  try {
    const parsed = JSON.parse(fs.readFileSync(pinsFile(), 'utf-8'));
    return parsed?.scopes && typeof parsed.scopes === 'object' ? parsed.scopes : {};
  } catch {
    // Dosya yok ya da bozuk: boş kabul. Bozuk dosyanın üstüne YAZMAK gizli
    // modellerde olduğu gibi veri kaybına yol açmaz — yıldız küçük oyuncak.
    return {};
  }
}

/**
 * Köprüdeki tüm kapsamları döndürür. existed: dosya hiç oluşmamış mı?
 * İstemci ilk bağlanışta bunu görüp YEREL yıldızlarını bir kez taşır;
 * dosya varsa köprü kaynaktır ve yerel set EZİLİR (son yazan kazanır).
 */
export function readUiPins() {
  let existed = true;
  try { fs.statSync(pinsFile()); } catch { existed = false; }
  const raw = readMap();
  // Kopukluk bırakma: her kapsam dizgilerden temizlenmiş olsun (elle düzeltilen
  // dosyalarda null/"''" satırı gelirse istemciyi bozmasın).
  const scopes = {};
  for (const [scope, keys] of Object.entries(raw)) {
    if (!Array.isArray(keys)) continue;
    const clean = [...new Set(keys.map(k => String(k || '').trim()).filter(Boolean))];
    if (clean.length) scopes[scope] = clean;
  }
  return { existed, pins: scopes };
}

/**
 * Tek bir kapsamın setini YAZAR (diğer kapsamlara dokunmaz) ve tam tabloyu
 * döndürür. Son yazan kazanır: gelen kapsam değeri eskisini ezer.
 */
export function writeUiPinScope(scope, pinned) {
  const key = String(scope || '').trim();
  if (!key) throw new Error('scope gerekli');
  const clean = [...new Set((Array.isArray(pinned) ? pinned : [])
    .map(k => String(k || '').trim()).filter(Boolean))];
  const map = readMap();
  if (clean.length) map[key] = clean;
  else delete map[key];      // boş set = kapsamda yıldız yok; satır bulaşmasın
  fs.writeFileSync(pinsFile(), JSON.stringify({ scopes: map }, null, 2) + '\n', 'utf-8');
  return map;
}
