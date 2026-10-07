import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import { createRouter } from '../router.mjs';
import { listDriveRoots, register } from '../routes/general.mjs';

// Gezgin C:\'de sikisiyordu: surucu kokunun ustu yok, dolayisiyla D:'ye
// gecmenin hicbir yolu kalmiyordu. Listeyi kopru veriyor cunku hangi disklerin
// takili oldugunu yalniz o bilebilir.
describe('surucu kokleri', () => {
  it('yalniz var olan harfleri, kok yoluyla birlikte dondurur', function (t) {
    if (process.platform !== 'win32') return t.skip('Windows disi');
    const seen = new Set(['C:\\', 'D:\\', 'Z:\\']);
    const roots = listDriveRoots(p => seen.has(p));
    assert.deepEqual(roots.map(r => r.name), ['C:', 'D:', 'Z:']);
    assert.deepEqual(roots.map(r => r.path), ['C:\\', 'D:\\', 'Z:\\']);
    // Gezgin bunlari klasor satiri gibi cizecek.
    assert.ok(roots.every(r => r.type === 'dir'));
  });

  it('hic surucu yoksa bos dizi doner, patlamaz', function (t) {
    if (process.platform !== 'win32') return t.skip('Windows disi');
    assert.deepEqual(listDriveRoots(() => false), []);
  });

  it('gercek makinede en az bir surucu bulur', function (t) {
    if (process.platform !== 'win32') return t.skip('Windows disi');
    const roots = listDriveRoots();
    assert.ok(roots.length >= 1, 'en az sistem diski gorunmeli');
    assert.ok(roots.every(r => /^[A-Z]:$/.test(r.name)));
  });
});

// Yol testi: /dirs/roots gercekten kayitli mi ve cowork kapsaminda susuyor mu?
function startServer() {
  const router = createRouter();
  register(router, {
    readWorkspaceFile: () => ({ ok: false }),
    resolveWorkspacePath: () => ({ ok: false }),
    getActiveState: () => ({ running: false }),
    SLASH: {},
  });
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const route = router.match(req.method, url.pathname);
    if (route) return await route.handler(req, res);
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'not found' }));
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

function get(port, urlPath) {
  return new Promise((resolve, reject) => {
    http.get({ hostname: '127.0.0.1', port, path: urlPath }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }));
    }).on('error', reject);
  });
}

describe('GET /dirs/roots', () => {
  let server; let port;
  before(async () => { server = await startServer(); port = server.address().port; });
  after(() => server.close());

  it('surucu listesini dondurur', async () => {
    const r = await get(port, '/dirs/roots');
    assert.equal(r.status, 200);
    const data = JSON.parse(r.body);
    assert.equal(data.ok, true);
    assert.ok(Array.isArray(data.roots));
    if (process.platform === 'win32') assert.ok(data.roots.length >= 1);
  });

  it('cowork kapsaminda bos doner - kok kilidinin etrafindan dolasilmasin', async () => {
    const r = await get(port, '/dirs/roots?scope=cowork');
    assert.equal(r.status, 200);
    assert.deepEqual(JSON.parse(r.body).roots, []);
  });
});
