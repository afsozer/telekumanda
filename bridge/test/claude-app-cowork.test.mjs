// Cowork modu birim testleri — claude-app.mjs cowork katmanı (outputs/ teslimat sözleşmesi
// + workspace yönetimi). Gerçek `claude` süreci başlatılmaz; dosya sistemi tmp dizinlerde.
import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// COWORK_ROOT modül yükleme anında env'den sabitlenir; bu yüzden claude-app.mjs
// STATİK değil, env set edildikten SONRA dinamik import edilir. Aksi halde workspace
// testleri gerçek ~/CoworkSpaces'e yazar (yaşandı: sanitize testlerinden 'abcd' ve
// 'etcpasswd' klasörleri kullanıcının workspace listesine sızdı).
const COWORK_TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-root-'));
process.env.AGENTBRIDGE_COWORK_ROOT = COWORK_TMP;
const app = await import('../claude-app.mjs');

after(() => { try { fs.rmSync(COWORK_TMP, { recursive: true, force: true }); } catch {} });

describe('cowork: newSession cowork bayrağı + outputs/ oluşturma', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-new-'));

  after(() => { try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {} });

  it('cowork:true oturumunda cwd/outputs oluşur ve snapshot cowork:true döner', () => {
    const ns = app.newSession({ cwd: tmp, cowork: true });
    assert.equal(ns.ok, true);
    assert.equal(ns.cowork, true);
    const outputsDir = path.join(tmp, 'outputs');
    assert.equal(fs.existsSync(outputsDir), true, 'outputs/ oluşturulmalı');
    assert.ok(fs.statSync(outputsDir).isDirectory());

    const conv = app.getConversation(ns.sessionId);
    assert.equal(conv.cowork, true);
    assert.ok(Array.isArray(conv.outputs), 'snapshot outputs dizisi taşmalı');
  });

  it('cowork:false (default) oturumunda outputs/ oluşturulmaz ve outputs undefined', () => {
    const ns = app.newSession({ cwd: tmp });
    assert.equal(ns.ok, true);
    assert.equal(ns.cowork, false);
    const conv = app.getConversation(ns.sessionId);
    assert.equal(conv.cowork, false);
    assert.equal(conv.outputs, undefined, 'cowork değilse outputs alanı olmamalı');
  });
});

describe('cowork: outputs/ tarama (scanOutputsSnapshot + diffOutputs)', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-scan-'));
  const outputsDir = path.join(tmp, 'outputs');

  // Her testten önce outputs/ içeriğini temizle → testler birbirinden bağımsız.
  const cleanOutputs = () => {
    try { for (const f of fs.readdirSync(outputsDir)) fs.unlinkSync(path.join(outputsDir, f)); } catch {}
  };
  before(() => fs.mkdirSync(outputsDir, { recursive: true }));
  after(() => { try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {} });

  it('scanOutputsSnapshot boş outputs/ için boş dizi', () => {
    cleanOutputs();
    assert.deepEqual(app.scanOutputsSnapshot(tmp), []);
  });

  it('scanOutputsSnapshot dosyaları {name,path,size,mtime} ile listeler (alt klasör değil)', () => {
    cleanOutputs();
    const f = path.join(outputsDir, 'rapor.docx');
    fs.writeFileSync(f, 'docx-icerik'); // 11 byte
    const snap = app.scanOutputsSnapshot(tmp);
    assert.equal(snap.length, 1);
    assert.equal(snap[0].name, 'rapor.docx');
    assert.equal(snap[0].path, f);
    assert.equal(snap[0].size, 11);
    assert.equal(typeof snap[0].mtime, 'number');
  });

  it('diffOutputs before null iken tüm dosyaları döndürür', () => {
    cleanOutputs();
    const f = path.join(outputsDir, 'a.txt');
    fs.writeFileSync(f, 'aaa');
    const d = app.diffOutputs(tmp, null);
    assert.equal(d.length, 1);
    assert.equal(d[0].name, 'a.txt');
  });

  it('diffOutputs before === now iken boş döndürür (değişen yok)', () => {
    cleanOutputs();
    fs.writeFileSync(path.join(outputsDir, 'a.txt'), 'aaa');
    const before = app.scanOutputsSnapshot(tmp);
    const d = app.diffOutputs(tmp, before);
    assert.equal(d.length, 0, "değişmeyen dosya diff'te olmamalı");
  });

  it('diffOutputs yeni eklenen dosyayı yakalar', () => {
    cleanOutputs();
    fs.writeFileSync(path.join(outputsDir, 'a.txt'), 'aaa');
    const before = app.scanOutputsSnapshot(tmp); // [a.txt]
    fs.writeFileSync(path.join(outputsDir, 'b.xlsx'), 'bbb');
    const d = app.diffOutputs(tmp, before);
    assert.equal(d.length, 1);
    assert.equal(d[0].name, 'b.xlsx');
  });

  it('diffOutputs değişen (mtime) dosyayı yakalar', async () => {
    cleanOutputs();
    fs.writeFileSync(path.join(outputsDir, 'a.txt'), 'aaa');
    const before = app.scanOutputsSnapshot(tmp);
    // mtime kesin farklı olsun diye bekle.
    await new Promise(r => setTimeout(r, 30));
    fs.writeFileSync(path.join(outputsDir, 'a.txt'), 'aaa-yeni-uzun-icerik');
    const d = app.diffOutputs(tmp, before);
    const names = d.map(o => o.name);
    assert.ok(names.includes('a.txt'), "değişen a.txt diff'te olmalı");
  });
});

describe('cowork: workspace ad sanitizasyonu (createWorkspace)', () => {
  // Tüm workspace'ler COWORK_TMP altına yazılır; üstteki global after temizler.

  it('geçerli ad ile workspace oluşturur ve { ok, name, path } döner', () => {
    const r = app.createWorkspace({ name: 'agtest-cowork-test-a' });
    assert.equal(r.ok, true);
    assert.equal(r.name, 'agtest-cowork-test-a');
    assert.ok(r.path);
    assert.equal(fs.existsSync(r.path), true);
    assert.ok(fs.statSync(r.path).isDirectory());
  });

  it('idempotent — aynı ad tekrar verildiğinde yine ok', () => {
    const r1 = app.createWorkspace({ name: 'agtest-cowork-test-a' });
    const r2 = app.createWorkspace({ name: 'agtest-cowork-test-a' });
    assert.equal(r1.ok, true);
    assert.equal(r2.ok, true);
    assert.equal(r1.path, r2.path);
  });

  it('path traversal reddeder (../x)', () => {
    const r = app.createWorkspace({ name: '../../../etc/passwd' });
    // sanitize sonrası ad "/etc/passwd" → "etcpasswd" gibi bir şey olur veya reddedilir;
    // her halükarda COWORK_ROOT dışına ÇIKMAMALI. path, COWORK_ROOT altında olmalı.
    if (r.ok) {
      const rel = path.relative(app.coworkRoot(), r.path);
      assert.ok(!rel.startsWith('..') && !path.isAbsolute(rel), 'kök dışına çıkamaz');
    }
  });

  it('path ayraçlı ad sanitize edilir (Windows \\ / :)', () => {
    const r = app.createWorkspace({ name: 'a\\b/c:d' });
    // ayraçlar temizlenir; son ad COWORK_ROOT içinde güvenli kalır.
    if (r.ok) {
      const rel = path.relative(app.coworkRoot(), r.path);
      assert.ok(!rel.startsWith('..') && !path.isAbsolute(rel));
    }
  });

  it('Türkçe harfler korunur (\\w ASCII-only regresyonu)', () => {
    // Hata: /[^\w.\- ]+/ ile "ışık çağlar" → "k alar" oluyordu.
    for (const name of ['ışık çağlar', 'gönül öğüt', 'Şûra Çğİ Üyesi']) {
      const r = app.createWorkspace({ name });
      assert.equal(r.ok, true, name + ' oluşmalı');
      assert.equal(r.name, name, 'ad harf kaybetmemeli');
      assert.equal(path.basename(r.path), name);
      assert.equal(fs.existsSync(r.path), true);
    }
  });

  it('Türkçe ad path ayraçlarını yine de eler', () => {
    // Ayraçlar silinir; kalan noktalar tek klasör adının parçasıdır (traversal
    // değil). Asıl değişmez: sonuç COWORK_ROOT altında tek seviye kalmalı.
    const r = app.createWorkspace({ name: 'ışık/../çağlar' });
    assert.equal(r.ok, true);
    assert.equal(/[\\/:]/.test(r.name), false, 'ayraç kalmamalı');
    const rel = path.relative(app.coworkRoot(), r.path);
    assert.ok(!rel.startsWith('..') && !path.isAbsolute(rel), 'kök dışına çıkamaz');
    assert.equal(rel.includes(path.sep), false, 'tek seviye klasör olmalı');
  });

  it('boş ad reddedilir', () => {
    assert.equal(app.createWorkspace({ name: '' }).ok, false);
    assert.equal(app.createWorkspace({ name: '   ' }).ok, false);
    assert.equal(app.createWorkspace({}).ok, false);
  });

  it('nokta-only ad (gizli/relative) reddedilir', () => {
    assert.equal(app.createWorkspace({ name: '...' }).ok, false);
    assert.equal(app.createWorkspace({ name: '.' }).ok, false);
  });
});

describe('cowork: listWorkspaces', () => {
  it('ok ve workspaces dizisi döner, kök yol taşır', () => {
    const r = app.listWorkspaces();
    assert.equal(r.ok, true);
    assert.ok(Array.isArray(r.workspaces));
    assert.ok(typeof r.root === 'string');
  });

  it('oluşturulan workspace listelenir', () => {
    const r1 = app.createWorkspace({ name: 'agtest-cowork-test-b' });
    assert.equal(r1.ok, true);
    const list = app.listWorkspaces();
    const found = list.workspaces.find(w => w.name === 'agtest-cowork-test-b');
    assert.ok(found, 'workspace listelenmeli');
    assert.equal(found.path, r1.path);
    try { fs.rmSync(r1.path, { recursive: true, force: true }); } catch {}
  });
});
