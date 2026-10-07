import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { createRouter } from '../router.mjs';
import { register } from '../routes/update.mjs';

test('Lite manifest and APK stay separate from the standard update channel', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-update-test-'));
  const lite = path.join(dir, 'lite');
  fs.mkdirSync(lite);
  fs.writeFileSync(path.join(dir, 'latest.json'), JSON.stringify({ versionCode: 900, apkPath: '/update/app-latest.apk' }));
  fs.writeFileSync(path.join(dir, 'app-latest.apk'), 'standard apk');
  const router = createRouter();
  register(router, { updateDir: dir });
  const server = http.createServer((req, res) => {
    const route = router.match(req.method, new URL(req.url, 'http://localhost').pathname);
    if (route) route.handler(req, res);
    else { res.writeHead(404); res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(`${base}/update/lite/latest.json`)).status, 404);
  assert.equal((await fetch(`${base}/update/lite/app-latest.apk`)).status, 404);
  const manifest = { applicationId: 'com.agent.bridge.lite', versionCode: 438,
    versionName: '1.0-lite', apkPath: '/update/lite/app-latest.apk' };
  fs.writeFileSync(path.join(lite, 'latest.json'), JSON.stringify(manifest));
  fs.writeFileSync(path.join(lite, 'app-latest.apk'), 'lite apk');
  assert.deepEqual(await (await fetch(`${base}/update/lite/latest.json`)).json(), manifest);
  const liteApk = await fetch(`${base}/update/lite/app-latest.apk`);
  assert.equal(liteApk.headers.get('content-type'), 'application/vnd.android.package-archive');
  assert.equal(liteApk.headers.get('content-length'), '8');
  assert.equal(await liteApk.text(), 'lite apk');
  assert.equal((await (await fetch(`${base}/update/latest.json`)).json()).versionCode, 900);
  assert.equal(await (await fetch(`${base}/update/app-latest.apk`)).text(), 'standard apk');
});
