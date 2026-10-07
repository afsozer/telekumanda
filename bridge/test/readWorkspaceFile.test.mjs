import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { readWorkspaceFile } from '../server.mjs';

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rwf-'));
const testFile = path.join(tmpDir, 'hello.txt');
const testContent = 'hello world\n';

before(() => {
  fs.writeFileSync(testFile, testContent, 'utf-8');
});

after(() => {
  try { fs.unlinkSync(testFile); } catch {}
  try { fs.rmdirSync(tmpDir); } catch {}
});

describe('readWorkspaceFile()', () => {
  it('returns ok shape for an absolute Windows path', () => {
    const r = readWorkspaceFile(testFile);
    assert.equal(r.ok, true);
    assert.equal(r.name, 'hello.txt');
    assert.equal(r.path, testFile);
    assert.equal(r.content, testContent);
    assert.equal(typeof r.size, 'number');
    assert.equal(r.size > 0, true);
    assert.equal(typeof r.mtime, 'number');
    assert.match(r.hash, /^[a-f0-9]{64}$/);
    assert.equal(typeof r.truncated, 'boolean');
    assert.equal(r.truncated, false);
  });

  it('strips agfile:// prefix and resolves absolute path', () => {
    const r = readWorkspaceFile('agfile:///' + testFile.replace(/\\/g, '/'));
    assert.equal(r.ok, true);
    assert.equal(r.name, 'hello.txt');
  });

  it('returns ok:false for a non-existent path', () => {
    const r = readWorkspaceFile('C:\\nonexistent\\path\\nope_' + Date.now() + '.txt');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
  });

  it('returns ok:false with error when path is empty', () => {
    const r = readWorkspaceFile('');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'path required');
  });

  it('returns ok:false when path is null/undefined', () => {
    const r = readWorkspaceFile(null);
    assert.equal(r.ok, false);
    assert.equal(r.error, 'path required');
  });

  it('truncates content when file exceeds MAX_FILE', () => {
    const bigFile = path.join(tmpDir, 'big.txt');
    const big = 'x'.repeat(250000);
    fs.writeFileSync(bigFile, big, 'utf-8');
    try {
      const r = readWorkspaceFile(bigFile);
      assert.equal(r.ok, true);
      assert.equal(r.truncated, true);
      assert.equal(r.content.length <= 200000, true);
      assert.equal(r.size, 250000);
    } finally {
      try { fs.unlinkSync(bigFile); } catch {}
    }
  });
});
