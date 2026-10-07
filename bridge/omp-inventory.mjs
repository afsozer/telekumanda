// OMP runtime envanterini telefon sözleşmesine çeviren saf yardımcılar.
// Skill'lerde otoriter kaynak RPC `get_available_commands`; böylece model
// prompt'una bilerek konmayan ama `/skill:<ad>` ile çalışan `bro` da görünür.
// MCP'lerde get_state systemPrompt içindeki gerçekten mount edilmiş xd://
// araçları, dış araç config'lerindeki sunucu adlarıyla eşleştirilir.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf-8')); }
  catch { return {}; }
}

function addNames(map, names, source) {
  for (const name of names || []) {
    const clean = String(name || '').trim();
    if (clean && !map.has(clean)) map.set(clean, { name: clean, source });
  }
}

function codexMcpNames(file) {
  let text = '';
  try { text = fs.readFileSync(file, 'utf-8'); } catch { return []; }
  const out = [];
  const re = /^\s*\[mcp_servers\.(?:"([^"]+)"|'([^']+)'|([^\]]+))\]\s*$/gm;
  let match;
  while ((match = re.exec(text))) out.push((match[1] || match[2] || match[3] || '').trim());
  return out;
}

export function discoverOmpMcpCandidates({
  home = os.homedir(),
  ompRoot = process.env.AGENTBRIDGE_OMP_ROOT || path.join(home, '.omp', 'agent'),
} = {}) {
  const candidates = new Map();
  const native = readJson(path.join(ompRoot, 'mcp.json'));
  addNames(candidates, Object.keys(native.mcpServers || {}), 'omp');
  // OMP'nin kendi discovery sırasındaki yerel kullanıcı kaynakları. Yalnız
  // adları okuyoruz; env/header/secret alanları hiçbir zaman yanıt nesnesine girmez.
  const claude = readJson(path.join(home, '.claude', '.claude.json'));
  const claudeCompat = readJson(path.join(home, '.claude.json'));
  addNames(candidates, Object.keys(claude.mcpServers || {}), 'claude');
  addNames(candidates, Object.keys(claudeCompat.mcpServers || {}), 'claude');
  addNames(candidates, codexMcpNames(path.join(home, '.codex', 'config.toml')), 'codex');
  const opencode = readJson(path.join(home, '.config', 'opencode', 'opencode.json'));
  addNames(candidates, Object.keys(opencode.mcp || {}), 'opencode');
  return {
    candidates: [...candidates.values()],
    disabled: new Set(Array.isArray(native.disabledServers) ? native.disabledServers.map(String) : []),
  };
}

export function skillInventoryFromCommands(result) {
  const map = new Map();
  for (const command of Array.isArray(result?.commands) ? result.commands : []) {
    if (command?.source !== 'skill' || !String(command?.name || '').startsWith('skill:')) continue;
    const name = String(command.name).slice('skill:'.length).trim();
    if (!name || map.has(name)) continue;
    map.set(name, { name, description: String(command.description || '').trim() });
  }
  const skillDetails = [...map.values()].sort((a, b) => a.name.localeCompare(b.name));
  return { skills: skillDetails.map(s => s.name), skillDetails };
}

function promptText(systemPrompt) {
  return Array.isArray(systemPrompt) ? systemPrompt.join('\n') : String(systemPrompt || '');
}

function sanitizedMcpName(name) {
  return String(name || '').replace(/[^A-Za-z0-9_]/g, '_');
}

export function mcpInventoryFromPrompt(systemPrompt, discovery) {
  // Aynı cihaz hem tam Tool Inventory'de hem "docs on demand" özetinde
  // geçebilir; toolCount gerçek araç sayısı olsun diye kimliği tekilleştir.
  const ids = [...new Set([...promptText(systemPrompt).matchAll(/xd:\/\/mcp__([A-Za-z0-9_]+)/g)].map(m => m[1]))];
  const candidates = [...(discovery?.candidates || [])]
    .map(c => ({ ...c, prefix: sanitizedMcpName(c.name) + '_' }))
    .sort((a, b) => b.prefix.length - a.prefix.length);
  const claimed = new Set();
  const servers = [];
  for (const candidate of candidates) {
    const toolIndexes = [];
    ids.forEach((id, index) => { if (!claimed.has(index) && id.startsWith(candidate.prefix)) toolIndexes.push(index); });
    if (!toolIndexes.length) continue;
    toolIndexes.forEach(index => claimed.add(index));
    servers.push({
      name: candidate.name,
      enabled: true,
      type: 'runtime',
      status: `Bağlı · ${toolIndexes.length} araç · ${candidate.source}`,
      managed: true,
      toolCount: toolIndexes.length,
      source: candidate.source,
    });
  }
  // Kullanıcı denylist'e aldığı dış kayıtları yeniden açabilmek için envanterde
  // kapalı olarak tut. Runtime prompt'unda bulunmamaları beklenen davranıştır.
  for (const name of discovery?.disabled || []) {
    if (servers.some(s => s.name === name)) continue;
    const candidate = candidates.find(c => c.name === name);
    servers.push({
      name,
      enabled: false,
      type: 'runtime',
      status: `OMP'de kapalı${candidate?.source ? ` · ${candidate.source}` : ''}`,
      managed: true,
      toolCount: 0,
      source: candidate?.source || '',
    });
  }
  return servers.sort((a, b) => a.name.localeCompare(b.name));
}
