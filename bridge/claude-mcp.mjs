// Claude Code MCP yönetimi. Claude'un MCP sunucuları iki kaynaktan gelir:
//   1) claude.ai hesap connector'ları (Consensus, YargiMCP, Google Drive…) — sunucu
//      tarafında, hesaba bağlı; yerel dosyada DEĞİL. Bunlar salt-okunur (managed).
//   2) Yerel olarak eklenen stdio/http sunucular (claude mcp add ile).
// Otoriter liste yalnızca `claude mcp list` çıktısında olduğundan, .claude.json okumak
// yerine CLI'yi çalıştırıp çıktısını parse ediyoruz.
import { spawnResolvedExecutable, tokenizeCommandLine } from './session-utils.mjs';

function runClaude(args, timeoutMs = 25000) {
  return new Promise((resolve) => {
    const env = { ...process.env };
    let out = '', err = '', done = false;
    let child;
    try {
      child = spawnResolvedExecutable('claude', args, { windowsHide: true, env });
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

// `claude mcp list` satırları: "<ad>: <hedef> - <durum>"
//   claude.ai YargiMCP: https://…/mcp - ✓ Connected
//   claude.ai Gmail: https://… - ! Needs authentication
//   yerel-sunucu: node server.js - ✓ Connected
function parseListLine(line) {
  const t = line.trim();
  if (!t || /MCP server health/i.test(t)) return null;
  const colon = t.indexOf(': ');
  if (colon < 0) return null;
  const name = t.slice(0, colon).trim();
  const rest = t.slice(colon + 2);
  const dash = rest.lastIndexOf(' - ');
  const target = (dash >= 0 ? rest.slice(0, dash) : rest).trim();
  const rawStatus = (dash >= 0 ? rest.slice(dash + 3) : '').trim();
  const status = rawStatus.replace(/^[✓✔!✗×#\-\s]+/, '').trim() || rawStatus;
  const connected = /connected/i.test(rawStatus);
  const isRemote = /^https?:\/\//i.test(target);
  // claude.ai connector'ları hesap-yönetimli → telefondan silinemez/değiştirilemez.
  const managed = /^claude\.ai\s/i.test(name);
  return {
    name,
    enabled: connected,
    type: isRemote ? 'remote' : 'local',
    url: isRemote ? target : '',
    command: isRemote ? '' : target,
    args: [],
    status,
    connected,
    managed,
  };
}

export async function listServers() {
  const r = await runClaude(['mcp', 'list']);
  const servers = [];
  for (const line of String(r.stdout || '').split(/\r?\n/)) {
    const s = parseListLine(line);
    if (s) servers.push(s);
  }
  servers.sort((a, b) => a.name.localeCompare(b.name));
  // CLI bağlantı hatası verse bile (ama çıktı varsa) listeyi döndür.
  if (!servers.length && !r.ok && r.error) {
    return { ok: false, error: r.error, servers: [] };
  }
  return { ok: true, servers };
}

// Yerel/uzak MCP ekle. remote → http transport + url; local → stdio + command.
export async function saveServer({ name, type, url, command }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  let args;
  if (type === 'remote') {
    if (!url || !String(url).trim()) return { ok: false, error: 'url required' };
    args = ['mcp', 'add', '--transport', 'http', clean, url.trim()];
  } else {
    if (!command || !String(command).trim()) return { ok: false, error: 'command required' };
    // `claude mcp add <ad> -- <komut>` — komut `--`'den sonra olduğu gibi geçer.
    args = ['mcp', 'add', clean, '--', ...tokenizeCommandLine(command)];
  }
  const r = await runClaude(args);
  if (!r.ok) return { ok: false, error: (r.stderr || r.error || 'mcp add failed').trim().slice(0, 300) };
  return { ok: true, name: clean };
}

export async function removeServer({ name }) {
  const clean = String(name || '').trim();
  if (!clean) return { ok: false, error: 'name required' };
  if (/^claude\.ai\s/i.test(clean)) {
    return { ok: false, error: 'claude.ai connector\'ları claude.ai üzerinden yönetilir; telefondan silinemez.' };
  }
  const r = await runClaude(['mcp', 'remove', clean]);
  if (!r.ok) return { ok: false, error: (r.stderr || r.error || 'not found').trim().slice(0, 300) };
  return { ok: true };
}

// CLI'de yerel sunucular için aç/kapa yok; claude.ai connector'ları da hesap-yönetimli.
export function toggleServer() {
  return { ok: false, error: 'Claude MCP sunucuları telefondan aç/kapa desteklemiyor (claude.ai connector\'ları claude.ai\'dan yönetilir).' };
}
