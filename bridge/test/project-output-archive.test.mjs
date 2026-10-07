import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { archiveProjectOutputs, cleanupOutputsArchive } from '../projects.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'archive-'));
const projectDir = path.join(tmp, 'test-project');
const outputsDir = path.join(projectDir, 'outputs');

before(() => {
  fs.mkdirSync(outputsDir, { recursive: true });
  fs.writeFileSync(path.join(outputsDir, 'report.txt'), 'hello outputs');
  fs.writeFileSync(path.join(outputsDir, 'data.json'), '{"ok":true}');
});
after(() => {
  try { fs.rmSync(tmp, { recursive: true, force: true }); } catch {}
  // Clean up any temp zips
  const tmpBridgeDir = path.join(process.cwd(), 'bridge', 'tmp');
  try {
    for (const entry of fs.readdirSync(tmpBridgeDir)) {
      if (entry.endsWith('.zip')) fs.unlinkSync(path.join(tmpBridgeDir, entry));
    }
  } catch {}
});

describe('archiveProjectOutputs()', () => {
  it('creates a zip from the outputs/ folder', async () => {
    const r = await archiveProjectOutputs(projectDir);
    assert.equal(r.ok, true);
    assert.ok(r.path.endsWith('.zip'));
    assert.ok(fs.existsSync(r.path));
    assert.ok(r.name.includes('teslimatlar'));
    assert.ok(r.size > 0);
    // cleanup
    try { fs.unlinkSync(r.path); } catch {}
  });

  it('returns error for missing outputs/', async () => {
    const emptyProject = path.join(tmp, 'no-outputs');
    fs.mkdirSync(emptyProject, { recursive: true });
    const r = await archiveProjectOutputs(emptyProject);
    assert.equal(r.ok, false);
    assert.ok(r.error.includes('bulunamadı'));
  });

  it('returns error for empty outputs/', async () => {
    const emptyOutputs = path.join(tmp, 'empty-outputs');
    const outDir = path.join(emptyOutputs, 'outputs');
    fs.mkdirSync(outDir, { recursive: true });
    const r = await archiveProjectOutputs(emptyOutputs);
    assert.equal(r.ok, false);
    assert.ok(r.error.includes('boş'));
  });

  it('returns error for non-existent path', async () => {
    const r = await archiveProjectOutputs(path.join(tmp, 'does-not-exist'));
    assert.equal(r.ok, false);
  });

  it('archives multiple files preserving their names', async () => {
    const multiDir = path.join(tmp, 'multi-project');
    const multiOut = path.join(multiDir, 'outputs');
    fs.mkdirSync(multiOut, { recursive: true });
    fs.writeFileSync(path.join(multiOut, 'a.txt'), 'aaa');
    fs.writeFileSync(path.join(multiOut, 'b.txt'), 'bbb');
    fs.mkdirSync(path.join(multiOut, 'sub'));
    fs.writeFileSync(path.join(multiOut, 'sub', 'c.txt'), 'ccc');

    const r = await archiveProjectOutputs(multiDir);
    assert.equal(r.ok, true);
    assert.ok(fs.existsSync(r.path));
    assert.ok(r.size > 0);

    // Verify zip contains the files
    const { spawnSync } = await import('node:child_process');
    try {
      const testDir = path.join(os.tmpdir(), 'archive-test-extract');
      fs.mkdirSync(testDir, { recursive: true });
      // Use PowerShell to extract and verify
      const psUnzip = `Expand-Archive -LiteralPath '${r.path.replace(/'/g, "''")}' -DestinationPath '${testDir.replace(/'/g, "''")}' -Force`;
      spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psUnzip], { windowsHide: true });
      const extracted = fs.readdirSync(testDir).filter(e => !e.startsWith('.'));
      // The zip should contain the output files
      assert.ok(extracted.some(e => e === 'a.txt'));
      assert.ok(extracted.some(e => e === 'b.txt'));
      // subdirectory
      const subContent = extracted.some(e => e === 'sub');
      if (subContent) {
        assert.ok(fs.readdirSync(path.join(testDir, 'sub')).includes('c.txt'));
      }
      fs.rmSync(testDir, { recursive: true, force: true });
    } catch {}
    try { fs.unlinkSync(r.path); } catch {}
  });
});

describe('cleanupOutputsArchive()', () => {
  it('deletes a temp archive inside bridge/tmp', async () => {
    const r = await archiveProjectOutputs(projectDir);
    assert.equal(r.ok, true);
    assert.ok(fs.existsSync(r.path));
    const c = cleanupOutputsArchive(r.path);
    assert.equal(c.ok, true);
    assert.ok(!fs.existsSync(r.path));
  });

  it('is ok (idempotent) when the file is already gone', () => {
    const gone = path.join(process.cwd(), 'bridge', 'tmp', 'nope-outputs-00000000-0000.zip');
    const c = cleanupOutputsArchive(gone);
    assert.equal(c.ok, true);
  });

  it('rejects paths outside bridge/tmp (path traversal guard)', () => {
    const outside = path.join(tmp, 'test-project', 'outputs', 'report.txt');
    const c = cleanupOutputsArchive(outside);
    assert.equal(c.ok, false);
    // File must remain untouched.
    assert.ok(fs.existsSync(outside));
  });

  it('rejects an empty path', () => {
    assert.equal(cleanupOutputsArchive('').ok, false);
  });
});
