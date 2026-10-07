// Not yaşam döngüsü birim testleri — listNotes / createNote / attachNote
// (cowork.mjs, docs/notlar-plani.md Faz N0). COWORK_ROOT modül yükleme anında
// env'den sabitlenir; bu yüzden env set edildikten SONRA dinamik import edilir
// (cowork-savefile.test.mjs ile aynı desen).
import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const COWORK_TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-notes-'));
process.env.AGENTBRIDGE_COWORK_ROOT = COWORK_TMP;
const cowork = await import('../cowork.mjs');

after(() => { try { fs.rmSync(COWORK_TMP, { recursive: true, force: true }); } catch {} });

function mkProject(name) {
  const r = cowork.createProject({ name });
  assert.equal(r.ok, true, 'proje oluşturulmalı: ' + JSON.stringify(r));
  return r.path;
}

describe('createNote: bağımsız (genel) not', () => {
  it('not _genel-notlar/ altında .md üretir, slug + frontmatter', () => {
    const r = cowork.createNote({ name: 'Duruşma Özeti' });
    assert.equal(r.ok, true);
    assert.equal(r.note.project, null);
    assert.equal(r.note.id, '_genel-notlar/durusma-ozeti');
    assert.ok(r.note.mdPath.endsWith(path.join('_genel-notlar', 'durusma-ozeti.md')));
    const md = fs.readFileSync(r.note.mdPath, 'utf8');
    assert.match(md, /^---\r?\ntitle: Duruşma Özeti\r?\n---/);
  });

  it('eski istemcinin kind=ink isteği de düz .md not üretir (.ink yok)', () => {
    const r = cowork.createNote({ name: 'El yazısı', kind: 'ink' });
    assert.equal(r.ok, true);
    assert.ok(r.note.mdPath.endsWith('.md'));
    assert.equal(fs.existsSync(r.note.mdPath), true);
    assert.equal(fs.existsSync(r.note.mdPath.replace(/\.md$/, '.ink')), false);
    assert.equal('kind' in r.note, false);
    assert.equal('inkPath' in r.note, false);
  });

  it('aynı ad ikinci kez -> -2 eki', () => {
    cowork.createNote({ name: 'Tekrar' });
    const r2 = cowork.createNote({ name: 'Tekrar' });
    assert.equal(r2.note.id, '_genel-notlar/tekrar-2');
  });

  it('boş ad reddedilir', () => {
    const r = cowork.createNote({ name: '   ' });
    assert.equal(r.ok, false);
  });
});

describe('createNote: projeye bağlı not', () => {
  it('proje verilince <proje>/notlar/ altına yazar', () => {
    const p = mkProject('Dava A');
    const r = cowork.createNote({ name: 'Taslak', projectPath: p });
    assert.equal(r.ok, true);
    assert.equal(r.note.project.path, p);
    assert.ok(r.note.mdPath.startsWith(path.join(p, 'notlar') + path.sep));
    assert.equal(fs.existsSync(r.note.mdPath), true);
  });

  it('Cowork kökü dışındaki projectPath reddedilir', () => {
    const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-out-'));
    try {
      const r = cowork.createNote({ name: 'x', projectPath: outside });
      assert.equal(r.ok, false);
    } finally { fs.rmSync(outside, { recursive: true, force: true }); }
  });
});

describe('listNotes: birleşik liste', () => {
  it('genel + proje notlarını tek listede toplar', () => {
    const p = mkProject('Dava B');
    cowork.createNote({ name: 'Genel bir not' });
    cowork.createNote({ name: 'Proje notu', projectPath: p });

    const r = cowork.listNotes();
    assert.equal(r.ok, true);
    const genel = r.notes.find(n => n.title === 'Genel bir not');
    const proje = r.notes.find(n => n.title === 'Proje notu');
    assert.ok(genel && genel.project === null && genel.mdPath);
    assert.ok(proje && proje.project && proje.project.name === 'Dava B');
  });

  // Kalem notu desteği kaldırıldı: diskte kalan eski dosyalar listeyi bozmamalı.
  it('eski kalem notu: yalnız .ink listelenmez, türev .md düz not görünür', () => {
    const dir = cowork.generalNotesDir();
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'sadece-kalem.ink'), '{"schema":1,"title":"Sadece Kalem","pages":[]}', 'utf8');
    fs.writeFileSync(path.join(dir, 'turev.ink'), '{"schema":1,"title":"Turev","pages":[]}', 'utf8');
    fs.writeFileSync(path.join(dir, 'turev.md'),
      '---\ntitle: Turev\nsource: turev.ink\nstale: true\n---\nmetin\n', 'utf8');

    const notes = cowork.listNotes().notes;
    assert.equal(notes.some(n => n.title === 'Sadece Kalem' || n.id === '_genel-notlar/sadece-kalem'), false);
    const turev = notes.filter(n => n.id === '_genel-notlar/turev');
    assert.equal(turev.length, 1);
    assert.equal(turev[0].title, 'Turev');
    assert.ok(turev[0].mdPath.endsWith('turev.md'));
    assert.equal(turev[0].preview, 'metin');
    for (const k of ['kind', 'inkPath', 'stale']) assert.equal(k in turev[0], false, k);
  });
});

describe('listProjects: _genel-notlar proje sayılmaz', () => {
  it('genel not klasörü projeler listesine girmez', () => {
    cowork.createNote({ name: 'kok kanit' }); // _genel-notlar oluşsun
    const projects = cowork.listProjects().projects.map(p => p.name);
    assert.equal(projects.includes('_genel-notlar'), false);
  });
});

describe('attachNote: bağımsız notu projeye taşı', () => {
  it('.md notu <proje>/notlar/e taşır; eski .ink kaynakta kalır', () => {
    const p = mkProject('Hedef Dava');
    const note = cowork.createNote({ name: 'Baglanacak' });
    const mdSrc = note.note.mdPath;
    const inkSrc = mdSrc.replace(/\.md$/, '.ink');
    fs.writeFileSync(inkSrc, '{}', 'utf8');

    const r = cowork.attachNote({ noteId: note.note.id, projectPath: p });
    assert.equal(r.ok, true);
    assert.equal(r.note.project.path, p);
    assert.equal(fs.existsSync(mdSrc), false);
    assert.equal(fs.existsSync(r.note.mdPath), true);
    assert.ok(r.note.mdPath.startsWith(path.join(p, 'notlar') + path.sep));
    assert.equal(fs.existsSync(inkSrc), true, '.ink artık not parçası değil');
  });

  it('zaten aynı projedeki not tekrar bağlanamaz', () => {
    const p = mkProject('Ayni Proje');
    const note = cowork.createNote({ name: 'zaten burada', projectPath: p });
    const r = cowork.attachNote({ noteId: note.note.id, projectPath: p });
    assert.equal(r.ok, false);
  });

  it('var olmayan not bağlanamaz', () => {
    const p = mkProject('Bos Proje');
    const r = cowork.attachNote({ noteId: '_genel-notlar/yok-boyle-bir-not', projectPath: p });
    assert.equal(r.ok, false);
  });
});

describe('renameNote/deleteNote', () => {
  it('başlık ve dosya adı birlikte değişir; eski kalem alanları düşer', () => {
    const note = cowork.createNote({ name: 'Eski Başlık' });
    const mdPath = note.note.mdPath;
    fs.writeFileSync(
      mdPath,
      '---\ntitle: Eski Başlık\nsource: eski-baslik.ink\nsource_hash: sha256:x\nstale: true\nconverted_body_hash: sha256:y\n---\nmetin\n',
      'utf8',
    );
    const inkPath = mdPath.replace(/\.md$/, '.ink');
    fs.writeFileSync(inkPath, '{}', 'utf8');

    const r = cowork.renameNote({ noteId: note.note.id, name: 'Yeni İsim' });

    assert.equal(r.ok, true);
    assert.equal(r.note.title, 'Yeni İsim');
    assert.ok(r.note.mdPath.endsWith('yeni-isim.md'));
    assert.equal(fs.existsSync(mdPath), false);
    assert.equal(fs.existsSync(inkPath), true, '.ink dokunulmadan kalır');
    const renamedMd = fs.readFileSync(r.note.mdPath, 'utf8');
    assert.match(renamedMd, /title: Yeni İsim/);
    assert.doesNotMatch(renamedMd, /source:|stale:|converted_body_hash/);
    assert.match(renamedMd, /metin/);
  });

  // Not kimliği dosya adından türüyor, yani ad değişince KİMLİK DE değişir.
  // İstemci bunu benimsemek zorunda: benimsemeyip eski kimliği/yolu tutunca
  // sonraki kayıt taşınmış yola gidiyor, "not bulunamadı" ile düşüyor ve
  // kullanıcının yazdığı gövde kayboluyor (canlıda yaşandı, 10.08.2026).
  it('yeniden adlandırma kimliği değiştirir ve eski kimlik ölür', () => {
    const olusan = cowork.createNote({ name: 'Eski Başlık' });
    const eskiId = olusan.note.id;

    const r = cowork.renameNote({ noteId: eskiId, name: 'Yeni Başlık' });

    assert.equal(r.ok, true);
    assert.notEqual(r.note.id, eskiId);
    // Eski kimliğe yapılan her iş bundan sonra başarısız olmalı — sessizce
    // eski yola yazılıp kaybolmasındansa hata dönmesi doğru davranış.
    const bayat = cowork.renameNote({ noteId: eskiId, name: 'Herhangi' });
    assert.equal(bayat.ok, false);
    assert.match(bayat.error, /bulunamadı/);
  });

  it('ad çakışmasında -2 eki', () => {
    cowork.createNote({ name: 'Hedef' });
    const source = cowork.createNote({ name: 'Kaynak' });
    const r = cowork.renameNote({ noteId: source.note.id, name: 'Hedef' });
    assert.equal(r.ok, true);
    assert.ok(r.note.mdPath.endsWith('hedef-2.md'));
  });

  it('silme yalnız .md dosyasını kaldırır; eski .ink kalır', () => {
    const note = cowork.createNote({ name: 'Silinecek' });
    const mdPath = note.note.mdPath;
    const inkPath = mdPath.replace(/\.md$/, '.ink');
    fs.writeFileSync(inkPath, '{}', 'utf8');

    const r = cowork.deleteNote({ noteId: note.note.id });

    assert.equal(r.ok, true);
    assert.equal(fs.existsSync(mdPath), false);
    assert.equal(fs.existsSync(inkPath), true);
    assert.equal(cowork.deleteNote({ noteId: note.note.id }).ok, false);
  });

  it('Cowork içindeki sıradan dosya noteId olarak silinemez', () => {
    const project = mkProject('Koruma');
    const file = path.join(project, 'dosya');
    fs.writeFileSync(file + '.md', 'koru', 'utf8');
    const id = path.relative(COWORK_TMP, file).split(path.sep).join('/');
    const r = cowork.deleteNote({ noteId: id });
    assert.equal(r.ok, false);
    assert.equal(fs.existsSync(file + '.md'), true);
  });
});

describe('noteAi: önizleme ve onaylı uygulama', () => {
  function fakeProvider(resultText = '- Kısa özet') {
    let token = '';
    const calls = [];
    return {
      calls,
      defaultModel: () => 'fake-model',
      newSession: options => {
        calls.push(['newSession', options]);
        return { ok: true, sessionId: 'temp-ai-session' };
      },
      prompt: request => {
        calls.push(['prompt', request]);
        token = /<<<(AGENTBRIDGE_NOTE_[A-Z0-9]+)>>>/.exec(request.text)?.[1] || '';
        return { ok: true, sessionId: request.sessionId };
      },
      getConversation: () => ({
        running: false,
        awaitingFirstOutput: false,
        awaitingApproval: false,
        messages: [{
          role: 'agent',
          text: `<<<${token}>>>\n${resultText}\n<<<END_${token}>>>`,
        }],
      }),
      approve: request => calls.push(['approve', request]),
      stop: id => calls.push(['stop', id]),
      deleteDiskSession: request => calls.push(['delete', request]),
    };
  }

  it('özet yalnız önizleme üretir; dosya onaydan önce değişmez', async () => {
    const note = cowork.createNote({ name: 'AI Notu' });
    const original = '---\ntitle: AI Notu\n---\nBirinci bilgi.\nİkinci bilgi.\n';
    fs.writeFileSync(note.note.mdPath, original, 'utf8');
    const provider = fakeProvider('- İki bilgi içeriyor.');

    const preview = await cowork.noteAi({
      noteId: note.note.id,
      action: 'ozetle',
      provider,
      timeoutMs: 100,
    });

    assert.equal(preview.ok, true);
    assert.equal(fs.readFileSync(note.note.mdPath, 'utf8'), original);
    assert.match(preview.proposed, /Birinci bilgi/);
    assert.match(preview.proposed, /## AI Özeti/);
    assert.match(preview.proposed, /İki bilgi içeriyor/);
    assert.equal(provider.calls.some(call => call[0] === 'stop'), true);
    assert.equal(provider.calls.some(call => call[0] === 'delete'), true);
  });

  it('onay mevcut frontmatterı koruyarak öneriyi yazar', async () => {
    const note = cowork.createNote({ name: 'Düzelt' });
    fs.writeFileSync(note.note.mdPath, '---\ntitle: Düzelt\n---\nyanlis cumle\n', 'utf8');
    const preview = await cowork.noteAi({
      noteId: note.note.id,
      action: 'duzelt',
      provider: fakeProvider('Yanlış cümle.'),
      timeoutMs: 100,
    });

    const applied = cowork.applyNoteAi({
      noteId: note.note.id,
      baseHash: preview.base_hash,
      proposed: preview.proposed,
    });

    assert.equal(applied.ok, true);
    const saved = fs.readFileSync(note.note.mdPath, 'utf8');
    assert.match(saved, /^---\ntitle: Düzelt\n---\n/);
    assert.match(saved, /Yanlış cümle\./);
  });

  it('önizleme sonrası dosya değişmişse uygulama çatışmayla reddedilir', async () => {
    const note = cowork.createNote({ name: 'Çatışma' });
    fs.writeFileSync(note.note.mdPath, '---\ntitle: Çatışma\n---\nilk\n', 'utf8');
    const preview = await cowork.noteAi({
      noteId: note.note.id,
      action: 'formatla',
      provider: fakeProvider('# İlk'),
      timeoutMs: 100,
    });
    fs.appendFileSync(note.note.mdPath, 'elle değişti\n', 'utf8');

    const applied = cowork.applyNoteAi({
      noteId: note.note.id,
      baseHash: preview.base_hash,
      proposed: preview.proposed,
    });

    assert.equal(applied.ok, false);
    assert.equal(applied.conflict, true);
    assert.match(fs.readFileSync(note.note.mdPath, 'utf8'), /elle değişti/);
  });

  it('.md dosyası olmayan (eski yalnız-.ink) kimlik reddedilir', async () => {
    const dir = cowork.generalNotesDir();
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'ham-ink.ink'), '{}', 'utf8');
    const result = await cowork.noteAi({
      noteId: '_genel-notlar/ham-ink',
      action: 'ozetle',
      provider: fakeProvider(),
      timeoutMs: 100,
    });
    assert.equal(result.ok, false);
    assert.match(result.error, /bulunamadı/);
  });
});

// ── Hatırlatıcı alanları (docs/ekran-goruntusu-hatirlatici-plani.md, E5.1) ──
describe('listNotes: hatırlatıcı bilgisi', () => {
  it('reminder_* alanlarını listeye taşır, olmayan notta null kalır', () => {
    const withRem = cowork.createNote({ name: 'Hatirlaticili' });
    fs.writeFileSync(withRem.note.mdPath,
      '---\ntitle: Hatirlaticili\nreminder_at: 2026-08-12T14:00:00+03:00\n'
      + 'reminder_state: pending\nreminder_attempts: 2\n---\ngovde\n', 'utf8');
    const plain = cowork.createNote({ name: 'Sade Not' });

    const notes = cowork.listNotes().notes;
    const a = notes.find((n) => n.id === withRem.note.id);
    const b = notes.find((n) => n.id === plain.note.id);

    assert.equal(a.reminderAt, '2026-08-12T14:00:00+03:00');
    assert.equal(a.reminderState, 'pending');
    assert.equal(a.reminderAttempts, 2);
    assert.equal(b.reminderAt, null, 'hatirlaticisiz notta null olmali');
    assert.equal(b.reminderState, null);
  });

  it('tırnaklı başlığın tırnağı sökülür (reminders.mjs öyle yazıyor)', () => {
    const n = cowork.createNote({ name: 'Tirnakli' });
    fs.writeFileSync(n.note.mdPath,
      '---\ntitle: "Duruşma: 2. İş Mahkemesi"\nreminder_at: 2026-08-12T14:00:00+03:00\n---\nx\n', 'utf8');
    const found = cowork.listNotes().notes.find((x) => x.id === n.note.id);
    assert.equal(found.title, 'Duruşma: 2. İş Mahkemesi');
  });

  it('yeniden adlandırma hatırlatıcıyı SİLMEZ', () => {
    // buildFrontmatter beyaz liste kullaniyor; reminder_* listede yoksa
    // rename sirasinda sessizce dusuyordu.
    const n = cowork.createNote({ name: 'Adi Degisecek' });
    fs.writeFileSync(n.note.mdPath,
      '---\ntitle: Adi Degisecek\nreminder_at: 2026-08-12T14:00:00+03:00\n'
      + 'reminder_state: pending\nreminder_attempts: 0\nsource_screenshot: Screenshot_x.jpg\n---\ngovde\n', 'utf8');

    const r = cowork.renameNote({ noteId: n.note.id, name: 'Yeni Ad' });
    assert.equal(r.ok, true, JSON.stringify(r));

    const found = cowork.listNotes().notes.find((x) => x.title === 'Yeni Ad');
    assert.ok(found, 'yeniden adlandirilmis not bulunmali');
    assert.equal(found.reminderAt, '2026-08-12T14:00:00+03:00', 'hatirlatici korunmali');
    assert.equal(found.reminderState, 'pending');
    const md = fs.readFileSync(found.mdPath, 'utf8');
    assert.match(md, /source_screenshot: Screenshot_x\.jpg/, 'kaynak alani da korunmali');
    assert.match(md, /govde/);
  });
});

describe('listNotes: kart özeti (preview)', () => {
  it('gövde tek satıra iner, markdown işaretleri sökülür', () => {
    const n = cowork.createNote({ name: 'Ozetli' });
    fs.writeFileSync(n.note.mdPath,
      '---\ntitle: Ozetli\n---\n\n# Baslik\n\n- ilk madde\n> alinti satiri\n', 'utf8');
    const found = cowork.listNotes().notes.find((x) => x.id === n.note.id);
    assert.equal(found.preview, 'Baslik ilk madde alinti satiri');
  });

  it('frontmatter özete sızmaz, uzun gövde 220 karakterde kesilir', () => {
    const n = cowork.createNote({ name: 'Uzun' });
    fs.writeFileSync(n.note.mdPath,
      '---\ntitle: Uzun\nreminder_at: 2026-08-12T14:00:00+03:00\n---\n' + 'a'.repeat(400) + '\n', 'utf8');
    const found = cowork.listNotes().notes.find((x) => x.id === n.note.id);
    assert.equal(found.preview.length, 220);
    assert.ok(!found.preview.includes('reminder_at'), 'frontmatter özete girmemeli');
  });

  it('boş notta özet boş', () => {
    const n = cowork.createNote({ name: 'Bos Govde' });
    const found = cowork.listNotes().notes.find((x) => x.id === n.note.id);
    assert.equal(found.preview, '');
  });
});

describe('searchNotes: başlık + içerik', () => {
  it('boş sorgu tam listeyi döndürür', () => {
    const hepsi = cowork.listNotes().notes.length;
    assert.equal(cowork.searchNotes('').notes.length, hepsi);
    assert.equal(cowork.searchNotes('   ').notes.length, hepsi);
  });

  it('başlıkta ve gövdede eşleşir, eşleşmeyen düşer', () => {
    const a = cowork.createNote({ name: 'Kira Sozlesmesi' });
    const b = cowork.createNote({ name: 'Alakasiz Not' });
    fs.writeFileSync(b.note.mdPath, '---\ntitle: Alakasiz Not\n---\nkira bedeli aylik 12000 TL\n', 'utf8');
    const c = cowork.createNote({ name: 'Bambaska' });
    fs.writeFileSync(c.note.mdPath, '---\ntitle: Bambaska\n---\nhicbir alaka yok\n', 'utf8');

    const ids = cowork.searchNotes('kira').notes.map((x) => x.id);
    assert.ok(ids.includes(a.note.id), 'başlık eşleşmesi');
    assert.ok(ids.includes(b.note.id), 'gövde eşleşmesi');
    assert.ok(!ids.includes(c.note.id), 'eşleşmeyen listede olmamalı');
  });

  it('gövde eşleşmesinde preview eşleşmenin çevresini gösterir', () => {
    const n = cowork.createNote({ name: 'Derinde' });
    fs.writeFileSync(n.note.mdPath,
      '---\ntitle: Derinde\n---\n' + 'dolgu '.repeat(40) + 'IBAN TR12 3456\n', 'utf8');
    const found = cowork.searchNotes('IBAN').notes.find((x) => x.id === n.note.id);
    assert.ok(found, 'gövdede geçen not bulunmalı');
    assert.ok(found.preview.includes('IBAN TR12 3456'), 'özet eşleşmeyi içermeli: ' + found.preview);
    assert.ok(found.preview.startsWith('…'), 'baştan kesildiği belli olmalı');
  });

  it('Türkçe büyük/küçük harf: İ ve I doğru katlanır', () => {
    const n = cowork.createNote({ name: 'Icra Dosyasi' });
    fs.writeFileSync(n.note.mdPath, '---\ntitle: Icra Dosyasi\n---\nİCRA takibi baslatildi\n', 'utf8');
    assert.ok(cowork.searchNotes('icra').notes.some((x) => x.id === n.note.id), 'küçük harf sorgu tutmalı');
    assert.ok(cowork.searchNotes('İCRA').notes.some((x) => x.id === n.note.id), 'büyük harf sorgu tutmalı');
  });
});

describe('listNotes: kaynak (ekran görüntüsü / elle)', () => {
  it('source_screenshot listeye taşınır, elle notta null kalır', () => {
    const otomatik = cowork.createNote({ name: 'Ekrandan Gelen' });
    fs.writeFileSync(otomatik.note.mdPath,
      '---\ntitle: Ekrandan Gelen\nsource_screenshot: Screenshot_20260807_191420.jpg\n---\ngovde\n', 'utf8');
    const elle = cowork.createNote({ name: 'Elle Yazilan' });

    const notlar = cowork.listNotes().notes;
    const a = notlar.find((x) => x.id === otomatik.note.id);
    const b = notlar.find((x) => x.id === elle.note.id);
    assert.equal(a.sourceScreenshot, 'Screenshot_20260807_191420.jpg');
    assert.equal(b.sourceScreenshot, null, 'elle oluşturulan notta kaynak olmamalı');
  });

  it('yeniden adlandırma kaynağı düşürmez (frontmatter beyaz listesi)', () => {
    const n = cowork.createNote({ name: 'Adi Degisen Ekran Notu' });
    fs.writeFileSync(n.note.mdPath,
      '---\ntitle: Adi Degisen Ekran Notu\nsource_screenshot: Screenshot_x.jpg\n---\ngovde\n', 'utf8');
    const r = cowork.renameNote({ noteId: n.note.id, name: 'Yeni Ekran Notu' });
    assert.equal(r.ok, true, JSON.stringify(r));
    const found = cowork.listNotes().notes.find((x) => x.title === 'Yeni Ekran Notu');
    assert.equal(found.sourceScreenshot, 'Screenshot_x.jpg');
  });
});
