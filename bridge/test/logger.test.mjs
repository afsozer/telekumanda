import { describe, it, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { initLogger, rotate, resetLoggerForTest } from '../logger.mjs';

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-logger-'));
}

describe('logger — init & console yakalama', () => {
  let dir, logPath;
  beforeEach(() => {
    resetLoggerForTest();
    dir = makeTempDir();
    logPath = path.join(dir, 'bridge.log');
  });
  afterEach(() => {
    resetLoggerForTest();
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('initLogger sonrası console.log bridge.log’a yazılır', () => {
    initLogger({ filePath: logPath });
    console.log('merhaba dünya');
    // appendFileSync sync → readFileSync anında okuyabilir.
    const content = fs.readFileSync(logPath, 'utf8');
    assert.match(content, /merhaba dünya/);
    // timestamp + level öneki eklenir.
    assert.match(content, /\[log\]/);
    assert.match(content, /\d{4}-\d{2}-\d{2}T/); // ISO timestamp
  });

  it('console.warn [warn] seviyesi ile yazar', () => {
    initLogger({ filePath: logPath });
    console.warn('tehlikeli', 'durum');
    const content = fs.readFileSync(logPath, 'utf8');
    assert.match(content, /\[warn\] tehlikeli durum/);
  });

  it('console.error [error] seviyesi ile yazar', () => {
    initLogger({ filePath: logPath });
    console.error('bozuldu', { code: 500 });
    const content = fs.readFileSync(logPath, 'utf8');
    assert.match(content, /\[error\] bozuldu/);
    assert.match(content, /500/);
  });

  it('logWarn ile uyumlu — [ctx] msg {json} tek satırda korunur', () => {
    initLogger({ filePath: logPath });
    // logWarn: console.warn(`[ctx] ${msg}`, meta ? JSON.stringify(meta) : '')
    console.warn('[backend] bir şey oldu', JSON.stringify({ a: 1 }));
    const content = fs.readFileSync(logPath, 'utf8');
    assert.match(content, /\[warn\] \[backend\] bir şey oldu \{"a":1\}/);
  });
});

describe('logger — rotate()', () => {
  let dir, logPath;
  beforeEach(() => {
    resetLoggerForTest();
    dir = makeTempDir();
    logPath = path.join(dir, 'bridge.log');
  });
  afterEach(() => {
    resetLoggerForTest();
    fs.rmSync(dir, { recursive: true, force: true });
  });

  it('rotate() dosyayı .1’e taşır; sonraki yazılar yeni boş dosyaya gider', () => {
    // maxBytes'i tek satırı geçecek kadar küçük tut (bir satır ~70 bayt).
    initLogger({ filePath: logPath, maxBytes: 50, maxGenerations: 3 });
    console.log('önceki içerik satırı');
    // dosya ~70 bayt > 50 eşik → rotate tetiklenebilir.
    const r = rotate();
    assert.equal(r.rotated, true);
    assert.ok(fs.existsSync(logPath + '.1'), '.1 oluşmalı');
    assert.match(fs.readFileSync(logPath + '.1', 'utf8'), /önceki içerik satırı/);
    assert.equal(fs.statSync(logPath).size, 0, 'yeni bridge.log boş');
    // yeni yazı yeni (boş) dosyaya gider.
    console.log('rotasyondan sonra yeni satır');
    const after = fs.readFileSync(logPath, 'utf8');
    assert.match(after, /rotasyondan sonra yeni satır/);
    assert.doesNotMatch(after, /önceki içerik satırı/);
  });

  it('rotate() logger init edilmeden çağrılırsa hata fırlatmaz', () => {
    assert.doesNotThrow(() => rotate());
  });
});
