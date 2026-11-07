import { gzipSync } from 'node:zlib';

// Minimal route registry — Express-free. Preserves the existing http.createServer approach.
// Matches exact method + pathname. Parametric routes are not needed since all bridge
// endpoints use query params or POST body.
export function createRouter() {
  const routes = [];
  return {
    get(pattern, handler) { routes.push({ method: 'GET', pattern, handler }); },
    post(pattern, handler) { routes.push({ method: 'POST', pattern, handler }); },
    // 26.09.2026: DELETE gövdeli ya da query'li silme uçları için. match()
    // zaten method eşleştiriyor; olmayan metot registerBackend'teki
    // router[method]?.() ile SESSİZCE yutuluyordu — rota kayıtsız kalıp 404
    // "not found" dönüyordu.
    delete(pattern, handler) { routes.push({ method: 'DELETE', pattern, handler }); },
    match(method, pathname) { return routes.find(r => r.method === method && r.pattern === pathname) || null; },
    get routes() { return routes; },
  };
}

// gzip (21.10.2026): yanıtlar hiç sıkıştırılmıyordu; telefon mobil veride
// konuşma/operasyon/proje listelerini düz JSON olarak çekiyordu. Ölçülen
// oranlar: /operations 55 KB → 4,3 KB, konuşma 25 KB → 6,5 KB, /projects
// 323 KB → 57 KB. OkHttp ve tarayıcı Accept-Encoding: gzip yollayıp kendisi
// açıyor; başlığı yollamayan istemci (curl, testler) düz metin almaya devam
// eder. 1 KB altı sıkıştırmaya değmez. `res.req` Node'un ServerResponse'unda
// var; sahte res'te yoksa düz yola düşülür.
const GZIP_ESIGI = 1024;
export function json(res, code, obj) {
  const metin = JSON.stringify(obj);
  const kabul = String(res.req?.headers?.['accept-encoding'] || '');
  if (metin && metin.length >= GZIP_ESIGI && /\bgzip\b/i.test(kabul)) {
    if (!res.headersSent) res.writeHead(code, { 'content-type': 'application/json', 'content-encoding': 'gzip', 'vary': 'Accept-Encoding', 'x-content-type-options': 'nosniff' });
    res.end(gzipSync(metin));
    return;
  }
  if (!res.headersSent) res.writeHead(code, { 'content-type': 'application/json', 'x-content-type-options': 'nosniff' });
  res.end(metin);
}

// Gövde sınırı. Sınırsız okuma, kimlik kapısından önce gövde okuyan uçta
// (/pairing/complete) kimliksiz birinin paralel büyük gövdelerle köprünün
// belleğini tüketmesine izin veriyordu. Aşılınca istek kesilir ve 413 döner.
export const JSON_BODY_MAX = 16 * 1024 * 1024;
export const RAW_BODY_MAX = 1024 * 1024 * 1024;

export class BodyTooLargeError extends Error {
  constructor(limit) { super(`request body exceeds ${limit} bytes`); this.status = 413; }
}

async function readLimited(req, maxBytes) {
  const declared = Number(req.headers?.['content-length']);
  if (Number.isFinite(declared) && declared > maxBytes) throw new BodyTooLargeError(maxBytes);
  const chunks = []; let size = 0;
  for await (const c of req) {
    const chunk = typeof c === 'string' ? Buffer.from(c) : c;
    size += chunk.length;
    if (size > maxBytes) throw new BodyTooLargeError(maxBytes);
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

// İsteğe bağlı gövde denetleyicisi (server.mjs yol alanlarını denetlemek için kurar).
let bodyValidator = null;
export function setBodyValidator(fn) { bodyValidator = fn; }

/** 413 yanıtını yazar, ardından bağlantıyı keser (istemci gövdeyi göndermeye devam etmesin). */
export function rejectTooLarge(req, res) {
  if (!res.headersSent) res.writeHead(413, { 'content-type': 'application/json', connection: 'close' });
  res.on('finish', () => req.destroy?.());
  res.end(JSON.stringify({ ok: false, error: 'request body too large' }));
}

export async function body(req, { maxBytes = JSON_BODY_MAX } = {}) {
  const d = (await readLimited(req, maxBytes)).toString('utf8');
  const parsed = d ? JSON.parse(d) : {};
  if (bodyValidator) bodyValidator(parsed);
  return parsed;
}
export async function rawBody(req, { maxBytes = RAW_BODY_MAX } = {}) { return readLimited(req, maxBytes); }
