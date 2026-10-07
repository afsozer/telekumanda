import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRouter } from '../router.mjs';
import { register } from '../routes/general.mjs';

const writtenFiles = [];

after(() => {
  for (const file of writtenFiles) {
    try { fs.unlinkSync(file); } catch {}
  }
});

function fakeRawReq(bytes, filename = 'hello.txt') {
  return {
    url: '/context/file',
    headers: { 'x-filename': filename },
    [Symbol.asyncIterator]() {
      let done = false;
      return {
        next() {
          if (done) return Promise.resolve({ done: true });
          done = true;
          return Promise.resolve({ value: Buffer.from(bytes), done: false });
        },
      };
    },
  };
}

function fakeRes() {
  return {
    statusCode: 0,
    headers: null,
    data: '',
    writeHead(code, headers) { this.statusCode = code; this.headers = headers; },
    end(data) { this.data = data; },
  };
}

describe('/context/file', () => {
  it('writes uploaded bytes under bridge/tmp', async () => {
    const router = createRouter();
    register(router, {
      readWorkspaceFile: () => ({}),
      getActiveState: () => ({}),
      SLASH: {},
    });

    const route = router.match('POST', '/context/file');
    const res = fakeRes();
    await route.handler(fakeRawReq('hello bridge'), res);

    assert.equal(res.statusCode, 200);
    const json = JSON.parse(res.data);
    writtenFiles.push(json.path);
    assert.equal(json.ok, true);
    assert.equal(json.name, 'hello.txt');
    assert.equal(path.basename(path.dirname(json.path)), 'tmp');
    assert.equal(fs.readFileSync(json.path, 'utf-8'), 'hello bridge');
  });

  it('query ile gelen Türkçe dosya adını korur', async () => {
    const router = createRouter();
    register(router, {
      readWorkspaceFile: () => ({}),
      getActiveState: () => ({}),
      SLASH: {},
    });

    const route = router.match('POST', '/context/file');
    const res = fakeRes();
    const req = fakeRawReq('içerik');
    delete req.headers['x-filename'];
    req.url = '/context/file?filename=' + encodeURIComponent('Sipariş Sözleşmesi Onayı - Örnek.mht');
    await route.handler(req, res);

    assert.equal(res.statusCode, 200);
    const json = JSON.parse(res.data);
    writtenFiles.push(json.path);
    assert.equal(json.name, 'Sipariş Sözleşmesi Onayı - Örnek.mht');
    assert.equal(fs.existsSync(json.path), true);
  });

  it('24 saatten eski tmp eklerini siler', async () => {
    const router = createRouter();
    register(router, {
      readWorkspaceFile: () => ({}),
      getActiveState: () => ({}),
      SLASH: {},
    });
    const route = router.match('POST', '/context/file');

    const res1 = fakeRes();
    await route.handler(fakeRawReq('first'), res1);
    const first = JSON.parse(res1.data);
    writtenFiles.push(first.path);
    const tmpDir = path.dirname(first.path);

    const oldFile = path.join(tmpDir, 'eski_ek_ttl_test.txt');
    fs.writeFileSync(oldFile, 'x');
    const old = new Date(Date.now() - 25 * 60 * 60 * 1000);
    fs.utimesSync(oldFile, old, old);

    const res2 = fakeRes();
    await route.handler(fakeRawReq('second', 'ikinci.txt'), res2);
    const second = JSON.parse(res2.data);
    writtenFiles.push(second.path);

    assert.equal(fs.existsSync(oldFile), false);
    assert.equal(fs.existsSync(second.path), true);
  });
});
