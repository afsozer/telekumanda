import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import * as agy from '../agy.mjs';

describe('agy.mjs backend adapter', () => {
  it('exports AGY_MODELS as a non-empty array', () => {
    assert.ok(Array.isArray(agy.AGY_MODELS));
    assert.ok(agy.AGY_MODELS.length > 0);
    for (const m of agy.AGY_MODELS) {
      assert.equal(typeof m.label, 'string');
      assert.equal(typeof m.id, 'string');
    }
  });

  it('listSessions() returns empty array when no sessions exist', () => {
    const sessions = agy.listSessions();
    assert.ok(Array.isArray(sessions));
  });

  it('getConversation() for unknown sessionId returns empty defaults', () => {
    const conv = agy.getConversation('nonexistent-' + Date.now());
    assert.ok(Array.isArray(conv.messages));
    assert.equal(conv.messages.length, 0);
    assert.equal(conv.running, false);
    assert.equal(conv.awaitingApproval, false);
    assert.equal(typeof conv.contextTokens, 'number');
    assert.equal(typeof conv.contextWindow, 'number');
  });

  it('runningSessionId() returns null when nothing is running', () => {
    const sid = agy.runningSessionId();
    assert.equal(sid, null);
  });

  it('stop() for unknown sessionId returns not found', () => {
    const r = agy.stop('nonexistent-' + Date.now());
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
  });

  it('newSession() with valid cwd creates a session', () => {
    const r = agy.newSession({ cwd: os.homedir() });
    assert.equal(r.ok, true);
    assert.equal(typeof r.sessionId, 'string');
    assert.ok(r.sessionId.length > 0);
    assert.equal(typeof r.cwd, 'string');
    assert.equal(typeof r.model, 'string');

    const sessions = agy.listSessions();
    assert.ok(sessions.length >= 1);
    const found = sessions.find(s => s.id === r.sessionId);
    assert.ok(found);
    assert.equal(found.status, 'idle');
    assert.equal(typeof found.turns, 'number');
    assert.equal(typeof found.lastText, 'string');
  });

  it('newSession() with invalid cwd returns error', () => {
    const r = agy.newSession({ cwd: 'C:\\nonexistent_path_abc123_test' });
    assert.equal(r.ok, false);
    assert.ok(r.error.includes('cwd not a directory'));
  });

  it('getConversation() returns messages shape after session creation', () => {
    const r = agy.newSession({ cwd: os.homedir() });
    const conv = agy.getConversation(r.sessionId);
    assert.ok(Array.isArray(conv.messages));
    assert.equal(conv.messages.length, 0);
    assert.equal(conv.running, false);
    assert.equal(conv.awaitingApproval, false);
  });

  it('prompt() for unknown sessionId returns error', () => {
    const r = agy.prompt({ sessionId: 'bad-id', text: 'hello' });
    assert.equal(r.ok, false);
    assert.equal(r.error, 'session not found');
  });

  it('AGY_MODELS exposes only Gemini 3.8 Flash variants', () => {
    const flashIds = agy.AGY_MODELS
      .map(m => m.id)
      .filter(id => /^gemini-3\.[5678]-flash-/.test(id));
    assert.deepEqual(flashIds, [
      'gemini-3.8-flash-high',
      'gemini-3.8-flash-medium',
      'gemini-3.8-flash-low',
    ]);
  });

  // ── Paylaşımlı depo (desktop IDE + CLI, çift home) ──

  it('HOMES exposes cli and ide app data dirs', () => {
    assert.equal(agy.HOMES.cli.appDataDir, 'antigravity-cli');
    assert.equal(agy.HOMES.ide.appDataDir, 'antigravity');
    assert.equal(typeof agy.HOMES.cli.label, 'string');
    assert.equal(typeof agy.HOMES.ide.label, 'string');
  });

  it('newSession() defaults to ide home (desktop-visible) and accepts cli override', () => {
    const rDefault = agy.newSession({ cwd: os.homedir() });
    assert.equal(rDefault.ok, true);
    assert.equal(rDefault.home, 'ide');

    const rCli = agy.newSession({ cwd: os.homedir(), home: 'cli' });
    assert.equal(rCli.ok, true);
    assert.equal(rCli.home, 'cli');

    const rBad = agy.newSession({ cwd: os.homedir(), home: 'garbage' });
    assert.equal(rBad.ok, true);
    assert.equal(rBad.home, 'ide'); // bilinmeyen home → varsayılan

    const sessions = agy.listSessions();
    const found = sessions.find(s => s.id === rCli.sessionId);
    assert.ok(found);
    assert.equal(found.home, 'cli');
    assert.equal(typeof found.sourceLabel, 'string');
  });

  it('listDiskSessions() merges both homes with per-home source labels', () => {
    const r = agy.listDiskSessions();
    assert.equal(r.ok, true);
    assert.ok(Array.isArray(r.sessions));
    for (const s of r.sessions) {
      assert.ok(s.source === 'cli' || s.source === 'ide', 'source must be cli|ide, got: ' + s.source);
      assert.equal(s.sourceLabel, agy.HOMES[s.source].label);
      assert.equal(s.home, s.source);
    }
    // mtime azalan sıralı olmalı (iki home birleşimi sonrası)
    for (let i = 1; i < r.sessions.length; i++) {
      assert.ok(r.sessions[i - 1].mtime >= r.sessions[i].mtime);
    }
  });
});
