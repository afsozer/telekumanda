// Codex profilleri: ozel model saglayicilarini telefondaki model secicide
// gostermek ve thread/start'a modelProvider gecirmek.
//
// Profiller DISKTEN okunuyor (CODEX_HOME/<ad>.config.toml) — kaynakta hicbir
// saglayici adi gomulu degil. Testler gecici bir CODEX_HOME ile calisir.

import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

let app;
let home;

before(async () => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), 'codexhome-'));
  // Varsayilan effort'u BILEREK statik merdivenin disindaki bir degere kuruyoruz:
  // "config'te ne yaziyorsa menude secilebilir olmali" kuralini olcmek icin.
  fs.writeFileSync(path.join(home, 'config.toml'), 'model = "gpt-5.6-sol"\nmodel_reasoning_effort = "ultra"\n', 'utf8');
  // Gecerli profil: model + model_provider birlikte.
  fs.writeFileSync(
    path.join(home, 'ornek.config.toml'),
    '# yorum\nmodel = "ornek-model"\nmodel_provider = "ornek"\n',
    'utf8',
  );
  // Saglayicisi olmayan profil: varsayilan saglayiciyi kullanir, ayri satir gerekmez.
  fs.writeFileSync(path.join(home, 'sadece-model.config.toml'), 'model = "x"\n', 'utf8');
  // Tablo basligindan SONRAKI anahtarlar profilin koku degildir.
  fs.writeFileSync(
    path.join(home, 'tablolu.config.toml'),
    'model = "ust-model"\nmodel_provider = "ust"\n[bir.tablo]\nmodel = "yanlis"\n',
    'utf8',
  );
  process.env.CODEX_HOME = home;
  app = await import(`../codex-app.mjs?codex-profiles=${Date.now()}`);
});

after(() => {
  delete process.env.CODEX_HOME;
  try { fs.rmSync(home, { recursive: true, force: true }); } catch { /* gecici dizin */ }
});

describe('codex profilleri', () => {
  it('yalniz model+saglayici tasiyan profilleri listeler', () => {
    const names = app.listCodexProfiles().map(p => p.name).sort();
    assert.deepEqual(names, ['ornek', 'tablolu']);
  });

  it('tablo basligindan sonraki anahtarlari kok sanmaz', () => {
    const hit = app.listCodexProfiles().find(p => p.name === 'tablolu');
    assert.equal(hit.model, 'ust-model');
    assert.equal(hit.modelProvider, 'ust');
  });

  it('model listesi GERCEK model kimlikleri verir - sahte "profile:" kimligi YOK', () => {
    // Canli hata: sahte kimlik turn/start'a ham gidip API tarafindan
    // reddedilmisti ("... ama sen profile:deepseek gonderdin").
    const list = app.listSelectableModels();
    assert.ok(list.every(m => !m.id.startsWith('profile:')), 'hicbir kimlik profile: ile baslamamali');
    assert.ok(list.some(m => m.id === 'ornek-model'), 'profil modeli gercek kimligiyle listede');
    assert.ok(list.some(m => m.id === 'gpt-6.1-sol'), 'yerlesikler duruyor');
    assert.ok(list.every(m => !m.id.startsWith('gpt-5.6')), 'eski varsayilan menude yok');
  });

  it('ayni model iki kez listelenmez', () => {
    const ids = app.listSelectableModels().map(m => m.id);
    assert.equal(new Set(ids).size, ids.length);
  });

  it('varsayilan model listenin BASINDA durur', () => {
    // Dipte kalirsa kullanici her yeni oturumda onu aramak zorunda kaliyor.
    const list = app.listSelectableModels();
    assert.equal(list[0].id, app.defaultModel());
    // Profil modelleri yerlesiklerden once gelir.
    const ornek = list.findIndex(m => m.id === 'ornek-model');
    const yerlesik = list.findIndex(m => m.id === 'gpt-6-luna');
    assert.ok(ornek < yerlesik, 'profil modeli yerlesiklerin ustunde olmali');
  });

  it('saglayici model kimliginden turer; yerlesik modelde bos kalir', () => {
    assert.equal(app.providerForModel('ornek-model'), 'ornek');
    // Bos olmayan bir deger Codex'in varsayilan saglayicisini ezerdi.
    assert.equal(app.providerForModel('gpt-5.6-sol'), '');
  });

  it('eski Codex varsayilanini AgentBridge seciminde GPT-6.1 Sol ile degistirir', () => {
    assert.equal(app.defaultModel(), 'gpt-6.1-sol');
  });

  it('eski "profile:<ad>" kimlikleri gercek modele goc eder', () => {
    // Diskteki eski oturum kayitlari bu kimligi tasiyor olabilir.
    assert.equal(app.normalizeModelId('profile:ornek'), 'ornek-model');
    assert.equal(app.normalizeModelId('gpt-5.6-sol'), 'gpt-5.6-sol');
    // Profil silinmisse oturum kirilmaz.
    assert.equal(app.normalizeModelId('profile:artik-yok'), app.defaultModel());
  });
});

// model/list YALNIZ yerlesik saglayicinin modellerini dondurur; profil modeli
// katalogda hic yoktur. Canli hata: cip "default·max" diyordu ama effort
// secicide max HIC yoktu — UI statik kesisim listesine dusuyordu.
describe('katalogda olmayan modelin effort listesi', () => {
  it('profil modeline max dahil tam merdiven verilir', async () => {
    app.__testSetModelCatalog({
      'gpt-5.6-sol': { label: 'sol', efforts: ['low', 'medium', 'high', 'xhigh', 'max'], defaultEffort: 'medium' },
    });
    const info = await app.getEffortInfo();
    const set = info.effortsByModel['ornek-model'];
    assert.ok(set.includes('max'), 'max secilebilir olmali');
    // Config'te yazan deger merdivende olmasa bile listeye girer ve SIRALI durur.
    assert.deepEqual(set, ['low', 'medium', 'high', 'xhigh', 'max', 'ultra']);
    // Katalogdaki model kendi kumesini korur — uydurma deger eklenmez.
    assert.deepEqual(info.effortsByModel['gpt-5.6-sol'], ['low', 'medium', 'high', 'xhigh', 'max']);
    app.__testSetModelCatalog(null);
  });
});

// Codex her turun basina KULLANICININ YAZMADIGI bloklar enjekte ediyor. Bunlar
// sohbete dusmemeli: canli vakada kopru restart'i sonrasi ilk promptta AGENTS.md
// dosyasinin TAMAMI sohbete dokuldu (hidrasyon yolunda filtre yoktu ve baslik
// bicimi degistigi icin desen de tutmuyordu).
describe('enjekte edilen kullanici bloklari', () => {
  it('AGENTS.md basliginin ESKI ve YENI bicimini birlikte yakalar', () => {
    // Eski: "... instructions for <yol>" — Yeni: sade baslik + <INSTRUCTIONS>.
    assert.equal(app.isInjectedUserText('# AGENTS.md instructions for C:/x\n...'), true);
    assert.equal(app.isInjectedUserText('# AGENTS.md instructions\n\n<INSTRUCTIONS>\n# Global'), true);
  });

  it('diger enjeksiyon bloklarini eler', () => {
    for (const t of [
      '<environment_context>\nx',
      '<recommended_plugins>\ny',
      '<skills_instructions>\nz',
      '# Context from my IDE setup:\n...',
      '   ',
    ]) assert.equal(app.isInjectedUserText(t), true, JSON.stringify(t.slice(0, 24)));
  });

  it('gercek kullanici mesajini ELEMEZ', () => {
    assert.equal(app.isInjectedUserText('ses deneme'), false);
    assert.equal(app.isInjectedUserText('AGENTS.md dosyasini guncelle'), false);
    // Markdown baslikla baslayan normal bir istek de kullanici metnidir.
    assert.equal(app.isInjectedUserText('# Plan\n1. sunu yap'), false);
  });
});
