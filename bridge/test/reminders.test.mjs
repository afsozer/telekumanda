// Hatırlatıcı kaydı + zamanlayıcı birim testleri (reminders.mjs,
// docs/ekran-goruntusu-hatirlatici-plani.md Faz E3). Geçici klasör,
// sabit now; ağ/cihaz yok.
import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { createReminders, slugify } from '../reminders.mjs';

// Tüm harness'ların altında yaşadığı ortak geçici kök; after'da temizlenir.
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'reminders-tests-'));

// Sabit "şimdi": 2026-08-07 10:00 (+03:00).
const NOW = new Date('2026-08-07T10:00:00+03:00');

function makeHarness() {
  const notesDir = fs.mkdtempSync(path.join(TMP, 'notes-'));
  const logLines = [];
  const r = createReminders({
    notesDir,
    log: (line) => logLines.push(line),
    now: () => NOW,
  });
  return { notesDir, r, logLines };
}

function readFile(p) { return fs.readFileSync(p, 'utf8'); }

// Vakti gelmiş bir kaydı diskten oku: state/attempts frontmatter'da mı?
function reminderMeta(p) {
  const md = readFile(p);
  const state = /^reminder_state:\s*(.*)$/m.exec(md)?.[1];
  const attempts = /^reminder_attempts:\s*(.*)$/m.exec(md)?.[1];
  return { state, attempts };
}

after(() => { try { fs.rmSync(TMP, { recursive: true, force: true }); } catch {} });

describe('slugify', () => {
  it('Türkçe karakterleri ASCII ye çevirir, küçültür, tireler', () => {
    assert.equal(slugify('Duruşma Günü'), 'durusma-gunu');
    assert.equal(slugify('ÇÖĞÜŞIİÖÜ'), 'cogusiiou');
    assert.equal(slugify('Duruşma — 2. İş Mahkemesi'), 'durusma-2-is-mahkemesi');
  });

  // Sınır 80: slug artık cowork notlarıyla ORTAK (note-slug.mjs). Aynı başlık
  // her iki yolda da aynı dosya adını üretmeli, o yüzden cowork'ün mevcut
  // 80'i kanonik — buradaki eski 60 keyfiydi.
  it('80 karakterle sınırlar, baştaki/sondaki tireleri atar', () => {
    assert.equal(slugify('!!!'), 'hatirlatici'); // boş kalırsa yedek ad
    assert.equal(slugify('---başlık---'), 'baslik');
    assert.equal(slugify('a'.repeat(100)).length, 80);
  });
});

describe('setReminder + proje notu kapsami', () => {
  const mk = (dir, name, md) => {
    fs.mkdirSync(dir, { recursive: true });
    const p = path.join(dir, name);
    fs.writeFileSync(p, md, 'utf8');
    return p;
  };

  it('scan listNotePaths verilince proje notlarini da gorur', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rem-scope-'));
    const genel = mk(path.join(root, '_genel-notlar'), 'a.md',
      '---\ntitle: "Genel"\nreminder_at: 2026-08-12T14:00:00+03:00\n---\ngovde\n');
    const proje = mk(path.join(root, 'dava-x', 'notlar'), 'b.md',
      '---\ntitle: "Proje notu"\nreminder_at: 2026-08-13T09:00:00+03:00\n---\ngovde\n');

    const onlyGeneral = createReminders({ notesDir: path.join(root, '_genel-notlar') });
    assert.equal(onlyGeneral.scan().length, 1, 'notesDir modunda yalniz genel');

    const all = createReminders({
      notesDir: path.join(root, '_genel-notlar'),
      listNotePaths: () => [genel, proje],
    });
    assert.deepEqual(all.scan().map((r) => r.title).sort(), ['Genel', 'Proje notu']);
  });

  it('listNotePaths patlarsa scan cokmez', () => {
    const r = createReminders({
      notesDir: '/yok',
      listNotePaths: () => { throw new Error('liste yok'); },
      log: () => {},
    });
    assert.deepEqual(r.scan(), []);
  });

  it('setReminder var olan nota tarih kurar, govdeyi ve alanlari korur', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rem-set-'));
    const p = mk(dir, 'not.md', '---\ntitle: "Elle not"\nsource: x.ink\n---\ngovde satiri\nikinci\n');
    const r = createReminders({ notesDir: dir });

    const out = r.setReminder(p, '2026-08-12T14:00:00+03:00');
    assert.equal(out.ok, true);
    const md = fs.readFileSync(p, 'utf8');
    assert.match(md, /reminder_at: 2026-08-12T14:00:00\+03:00/);
    assert.match(md, /reminder_state: pending/);
    assert.match(md, /source: x\.ink/, 'diger alanlar korunmali');
    assert.match(md, /govde satiri\nikinci/, 'govde korunmali');
    assert.equal(r.scan().length, 1);
  });

  it('setReminder Date verilince +03:00 yazar, UTC Z degil', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rem-tz-'));
    const p = mk(dir, 'n.md', '---\ntitle: "T"\n---\nx\n');
    const r = createReminders({ notesDir: dir });
    r.setReminder(p, new Date('2026-08-12T11:00:00Z'));
    const md = fs.readFileSync(p, 'utf8');
    assert.match(md, /reminder_at: 2026-08-12T14:00:00\+03:00/);
    assert.ok(!/Z$/m.test(md.split('\n').find((l) => l.startsWith('reminder_at'))));
  });

  it('setReminder(null) hatirlaticiyi kaldirir, not kalir', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rem-clear-'));
    const p = mk(dir, 'n.md',
      '---\ntitle: "T"\nreminder_at: 2026-08-12T14:00:00+03:00\nreminder_state: sent\nreminder_attempts: 0\n---\ngovde\n');
    const r = createReminders({ notesDir: dir });

    assert.equal(r.setReminder(p, null).ok, true);
    const md = fs.readFileSync(p, 'utf8');
    assert.ok(!md.includes('reminder_at'), 'alan silinmeli');
    assert.match(md, /title: "T"/);
    assert.match(md, /govde/);
    assert.deepEqual(r.scan(), [], 'zamanlayiciya girmemeli');
  });

  it('setReminder sent notu yeniden pending yapar', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rem-re-'));
    const p = mk(dir, 'n.md',
      '---\ntitle: "T"\nreminder_at: 2026-08-01T10:00:00+03:00\nreminder_state: sent\nreminder_attempts: 3\n---\nx\n');
    const r = createReminders({ notesDir: dir });
    r.setReminder(p, '2026-09-01T10:00:00+03:00');
    const rec = r.scan()[0];
    assert.equal(rec.state, 'pending');
    assert.equal(rec.attempts, 0, 'sayac sifirlanmali');
  });

  it('setReminder gecersiz tarih ve frontmatter\'siz dosyayi reddeder', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rem-bad-'));
    const ok = mk(dir, 'a.md', '---\ntitle: "T"\n---\nx\n');
    const noFm = mk(dir, 'b.md', 'frontmatter yok\n');
    const r = createReminders({ notesDir: dir, log: () => {} });

    assert.equal(r.setReminder(ok, 'salı günü').ok, false);
    assert.equal(r.setReminder(noFm, '2026-08-12T14:00:00+03:00').ok, false);
    assert.equal(r.setReminder(path.join(dir, 'yok.md'), '2026-08-12T14:00:00+03:00').ok, false);
    assert.equal(fs.readFileSync(noFm, 'utf8'), 'frontmatter yok\n', 'reddedilen dosya degismemeli');
  });
});

describe('create', () => {
  it('doğru frontmatter lı dosya yazar; içerik geri okunabilir', () => {
    const { notesDir, r } = makeHarness();
    const res = r.create({
      baslik: 'Duruşma — 2. İş Mahkemesi',
      tarihSaat: '2026-08-12T14:00:00+03:00',
      alinti: 'duruşma günü 12.08.2026 saat 14:00',
      sourceScreenshot: 'Screenshot_20260807_110354_com_example_app.jpg',
    });
    assert.ok(res);
    assert.equal(res.id, 'durusma-2-is-mahkemesi.md');
    assert.equal(path.dirname(res.path), notesDir);
    assert.equal(fs.existsSync(res.path), true);

    const md = readFile(res.path);
    assert.match(md, /^title: "Duruşma — 2\. İş Mahkemesi"$/m);
    assert.match(md, /^reminder_at: 2026-08-12T14:00:00\+03:00$/m);
    assert.match(md, /^reminder_state: pending$/m);
    assert.match(md, /^reminder_attempts: 0$/m);
    assert.match(md, /^source_screenshot: Screenshot_20260807_110354_com_example_app\.jpg$/m);
    assert.match(md, /duruşma günü 12\.08\.2026 saat 14:00/);

    // scan ile geri okunur: title tırnakları sökülmüş, at Date olarak gelir.
    const [rec] = r.scan();
    assert.equal(rec.title, 'Duruşma — 2. İş Mahkemesi');
    assert.equal(rec.at.getTime(), new Date('2026-08-12T14:00:00+03:00').getTime());
    assert.equal(rec.state, 'pending');
    assert.equal(rec.attempts, 0);
    assert.equal(rec.source, 'Screenshot_20260807_110354_com_example_app.jpg');
  });

  it('ad çakışmasında -2 eki gelir', () => {
    const { r } = makeHarness();
    const first = r.create({ baslik: 'Tekrar', aciklama: 'bir' });
    const second = r.create({ baslik: 'Tekrar', aciklama: 'iki' });
    const third = r.create({ baslik: 'Tekrar', aciklama: 'üç' });
    assert.equal(first.id, 'tekrar.md');
    assert.equal(second.id, 'tekrar-2.md');
    assert.equal(third.id, 'tekrar-3.md');
    assert.equal(fs.existsSync(second.path), true);
  });

  it('tarihSaat yoksa reminder_at yazılmaz ve scan onu döndürmez', () => {
    const { r } = makeHarness();
    const res = r.create({ baslik: 'Tarihsiz iş', aciklama: 'bugün halledilecek' });
    assert.ok(res);
    const md = readFile(res.path);
    assert.doesNotMatch(md, /^reminder_at:/m); // reminder_attempts ile karışmasın
    assert.match(md, /bugün halledilecek/);
    assert.deepEqual(r.scan(), []);
  });

  it('başlık boşsa null döner', () => {
    const { r } = makeHarness();
    assert.equal(r.create({ baslik: '   ', aciklama: 'x' }), null);
    assert.equal(r.create({ baslik: '', tarihSaat: NOW.toISOString() }), null);
  });
});

describe('scan', () => {
  it('reminder_at i olmayan sıradan notları atlar', () => {
    const { notesDir, r } = makeHarness();
    fs.writeFileSync(path.join(notesDir, 'sıradan.md'), '---\ntitle: Sıradan not\n---\nmetin\n', 'utf8');
    assert.deepEqual(r.scan(), []);
  });

  it('bozuk frontmatter çökmeye yol açmaz', () => {
    const { notesDir, r, logLines } = makeHarness();
    fs.writeFileSync(path.join(notesDir, 'yarim.md'), '---\ntitle: Yarım\nreminder_at: 2026-08-12T14:00:00+03:00\n', 'utf8'); // kapanış yok
    fs.writeFileSync(path.join(notesDir, 'düz.md'), 'frontmatter yok, düz metin\n', 'utf8');
    fs.writeFileSync(path.join(notesDir, 'tarih.md'), '---\ntitle: Tarih\nreminder_at: hicbir-zaman\n---\nmetin\n', 'utf8'); // geçersiz tarih

    assert.deepEqual(r.scan(), []); // hiçbiri dönmez
    assert.ok(logLines.some((l) => l.includes('yarim')), 'yarım loglanmalı');
    assert.ok(!logLines.some((l) => l.includes('düz')), 'frontmatter\'sız düz not sıradandır, loglanmamalı');
    assert.ok(logLines.some((l) => l.includes('tarih')), 'geçersiz tarih loglanmalı');

    // tick de çökmez — bozuk dosyalar turları düşürmez.
    const res = r.tick();
    assert.doesNotReject(res);
    return res.then((t) => assert.deepEqual(t, { sent: 0, failed: 0, skipped: 0 }));
  });
});

describe('tick: durum', () => {
  it('vakti gelmiş kaydı sent yapar', async () => {
    const { r } = makeHarness();
    const res = r.create({ baslik: 'Toplantı', tarihSaat: '2026-08-07T09:00:00+03:00', alinti: 'toplantı 09:00' });
    const t = await r.tick();
    assert.deepEqual(t, { sent: 1, failed: 0, skipped: 0 });
    assert.equal(reminderMeta(res.path).state, 'sent');
  });

  it('vakti gelmemiş kayıt pending kalır', async () => {
    const { r } = makeHarness();
    const res = r.create({ baslik: 'Sonra', tarihSaat: '2026-08-07T12:00:00+03:00', alinti: 'öğleden sonra' });
    const t = await r.tick();
    assert.deepEqual(t, { sent: 0, failed: 0, skipped: 1 });
    assert.equal(reminderMeta(res.path).state, 'pending');
  });

  it('vakti geçmiş kayıt da sent yapılır', async () => {
    const { r } = makeHarness();
    const res = r.create({ baslik: 'Kaçan', tarihSaat: '2026-08-05T18:00:00+03:00', alinti: 'dün' });
    const t = await r.tick();
    assert.deepEqual(t, { sent: 1, failed: 0, skipped: 0 });
    assert.equal(reminderMeta(res.path).state, 'sent');
  });

  it('sent olan kayıt ikinci tick te yeniden sayılmaz', async () => {
    const { r } = makeHarness();
    r.create({ baslik: 'Bir Kere', tarihSaat: '2026-08-07T09:00:00+03:00' });
    await r.tick();
    const t = await r.tick();
    assert.deepEqual(t, { sent: 0, failed: 0, skipped: 1 });
  });

  it('güncelleme sonrası gövde ve source_screenshot alanı korunur', async () => {
    const { r } = makeHarness();
    const res = r.create({
      baslik: 'Duruşma',
      tarihSaat: '2026-08-07T09:00:00+03:00',
      alinti: 'duruşma günü 12.08.2026 saat 14:00',
      sourceScreenshot: 'Screenshot_20260807_110354_com_example_app.jpg',
    });
    await r.tick();

    const md = readFile(res.path);
    assert.match(md, /^source_screenshot: Screenshot_20260807_110354_com_example_app\.jpg$/m);
    assert.match(md, /duruşma günü 12\.08\.2026 saat 14:00/); // gövde aynen duruyor
    assert.match(md, /^reminder_state: sent$/m);
    assert.match(md, /^reminder_attempts: 0$/m);
  });
});
