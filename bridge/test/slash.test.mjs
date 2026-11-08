import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import './helpers/test-config.mjs';
import { SLASH } from '../server.mjs';

describe('/slash endpoint command lists', () => {
  it('removed CLI backends return empty arrays', () => {
    for (const backend of ['claude', 'codex', 'agy']) {
      assert.deepEqual(SLASH[backend] ?? [], []);
    }
  });

  it('unknown backend returns empty array', () => {
    const cmds = SLASH['nonexistent'] ?? [];
    assert.deepEqual(cmds, []);
  });

  it('every remaining command has name and desc strings', () => {
    for (const backend of ['claude-app', 'codex-app']) {
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
