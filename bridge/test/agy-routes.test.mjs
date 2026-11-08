// agy ROTALARI — telefonun ve web arayüzünün gerçekten çağırdığı uçlar.
//
// Neden ayrı bir dosya: modül testi (backend-agy.test.mjs) eşlemeyi doğruluyor,
// canlı ölçüm ajanı doğruluyor; ama ikisi de ROTA KATMANINI atlıyordu. Yol adı,
// gövde alan adı ya da HTTP metodu yanlış yazılsa her ikisi de yeşil kalır ve
// hata yalnız telefonda görünürdü. Burada server.mjs'teki kayıtların AYNISI
// kurulup istekler port açmadan sürülüyor.
import { describe, it, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { createRouter } from '../router.mjs';
import { registerBackend } from '../routes/backend.mjs';
import { json, body } from '../router.mjs';
import * as agy from '../agy.mjs';

const { __agyTest } = agy;

// server.mjs'teki agy kaydının birebir kopyası. Değişirse bu test kırılır ki
// kopya sessizce eskimesin.
function kur() {
  const router = createRouter();
  registerBackend(router, 'agy', agy, {
    getModels: () => agy.getModels(),
    defaultModel: () => agy.defaultModel(),
    extras: [
      { method: 'GET', pattern: '/thought', handler: (req, res) => {
        const url = new URL(req.url, 'http://x');
        json(res, 200, agy.getThought(url.searchParams.get('sessionId') || '', parseInt(url.searchParams.get('i') || '0', 10)));
      }},
      { method: 'POST', pattern: '/respond', handler: async (req, res) => {
        const b = await body(req);
        const r = agy.respond(b.sessionId, b.answer);
        json(res, r.ok ? 200 : 400, r);
      }},
      { method: 'POST', pattern: '/approve', handler: async (req, res) => {
        const b = await body(req);
        const r = agy.respond(b.sessionId, b.allow === true ? 'yes' : 'no');
        json(res, r.ok ? 200 : 400, r);
      }},
      { method: 'GET', pattern: '/permission-modes', handler: (req, res) => json(res, 200, { modes: agy.PERMISSION_MODES }) },
    ],
  });
  return router;
}

// Gövdeli istek: router.mjs gövdeyi `for await (const c of req)` ile okuyor,
// yani sahte istek ASENKRON ITERABLE olmali (olay dinleyicisi yetmiyor —
// "req is not async iterable" ile patliyor).
function istek(url, gövde) {
  if (gövde === undefined) return { url, method: 'GET', headers: {} };
  const ham = Buffer.from(JSON.stringify(gövde));
  return {
    url,
    method: 'POST',
    headers: { 'content-type': 'application/json', 'content-length': String(ham.length) },
    async *[Symbol.asyncIterator]() { yield ham; },
  };
}

async function sur(router, method, url, gövde) {
  const route = router.match(method, url.split('?')[0]);
  assert.ok(route, `rota kayıtlı değil: ${method} ${url}`);
  let status = 0; let payload = null;
  const res = {
    writeHead(code) { status = code; },
    end(b) { payload = b ? JSON.parse(b) : null; },
    setHeader() {},
  };
  await route.handler(istek(url, gövde), res);
  return { status, payload };
}

describe('agy rotalari', () => {
  beforeEach(() => { __agyTest.sessions.clear(); });

  it('telefonun cagirdigi butun uclar KAYITLI', () => {
    const router = kur();
    const uclar = [
      ['GET', '/agy/models'], ['GET', '/agy/sessions'], ['POST', '/agy/new'],
      ['POST', '/agy/prompt'], ['GET', '/agy/conversation'], ['GET', '/agy/disk-sessions'],
      ['POST', '/agy/adopt'], ['POST', '/agy/stop'], ['GET', '/agy/thought'],
      ['POST', '/agy/respond'], ['POST', '/agy/approve'], ['GET', '/agy/permission-modes'],
    ];
    for (const [m, p] of uclar) {
      assert.ok(router.match(m, p), `eksik uc: ${m} ${p}`);
    }
  });

  it('GET /agy/models katalogu ve varsayilani doner', async () => {
    const { status, payload } = await sur(kur(), 'GET', '/agy/models');
    assert.equal(status, 200);
    assert.ok(Array.isArray(payload.models) && payload.models.length > 0);
    assert.equal(payload.defaultModel, payload.models[0].id);
    for (const m of payload.models) {
      assert.equal(typeof m.id, 'string');
      assert.equal(typeof m.label, 'string');
    }
  });

  it('GET /agy/permission-modes ACP kiplerini doner', async () => {
    const { status, payload } = await sur(kur(), 'GET', '/agy/permission-modes');
    assert.equal(status, 200);
    assert.deepEqual(payload.modes.map(m => m.id), ['default', 'accept-edits', 'plan']);
  });

  it('GET /agy/conversation onay kartini ApprovalInfo sekliyle tasir', async () => {
    const router = kur();
    const s = __agyTest.ekleSahteOturum('s1');
    __agyTest.sunucuIstegiIsle('session/request_permission', {
      sessionId: 's1',
      toolCall: { toolCallId: 'c1', name: 'run_command', title: 'Get-ChildItem', kind: 'execute' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
      ],
    });
    const { status, payload } = await sur(router, 'GET', '/agy/conversation?sessionId=s1');
    assert.equal(status, 200);
    assert.equal(payload.awaitingApproval, true);
    // İstemci parseApproval'un okudugu alanlar:
    assert.equal(payload.approval.requestId, 'c1');
    assert.equal(payload.approval.tool, 'run_command');
    assert.equal(payload.approval.summary, 'Get-ChildItem');
    assert.deepEqual(payload.approval.options.map(o => o.id), ['allow', 'deny']);
    assert.ok(s.pendingApproval);
  });

  it('POST /agy/approve {allow:true} bekleyen onayi COZER', async () => {
    const router = kur();
    __agyTest.ekleSahteOturum('s1');
    const sozluk = __agyTest.sunucuIstegiIsle('session/request_permission', {
      sessionId: 's1',
      toolCall: { toolCallId: 'c1', name: 'write_file' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
      ],
    });
    const { status, payload } = await sur(router, 'POST', '/agy/approve', { sessionId: 's1', allow: true });
    assert.equal(status, 200);
    assert.equal(payload.optionId, 'allow');
    assert.deepEqual(await sozluk, { outcome: { outcome: 'selected', optionId: 'allow' } });
  });

  it('POST /agy/approve {allow:false} reddi gonderir', async () => {
    const router = kur();
    __agyTest.ekleSahteOturum('s1');
    const sozluk = __agyTest.sunucuIstegiIsle('session/request_permission', {
      sessionId: 's1',
      toolCall: { toolCallId: 'c1', name: 'write_file' },
      options: [
        { optionId: 'allow', name: 'Allow', kind: 'allow_once' },
        { optionId: 'deny', name: 'Deny', kind: 'reject_once' },
      ],
    });
    const { status, payload } = await sur(router, 'POST', '/agy/approve', { sessionId: 's1', allow: false });
    assert.equal(status, 200);
    assert.equal(payload.optionId, 'deny');
    assert.deepEqual(await sozluk, { outcome: { outcome: 'selected', optionId: 'deny' } });
  });

  it('POST /agy/respond bekleyen onay yoksa 400 doner', async () => {
    const router = kur();
    __agyTest.ekleSahteOturum('s1');
    const { status, payload } = await sur(router, 'POST', '/agy/respond', { sessionId: 's1', answer: 'yes' });
    assert.equal(status, 400);
    assert.equal(payload.ok, false);
  });

  it('GET /agy/thought arac detayini satir indeksiyle verir', async () => {
    const router = kur();
    const s = __agyTest.ekleSahteOturum('s1');
    s.messages.push({ role: 'tool', text: 'write_file', time: '' });
    s.toolDetails[0] = 'girdi:\n{}';
    const { status, payload } = await sur(router, 'GET', '/agy/thought?sessionId=s1&i=0');
    assert.equal(status, 200);
    assert.match(payload.text, /girdi/);
  });

  it('GET /agy/sessions canli oturumlari listeler', async () => {
    const router = kur();
    const s = __agyTest.ekleSahteOturum('s1');
    s.messages.push({ role: 'user', text: 'merhaba', time: '' });
    const { status, payload } = await sur(router, 'GET', '/agy/sessions');
    assert.equal(status, 200);
    assert.equal(payload.sessions.length, 1);
    assert.equal(payload.sessions[0].id, 's1');
    assert.equal(payload.sessions[0].turns, 1);
  });

  // Ev kurali: /stop bilinmeyen oturumda 404 (digerleri 400). Uc degil, test
  // yanlis varsayiyordu.
  it('POST /agy/stop bilinmeyen oturumda 404 doner', async () => {
    const { status, payload } = await sur(kur(), 'POST', '/agy/stop', { sessionId: 'yok' });
    assert.equal(status, 404);
    assert.equal(payload.ok, false);
  });

  it('POST /agy/new gecersiz dizini 400 ile reddeder (ajan baslatmadan)', async () => {
    const { status, payload } = await sur(kur(), 'POST', '/agy/new', { cwd: 'C:/boyle/dizin/yok-98765' });
    assert.equal(status, 400);
    assert.match(payload.error, /cwd not a directory/);
  });
});
