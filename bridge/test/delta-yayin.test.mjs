// Delta yayınının backend KAPSAMI. Protokolün kendisi agent-session-core
// testlerinde; buradaki soru şu: hangi backend'ler onu gerçekten kullanıyor?
//
// Kapsam boşluğu canlıda pahalıya patladı: omp, opencode-app ve agy `subscribe`
// içinde opts'u yutuyordu, dolayısıyla `delta=1` isteyen istemciye (Android
// 07.08'den beri, web de öyle) HER karede tam transkript gidiyordu. Bu dosya
// bir backend eklendiğinde ya da subscribe imzası bozulduğunda uyarır.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-delta-'));
process.env.AGENTBRIDGE_OMP_ROOT = process.env.AGENTBRIDGE_OMP_ROOT || root;

const claudeApp = await import('../claude-app.mjs');
const codexApp = await import('../codex-app.mjs');
const omp = await import('../omp-app.mjs');
const agy = await import('../agy.mjs');

function fakeWs() {
  const ws = {
    sent: [],
    send(v) { ws.sent.push(JSON.parse(v)); },
    on() {},
    close() {},
  };
  return ws;
}

// Akışı olan her backend: subscribe(sessionId, ws, opts) imzasını taşımalı ve
// opts'u sessionCore'a iletmeli. `delta: true` geçilen abone delta kipine
// alınmalı (ws._deltaMode) — bunu yutmak sessiz bir gerilemedir, hata vermez.
const BACKENDS = [
  { name: 'claude-app', mod: claudeApp },
  { name: 'codex-app', mod: codexApp },
  { name: 'omp', mod: omp },
  { name: 'agy', mod: agy },
];

// Bu üçü canlıda opts'u yutuyordu. Arity'ye BAKMA: `opts = {}` varsayılanı
// Function.length'i 2 yapar, yani imza testi yeşil kalırken davranış bozuk
// olabilir (bu dosyada bir kez yaşandı). Ölçüt ws._deltaMode.
const FIXED = [
  { name: 'omp', mod: omp, drop: id => omp.__ompAppTest.removeSession(id) },
  { name: 'agy', mod: agy, drop: () => {} },
];

describe('delta yayını backend kapsamı', () => {
  for (const { name, mod } of BACKENDS) {
    it(`${name}: bilinmeyen oturumda hata gönderip soketi kapatır`, () => {
      const ws = fakeWs();
      let closed = false;
      ws.close = () => { closed = true; };
      mod.subscribe('yok-boyle-oturum-' + name, ws, { delta: true });
      assert.equal(ws.sent[0]?.type, 'error');
      assert.equal(closed, true);
    });
  }

  for (const { name, mod, drop } of FIXED) {
    it(`${name}: delta isteyen abone delta kipine alınır, istemeyen düz kalır`, () => {
      const created = mod.newSession({ cwd: root });
      assert.equal(created.ok, true, `${name}.newSession başarısız`);
      const deltaWs = fakeWs();
      const plainWs = fakeWs();
      mod.subscribe(created.sessionId, deltaWs, { delta: true });
      mod.subscribe(created.sessionId, plainWs, {});
      assert.equal(deltaWs._deltaMode, true, `${name}: delta=1 isteği yutulmamalı`);
      assert.equal(plainWs._deltaMode, false);
      // İlk mesaj her iki kipte de tam konuşmadır (delta'nın tabanı odur) ve
      // seq taşımalıdır — istemci yeniden bağlanırken since=seq gönderiyor.
      assert.equal(deltaWs.sent[0].type, 'conversation');
      assert.ok(Number.isFinite(deltaWs.sent[0].seq), `${name}: snapshot seq taşımalı`);
      drop(created.sessionId);
    });
  }

  it('agy onay istemi (pendingPrompt) delta ile taşınabilir alanlar arasında', async () => {
    // pendingPrompt META_KEYS'te değilse delta kipinde HİÇ gönderilmez ve
    // OAuth/login kartı telefonda hiç görünmez. agy'ye özgü alan olduğu için
    // core'a eklenmesi kolayca unutulur.
    const { diffSnapshots } = await import('../agent-session-core.mjs');
    const prev = { messages: [], pendingPrompt: null };
    const next = { messages: [], pendingPrompt: { prompt: 'Continue?', options: ['Yes', 'No'] } };
    const ops = diffSnapshots(prev, next);
    const meta = ops.find(op => op.op === 'setMeta');
    assert.ok(meta, 'pendingPrompt değişimi setMeta üretmeli');
    assert.deepEqual(meta.meta.pendingPrompt, next.pendingPrompt);
  });
});
