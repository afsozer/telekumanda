import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createPromptRequests } from '../prompt-requests.mjs';

test('prompt request ids are persistent, payload-bound and deduplicated', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prompt-requests-'));
  const filePath = path.join(root, 'requests.json');
  try {
    const store = createPromptRequests({ filePath });
    assert.equal(store.begin({ requestId: 'r1', backend: 'codex-app', sessionId: 's1', text: 'hello' }).duplicate, false);
    store.finish('r1', { ok: true, sessionId: 's1' });
    const reloaded = createPromptRequests({ filePath });
    assert.equal(reloaded.begin({ requestId: 'r1', backend: 'codex-app', sessionId: 's1', text: 'hello' }).record.status, 'delivered');
    assert.equal(reloaded.begin({ requestId: 'r1', backend: 'codex-app', sessionId: 's1', text: 'changed' }).ok, false);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('pending requests reconcile from transcript without automatic replay', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prompt-reconcile-'));
  try {
    const store = createPromptRequests({ filePath: path.join(root, 'requests.json') });
    store.begin({ requestId: 'delivered', backend: 'claude-app', sessionId: 's1', text: 'seen' });
    const delivered = store.begin({ requestId: 'delivered', backend: 'claude-app', sessionId: 's1', text: 'seen' }, () => ({ messages: [{ role: 'user', text: 'seen' }] }));
    assert.equal(delivered.record.status, 'delivered');
    store.begin({ requestId: 'missing', backend: 'claude-app', sessionId: 's1', text: 'lost' });
    const interrupted = store.begin({ requestId: 'missing', backend: 'claude-app', sessionId: 's1', text: 'lost' }, () => ({ messages: [] }));
    assert.equal(interrupted.record.status, 'interrupted');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
