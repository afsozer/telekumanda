import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { getPosition, putPosition, positionKey, MAX_ENTRIES, _internals } from '../pdf-positions.mjs';

let dir; let file;
before(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pdfpos-'));
  file = path.join(dir, 'pdf-positions.json');
});
after(() => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch {} });

describe('positionKey', () => {
  it('ters bolu ve buyuk harf farkini siler', () => {
    assert.equal(positionKey('C:\\Kitap\\A.pdf'), 'c:/kitap/a.pdf');
    assert.equal(positionKey('c:/KITAP/a.PDF'), 'c:/kitap/a.pdf');
  });
  it('sondaki boluleri ve bosluklari atar', () => {
    assert.equal(positionKey('  c:/kitap/a.pdf//  '), 'c:/kitap/a.pdf');
  });
  it('bos girdi bos anahtar', () => {
    assert.equal(positionKey(''), '');
    assert.equal(positionKey(null), '');
  });
});

describe('putPosition / getPosition', () => {
  it('yazip geri okur', () => {
    const r = putPosition('C:\\Kitap\\A.pdf', { page: 12, ri: 40, ro: 7, mode: 'r', savedAt: 1000 }, { file });
    assert.equal(r.ok, true);
    assert.equal(r.stored, true);
    assert.deepEqual(getPosition('C:\\Kitap\\A.pdf', { file }), { page: 12, ri: 40, ro: 7, mode: 'r', savedAt: 1000 });
  });

  it('AYNI belgeye farkli yazimla ulasan cihaz ayni kaydi gorur', () => {
    // Junction/kisayol farkini yok eden asil davranis bu.
    assert.deepEqual(getPosition('c:/kitap/a.pdf', { file })?.page, 12);
  });

  it('bayat kayit dosyadakini EZMEZ, kazanani dondurur', () => {
    const r = putPosition('c:/kitap/a.pdf', { page: 3, mode: 'p', savedAt: 500 }, { file });
    assert.equal(r.ok, true);
    assert.equal(r.stored, false);
    assert.equal(r.position.page, 12);
    assert.equal(getPosition('c:/kitap/a.pdf', { file }).page, 12);
  });

  it('yeni kayit uzerine yazar', () => {
    const r = putPosition('c:/kitap/a.pdf', { page: 30, mode: 'p', savedAt: 2000 }, { file });
    assert.equal(r.stored, true);
    assert.equal(getPosition('c:/kitap/a.pdf', { file }).page, 30);
  });

  it('bilinmeyen belge null', () => {
    assert.equal(getPosition('c:/kitap/yok.pdf', { file }), null);
  });

  it('gecersiz sayfa reddedilir', () => {
    assert.equal(putPosition('c:/kitap/b.pdf', { page: 0 }, { file }).ok, false);
    assert.equal(putPosition('c:/kitap/b.pdf', { page: 'x' }, { file }).ok, false);
    assert.equal(putPosition('', { page: 5 }, { file }).ok, false);
  });

  it('bilinmeyen kip "p"ye duser, negatif offset sifirlanir', () => {
    putPosition('c:/kitap/c.pdf', { page: 5, ri: -3, ro: -9, mode: 'zzz', savedAt: 10 }, { file });
    assert.deepEqual(getPosition('c:/kitap/c.pdf', { file }), { page: 5, ri: 0, ro: 0, mode: 'p', savedAt: 10 });
  });

  it('bozuk dosya okumayi kilitlemez', () => {
    const broken = path.join(dir, 'bozuk.json');
    fs.writeFileSync(broken, '{ bu json degil');
    assert.equal(getPosition('c:/kitap/a.pdf', { file: broken }), null);
    assert.equal(putPosition('c:/kitap/a.pdf', { page: 1, savedAt: 1 }, { file: broken }).ok, true);
  });

  it('tavan asilinca en eski kayitlar dusuyor', () => {
    const capFile = path.join(dir, 'cap.json');
    const map = {};
    for (let i = 0; i < MAX_ENTRIES + 10; i += 1) map[`c:/k/${i}.pdf`] = { page: 1, savedAt: i };
    const out = _internals.writeAll(capFile, map);
    assert.equal(Object.keys(out).length, MAX_ENTRIES);
    assert.equal(out['c:/k/0.pdf'], undefined, 'en eski dusmeli');
    assert.ok(out[`c:/k/${MAX_ENTRIES + 9}.pdf`], 'en yeni kalmali');
  });
});
