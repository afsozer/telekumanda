// Codex MCP yönetimi. Codex MCP sunucularını ~/.codex/config.toml altındaki
// [mcp_servers.<ad>] bölümlerinde tutar. Otoriter okuma/yazma `codex mcp`
// CLI'ı ile yapılır (list --json / add / remove). CLI'da sunucu bazlı aç/kapa
// komutu yok; toggle için config.toml'daki `enabled = ...` satırı hedefli
// düzenlenir (bölüm sınırları korunur, dosyanın kalanı elden geçirilmez).
// Değişiklikler YENİ Codex oturumlarında geçerli olur; çalışan app-server
// süreci config'i geriye dönük yeniden okumaz.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnResolvedExecutable, tokenizeCommandLine } from './session-utils.mjs';

function codexHome() {
  return process.env.AGENTBRIDGE_CODEX_HOME || path.join(os.homedir(), '.codex');
}

function runCodex(args, timeoutMs = 25000) {
  return new Promise((resolve) => {
    let out = '', err = '', done = false;
    let child;
    try {
      child = spawnResolvedExecutable('codex', args, { windowsHide: true });
    } catch (e) {
      return resolve({ ok: false, error: e.message, stdout: '', stderr: '' });
    }
    const timer = setTimeout(() => {
      if (done) return; done = true;
      try { child.kill(); } catch {}
      resolve({ ok: false, error: 'timeout', stdout: out, stderr: err });
    }, timeoutMs);
    child.stdout?.on('data', d => { out += d; });
    child.stderr?.on('data', d => { err += d; });
    child.on('error', e => { if (done) return; done = true; clearTimeout(timer); resolve({ ok: false, error: e.message, stdout: out, stderr: err }); });
    child.on('close', code => { if (done) return; done = true; clearTimeout(timer); resolve({ ok: code === 0, code, stdout: out, stderr: err }); });
  });
}

// `codex mcp list --json` girdisi → telefon McpServer şekli.
function shape(entry) {
  const t = entry.transport || {};
  const isRemote = !!t.url;
  return {
    name: entry.name,
    enabled: entry.enabled !== false,
    type: isRemote ? 'remote' : 'local',
    url: t.url || '',
    command: t.command || '',
    args: Array.isArray(t.args) ? t.args : [],
    status: entry.enabled === false ? (entry.disabled_reason || 'disabled') : '',
    managed: false,
  };
}

export async function listServers() {
  const r = await runCodex(['mcp', 'list', '--json']);
  if (!r.ok) return { ok: false, error: (r.stderr || r.error || 'codex mcp list failed').trim().slice(0, 300), servers: [] };
  let parsed;
  try { parsed = JSON.parse(r.stdout); } catch { return { ok: false, error: 'codex mcp list çıktısı JSON değil', servers: [] }; }
  const servers = (Array.isArray(parsed) ? parsed : []).map(shape)
    .sort((a, b) => a.name.localeCompare(b.name));
  return { ok: true, servers, configPath: path.join(codexHome(), 'config.toml') };
}

// remote → --url; local → `--` sonrası komut. Aynı adla add mevcut kaydı günceller.
export async function saveServer({ name, type, url, command }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  let args;
  if (type === 'remote') {
    if (!url || !String(url).trim()) return { ok: false, error: 'url required' };
    args = ['mcp', 'add', clean, '--url', url.trim()];
  } else {
    if (!command || !String(command).trim()) return { ok: false, error: 'command required' };
    args = ['mcp', 'add', clean, '--', ...tokenizeCommandLine(command)];
  }
  const r = await runCodex(args);
  if (!r.ok) return { ok: false, error: (r.stderr || r.error || 'codex mcp add failed').trim().slice(0, 300) };
  return { ok: true, name: clean };
}

export async function removeServer({ name }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  const r = await runCodex(['mcp', 'remove', clean]);
  if (!r.ok) return { ok: false, error: (r.stderr || r.error || 'not found').trim().slice(0, 300) };
  return { ok: true };
}

// config.toml'da [mcp_servers.<ad>] bölümündeki `enabled` satırını günceller/ekler.
// Bölüm adı hem çıplak hem tırnaklı ("ad") yazılmış olabilir; ikisi de aranır.
// Salt metin düzenleme: yalnızca hedef bölümün içine dokunulur.
export function toggleServer({ name, enabled }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  const file = path.join(codexHome(), 'config.toml');
  let text;
  try { text = fs.readFileSync(file, 'utf-8'); } catch (e) {
    return { ok: false, error: `config.toml okunamadı: ${e.message}` };
  }
  const lines = text.split(/\r?\n/);
  const headerRe = new RegExp(`^\\s*\\[mcp_servers\\.("?)${clean.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\1\\]\\s*$`);
  const start = lines.findIndex(l => headerRe.test(l));
  if (start < 0) return { ok: false, error: `config.toml'da [mcp_servers.${clean}] bölümü yok` };
  // Bölüm sonu: bir sonraki `[...]` başlığı (alt-tablolar [mcp_servers.<ad>.env] dahil sayılır,
  // ama enabled satırı ana bölümde aranır; alt-tablo başlığı bölümü bitirir).
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (/^\s*\[/.test(lines[i])) { end = i; break; }
  }
  const flag = enabled ? 'enabled = true' : 'enabled = false';
  let found = false;
  for (let i = start + 1; i < end; i++) {
    if (/^\s*enabled\s*=/.test(lines[i])) { lines[i] = flag; found = true; break; }
  }
  if (!found) lines.splice(start + 1, 0, flag);
  try { fs.writeFileSync(file, lines.join('\n'), 'utf-8'); } catch (e) {
    return { ok: false, error: `config.toml yazılamadı: ${e.message}` };
  }
  return { ok: true, name: clean, enabled: !!enabled };
}
