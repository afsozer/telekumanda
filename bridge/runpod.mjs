// RunPod RunPod worker controller.
//
// Desktop shortcuts and AgentBridge intentionally execute the same PowerShell
// scripts. Long start/stop operations are kept out of HTTP requests: callers
// start an operation, then poll status while this manager captures progress.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const CACHE_MS = 5_000;
const FAILURE_TTL_MS = 15 * 60_000;
const START_TIMEOUT_MS = 8 * 60_000;
const STOP_TIMEOUT_MS = 3 * 60_000;
const START_PROGRESS = {
  requested: 'RunPod başlatılıyor…',
  pod: 'Pod başlatılıyor…',
  ssh_port: 'SSH portu bekleniyor…',
  ssh: 'Pod SSH bağlantısı bekleniyor…',
  model: 'RunPod model sunucusu başlatılıyor…',
  tunnel: 'SSH tüneli açılıyor…',
  health: 'Model ısıtılıyor (soğuk başlatmada ~70sn sürebilir)…',
  proxy: 'OpenCode proxy başlatılıyor…',
};
const STOP_PROGRESS = {
  requested: 'RunPod durduruluyor…',
  tunnel: 'SSH tüneli kapatılıyor…',
  pod: 'Pod durduruluyor…',
};

export function parseRunPodProgressLine(line) {
  const clean = String(line || '').trim();
  let match = clean.match(/^RUNPOD_PROGRESS\|([^|]+)\|(.+)$/);
  if (match) return { type: 'progress', step: match[1], message: match[2] };
  match = clean.match(/^RUNPOD_READY\|(.+)$/);
  if (match) return { type: 'ready', message: match[1] };
  match = clean.match(/^RUNPOD_STOPPED\|(.+)$/);
  if (match) return { type: 'stopped', message: match[1] };
  match = clean.match(/^RUNPOD_ERROR\|(.+)$/);
  if (match) return { type: 'error', message: match[1] };
  match = clean.match(/^RUNPOD_NEED_CONSOLE\|(.+)$/);
  if (match) return { type: 'need_console', url: match[1] };
  return null;
}

export function friendlyRunPodMessage(message) {
  const text = String(message || '').trim();
  if (/not enough free GPUs/i.test(text)) {
    return 'RunPod hostunda yeterli boş GPU yok. Birkaç dakika sonra tekrar deneyin.';
  }
  return text;
}

function jsonLine(text) {
  const lines = String(text || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  for (let i = lines.length - 1; i >= 0; i--) {
    try { return JSON.parse(lines[i]); } catch {}
  }
  return null;
}

export function createRunPodManager(config = {}, deps = {}) {
  const spawnImpl = deps.spawn || spawn;
  const existsSync = deps.existsSync || fs.existsSync;
  const logger = deps.logger || console;
  const now = deps.now || (() => Date.now());
  const scriptDir = String(config.scriptDir || '').trim();
  const powershell = String(config.powershell || 'powershell.exe').trim();
  const scripts = {
    start: path.join(scriptDir, 'runpod-ac.ps1'),
    stop: path.join(scriptDir, 'runpod-kapat.ps1'),
    status: path.join(scriptDir, 'runpod-durum.ps1'),
  };
  const enabled = config.enabled !== false && !!scriptDir && Object.values(scripts).every(file => existsSync(file));
  const disabledMessage = !scriptDir
    ? 'RunPod scriptDir yapılandırılmamış'
    : 'RunPod PowerShell scriptlerinden biri bulunamadı';

  let operation = null;
  let lastFailure = null;
  let lastNeedConsole = null;
  let statusCacheAt = 0;
  let current = {
    ok: enabled,
    enabled,
    phase: enabled ? 'unknown' : 'disabled',
    ready: false,
    action: '',
    step: '',
    message: enabled ? 'RunPod durumu alınıyor' : disabledMessage,
    podStatus: '',
    consoleUrl: '',
    tunnel: false,
    tunnelPid: null,
    billingAvailable: false,
    clientBalance: null,
    currentSpendPerHr: null,
    spendLimit: null,
    startedAt: 0,
    updatedAt: now(),
  };

  const snapshot = (extra = {}) => ({ ...current, operationActive: !!operation, ...extra });
  const update = (next) => {
    current = { ...current, ...next, enabled, updatedAt: now() };
    return snapshot();
  };

  function spawnPowerShell(script, args, timeoutMs, onLine = null) {
    return new Promise((resolve, reject) => {
      const child = spawnImpl(powershell, [
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', script, ...args,
      ], { windowsHide: true, cwd: scriptDir, stdio: ['ignore', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      let stdoutBuf = '';
      let settled = false;
      const finish = (error, result) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) reject(error); else resolve(result);
      };
      const feed = (chunk) => {
        const text = chunk.toString();
        stdout += text;
        stdoutBuf += text;
        const lines = stdoutBuf.split(/\r?\n/);
        stdoutBuf = lines.pop() || '';
        if (onLine) for (const line of lines) onLine(line);
      };
      child.stdout?.on('data', feed);
      child.stderr?.on('data', chunk => { stderr += chunk.toString(); });
      child.on('error', error => finish(error));
      child.on('close', code => {
        if (stdoutBuf && onLine) onLine(stdoutBuf);
        finish(null, { code: Number(code ?? -1), stdout: stdout.slice(-16_000), stderr: stderr.slice(-8_000) });
      });
      const timer = setTimeout(() => {
        try { child.kill(); } catch {}
        finish(new Error(`RunPod PowerShell işlemi ${Math.round(timeoutMs / 1000)} saniyede tamamlanmadı`));
      }, timeoutMs);
      timer.unref?.();
    });
  }

  async function queryStatus() {
    const result = await spawnPowerShell(scripts.status, [], 35_000);
    const parsed = jsonLine(result.stdout);
    if (!parsed) throw new Error(result.stderr.trim() || 'RunPod durum scripti geçerli JSON döndürmedi');
    statusCacheAt = now();
    const observedPhase = String(parsed.phase || (parsed.ready ? 'ready' : 'stopped'));
    if (observedPhase === 'ready' || observedPhase === 'running') {
      lastFailure = null;
      lastNeedConsole = null;
    } else if (lastFailure && (now() - lastFailure.at) >= FAILURE_TTL_MS) {
      lastFailure = null;
    }
    const failure = lastFailure;
    const needConsole = failure ? null : lastNeedConsole;
    return update({
      ok: failure ? false : parsed.ok !== false,
      phase: failure ? 'error' : needConsole ? 'need_console' : observedPhase,
      ready: !!parsed.ready,
      action: '',
      step: failure ? 'error' : needConsole ? 'need_console' : '',
      message: failure
        ? failure.message
        : needConsole
        ? 'Pod kapalı — RunPod\'dan başlat/migrate et, sonra tekrar Başlat.'
        : parsed.phase === 'ready'
        ? 'RunPod hazır'
        : parsed.phase === 'running'
          ? 'Pod çalışıyor; RunPod API hazır değil'
          : parsed.phase === 'stopped'
            ? 'RunPod kapalı'
            : String(parsed.message || ''),
      consoleUrl: needConsole ? needConsole.url : '',
      podStatus: String(parsed.podStatus || ''),
      tunnel: !!parsed.tunnel,
      tunnelPid: parsed.tunnelPid != null && Number.isFinite(Number(parsed.tunnelPid)) ? Number(parsed.tunnelPid) : null,
      billingAvailable: parsed.billingAvailable === true,
      clientBalance: parsed.clientBalance != null && Number.isFinite(Number(parsed.clientBalance))
        ? Number(parsed.clientBalance) : null,
      currentSpendPerHr: parsed.currentSpendPerHr != null && Number.isFinite(Number(parsed.currentSpendPerHr))
        ? Number(parsed.currentSpendPerHr) : null,
      spendLimit: parsed.spendLimit != null && Number.isFinite(Number(parsed.spendLimit))
        ? Number(parsed.spendLimit) : null,
      startedAt: 0,
    });
  }

  async function status({ force = false } = {}) {
    if (!enabled) return snapshot();
    if (operation) return snapshot();
    if (!force && statusCacheAt && (now() - statusCacheAt) < CACHE_MS) return snapshot();
    try {
      return await queryStatus();
    } catch (error) {
      return update({ ok: false, phase: 'error', ready: false, message: error.message || String(error) });
    }
  }

  function onProgress(action, line) {
    const event = parseRunPodProgressLine(line);
    if (!event) return;
    if (event.type === 'progress') {
      const translated = (action === 'start' ? START_PROGRESS : STOP_PROGRESS)[event.step] || event.message;
      update({ phase: action === 'start' ? 'starting' : 'stopping', action, step: event.step, message: translated });
    } else if (event.type === 'ready') {
      update({ phase: 'ready', ready: true, action, step: 'ready', message: 'RunPod hazır' });
    } else if (event.type === 'need_console') {
      update({ ok: true, phase: 'need_console', ready: false, action, step: 'need_console', message: 'Pod kapalı — RunPod\'dan başlat/migrate et, sonra tekrar Başlat.', consoleUrl: event.url });
    } else if (event.type === 'stopped') {
      update({ phase: 'stopped', ready: false, action, step: 'stopped', message: 'RunPod durduruldu' });
    } else if (event.type === 'error') {
      update({ ok: false, phase: 'error', ready: false, action, step: 'error', message: friendlyRunPodMessage(event.message) });
    }
    return event;
  }

  function begin(action) {
    if (!enabled) return snapshot({ accepted: false });
    if (operation) return snapshot({ accepted: false });
    lastFailure = null;
    lastNeedConsole = null;
    let needConsole = null;
    const expectedPhase = action === 'start' ? 'starting' : 'stopping';
    update({
      ok: true,
      phase: expectedPhase,
      ready: action === 'stop' ? current.ready : false,
      action,
      step: 'requested',
      message: action === 'start' ? 'RunPod başlatılıyor…' : 'RunPod durduruluyor…',
      startedAt: now(),
    });
    const script = action === 'start' ? scripts.start : scripts.stop;
    const timeout = action === 'start' ? START_TIMEOUT_MS : STOP_TIMEOUT_MS;
    operation = spawnPowerShell(script, ['-NoPause'], timeout, line => {
      const ev = onProgress(action, line);
      if (ev && ev.type === 'need_console') needConsole = ev;
    })
      .then(async result => {
        if (result.code !== 0) {
          const marker = String(result.stdout).split(/\r?\n/).map(parseRunPodProgressLine).filter(Boolean).findLast?.(e => e.type === 'error');
          throw new Error(marker?.message || result.stderr.trim() || `RunPod scripti ${result.code} koduyla kapandı`);
        }
        if (needConsole) {
          lastNeedConsole = { url: needConsole.url, at: now() };
          return;
        }
        const verified = await queryStatus();
        if (action === 'start' && !verified.ready) throw new Error(verified.message || 'RunPod başlatıldı ancak API hazır değil');
        if (action === 'stop' && verified.phase !== 'stopped') throw new Error(verified.message || 'RunPod durdurma doğrulanamadı');
      })
      .catch(error => {
        const message = friendlyRunPodMessage(error.message || String(error));
        lastFailure = { action, message, at: now() };
        logger.warn?.(`[runpod] ${action} failed: ${message}`);
        return update({ ok: false, phase: 'error', ready: false, action: '', step: 'error', message, startedAt: 0 });
      })
      .finally(() => { operation = null; });
    return snapshot({ accepted: true });
  }

  return {
    status,
    start: () => begin('start'),
    stop: () => begin('stop'),
  };
}
