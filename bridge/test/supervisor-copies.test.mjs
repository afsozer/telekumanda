import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { cleanupSupervisorCopies } from '../supervisor-copies.mjs';

function dirWith(names) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tk-sup-'));
  for (const n of names) fs.writeFileSync(path.join(dir, n), '@echo off\r\n');
  return dir;
}

describe('cleanupSupervisorCopies', () => {
  it('çalışan süpervizörün kopyasını korur, ölü kopyaları siler', () => {
    const dir = dirWith([
      'telekumanda-supervisor-111.cmd',
      'telekumanda-supervisor-222.cmd',
      'telekumanda-supervisor-333.cmd',
      'baska-dosya.cmd',
      'telekumanda-supervisor-notes.txt',
    ]);
    const r = cleanupSupervisorCopies({ dir, ownName: 'telekumanda-supervisor-222.cmd' });
    assert.deepEqual(r.removed.sort(), ['telekumanda-supervisor-111.cmd', 'telekumanda-supervisor-333.cmd']);
    assert.deepEqual(fs.readdirSync(dir).sort(), [
      'baska-dosya.cmd', 'telekumanda-supervisor-222.cmd', 'telekumanda-supervisor-notes.txt',
    ]);
  });

  it('adı büyük-küçük harf farkıyla gelse de kendi kopyasını korur', () => {
    const dir = dirWith(['telekumanda-supervisor-42.cmd']);
    const r = cleanupSupervisorCopies({ dir, ownName: 'TELEKUMANDA-SUPERVISOR-42.CMD' });
    assert.deepEqual(r.removed, []);
  });

  it('klasör yoksa çökmez', () => {
    const r = cleanupSupervisorCopies({ dir: path.join(os.tmpdir(), 'yok-' + Date.now()), ownName: '' });
    assert.deepEqual(r.removed, []);
  });
});
