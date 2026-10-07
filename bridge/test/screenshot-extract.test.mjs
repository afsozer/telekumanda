import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createScreenshotExtract } from '../screenshot-extract.mjs';

// 2026-08-07 11:03:54 +03:00 — Screenshot_20260807_110354_... adindan
// cozulmesi beklenen an.
const CAPTURED_AT = new Date('2026-08-07T11:03:54+03:00');
const FILE = 'Screenshot_20260807_110354_com_example_app.jpg';
const ADD_DIR = 'C:\\Users\\x\\screenshots';
const DEFAULT_OUT = '{"tur":"bilgi","baslik":"Test","tarih_saat":null,"alinti":null,"aciklama":"aciklama","metin":"","etiketler":[]}';

// runCommand enjeksiyonu: (file, args) => Promise<{err, out, errOut}>
// Cagrilar kaydedilir ki komut argumanlari incelenebilsin. Gercek agy
// cagrilmaz.
function makeClient({ out, err, ...opts } = {}) {
  const logs = [];
  const calls = [];
  const client = createScreenshotExtract({
    addDir: ADD_DIR,
    runCommand: async (file, args) => {
      calls.push({ file, args });
      if (err) return { err, out: '', errOut: String(err?.message || err) };
      return { err: null, out: out ?? DEFAULT_OUT, errOut: '' };
    },
    log: (line) => logs.push(line),
    ...opts,
  });
  return { client, logs, calls };
}

describe('screenshot-extract', () => {
  it('temiz JSON ciktisi dogru parse edilir', async () => {
    const { client } = makeClient({
      out: '{"tur":"hatirlatici","baslik":"Durusma","tarih_saat":"2026-08-12T14:00:00+03:00","alinti":"durusma 12.08.2026 14:00","aciklama":"2. Is Mahkemesi","metin":"durusma gunu 12.08.2026","etiketler":["durusma","is mahkemesi"]}',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.tur, 'hatirlatici');
    assert.equal(r.baslik, 'Durusma');
    assert.equal(r.tarih_saat, '2026-08-12T14:00:00+03:00');
    assert.equal(r.alinti, 'durusma 12.08.2026 14:00');
    assert.equal(r.aciklama, '2. Is Mahkemesi');
    assert.equal(r.metin, 'durusma gunu 12.08.2026');
    assert.deepEqual(r.etiketler, ['durusma', 'is mahkemesi']);
  });

  it('markdown fence ciktisi da parse edilir', async () => {
    const { client } = makeClient({
      out: '```json\n{"tur":"bilgi","baslik":"Fence","tarih_saat":null,"alinti":null,"aciklama":null}\n```',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.baslik, 'Fence');
    assert.equal(r.tarih_saat, null);
  });

  it('etrafinda laf olan cikti icinden JSON cikarilir', async () => {
    const { client } = makeClient({
      out: 'İşte sonuç: {"tur":"bilgi","baslik":"Laf","tarih_saat":null,"alinti":null,"aciklama":null} umarım olur',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.baslik, 'Laf');
  });

  it('bozuk JSON -> null, throw yok', async () => {
    const { client, logs } = makeClient({ out: '{"tur": "bilgi", "baslik": ' });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r, null);
    assert.ok(logs.some((l) => l.includes('JSON')));
  });

  it('model hatasi -> null', async () => {
    const { client, logs } = makeClient({ out: '{"error":"cannot read image"}' });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r, null);
    assert.ok(logs.some((l) => l.includes('cannot read image')));
  });

  it('tur "yok" -> null (oyun/mem ekrani not uretmez)', async () => {
    const { client } = makeClient({
      out: '{"tur":"yok","baslik":"LoL Katarina","tarih_saat":null,"alinti":null,"aciklama":"oyun videosu","metin":"","etiketler":["oyun"]}',
    });
    assert.equal(await client.extract(FILE, CAPTURED_AT), null);
  });

  it('taninmayan tur -> null', async () => {
    const { client, logs } = makeClient({
      out: '{"tur":"belki","baslik":"X","tarih_saat":null}',
    });
    assert.equal(await client.extract(FILE, CAPTURED_AT), null);
    assert.ok(logs.some((l) => l.includes('tur')));
  });

  // 07.08 canli olcumu: high, sozlesme ekraninda tarih_saat'i SOZLESME
  // tarihine (22.07, gecmis) koydu. Zamanlayiciya girseydi bildirim aninda
  // calardi. Tarih yalniz `hatirlatici` turunde tasinir.
  it('tur "bilgi" ise tarih_saat zamanlayiciya tasinmaz', async () => {
    const { client } = makeClient({
      out: '{"tur":"bilgi","baslik":"Sozlesme","tarih_saat":"2026-07-22T00:00:00+03:00","alinti":"Sozlesme Tarihi: 22.07.2026","aciklama":"fiyat teklifi","metin":"Toplam Tutari: 1.000,00 TL","etiketler":["sozlesme"]}',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.tur, 'bilgi');
    assert.equal(r.tarih_saat, null, 'bilgi kaydi zamanlanmamali');
    // Tarih bilgisi kaybolmaz: alinti ve metin govdeye giriyor.
    assert.match(r.alinti, /22\.07\.2026/);
  });

  it('eski actionable bicimi de kabul edilir', async () => {
    const { client } = makeClient({
      out: '{"actionable":true,"baslik":"Eski","tarih_saat":"2026-08-12T14:00:00+03:00","alinti":null,"aciklama":null}',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.tur, 'hatirlatici');
    assert.equal(r.tarih_saat, '2026-08-12T14:00:00+03:00');

    const { client: c2 } = makeClient({ out: '{"actionable":false,"baslik":"yok"}' });
    assert.equal(await c2.extract(FILE, CAPTURED_AT), null);
  });

  it('gecersiz tarih_saat -> kayit doner, alan null olur', async () => {
    const { client, logs } = makeClient({
      // Ay 13: V8 bu girdiyi NaN yapar (02-30 gibi gun kaydiranlar parse olur).
      out: '{"tur":"hatirlatici","baslik":"TarihSiz","tarih_saat":"2026-13-01T10:00:00+03:00","alinti":null,"aciklama":null}',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.baslik, 'TarihSiz');
    assert.equal(r.tarih_saat, null);
    assert.ok(logs.some((l) => l.includes('tarih_saat')));
  });

  it('asiri uzun alanlar kirpilir, etiket sayisi tavanlanir', async () => {
    const { client } = makeClient({
      out: JSON.stringify({
        tur: 'bilgi',
        baslik: 'B'.repeat(100),
        tarih_saat: null,
        alinti: 'A'.repeat(200),
        aciklama: 'C'.repeat(600),
        metin: 'M'.repeat(1200),
        etiketler: ['a', 'b', 'c', 'd', 'e', 'f'],
      }),
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r.baslik.length, 60);
    assert.equal(r.alinti.length, 120);
    assert.equal(r.aciklama.length, 400);
    assert.equal(r.metin.length, 800);
    assert.deepEqual(r.etiketler, ['a', 'b', 'c', 'd']);
  });

  it('etiketler dizi degilse bos dizi olur, metin yoksa bos string', async () => {
    const { client } = makeClient({
      out: '{"tur":"bilgi","baslik":"X","etiketler":"a,b"}',
    });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.deepEqual(r.etiketler, []);
    assert.equal(r.metin, '');
  });

  it('surec hatasi -> null, throw yok', async () => {
    const { client, logs } = makeClient({ err: new Error('agy patladi') });
    const r = await client.extract(FILE, CAPTURED_AT);
    assert.equal(r, null);
    assert.ok(logs.some((l) => l.includes('hatasi')));
  });

  it('parseCapturedAt dogru tarih uretir, uymayan adda null', () => {
    const { client } = makeClient();
    const d = client.parseCapturedAt(FILE);
    assert.equal(d.toISOString(), '2026-08-07T08:03:54.000Z');
    // Tam yol verilse de sadece dosya adi kullanilir.
    assert.equal(
      client.parseCapturedAt('C:\\pics\\' + FILE).toISOString(),
      '2026-08-07T08:03:54.000Z',
    );
    assert.equal(client.parseCapturedAt('IMG_20260807_110354.jpg'), null);
    assert.equal(client.parseCapturedAt(''), null);
  });

  it('komut izole oturum deposu, --add-dir, --mode plan ve izin bayragini kullanir', async () => {
    const { client, calls } = makeClient();
    await client.extract(FILE, CAPTURED_AT);
    const { file, args } = calls[0];
    assert.equal(file, 'agy');
    // Arka plan siniflandirmasi normal antigravity-cli deposuna yazilirsa her
    // ekran goruntusu kullanicinin AGY sohbet gecmisinde gorunur.
    assert.ok(
      args.includes('--app_data_dir=agentbridge-screenshot-notes'),
      'sistem isi kullanici AGY oturum deposundan izole olmali',
    );
    assert.ok(args.includes('--add-dir'), '--add-dir hic atlanmamali');
    assert.equal(args[args.indexOf('--add-dir') + 1], ADD_DIR);
    assert.ok(args.includes('--mode'));
    assert.equal(args[args.indexOf('--mode') + 1], 'plan');
    // Bu bayrak olmadan agy goruntuyu acmak icin izin isteyip headless'ta
    // otomatik reddediliyor ve extract HER goruntude sessizce null donuyor.
    assert.ok(
      args.includes('--dangerously-skip-permissions'),
      'izin bayragi atlanirsa cikarim tamamen sessizce oluyor',
    );
    assert.ok(args.includes('--print-timeout'));
    assert.equal(args[args.indexOf('--print-timeout') + 1], '2m');
  });

  // Dizgeyi bilerek sabit tutuyoruz (sabiti import etmiyoruz): modeli degistiren
  // kisi bu testi de guncellemek zorunda kalsin, degisiklik sessizce gecmesin.
  it('varsayilan model 3.7 medium', async () => {
    const { client, calls } = makeClient();
    await client.extract(FILE, CAPTURED_AT);
    const args = calls[0].args;
    assert.equal(args[args.indexOf('--model') + 1], 'gemini-3.7-flash-medium');
  });

  it('buildPrompt capturedAt ISO degerini ve uc sinifi icerir', () => {
    const { client } = makeClient();
    const prompt = client.buildPrompt(FILE, CAPTURED_AT);
    assert.ok(prompt.includes('2026-08-07T11:03:54+03:00'));
    assert.ok(prompt.includes(FILE));
    assert.ok(prompt.includes('+03:00'));
    for (const tur of ['hatirlatici', 'bilgi', 'yok']) {
      assert.ok(prompt.includes(`"${tur}"`), `${tur} sinifi promptta olmali`);
    }
  });

  it('capturedAt verilmezse dosya adindan cekim zamani cozulur', async () => {
    const { client, calls } = makeClient();
    await client.extract(FILE); // capturedAt yok
    const args = calls[0].args;
    const prompt = args[args.indexOf('--print') + 1];
    assert.ok(prompt.includes('2026-08-07T11:03:54+03:00'));
  });
});
