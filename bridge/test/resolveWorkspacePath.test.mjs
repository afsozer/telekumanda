import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { resolveWorkspacePath, readWorkspaceFile } from '../server.mjs';

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rwp-'));
const testFile = path.join(tmpDir, 'doc.txt');
fs.writeFileSync(testFile, 'content\n', 'utf-8');

after(() => {
  try { fs.unlinkSync(testFile); } catch {}
  try { fs.rmdirSync(tmpDir); } catch {}
});

describe('resolveWorkspacePath()', () => {
  it('resolves an absolute path', () => {
    const r = resolveWorkspacePath(testFile);
    assert.equal(r.ok, true);
    assert.equal(r.target, testFile);
    assert.equal(r.name, 'doc.txt');
  });

  it('strips agfile:// prefix', () => {
    const r = resolveWorkspacePath('agfile:///' + testFile.replace(/\\/g, '/'));
    assert.equal(r.ok, true);
    assert.equal(r.name, 'doc.txt');
  });

  it('returns ok:false for empty path', () => {
    const r = resolveWorkspacePath('');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'path required');
  });

  it('returns ok:false with name for non-existent path', () => {
    const r = resolveWorkspacePath('C:\\nonexistent\\nope.txt');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
    assert.equal(r.name, 'nope.txt');
  });
});

describe('readWorkspaceFile (uses resolveWorkspacePath)', () => {
  it('still returns content for absolute path', () => {
    const r = readWorkspaceFile(testFile);
    assert.equal(r.ok, true);
    assert.equal(r.content, 'content\n');
    assert.equal(r.name, 'doc.txt');
  });
});
