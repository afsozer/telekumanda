import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { createRouter } from '../router.mjs';
import { register } from '../routes/general.mjs';
import { resolveWorkspacePath } from '../server.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dl-'));
const txt = path.join(tmp, 'note.txt');
const bin = path.join(tmp, 'data.bin');
const png = path.join(tmp, 'img.png');
before(() => {
  fs.writeFileSync(txt, 'hello download');
  fs.writeFileSync(bin, Buffer.from([0, 1, 2, 3, 255]));
  fs.writeFileSync(png, 'not really png');
});
after(() => {
  for (const f of [txt, bin, png]) try { fs.unlinkSync(f); } catch {}
  try { fs.rmdirSync(tmp); } catch {}
});

// Build a router + http server that mirrors server.mjs dispatch (match + handler), but
// without the auth gate so tests can call /download directly.
function startServer() {
  const router = createRouter();
  register(router, {
    readWorkspaceFile: () => ({ ok: false }),
    resolveWorkspacePath,
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
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

function get(port, urlPath) {
  return new Promise((resolve, reject) => {
    http.get({ hostname: '127.0.0.1', port, path: urlPath }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    }).on('error', reject);
  });
}

function post(port, urlPath, bytes) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: urlPath, method: 'POST' }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.end(bytes);
  });
}

describe('GET /download', () => {
  let server, port;
  before(async () => { server = await startServer(); port = server.address().port; });
  after(() => server.close());

  it('streams file bytes with correct MIME and headers', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent(txt));
    assert.equal(r.status, 200);
    assert.equal(r.headers['content-type'], 'text/plain');
    assert.equal(r.headers['content-length'], String(Buffer.byteLength('hello download')));
    assert.equal(r.body.toString('utf-8'), 'hello download');
    assert.ok(r.headers['content-disposition'].includes('note.txt'));
  });

  it('returns application/octet-stream for unknown ext and exact bytes', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent(bin));
    assert.equal(r.status, 200);
    assert.equal(r.headers['content-type'], 'application/octet-stream');
    assert.deepEqual(Array.from(r.body), [0, 1, 2, 3, 255]);
  });

  it('returns image/png for .png', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent(png));
    assert.equal(r.headers['content-type'], 'image/png');
  });

  it('returns 404 JSON for missing path', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent('C:\\nope\\missing.txt'));
    assert.equal(r.status, 404);
    const parsed = JSON.parse(r.body.toString('utf-8'));
    assert.equal(parsed.error, 'not found');
  });
});

describe('POST /savefile', () => {
  let server, port;
  before(async () => { server = await startServer(); port = server.address().port; });
  after(() => server.close());

  it('atomically overwrites an existing file, including an empty body', async () => {
    const target = path.join(tmp, 'editable.md');
    fs.writeFileSync(target, 'before');
    const first = await post(port, '/savefile?path=' + encodeURIComponent(target), Buffer.from('# after'));
    assert.equal(first.status, 200);
    assert.match(JSON.parse(first.body.toString('utf8')).hash, /^[a-f0-9]{64}$/);
    assert.equal(fs.readFileSync(target, 'utf8'), '# after');
    const second = await post(port, '/savefile?path=' + encodeURIComponent(target), Buffer.alloc(0));
    assert.equal(second.status, 200);
    assert.equal(fs.readFileSync(target).length, 0);
    fs.unlinkSync(target);
  });

  it('does not create a missing file', async () => {
    const target = path.join(tmp, 'missing.docx');
    const r = await post(port, '/savefile?path=' + encodeURIComponent(target), Buffer.from('x'));
    assert.equal(r.status, 404);
    assert.equal(fs.existsSync(target), false);
  });

  it('rejects a stale native-editor save without overwriting the PC copy', async () => {
    const target = path.join(tmp, 'conflict.md');
    fs.writeFileSync(target, 'phone opened this');
    const openedHash = crypto.createHash('sha256').update('phone opened this').digest('hex');
    fs.writeFileSync(target, 'PC changed this');

    const r = await post(
      port,
      '/savefile?path=' + encodeURIComponent(target) + '&expectedHash=' + openedHash,
      Buffer.from('phone edit'),
    );

    assert.equal(r.status, 409);
    assert.equal(JSON.parse(r.body.toString('utf8')).conflict, true);
    assert.equal(fs.readFileSync(target, 'utf8'), 'PC changed this');
    fs.unlinkSync(target);
  });
});
