import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import * as agy from '../agy.mjs';

describe('backend MODELS regression', () => {
  it('remaining CLI backend exports a non-empty MODELS alias', () => {
    assert.ok((agy.MODELS?.length ?? 0) > 0, 'agy.MODELS should be non-empty');
  });

  it('MODELS alias matches the canonical export', () => {
    assert.deepStrictEqual(agy.MODELS, agy.AGY_MODELS);
  });
});
