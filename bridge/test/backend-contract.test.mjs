import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  CONTRACT_VERSION,
  CAPABILITY_SCHEMA,
  BACKENDS,
  POLL_APPROVAL_BACKENDS,
  buildCatalog,
  describeBackend,
  normalizeCapabilities,
  coworkCapabilities,
  verifyAdapter,
} from '../backend-contract.mjs';

import * as agy from '../agy.mjs';
import * as claudeApp from '../claude-app.mjs';
import * as codexApp from '../codex-app.mjs';
import * as omp from '../omp-app.mjs';
import * as cowork from '../cowork.mjs';

// Testin bildiği gerçek modüller. Katalogdaki her id burada bulunmalı; eksikse
// katalog gerçek koddan sapmış demektir (aşağıdaki bütünlük testi yakalar).
const MODULES = {
  'agy': agy,
  'claude-app': claudeApp,
  'codex-app': codexApp,
  'omp': omp,
  'cowork': cowork,
};

describe('backend contract catalog', () => {
  it('publishes a versioned catalog covering every declared backend', () => {
    const catalog = buildCatalog();
    assert.equal(catalog.contractVersion, CONTRACT_VERSION);
    assert.deepEqual(catalog.backends.map(b => b.id).sort(), Object.keys(BACKENDS).sort());
  });

  it('normalizes every capability field so the served object is complete', () => {
    for (const b of buildCatalog().backends) {
      assert.deepEqual(Object.keys(b.capabilities).sort(), Object.keys(CAPABILITY_SCHEMA).sort(),
        `${b.id} capabilities must match schema`);
      assert.ok(b.id && b.apiBackend && b.label, `${b.id} missing identity fields`);
    }
  });

  it('every catalog id has a real backend module', () => {
    for (const id of Object.keys(BACKENDS)) {
      assert.ok(MODULES[id], `catalog lists ${id} but no module is wired`);
    }
  });

  it('declares efforts/interrupt only where the routes actually exist', () => {
    // Bu iki uç registerBackend'in ORTAK yüzeyinde değil, server.mjs'de
    // backend'e özel `extras` olarak kayıtlı — bu yüzden hiçbir yetenek alanı
    // onları karşılamıyordu ve arayüz desteklenmeyen kontrolü gizleyemiyordu.
    // Burada kilitlenen şey BİLDİRİM; gerçek kayıt server.mjs'de.
    const caps = Object.fromEntries(buildCatalog().backends.map(b => [b.id, b.capabilities]));

    // /efforts + /effort: Claude, Codex ve OMP.
    assert.equal(caps['claude-app'].efforts, true);
    assert.equal(caps['codex-app'].efforts, true);
    assert.equal(caps['omp'].efforts, true);

    // /interrupt: yalnız claude-app. (/stop hepsinde var, o ayrı.)
    assert.equal(caps['claude-app'].interrupt, true);
    assert.equal(caps['codex-app'].interrupt, false);

    // GET /<b>/diff: "Değişiklikler" görünümü. Kaynak codex'te akan fileChange
    // item'ları; Claude/OMP'de karşılığı yok, ilan da edilmiyor.
    assert.equal(caps['claude-app'].sessionDiff, false);
    assert.equal(caps['codex-app'].sessionDiff, true);
    assert.equal(caps.omp.sessionDiff, false);

    // Şemadan düşen alanlar artık hiçbir backend'de yayınlanmıyor; telefon
    // onları görürse ölü kontrol çizerdi.
    for (const alan of ['runpod', 'agents', 'sessionRevert', 'subagents', 'sessionShare',
      'sessionCommands', 'agentsInit', 'sessionInstructions', 'savedPermissions']) {
      assert.equal(alan in CAPABILITY_SCHEMA, false, alan);
    }
    assert.deepEqual(Object.keys(BACKENDS).sort(), ['agy', 'claude-app', 'codex-app', 'cowork', 'omp']);

    // agy (ACP): onay ve izin kipi VAR; efforts/interrupt/diff yok, context yok
    // (ACP usage toplami veriyor, pencere boyutu vermiyor).
    assert.equal(caps.agy.approvals, true);
    assert.equal(caps.agy.permissionModes, true);
    assert.equal(caps.agy.efforts, false);
    assert.equal(caps.agy.interrupt, false);
    assert.equal(caps.agy.context, false);

    assert.equal(caps['claude-app'].sessionPin, true);
    assert.equal(caps['codex-app'].sessionArchive, true);
    assert.equal(caps.omp.sessionDelete, true);
  });

  // Tur sürerken gönderim iki ayrı uç: /steer (süren tura enjeksiyon) ve
  // /follow-up (tur bitince işlensin). Telefon composer kipini BU ikisinden
  // türetiyor; kaba `userInput` tek başına yetmiyordu ve gerçekten steer'i
  // olmayan bir backend'e "Yönlendir" tuşu çizdirebilirdi.
  it('declares steer/queue only where the mid-turn routes actually exist', () => {
    const caps = Object.fromEntries(buildCatalog().backends.map(b => [b.id, b.capabilities]));

    // OMP: ikisi de kayıtlı (server.mjs /steer + /follow-up).
    assert.equal(caps.omp.userInputSteer, true, 'omp steer');
    assert.equal(caps.omp.userInputQueue, true, 'omp queue');

    // Codex'te /steer var ama /follow-up yok; Claude'da ikisi de yok.
    assert.equal(caps['codex-app'].userInputSteer, true);
    assert.equal(caps['codex-app'].userInputQueue, false);
    assert.equal(caps['claude-app'].userInputSteer, false);
    assert.equal(caps['claude-app'].userInputQueue, false);
  });

  it('derives cowork capabilities from the selected provider plus shared outputs', () => {
    const caps = coworkCapabilities('codex-app');
    assert.equal(caps.plan, true);      // Codex sağlayıcısından gelir
    assert.equal(caps.outputs, true);   // cowork ortak teslimat katmanı
    // Bilinmeyen provider claude-app tabanına düşer, yine de outputs kazanır.
    assert.equal(coworkCapabilities('nope').outputs, true);
  });
});

describe('adapter conformance (real modules)', () => {
  for (const [id, descriptor] of Object.entries(BACKENDS)) {
    it(`${id} conforms to the contract`, () => {
      const result = verifyAdapter(id, MODULES[id]);
      assert.deepEqual(result.problems, [], `${id}: ${result.problems.join('; ')}`);
      assert.equal(result.ok, true);
    });
    void descriptor;
  }

  it('every poll-approval backend actually exports getPendingApproval', () => {
    for (const id of POLL_APPROVAL_BACKENDS) {
      assert.equal(typeof MODULES[id].getPendingApproval, 'function', `${id} must poll approvals`);
    }
  });

  // OMP çıplak dizi döndürdüğü için Android ve web listeleri sessizce boş
  // kalıyordu (ikisi de `sessions` alanını okuyor, dizide bulamıyor). Şekil
  // sözleşmesi burada bağlanır: her disk-sessions uygulaması {ok, sessions}.
  it('every listDiskSessions returns the {ok, sessions} envelope', async () => {
    for (const [id, mod] of Object.entries(MODULES)) {
      if (typeof mod.listDiskSessions !== 'function') continue;
      const result = await mod.listDiskSessions({});
      assert.equal(typeof result, 'object', `${id}: nesne dönmeli`);
      assert.ok(!Array.isArray(result), `${id}: çıplak dizi değil, {ok, sessions} dönmeli`);
      assert.equal(result.ok, true, `${id}: ok=true bekleniyor`);
      assert.ok(Array.isArray(result.sessions), `${id}: sessions dizi olmalı`);
    }
  });

  it('flags an adapter that is missing a required method', () => {
    const broken = verifyAdapter('claude-app', { newSession() {}, prompt() {}, getConversation() {}, stop() {} });
    assert.equal(broken.ok, false);
    assert.ok(broken.problems.some(p => p.includes('listSessions')));
  });

  it('flags a poll-approval backend without getPendingApproval', () => {
    const broken = verifyAdapter('codex-app', {
      listSessions() {}, newSession() {}, prompt() {}, getConversation() {}, stop() {},
    });
    assert.ok(broken.problems.some(p => p.includes('getPendingApproval')));
  });
});

describe('backend descriptor lookup', () => {
  it('returns null for an unknown backend', () => {
    assert.equal(describeBackend('made-up'), null);
    assert.equal(verifyAdapter('made-up', {}).ok, false);
  });

  it('normalizeCapabilities fills defaults and coerces to boolean', () => {
    const caps = normalizeCapabilities({ approvals: 1 });
    assert.equal(caps.approvals, true);
    assert.equal(caps.context, true);   // şema varsayılanı
    assert.equal(caps.plan, false);
  });
});
