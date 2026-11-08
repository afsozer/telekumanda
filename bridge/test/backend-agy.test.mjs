// ACP tabanlı agy backend'i — ajan ÇALIŞTIRMADAN test edilebilen kısımlar.
//
// Eski dosyanın kusuru buydu: 8 vakanın hepsi API şekliydi (dizi mi, ok:false
// mu) ve asıl kırılgan mantık (transcript ayrıştırma, tur sonu) hiç test
// edilmiyordu. ACP'de o mantık protokole geçti; burada test edilen şey
// bildirim → mesaj eşlemesi, yani bizim yazdığımız kısım.
import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import * as agy from '../agy.mjs';

const { __agyTest } = agy;

function yeni(id = 's1') {
  return __agyTest.ekleSahteOturum(id);
}

function guncelle(id, update) {
  __agyTest.bildirimIsle('session/update', { sessionId: id, update });
}

describe('agy ACP backend', () => {
  beforeEach(() => { __agyTest.sessions.clear(); });

  it('model katalogu ve izin kipleri dolu', () => {
    assert.ok(agy.MODELS.length > 0);
    assert.deepEqual(agy.MODELS, agy.AGY_MODELS);
    for (const m of agy.MODELS) {
      assert.equal(typeof m.id, 'string');
      assert.equal(typeof m.label, 'string');
    }
    assert.deepEqual(agy.PERMISSION_MODES.map(m => m.id), ['default', 'accept-edits', 'plan']);
  });

  it('bilinmeyen oturum icin bos ve guvenli varsayilan doner', () => {
    const c = agy.getConversation('yok');
    assert.deepEqual(c.messages, []);
    assert.equal(c.running, false);
    assert.equal(c.awaitingApproval, false);
    assert.equal(agy.runningSessionId(), null);
    assert.deepEqual(agy.listSessions(), []);
    assert.equal(agy.getThought('yok', 0).text, '');
  });

  it('agent_message_chunk parcalari TEK mesajda birikir', () => {
    const s = yeni();
    guncelle('s1', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Mer' } });
    guncelle('s1', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'haba' } });
    assert.equal(s.messages.length, 1);
    assert.equal(s.messages[0].role, 'agent');
    assert.equal(s.messages[0].text, 'Merhaba');
  });

  it('agent_thought_chunk mesaj satiri ACMAZ, detaya yazar', () => {
    const s = yeni();
    guncelle('s1', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'cevap' } });
    guncelle('s1', { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'dusunuyorum' } });
    assert.equal(s.messages.length, 1, 'dusunce ayri satir olmamali');
    assert.equal(agy.getThought('s1', 0).text, 'dusunuyorum');
  });

  it('tool_call satir acar, tool_call_update AYNI satiri guncellertr', () => {
    const s = yeni();
    guncelle('s1', {
      sessionUpdate: 'tool_call', toolCallId: 'c1', name: 'write_file',
      title: 'File not.txt', status: 'in_progress', kind: 'edit',
      rawInput: { Path: 'not.txt' },
    });
    assert.equal(s.messages.length, 1);
    assert.match(s.messages[0].text, /write_file/);
    assert.equal(s.messages[0].status, 'in_progress');

    guncelle('s1', {
      sessionUpdate: 'tool_call_update', toolCallId: 'c1', name: 'write_file',
      title: 'File not.txt', status: 'completed', kind: 'edit',
      content: [{ type: 'diff', path: 'not.txt', newText: 'merhaba' }],
    });
    assert.equal(s.messages.length, 1, 'guncelleme yeni satir ACMAMALI');
    assert.equal(s.messages[0].status, 'completed');
    assert.match(agy.getThought('s1', 0).text, /merhaba/);
  });

  it('onay bekleyen arac satirinda durum kullaniciya gorunur', () => {
    const s = yeni();
    guncelle('s1', {
      sessionUpdate: 'tool_call', toolCallId: 'c1', name: 'run_command',
      title: 'Get-ChildItem', status: 'pending', kind: 'execute',
    });
    assert.match(s.messages[0].text, /onay bekliyor/);
  });

  it('izin istegi onay kartini snapshot\'a koyar ve respond onu cozer', async () => {
    const s = yeni();
    const sozluk = __agyTest.sunucuIstegiIsle('session/request_permission', {
      sessionId: 's1',
      toolCall: { toolCallId: 'c1', name: 'write_file', title: 'File not.txt', kind: 'edit' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
      ],
    });

    const snap = __agyTest.snapshot(s);
    assert.equal(snap.awaitingApproval, true);
    assert.equal(snap.approval.allowId, 'allow');
    assert.equal(snap.approval.denyId, 'deny');
    // pendingPrompt eski telefon onay kartinin okudugu alan; ayni nesne.
    assert.equal(snap.pendingPrompt, snap.approval);

    const r = agy.respond('s1', 'yes');
    assert.equal(r.ok, true);
    assert.equal(r.optionId, 'allow');
    const sonuc = await sozluk;
    assert.deepEqual(sonuc, { outcome: { outcome: 'selected', optionId: 'allow' } });
    assert.equal(__agyTest.snapshot(s).awaitingApproval, false, 'kart temizlenmeli');
  });

  it('respond "no" reddi secer, bekleyen onay yoksa hata doner', async () => {
    const s = yeni();
    const sozluk = __agyTest.sunucuIstegiIsle('session/request_permission', {
      sessionId: 's1',
      toolCall: { toolCallId: 'c1', name: 'run_command' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
      ],
    });
    assert.equal(agy.respond('s1', 'no').optionId, 'deny');
    assert.deepEqual(await sozluk, { outcome: { outcome: 'selected', optionId: 'deny' } });
    assert.equal(agy.respond('s1', 'yes').ok, false);
    assert.equal(agy.respond('yok-boyle', 'yes').ok, false);
  });

  it('oturumu olmayan izin istegi REDDEDILIR (tur askida kalmasin)', async () => {
    const sonuc = await __agyTest.sunucuIstegiIsle('session/request_permission', {
      sessionId: 'kayitsiz',
      toolCall: { toolCallId: 'c9', name: 'write_file' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
      ],
    });
    assert.equal(sonuc.outcome.optionId, 'deny');
  });

  it('bilinmeyen sunucu istegi BOS nesne ile cevaplanir', async () => {
    assert.deepEqual(await __agyTest.sunucuIstegiIsle('bilinmeyen/metod', {}), {});
  });

  it('current_mode_update izin kipini, session_info_update basligi yazar', () => {
    const s = yeni();
    guncelle('s1', { sessionUpdate: 'current_mode_update', currentModeId: 'plan' });
    guncelle('s1', { sessionUpdate: 'session_info_update', title: 'Deneme' });
    assert.equal(s.permissionMode, 'plan');
    assert.equal(s.title, 'Deneme');
    assert.equal(__agyTest.snapshot(s).permissionMode, 'plan');
  });

  it('available_commands_update slash listesini besler', () => {
    yeni();
    guncelle('s1', {
      sessionUpdate: 'available_commands_update',
      availableCommands: [{ name: 'goal', description: 'Hedefe kadar calis' }],
    });
    assert.deepEqual(agy.getSlashCommands('s1'), [{ name: 'goal', desc: 'Hedefe kadar calis' }]);
  });

  it('kendi istemimizin yankisi ikinci kez mesaj ACMAZ', () => {
    const s = yeni();
    s.messages.push({ role: 'user', text: 'merhaba', time: '' });
    guncelle('s1', { sessionUpdate: 'user_message_chunk', content: { type: 'text', text: 'merhaba' } });
    assert.equal(s.messages.length, 1);
  });

  it('bilinmeyen oturumun bildirimi patlamaz', () => {
    guncelle('hayalet', { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'x' } });
    assert.deepEqual(agy.listSessions(), []);
  });

  it('listSessions tur sayisini kullanici mesajlarindan sayar', () => {
    const s = yeni();
    s.messages.push({ role: 'user', text: 'bir', time: '' });
    s.messages.push({ role: 'agent', text: 'cevap', time: '' });
    s.messages.push({ role: 'user', text: 'iki', time: '' });
    const liste = agy.listSessions();
    assert.equal(liste.length, 1);
    assert.equal(liste[0].turns, 2);
    assert.equal(liste[0].lastText, 'cevap');
  });

  it('calisan oturum runningSessionId ile bulunur', () => {
    const s = yeni();
    s.status = 'running';
    assert.equal(agy.runningSessionId(), 's1');
    assert.equal(agy.getConversation('s1').running, true);
  });

  it('stop bilinmeyen oturumda not found doner', async () => {
    const r = await agy.stop('yok');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
  });

  it('prompt bilinmeyen oturumda not found doner', async () => {
    const r = await agy.prompt({ sessionId: 'yok', text: 'x' });
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
  });

  it('newSession gecersiz dizini reddeder (surec baslatmadan)', async () => {
    const r = await agy.newSession({ cwd: 'C:/boyle/bir/dizin/yok-12345' });
    assert.equal(r.ok, false);
    assert.match(r.error, /cwd not a directory/);
  });

  it('adoptSession id olmadan reddeder', async () => {
    const r = await agy.adoptSession({});
    assert.equal(r.ok, false);
    assert.equal(r.error, 'id required');
  });

  it('acik oturum yokken surec listesi bostur', () => {
    const p = agy.listLiveProcesses();
    assert.ok(Array.isArray(p.processes));
    assert.equal(p.count, 0);
  });

  it('killAllSessions oturum haritasini bosaltir', () => {
    yeni('a'); yeni('b');
    const r = agy.killAllSessions();
    assert.equal(r.killed, 2);
    assert.deepEqual(agy.listSessions(), []);
  });
});
