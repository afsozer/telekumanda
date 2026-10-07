// claude-app backend — export sözleşmesi birim testleri.
// Gerçek `claude` süreci başlatılmaz; yalnız saf-mantık (slash router, setModel,
// setPermissionMode, clearSession, getInfo envanteri, snapshot defaultları) test edilir.
// Süreç başlatan yollar (prompt → ensureProc) burada kapsanmaz.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import * as app from '../claude-app.mjs';

function freshSid() {
  const ns = app.newSession({ cwd: os.homedir() });
  assert.ok(ns.ok, 'newSession ok');
  return ns.sessionId;
}

describe('claude-app: slash router', () => {
  it('/clear handled olur ve oturumu yeni id altında re-key’ler', () => {
    const sid = freshSid();
    const r = app.slash({ sessionId: sid, command: 'clear' });
    assert.equal(r.ok, true);
    assert.equal(r.handled, true);
    assert.equal(r.cleared, true);
    assert.ok(r.sessionId && r.sessionId !== sid, 'yeni sessionId verilmeli');
    // Eski id artık yok, yeni id geçerli.
    assert.equal(app.getConversation(sid).messages.length, 0); // not-found default
    const conv = app.getConversation(r.sessionId);
    assert.equal(conv.messages.length, 0);
    assert.equal(conv.cost, 0);
  });

  it('/model arg’sız hata + models listesi döndürür', () => {
    const sid = freshSid();
    const r = app.slash({ sessionId: sid, command: 'model' });
    assert.equal(r.ok, false);
    assert.equal(r.handled, true);
    assert.ok(Array.isArray(r.models) && r.models.length > 0);
  });

  it('/model <x> idle iken modeli uygular', () => {
    const sid = freshSid();
    const r = app.slash({ sessionId: sid, command: 'model', args: 'claude-opus-5' });
    assert.equal(r.ok, true);
    assert.equal(r.handled, true);
    assert.equal(r.model, 'claude-opus-5');
    assert.equal(app.getInfo(sid).model, 'claude-opus-5');
  });

  it('/compact ve custom komutlar passthrough olur (handled=false)', () => {
    const sid = freshSid();
    for (const cmd of ['compact', 'mycustomcmd']) {
      const r = app.slash({ sessionId: sid, command: cmd });
      assert.equal(r.ok, true);
      assert.equal(r.handled, false);
      assert.equal(r.passthrough, true);
    }
  });

  it('/bro passthrough kalır ama skill metnine genişler', () => {
    const sid = freshSid();
    const r = app.slash({ sessionId: sid, command: 'bro' });
    assert.equal(r.ok, true);
    assert.equal(r.handled, false, 'normal tur olarak akmalı');
    assert.equal(r.passthrough, true);
    assert.equal(r.expanded, app.PROMPT_MACROS.bro, 'CLI a skill metni gitmeli');
    assert.ok(!r.expanded.includes('/bro'), 'genişletilmiş metin komutu içermemeli');
  });

  it('/bro <arg> skill metnine ek talimat olarak iliştirir', () => {
    const sid = freshSid();
    const r = app.slash({ sessionId: sid, command: 'bro', args: 'özellikle son paragrafı' });
    assert.equal(r.ok, true);
    assert.ok(r.expanded.startsWith(app.PROMPT_MACROS.bro));
    assert.ok(r.expanded.endsWith('özellikle son paragrafı'));
  });

  it('/devret skill metnini kopyalamaz, Skill aracına yönlendirir', () => {
    const sid = freshSid();
    const r = app.slash({ sessionId: sid, command: 'devret' });
    assert.equal(r.ok, true);
    assert.equal(r.handled, false);
    assert.equal(r.passthrough, true);
    assert.equal(r.expanded, app.PROMPT_MACROS.devret);
    assert.ok(/Skill/.test(r.expanded),
      'skill metni bridge e kopyalanmamalı; tek nüsha SKILL.md de kalmalı');
  });

  // Menüye eklenip makrosu unutulan komut, stream-json -p modunda genişletme
  // olmadığı için CLI a düz metin gider ve sessizce hiçbir şey yapmaz. Bu
  // tripwire ikisini eş tutar.
  it('PROMPT_MACROS taki her komut claude-app menüsünde de var', () => {
    const src = fs.readFileSync(new URL('../server.mjs', import.meta.url), 'utf8');
    const start = src.indexOf("'claude-app': [");
    assert.ok(start > -1, 'server.mjs te claude-app slash listesi bulunamadı');
    const block = src.slice(start, src.indexOf(']', start));
    const names = [...block.matchAll(/name:\s*'([^']+)'/g)].map(m => m[1]);
    for (const macro of Object.keys(app.PROMPT_MACROS)) {
      assert.ok(names.includes(macro),
        `/${macro} makrosu var ama server.mjs slash listesinde yok`);
    }
  });

  // fb52139 + devamı: child öldüren HER yol disk-senkron imlecini sabitlemeli,
  // yoksa oturum "izleyici" sanılıp transkript s.messages'a ikinci kez eklenir
  // (model/hesap/mod/effort değişimi → son alışveriş kopyası). Tek kapı:
  // killPersistentChild. Bu tripwire yeni bir elle kill eklenirse patlar.
  it('child öldüren tüm yollar killPersistentChild üzerinden geçer', () => {
    const src = fs.readFileSync(new URL('../claude-app.mjs', import.meta.url), 'utf8');
    const calls = src.split('\n')
      .map((line, i) => ({ line: line.trim(), no: i + 1 }))
      // Sync varyantı da sayılır: çıkış kancası ona geçti, desen dışında
      // kalsaydı tripwire yeni adla sessizce atlatılabilirdi.
      .filter(r => /killChildTree(Sync)?\s*\(/.test(r.line) && !/^import\b/.test(r.line));
    // İzinli üç çağrı: killPersistentChild'ın kendisi, killAllSessions (pid/hata
    // raporu için elle öldürüp seedDiskCursorToEof çağırır) ve process exit
    // (süreç zaten ölüyor, imlecin anlamı yok — orada killChildTreeSync).
    assert.equal(calls.length, 3,
      'Yeni elle killChildTree çağrısı var. Bunun yerine killPersistentChild(s) kullan; ' +
      'zorunluysa seedDiskCursorToEof(s) ekle ve bu testi güncelle. Satırlar: ' +
      calls.map(c => c.no).join(', '));
    assert.ok(src.includes('function seedDiskCursorToEof'), 'imleç sabitleyici kaldırılmamalı');
  });

  it('bilinmeyen oturumda hata verir', () => {
    const r = app.slash({ sessionId: 'yok-boyle-id', command: 'clear' });
    assert.equal(r.ok, false);
  });
});

describe('claude-app: setModel', () => {
  it('boş model reddedilir', () => {
    const sid = freshSid();
    const r = app.setModel({ sessionId: sid, model: '' });
    assert.equal(r.ok, false);
  });
  it('idle iken modeli set eder (süreç yok)', () => {
    const sid = freshSid();
    const r = app.setModel({ sessionId: sid, model: 'haiku' });
    assert.equal(r.ok, true);
    assert.equal(r.model, 'haiku');
    assert.notEqual(r.applied, 'deferred'); // idle → hemen
  });
});

describe('claude-app: setPermissionMode normalizasyonu', () => {
  it('büyük/küçük harf duyarsız geçerli modu normalize eder', () => {
    const sid = freshSid();
    const r = app.setPermissionMode({ sessionId: sid, mode: 'acceptedits' });
    assert.equal(r.ok, true);
    assert.equal(r.permissionMode, 'acceptEdits');
  });
  it('geçersiz mod boş stringe düşer', () => {
    const sid = freshSid();
    const r = app.setPermissionMode({ sessionId: sid, mode: 'zırva' });
    assert.equal(r.ok, true);
    assert.equal(r.permissionMode, '');
  });
  it('plan modu korunur', () => {
    const sid = freshSid();
    const r = app.setPermissionMode({ sessionId: sid, mode: 'plan' });
    assert.equal(r.permissionMode, 'plan');
  });
});

describe('claude-app: getInfo envanteri', () => {
  it('models ve permissionModes listelerini döndürür', () => {
    const sid = freshSid();
    const info = app.getInfo(sid);
    assert.equal(info.ok, true);
    assert.ok(Array.isArray(info.models) && info.models.length > 0);
    assert.ok(Array.isArray(info.permissionModes));
    for (const m of ['plan', 'acceptEdits', 'default', 'bypassPermissions']) {
      assert.ok(info.permissionModes.includes(m), `permissionModes ${m} içermeli`);
    }
  });
});

describe('claude-app: snapshot cost/usage', () => {
  it('yeni oturumda cost=0, usage=null', () => {
    const sid = freshSid();
    const conv = app.getConversation(sid);
    assert.equal(conv.cost, 0);
    assert.equal(conv.usage, null);
  });
  it('bilinmeyen oturum default’u cost/usage taşır', () => {
    const conv = app.getConversation('yok-boyle-id');
    assert.equal(conv.cost, 0);
    assert.equal(conv.usage, null);
  });
});

describe('claude-app: oturum kalıcılığı', () => {
  it('__restoreSessionsFromData restores persisted claude-app shells', () => {
    const id = 'claude-restore-test-' + Date.now();
    const restored = app.__restoreSessionsFromData({
      sessions: [{ id, cwd: os.homedir(), model: 'sonnet', permissionMode: 'plan', effort: 'low', cowork: true }],
    });
    assert.equal(restored, 1);
    const found = app.listSessions().find(s => s.id === id);
    assert.ok(found);
    assert.equal(found.model, 'sonnet');
    assert.equal(found.cwd, os.homedir());
  });

  it('upgrades retired claude-opus-5 sessions to claude-opus-5-5 on restore', () => {
    const id = 'claude-restore-opus55-' + Date.now();
    app.__restoreSessionsFromData({ sessions: [{ id, cwd: os.homedir(), model: 'claude-opus-5' }] });
    assert.equal(app.listSessions().find(s => s.id === id).model, 'claude-opus-5-5');
  });
});

describe('claude-app: watcher and poll fallback', () => {
  it('abone olunduğunda ve _diskFile varsa fs.watch kurulur', () => {
    const sid = freshSid();
    const tempFile = path.join(os.tmpdir(), `test-sess-${sid}.jsonl`);
    fs.writeFileSync(tempFile, 'test data\n', 'utf-8');
    
    const mockId = 'watcher-test-' + Date.now();
    app.__restoreSessionsFromData({
      sessions: [{ id: mockId, cwd: os.homedir(), model: 'sonnet' }]
    });
    app.__setSessionDiskFile(mockId, tempFile);
    
    const mockWs = new EventEmitter();
    mockWs.send = () => {};
    app.subscribe(mockId, mockWs);
    
    const watcher = app.__getWatcher(mockId);
    assert.ok(watcher, 'Watcher set up edilmeli');
    
    mockWs.emit('close');
    const watcherAfter = app.__getWatcher(mockId);
    assert.equal(watcherAfter, null, 'Abonelik bittiğinde watcher kapatılmalı');
    
    try { fs.unlinkSync(tempFile); } catch {}
  });

  it('fs.watch hata fırlattığında sessizce poll fallbacke düşer', () => {
    const mockId = 'watcher-err-test-' + Date.now();
    const tempFile = path.join(os.tmpdir(), `test-sess-${mockId}.jsonl`);
    fs.writeFileSync(tempFile, 'test data\n', 'utf-8');
    
    app.__restoreSessionsFromData({
      sessions: [{ id: mockId, cwd: os.homedir(), model: 'sonnet' }]
    });
    app.__setSessionDiskFile(mockId, tempFile);
    
    const originalWatch = fs.watch;
    fs.watch = () => { throw new Error('Simulated watch failure'); };
    
    try {
      const mockWs = {
        on() {},
        send() {}
      };
      app.subscribe(mockId, mockWs);
      
      const watcher = app.__getWatcher(mockId);
      assert.equal(watcher, null, 'fs.watch hata fırlattığında watcher null olmalı (poll fallback)');
    } finally {
      fs.watch = originalWatch;
      try { fs.unlinkSync(tempFile); } catch {}
    }
  });
});

describe('claude-app: pin & archive metadata', () => {
  it('oturum pinlenebilir ve pinden çıkarılabilir', () => {
    const mockId = 'meta-test-pin-' + Date.now();
    const r1 = app.pinThread({ id: mockId });
    assert.equal(r1.ok, true);
    assert.equal(r1.pinned, true);

    const r2 = app.unpinThread({ id: mockId });
    assert.equal(r2.ok, true);
    assert.equal(r2.pinned, false);
  });

  it('oturum arşivlenebilir ve arşivden çıkarılabilir', () => {
    const mockId = 'meta-test-archive-' + Date.now();
    const r1 = app.archiveThread({ id: mockId });
    assert.equal(r1.ok, true);
    assert.equal(r1.archived, true);

    const r2 = app.unarchiveThread({ id: mockId });
    assert.equal(r2.ok, true);
    assert.equal(r2.archived, false);
  });
});

// Canli vaka (05.08.2026): model degisimi eski child'i oldurdu; YENI child spawn
// edildikten sonra eski child'in gecikmeli close olayi geldi, handler s.child'i
// kosulsuz null'layip sagliklı tura sahte "surec beklenmedik kapandi" basti.
// Kullanici prompt'u tekrar gonderince iki child ayni oturuma paralel yazdi ve
// her mesaj sohbette CIFT gorundu.
describe('claude-app: wireChild kimlik korumasi', () => {
  function fakeChild() {
    const c = new EventEmitter();
    c.stdout = new EventEmitter();
    c.stderr = new EventEmitter();
    c.stdin = { write: () => true, destroyed: false };
    c.pid = 0;
    return c;
  }

  it('eski child kapaninca yeni child sahiplikte kalir ve tur bozulmaz', () => {
    const sid = freshSid();
    const s = app.__getSession(sid);
    const eski = fakeChild();
    app.wireChild(s, eski);
    const yeni = fakeChild();
    app.wireChild(s, yeni); // sahiplik yeni child'a gecti (kill sonrasi respawn)
    s.status = 'running';
    eski.emit('close', 1); // gecikmeli close — canli vakadaki an
    assert.equal(s.child, yeni, 'eski child yeni sahipligi bozmamali');
    assert.equal(s.status, 'running', 'sagliklı tur sahte hatayla kapanmamali');
    assert.ok(!s.messages.some(m => /beklenmedik kapandı/.test(m.text || '')),
      'sahte "beklenmedik kapandi" mesaji basilmamali');
    s.status = 'idle'; // temizlik: diger testler etkilenmesin
    s.child = null;
  });

  it('oksuz child oturuma yazamaz', () => {
    const sid = freshSid();
    const s = app.__getSession(sid);
    const eski = fakeChild();
    app.wireChild(s, eski);
    const yeni = fakeChild();
    app.wireChild(s, yeni);
    const onceki = s.messages.length;
    // Oksuz child hala akis uretiyor (canli vakada ikinci kopyalari bu yazdi).
    eski.stdout.emit('data', JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: 'HAYALET' }] } }) + '\n');
    assert.equal(s.messages.length, onceki, 'oksuz child mesaj ekleyememeli');
    assert.ok(!s.messages.some(m => /HAYALET/.test(m.text || '')));
    s.child = null;
  });

  it('aktif child kapaninca eski davranis korunur (gercek cokme)', () => {
    const sid = freshSid();
    const s = app.__getSession(sid);
    const c = fakeChild();
    app.wireChild(s, c);
    s.status = 'running';
    c.emit('close', 1);
    assert.equal(s.child, null);
    assert.equal(s.status, 'idle');
    assert.ok(s.messages.some(m => /beklenmedik kapandı/.test(m.text || '')),
      'gercek cokme hatasi gorunmeye devam etmeli');
  });

  it('result sonrasi arka plan bildirimi gelince tur yeniden aktif olur', () => {
    const sid = freshSid();
    const s = app.__getSession(sid);
    const c = fakeChild();
    app.wireChild(s, c);
    s.status = 'idle';

    c.stdout.emit('data', JSON.stringify({
      type: 'user',
      message: {
        role: 'user',
        content: '<task-notification><status>completed</status></task-notification>',
      },
    }) + '\n');

    assert.equal(s.status, 'running', 'arka plan turu Android meta verisinde aktif gorunmeli');
    assert.equal(app.getConversation(sid).running, true);

    c.stdout.emit('data', JSON.stringify({ type: 'result', subtype: 'success' }) + '\n');
    assert.equal(s.status, 'idle', 'otonom turun kendi result olayi turu yeniden kapatmali');
    s.child = null;
  });
});
