// Yas'a gore budama. Kritik nokta: budama sessizce FAZLA silmemeli — bu yol
// kullanicinin bakmadigi klasorlerde calisiyor, hatasi da gorunmez olurdu.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pruneOlderThan, pruneAll } from '../disk-prune.mjs';

const GUN = 24 * 60 * 60 * 1000;
const SIMDI = Date.parse('2026-08-19T23:00:00+03:00');
const now = () => SIMDI;

function tmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'prune-'));
}

// Yasi mtime belirler; dosya adi degil.
function dosya(dir, ad, gunOnce, icerik = 'x') {
  const p = path.join(dir, ad);
  fs.writeFileSync(p, icerik);
  const t = new Date(SIMDI - gunOnce * GUN);
  fs.utimesSync(p, t, t);
  return p;
}

function klasor(dir, ad, gunOnce, dosyalar = { 'a.txt': 'icerik' }) {
  const p = path.join(dir, ad);
  fs.mkdirSync(p, { recursive: true });
  for (const [k, v] of Object.entries(dosyalar)) fs.writeFileSync(path.join(p, k), v);
  const t = new Date(SIMDI - gunOnce * GUN);
  fs.utimesSync(p, t, t);
  return p;
}

describe('disk budama', () => {
  it('3 gunden eskiyi siler, yenisine dokunmaz', () => {
    const dir = tmp();
    dosya(dir, 'eski.jpg', 5);
    dosya(dir, 'yeni.jpg', 1);
    const r = pruneOlderThan({ dir, maxAgeMs: 3 * GUN, now });
    assert.equal(r.removed, 1);
    assert.equal(r.kept, 1);
    assert.deepEqual(fs.readdirSync(dir), ['yeni.jpg']);
  });

  it('tam sinirdaki girdi KALIR', () => {
    // "3 gunden eski" demek; tam 3 gun eski degil. Sinir hatasi burada
    // gunde bir dosyayi erken siler ve fark edilmez.
    const dir = tmp();
    dosya(dir, 'tam.jpg', 3);
    const r = pruneOlderThan({ dir, maxAgeMs: 3 * GUN, now });
    assert.equal(r.removed, 0);
    assert.equal(r.kept, 1);
  });

  it('klasorleri de siler ve icerigiyle birlikte gider', () => {
    const dir = tmp();
    const eski = klasor(dir, 'konusma-eski', 10, { 'transcript.jsonl': 'satir', 'log.txt': 'y' });
    klasor(dir, 'konusma-yeni', 1);
    const r = pruneOlderThan({ dir, maxAgeMs: 3 * GUN, now });
    assert.equal(r.removed, 1);
    assert.ok(!fs.existsSync(eski));
    assert.deepEqual(fs.readdirSync(dir), ['konusma-yeni']);
  });

  it('kazanilan boyut silinen icerigin gercek toplami', () => {
    const dir = tmp();
    klasor(dir, 'k', 10, { 'a.bin': 'x'.repeat(1000), 'b.bin': 'y'.repeat(500) });
    const r = pruneOlderThan({ dir, maxAgeMs: 3 * GUN, now });
    assert.equal(r.freedBytes, 1500);
  });

  it('olmayan klasor hata degil', () => {
    const r = pruneOlderThan({ dir: path.join(tmp(), 'yok'), maxAgeMs: 3 * GUN, now });
    assert.deepEqual(r, { removed: 0, kept: 0, freedBytes: 0, failed: 0 });
  });

  it('maxAgeMs gecersizse HICBIR SEY silinmez', () => {
    // Config'ten gelen bozuk bir deger butun klasoru supurmemeli.
    const dir = tmp();
    dosya(dir, 'eski.jpg', 90);
    for (const kotu of [0, -1, NaN, undefined]) {
      assert.equal(pruneOlderThan({ dir, maxAgeMs: kotu, now }).removed, 0, `maxAgeMs=${kotu}`);
    }
    assert.equal(fs.readdirSync(dir).length, 1);
  });

  it('dir bos ise dokunmaz', () => {
    assert.equal(pruneOlderThan({ dir: '', maxAgeMs: 3 * GUN, now }).removed, 0);
  });

  it('pruneAll birden fazla klasoru toplar', () => {
    const a = tmp();
    const b = tmp();
    dosya(a, 'eski1.jpg', 5, 'aaa');
    dosya(b, 'eski2.jpg', 5, 'bb');
    dosya(b, 'yeni.jpg', 1);
    const r = pruneAll([a, b, path.join(a, 'yok')], { maxAgeMs: 3 * GUN, now });
    assert.equal(r.removed, 2);
    assert.equal(r.kept, 1);
    assert.equal(r.freedBytes, 5);
  });
});
