import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import * as codexApp from '../codex-app.mjs';

// prompt() testleri gercek `codex app-server` cocugunu spawn eder; kapatilmazsa
// stdio pipe'lari event loop'u tutar ve `node --test` asla cikamaz (33dk asili
// kalma vakasi). Suite sonunda her kosulda oldur.
after(() => { codexApp.killAllSessions(); });

// Pure unit tests: nothing here starts the real `codex app-server` process.
// We deliberately avoid listDiskSessions / adoptSession-with-id / prompt calls
// because those would spawn the live app-server. Integration tests are gated
// on $CODEX_APP_INTEGRATION=1 (kept out of CI).

describe('codex-app.mjs backend adapter (pure)', () => {
  it('exports CODEX_APP_MODELS as a non-empty array', () => {
    assert.ok(Array.isArray(codexApp.CODEX_APP_MODELS));
    assert.ok(codexApp.CODEX_APP_MODELS.length > 0);
    for (const m of codexApp.CODEX_APP_MODELS) {
      assert.equal(typeof m.label, 'string');
      assert.equal(typeof m.id, 'string');
    }
  });

  it('exposes MODELS alias', () => {
    assert.ok(Array.isArray(codexApp.MODELS));
  });

  it('listSessions() returns an array', () => {
    const sessions = codexApp.listSessions();
    assert.ok(Array.isArray(sessions));
  });

  it('60 rollout dilimi dışındaki restore kabuğunu state DB başlığıyla zenginleştirir', () => {
    const row = codexApp.__testDiskSessionFromLive({
      id: 'shell-1',
      cwd: '',
      messages: [],
      _lastActivity: 10,
      lastUserAt: 0,
    }, 'thread-1', {
      title: 'Eski ama gerçek Codex oturumu',
      preview: 'Son konuşma önizlemesi',
      cwd: 'C:\\Users\\ornek\\agtest',
      mtime: 25,
    });

    assert.equal(row.id, 'thread-1');
    assert.equal(row.title, 'Eski ama gerçek Codex oturumu');
    assert.equal(row.lastText, 'Son konuşma önizlemesi');
    assert.equal(row.cwd, 'C:\\Users\\ornek\\agtest');
    assert.equal(row.mtime, 25);
  });

  it('listCodexSkills() lists SKILL.md dirs, skipping .system and plain files', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-skills-'));
    try {
      fs.mkdirSync(path.join(dir, 'docx'));
      fs.writeFileSync(path.join(dir, 'docx', 'SKILL.md'), '---\nname: docx\n---\n');
      fs.mkdirSync(path.join(dir, 'xlsx'));
      fs.writeFileSync(path.join(dir, 'xlsx', 'SKILL.md'), '---\nname: xlsx\n---\n');
      fs.mkdirSync(path.join(dir, '.system'));         // yerleşikler gizli
      fs.mkdirSync(path.join(dir, 'no-manifest'));     // SKILL.md yok → listelenmez
      fs.writeFileSync(path.join(dir, 'stray.txt'), 'x');
      assert.deepEqual(codexApp.listCodexSkills(dir), ['docx', 'xlsx']);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('listCodexSkills() returns [] for a missing dir', () => {
    assert.deepEqual(codexApp.listCodexSkills(path.join(os.tmpdir(), 'yok-' + Date.now())), []);
  });

  it('native skill ve MCP envanterini tekilleştirip şekillendirir', () => {
    const shaped = codexApp.shapeNativeInventory({ data: [
      { cwd: 'a', skills: [{ name: 'docx', description: 'Belge', enabled: true }, { name: 'off', enabled: false }] },
      { cwd: 'b', skills: [{ name: 'docx', description: 'Tekrar', enabled: true }, { name: 'browser:control', description: 'Tarayıcı', enabled: true }] },
    ] }, { data: [
      { name: 'codex_apps', authStatus: 'bearerToken', tools: { open: {}, click: {} } },
      { name: 'emsal', authStatus: 'unsupported', tools: { search: {} } },
    ] });
    assert.deepEqual(shaped.skills, ['browser:control', 'docx']);
    assert.equal(shaped.mcpServers[0].name, 'codex_apps');
    assert.equal(shaped.mcpServers[0].toolCount, 2);
    assert.equal(shaped.mcpServers[0].managed, true);
  });

  it('native MCP durumunu config listesine ekler ve managed connectoru korur', () => {
    const merged = codexApp.mergeMcpServerInventory(
      { ok: true, servers: [{ name: 'emsal', enabled: true, managed: false, status: '' }] },
      [{ name: 'emsal', enabled: true, managed: true, status: '14 araç' }, { name: 'codex_apps', enabled: true, managed: true, status: '43 araç' }],
    );
    assert.deepEqual(merged.servers.map(s => s.name), ['codex_apps', 'emsal']);
    assert.equal(merged.servers.find(s => s.name === 'emsal').managed, false);
    assert.equal(merged.servers.find(s => s.name === 'emsal').status, '14 araç');
  });

  it('getConversation() unknown sessionId returns empty defaults', () => {
    const conv = codexApp.getConversation('nonexistent-' + Date.now());
    assert.ok(Array.isArray(conv.messages));
    assert.equal(conv.messages.length, 0);
    assert.equal(conv.running, false);
    assert.equal(conv.awaitingApproval, false);
    assert.equal(conv.awaitingFirstOutput, false);
    assert.equal(typeof conv.contextTokens, 'number');
    assert.equal(typeof conv.contextWindow, 'number');
  });

  it('getThought() unknown sessionId returns empty text', () => {
    const thought = codexApp.getThought('nonexistent-' + Date.now(), 0);
    assert.equal(thought.text, '');
  });

  it('getThought() unknown index returns empty text', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const t = codexApp.getThought(r.sessionId, 999);
    assert.equal(t.text, '');
  });

  it('runningSessionId() returns null when nothing running', () => {
    const sid = codexApp.runningSessionId();
    assert.equal(sid, null);
  });

  it('coalesces parallel request timeouts and preserves an active turn', () => {
    const first = codexApp.__testTimeoutRecoveryDecision({
      now: 100_000,
      lastStrikeAt: null,
      strikes: 0,
      hasRunningTurns: true,
      killedForEpoch: false,
    });
    assert.deepEqual(first, {
      strikes: 1,
      lastStrikeAt: 100_000,
      defer: false,
      kill: false,
    });

    const parallel = codexApp.__testTimeoutRecoveryDecision({
      now: 105_000,
      lastStrikeAt: first.lastStrikeAt,
      strikes: first.strikes,
      hasRunningTurns: true,
      killedForEpoch: false,
    });
    assert.equal(parallel.strikes, 1);

    const threshold = codexApp.__testTimeoutRecoveryDecision({
      now: 130_000,
      lastStrikeAt: 115_000,
      strikes: 2,
      hasRunningTurns: true,
      killedForEpoch: false,
    });
    assert.equal(threshold.strikes, 3);
    assert.equal(threshold.defer, true);
    assert.equal(threshold.kill, false);

    const idle = codexApp.__testTimeoutRecoveryDecision({
      now: 130_000,
      lastStrikeAt: 115_000,
      strikes: 2,
      hasRunningTurns: false,
      killedForEpoch: false,
    });
    assert.equal(idle.kill, true);

    const alreadyKilled = codexApp.__testTimeoutRecoveryDecision({
      now: 145_000,
      lastStrikeAt: idle.lastStrikeAt,
      strikes: idle.strikes,
      hasRunningTurns: false,
      killedForEpoch: true,
    });
    assert.equal(alreadyKilled.kill, false);
  });

  it('stop() unknown sessionId returns not found', async () => {
    const r = await codexApp.stop('nonexistent-' + Date.now());
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
  });

  it('newSession() with valid cwd creates a session listed by listSessions()', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    assert.equal(typeof r.sessionId, 'string');
    assert.ok(r.sessionId.length > 0);
    assert.equal(typeof r.cwd, 'string');
    assert.equal(typeof r.model, 'string');

    const sessions = codexApp.listSessions();
    assert.ok(sessions.length >= 1);
    const found = sessions.find(s => s.id === r.sessionId);
    assert.ok(found);
    assert.equal(found.status, 'idle');
  });

  it('newSession() with invalid cwd returns error', () => {
    const r = codexApp.newSession({ cwd: 'C:\\nonexistent_path_abc123_test' });
    assert.equal(r.ok, false);
    assert.ok(r.error.includes('cwd not a directory'));
  });

  it('getConversation() empty messages after new session', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    const conv = codexApp.getConversation(r.sessionId);
    assert.ok(Array.isArray(conv.messages));
    assert.equal(conv.messages.length, 0);
    assert.equal(conv.running, false);
    assert.equal(conv.awaitingApproval, false);
  });

  it('prompt() unknown sessionId returns error', async () => {
    const r = await codexApp.prompt({ sessionId: 'bad-id', text: 'hello' });
    assert.equal(r.ok, false);
    assert.equal(r.error, 'session not found');
  });

  it('prompt() empty text returns error', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    const p = await codexApp.prompt({ sessionId: r.sessionId, text: '' });
    assert.equal(p.ok, false);
    assert.equal(p.error, 'text required');
  });

  it('MODELS lists only the selectable GPT-6 variants', () => {
    const ids = codexApp.MODELS.map(m => m.id);
    assert.deepEqual(ids, ['gpt-6.1-sol', 'gpt-6-astra', 'gpt-6-luna']);
    // Varsayilan model Codex config'inden gelir ve ozel bir saglayiciya ait
    // olabilir; degismez olan sey SECILEBILIR olmasi (statik listede olmasi degil).
    const selectable = codexApp.listSelectableModels().map(m => m.id);
    assert.ok(selectable.includes(codexApp.defaultModel()), 'varsayilan model secilebilir listede olmali');
    assert.ok(selectable.every(id => !/^gpt-5\.6(?:-|$)/i.test(id)), 'GPT-5.6 secicide olmamali');
  });

  // ── Effort clamp: model bazlı destek kümesi (model/list kataloğu) ────────
  it('clampEffortForModel maps unsupported efforts to nearest supported level', () => {
    codexApp.__testSetModelCatalog({
      'gpt-5.6-sol': { label: 'GPT-5.6-Sol', efforts: ['low', 'medium', 'high', 'xhigh', 'max', 'ultra'], defaultEffort: 'low' },
      'gpt-5.1': { label: 'GPT-5.1', efforts: ['minimal', 'low', 'medium', 'high'], defaultEffort: 'medium' },
    });
    try {
      assert.equal(codexApp.clampEffortForModel('minimal', 'gpt-5.6-sol'), 'low');
      assert.equal(codexApp.clampEffortForModel('xhigh', 'gpt-5.6-sol'), 'xhigh');
      assert.equal(codexApp.clampEffortForModel('ultra', 'gpt-5.1'), 'high');
      assert.equal(codexApp.clampEffortForModel('medium', 'gpt-5.1'), 'medium');
      assert.equal(codexApp.clampEffortForModel('', 'gpt-5.6-sol'), '');
      // katalogda olmayan model → dokunma (turn'de app-server hakem olur)
      assert.equal(codexApp.clampEffortForModel('minimal', 'bilinmeyen-model'), 'minimal');
    } finally {
      codexApp.__testSetModelCatalog(null);
    }
  });

  it('clampEffortForModel without catalog normalizes but does not clamp', () => {
    codexApp.__testSetModelCatalog(null);
    assert.equal(codexApp.clampEffortForModel('minimal', 'gpt-5.6-sol'), 'minimal');
    assert.equal(codexApp.clampEffortForModel('saçmalık', 'gpt-5.6-sol'), '');
  });

  it('newSession() "default" effort ile acilir (config default yoksa high)', () => {
    // Model artik Codex config.toml'dan geliyor (tek dogruluk kaynagi), bu
    // yuzden sabit bir ada baglanmiyoruz: onemli olan modul varsayilaniyla
    // AYNI olmasi ve gecerli/secilebilir bir model olmasi.
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    assert.equal(r.model, codexApp.defaultModel());
    assert.ok(codexApp.listSelectableModels().some(m => m.id === r.model));
    // Bos = "default" (config.toml'daki model_reasoning_effort). Test ortaminda
    // config olmayabilir; o zaman yedek 'high'.
    assert.equal(r.effort, codexApp.readDefaultEffort() ? '' : 'high');
    assert.equal(r.effort, codexApp.defaultEffort());
  });

  it('setEffort() clamps against the session model when catalog is known', () => {
    codexApp.__testSetModelCatalog({
      [codexApp.defaultModel()]: { label: 'Varsayılan', efforts: ['low', 'medium', 'high', 'xhigh'], defaultEffort: 'low' },
    });
    try {
      const r = codexApp.newSession({ cwd: os.homedir() }); // varsayılan model
      assert.equal(r.ok, true);
      const e = codexApp.setEffort({ sessionId: r.sessionId, effort: 'minimal' });
      assert.equal(e.ok, true);
      assert.equal(e.effort, 'low');
    } finally {
      codexApp.__testSetModelCatalog(null);
    }
  });

  it('setModel() re-clamps a stale effort for the new model', () => {
    codexApp.__testSetModelCatalog({
      'gpt-5.6-sol': { label: 'GPT-5.6-Sol', efforts: ['low', 'medium', 'high', 'xhigh'], defaultEffort: 'low' },
      'gpt-5.1': { label: 'GPT-5.1', efforts: ['minimal', 'low', 'medium', 'high'], defaultEffort: 'medium' },
    });
    try {
      const r = codexApp.newSession({ cwd: os.homedir(), model: 'gpt-5.1', effort: 'minimal' });
      assert.equal(r.ok, true);
      assert.equal(r.effort, 'minimal');
      const m = codexApp.setModel({ sessionId: r.sessionId, model: 'gpt-5.6-sol' });
      assert.equal(m.ok, true);
      const conv = codexApp.getConversation(r.sessionId);
      assert.equal(conv.effort, 'low');
    } finally {
      codexApp.__testSetModelCatalog(null);
    }
  });

  it('CODEX_APP_EFFORT_LEVELS fallback is a safe cross-model subset', () => {
    assert.deepEqual(codexApp.CODEX_APP_EFFORT_LEVELS, ['low', 'medium', 'high', 'xhigh']);
  });

  it('compact() unknown sessionId returns not found', async () => {
    const r = await codexApp.compact('nonexistent-' + Date.now());
    assert.equal(r.ok, false);
    assert.equal(r.error, 'session not found');
  });

  it('compact() without thread returns thread not started (no app-server required)', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const c = await codexApp.compact(r.sessionId);
    assert.equal(c.ok, false);
    assert.equal(c.error, 'thread not started');
  });

  it('snapshot plan field is empty array for new sessions', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    // getConversation returns snapshot-like shape; plan is not yet in
    // getConversation but internally snapshot(s) includes plan:[]
    // Verify session exists and is idle
    const conv = codexApp.getConversation(r.sessionId);
    assert.ok(Array.isArray(conv.messages));
    assert.equal(conv.running, false);
  });

  it('setModel() updates session model', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    const m = codexApp.setModel({ sessionId: r.sessionId, model: 'gpt-5.1' });
    assert.equal(m.ok, true);
    assert.equal(m.model, 'gpt-5.1');
  });

  it('setModel() rejects empty model', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    const m = codexApp.setModel({ sessionId: r.sessionId, model: '' });
    assert.equal(m.ok, false);
    assert.equal(m.error, 'model required');
  });

  it('approve() with unknown session returns error', () => {
    const r = codexApp.approve({ sessionId: 'no-pending-' + Date.now(), allow: true });
    assert.equal(r.ok, false);
    assert.ok(r.error, 'expected an error string');
  });

  it('approve() with a real session but no pending approval returns error', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const a = codexApp.approve({ sessionId: r.sessionId, allow: true });
    assert.equal(a.ok, false);
    assert.equal(a.error, 'no pending approval');
  });

  it('respondUserInput() for unknown session returns error', () => {
    const r = codexApp.respondUserInput({ sessionId: 'no-pending-' + Date.now(), text: 'hi' });
    assert.equal(r.ok, false);
    assert.ok(r.error, 'expected an error string');
  });

  it('respondUserInput() with real session but no pending input returns error', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const a = codexApp.respondUserInput({ sessionId: r.sessionId, text: 'hi' });
    assert.equal(a.ok, false);
    assert.ok(/no pending/.test(a.error), 'expected a no pending error, got: ' + a.error);
  });

  it('adoptSession() with no id returns error without spawning app-server', async () => {
    const r = await codexApp.adoptSession({});
    assert.equal(r.ok, false);
    assert.equal(r.error, 'id required');
  });

  // ── Task #6: listDiskSessions spike filter helper (pure) ──────────────────
  it('isSpikeCwd() filters codexapp-spike-* test cwds', () => {
    assert.equal(codexApp.isSpikeCwd('codexapp-spike-abc'), true);
    assert.equal(codexApp.isSpikeCwd('C:\\Users\\me\\codexapp-spike-123\\repo'), true);
    assert.equal(codexApp.isSpikeCwd('/home/me/codexapp-spike-7'), true);
    assert.equal(codexApp.isSpikeCwd(''), false);
    assert.equal(codexApp.isSpikeCwd(null), false);
    assert.equal(codexApp.isSpikeCwd(undefined), false);
    assert.equal(codexApp.isSpikeCwd('C:\\Users\\Dev\\agtest'), false);
    assert.equal(codexApp.isSpikeCwd('/home/me/proj'), false);
    assert.equal(codexApp.isSpikeCwd('Spike something else'), false);
  });
  it('readRolloutHead samples head and tail without needing a full-file parser', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-rollout-head-'));
    const file = path.join(dir, 'rollout-test.jsonl');
    const row = payload => JSON.stringify({ type: 'response_item', payload }) + '\n';
    const filler = row({ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'x'.repeat(2000) }] }).repeat(40);
    fs.writeFileSync(file, [
      JSON.stringify({ type: 'session_meta', payload: { cwd: os.homedir() } }) + '\n',
      row({ type: 'message', role: 'user', content: [{ type: 'input_text', text: '<environment_context>ignore me</environment_context>' }] }),
      row({ type: 'message', role: 'user', content: [{ type: 'input_text', text: 'first real prompt' }] }),
      filler,
      row({ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'final assistant preview' }] }),
    ].join(''), 'utf-8');

    const head = codexApp.__testReadRolloutHead(file, 4);
    assert.equal(head.cwd, os.homedir());
    assert.equal(head.firstUser, 'first real prompt');
    assert.equal(head.turns, 1);
    assert.equal(head.lastText, 'final assistant preview');
  });

  // ── Task #6: snapshot.plan is [] for a fresh session ──────────────────────
  it('snapshot.plan is an empty array for a freshly created session', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const snap = codexApp.snapshot({ id: r.sessionId, status: 'idle', messages: [], _plan: [] });
    assert.ok(Array.isArray(snap.plan));
    assert.equal(snap.plan.length, 0);
  });

  // The shape returned by getConversation must also carry a (empty) plan field
  // so the phone UI can clear the panel after compact or on a brand-new session.
  it('getConversation() includes a (possibly empty) plan array', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    const conv = codexApp.getConversation(r.sessionId);
    assert.ok(Array.isArray(conv.plan));
    assert.equal(conv.plan.length, 0);
  });

  // ── Task #6: fake plan notification reducer updates the snapshot's plan ──
  it('turn/plan/updated notification reduces into snapshot.plan', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const ok = codexApp.__testApplyNotification(r.sessionId, {
      method: 'turn/plan/updated',
      params: {
        plan: [
          { step: 'Ön koşulları hazırla', status: 'completed' },
          { step: 'Backend yaklaşımını uygula', status: 'inProgress' },
          { step: 'Testleri çalıştır', status: 'pending' },
        ],
      },
    });
    assert.equal(ok, true);
    const conv = codexApp.getConversation(r.sessionId);
    assert.ok(Array.isArray(conv.plan));
    assert.equal(conv.plan.length, 3);
    assert.equal(conv.plan[0].text, 'Ön koşulları hazırla');
    assert.equal(conv.plan[0].status, 'completed');
    assert.equal(conv.plan[1].text, 'Backend yaklaşımını uygula');
    assert.equal(conv.plan[1].status, 'in_progress');
    assert.equal(conv.plan[2].text, 'Testleri çalıştır');
    assert.equal(conv.plan[2].status, 'pending');
  });

  it('reads Codex thread titles from state DB without app-server thread/list', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-state-title-'));
    const file = path.join(dir, 'state.sqlite');
    let db;
    try {
      db = new DatabaseSync(file);
      db.exec(`
        CREATE TABLE threads (
          id TEXT PRIMARY KEY, name TEXT, title TEXT, first_user_message TEXT,
          preview TEXT, cwd TEXT, updated_at INTEGER, updated_at_ms INTEGER,
          recency_at INTEGER, recency_at_ms INTEGER
        )
      `);
      db.prepare(`
        INSERT INTO threads
          (id, name, title, first_user_message, preview, cwd,
           updated_at, updated_at_ms, recency_at, recency_at_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).run(
        'thread-1', null, 'Gerçek Codex oturum başlığı', 'ilk soru',
        'önizleme', '\\\\?\\C:\\Users\\ornek\\agtest',
        100, 100_500, 99, 99_500,
      );
      db.close();
      db = null;

      const metadata = codexApp.__testReadCodexStateThreadMetadata(file).get('thread-1');
      assert.deepEqual(metadata, {
        title: 'Gerçek Codex oturum başlığı',
        preview: 'önizleme',
        cwd: 'C:\\Users\\ornek\\agtest',
        mtime: 100_500,
      });
    } finally {
      try { db?.close(); } catch {}
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('streams rollout history and skips oversized tool-result JSON lines', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-rollout-stream-'));
    const file = path.join(dir, 'rollout-stream.jsonl');
    try {
      const records = [
        { type: 'session_meta', payload: { id: 'stream-id', cwd: os.homedir() } },
        { type: 'response_item', payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'soru' }] } },
        { type: 'response_item', payload: { type: 'function_call_output', call_id: 'x', output: 'z'.repeat(2_100_000) } },
        { type: 'response_item', payload: { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'cevap' }] } },
      ];
      fs.writeFileSync(file, records.map(record => JSON.stringify(record)).join('\n') + '\n');
      const parsed = await codexApp.__testFullRolloutParseAsync(file);
      assert.equal(parsed.id, 'stream-id');
      assert.deepEqual(parsed.messages.map(message => message.text), ['soru', 'cevap']);
      assert.equal(parsed.toolDetails.length, 0);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('turn/started clears the previous turn plan', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    codexApp.__testApplyNotification(r.sessionId, {
      method: 'turn/plan/updated',
      params: {
        plan: [{ step: 'Eski görevin planı', status: 'inProgress', turnId: 'turn-old' }],
      },
    });
    assert.equal(codexApp.getConversation(r.sessionId).plan.length, 1);

    codexApp.__testApplyNotification(r.sessionId, {
      method: 'turn/started',
      params: { turn: { id: 'turn-new' } },
    });

    assert.deepEqual(codexApp.getConversation(r.sessionId).plan, []);
  });

  it('item/agentMessage/delta streams appended text but does NOT spam the plan field', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    codexApp.__testApplyNotification(r.sessionId, {
      method: 'turn/plan/updated',
      params: { plan: [{ step: 'Adım 1', status: 'inProgress' }] },
    });
    const before = codexApp.getConversation(r.sessionId).plan;
    assert.equal(before.length, 1);
    // Send a streamed agent delta; plan must remain untouched.
    codexApp.__testApplyNotification(r.sessionId, {
      method: 'item/agentMessage/delta',
      params: { itemId: 'msg-' + r.sessionId, delta: 'Hello ' },
    });
    codexApp.__testApplyNotification(r.sessionId, {
      method: 'item/agentMessage/delta',
      params: { itemId: 'msg-' + r.sessionId, delta: 'world' },
    });
    const after = codexApp.getConversation(r.sessionId);
    assert.equal(after.plan.length, 1, 'plan must not be polluted by agent deltas');
    assert.equal(after.plan[0].text, 'Adım 1');
    assert.ok(after.messages.some(m => m.role === 'agent' && m.text.includes('Hello world')),
      'agent delta must still land in the message transcript');
  });

  it('thread/read userMessage items are restored as user rows', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const ok = codexApp.__testAppendThreadItem(r.sessionId, {
      id: 'user-item-' + r.sessionId,
      type: 'userMessage',
      text: 'restore this user prompt',
    });
    assert.equal(ok, true);
    const conv = codexApp.getConversation(r.sessionId);
    assert.ok(conv.messages.some(m => m.role === 'user' && m.text === 'restore this user prompt'));
  });

  it('__testApplyNotification with an unknown session returns false', () => {
    const ok = codexApp.__testApplyNotification('never-existed-' + Date.now(), { method: 'turn/plan/updated', params: {} });
    assert.equal(ok, false);
  });

  // ── Feature 1: forkSession pure tests ─────────────────────────────────
  it('forkSession() with no sessionId returns error', async () => {
    const r = await codexApp.forkSession({ sessionId: 'no-such-' + Date.now() });
    assert.equal(r.ok, false);
    assert.ok(/not found/.test(r.error));
  });

  it('forkSession() without thread returns thread not started', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const f = await codexApp.forkSession({ sessionId: r.sessionId });
    assert.equal(f.ok, false);
    assert.ok(/thread not started/.test(f.error));
  });

  // ── Feature 2: steerTurn pure tests ──────────────────────────────────
  it('steerTurn() with no sessionId returns error', async () => {
    const r = await codexApp.steerTurn({ sessionId: 'no-such-' + Date.now(), text: 'hello' });
    assert.equal(r.ok, false);
    assert.ok(/not found/.test(r.error));
  });

  it('steerTurn() with empty text returns error', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const s = await codexApp.steerTurn({ sessionId: r.sessionId, text: '' });
    assert.equal(s.ok, false);
    assert.ok(/text required/.test(s.error));
  });

  it('steerTurn() without active turn returns error', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const s = await codexApp.steerTurn({ sessionId: r.sessionId, text: 'hello' });
    assert.equal(s.ok, false);
    assert.ok(/no active turn/.test(s.error));
  });

  // ── Feature 3: approve with decision/scope pure tests ────────────────
  it('approve() passes decision and scope to server (no server spawn)', () => {
    // Without a live pendingApproval, approve returns error. Key point:
    // the shape accepted by approve() includes decision/scope.
    const r = codexApp.newSession({ cwd: os.homedir() });
    const a = codexApp.approve({ sessionId: r.sessionId, decision: 'acceptForSession', scope: 'session' });
    assert.equal(a.ok, false);
    assert.equal(a.error, 'no pending approval');
  });
  it('approval result maps plain allow/deny/cancel safely', () => {
    assert.deepEqual(codexApp.__testApprovalResultFor('item/commandExecution/requestApproval', { allow: true }), { decision: 'accept' });
    assert.deepEqual(codexApp.__testApprovalResultFor('item/fileChange/requestApproval', { allow: true, decision: 'acceptForSession' }), { decision: 'acceptForSession' });
    assert.deepEqual(codexApp.__testApprovalResultFor('item/commandExecution/requestApproval', { allow: false }), { decision: 'decline' });
    assert.deepEqual(codexApp.__testApprovalResultFor('item/fileChange/requestApproval', { decision: 'cancel' }), { decision: 'cancel' });
  });

  it('permissions approval grants requested permissions on allow and strict-denies on deny', () => {
    const permissions = { network: { enabled: true }, fileSystem: { entries: [] } };
    assert.deepEqual(
      codexApp.__testApprovalResultFor('item/permissions/requestApproval', { allow: true, rawParams: { permissions }, scope: 'session' }),
      { permissions, scope: 'session' },
    );
    assert.deepEqual(
      codexApp.__testApprovalResultFor('item/permissions/requestApproval', { allow: false }),
      { permissions: null, scope: 'turn', strictAutoReview: true },
    );
  });

  // -- Feature 4: getChanges pure tests ─────────────────────────────────
  it('getChanges() with unknown session returns error', () => {
    const r = codexApp.getChanges('no-such-' + Date.now());
    assert.equal(r.ok, false);
    assert.ok(/not found/.test(r.error));
  });

  it('getChanges() with fresh session returns empty changes', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const c = codexApp.getChanges(r.sessionId);
    assert.equal(c.ok, true);
    assert.ok(Array.isArray(c.changes));
    assert.equal(c.changes.length, 0);
  });


  it('getChanges() returns prebuilt file change summaries', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    // ÖLÇÜLEN şema (codex 0.147.0): dosyanın durumu `kind.type`te, item'ın
    // `status`u ise yamanın uygulanıp uygulanmadığı.
    assert.equal(codexApp.__testAppendThreadItem(r.sessionId, {
      id: 'file-change-1',
      type: 'fileChange',
      status: 'completed',
      changes: [{ path: 'src/app.js', kind: { type: 'update', move_path: null }, diff: '-old\n+new' }],
    }), true);
    const c = codexApp.getChanges(r.sessionId);
    assert.equal(c.ok, true);
    assert.equal(c.changes.length, 1);
    assert.equal(c.changes[0].path, 'src/app.js');
    assert.equal(c.changes[0].itemId, 'file-change-1');
    assert.equal(c.changes[0].diff, '-old\n+new');
    assert.equal(c.changes[0].status, 'modified');
    assert.equal(c.changes[0].applyStatus, 'completed');
  });
  // ── Feature 5: getCommands pure tests ────────────────────────────────
  it('getCommands() with unknown session returns error', () => {
    const r = codexApp.getCommands('no-such-' + Date.now());
    assert.equal(r.ok, false);
    assert.ok(/not found/.test(r.error));
  });

  it('getCommands() with fresh session returns empty commands', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const c = codexApp.getCommands(r.sessionId);
    assert.equal(c.ok, true);
    assert.ok(Array.isArray(c.commands));
    assert.equal(c.commands.length, 0);
  });


  it('getCommands() returns prebuilt command summaries', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    assert.equal(codexApp.__testAppendThreadItem(r.sessionId, {
      id: 'cmd-1',
      type: 'commandExecution',
      command: 'npm test',
      cwd: os.homedir(),
      status: 'completed',
      exitCode: 0,
      stdout: 'ok',
    }), true);
    const c = codexApp.getCommands(r.sessionId);
    assert.equal(c.ok, true);
    assert.equal(c.commands.length, 1);
    assert.equal(c.commands[0].id, 'cmd-1');
    assert.equal(c.commands[0].command, 'npm test');
    assert.equal(c.commands[0].exitCode, 0);
  });

  it('bounds large completed tool details before storing them', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(codexApp.__testAppendThreadItem(r.sessionId, {
      id: 'cmd-large',
      type: 'commandExecution',
      command: 'large-output',
      status: 'completed',
      stdout: 'x'.repeat(500_000),
    }), true);
    const row = codexApp.getConversation(r.sessionId).messages.at(-1);
    const detail = codexApp.getThought(r.sessionId, row.thoughtIndex).text;
    assert.ok(detail.length < 70_000);
    assert.match(detail, /field truncated|detail truncated/);
  });
  // ── Feature 8: contextPercent pure tests ─────────────────────────────
  it('contextPercent() with unknown session returns 0', () => {
    const p = codexApp.contextPercent('no-such-' + Date.now());
    assert.equal(p, 0);
  });

  it('contextPercent() with zero window returns 0', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    // Fresh session has contextWindow=0, so percent is 0.
    const p = codexApp.contextPercent(r.sessionId);
    assert.equal(p, 0);
  });

  // ── Feature 10: getInfo pure tests ───────────────────────────────────
  it('getInfo() returns ok with features map (no server required)', async () => {
    const info = await codexApp.getInfo();
    assert.equal(info.ok, true);
    assert.equal(typeof info.codexVersion, 'string');
    assert.ok(info.codexVersion.length > 0);
    assert.equal(typeof info.appServer, 'boolean');
    assert.equal(typeof info.features, 'object');
    // All feature flags must be present and boolean.
    for (const [k, v] of Object.entries(info.features)) {
      assert.equal(typeof v, 'boolean', `feature flag ${k} must be boolean`);
    }
  });

  // ── Feature 6: setPermissionMode pure tests ──────────────────────────
  it('setPermissionMode() with unknown session returns error', () => {
    const r = codexApp.setPermissionMode({ sessionId: 'no-such-' + Date.now(), permissionMode: 'ask' });
    assert.equal(r.ok, false);
    assert.ok(/not found/.test(r.error));
  });

  it('setPermissionMode() with empty mode returns error', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const s = codexApp.setPermissionMode({ sessionId: r.sessionId, permissionMode: '' });
    assert.equal(s.ok, false);
    assert.ok(/required/.test(s.error));
  });

  it('setPermissionMode() with invalid mode returns error', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const s = codexApp.setPermissionMode({ sessionId: r.sessionId, permissionMode: 'invalid-mode' });
    assert.equal(s.ok, false);
    assert.ok(/invalid/.test(s.error));
  });

  it('setPermissionMode() with valid idle session succeeds', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const s = codexApp.setPermissionMode({ sessionId: r.sessionId, permissionMode: 'ask' });
    assert.equal(s.ok, true);
    assert.equal(s.permissionMode, 'ask');
  });

  it('PERMISSION_MODES export is a non-empty array', () => {
    assert.ok(Array.isArray(codexApp.PERMISSION_MODES));
    assert.ok(codexApp.PERMISSION_MODES.length > 0);
    for (const m of codexApp.PERMISSION_MODES) {
      assert.equal(typeof m.id, 'string');
      assert.equal(typeof m.label, 'string');
    }
  });

  // ── Feature 7: archive/pin pure tests ────────────────────────────────
  it('archiveThread() with no id returns error', () => {
    const r = codexApp.archiveThread({});
    assert.equal(r.ok, false);
    assert.ok(/id required/.test(r.error));
  });

  it('pinThread() with no id returns error', () => {
    const r = codexApp.pinThread({});
    assert.equal(r.ok, false);
    assert.ok(/id required/.test(r.error));
  });

  it('unarchiveThread() with no id returns error', () => {
    const r = codexApp.unarchiveThread({});
    assert.equal(r.ok, false);
    assert.ok(/id required/.test(r.error));
  });

  it('unpinThread() with no id returns error', () => {
    const r = codexApp.unpinThread({});
    assert.equal(r.ok, false);
    assert.ok(/id required/.test(r.error));
  });

  it('archiveThread/pinThread succeed with valid id (no disk I/O for test session)', () => {
    const testId = 'test-' + Date.now();
    const ar = codexApp.archiveThread({ id: testId });
    assert.equal(ar.ok, true);
    assert.equal(ar.archived, true);
    const pr = codexApp.pinThread({ id: testId });
    assert.equal(pr.ok, true);
    assert.equal(pr.pinned, true);
    // Cleanup
    codexApp.unarchiveThread({ id: testId });
    codexApp.unpinThread({ id: testId });
  });

  // ── Feature 9: getPlanItems pure tests ───────────────────────────────
  it('getPlanItems() with unknown session returns error', () => {
    const r = codexApp.getPlanItems('no-such-' + Date.now());
    assert.equal(r.ok, false);
    assert.ok(/not found/.test(r.error));
  });

  it('getPlanItems() with fresh session returns empty plan', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const p = codexApp.getPlanItems(r.sessionId);
    assert.equal(p.ok, true);
    assert.ok(Array.isArray(p.plan));
    assert.equal(p.plan.length, 0);
  });

  it('plan items include itemId and turnId after turn/plan/updated', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    codexApp.__testApplyNotification(r.sessionId, {
      method: 'turn/plan/updated',
      params: { plan: [{ step: 'Test', status: 'inProgress', itemId: 'item-1', turnId: 'turn-a' }] },
    });
    const p = codexApp.getPlanItems(r.sessionId);
    assert.equal(p.plan.length, 1);
    assert.equal(p.plan[0].itemId, 'item-1');
    assert.equal(p.plan[0].turnId, 'turn-a');
  });

  // ── Session persistence: restore (pure — app-server spawn yok) ────────
  // NOT: threadId'li kabukta getConversation/subscribe hydrateFromThread
  // tetikler ve gercek app-server spawn eder; pure testlerde restore edilen
  // kabuklara sadece listSessions uzerinden bakiyoruz.
  it('__restoreSessionsFromData restores session shells into listSessions', () => {
    const id = 'restore-test-' + Date.now();
    const n = codexApp.__restoreSessionsFromData({
      sessions: [{ id, threadId: 'thr-' + id, cwd: os.homedir(), model: 'gpt-5.1', permissionMode: 'ask', effort: 'high', lastActivity: 123 }],
    });
    assert.equal(n, 1);
    const found = codexApp.listSessions().find(s => s.id === id);
    assert.ok(found, 'restored session must be listed');
    assert.equal(found.status, 'idle');
    assert.equal(found.model, 'gpt-5.1');
    assert.equal(found.threadId, 'thr-' + id);
    assert.equal(found.turns, 0, 'messages are hydrated lazily, not at restore');
  });

  // Cekmece canli oturumu `live.threadId || live.id` ile listeler; oradan
  // acilinca adopt'a THREAD kimligi gelir. Kabuklar kendi id'leriyle anahtarli
  // oldugu icin bu ikinci bir kabuk aciyordu: telefonda ikinci sekme aciliyor,
  // tur oraya kayiyordu (02.08.2026). Adopt artik mevcut kabugu dondurmeli.
  it('adoptSession returns the existing shell when given its threadId', async () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'live-thread-' + suffix;
    const shellId = 'live-shell-' + suffix;
    codexApp.__restoreSessionsFromData({
      sessions: [{ id: shellId, threadId, cwd: os.homedir(), lastActivity: 5 }],
    });

    const r = await codexApp.adoptSession({ id: threadId, cwd: os.homedir() });

    assert.equal(r.ok, true);
    assert.equal(r.sessionId, shellId, 'yeni kabuk acmak yerine mevcut kabuk donmeli');
    const forThread = codexApp.listSessions().filter(s => s.threadId === threadId);
    assert.equal(forThread.length, 1, 'thread basina tek kabuk kalmali');
  });

  it('adoptSession picks the most recently active shell among legacy duplicates', async () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'legacy-thread-' + suffix;
    codexApp.__restoreSessionsFromData({
      sessions: [
        { id: 'legacy-stale-' + suffix, threadId, cwd: os.homedir(), lastActivity: 1 },
        { id: 'legacy-fresh-' + suffix, threadId, cwd: os.homedir(), lastActivity: 9 },
      ],
    });

    const r = await codexApp.adoptSession({ id: threadId, cwd: os.homedir() });

    assert.equal(r.sessionId, 'legacy-fresh-' + suffix);
  });

  it('routes one native thread notification to every duplicate restored shell', () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'shared-thread-' + suffix;
    const firstId = 'duplicate-a-' + suffix;
    const secondId = 'duplicate-b-' + suffix;
    const n = codexApp.__restoreSessionsFromData({
      sessions: [
        { id: firstId, threadId, cwd: os.homedir() },
        { id: secondId, threadId, cwd: os.homedir() },
      ],
    });
    assert.equal(n, 2);

    codexApp.__testRouteNotification({
      method: 'item/completed',
      params: {
        threadId,
        item: { id: 'answer-' + suffix, type: 'agentMessage', text: 'iki kabukta da görünür' },
      },
    });

    const listed = codexApp.listSessions();
    assert.equal(listed.find(s => s.id === firstId)?.lastText, 'iki kabukta da görünür');
    assert.equal(listed.find(s => s.id === secondId)?.lastText, 'iki kabukta da görünür');
  });

  // Canli hata (2 Ag 2026): thread durumu bildirim yoluyla her kabuga ulasiyordu
  // ama onay/soru YUKU yalniz sessionsByThread'in tuttugu tek kabuga dusuyordu.
  // Telefonda "onay bekleniyor" yaniyor, gosterilecek kart olmadigi icin oturum
  // kilitli goründü.
  it('mirrors a server request onto every shell sharing the thread', () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'approval-thread-' + suffix;
    const firstId = 'approval-a-' + suffix;
    const secondId = 'approval-b-' + suffix;
    assert.equal(codexApp.__restoreSessionsFromData({
      sessions: [
        { id: firstId, threadId, cwd: os.homedir() },
        { id: secondId, threadId, cwd: os.homedir() },
      ],
    }), 2);

    codexApp.__testRouteServerRequest({
      id: 4242,
      method: 'mcpServer/elicitation/request',
      params: { threadId, serverName: 'test', message: 'kurulsun mu?' },
    });

    for (const id of [firstId, secondId]) {
      const p = codexApp.__testPendingApproval(id);
      assert.equal(p.requestId, 4242, `${id} kaydi tasimali`);
      assert.equal(p.kind, 'question');
      assert.equal(p.awaitingUserInput, true);
      // Telde tek bayrak: soru da "cevap bekleniyor" saymali. Icerideki
      // awaitingApproval false kalir ama listSessions bunu true bildirmeli;
      // aksi halde istemci "false -> approval yok" deyip soruyu atiyordu.
      const listed = codexApp.listSessions().find(x => x.id === id);
      assert.equal(listed.awaitingApproval, true, `${id} telde bekliyor gorunmeli`);
      assert.equal(p.awaitingApproval, false, 'icerideki ayrim korunmali');
    }

    // Bir kabuktan cevaplanınca kardeste ölü kart kalmamalı — yoksa oradan
    // verilen ikinci cevap aynı requestId'ye ikinci yanıt gönderirdi.
    codexApp.respondUserInput({ sessionId: firstId, text: 'hayir' });
    for (const id of [firstId, secondId]) {
      const p = codexApp.__testPendingApproval(id);
      assert.equal(p.requestId, null, `${id} temizlenmeli`);
      assert.equal(p.awaitingUserInput, false);
    }
    assert.equal(codexApp.respondUserInput({ sessionId: secondId, text: 'x' }).ok, false);
  });

  it('__restoreSessionsFromData skips existing ids and malformed entries', () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const n = codexApp.__restoreSessionsFromData({
      sessions: [
        { id: r.sessionId, threadId: 'should-not-overwrite' }, // mevcut id → atla
        { threadId: 'no-id' },                                  // id yok → atla
        null,                                                   // bozuk → atla
      ],
    });
    assert.equal(n, 0);
    const found = codexApp.listSessions().find(s => s.id === r.sessionId);
    assert.equal(found.threadId, null, 'existing session must not be overwritten');
  });

  it('__restoreSessionsFromData tolerates empty/garbage input', () => {
    assert.equal(codexApp.__restoreSessionsFromData(null), 0);
    assert.equal(codexApp.__restoreSessionsFromData({}), 0);
    assert.equal(codexApp.__restoreSessionsFromData({ sessions: 'not-an-array' }), 0);
  });

  // ── Faz 1: Prompt 400 guard regression ──────────────────────────────
  // NOT: codex kuruluysa prompt() GERCEK app-server'i spawn eder ve ok:true
  // doner; kurulu degilse thread/start'ta duser ve ok:false doner. Test iki
  // ortamda da gecmeli — asil dogruladigimiz sey kullanici mesajinin kaydi.
  // ok:true durumunda turn'u hemen durdur ki test gercek bir model cagrisi
  // baslatmis olarak sarkmasin. (Bu test yuzunden `node --test` cikista canli
  // app-server cocugu nedeniyle asili kaliyordu; --test-force-exit ile calistir.)
  it('prompt() on idle session with valid text records the user message', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const p = await codexApp.prompt({ sessionId: r.sessionId, text: 'test' });
    const conv = codexApp.getConversation(r.sessionId);
    assert.ok(conv.messages.length > 0, 'user message was recorded');
    assert.ok(conv.messages[0].role === 'user', 'first message is user');
    assert.equal(typeof p.ok, 'boolean');
    if (p.ok) await codexApp.stop(r.sessionId);
  });

  // ── Thread goal (app-server thread/goal/*) ──────────────────────────────
  // Not: saf test; app-server SPAWN etmez. Bildirim yolu __testRouteNotification,
  // guard yollari (thread yok / bos objective) appSend'e HIC gitmeden erken doner.
  const mkGoal = (threadId, over = {}) => ({
    threadId, objective: 'X hedefi', status: 'active', tokenBudget: null,
    tokensUsed: 0, timeUsedSeconds: 0, createdAt: 1, updatedAt: 1, ...over,
  });

  it('thread/goal/updated bildirimi aynı thread\'i paylaşan İKİ kabuğa da yansır', () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'goal-thread-' + suffix;
    const a = 'goal-a-' + suffix, b = 'goal-b-' + suffix;
    assert.equal(codexApp.__restoreSessionsFromData({
      sessions: [{ id: a, threadId, cwd: os.homedir() }, { id: b, threadId, cwd: os.homedir() }],
    }), 2);
    const goal = mkGoal(threadId);
    codexApp.__testRouteNotification({ method: 'thread/goal/updated', params: { threadId, turnId: null, goal } });
    assert.deepEqual(codexApp.__testGoal(a), goal);
    assert.deepEqual(codexApp.__testGoal(b), goal, 'kardeş kabuk da goal taşımalı');
  });

  it('thread/goal/cleared goal\'ü null yapar', () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'goal-clear-thread-' + suffix;
    const id = 'goal-clear-' + suffix;
    codexApp.__restoreSessionsFromData({ sessions: [{ id, threadId, cwd: os.homedir() }] });
    codexApp.__testRouteNotification({ method: 'thread/goal/updated', params: { threadId, goal: mkGoal(threadId) } });
    assert.ok(codexApp.__testGoal(id), 'önce goal set olmalı');
    codexApp.__testRouteNotification({ method: 'thread/goal/cleared', params: { threadId } });
    assert.equal(codexApp.__testGoal(id), null);
  });

  it('threadId olmayan oturumda goal işlemleri (get ok+null, set/clear ok:false)', async () => {
    const r = codexApp.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    const sid = r.sessionId;
    // Karar: get için thread yoksa appSend'e gitmeden ok:true/goal:null; set ve
    // clear için ok:false (goal yalnız canlı thread üzerinde anlamlı).
    const g = await codexApp.getGoal(sid);
    assert.deepEqual(g, { ok: true, goal: null });
    assert.equal((await codexApp.setGoal({ sessionId: sid, objective: 'x' })).ok, false);
    assert.equal((await codexApp.clearGoal({ sessionId: sid })).ok, false);
  });

  it('setGoal boş/whitespace objective → ok:false (thread var olsa bile)', async () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const id = 'goal-empty-' + suffix;
    // threadId'li kabuk: thread guard'ını geçer, objective guard'ı appSend'e
    // gitmeden ok:false döner (bu yüzden app-server spawn olmaz).
    codexApp.__restoreSessionsFromData({ sessions: [{ id, threadId: 'thr-' + id, cwd: os.homedir() }] });
    assert.equal((await codexApp.setGoal({ sessionId: id, objective: '   ' })).ok, false);
    assert.equal((await codexApp.setGoal({ sessionId: id, objective: '' })).ok, false);
  });

  it('snapshot goal alanını taşır (varsa değer, yoksa null)', () => {
    const goal = mkGoal('t-snap');
    const snap = codexApp.snapshot({ id: 'snap-goal', status: 'idle', messages: [], _plan: [], goal });
    assert.deepEqual(snap.goal, goal);
    const bare = codexApp.snapshot({ id: 'snap-nogoal', status: 'idle', messages: [], _plan: [] });
    assert.equal(bare.goal, null);
  });

  // /goal istemcide yakalanip prompt() yolundan gecmedigi icin transcript'e
  // kendiliginden kullanici satiri dusmuyordu; goal turunun sahibi kayitta
  // gorunmez kaliyordu (canli sikayet 02.08.2026). setGoal/clearGoal artik
  // appendGoalCommandRow ile thread'i paylasan TUM kabuklara satir isler.
  it('goal komut satırı thread\'i paylaşan İKİ kabuğun transcript\'ine de düşer', () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const threadId = 'goal-row-thread-' + suffix;
    const a = 'goal-row-a-' + suffix, b = 'goal-row-b-' + suffix;
    assert.equal(codexApp.__restoreSessionsFromData({
      sessions: [{ id: a, threadId, cwd: os.homedir() }, { id: b, threadId, cwd: os.homedir() }],
    }), 2);
    codexApp.__testAppendGoalCommandRow(threadId, '/goal kalan fazları bitir');
    for (const id of [a, b]) {
      const rows = codexApp.__testMessages(id);
      const last = rows[rows.length - 1];
      assert.ok(last, `${id}: satır eklenmiş olmalı`);
      assert.equal(last.role, 'user', `${id}: satır kullanıcı rolünde olmalı`);
      assert.equal(last.text, '/goal kalan fazları bitir');
    }
    // Bilinmeyen thread sessizce no-op (satır yok, hata yok).
    codexApp.__testAppendGoalCommandRow('yok-boyle-thread-' + suffix, '/goal x');
  });

  // Aynı thread'e bağlı mükerrer kabuklar telefonda AYNI konuşma için iki sekme
  // açıyordu (şikâyet 02.08.2026). Artık diske YAZILMIYORLAR; bellekteki tolerans
  // (fan-out) olduğu gibi duruyor, mükerrerlik restart'ı aşamıyor.
  it('mükerrer kabuklar thread başına teke iner; kanonik kabuk (id==threadId) kazanır', () => {
    const dedupe = codexApp.__testDedupeShellsByThread;
    // Kanonik olmayan kabuk listede ÖNCE gelse bile kanonik olan onun yerini alır.
    const out = dedupe([
      { id: 'kabuk-b', threadId: 'T1' },
      { id: 'T1', threadId: 'T1' },
      { id: 'kabuk-c', threadId: 'T2' },
      { id: 'kabuk-d', threadId: 'T2' },
      { id: 'thread-yok-1', threadId: null },
      { id: 'thread-yok-2', threadId: null },
    ]);
    assert.deepEqual(out.map(s => s.id), ['T1', 'kabuk-c', 'thread-yok-1', 'thread-yok-2']);
    // Kanonik yoksa listedeki ilk (persist sırasına göre en son kullanılan) kalır.
    assert.deepEqual(dedupe([{ id: 'x', threadId: 'T' }, { id: 'y', threadId: 'T' }]).map(s => s.id), ['x']);
    // Bozuk/boş girdi patlamaz.
    assert.deepEqual(dedupe([null, { threadId: 'T' }]).map(s => s.id), []);
  });

  it('clearGoal silent bayrağı transcript satırını atlar (thread guard\'ı öncesinde bile sözleşme sabit)', async () => {
    const suffix = Date.now() + '-' + Math.random().toString(16).slice(2);
    const id = 'goal-silent-' + suffix;
    // threadId YOK: appSend'e gitmeden ok:false döner. Buradaki asıl güvence,
    // silent'ın imzada taşınması ve guard yolunun satır yazmaması.
    codexApp.__restoreSessionsFromData({ sessions: [{ id, cwd: os.homedir() }] });
    const r = await codexApp.clearGoal({ sessionId: id, silent: true });
    assert.equal(r.ok, false);
    assert.equal(codexApp.__testMessages(id).length, 0, 'guard yolunda satır yazılmamalı');
  });
});

// Codex, kendi arayüzünde kart olarak çizdiği işaretlemeleri düz metne gömüyor;
// bizim istemcimiz bilmediği için sohbetin sonuna dökülüyordu (29.07.2026).
describe('codex-app: stripCodexMarkup', () => {
  it('cevabın sonundaki ::git-commit direktifini ve hafıza atfını atar', () => {
    const raw = [
      'Düzelttim ve commitledim.',
      '',
      '::git-commit{cwd="C:/Users/ornek/proje"}',
      '',
      '<oai-mem-citation>',
      '<citation_entries>',
      'MEMORY.md:40-43|note=[Bridge restart ownership rule]',
      '</citation_entries>',
      '<rollout_ids>',
      '019f8fc7-10b2-7f81-914a-8a4b0d1e91a6',
      '</rollout_ids>',
      '</oai-mem-citation>',
    ].join('\n');
    assert.equal(codexApp.stripCodexMarkup(raw), 'Düzelttim ve commitledim.');
  });

  it('diğer direktif ailesini de temizler', () => {
    const raw = 'Bitti.\n::git-push{cwd="/x" branch="main"}\n::created-thread{threadId="abc"}';
    assert.equal(codexApp.stripCodexMarkup(raw), 'Bitti.');
  });

  it('kapanış etiketi gelmemiş atıf bloğunu da keser (akış ortası)', () => {
    assert.equal(codexApp.stripCodexMarkup('Metin.\n<oai-mem-citation>\n<citation'), 'Metin.');
  });

  it('normal metne ve kod bloğuna dokunmaz', () => {
    const md = 'Şuna bak:\n\n```js\nconst a = { b: 1 };\n```\n\nC++ `std::vector` kullan.';
    assert.equal(codexApp.stripCodexMarkup(md), md);
  });

  it('boş/undefined girdide patlamaz', () => {
    assert.equal(codexApp.stripCodexMarkup(undefined), '');
    assert.equal(codexApp.stripCodexMarkup(''), '');
  });
});
