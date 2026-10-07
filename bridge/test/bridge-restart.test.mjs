import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRouter } from '../router.mjs';
import { register } from '../routes/general.mjs';

function fakeRes(events) {
  return {
    statusCode: 0,
    data: '',
    writeHead(code) {
      this.statusCode = code;
      events.push('writeHead');
    },
    end(data) {
      this.data = data;
      events.push('end');
    },
  };
}

describe('POST /bridge/restart', () => {
  it('responds before scheduling a supervisor restart', async () => {
    const events = [];
    const router = createRouter();
    register(router, {
      readWorkspaceFile: () => ({}),
      resolveWorkspacePath: () => ({ ok: false }),
      getActiveState: () => ({}),
      SLASH: {},
      scheduleRestart: () => events.push('scheduleRestart'),
    });

    const route = router.match('POST', '/bridge/restart');
    const res = fakeRes(events);
    await route.handler({ url: '/bridge/restart', headers: {} }, res);

    assert.equal(res.statusCode, 202);
    assert.deepEqual(JSON.parse(res.data), {
      ok: true,
      restarting: true,
      method: 'supervisor',
    });
    assert.deepEqual(events, ['writeHead', 'end', 'scheduleRestart']);
  });
});
