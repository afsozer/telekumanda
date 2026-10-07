// Minimal route registry — Express-free. Preserves the existing http.createServer approach.
// Matches exact method + pathname. Parametric routes are not needed since all bridge
// endpoints use query params or POST body.
export function createRouter() {
  const routes = [];
  return {
    get(pattern, handler) { routes.push({ method: 'GET', pattern, handler }); },
    post(pattern, handler) { routes.push({ method: 'POST', pattern, handler }); },
    // 26.09.2026: DELETE gövdeli ya da query'li silme uçları için (ör.
    // /opencode2-app/instructions). match() zaten method eşleştiriyor; olmayan
    // metot registerBackend'teki router[method]?.() ile SESSİZCE yutuluyordu —
    // rota kayıtsız kalıp 404 "not found" dönüyordu.
    delete(pattern, handler) { routes.push({ method: 'DELETE', pattern, handler }); },
    match(method, pathname) { return routes.find(r => r.method === method && r.pattern === pathname) || null; },
    get routes() { return routes; },
  };
}

export function json(res, code, obj) { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); }
export async function body(req) { let d=''; for await (const c of req) d+=c; return d?JSON.parse(d):{}; }
export async function rawBody(req) { const chunks=[]; for await (const c of req) chunks.push(c); return Buffer.concat(chunks); }
