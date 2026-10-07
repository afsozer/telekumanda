import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createClaudeEventAdapter, errorTextFromResult } from '../claude-app-adapter.mjs';
import { dispatchCodexNotification } from '../codex-app-adapter.mjs';

describe('claude-app adapter', () => {
  it('normalizes control_request into typed approval state', () => {
    const s = { messages: [], toolDetails: [] };
    let pushed = false;
    const handle = createClaudeEventAdapter({
      capMessages() {},
      clearInterruptTimer() {},
      extractChoices() { return null; },
      finalizeTurnIdle() {},
      hhmm() { return '12:00'; },
      initFromEvent() { return {}; },
      logWarn() {},
      maxToolResultChars: 100,
      normalizeApprovalQuestions() { return []; },
      normalizePermissionMode(v) { return v; },
      pushSnapshot() { pushed = true; },
      summarizeTool() { return 'Use tool'; },
      textFromContent(v) { return String(v || ''); },
    });
    handle(s, { type: 'control_request', request_id: 'r1', request: { subtype: 'can_use_tool', tool_name: 'Bash', input: {} } });
    assert.equal(pushed, true);
    assert.equal(s.pendingApproval.requestId, 'r1');
    assert.equal(s.pendingApproval.kind, 'tool');
    assert.equal(s.pendingApproval.options[0].id, 'allow');
  });

  // Canlı vaka 06.08.2026: --resume başarısız oldu, CLI
  // {subtype:'error_during_execution', is_error:true, errors:[...]} gönderdi ama
  // `result` alanı YOKTU. Adaptör yalnız ev.result'a baktığı için tur sohbete tek
  // satır bile düşürmeden "başarıyla" bitti; kullanıcı boş ekrana bakıp iki kez
  // daha gönderdi. Hata artık errors[] dizisinden okunur.
  function makeHandle(s, finalizeCalls, autonomousCalls = []) {
    return createClaudeEventAdapter({
      capMessages() {},
      clearInterruptTimer() {},
      extractChoices() { return null; },
      finalizeTurnIdle(sess, opts) { finalizeCalls.push(opts || {}); },
      hhmm() { return '12:33'; },
      initFromEvent() { return {}; },
      logWarn() {},
      maxToolResultChars: 100,
      normalizeApprovalQuestions() { return []; },
      normalizePermissionMode(v) { return v; },
      onAutonomousActivity(sess, ev) { autonomousCalls.push([sess, ev]); },
      pushSnapshot() {},
      summarizeTool() { return 'Use tool'; },
      textFromContent(v) { return String(v || ''); },
    });
  }

  it('errors[] dizisindeki hatayı sessizce yutmaz', () => {
    const s = { messages: [], toolDetails: [] };
    const calls = [];
    makeHandle(s, calls)(s, {
      type: 'result',
      subtype: 'error_during_execution',
      is_error: true,
      errors: ['No conversation found with session ID: 0b2545a5'],
    });
    assert.equal(calls.length, 1);
    assert.match(calls[0].error, /No conversation found with session ID/);
  });

  it('ajan bu turda konuştuysa hata mesajı tekrarlanmaz', () => {
    const s = { messages: [], toolDetails: [], _turnHadAgent: true };
    const calls = [];
    makeHandle(s, calls)(s, { type: 'result', is_error: true, errors: ['bir şey patladı'] });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].error, undefined);
  });

  it('başarılı turda hata iletilmez', () => {
    const s = { messages: [], toolDetails: [] };
    const calls = [];
    makeHandle(s, calls)(s, { type: 'result', subtype: 'success', result: 'tamam' });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].error, undefined);
  });

  it('arka plan görev bildirimi otonom tur etkinliği sayılır', () => {
    const s = { messages: [], toolDetails: [] };
    const autonomous = [];
    makeHandle(s, [], autonomous)(s, {
      type: 'user',
      message: { role: 'user', content: '<task-notification><status>completed</status></task-notification>' },
    });
    assert.equal(autonomous.length, 1);
    assert.equal(autonomous[0][0], s);
  });

  it('normal tool_result ve result otonom turu yanlışlıkla başlatmaz', () => {
    const s = { messages: [], toolDetails: [], _toolUseIndex: new Map() };
    const finalizeCalls = [];
    const autonomous = [];
    const handle = makeHandle(s, finalizeCalls, autonomous);
    handle(s, { type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: 'ok' }] } });
    handle(s, { type: 'result', subtype: 'success' });
    assert.equal(autonomous.length, 0);
    assert.equal(finalizeCalls.length, 1);
  });

  it('errorTextFromResult alanları birleştirir, tekrarı ayıklar', () => {
    assert.equal(errorTextFromResult({ errors: ['a', 'a'], error: 'b' }), 'a · b');
    assert.equal(errorTextFromResult({ errors: [{ message: 'nesne hata' }] }), 'nesne hata');
    assert.equal(errorTextFromResult({}), '');
  });
});

describe('codex-app adapter', () => {
  it('dispatches notifications to injected handlers', () => {
    const calls = [];
    const handlers = new Proxy({}, {
      get: (_, name) => (session, params, kind) => calls.push([String(name), session.id, params.value, kind || '']),
    });
    dispatchCodexNotification(
      { method: 'item/reasoning/textDelta', params: { value: 7 } },
      { id: 's1' },
      handlers,
    );
    assert.deepEqual(calls, [['onReasoningDelta', 's1', 7, 'content']]);
  });
});
