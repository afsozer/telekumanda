import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as codexMcp from '../codex-mcp.mjs';

// CLI'a dokunmayan kısım test edilir: codex-mcp'nin config.toml `enabled`
// toggle düzenlemesi. `codex mcp list/add/remove` gerçek CLI istediğinden
// burada koşulmaz.

let tmpDir;
before(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-mcp-test-'));
});
after(() => {
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch {}
  delete process.env.AGENTBRIDGE_CODEX_HOME;
});

describe('codex-mcp config.toml toggle', () => {
  it('enabled satırını bölüm içinde günceller/ekler, komşu bölümlere dokunmaz', () => {
    const home = path.join(tmpDir, 'codex-home');
    fs.mkdirSync(home, { recursive: true });
    process.env.AGENTBRIDGE_CODEX_HOME = home;
    const toml = [
      'model = "gpt-5"',
      '',
      '[mcp_servers.yargi]',
      'enabled = true',
      'url = "https://x/mcp"',
      '',
      '[mcp_servers.emsal]',
      "command = 'server.exe'",
      '',
      '[mcp_servers.emsal.env]',
      'KEY = "v"',
    ].join('\n');
    fs.writeFileSync(path.join(home, 'config.toml'), toml);

    // Var olan enabled satırı güncellenir
    let r = codexMcp.toggleServer({ name: 'yargi', enabled: false });
    assert.equal(r.ok, true);
    let text = fs.readFileSync(path.join(home, 'config.toml'), 'utf-8');
    assert.match(text, /\[mcp_servers\.yargi\]\nenabled = false\nurl = "https:\/\/x\/mcp"/);

    // enabled satırı olmayan bölüme başlık altına eklenir; env alt-tablosu bozulmaz
    r = codexMcp.toggleServer({ name: 'emsal', enabled: false });
    assert.equal(r.ok, true);
    text = fs.readFileSync(path.join(home, 'config.toml'), 'utf-8');
    assert.match(text, /\[mcp_servers\.emsal\]\nenabled = false\ncommand = 'server\.exe'/);
    assert.match(text, /\[mcp_servers\.emsal\.env\]\nKEY = "v"/);

    // Olmayan sunucu → hata
    assert.equal(codexMcp.toggleServer({ name: 'yok', enabled: true }).ok, false);
  });
});
