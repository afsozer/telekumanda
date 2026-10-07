import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeWarmTargets, warmOpenTabBackends } from '../backend-warmth.mjs';

describe('open-tab backend warmth', () => {
  it('keeps only warmable backends and groups duplicate tab sessions', () => {
    assert.deepStrictEqual(
      normalizeWarmTargets([
        { backend: 'opencode2-app', sessionId: 'h1' },
        { backend: 'opencode2-app', sessionId: 'h1' },
        { backend: 'opencode2-app', sessionId: 'h2' },
        { backend: 'codex-app', sessionId: 'c1' },
        { backend: 'claude-app', sessionId: 'cl1' },
        { backend: 'agy', sessionId: 'a1' },
        { backend: 'codex-app', sessionId: '' },
      ]),
      [
        {
          backend: 'opencode2-app',
          openSessions: [
            { sessionId: 'h1', cwd: '' },
            { sessionId: 'h2', cwd: '' },
          ],
        },
        { backend: 'codex-app', openSessions: [{ sessionId: 'c1', cwd: '' }] },
      ],
    );
  });

  it('starts warmups asynchronously without holding the HTTP response', async () => {
    let resolveWarmup;
    const warmupFinished = new Promise(resolve => { resolveWarmup = resolve; });
    const calls = [];
    const result = warmOpenTabBackends(
      [{ backend: 'codex-app', sessionId: 'c1' }],
      {
        'codex-app': {
          warmup: async ({ openSessions, sessionIds }) => {
            calls.push({ openSessions, sessionIds });
            await warmupFinished;
          },
        },
      },
    );

    assert.deepStrictEqual(result, { ok: true, accepted: ['codex-app'] });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepStrictEqual(calls, [{
      openSessions: [{ sessionId: 'c1', cwd: '' }],
      sessionIds: ['c1'],
    }]);
    resolveWarmup();
    await warmupFinished;
    await new Promise(resolve => setImmediate(resolve));
  });

  it('coalesces concurrent warm requests per backend and allows the next one after completion', async () => {
    let release;
    const pending = new Promise(resolve => { release = resolve; });
    let calls = 0;
    const modules = {
      'codex-app': {
        warmup: async () => {
          calls++;
          await pending;
        },
      },
    };

    warmOpenTabBackends([{ backend: 'codex-app', sessionId: 'phone' }], modules);
    warmOpenTabBackends([{ backend: 'codex-app', sessionId: 'tablet' }], modules);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls, 1);

    release();
    await pending;
    await new Promise(resolve => setImmediate(resolve));
    warmOpenTabBackends([{ backend: 'codex-app', sessionId: 'phone' }], modules);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls, 2);
  });
});
