// OpenCode MCP yönetimi. OpenCode MCP sunucularını global config'in `mcp`
// anahtarında tutar (~/.config/opencode/opencode.json):
//   remote: { "type": "remote", "url": "https://…/mcp", "enabled": true }
//   local:  { "type": "local", "command": ["cmd", "arg1", …], "enabled": true }
// Dosya düz JSON olduğundan doğrudan okunup yazılır (CLI gerekmez). `enabled`
// alanı native desteklidir. Değişiklikler `opencode serve` süreci yeniden
// başlayınca geçerli olur; çalışan sunucu config'i yeniden okumaz.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function configFile() {
  return process.env.AGENTBRIDGE_OPENCODE_CONFIG ||
    path.join(os.homedir(), '.config', 'opencode', 'opencode.json');
}

function readRaw() {
  try { return JSON.parse(fs.readFileSync(configFile(), 'utf-8')); }
  catch { return {}; }
}

function writeRaw(obj) {
  fs.mkdirSync(path.dirname(configFile()), { recursive: true });
  fs.writeFileSync(configFile(), JSON.stringify(obj, null, 2) + '\n', 'utf-8');
}

// "cmd arg1 \"iki kelime\"" → ["cmd", "arg1", "iki kelime"]. Opencode local
// command'i dizi ister; telefondan tek satır string gelir.
export function tokenizeCommand(s) {
  const tokens = [];
  const re = /"([^"]*)"|'([^']*)'|(\S+)/g;
  let m;
  while ((m = re.exec(String(s || ''))) !== null) {
    tokens.push(m[1] ?? m[2] ?? m[3]);
  }
  return tokens;
}

function shape(name, def) {
  const isRemote = def?.type === 'remote' || !!def?.url;
  const cmdArr = Array.isArray(def?.command) ? def.command : [];
  return {
    name,
    enabled: def?.enabled !== false,
    type: isRemote ? 'remote' : 'local',
    url: def?.url || '',
    command: cmdArr[0] || '',
    args: cmdArr.slice(1),
    status: def?.enabled === false ? 'disabled' : '',
    managed: false,
  };
}

export function listServers() {
  const raw = readRaw();
  const servers = Object.entries(raw.mcp || {})
    .map(([n, d]) => shape(n, d))
    .sort((a, b) => a.name.localeCompare(b.name));
  return { ok: true, servers, configPath: configFile() };
}

export function saveServer({ name, type, url, command }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  let def;
  if (type === 'remote') {
    if (!url || !String(url).trim()) return { ok: false, error: 'url required' };
    def = { type: 'remote', url: String(url).trim(), enabled: true };
  } else {
    const tokens = tokenizeCommand(command);
    if (!tokens.length) return { ok: false, error: 'command required' };
    def = { type: 'local', command: tokens, enabled: true };
  }
  const raw = readRaw();
  raw.mcp = { ...(raw.mcp || {}), [clean]: def };
  try { writeRaw(raw); } catch (e) { return { ok: false, error: e.message }; }
  return { ok: true, name: clean };
}

export function removeServer({ name }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  const raw = readRaw();
  if (!raw.mcp || !(clean in raw.mcp)) return { ok: false, error: 'not found' };
  delete raw.mcp[clean];
  try { writeRaw(raw); } catch (e) { return { ok: false, error: e.message }; }
  return { ok: true };
}

export function toggleServer({ name, enabled }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  const raw = readRaw();
  if (!raw.mcp || !(clean in raw.mcp)) return { ok: false, error: 'not found' };
  raw.mcp[clean] = { ...raw.mcp[clean], enabled: !!enabled };
  try { writeRaw(raw); } catch (e) { return { ok: false, error: e.message }; }
  return { ok: true, name: clean, enabled: !!enabled };
}
