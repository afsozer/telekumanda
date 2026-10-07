// opencode2-app birim testleri — saf/yerel mantık.
//
// KAPSAM BİLİNÇLİ SINIRLI: api()'nin spawn eden yolları (getModels, revert,
// compact, …) mock'lanmıyor — onların doğruluğu canlı ölçümlerle
// (CoworkSpaces\opencode-v2\scripts\probe-features.mjs) ve uçtan uca
// doğrulamayla sağlandı. Burada sunucu OLMADAN test edilebilenler duruyor:
// doğrulama reddleri, yerel mesaj kesme, diff normalize, pin/rename metadata.
//
// META ENV'i import'tan ÖNCE set ediliyor: modül sabitleri import anında
// çözülür (AGENTS.md kuralı: env yolu kırılgandır, test izolasyonu şart).
import assert from 'node:assert/strict';
import { after, describe, it } from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const metaPath = path.join(os.tmpdir(), 'opencode2-meta-test-' + Date.now() + '.json');
const homePath = fs.mkdtempSync(path.join(os.tmpdir(), 'opencode2-home-test-'));
process.env.AGENTBRIDGE_OPENCODE2_META = metaPath;
process.env.AGENTBRIDGE_OPENCODE2_HOME = homePath;

const app = await import('../opencode2-app.mjs?birim=' + Date.now());

after(() => {
  try { fs.rmSync(metaPath, { force: true }); } catch {}
  try { fs.rmSync(homePath, { recursive: true, force: true }); } catch {}
});

describe('opencode2 — katalog eşleyicileri (saf)', () => {
  it('v2 ajan kimliğini ortak name alanına çevirir ve alt ajanı eler', () => {
    assert.deepEqual(app.__testMapAgents([
      { id: 'build', name: 'Build', mode: 'primary', description: 'Varsayılan ajan' },
      { id: 'general', name: 'General', mode: 'subagent' },
      { id: 'hidden', mode: 'primary', hidden: true },
    ]), [{ name: 'build', description: 'Varsayılan ajan', mode: 'primary', builtIn: false, model: '' }]);
  });

  // 26.09.2026: katalog yalnız {id,label} taşıyordu; telefondaki efor çipi
  // modelin variants listesine baktığı için HİÇ çizilmiyordu. v2 Model.Info
  // variants'ı DİZİ olarak veriyor (openapi-v2.json), v1'de NESNE idi.
  it('mapCatalogModel variants dizisini ad listesine indirger', () => {
    const r = app.__testMapCatalogModel({
      providerID: 'deepseek', modelID: 'deepseek-flash', name: 'DeepSeek V4.1 Flash',
      variants: [{ id: 'high' }, { id: 'max' }, { id: '' }],
      settings: { reasoningEffort: 'high' },
    });
    assert.equal(r.id, 'deepseek/deepseek-flash');
    // Etiket KİMLİK: arayüz "saglayici/model"i ikiye ayırıp alt satıra
    // sağlayıcıyı yazıyor; dostane ad etikete girerse o ayrım kayboluyordu.
    assert.equal(r.label, 'deepseek/deepseek-flash');
    assert.equal(r.detail, 'DeepSeek V4.1 Flash');
    assert.deepEqual(r.variants, ['high', 'max']);
    assert.equal(r.defaultVariant, 'high');
  });

  it('mapCatalogModel variants/ad yokken güvenli varsayılanlar verir', () => {
    const r = app.__testMapCatalogModel({ providerID: 'opencode', modelID: 'big-pickle' });
    assert.equal(r.label, 'opencode/big-pickle');
    assert.equal(r.detail, '');
    assert.deepEqual(r.variants, []);
    assert.equal(r.defaultVariant, '');
  });

  it('dostane ad kimlikle AYNIYSA detail boş kalır (satır tekrar etmesin)', () => {
    const r = app.__testMapCatalogModel({
      providerID: 'deepseek', modelID: 'deepseek-flash', name: 'deepseek/deepseek-flash',
    });
    assert.equal(r.detail, '');
  });

  it('RunPod etiketi v1 ile aynı özel adı kullanır', () => {
    const r = app.__testMapCatalogModel({ providerID: 'runpod', modelID: 'runpod', name: 'Qwen3.8-27B UD-Q6_K_XL' });
    assert.equal(r.label, 'Qwen3.8-27B · RunPod');
    assert.equal(r.detail, 'Qwen3.8-27B UD-Q6_K_XL');
  });

  // 26.09.2026 ölçümü: köprü 21:34'te restart oldu, 21:35'te v2 sunucusu
  // openai/gpt-4o, gpt-4.1-mini gibi ONLARCA modeli listeliyordu; 21:38'de aynı
  // istek 27 modele indi. Yani katalog açılıştan hemen sonra DOLU ama YANLIŞ
  // gelebiliyor ve "boş mu" kontrolü bunu yakalamıyor. Hizalama v1'in
  // kataloğunu otorite kabul eder.
  it('peer hizalaması v1 kataloğunda olmayanı eler ve gizleneni bildirir', () => {
    const models = [
      { id: 'deepseek/deepseek-flash' },
      { id: 'openai/gpt-4o' },
      { id: 'openai/gpt-4.1-mini' },
      { id: 'nanogpt/z-ai/glm-5.3-flash' },
    ];
    const r = app.__testPeerIleHizala(models, new Set([
      'deepseek/deepseek-flash', 'nanogpt/z-ai/glm-5.3-flash', 'evren/glm-5.3',
    ]));
    assert.deepEqual(r.models.map(m => m.id), ['deepseek/deepseek-flash', 'nanogpt/z-ai/glm-5.3-flash']);
    assert.deepEqual(r.gizlenen, ['openai/gpt-4o', 'openai/gpt-4.1-mini']);
  });

  it('peer kaydı yoksa liste OLDUĞU GİBİ kalır (silmektense süzülmemiş göster)', () => {
    const models = [{ id: 'openai/gpt-4o' }];
    assert.deepEqual(app.__testPeerIleHizala(models, null).models, models);
    assert.deepEqual(app.__testPeerIleHizala(models, new Set()).models, models);
  });

  it('mapSkillInfo adı olmayan kaydı boş adla işaretler (süzülsün diye)', () => {
    assert.deepEqual(
      app.__testMapSkillInfo({ id: 'pdf', name: 'pdf', description: 'PDF işleri' }),
      { name: 'pdf', description: 'PDF işleri' },
    );
    assert.equal(app.__testMapSkillInfo({}).name, '');
  });
});

describe('opencode2 — yerel oturum ve doğrulama', () => {
  it('newSession sunucuya gitmeden yerel zarf döndürür', () => {
    const r = app.newSession({ cwd: os.tmpdir() });
    assert.equal(r.ok, true);
    assert.ok(r.sessionId);
    assert.equal(typeof app.__testSession(r.sessionId), 'object');
  });

  it('newSession var olmayan dizini reddeder', () => {
    const r = app.newSession({ cwd: 'C:\\yok-boyle-bir-dizin-xyz' });
    assert.equal(r.ok, false);
  });

  it('putInstruction geçersiz anahtarı api çağırmadan reddeder', async () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const r = await app.putInstruction({ sessionId: s.sessionId, key: 'GEÇERSİZ KEY!', value: 'x' });
    assert.equal(r.ok, false);
    assert.match(r.error, /anahtar biçimi/);
  });

  it('putInstruction value\'su olmayan istediği reddeder', async () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const r = await app.putInstruction({ sessionId: s.sessionId, key: 'gecerli-anahtar' });
    assert.equal(r.ok, false);
    assert.match(r.error, /value/);
  });

  it('steer boş metni reddeder', async () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const r = await app.steer({ sessionId: s.sessionId, text: '   ' });
    assert.equal(r.ok, false);
    assert.match(r.error, /empty/);
  });

  it('forkFromMessage dropUserTurns < 1 reddeder', async () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const r = await app.forkFromMessage({ sessionId: s.sessionId, dropUserTurns: 0 });
    assert.equal(r.ok, false);
  });

  it('rewindSession dropUserTurns < 1 reddeder', async () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const r = await app.rewindSession({ sessionId: s.sessionId, dropUserTurns: -3 });
    assert.equal(r.ok, false);
  });

  it('deleteSavedPermission idsiz istediği reddeder', async () => {
    const r = await app.deleteSavedPermission({});
    assert.equal(r.ok, false);
  });

  it('listDiskSessions sunucu kapalıyken boş zarf döner (sözleşme)', async () => {
    const r = await app.listDiskSessions();
    assert.equal(r.ok, true);
    assert.ok(Array.isArray(r.sessions));
  });

  it('listDiskSessions sunucu kapalıyken v2 veritabanından geçmişi okur', async () => {
    const dir = path.join(homePath, 'data', 'opencode');
    fs.mkdirSync(dir, { recursive: true });
    const db = new DatabaseSync(path.join(dir, 'opencode.db'));
    db.exec('CREATE TABLE session_v2 (id TEXT, title TEXT, slug TEXT, directory TEXT, model TEXT, time_created INTEGER, time_updated INTEGER)');
    const insert = db.prepare('INSERT INTO session_v2 VALUES (?, ?, ?, ?, ?, ?, ?)');
    insert.run('ses_old', 'Eski', 'old', 'C:/old', null, 1000, 1000);
    insert.run('ses_new', 'Yeni', 'new', 'C:/new', JSON.stringify({ providerID: 'evren', id: 'glm-5.3' }), 2000, 3000);
    db.close();
    const r = await app.listDiskSessions();
    assert.equal(r.ok, true, r.error);
    assert.deepEqual(r.sessions.map(s => s.id), ['ses_new', 'ses_old']);
    assert.equal(r.sessions[0].model, 'evren/glm-5.3');
    assert.equal(r.sessions[0].mtime, 3000);
  });
});

describe('opencode2 — yerel mesaj kesme (revert sonrası görünüm)', () => {
  it('cutLocalMessages sondan N kullanıcı satırına kadar keser', () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const sess = app.__testSession(s.sessionId);
    for (const [role, text] of [
      ['user', 'bir'], ['agent', 'cevap1'], ['thought', 'araç'],
      ['user', 'iki'], ['agent', 'cevap2'], ['user', 'üç'], ['agent', 'cevap3'],
    ]) sess.messages.push({ role, text, time: '00:00' });
    app.__testCutLocalMessages(sess, 2); // sondan 2 kullanıcı turu (üç + iki) kesilir
    const roller = sess.messages.map(m => m.role);
    assert.deepEqual(roller, ['user', 'agent', 'thought']);
    assert.equal(sess.messages.at(-1).text, 'araç');
  });

  it('cutLocalMessages tüm turları kesince mesajlar boşalır', () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const sess = app.__testSession(s.sessionId);
    sess.messages.push({ role: 'user', text: 'a', time: '00:00' });
    sess.messages.push({ role: 'agent', text: 'b', time: '00:00' });
    app.__testCutLocalMessages(sess, 1);
    assert.equal(sess.messages.length, 0);
  });
});

describe('opencode2 — geri sar şeridi (reverted)', () => {
  it('commitRevert şeridi düşürür ve deltayı işaretler', () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const sess = app.__testSession(s.sessionId);
    sess.reverted = { messageID: 'msg_1', filesReverted: true, files: 3 };
    sess._deltaDirty = false;
    assert.equal(app.__testCommitRevert(sess), true);
    assert.equal(sess.reverted, null);
    // META_KEYS'te 'reverted' var; delta işaretlenmezse şerit telefonda
    // düşmez (uzun koşuda tam snapshot neredeyse hiç gitmiyor).
    assert.equal(sess._deltaDirty, true);
  });

  it('geri sarılmamış oturumda commitRevert dokunmaz', () => {
    const s = app.newSession({ cwd: os.tmpdir() });
    const sess = app.__testSession(s.sessionId);
    sess._deltaDirty = false;
    assert.equal(app.__testCommitRevert(sess), false);
    assert.equal(sess._deltaDirty, false);
  });

  // TRIPWIRE. v2'de geri sarma iki kademeli: `revert/stage` geri alınabilir,
  // `revert/commit` kalıcı. Köprü ikisini peş peşe çağırdığı için "Geri Al"
  // hiçbir zaman çalışamıyordu ve bu "v2 unrevert edemiyor" diye yanlış
  // genellenmişti. Commit'e gerek de yok: yeni tur gidince SUNUCU kendiliğinden
  // commit ediyor (30.09.2026 canlı ölçüm, revert-deney2.mjs).
  it('revertToMessage commit ETMEZ — yoksa Geri Al imkânsızlaşır', () => {
    const src = fs.readFileSync(new URL('../opencode2-app.mjs', import.meta.url), 'utf8');
    const start = src.indexOf('export async function revertToMessage(');
    assert.ok(start > -1, 'revertToMessage() bulunamadı');
    const body = src.slice(start, src.indexOf('\nexport ', start + 1));
    assert.ok(/revert\/stage/.test(body), 'stage çağrısı kaybolmuş');
    assert.ok(!/revert\/commit/.test(body),
      'revertToMessage yine commit ediyor — commit sonrası DELETE /revert hiçbir ' +
      'şeyi geri getirmiyor, yani "Geri Al" tuşu ölü tuşa döner.');
  });

  it('unrevertSession DELETE /revert çağırır ve konuşmayı yeniden yükler', () => {
    const src = fs.readFileSync(new URL('../opencode2-app.mjs', import.meta.url), 'utf8');
    const start = src.indexOf('export async function unrevertSession(');
    assert.ok(start > -1, 'unrevertSession() bulunamadı');
    const body = src.slice(start, src.indexOf('\nexport ', start + 1));
    assert.ok(/api\('DELETE',[^)]*revert/.test(body), 'DELETE /revert çağrısı yok');
    assert.ok(/reloadConversation\(s\)/.test(body),
      'unrevert sonrası yerel kuyruk sunucudan tazelenmezse kesilen turlar ekranda geri gelmez');
  });

  // TRIPWIRE. Asıl kusur helper'ın yanlış çalışması değildi: v1'deki
  // commitRevert v2 modülüne hiç TAŞINMAMIŞTI, yani `s.reverted` bir kez set
  // edilip hiçbir yerde temizlenmiyordu ve "Geri sarıldı" şeridi oturum
  // boyunca ekranda kalıyordu (30.09.2026). Yeni bir tur başlatan yol
  // eklenirse burada patlasın.
  it('tur başlatan her yol commitRevert çağırır', () => {
    const src = fs.readFileSync(new URL('../opencode2-app.mjs', import.meta.url), 'utf8');
    for (const fn of ['prompt', 'runCommand']) {
      const start = src.indexOf(`export async function ${fn}(`);
      assert.ok(start > -1, `${fn}() bulunamadı`);
      const next = src.indexOf('\nexport ', start + 1);
      const body = src.slice(start, next > -1 ? next : src.length);
      assert.ok(/commitRevert\(s\)/.test(body),
        `${fn}() yeni tur başlatıyor ama commitRevert(s) çağırmıyor — ` +
        '"Geri sarıldı" şeridi ekranda kalır.');
    }
  });
});

describe('opencode2 — diff kayıt normalize', () => {
  it('tam kayıt aynen geçer', () => {
    const e = app.__testNormalizeDiffEntry({ file: 'a/b.txt', patch: '@@ -1 +1 @@', additions: 2, deletions: 1, status: 'modified' });
    assert.deepEqual(e, { path: 'a/b.txt', patch: '@@ -1 +1 @@', additions: 2, deletions: 1, status: 'modified', truncated: false });
  });

  it('yolsuz ve sayısız kayıtlar savunmayla okunur', () => {
    const e = app.__testNormalizeDiffEntry({ file: 'x.txt', patch: 'p' });
    assert.equal(e.additions, 0);
    assert.equal(e.deletions, 0);
    assert.equal(e.status, '');
  });

  it('dosya adı olmayan kayıt elenir', () => {
    assert.equal(app.__testNormalizeDiffEntry({ patch: 'p' }), null);
    assert.equal(app.__testNormalizeDiffEntry(null), null);
  });
});

describe('opencode2 — pin/rename metadata (ENV ile izole dosya)', () => {
  it('pin → dosyaya yazar, unpin → düşürür', () => {
    const r = app.pinThread({ id: 'ses_test1' });
    assert.equal(r.ok, true);
    const disk = JSON.parse(fs.readFileSync(metaPath, 'utf-8'));
    assert.equal(disk.ses_test1.pinned, true);
    const u = app.unpinThread({ id: 'ses_test1' });
    assert.equal(u.ok, true);
    const disk2 = JSON.parse(fs.readFileSync(metaPath, 'utf-8'));
    assert.equal(disk2.ses_test1.pinned, false);
  });

  it('rename başlığı kırpıp yazar; boş başlık alanı siler', () => {
    app.renameThread({ id: 'ses_test2', title: '  çok   uzun   başlık  ' });
    const disk = JSON.parse(fs.readFileSync(metaPath, 'utf-8'));
    assert.equal(disk.ses_test2.title, 'çok uzun başlık');
    app.renameThread({ id: 'ses_test2', title: '   ' });
    const disk2 = JSON.parse(fs.readFileSync(metaPath, 'utf-8'));
    assert.equal('title' in disk2.ses_test2, false);
  });

  it('idsiz pin reddedilir', () => {
    assert.equal(app.pinThread({}).ok, false);
  });
});

// ── Katalog TAMLIK dogrulamasi (30.09.2026) ────────────────────────────────
// Kullanici bildirdi: "model listesinde cok az model var". bridge.log
// 09:07:55Z'de runpod/nanogpt/evren/mac modellerini gizlemis — dogrulama
// kaynagi (`opencode models`) o an YARIM cevap vermis, kesisim de gerisini
// silmisti. Bos kontrolu yetmiyor; config'te tanimli her saglayicidan en az
// bir model gelmis olmali.
describe('opencode2 — katalog tamlik kontrolu', () => {
  it('beklenen saglayicilardan biri hic yoksa eksik olarak isaretlenir', () => {
    const beklenen = new Set(['deepseek', 'nanogpt', 'mac']);
    const yarim = ['deepseek/deepseek-flash', 'opencode/big-pickle'];
    assert.deepEqual(app.__testEksikSaglayicilar(yarim, beklenen).sort(), ['mac', 'nanogpt']);
  });

  it('butun saglayicilar temsil ediliyorsa eksik yok', () => {
    const beklenen = new Set(['deepseek', 'mac']);
    const tam = ['deepseek/deepseek-flash', 'mac/gemma4-26b-a4b-heretic-omlx', 'opencode/big-pickle'];
    assert.deepEqual(app.__testEksikSaglayicilar(tam, beklenen), []);
  });

  it('beklenti bos ise (config okunamadi) hicbir sey eksik sayilmaz', () => {
    assert.deepEqual(app.__testEksikSaglayicilar(['deepseek/deepseek-flash'], new Set()), []);
    assert.deepEqual(app.__testEksikSaglayicilar([], null), []);
  });

  it('cok egik cizgili kimlikte saglayici ILK parcadir', () => {
    const beklenen = new Set(['nanogpt']);
    assert.deepEqual(app.__testEksikSaglayicilar(['nanogpt/z-ai/glm-5.3-flash'], beklenen), []);
  });
});

// ── Ajan modeli (30.09.2026) ───────────────────────────────────────────────
// Mac'teki ajanlar bu makineye alinirken goruldu: v2 ajanin modelini NESNE
// olarak veriyor ({providerID, id}); yalniz string okuyan harita her ajanda ""
// donduruyordu, telefonda ajanin modeli hic gorunmuyordu.
describe('opencode2 — ajan model alani', () => {
  it('nesne bicimini saglayici/model metnine cevirir', () => {
    assert.equal(app.__testAgentModelString({ providerID: 'nanogpt', id: 'meta/muse-spark-1.3-contributor' }),
      'nanogpt/meta/muse-spark-1.3-contributor');
  });

  it('string bicimi aynen gecer, bos/eksik alanlar bos doner', () => {
    assert.equal(app.__testAgentModelString('deepseek/deepseek-flash'), 'deepseek/deepseek-flash');
    assert.equal(app.__testAgentModelString(undefined), '');
    assert.equal(app.__testAgentModelString({ providerID: 'nanogpt' }), '');
  });

  it('saglayicisi olmayan nesnede yalniz kimlik doner', () => {
    assert.equal(app.__testAgentModelString({ id: 'deepseek-flash' }), 'deepseek-flash');
  });

  it('mapAgents ajanin modelini tasir (alt-ajan ve gizli elenir)', () => {
    const cikti = app.__testMapAgents([
      { id: 'muse', description: 'x', mode: 'primary', model: { providerID: 'nanogpt', id: 'meta/muse-spark-1.3-contributor' } },
      { id: 'gizli', mode: 'primary', hidden: true },
      { id: 'alt', mode: 'subagent' },
    ]);
    assert.deepEqual(cikti.map(a => a.name), ['muse']);
    assert.equal(cikti[0].model, 'nanogpt/meta/muse-spark-1.3-contributor');
  });
});

describe('opencode2 — akış satırı titremesi (thoughtSlot)', () => {
  // TRIPWIRE. Telefonun görünüm modeli (ChatUiModels.activityKind) bir `thought`
  // satırını YALNIZ thoughtIndex >= 0 ise katlanabilir gruba koyuyor; indekssiz
  // kısa satır bağımsız durum şeridi olarak çiziliyor. v2 yuvayı satır
  // DOĞDUKTAN SONRA açtığı için her araç/düşünce adımı önce şerit, birkaç yüz ms
  // sonra grup üyesi oluyordu: ekranda satır görünüp kayboluyor, LazyColumn
  // anahtarı da (rowId -> grp_rowId) değiştiği için kart yok edilip yeniden
  // kuruluyordu. Kullanıcı bildirdi 30.09.2026; ölçüm titreme-deney.mjs.
  it('satır doğuran olaylar thoughtSlot ile yuvayı ilk karede açar', () => {
    const src = fs.readFileSync(new URL('../opencode2-app.mjs', import.meta.url), 'utf8');
    const start = src.indexOf('function onEvent(');
    assert.ok(start > -1, 'onEvent() bulunamadı');
    const body = src.slice(start, src.indexOf('\n// ── ', start));
    for (const [olay, sonraki] of [
      ["case 'session.reasoning.started':", "case 'session.reasoning.delta':"],
      ["case 'session.tool.input.started':", "case 'session.tool.called':"],
    ]) {
      const i = body.indexOf(olay);
      assert.ok(i > -1, olay + ' bulunamadı');
      const blok = body.slice(i, body.indexOf(sonraki, i));
      assert.ok(/thoughtSlot\(/.test(blok),
        olay + ' satırı yuvasız doğuruyor — satır ilk karede şerit, sonraki karede ' +
        'grup üyesi olur ve ekranda görünüp kaybolur.');
    }
  });

  it('onEvent içinde elle yuva açan kalıntı kalmamıştır', () => {
    const src = fs.readFileSync(new URL('../opencode2-app.mjs', import.meta.url), 'utf8');
    const start = src.indexOf('function onEvent(');
    const body = src.slice(start, src.indexOf('\n// ── ', start));
    assert.ok(!/thoughtIndex === undefined/.test(body),
      'onEvent yuvayı elle açıyor — tek kapı thoughtSlot olmalı, yoksa yeni bir ' +
      'olay yolu aynı titremeyi geri getirir.');
  });

  it('thoughtSlot yuvayı bir kez açar ve aynı indeksi döner', () => {
    const s = { toolDetails: ['onceki'] };
    const row = { role: 'thought', text: 'bash' };
    const i = app.__testThoughtSlot(s, row);
    assert.equal(i, 1);
    assert.equal(row.thoughtIndex, 1);
    assert.equal(s.toolDetails.length, 2);
    // İkinci çağrı YENİ yuva açmaz (tool.called/success aynı satır için tekrar çağırıyor).
    assert.equal(app.__testThoughtSlot(s, row), 1);
    assert.equal(s.toolDetails.length, 2);
  });
});

describe('opencode2 — düşünce/araç ayrımı (DUSUNCE_ISARETI)', () => {
  // TRIPWIRE. Telefonun sınıflandırıcısı (ChatUiModels.activityKind) bir
  // `thought` satırını yalnız metni 🧠 ile BAŞLIYORSA (ya da metni tamamen
  // boşsa) Düşünce sayıyor; thoughtIndex taşıyan ve metni dolu olan satır Araç
  // grubuna gidiyor. İşaretsiz "Düşündü · …" etiketi bu yüzden araç adımlarıyla
  // aynı kartta birikip "Araç" diye etiketleniyordu (ölçüm 30.09.2026,
  // satir-sinif-olcum.mjs). Kullanıcı iki ayrı grup istedi.
  it('düşünce satırlarının etiketi işaretle başlar', () => {
    const src = fs.readFileSync(new URL('../opencode2-app.mjs', import.meta.url), 'utf8');
    assert.ok(/const DUSUNCE_ISARETI = '🧠';/.test(src), 'DUSUNCE_ISARETI sabiti yok');
    // İşaretsiz düşünce etiketi (string literalin BAŞINDA "Düşün…") kalmamalı.
    const isaretsiz = src.match(/'Düşün(üyor|dü)/g) || [];
    assert.deepEqual(isaretsiz, [],
      'işaretsiz düşünce etiketi kalmış — o satır telefonda Araç grubuna düşer ' +
      've kart "Araç" diye etiketlenir: ' + isaretsiz.join(', '));
    // Canlı akış + disk oturumu devralma: dördü de işaretli olmalı.
    const isaretli = src.match(/DUSUNCE_ISARETI \+ ' Düşün/g) || [];
    assert.equal(isaretli.length, 4,
      'düşünce etiketi yazan yer sayısı değişti (beklenen 4: reasoning ' +
      'started/delta/ended + adoptSession) — yeni yol da işaretlenmeli');
  });
});
