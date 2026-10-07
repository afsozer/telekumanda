import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { listDirs } from '../routes/general.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dirs-'));
const subDir = path.join(tmp, 'zfolder');
const aFile = path.join(tmp, 'a.txt');
const bFile = path.join(tmp, 'b.png');
const hiddenFile = path.join(tmp, '.hidden');

before(() => {
  fs.mkdirSync(subDir);
  fs.writeFileSync(aFile, 'hello');
  fs.writeFileSync(bFile, 'pngdata');
  fs.writeFileSync(hiddenFile, 'secret');
});
after(() => {
  for (const f of [aFile, bFile, hiddenFile]) try { fs.unlinkSync(f); } catch {}
  try { fs.rmdirSync(subDir); } catch {}
  try { fs.rmdirSync(tmp); } catch {}
});

describe('listDirs()', () => {
  it('lists only dirs by default (no files flag)', () => {
    const r = listDirs(tmp, false);
    assert.equal(r.ok, true);
    assert.equal(r.base, tmp);
    assert.equal(r.parent, path.dirname(tmp));
    assert.equal(r.dirs.length, 1);
    assert.equal(r.dirs[0].name, 'zfolder');
    assert.equal(r.dirs[0].type, 'dir');
    assert.equal(r.dirs[0].size, 0);
  });

  it('lists dirs and files when includeFiles=true, dirs first then alphabetical', () => {
    const r = listDirs(tmp, true);
    assert.equal(r.ok, true);
    assert.equal(r.dirs.length, 3);
    assert.equal(r.dirs[0].name, 'zfolder');
    assert.equal(r.dirs[0].type, 'dir');
    assert.equal(r.dirs[1].name, 'a.txt');
    assert.equal(r.dirs[1].type, 'file');
    assert.equal(r.dirs[1].size, 5);
    assert.equal(r.dirs[2].name, 'b.png');
    assert.equal(r.dirs[2].type, 'file');
    assert.equal(r.dirs[2].size, 7);
  });

  it('file entries carry numeric mtime; dirs mtime=0', () => {
    const r = listDirs(tmp, true);
    const file = r.dirs.find(d => d.name === 'a.txt');
    const dir = r.dirs.find(d => d.name === 'zfolder');
    assert.equal(typeof file.mtime, 'number');
    assert.ok(file.mtime > 0, 'dosya mtime > 0 olmalı');
    assert.equal(dir.mtime, 0);
  });

  it('skips dotfiles and node_modules even with files=true', () => {
    const r = listDirs(tmp, true);
    assert.equal(r.dirs.find(d => d.name === '.hidden'), undefined);
  });

  it('returns ok:false for non-directory', () => {
    const r = listDirs(aFile, true);
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not a directory');
  });

  it('does not expose a parent above the filesystem root', () => {
    const root = path.parse(tmp).root;
    const r = listDirs(root, false);
    assert.equal(r.ok, true);
    assert.equal(r.base, root);
    assert.equal(r.parent, '');
  });

  it('Cowork confinement hides the parent and rejects an outside base', () => {
    const atRoot = listDirs(tmp, true, tmp);
    assert.equal(atRoot.ok, true);
    assert.equal(atRoot.parent, '');

    const outside = listDirs(path.dirname(tmp), true, tmp);
    assert.equal(outside.ok, false);
    assert.match(outside.error, /Cowork root/);
  });

  it('Cowork confinement does not list a junction that resolves outside', () => {
    const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'dirs-outside-'));
    const link = path.join(tmp, 'outside-link');
    try {
      fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
      const r = listDirs(tmp, true, tmp);
      assert.equal(r.ok, true);
      assert.equal(r.dirs.some(d => d.name === 'outside-link'), false);
    } finally {
      try { fs.rmSync(link, { recursive: true, force: true }); } catch {}
      try { fs.rmSync(outside, { recursive: true, force: true }); } catch {}
    }
  });
});
