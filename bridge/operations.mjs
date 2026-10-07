import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const MAX_EVENTS = 250;

function readEvents(filePath) {
  try {
    const value = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    return Array.isArray(value) ? value.slice(-MAX_EVENTS) : [];
  } catch { return []; }
}

function writeEvents(filePath, events) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmp = filePath + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(events.slice(-MAX_EVENTS), null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, filePath);
}

function operationStatus(session) {
  if (session?.awaitingApproval || session?.awaitingUserInput) return 'waiting';
  const raw = String(session?.status || '').toLowerCase();
  if (raw === 'running' || session?.running) return 'running';
  if (raw === 'failed' || raw === 'error' || session?.interruptStuck) return 'failed';
  return 'idle';
}

function sessionSummary(session) {
  const text = String(session?.lastText || session?.title || '').replace(/\s+/g, ' ').trim();
  return text.slice(0, 240);
}

// Oturumun kendi basligi (ilk gercek kullanici mesaji). Kart basligi bundan
// gelir; backendLabel ("Codex App") artik yalnizca alt satirdaki etikettir.
function sessionTitle(session) {
  return String(session?.title || '').replace(/\s+/g, ' ').trim().slice(0, 120);
}

// Oturumun son gercek etkinligi (ms). Ornekleme saati DEGIL.
function activityMs(session) {
  return Math.max(Number(session?.lastActivity || 0), Number(session?.lastUserAt || 0)) || 0;
}

const STATUS_RANK = { waiting: 3, failed: 2, running: 1, idle: 0 };

// Bir codex thread'ini birden fazla oturum kabugu paylasabilir (diskten adopt
// edilen kabuk + canli kabuk ayni threadId'yi tasir). Operasyon listesi kabuk
// degil IS gosterdigi icin ayni thread tek kart olmali; 02.08.2026'da ayni
// sohbet iki kez "Codex App" karti olarak goruluyordu.
// Kazanan kabuk: once dikkat gerektiren durum, sonra en taze etkinlik, sonra
// en cok tur. Boylece "Ac" her zaman canli kabugu acar.
function pickThreadShells(sessions) {
  const best = new Map();
  for (const session of sessions) {
    if (!session?.id) continue;
    const key = String(session.threadId || session.id);
    const current = best.get(key);
    if (!current || shellScore(session) > shellScore(current)) best.set(key, session);
  }
  return best;
}

function shellScore(session) {
  const status = STATUS_RANK[operationStatus(session)] ?? 0;
  // Tek sayiya katla: durum en agir basar, sonra etkinlik, sonra tur sayisi.
  return status * 1e15 + activityMs(session) * 1e3 + Math.min(Number(session.turns || 0), 999);
}

export function createOperationsTracker({
  modules,
  labels = {},
  filePath = path.join(os.homedir(), '.agentbridge', 'operations.json'),
  now = () => new Date(),
  // Yeni olay dustugunde cagrilir (orn. bildirim kuyrugu); hatasi izlemeyi bozamaz.
  onEvent = null,
} = {}) {
  let previous = new Map();
  let events = readEvents(filePath);
  let timer = null;

  function appendEvent(operation, kind, status, summary = '') {
    const at = now().toISOString();
    const event = {
      id: `${at}-${operation.backend}-${operation.sessionId}-${kind}`,
      kind,
      status,
      backend: operation.backend,
      backendLabel: operation.backendLabel,
      sessionId: operation.sessionId,
      cwd: operation.cwd,
      model: operation.model,
      // Kabuk oldukten sonra da cozulebilsin diye kalici kimlik de saklanir.
      diskId: operation.diskId || '',
      title: operation.title || '',
      // Olay kartinin alt satiri: once anlik metin, yoksa oturum basligi.
      summary: summary || operation.summary || operation.title || '',
      at,
    };
    events.push(event);
    events = events.slice(-MAX_EVENTS);
    if (onEvent) { try { onEvent(event); } catch {} }
  }

  function sample() {
    const current = new Map();
    const failedBackends = new Set();
    let changed = false;
    for (const [backend, mod] of Object.entries(modules || {})) {
      let sessions = [];
      try { sessions = mod.listSessions?.() || []; } catch { failedBackends.add(backend); continue; }
      for (const [threadKey, session] of pickThreadShells(sessions)) {
        const status = operationStatus(session);
        const title = sessionTitle(session);
        const summary = sessionSummary(session);
        // Kimlik thread'e sabitlenir (kabuk degisse de kart ayni kalir), ama
        // sessionId kazanan CANLI kabugu gosterir — "Ac" onu acmali.
        const id = `${backend}:${threadKey}`;
        const before = previous.get(id);
        // Damga "en son ne zaman degisti" demek. Eskiden her ornekleme now()
        // yaziliyordu: butun kartlar ayni saniyeyi gosteriyor ve saat hic
        // durmuyordu (kullanici sikayeti 02.08.2026).
        const unchanged = before
          && before.status === status
          && before.title === title
          && before.summary === summary;
        const stampMs = unchanged ? Date.parse(before.updatedAt) : now().getTime();
        const updatedAt = new Date(Math.max(stampMs || 0, activityMs(session))).toISOString();
        const operation = {
          id,
          backend,
          backendLabel: labels[backend] || backend,
          sessionId: session.id,
          cwd: String(session.cwd || ''),
          model: String(session.model || ''),
          // Kabuk kimligi ucucu; diskId (varsa) kabuk oldukten sonra da acilir.
          diskId: String(session.diskId || ''),
          title,
          summary,
          status,
          needsAttention: status === 'waiting' || status === 'failed',
          updatedAt,
        };
        current.set(operation.id, operation);
        if (!before) {
          if (status === 'running') { appendEvent(operation, 'started', status); changed = true; }
          if (status === 'waiting') { appendEvent(operation, 'attention', status); changed = true; }
          if (status === 'failed') { appendEvent(operation, 'failed', status); changed = true; }
        } else if (before.status !== status) {
          if (status === 'running') appendEvent(operation, 'started', status);
          else if (status === 'waiting') appendEvent(operation, 'attention', status);
          else if (status === 'failed') appendEvent(operation, 'failed', status);
          else if (status === 'idle' && (before.status === 'running' || before.status === 'waiting')) appendEvent(operation, 'completed', 'completed');
          changed = true;
        }
      }
    }
    // Silinen/kaybolan canlı oturum artık listSessions'ta görünmez. Önceki
    // örnekten düşüşünü bitiş olayı olarak yayınla ki telefondaki kapsül kapansın.
    for (const [id, before] of previous) {
      if (failedBackends.has(before.backend)) {
        current.set(id, before);
        continue;
      }
      if (!current.has(id) && (before.status === 'running' || before.status === 'waiting')) {
        appendEvent(before, 'completed', 'completed');
        changed = true;
      }
    }
    previous = current;
    if (changed) {
      try { writeEvents(filePath, events); } catch {}
    }
    return snapshot();
  }

  // Olay hala acilabilir bir oturuma mi isaret ediyor? Backend modulu
  // sessionExists uygulamiyorsa kayit oldugu gibi kalir (davranis degismez);
  // uygulayan backend'de silinmis/diskte olmayan oturumlar listeden duser.
  // Kullanici sikayeti 02.08.2026: opencode kayitlarina dokununca hicbir sey
  // olmuyordu, cunku kayittaki kabuk kimligi artik hicbir seye cozulmuyordu.
  function eventResolvable(event) {
    const mod = (modules || {})[event.backend];
    if (typeof mod?.sessionExists !== 'function') return true;
    try {
      return !!(mod.sessionExists(event.diskId) || mod.sessionExists(event.sessionId));
    } catch { return true; }  // kontrol patlarsa gecmisi saklamak, silmekten iyidir
  }

  function snapshot(limit = 100) {
    const operations = [...previous.values()]
      .filter(op => op.status !== 'idle')
      .sort((a, b) => Number(b.needsAttention) - Number(a.needsAttention) || b.updatedAt.localeCompare(a.updatedAt));
    const visible = events.filter(eventResolvable);
    return {
      ok: true,
      operations,
      events: visible.slice(-Math.max(1, Math.min(Number(limit) || 100, MAX_EVENTS))).reverse(),
      counts: {
        running: operations.filter(op => op.status === 'running').length,
        waiting: operations.filter(op => op.status === 'waiting').length,
        failed: operations.filter(op => op.status === 'failed').length,
      },
    };
  }

  function start(intervalMs = 2000) {
    sample();
    if (timer) clearInterval(timer);
    timer = setInterval(sample, intervalMs);
    timer.unref?.();
  }

  function stop() { if (timer) clearInterval(timer); timer = null; }

  return { sample, snapshot, start, stop };
}

