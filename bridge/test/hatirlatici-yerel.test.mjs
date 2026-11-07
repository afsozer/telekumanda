// Cihaz-yerel hatırlatma (21.10.2026): köprü göndermiyor, liste veriyor ve
// değişince ADB ile itiyor. reminders.upcoming(), gönderimsiz tick ve
// hatirlatici-adb.mjs; ağ/cihaz yok (sahte adb koşucusu).
import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { createReminders } from '../reminders.mjs';
import { createHatirlaticiAdb } from '../hatirlatici-adb.mjs';

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'hatirlatici-yerel-'));
after(() => { try { fs.rmSync(TMP, { recursive: true, force: true }); } catch {} });

const NOW = new Date('2026-10-21T12:00:00+03:00');
const GUN = 24 * 60 * 60 * 1000;

function not(dir, ad, { title, at, state = 'pending', govde = 'ilk satir' }) {
  const p = path.join(dir, ad);
  fs.writeFileSync(p, `---\ntitle: "${title}"\nreminder_at: ${at}\nreminder_state: ${state}\nreminder_attempts: 0\n---\n${govde}\nikinci satir\n`, 'utf8');
  return p;
}

function kur() {
  const dir = fs.mkdtempSync(path.join(TMP, 'notlar-'));
  const r = createReminders({
    notesDir: dir,
    noteIdOf: (p) => `_genel-notlar/${path.basename(p, '.md')}`,
    now: () => NOW,
  });
  return { dir, r };
}

describe('upcoming', () => {
  it('pencere icindekileri zamana gore sirali, ozetiyle verir', () => {
    const { dir, r } = kur();
    not(dir, 'b.md', { title: 'Durusma', at: '2026-10-22T09:00:00+03:00', govde: 'Is mahkemesi 3. kat' });
    not(dir, 'a.md', { title: 'Dun', at: '2026-10-20T18:00:00+03:00', state: 'sent' });
    not(dir, 'eski.md', { title: 'Cok eski', at: '2026-10-01T09:00:00+03:00' });
    not(dir, 'uzak.md', { title: 'Cok uzak', at: '2027-06-01T09:00:00+03:00' });
    fs.writeFileSync(path.join(dir, 'duz.md'), '---\ntitle: "Duz not"\n---\ngovde\n', 'utf8');

    const liste = r.upcoming();
    assert.deepEqual(liste.map((x) => x.title), ['Dun', 'Durusma']);
    const d = liste[1];
    assert.equal(d.noteId, '_genel-notlar/b');
    assert.equal(d.summary, 'Is mahkemesi 3. kat');
    assert.equal(d.at, new Date('2026-10-22T09:00:00+03:00').getTime());
  });

  it('limit ve pencere ayarlanabilir', () => {
    const { dir, r } = kur();
    for (let i = 1; i <= 5; i++) not(dir, `n${i}.md`, { title: `N${i}`, at: new Date(NOW.getTime() + i * GUN).toISOString() });
    assert.equal(r.upcoming({ limit: 2 }).length, 2);
    assert.equal(r.upcoming({ futureMs: 2.5 * GUN }).length, 2);
  });
});

describe('gonderimsiz tick', () => {
  it('vakti gelen pending kaydi yalniz sent yapar, gelecektekine dokunmaz', async () => {
    const { dir, r } = kur();
    const gecmis = not(dir, 'g.md', { title: 'G', at: '2026-10-21T11:59:00+03:00' });
    const gelecek = not(dir, 'f.md', { title: 'F', at: '2026-10-21T13:00:00+03:00' });
    const sonuc = await r.tick();
    assert.equal(sonuc.sent, 1);
    assert.match(fs.readFileSync(gecmis, 'utf8'), /^reminder_state: sent$/m);
    assert.match(fs.readFileSync(gelecek, 'utf8'), /^reminder_state: pending$/m);
  });
});

describe('hatirlatici-adb', () => {
  function sahteAdb({ basarili = true } = {}) {
    const cagrilar = [];
    const runCommand = (args) => {
      cagrilar.push(args);
      if (args[0] === '-s') return basarili ? { err: null, out: 'Broadcasting: Intent\nBroadcast completed: result=0' } : { err: new Error('closed'), out: '' };
      return { err: null, out: '' };
    };
    return { cagrilar, runCommand };
  }

  it('listeyi base64 JSON olarak explicit broadcast ile iter ve baglantiyi kapatir', async () => {
    const { cagrilar, runCommand } = sahteAdb();
    const liste = [{ noteId: 'x', title: 'Duruşma "ön" hazırlık', summary: "a'b", at: 1 }];
    const adb = createHatirlaticiAdb({ devices: ['tel:5555'], liste: () => liste, runCommand });
    const sonuc = await adb.it();
    assert.deepEqual(sonuc, [{ seri: 'tel:5555', ok: true }]);
    assert.deepEqual(cagrilar[0], ['connect', 'tel:5555']);
    const komut = cagrilar[1][3];
    assert.match(komut, /^am broadcast -n com\.agent\.bridge\/\.HatirlaticiAdbReceiver -a com\.agent\.bridge\.HATIRLATICI_LISTESI --es kayitlar '[A-Za-z0-9+/=]+'$/);
    const yuk = /'([^']+)'$/.exec(komut)[1];
    assert.deepEqual(JSON.parse(Buffer.from(yuk, 'base64').toString('utf8')), liste);
    assert.deepEqual(cagrilar.at(-1), ['disconnect', 'tel:5555']);
  });

  it('liste degismediyse ikinci kez itmez', async () => {
    const { cagrilar, runCommand } = sahteAdb();
    const adb = createHatirlaticiAdb({ devices: ['tel:5555'], liste: () => [], runCommand });
    await adb.it();
    const ilk = cagrilar.length;
    const ikinci = await adb.it();
    assert.equal(cagrilar.length, ilk);
    assert.deepEqual(ikinci, [{ seri: 'tel:5555', ok: true, atlandi: true }]);
  });

  it('ulasilamazsa bir kez yeniden baglanir, basarisizligi kaydeder ve sonraki itmede yine dener', async () => {
    const { cagrilar, runCommand } = sahteAdb({ basarili: false });
    const loglar = [];
    const adb = createHatirlaticiAdb({ devices: ['tel:5555'], liste: () => [], runCommand, log: (m) => loglar.push(m) });
    assert.deepEqual(await adb.it(), [{ seri: 'tel:5555', ok: false }]);
    assert.equal(cagrilar.filter((a) => a[0] === '-s').length, 2);
    assert.equal(loglar.length, 1);
    const once = cagrilar.length;
    await adb.it();
    assert.ok(cagrilar.length > once);
  });

  it('cihaz ya da liste yoksa devre disi', () => {
    assert.equal(createHatirlaticiAdb({ devices: [], liste: () => [], runCommand: () => ({}) }).etkin, false);
    assert.equal(createHatirlaticiAdb({ adb: 'C:/yok/adb.exe', devices: ['x'], liste: () => [] }).etkin, false);
  });
});
