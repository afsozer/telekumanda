import fs from 'node:fs';
import { broadcast, makeThrottler, withStreamSeq } from './session-utils.mjs';

const DELTA_RING_LIMIT = 200;
const META_KEYS = [
  'running', 'awaitingApproval', 'awaitingFirstOutput', 'awaitingUserInput',
  'approval', 'contextTokens', 'contextWindow', 'cost', 'usage',
  'permissionMode', 'effort', 'cowork', 'outputs', 'init',
  'interruptStuck', 'choices', 'plan', 'planDraft', 'availableModels',
  'permissionModes', 'commands',
  // opencode ajan secimi (build/plan/yerel/…). Cip cihazlar arasi ayni degeri
  // gostersin diye delta kipinde de tasinmali.
  'agent',
  // Oto kipte turu GERCEKTEN kosan/kosacak ajan. Ayni gerekce + bir tanesi daha:
  // deger tam da tur baslarken degisiyor ve uzun kosuda tam snapshot neredeyse
  // hic gitmiyor — META_KEYS'te olmasa gosterge o anda hic guncellenmezdi.
  'resolvedAgent',
  // Görev panosu (opencode): todo listesi + bağlam doluluk yüzdesi. Uzun otonom
  // koşuda tam snapshot neredeyse hiç gitmiyor (delta ring), o yüzden panonun
  // yaşayabilmesi için ikisi de delta kipinde taşınmak ZORUNDA.
  'todos', 'contextPct',
  // Alt-ajan kartları (opencode `task`). Aynı gerekçe: kart yığınının bütün
  // değeri uzun otonom koşuda ve orada tam snapshot neredeyse hiç gitmiyor.
  'subagents',
  // agy'nin onay kartı bu alandan besleniyor. META_KEYS'te olmayan bir alan
  // delta kipinde HİÇ gönderilmez — agy delta'ya bağlanırken eklendi, yoksa
  // OAuth/login istemi telefonda hiç görünmezdi.
  'pendingPrompt',
  // opencode checkpoint geri sarma: {messageID, filesReverted, files} ya da
  // null. Sohbetin üstündeki "Geri sarıldı · Geri Al" şeridi bundan doğuyor;
  // META_KEYS'te olmasaydı delta kipinde HİÇ gelmezdi (uzun koşuda tam
  // snapshot neredeyse hiç gitmiyor — todos/contextPct'nin aynı gerekçesi).
  'reverted',
  // opencode oturum paylaşımı: opncd.ai linki ya da '' (yayında değil).
  // reverted'ın aynı gerekçesi — menüdeki "Paylaş / Paylaşımı kaldır" satırı
  // delta kipinde de doğru duruma dönsün (uzun koşuda tam snapshot neredeyse
  // hiç gitmiyor) ve başka bir cihazdan yapılan paylaşım burada da görünsün.
  'share',
];

export function reducerAction(type, payload = {}) {
  return { type, ...payload };
}

export function applyReducerActions(s, actions = []) {
  if (!s || !Array.isArray(actions)) return [];
  const applied = [];
  for (const action of actions) {
    if (!action || !action.type) continue;
    switch (action.type) {
      case 'appendMessage':
        if (action.message) { s.messages.push(action.message); ensureMessageRowIds(s); applied.push(action); }
        break;
      case 'setApproval':
        s.pendingApproval = action.approval || null;
        s.awaitingApproval = !!s.pendingApproval;
        applied.push(action);
        break;
      case 'clearApproval':
        s.pendingApproval = null;
        s.awaitingApproval = false;
        s.awaitingUserInput = false;
        applied.push(action);
        break;
      case 'setMeta':
        Object.assign(s, action.meta || {});
        applied.push(action);
        break;
      case 'turnEnded':
        s.status = 'idle';
        s.awaitingFirstOutput = false;
        applied.push(action);
        break;
      default:
        break;
    }
  }
  return applied;
}

function stableString(v) {
  try { return JSON.stringify(v ?? null); } catch { return String(v); }
}

function rowKey(row, index) {
  if (!row || typeof row !== 'object') return 'row:' + index;
  if (row.rowId) return String(row.rowId);
  if (row.id) return String(row.id);
  const role = row.role || '';
  const thought = Number.isInteger(row.thoughtIndex) ? row.thoughtIndex : '';
  return role + ':' + thought + ':' + index;
}

export function ensureMessageRowIds(s) {
  if (!s || !Array.isArray(s.messages)) return [];
  if (!Number.isInteger(s._rowSeq)) s._rowSeq = 0;
  for (const row of s.messages) {
    if (!row || typeof row !== 'object' || row.rowId) continue;
    s._rowSeq += 1;
    row.rowId = String(s.id || 'session') + ':row:' + s._rowSeq;
  }
  return s.messages;
}

function withRowIds(messages = []) {
  return messages.map((row, index) => ({ rowId: rowKey(row, index), ...row }));
}

export function snapshotForWire(raw) {
  if (!raw || typeof raw !== 'object') return raw;
  if (!Array.isArray(raw.messages)) return raw;
  return { ...raw, messages: withRowIds(raw.messages) };
}

export function paginateMessages(messages = [], { before = '', limit = 0 } = {}) {
  if (!Array.isArray(messages)) return [];
  const max = Number(limit) > 0 ? Math.min(Number(limit), 500) : 0;
  if (!before && !max) return messages;
  const rows = withRowIds(messages);
  let end = rows.length;
  if (before) {
    const idx = rows.findIndex(row => row.rowId === before);
    if (idx >= 0) end = idx;
  }
  const start = max ? Math.max(0, end - max) : 0;
  return rows.slice(start, end);
}

function safeArchiveName(id) {
  return String(id || 'session').replace(/[^a-zA-Z0-9_.-]/g, '_') + '.jsonl';
}

export function archiveMessagesToDisk(s, evicted = [], { dir } = {}) {
  if (!s || !Array.isArray(evicted) || evicted.length === 0 || !dir) return '';
  fs.mkdirSync(dir, { recursive: true });
  const file = s._messageArchiveFile || `${dir.replace(/[\\/]$/, '')}/${safeArchiveName(s.id)}`;
  const lines = evicted.map(row => JSON.stringify(row)).join('\n') + '\n';
  fs.appendFileSync(file, lines, 'utf8');
  s._messageArchiveFile = file;
  return file;
}

// Oturum tam transcript'ten YENİDEN kurulduğunda (adopt, tam re-parse, hydrate)
// çağrılır: eski arşiv dosyası silinir ki aynı satırlar her kuruluşta yeniden
// birikmesin (sayfalamada mükerrer eski mesajlar). Kaynak gerçek transcript
// olduğundan veri kaybı yoktur; kırpılanlar capSession'la yeniden arşivlenir.
export function clearArchivedMessages(s, { dir } = {}) {
  if (!s) return;
  const file = s._messageArchiveFile || (dir ? `${String(dir).replace(/[\\/]$/, '')}/${safeArchiveName(s.id)}` : '');
  if (file) { try { fs.unlinkSync(file); } catch {} }
  s._messageArchiveFile = null;
}

export function readArchivedMessages(s) {
  const file = s?._messageArchiveFile;
  if (!file) return [];
  try {
    const text = fs.readFileSync(file, 'utf8');
    return text.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line)).filter(row => row && typeof row === 'object');
  } catch {
    return [];
  }
}

export function paginateSessionMessages(s, opts = {}) {
  if (!s || !Array.isArray(s.messages)) return [];
  return paginateMessages([...readArchivedMessages(s), ...s.messages], opts);
}

export function createTurnLifecycle({
  flushThrottle = () => {},
  sendEnd = () => {},
  capSession = () => {},
  persist = () => {},
  beforeFinalize = () => {},
  afterFinalize = () => {},
  errorMessage = error => ({ role: 'agent', text: String(error || ''), time: '' }),
} = {}) {
  function finalizeTurnIdle(s, opts = {}) {
    if (!s) return;
    beforeFinalize(s, opts);
    if (s.status !== 'idle') s.status = 'idle';
    s.awaitingFirstOutput = false;
    s.interruptStuck = false;
    if (opts.error) s.messages.push(errorMessage(opts.error, s));
    for (const m of s.messages || []) delete m._open;
    capSession(s, opts);
    persist(s, opts);
    flushThrottle(s);
    afterFinalize(s, opts);
    sendEnd(s);
  }
  return { finalizeTurnIdle };
}

export function createSessionWatchdog({
  intervalMs = 30_000,
  activityTimeoutMs,
  firstOutputTimeoutMs,
  turnTimeoutMs,
  isRunning = s => s?.status === 'running',
  isPaused = s => !!s?.pendingApproval,
  onTimeout,
  log = () => {},
  now = () => Date.now(),
} = {}) {
  function disarm(s) {
    if (s?._watchdog) { clearInterval(s._watchdog); s._watchdog = null; }
  }
  function arm(s) {
    disarm(s);
    s._lastActivity = now();
    s._turnStartedAt = s._turnStartedAt || s._lastActivity;
    s._gotFirstOutput = false;
    s._watchdog = setInterval(() => {
      if (!isRunning(s)) { disarm(s); return; }
      if (isPaused(s)) return;
      const ts = now();
      if (turnTimeoutMs > 0 && s._turnStartedAt && ts - s._turnStartedAt >= turnTimeoutMs) {
        log('turn-timeout', s);
        onTimeout?.(s, 'turn');
        return;
      }
      const limit = s._gotFirstOutput ? activityTimeoutMs : firstOutputTimeoutMs;
      if (!(limit > 0) || ts - s._lastActivity < limit) return;
      log('activity-timeout', s);
      onTimeout?.(s, s._gotFirstOutput ? 'activity' : 'first-output');
    }, intervalMs);
    if (s._watchdog.unref) s._watchdog.unref();
  }
  function markActivity(s, { firstOutput = false } = {}) {
    if (!s) return;
    if (s._watchdog) s._lastActivity = now();
    if (firstOutput && !s._gotFirstOutput) {
      s._gotFirstOutput = true;
      s.awaitingFirstOutput = false;
    }
  }
  return { arm, disarm, markActivity };
}

export function createCollectionWatchdog({
  sessions,
  intervalMs = 15_000,
  isCandidate = () => false,
  isBusy = async () => false,
  onBusy = () => {},
  onProbeError = () => {},
  onTimeout = () => {},
  log = () => {},
  now = () => Date.now(),
} = {}) {
  let timer = null;
  const probing = new WeakSet();
  async function check() {
    for (const s of sessions?.values?.() || []) {
      if (!isCandidate(s, now()) || probing.has(s)) continue;
      probing.add(s);
      try {
        const busy = await isBusy(s);
        if (busy) {
          onBusy(s);
          continue;
        }
        // The probe can take longer than the watchdog interval. Activity may
        // have arrived (or the turn may have completed) while it was in flight;
        // never act on that stale pre-probe candidate decision.
        if (!isCandidate(s, now())) continue;
        log(s);
        onTimeout(s);
      } catch (error) {
        // "Could not determine" is not evidence that a live turn ended.
        // Callers may log/recover, but the watchdog must fail open here.
        onProbeError(s, error);
      } finally {
        probing.delete(s);
      }
    }
  }
  function start() {
    if (timer) return;
    timer = setInterval(() => { void check(); }, intervalMs);
    if (timer.unref) timer.unref();
  }
  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }
  return { start, stop, check };
}

export function diffSnapshots(prev, next) {
  const ops = [];
  const oldRows = Array.isArray(prev?.messages) ? prev.messages : [];
  const newRows = Array.isArray(next?.messages) ? next.messages : [];
  const oldById = new Map(oldRows.map((row, index) => [rowKey(row, index), row]));

  if (oldRows.length && newRows.length) {
    const oldFirst = rowKey(oldRows[0], 0);
    const newFirst = rowKey(newRows[0], 0);
    if (oldFirst !== newFirst && oldById.has(newFirst)) {
      ops.push({ op: 'dropRows', beforeRowId: newFirst });
    }
  } else if (oldRows.length > newRows.length) {
    const first = newRows[0] ? rowKey(newRows[0], 0) : '';
    ops.push({ op: 'dropRows', beforeRowId: first });
  }

  for (let i = 0; i < newRows.length; i++) {
    const row = newRows[i];
    const id = rowKey(row, i);
    const old = oldById.get(id);
    const rowWithId = { rowId: id, ...row };
    if (!old) {
      ops.push({ op: 'appendRow', row: rowWithId });
      continue;
    }
    if (old.text !== row.text) {
      const oldText = String(old.text || '');
      const newText = String(row.text || '');
      if (newText.startsWith(oldText)) ops.push({ op: 'appendText', rowId: id, chunk: newText.slice(oldText.length) });
      else ops.push({ op: 'patchRow', rowId: id, row: rowWithId });
    } else if (stableString(old) !== stableString(row)) {
      ops.push({ op: 'patchRow', rowId: id, row: rowWithId });
    }
  }

  const meta = {};
  for (const key of META_KEYS) {
    // A4.3: 'init' için referans eşitliği yeterlidir (her push'ta JSON.stringify atlanır)
    if (key === 'init') {
      if (prev?.[key] !== next?.[key]) meta[key] = next?.[key] ?? null;
    } else if (stableString(prev?.[key]) !== stableString(next?.[key])) {
      meta[key] = next?.[key] ?? null;
    }
  }
  if (Object.keys(meta).length) ops.push({ op: 'setMeta', meta });
  return ops;
}

function rememberDelta(s, delta) {
  if (!s || !delta || !Array.isArray(delta.ops) || delta.ops.length === 0) return;
  if (!Array.isArray(s._deltaRing)) s._deltaRing = [];
  s._deltaRing.push(delta);
  if (s._deltaRing.length > DELTA_RING_LIMIT) s._deltaRing.splice(0, s._deltaRing.length - DELTA_RING_LIMIT);
}

function rememberSnapshotBase(s, seq, snapshot) {
  if (!s || !Number.isFinite(Number(seq)) || !snapshot) return;
  if (!Array.isArray(s._snapshotRing)) s._snapshotRing = [];
  s._snapshotRing.push({ seq: Number(seq), snapshot });
  if (s._snapshotRing.length > 20) s._snapshotRing.splice(0, s._snapshotRing.length - 20);
}

export function createAgentSessionCore({ snapshot, deltaRingLimit = DELTA_RING_LIMIT } = {}) {
  if (typeof snapshot !== 'function') throw new Error('snapshot function required');

  function buildSnapshot(s) {
    ensureMessageRowIds(s);
    return snapshotForWire(snapshot(s));
  }

  function pushSnapshot(s) {
    // A2 sigorta: capMessages baştan kırptıysa indeks-tabanlı rowId'ler kaymıştır —
    // diff güvenilmezdir. Halkayı boşalt ve BU push'u delta abonelerine de full
    // snapshot olarak gönder (istemci 'conversation' tipinde listeyi baştan kurar).
    // Diff'i null tabana karşı çalıştırmak dev bir appendRow delta'sı üretir ve
    // istemcide tüm satırları KOPYALAR; o yüzden dirty'de diff tamamen atlanır.
    const dirty = !!s._deltaDirty;
    const prevSeq = Number.isFinite(s._seq) ? s._seq : 0;
    if (dirty) {
      s._deltaDirty = false;
      if (Array.isArray(s._deltaRing)) s._deltaRing.length = 0;
      if (Array.isArray(s._snapshotRing)) s._snapshotRing.length = 0;
      s._deltaReplaySince = prevSeq + 1;
      s._deltaRingTrimmed = true;
    }
    const raw = buildSnapshot(s);
    const ops = dirty ? [] : diffSnapshots(s._lastWireSnapshot, raw);
    s._lastWireSnapshot = raw;
    const changed = dirty || ops.length > 0 || prevSeq === 0;
    const seq = changed ? prevSeq + 1 : prevSeq;
    s._seq = seq;
    const full = { ...raw, seq };
    const delta = ops.length ? { type: 'delta', sessionId: s.id, ops, seq } : null;
    if (delta) {
      if ((!Array.isArray(s._deltaRing) || s._deltaRing.length === 0) && !Number.isFinite(s._deltaReplaySince)) {
        s._deltaReplaySince = prevSeq;
        s._deltaRingTrimmed = false;
      }
      rememberDelta(s, delta);
      if (Array.isArray(s._deltaRing) && s._deltaRing.length > deltaRingLimit) {
        const removed = s._deltaRing.splice(0, s._deltaRing.length - deltaRingLimit);
        const lastRemoved = removed[removed.length - 1];
        if (lastRemoved && Number.isFinite(Number(lastRemoved.seq))) {
          const removedSeq = Number(lastRemoved.seq);
          s._deltaReplaySince = removedSeq;
          if (Array.isArray(s._snapshotRing)) s._snapshotRing = s._snapshotRing.filter(item => Number(item.seq) > removedSeq);
        }
        if (removed.length) s._deltaRingTrimmed = true;
      }
    }
    for (const ws of s.subscribers || []) {
      // A4.1: delta-mode aboneye ops boşken hiçbir şey gönderme; dirty'de full resync.
      if (ws._deltaMode && !dirty) {
        if (delta) try { ws.send(JSON.stringify(delta)); } catch {}
      } else {
        try { ws.send(JSON.stringify(full)); } catch {}
      }
    }
  }

  const { throttledPush, flushThrottle } = makeThrottler(pushSnapshot);

  function subscribe(s, ws, { since = null, delta = false } = {}) {
    if (!s) {
      try { ws.send(JSON.stringify({ type: 'error', error: 'session not found' })); ws.close(); } catch {}
      return;
    }
    const sinceNum = Number(since);
    const hasSince = since !== null && since !== '' && Number.isFinite(sinceNum) && sinceNum > 0;
    const wantsDelta = delta === true || hasSince;
    const ring = Array.isArray(s._deltaRing) ? s._deltaRing : [];
    const replay = hasSince ? ring.filter(d => Number(d.seq) > sinceNum) : [];
    const oldestSeq = ring.length ? Number(ring[0].seq) : null;
    const newestSeq = ring.length ? Number(ring[ring.length - 1].seq) : Number(s._seq || 0);
    // A3: istemci seq'i bu sunucu ömründen büyükse (bridge restart) full snapshot'a düş
    const clientAhead = hasSince && sinceNum > (s._seq || 0);
    const replaySince = Number.isFinite(s._deltaReplaySince) ? Number(s._deltaReplaySince) : (oldestSeq != null ? oldestSeq - 1 : newestSeq);
    const gapReplaySince = (!s._deltaRingTrimmed && oldestSeq != null) ? Math.min(replaySince, oldestSeq - 2) : replaySince;
    const canReplay = !clientAhead && hasSince && (
      (replay.length > 0 && sinceNum >= gapReplaySince) ||
      (replay.length === 0 && sinceNum >= newestSeq)
    );
    ws._deltaMode = wantsDelta;
    if (hasSince && canReplay) {
      for (const delta of replay) {
        try { ws.send(JSON.stringify(delta)); } catch {}
      }
    } else {
      // A4.2: buildSnapshot'ı bir kez üret, iki yerde kullanma
      const base = hasSince && !clientAhead && Array.isArray(s._snapshotRing)
        ? [...s._snapshotRing].reverse().find(item => Number(item.seq) === sinceNum)
        : null;
      if (base) {
        const snap = buildSnapshot(s);
        const ops = diffSnapshots(base.snapshot, snap);
        if (ops.length) {
          let seq = Number(s._seq || 0);
          if (seq <= sinceNum) { seq = sinceNum + 1; s._seq = seq; }
          try { ws.send(JSON.stringify({ type: 'delta', sessionId: s.id, ops, seq })); } catch {}
          s._lastWireSnapshot = snap;
          s.subscribers.add(ws);
          ws.on('close', () => s.subscribers.delete(ws));
          ws.on('error', () => s.subscribers.delete(ws));
          return;
        }
      }
      if (hasSince && !clientAhead && !s._deltaRingTrimmed) {
        const snap = buildSnapshot(s);
        const ops = diffSnapshots(null, snap);
        if (ops.length) {
          let seq = Number(s._seq || 0);
          if (seq <= sinceNum) { seq = sinceNum + 1; s._seq = seq; }
          try { ws.send(JSON.stringify({ type: 'delta', sessionId: s.id, ops, seq })); } catch {}
          s._lastWireSnapshot = snap;
          s.subscribers.add(ws);
          ws.on('close', () => s.subscribers.delete(ws));
          ws.on('error', () => s.subscribers.delete(ws));
          return;
        }
      }
      const snap = buildSnapshot(s);
      const full = withStreamSeq(s, snap);
      if (Number.isFinite(Number(full.seq))) {
        const seq = Number(full.seq);
        if (!Array.isArray(s._deltaRing) || s._deltaRing.length === 0 || !Number.isFinite(s._deltaReplaySince)) {
          s._deltaReplaySince = seq;
          s._deltaRingTrimmed = false;
        }
      }
      rememberSnapshotBase(s, full.seq, snap);
      try { ws.send(JSON.stringify(full)); } catch {}
      s._lastWireSnapshot = snap;
    }
    s.subscribers.add(ws);
    ws.on('close', () => s.subscribers.delete(ws));
    ws.on('error', () => s.subscribers.delete(ws));
  }

  function sendEnd(s) {
    broadcast(s, { type: 'end', sessionId: s.id });
  }

  return { buildSnapshot, pushSnapshot, throttledPush, flushThrottle, subscribe, sendEnd };
}

function readJsonFile(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return {}; }
}

function writeJsonFile(file, data) {
  fs.mkdirSync(file.replace(/[\\/][^\\/]+$/, ''), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 2));
}

export function createShellPersistence({
  file,
  sessions,
  serializeSession,
  restoreSession,
  disabled = !!process.env.NODE_TEST_CONTEXT,
  max = 100,
  debounceMs = 500,
  log = () => {},
} = {}) {
  if (!file || !sessions || typeof serializeSession !== 'function' || typeof restoreSession !== 'function') {
    throw new Error('file, sessions, serializeSession and restoreSession are required');
  }
  let timer = null;

  function persist() {
    if (disabled) return;
    if (timer) return;
    timer = setTimeout(() => {
      timer = null;
      const rows = [...sessions.values()]
        .map(serializeSession)
        .filter(Boolean)
        .sort((a, b) => (b.lastActivity || 0) - (a.lastActivity || 0))
        .slice(0, max);
      writeJsonFile(file, { sessions: rows });
    }, debounceMs);
    if (timer.unref) timer.unref();
  }

  function restoreFromData(data) {
    const rows = Array.isArray(data?.sessions) ? data.sessions : [];
    let restored = 0;
    for (const row of rows) {
      if (!row || !row.id || sessions.has(row.id)) continue;
      const shell = restoreSession(row);
      if (!shell) continue;
      sessions.set(shell.id || row.id, shell);
      restored++;
    }
    return restored;
  }

  function restoreFromDisk() {
    if (disabled) return 0;
    try {
      const restored = restoreFromData(readJsonFile(file));
      if (restored) log('restored', { count: restored });
      return restored;
    } catch (e) {
      log('restore failed', { error: e.message });
      return 0;
    }
  }

  return { persist, restoreFromData, restoreFromDisk };
}
