// Rota testi: junction'dan listelenen DOSYA gercek yoluyla donuyor mu ve
// /pdf-position ucu yaziyi/okumayi dogru yapiyor mu.
//
// Cift kopya hatasinin kokeni buydu: ayni kitap junction uzerinden acilinca
// istemcide baska bir yol dizesi oluyor, indirme onbellegi ve metin cikarimi
// ikiye bolunuyordu.
import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'dirs-junction-'));
process.env.AGENTBRIDGE_PDF_POSITIONS = path.join(tmpRoot, 'positions.json');

const { createRouter } = await import('../router.mjs');
const { register } = await import('../routes/general.mjs');

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

function request(port, method, urlPath, body) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: urlPath, method }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, json: JSON.parse(Buffer.concat(chunks).toString()) }));
    });
    req.on('error', reject);
    if (body !== undefined) req.write(JSON.stringify(body));
    req.end();
  });
}

let server; let port; let realDir; let linkDir;
before(async () => {
  realDir = path.join(tmpRoot, 'gercek');
  linkDir = path.join(tmpRoot, 'kisayol');
  fs.mkdirSync(path.join(realDir, 'alt'), { recursive: true });
  fs.writeFileSync(path.join(realDir, 'kitap.pdf'), 'x'.repeat(11));
  // junction: Windows'ta yonetici gerektirmez.
  fs.symlinkSync(realDir, linkDir, process.platform === 'win32' ? 'junction' : 'dir');
  server = await startServer();
  port = server.address().port;
});
after(() => {
  server?.close();
  try { fs.rmSync(tmpRoot, { recursive: true, force: true }); } catch {}
});

describe('GET /dirs junction cozumu', () => {
  it('DOSYA yolu gercek hedefe cozulur', async () => {
    const r = await request(port, 'GET', `/dirs?files=true&root=${encodeURIComponent(linkDir)}`);
    assert.equal(r.status, 200);
    const file = r.json.dirs.find(d => d.type === 'file');
    assert.ok(file, 'dosya listelenmeli');
    assert.equal(file.path, fs.realpathSync(path.join(realDir, 'kitap.pdf')));
    assert.equal(file.size, 11, 'stat gercek dosyadan okunmali');
  });

  it('KLASOR yolu cozulmez - gezginde kisayolun icinde kalinir', async () => {
    const r = await request(port, 'GET', `/dirs?files=true&root=${encodeURIComponent(linkDir)}`);
    const dir = r.json.dirs.find(d => d.type === 'dir');
    assert.equal(dir.path, path.join(linkDir, 'alt'));
  });

  it('iki yoldan listelenen ayni dosya AYNI yolu verir', async () => {
    const viaLink = await request(port, 'GET', `/dirs?files=true&root=${encodeURIComponent(linkDir)}`);
    const viaReal = await request(port, 'GET', `/dirs?files=true&root=${encodeURIComponent(realDir)}`);
    assert.equal(
      viaLink.json.dirs.find(d => d.type === 'file').path,
      viaReal.json.dirs.find(d => d.type === 'file').path,
    );
  });
});

describe('/pdf-position uclari', () => {
  it('kayit yokken null doner', async () => {
    const r = await request(port, 'GET', '/pdf-position?path=c:%5Cyok.pdf');
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
    assert.equal(r.json.position, null);
  });

  it('yazar ve geri okur', async () => {
    const w = await request(port, 'POST', '/pdf-position', {
      path: 'C:\\Kitap\\A.pdf', page: 42, ri: 7, ro: 3, mode: 'r', savedAt: 1234,
    });
    assert.equal(w.json.ok, true);
    assert.equal(w.json.stored, true);
    const r = await request(port, 'GET', '/pdf-position?path=c:/kitap/a.pdf');
    assert.deepEqual(r.json.position, { page: 42, ri: 7, ro: 3, mode: 'r', savedAt: 1234 });
  });

  it('bayat yazi reddedilir, kazanan doner', async () => {
    const w = await request(port, 'POST', '/pdf-position', {
      path: 'c:/kitap/a.pdf', page: 1, mode: 'p', savedAt: 5,
    });
    assert.equal(w.json.stored, false);
    assert.equal(w.json.position.page, 42);
  });

  it('bos yol 400', async () => {
    assert.equal((await request(port, 'GET', '/pdf-position?path=')).status, 400);
    assert.equal((await request(port, 'POST', '/pdf-position', { page: 1 })).status, 400);
  });
});
