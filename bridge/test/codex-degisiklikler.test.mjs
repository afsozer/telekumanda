// "Değişiklikler" — codex oturumunun dosya değişiklikleri (GET /codex-app/diff).
//
// Gerçek `codex app-server` doğurulmaz: kaynak, akan `item/completed`
// bildirimlerinden biriken `_fileChanges` dizisi ve o dizi
// `__testAppendThreadItem` ile doğrudan beslenebiliyor.
//
// Buradaki sahte item'lar ÖLÇÜLEN şemayla birebir (codex 0.147.0,
// `codex app-server generate-json-schema`):
//   FileChangeThreadItem { id, type:'fileChange', status: PatchApplyStatus,
//                          changes: FileUpdateChange[] }
//   FileUpdateChange     { path, kind:{type:'add'|'delete'|'update'}, diff }
// Yanıt şeması opencode-app'in sessionDiff'iyle AYNI olmak ZORUNDA: telefonda
// ikisini de tek bir Compose gövdesi çiziyor.

import assert from 'node:assert/strict';
import { after, describe, it } from 'node:test';
import os from 'node:os';
import path from 'node:path';
import * as codexApp from '../codex-app.mjs';

after(() => { codexApp.killAllSessions(); });

const KOK = os.homedir();

function oturum() {
  const r = codexApp.newSession({ cwd: KOK });
  assert.equal(r.ok, true);
  return r.sessionId;
}

/** Mutlak yol — codex yolu mutlak veriyor (rollout'ta ölçüldü). */
const yol = (...p) => path.join(KOK, ...p);

function dokun(sid, changes, { status = 'completed', id = 'fc-' + Math.random(), turnId } = {}) {
  assert.equal(codexApp.__testAppendThreadItem(sid, { id, type: 'fileChange', status, changes }, turnId), true);
}

describe('codex sessionDiff — normal hâl', () => {
  it('dosyaları rozetleriyle döner ve yolu proje köküne göreliler', () => {
    const sid = oturum();
    dokun(sid, [
      { path: yol('src', 'a.ts'), kind: { type: 'update', move_path: null }, diff: '@@ -1 +1 @@\n-eski\n+yeni\n' },
      { path: yol('yeni.md'), kind: { type: 'add' }, diff: '@@ -0,0 +1 @@\n+selam\n' },
    ]);
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.equal(r.ok, true);
    assert.deepEqual(r.files.map(f => f.path), ['src/a.ts', 'yeni.md']);
    assert.deepEqual(r.files.map(f => f.status), ['modified', 'added']);
    assert.equal(r.files[0].additions, 1);
    assert.equal(r.files[0].deletions, 1);
    assert.equal(r.additions, 2);
    assert.equal(r.deletions, 1);
    assert.equal(r.truncated, false);
  });

  // ASIL TUZAK: `+++`/`---` başlıkları da artı/eksiyle başlıyor. Sayılsalardı
  // her dosya en az "+1 −1" görünürdü.
  it('yama başlıkları sayaçlara girmez', () => {
    const sid = oturum();
    dokun(sid, [{
      path: yol('b.ts'),
      kind: { type: 'update', move_path: null },
      diff: '--- a/b.ts\n+++ b/b.ts\n@@ -1,2 +1,2 @@\n-bir\n+iki\n uc\n',
    }]);
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.equal(r.files[0].additions, 1);
    assert.equal(r.files[0].deletions, 1);
  });

  it('silinen dosya deleted rozetiyle gelir', () => {
    const sid = oturum();
    dokun(sid, [{ path: yol('gitti.ts'), kind: { type: 'delete' }, diff: '@@ -1 +0,0 @@\n-son\n' }]);
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.equal(r.files[0].status, 'deleted');
    assert.equal(r.files[0].deletions, 1);
  });

  // Yinelenen yol telefonda LazyColumn'un `key = { path }`ini çökertirdi.
  it('aynı dosyaya iki kez dokunulursa tek satırda birleşir', () => {
    const sid = oturum();
    dokun(sid, [{ path: yol('c.ts'), kind: { type: 'add' }, diff: '+bir\n' }]);
    dokun(sid, [{ path: yol('c.ts'), kind: { type: 'update', move_path: null }, diff: '+iki\n-bir\n' }]);
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.equal(r.files.length, 1);
    assert.equal(r.files[0].additions, 2);
    assert.equal(r.files[0].deletions, 1);
    // Eklenip sonra düzenlenen dosya `added` KALIR: oturumdan önce yoktu.
    assert.equal(r.files[0].status, 'added');
    assert.ok(r.files[0].patch.includes('+bir'));
    assert.ok(r.files[0].patch.includes('+iki'));
  });

  it('tur sayısı ayrı turId taşıyan kayıtlardan çıkar', () => {
    const sid = oturum();
    dokun(sid, [{ path: yol('t1.ts'), kind: { type: 'add' }, diff: '+a\n' }], { turnId: 'turn-1' });
    dokun(sid, [{ path: yol('t2.ts'), kind: { type: 'add' }, diff: '+b\n' }], { turnId: 'turn-2' });
    dokun(sid, [{ path: yol('t3.ts'), kind: { type: 'add' }, diff: '+c\n' }], { turnId: 'turn-2' });
    assert.equal(codexApp.sessionDiff({ sessionId: sid }).turns, 2);
  });
});

describe('codex sessionDiff — dürüstlük', () => {
  // Reddedilen/başarısız yama diske DOKUNMADI; listelenmesi yapılmamış bir
  // düzenlemeyi yapılmış gibi okuturdu.
  it('uygulanmayan yamalar listeye girmez', () => {
    const sid = oturum();
    dokun(sid, [{ path: yol('red.ts'), kind: { type: 'add' }, diff: '+x\n' }], { status: 'declined' });
    dokun(sid, [{ path: yol('hata.ts'), kind: { type: 'add' }, diff: '+y\n' }], { status: 'failed' });
    dokun(sid, [{ path: yol('oldu.ts'), kind: { type: 'add' }, diff: '+z\n' }], { status: 'completed' });
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.deepEqual(r.files.map(f => f.path), ['oldu.ts']);
  });

  // Eski app-server `status` göndermiyorsa kayıt UYGULANDI sayılır: aksi hâlde
  // sürüm farkı yüzünden bütün liste sessizce boşalırdı.
  it('status boşsa değişiklik listelenir', () => {
    const sid = oturum();
    dokun(sid, [{ path: yol('eski.ts'), kind: { type: 'add' }, diff: '+q\n' }], { status: '' });
    assert.equal(codexApp.sessionDiff({ sessionId: sid }).files.length, 1);
  });

  it('kırpılan yama truncated bayrağını taşır', () => {
    const sid = oturum();
    dokun(sid, [{ path: yol('dev.ts'), kind: { type: 'add' }, diff: '+' + 'x'.repeat(200 * 1024) }]);
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.equal(r.files[0].truncated, true);
    assert.equal(r.truncated, true);
    assert.ok(r.files[0].patch.length <= 100 * 1024);
  });

  // Geçmişi rollout'tan kurulan oturumda liste BOŞ başlıyor (rollout fileChange
  // item'ı taşımıyor). Bayrak olmasaydı telefon "bu oturum dosya değiştirmedi"
  // yazardı — eski bir oturumu açan kullanıcı için bu düpedüz yanlış.
  it('geçmiş boşluğu yüke taşınır', () => {
    const sid = oturum();
    assert.equal(codexApp.sessionDiff({ sessionId: sid }).historyGap, false);
    assert.equal(codexApp.__testSetDiffHistoryGap(sid, true), true);
    assert.equal(codexApp.sessionDiff({ sessionId: sid }).historyGap, true);
  });

  it('proje kökü dışındaki dosya mutlak kalır', () => {
    const sid = oturum();
    const disarida = path.join(path.parse(KOK).root, 'baska', 'yer.ts');
    dokun(sid, [{ path: disarida, kind: { type: 'update', move_path: null }, diff: '+a\n' }]);
    const r = codexApp.sessionDiff({ sessionId: sid });
    assert.equal(r.files[0].path, disarida.replace(/\\/g, '/'));
  });
});

describe('codex sessionDiff — boş hâller', () => {
  it('bilinmeyen oturum hata döner', () => {
    const r = codexApp.sessionDiff({ sessionId: 'yok-boyle-' + Date.now() });
    assert.equal(r.ok, false);
    assert.match(r.error, /not found/);
  });

  // Boş liste HATA DEĞİL: telefon "bu oturum dosya değiştirmedi" der.
  it('taze oturum boş liste döner', () => {
    const r = codexApp.sessionDiff({ sessionId: oturum() });
    assert.equal(r.ok, true);
    assert.deepEqual(r.files, []);
    assert.equal(r.additions, 0);
    assert.equal(r.deletions, 0);
    assert.equal(r.turns, 0);
    assert.equal(r.truncated, false);
  });

  it('yolu olmayan kayıt atlanır', () => {
    const sid = oturum();
    dokun(sid, [{ path: '', kind: { type: 'add' }, diff: '+a\n' }]);
    assert.deepEqual(codexApp.sessionDiff({ sessionId: sid }).files, []);
  });
});
