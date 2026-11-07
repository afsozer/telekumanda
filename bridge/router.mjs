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
    res.writeHead(code, { 'content-type': 'application/json', 'content-encoding': 'gzip', 'vary': 'Accept-Encoding' });
    res.end(gzipSync(metin));
    return;
  }
  res.writeHead(code, { 'content-type': 'application/json' }); res.end(metin);
}
export async function body(req) { let d=''; for await (const c of req) d+=c; return d?JSON.parse(d):{}; }
export async function rawBody(req) { const chunks=[]; for await (const c of req) chunks.push(c); return Buffer.concat(chunks); }
