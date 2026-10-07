import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as opencodeMcp from '../opencode-mcp.mjs';
import * as codexMcp from '../codex-mcp.mjs';

// CLI'a dokunmayan kısımlar test edilir: opencode-mcp'nin JSON CRUD'u
// (config dosyası env ile geçici dosyaya yönlendirilir) ve codex-mcp'nin
// config.toml `enabled` toggle düzenlemesi. `codex mcp list/add/remove`
// gerçek CLI istediğinden burada koşulmaz.

let tmpDir;
before(() => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-mcp-test-'));
});
after(() => {
  try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch {}
  delete process.env.AGENTBRIDGE_OPENCODE_CONFIG;
  delete process.env.AGENTBRIDGE_CODEX_HOME;
});

describe('opencode-mcp config CRUD', () => {
  it('save/list/toggle/remove döngüsü mcp anahtarını doğru yönetir', () => {
    const cfg = path.join(tmpDir, 'opencode.json');
    process.env.AGENTBRIDGE_OPENCODE_CONFIG = cfg;
    fs.writeFileSync(cfg, JSON.stringify({ provider: { keep: true } }, null, 2));

    let r = opencodeMcp.saveServer({ name: 'yargi', type: 'remote', url: 'https://x/mcp' });
    assert.equal(r.ok, true);
    r = opencodeMcp.saveServer({ name: 'emsal', type: 'local', command: '"C:\\bir yol\\python.exe" -m emsal' });
    assert.equal(r.ok, true);

    const list = opencodeMcp.listServers();
    assert.equal(list.ok, true);
    assert.deepEqual(list.servers.map(s => s.name), ['emsal', 'yargi']);
    const emsal = list.servers[0];
    assert.equal(emsal.type, 'local');
    assert.equal(emsal.command, 'C:\\bir yol\\python.exe');
    assert.deepEqual(emsal.args, ['-m', 'emsal']);

    r = opencodeMcp.toggleServer({ name: 'yargi', enabled: false });
    assert.equal(r.ok, true);
    const raw = JSON.parse(fs.readFileSync(cfg, 'utf-8'));
    assert.equal(raw.mcp.yargi.enabled, false);
    assert.equal(raw.provider.keep, true); // diğer anahtarlara dokunulmaz

    r = opencodeMcp.removeServer({ name: 'yargi' });
    assert.equal(r.ok, true);
    assert.equal(opencodeMcp.removeServer({ name: 'yok' }).ok, false);
    assert.deepEqual(opencodeMcp.listServers().servers.map(s => s.name), ['emsal']);
  });

  it('tokenizeCommand tırnaklı argümanları korur', () => {
    assert.deepEqual(opencodeMcp.tokenizeCommand('npx -y "@scope/pkg name"'), ['npx', '-y', '@scope/pkg name']);
    assert.deepEqual(opencodeMcp.tokenizeCommand(''), []);
  });
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
