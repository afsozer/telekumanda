// Tarayıcı arayüzünü (`web/`) statik olarak servis eder.
//
// KİMLİK DOĞRULAMASINDAN ÖNCE çalışır — ve bu bilinçli bir karar:
//
// Köprü server.mjs'de yönlendirmeden önce auth uyguluyor. Arayüz auth'un
// arkasında kalsaydı tarayıcı kilitlenirdi: ilk istek `/ui/?token=...` ile
// geçerdi ama HTML'in referans verdiği `/ui/assets/index-*.js` token taşımaz,
// 401 alır ve sayfa boş kalır. Tarayıcı alt kaynak isteklerine başlık ekleyemez.
//
// Paketin kendisinde sır yok — istemci kodu. Açıkta kalan tek bilgi "burada bir
// AgentBridge var" ki `/health` zaten kimlik doğrulamasız bunu söylüyor.
// API uçlarının hepsi auth'un arkasında kalmaya devam ediyor.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BRIDGE_DIR = path.dirname(fileURLToPath(import.meta.url));
export const WEB_DIST = path.join(BRIDGE_DIR, '..', 'web', 'dist');

export const UI_PREFIX = '/ui';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

/** İstek yolu arayüze mi ait? `/ui`, `/ui/`, `/ui/...` — ama `/uiXYZ` değil. */
export function isUiPath(pathname) {
  return pathname === UI_PREFIX || pathname.startsWith(UI_PREFIX + '/');
}

/**
 * `/ui/...` yolunu dist içindeki mutlak dosya yoluna çevirir.
 * Dist kökünün dışına çıkan her istek (`..`, mutlak yol, kodlanmış ayraç)
 * `null` döner. Saf fonksiyon — testte doğrudan çağrılır.
 */
export function resolveAsset(pathname, root = WEB_DIST) {
  if (!isUiPath(pathname)) return null;
  let rel = pathname.slice(UI_PREFIX.length);
  try {
    rel = decodeURIComponent(rel);
  } catch {
    return null; // bozuk yüzde kodlaması
  }
  // Ters bölü Windows'ta ayraçtır; `..\..` ile kaçışı engellemek için normalize et.
  rel = rel.replace(/\\/g, '/');
  if (rel === '' || rel === '/') return path.join(root, 'index.html');
  const target = path.resolve(root, '.' + (rel.startsWith('/') ? rel : '/' + rel));
  const rootResolved = path.resolve(root);
  const relFromRoot = path.relative(rootResolved, target);
  if (relFromRoot.startsWith('..') || path.isAbsolute(relFromRoot)) return null;
  return target;
}

/**
 * Önbellek başlığı. Vite varlıkları içerik özetiyle adlandırıyor
 * (`index-Bkm36FbW.js`), yani sonsuza dek önbelleklenebilir. index.html
 * ise asla — yoksa yeni derleme kullanıcıya hiç ulaşmaz.
 */
export function cacheControl(filePath) {
  return /[\\/]assets[\\/]/.test(filePath)
    ? 'public, max-age=31536000, immutable'
    : 'no-store';
}

function send(res, code, headers, body) {
  res.writeHead(code, headers);
  res.end(body);
}

/**
 * İsteği karşılarsa true döner; arayüze ait değilse false (çağıran normal
 * yönlendirmeye devam eder).
 */
export function serveWebUi(req, res, root = WEB_DIST) {
  const pathname = new URL(req.url, 'http://x').pathname;
  if (!isUiPath(pathname)) return false;
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    send(res, 405, { 'content-type': 'application/json', allow: 'GET, HEAD' },
      JSON.stringify({ error: 'method not allowed' }));
    return true;
  }
  // `/ui` → `/ui/`: göreli varlık adreslerinin doğru çözülmesi için.
  if (pathname === UI_PREFIX) {
    const search = req.url.slice(pathname.length);
    send(res, 302, { location: UI_PREFIX + '/' + search }, '');
    return true;
  }
  if (!fs.existsSync(root)) {
    send(res, 503, { 'content-type': 'text/plain; charset=utf-8' },
      'Arayüz derlenmemiş. `cd web && npm install && npm run build` çalıştırın.');
    return true;
  }

  let file = resolveAsset(pathname, root);
  if (file === null) {
    send(res, 400, { 'content-type': 'application/json' }, JSON.stringify({ error: 'bad path' }));
    return true;
  }
  // SPA geri düşüşü: bilinmeyen yol index.html'e gider. AMA /assets/ altındaki
  // kayıp dosya 404 olmalı — index.html'i JS diye servis etmek tarayıcıda
  // teşhisi zor "Unexpected token '<'" hatası üretir.
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    if (/[\\/]assets[\\/]/.test(file)) {
      send(res, 404, { 'content-type': 'application/json' }, JSON.stringify({ error: 'not found' }));
      return true;
    }
    file = path.join(root, 'index.html');
    if (!fs.existsSync(file)) {
      send(res, 503, { 'content-type': 'text/plain; charset=utf-8' },
        'Arayüz derlenmemiş. `cd web && npm run build` çalıştırın.');
      return true;
    }
  }

  const body = fs.readFileSync(file);
  const headers = {
    'content-type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
    'content-length': body.length,
    'cache-control': cacheControl(file),
  };
  if (req.method === 'HEAD') { res.writeHead(200, headers); res.end(); return true; }
  send(res, 200, headers, body);
  return true;
}
