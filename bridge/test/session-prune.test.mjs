// Bir haftadan fazla inaktif oturumlarin budanmasi.
//
// Testlerin agirligi SECIM tarafinda: silme geri alinamaz, o yuzden yanlis
// secimin testte goze batmasi gerekiyor. Ozellikle iki muafiyet (cowork ve pin)
// ve "yasi bilinmeyen oturum SAKLANIR" kurali sabitleniyor.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import os from 'node:os';
import { selectStale, pruneSessions, isUnderRoot } from '../session-prune.mjs';

const GUN = 24 * 60 * 60 * 1000;
const SIMDI = Date.parse('2026-08-19T23:00:00+03:00');
const HAFTA = 7 * GUN;
const COWORK = path.join(os.homedir(), 'CoworkSpaces');

const oturum = (id, gunOnce, ek = {}) => ({
  id,
  cwd: path.join(os.homedir(), 'agtest'),
  mtime: SIMDI - gunOnce * GUN,
  ...ek,
});

const sec = (sessions) => selectStale({ sessions, maxAgeMs: HAFTA, now: SIMDI, coworkRoot: COWORK });

describe('oturum budama secimi', () => {
  it('7 gunden eskiyi secer, yenisini birakir', () => {
    const r = sec([oturum('eski', 10), oturum('yeni', 2)]);
    assert.deepEqual(r.stale.map(s => s.id), ['eski']);
    assert.equal(r.fresh, 1);
  });

  it('tam sinirdaki oturum KALIR', () => {
    const r = sec([oturum('tam', 7)]);
    assert.deepEqual(r.stale, []);
    assert.equal(r.fresh, 1);
  });

  it('cowork oturumu ne kadar eski olursa olsun MUAF', () => {
    // Bir dava dosyasi aylarca sessiz kalip sonra devam edebilir.
    const r = sec([oturum('dava', 200, { cwd: path.join(COWORK, 'ornek-dava') })]);
    assert.deepEqual(r.stale, []);
    assert.equal(r.cowork, 1);
  });

  it('cowork kokunun kendisi de muaf', () => {
    const r = sec([oturum('kok', 90, { cwd: COWORK })]);
    assert.equal(r.cowork, 1);
  });

  it('cowork cwd egik bolu ile gelse de taninir', () => {
    // opencode `directory` alanini '/' ile veriyor; ayirici normalize edilmezse
    // muafiyet sessizce kacar ve calisma alani oturumu silinirdi.
    const egik = path.join(COWORK, 'test-alani').replace(/\\/g, '/');
    const r = sec([oturum('opencode-cowork', 60, { cwd: egik })]);
    assert.equal(r.cowork, 1);
    assert.deepEqual(r.stale, []);
  });

  it('cowork bayragi cwd cowork disinda olsa da muaf birakir', () => {
    // claude tarafinda catal grubunun HERHANGI bir uyesi cowork ise grup muaf.
    const r = sec([oturum('grup', 60, { cowork: true })]);
    assert.equal(r.cowork, 1);
  });

  it('cowork ADI GECEN ama altinda OLMAYAN klasor muaf degil', () => {
    // "CoworkSpaces-yedek" cowork kokunun altinda degil; path.relative bunu
    // '..' ile disari atmali. Duz string prefix karsilastirmasi yanilirdi.
    const r = sec([oturum('yedek', 60, { cwd: COWORK + '-yedek' })]);
    assert.deepEqual(r.stale.map(s => s.id), ['yedek']);
    assert.equal(r.cowork, 0);
  });

  it('pinli oturum muaf', () => {
    const r = sec([oturum('pinli', 60, { pinned: true })]);
    assert.deepEqual(r.stale, []);
    assert.equal(r.pinned, 1);
  });

  it('yasi bilinmeyen ama ICI DOLU oturum SAKLANIR', () => {
    // Bilinmeyen yas tek basina silme gerekcesi degil. Tersi olsaydi
    // (0 = cok eski) zamani okunamayan her oturum ilk turda ucardi.
    const r = sec([
      oturum('sifir', 0, { mtime: 0, turns: 5 }),
      oturum('yok', 0, { mtime: undefined, turns: 1 }),
      oturum('metin', 0, { mtime: 'dun', turns: 3 }),
    ]);
    assert.deepEqual(r.stale, []);
    assert.equal(r.unknown, 3);
  });

  it('yasi bilinmeyen VE BOS oturum budanir', () => {
    // Kaybedilecek bir sey yok; saklamak ise sonsuza dek saklamak demek,
    // cunku bu kayitlarin yasi hicbir zaman gelmez.
    const r = sec([oturum('kabuk', 0, { mtime: 0, turns: 0 })]);
    assert.deepEqual(r.stale.map(s => s.id), ['kabuk']);
    assert.equal(r.emptyUnknown, 1);
    assert.equal(r.unknown, 0);
  });

  it('tur sayisi BILDIRILMEYEN kayit bos sayilmaz', () => {
    // `=== 0` bilerek kati: eksik alani "bos" saymak, tur sayisini bildirmeyen
    // bir backend'in butun gecmisini sessizce silerdi.
    const r = sec([oturum('bilinmez', 0, { mtime: 0 })]);
    assert.deepEqual(r.stale, []);
    assert.equal(r.unknown, 1);
  });

  it('bos ama YASI BILINEN oturum normal kurala tabi', () => {
    // Yeni acilmis bos sekme silinmemeli — createdAt bunun icin eklendi.
    const r = sec([oturum('yeni-bos', 1, { turns: 0 }), oturum('eski-bos', 30, { turns: 0 })]);
    assert.deepEqual(r.stale.map(s => s.id), ['eski-bos']);
    assert.equal(r.fresh, 1);
  });

  it('maxAgeMs gecersizse hicbir sey secilmez', () => {
    for (const kotu of [0, -1, NaN, undefined]) {
      const r = selectStale({ sessions: [oturum('eski', 500)], maxAgeMs: kotu, now: SIMDI });
      assert.deepEqual(r.stale, [], `maxAgeMs=${kotu}`);
    }
  });

  it('idsiz kayit atlanir', () => {
    const r = sec([{ cwd: '', mtime: SIMDI - 90 * GUN }, oturum('gecerli', 90)]);
    assert.deepEqual(r.stale.map(s => s.id), ['gecerli']);
  });
});

describe('oturum budama uygulamasi', () => {
  it('yalniz secilenleri siler ve kaydin tamamini verir', async () => {
    const silinen = [];
    const r = await pruneSessions({
      sessions: [oturum('eski', 30, { ids: ['a', 'b'] }), oturum('yeni', 1)],
      remove: async (s) => { silinen.push(s); return { ok: true }; },
      maxAgeMs: HAFTA, now: SIMDI, coworkRoot: COWORK,
    });
    assert.equal(r.removed, 1);
    assert.equal(silinen.length, 1);
    // Kaydin tamami gecmeli: claude'da bir oturum birden fazla dosya olabiliyor.
    assert.deepEqual(silinen[0].ids, ['a', 'b']);
  });

  it('bir oturumun hatasi digerlerini durdurmaz', async () => {
    const r = await pruneSessions({
      sessions: [oturum('kilitli', 30), oturum('normal', 30), oturum('patlayan', 30)],
      remove: async (s) => {
        if (s.id === 'kilitli') return { ok: false, error: 'dosya kullanimda' };
        if (s.id === 'patlayan') throw new Error('beklenmeyen');
        return { ok: true };
      },
      maxAgeMs: HAFTA, now: SIMDI,
    });
    assert.equal(r.removed, 1);
    assert.equal(r.failed, 2);
  });

  it('raporda muafiyetler ayri ayri sayilir', async () => {
    const r = await pruneSessions({
      sessions: [
        oturum('cw', 30, { cwd: path.join(COWORK, 'x') }),
        oturum('pin', 30, { pinned: true }),
        oturum('taze', 1),
        oturum('eski', 30),
      ],
      remove: async () => ({ ok: true }),
      maxAgeMs: HAFTA, now: SIMDI, coworkRoot: COWORK,
    });
    assert.deepEqual(
      { removed: r.removed, cowork: r.cowork, pinned: r.pinned, fresh: r.fresh },
      { removed: 1, cowork: 1, pinned: 1, fresh: 1 },
    );
  });
});

describe('isUnderRoot', () => {
  it('kok yoksa false', () => {
    assert.equal(isUnderRoot('/a/b', ''), false);
    assert.equal(isUnderRoot('', '/a'), false);
  });
});
