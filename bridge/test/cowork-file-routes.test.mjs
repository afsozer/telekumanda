import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRouter } from '../router.mjs';
import { register } from '../routes/general.mjs';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-routes-root-'));
const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-routes-out-'));
const source = path.join(root, 'alan', 'not.txt');

before(() => {
  fs.mkdirSync(path.dirname(source), { recursive: true });
  fs.writeFileSync(source, 'korunacak');
});

after(() => {
  try { fs.rmSync(root, { recursive: true, force: true }); } catch {}
  try { fs.rmSync(outside, { recursive: true, force: true }); } catch {}
});

function routerWithCoworkRoot() {
  const router = createRouter();
  register(router, {
    readWorkspaceFile: () => ({}),
    resolveWorkspacePath: () => ({ ok: false }),
    getActiveState: () => ({}),
    SLASH: {},
    getCoworkRoot: () => root,
    scheduleRestart: () => {},
  });
  return router;
}

function jsonReq(url, payload) {
  return {
    url,
    headers: {},
    async *[Symbol.asyncIterator]() { yield Buffer.from(JSON.stringify(payload)); },
  };
}

function fakeRes() {
  return {
    statusCode: 0,
    data: '',
    writeHead(code) { this.statusCode = code; },
    end(data) { this.data = data; },
  };
}

describe('Cowork-scoped general file routes', () => {
  it('rejects moving a Cowork file outside the configured root', async () => {
    const route = routerWithCoworkRoot().match('POST', '/move');
    const res = fakeRes();
    await route.handler(jsonReq('/move', {
      path: source,
      destDir: outside,
      scope: 'cowork',
    }), res);

    assert.equal(res.statusCode, 400);
    assert.match(JSON.parse(res.data).error, /Cowork root/);
    assert.equal(fs.readFileSync(source, 'utf8'), 'korunacak');
    assert.equal(fs.existsSync(path.join(outside, 'not.txt')), false);
  });

  it('rejects deleting an outside file through Cowork scope', async () => {
    const outsideFile = path.join(outside, 'silinmesin.txt');
    fs.writeFileSync(outsideFile, 'x');
    const route = routerWithCoworkRoot().match('POST', '/delete');
    const res = fakeRes();
    await route.handler(jsonReq('/delete', { path: outsideFile, scope: 'cowork' }), res);

    assert.equal(res.statusCode, 400);
    assert.equal(fs.existsSync(outsideFile), true);
  });
});
