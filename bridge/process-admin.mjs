// Process admin — bridge'in başlattığı harness oturumlarını CommandLine imzasıyla bul/öldür.
// Hedef: powershell.exe wrapper'lar (CommandLine'ı bridge\<harness>-tmp içeren).
// taskkill /T ile çocuk CLI ağacı da temizlenir. Masaüstü uygulamalarına ASLA dokunmaz.

import { spawnSync } from 'node:child_process';

// Her harness için temp klasör adı (bridge/<name>-tmp). CommandLine eşleşmesi bununla yapılır.
const HARNESS_TMP = {
  codex:    'codex-tmp',
  opencode: 'opencode-tmp',
  claude:   'claude-tmp',
  agy:      'agy-tmp',
};

// ── CIM ile powershell.exe process'lerini çek ────────────────────────────

function listPowershellProcs() {
  const psScript = `
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
  Select-Object ProcessId, CommandLine, CreationDate |
  ConvertTo-Json -Compress
`;
  const r = spawnSync('powershell.exe',
    ['-NoProfile', '-NonInteractive', '-Command', psScript],
    { encoding: 'utf-8', windowsHide: true, timeout: 10_000 });

  if (r.error || r.status !== 0 || !r.stdout.trim()) return [];
  try {
    const parsed = JSON.parse(r.stdout.trim());
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch { return []; }
}

function parseCimDate(creationDate) {
  // CIM formatı: "20260620191119.123456+180"
  const ds = String(creationDate || '').replace(/[^0-9]/g, '').slice(0, 14);
  if (ds.length !== 14) return { startTime: null, elapsedSec: 0 };
  const y = +ds.slice(0,4), m = +ds.slice(4,6)-1, d = +ds.slice(6,8),
        h = +ds.slice(8,10), mi = +ds.slice(10,12), s = +ds.slice(12,14);
  const dt = new Date(y, m, d, h, mi, s);
  return { startTime: dt.toISOString(), elapsedSec: Math.round((Date.now() - dt.getTime()) / 1000) };
}

// ── Bir harness'e ait bridge-spawned powershell wrapper'larını döndür ───

function listProcesses(name) {
  const tmp = HARNESS_TMP[name];
  if (!tmp) return [];
  const bridgePid = process.pid;
  const needle = `\\${tmp}\\`;            // ör. "\codex-tmp\"
  return listPowershellProcs()
    .filter(it => it && it.ProcessId && it.ProcessId !== bridgePid)
    .filter(it => String(it.CommandLine || '').toLowerCase().includes(needle.toLowerCase()))
    .map(it => {
      const { startTime, elapsedSec } = parseCimDate(it.CreationDate);
      return { pid: it.ProcessId, name: 'powershell.exe', startTime, elapsedSec, cmdline: String(it.CommandLine || '') };
    });
}

// ── Kill harness sessions ─────────────────────────────────────────────────

function killHarness(name) {
  if (!HARNESS_TMP[name]) return { ok: false, killed: [], errors: [`Unknown harness: ${name}`] };
  const procs = listProcesses(name);
  const killed = [];
  const errors = [];
  for (const p of procs) {
    if (p.pid === process.pid) continue;          // bridge'i asla öldürme
    const r = spawnSync('taskkill', ['/PID', String(p.pid), '/T', '/F'],
      { encoding: 'utf-8', windowsHide: true, timeout: 5000 });
    if (r.error || r.status !== 0) errors.push({ pid: p.pid, error: r.stderr?.trim() || `taskkill exit ${r.status}` });
    else killed.push(p.pid);
  }
  return { ok: errors.length === 0, killed, errors };
}

// ── Route helpers (server.mjs tarafından çağrılır) ────────────────────────

export function listProcessesRoute(name) {
  const processes = listProcesses(name);
  return { processes, count: processes.length };
}

