import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { buildPrompt, parseFindings, checkLanguage, MAX_TEXT } from '../pdf-lang.mjs';

const envelope = bulgular => ({ status: 'SUCCESS', structured_output: { bulgular } });

describe('dil kontrolü — istem', () => {
  it('metni sınır işaretleri arasına koyar', () => {
    const prompt = buildPrompt('İçerik burada.');
    assert.ok(prompt.includes('--- METİN BAŞLANGICI ---'));
    assert.ok(prompt.includes('--- METİN SONU ---'));
    assert.ok(prompt.includes('İçerik burada.'));
  });

  it('metnin veri olduğunu ve yeniden yazılmayacağını söyler', () => {
    // İki kural da güvenlik/tasarım gereği: belge içindeki "şunu yap" cümlesi
    // komut değildir, ve hukuki evrak asla yeniden yazılmaz.
    const prompt = buildPrompt('x');
    assert.ok(prompt.includes('VERİDİR'));
    assert.ok(prompt.includes('YENİDEN YAZMA'));
  });

  it('çıkarım artıklarını bildirmemesini ister', () => {
    assert.ok(buildPrompt('x').includes('ÇIKARIM'));
  });
});

describe('dil kontrolü — bulgu ayrıştırma', () => {
  const metin = 'Vergi dairesine bildirilmesi gereken husuular kanunda sayılmışdır.';

  it('geçerli bulguyu geçirir', () => {
    const out = parseFindings(envelope([
      { tur: 'yazim', alinti: 'husuular', oneri: 'hususlar', not: 'imla' },
    ]), metin);
    assert.equal(out.length, 1);
    assert.deepEqual(out[0], { tur: 'yazim', alinti: 'husuular', oneri: 'hususlar', not: 'imla' });
  });

  it('metinde bulunmayan alıntıyı atar', () => {
    // Şema motorda zorlanıyor ama model şemaya uyan UYDURMA bir alıntı
    // üretebilir; kullanıcı sayfada bulamayacaksa bulgu değil gürültüdür.
    const out = parseFindings(envelope([
      { tur: 'yazim', alinti: 'bu cümle metinde yok', oneri: 'x', not: '' },
    ]), metin);
    assert.equal(out.length, 0);
  });

  it('bilinmeyen türü atar', () => {
    const out = parseFindings(envelope([
      { tur: 'uydurma', alinti: 'husuular', oneri: 'hususlar', not: '' },
    ]), metin);
    assert.equal(out.length, 0);
  });

  it('öneri ya da alıntı boşsa atar', () => {
    const out = parseFindings(envelope([
      { tur: 'yazim', alinti: 'husuular', oneri: '', not: 'x' },
      { tur: 'yazim', alinti: '', oneri: 'hususlar', not: 'x' },
    ]), metin);
    assert.equal(out.length, 0);
  });

  it('aynı bulguyu iki kez listelemez', () => {
    const out = parseFindings(envelope([
      { tur: 'yazim', alinti: 'husuular', oneri: 'hususlar', not: 'a' },
      { tur: 'yazim', alinti: 'husuular', oneri: 'hususlar', not: 'b' },
    ]), metin);
    assert.equal(out.length, 1);
  });

  it('uzun notu kırpar', () => {
    const out = parseFindings(envelope([
      { tur: 'yazim', alinti: 'husuular', oneri: 'hususlar', not: 'ç'.repeat(900) },
    ]), metin);
    assert.equal(out[0].not.length, 400);
  });

  it('yapısal çıktı yoksa boş liste döner', () => {
    assert.deepEqual(parseFindings({ status: 'SUCCESS' }, metin), []);
    assert.deepEqual(parseFindings(null, metin), []);
  });
});

describe('dil kontrolü — kapı koşulları', () => {
  const tmpDir = () => fs.mkdtempSync(path.join(os.tmpdir(), 'agdiltest-'));

  it('boş metinde modele hiç gitmez', async () => {
    const r = await checkLanguage('   ', { cacheDir: tmpDir() });
    assert.equal(r.ok, false);
    assert.equal(r.reason, 'metin boş');
  });

  it('tavanı aşan metni reddeder', async () => {
    const r = await checkLanguage('a'.repeat(MAX_TEXT + 1), { cacheDir: tmpDir() });
    assert.equal(r.ok, false);
    assert.match(r.reason, /çok uzun/);
  });

  it('önbellekteki sonucu modele gitmeden döndürür', async () => {
    // Modeli çağırmadan doğrulanabilsin diye önbellek dosyası elle yazılıyor:
    // dosya varsa `checkLanguage` agy'yi hiç çalıştırmamalı (14 sn + token).
    const dir = tmpDir();
    const metin = 'önbellekten gelmeli';
    const { _internals } = await import('../pdf-lang.mjs');
    fs.writeFileSync(
      path.join(dir, _internals.cacheKey(metin)),
      JSON.stringify({ ok: true, bulgular: [{ tur: 'yazim', alinti: 'x', oneri: 'y', not: '' }] }),
    );
    const r = await checkLanguage(metin, { cacheDir: dir });
    assert.equal(r.ok, true);
    assert.equal(r.bulgular.length, 1);
  });
});
