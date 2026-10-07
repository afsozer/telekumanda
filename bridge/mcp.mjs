// MCP server registry for Antigravity. Antigravity (Gemini-based) reads its MCP servers from
// ~/.gemini/config/mcp_config.json under the "mcpServers" key. Two server shapes:
//   remote: { serverUrl: "https://…/mcp/", headers?: {...} }
//   local:  { command: "C:\\…\\server.exe", args?: [...], env?: {...} }
// There is no native "enabled" flag, so to disable a server we move it to a sibling
// "_disabledServers" object (Antigravity ignores unknown top-level keys); enabling moves it back.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CONFIG_FILE = path.join(os.homedir(), '.gemini', 'config', 'mcp_config.json');

function readRaw() {
  try { return JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf-8')); }
  catch { return {}; }
}

function writeRaw(obj) {
  fs.mkdirSync(path.dirname(CONFIG_FILE), { recursive: true });
  fs.writeFileSync(CONFIG_FILE, JSON.stringify(obj, null, 2) + '\n', 'utf-8');
}

function shape(name, def, enabled) {
  const isRemote = !!(def && (def.serverUrl || def.url));
  return {
    name,
    enabled,
    type: isRemote ? 'remote' : 'local',
    url: def?.serverUrl || def?.url || '',
    command: def?.command || '',
    args: Array.isArray(def?.args) ? def.args : [],
  };
}

export function listServers() {
  const raw = readRaw();
  const on = raw.mcpServers || {};
  const off = raw._disabledServers || {};
  const servers = [
    ...Object.entries(on).map(([n, d]) => shape(n, d, true)),
    ...Object.entries(off).map(([n, d]) => shape(n, d, false)),
  ].sort((a, b) => a.name.localeCompare(b.name));
  return { ok: true, servers, configPath: CONFIG_FILE };
}

// Add or update a server. type 'remote' needs url; type 'local' needs command.
export function saveServer({ name, type, url, command, args, env, headers }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  let def;
  if (type === 'remote') {
    if (!url || !String(url).trim()) return { ok: false, error: 'url required' };
    def = { serverUrl: String(url).trim() };
    if (headers && typeof headers === 'object') def.headers = headers;
    else def.headers = { 'Content-Type': 'application/json' };
  } else {
    if (!command || !String(command).trim()) return { ok: false, error: 'command required' };
    def = { command: String(command).trim(), args: Array.isArray(args) ? args : [] };
    if (env && typeof env === 'object' && Object.keys(env).length) def.env = env;
  }
  const raw = readRaw();
  raw.mcpServers = raw.mcpServers || {};
  // If it was disabled, drop the disabled copy so the enabled one wins.
  if (raw._disabledServers) delete raw._disabledServers[clean];
  raw.mcpServers[clean] = def;
  writeRaw(raw);
  return { ok: true, name: clean };
}

export function removeServer(name) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  const raw = readRaw();
  let found = false;
  if (raw.mcpServers && raw.mcpServers[clean]) { delete raw.mcpServers[clean]; found = true; }
  if (raw._disabledServers && raw._disabledServers[clean]) { delete raw._disabledServers[clean]; found = true; }
  if (!found) return { ok: false, error: 'not found' };
  writeRaw(raw);
  return { ok: true };
}

export function toggleServer(name, enabled) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  const raw = readRaw();
  raw.mcpServers = raw.mcpServers || {};
  raw._disabledServers = raw._disabledServers || {};
  if (enabled) {
    const def = raw._disabledServers[clean];
    if (!def) return { ok: false, error: 'not found in disabled' };
    delete raw._disabledServers[clean];
    raw.mcpServers[clean] = def;
  } else {
    const def = raw.mcpServers[clean];
    if (!def) return { ok: false, error: 'not found in enabled' };
    delete raw.mcpServers[clean];
    raw._disabledServers[clean] = def;
  }
  if (!Object.keys(raw._disabledServers).length) delete raw._disabledServers;
  writeRaw(raw);
  return { ok: true, enabled };
}
