import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { SLASH } from '../server.mjs';

describe('/slash endpoint command lists', () => {
  it('agy backend returns 5 commands', () => {
    const cmds = SLASH['agy'] ?? [];
    assert.equal(cmds.length, 5);
    assert.equal(cmds[0].name, 'goal');
    assert.equal(cmds[4].name, 'plan');
  });

  it('removed CLI backends return empty arrays', () => {
    for (const backend of ['claude', 'codex', 'opencode']) {
      assert.deepEqual(SLASH[backend] ?? [], []);
    }
  });

  it('unknown backend returns empty array', () => {
    const cmds = SLASH['nonexistent'] ?? [];
    assert.deepEqual(cmds, []);
  });

  it('every remaining command has name and desc strings', () => {
    for (const backend of ['agy', 'claude-app', 'codex-app', 'opencode-app']) {
      const cmds = SLASH[backend] ?? [];
      for (const cmd of cmds) {
        assert.equal(typeof cmd.name, 'string');
        assert.equal(typeof cmd.desc, 'string');
        assert.ok(cmd.name.length > 0, `${backend} command missing name`);
        assert.ok(cmd.desc.length > 0, `${backend} command missing desc`);
      }
    }
  });
});
