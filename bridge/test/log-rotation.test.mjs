import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { rotateLogIfNeeded, ROTATION_DEFAULTS } from '../log-rotation.mjs';

// Geçici çalışma dizini oluşturan yardımcı. Her test kendi dizinini alır.
function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-logrot-'));
}

// Belirtilen dosyaya `bytes` bayt yazıldıktan sonra gerçek dosya boyutunu döndürür.
function fillFile(filePath, bytes) {
  const chunk = 'x'.repeat(8192);
  const buf = Buffer.alloc(bytes, 'x');
  fs.writeFileSync(filePath, buf);
  return fs.statSync(filePath).size;
}

describe('rotateLogIfNeeded() — küçük dosya', () => {
  let dir, logPath;
  beforeEach(() => {
    dir = makeTempDir();
    logPath = path.join(dir, 'bridge.log');
  });
  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('alt eşiğin altındaysa dokunmaz', () => {
    fillFile(logPath, 1024); // 1 KB — ROTATION_DEFAULTS.maxBytes altında
    const result = rotateLogIfNeeded(logPath, { maxBytes: 5 * 1024 * 1024 });
    assert.equal(result.rotated, false);
    assert.ok(fs.existsSync(logPath));
    assert.equal(fs.statSync(logPath).size, 1024);
    assert.equal(fs.existsSync(logPath + '.1'), false);
  });
});

describe('rotateLogIfNeeded() — eşik aşımı', () => {
  let dir, logPath;
  beforeEach(() => {
    dir = makeTempDir();
    logPath = path.join(dir, 'bridge.log');
  });
  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('5 MB üzeri dosyayı bridge.log.1’e taşır ve yeni bridge.log boş olur', () => {
    fillFile(logPath, 6 * 1024 * 1024); // 6 MB
    const result = rotateLogIfNeeded(logPath, { maxBytes: 5 * 1024 * 1024 });
    assert.equal(result.rotated, true);
    assert.ok(fs.existsSync(logPath + '.1'), '.1 oluşmalı');
    assert.ok(!fs.existsSync(logPath + '.2'), '.2 henüz olmamalı');
    assert.equal(fs.statSync(logPath).size, 0, 'yeni bridge.log boş olmalı');
    assert.equal(fs.statSync(logPath + '.1').size, 6 * 1024 * 1024);
  });

  it('mevcut bridge.log.1 varsa .2’ye kaydırır (zincir)', () => {
    fillFile(logPath, 6 * 1024 * 1024);
    fs.writeFileSync(logPath + '.1', 'once-newest');
    const result = rotateLogIfNeeded(logPath, { maxBytes: 5 * 1024 * 1024 });
    assert.equal(result.rotated, true);
    assert.equal(fs.readFileSync(logPath + '.2', 'utf8'), 'once-newest');
    assert.equal(fs.statSync(logPath + '.1').size, 6 * 1024 * 1024);
    assert.equal(fs.statSync(logPath).size, 0);
  });
});

describe('rotateLogIfNeeded() — nesil tavanı (maxGenerations=3)', () => {
  let dir, logPath;
  const MAX_BYTES = 5 * 1024 * 1024;

  beforeEach(() => {
    dir = makeTempDir();
    logPath = path.join(dir, 'bridge.log');
  });
  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('tavan 3 nesildir: aktif + .1 + .2; 4. rotasyonda en eski (.2→.3) silinir', () => {
    // 3 nesil = aktif bridge.log + .1 + .2 (≈15 MB tavan). .3'e kadar gitmez.
    // Her rotasyon 6 MB yazıp tetikler; 4 rotasyon sonunda .2 en eski kalır,
    // .3 hiç oluşmaz (tavan).
    for (let i = 0; i < 4; i++) {
      fillFile(logPath, 6 * 1024 * 1024);
      rotateLogIfNeeded(logPath, { maxBytes: MAX_BYTES });
    }
    assert.ok(fs.existsSync(logPath), 'aktif log var');
    assert.ok(fs.existsSync(logPath + '.1'), '.1 var');
    assert.ok(fs.existsSync(logPath + '.2'), '.2 var (en eski nesil)');
    assert.ok(!fs.existsSync(logPath + '.3'), '.3 olmamalı (tavan = 3 nesil)');
  });

  it('mevcut .2 (en eski) varsa yeni rotasyon onu siler (üzerine kaymaz)', () => {
    fillFile(logPath, 6 * 1024 * 1024);
    fs.writeFileSync(logPath + '.1', 'g1');
    fs.writeFileSync(logPath + '.2', 'g2-oldest');
    rotateLogIfNeeded(logPath, { maxBytes: MAX_BYTES });
    // .2 silinip .1 kayar: yeni .2 eski .1 içeriğini taşır.
    assert.equal(fs.readFileSync(logPath + '.2', 'utf8'), 'g1', '.2 eski .1 içeriğini taşır');
    assert.equal(fs.statSync(logPath + '.1').size, 6 * 1024 * 1024);
  });

  it('tavan dışı kalıntı .3 dosyası yeni rotasyonda silinir (birikmez)', () => {
    // Geçmişte yüksek maxGenerations ile oluşturulmuş .3 kalıntısı olsa,
    // maxGenerations=3 rotasyonu onu temizler.
    fillFile(logPath, 6 * 1024 * 1024);
    fs.writeFileSync(logPath + '.1', 'g1');
    fs.writeFileSync(logPath + '.2', 'g2');
    fs.writeFileSync(logPath + '.3', 'stale-g3');
    rotateLogIfNeeded(logPath, { maxBytes: MAX_BYTES });
    assert.ok(!fs.existsSync(logPath + '.3'), 'kalıntı .3 silinmeli');
  });
});

describe('rotateLogIfNeeded() — kenar durumlar', () => {
  let dir, logPath;
  beforeEach(() => {
    dir = makeTempDir();
    logPath = path.join(dir, 'bridge.log');
  });
  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('dosya yoksa rotasyon yapmaz (hata fırlatmaz)', () => {
    const missing = path.join(dir, 'yok.log');
    const result = rotateLogIfNeeded(missing, { maxBytes: 100 });
    assert.equal(result.rotated, false);
    assert.ok(!fs.existsSync(missing));
    assert.ok(!fs.existsSync(missing + '.1'));
  });

  it('maxGenerations 1 ise hiç .N arşivi tutmaz (sadece bridge.log sıfırlanır)', () => {
    fillFile(logPath, 6 * 1024 * 1024);
    const result = rotateLogIfNeeded(logPath, { maxBytes: 5 * 1024 * 1024, maxGenerations: 1 });
    assert.equal(result.rotated, true);
    assert.equal(fs.statSync(logPath).size, 0);
    assert.ok(!fs.existsSync(logPath + '.1'), 'maxGenerations=1 → arşiv tutulmaz');
  });

  it('ROTATION_DEFAULTS güvenli varsayılanlar taşır', () => {
    assert.ok(ROTATION_DEFAULTS.maxBytes > 0);
    assert.ok(ROTATION_DEFAULTS.maxGenerations >= 1);
    assert.ok(typeof ROTATION_DEFAULTS.intervalMs === 'number' && ROTATION_DEFAULTS.intervalMs > 0);
  });
});
