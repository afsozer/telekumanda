import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { searchDir } from '../routes/general.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dirsearch-'));
const matchingDir = path.join(tmp, 'targetproject');
const nestedMatch = path.join(tmp, 'src', 'target-lib');
const noMatchDir = path.join(tmp, 'other');
const hiddenDir = path.join(tmp, '.private', 'target');

before(() => {
  fs.mkdirSync(matchingDir);
  fs.mkdirSync(nestedMatch, { recursive: true });
  fs.mkdirSync(noMatchDir);
  fs.mkdirSync(hiddenDir, { recursive: true });
  // a file (should be ignored)
  fs.writeFileSync(path.join(tmp, 'target.txt'), 'data');
});
after(() => {
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {}
});

describe('searchDir()', () => {
  it('finds immediate matching dir', () => {
    const r = searchDir(tmp, 'target', 2, 50);
    const names = r.map(d => d.name);
    assert.ok(names.includes('targetproject'));
    // file ignored
    assert.equal(r.find(d => d.name === 'target.txt'), undefined);
  });

  it('finds nested matching dir up to maxDepth', () => {
    const r = searchDir(tmp, 'target', 3, 50);
    const names = r.map(d => d.name);
    assert.ok(names.includes('targetproject'));
    assert.ok(names.includes('target-lib'));
  });

  it('does not find match beyond maxDepth', () => {
    const r = searchDir(tmp, 'target', 1, 50);
    const names = r.map(d => d.name);
    assert.ok(names.includes('targetproject'));
    // nestedMatch is at depth 2 — should be skipped
    assert.equal(r.find(d => d.name === 'target-lib'), undefined);
  });

  it('respects limit', () => {
    const r = searchDir(tmp, 'target', 5, 1);
    assert.ok(r.length <= 1);
  });

  it('skips dotfiles and hidden dirs', () => {
    const r = searchDir(tmp, 'target', 3, 50);
    assert.equal(r.find(d => d.name === 'target'), undefined);
  });

  it('returns empty for query shorter than 2 chars', () => {
    const r = searchDir(tmp, 'a', 5, 50);
    assert.equal(r.length, 0);
  });

  it('case-insensitive match', () => {
    const r = searchDir(tmp, 'TARGET', 3, 50);
    assert.ok(r.length > 0);
    assert.ok(r.map(d => d.name).includes('targetproject'));
  });

  it('returns empty for non-existing root', () => {
    const r = searchDir(path.join(tmp, 'doesnotexist'), 'target', 3, 50);
    assert.equal(r.length, 0);
  });
});
