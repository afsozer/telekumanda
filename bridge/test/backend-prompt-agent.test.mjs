import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createRouter } from '../router.mjs';
import { registerBackend } from '../routes/backend.mjs';

// Regresyon: /prompt route'u gövdedeki `agent`'i prompt()'a iletmeli. Telefonun
// opencode ajan secimi (or. atlas) HER turda govdede tasiniyor; iletilmezse
// secim sunucuya ulasmaz ve varsayilan ajana sessizce duser.
// (agentbridge-atlas-agent-routing bulgusu, 15 Ağu 2026)

// promptRequests gercek bir dosyaya (~/.agentbridge/prompt-requests.json) persist
// eder ve requestId'yi dedupler; testin koşular arasi cakismamasi icin her
// requestId benzersiz olmali (yoksa 2. koşuda duplicate'e duser, prompt() cagrilmaz).
let reqSeq = 0;
const uniqueReqId = () => `agentfwd-test-${process.pid}-${Date.now()}-${reqSeq++}`;

function fakeReq(url, bodyObj) {
  return {
    url,
    async *[Symbol.asyncIterator]() { yield JSON.stringify(bodyObj); },
  };
}
function fakeRes() {
  const out = { status: 0, payload: null };
  return {
    out,
    writeHead(code) { out.status = code; },
    end(body) { out.payload = body ? JSON.parse(body) : null; },
  };
}

describe('backend /prompt agent forwarding', () => {
  it('gövdedeki agent alanini prompt()e iletir', async () => {
    const router = createRouter();
    let seen = null;
    const mod = {
      listSessions: () => [],
      getConversation: () => ({}),
      prompt: async (args) => { seen = args; return { ok: true, sessionId: args.sessionId }; },
    };
    registerBackend(router, 'faketest', mod);

    const route = router.match('POST', '/faketest/prompt');
    const res = fakeRes();
    await route.handler(
      fakeReq('/faketest/prompt', { requestId: uniqueReqId(), sessionId: 's1', text: 'merhaba', agent: 'atlas' }),
      res,
    );

    assert.equal(res.out.status, 200);
    assert.equal(seen.agent, 'atlas');
    assert.equal(seen.sessionId, 's1');
    assert.equal(seen.text, 'merhaba');
  });

  it('agent gonderilmezse undefined kalir (otomatik ajana dokunmaz)', async () => {
    const router = createRouter();
    let seen = null;
    const mod = {
      listSessions: () => [],
      getConversation: () => ({}),
      prompt: async (args) => { seen = args; return { ok: true, sessionId: args.sessionId }; },
    };
    registerBackend(router, 'faketest2', mod);

    const route = router.match('POST', '/faketest2/prompt');
    const res = fakeRes();
    await route.handler(
      fakeReq('/faketest2/prompt', { requestId: uniqueReqId(), sessionId: 's2', text: 'selam' }),
      res,
    );

    assert.equal(res.out.status, 200);
    assert.equal(seen.agent, undefined);
  });
});
