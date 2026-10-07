import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { getActiveState } from '../server.mjs';

describe('getActiveState()', () => {
  it('returns idle when no session is running', () => {
    const s = getActiveState();
    assert.equal(s.running, false);
    assert.equal(s.backend, '');
    assert.equal(s.sessionId, '');
    assert.equal(s.title, '');
  });

  it('returns the correct shape', () => {
    const s = getActiveState();
    assert.equal(typeof s.running, 'boolean');
    assert.equal(typeof s.backend, 'string');
    assert.equal(typeof s.sessionId, 'string');
    assert.equal(typeof s.title, 'string');
  });

  it('title never equals raw camelCase module key (A2 regresyon)', () => {
    // getActiveState should never return the raw internal key as title.
    // A2 bug: MODULE_LABELS had 'chatgpt-planner' instead of chatgptPlanner,
    // causing the fallback to the raw key. Verify the title is either empty
    // (idle) or a proper label (when running).
    const s = getActiveState();
    assert.notEqual(s.title, 'chatgptPlanner', 'title should not be raw module key');
    assert.notEqual(s.title, 'chatgpt-planner', 'title should not be raw kebab key');
  });
});
