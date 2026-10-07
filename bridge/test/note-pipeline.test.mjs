// Boru hatti entegrasyonu: paylasilan icerik -> screenshot-extract -> reminders.
// Iki modul tek tek test edildi; burada BIRBIRINE BAGLANISI test ediliyor
// (server.mjs'teki /cowork/note/from-share ucunun ayni sirasi), cunku alan
// adlari uyusmazsa (`tarih_saat` vs `tarihSaat`) her modulun kendi testi yine
// yesil kalir.
//
// Bu dosya eskiden screenshot-watch -> extract -> reminders zincirini test
// ediyordu. Tarayici 19.08.2026'da sokuldu (297 goruntu -> 3 not); zincirin
// bas tarafi artik paylas menusundeki "Not ekle".
//
// Gercek agy YOK: runCommand enjekte edilir.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createScreenshotExtract } from '../screenshot-extract.mjs';
import { createReminders } from '../reminders.mjs';

const PHONE = '100.100.100.1:5555';

function tmp() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notpipe-'));
  const shareDir = path.join(dir, 'share-notes');
  fs.mkdirSync(shareDir, { recursive: true });
  return { shareDir, notesDir: path.join(dir, '_genel-notlar') };
}

// Ucun diske yazdigi kopyanin taklidi: ad onunde zaman damgasi var.
function paylasilan(shareDir, ad = 'paylasilan.jpg') {
  const p = path.join(shareDir, `1755600000000_${ad}`);
  fs.writeFileSync(p, 'jpeg-bytes');
  return p;
}

function modeliKur(shareDir, out) {
  return createScreenshotExtract({
    addDir: shareDir,
    runCommand: async () => ({ err: null, out, errOut: '' }),
    log: () => {},
  });
}

// server.mjs'teki /cowork/note/from-share akisinin aynisi. `siniflandir: false`
// burada da sabit: kullanici paylas tusuna basarak "bu kayda deger" demis oldu.
async function paylasimdanNot(localPath, extract, reminders) {
  const rec = await extract.extract(localPath, null, { siniflandir: false });
  if (!rec) return null;
  const note = reminders.create({
    baslik: rec.baslik,
    tarihSaat: rec.tarih_saat,
    alinti: rec.alinti,
    aciklama: rec.aciklama,
    metin: rec.metin,
    etiketler: rec.etiketler,
    sourceScreenshot: path.basename(localPath),
  });
  return note ? { rec, note } : null;
}

describe('paylasimdan not boru hatti', () => {
  it('paylasilan icerik -> hatirlatici notu -> vakti gelince bildirim', async () => {
    const { shareDir, notesDir } = tmp();
    const sent = [];
    const send = async (extras, opts) => { sent.push({ extras, opts }); return [{ serial: PHONE, ok: true }]; };

    const extract = modeliKur(
      shareDir,
      '{"tur":"hatirlatici","baslik":"Duruşma","tarih_saat":"2026-08-12T14:00:00+03:00",'
        + '"alinti":"duruşma 12.08.2026 saat 14:00","aciklama":"2. İş Mahkemesi",'
        + '"metin":"Duruşma günü\\n12.08.2026 saat 14:00","etiketler":["duruşma","iş mahkemesi"]}',
    );
    const reminders = createReminders({
      notesDir, send, targets: [PHONE],
      now: () => new Date('2026-08-12T13:00:00+03:00'), // henuz vakti gelmedi
      log: () => {},
    });

    const dosya = paylasilan(shareDir);
    const sonuc = await paylasimdanNot(dosya, extract, reminders);
    assert.ok(sonuc, 'not olusmali');
    assert.equal(sonuc.rec.tur, 'hatirlatici');

    // Not gercekten yazildi ve alan adlari tuttu (tarih_saat -> reminder_at).
    const found = reminders.scan();
    assert.equal(found.length, 1, 'bir hatirlatici olusmali');
    assert.equal(found[0].state, 'pending');
    assert.equal(found[0].at.toISOString(), new Date('2026-08-12T14:00:00+03:00').toISOString());
    assert.equal(found[0].source, path.basename(dosya));

    // Uc SENKRON cevap veriyor: "kuruldu" diye ayrica bildirim ATMAZ. Tarayici
    // atardi, cunku orada isi baslatan bir kullanici hareketi yoktu.
    assert.deepEqual(sent, [], 'not olustururken bildirim gitmemeli');

    // Vakti gelmeden tick gondermez.
    assert.equal((await reminders.tick()).sent, 0);
    assert.equal(sent.length, 0);

    // Vakti gelince gonderir ve bir daha gondermez.
    const due = createReminders({
      notesDir, send, targets: [PHONE],
      now: () => new Date('2026-08-12T14:00:01+03:00'),
      log: () => {},
    });
    assert.equal((await due.tick()).sent, 1);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].extras.kind, 'reminder');
    assert.equal(due.scan()[0].state, 'sent');
    assert.equal((await due.tick()).sent, 0, 'sent olan tekrar gonderilmemeli');

    // Govde arama icin yazildi: ekrandaki metin ve etiketler notta duruyor.
    const md = fs.readFileSync(found[0].path, 'utf8');
    assert.match(md, /## Ekrandaki metin/);
    assert.match(md, /> 12\.08\.2026 saat 14:00/);
    assert.match(md, /Etiketler: duruşma · iş mahkemesi/);
  });

  // 07.08 canli olcumu: model, sozlesme ekraninda tarih_saat'i SOZLESME
  // tarihine koydu (gecmis bir tarih). Zamanlayiciya girseydi bildirim aninda
  // calardi. Bu, boru hattinin ucundan olculmesi gereken bir davranis:
  // extract tarihi dusurur, dolayisiyla reminders onu hic gormez.
  it('bilgi kaydi gecmis tarihli olsa da bildirim calmaz', async () => {
    const { shareDir, notesDir } = tmp();
    const sent = [];
    const send = async (extras, opts) => { sent.push({ extras, opts }); return [{ serial: PHONE, ok: true }]; };
    const extract = modeliKur(
      shareDir,
      '{"tur":"bilgi","baslik":"Yazılım lisans sözleşmesi","tarih_saat":"2026-07-22T00:00:00+03:00",'
        + '"alinti":"Sözleşme Tarihi: 22.07.2026","aciklama":"fiyat teklifi",'
        + '"metin":"Toplam Tutarı: 1.000,00 TL","etiketler":["sözleşme"]}',
    );
    const reminders = createReminders({
      notesDir, send, targets: [PHONE],
      now: () => new Date('2026-08-07T16:00:00+03:00'), // sozlesme tarihinden SONRA
      log: () => {},
    });

    assert.ok(await paylasimdanNot(paylasilan(shareDir), extract, reminders));

    assert.deepEqual(reminders.scan(), [], 'bilgi kaydi zamanlayiciya girmemeli');
    assert.equal((await reminders.tick()).sent, 0, 'gecmis tarih bildirim caldirmamali');
    // Not yazildi ve tarih bilgisi govdede korundu.
    assert.equal(fs.readdirSync(notesDir).length, 1);
    const md = fs.readFileSync(path.join(notesDir, fs.readdirSync(notesDir)[0]), 'utf8');
    assert.doesNotMatch(md, /^reminder_at:/m);
    assert.doesNotMatch(md, /^reminder_state:/m, 'tarihsiz notta hayalet pending kalmamali');
    assert.match(md, /22\.07\.2026/, 'tarih govdede kaybolmamali');
    assert.deepEqual(sent, []);
  });

  // Tarayicidaki kural TERSINE dondu: orada "yok" cevabi kaydi dusururdu,
  // burada kullanicinin acik karari modelin yargisini yener.
  it('model "yok" dese bile not olusur', async () => {
    const { shareDir, notesDir } = tmp();
    const extract = modeliKur(shareDir, '{"tur":"yok","baslik":"LoL Katarina","metin":"skor tablosu"}');
    const reminders = createReminders({ notesDir, send: async () => [], targets: [PHONE], log: () => {} });

    const sonuc = await paylasimdanNot(paylasilan(shareDir), extract, reminders);
    assert.ok(sonuc, 'kullanici istedigi icin not yazilmali');
    assert.equal(sonuc.rec.tur, 'bilgi', '"yok" bilgiye cevrilmeli');
    assert.equal(fs.readdirSync(notesDir).length, 1);
  });

  it('basliksiz kayittan not cikmaz', async () => {
    const { shareDir, notesDir } = tmp();
    const extract = modeliKur(shareDir, '{"tur":"bilgi","baslik":"","metin":"bir sey"}');
    const reminders = createReminders({ notesDir, send: async () => [], targets: [PHONE], log: () => {} });

    assert.equal(await paylasimdanNot(paylasilan(shareDir), extract, reminders), null);
    assert.ok(!fs.existsSync(notesDir) || fs.readdirSync(notesDir).length === 0);
  });

  it('tarihsiz kayit not olur ama zamanlayiciya girmez', async () => {
    const { shareDir, notesDir } = tmp();
    const sent = [];
    const send = async (e, o) => { sent.push({ e, o }); return [{ serial: PHONE, ok: true }]; };
    const extract = modeliKur(
      shareDir,
      '{"tur":"bilgi","baslik":"Fatura öde","tarih_saat":null,"alinti":null,'
        + '"aciklama":"su faturası","metin":"Son ödeme: gişeden","etiketler":["fatura"]}',
    );
    const reminders = createReminders({ notesDir, send, targets: [PHONE], log: () => {} });

    assert.ok(await paylasimdanNot(paylasilan(shareDir), extract, reminders));

    assert.deepEqual(reminders.scan(), [], 'reminder_at yok, zamanlayiciya girmemeli');
    assert.equal(fs.readdirSync(notesDir).length, 1, 'not yine de yazilmali');
    assert.deepEqual(sent, []);
  });

  // Paylasilan sey metin de olabilir: uzanti .txt olsa da yol degismemeli.
  it('metin paylasimi da ayni yoldan gecer', async () => {
    const { shareDir, notesDir } = tmp();
    const extract = modeliKur(
      shareDir,
      '{"tur":"bilgi","baslik":"Kaynak bağlantısı","tarih_saat":null,"alinti":null,'
        + '"aciklama":"tarayıcıdan paylaşıldı","metin":"https://example.org/x","etiketler":[]}',
    );
    const reminders = createReminders({ notesDir, send: async () => [], targets: [PHONE], log: () => {} });

    const sonuc = await paylasimdanNot(
      paylasilan(shareDir, 'paylasilan-metin.txt'), extract, reminders,
    );
    assert.ok(sonuc);
    assert.equal(fs.readdirSync(notesDir).length, 1);
  });
});
