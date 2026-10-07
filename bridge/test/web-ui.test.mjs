import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { cacheControl, isUiPath, resolveAsset } from '../web-ui.mjs';

const ROOT = path.resolve('/tmp/web-dist');

test('isUiPath yalnız /ui ve altını sahiplenir', () => {
  assert.equal(isUiPath('/ui'), true);
  assert.equal(isUiPath('/ui/'), true);
  assert.equal(isUiPath('/ui/assets/index-abc.js'), true);
  // Benzer başlayan API yolları arayüze kapılmamalı.
  assert.equal(isUiPath('/uiXYZ'), false);
  assert.equal(isUiPath('/usage'), false);
  assert.equal(isUiPath('/claude-app/stream'), false);
  assert.equal(isUiPath('/'), false);
});

test('kök ve boş yol index.html verir', () => {
  assert.equal(resolveAsset('/ui/', ROOT), path.join(ROOT, 'index.html'));
  assert.equal(resolveAsset('/ui', ROOT), path.join(ROOT, 'index.html'));
});

test('normal varlık yolu dist içinde çözülür', () => {
  assert.equal(
    resolveAsset('/ui/assets/index-Bkm36FbW.js', ROOT),
    path.join(ROOT, 'assets', 'index-Bkm36FbW.js'),
  );
});

test('dist dışına çıkma girişimleri reddedilir', () => {
  // Köprünün config.json'u tam iki seviye yukarıda duruyor — token orada.
  assert.equal(resolveAsset('/ui/../../bridge/config.json', ROOT), null);
  assert.equal(resolveAsset('/ui/../config.json', ROOT), null);
  // Yüzde kodlanmış hâli de: decode SONRASI kontrol edildiği için yakalanır.
  assert.equal(resolveAsset('/ui/%2e%2e/%2e%2e/config.json', ROOT), null);
  // Windows ayracı: `..\..` normalize edilmezse path.resolve kaçışa izin verirdi.
  assert.equal(resolveAsset('/ui/..\\..\\config.json', ROOT), null);
});

test('bozuk yüzde kodlaması reddedilir', () => {
  assert.equal(resolveAsset('/ui/%E0%A4%A', ROOT), null);
});

test('arayüze ait olmayan yol null döner', () => {
  assert.equal(resolveAsset('/usage', ROOT), null);
});

test('içerik özetli varlıklar kalıcı, index.html asla önbelleklenmez', () => {
  assert.match(cacheControl(path.join(ROOT, 'assets', 'index-abc.js')), /immutable/);
  assert.equal(cacheControl(path.join(ROOT, 'index.html')), 'no-store');
});
