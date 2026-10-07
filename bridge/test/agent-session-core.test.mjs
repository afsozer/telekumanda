import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { applyReducerActions, archiveMessagesToDisk, createAgentSessionCore, createCollectionWatchdog, createShellPersistence, diffSnapshots, ensureMessageRowIds, paginateMessages, paginateSessionMessages, reducerAction, snapshotForWire } from '../agent-session-core.mjs';

function fakeWs() {
  return {
    sent: [],
    send(msg) { this.sent.push(JSON.parse(msg)); },
    on() {},
    close() { this.closed = true; },
  };
}

describe('agent-session-core', () => {
  it('adds stable rowIds to wire snapshots', () => {
    const snap = snapshotForWire({
      type: 'conversation',
      messages: [{ role: 'agent', text: 'hi', thoughtIndex: -1 }],
    });
    assert.equal(snap.messages[0].rowId, 'agent:-1:0');
  });

  it('assigns persistent rowIds to session messages', () => {
    const s = { id: 's1', messages: [{ role: 'user', text: 'a' }, { role: 'agent', text: 'b' }] };
    ensureMessageRowIds(s);
    assert.equal(s.messages[0].rowId, 's1:row:1');
    s.messages.splice(0, 1);
    s.messages.push({ role: 'agent', text: 'c' });
    ensureMessageRowIds(s);
    assert.deepEqual(s.messages.map(m => m.rowId), ['s1:row:2', 's1:row:3']);
  });

  it('diffs appended rows and appended text as delta ops', () => {
    const before = { messages: [{ role: 'agent', text: 'he' }] };
    const after = { messages: [{ role: 'agent', text: 'hello' }, { role: 'user', text: 'ok' }], running: true };
    const ops = diffSnapshots(before, after);
    assert.equal(ops[0].op, 'appendText');
    assert.equal(ops[0].chunk, 'llo');
    assert.equal(ops[1].op, 'appendRow');
    assert.equal(ops[2].op, 'setMeta');
    assert.equal(ops[2].meta.running, true);
  });

  it('diffs trimmed heads with dropRows when rowIds are persistent', () => {
    const before = {
      messages: [
        { rowId: 's1:row:1', role: 'user', text: 'a' },
        { rowId: 's1:row:2', role: 'agent', text: 'b' },
      ],
    };
    const after = {
      messages: [
        { rowId: 's1:row:2', role: 'agent', text: 'b' },
        { rowId: 's1:row:3', role: 'agent', text: 'c' },
      ],
    };
    const ops = diffSnapshots(before, after);
    assert.equal(ops[0].op, 'dropRows');
    assert.equal(ops[0].beforeRowId, 's1:row:2');
    assert.equal(ops[1].op, 'appendRow');
    assert.equal(ops[1].row.rowId, 's1:row:3');
  });

  it('serves full snapshots to legacy subscribers and deltas to since subscribers', () => {
    const s = { id: 's1', subscribers: new Set(), messages: [] };
    const core = createAgentSessionCore({
      snapshot: session => ({ type: 'conversation', sessionId: session.id, messages: session.messages, running: !!session.running }),
    });

    const legacy = fakeWs();
    core.subscribe(s, legacy);
    assert.equal(legacy.sent[0].type, 'conversation');

    s.messages.push({ role: 'agent', text: 'hi' });
    core.pushSnapshot(s);
    const since = s._seq - 1;

    const deltaClient = fakeWs();
    core.subscribe(s, deltaClient, { since });
    assert.equal(deltaClient.sent[0].type, 'delta');
    assert.equal(deltaClient.sent[0].ops[0].op, 'appendRow');
  });

  it('sends one initial snapshot then deltas to delta-capable subscribers', () => {
    const s = { id: 's1', subscribers: new Set(), messages: [] };
    const core = createAgentSessionCore({
      snapshot: session => ({ type: 'conversation', sessionId: session.id, messages: session.messages, running: !!session.running }),
    });
    const client = fakeWs();

    core.subscribe(s, client, { delta: true });
    assert.equal(client.sent[0].type, 'conversation');

    s.messages.push({ role: 'agent', text: 'yalnız delta' });
    core.pushSnapshot(s);
    assert.equal(client.sent[1].type, 'delta');
    assert.equal(client.sent[1].ops[0].op, 'appendRow');
  });

  it('replays deltas after reconnecting from the initial full snapshot seq', () => {
    const s = { id: 's1', subscribers: new Set(), messages: [] };
    const core = createAgentSessionCore({
      snapshot: session => ({ type: 'conversation', sessionId: session.id, messages: session.messages, running: !!session.running }),
    });

    const first = fakeWs();
    core.subscribe(s, first);
    const since = first.sent[0].seq;

    s.messages.push({ role: 'agent', text: 'after disconnect' });
    core.pushSnapshot(s);

    const resumed = fakeWs();
    core.subscribe(s, resumed, { since });
    assert.equal(resumed.sent[0].type, 'delta');
    assert.equal(resumed.sent[0].ops[0].op, 'appendRow');
    assert.equal(resumed.sent[0].ops[0].row.text, 'after disconnect');
  });

  it('falls back to a full snapshot when since is older than the ring', () => {
    const s = { id: 's1', subscribers: new Set(), messages: [] };
    const core = createAgentSessionCore({
      deltaRingLimit: 1,
      snapshot: session => ({ type: 'conversation', sessionId: session.id, messages: session.messages }),
    });
    core.subscribe(s, fakeWs(), { since: 1 });
    s.messages.push({ role: 'agent', text: 'one' });
    core.pushSnapshot(s);
    s.messages.push({ role: 'agent', text: 'two' });
    core.pushSnapshot(s);

    const oldClient = fakeWs();
    core.subscribe(s, oldClient, { since: 1 });
    assert.equal(oldClient.sent[0].type, 'conversation');
  });

  it('resyncs delta subscribers with a full snapshot after a trim (dirty flag)', () => {
    const s = { id: 's1', subscribers: new Set(), messages: [] };
    const core = createAgentSessionCore({
      snapshot: session => ({ type: 'conversation', sessionId: session.id, messages: session.messages }),
    });
    s.messages.push({ role: 'user', text: 'a' }, { role: 'agent', text: 'b' });
    core.pushSnapshot(s);

    const deltaClient = fakeWs();
    core.subscribe(s, deltaClient, { since: s._seq });
    // Kırpma simülasyonu: baştan bir satır düşür + yeni satır ekle. Uzunluk aynı
    // kaldığı için diff dropRows üretmez; dirty bayrağı olmadan istemciye kopya
    // appendRow'lar giderdi. Beklenen: tek bir FULL snapshot (tam resync).
    s.messages.splice(0, 1);
    s.messages.push({ role: 'agent', text: 'c' });
    s._deltaDirty = true;
    core.pushSnapshot(s);

    assert.equal(deltaClient.sent.length, 1);
    assert.equal(deltaClient.sent[0].type, 'conversation');
    assert.deepEqual(deltaClient.sent[0].messages.map(m => m.text), ['b', 'c']);
    assert.equal((s._deltaRing || []).length, 0);
  });

  it('sends nothing to delta subscribers when nothing changed', () => {
    const s = { id: 's1', subscribers: new Set(), messages: [] };
    const core = createAgentSessionCore({
      snapshot: session => ({ type: 'conversation', sessionId: session.id, messages: session.messages }),
    });
    s.messages.push({ role: 'agent', text: 'hi' });
    core.pushSnapshot(s);

    const legacy = fakeWs();
    const deltaClient = fakeWs();
    core.subscribe(s, legacy);
    core.subscribe(s, deltaClient, { since: s._seq });
    const legacyBefore = legacy.sent.length;

    core.pushSnapshot(s); // state değişmedi → ops boş
    assert.equal(deltaClient.sent.length, 0);             // delta abonesine sessizlik
    assert.equal(legacy.sent.length, legacyBefore + 1);   // legacy yine full alır
  });

  it('paginates messages before a stable row id', () => {
    const messages = [
      { role: 'user', text: 'a' },
      { role: 'agent', text: 'b' },
      { role: 'user', text: 'c' },
      { role: 'agent', text: 'd' },
    ];
    const page = paginateMessages(messages, { before: 'user::2', limit: 2 });
    assert.deepEqual(page.map(m => m.text), ['a', 'b']);
  });

  it('paginates archived evicted messages together with live rows', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-archive-'));
    const s = {
      id: 's1',
      messages: [
        { rowId: 's1:row:3', role: 'agent', text: 'live-1' },
        { rowId: 's1:row:4', role: 'agent', text: 'live-2' },
      ],
    };
    archiveMessagesToDisk(s, [
      { rowId: 's1:row:1', role: 'user', text: 'old-1' },
      { rowId: 's1:row:2', role: 'agent', text: 'old-2' },
    ], { dir });

    const page = paginateSessionMessages(s, { before: 's1:row:3', limit: 2 });
    assert.deepEqual(page.map(m => m.text), ['old-1', 'old-2']);
  });

  it('applies common reducer actions to a session shell', () => {
    const s = { messages: [], status: 'running', awaitingApproval: false };
    const applied = applyReducerActions(s, [
      reducerAction('appendMessage', { message: { role: 'agent', text: 'hello' } }),
      reducerAction('setApproval', { approval: { requestId: 1, kind: 'tool' } }),
      reducerAction('turnEnded'),
    ]);
    assert.equal(applied.length, 3);
    assert.equal(s.messages[0].text, 'hello');
    assert.equal(s.awaitingApproval, true);
    assert.equal(s.status, 'idle');
  });

  it('restores shell records through shared persistence helper', () => {
    const sessions = new Map();
    const persistence = createShellPersistence({
      file: 'unused.json',
      sessions,
      disabled: true,
      serializeSession: s => s,
      restoreSession: row => ({ id: row.id, model: row.model || 'default' }),
    });
    const restored = persistence.restoreFromData({ sessions: [{ id: 'a', model: 'm1' }, { bad: true }] });
    assert.equal(restored, 1);
    assert.equal(sessions.get('a').model, 'm1');
  });

  it('preserves a candidate turn when its watchdog probe fails', async () => {
    const s = { status: 'running' };
    const probeErrors = [];
    const timeouts = [];
    const watchdog = createCollectionWatchdog({
      sessions: new Map([['s1', s]]),
      isCandidate: session => session.status === 'running',
      isBusy: async () => { throw new Error('probe timeout'); },
      onProbeError: (_session, error) => probeErrors.push(error.message),
      onTimeout: session => timeouts.push(session),
    });

    await watchdog.check();

    assert.deepEqual(probeErrors, ['probe timeout']);
    assert.equal(timeouts.length, 0);
  });

  it('does not overlap probes or finalize from a stale probe result', async () => {
    const s = { status: 'running', lastActivity: 0 };
    let probes = 0;
    let finishProbe;
    const probeResult = new Promise(resolve => { finishProbe = resolve; });
    const timeouts = [];
    const watchdog = createCollectionWatchdog({
      sessions: new Map([['s1', s]]),
      isCandidate: session => session.status === 'running' && session.lastActivity === 0,
      isBusy: async () => { probes++; return probeResult; },
      onTimeout: session => timeouts.push(session),
    });

    const firstCheck = watchdog.check();
    await watchdog.check();
    assert.equal(probes, 1);

    // Real activity arrives while thread/read is still pending.
    s.lastActivity = 1;
    finishProbe(false);
    await firstCheck;

    assert.equal(timeouts.length, 0);
  });
});
