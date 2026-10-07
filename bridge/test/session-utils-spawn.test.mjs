import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { spawnResolvedAsync, tokenizeCommandLine } from '../session-utils.mjs';

describe('shellsiz executable yardımcıları', () => {
  it('tırnaklı MCP komutunu argv dizisine çevirir, shell operatörlerini çalıştırmaz', () => {
    assert.deepEqual(
      tokenizeCommandLine('"C:\\Program Files\\node.exe" server.js "iki kelime" & whoami'),
      ['C:\\Program Files\\node.exe', 'server.js', 'iki kelime', '&', 'whoami'],
    );
  });

  it('çözülen executable çıktısını ve exit kodunu toplar', async () => {
    const result = await spawnResolvedAsync(process.execPath, ['-e', 'process.stdout.write("ok")'], { timeout: 5_000 });
    assert.equal(result.status, 0);
    assert.equal(result.stdout, 'ok');
    assert.equal(result.error, null);
  });
});
