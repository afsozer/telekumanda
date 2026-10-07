import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-omp-app-'));
process.env.AGENTBRIDGE_OMP_ROOT = root;
const omp = await import('../omp-app.mjs');

let sessionId;
let state;
let requests;

before(() => {
  const created = omp.newSession({ cwd: root, effort: 'high' });
  assert.equal(created.ok, true);
  sessionId = created.sessionId;
  state = omp.__ompAppTest.session(sessionId);
  requests = [];
  state.client = {
    child: { exitCode: null, pid: 12345 },
    async request(type, payload = {}) {
      requests.push({ type, payload });
      if (type === 'get_state') return { thinkingLevel: 'high' };
      return {};
    },
    async close() {},
    async abort() {},
  };
});

after(() => {
  omp.killAllSessions();
  delete process.env.AGENTBRIDGE_OMP_ROOT;
  fs.rmSync(root, { recursive: true, force: true });
});

describe('OMP app lifecycle and frame reducer', () => {
  it('boş effort değerini çalışan RPC childına göndererek varsayılana döner', async () => {
    assert.equal((await omp.setEffort({ sessionId, effort: '' })).ok, true);
    assert.deepEqual(requests.at(-1), { type: 'set_thinking_level', payload: { level: '' } });
    assert.equal(omp.getConversation(sessionId).effort, '');

    // Native event çözülen varsayılanı (high) bildirse bile UI seçimi explicit
    // high'a dönüşmemeli.
    omp.__ompAppTest.onFrame(state, { type: 'thinking_level_changed', level: 'high' });
    assert.equal(omp.getConversation(sessionId).effort, '');

    requests.length = 0;
    const prompted = await omp.prompt({ sessionId, text: 'resetli tur', variant: '' });
    assert.equal(prompted.ok, true);
    assert.deepEqual(requests.slice(0, 2), [
      { type: 'set_thinking_level', payload: { level: '' } },
      { type: 'prompt', payload: { message: 'resetli tur', images: [] } },
    ]);
    omp.__ompAppTest.onFrame(state, { type: 'agent_end' });
  });

  it('thinking, araç ve cevap bloklarını doğru sırada ayrı satırlara indirger', () => {
    omp.__ompAppTest.onFrame(state, { type: 'agent_start' });
    omp.__ompAppTest.onFrame(state, { type: 'message_update', assistantMessageEvent: { type: 'thinking_start' } });
    omp.__ompAppTest.onFrame(state, { type: 'message_update', assistantMessageEvent: { type: 'thinking_delta', delta: 'Önce planla' } });
    omp.__ompAppTest.onFrame(state, { type: 'message_update', assistantMessageEvent: { type: 'thinking_end' } });
    omp.__ompAppTest.onFrame(state, { type: 'tool_execution_start', toolCallId: 'tool-1', toolName: 'read', args: { path: 'a.txt' } });
    omp.__ompAppTest.onFrame(state, { type: 'tool_execution_update', toolCallId: 'tool-1', partialResult: 'yarısı' });
    omp.__ompAppTest.onFrame(state, { type: 'tool_execution_end', toolCallId: 'tool-1', result: 'tamam' });
    omp.__ompAppTest.onFrame(state, { type: 'message_update', assistantMessageEvent: { type: 'text_start' } });
    omp.__ompAppTest.onFrame(state, { type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'Son cevap' } });
    omp.__ompAppTest.onFrame(state, { type: 'message_update', assistantMessageEvent: { type: 'text_end' } });
    omp.__ompAppTest.onFrame(state, { type: 'agent_end' });

    const conversation = omp.getConversation(sessionId);
    const tail = conversation.messages.slice(-3);
    assert.deepEqual(tail.map(row => row.role), ['thought', 'thought', 'agent']);
    assert.match(tail[0].text, /Önce planla/);
    assert.match(tail[1].text, /read/);
    assert.equal(tail[2].text, 'Son cevap');
    assert.match(omp.getThought(sessionId, tail[1].thoughtIndex).text, /yarısı[\s\S]*tamam/);
    assert.equal(conversation.running, false);
    assert.equal(conversation.awaitingFirstOutput, false);
  });
});

describe('OMP tur donması nöbetçisi', () => {
  const { OMP_TURN_INACTIVITY_MS, OMP_MAX_PROBE_FAILURES } = omp.__ompAppTest.constants;

  function stalledSession(id, { respondsToProbe }) {
    const s = omp.__ompAppTest.freshSession(id, root, 'deepseek/deepseek-v4-pro', 'yolo');
    s.status = 'running';
    // Son aktivite atıllık eşiğinin ötesinde: nöbetçi yoklamaya girsin.
    s._lastActivity = Date.now() - OMP_TURN_INACTIVITY_MS - 1_000;
    s.client = {
      child: { exitCode: null, pid: 4242 },
      closed: false,
      async request(type) {
        if (type === 'get_state') {
          if (respondsToProbe) return { thinkingLevel: 'high' };
          // Kilitli child: yoklama zaman aşımına uğrar.
          throw new Error('OMP RPC get_state timeout after 8000ms');
        }
        return {};
      },
      async close() { this.closed = true; this.child.exitCode = 0; },
      async abort() {},
    };
    return omp.__ompAppTest.addSession(s);
  }

  it('child get_state yoklamasına cevap veriyorsa uzun turu KESMEZ', async () => {
    const s = stalledSession('wd-alive', { respondsToProbe: true });
    await omp.__ompAppTest.ompWatchdogTick();
    assert.equal(s.status, 'running', 'yaşayan child turu açık kalmalı');
    assert.equal(s.client.closed, false, 'child düşürülmemeli');
    assert.equal(s._staleProbes, 0, 'başarılı yoklama sayacı sıfırlar');
    omp.__ompAppTest.removeSession('wd-alive');
  });

  it('ard arda yanıtsız yoklamadan sonra turu kapatıp child düşürür', async () => {
    const s = stalledSession('wd-stalled', { respondsToProbe: false });
    // İlk K-1 tick yalnız sayacı artırır, turu kesmez.
    for (let i = 1; i < OMP_MAX_PROBE_FAILURES; i++) {
      await omp.__ompAppTest.ompWatchdogTick();
      assert.equal(s.status, 'running', `${i}. yanıtsız yoklamada henüz kapatılmamalı`);
      assert.equal(s._staleProbes, i);
      // Nöbetçi yalnız zamana bakıyor; bir sonraki tick'te tekrar eşiği aşsın.
      s._lastActivity = Date.now() - OMP_TURN_INACTIVITY_MS - 1_000;
    }
    await omp.__ompAppTest.ompWatchdogTick();
    assert.equal(s.status, 'idle', 'K. yanıtsız yoklamada tur kapanmalı');
    assert.equal(s.client, null, 'child referansı düşürülmeli');
    assert.match(s.messages.at(-1).text, /yanıt vermiyor/);
    omp.__ompAppTest.removeSession('wd-stalled');
  });

  it('onay bekleyen turu (pendingApproval) atıl sayıp kapatmaz', async () => {
    const s = stalledSession('wd-approval', { respondsToProbe: false });
    s.pendingApproval = { requestId: 'x', method: 'confirm' };
    await omp.__ompAppTest.ompWatchdogTick();
    assert.equal(s.status, 'running');
    assert.equal(s._staleProbes, 0, 'onay bekleyen tur hiç yoklanmamalı');
    omp.__ompAppTest.removeSession('wd-approval');
  });

  it('atıllık eşiği dolmamış turu yoklamaz', async () => {
    const s = stalledSession('wd-fresh', { respondsToProbe: false });
    s._lastActivity = Date.now(); // taze aktivite
    await omp.__ompAppTest.ompWatchdogTick();
    assert.equal(s.status, 'running');
    assert.equal(s._staleProbes, 0);
    omp.__ompAppTest.removeSession('wd-fresh');
  });
});

describe('Aynı transkripte bağlı kabukların tekilleştirilmesi', () => {
  const DISK_ID = '019ff7a6-6f50-7000-b14f-355e8784b998';
  const file = path.join(root, `${DISK_ID}.jsonl`);

  function shell(id, { ompSessionId = DISK_ID, sessionFile = file, lastUserAt = 0, messages = [] } = {}) {
    const s = omp.__ompAppTest.freshSession(id, root, 'deepseek/deepseek-v4-pro', 'yolo');
    s.ompSessionId = ompSessionId; s.sessionFile = sessionFile;
    s.lastUserAt = lastUserAt; s.messages = messages;
    return omp.__ompAppTest.addSession(s);
  }
  function clear(...ids) { for (const id of ids) omp.__ompAppTest.removeSession(id); }

  it('köprü kabuğu id\'si, disk id\'si ve transkript yolu aynı oturuma çözülür', () => {
    shell('dedupe-solo');
    assert.equal(omp.__ompAppTest.resolveSession('dedupe-solo')?.id, 'dedupe-solo');
    assert.equal(omp.__ompAppTest.resolveSession(DISK_ID)?.id, 'dedupe-solo');
    assert.equal(omp.__ompAppTest.resolveSession(file)?.id, 'dedupe-solo');
    assert.equal(omp.__ompAppTest.resolveSession('yok-böyle-bir-şey'), null);
    clear('dedupe-solo');
  });

  it('kopya kabuklar en taze olanda birleşir, eski id alias üstünden çözülür', () => {
    // Canlıda ölçülen durum: tek diskId'ye bağlı üç kabuk, biri geride kalmış.
    shell('dedupe-stale', { lastUserAt: 1_000, messages: [{ role: 'user', text: 'eski' }] });
    shell('dedupe-fresh', { lastUserAt: 9_000, messages: [{ role: 'user', text: 'yeni' }] });
    shell('dedupe-blank', { lastUserAt: 0 });

    const kept = omp.__ompAppTest.dedupeTranscript(DISK_ID);
    assert.equal(kept.id, 'dedupe-fresh');
    assert.equal(omp.listSessions().filter(s => s.diskId === DISK_ID).length, 1);
    // Elinde eski kabuk id'si kalan cihaz "session not found" almamalı.
    assert.equal(omp.__ompAppTest.resolveSession('dedupe-stale')?.id, 'dedupe-fresh');
    assert.equal(omp.__ompAppTest.resolveSession('dedupe-blank')?.id, 'dedupe-fresh');
    assert.equal(omp.__ompAppTest.aliasOf('dedupe-stale'), 'dedupe-fresh');
    clear('dedupe-stale', 'dedupe-fresh', 'dedupe-blank');
  });

  it('birleştirme aboneleri kalan kabuğa taşır ve kopyanın childını kapatır', () => {
    const sent = [];
    const ws = { send: v => sent.push(v), on() {} };
    let closed = false;
    const dup = shell('dedupe-dup', { lastUserAt: 1 });
    dup.subscribers.add(ws);
    dup.client = { child: { exitCode: null }, async close() { closed = true; }, async request() { return {}; } };
    shell('dedupe-keep', { lastUserAt: 2 });

    const kept = omp.__ompAppTest.dedupeTranscript(DISK_ID);
    assert.equal(kept.id, 'dedupe-keep');
    assert.equal(kept.subscribers.has(ws), true);
    assert.equal(dup.client, null);
    assert.equal(closed, true);
    assert.equal(JSON.parse(sent.at(-1)).sessionId, 'dedupe-keep');
    clear('dedupe-dup', 'dedupe-keep');
  });

  it('farklı transkriptler birleşmez', () => {
    shell('dedupe-a', { ompSessionId: 'disk-a', sessionFile: path.join(root, 'a.jsonl') });
    shell('dedupe-b', { ompSessionId: 'disk-b', sessionFile: path.join(root, 'b.jsonl') });
    assert.equal(omp.__ompAppTest.dedupeTranscript('disk-a').id, 'dedupe-a');
    assert.equal(omp.__ompAppTest.resolveSession('dedupe-b')?.id, 'dedupe-b');
    clear('dedupe-a', 'dedupe-b');
  });
});

describe('OMP delta yayını', () => {
  function fakeWs() {
    const ws = { sent: [], handlers: {}, send(v) { ws.sent.push(JSON.parse(v)); }, on(ev, fn) { ws.handlers[ev] = fn; }, close() {} };
    return ws;
  }
  function streamShell(id) {
    const s = omp.__ompAppTest.freshSession(id, root, 'deepseek/deepseek-v4-pro', 'yolo');
    // sessionFile boş: subscribe içindeki ensureClient tetiklenmesin (testte child yok).
    return omp.__ompAppTest.addSession(s);
  }

  it('delta abonesi küçük ops alır, düz abone her push’ta tam snapshot alır', async () => {
    const s = streamShell('delta-akis');
    const plain = fakeWs();
    const delta = fakeWs();
    omp.subscribe('delta-akis', plain, {});
    omp.subscribe('delta-akis', delta, { delta: true });
    // İkisi de açılışta tam konuşma almalı (delta modunda taban bu snapshot'tır).
    assert.equal(plain.sent[0].type, 'conversation');
    assert.equal(delta.sent[0].type, 'conversation');

    omp.__ompAppTest.onFrame(s, { type: 'agent_start' });
    omp.__ompAppTest.onFrame(s, { type: 'message_update', assistantMessageEvent: { type: 'text_start' } });
    omp.__ompAppTest.onFrame(s, { type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'Merhaba' } });
    // Kısıcının ertelediği kareyi bekle. Sabit süre YAZMA (THROTTLE_MS
    // ayarlanabilir; 120→350 değişince bu test kırılmıştı) ve "bir mesaj geldi"
    // ile de yetinme — ilk delta yalnız setMeta olabiliyor. Asıl beklenen şey
    // metin op'u; koşul o.
    const hasTextOp = () => delta.sent.slice(1).flatMap(m => m.ops || [])
      .some(op => op.op === 'appendRow' || op.op === 'appendText');
    for (let i = 0; i < 100 && !hasTextOp(); i++) await new Promise(r => setTimeout(r, 20));

    const deltaMsgs = delta.sent.slice(1);
    assert.ok(deltaMsgs.length >= 1, 'delta abonesi en az bir delta almalı');
    assert.ok(deltaMsgs.every(m => m.type === 'delta'), 'delta abonesine tam snapshot gitmemeli');
    const ops = deltaMsgs.flatMap(m => m.ops);
    assert.ok(ops.some(op => op.op === 'appendRow' || op.op === 'appendText'), 'metin ops olarak akmalı');
    // Düz abone eski davranışı görür: her push tam 'conversation'.
    assert.ok(plain.sent.slice(1).every(m => m.type === 'conversation'));
    assert.ok(plain.sent.length >= 2);
    omp.__ompAppTest.removeSession('delta-akis');
  });

  it('tur sonu kısıcıyı beklemez ve running=false delta ile duyurulur', async () => {
    const s = streamShell('delta-final');
    const delta = fakeWs();
    omp.subscribe('delta-final', delta, { delta: true });
    s.status = 'running';
    omp.__ompAppTest.onFrame(s, { type: 'message_update', assistantMessageEvent: { type: 'text_start' } });
    omp.__ompAppTest.onFrame(s, { type: 'agent_end' }); // finishTurn -> pushNow (flush)
    // Flush SENKRON gönderir; bekleme bilerek yok.
    const metaOps = delta.sent.slice(1).flatMap(m => m.ops || []).filter(op => op.op === 'setMeta');
    assert.ok(metaOps.some(op => op.meta.running === false), 'running=false delta ile gelmeli');
    omp.__ompAppTest.removeSession('delta-final');
  });

  it('kabuk birleştirmede taşınan abonenin delta tercihi korunur', () => {
    const DISK='019ff7a6-6f50-7000-b14f-355e8784b998';
    const file=path.join(root, DISK + '.jsonl');
    const mk=(id,last)=>{ const s=omp.__ompAppTest.freshSession(id, root,'deepseek/deepseek-v4-pro','yolo');
      s.ompSessionId=DISK; s.sessionFile=file; s.lastUserAt=last; return omp.__ompAppTest.addSession(s); };
    const dup=mk('merge-dup',1); const keep=mk('merge-keep',2);
    const ws=fakeWs();
    // sessionFile dolu ama dosya yok: ensureClient çağrılmasın diye client taklidi ver.
    dup.client={child:{exitCode:null},async close(){},async request(){return{}}};
    keep.client={child:{exitCode:null},async close(){},async request(){return{}}};
    omp.subscribe('merge-dup', ws, { delta: true });
    assert.equal(ws._deltaMode, true);
    const kept=omp.__ompAppTest.dedupeTranscript(DISK);
    assert.equal(kept.id,'merge-keep');
    assert.equal(ws._deltaMode, true, 'birleştirme delta kipini düşürmemeli');
    assert.equal(ws.sent.at(-1).type, 'conversation'); // yeni oturum tam snapshot'la kurulur
    assert.equal(ws.sent.at(-1).sessionId, 'merge-keep');
    omp.__ompAppTest.removeSession('merge-dup'); omp.__ompAppTest.removeSession('merge-keep');
  });
});

describe('OMP sekme işlemleri (sabitle / adlandır / arşivle)', () => {
  const DISK_ID = '019ffabc-0000-7000-9999-aaaabbbbcccc';
  const META = path.join(root, 'agentbridge-session-meta.json');
  const SESS_DIR = path.join(root, 'agentbridge-sessions');
  const file = path.join(SESS_DIR, `2026-08-13T00-00-00-000Z_${DISK_ID}.jsonl`);

  function writeTranscript() {
    fs.mkdirSync(SESS_DIR, { recursive: true });
    fs.writeFileSync(file, [
      JSON.stringify({ type: 'session', id: DISK_ID, cwd: root }),
      JSON.stringify({ type: 'message', message: { role: 'user', content: [{ type: 'text', text: 'ilk soru' }] } }),
    ].join('\n') + '\n', 'utf8');
  }
  function reset() {
    try { fs.unlinkSync(META); } catch {}
    try { fs.unlinkSync(file); } catch {}
  }

  it('sabitleme diske yazılır ve liste sabitliyi başa alır', async () => {
    reset(); writeTranscript();
    assert.equal(omp.pinThread({ id: DISK_ID }).pinned, true);
    const { sessions } = await omp.listDiskSessions();
    const row = sessions.find(s => s.id === DISK_ID);
    assert.equal(row.pinned, true);
    assert.equal(sessions[0].id, DISK_ID, 'sabitlenen kayıt listenin başında olmalı');
    assert.equal(omp.unpinThread({ id: DISK_ID }).pinned, false);
    reset();
  });

  it('yeniden adlandırma OMP başlığını ezer, boş başlık geri alır', async () => {
    reset(); writeTranscript();
    omp.renameThread({ id: DISK_ID, title: '  Trafik   tazminat dosyası  ' });
    let row = (await omp.listDiskSessions()).sessions.find(s => s.id === DISK_ID);
    assert.equal(row.title, 'Trafik tazminat dosyası', 'boşluklar sadeleşmeli');
    omp.renameThread({ id: DISK_ID, title: '' });
    row = (await omp.listDiskSessions()).sessions.find(s => s.id === DISK_ID);
    assert.equal(row.title, 'ilk soru', 'boş başlık OMP’nin kendi başlığına dönmeli');
    reset();
  });

  it('arşivlenen kayıt varsayılan listede yok, arşiv sorgusunda var', async () => {
    reset(); writeTranscript();
    omp.archiveThread({ id: DISK_ID });
    const plain = await omp.listDiskSessions();
    assert.equal(plain.sessions.some(s => s.id === DISK_ID), false);
    const archived = await omp.listDiskSessionsWithQuery({ archived: true });
    assert.equal(archived.sessions.some(s => s.id === DISK_ID), true);
    omp.unarchiveThread({ id: DISK_ID });
    assert.equal((await omp.listDiskSessions()).sessions.some(s => s.id === DISK_ID), true);
    reset();
  });

  it('canlı kabuk uuid’si ile gelen istek KALICI disk kimliğine yazılır', async () => {
    // Kabuk uuid'si her adopt'ta yenileniyor; onunla anahtarlansaydı sabitleme
    // bir sonraki açılışta kaybolurdu.
    reset(); writeTranscript();
    const s = omp.__ompAppTest.freshSession('kabuk-uuid', root, 'deepseek/deepseek-v4-pro', 'yolo');
    s.ompSessionId = DISK_ID; s.sessionFile = file;
    omp.__ompAppTest.addSession(s);
    omp.pinThread({ id: 'kabuk-uuid' });
    const meta = JSON.parse(fs.readFileSync(META, 'utf8'));
    assert.deepEqual(Object.keys(meta), [DISK_ID], 'meta kalıcı kimlikle anahtarlanmalı');
    // Canlı liste de kullanıcı başlığını ve sabitlemeyi göstermeli (sekme adı buradan).
    omp.renameThread({ id: 'kabuk-uuid', title: 'Elle verilen ad' });
    const live = omp.listSessions().find(x => x.id === 'kabuk-uuid');
    assert.equal(live.title, 'Elle verilen ad');
    assert.equal(live.pinned, true);
    omp.__ompAppTest.removeSession('kabuk-uuid');
    reset();
  });

  it('arama başlık ve son metinde eşleşir', async () => {
    reset(); writeTranscript();
    omp.renameThread({ id: DISK_ID, title: 'Kira sözleşmesi' });
    assert.equal((await omp.listDiskSessionsWithQuery({ query: 'kira' })).sessions.length, 1);
    assert.equal((await omp.listDiskSessionsWithQuery({ query: 'bulunmayan' })).sessions.length, 0);
    reset();
  });
});

describe('OMP slash komut kataloğu', () => {
  it('RPC komut listesi app şekline eşlenir; adı olmayan kayıt elenir', () => {
    const mapped = omp.__ompAppTest.slashFromRpcCommands([
      { name: 'compact', description: 'Compact the conversation', input: { hint: '[talimat]' } },
      { name: 'skill:uyap', description: 'UYAP indirici' },
      { description: 'adsız' },
      null,
    ]);
    assert.deepEqual(mapped, [
      { name: 'compact', desc: 'Compact the conversation', hint: '[talimat]' },
      { name: 'skill:uyap', desc: 'UYAP indirici', hint: '' },
    ]);
  });

  it('available_commands_update frame cachei doldurur, getSlashCommands oradan döner', async () => {
    const s = omp.__ompAppTest.session(sessionId);
    omp.__ompAppTest.onFrame(s, { type: 'available_commands_update', commands: [{ name: 'memory', description: 'Manage memory' }] });
    const commands = await omp.getSlashCommands();
    assert.deepEqual(commands, [{ name: 'memory', desc: 'Manage memory', hint: '' }]);
    assert.equal(requests.some(r => r.type === 'get_available_commands'), false);
  });

  it('cache bayatlayınca canlı childa sorar; boş cevap eski listeyi korur', async () => {
    omp.__ompAppTest.slashCache().at = 0;
    const commands = await omp.getSlashCommands();
    assert.equal(requests.some(r => r.type === 'get_available_commands'), true);
    assert.deepEqual(commands, [{ name: 'memory', desc: 'Manage memory', hint: '' }]);
  });
});

describe('OMP içerik araması (searchDiskSessions)', () => {
  const DISK_ID = '019ffdef-1111-7000-8888-ddddeeeeffff';
  const SESS_DIR = path.join(root, 'agentbridge-sessions');
  const file = path.join(SESS_DIR, `2026-08-13T01-00-00-000Z_${DISK_ID}.jsonl`);

  it('başlık VE mesaj içeriği eşleşir; asistan rolü agent olarak döner', () => {
    fs.mkdirSync(SESS_DIR, { recursive: true });
    fs.writeFileSync(file, [
      JSON.stringify({ type: 'session', id: DISK_ID, cwd: root }),
      JSON.stringify({ type: 'message', message: { role: 'user', content: [{ type: 'text', text: 'emülatör kurulumuna bak' }] } }),
      JSON.stringify({ type: 'message', message: { role: 'assistant', content: [{ type: 'text', text: 'Emülatör 5555 portunda ayakta.' }] } }),
      JSON.stringify({ type: 'message', message: { role: 'toolResult', content: [{ type: 'text', text: 'emülatör log satırı — araçtan' }] } }),
    ].join('\n') + '\n', 'utf8');

    // Dönüş {hits, truncated} (18.08.2026): süre bütçesinde kesilip kesilmediği
    // çağırana söylenmek zorunda, globalSearch bunu tahmin edemiyor.
    const sonuc = omp.searchDiskSessions({ query: 'emülatör' });
    assert.equal(sonuc.truncated, false, 'bütçe verilmediyse kesilmemiş olmalı');
    const hits = sonuc.hits;
    const roles = hits.filter(h => h.type === 'message').map(h => h.role).sort();
    // toolResult aramaya girmez (claude/codex ile aynı: yalnız user/agent).
    assert.deepEqual(roles, ['agent', 'user']);
    assert.ok(hits.every(h => h.sessionId === DISK_ID));
    // Tekrarlanan sorgu önbellekten gelir ve aynı sonucu verir.
    assert.equal(omp.searchDiskSessions({ query: 'emülatör' }).hits.length, hits.length);

    // Süresi geçmiş bir son tarih verilirse hiç taramadan kesilir ve bunu söyler.
    const kesik = omp.searchDiskSessions({ query: 'emülatör', deadline: Date.now() - 1 });
    assert.equal(kesik.truncated, true);
    assert.equal(kesik.hits.length, 0);
    fs.unlinkSync(file);
  });
});

describe('OMP çatallama (fork-from)', () => {
  it('aktif dal SON kaydın parentId zinciridir; bayat dalın turları sayılmaz', () => {
    // 13.08 olayındaki dosya şekli: tam sohbet bir dalda, dosyanın son kaydı
    // bayat dalda. OMP resume son kayıttan yürüyor; sayım da aynı kuralı
    // izlemeli, yoksa "sondan N'inci tur" child'ın gördüğünden kayar.
    const file = path.join(root, 'fork-zincir.jsonl');
    const rec = (id, parentId, role, text) => JSON.stringify(
      role ? { type: 'message', id, parentId, message: { role, content: [{ type: 'text', text }] } }
           : { type: 'model_change', id, parentId });
    fs.writeFileSync(file, [
      JSON.stringify({ type: 'session', id: 'x', cwd: root }),
      rec('u1', null, 'user', 'birinci'),
      rec('a1', 'u1', 'assistant', 'cevap 1'),
      rec('u2', 'a1', 'user', 'ikinci — tam dal'),
      rec('a2', 'u2', 'assistant', 'cevap 2'),
      // Bayat dal a1'den çatallanıyor ve dosyanın SONUNA yazılmış:
      rec('m1', 'a1', null),
      rec('u2b', 'm1', 'user', 'ikinci — bayat dal'),
    ].join('\n') + '\n', 'utf8');

    const users = omp.__ompAppTest.activeChainUserNodes(file);
    assert.deepEqual(users.map(u => u.id), ['u1', 'u2b'], 'zincir son kayıttan köke yürümeli');
    fs.unlinkSync(file);
  });

  it('doğrulamalar: bilinmeyen oturum, drop<1, dosyasız oturum, süren tur', async () => {
    assert.equal((await omp.forkFromMessage({ sessionId: 'yok', dropUserTurns: 1 })).ok, false);

    const s = omp.__ompAppTest.freshSession('fork-dogrulama', root, 'deepseek/deepseek-v4-pro', 'yolo');
    omp.__ompAppTest.addSession(s);
    assert.match((await omp.forkFromMessage({ sessionId: 'fork-dogrulama', dropUserTurns: 0 })).error, /dropUserTurns/);
    assert.match((await omp.forkFromMessage({ sessionId: 'fork-dogrulama', dropUserTurns: 1 })).error, /transkript/);
    s.status = 'running'; s.sessionFile = path.join(root, 'hicyok.jsonl');
    assert.match((await omp.forkFromMessage({ sessionId: 'fork-dogrulama', dropUserTurns: 1 })).error, /tur sürerken/);
    omp.__ompAppTest.removeSession('fork-dogrulama');
  });

  it('sondan drop kadar sayınca hedef doğru user girdisidir', () => {
    const file = path.join(root, 'fork-say.jsonl');
    const rec = (id, parentId, text) => JSON.stringify({ type: 'message', id, parentId, message: { role: 'user', content: [{ type: 'text', text }] } });
    fs.writeFileSync(file, [rec('u1', null, 'bir'), rec('u2', 'u1', 'iki'), rec('u3', 'u2', 'üç')].join('\n') + '\n', 'utf8');
    const users = omp.__ompAppTest.activeChainUserNodes(file);
    // drop=1 → son turdan çatalla → hedef u3; drop=3 → en baştan → u1.
    assert.equal(users[users.length - 1].id, 'u3');
    assert.equal(users[users.length - 3].id, 'u1');
    fs.unlinkSync(file);
  });
});

describe('OMP tur sürerken gönderim (steer / follow_up)', () => {
  function runningShell(id, { onRequest } = {}) {
    const s = omp.__ompAppTest.freshSession(id, root, 'deepseek/deepseek-v4-pro', 'yolo');
    s.status = 'running';
    s.client = {
      child: { exitCode: null, pid: 999 },
      calls: [],
      async request(type, payload) { this.calls.push({ type, payload }); if (onRequest) onRequest(type); return {}; },
      async close() {}, async abort() {},
    };
    return omp.__ompAppTest.addSession(s);
  }

  it('steer native metodu çağırır ve kullanıcı satırını YEREL ekler', async () => {
    // OMP mesajı kendi transkriptine yazıyor ama bize user FRAME'i gelmiyor
    // (reducer yalnız asistan olaylarını satıra çeviriyor) — satır eklenmezse
    // gönderilen metin sohbette hiç görünmez.
    const s = runningShell('steer-1');
    const before = s.messages.length;
    const r = await omp.steer({ sessionId: 'steer-1', text: '  dur, plan değişti  ' });
    assert.equal(r.ok, true);
    assert.deepEqual(s.client.calls.at(-1), { type: 'steer', payload: { message: 'dur, plan değişti' } });
    assert.equal(s.messages.length, before + 1);
    assert.deepEqual(
      { role: s.messages.at(-1).role, text: s.messages.at(-1).text },
      { role: 'user', text: 'dur, plan değişti' },
    );
    // Nöbetçi penceresi tazelenmeli: kullanıcı müdahalesi aktivitedir.
    assert.ok(s._lastActivity > 0);
    assert.equal(s._staleProbes, 0);
    omp.__ompAppTest.removeSession('steer-1');
  });

  it('follow_up ayrı native metodu çağırır', async () => {
    const s = runningShell('fu-1');
    assert.equal((await omp.followUp({ sessionId: 'fu-1', text: 'sonra şunu yap' })).ok, true);
    assert.equal(s.client.calls.at(-1).type, 'follow_up');
    omp.__ompAppTest.removeSession('fu-1');
  });

  it('tur sürmüyorsa reddeder — normal prompt yoluna yönlendirir', async () => {
    const s = runningShell('idle-1');
    s.status = 'idle';
    const r = await omp.steer({ sessionId: 'idle-1', text: 'merhaba' });
    assert.equal(r.ok, false);
    assert.match(r.error, /tur sürmüyor/);
    assert.equal(s.client.calls.length, 0, 'RPC hiç çağrılmamalı');
    omp.__ompAppTest.removeSession('idle-1');
  });

  it('boş metin, bilinmeyen oturum ve ölü child reddedilir', async () => {
    assert.equal((await omp.steer({ sessionId: 'yok', text: 'x' })).ok, false);
    const s = runningShell('bos-1');
    assert.match((await omp.steer({ sessionId: 'bos-1', text: '   ' })).error, /text required/);
    s.client.child.exitCode = 0;
    assert.match((await omp.steer({ sessionId: 'bos-1', text: 'x' })).error, /canlı OMP süreci yok/);
    omp.__ompAppTest.removeSession('bos-1');
  });

  it('RPC hata verirse kullanıcı satırı EKLENMEZ', async () => {
    // Yoksa gönderilmemiş mesaj sohbette gönderilmiş gibi durur.
    const s = runningShell('hata-1', { onRequest: () => { throw new Error('rpc patladı'); } });
    const before = s.messages.length;
    const r = await omp.steer({ sessionId: 'hata-1', text: 'gitmeyecek' });
    assert.equal(r.ok, false);
    assert.equal(s.messages.length, before);
    omp.__ompAppTest.removeSession('hata-1');
  });
});

describe('OMP child devri (switch_session / new_session)', () => {
  // omp.exe ~157 MB ve açılışı 1-2 sn; boştaki child'ı devretmek ölçümde ~100 ms.
  function fakeClient() {
    return {
      child: { exitCode: null, pid: 4321 },
      calls: [],
      handlers: {},
      closed: false,
      on(ev, fn) { (this.handlers[ev] ||= []).push(fn); },
      emit(ev, payload) { (this.handlers[ev] || []).forEach(fn => fn(payload)); },
      async request(type, payload) { this.calls.push({ type, payload }); if (type === 'get_state') return {}; if (type === 'get_messages') return { messages: [] }; return {}; },
      async close() { this.closed = true; },
      async abort() {},
    };
  }
  function shell(id, { cwd = root, permissionMode = 'yolo', sessionFile = '', client = null, status = 'idle' } = {}) {
    const s = omp.__ompAppTest.freshSession(id, cwd, 'deepseek/deepseek-v4-pro', permissionMode);
    s.sessionFile = sessionFile; s.status = status;
    // Gerçek bindClient'tan geçir: `_bound`'u elle işaretlemek kare/çıkış
    // işleyicilerini kurmadan bırakıyor ve devir testi sessizce anlamsızlaşıyor.
    if (client) omp.__ompAppTest.bindClient(client, s);
    return omp.__ompAppTest.addSession(s);
  }
  const drop = (...ids) => ids.forEach(id => omp.__ompAppTest.removeSession(id));

  it('boştaki child devredilir; dosyası olan oturum switch_session ile yüklenir', async () => {
    const file = path.join(root, 'devir.jsonl');
    fs.writeFileSync(file, '{}\n', 'utf8');
    const c = fakeClient();
    const donor = shell('devir-donor', { client: c });
    const target = shell('devir-hedef', { sessionFile: file });

    const got = await omp.__ompAppTest.adoptIdleClient(target);
    assert.equal(got, c, 'aynı child devredilmeli — yeni süreç açılmamalı');
    assert.deepEqual(c.calls.at(-1), { type: 'switch_session', payload: { sessionPath: file } });
    assert.equal(donor.client, null, 'verici kabuk child’ı bırakmalı');
    assert.equal(target.client, c);
    assert.equal(c.closed, false, 'devirde süreç KAPATILMAMALI');
    fs.unlinkSync(file); drop('devir-donor', 'devir-hedef');
  });

  it('dosyası olmayan yeni oturum new_session ile aynı childa kurulur', async () => {
    const c = fakeClient();
    shell('yeni-donor', { client: c });
    const target = shell('yeni-hedef');
    assert.equal(await omp.__ompAppTest.adoptIdleClient(target), c);
    assert.deepEqual(c.calls.at(-1), { type: 'new_session', payload: {} });
    drop('yeni-donor', 'yeni-hedef');
  });

  it('devredilen childın kareleri YENİ sahibe akar', async () => {
    // Kare işleyicisi closure'daki kabuğa sabitlenseydi, devirden sonra
    // mesajlar eski sohbete düşerdi — bu testin tek amacı o tuzağı tutmak.
    const c = fakeClient();
    const donor = shell('kare-donor', { client: c });
    // bindClient'ın gerçek işleyicisini kur: ensureClient yolundan geçelim.
    const target = shell('kare-hedef');
    await omp.__ompAppTest.ensureClient(target);
    assert.equal(target.client, c);
    const before = donor.messages.length;
    c.emit('frame', { type: 'agent_start' });
    c.emit('frame', { type: 'message_update', assistantMessageEvent: { type: 'text_start' } });
    c.emit('frame', { type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'yeni sahibe' } });
    assert.equal(donor.messages.length, before, 'eski sahibe satır düşmemeli');
    assert.ok(target.messages.some(m => m.text?.includes('yeni sahibe')), 'yeni sahip satırı almalı');
    drop('kare-donor', 'kare-hedef');
  });

  it('cwd ya da izin kipi farklıysa devir YAPILMAZ', async () => {
    // cwd ve approval-mode süreç argümanı: uyuşmazsa araçlar yanlış klasörde
    // ya da yanlış onay kipinde çalışırdı.
    const c1 = fakeClient(); shell('uyum-cwd-donor', { client: c1, cwd: path.join(root, 'baska') });
    const t1 = shell('uyum-cwd-hedef');
    assert.equal(await omp.__ompAppTest.adoptIdleClient(t1), null);

    const c2 = fakeClient(); shell('uyum-izin-donor', { client: c2, permissionMode: 'ask' });
    const t2 = shell('uyum-izin-hedef', { permissionMode: 'yolo' });
    assert.equal(await omp.__ompAppTest.adoptIdleClient(t2), null);
    drop('uyum-cwd-donor', 'uyum-cwd-hedef', 'uyum-izin-donor', 'uyum-izin-hedef');
  });

  it('çalışan, onay bekleyen ya da izlenen kabuğun childı devredilmez', async () => {
    const cRun = fakeClient(); shell('mesgul-donor', { client: cRun, status: 'running' });
    const t1 = shell('mesgul-hedef');
    assert.equal(await omp.__ompAppTest.adoptIdleClient(t1), null);

    const cSub = fakeClient(); const d = shell('izlenen-donor', { client: cSub });
    d.subscribers.add({ send() {}, on() {} });
    const t2 = shell('izlenen-hedef');
    assert.equal(await omp.__ompAppTest.adoptIdleClient(t2), null, 'abonesi olan kabuk soğutulmamalı');
    drop('mesgul-donor', 'mesgul-hedef', 'izlenen-donor', 'izlenen-hedef');
  });

  it('switch_session hata verirse devir iptal, verici childını KORUR', async () => {
    const file = path.join(root, 'devir-hata.jsonl');
    fs.writeFileSync(file, '{}\n', 'utf8');
    const c = fakeClient();
    c.request = async function (type) { this.calls.push({ type }); if (type === 'switch_session') throw new Error('switch reddedildi'); return {}; };
    const donor = shell('hata-donor', { client: c });
    const target = shell('hata-hedef', { sessionFile: file });
    assert.equal(await omp.__ompAppTest.adoptIdleClient(target), null, 'başarısız devirde null dönüp spawn’a düşmeli');
    assert.equal(donor.client, c, 'verici childını kaybetmemeli');
    assert.equal(target.client, null);
    fs.unlinkSync(file); drop('hata-donor', 'hata-hedef');
  });
});

describe('model kataloğu süzgeci', () => {
  const model = (provider, id, cost) => (cost === undefined ? { provider, id } : { provider, id, cost });

  it('OpenCode Go düz abonelik: hiçbir model elenmez', () => {
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-go', 'qwen3.8-max', { input: 2, output: 8 })), true);
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-go', 'kimi-k3', { input: 1, output: 3 })), true);
  });

  it('diğer sağlayıcılar süzülmez', () => {
    assert.equal(omp.isAgentBridgeOmpModel(model('deepseek', 'deepseek-v4-pro', { input: 0.5, output: 2 })), true);
  });

  it('OpenCode Zen: yalnız adı VE fiyatı bedava diyenler geçer', () => {
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'deepseek-flash-free', { input: 0, output: 0 })), true);
    // Eki olmayan bilinen gizli bedava model.
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'big-pickle', { input: 0, output: 0 })), true);
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'claude-opus-5', { input: 5, output: 25 })), false);
    // Girdisi bedava ama çıktısı paralı olan model bedava DEĞİLDİR.
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'yari-bedava-free', { input: 0, output: 4 })), false);
  });

  it('OMP kataloğunun fiyatsız yeni modeli 0 yazması bedava saydırmaz', () => {
    // Canlı ölçüm (16.08.2026): muse-spark-1.2 OMP'de 0, models.dev'de 1,25/4,25.
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'muse-spark-1.2', { input: 0, output: 0 })), false);
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'muse-spark-1.2')), false);
  });

  it('Zen fiyatı hiç yoksa ad kuralına düşer, yokluğu bedava saymaz', () => {
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'glm-5.2-free')), true);
    assert.equal(omp.isAgentBridgeOmpModel(model('opencode-zen', 'glm-5.2')), false);
  });

  it('NanoGPT: yalnız beyaz listedeki model geçer (canlı katalog 881 model basıyor)', () => {
    assert.equal(omp.isAgentBridgeOmpModel(model('nanogpt', 'qwen/qwen3.8-27b-uncensored', { input: 0.18, output: 0.5 })), true);
    assert.equal(omp.isAgentBridgeOmpModel(model('nanogpt', 'venice-uncensored')), false);
    assert.equal(omp.isAgentBridgeOmpModel(model('nanogpt', 'qwen3.8-max', { input: 1, output: 4 })), false);
  });
});

describe('model etiketi', () => {
  it('sağlayıcıyı başa yazar', () => {
    assert.equal(omp.ompModelLabel({ provider: 'opencode-go', id: 'deepseek-flash', name: 'deepseek-flash (2x usage)' }),
      'opencode-go · deepseek-flash (2x usage)');
    assert.equal(omp.ompModelLabel({ provider: 'deepseek', id: 'deepseek-flash', name: 'deepseek-flash' }),
      'deepseek · deepseek-flash');
  });

  it('adı yoksa kimliğe düşer', () => {
    assert.equal(omp.ompModelLabel({ provider: 'opencode-zen', id: 'big-pickle' }), 'opencode-zen · big-pickle');
  });

  it('ad sağlayıcıyla başlasa bile kısaltmaz — ikizini ayırt eden şey o', () => {
    assert.equal(omp.ompModelLabel({ provider: 'deepseek', id: 'x', name: 'DeepSeek V4 Pro' }), 'deepseek · DeepSeek V4 Pro');
  });

  it('sağlayıcısız satırda yalnız adı verir', () => {
    assert.equal(omp.ompModelLabel({ id: 'x', name: 'Model' }), 'Model');
  });
});
