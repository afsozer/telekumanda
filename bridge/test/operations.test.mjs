import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createOperationsTracker } from '../operations.mjs';

test('operations tracker persists started, attention and completed transitions', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-test-'));
  const filePath = path.join(dir, 'operations.json');
  let clock = 0;
  const session = { id: 's1', cwd: 'C:/repo', model: 'model-a', status: 'idle', lastText: 'Task A' };
  const tracker = createOperationsTracker({
    modules: { 'codex-app': { listSessions: () => [session] } },
    labels: { 'codex-app': 'Codex App' },
    filePath,
    now: () => new Date(1_700_000_000_000 + clock++ * 1000),
  });
  try {
    tracker.sample();
    session.status = 'running';
    tracker.sample();
    session.awaitingApproval = true;
    tracker.sample();
    session.awaitingApproval = false;
    session.status = 'idle';
    const snapshot = tracker.sample();

    assert.deepEqual(snapshot.events.map(e => e.kind), ['completed', 'attention', 'started']);
    assert.equal(snapshot.operations.length, 0);
    assert.equal(fs.existsSync(filePath), true);

    const restored = createOperationsTracker({ modules: {}, filePath });
    assert.equal(restored.snapshot().events.length, 3);
  } finally {
    tracker.stop();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker completes a running session removed from the backend', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-deleted-'));
  let sessions = [{ id: 'deleted', status: 'running' }];
  const delivered = [];
  const tracker = createOperationsTracker({
    modules: { 'codex-app': { listSessions: () => sessions } },
    filePath: path.join(dir, 'operations.json'),
    onEvent: event => delivered.push(event),
  });
  try {
    tracker.sample();
    sessions = [];
    assert.equal(tracker.sample().operations.length, 0);
    assert.deepEqual(delivered.map(event => event.kind), ['started', 'completed']);
    assert.equal(delivered[1].sessionId, 'deleted');
    tracker.sample();
    assert.equal(delivered.length, 2);
  } finally {
    tracker.stop();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker keeps a session when backend listing temporarily fails', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-list-error-'));
  let fail = false;
  const delivered = [];
  const tracker = createOperationsTracker({
    modules: { agy: { listSessions: () => {
      if (fail) throw new Error('temporary');
      return [{ id: 'still-running', status: 'running' }];
    } } },
    filePath: path.join(dir, 'operations.json'),
    onEvent: event => delivered.push(event),
  });
  try {
    tracker.sample();
    fail = true;
    assert.equal(tracker.sample().operations.length, 1);
    assert.deepEqual(delivered.map(event => event.kind), ['started']);
  } finally {
    tracker.stop();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker collapses shells sharing one thread into a single card', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-thread-'));
  // Ayni codex thread'inin iki kabugu: diskten adopt edilen eski kabuk ve
  // canli kabuk. Tek kart cikmali, sessionId canli kabugu gostermeli.
  const adopted = { id: 'thread-1', threadId: 'thread-1', status: 'running', lastUserAt: 1000, turns: 53, title: 'Köprü tanısı' };
  const live = { id: 'shell-2', threadId: 'thread-1', status: 'running', lastUserAt: 2000, turns: 54, title: 'Köprü tanısı' };
  const tracker = createOperationsTracker({
    modules: { 'codex-app': { listSessions: () => [adopted, live] } },
    labels: { 'codex-app': 'Codex App' },
    filePath: path.join(dir, 'operations.json'),
  });
  try {
    const snapshot = tracker.sample();
    assert.equal(snapshot.operations.length, 1);
    assert.equal(snapshot.operations[0].sessionId, 'shell-2');
    assert.equal(snapshot.operations[0].id, 'codex-app:thread-1');
    assert.equal(snapshot.operations[0].title, 'Köprü tanısı');
    assert.equal(snapshot.counts.running, 1);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker keeps the updatedAt stamp still while nothing changes', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-stamp-'));
  let clock = 0;
  const session = { id: 's1', status: 'running', lastText: 'Task A' };
  const tracker = createOperationsTracker({
    modules: { agy: { listSessions: () => [session] } },
    filePath: path.join(dir, 'operations.json'),
    now: () => new Date(1_700_000_000_000 + clock++ * 1000),
  });
  try {
    const first = tracker.sample().operations[0].updatedAt;
    const second = tracker.sample().operations[0].updatedAt;
    // Ornekleme saati degisse de is degismedi: damga sabit kalmali.
    assert.equal(second, first);
    session.lastText = 'Task B';
    assert.notEqual(tracker.sample().operations[0].updatedAt, first);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker prefers real session activity over sample time', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-activity-'));
  const activity = Date.UTC(2026, 7, 2, 17, 38, 15);
  const tracker = createOperationsTracker({
    modules: { agy: { listSessions: () => [{ id: 's1', status: 'running', lastActivity: activity }] } },
    filePath: path.join(dir, 'operations.json'),
    now: () => new Date(activity - 60_000),
  });
  try {
    assert.equal(tracker.sample().operations[0].updatedAt, new Date(activity).toISOString());
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker hides events whose session no longer resolves', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-dead-'));
  const filePath = path.join(dir, 'operations.json');
  const session = { id: 'shell-1', diskId: 'ses_kalici', status: 'idle', lastText: 'İş' };
  const alive = new Set(['shell-1', 'ses_kalici']);
  const modules = {
    'opencode-app': {
      listSessions: () => [session],
      sessionExists: id => alive.has(String(id || '')),
    },
  };
  const tracker = createOperationsTracker({ modules, filePath });
  try {
    session.status = 'running';
    tracker.sample();
    session.status = 'idle';
    const before = tracker.sample();
    assert.deepEqual(before.events.map(e => e.kind), ['completed', 'started']);
    assert.equal(before.events[0].diskId, 'ses_kalici');

    // Oturum diskten silindi: kabuk da kalici kimlik de artik cozulmuyor.
    alive.clear();
    assert.deepEqual(tracker.snapshot().events, []);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker keeps events for backends without an existence check', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-nocheck-'));
  const session = { id: 's1', status: 'running' };
  const tracker = createOperationsTracker({
    modules: { agy: { listSessions: () => [session] } },
    filePath: path.join(dir, 'operations.json'),
  });
  try {
    assert.equal(tracker.sample().events.length, 1);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('operations tracker exposes waiting work before running work', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-order-'));
  const tracker = createOperationsTracker({
    modules: {
      agy: { listSessions: () => [{ id: 'run', status: 'running' }] },
      'claude-app': { listSessions: () => [{ id: 'wait', status: 'running', awaitingApproval: true }] },
    },
    filePath: path.join(dir, 'operations.json'),
  });
  try {
    const snapshot = tracker.sample();
    assert.equal(snapshot.operations[0].status, 'waiting');
    assert.deepEqual(snapshot.counts, { running: 1, waiting: 1, failed: 0 });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});


// --- Kapsul (Android 16 Live Updates) push sozlesmesi, 16.09.2026 ---
// docs/kapsul-live-updates-plani.md §2.1: kapsulu acan olay 'started'; ayrica
// "onay cozuldu" diye ayri olay yok, `waiting -> running` de 'started' uretiyor.

test('waiting to running emits started so the capsule can leave approval state', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'operations-capsule-'));
  const session = { id: 's1', status: 'running', awaitingApproval: true, title: 'Kapsül işi', lastText: 'Onay bekliyor' };
  const tracker = createOperationsTracker({
    modules: { 'claude-app': { listSessions: () => [session] } },
    labels: { 'claude-app': 'Claude' },
    filePath: path.join(dir, 'operations.json'),
  });
  try {
    tracker.sample();
    session.awaitingApproval = false;
    const snapshot = tracker.sample();
    assert.deepEqual(snapshot.events.map(e => e.kind), ['started', 'attention']);
    const started = snapshot.events[0];
    // Kapsulun govdesi bu iki alandan besleniyor: kart basligi ve chronometer.
    assert.equal(started.title, 'Kapsül işi');
    assert.equal(Number.isFinite(Date.parse(started.at)), true);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('started passes the push filter and carries title/startedAt', async () => {
  const { OPERATION_PUSH_KINDS, operationPushBody } = await import('../server.mjs');
  // Eski surumde 'started' SUZGECTEN DUSUYORDU; kapsul onsuz hic acilmaz.
  assert.deepEqual(OPERATION_PUSH_KINDS, ['started', 'attention', 'completed', 'failed']);
  const body = operationPushBody({
    id: '2026-09-16T10:00:00.000Z-claude-app-s1-started',
    kind: 'started',
    backend: 'claude-app',
    backendLabel: 'Claude',
    sessionId: 's1',
    summary: 'Kapsül işi',
    title: 'Kapsül işi',
    at: '2026-09-16T10:00:00.000Z',
  });
  assert.equal(body.kind, 'started');
  assert.equal(body.title, 'Kapsül işi');
  // ISO metin oldugu gibi gider: Android alani metin olarak okuyor.
  assert.equal(body.startedAt, '2026-09-16T10:00:00.000Z');
  assert.equal(body.deliveryId, '2026-09-16T10:00:00.000Z-claude-app-s1-started');
  // Alanlari olmayan olay `undefined` sizdirmamali (extras metne cevriliyor).
  const bos = operationPushBody({ id: 'e1', kind: 'completed', backend: 'agy', sessionId: 's2' });
  assert.equal(bos.title, '');
  assert.equal(bos.startedAt, '');
});
