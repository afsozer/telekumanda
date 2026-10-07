// saveProjectFile birim testleri — telefonda düzenlenen dosyanın Cowork root
// içindeki mevcut dosyanın üzerine atomik yazımı (cowork.mjs).
import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// COWORK_ROOT modül yükleme anında env'den sabitlenir; claude-app.mjs (ve onu
// kullanan cowork.mjs) env set edildikten SONRA dinamik import edilir — aksi
// halde testler gerçek ~/CoworkSpaces'e yazar (claude-app-cowork.test.mjs notu).
const COWORK_TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-save-'));
process.env.AGENTBRIDGE_COWORK_ROOT = COWORK_TMP;
const cowork = await import('../cowork.mjs');

after(() => { try { fs.rmSync(COWORK_TMP, { recursive: true, force: true }); } catch {} });

function mkfile(rel, content = 'orijinal') {
  const full = path.join(COWORK_TMP, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content);
  return full;
}

describe('saveProjectFile: üzerine yazma', () => {
  it('mevcut dosyayı yeni içerikle değiştirir, size/mtime döner', () => {
    const f = mkfile('alan-a/not.txt', 'eski');
    const r = cowork.saveProjectFile({ filePath: f, buffer: Buffer.from('yeni içerik') });
    assert.equal(r.ok, true);
    assert.equal(r.path, f);
    assert.equal(r.name, 'not.txt');
    assert.equal(fs.readFileSync(f, 'utf8'), 'yeni içerik');
    assert.equal(r.size, Buffer.byteLength('yeni içerik'));
    assert.equal(typeof r.mtime, 'number');
  });

  it('Türkçe karakterli/boşluklu ad bozulmaz', () => {
    const f = mkfile('örnek çalışma/dilekçe taslağı.txt', 'eski');
    const r = cowork.saveProjectFile({ filePath: f, buffer: Buffer.from('güncel') });
    assert.equal(r.ok, true);
    assert.equal(r.name, 'dilekçe taslağı.txt');
    assert.equal(fs.readFileSync(f, 'utf8'), 'güncel');
    // Aynı klasörde başka dosya türememiş olmalı (sanitize/(n) davranışı YOK).
    const entries = fs.readdirSync(path.join(COWORK_TMP, 'örnek çalışma'));
    assert.deepEqual(entries, ['dilekçe taslağı.txt']);
  });

  it('boş buffer geçerlidir (dosya bilinçli boşaltılmış)', () => {
    const f = mkfile('alan-a/bosalt.txt', 'dolu');
    const r = cowork.saveProjectFile({ filePath: f, buffer: Buffer.alloc(0) });
    assert.equal(r.ok, true);
    assert.equal(r.size, 0);
    assert.equal(fs.readFileSync(f, 'utf8'), '');
  });

  it('başarı sonrası .agbtmp artığı kalmaz', () => {
    const f = mkfile('alan-a/temiz.txt');
    const r = cowork.saveProjectFile({ filePath: f, buffer: Buffer.from('x') });
    assert.equal(r.ok, true);
    assert.equal(fs.existsSync(f + '.agbtmp'), false);
  });

  it('kullanıcıya ait sabit .agbtmp dosyasına dokunmaz', () => {
    const f = mkfile('alan-a/cakisma.txt', 'eski');
    const userTmp = f + '.agbtmp';
    fs.writeFileSync(userTmp, 'kullanıcı dosyası');
    const r = cowork.saveProjectFile({ filePath: f, buffer: Buffer.from('yeni') });
    assert.equal(r.ok, true);
    assert.equal(fs.readFileSync(f, 'utf8'), 'yeni');
    assert.equal(fs.readFileSync(userTmp, 'utf8'), 'kullanıcı dosyası');
  });
});

describe('saveProjectFile: red durumları', () => {
  it('Cowork root dışı yol reddedilir', () => {
    const outside = path.join(os.tmpdir(), 'cowork-save-disari.txt');
    fs.writeFileSync(outside, 'x');
    try {
      const r = cowork.saveProjectFile({ filePath: outside, buffer: Buffer.from('y') });
      assert.equal(r.ok, false);
      assert.match(r.error, /root dışında/);
      assert.equal(fs.readFileSync(outside, 'utf8'), 'x', 'dosyaya dokunulmamalı');
    } finally { try { fs.unlinkSync(outside); } catch {} }
  });

  it('.. traversal ile kök dışına çıkılamaz', () => {
    const sneaky = path.join(COWORK_TMP, 'alan-a', '..', '..', 'kacak.txt');
    const r = cowork.saveProjectFile({ filePath: sneaky, buffer: Buffer.from('y') });
    assert.equal(r.ok, false);
  });

  it('olmayan hedef "dosya bulunamadı" döner (create-on-save yok)', () => {
    const r = cowork.saveProjectFile({ filePath: path.join(COWORK_TMP, 'yok.txt'), buffer: Buffer.from('y') });
    assert.equal(r.ok, false);
    assert.match(r.error, /bulunamadı/);
    assert.equal(fs.existsSync(path.join(COWORK_TMP, 'yok.txt')), false);
  });

  it('klasör hedef reddedilir', () => {
    const dir = path.join(COWORK_TMP, 'bir-klasor');
    fs.mkdirSync(dir, { recursive: true });
    const r = cowork.saveProjectFile({ filePath: dir, buffer: Buffer.from('y') });
    assert.equal(r.ok, false);
    assert.match(r.error, /bulunamadı/);
  });

  it('boş yol reddedilir', () => {
    // path.resolve('') cwd'ye çözülür; cwd Cowork root dışında olduğundan root
    // reddi devreye girer. Her halükarda ok:false beklenir.
    const r = cowork.saveProjectFile({ filePath: '', buffer: Buffer.from('y') });
    assert.equal(r.ok, false);
  });

  it('Cowork içindeki junction üzerinden dışarı yazmayı reddeder', () => {
    const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-save-junction-out-'));
    const outsideFile = path.join(outside, 'dis.txt');
    const link = path.join(COWORK_TMP, 'disari-link');
    fs.writeFileSync(outsideFile, 'korunacak');
    try {
      fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
      const r = cowork.saveProjectFile({ filePath: path.join(link, 'dis.txt'), buffer: Buffer.from('ezme') });
      assert.equal(r.ok, false);
      assert.match(r.error, /root dışında/);
      assert.equal(fs.readFileSync(outsideFile, 'utf8'), 'korunacak');
    } finally {
      try { fs.rmSync(link, { recursive: true, force: true }); } catch {}
      try { fs.rmSync(outside, { recursive: true, force: true }); } catch {}
    }
  });
});
