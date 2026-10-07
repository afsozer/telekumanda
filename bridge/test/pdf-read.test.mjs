import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  assembleMarkdown,
  bodyFontSize,
  findRepeatedLines,
  joinLines,
  normalizeRepeatKey,
  readPdfAsMarkdown,
  tableToMarkdown,
} from '../pdf-read.mjs';

// Çıkarıcının (pdf-read.py) ürettiği biçimde sahte sayfa kurar. Testler
// bilerek Python'a dokunmaz: markdown mantığı saf ve burada doğrulanır.
function line(t, extra = {}) {
  return { t, b: false, s: 10, x0: 50, y0: 100, x1: 500, y1: 112, ...extra };
}
function page(lines, { w = 595, h = 842, tables = [] } = {}) {
  return { w, h, lines, tables };
}

describe('pdf okuma — tekrarlayan üst/alt bilgi', () => {
  it('her sayfada aynı olan kenar satırlarını yakalar', () => {
    const pages = [1, 2, 3, 4].map(n => page([
      line('Hukuk Meslekleri Akademisi', { y0: 20, y1: 32 }),
      line(`Bu ${n}. sayfanın gövde metnidir.`, { y0: 300, y1: 312 }),
      line(`${100 + n}`, { y0: 820, y1: 832 }),
    ]));
    const repeated = findRepeatedLines(pages);
    assert.ok(repeated.has(normalizeRepeatKey('Hukuk Meslekleri Akademisi')));
    // Sayfa numaraları rakam maskesiyle aynı anahtara iner.
    assert.ok(repeated.has(normalizeRepeatKey('101')));
    assert.ok(!repeated.has(normalizeRepeatKey('Bu 1. sayfanın gövde metnidir.')));
  });

  it('sayfa ortasındaki tekrar üst bilgi sayılmaz', () => {
    const pages = [1, 2, 3, 4].map(() => page([
      line('TEKRAR EDEN ORTA SATIR', { y0: 400, y1: 412 }),
    ]));
    assert.equal(findRepeatedLines(pages).size, 0);
  });

  it('rakamları maskeler, Türkçe büyük-küçük harfi doğru katlar', () => {
    assert.equal(normalizeRepeatKey('Sayfa 12'), normalizeRepeatKey('Sayfa 345'));
    assert.equal(normalizeRepeatKey('İCRA'), normalizeRepeatKey('icra'));
  });
});

describe('pdf okuma — satır birleştirme', () => {
  it('satır sonu tiresini birleştirir', () => {
    assert.deepEqual(joinLines(['vergi kanunlarında gös-', 'terilen gün ve zaman.']),
      ['vergi kanunlarında gösterilen gün ve zaman.']);
  });

  it('gerçek birleşik sözcüğü bozmaz', () => {
    // Sonraki satır BÜYÜK harfle başlıyorsa tire metnin parçasıdır.
    assert.deepEqual(joinLines(['Türk-', 'Alman ortaklığı.']),
      ['Türk- Alman ortaklığı.']);
  });

  it('noktalama ile biten satır paragrafı kapatır', () => {
    assert.deepEqual(joinLines(['Birinci cümle bitti.', 'İkinci paragraf başladı']),
      ['Birinci cümle bitti.', 'İkinci paragraf başladı']);
  });

  it('cümle ortasında kırılan satırları boşlukla ekler', () => {
    assert.deepEqual(joinLines(['ihalenin hangi gün ve', 'saatte tamamlandığı']),
      ['ihalenin hangi gün ve saatte tamamlandığı']);
  });

  it('kalın satırlar arasındaki bölünmüş sözcüğü de birleştirir', () => {
    // Kalın satırlar `**…**` ile sarılı geldiği için tire satırın SONUNDA
    // değil, `**`'dan önce duruyor; sınama sarmalı atlamazsa sözcük bölük kalır.
    assert.deepEqual(joinLines(['**vergi dairesi-**', '**ne bildirilmesi**']),
      ['**vergi dairesine bildirilmesi**']);
  });

  it('roma rakamlı öncülleri ayrı madde sayar', () => {
    assert.deepEqual(joinLines(['I- Zirai Mahsuller', 'II- Hayvanlar', 'III- Demirbaşlar']),
      ['I- Zirai Mahsuller', 'II- Hayvanlar', 'III- Demirbaşlar']);
  });

  it('madde işaretli satır kendi paragrafında kalır', () => {
    assert.deepEqual(joinLines(['giriş cümlesi', '- birinci madde', '- ikinci madde']),
      ['giriş cümlesi', '- birinci madde', '- ikinci madde']);
  });

  it('ok imli maddeleri ayrı paragraf sayar', () => {
    // Hakimlik hazırlık kitaplarının standart madde imi "→"; tanınmayınca bütün
    // maddeler bir önceki paragrafa akıyordu.
    assert.deepEqual(
      joinLines(['giriş', '→ birinci madde', '→ ikinci madde']),
      ['giriş', '→ birinci madde', '→ ikinci madde'],
    );
  });

  it('düz satırın ardından gelen kalın satır yeni paragraf açar', () => {
    // Regresyon: kalın bilgi kutusu bir önceki madde metnine yapışıyordu
    // ("…getirilmiştir **Diyanet İşleri Başkanlığı 1924…**"). Dil kontrolü bunu
    // "nokta eksik" diye bildirince yakalandı — kalınlık değişimi görsel bir
    // kırılmadır, cümle devamı değil.
    assert.deepEqual(
      joinLines(['siyasi partilere hazine yardımı getirilmiştir', '**Diyanet İşleri Başkanlığı 1924**']),
      ['siyasi partilere hazine yardımı getirilmiştir', '**Diyanet İşleri Başkanlığı 1924**'],
    );
  });
});

describe('pdf okuma — madde biçimlendirme', () => {
  const page = lines => ({ pages: [{ w: 600, h: 800, lines, tables: [] }] });
  const line = (t, extra = {}) => ({ t, b: false, s: 10, x0: 50, y0: 100, x1: 500, y1: 112, ...extra });

  it('yinelenen ok imini tek maddeye indirir', () => {
    // Çıkarıcı bazı kitaplarda simgeyi iki ayrı metin parçası olarak veriyor.
    const { markdown } = assembleMarkdown(page([line('→ → Bakanlar Kuruluna yetki verilmiştir')]));
    assert.equal(markdown, '- Bakanlar Kuruluna yetki verilmiştir');
  });

  it('kalın maddede sarmalı bozmaz', () => {
    const { markdown } = assembleMarkdown(page([line('→ önemli not', { b: true })]));
    assert.equal(markdown, '- **önemli not**');
  });

  it('numaralı maddeye ikinci bir im eklemez', () => {
    const { markdown } = assembleMarkdown(page([line('28. 1961 Anayasasında yapılan değişiklikler')]));
    assert.equal(markdown, '28. 1961 Anayasasında yapılan değişiklikler');
  });
});

describe('pdf okuma — tablo', () => {
  it('boru işaretini kaçırır ve satır sonunu <br> yapar', () => {
    const md = tableToMarkdown([['a|b', 'c\nd'], ['e', 'f']]);
    assert.ok(md.includes('a\\|b'));
    assert.ok(md.includes('c<br>d'));
  });

  it('eksik hücreli satırları tamamlar', () => {
    const md = tableToMarkdown([['a', 'b', 'c'], ['x']]);
    const rows = md.split('\n');
    assert.equal(rows[0], '| a | b | c |');
    assert.equal(rows[1], '| --- | --- | --- |');
    assert.equal(rows[2], '| x |  |  |');
  });

  it('boş tabloda boş dize döner', () => {
    assert.equal(tableToMarkdown([]), '');
  });
});

describe('pdf okuma — markdown kurulumu', () => {
  it('tabloyu içindeki ilk satırın yerinde basar, metni tekrarlamaz', () => {
    const doc = {
      pages: [page([
        line('Giriş paragrafı.', { y0: 100, y1: 112 }),
        line('Ad Soyad', { y0: 200, y1: 212 }),
        line('Tutar', { y0: 220, y1: 232 }),
        line('Kapanış paragrafı.', { y0: 400, y1: 412 }),
      ], {
        tables: [{ x0: 40, y0: 190, x1: 520, y1: 240, rows: [['Ad Soyad', 'Tutar'], ['A', '5']] }],
      })],
    };
    const { markdown } = assembleMarkdown(doc);
    assert.ok(markdown.includes('| Ad Soyad | Tutar |'));
    assert.ok(markdown.indexOf('Giriş paragrafı.') < markdown.indexOf('| Ad Soyad'));
    assert.ok(markdown.indexOf('| Ad Soyad') < markdown.indexOf('Kapanış paragrafı.'));
    // Tablo hücreleri düz metin paragrafı olarak İKİNCİ kez görünmemeli.
    assert.equal(markdown.match(/Tutar/g).length, 1);
  });

  it('hiç metin satırı olmayan tabloyu sayfa sonuna ekler', () => {
    const doc = {
      pages: [page([line('Sadece paragraf.', { y0: 100, y1: 112 })], {
        tables: [{ x0: 40, y0: 600, x1: 520, y1: 700, rows: [['K1', 'K2']] }],
      })],
    };
    const { markdown } = assembleMarkdown(doc);
    assert.ok(markdown.includes('| K1 | K2 |'));
    assert.ok(markdown.indexOf('Sadece paragraf.') < markdown.indexOf('| K1'));
  });

  it('tekrarlayan üst bilgiyi ve yalnız sayfa numarasını atar', () => {
    const doc = {
      pages: [1, 2, 3, 4].map(n => page([
        line('THEMIS İcra ve İflas Hukuku', { y0: 20, y1: 32 }),
        line(`Gövde metni ${n} burada.`, { y0: 300, y1: 312 }),
        line(`${100 + n}`, { y0: 820, y1: 832 }),
      ])),
    };
    const { markdown } = assembleMarkdown(doc);
    assert.ok(!markdown.includes('THEMIS'));
    assert.ok(!/^\s*10\d\s*$/m.test(markdown));
    assert.ok(markdown.includes('Gövde metni 3 burada.'));
  });

  it('kalın satırı işaretler ve sayfa başlangıçlarını bildirir', () => {
    const doc = {
      pages: [
        page([line('Birinci sayfa gövdesi.', { y0: 300, y1: 312 })]),
        page([line('İkinci sayfa gövdesi.', { y0: 300, y1: 312, b: true })]),
      ],
    };
    const { markdown, pageStarts } = assembleMarkdown(doc);
    assert.equal(pageStarts.length, 2);
    assert.equal(pageStarts[0], 0);
    assert.equal(markdown.slice(pageStarts[1]).startsWith('**İkinci sayfa gövdesi.**'), true);
  });

  it('komşu sayfaların kalın satırları sayfa konumlarını kaydırmaz', () => {
    // Regresyon: kalın birleştirme ("**a** **b**" → "**a b**") önce TÜM
    // markdown kurulduktan sonra tek seferde yapılıyordu. İki ayrı sayfanın
    // kalın satırlarını da birleştirip dizgiyi kısaltıyor, önceden hesaplanan
    // pageStarts konumları kayıyordu — sayfa metnin ortasından başlıyordu.
    const doc = {
      pages: [
        page([line('Birinci sayfanın son satırı.', { y0: 300, y1: 312, b: true })]),
        page([line('İkinci sayfanın ilk satırı.', { y0: 300, y1: 312, b: true })]),
        page([line('Üçüncü sayfa gövdesi.', { y0: 300, y1: 312 })]),
      ],
    };
    const { markdown, pageStarts } = assembleMarkdown(doc);
    assert.ok(markdown.slice(pageStarts[1]).startsWith('**İkinci sayfanın ilk satırı.**'));
    assert.ok(markdown.slice(pageStarts[2]).startsWith('Üçüncü sayfa gövdesi.'));
  });

  it('soru şıklarını ayrı maddelere böler', () => {
    const doc = {
      pages: [page([
        line('Aşağıdakilerden hangisi doğrudur?', { y0: 100, y1: 112 }),
        line('A) Birinci şık', { y0: 120, y1: 132 }),
        line('B) İkinci şık', { y0: 140, y1: 152 }),
        line('C) Üçüncü şık', { y0: 160, y1: 172 }),
      ])],
    };
    const { markdown } = assembleMarkdown(doc);
    const satirlar = markdown.split('\n').filter(Boolean);
    assert.ok(satirlar.includes('A) Birinci şık'));
    assert.ok(satirlar.includes('B) İkinci şık'));
    assert.ok(!markdown.includes('A) Birinci şık B) İkinci şık'));
  });

  it('sayfası olmayan belgede boş markdown üretir, patlamaz', () => {
    assert.deepEqual(assembleMarkdown({ pages: [] }), { markdown: '', pageStarts: [] });
    assert.deepEqual(assembleMarkdown(null), { markdown: '', pageStarts: [] });
  });
});

describe('pdf okuma — gövde yazı boyutu', () => {
  it('en çok karakteri taşıyan boyutu seçer', () => {
    const doc = [page([
      line('KISA BAŞLIK', { s: 20 }),
      line('Uzun uzun gövde metni burada akıyor ve sayfanın çoğunu kaplıyor.', { s: 10 }),
      line('Yine gövde metni, aynı boyutta devam ediyor.', { s: 10 }),
    ])];
    assert.equal(bodyFontSize(doc), 10);
  });
});

describe('pdf okuma — dosya katmanı', () => {
  it('olmayan dosyada sebep bildirir, throw etmez', async () => {
    const result = await readPdfAsMarkdown(path.join(os.tmpdir(), 'yok-boyle-bir-dosya.pdf'));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'dosya bulunamadı');
  });

  it('sonucu önbelleğe yazar ve ikinci çağrıda oradan okur', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-pdfread-'));
    try {
      const file = path.join(dir, 'sahte.pdf');
      fs.writeFileSync(file, 'bu bir PDF degil');
      const cacheDir = path.join(dir, 'onbellek');
      // PDF olmadığı için ok:false döner; önemli olan sonucun ÖNBELLEĞE
      // yazılması — taranmış/bozuk belge her açılışta yeniden denenmesin.
      const first = await readPdfAsMarkdown(file, { cacheDir });
      assert.equal(first.ok, false);
      const cached = fs.readdirSync(cacheDir).filter(n => n.endsWith('.json'));
      assert.equal(cached.length, 1);

      // Önbellek dosyasını işaretleyip ikinci çağrının oradan geldiğini kanıtla.
      const marked = { ok: false, reason: 'onbellekten' };
      fs.writeFileSync(path.join(cacheDir, cached[0]), JSON.stringify(marked));
      const second = await readPdfAsMarkdown(file, { cacheDir });
      assert.equal(second.reason, 'onbellekten');
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});
