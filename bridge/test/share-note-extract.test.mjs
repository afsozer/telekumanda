import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createScreenshotExtract } from '../screenshot-extract.mjs';

// Paylas menusundeki "Not ekle" yolu: cikarim ayni, SINIFLANDIRMA yok.
// Kullanici paylas tusuna basarak "bu kayda deger" demis oluyor; otomatik
// taramanin pahali kismi (297 goruntu tarayip 3 not) tam olarak bu soruydu.
const FILE = 'paylasilan-metin.txt';
const ADD_DIR = 'C:\\Users\\x\\share-notes';
const ELLE = { siniflandir: false };

function makeClient({ out, err } = {}) {
  const calls = [];
  const logs = [];
  const client = createScreenshotExtract({
    addDir: ADD_DIR,
    runCommand: async (file, args) => {
      calls.push({ file, args });
      if (err) return { err, out: '', errOut: String(err?.message || err) };
      return { err: null, out, errOut: '' };
    },
    log: (line) => logs.push(line),
  });
  return { client, calls, logs };
}

const prompt = calls => calls[0].args[calls[0].args.indexOf('--print') + 1];

describe('paylasimdan not', () => {
  it('"yok" cevabi kaydi DUSURMEZ, bilgiye cevrilir', async () => {
    // Kullanicinin acik karari modelin yargisini yener. Otomatik yolda ayni
    // cevap kaydi dusururdu; buradaki fark bilerek.
    const { client } = makeClient({
      out: '{"tur":"yok","baslik":"Bir sey","tarih_saat":null,"aciklama":"aciklama","metin":"metin","etiketler":[]}',
    });
    const r = await client.extract(FILE, null, ELLE);
    assert.ok(r);
    assert.equal(r.tur, 'bilgi');
  });

  it('otomatik taramada "yok" hala kaydi dusurur', async () => {
    const { client } = makeClient({
      out: '{"tur":"yok","baslik":"Bir sey","tarih_saat":null,"aciklama":"a","metin":"","etiketler":[]}',
    });
    assert.equal(await client.extract(FILE, null), null);
  });

  it('elle tetiklenen promptta "yok" secenegi hic sunulmaz', async () => {
    const { client, calls } = makeClient({
      out: '{"tur":"bilgi","baslik":"T","tarih_saat":null,"aciklama":"a","metin":"","etiketler":[]}',
    });
    await client.extract(FILE, null, ELLE);
    const p = prompt(calls);
    assert.ok(p.includes('Never answer "yok"'));
    assert.ok(!p.includes('"yok": nothing worth keeping'));
    assert.ok(p.includes('EXPLICITLY asked to save this as a note'));
  });

  it('elle tetiklenen prompt goruntu disi icerigi de kabul eder', async () => {
    const { client, calls } = makeClient({
      out: '{"tur":"bilgi","baslik":"T","tarih_saat":null,"aciklama":"a","metin":"","etiketler":[]}',
    });
    await client.extract(FILE, null, ELLE);
    assert.ok(prompt(calls).includes('It may be an image, a screenshot or a text file.'));
  });

  it('otomatik tarama promptu AYNEN korunur', async () => {
    // Bu yolun davranisi 297 goruntu uzerinde olculdu; kelime degisikligi
    // olcumu gecersiz kilar. Elle yol ayri promptla ilerliyor.
    const { client, calls } = makeClient({
      out: '{"tur":"bilgi","baslik":"T","tarih_saat":null,"aciklama":"a","metin":"","etiketler":[]}',
    });
    await client.extract(FILE, null);
    const p = prompt(calls);
    assert.ok(p.includes('Read the image file named'));
    assert.ok(p.includes('- "yok": nothing worth keeping.'));
    assert.ok(!p.includes('EXPLICITLY asked'));
  });

  it('tarihli kayit elle yolda da hatirlatici olur', async () => {
    const { client } = makeClient({
      out: '{"tur":"hatirlatici","baslik":"Durusma","tarih_saat":"2026-09-01T14:00:00+03:00","alinti":"01.09 14:00","aciklama":"a","metin":"m","etiketler":["durusma"]}',
    });
    const r = await client.extract(FILE, null, ELLE);
    assert.equal(r.tur, 'hatirlatici');
    assert.equal(r.tarih_saat, '2026-09-01T14:00:00+03:00');
  });

  it('bilgi kaydinda tarih zamanlayiciya girmez', async () => {
    // Otomatik yoldaki kural burada da gecerli: gecmis tarihli bir fatura
    // aninda bildirim caldirmasin.
    const { client } = makeClient({
      out: '{"tur":"bilgi","baslik":"Fatura","tarih_saat":"2026-07-22T10:00:00+03:00","aciklama":"a","metin":"m","etiketler":[]}',
    });
    const r = await client.extract(FILE, null, ELLE);
    assert.equal(r.tarih_saat, null);
  });

  it('bos basliktan kayit cikmaz', async () => {
    const { client } = makeClient({
      out: '{"tur":"bilgi","baslik":"","tarih_saat":null,"aciklama":"a","metin":"m","etiketler":[]}',
    });
    assert.equal(await client.extract(FILE, null, ELLE), null);
  });

  it('model hatasinda null doner, throw etmez', async () => {
    const { client } = makeClient({ out: '{"error":"cannot read image"}' });
    assert.equal(await client.extract(FILE, null, ELLE), null);
  });
});
