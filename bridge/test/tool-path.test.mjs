import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ensureDocumentToolPath, findWingetPopplerBin } from '../tool-path.mjs';

describe('document tool path', () => {
  it('finds Winget Poppler and prepends it idempotently', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'agentbridge-poppler-'));
    try {
      const bin = path.join(
        root,
        'Microsoft',
        'WinGet',
        'Packages',
        'oschwartz10612.Poppler_Microsoft.Winget.Source_test',
        'poppler-25.07.0',
        'Library',
        'bin',
      );
      fs.mkdirSync(bin, { recursive: true });
      fs.writeFileSync(path.join(bin, 'pdftoppm.exe'), '');
      assert.equal(findWingetPopplerBin({ localAppData: root }), bin);

      const env = { LOCALAPPDATA: root, PATH: 'C:\\Windows\\System32' };
      const first = ensureDocumentToolPath(env);
      assert.equal(first.changed, process.platform === 'win32');
      if (process.platform === 'win32') {
        assert.equal(env.PATH.split(path.delimiter)[0], bin);
        assert.equal(ensureDocumentToolPath(env).changed, false);
      }
    } finally {
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
});
