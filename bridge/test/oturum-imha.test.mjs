import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { createOturumImhaManager, imhaOzetMesaji, sonJsonSatiri } from '../oturum-imha.mjs';

// Betik insan okunur metin + son satirda JSON basiyor; ayristirici SADECE
// son gecerli JSON satirini almali, aradaki suslu parantezli metne kanmamali.
test('sonJsonSatiri son gecerli JSON satirini alir', () => {
  const cikti = [
    'opencode oturum imha - mod: RAPOR',
    '  - kalinti {bozuk json',
    '{"mod":"rapor","oluCanli":2}',
    'RAPOR MODU - hicbir sey degismedi.',
  ].join('\n');
  assert.deepEqual(sonJsonSatiri(cikti), { mod: 'rapor', oluCanli: 2 });
  assert.equal(sonJsonSatiri('hic json yok'), null);
});

test('imhaOzetMesaji rapor ve sonuc kipini ayirir', () => {
  assert.equal(imhaOzetMesaji({ mod: 'rapor', oluCanli: 0, oluArtik: 0 }),
    'Temiz — kalinti yok');
  assert.equal(imhaOzetMesaji({ mod: 'rapor', oluCanli: 3, oluArtik: 0 }),
    '3 silinmis oturum izi hala okunabilir');
  // Artik dosyalardaki sohbetler varsayilan imhaya girmiyor: kart bunu
  // "temiz" diye yutmamali ama alarm da vermemeli.
  assert.equal(imhaOzetMesaji({ mod: 'rapor', oluCanli: 0, oluArtik: 22 }),
    'Temiz · eski dosyalarda 22 sohbet duruyor');
  assert.equal(imhaOzetMesaji({ mod: 'uygula', temiz: true }), 'Temiz — kalinti yok');
  assert.equal(imhaOzetMesaji({ mod: 'uygula', temiz: false, dogrulama: { kalanOturum: 2 } }),
    '2 kalinti silinemedi');
});

test('betik yoksa uc disabled doner, spawn denenmez', async () => {
  let spawned = 0;
  const manager = createOturumImhaManager(
    { script: 'C:/yok/oturum-imha.ps1' },
    { spawn: () => { spawned++; }, existsSync: () => false },
  );
  const durum = await manager.report();
  assert.equal(durum.enabled, false);
  assert.match(durum.message, /bulunamadi/);
  const r = manager.run({});
  assert.equal(r.ok, false);
  assert.equal(spawned, 0);
});

function fakeSpawn(satirlar, kod = 0, kayit = null) {
  return (exe, args) => {
    if (kayit) kayit.push({ exe, args });
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => {};
    queueMicrotask(() => {
      for (const s of satirlar) child.stdout.write(s + '\n');
      // Gercek ChildProcess ikisini de yayar. Yonetici 'exit'i dinliyor:
      // betik 4096 servisini kaldirinca torun surec stdout borusunu miras
      // aliyor ve 'close' hic gelmeyebiliyor (30.09.2026 canli tuzak).
      child.emit('exit', kod);
      child.emit('close', kod);
    });
    return child;
  };
}

test('rapor JSON ozeti okunur ve TTL icinde yeniden kosmaz', async () => {
  const cagrilar = [];
  const manager = createOturumImhaManager(
    { script: 'C:/x/oturum-imha.ps1' },
    {
      spawn: fakeSpawn(['insan metni', '{"mod":"rapor","oluCanli":2,"oluArtik":22}'], 0, cagrilar),
      existsSync: () => true,
      now: () => 1_000,
    },
  );
  const bir = await manager.report();
  assert.equal(bir.report.oluCanli, 2);
  assert.equal(bir.message, '2 silinmis oturum izi hala okunabilir');
  assert.deepEqual(cagrilar[0].args.slice(-1), ['-Json']);

  await manager.report();
  assert.equal(cagrilar.length, 1, 'TTL icinde ikinci tarama kosmamali');
  await manager.report({ refresh: true });
  assert.equal(cagrilar.length, 2, 'refresh TTL atlamali');
});

test('run -Uygula -SurecleriDurdur -ServisiBaslat gonderir ve sonucu isler', async () => {
  const cagrilar = [];
  const manager = createOturumImhaManager(
    { script: 'C:/x/oturum-imha.ps1' },
    {
      spawn: fakeSpawn(['  - vacuum tamam', '{"mod":"uygula","temiz":true}'], 0, cagrilar),
      existsSync: () => true,
    },
  );
  const basladi = manager.run({});
  assert.equal(basladi.ok, true);
  assert.equal(basladi.running, true);
  // Ikinci tetik cakismamali.
  assert.equal(manager.run({}).ok, false);

  const args = cagrilar[0].args;
  for (const beklenen of ['-Uygula', '-SurecleriDurdur', '-ServisiBaslat', '-Json']) {
    assert.ok(args.includes(beklenen), `${beklenen} eksik`);
  }
  assert.ok(!args.includes('-EskiDb'), 'eskiDb istenmedikce gonderilmemeli');

  await new Promise(resolve => setTimeout(resolve, 500));
  const durum = manager.status();
  assert.equal(durum.running, false);
  assert.equal(durum.result.temiz, true);
  assert.equal(durum.message, 'Temiz — kalinti yok');
});

test('cikis kodu 2 (kalinti var) hata sayilmaz, sonuc yine islenir', async () => {
  const manager = createOturumImhaManager(
    { script: 'C:/x/oturum-imha.ps1' },
    {
      spawn: fakeSpawn(['{"mod":"uygula","temiz":false,"dogrulama":{"kalanOturum":1}}'], 2),
      existsSync: () => true,
    },
  );
  manager.run({});
  await new Promise(resolve => setTimeout(resolve, 500));
  const durum = manager.status();
  assert.equal(durum.result.temiz, false);
  assert.equal(durum.message, '1 kalinti silinemedi');
});

// Canli tuzak (30.09.2026): betik sonunda 4096 servisi kalkiyor ve stdout
// borusunu miras aliyor; 'close' HIC gelmiyor. Yonetici 'exit' ile kapanmali,
// yoksa bitmis is telefon kartinda 10 dk "suruyor" goruntusunde kaliyor.
test('close hic gelmese bile exit ile kapanir', async () => {
  const askidaSpawn = () => {
    const child = new EventEmitter();
    child.stdout = new PassThrough();
    child.stderr = new PassThrough();
    child.kill = () => {};
    queueMicrotask(() => {
      child.stdout.write('{"mod":"uygula","temiz":true}\n');
      child.emit('exit', 0);   // 'close' bilerek YOK: boruyu torun tutuyor.
    });
    return child;
  };
  const manager = createOturumImhaManager(
    { script: 'C:/x/oturum-imha.ps1' },
    { spawn: askidaSpawn, existsSync: () => true },
  );
  manager.run({});
  await new Promise(resolve => setTimeout(resolve, 500));
  const durum = manager.status();
  assert.equal(durum.running, false, 'exit sonrasi is bitmis sayilmali');
  assert.equal(durum.result.temiz, true);
});

test('istege bagli bayraklar gecirilir', async () => {
  const cagrilar = [];
  const manager = createOturumImhaManager(
    { script: 'C:/x/oturum-imha.ps1' },
    { spawn: fakeSpawn(['{"mod":"uygula","temiz":true}'], 0, cagrilar), existsSync: () => true },
  );
  manager.run({ eskiDb: true, snapshotSil: true, loguKoru: true, kanit: ['gizli cumle', ''] });
  const args = cagrilar[0].args;
  assert.ok(args.includes('-EskiDb'));
  assert.ok(args.includes('-Snapshot'));
  assert.ok(args.includes('-LoguKoru'));
  assert.deepEqual(args.slice(args.indexOf('-Kanit')), ['-Kanit', 'gizli cumle']);
});
