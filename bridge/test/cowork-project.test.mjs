import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const COWORK_TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-project-root-'));
process.env.AGENTBRIDGE_COWORK_ROOT = COWORK_TMP;

const cowork = await import('../cowork.mjs');
const claudeApp = await import('../claude-app.mjs');
const codexApp = await import('../codex-app.mjs');
const opencodeApp = await import('../opencode2-app.mjs');
const opencode2App = await import('../opencode2-app.mjs');

after(() => {
  // Codex resume testleri kalıcı app-server child'ını başlatabilir. Kapatılmazsa
  // tüm assertion'lar geçse bile açık stdio pipe'ları `node --test`i ayakta tutar.
  codexApp.killAllSessions();
  // OpenCode sağlayıcılı cowork oturumu da GERÇEK bir `opencode serve`
  // başlatıyor ve aynı tuzağa düşüyordu: 3 Ağu 2026'da ölçüldü — 40 test 9,6
  // saniyede geçti, süreç 330 saniye daha asılı kaldı, serve elle öldürülünce
  // anında çıktı. `npm test`in "zaman aşımı" dediği şey buydu.
  opencodeApp.killAllSessions();
  opencode2App.killAllSessions();
  try { fs.rmSync(COWORK_TMP, { recursive: true, force: true }); } catch {}
});

describe('cowork project metadata', () => {
  it('creates .cowork/project.json under a workspace', () => {
    const r = cowork.createProject({ name: 'project-a' });
    assert.equal(r.ok, true);

    const file = path.join(r.path, '.cowork', 'project.json');
    assert.equal(fs.existsSync(file), true);
    const meta = JSON.parse(fs.readFileSync(file, 'utf8'));
    assert.equal(meta.name, 'project-a');
    assert.equal(meta.path, r.path);
    assert.equal(meta.activeProvider, 'claude-app');
  });

  it('starts and resumes the latest Claude App native session for a project', async () => {
    const p = cowork.createProject({ name: 'project-claude' });
    const first = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });
    assert.equal(first.ok, true);
    assert.equal(first.provider, 'claude-app');
    assert.ok(first.sessionId);

    const again = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });
    assert.equal(again.ok, true);
    assert.equal(again.sessionId, first.sessionId);

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    assert.ok(sessions.length >= 1);
    assert.equal(sessions[0].provider, 'claude-app');
    assert.equal(sessions[0].sessionId, first.sessionId);
  });

  it('forceNew opens a fresh session in the same workspace instead of adopting the latest', async () => {
    // Drawer "Başlat" davranışı: aktif oturum varken bile yeni oturum açılmalı.
    // forceNew olmadan startSession en son oturumu adopt ediyordu → "yeni oturum açılmıyor" hatası.
    const p = cowork.createProject({ name: 'project-forcenew' });
    const first = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });
    assert.equal(first.ok, true);
    assert.ok(first.sessionId);

    const fresh = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet', forceNew: true });
    assert.equal(fresh.ok, true);
    assert.notEqual(fresh.sessionId, first.sessionId, 'forceNew should not reuse the latest session');

    // Her iki oturum da kayıtlı kalmalı (drawer ikisini de listeler).
    const ids = new Set(cowork.listProjectSessions({ projectPath: p.path }).sessions.map(s => s.sessionId));
    assert.ok(ids.has(first.sessionId), 'eski oturum kaydı korunmalı');
    assert.ok(ids.has(fresh.sessionId), 'yeni oturum kaydı eklenmeli');
  });

  it('does not duplicate a session record across resumes (update-in-place)', async () => {
    const p = cowork.createProject({ name: 'project-dedup' });
    const first = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });
    await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });
    await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });

    const fileDir = path.join(p.path, '.cowork', 'providers', 'claude-app', 'sessions');
    const files = fs.readdirSync(fileDir).filter(n => n.endsWith('.json'));
    assert.equal(files.length, 1, 'resume should update-in-place, not write new files');

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].sessionId, first.sessionId);
  });

  it('resumes a specific Claude App session when sessionId is passed', async () => {
    const p = cowork.createProject({ name: 'project-resume-id' });
    const first = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', model: 'sonnet' });
    // İkinci bir farklı oturum aç. İki farklı sessionId olduğundan emin olmak için
    // first.sessionId dışında bir kayıt hedefliyoruz.
    await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5' });

    const resumed = await cowork.startSession({
      projectPath: p.path,
      provider: 'claude-app',
      model: 'sonnet',
      sessionId: first.sessionId,
    });
    assert.equal(resumed.ok, true);
    assert.equal(resumed.provider, 'claude-app');
    assert.equal(resumed.sessionId, first.sessionId);
  });

  it('returns an error when a nonexistent sessionId is requested', async () => {
    const p = cowork.createProject({ name: 'project-resume-miss' });
    await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });

    const r = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', sessionId: 'does-not-exist' });
    assert.equal(r.ok, false);
    assert.match(r.error, /bulunamadi/);
  });

  it('does not turn stale metadata into a new session when resume is requested', async () => {
    const p = cowork.createProject({ name: 'project-stale-resume' });
    const dir = path.join(p.path, '.cowork', 'providers', 'claude-app', 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'stale.json'), JSON.stringify({
      provider: 'claude-app',
      sessionId: 'stale-bridge-id',
      cwd: p.path,
      model: 'sonnet',
      createdAt: '2024-01-01T00:00:00Z',
      lastUsedAt: '2024-01-01T00:00:00Z',
    }));

    const before = claudeApp.listSessions().length;
    const resumed = await cowork.startSession({
      projectPath: p.path,
      provider: 'claude-app',
      sessionId: 'stale-bridge-id',
    });

    assert.equal(resumed.ok, false);
    assert.match(resumed.error, /bulunamadi/);
    assert.equal(claudeApp.listSessions().length, before, 'failed resume must not create a replacement session');
  });

  it('omits metadata ghosts/restored shells and gives a fresh session a readable name', async () => {
    const p = cowork.createProject({ name: 'project-resolved-list' });
    const live = await cowork.startSession({ projectPath: p.path, provider: 'claude-app', forceNew: true });
    const dir = path.join(p.path, '.cowork', 'providers', 'claude-app', 'sessions');
    fs.writeFileSync(path.join(dir, 'ghost.json'), JSON.stringify({
      provider: 'claude-app',
      sessionId: 'ghost-only-metadata',
      cwd: p.path,
      model: 'sonnet',
      createdAt: '2024-01-01T00:00:00Z',
      lastUsedAt: '2024-01-01T00:00:00Z',
    }));
    const restoredId = 'empty-restored-shell';
    claudeApp.__restoreSessionsFromData({ sessions: [{
      id: restoredId,
      cwd: p.path,
      model: 'sonnet',
      cowork: true,
    }] });

    const resolved = await cowork.listResolvedProjectSessions({ projectPath: p.path });
    assert.equal(resolved.ok, true);
    assert.ok(resolved.sessions.some(s => s.sessionId === live.sessionId));
    assert.equal(resolved.sessions.some(s => s.sessionId === 'ghost-only-metadata'), false);
    assert.equal(resolved.sessions.some(s => s.sessionId === restoredId), false);
    assert.equal(resolved.sessions.find(s => s.sessionId === live.sessionId)?.title, 'Yeni Claude oturumu');
    claudeApp.deleteDiskSession({ id: restoredId });
  });

  it('starts Codex App sessions in the same project metadata namespace', async () => {
    const p = cowork.createProject({ name: 'project-codex' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5' });
    assert.equal(r.ok, true);
    assert.equal(r.provider, 'codex-app');
    assert.equal(r.apiBackend, 'codex-app');
    assert.ok(r.sessionId);

    const fileDir = path.join(p.path, '.cowork', 'providers', 'codex-app', 'sessions');
    const files = fs.readdirSync(fileDir).filter(n => n.endsWith('.json'));
    assert.equal(files.length, 1);
    const rec = JSON.parse(fs.readFileSync(path.join(fileDir, files[0]), 'utf8'));
    assert.equal(rec.provider, 'codex-app');
    assert.equal(rec.cwd, p.path);
    assert.equal(rec.model, 'gpt-5.5');
    assert.equal(rec.permissionMode, 'ask', 'codex cowork default should be approval-required (madde 6)');
  });

  it('opts into yolo for Codex only when explicitly requested (madde 6)', async () => {
    const p = cowork.createProject({ name: 'project-codex-yolo' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5', permissionMode: 'yolo' });
    assert.equal(r.ok, true);

    const fileDir = path.join(p.path, '.cowork', 'providers', 'codex-app', 'sessions');
    const files = fs.readdirSync(fileDir).filter(n => n.endsWith('.json'));
    const rec = JSON.parse(fs.readFileSync(path.join(fileDir, files[0]), 'utf8'));
    assert.equal(rec.permissionMode, 'yolo', 'explicit yolo opt-in should be honored');
    assert.equal(rec.permissionModeExplicit, true, 'explicit yolo opt-in should be marked');
  });

  it('preserves explicit yolo on resume but downgrades legacy implicit yolo to ask', async () => {
    const explicit = cowork.createProject({ name: 'project-codex-explicit-yolo' });
    const first = await cowork.startSession({ projectPath: explicit.path, provider: 'codex-app', model: 'gpt-5.5', permissionMode: 'yolo' });
    assert.equal(first.ok, true);
    const resumed = await cowork.startSession({ projectPath: explicit.path, provider: 'codex-app', model: 'gpt-5.5' });
    assert.equal(resumed.ok, true);
    const explicitRec = cowork.listProjectSessions({ projectPath: explicit.path }).sessions.find(s => s.provider === 'codex-app');
    assert.equal(explicitRec.permissionMode, 'yolo');
    assert.equal(explicitRec.permissionModeExplicit, true);

    const legacy = cowork.createProject({ name: 'project-codex-legacy-yolo' });
    const legacyFirst = await cowork.startSession({ projectPath: legacy.path, provider: 'codex-app', model: 'gpt-5.5', permissionMode: 'yolo' });
    assert.equal(legacyFirst.ok, true);
    const dir = path.join(legacy.path, '.cowork', 'providers', 'codex-app', 'sessions');
    const file = path.join(dir, fs.readdirSync(dir).find(n => n.endsWith('.json')));
    const oldRec = JSON.parse(fs.readFileSync(file, 'utf8'));
    delete oldRec.permissionModeExplicit;
    fs.writeFileSync(file, JSON.stringify(oldRec));
    const migrated = await cowork.startSession({ projectPath: legacy.path, provider: 'codex-app', model: 'gpt-5.5' });
    assert.equal(migrated.ok, true);
    assert.equal(migrated.record.permissionMode, 'ask');
    assert.equal(migrated.record.permissionModeExplicit, false);
  });

  it('preserves an explicit non-yolo permissionMode on resume (madde 6)', async () => {
    const p = cowork.createProject({ name: 'project-codex-onfailure' });
    const first = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5', permissionMode: 'on-failure' });
    assert.equal(first.ok, true);
    const rec1 = cowork.listProjectSessions({ projectPath: p.path }).sessions.find(s => s.sessionId === first.sessionId);
    assert.equal(rec1.permissionMode, 'on-failure');

    // permissionMode vermeden resume → kullanıcının önceki tercihi (on-failure) korunmalı,
    // asla 'ask'a ya da 'yolo'ya düşmemeli.
    const again = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5', sessionId: first.sessionId });
    assert.equal(again.ok, true);
    const rec2 = cowork.listProjectSessions({ projectPath: p.path }).sessions.find(s => s.sessionId === first.sessionId);
    assert.equal(rec2.permissionMode, 'on-failure');
  });

  it('updates Codex App metadata with native threadId after first prompt starts a thread', async () => {
    const p = cowork.createProject({ name: 'project-codex-thread' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5' });
    const update = cowork.updateCodexSessionThread({
      sessionId: r.sessionId,
      threadId: 'native-thread-123',
      cwd: p.path,
      model: 'gpt-5.5',
      permissionMode: 'yolo',
    });
    assert.equal(update.ok, true);
    assert.equal(update.updated, 1);

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    const latest = sessions.find(s => s.provider === 'codex-app' && s.sessionId === r.sessionId);
    assert.ok(latest);
    assert.equal(latest.threadId, 'native-thread-123');
    assert.equal(latest.permissionMode, 'yolo');
  });

  it('updates only the intended Codex record when another record shares the target threadId (madde 8)', () => {
    const p = cowork.createProject({ name: 'project-codex-thread-match' });
    const dir = path.join(p.path, '.cowork', 'providers', 'codex-app', 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    const base = {
      provider: 'codex-app',
      cwd: p.path,
      model: 'gpt-5.5',
      permissionMode: 'ask',
      createdAt: '2024-01-01T00:00:00Z',
    };
    fs.writeFileSync(path.join(dir, 'older-target.json'), JSON.stringify({
      ...base,
      sessionId: 'target-session',
      threadId: 'old-thread',
      lastUsedAt: '2024-01-01T00:00:00Z',
    }));
    fs.writeFileSync(path.join(dir, 'newer-unrelated.json'), JSON.stringify({
      ...base,
      sessionId: 'unrelated-session',
      threadId: 'native-thread-shared',
      lastUsedAt: '2024-02-01T00:00:00Z',
    }));

    const update = cowork.updateCodexSessionThread({
      sessionId: 'target-session',
      threadId: 'native-thread-shared',
      cwd: p.path,
      model: 'gpt-5.5',
      permissionMode: 'ask',
    });
    assert.equal(update.ok, true);
    assert.equal(update.updated, 1);
    assert.deepEqual(update.touched, ['older-target.json']);

    const target = JSON.parse(fs.readFileSync(path.join(dir, 'older-target.json'), 'utf8'));
    const unrelated = JSON.parse(fs.readFileSync(path.join(dir, 'newer-unrelated.json'), 'utf8'));
    assert.equal(target.threadId, 'native-thread-shared');
    assert.equal(unrelated.sessionId, 'unrelated-session');
    assert.equal(unrelated.threadId, 'native-thread-shared');
    assert.equal(unrelated.lastUsedAt, '2024-02-01T00:00:00Z');
  });

  it('collapses Codex records by native threadId after bridge-local session id changes', () => {
    const p = cowork.createProject({ name: 'project-codex-thread-dedup' });
    const dir = path.join(p.path, '.cowork', 'providers', 'codex-app', 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    const base = {
      provider: 'codex-app',
      threadId: 'native-thread-same',
      cwd: p.path,
      model: 'gpt-5.5',
      permissionMode: 'ask',
      createdAt: '2024-01-01T00:00:00Z',
    };
    fs.writeFileSync(path.join(dir, 'old-bridge-id.json'), JSON.stringify({
      ...base,
      sessionId: 'old-bridge-id',
      lastUsedAt: '2024-01-01T00:00:00Z',
    }));
    fs.writeFileSync(path.join(dir, 'native-thread-id.json'), JSON.stringify({
      ...base,
      sessionId: 'native-thread-same',
      lastUsedAt: '2024-02-01T00:00:00Z',
    }));

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions
      .filter(s => s.provider === 'codex-app' && s.threadId === 'native-thread-same');
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].sessionId, 'native-thread-same');
    assert.equal(fs.readdirSync(dir).filter(n => n.endsWith('.json')).length, 1);
  });

  it('starts OpenCode App sessions with ask default in the same metadata namespace', async () => {
    const p = cowork.createProject({ name: 'project-opencode' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'opencode2-app', model: 'deepseek/deepseek-flash' });
    assert.equal(r.ok, true);
    assert.equal(r.provider, 'opencode2-app');
    assert.equal(r.apiBackend, 'opencode2-app');
    assert.ok(r.sessionId);

    const fileDir = path.join(p.path, '.cowork', 'providers', 'opencode2-app', 'sessions');
    const files = fs.readdirSync(fileDir).filter(n => n.endsWith('.json'));
    assert.equal(files.length, 1);
    const rec = JSON.parse(fs.readFileSync(path.join(fileDir, files[0]), 'utf8'));
    assert.equal(rec.provider, 'opencode2-app');
    assert.equal(rec.cwd, p.path);
    assert.equal(rec.model, 'deepseek/deepseek-flash');
    assert.equal(rec.permissionMode, 'ask', 'opencode cowork default should be approval-required (madde 6)');
  });

  it('opts into yolo for OpenCode only when explicitly requested (madde 6)', async () => {
    const p = cowork.createProject({ name: 'project-opencode-yolo' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'opencode2-app', model: 'deepseek/deepseek-flash', permissionMode: 'yolo' });
    assert.equal(r.ok, true);

    const fileDir = path.join(p.path, '.cowork', 'providers', 'opencode2-app', 'sessions');
    const files = fs.readdirSync(fileDir).filter(n => n.endsWith('.json'));
    const rec = JSON.parse(fs.readFileSync(path.join(fileDir, files[0]), 'utf8'));
    assert.equal(rec.permissionMode, 'yolo', 'explicit yolo opt-in should be honored');
    assert.equal(rec.permissionModeExplicit, true, 'explicit yolo opt-in should be marked');
  });

  it('updates OpenCode metadata with the native ses_ id after first prompt (onSessionResolved)', async () => {
    const p = cowork.createProject({ name: 'project-opencode-sid' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'opencode2-app', model: 'deepseek/deepseek-flash' });
    const update = cowork.updateOpencode2SessionSid({
      sessionId: r.sessionId,
      threadId: 'ses_native_abc123',
      cwd: p.path,
      model: 'deepseek/deepseek-flash',
      permissionMode: 'ask',
    });
    assert.equal(update.ok, true);
    assert.equal(update.updated, 1);

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    const latest = sessions.find(s => s.provider === 'opencode2-app' && s.sessionId === r.sessionId);
    assert.ok(latest);
    assert.equal(latest.threadId, 'ses_native_abc123');
  });

  it('starts OpenCode 2 in Cowork and records its native session id', async () => {
    const p = cowork.createProject({ name: 'project-opencode2' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'opencode2-app', model: 'deepseek/deepseek-flash' });
    assert.equal(r.ok, true, r.error);
    assert.equal(r.apiBackend, 'opencode2-app');
    assert.ok(r.sessionId);
    assert.equal(r.record.permissionMode, 'ask');
    const updated = cowork.updateOpencode2SessionSid({
      sessionId: r.sessionId,
      threadId: 'ses_v2_native_abc123',
      cwd: p.path,
      model: 'deepseek/deepseek-flash',
    });
    assert.equal(updated.updated, 1);
    const rec = cowork.listProjectSessions({ projectPath: p.path }).sessions.find(s => s.provider === 'opencode2-app');
    assert.equal(rec.threadId, 'ses_v2_native_abc123');
    assert.equal(rec.permissionMode, 'ask');
  });

  it('starts OMP sessions with ask default in the same metadata namespace', async () => {
    const p = cowork.createProject({ name: 'project-omp' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'omp', model: 'deepseek/deepseek-flash' });
    assert.equal(r.ok, true);
    assert.equal(r.provider, 'omp');
    assert.equal(r.apiBackend, 'omp');
    assert.ok(r.sessionId);

    const fileDir = path.join(p.path, '.cowork', 'providers', 'omp', 'sessions');
    const files = fs.readdirSync(fileDir).filter(n => n.endsWith('.json'));
    assert.equal(files.length, 1);
    const rec = JSON.parse(fs.readFileSync(path.join(fileDir, files[0]), 'utf8'));
    assert.equal(rec.provider, 'omp');
    assert.equal(rec.cwd, p.path);
    assert.equal(rec.model, 'deepseek/deepseek-flash');
    assert.equal(rec.permissionMode, 'ask', 'omp cowork default should be approval-required (madde 6)');
  });

  it('updates OMP metadata with the native session id after first prompt (onSessionResolved)', async () => {
    const p = cowork.createProject({ name: 'project-omp-sid' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'omp', model: 'deepseek/deepseek-flash' });
    // OMP canlı kabuğa her açılışta yeni bridge-uuid veriyor; kalıcı kimlik
    // OMP'nin kendi sessionId'si. Kayıt onsuz resume edilemez.
    const update = cowork.updateOmpSessionId({
      sessionId: r.sessionId,
      threadId: '01930000-0000-7000-8000-000000000abc',
      cwd: p.path,
      model: 'deepseek/deepseek-flash',
      permissionMode: 'ask',
    });
    assert.equal(update.ok, true);
    assert.equal(update.updated, 1);

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    const latest = sessions.find(s => s.provider === 'omp' && s.sessionId === r.sessionId);
    assert.ok(latest);
    assert.equal(latest.threadId, '01930000-0000-7000-8000-000000000abc');
  });

  it('collapses OMP records by native session id after bridge-local session id changes', () => {
    const p = cowork.createProject({ name: 'project-omp-dedup' });
    const dir = path.join(p.path, '.cowork', 'providers', 'omp', 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    const base = {
      provider: 'omp',
      threadId: 'omp-native-same',
      cwd: p.path,
      model: 'deepseek/deepseek-flash',
      permissionMode: 'ask',
      createdAt: '2024-01-01T00:00:00Z',
    };
    fs.writeFileSync(path.join(dir, 'a.json'), JSON.stringify({ ...base, sessionId: 'bridge-uuid-1', lastUsedAt: '2024-01-01T00:00:00Z' }));
    fs.writeFileSync(path.join(dir, 'b.json'), JSON.stringify({ ...base, sessionId: 'omp-native-same', lastUsedAt: '2024-01-02T00:00:00Z' }));

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions
      .filter(s => s.provider === 'omp' && s.threadId === 'omp-native-same');
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].sessionId, 'omp-native-same');
  });

  it('collapses OpenCode records by native ses_ id after bridge-local session id changes', () => {
    const p = cowork.createProject({ name: 'project-opencode-dedup' });
    const dir = path.join(p.path, '.cowork', 'providers', 'opencode2-app', 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    const base = {
      provider: 'opencode2-app',
      threadId: 'ses_native_same',
      cwd: p.path,
      model: 'deepseek/deepseek-flash',
      permissionMode: 'ask',
      createdAt: '2024-01-01T00:00:00Z',
    };
    fs.writeFileSync(path.join(dir, 'old-bridge-id.json'), JSON.stringify({
      ...base,
      sessionId: 'old-bridge-uuid',
      lastUsedAt: '2024-01-01T00:00:00Z',
    }));
    fs.writeFileSync(path.join(dir, 'native-sid.json'), JSON.stringify({
      ...base,
      sessionId: 'ses_native_same',
      lastUsedAt: '2024-02-01T00:00:00Z',
    }));

    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions
      .filter(s => s.provider === 'opencode2-app' && s.threadId === 'ses_native_same');
    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].sessionId, 'ses_native_same');
    assert.equal(fs.readdirSync(dir).filter(n => n.endsWith('.json')).length, 1);
  });

  it('imports PC files and folders into the workspace (recursive, collision-safe)', () => {
    const p = cowork.createProject({ name: 'project-import' });
    const srcRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-import-src-'));
    try {
      fs.writeFileSync(path.join(srcRoot, 'dilekce.docx'), 'doc');
      fs.mkdirSync(path.join(srcRoot, 'deliller', 'alt'), { recursive: true });
      fs.writeFileSync(path.join(srcRoot, 'deliller', 'a.pdf'), 'a');
      fs.writeFileSync(path.join(srcRoot, 'deliller', 'alt', 'b.pdf'), 'b');

      const r = cowork.importIntoProject({
        projectPath: p.path,
        sources: [path.join(srcRoot, 'dilekce.docx'), path.join(srcRoot, 'deliller')],
      });
      assert.equal(r.ok, true);
      assert.equal(r.errors.length, 0);
      assert.equal(r.copied.length, 2);
      assert.equal(fs.readFileSync(path.join(p.path, 'dilekce.docx'), 'utf8'), 'doc');
      assert.equal(fs.readFileSync(path.join(p.path, 'deliller', 'alt', 'b.pdf'), 'utf8'), 'b');

      // Aynı dosya ikinci kez → "dilekce (2).docx" olarak kopyalanmalı, üzerine yazmamalı.
      const again = cowork.importIntoProject({ projectPath: p.path, sources: [path.join(srcRoot, 'dilekce.docx')] });
      assert.equal(again.ok, true);
      assert.equal(again.copied[0].name, 'dilekce (2).docx');
      assert.equal(fs.existsSync(path.join(p.path, 'dilekce (2).docx')), true);
    } finally {
      fs.rmSync(srcRoot, { recursive: true, force: true });
    }
  });

  it('rejects importing the workspace into itself and reports missing sources', () => {
    const p = cowork.createProject({ name: 'project-import-guard' });
    const r = cowork.importIntoProject({
      projectPath: p.path,
      sources: [p.path, path.join(os.tmpdir(), 'yok-boyle-dosya-' + process.pid)],
    });
    assert.equal(r.ok, true);
    assert.equal(r.copied.length, 0);
    assert.equal(r.errors.length, 2);
    assert.match(r.errors[0].error, /kendi icine/);
  });

  it('keeps outputs scanning project scoped and provider independent', async () => {
    const p = cowork.createProject({ name: 'project-outputs' });
    const outputs = path.join(p.path, 'outputs');
    fs.mkdirSync(outputs, { recursive: true });
    fs.writeFileSync(path.join(outputs, 'report.txt'), 'done');

    await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    await cowork.startSession({ projectPath: p.path, provider: 'codex-app' });

    const project = cowork.getProject({ projectPath: p.path });
    assert.equal(project.ok, true);
    assert.equal(project.outputs.length, 1);
    assert.equal(project.outputs[0].name, 'report.txt');
  });

  it('collapses legacy duplicate records into one on read (migration)', () => {
    const p = cowork.createProject({ name: 'project-legacy-dup' });
    const dir = path.join(p.path, '.cowork', 'providers', 'claude-app', 'sessions');
    fs.mkdirSync(dir, { recursive: true });
    // Eski davranışı simüle et: aynı sessionId için üç farklı timestamp'li dosya.
    const sid = 'legacy-session-42';
    const rec = {
      provider: 'claude-app', sessionId: sid, threadId: '', cwd: p.path,
      model: 'sonnet', permissionMode: '',
    };
    fs.writeFileSync(path.join(dir, '2024-01-01T00-00-00-000Z-' + sid + '.json'),
      JSON.stringify({ ...rec, createdAt: '2024-01-01T00:00:00Z', lastUsedAt: '2024-01-01T00:00:00Z' }));
    fs.writeFileSync(path.join(dir, '2024-02-01T00-00-00-000Z-' + sid + '.json'),
      JSON.stringify({ ...rec, createdAt: '2024-01-01T00:00:00Z', lastUsedAt: '2024-02-01T00:00:00Z' }));
    fs.writeFileSync(path.join(dir, '2024-03-01T00-00-00-000Z-' + sid + '.json'),
      JSON.stringify({ ...rec, createdAt: '2024-01-01T00:00:00Z', lastUsedAt: '2024-03-01T00:00:00Z' }));

    // İlk okuma duplicate'leri collapse etmeli ve eski dosyaları silmeli.
    const sessions = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    const matching = sessions.filter(s => s.sessionId === sid);
    assert.equal(matching.length, 1, 'duplicate records should collapse to one');
    assert.equal(matching[0].lastUsedAt, '2024-03-01T00:00:00Z', 'should keep the newest');

    const remaining = fs.readdirSync(dir).filter(n => n.endsWith('.json'));
    assert.equal(remaining.length, 1, 'stale duplicate files should be deleted');
  });

  it('uses the provider module defaultModel for a new cowork session (madde 7)', async () => {
    const p = cowork.createProject({ name: 'project-default-model' });
    // Model belirtmeden başlat → provider modülünün defaultModel() kullanılmalı.
    const r = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    assert.equal(r.ok, true);
    assert.equal(r.model, claudeApp.defaultModel(), 'cowork should use the provider default, not a hardcoded string');
    assert.equal(r.model, 'claude-opus-5-5');

    const c = await cowork.startSession({ projectPath: p.path, provider: 'codex-app' });
    assert.equal(c.ok, true);
    assert.equal(c.model, codexApp.defaultModel());
    // Sabit ad yok: varsayilan Codex config'inden geliyor, degismez olan sey
    // cowork'un kendi string'ini degil modul varsayilanini kullanmasi.
    assert.ok(codexApp.listSelectableModels().some(m => m.id === c.model));
  });

  it('sources the cowork default from the provider module, not a hardcoded string (madde 7)', async () => {
    // cowork.mjs içinde 'claude-opus-4-8' / 'gpt-5.5' string'i gömülü olmamalı;
    // default provider modülünün defaultModel() fonksiyonundan gelmeli. Eğer provider
    // default'u değişirse cowork otomatik takip eder. Modül export'ları read-only
    // olduğundan doğrudan monkeypatch yapılamaz; bunun yerine cowork çıktısının
    // defaultModel() değeriyle aynı olduğunu (tek kaynak) doğrularız.
    const p = cowork.createProject({ name: 'project-default-source' });
    const r = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    assert.equal(r.model, claudeApp.defaultModel());

    // defaultModel değerini listeden bul ve geçerli bir model id olduğundan emin ol.
    const ids = claudeApp.CLAUDE_APP_MODELS.map(m => m.id);
    assert.ok(ids.includes(r.model), 'default should be a real model id in the catalog');
  });

  it('scaffolds the dava-dosyasi template on createProject', () => {
    const r = cowork.createProject({ name: 'project-dava-template', template: 'dava-dosyasi' });
    assert.equal(r.ok, true);
    for (const d of ['belgeler', 'outputs']) {
      assert.equal(fs.statSync(path.join(r.path, d)).isDirectory(), true);
    }
    const claudeMd = fs.readFileSync(path.join(r.path, 'CLAUDE.md'), 'utf8');
    assert.match(claudeMd, /dilekce-taslagi/);
    assert.equal(fs.existsSync(path.join(r.path, 'AGENTS.md')), true);
    assert.equal(fs.existsSync(path.join(r.path, 'notlar.md')), true);
    assert.match(fs.readFileSync(path.join(r.path, 'notlar.md'), 'utf8'), /Dava Dosyası Notları/);
  });

  it('opposite case: createProject with different template or no template does not create dava-dosyasi files', () => {
    // "dilekce" sablonu kaldirildi; taninmayan sablon adi hicbir sey iskele etmemeli.
    const r = cowork.createProject({ name: 'project-no-dava-template', template: 'dilekce' });
    assert.equal(r.ok, true);
    assert.equal(fs.existsSync(path.join(r.path, 'belgeler')), false);
    assert.equal(fs.existsSync(path.join(r.path, 'notlar.md')), false);
  });

  it('createProject without template does not scaffold', () => {
    const r = cowork.createProject({ name: 'project-no-template' });
    assert.equal(r.ok, true);
    assert.equal(fs.existsSync(path.join(r.path, 'CLAUDE.md')), false);
    assert.equal(fs.existsSync(path.join(r.path, 'kaynaklar')), false);
  });

  it('archives a workspace into a zip excluding .cowork', async () => {
    const p = cowork.createProject({ name: 'project-archive' });
    fs.writeFileSync(path.join(p.path, 'dilekce.docx'), 'doc');
    fs.mkdirSync(path.join(p.path, 'outputs'), { recursive: true });
    fs.writeFileSync(path.join(p.path, 'outputs', 'sonuc.pdf'), 'pdf');

    const r = await cowork.archiveProject({ projectPath: p.path });
    assert.equal(r.ok, true, r.error);
    assert.equal(fs.existsSync(r.path), true);
    assert.ok(r.size > 0);
    assert.equal(r.name, 'project-archive.zip');
    try { fs.unlinkSync(r.path); } catch {}
  });

  it('stores and lists a project matter label', () => {
    const p = cowork.createProject({ name: 'project-matter' });
    const r = cowork.setProjectMatter({ projectPath: p.path, matter: '2026/123 E. — Mehmet Yilmaz' });
    assert.equal(r.ok, true);
    const listed = cowork.listProjects().projects.find(x => x.path === p.path);
    assert.equal(listed.matter, '2026/123 E. — Mehmet Yilmaz');
  });

  it('deletes a project folder from disk and the list', async () => {
    const p = cowork.createProject({ name: 'project-delete' });
    fs.writeFileSync(path.join(p.path, 'belge.txt'), 'icerik', 'utf8');
    assert.equal(fs.existsSync(p.path), true);
    const r = await cowork.deleteProject({ projectPath: p.path });
    assert.equal(r.ok, true);
    assert.equal(r.name, 'project-delete');
    assert.equal(fs.existsSync(p.path), false);
    assert.equal(cowork.listProjects().projects.some(x => x.path === p.path), false);
  });

  it('deletes a project that has session records (live claude session dropped first)', async () => {
    const p = cowork.createProject({ name: 'project-delete-live' });
    const s = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    assert.equal(s.ok, true);
    assert.ok(claudeApp.listSessions().some(x => x.id === s.sessionId), 'oturum bellekte olmalı');
    const r = await cowork.deleteProject({ projectPath: p.path });
    assert.equal(r.ok, true, r.error);
    assert.equal(r.deletedSessionIds.includes(s.sessionId), true);
    assert.equal(fs.existsSync(p.path), false);
    assert.equal(claudeApp.listSessions().some(x => x.id === s.sessionId), false,
      'proje silinince canlı oturum bellekten düşmeli (cwd kilidi)');
  });

  it('refuses to delete a path outside the Cowork root', async () => {
    const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-outside-'));
    const r = await cowork.deleteProject({ projectPath: outside });
    assert.equal(r.ok, false);
    assert.equal(fs.existsSync(outside), true, 'dis yol silinmemeli');
    fs.rmSync(outside, { recursive: true, force: true });
  });

  it('deletes a single session record without touching other providers', async () => {
    const p = cowork.createProject({ name: 'project-session-delete' });
    const c = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    const x = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5' });
    assert.equal(c.ok, true);
    assert.equal(x.ok, true);

    const r = await cowork.deleteSession({ projectPath: p.path, sessionId: c.sessionId });
    assert.equal(r.ok, true, r.error);
    assert.equal(r.provider, 'claude-app');

    const remaining = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    assert.equal(remaining.some(s => s.sessionId === c.sessionId), false, 'silinen kayıt listeden düşmeli');
    assert.equal(remaining.some(s => s.sessionId === x.sessionId), true, 'diğer provider kaydı kalmalı');
    assert.equal(fs.existsSync(p.path), true, 'workspace klasörü yerinde kalmalı');
  });

  it('deleteSession matches a codex record by native threadId too', async () => {
    const p = cowork.createProject({ name: 'project-session-delete-thread' });
    const x = await cowork.startSession({ projectPath: p.path, provider: 'codex-app', model: 'gpt-5.5' });
    cowork.updateCodexSessionThread({
      sessionId: x.sessionId, threadId: 'native-del-thread', cwd: p.path, model: 'gpt-5.5',
    });
    const r = await cowork.deleteSession({ projectPath: p.path, sessionId: 'native-del-thread' });
    assert.equal(r.ok, true, r.error);
    const remaining = cowork.listProjectSessions({ projectPath: p.path }).sessions;
    assert.equal(remaining.length, 0);
  });

  it('deleteSession returns an error for an unknown sessionId', async () => {
    const p = cowork.createProject({ name: 'project-session-delete-miss' });
    const r = await cowork.deleteSession({ projectPath: p.path, sessionId: 'yok-boyle-oturum' });
    assert.equal(r.ok, false);
    assert.match(r.error, /bulunamadi/);
  });

  it('publishes one provider model catalog with bridge-owned defaults', async () => {
    const catalog = await cowork.providerCatalog();
    assert.equal(catalog.ok, true);
    assert.deepEqual(catalog.providers.map(p => p.id), ['claude-app', 'codex-app', 'opencode2-app', 'omp']);
    for (const provider of catalog.providers) {
      assert.ok(provider.defaultModel);
      assert.ok(provider.models.some(model => model.id === provider.defaultModel));
    }
  });

  it('allows only one writer lease per workspace and validates the owner on release', async () => {
    const p = cowork.createProject({ name: 'project-lease' });
    const claude = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    const codex = await cowork.startSession({ projectPath: p.path, provider: 'codex-app' });
    const first = cowork.acquireLease({ projectPath: p.path, provider: 'claude-app', sessionId: claude.sessionId });
    assert.equal(first.ok, true);
    const conflict = cowork.acquireLease({ projectPath: p.path, provider: 'codex-app', sessionId: codex.sessionId });
    assert.equal(conflict.ok, false);
    assert.equal(conflict.lease.provider, 'claude-app');
    assert.equal(cowork.releaseLease({ projectPath: p.path, provider: 'codex-app', sessionId: codex.sessionId }).ok, false);
    assert.equal(cowork.releaseLease({ projectPath: p.path, provider: 'claude-app', sessionId: claude.sessionId }).released, true);
    assert.equal(cowork.acquireLease({ projectPath: p.path, provider: 'codex-app', sessionId: codex.sessionId }).ok, true);
  });

  it('guards cowork provider prompts at the bridge route boundary', async () => {
    const p = cowork.createProject({ name: 'project-prompt-guard' });
    const session = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    assert.equal(cowork.guardProviderPrompt({ provider: 'claude-app', sessionId: session.sessionId }).ok, false);
    cowork.acquireLease({ projectPath: p.path, provider: 'claude-app', sessionId: session.sessionId });
    assert.equal(cowork.guardProviderPrompt({ provider: 'claude-app', sessionId: session.sessionId }).ok, true);

    const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'cowork-prompt-outside-'));
    try {
      const plain = claudeApp.newSession({ cwd: outside });
      assert.equal(cowork.guardProviderPrompt({ provider: 'claude-app', sessionId: plain.sessionId }).ok, true);
    } finally {
      fs.rmSync(outside, { recursive: true, force: true });
    }
  });

  it('writes a bounded handoff note with recent conversation and outputs, then releases the lease', async () => {
    const p = cowork.createProject({ name: 'project-handoff' });
    const session = await cowork.startSession({ projectPath: p.path, provider: 'claude-app' });
    const shell = claudeApp.__getSession(session.sessionId);
    shell.messages.push({ role: 'user', text: 'Dilekçe taslağını hazırla' });
    shell.messages.push({ role: 'assistant', text: 'Taslak hazırlandı, kaynak kontrolü açık kaldı.' });
    fs.writeFileSync(path.join(p.path, 'outputs', 'taslak.docx'), 'docx-test');
    assert.equal(cowork.acquireLease({ projectPath: p.path, provider: 'claude-app', sessionId: session.sessionId }).ok, true);

    const handoff = cowork.createHandoff({
      projectPath: p.path,
      fromProvider: 'claude-app',
      sessionId: session.sessionId,
      toProvider: 'codex-app',
    });
    assert.equal(handoff.ok, true, handoff.error);
    const text = fs.readFileSync(handoff.path, 'utf8');
    assert.match(text, /Dilekçe taslağını hazırla/);
    assert.match(text, /taslak\.docx/);
    assert.match(text, /Devralan: codex-app/);
    assert.equal(cowork.getLease({ projectPath: p.path }).lease, null);
  });
});
