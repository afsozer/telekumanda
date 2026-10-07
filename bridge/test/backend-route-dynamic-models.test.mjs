import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRouter } from '../router.mjs';
import { registerBackend } from '../routes/backend.mjs';

describe('backend dynamic model route', () => {
  it('passes the request to getModels so session-scoped catalogs can load', async () => {
    const router = createRouter();
    const mod = {
      MODELS: [{ id: 'seed', label: 'Seed' }],
      listSessions: () => [],
    };
    let seenUrl = '';
    registerBackend(router, 'dynamic', mod, {
      getModels: async (req) => {
        seenUrl = req.url;
        return [{ id: 'full', label: 'Full catalog' }];
      },
    });

    const route = router.match('GET', '/dynamic/models');
    let status = 0;
    let payload = null;
    const res = {
      writeHead(code) { status = code; },
      end(body) { payload = JSON.parse(body); },
    };
    await route.handler({ url: '/dynamic/models?sessionId=session-42' }, res);

    assert.equal(seenUrl, '/dynamic/models?sessionId=session-42');
    assert.equal(status, 200);
    assert.deepStrictEqual(payload.models, [{ id: 'full', label: 'Full catalog' }]);
    assert.equal(payload.defaultModel, 'full');
  });
});
