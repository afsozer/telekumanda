// Codex app-server backend — long-lived `codex app-server --listen stdio://` JSON-RPC.
//
// Newest sibling to bridge/codex.mjs (which spawns `codex exec` per prompt).
// This backend holds ONE persistent app-server child and multiplexes several
// Codex threads through it, talking its NDJSON (newline-delimited JSON-RPC)
// protocol. Phone-facing export contract intentionally mirrors codex.mjs
// (MODELS / newSession / prompt / getConversation / getThought / stop /
// subscribe / listSessions / listDiskSessions / adoptSession /
// runningSessionId) so server.mjs can register it identically. EXTRA exports
// (approve / respondUserInput / compact / setModel) power the approval-card
// flow that the legacy codex backend could not provide.
//
// PROTOCOL NOTES (verified by generating the schema with
// `codex app-server generate-json-schema` and a live initialize→thread/start→
// turn/start spike against codex-cli 0.142.2):
//   • Transport framing is NDJSON: one JSON-RPC message per line. `jsonrpc`
//     field is NOT required (server rejects an extra `jsonrpc` field on a
//     request that has both `id` and `method`? — no, it just ignores it; we
//     omit it to match server output exactly).
//   • `initialize` is called once per process lifetime; a second call returns
//     {error:{code:-32600,"Already initialized"}}. We guard with initializedAt.
//   • Method names verified against ClientRequest.ts (note `thread/inject_items`
//     uses an underscore, not `thread/inject-items`).
//   • Server-request approval responses use a per-method decision enum; the
//     decision variants we send back are taken straight from the generated
//     JSON schema (CommandExecution/FileChange: accept|decline|cancel|...;
//     applyPatchApproval/execCommandApproval: approved|denied|abort; MCP
//     elicitation: accept|decline|cancel; permissions: {permissions,...};
//     tool user-input: {answers:{id:{answers:[...]}}}).
//   • Notifications routed by `params.threadId`; some (remoteControl/*) carry
//     no threadId and are not session-scoped.
//   • Error response shape on a request: `{ "id": n, "error": { code, message } }`
//     — no `result` field. We translate that to a rejected promise.
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { DatabaseSync } from 'node:sqlite';
import { hhmm, capMessages, capToolDetails, appendToolDetail, pruneEvictedThoughtDetail, logWarn, killChildTree, killChildTreeSync, spawnResolvedExecutable, spawnResolvedAsync, matchTranscriptMessages } from './session-utils.mjs';
import { archiveMessagesToDisk, clearArchivedMessages, createAgentSessionCore, createCollectionWatchdog, createTurnLifecycle, ensureMessageRowIds, paginateSessionMessages } from './agent-session-core.mjs';
import { dispatchCodexNotification } from './codex-app-adapter.mjs';
import { readSkillCatalog } from './skills.mjs';

const SESSIONS_DIR = path.join(os.homedir(), '.codex', 'sessions');
const CODEX_STATE_DB = path.join(os.homedir(), '.codex', 'state_5.sqlite');
const REQ_TIMEOUT_MS = 30_000;       // single JSON-RPC request fails-open timeout

// Inactivity watchdog (opencode-app deseninin codex-app karsiligi):
// session/prompt response'u kaybolursa ya da app-server sessiz kalirsa turn
// sonsuza kadar 'running' kalmasin. Son N ms hicbir item/turn eventi gelmediyse
// turn'u finalize et. (NOT: awaitingApproval/awaitingUserInput iken calismaz.)
const INACTIVITY_MS = 120_000;
const WATCHDOG_TICK_MS = 15_000;
// "Değişiklikler" kırpma sınırları (gerekçeler sessionDiff başlığında).
const DIFF_PATCH_LIMIT = 100 * 1024;             // dosya başına ~100KB — opencode-app ile aynı söz
const CODEX_DIFF_BYTE_BUDGET = 4 * 1024 * 1024;  // oturum başına toplam yama belleği
const DIFF_FILE_LIMIT = 300;                     // dosya sayısı tavanı (opencode-app ile aynı)
// Codex'in kendisi CODEX_HOME'u okuyor; kopru de ayni yere baksin ki kullanici
// codex home'unu tasidiginda profil/config okumalari kaymasin.
const CODEX_HOME = process.env.CODEX_HOME || path.join(os.homedir(), '.codex');
const BRIDGE_STATE_DIR = process.env.NODE_TEST_CONTEXT
  ? path.join(os.tmpdir(), 'agentbridge-codex-test-' + process.pid)
  : CODEX_HOME;
const BRIDGE_MESSAGE_ARCHIVE_DIR = path.join(BRIDGE_STATE_DIR, 'bridge-message-archive');

// Sandbox / approval policies for the new thread. Default to "YOLO"-equivalent so
// app-server behaves like codex.mjs's --dangerously-bypass-approvals-and-sandbox.
// Caller can override via newSession({ permissionMode: 'ask' }) etc.
const DEFAULT_SANDBOX_MODE = 'danger-full-access';
const DEFAULT_APPROVAL_POLICY = 'never';
const DEFAULT_SANDBOX_POLICY = { type: 'dangerFullAccess' };

// Seçilebilir GPT-6 kuşağı modelleri. Eski kuşak modeller menüde gösterilmez.
// Sıra app-server'ın model/list sırasını izler (en yeni kuşak üstte).
// NOT: bu liste yalnız SEÇİM menüsüdür. Eskiden başka modelle açılmış bir oturum
// resume edilirse modeli kendi kaydından gelir, burada olmaması onu kırmaz.
export const CODEX_APP_MODELS = [
  { label: 'GPT-6.1 Sol', id: 'gpt-6.1-sol' },
  { label: 'GPT-6 Astra', id: 'gpt-6-astra' },
  { label: 'GPT-6 Luna', id: 'gpt-6-luna' },
];
export const MODELS = CODEX_APP_MODELS;

// Tek model-default kaynağı (madde 7): cowork.mjs ve Android buradan okur.
// Codex config.toml varsayılanı kullanılır; seçiciden kaldırılmış GPT-5.6
// modelleri veya okunamayan config için desteklenen bir yerleşiğe düşülür.
const FALLBACK_MODEL = 'gpt-6.1-sol';
const isRetiredModel = id => /^gpt-5\.6(?:-|$)/i.test(String(id || ''));
export function defaultModel() {
  const configured = readTomlScalar(readCodexConfigText(), 'model');
  return configured && !isRetiredModel(configured) ? configured : FALLBACK_MODEL;
}

// Yeni oturumun başlangıç effort'u. Boş string "default" demek: turn/start'a
// effort gönderilmez, codex config.toml'daki model_reasoning_effort'u uygular.
// Kullanıcı kararı (03.09.2026): oturum 'max' ile değil "default" ile açılsın;
// config'de bir default YOKSA 'high'. (Eskiden burada açıkça 'max' seçiliyordu.)
export function defaultEffort() { return readDefaultEffort() ? '' : 'high'; }

// ── Single long-lived app-server child ─────────────────────────────────────
let appServer = null;
let appServerBuf = '';
let nextReqId = 1;
let initialized = false;
let initializing = null; // Promise during ensureAppServer->initialize
const pendingReqs = new Map(); // id -> { resolve, reject, dirty }
let appEpoch = 0; // bumps when app-server dies; ids from old epoch are stale
let appServerStderrTail = ''; // stderr ring buffer (~4KB); attached to close/finalize error messages
let appServerStartedAt = 0;
let spawnFailures = 0;
let nextSpawnAllowedAt = 0;
let consecutiveTimeouts = 0;
let lastTimeoutStrikeAt = null;
let timeoutKillEpoch = -1;
let timeoutDeferredEpoch = -1;
let warmupInFlight = null;
const MAX_STREAM_DETAIL_CHARS = 64_000;
const MAX_ROLLOUT_JSON_LINE_CHARS = 2 * 1024 * 1024;
const TIMEOUT_STRIKE_GAP_MS = Math.max(5_000, Math.floor(REQ_TIMEOUT_MS / 2));

const sessions = new Map();          // phone-facing uuid -> session
const sessionsByThread = new Map();  // codex threadId -> session

function ensureAppServer() {
  if (appServer && !appServer.stdin.destroyed && !appServer.exitCode) return appServer;
  const now = Date.now();
  if (nextSpawnAllowedAt > now) {
    const waitSec = Math.ceil((nextSpawnAllowedAt - now) / 1000);
    throw new Error('codex app-server restart backoff active (' + waitSec + 's)');
  }
  const child = spawnResolvedExecutable('codex', ['app-server', '--listen', 'stdio://'], {
    cwd: os.homedir(),
    windowsHide: true,
    env: process.env,
  }, 'AGENTBRIDGE_CODEX_BIN');
  appServer = child;
  appServerBuf = '';
  appServerStderrTail = '';
  appServerStartedAt = Date.now();
  initialized = false;
  initializing = null;
  child.stdout.setEncoding('utf-8');
  child.stdout.on('data', onAppServerData);
  child.stderr.on('data', d => { appServerStderrTail = (appServerStderrTail + d.toString()).slice(-4096); });
  child.on('error', e => logWarn('codex-app', 'app-server spawn hatası', { error: e.message }));
  child.on('close', code => onAppServerClose(code));
  return child;
}

// One-LAST initialize handshake. After the first initialize response we cache
// a flag; subsequent spawn cycles (post-crash) start a new process and require
// a fresh initialize.
function ensureInitialized() {
  if (initialized) return Promise.resolve();
  ensureAppServer();
  if (initializing) return initializing;
  initializing = appSend('initialize', {
    clientInfo: { name: 'agentbridge-codex-app', title: null, version: '1.0.0' },
    capabilities: null,
  }).then(() => { initialized = true; initializing = null; spawnFailures = 0; nextSpawnAllowedAt = 0; }, e => {
    initializing = null;
    throw e;
  });
  return initializing;
}

// Android'de açık Codex sekmesi varken ortak app-server'ı sıcak tut. model/list
// salt-okuma ping'idir; süreç düştüyse initialize eder, ayaktaysa idle süresini
// gerçek bir protokol isteğiyle tazeler.
export function warmup() {
  if (warmupInFlight) return warmupInFlight;
  const task = (async () => {
    await ensureInitialized();
    await appSend('model/list', {});
    return { ok: true, pid: appServer?.pid || null };
  })().finally(() => {
    if (warmupInFlight === task) warmupInFlight = null;
  });
  warmupInFlight = task;
  return warmupInFlight;
}

function onAppServerData(chunk) {
  appServerBuf += chunk;
  let i;
  while ((i = appServerBuf.indexOf('\n')) >= 0) {
    const line = appServerBuf.slice(0, i).trim();
    appServerBuf = appServerBuf.slice(i + 1);
    if (!line) continue;
    let m;
    try { m = JSON.parse(line); } catch { /* ignore malformed line */ continue; }
    try { routeAppMessage(m); } catch (e) { logWarn('codex-app', 'route error', { error: String(e && e.message), line: line.slice(0, 200) }); }
  }
}

// Dispatch one parsed JSON-RPC message to one of:
//   1. Response (has id, we have a pending request for it)
//   2. Server request (has id + method, expects a reply) — approval / user-input
//   3. Notification (has method, no id) — item/turn/thread event stream
function routeAppMessage(m) {
  if (m.id != null && pendingReqs.has(m.id)) {
    const p = pendingReqs.get(m.id);
    pendingReqs.delete(m.id);
    if (p.timer) clearTimeout(p.timer);
    consecutiveTimeouts = 0;
    lastTimeoutStrikeAt = null;
    timeoutDeferredEpoch = -1;
    if (m.error) p.reject(m.error);
    else p.resolve(m.result);
    return;
  }
  if (m.id != null && m.method != null) {
    handleServerRequest(m);
    return;
  }
  if (m.method != null) {
    handleNotification(m);
    return;
  }
  // Unknown stray message: ignore.
}

// Send a JSON-RPC request and resolve its result / reject its error.
function appSend(method, params) {
  return new Promise((resolve, reject) => {
    if (!appServer || !appServer.stdin || appServer.stdin.destroyed || appServer.exitCode != null) {
      reject(new Error('codex app-server kapalı')); return;
    }
    const id = nextReqId++;
    const line = JSON.stringify({ id, method, params }) + '\n';
    pendingReqs.set(id, { resolve, reject, method });
    try { appServer.stdin.write(line); }
    catch (e) { pendingReqs.delete(id); reject(e); return; }
    const t = setTimeout(() => {
      if (pendingReqs.has(id)) {
        pendingReqs.delete(id);
        const recovery = timeoutRecoveryDecision({
          now: Date.now(),
          lastStrikeAt: lastTimeoutStrikeAt,
          strikes: consecutiveTimeouts,
          hasRunningTurns: [...sessions.values()].some(s => s.status === 'running'),
          killedForEpoch: timeoutKillEpoch === appEpoch,
        });
        consecutiveTimeouts = recovery.strikes;
        lastTimeoutStrikeAt = recovery.lastStrikeAt;
        const err = new Error('request timeout: ' + method);
        err.code = 'ETIMEOUT';
        err.method = method;
        reject(err);
        if (recovery.defer && timeoutDeferredEpoch !== appEpoch) {
          timeoutDeferredEpoch = appEpoch;
          logWarn('codex-app', 'app-server timeout self-heal deferred; active turn preserved', { method, consecutiveTimeouts });
        }
        if (recovery.kill && appServer && appServer.pid) {
          timeoutKillEpoch = appEpoch;
          logWarn('codex-app', 'app-server timeout self-heal kill', { method, consecutiveTimeouts });
          try { killChildTree(appServer.pid); } catch {}
        }
      }
    }, REQ_TIMEOUT_MS);
    const p = pendingReqs.get(id);
    if (p) p.timer = t;
    if (t.unref) t.unref();
  });
}

function timeoutRecoveryDecision({
  now,
  lastStrikeAt,
  strikes,
  hasRunningTurns,
  killedForEpoch,
}) {
  const countStrike = !Number.isFinite(lastStrikeAt) || (now - lastStrikeAt) >= TIMEOUT_STRIKE_GAP_MS;
  const nextStrikes = countStrike ? strikes + 1 : strikes;
  const thresholdReached = nextStrikes >= 3;
  return {
    strikes: nextStrikes,
    lastStrikeAt: countStrike ? now : lastStrikeAt,
    defer: thresholdReached && hasRunningTurns,
    kill: thresholdReached && !hasRunningTurns && !killedForEpoch,
  };
}

export function __testTimeoutRecoveryDecision(input) {
  return timeoutRecoveryDecision(input);
}

// Reply to a server-initiated request (approval / user-input) with a result.
function appRespond(id, result) {
  if (!appServer || !appServer.stdin || appServer.stdin.destroyed) return;
  try { appServer.stdin.write(JSON.stringify({ id, result }) + '\n'); }
  catch (e) { logWarn('codex-app', 'appRespond yazılamadı', { error: e.message }); }
}

// Reply with a JSON-RPC error (used when an approval response cannot be built).
function appReject(id, code, message, data) {
  if (!appServer || !appServer.stdin || appServer.stdin.destroyed) return;
  const err = { code, message };
  if (data !== undefined) err.data = data;
  try { appServer.stdin.write(JSON.stringify({ id, error: err }) + '\n'); }
  catch (e) { logWarn('codex-app', 'appReject yazılamadı', { error: e.message }); }
}

function onAppServerClose(code) {
  const tail = appServerStderrTail ? appServerStderrTail.slice(-500) : '';
  const ageMs = Date.now() - (appServerStartedAt || Date.now());
  if (code !== 0 && ageMs < 10_000) {
    spawnFailures++;
    nextSpawnAllowedAt = Date.now() + Math.min((2 ** spawnFailures) * 1000, 60_000);
  }
  logWarn('codex-app', 'app-server kapandı', { code, stderr: tail });
  appServer = null; appServerBuf = ''; initialized = false; initializing = null;
  consecutiveTimeouts = 0; lastTimeoutStrikeAt = null; timeoutKillEpoch = -1; timeoutDeferredEpoch = -1;
  warmupInFlight = null; appServerStderrTail = '';
  appEpoch++;
  sessionsByThread.clear();
  for (const s of sessions.values()) {
    if (s.status === 'running') finalizeTurnIdle(s, { error: '[codex-app] app-server süreci kapandı' + (tail ? ' (stderr: ' + tail + ')' : '') + '.' });
    else if (s.threadId) {
      // Idle sessions with threadId can lazily resume on next prompt.
      // Reset turn state to avoid stale references.
      s._booting = false;
      s._turnId = null;
      resetTurnBuffers(s);
    }
  }
  for (const [id, p] of pendingReqs) { pendingReqs.delete(id); if (p.timer) clearTimeout(p.timer); p.reject(new Error('codex app-server closed')); }
}

function stderrSuffix(tail) {
  const s = String(tail || '').trim().slice(-500);
  return s ? ': ' + s : '';
}

/**
 * Telde "kullanicidan cevap bekleniyor" TEK bayraktir: awaitingApproval.
 *
 * codex-app iceride onay (awaitingApproval) ile soru/elicitation
 * (awaitingUserInput) ayrimini tutuyor, ama istemci sozlesmesi claude-app'ten
 * geliyor ve orada tek bayrak var. Ikisini birlestirmemek canlida sunu yapti
 * (2 Agu 2026): soru geldiginde awaitingApproval=false gidiyor, istemci de
 * "false -> approval yok" diye yuku atiyordu — codex sorulari hic gorunmedi.
 * listSessions() zaten birlesik bicimi kullaniyordu; sekme noktasi yaniyor,
 * sohbet ekrani bos kaliyordu.
 */
function awaitingAnswer(s) {
  return !!(s.awaitingApproval || s.awaitingUserInput);
}

// -- Session shape ──────────────────────────────────────────────────────────
export function snapshot(s) {
  return {
    type: 'conversation', sessionId: s.id, messages: s.messages,
    running: s.status === 'running', awaitingApproval: awaitingAnswer(s),
    awaitingFirstOutput: !!s.awaitingFirstOutput,
    awaitingUserInput: !!s.awaitingUserInput,
    contextTokens: s.contextTokens || 0, contextWindow: s.contextWindow || 0,
    approval: awaitingAnswer(s) ? s.pendingApproval || null : null,
    plan: Array.isArray(s._plan) ? s._plan.map(p => ({ text: String(p.text || ''), status: String(p.status || 'pending'), itemId: String(p.itemId || ''), turnId: String(p.turnId || '') })) : [],
    planDraft: s._planDraft || '',
    effort: s.effort || '',   // UI mevcut çaba seviyesini gösterebilsin (claude-app ile aynı sözleşme)
    goal: s.goal || null,     // thread hedefi (objective/status/token bütçesi) — listSessions'a KOYMA, liste şişmesin
  };
}

// Pure check for codexapp-spike-* temp test session cwds. Kept exported so
// listDiskSessions() and the unit tests share one definition (task #6).
export function isSpikeCwd(cwd) {
  return /codexapp-spike-/i.test(String(cwd || ''));
}

// Test-only helper: apply one app-server notification message to the named
// session through the same reducer used at runtime. No app-server spawn is
// required; only sessions created via newSession/adoptSession are visible.
// Returns true when a session was found and the notification routed.
export function __testApplyNotification(sessionId, message) {
  const s = sessions.get(sessionId);
  if (!s) return false;
  // Ensure the threadId<-session lookup table has an entry for this session so
  // handleNotification's thread-keyed dispatch can find it. If the test session
  // has no real threadId yet (typical for newSession() in pure unit tests), we
  // synthesize one and inject it into the message params so the reducer sees a
  // matching thread binding.
  let tid = s.threadId;
  if (!tid) {
    tid = 'test-thread-' + s.id;
    s.threadId = tid;
    sessionsByThread.set(tid, s);
  } else {
    sessionsByThread.set(tid, s);
  }
  const m = message && typeof message === 'object' ? { ...message, params: { ...((message.params && typeof message.params === 'object') ? message.params : {}), threadId: (message.params && message.params.threadId) || tid } } : { method: '', params: { threadId: tid } };
  try { handleNotification(m); } catch { /* swallow test-time parse errors */ }
  return true;
}
// Test-only helper for the real thread-keyed notification router. Unlike
// __testApplyNotification it does not alter the thread-to-session binding.
export function __testRouteNotification(message) {
  try { handleNotification(message || {}); return true; }
  catch { return false; }
}
// Test-only: server-request (onay / soru) yönlendiricisi. __testRouteNotification
// ile aynı mantık, thread bağlamasına dokunmaz.
export function __testRouteServerRequest(message) {
  try { handleServerRequest(message || {}); return true; }
  catch { return false; }
}
// Test-only: bir kabuğun bekleyen onay kaydını okur. getConversation threadId'li
// kabukta hydrate tetikleyip gerçek app-server spawn ettiği için saf testlerde
// kullanılamıyor.
export function __testPendingApproval(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return null;
  return {
    awaitingApproval: !!s.awaitingApproval,
    awaitingUserInput: !!s.awaitingUserInput,
    requestId: s.pendingApproval ? s.pendingApproval.requestId : null,
    kind: s.pendingApproval ? s.pendingApproval.kind : null,
  };
}
// Test-only: bir kabuğun goal'ünü okur. snapshot(s) session NESNESI ister ve
// getConversation threadId'li kabukta hydrate → gerçek spawn tetikler; saf testte
// bildirim sonrası goal'ü doğrulamak için bu okuyucu spawn'suz.
export function __testGoal(sessionId) {
  const s = sessions.get(sessionId);
  return s ? (s.goal || null) : null;
}
// Test-only: transcript'i hydrate tetiklemeden okur (gerekce __testGoal ile ayni —
// getConversation threadId'li kabukta gercek app-server spawn eder).
export function __testMessages(sessionId) {
  const s = sessions.get(sessionId);
  return s && Array.isArray(s.messages) ? s.messages : [];
}
// Test kancası: "geçmiş rollout'tan kuruldu" bayrağı. Gerçek yazarları
// hydrateHistoryFromRollout ve adoptSession'ın rollout fallback'i; ikisi de
// canlı app-server ya da diskte gerçek bir rollout istediği için testte
// çağrılamıyor. Kilitlenen şey bayrağın YÜKE TAŞINMASI.
export function __testSetDiffHistoryGap(sessionId, value) {
  const s = sessions.get(sessionId);
  if (!s) return false;
  s._diffHistoryGap = !!value;
  return true;
}
export function __testAppendThreadItem(sessionId, item, turnId) {
  const s = sessions.get(sessionId);
  if (!s) return false;
  appendThreadItemToMessages(s, item, undefined, turnId);
  return true;
}
const sessionCore = createAgentSessionCore({ snapshot });
const { pushSnapshot, throttledPush, flushThrottle } = sessionCore;
const turnLifecycle = createTurnLifecycle({
  flushThrottle,
  sendEnd: s => sessionCore.sendEnd(s),
  capSession,
  // claude-app'te olan, burada UNUTULAN kanca. Tur bitince kalicilastirilmayinca
  // _lastActivity diske yalniz prompt()/newSession gibi yollarda yaziliyordu;
  // kopru restart edilince oturum listesi son yazma anina geri sariyor ve oturum
  // "saatlerdir sessiz" gibi siralaniyordu. app-server'in kendi baslattigi turlerde
  // (goal) prompt() hic cagrilmadigi icin fark daha da buyuk: 80 dakikalik kosu
  // listeye hic yansimadi (canli sikayet 02.08.2026).
  persist: () => persistSessions(),
  beforeFinalize: s => {
    if (s.pendingApproval || s.awaitingApproval || s.awaitingUserInput) clearPendingApproval(s);
  },
  afterFinalize: s => pushSnapshot(s),
  errorMessage: error => ({ role: 'agent', text: String(error || ''), time: hhmm() }),
});
const threadResolvedListeners = new Set();
const notificationHandlers = {
  onTurnError,
  onTurnStarted,
  onTurnCompleted,
  onThreadStarted: onThreadStartedNf,
  onThreadStatusChanged,
  onTokenUsage,
  onItemStarted,
  onItemCompleted,
  onAgentMessageDelta,
  onPlanDelta,
  onReasoningDelta,
  onStreamDelta,
  onTurnPlanUpdated,
  onContextCompacted,
  onServerRequestResolved,
  onThreadClosed,
  onGoalUpdated,
  onGoalCleared,
};

function capSession(s) {
  ensureMessageRowIds(s);
  const evicted = capMessages(s, undefined, msg => pruneEvictedThoughtDetail(s, msg));
  archiveMessagesToDisk(s, evicted, { dir: BRIDGE_MESSAGE_ARCHIVE_DIR });
  if (evicted.some(msg => !msg?.rowId)) s._deltaDirty = true;
  capToolDetails(s);
}

export function onThreadResolved(fn) {
  if (typeof fn !== 'function') return () => {};
  threadResolvedListeners.add(fn);
  return () => threadResolvedListeners.delete(fn);
}

function emitThreadResolved(s) {
  if (!s || !s.threadId) return;
  const payload = {
    sessionId: s.id,
    threadId: s.threadId,
    cwd: s.cwd,
    model: s.model,
    permissionMode: s.permissionMode,
  };
  for (const fn of threadResolvedListeners) {
    try { fn(payload); } catch (e) { logWarn('codex-app', 'threadResolved listener failed', { error: errMessage(e) }); }
  }
}

// codex app-server TurnStartParams.effort: serbest string (ReasoningEffort); geçerli
// küme MODELE göre değişir (model/list → supportedReasoningEfforts; ör. gpt-5.6-sol
// minimal desteklemez: low/medium/high/xhigh/max/ultra). Boş = config.toml default'u.
// Sıralı liste hem sanitizasyon hem clampEffortForModel'deki "en yakın seviye"
// hesabı içindir; model bazlı doğrulamanın kaynağı model kataloğudur (aşağıda).
const CODEX_EFFORT_ORDER = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'];
function normalizeCodexEffort(e) {
  const v = String(e || '').toLowerCase().trim();
  return CODEX_EFFORT_ORDER.includes(v) ? v : '';
}
// Katalog henüz çekilmemişken UI'ya sunulan güvenli kesişim (tüm güncel modeller destekler).
export const CODEX_APP_EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh'];
// model/list YALNIZ yerleşik sağlayıcının modellerini döndürüyor; özel sağlayıcı
// profillerinin modeli orada hiç görünmüyor. O modellerde UI yukarıdaki statik
// kesişime düşüyordu: config.toml default'u 'max' iken menüde max HİÇ yoktu
// (canlı görüldü — çip "default·max" diyor ama elle seçilemiyor). Kataloğu
// olmayan modelde clamp da yapılmadığı için tam merdiveni sunmak güvenli.
const CODEX_UNKNOWN_MODEL_EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];

// ── Model kataloğu (model/list) ─────────────────────────────────────────────
// Model başına desteklenen effort kümesi ve modelin kendi default effort'u
// app-server'dan gelir; statik CODEX_APP_MODELS yalnız model SEÇİM listesi
// fallback'idir. Katalog epoch'a bağlı tazelenir; bayat katalog clamp için yine
// de kullanılır (destek kümeleri sık değişmez, hiç yoktan iyidir).
let modelCatalog = null;       // Map<modelId, { label, efforts: string[], defaultEffort }>
let modelCatalogEpoch = -1;
let modelCatalogFetch = null;
async function ensureModelCatalog() {
  if (modelCatalog && modelCatalogEpoch === appEpoch) return modelCatalog;
  if (modelCatalogFetch) return modelCatalogFetch;
  modelCatalogFetch = (async () => {
    await ensureInitialized();
    const epoch = appEpoch;
    const r = await appSend('model/list', {});
    const map = new Map();
    for (const m of (Array.isArray(r?.data) ? r.data : [])) {
      const id = String(m?.id || m?.model || '');
      if (!id || m?.hidden) continue;
      const efforts = (Array.isArray(m?.supportedReasoningEfforts) ? m.supportedReasoningEfforts : [])
        .map(e => normalizeCodexEffort(e?.reasoningEffort)).filter(Boolean);
      map.set(id, {
        label: m?.displayName || id,
        efforts,
        defaultEffort: normalizeCodexEffort(m?.defaultReasoningEffort),
      });
    }
    if (map.size) { modelCatalog = map; modelCatalogEpoch = epoch; }
    return modelCatalog;
  })().finally(() => { modelCatalogFetch = null; });
  return modelCatalogFetch;
}

// Effort'u modelin destek kümesine indirger: destekleniyorsa aynen, değilse
// CODEX_EFFORT_ORDER üzerinde en yakın desteklenen seviye (gpt-5.6-sol'da
// 'minimal' → 'low'; eski modelde 'ultra' → en yükseği). Katalog/model yoksa
// dokunmaz — prompt() katalog yüklendikten sonra zaten tekrar clamp'ler.
export function clampEffortForModel(effort, model) {
  const eff = normalizeCodexEffort(effort);
  if (!eff) return '';
  const cat = modelCatalog ? modelCatalog.get(String(model || '')) : null;
  if (!cat || !cat.efforts.length || cat.efforts.includes(eff)) return eff;
  const want = CODEX_EFFORT_ORDER.indexOf(eff);
  let best = cat.efforts[0];
  let bestDist = Infinity;
  for (const e of cat.efforts) {
    const d = Math.abs(CODEX_EFFORT_ORDER.indexOf(e) - want);
    if (d < bestDist) { best = e; bestDist = d; }
  }
  return best;
}

export function __testSetModelCatalog(entries) {
  modelCatalog = entries ? new Map(Object.entries(entries)) : null;
  modelCatalogEpoch = entries ? appEpoch : -1;
}

// /efforts yanıtı: düz `efforts` eski APK'lar için (varsayılan modelin kümesi),
// `effortsByModel` + `modelDefaultEfforts` model-duyarlı menü için (yeni UI).
export async function getEffortInfo() {
  try { await ensureModelCatalog(); } catch { /* katalog yoksa statik fallback */ }
  const effortsByModel = {};
  const modelDefaultEfforts = {};
  if (modelCatalog) {
    for (const [id, m] of modelCatalog) {
      effortsByModel[id] = m.efforts;
      modelDefaultEfforts[id] = m.defaultEffort;
    }
  }
  // Katalogda olmayan seçilebilir modeller (profil modelleri, config.toml'daki
  // varsayılan) da model-duyarlı menüye girsin; ayrıca config'te yazan default
  // effort HER ZAMAN seçilebilir olmalı — gösterip seçtirmemek tuzak.
  const configured = readDefaultEffort();
  for (const m of listSelectableModels()) {
    if (effortsByModel[m.id]) continue;
    const set = CODEX_UNKNOWN_MODEL_EFFORTS.slice();
    if (configured && !set.includes(configured)) set.push(configured);
    set.sort((a, b) => CODEX_EFFORT_ORDER.indexOf(a) - CODEX_EFFORT_ORDER.indexOf(b));
    effortsByModel[m.id] = set;
  }
  const def = effortsByModel[defaultModel()];
  return {
    efforts: (def && def.length) ? def.slice() : CODEX_APP_EFFORT_LEVELS.slice(),
    defaultEffort: configured,
    effortsByModel,
    modelDefaultEfforts,
  };
}

// "default" (boş effort) gerçekte config.toml'daki `model_reasoning_effort`e çözülür.
// UI'da "default"un neye karşılık geldiğini gösterebilmek için o değeri okuruz.
// TOML'u tam parse etmeye gerek yok: önce üst düzey anahtar, yoksa dosyada ilk eşleşme.
// Bulunamazsa '' döner (codex kendi yerleşik varsayılanını uygular).
export function readDefaultEffort() {
  try {
    const txt = fs.readFileSync(path.join(CODEX_HOME, 'config.toml'), 'utf8');
    let topLevel = '';
    let anyLevel = '';
    let inSection = false;
    for (const raw of txt.split(/\r?\n/)) {
      const line = raw.replace(/#.*$/, '').trim();
      if (line.startsWith('[')) { inSection = true; continue; }
      const m = line.match(/^model_reasoning_effort\s*=\s*["']?([a-zA-Z]+)["']?/);
      if (m) {
        const v = normalizeCodexEffort(m[1]);
        if (!inSection && !topLevel) topLevel = v;
        if (!anyLevel) anyLevel = v;
      }
    }
    return topLevel || anyLevel || '';
  } catch { return ''; }
}

// ── Codex profilleri (ozel model saglayicilari) ─────────────────────────────
// Codex 0.144'te profiller CODEX_HOME/<ad>.config.toml dosyalarina tasindi ve
// her biri `model` + `model_provider` tasiyabiliyor. app-server thread/start'ta
// `modelProvider` alanini kabul ediyor (JSON semasiyla dogrulandi), yani
// saglayici TUR BASINA secilebilir — tek app-server'i paylasmamiz engel degil.
//
// Liste DISKTEN okunuyor, kaynakta hicbir saglayici adi gomulu DEGIL: makinede
// hangi profil tanimliysa uygulamada o gorunur.
const PROFILE_FILE_RE = /^(.+)\.config\.toml$/i;

function readTomlScalar(txt, key) {
  for (const raw of txt.split(/\r?\n/)) {
    const line = raw.replace(/#.*$/, '').trim();
    // Tablo basligindan sonrasi profilin kok anahtari degildir.
    if (line.startsWith('[')) break;
    const m = line.match(new RegExp('^' + key + '\\s*=\\s*["\']([^"\']+)["\']'));
    if (m) return m[1];
  }
  return '';
}

export function listCodexProfiles() {
  let files = [];
  try {
    files = fs.readdirSync(CODEX_HOME, { withFileTypes: true })
      .filter(e => e.isFile() && PROFILE_FILE_RE.test(e.name))
      .map(e => e.name);
  } catch { return []; }
  const out = [];
  for (const file of files) {
    const name = file.match(PROFILE_FILE_RE)[1];
    let txt = '';
    try { txt = fs.readFileSync(path.join(CODEX_HOME, file), 'utf8'); } catch { continue; }
    const model = readTomlScalar(txt, 'model');
    const provider = readTomlScalar(txt, 'model_provider');
    // Saglayici belirtmeyen profil zaten varsayilan saglayiciyi kullanir;
    // model secicide ayri bir satir olmasini gerektirmiyor.
    if (!model || !provider) continue;
    out.push({ name, model, modelProvider: provider });
  }
  return out;
}

// ESKI TASARIM HATASI: model listesinde "profile:<ad>" gibi SAHTE bir kimlik
// kullaniliyordu ve bu kimlik thread/start'ta cozulse de turn/start'a HAM
// gidiyordu; API "desteklenen model adlari ... ama sen profile:deepseek
// gonderdin" diyerek turu reddediyordu (canli goruldu). Ders: sahte kimlik
// oturumda TUTULMAMALI. Artik liste GERCEK model kimliklerini veriyor,
// saglayici ayri bir alanda tasiniyor.

function readCodexConfigText() {
  try { return fs.readFileSync(path.join(CODEX_HOME, 'config.toml'), 'utf8'); }
  catch { return ''; }
}

/** Codex'in temel config'indeki varsayilan model + saglayici. */
function baseConfigModel() {
  const txt = readCodexConfigText();
  return { model: readTomlScalar(txt, 'model'), provider: readTomlScalar(txt, 'model_provider') };
}

/**
 * Model secicide gorunen liste.
 *
 * Sira: once config.toml'daki VARSAYILAN model, sonra diger profil modelleri,
 * en sonda yerlesikler. Varsayilan listenin dibinde kalirsa kullanici her yeni
 * oturumda onu aramak zorunda kaliyor — secici "hangisini kullaniyorum"u en
 * ustte gostermeli.
 */
export function listSelectableModels() {
  // Yerlesik etiket ("GPT-6.1 Sol") profil etiketinden ("gpt · gpt-6.1-sol")
  // daha okunakli; model bilinen bir yerlesikse sira profilden gelse bile
  // etiketi yerlesikten al.
  const builtinLabel = new Map(CODEX_APP_MODELS.map(m => [m.id, m.label]));
  const seen = new Set();
  const out = [];
  const add = (id, label) => {
    if (!id || isRetiredModel(id) || seen.has(id)) return;
    seen.add(id);
    out.push({ label: builtinLabel.get(id) || label, id });
  };
  const base = baseConfigModel();
  const selectedDefault = defaultModel();
  add(selectedDefault, selectedDefault === base.model && base.provider
    ? `${base.provider} · ${base.model}` : selectedDefault);
  for (const p of listCodexProfiles()) add(p.model, `${p.name} · ${p.model}`);
  for (const m of CODEX_APP_MODELS) add(m.id, m.label);
  return out;
}

/** Bu model hangi saglayiciyi ister? Yerlesik modellerde bos (Codex varsayilani). */
export function providerForModel(model) {
  const id = String(model || '').trim();
  if (!id) return '';
  const base = baseConfigModel();
  if (base.model === id && base.provider) return base.provider;
  const hit = listCodexProfiles().find(p => p.model === id);
  return hit ? hit.modelProvider : '';
}

/**
 * Gelen model kimligini GERCEK model adina cevirir.
 *
 * Eski surumun yazdigi "profile:<ad>" kimlikleri diskteki oturum kayitlarinda
 * duruyor olabilir; onlari sessizce gercek modele goc ettiriyoruz ki restore
 * edilen oturum ilk turda patlamasin.
 */
export function normalizeModelId(model) {
  const raw = String(model || '').trim();
  if (!raw.startsWith('profile:')) return raw;
  const name = raw.slice('profile:'.length);
  const hit = listCodexProfiles().find(p => p.name === name);
  return hit ? hit.model : defaultModel();
}

export function newSession({ cwd, model, permissionMode, effort } = {}) {
  let dir = cwd && String(cwd).trim() ? String(cwd).trim() : os.homedir();
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return { ok: false, error: 'cwd not a directory: ' + dir };
  const id = randomUUID();
  const { approvalPolicy, sandbox } = policyForMode(permissionMode);
  const eff = normalizeCodexEffort(effort) || defaultEffort();
  sessions.set(id, {
    id,
    threadId: null,
    // Olusturulma ani: oturumun tek KESIN zaman damgasi. _lastActivity yalniz
    // prompt() ile doluyor, yani hic konusulmamis bir kabugun hicbir zamani
    // olmuyordu ve budama onu "yasi bilinmeyen" diye sonsuza dek sakliyordu
    // (19.08.2026'da olculdu: 32 bos kabuk). Artik en kotu ihtimalle bu var.
    createdAt: Date.now(),
    cwd: dir,
    model: normalizeModelId(model) || defaultModel(),
    permissionMode: permissionMode || 'yolo',
    effort: eff,
    approvalPolicy,
    sandboxMode: sandbox,
    sandboxPolicy: sandboxPolicyFor(sandbox),
    status: 'idle',
    messages: [],
    toolDetails: [],
    subscribers: new Set(),
    awaitingFirstOutput: false,
    awaitingApproval: false,
    awaitingUserInput: false,
    pendingApproval: null, // { requestId, method, summary, description, tool, questions, rawParams }
    contextTokens: 0,
    contextWindow: 0,
    _epoch: appEpoch,
    _turnId: null,
    _agentRows: new Map(),     // itemId -> agent mesaj nesnesi
    _detailIdx: new Map(),     // 'reasoning:'/'cmd:'/'file:'+itemId -> toolDetails indeksi
    _fileChanges: [],          // getChanges() icin yapilandirilmis özet dizi (son 200)
    _commands: [],             // getCommands() icin yapilandirilmis özet dizi (son 200)
    _reasoningBufs: new Map(), // itemId -> accumulator string
    _planBufs: new Map(),      // itemId -> accumulator string
    _lastActivity: 0,
    _booting: false,           // thread/start in flight
    _plan: [],                // array of { text, status }
    _planDraft: '',            // planDelta akarken biriken taslak metin
    _needsHydration: false,    // restore sonrasi ilk eriste thread/resume ile gecmis yukle
    _hydrating: false,
    goal: null,                // thread hedefi; setGoal/bildirim doldurur (app-server otoritesi)
  });
  persistSessions();
  return { ok: true, sessionId: id, cwd: dir, model: model || defaultModel(), effort: eff };
}

// Effort'u kalıcı olarak değiştirir. Her turn/start'ta props.effort gönderildiği için
// gelecek turlar yeni effort ile başlar (codex schema: TurnStartParams.effort "for this
// turn and subsequent turns"). Tur içindeyken anlık etki yoktur; bir sonraki turda geçerli.
export function setEffort({ sessionId, effort } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  // app-server zaten ayaktaysa kataloğu arkaplanda tazele (spawn ETMEZ; testler
  // ve soğuk bridge app-server'ı buradan başlatmasın) — sıradaki turn taze clamp'ler.
  if (appServer && appServer.exitCode == null && !appServer.stdin.destroyed) ensureModelCatalog().catch(() => {});
  s.effort = clampEffortForModel(effort, s.model);
  persistSessions();
  pushSnapshot(s);   // UI rozeti anında güncellensin (bir sonraki turda geçerli olur)
  return { ok: true, effort: s.effort };
}

function policyForMode(permissionMode) {
  switch (permissionMode) {
    case 'ask':
    case 'on-request':
      return { approvalPolicy: 'on-request', sandbox: 'workspace-write' };
    case 'on-failure':
      return { approvalPolicy: 'on-failure', sandbox: 'workspace-write' };
    case 'untrusted':
      return { approvalPolicy: 'untrusted', sandbox: 'workspace-write' };
    case 'never':
    case 'yolo':
    default:
      return { approvalPolicy: DEFAULT_APPROVAL_POLICY, sandbox: DEFAULT_SANDBOX_MODE };
  }
}

function sandboxPolicyFor(sandboxMode) {
  switch (sandboxMode) {
    case 'read-only': return { type: 'readOnly' };
    case 'workspace-write': return { type: 'workspaceWrite' };
    case 'danger-full-access':
    default: return { type: 'dangerFullAccess' };
  }
}

// App-server yeni epoch'taysa ya da thread eslemesi baska kabuga kaydiysa, ayni
// thread'i thread/resume ile canli tut — model baglami korunur. Bayat degilse hic
// istek atmaz. Basarisiz olursa THROW eder ve threadId'ye DOKUNMAZ: prompt() taze
// thread acmak icin null'lamak isterken goal cagrilari hata donmek istiyor; ikisi
// ayni bloktan cikinca bu ayrimi cagirana biraktik (kopyala-yapistir yerine paylasim).
async function ensureThreadLive(s) {
  if (!s.threadId) return false;
  if (s._epoch === appEpoch && sessionsByThread.get(s.threadId) === s) return true;
  await appSend('thread/resume', { threadId: s.threadId });
  sessionsByThread.set(s.threadId, s);
  s._epoch = appEpoch;
  s._turnId = null;
  return true;
}

// ── Prompt flow ────────────────────────────────────────────────────────────
export async function prompt({ sessionId, text, model, permissionMode } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (s.status === 'running') return { ok: false, error: 'busy' };
  if (!text || !String(text).trim()) return { ok: false, error: 'text required' };

  // Restore edilmis kabuk: kullanici mesajini eklemeden ONCE gecmisi yukle;
  // hydrate messages dizisini bastan kurdugu icin sonra yapilirsa yeni mesaj silinir.
  if (s._needsHydration) { try { await hydrateFromThread(s); } catch { /* resume prompt icinde tekrar denenir */ } }

  if (model) s.model = model;
  if (permissionMode) {
    s.permissionMode = permissionMode;
    const { approvalPolicy, sandbox } = policyForMode(permissionMode);
    s.approvalPolicy = approvalPolicy;
    s.sandboxMode = sandbox;
    s.sandboxPolicy = sandboxPolicyFor(sandbox);
  }

  s.messages.push({ role: 'user', text: String(text), time: hhmm() });
  s.lastUserAt = Date.now(); // proje detayi siralamasi: son user prompt ani
  capSession(s);
  s.status = 'running';
  s.awaitingFirstOutput = true;
  s._turnId = null;
  resetTurnBuffers(s);
  s._lastActivity = Date.now();
  pushSnapshot(s);

  try {
    await ensureInitialized();
    // App-server yeni epoch'taysa eski threadId stale (sessionsByThread temizlenmis).
    // Codex thread'leri diske yazildigi icin once thread/resume ile ayni thread'e
    // geri baglanmayi dene — model baglami korunur. Resume basarisizsa (thread yok,
    // eski surum vs.) taze thread'e dus.
    if (s.threadId) {
      try {
        await ensureThreadLive(s);
      } catch (e) {
        // prompt() resume başarısızsa TAZE thread açar (aşağıdaki !s.threadId bloğu);
        // bu yüzden temizlik burada, ensureThreadLive'ın içinde değil — goal çağrıları
        // aynı hatada thread AÇMAK yerine hata dönmek istiyor, karar çağırana kalsın.
        logWarn('codex-app', 'thread/resume başarısız; taze thread açılacak', { threadId: s.threadId, error: errMessage(e) });
        sessionsByThread.delete(s.threadId);
        s.threadId = null;
        s._turnId = null;
      }
    }
    if (!s.threadId) {
      s._booting = true;
      // s.model GERCEK model adidir; saglayici ondan turetilir. modelProvider
      // BOSSA HIC GONDERILMEZ — alanin varligi bile Codex'in config'teki
      // varsayilan saglayicisini ezebilir.
      const startParams = {
        cwd: s.cwd, model: s.model,
        approvalPolicy: s.approvalPolicy, sandbox: s.sandboxMode,
      };
      const provider = providerForModel(s.model);
      if (provider) startParams.modelProvider = provider;
      const ts = await appSend('thread/start', startParams);
      s.threadId = ts?.thread?.id || null;
      if (s.threadId) sessionsByThread.set(s.threadId, s);
      s._epoch = appEpoch;
      s._booting = false;
      if (!s.threadId) {
        finalizeTurnIdle(s, { error: '[codex-app] thread başlatılamadı' });
        return { ok: false, error: 'thread start failed' };
      }
      emitThreadResolved(s);
      persistSessions(); // threadId eslemesi artik biliniyor — restart'ta resume edilebilsin
    }
    // Effort'u modelin destek kümesine indir (ör. gpt-5.6'da kalıcılaşmış eski
    // 'minimal' → 'low'); yoksa turn/start "Unsupported value" ile reddediyor.
    await ensureModelCatalog().catch(() => {}); // katalog çekilemezse clamp atlanır, ölümcül değil
    const clampedEffort = clampEffortForModel(s.effort, s.model);
    if (clampedEffort !== s.effort) {
      logWarn('codex-app', 'effort modelin destek kümesine indirgendi', { model: s.model, from: s.effort, to: clampedEffort });
      s.effort = clampedEffort;
      persistSessions();
    }
    const turnParams = {
      threadId: s.threadId,
      input: [{ type: 'text', text: String(text) }],
      model: s.model,
      approvalPolicy: s.approvalPolicy,
      sandboxPolicy: s.sandboxPolicy,
    };
    if (s.effort) turnParams.effort = s.effort;
    const requestStartedAt = Date.now();
    try {
      const tr = await appSend('turn/start', turnParams);
      s._turnId = tr?.turn?.id || s._turnId || null;
    } catch (e) {
      // Some app-server builds accept turn/start, emit the live turn events, and
      // still answer the JSON-RPC request with an empty/shape-less error. At
      // this point the prompt is already in the thread and the phone should not
      // show a false HTTP 400.
      if (e && e.code === 'ETIMEOUT' && s._lastActivity > requestStartedAt) {
        // The turn is alive; let notifications and the watchdog own completion.
      } else if (!isEmptyRpcError(e)) throw e;
    }
    s._lastActivity = Date.now();
    return { ok: true, sessionId: s.id };
  } catch (e) {
    s._booting = false;
    const msg = errMessage(e);
    // Eskiden "kullanici mesaji zaten push edildi mi" (her zaman true) ile sahte-ok
    // donup turn'u 'running'de birakiyorduk; boylece gercek hatalar (timeout dahil)
    // kullanicida "app takildi" olarak gozukuyordu. "Bos RPC hatasi ama event'ler
    // akiyor" senaryosu zaten yukaridaki isEmptyRpcError ile ele alindi. Gercek
    // hatada turn'u finalize et ki oturum idle'a donsun (watchdog olmadan da bu
    // artik sarkmıyor).
    finalizeTurnIdle(s, { error: '[codex-app] turn başlatılamadı: ' + msg });
    return { ok: false, error: msg };
  }
}

function resetTurnBuffers(s) {
  s._agentRows && s._agentRows.clear();
  s._detailIdx && s._detailIdx.clear();
  s._reasoningBufs && s._reasoningBufs.clear();
  s._planBufs && s._planBufs.clear();
  s._planDraft = '';
  // Plan canlı turn artefaktıdır. Yeni görev başlarken önceki turnün planını
  // taşımak, kullanıcı başka işle uğraşırken eski kartı yeniden gösteriyordu.
  s._plan = [];
}

// ── Stop / interrupt ────────────────────────────────────────────────────────
export async function stop(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'not found' };
  if (s.status !== 'running' && !s.awaitingFirstOutput) {
    // Clean any stale awaiting flags so the phone stops spinning.
    if (s.awaitingApproval || s.awaitingUserInput) {
      s.awaitingApproval = false; s.awaitingUserInput = false; s.pendingApproval = null;
      pushSnapshot(s);
    }
    return { ok: true, already: true };
  }
  // Onay beklerken durduruluyorsa: cevapsiz server-request'i once reddet ki
  // app-server tarafinda istek asili kalmasin; sonra bayraklari temizle.
  // (finalizeTurnIdle de temizler ama server'a cevap gitmesi burada sart.)
  if (s.pendingApproval) {
    try { autoDeclineUnknownMethod({ id: s.pendingApproval.requestId, method: s.pendingApproval.method }); } catch {}
    clearPendingApproval(s);
  }
  // Best-effort interrupt: do not kill the shared process (it serves all
  // sessions). turn/interrupt asks the server to abort the active turn.
  if (s.threadId && s._turnId) {
    try { await appSend('turn/interrupt', { threadId: s.threadId, turnId: s._turnId }); }
    catch (e) { logWarn('codex-app', 'turn/interrupt failed', { error: errMessage(e) }); }
  }
  finalizeTurnIdle(s, { suppressed: true });
  return { ok: true };
}

// ── Approvals / user input ──────────────────────────────────────────────────
// Feature 3: approval now supports scope selection:
//   decision: 'allow'|'deny'|'acceptForSession'|'acceptForTurn'
//   scope:    'turn'|'session' (permissions only)
export function approve({ sessionId, allow = true, decision, cancel, answers, content, scope } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.pendingApproval) return { ok: false, error: 'no pending approval' };
  const { requestId, method, rawParams } = s.pendingApproval;
  // cancel=true → karar enum'unda cancel
  const effectiveDecision = cancel ? 'cancel' : decision;
  const result = approvalResultFor(method, { allow, decision: effectiveDecision, answers, content, scope, rawParams });
  if (result === null) {
    appReject(requestId, -32603, 'approval response cannot be built');
  } else {
    appRespond(requestId, result);
  }
  clearPendingApproval(s);
  pushSnapshot(s);
  return { ok: true };
}

export function respondUserInput({ sessionId, answers, text } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.pendingApproval) return { ok: false, error: 'no pending user input' };
  const { requestId, method, rawParams } = s.pendingApproval;
  if (method !== 'item/tool/requestUserInput' && method !== 'mcpServer/elicitation/request') {
    return { ok: false, error: 'not a user-input request' };
  }
  let result;
  if (method === 'item/tool/requestUserInput') {
    // answers must be {questionId: { answers: [string, ...] } } per ToolRequestUserInputResponse.
    let ans = answers && typeof answers === 'object' ? answers : null;
    if (!ans && text != null) {
      // Build a single-string answer map keyed by the first question id, if available.
      const ids = (rawParams && Array.isArray(rawParams.questions))
        ? rawParams.questions.map(q => q.id).filter(Boolean) : [];
      ans = ids.length ? { [ids[0]]: { answers: [String(text)] } } : {};
    }
    result = { answers: ans || {} };
  } else {
    // mcpServer/elicitation/request: accept/decline/cancel + content.
    result = { action: answers?.action || (text == null && !answers ? 'decline' : 'accept') };
    if (answers && answers.content != null) result.content = answers.content;
    else if (text != null) result.content = text;
  }
  appRespond(requestId, result);
  clearPendingApproval(s);
  pushSnapshot(s);
  return { ok: true };
}

/**
 * Bekleyen onayı temizler — aynı isteği gösteren KARDEŞ kabuklarla birlikte.
 *
 * İstek tüm kabuklara yansıtıldığı için (bkz. handleServerRequest) tek kabukta
 * temizlemek diğerlerinde ölü bir kart bırakırdı; oradan verilen ikinci cevap
 * app-server'a aynı requestId için ikinci yanıt göndermeye çalışırdı.
 */
function clearPendingApproval(s) {
  const record = s.pendingApproval;
  s.pendingApproval = null;
  s.awaitingApproval = false;
  s.awaitingUserInput = false;
  if (!record) return;
  for (const sibling of sessionsForThread(s.threadId)) {
    if (sibling === s || sibling.pendingApproval !== record) continue;
    sibling.pendingApproval = null;
    sibling.awaitingApproval = false;
    sibling.awaitingUserInput = false;
    pushSnapshot(sibling);
  }
}

// Build the per-method response payload for an approval decision. The decision
// enum values are taken from the generated JSON schema and MUST NOT be guessed.
function approvalResultFor(method, { allow, decision, answers, content, scope, rawParams }) {
  const d = decision != null ? decision : (allow ? 'accept' : 'deny');
  switch (method) {
    case 'item/commandExecution/requestApproval':
      return { decision: commandExecDecision(method, d) };
    case 'item/fileChange/requestApproval':
      return { decision: fileChangeDecision(method, d) };
    case 'item/permissions/requestApproval':
      // allow → talep edilen izinleri (rawParams.permissions) geri gönder,
      // deny → null + strictAutoReview (hiçbir yeni izin tanıma).
      return allow
        ? { permissions: rawParams?.permissions || {}, scope: scope || 'turn' }
        : { permissions: null, scope: scope || 'turn', strictAutoReview: true };
    case 'item/tool/requestUserInput':
      return { answers: answers && typeof answers === 'object' ? answers : {} };
    case 'mcpServer/elicitation/request':
      return { action: allow ? 'accept' : 'decline', content: content != null ? content : null };
    case 'applyPatchApproval':
      return { decision: allow ? 'approved' : 'denied' };
    case 'execCommandApproval':
      return { decision: allow ? 'approved' : 'denied' };
    default:
      return null;
  }
}

function commandExecDecision(method, d) {
  if (d === 'allow' || d === 'accept' || d === 'approved') return 'accept';
  if (d === 'deny' || d === 'decline' || d === 'denied') return 'decline';
  if (d === 'cancel' || d === 'abort') return 'cancel';
  if (d === 'acceptForSession' || d === 'approved_for_session' || d === 'allowForSession') return 'acceptForSession';
  return 'decline'; // safe default: unknown decision → decline
}
function fileChangeDecision(method, d) {
  if (d === 'allow' || d === 'accept' || d === 'approved') return 'accept';
  if (d === 'deny' || d === 'decline' || d === 'denied') return 'decline';
  if (d === 'cancel' || d === 'abort') return 'cancel';
  if (d === 'acceptForSession' || d === 'approved_for_session' || d === 'allowForSession') return 'acceptForSession';
  return 'decline'; // safe default: unknown decision → decline
}

export function __testApprovalResultFor(method, opts = {}) {
  return approvalResultFor(method, opts);
}

// -- Compact / model ──────────────────────────────────────────────────────────
export async function compact(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.threadId) return { ok: false, error: 'thread not started' };
  try {
    await ensureInitialized();
    await appSend('thread/compact/start', { threadId: s.threadId });
    return { ok: true };
  } catch (e) {
    // Some app-server builds start compaction and emit the compact boundary, but
    // answer the JSON-RPC request with an empty error object. Treat that like
    // turn/start's accepted-but-shapeless response so the phone does not show a
    // false failure while the transcript updates successfully.
    if (isEmptyRpcError(e)) return { ok: true, warning: 'compact accepted by app-server' };
    return { ok: false, error: errMessage(e) };
  }
}

// ── Thread goal (objective + status + token budget) ─────────────────────────
// app-server thread/goal/{set,get,clear}. Goal thread'e bagli oldugu icin thread
// AÇILMADAN goal olamaz; thread yalniz prompt() icinde tembel aciliyor, goal ugruna
// thread acmak scope disi (kullanici once bir mesaj gondermeli).
export async function setGoal({ sessionId, objective, status, tokenBudget } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  // Mesaj kullaniciya AYNEN gosteriliyor (app "Hedef kurulamadi: <bu>" basar),
  // o yuzden "thread" jargonu yerine ne yapmasi gerektigi yazar. 06 Ago 2026'da
  // kullanici yeni oturumda /goal deneyip yalnizca "HTTP 400" gordu.
  if (!s.threadId) return { ok: false, error: 'sohbet henüz başlamadı — önce bir mesaj gönderin, sonra hedefi kurun' };
  // objective verilmisse bos/whitespace olamaz (bos goal acmak anlamsiz). Ama HIC
  // verilmemisse status-only guncelleme ( or. pause) mesru — o zaman params'a koyma,
  // app-server mevcut objective'i korur.
  const obj = objective == null ? '' : String(objective).trim();
  if (objective != null && !obj) return { ok: false, error: 'objective required' };
  try {
    await ensureInitialized();
    await ensureThreadLive(s);
    const p = { threadId: s.threadId };
    if (obj) p.objective = obj;
    if (status) p.status = status;
    if (tokenBudget !== undefined) p.tokenBudget = tokenBudget;
    const r = await appSend('thread/goal/set', p);
    const goal = (r && r.goal) || null;
    // thread/goal/updated bildirimi de ayni goal'u tum kabuklara fan-out edecek; bu
    // atama yalniz cagiran kabugun snapshot'ini bildirimi beklemeden dogru tutmak icin.
    if (goal) s.goal = goal;
    // Yalniz yeni objective'de satir yaz; status-only guncellemeler (pause vb.)
    // kullanici girdisi degildir.
    if (obj) appendGoalCommandRow(s.threadId, '/goal ' + obj);
    return { ok: true, goal };
  } catch (e) { return { ok: false, error: errMessage(e) }; }
}

export async function getGoal(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  // threadId yoksa app-server'da goal da olamaz — appSend'e (ve thread acmaya) hic
  // gitmeden bos don. get salt-okuma; thread canlandirmayi da tetiklemesin diye erken cikis.
  if (!s.threadId) return { ok: true, goal: null };
  try {
    await ensureInitialized();
    await ensureThreadLive(s);
    const r = await appSend('thread/goal/get', { threadId: s.threadId });
    const goal = (r && r.goal) || null;
    s.goal = goal;
    return { ok: true, goal };
  } catch (e) { return { ok: false, error: errMessage(e) }; }
}

// silent: hedefi kullanici KOMUTLA degil, biten hedefin bildirimini kapatarak
// dusurdugunde transcript'e satir yazma. Komutla temizlemek bir niyet beyanidir
// (kayda deger), bitmis bir hedefi "gordum" demek degildir — her kapatista
// sohbete "/goal temizle" dusmesi gurultu olurdu.
export async function clearGoal({ sessionId, silent } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  // Mesaj kullaniciya AYNEN gosteriliyor (app "Hedef kurulamadi: <bu>" basar),
  // o yuzden "thread" jargonu yerine ne yapmasi gerektigi yazar. 06 Ago 2026'da
  // kullanici yeni oturumda /goal deneyip yalnizca "HTTP 400" gordu.
  if (!s.threadId) return { ok: false, error: 'sohbet henüz başlamadı — önce bir mesaj gönderin, sonra hedefi kurun' };
  try {
    await ensureInitialized();
    await ensureThreadLive(s);
    const r = await appSend('thread/goal/clear', { threadId: s.threadId });
    const cleared = !!(r && r.cleared);
    // thread/goal/cleared bildirimi de fan-out edecek; anlik netlik icin lokal null.
    s.goal = null;
    // setGoal'daki kullanici satiriyla simetri: temizleme de kayitli bir iz biraksin.
    if (!silent) appendGoalCommandRow(s.threadId, '/goal temizle');
    return { ok: true, cleared };
  } catch (e) { return { ok: false, error: errMessage(e) }; }
}

// steerTurn ile ayni gerekce: goal komutu istemcide yakalanip prompt() yolundan
// hic gecmedigi icin transcript'e kendiliginden kullanici satiri dusmuyordu —
// "/goal ..." girdisi VE pesinden baslayan turun sahibi kayitta gorunmez kaliyordu
// (canlida yasandi, 02.08.2026). Satir kopru-yerlidir, codex rollout'una yazilmaz;
// tam re-hydrate'te kaybolmasi steer satirlariyla ayni kabul. Thread'i paylasan
// TUM kabuklara islenir ki mirror'lar birbirinden sapmasin.
function appendGoalCommandRow(threadId, text) {
  for (const shell of sessionsForThread(threadId)) {
    shell.messages.push({ role: 'user', text, time: hhmm() });
    shell.lastUserAt = Date.now();
    capSession(shell);
    pushSnapshot(shell);
  }
}
// Test-only: saf testler appSend'e ulasamadigi icin satir-ekleme mantigi ayrica
// dogrulanir (goal bildirim testlerindeki __testRouteNotification desenine es).
export function __testAppendGoalCommandRow(threadId, text) {
  appendGoalCommandRow(threadId, text);
}

export function setModel({ sessionId, model } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!model || !String(model).trim()) return { ok: false, error: 'model required' };
  // No app-server method exists to pre-set the next-turn model; we store the
  // override and apply it on turn/start. Genuine in-flight changes require a
  // respawn of the thread (out of scope for the first cut).
  s.model = normalizeModelId(model);
  // Eski modelde seçilmiş effort yeni modelde geçersiz olabilir (minimal → 5.6).
  s.effort = clampEffortForModel(s.effort, s.model);
  persistSessions();
  return { ok: true, model: s.model };
}

// ── Conversation / thought / subscription readers ───────────────────────────
export function getConversation(sessionId, opts = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { messages: [], running: false, awaitingApproval: false, awaitingFirstOutput: false, awaitingUserInput: false, contextTokens: 0, contextWindow: 0, approval: null, plan: [] };
  // Restore edilmis kabuk: gecmisi arka planda yukle; hazir olunca ws snapshot gider.
  if (s._needsHydration) hydrateFromThread(s).catch(() => {});
  return {
    messages: paginateSessionMessages(s, opts),
    running: s.status === 'running',
    awaitingApproval: awaitingAnswer(s),
    awaitingFirstOutput: !!s.awaitingFirstOutput,
    awaitingUserInput: !!s.awaitingUserInput,
    contextTokens: s.contextTokens || 0,
    contextWindow: s.contextWindow || 0,
    approval: awaitingAnswer(s) ? s.pendingApproval : null,
    plan: Array.isArray(s._plan) ? s._plan.map(p => ({ text: String(p.text || ''), status: String(p.status || 'pending'), itemId: String(p.itemId || ''), turnId: String(p.turnId || '') })) : [],
    planDraft: s._planDraft || '',
    effort: s.effort || '',   // poll yolu da WS snapshot ile aynı effort'u taşısın
    goal: s.goal || null,     // poll yolu da WS snapshot ile aynı goal'u taşısın
  };
}

// getPlanItems zaten snapshot(s).plan yerine doğrudan _plan'dan okur.
export function getPlanItems(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  return { ok: true, plan: Array.isArray(s._plan) ? s._plan.map(p => ({ text: String(p.text || ''), status: String(p.status || 'pending'), itemId: String(p.itemId || ''), turnId: String(p.turnId || '') })) : [], sessionId };
}

export function getThought(sessionId, i) {
  const s = sessions.get(sessionId);
  if (!s) return { text: '' };
  return { text: s.toolDetails[i] || '' };
}

export function listSessions() {
  const titleData = readBridgeJson(BRIDGE_TITLES_FILE);
  return [...sessions.values()].map(s => ({
    id: s.id, cwd: s.cwd, model: s.model, status: s.status,
    threadId: s.threadId,
    // Baslik ILK GERCEK kullanici mesajindan. Ham "ilk user mesaji" AGENTS.md /
    // <environment_context> gibi enjekte bloklara denk geliyordu; Merkez >
    // Operasyonlar'da her codex karti "# AGENTS.md instructions ..." diye
    // basliyordu (kullanici sikayeti 02.08.2026). isInjectedUserText rollout
    // ayiklayicisiyla ayni kurali paylasir.
    // Kullanıcının verdiği ad hem disk çekmecesinde hem açık sekme/header'da
    // görünmeli. Adopt edilmiş kabuğun id'si thread id'sinden farklı olabilir;
    // rename ucu disk/thread kimliğini aldığı için önce threadId ile bakılır.
    title: (titleData[s.threadId] || titleData[s.id] ||
      s.messages.find(m => m.role === 'user' && !isInjectedUserText(m.text))?.text || '')
      .replace(/\s+/g, ' ').slice(0, 80),
    lastUserAt: s.lastUserAt || 0,
    lastActivity: s._lastActivity || 0,
    createdAt: s.createdAt || 0,
    turns: s.messages.filter(m => m.role === 'user').length,
    lastText: (s.messages[s.messages.length - 1]?.text || '').slice(0, 80),
    awaitingApproval: awaitingAnswer(s),
    restoredShell: !!s._restoredShell,
  }));
}

export function runningSessionId() {
  for (const s of sessions.values()) if (s.status === 'running') return s.id;
  return null;
}

export function getPendingApproval() {
  for (const s of sessions.values()) {
    if (s.pendingApproval && (s.awaitingApproval || s.awaitingUserInput)) {
      return {
        backend: 'codex-app',
        sessionId: s.id,
        summary: s.pendingApproval.summary || s.pendingApproval.description || 'İzin gerekiyor',
      };
    }
  }
  return null;
}

export function subscribe(sessionId, ws, opts = {}) {
  const s = sessions.get(sessionId);
  if (s && s._needsHydration) hydrateFromThread(s).catch(() => {});
  sessionCore.subscribe(s, ws, opts);
}

// ── Disk / adoption ─────────────────────────────────────────────────────────
let _diskCache = { at: 0, list: null };

function diskSessionFromLive(live, id, metadata) {
  return {
    id,
    cwd: live.cwd || metadata?.cwd || '',
    title: (metadata?.title || live.messages.find(message =>
      message.role === 'user' && !isInjectedUserText(message.text))?.text || '')
      .replace(/\s+/g, ' ').slice(0, 80),
    lastText: (live.messages.at(-1)?.text || metadata?.preview || '')
      .replace(/\s+/g, ' ').slice(0, 100),
    turns: live.messages.filter(message => message.role === 'user').length,
    // createdAt en son care: hic konusulmamis kabugun baska zamani yok ve
    // zamansiz kayit budamanin disinda kaliyordu.
    mtime: Math.max(
      live._lastActivity || 0, live.lastUserAt || 0, metadata?.mtime || 0, live.createdAt || 0,
    ),
  };
}

export function __testDiskSessionFromLive(live, id, metadata) {
  return diskSessionFromLive(live, id, metadata);
}

export async function listDiskSessions({ all: includeAll = false } = {}) {
  const now = Date.now();
  if (!includeAll && _diskCache.list && (now - _diskCache.at) < 5000) return _diskCache.list;
  // Drawer/list HTTP isteğini app-server thread/list'e bağlama. App-server yoğun
  // bir tur veya hydrate sırasında 30 sn cevap vermeyebiliyor; Android bunu
  // "kayıtlı oturum yok" diye gösteriyor ve bütün bridge bozuk sanılıyor.
  // Rollout baş+son örneklemesi yerel, sınırlı ve app-server'dan bağımsızdır.
  const disk = diskSessionsFallback(includeAll);
  const byId = new Map(disk.map(session => [session.id, session]));
  // Varsayılan çekmece yolu rollout'ların yalnız en yeni 60 tanesini örnekler.
  // Bridge restart'ında geri yüklenen eski kabuklar bu dilimin dışında kalınca
  // aşağıdaki canlı ekleme yolu boş messages üzerinden UUID başlığı üretiyordu.
  // Codex'in state DB'si bu eski thread'lerin gerçek başlığını/önizlemesini
  // zaten taşıyor; canlı kabuğu onunla zenginleştir.
  const stateMetadata = readCodexStateThreadMetadata();
  for (const live of sessions.values()) {
    const id = live.threadId || live.id;
    if (!id || byId.has(id) || isSpikeCwd(live.cwd)) continue;
    const metadata = stateMetadata.get(id);
    byId.set(id, diskSessionFromLive(live, id, metadata));
  }
  const out = { ok: true, sessions: [...byId.values()].sort((a, b) => (b.mtime || 0) - (a.mtime || 0)) };
  if (!includeAll) _diskCache = { at: now, list: out };
  return out;
}

// Best-effort: mirror codex.mjs's ~/.codex/sessions rollout walker so the phone
// can still list historical CLI sessions even when app-server is unavailable.
function diskSessionsFallback(includeAll = false) {
  const files = [];
  walkRollouts(SESSIONS_DIR, files, 0);
  const stateMetadata = readCodexStateThreadMetadata();
  const stated = [];
  for (const f of files) {
    let st; try { st = fs.statSync(f); } catch { continue; }
    stated.push({ f, mtime: st.mtimeMs });
  }
  stated.sort((a, b) => b.mtime - a.mtime);
  const out = [];
  for (const { f, mtime } of includeAll ? stated : stated.slice(0, 60)) {
    const id = sessionIdFromRollout(f);
    const head = readRolloutHead(f, 4); // first lines enough for a preview
    const metadata = stateMetadata.get(id);
    const title = metadata?.title || head.firstUser || '';
    const lastText = head.lastText || metadata?.preview || '';
    const cwd = metadata?.cwd || head.cwd || '';
    if (!title && !lastText) continue;
    if (isSpikeCwd(cwd)) continue;
    out.push({
      id,
      cwd,
      title: title.replace(/\s+/g, ' ').slice(0, 80),
      lastText: lastText.replace(/\s+/g, ' ').slice(0, 100),
      turns: head.turns || 0,
      mtime: Math.max(mtime, metadata?.mtime || 0),
    });
  }
  return out;
}

// app-server thread/list'e dönmek drawer'ı yeniden 30 saniyelik backend
// timeout'una bağlar. Codex'in küçük state DB'si aynı başlık metadata'sını
// doğrudan ve hızlı verir; transcript metni için rollout fallback'i kalır.
function readCodexStateThreadMetadata(file = CODEX_STATE_DB) {
  const out = new Map();
  if (!fs.existsSync(file)) return out;
  let db;
  try {
    db = new DatabaseSync(file, { readOnly: true });
    const rows = db.prepare(`
      SELECT id, name, title, first_user_message, preview, cwd,
             updated_at, updated_at_ms, recency_at, recency_at_ms
      FROM threads
    `).all();
    for (const row of rows) {
      const id = String(row.id || '').trim();
      if (!id) continue;
      const title = String(row.name || row.title || row.first_user_message || row.preview || '')
        .replace(/\s+/g, ' ').trim().slice(0, 80);
      const preview = String(row.preview || row.first_user_message || row.title || '')
        .replace(/\s+/g, ' ').trim().slice(0, 100);
      const rawCwd = String(row.cwd || '');
      const cwd = rawCwd.startsWith('\\\\?\\') ? rawCwd.slice(4) : rawCwd;
      const mtime = Math.max(
        Number(row.updated_at_ms || 0),
        Number(row.recency_at_ms || 0),
        Number(row.updated_at || 0) * 1000,
        Number(row.recency_at || 0) * 1000,
      );
      out.set(id, { title, preview, cwd, mtime });
    }
  } catch {
    // DB eski şemadaysa/kısa süre kilitliyse rollout fallback tek başına çalışır.
  } finally {
    try { db?.close(); } catch {}
  }
  return out;
}

export function __testReadCodexStateThreadMetadata(file) {
  return readCodexStateThreadMetadata(file);
}

function walkRollouts(dir, acc, depth) {
  if (depth > 6) return;
  let entries; try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walkRollouts(full, acc, depth + 1);
    else if (e.name.startsWith('rollout-') && e.name.endsWith('.jsonl')) acc.push(full);
  }
}

function sessionIdFromRollout(file) {
  const base = path.basename(file, '.jsonl');
  const m = base.match(/-([0-9a-f]{8}-[0-9a-f-]{27,})$/i);
  return m ? m[1] : base;
}

function readRolloutHead(file, lineLimit) {
  const out = { cwd: '', firstUser: '', lastText: '', turns: 0 };
  let fd; try { fd = fs.openSync(file, 'r'); } catch { return out; }
  try {
    // İlk 32 KB: cwd + firstUser + turn sayısı için yeterli.
    const HEAD_BYTES = 32 * 1024;
    const headBuf = Buffer.alloc(HEAD_BYTES);
    const headBytes = fs.readSync(fd, headBuf, 0, HEAD_BYTES, 0);
    const headText = headBuf.toString('utf-8', 0, headBytes);
    const lines = headText.split('\n');
    let parsed = 0;
    for (const line of lines) {
      if (parsed >= lineLimit * 4 && out.firstUser) break;
      const t = line.trim(); if (!t) continue;
      let r; try { r = JSON.parse(t); } catch { continue; }
      parsed++;
      if (r.type === 'session_meta' && r.payload) out.cwd = r.payload.cwd || out.cwd;
      const p = r.type === 'response_item' ? r.payload : (r.payload || r);
      if (!p) continue;
      if (p.type === 'message' && p.role === 'user') {
        const txt = textFromContent(p.content);
        if (!isInjectedUserText(txt)) { if (!out.firstUser) out.firstUser = txt; out.turns++; out.lastText = txt; }
      } else if (p.type === 'message' && p.role === 'assistant') {
        const txt = textFromContent(p.content);
        if (txt) out.lastText = txt;
      }
    }
    // Son 8 KB: lastText daha iyi tahmin (son asistan/user mesajı).
    const st = fs.fstatSync(fd);
    if (st.size > HEAD_BYTES) {
      const TAIL_BYTES = 8 * 1024;
      const tailStart = Math.max(0, st.size - TAIL_BYTES);
      const tailBuf = Buffer.alloc(Math.min(TAIL_BYTES, st.size - tailStart));
      const tailBytes = fs.readSync(fd, tailBuf, 0, tailBuf.length, tailStart);
      const tailText = tailBuf.toString('utf-8', 0, tailBytes);
      for (const line of tailText.split('\n')) {
        const t = line.trim(); if (!t) continue;
        let r; try { r = JSON.parse(t); } catch { continue; }
        const p = r.type === 'response_item' ? r.payload : (r.payload || r);
        if (!p) continue;
        if ((p.type === 'message' && (p.role === 'user' || p.role === 'assistant')) || p.type === 'reasoning') {
          const txt = textFromContent(p.content || p.text || p.summary) || p.text || '';
          if (txt && !isInjectedUserText(txt)) out.lastText = txt;
        }
      }
    }
  } finally { try { fs.closeSync(fd); } catch {} }
  return out;
}

export function __testReadRolloutHead(file, lineLimit = 4) {
  return readRolloutHead(file, lineLimit);
}

// Codex app-server, KENDI arayuzunde zengin kart olarak cizdigi isaretlemeleri
// duz metnin icine gomer ve ayiklamayi istemciye birakir:
//   ::git-commit{cwd="..."}   (leaf directive; git-push/git-stage/git-create-pr/
//                              git-create-branch/created-thread/code-comment de var)
//   <oai-mem-citation>…</oai-mem-citation>   (hafiza atifi + rollout id'leri)
// Codex'in kendi `event_msg` akisinda bunlar zaten metinden cikarilip yapisal
// alana (memory_citation) tasiniyor; biz otoriter `item.text` / rollout
// `response_item` ham metnini okudugumuz icin temizlik bize kaliyor. Aksi halde
// cevabin sonuna `::git-commit{...}` ve `MEMORY.md:40-43|note=[...]` + uuid
// dokuluyordu (kullanici raporu 29.07.2026).
const CODEX_MEM_CITATION_RE = /\n*<oai-mem-citation>[\s\S]*?(?:<\/oai-mem-citation>|$)/gi;
const CODEX_DIRECTIVE_RE = /^[ \t]*::[a-z][a-z0-9-]*\{[^\n}]*\}[ \t]*$/gim;

export function stripCodexMarkup(text) {
  const s = String(text || '');
  // Hizli cikis: mesajlarin ezici cogunlugunda bu isaretlemeler yok.
  if (!s.includes('::') && !s.includes('<oai-')) return s;
  return s
    .replace(CODEX_MEM_CITATION_RE, '')
    .replace(CODEX_DIRECTIVE_RE, '')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd();
}

function textFromContent(content) {
  if (typeof content === 'string') return stripCodexMarkup(content);
  if (Array.isArray(content)) return stripCodexMarkup(content
    .filter(c => c && (c.type === 'input_text' || c.type === 'output_text' || c.type === 'text'))
    .map(c => c.text || '').filter(txt => !isInjectedUserText(txt)).join('\n').trim());
  return '';
}
function threadUserMessageText(item) {
  if (!item) return '';
  const txt =
    item.text ||
    item.message ||
    item.content ||
    item.input ||
    (item.item && (item.item.text || item.item.content));
  return textFromContent(txt) || String(txt || '').trim();
}
export function isInjectedUserText(txt) {
  const s = String(txt || '').trim();
  if (!s) return true;
  return (
    s.startsWith('<') ||
    // Codex bu basligi "… instructions for <yol>" seklinde yaziyordu, sonra
    // sade "# AGENTS.md instructions"a gecti; " for " sart kosulunca yeni bicim
    // filtreden kacip sohbete dusuyordu. Onek artik ikisini de yakalar.
    s.startsWith('# AGENTS.md instructions') ||
    s.startsWith('<environment_context>') ||
    s.startsWith('<permissions instructions>') ||
    s.startsWith('<app-context>') ||
    s.startsWith('<collaboration_mode>') ||
    s.startsWith('<skills_instructions>') ||
    s.startsWith('<plugins_instructions>') ||
    s.startsWith('# Context from my IDE setup:')
  );
}

// Bu thread'i tasiyan mevcut kabuk. sessionsByThread YALNIZ app-server'da canli
// olan thread'leri tutar (restore edilen kabuklar bilerek eklenmez), o yuzden
// harita bosa cikinca kabuklar taranir. Eski mukerrer kayitlar icin en taze
// etkinlige sahip olan secilir.
function findShellByThread(threadId) {
  const key = String(threadId || '');
  if (!key) return null;
  const mapped = sessionsByThread.get(key);
  if (mapped && sessions.get(mapped.id) === mapped) return mapped;
  let best = null;
  for (const s of sessions.values()) {
    if (s.threadId !== key) continue;
    if (!best || (s._lastActivity || 0) > (best._lastActivity || 0)) best = s;
  }
  return best;
}

export async function adoptSession({ id, cwd } = {}) {
  // (deliberate duplicate declaration guard removed below)
  if (!id) return { ok: false, error: 'id required' };
  if (sessions.has(id)) {
    const s = sessions.get(id);
    return { ok: true, sessionId: s.id, cwd: s.cwd, model: s.model };
  }
  // Gelen kimlik bir THREAD kimligi olabilir: listDiskSessions canli oturumu
  // `live.threadId || live.id` ile listeler, yani cekmeceden acilan canli bir
  // oturum buraya threadId ile gelir. Kabuklar kendi id'leriyle anahtarli
  // oldugu icin `sessions.has(threadId)` false donuyor ve AYNI thread'e IKINCI
  // bir kabuk aciliyordu: telefonda ikinci sekme aciliyor, sessionsByThread
  // yeni kabuga kaydigi icin tur oraya akiyordu (kullanici sikayeti 02.08.2026,
  // "prompt yaziyorum 2. sekme aciliyor, orada devam ediyor").
  const existing = findShellByThread(id);
  if (existing) return { ok: true, sessionId: existing.id, cwd: existing.cwd, model: existing.model };
  const s = {
    // Adopt = thread/resume: diskteki thread'i app-server'a canli yukle. Boylece
    // hem gecmis (turns) gelir hem de sonraki turn/start AYNI thread uzerinde
    // kaldigi icin model baglami korunur. (Eski surum thread/read + threadId:null
    // yapiyordu — her adopt sonrasi taze thread acilip baglam sifirlaniyordu.)
    id, threadId: null, cwd: cwd || '', model: defaultModel(), status: 'idle',
    messages: [], toolDetails: [], subscribers: new Set(),
    awaitingFirstOutput: false, awaitingApproval: false, awaitingUserInput: false,
    pendingApproval: null, contextTokens: 0, contextWindow: 0,
    permissionMode: 'yolo', approvalPolicy: DEFAULT_APPROVAL_POLICY,
    sandboxMode: DEFAULT_SANDBOX_MODE, sandboxPolicy: { ...DEFAULT_SANDBOX_POLICY },
    _epoch: appEpoch, _turnId: null,
    _agentRows: new Map(), _detailIdx: new Map(), _fileChanges: [], _commands: [], _reasoningBufs: new Map(), _planBufs: new Map(),
    _lastActivity: 0, _booting: false, _plan: [], _planDraft: '',
  };
  try {
    await ensureInitialized();
    const r = await appSend('thread/resume', { threadId: id });
    const thread = r && r.thread;
    // thread/resume cevap verip thread tasimazsa oturum sessizce bos kurulur ve
    // sonraki mesaj YENI thread acardi (baglam kaybi). Firlat ki asagidaki
    // rollout-fallback'i gecmisi diskten kurtarsin.
    if (!thread) throw new Error('thread/resume cevabinda thread yok');
    s.cwd = thread.cwd || r.cwd || s.cwd;
    s.model = r.model || s.model;
    s.threadId = thread.id || id;
    s._epoch = appEpoch;
    sessionsByThread.set(s.threadId, s);
    // thread/read(includeTurns:true) büyük tool çıktılı oturumlarda yüzlerce MB'lik
    // tek JSON cevabı üretip bridge event-loop'unu kilitliyor. Geçmişi rollout'tan
    // akışlı yükle; yalnız rollout yoksa app-server'ın ağır yoluna düş.
    const hydrated = await hydrateHistoryFromRollout(s, s.threadId);
    if (!hydrated) {
      if (Array.isArray(thread.turns)) buildMessagesFromTurns(s, thread.turns);
      else await readTurnsInto(s, s.threadId);
    }
  } catch (e) {
    // app-server doesn't know this thread (e.g. legacy `codex exec` session).
    // Fall back to parsing the rollout JSONL on disk.
    const file = findRolloutForId(id);
    if (file) {
      const parsed = fullRolloutParse(file);
      s.cwd = parsed.cwd || s.cwd;
      s.messages = parsed.messages;
      s.toolDetails = parsed.toolDetails;
      s.model = parsed.model || s.model;
      s.contextTokens = parsed.contextTokens || 0;
      s.contextWindow = parsed.contextWindow || 0;
      s._diffHistoryGap = true;   // rollout'ta dosya değişikliği yok, bkz. hydrateHistoryFromRollout
      // Tam rollout parse = arşiv sıfırlanır (mükerrer birikme), sonra tavan uygulanır.
      clearArchivedMessages(s, { dir: BRIDGE_MESSAGE_ARCHIVE_DIR });
      capSession(s); // uzun rollout'ta mesaj tavanı — ws snapshot'ı sınırsız büyümesin
    } else {
      return { ok: false, error: 'thread not found and no rollout on disk: ' + id };
    }
  }
  if (!s.cwd || !fs.existsSync(s.cwd)) {
    // Allow adopt with cwd arg even if disk path is unknown.
    if (cwd && fs.existsSync(cwd)) s.cwd = cwd;
  }
  sessions.set(s.id, s);
  persistSessions();
  return { ok: true, sessionId: s.id, cwd: s.cwd, model: s.model };
}

// Unix saniyeyi yerel HH:MM'e cevirir; yoksa "" (UI bos saati gizler). Hydrate
// edilen gecmis mesajlarda hhmm() KULLANILMAZ — o "su anki saat"i basar ve
// reload sonrasi tum gecmis o anki saati gosterirdi.
function hhmmAt(unixSeconds) {
  if (!unixSeconds || typeof unixSeconds !== 'number') return '';
  const d = new Date(unixSeconds * 1000);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

// Rollout satırındaki ISO timestamp'ten yerel HH:MM (geçmiş codex mesaj damgası).
function hhmmFromIso(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

// thread/resume canli thread'i dondurur ama gecmis turn'leri (turns) DAHIL ETMEZ;
// gecmis yalnizca thread/read includeTurns:true ile gelir (fork yolunda kanitli
// sema, codex 0.142.2). Adopt/hydrate sonrasi mesajlar bos kalmasin diye burada
// cekilir. Basarisizlik sessizce yutulur — thread yine de canli, prompt calisir.
async function readTurnsInto(s, threadId) {
  try {
    const tr = await appSend('thread/read', { threadId, includeTurns: true });
    const thread = tr && tr.thread;
    if (thread && Array.isArray(thread.turns)) {
      s.messages = [];
      s.toolDetails = [];
      s._agentRows.clear();
      s._detailIdx.clear();
      s._fileChanges = [];
      s._commands = [];
      // Geçmiş sıfırdan kuruluyor: eski arşiv satırları kopyaya dönüşür, sıfırla.
      clearArchivedMessages(s, { dir: BRIDGE_MESSAGE_ARCHIVE_DIR });
      buildMessagesFromTurns(s, thread.turns);
    }
  } catch (e) {
    logWarn('codex-app', 'thread/read includeTurns başarısız', { threadId, error: errMessage(e) });
  }
}

function buildMessagesFromTurns(s, turns) {
  let itemCount = 0;
  for (const turn of turns) {
    const items = Array.isArray(turn.items) ? turn.items : [];
    const time = hhmmAt(turn.startedAt || turn.completedAt);
    for (const it of items) {
      appendThreadItemToMessages(s, it, time, turn.id || '');
      if (++itemCount % 100 === 0) capSession(s);
    }
  }
  // Bu yol GERÇEK thread item'larından kuruyor, yani `fileChange`ler de geldi:
  // rollout yolunun bıraktığı boşluk burada YOK (bkz. hydrateHistoryFromRollout).
  s._diffHistoryGap = false;
  capSession(s);
}

function boundedToolJson(value, maxChars = MAX_STREAM_DETAIL_CHARS) {
  let text;
  try {
    text = JSON.stringify(value, (_key, entry) => {
      if (typeof entry === 'string' && entry.length > 20_000) {
        return entry.slice(0, 20_000) + '\n[...field truncated...]';
      }
      return entry;
    }, 2);
  } catch {
    text = String(value ?? '');
  }
  if (text.length <= maxChars) return text;
  return text.slice(0, maxChars) + '\n[...detail truncated...]';
}

// Append an app-server ThreadItem (item.completed shape) to s.messages /
// s.toolDetails using the phone UI message contract. `time` verilirse (gecmis
// hydrate) o kullanilir; verilmezse canli akis oldugu icin simdiki saat dogru.
function appendThreadItemToMessages(s, item, time, turnId) {
  if (!item || !item.type) return;
  const at = time !== undefined ? time : hhmm();
  switch (item.type) {
    case 'userMessage': {
      const txt = threadUserMessageText(item);
      // Codex her turun basina KULLANICININ YAZMADIGI bloklar enjekte ediyor
      // (AGENTS.md talimatlari, environment_context, recommended_plugins...).
      // Rollout yolunda bunlar zaten eleniyordu; burada elenmiyordu ve oturum
      // hidrasyonunda (kopru restart'i sonrasi ilk prompt) sohbete AGENTS.md'nin
      // tamami dokuluyordu — canli goruldu.
      if (txt && !isInjectedUserText(txt)) s.messages.push({ role: 'user', text: txt, time: at });
      break;
    }
    case 'agentMessage': {
      const row = s._agentRows.get(item.id);
      if (row) {
        row.text = stripCodexMarkup(item.text) || row.text || '';
      } else {
        const msg = { role: 'agent', text: stripCodexMarkup(item.text), time: at };
        s.messages.push(msg);
        s._agentRows.set(item.id, msg);
      }
      break;
    }
    case 'reasoning': {
      const sum = Array.isArray(item.summary) ? item.summary.map(x => x.text || x).join('\n') : '';
      const cont = Array.isArray(item.content) ? item.content.join('\n') : '';
      const idx = s.toolDetails.length;
      s.toolDetails.push(sum || cont || '');
      s.messages.push({ role: 'thought', text: '🧠', thoughtIndex: idx });
      break;
    }
    case 'plan': {
      // Plan items are surfaced via snapshot.plan, NOT as chat messages.
      // Still stash the full text in toolDetails for thought inspection.
      const idx = s.toolDetails.length;
      s.toolDetails.push(String(item.text || ''));
      break;
    }
    case 'commandExecution': {
      const idx = s.toolDetails.length;
      s.toolDetails.push(boundedToolJson(item));
      s.messages.push({ role: 'thought', text: '$ ' + String(item.command || '').replace(/\s+/g, ' ').slice(0, 70), thoughtIndex: idx });
      s._commands.push({
        id: item.id || '',
        command: item.command || '',
        cwd: item.cwd || s.cwd,
        status: item.status || (item.exitCode != null ? 'completed' : 'running'),
        stdout: (item.stdout || '').slice(0, 1000),
        stderr: (item.stderr || '').slice(0, 1000),
        exitCode: item.exitCode,
        startedAt: item.startedAt || item.created || 0,
        completedAt: item.completedAt || 0,
      });
      if (s._commands.length > 200) s._commands.splice(0, s._commands.length - 200);
      break;
    }
    case 'fileChange': {
      const idx = s.toolDetails.length;
      s.toolDetails.push(boundedToolJson(item));
      const files = (item.changes || []).map(c => (c.path || '').split(/[\\/]/).pop()).filter(Boolean);
      s.messages.push({ role: 'thought', text: 'Edit ' + (files.join(', ').slice(0, 60) || ''), thoughtIndex: idx });
      // ŞEMA ÖLÇÜLDÜ (codex 0.147.0, `codex app-server generate-json-schema`):
      //   FileChangeThreadItem { id, type:'fileChange', status: PatchApplyStatus,
      //                          changes: FileUpdateChange[] }
      //   FileUpdateChange     { path, kind: {type:'add'|'delete'|'update'}, diff }
      // Yani dosyanın eklendiği/silindiği `kind.type`te, `status` ise ITEM
      // düzeyinde ve yamanın diske DEĞDİĞİNİ söylüyor. Eskiden `fc.status`
      // okunuyordu — o alan şemada YOK, dolayısıyla her dosya "modified"
      // görünüyordu; `summary`/`approved` de şemada yok, biri hep boş diğeri hep
      // true dönüyordu (reddedilen yama bile "onaylı" sayılıyordu).
      const applyDurumu = String(item.status || '');
      for (const fc of (Array.isArray(item.changes) ? item.changes : [item])) {
        const ham = String(fc.diff || fc.unifiedDiff || '');
        // Bütçe: 100KB dosya başına opencode ile aynı söz, ama codex'te yamalar
        // BELLEKTE duruyor (opencode her açılışta serve'den çekiyor). Oturum
        // toplamı da sınırlı olmasa 200 kayıt × 100KB = 20MB'a çıkardı. Bütçe
        // dizinin kendisinden sayılıyor, ayrı sayaç alanından değil: dizi yedi
        // ayrı yerde sıfırlanıyor ve sayaç sıfırlamayı unutmak sessizce
        // "yama gelmiyor"a dönerdi.
        const kullanilan = s._fileChanges.reduce((n, c) => n + (c.diff ? c.diff.length : 0), 0);
        const pay = Math.min(DIFF_PATCH_LIMIT, Math.max(0, CODEX_DIFF_BYTE_BUDGET - kullanilan));
        s._fileChanges.push({
          path: fc.path || '',
          status: fileChangeStatus(fc),
          // Yamanın diske DEĞİP değmediği: failed/declined olanlar dosyayı
          // değiştirmedi. '' = eski app-server, bilinmiyor (uygulandı sayılır).
          applyStatus: applyDurumu,
          diff: ham.slice(0, pay),
          diffTruncated: ham.length > pay,
          itemId: item.id || '',
          // Tur kimliği: "kaç turda dokunuldu" sayacı için. Canlı akışta
          // bildirimden, geçmiş kurulumunda turun kendi kimliğinden geliyor.
          turnId: turnId || s._turnId || '',
        });
      }
      if (s._fileChanges.length > 200) s._fileChanges.splice(0, s._fileChanges.length - 200);
      break;
    }
    case 'mcpToolCall': {
      const idx = s.toolDetails.length;
      s.toolDetails.push(boundedToolJson(item));
      s.messages.push({ role: 'thought', text: (item.server || 'mcp') + '.' + (item.tool || ''), thoughtIndex: idx });
      break;
    }
    case 'dynamicToolCall': {
      const idx = s.toolDetails.length;
      s.toolDetails.push(boundedToolJson(item));
      s.messages.push({ role: 'thought', text: String(item.tool || 'tool'), thoughtIndex: idx });
      break;
    }
    case 'webSearch': {
      const idx = s.toolDetails.length;
      s.toolDetails.push(boundedToolJson(item));
      s.messages.push({ role: 'thought', text: 'WebSearch ' + String(item.query || '').slice(0, 40), thoughtIndex: idx });
      break;
    }
    default: {
      const idx = s.toolDetails.length;
      s.toolDetails.push(boundedToolJson(item));
      s.messages.push({ role: 'thought', text: String(item.type).replace(/([A-Z])/g, ' $1').trim(), thoughtIndex: idx });
      break;
    }
  }
}

function findRolloutForId(id) {
  const files = [];
  walkRollouts(SESSIONS_DIR, files, 0);
  for (const f of files) if (f.includes(id)) return f;
  return null;
}

function fullRolloutParse(file) {
  const out = { id: '', cwd: '', model: '', messages: [], toolDetails: [], contextTokens: 0, contextWindow: 0 };
  let raw; try { raw = fs.readFileSync(file, 'utf-8'); } catch { return out; }
  for (const line of raw.split('\n')) {
    const t = line.trim(); if (!t) continue;
    let r; try { r = JSON.parse(t); } catch { continue; }
    applyRolloutRecord(out, r);
  }
  return out;
}

function applyRolloutRecord(out, r) {
  if (r.type === 'session_meta' && r.payload) { out.id = r.payload.id || out.id; out.cwd = r.payload.cwd || out.cwd; }
  if (r.type === 'turn_context' && r.payload && r.payload.model) out.model = r.payload.model;
  if (r.type === 'event_msg' && r.payload && r.payload.type === 'token_count' && r.payload.info) {
    const info = r.payload.info;
    if (info.last_token_usage && typeof info.last_token_usage.input_tokens === 'number') out.contextTokens = info.last_token_usage.input_tokens;
    if (typeof info.model_context_window === 'number' && info.model_context_window > 0) out.contextWindow = info.model_context_window;
    return;
  }
  const p = r.type === 'response_item' ? r.payload : (r.payload || r);
  if (!p) return;
  if (p.type === 'message' && p.role === 'user') {
    const txt = textFromContent(p.content); if (isInjectedUserText(txt)) return;
    out.messages.push({ role: 'user', text: txt, time: hhmmFromIso(r.timestamp) });
  } else if (p.type === 'message' && p.role === 'assistant') {
    const txt = textFromContent(p.content); if (!txt) return;
    out.messages.push({ role: 'agent', text: txt, time: hhmmFromIso(r.timestamp) });
  } else if (p.type === 'reasoning') {
    const summary = Array.isArray(p.summary) ? p.summary.map(x => x.text || '').join('') : (p.text || '');
    const idx = out.toolDetails.length; out.toolDetails.push(String(summary).slice(0, MAX_STREAM_DETAIL_CHARS));
    out.messages.push({ role: 'thought', text: '🧠', thoughtIndex: idx });
  } else if (p.type === 'function_call' || p.type === 'custom_tool_call') {
    const idx = out.toolDetails.length; out.toolDetails.push(boundedToolJson(p));
    const label = p.name === 'shell'
      ? '$ ' + String((() => { try { return JSON.parse(p.arguments || '{}').command?.join?.(' ') || ''; } catch { return ''; } })()).slice(0, 70)
      : (p.name || 'tool');
    out.messages.push({ role: 'thought', text: label, thoughtIndex: idx });
  }
}

function rolloutPayloadTypeFromPrefix(text) {
  const prefix = text.slice(0, 16 * 1024);
  const types = [];
  const re = /"type"\s*:\s*"([^"]+)"/g;
  let match;
  while (types.length < 3 && (match = re.exec(prefix))) types.push(match[1]);
  return types[0] === 'response_item' ? (types[1] || '') : (types[0] || '');
}

function appendOversizedRolloutMessage(out, text) {
  const prefix = text.slice(0, 256 * 1024);
  const suffix = text.length > prefix.length ? text.slice(-256 * 1024) : '';
  const sample = prefix + suffix;
  const role = /"role"\s*:\s*"assistant"/.test(prefix) ? 'agent' : 'user';
  const snippets = [];
  const re = /"text"\s*:\s*("(?:\\.|[^"\\])*")/g;
  let match;
  while (snippets.length < 4 && (match = re.exec(sample))) {
    try {
      const value = JSON.parse(match[1]);
      if (value && !isInjectedUserText(value)) snippets.push(String(value).slice(0, 8_000));
    } catch {}
  }
  out.messages.push({
    role,
    text: snippets.join('\n') || '[çok büyük geçmiş mesajı özetlenerek atlandı]',
    time: '',
  });
}

async function fullRolloutParseAsync(file) {
  const out = { id: '', cwd: '', model: '', messages: [], toolDetails: [], contextTokens: 0, contextWindow: 0 };
  let stream;
  try { stream = fs.createReadStream(file, { encoding: 'utf-8' }); } catch { return out; }
  const lines = createInterface({ input: stream, crlfDelay: Infinity });
  let parsedLines = 0;
  try {
    for await (const line of lines) {
      const t = line.trim();
      if (!t) continue;
      if (t.length > MAX_ROLLOUT_JSON_LINE_CHARS) {
        if (rolloutPayloadTypeFromPrefix(t) === 'message') appendOversizedRolloutMessage(out, t);
        continue;
      }
      let r; try { r = JSON.parse(t); } catch { continue; }
      applyRolloutRecord(out, r);
      if (++parsedLines % 100 === 0) await new Promise(resolve => setImmediate(resolve));
    }
  } catch {
    try { stream.destroy(); } catch {}
  }
  return out;
}

async function hydrateHistoryFromRollout(s, id) {
  const file = findRolloutForId(id);
  if (!file) return false;
  const parsed = await fullRolloutParseAsync(file);
  s.cwd = parsed.cwd || s.cwd;
  s.messages = parsed.messages;
  s.toolDetails = parsed.toolDetails;
  s.model = parsed.model || s.model;
  s.contextTokens = parsed.contextTokens || 0;
  s.contextWindow = parsed.contextWindow || 0;
  s._agentRows.clear();
  s._detailIdx.clear();
  s._fileChanges = [];
  s._commands = [];
  // ROLLOUT DOSYA DEĞİŞİKLİĞİ TAŞIMIYOR: `applyRolloutRecord` yalnız mesaj /
  // reasoning / araç çağrısı kayıtlarını okuyor; app-server'ın `fileChange`
  // item'ları orada yok (codex çekirdeği `patch_apply_end` yazıyor, ayrı bir
  // biçim). Bu yüzden geçmişi buradan kurulan oturumun değişiklik listesi BOŞ
  // başlar — ve boş liste "hiçbir dosya değişmedi" diye okunur. Bayrak o yalanı
  // engelliyor: sessionDiff `historyGap` diye bildiriyor, telefon "daha
  // öncekiler okunamıyor" yazıyor.
  s._diffHistoryGap = true;
  clearArchivedMessages(s, { dir: BRIDGE_MESSAGE_ARCHIVE_DIR });
  capSession(s);
  return true;
}

export function __testFullRolloutParseAsync(file) {
  return fullRolloutParseAsync(file);
}

/**
 * Bir native Codex thread'ine bağlı TÜM kabuklar (mapped olan başta).
 *
 * Tek değerli `sessionsByThread` yalnız en son hydrate edilen kabuğu tutar,
 * ama aynı thread birden fazla geri-yüklenmiş kabukta canlı olabiliyor. Yalnız
 * mapped olana yazmak, kullanıcının açık tuttuğu sekmeyi sağır bırakıyor.
 */
function sessionsForThread(threadId) {
  if (!threadId) return [];
  const targets = [];
  const seen = new Set();
  const mapped = sessionsByThread.get(threadId);
  if (mapped) { targets.push(mapped); seen.add(mapped.id); }
  for (const candidate of sessions.values()) {
    if (candidate.threadId === threadId && !seen.has(candidate.id)) {
      targets.push(candidate);
      seen.add(candidate.id);
    }
  }
  return targets;
}

// ── Live notification reducer ───────────────────────────────────────────────
function handleNotification(m) {
  const params = m.params || {};
  const threadId = params.threadId || (params.thread && params.thread.id);
  if (!threadId) {
    dispatchCodexNotification(m, null, notificationHandlers);
    return;
  }

  const targets = sessionsForThread(threadId);
  if (targets.length === 0) {
    dispatchCodexNotification(m, null, notificationHandlers);
    return;
  }
  for (const s of targets) {
    // Any session-scoped notification proves that the app-server/turn is alive.
    // In particular, long reasoning, agent text and command-output delta streams
    // must keep the inactivity watchdog armed.
    if (s.status === 'running') s._lastActivity = Date.now();
    dispatchCodexNotification(m, s, notificationHandlers);
  }
}

function onTurnError(s, params) {
  if (!s.awaitingFirstOutput && s.status !== 'running') return;
  const err = params.error || {};
  const msg = err.message || 'Bilinmeyen hata';
  s.messages.push({ role: 'agent', text: '[codex-app error] ' + friendlyError(msg, params.willRetry), time: hhmm() });
  capSession(s);
  if (!params.willRetry) flushThrottle(s);
  // turn/completed (status=failed) will finalize the turn on its own.
}

function onTurnStarted(s, params) {
  // Turn telefon dışından (ör. masaüstü Codex'ten ya da goal-set'in tetiklediği
  // otomatik turn) başlatılmışsa prompt() yolundan GEÇMEZ; burada da eski turn
  // artefaktlarını temizle.
  resetTurnBuffers(s);
  // KRİTİK: dıştan başlayan turn'de prompt() status'u 'running' yapmadığı için,
  // düzeltmezsek watchdog (isCandidate → status==='running') ve delta'ların
  // _lastActivity tazelemesi (handleNotification → if running) bu turn'ü İZLEMEZ,
  // UI de 'running' göstermez. Turn/started geldiği an turn canlıdır; running'e çek.
  // prompt() yolunda zaten 'running'; bu no-op olur.
  if (s.status !== 'running') s.status = 'running';
  s.awaitingFirstOutput = false;
  s._turnId = (params.turn && params.turn.id) || s._turnId;
  s._lastActivity = Date.now();
  throttledPush(s);
}

// Goal seyrek degisir (kullanici aksiyonu ya da turn sonu); delta throttle yerine
// anlik pushSnapshot yeterli ve daha net. Fan-out'u handleNotification/sessionsForThread
// zaten yapiyor — tek kabuk uzerinde calis, thread'i paylasan kardeslere elle yazma.
function onGoalUpdated(s, params) {
  s.goal = params.goal || null;
  pushSnapshot(s);
}
function onGoalCleared(s, _params) {
  s.goal = null;
  pushSnapshot(s);
}

function onTurnCompleted(s, params) {
  const turn = params.turn || {};
  if (turn.status === 'failed' && turn.error && turn.error.message) {
    if (!s.messages.some(m => m.role === 'agent' && m.text.includes(friendlyError(turn.error.message, false)))) {
      s.messages.push({ role: 'agent', text: '[codex-app hata] ' + friendlyError(turn.error.message, false), time: hhmm() });
    }
  }
  capSession(s);
  finalizeTurnIdle(s, { suppressed: false });
}

function onThreadStartedNf(s, params) {
  if (!s.threadId && params.thread && params.thread.id) {
    s.threadId = params.thread.id;
    sessionsByThread.set(s.threadId, s);
    persistSessions();
    emitThreadResolved(s);
  }
  throttledPush(s);
}

function onThreadStatusChanged(s, params) {
  const st = params.status || {};
  if (st.type === 'active') {
    const flags = Array.isArray(st.activeFlags) ? st.activeFlags : [];
    s.awaitingApproval = flags.includes('waitingOnApproval');
    s.awaitingUserInput = flags.includes('waitingOnUserInput');
  } else if (st.type === 'idle' || st.type === 'systemError') {
    // Do not clear awaitingApproval here; the server request itself (which set
    // it) is the canonical resolver; clearing on status alone could race the
    // approval event arrival.
  }
  throttledPush(s);
}

function onTokenUsage(s, params) {
  const u = params.tokenUsage || {};
  const last = u.last || {};
  if (typeof last.inputTokens === 'number') s.contextTokens = last.inputTokens;
  if (typeof u.modelContextWindow === 'number' && u.modelContextWindow > 0) s.contextWindow = u.modelContextWindow;
  throttledPush(s);
}

function onItemStarted(s, params) {
  s._lastActivity = Date.now();
  // We create lightweight rows only on item/completed (full info).
  throttledPush(s);
}

function onItemCompleted(s, params) {
  s._lastActivity = Date.now();
  if (params.item && params.item.type === 'agentMessage') {
    // Append authoritative final text. If we already streamed deltas into a
    // row, replace that text with the final version (the server's authoritative
    // text); keep ordering. _agentRows mesaj NESNESI saklar (indeks degil):
    // capMessages baştan kirpinca indeksler kayip delta'lar yanlis satirlarin
    // ustune yaziyordu ("aradaki mesajlar kayboluyor" bug'i).
    // Temizlik YALNIZ burada (delta yolunda degil): delta'lar birikirken
    // trimEnd sondaki bosluğu yiyip bir sonraki parcayi kelimeye yapistirirdi.
    // Otoriter final metin zaten satiri butunuyle degistiriyor.
    const row = s._agentRows.get(params.item.id);
    if (row) row.text = stripCodexMarkup(params.item.text);
    else {
      const msg = { role: 'agent', text: stripCodexMarkup(params.item.text), time: hhmm() };
      s.messages.push(msg);
      s._agentRows.set(params.item.id, msg);
    }
  } else if (params.item && params.item.type === 'userMessage') {
    // Skip; user row already added at prompt time.
  } else {
    // Plan item tamamlandığında taslak metni temizle.
    if (params.item && params.item.type === 'plan') s._planDraft = '';
    appendThreadItemToMessages(s, params.item, undefined, params.turnId || '');
  }
  capSession(s);
  throttledPush(s);
}

function onAgentMessageDelta(s, params) {
  const { itemId, delta } = params;
  if (!delta) return;
  let row = s._agentRows.get(itemId);
  if (!row) {
    row = { role: 'agent', text: '', time: hhmm() };
    s.messages.push(row);
    s._agentRows.set(itemId, row);
  }
  row.text = (row.text || '') + delta;
  capSession(s);
  throttledPush(s);
}

function onPlanDelta(s, params) {
  const { itemId, delta } = params;
  // Delta'ları adımlara dokunmadan ayrı bir taslak alanında biriktir.
  // Yapılandırılmış plan yalnızca turn/plan/updated ile değişir.
  s._planDraft = (s._planDraft || '') + (delta || '');
  s._lastActivity = Date.now();
  throttledPush(s);
}

function onReasoningDelta(s, params, kind) {
  const { itemId, delta } = params;
  if (!delta) return;
  let buf = s._reasoningBufs.get(itemId) || '';
  buf += delta;
  s._reasoningBufs.set(itemId, buf);
  let idx = s._detailIdx.get('reasoning:' + itemId);
  if (idx == null) {
    s.toolDetails.push(buf);
    idx = s.toolDetails.length - 1;
    s._detailIdx.set('reasoning:' + itemId, idx);
    s.messages.push({ role: 'thought', text: '🧠', thoughtIndex: idx });
  } else {
    s.toolDetails[idx] = buf;
  }
  capSession(s);
  throttledPush(s);
}

function onStreamDelta(s, params, kind) {
  // Append command/file output deltas to the corresponding tool item's
  // toolDetail, so tapping the row shows live output. We key by itemId.
  const itemId = params.itemId;
  if (!itemId) return;
  const key = kind + ':' + itemId;
  let idx = s._detailIdx.get(key);
  if (idx == null) {
    // The commandExecution/fileChange thought row is created on item/completed;
    // here we stash the buffer so the completed handler can merge it.
    idx = s.toolDetails.length;
    s.toolDetails.push('');
    s._detailIdx.set(key, idx);
  }
  appendToolDetail(s, idx, String(params.delta || ''), MAX_STREAM_DETAIL_CHARS);
  throttledPush(s);
}

function onTurnPlanUpdated(s, params) {
  const plan = Array.isArray(params.plan) ? params.plan : [];
  s._plan = plan.map(step => ({
    text: step.step || step.text || '',
    status: step.status === 'inProgress' ? 'in_progress'
          : step.status === 'completed' ? 'completed'
          : 'pending',
    itemId: step.itemId || step.id || '',
    turnId: step.turnId || s._turnId || '',
  }));
  s._lastActivity = Date.now();
  throttledPush(s);
}

function onContextCompacted(s, params) {
  s.messages.push({ role: 'agent', text: '[codex-app] bağlam sıkıştırıldı.', time: hhmm() });
  capSession(s);
  pushSnapshot(s);
}

function onServerRequestResolved(s, params) {
  // The server is telling us a prior server-request (approval/user-input) has
  // been resolved. Clear any lingering awaiting flags so the UI re-enables
  // writing.
  if (s.pendingApproval && s.pendingApproval.requestId === params.requestId) {
    clearPendingApproval(s);
    pushSnapshot(s);
  }
}

function onThreadClosed(s, params) {
  // Kapanan thread'in eslemesini dusur: sonraki prompt sessionsByThread'de
  // bulamayinca thread/resume yoluna girer (bulursa kapali thread'e turn/start
  // atip hata alirdi).
  if (s.threadId) sessionsByThread.delete(s.threadId);
  if (s.status === 'running') finalizeTurnIdle(s, { error: '[codex-app] thread kapandı.' });
}

// ── Server requests (approval / user-input flow) ────────────────────────────
function handleServerRequest(m) {
  const params = m.params || {};
  const threadId = params.threadId || (params.thread && params.thread.id);
  if (!threadId) {
    // No thread context — answer with a quick denial so the server doesn't hang.
    appReject(m.id, -32603, 'no threadId for server request');
    return;
  }
  // Bildirimler gibi server-request'ler de thread'e bağlı TÜM kabuklara
  // yansıtılır. Tek kabuğa yazmak canlıda şu hataya yol açtı (2 Ağu 2026):
  // thread durumu ("waitingOnApproval" bayrağı) bildirim yoluyla her kabuğa
  // ulaşıyor ama soru/onay yükü yalnız mapped kabuğa düşüyordu — telefonda
  // "onay bekleniyor" yanıyor, gösterilecek kart olmadığı için oturum kilitli
  // görünüyordu.
  const targets = sessionsForThread(threadId);
  if (targets.length === 0) {
    // Unknown thread (e.g. another client's thread). Decline politely.
    autoDeclineUnknownMethod(m);
    return;
  }
  const summary = approvalSummary(m.method, params);
  const description = approvalDescription(m.method, params);
  const isUserInput = m.method === 'item/tool/requestUserInput' || m.method === 'mcpServer/elicitation/request';
  // Kayıt TEK nesne, kabuklar arasında paylaşılır: hangi sekmeden cevaplanırsa
  // cevaplansın requestId aynı ve tek cevap gider.
  const record = {
    requestId: m.id,
    kind: approvalKind(m.method),
    method: m.method,
    summary,
    description,
    tool: approvalTool(m.method, params),
    options: approvalOptions(m.method, params),
    questions: isUserInput ? (params.questions || null) : null,
    rawParams: params,
  };
  for (const s of targets) {
    s.pendingApproval = record;
    s.awaitingApproval = !isUserInput;
    s.awaitingUserInput = isUserInput;
    pushSnapshot(s);
  }
}

function approvalKind(method) {
  if (method === 'item/commandExecution/requestApproval' || method === 'execCommandApproval') return 'command';
  if (method === 'item/fileChange/requestApproval' || method === 'applyPatchApproval') return 'fileEdit';
  if (method === 'item/permissions/requestApproval') return 'permissions';
  if (method === 'item/tool/requestUserInput' || method === 'mcpServer/elicitation/request') return 'question';
  return 'tool';
}

function approvalOptions(method) {
  if (method === 'item/tool/requestUserInput' || method === 'mcpServer/elicitation/request') {
    return [{ id: 'cancel', label: 'Cancel', consequence: 'cancel' }];
  }
  if (method === 'item/permissions/requestApproval') {
    return [
      { id: 'allow', label: 'Allow', consequence: 'once' },
      { id: 'deny', label: 'Deny', consequence: 'deny' },
    ];
  }
  return [
    { id: 'allow', label: 'Allow', consequence: 'once' },
    { id: 'allowForSession', label: 'Allow for session', consequence: 'session' },
    { id: 'deny', label: 'Deny', consequence: 'deny' },
    { id: 'cancel', label: 'Cancel', consequence: 'cancel' },
  ];
}

function autoDeclineUnknownMethod(m) {
  // For approvals we don't own, send a deny-shaped decision rather than an
  // error so the server-side turn keeps flowing. For tool user-input / MCP
  // elicitation we send a decline/cancel result too.
  switch (m.method) {
    case 'item/commandExecution/requestApproval':
    case 'item/fileChange/requestApproval':
      appRespond(m.id, { decision: 'decline' }); break;
    case 'item/permissions/requestApproval':
      appRespond(m.id, { permissions: null, scope: 'turn' }); break;
    case 'item/tool/requestUserInput':
      appRespond(m.id, { answers: {} }); break;
    case 'mcpServer/elicitation/request':
      appRespond(m.id, { action: 'decline' }); break;
    case 'applyPatchApproval':
    case 'execCommandApproval':
      appRespond(m.id, { decision: 'denied' }); break;
    default:
      appReject(m.id, -32601, 'method not handled by bridge');
  }
}

function approvalSummary(method, p) {
  switch (method) {
    case 'item/commandExecution/requestApproval':
      return '$ ' + String(p.command || '').replace(/\s+/g, ' ').slice(0, 100);
    case 'item/fileChange/requestApproval': return 'Dosya değişikliği' + (p.grantRoot ? ' (' + p.grantRoot + ')' : '');
    case 'item/permissions/requestApproval': return 'İzin talebi: ' + (p.reason || 'ek yetki');
    case 'item/tool/requestUserInput': return 'Soru: ' + (p.questions && p.questions[0] ? p.questions[0].question : '');
    case 'mcpServer/elicitation/request': return 'MCP elicitation';
    case 'applyPatchApproval': return 'Patch uygula' + (p.callId ? ' (' + p.callId + ')' : '');
    case 'execCommandApproval': return '$ ' + (Array.isArray(p.command) ? p.command.join(' ') : p.command || '').slice(0, 100);
    default: return method;
  }
}
function approvalDescription(method, p) {
  if (p.reason) return String(p.reason);
  if (method === 'item/fileChange/requestApproval') return 'Ajan dosya değişikliği yapmak istiyor.';
  if (method === 'item/permissions/requestApproval') return 'Ajan çalışma zamanı izinleri talep ediyor.';
  if (method === 'item/tool/requestUserInput') return 'Ajan ek kullanıcı girdisi istiyor.';
  return '';
}
function approvalTool(method, p) {
  if (method === 'item/commandExecution/requestApproval' || method === 'execCommandApproval') return 'shell';
  if (method === 'item/fileChange/requestApproval' || method === 'applyPatchApproval') return 'patch';
  if (method === 'item/permissions/requestApproval') return 'permissions';
  if (method === 'item/tool/requestUserInput') return 'requestUserInput';
  if (method === 'mcpServer/elicitation/request') return 'mcp';
  return method;
}

// ── Finalize / idle ─────────────────────────────────────────────────────────
function finalizeTurnIdle(s, opts = {}) {
  turnLifecycle.finalizeTurnIdle(s, opts);
}

function friendlyError(raw, willRetry) {
  const txt = String(raw || '');
  try {
    // Server error bodies are sometimes embedded JSON strings; surface the
    // inner message when present.
    const j = JSON.parse(txt);
    if (j && j.error && j.error.message) return j.error.message + (willRetry ? ' (yeniden deneniyor)' : '');
  } catch { /* not JSON */ }
  return (txt || 'bilinmeyen hata') + (willRetry ? ' (yeniden deneniyor)' : '');
}
function errMessage(e) {
  if (!e) return 'bilinmeyen hata';
  if (typeof e === 'string') return e;
  if (e.message) return e.message;
  try { return JSON.stringify(e); } catch { return String(e); }
}
function isEmptyRpcError(e) {
  if (!e || typeof e !== 'object') return false;
  if (e.message || e.code || e.data) return false;
  return Object.keys(e).length === 0;
}

// ── Feature 10: Schema/Version Info ─────────────────────────────────────────
let _cachedCodexVersion = null;
let _versionCheckedAt = 0;
const VERSION_CACHE_MS = 5 * 60_000;

async function detectCodexVersion() {
  if (_cachedCodexVersion && (Date.now() - _versionCheckedAt) < VERSION_CACHE_MS) return _cachedCodexVersion;
  // Windows npm .cmd shim'i gerekiyorsa resolver onu açıkça cmd.exe üzerinden
  // shell:false ile çalıştırır; gerçek executable varsa doğrudan başlatır.
  const r = await spawnResolvedAsync('codex', ['--version'], { encoding: 'utf-8', timeout: 5000, windowsHide: true });
  const out = String(r.stdout || '').trim();
  const m = out.match(/[\d.]+/);
  _cachedCodexVersion = (m ? m[0] : out.split(/\s+/)[0]) || 'unknown';
  _versionCheckedAt = Date.now();
  return _cachedCodexVersion;
}

// Sürüm karşılaştırma: "0.142.2" >= "0.142" → true, "0.141" >= "0.142" → false.
// Bilinmeyen sürüm her zaman gte kabul edilir (conservative: özellikleri göstermek gizlemekten iyidir).
function versionGte(ver, min) {
  if (!ver || ver === 'unknown') return true;
  const a = String(ver).split('.').map(s => parseInt(s, 10) || 0);
  const b = String(min).split('.').map(s => parseInt(s, 10) || 0);
  const n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) {
    const x = a[i] || 0, y = b[i] || 0;
    if (x > y) return true;
    if (x < y) return false;
  }
  return true;
}

// Kurulu Codex kullanıcı skill'leri: $CODEX_HOME/skills/<ad>/SKILL.md (Agent Skills
// formatı — Claude skill'leriyle birebir aynı; skill-installer sistem skill'i de bu
// dizine kurar). .system altındaki yerleşikler ve nokta-önekli girdiler listelenmez;
// cowork barı kullanıcı skill'lerini (docx/xlsx/pptx/pdf) gösterir.
// scripts/setup-cowork-skills.ps1 aynı skill'leri bu dizine de kurar.
export function listCodexSkills(dir = path.join(CODEX_HOME, 'skills')) {
  return readSkillCatalog([dir]).map(s => s.name);
}

// app-server native envanter cevaplarını telefonun kullandığı yalın şekle çevir.
// `skills/list` birden fazla cwd döndürebilir; aynı global skill her cwd'de
// tekrarlandığı için ada göre tekilleştirilir. MCP tarafında araç şemalarını
// telefona taşımıyoruz, yalnız runtime'da gerçekten kayıtlı sunucu ve araç sayısı
// gösterilir.
export function shapeNativeInventory(skillsResult, mcpResult) {
  const skillMap = new Map();
  for (const group of Array.isArray(skillsResult?.data) ? skillsResult.data : []) {
    for (const skill of Array.isArray(group?.skills) ? group.skills : []) {
      const name = String(skill?.name || '').trim();
      if (!name || skill?.enabled === false || skillMap.has(name)) continue;
      skillMap.set(name, {
        name,
        description: String(skill?.description || '').trim(),
        scope: String(skill?.scope || ''),
        path: String(skill?.path || ''),
      });
    }
  }
  const skillDetails = [...skillMap.values()].sort((a, b) => a.name.localeCompare(b.name));
  const mcpServers = (Array.isArray(mcpResult?.data) ? mcpResult.data : [])
    .map(server => ({
      name: String(server?.name || '').trim(),
      enabled: true,
      type: 'runtime',
      status: `${Object.keys(server?.tools || {}).length} araç · ${String(server?.authStatus || 'unknown')}`,
      managed: true,
      toolCount: Object.keys(server?.tools || {}).length,
      authStatus: String(server?.authStatus || ''),
    }))
    .filter(server => server.name)
    .sort((a, b) => a.name.localeCompare(b.name));
  return { skills: skillDetails.map(s => s.name), skillDetails, mcpServers };
}

function inventoryCwds() {
  return [...new Set([
    process.cwd(),
    ...[...sessions.values()].map(s => s?.cwd),
  ].filter(Boolean).map(String))];
}

export async function getNativeInventory({ forceReload = false } = {}) {
  await ensureInitialized();
  const [skillsResult, mcpResult] = await Promise.all([
    appSend('skills/list', { cwds: inventoryCwds(), forceReload: !!forceReload }),
    appSend('mcpServerStatus/list', {}),
  ]);
  return shapeNativeInventory(skillsResult, mcpResult);
}

// Config tabanlı Codex MCP listesine native runtime durumunu ekler. `codex_apps`
// gibi yalnız app-server'ın bildiği yönetilen connector'lar da böyle görünür.
export function mergeMcpServerInventory(configuredResult, nativeServers) {
  const configured = Array.isArray(configuredResult?.servers) ? configuredResult.servers : [];
  const nativeByName = new Map((Array.isArray(nativeServers) ? nativeServers : []).map(s => [s.name, s]));
  const servers = configured.map(server => {
    const runtime = nativeByName.get(server.name);
    nativeByName.delete(server.name);
    return runtime ? { ...server, status: runtime.status || server.status, managed: !!server.managed } : server;
  });
  for (const runtime of nativeByName.values()) servers.push(runtime);
  servers.sort((a, b) => a.name.localeCompare(b.name));
  return { ...configuredResult, ok: configuredResult?.ok !== false, servers };
}

export async function getInfo() {
  const ver = await detectCodexVersion();
  const diskSkillDetails = readSkillCatalog([path.join(CODEX_HOME, 'skills')]);
  let inventory = {
    skills: diskSkillDetails.map(s => s.name),
    skillDetails: diskSkillDetails,
    mcpServers: [],
  };
  // Birim testleri saf ve CLI-bağımsız kalsın. Üretimde native API başarısızsa
  // eski disk envanteri çalışmaya devam eder; /info sırf app-server geçici olarak
  // açılamadı diye tüm skill listesini kaybetmez.
  if (!process.env.NODE_TEST_CONTEXT) {
    try { inventory = await getNativeInventory(); }
    catch (error) { logWarn('codex-app', 'native envanter alınamadı; disk fallback kullanılıyor', { error: errMessage(error) }); }
  }
  const appServerAlive = !!(appServer && !appServer.stdin.destroyed && appServer.exitCode == null);
  // Feature flags: sürüm-kapılı bayraklar (detectCodexVersion'dan gelen gerçek değer).
  // app-server ilk 0.142'de geldi; compact 0.142+ gibi bir sürümle eklendi.
  const gte142 = versionGte(ver, '0.142');
  const features = {
    threadFork: gte142,        // thread/fork in app-server since initial release
    turnSteer: gte142,         // turn/steer in app-server since initial release
    compact: gte142,           // thread/compact/start
    approvals: gte142,         // item/*/requestApproval
    plan: gte142,              // turn/plan/updated + item/plan/delta
    tokenUsage: gte142,        // thread/tokenUsage/updated
    items: gte142,             // item/started|completed|agentMessage/delta etc.
  };
  return {
    ok: true,
    codexVersion: ver,
    appServer: appServerAlive,
    appServerPid: appServerAlive ? appServer.pid : null,
    initialized,
    features,
    skills: inventory.skills,
    skillDetails: inventory.skillDetails,
    mcpServers: inventory.mcpServers,
  };
}

// ── Feature 1: Thread Fork ──────────────────────────────────────────────────
export async function forkSession({ sessionId, cwd } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.threadId) return { ok: false, error: 'thread not started' };
  if (s.status === 'running') return { ok: false, error: 'cannot fork a running session' };
  try {
    await ensureInitialized();
    const r = await appSend('thread/fork', { threadId: s.threadId });
    const newThreadId = r?.thread?.id || null;
    if (!newThreadId) return { ok: false, error: 'fork did not return a new thread id' };
    // Create a new bridge session sharing model/cwd from the parent.
    const newSession = {
      id: randomUUID(),
      threadId: newThreadId,
      cwd: cwd || s.cwd,
      model: s.model,
      permissionMode: s.permissionMode,
      effort: s.effort || '',
      approvalPolicy: s.approvalPolicy,
      sandboxMode: s.sandboxMode,
      sandboxPolicy: { ...s.sandboxPolicy },
      status: 'idle',
      messages: [],
      toolDetails: [],
      subscribers: new Set(),
      awaitingFirstOutput: false,
      awaitingApproval: false,
      awaitingUserInput: false,
      pendingApproval: null,
      contextTokens: 0,
      contextWindow: 0,
      _epoch: appEpoch,
      _turnId: null,
      _agentRows: new Map(),
      _detailIdx: new Map(),
      _fileChanges: [],
      _commands: [],
      _reasoningBufs: new Map(),
      _planBufs: new Map(),
      _lastActivity: 0,
      _booting: false,
      _plan: [],
      _planDraft: '',
    };
    // Hydrate the new session's transcript from the forked thread.
    try {
      const tr = await appSend('thread/read', { threadId: newThreadId, includeTurns: true });
      if (tr && tr.thread && Array.isArray(tr.thread.turns)) {
        buildMessagesFromTurns(newSession, tr.thread.turns);
      }
    } catch { /* fork succeeded even if transcript hydration fails */ }
    sessions.set(newSession.id, newSession);
    sessionsByThread.set(newThreadId, newSession);
    persistSessions();
    return { ok: true, sessionId: newSession.id, threadId: newThreadId, cwd: newSession.cwd, model: newSession.model };
  } catch (e) { return { ok: false, error: errMessage(e) }; }
}

// ── Fork (buradan çatalla) ──────────────────────────────────────────────────
// Orijinal oturuma dokunmadan belirli mesaja kadar kopya çıkarır: önce thread
// bütünüyle çatallanır (thread/fork), sonra KOPYA geri sarılır (rewindSession →
// thread/rollback). Rollback kopyada başarısız olursa yarım kopya silinir ki
// listede kafa karıştıran hayalet oturum kalmasın.
export async function forkFromMessage({ sessionId, dropUserTurns } = {}) {
  const drop = Math.floor(Number(dropUserTurns));
  if (!Number.isFinite(drop) || drop < 1) return { ok: false, error: 'dropUserTurns >= 1 olmalı' };
  const f = await forkSession({ sessionId });
  if (!f.ok) return f;
  if (drop > 0) {
    const r = await rewindSession({ sessionId: f.sessionId, dropUserTurns: drop });
    if (!r.ok) {
      const ns = sessions.get(f.sessionId);
      if (ns) { sessionsByThread.delete(ns.threadId); sessions.delete(f.sessionId); persistSessions(); }
      return { ok: false, error: 'çatal geri sarılamadı: ' + r.error };
    }
  }
  return { ok: true, sessionId: f.sessionId, cwd: f.cwd, model: f.model };
}

// ── Rewind (mesaja geri dön) ────────────────────────────────────────────────
// codex app-server thread/rollback { threadId, numTurns }: thread'in SONUNDAN
// numTurns turn düşürür (0.142 şemasıyla doğrulandı). Turn ≈ bir kullanıcı
// mesajı + ajan cevabı döngüsü olduğundan dropUserTurns doğrudan numTurns'e
// eşlenir (turn/steer ile aynı turn'e eklenen ikinci kullanıcı satırı nadir
// bir sapma yaratabilir — v1'de kabul). Sonra transcript thread/read ile
// yeniden kurulur; sessionId değişmez.
export async function rewindSession({ sessionId, dropUserTurns } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const drop = Math.floor(Number(dropUserTurns));
  if (!Number.isFinite(drop) || drop < 1) return { ok: false, error: 'dropUserTurns >= 1 olmalı' };
  if (s.status === 'running') return { ok: false, error: 'tur sürerken geri dönülemez' };
  if (!s.threadId) return { ok: false, error: 'thread başlatılmamış' };
  try {
    await ensureInitialized();
    await appSend('thread/rollback', { threadId: s.threadId, numTurns: drop });
    await readTurnsInto(s, s.threadId);
    pushSnapshot(s);
    persistSessions();
    return { ok: true, sessionId: s.id };
  } catch (e) { return { ok: false, error: errMessage(e) }; }
}

// ── Feature 2: Turn Steer ───────────────────────────────────────────────────
export async function steerTurn({ sessionId, text } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!text || !String(text).trim()) return { ok: false, error: 'text required' };
  if (!s.threadId || !s._turnId) return { ok: false, error: 'no active turn to steer' };
  if (s.status !== 'running') return { ok: false, error: 'no active turn to steer' };
  try {
    await ensureInitialized();
    await appSend('turn/steer', {
      threadId: s.threadId,
      turnId: s._turnId,
      expectedTurnId: s._turnId,
      input: [{ type: 'text', text: String(text) }],
    });
    // Add a user row so the steering input is visible in the transcript.
    s.messages.push({ role: 'user', text: String(text), time: hhmm() });
    s.lastUserAt = Date.now();
    capSession(s);
    s._lastActivity = Date.now();
    pushSnapshot(s);
    return { ok: true, sessionId: s.id };
  } catch (e) { return { ok: false, error: errMessage(e) }; }
}

// ── Feature 4: Diff/File Change Panel ───────────────────────────────────────
//
// Telefondaki "Değişiklikler" görünümü opencode ile ORTAK (aynı Compose gövdesi,
// aynı JSON şeması). Dönüşüm KÖPRÜDE yapılıyor, Android'de değil, üç ölçülmüş
// sebeple:
//
//   1. Codex yükünde additions/deletions YOK — yama metninden sayılması
//      gerekiyor. Aynı sayım opencode tarafında zaten serve'den geliyor; sayacı
//      Kotlin'e taşımak mantığı iki dilde çoğaltırdı.
//   2. Aynı dosyaya birden çok `fileChange` item'ında dokunuluyor; liste yol
//      bazlı BİRLEŞTİRİLMEK ZORUNDA — telefondaki LazyColumn `key = { path }`
//      kullanıyor, yinelenen anahtar çalışma anında çöker.
//   3. Kırpma köprünün bilgisi: yamalar buraya girerken kesiliyor (aşağıdaki
//      bütçe), Android o kaybı dışarıdan göremez. "Kırpıldı" satırını dürüstçe
//      ancak burası söyleyebilir.
//
// Codex'te opencode'un "tur diff'i" gibi donmuş bir anlık görüntü yok: kaynak,
// akan `item/completed` bildirimlerinden biriktirilen `_fileChanges` dizisi.
// Bunun bilinen sınırı: geçmişi rollout dosyasından kurulan oturumlarda
// (`hydrateHistoryFromRollout`) dizi boş kalır — rollout'ta app-server'ın
// fileChange item'ları değil, codex çekirdeğinin `patch_apply_end` olayları var.
// `thread/items/list` bu boşluğu kapatabilirdi ama ölçüldü (0.147.0):
// `experimentalApi` yeteneği isteniyor, köprü onu bildirmiyor.
// Kırpma sınırları dosyanın başında (DIFF_PATCH_LIMIT / CODEX_DIFF_BYTE_BUDGET /
// DIFF_FILE_LIMIT) — ilk ikisi bu bölümde değil, yamaların BİRİKTİĞİ
// `appendThreadItemToMessages` içinde kullanılıyor.

/** `kind.type` → arayüzün dili. Eski app-server'da `kind` yoksa `status`a düşer. */
function fileChangeStatus(fc) {
  const kind = fc && fc.kind && typeof fc.kind === 'object' ? String(fc.kind.type || '') : '';
  if (kind === 'add') return 'added';
  if (kind === 'delete') return 'deleted';
  if (kind === 'update') return 'modified';
  const eski = String((fc && fc.status) || '');
  return ['added', 'deleted', 'modified'].includes(eski) ? eski : 'modified';
}

/**
 * Yamadaki eklenen/silinen satır sayısı.
 *
 * `+++`/`---` BAŞLIK satırları sayılmaz: birleşik diff'te dosya başlıkları da
 * artı/eksiyle başlıyor ve sayılsalardı her dosya en az "+1 −1" görünürdü.
 */
function patchSayaclari(patch) {
  let additions = 0, deletions = 0;
  for (const satir of String(patch || '').split('\n')) {
    if (satir.startsWith('+++') || satir.startsWith('---')) continue;
    if (satir.startsWith('+')) additions++;
    else if (satir.startsWith('-')) deletions++;
  }
  return { additions, deletions };
}

/**
 * Yolu proje köküne göreli hâle getirir ve ayraçları '/' yapar.
 *
 * Codex mutlak yol veriyor (ölçüldü: `C:\Users\<you>\proje\lib\x.dart`).
 * Telefonda satır dar: mutlak yol yazılırsa ayırt edici kısım (dosya adı)
 * kırpmaya kurban gidiyor. Kök dışındaki dosya mutlak kalır — yalan söylemek
 * yerine uzun görünmek yeğdir.
 */
function diffGoreliYol(mutlak, cwd) {
  const p = String(mutlak || '').replace(/\\/g, '/');
  const kok = String(cwd || '').replace(/\\/g, '/').replace(/\/+$/, '');
  if (!kok) return p;
  // Windows'ta sürücü harfi büyük/küçük olabiliyor; karşılaştırma o yüzden
  // küçültülmüş kopya üzerinden, dönen değer HAM yoldan kesiliyor.
  if (p.toLowerCase().startsWith(kok.toLowerCase() + '/')) return p.slice(kok.length + 1);
  return p;
}

/**
 * "Değişiklikler" yükü — opencode-app'in `sessionDiff` şemasının BİREBİR aynısı
 * ({ files:[{path,additions,deletions,status,patch,truncated}], additions,
 * deletions, turns, truncated }), çünkü telefonda ikisini de aynı gövde çiziyor.
 *
 * Uygulanmayan yamalar (failed/declined) LİSTEYE GİRMEZ: soru "bu oturum neyi
 * değiştirdi" ve o dosyalar diske hiç dokunmadı; onları da göstermek reddedilen
 * bir düzenlemeyi yapılmış gibi okutur.
 */
export function sessionDiff({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const byPath = new Map();
  const turlar = new Set();
  let tasan = false;
  for (const fc of (s._fileChanges || [])) {
    if (fc.applyStatus === 'failed' || fc.applyStatus === 'declined') continue;
    const yol = diffGoreliYol(fc.path, s.cwd);
    if (!yol) continue;
    if (fc.turnId) turlar.add(fc.turnId);
    const { additions, deletions } = patchSayaclari(fc.diff);
    const varOlan = byPath.get(yol);
    if (varOlan) {
      // Aynı dosyaya birden çok kez dokunulmuş: sayılar TOPLANIR ("net" değil),
      // yamalar ARDIŞIK eklenir — tek yamaya kaynatmak satır numaralarını
      // yanlış yapardı. Durum birleşimi opencode-app'teki `mergeDiffEntry` ile
      // aynı: silme son sözü söyler, ekleme (dosya bu oturumdan önce yoktu)
      // sonraki düzenlemelerde de korunur.
      varOlan.additions += additions;
      varOlan.deletions += deletions;
      if (fc.status === 'deleted') varOlan.status = 'deleted';
      else if (varOlan.status !== 'added' && fc.status === 'added') varOlan.status = 'added';
      if (fc.diff) varOlan.patch = varOlan.patch ? varOlan.patch + '\n' + fc.diff : fc.diff;
      if (fc.diffTruncated) varOlan.truncated = true;
      continue;
    }
    if (byPath.size >= DIFF_FILE_LIMIT) { tasan = true; continue; }
    byPath.set(yol, {
      path: yol,
      additions,
      deletions,
      status: fc.status || 'modified',
      patch: String(fc.diff || ''),
      truncated: !!fc.diffTruncated,
    });
  }
  // Sıralama opencode-app ile AYNI (yola göre alfabetik): aynı ekran iki
  // backend'de farklı sırada dosya gösterirse liste "değişmiş" gibi okunuyor.
  const files = [...byPath.values()].sort((a, b) => a.path.localeCompare(b.path));
  return {
    ok: true,
    sessionId: s.id,
    cwd: s.cwd,
    files,
    additions: files.reduce((n, f) => n + f.additions, 0),
    deletions: files.reduce((n, f) => n + f.deletions, 0),
    turns: turlar.size,
    // Liste eksik mi: dosya tavanı aşıldı ya da bir patch kırpıldı.
    truncated: tasan || files.some(f => f.truncated),
    // Geçmiş rollout'tan kuruldu — köprü yeniden başlamadan (ya da eski bir
    // oturum açılmadan) önceki değişiklikler bu listede YOK. Telefon boş listeyi
    // "hiçbir dosya değişmedi" diye yazmadan önce bunu bilmeli.
    historyGap: !!s._diffHistoryGap,
  };
}

// Returns file changes from the session's pre-built _fileChanges array
// (populated during appendThreadItemToMessages — no full JSON.parse per call).
export function getChanges(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  return { ok: true, changes: s._fileChanges || [], sessionId };
}

// ── Feature 5: Command Execution Timeline ────────────────────────────────────
// Returns commands from the session's pre-built _commands array
// (populated during appendThreadItemToMessages — no full JSON.parse per call).
export function getCommands(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  return { ok: true, commands: s._commands || [], sessionId };
}

// ── Feature 8 helper: context fill percentage ────────────────────────────────
export function contextPercent(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return 0;
  if (!s.contextWindow || s.contextWindow <= 0) return 0;
  return Math.min(100, Math.round((s.contextTokens / s.contextWindow) * 100));
}

// ── Feature 6: Permission Mode Selector ──────────────────────────────────────
const VALID_PERMISSION_MODES = ['yolo', 'ask', 'on-request', 'on-failure', 'untrusted', 'never', 'plan'];
export const PERMISSION_MODES = [
  { id: 'yolo', label: 'YOLO' },
  { id: 'ask', label: 'Ask' },
  { id: 'on-failure', label: 'On Failure' },
  { id: 'untrusted', label: 'Untrusted' },
];

export function setPermissionMode({ sessionId, permissionMode } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!permissionMode || !String(permissionMode).trim()) return { ok: false, error: 'permissionMode required' };
  const mode = String(permissionMode).trim().toLowerCase();
  if (!VALID_PERMISSION_MODES.includes(mode)) return { ok: false, error: 'invalid permission mode: ' + mode + '. Valid: ' + VALID_PERMISSION_MODES.join(', ') };
  if (s.status === 'running') return { ok: false, error: 'cannot change permission mode while running' };
  s.permissionMode = mode;
  const { approvalPolicy, sandbox } = policyForMode(mode);
  s.approvalPolicy = approvalPolicy;
  s.sandboxMode = sandbox;
  s.sandboxPolicy = sandboxPolicyFor(sandbox);
  persistSessions();
  return { ok: true, permissionMode: mode };
}

// ── Feature 7: Thread Search, Pin, Archive ───────────────────────────────────
const BRIDGE_ARCHIVE_FILE = path.join(BRIDGE_STATE_DIR, 'bridge-archive.json');
const BRIDGE_PINS_FILE = path.join(BRIDGE_STATE_DIR, 'bridge-pins.json');
const BRIDGE_TITLES_FILE = path.join(BRIDGE_STATE_DIR, 'bridge-titles.json');

function readBridgeJson(file) {
  try { if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf-8')); } catch {}
  return {};
}

function writeBridgeJson(file, data) {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf-8');
    return true;
  } catch { return false; }
}

export function archiveThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const archive = readBridgeJson(BRIDGE_ARCHIVE_FILE);
  archive[id] = Date.now();
  writeBridgeJson(BRIDGE_ARCHIVE_FILE, archive);
  return { ok: true, archived: true };
}

export function unarchiveThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const archive = readBridgeJson(BRIDGE_ARCHIVE_FILE);
  delete archive[id];
  writeBridgeJson(BRIDGE_ARCHIVE_FILE, archive);
  return { ok: true, archived: false };
}

// Oturumu app'ten VE diskten sil. Codex thread'lerinin otoriter kaydı codex'in
// kendi app-server state-db'sindedir (thread/delete API'si yok); bu yüzden:
//  1) rollout .jsonl'ini diskten kaldır (transcript verisi gider),
//  2) bridge archive metadata'sına işaretle → telefon listesi (disk-sessions-search,
//     archived:false) oturumu bir daha göstermez.
// Canlı oturum varsa önce durdurulur ve bellekten düşer.
export async function deleteDiskSession({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  // Çekmece disk kaydını (threadId) listeler, bellek haritası köprü id'siyle
  // anahtarlı. Genelde ikisi aynı ama adopt yollarında ayrışabiliyor; ayrıştığı
  // anda canlı oturum bellekte kalıp sohbeti servis etmeye devam eder
  // (opencode'da tam olarak bu yaşandı, 1 Ağu 2026).
  const s = sessions.get(id) || [...sessions.values()].find(x => x.threadId === id) || null;
  if (s) {
    try { await stop(s.id); } catch {}
    clearArchivedMessages(s, { dir: BRIDGE_MESSAGE_ARCHIVE_DIR });
    sessions.delete(s.id);
    // Kalicilik dosyasina YAZ. Eksikti: kayit yalniz bellekten dusuyordu, dosya
    // eski halinde kaliyordu ve restart'ta silinmis oturum bos bir kabuk olarak
    // geri geliyordu. 20.08.2026'da olculdu: budama 121 oturumu sildi, restart
    // sonrasi 63'u geri gelmisti (rollout'lari gercekten silinmis oldugu icin
    // icleri bostu, ama kayit olarak duruyorlardi).
    persistSessions();
  }
  // Rollout dosyası: id thread'inkiyle aynı olmayabilir; hem verilen id'yi hem
  // canlı oturumun threadId'sini dene.
  const file = findRolloutForId(id) || (s && s.threadId ? findRolloutForId(s.threadId) : null);
  if (file && fs.existsSync(file)) {
    try { fs.unlinkSync(file); }
    catch (e) { return { ok: false, error: 'rollout silinemedi: ' + (e.message || String(e)) }; }
  }
  // Telefon listesinden gizle (codex state-db'de thread kalsa da drawer görmez).
  const archive = readBridgeJson(BRIDGE_ARCHIVE_FILE);
  archive[id] = Date.now();
  if (s && s.threadId) archive[s.threadId] = Date.now();
  writeBridgeJson(BRIDGE_ARCHIVE_FILE, archive);
  _diskCache = { at: 0, list: null };
  return { ok: true, id };
}

// Kullanıcı başlığı: transcript'ten türetilen firstUser başlığının önüne geçer.
// Boş = özel başlığı kaldır.
export function renameThread({ id, title } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const titles = readBridgeJson(BRIDGE_TITLES_FILE);
  const clean = String(title || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  if (clean) titles[id] = clean;
  else delete titles[id];
  writeBridgeJson(BRIDGE_TITLES_FILE, titles);
  return { ok: true, title: clean };
}

export function pinThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const pins = readBridgeJson(BRIDGE_PINS_FILE);
  pins[id] = Date.now();
  writeBridgeJson(BRIDGE_PINS_FILE, pins);
  return { ok: true, pinned: true };
}

export function unpinThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const pins = readBridgeJson(BRIDGE_PINS_FILE);
  delete pins[id];
  writeBridgeJson(BRIDGE_PINS_FILE, pins);
  return { ok: true, pinned: false };
}

// Update listDiskSessions to support search query and archive/pin filtering
export async function listDiskSessionsWithQuery({ query = '', archived = null, pinned = null } = {}) {
  const base = await listDiskSessions();
  if (!base.ok) return base;
  // Modul-seviye `sessions` Map'ini gölgelememek için diskSessions olarak adlandirdik.
  let diskSessions = base.sessions;
  // Load archive/pin metadata
  const archiveData = readBridgeJson(BRIDGE_ARCHIVE_FILE);
  const pinData = readBridgeJson(BRIDGE_PINS_FILE);
  const titleData = readBridgeJson(BRIDGE_TITLES_FILE);
  // Annotate each session with archived/pinned status + kullanıcı başlığı (varsa).
  diskSessions = diskSessions.map(s => ({
    ...s,
    title: titleData[s.id] || s.title,
    archived: !!archiveData[s.id],
    pinned: !!pinData[s.id],
  }));
  // Filter by query (search title + cwd + lastText)
  if (query && String(query).trim()) {
    const q = String(query).trim().toLowerCase();
    diskSessions = diskSessions.filter(s =>
      (s.title || '').toLowerCase().includes(q) ||
      (s.cwd || '').toLowerCase().includes(q) ||
      (s.lastText || '').toLowerCase().includes(q)
    );
  }
  // Filter by archive status
  if (archived === true) diskSessions = diskSessions.filter(s => s.archived);
  else if (archived === false) diskSessions = diskSessions.filter(s => !s.archived);
  // Sort: pinned first, then by mtime desc
  diskSessions.sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    return (b.mtime || 0) - (a.mtime || 0);
  });
  return { ok: true, sessions: diskSessions };
}

// ── Session persistence (bridge restart dayanikliligi) ──────────────────────
// Oturum haritasi bellekteydi; bridge her yeniden basladiginda telefonun oturum
// listesi bosaliyordu ("oturumlar rastgele kayboluyor"). Mesaj gecmisini codex
// zaten diske yaziyor (thread rollouts); bizim kalicilastirmamiz gereken tek sey
// telefon oturum kimligi -> threadId eslemesi + oturum ayarlari. Acilista bu
// kabuklar geri yuklenir; mesajlar ilk eriste thread/resume ile hydrate edilir.
const BRIDGE_SESSIONS_FILE = path.join(BRIDGE_STATE_DIR, 'bridge-codex-app-sessions.json');
const PERSIST_MAX = 100;
const PERSIST_DEBOUNCE_MS = 500;
let _persistTimer = null;

// node --test kosulari test dosyalarini NODE_TEST_CONTEXT ile calistirir;
// testlerin newSession cagrilari gercek kullanicinin oturum dosyasini
// kirletmesin diye persist/restore test baglaminda devre disi.
const PERSIST_DISABLED = !!process.env.NODE_TEST_CONTEXT;

function persistSessions() {
  if (PERSIST_DISABLED) return;
  if (_persistTimer) return;
  _persistTimer = setTimeout(() => {
    _persistTimer = null;
    const list = dedupeShellsByThread(
      [...sessions.values()]
        .filter(s => !isSpikeCwd(s.cwd))
        // Sirali: dedupe "listedeki ilk" derken en son kullanilani kastediyor.
        .sort((a, b) => (b._lastActivity || 0) - (a._lastActivity || 0)))
      // Mukerrer kabuk diske YAZILMASIN: aksi halde restart'taki ayiklama her
      // seferinde ayni artigi temizlemek zorunda kalirdi.
      .slice(0, PERSIST_MAX)
      .map(s => ({
        id: s.id,
        threadId: s.threadId || null,
        cwd: s.cwd || '',
        model: s.model || defaultModel(),
        permissionMode: s.permissionMode || 'yolo',
        effort: s.effort || '',
        lastActivity: s._lastActivity || 0,
      }));
    writeBridgeJson(BRIDGE_SESSIONS_FILE, { sessions: list });
  }, PERSIST_DEBOUNCE_MS);
  if (_persistTimer.unref) _persistTimer.unref();
}

/**
 * Ayni thread'e bagli MUKERRER kabuklari ayiklar; her thread'ten bir tane kalir.
 *
 * Bir thread'i birden fazla kabuk paylasabiliyordu (adoptSession'daki
 * findShellByThread korumasi eklenmeden once acilanlar diske yazilmis ve her
 * restart'ta geri geliyordu). Sonucu kullanici goruyor: oturum listesinde ayni
 * konusma iki kez, telefonda AYNI konusma icin iki sekme — sekme kimligi oturum
 * kimligine bagli oldugu icin ikinci kabuga baglanan cevap yeni sekme aciyor
 * (sikayet 02.08.2026: "codex cevabini yine yeni sekme acarak verdi").
 *
 * Hangisi kalir: kimligi threadId'ye ESIT olan kabuk (codex'in kendi kimligi,
 * kanonik olan). Yoksa listedeki ilk kayit — persistSessions listeyi
 * _lastActivity'ye gore azalan sirada yazdigi icin bu "en son kullanilan"
 * demektir. threadId'si olmayan kayitlar hic elenmez (heniz thread'e baglanmamis
 * bos kabuklar birbirinin kopyasi sayilmaz).
 */
function dedupeShellsByThread(list) {
  const byThread = new Map();
  const out = [];
  for (const p of list) {
    if (!p || !p.id) continue;
    if (!p.threadId) { out.push(p); continue; }
    const seen = byThread.get(p.threadId);
    if (!seen) { byThread.set(p.threadId, p); out.push(p); continue; }
    // Kanonik kabuk sonradan geldiyse, once eklenmis olani onunla degistir.
    if (p.id === p.threadId && seen.id !== seen.threadId) {
      out[out.indexOf(seen)] = p;
      byThread.set(p.threadId, p);
    }
  }
  return out;
}
export function __testDedupeShellsByThread(list) { return dedupeShellsByThread(list); }

// Kayitli kabuklari sessions haritasina geri yukler. threadId'ler
// sessionsByThread'e EKLENMEZ (thread henuz app-server'da canli degil);
// _epoch: -1 sayesinde prompt() stale-epoch yolundan thread/resume dener,
// subscribe/getConversation ise hydrateFromThread ile gecmisi ceker.
// Test edilebilirlik icin veri parametreli; modul yuklenirken dosyadan cagrilir.
export function __restoreSessionsFromData(data) {
  // Buraya AYIKLAMA KOYMA: mukerrer kabuklara tolerans bilincli tasarim
  // (bildirim/onay fan-out'u ve adoptSession'in en-son-kullanilani secmesi bunun
  // uzerine kurulu, testleri de oyle). Mukerrerlik yazma yolunda kesiliyor —
  // dedupeShellsByThread persistSessions icinde.
  const list = Array.isArray(data && data.sessions) ? data.sessions : [];
  // Silinmis oturum DIRILMEZ. Ayiklama degil, mezar tasi kontrolu: arsivde adi
  // gecen kayit bilerek kaldirilmis demektir (yukaridaki mukerrer-kabuk
  // toleransiyla karistirilmamali). Eski kalicilik dosyalarinda bu kayitlar
  // hala duruyor; ilk persist yazimindan sonra kendiliginden temizlenirler.
  const arsiv = readBridgeJson(BRIDGE_ARCHIVE_FILE);
  let restored = 0;
  for (const p of list) {
    if (!p || !p.id || sessions.has(p.id)) continue;
    if (arsiv[p.id] || (p.threadId && arsiv[p.threadId])) continue;
    const { approvalPolicy, sandbox } = policyForMode(p.permissionMode);
    sessions.set(p.id, {
      id: p.id,
      threadId: p.threadId || null,
      // Eski kayitlarda YOK (alan 19.08.2026'da eklendi): 0 kalir ve budama
      // bunlari "yasi bilinmeyen bos kabuk" olarak temizler.
      createdAt: p.createdAt || 0,
      cwd: p.cwd || '',
      // Eski surumun yazdigi "profile:<ad>" kimlikleri burada gercek modele
      // goc eder; yoksa restore edilen oturum ilk turda API tarafindan reddedilir.
      model: normalizeModelId(p.model) || defaultModel(),
      permissionMode: p.permissionMode || 'yolo',
      effort: normalizeCodexEffort(p.effort),
      approvalPolicy,
      sandboxMode: sandbox,
      sandboxPolicy: sandboxPolicyFor(sandbox),
      status: 'idle',
      messages: [],
      toolDetails: [],
      subscribers: new Set(),
      awaitingFirstOutput: false,
      awaitingApproval: false,
      awaitingUserInput: false,
      pendingApproval: null,
      contextTokens: 0,
      contextWindow: 0,
      _epoch: -1,
      _turnId: null,
      _agentRows: new Map(),
      _detailIdx: new Map(),
      _fileChanges: [],
      _commands: [],
      _reasoningBufs: new Map(),
      _planBufs: new Map(),
      _lastActivity: p.lastActivity || 0,
      _booting: false,
      _plan: [],
      _planDraft: '',
      _needsHydration: !!p.threadId,
      _hydrating: false,
      _restoredShell: true,
      goal: null,                // hydrate/get/bildirim ile app-server'dan tazelenir
    });
    restored++;
  }
  return restored;
}

function restorePersistedSessions() {
  if (PERSIST_DISABLED) return;
  try {
    const n = __restoreSessionsFromData(readBridgeJson(BRIDGE_SESSIONS_FILE));
    if (n) logWarn('codex-app', 'kayitli oturumlar geri yuklendi', { count: n });
  } catch (e) { logWarn('codex-app', 'oturum geri yukleme hatasi', { error: errMessage(e) }); }
}

// Restore edilen bir oturumun gecmisini thread/resume ile doldurur ve thread'i
// app-server'da canlandirir. Basarisizlikta _needsHydration true kalir (sonraki
// eriste tekrar denenir); ayni anda gelen erisimler ayni Promise'i bekler.
async function hydrateFromThread(s) {
  if (!s._needsHydration || !s.threadId) return;
  if (s._hydratePromise) return s._hydratePromise;
  s._hydrating = true;
  const task = (async () => {
    try {
      await ensureInitialized();
      const r = await appSend('thread/resume', { threadId: s.threadId });
      const thread = r && r.thread;
      if (thread) {
        s.cwd = thread.cwd || r.cwd || s.cwd;
        s.model = r.model || s.model;
        s.threadId = thread.id || s.threadId;
        s._epoch = appEpoch;
        sessionsByThread.set(s.threadId, s);
        s.messages = [];
        s.toolDetails = [];
        s._agentRows.clear();
        s._detailIdx.clear();
        s._fileChanges = [];
        s._commands = [];
        const hydrated = await hydrateHistoryFromRollout(s, s.threadId);
        if (!hydrated) {
          if (Array.isArray(thread.turns)) buildMessagesFromTurns(s, thread.turns);
          else await readTurnsInto(s, s.threadId);
        }
        s._needsHydration = false;
        pushSnapshot(s);
      }
    } catch (e) {
      logWarn('codex-app', 'hydrate thread/resume başarısız', { threadId: s.threadId, error: errMessage(e) });
    } finally {
      s._hydrating = false;
      if (s._hydratePromise === task) s._hydratePromise = null;
    }
  })();
  s._hydratePromise = task;
  return task;
}

// No-op kept for parity with the other backend modules' startup cleanup hook.
export function cleanupTmp() { /* no temp scripts in this backend */ }

// ── Process admin (agy/opencode-app paritesi) ──────────────────────────
export function listLiveProcesses() {
  const out = [];
  if (appServer && !appServer.exitCode) out.push({ pid: appServer.pid, kind: 'app-server' });
  return out;
}
// Kalici app-server'i ve kosan turlari kapatir. Testler de bunu after() icinde
// cagirir — child pipe'lari event loop'u tutmasin, node --test temiz cikssin.
// Bellekteki oturum kayitlari da temizlenir (opencode-app ile ayni sozlesme):
// gecmis codex'in kendi thread deposunda durur, drawer listDiskSessions'tan dolar.
export function killAllSessions() {
  const killed = [];
  const errors = [];
  for (const s of sessions.values()) {
    if (s.status === 'running') finalizeTurnIdle(s, { error: '[codex-app] kill-all' });
  }
  if (appServer && !appServer.exitCode) {
    try { killChildTree(appServer.pid); killed.push(appServer.pid); }
    catch (e) { errors.push({ pid: appServer.pid, error: errMessage(e) }); logWarn('codex-app', 'kill-all', { error: errMessage(e) }); }
  }
  appServer = null;
  initialized = false;
  initializing = null;
  const cleared = sessions.size;
  sessions.clear();
  sessionsByThread.clear();
  persistSessions(); // bos listeyi yaz — restart'ta kayitlar geri gelmesin
  return { ok: errors.length === 0, killed, cleared, errors };
}

// ── Inactivity watchdog ──────────────────────────────────────────────────────
// app-server uzun-omurlu oldugundan, turn/start response'u kaybolursa ya da
// event akisi durursa turn sonsuza kadar 'running' kalir; prompt() hep 'busy'
// doner. N ms hicbir aktivite (item/turn eventi) yoksa turn'u finalize et.
// (opencode-app deseninin codex-app karsiligi.)
const codexInactivityWatchdog = createCollectionWatchdog({
  sessions,
  intervalMs: WATCHDOG_TICK_MS,
  isCandidate: (s, now) => (
    s.status === 'running' &&
    !s.awaitingApproval &&
    !s.awaitingUserInput &&
    s._lastActivity &&
    (now - s._lastActivity) >= INACTIVITY_MS
  ),
  isBusy: async s => {
    if (!s.threadId) return false;
    const r = await appSend('thread/read', { threadId: s.threadId, includeTurns: false });
    return !!(r && r.thread && r.thread.status && r.thread.status.type === 'active');
  },
  onBusy: s => { s._lastActivity = Date.now(); },
  onProbeError: (s, error) => logWarn('codex-app', 'inactivity probe failed; active turn preserved', {
    session: s.id,
    error: errMessage(error),
  }),
  log: s => logWarn('codex-app', 'inactivity finalize', { session: s.id, idleMs: Date.now() - s._lastActivity }),
  onTimeout: s => finalizeTurnIdle(s, { error: '[codex-app] Turn uzun sure sessiz kaldi, otomatik kapatildi.' }),
});

function ensureInactivityWatchdog() {
  return codexInactivityWatchdog.start();
}
ensureInactivityWatchdog();

// Read‑only content search over Codex disk sessions (Phase 7 global search).
// Session‑title matches plus FULL rollout transcript search (prompt/answer
// geçmişinin tamamı; yalnız title/lastText değil). Rollout dosya listesi bir kez
// çıkarılır; her oturum id'sine ait dosya fullRolloutParse ile taranır. Eşleşmeler
// matchOrdinal ile döner (sohbet içi arama sırasıyla hizalı — deep‑link).
// Arama için transkript metni önbelleği (dosya+mtime). claude-app'teki
// searchableMessages'ın karşılığı — burada YOKTU: her sorguda ~70 rollout
// dosyası baştan JSON-parse ediliyordu ve arama tuş vuruşu başına saniyeler
// yiyordu (ölçüldü 13.08.2026: tekrarlanan aynı sorgu hiç hızlanmıyor).
// SEARCH_TEXT_CAP eşleşme + snippet için fazlasıyla yeter; tam metin tutmak
// önbelleği transkript boyutuna şişirir.
const CODEX_SEARCH_TEXT_CAP = 4_000;
const _searchTextCache = new Map(); // file -> { mtime, msgs }
function searchableRolloutMessages(file) {
  let mtime = 0;
  try { mtime = fs.statSync(file).mtimeMs; } catch { return []; }
  const hit = _searchTextCache.get(file);
  if (hit && hit.mtime === mtime) return hit.msgs;
  const msgs = [];
  try {
    for (const m of fullRolloutParse(file).messages) {
      if (m.role !== 'user' && m.role !== 'agent') continue;
      const text = String(m.text || '');
      if (text) msgs.push({ role: m.role, text: text.length > CODEX_SEARCH_TEXT_CAP ? text.slice(0, CODEX_SEARCH_TEXT_CAP) : text });
    }
  } catch { /* okunamayan rollout boş girdiyle geçilir */ }
  _searchTextCache.set(file, { mtime, msgs });
  // Sınırsız büyüme sigortası: en eski girdiler düşer (Map ekleme sıralı).
  if (_searchTextCache.size > 500) {
    for (const key of _searchTextCache.keys()) {
      if (_searchTextCache.size <= 400) break;
      _searchTextCache.delete(key);
    }
  }
  return msgs;
}

export async function searchDiskSessions({ query, cwd, limit = 40, deadline = 0 } = {}) {
  const q = (query || '').toLowerCase().trim();
  if (q.length < 2) return [];
  const { sessions: all } = await listDiskSessions({ all: true }) || {};
  const results = [];
  let kesildi = false;
  let rolloutFiles = null; // lazy: yalnız mesaj taraması gerekince disk yürünür
  const fileForId = (id) => {
    if (!id) return null;
    if (!rolloutFiles) { rolloutFiles = []; try { walkRollouts(SESSIONS_DIR, rolloutFiles, 0); } catch {} }
    return rolloutFiles.find(f => f.includes(id)) || null;
  };
  for (const s of (all || [])) {
    // Süre bütçesi (bkz. search.mjs). Bu fonksiyon `async` ama içinde HİÇ await
    // yok — döngü baştan sona senkron akıyor, yani dışarıdaki zamanlayıcı yine
    // sırasını alamıyor. Durma kararı burada.
    if (deadline && Date.now() > deadline) { kesildi = true; break; }
    if (cwd && cwd.toLowerCase() !== (s.cwd || '').toLowerCase()) continue;
    if (results.length >= limit) break;
    if ((s.title || '').toLowerCase().includes(q)) {
      results.push({
        type: 'session', sessionId: s.id, title: s.title, projectPath: s.cwd || '',
        role: '', text: s.lastText || s.title || '', mtime: s.mtime || 0,
      });
    }
    if (results.length >= limit) break;
    try {
      const file = fileForId(s.id);
      if (!file) continue;
      for (const m of matchTranscriptMessages(searchableRolloutMessages(file), q)) {
        if (results.length >= limit) break;
        results.push({
          type: 'message', sessionId: s.id, title: s.title || '', projectPath: s.cwd || '',
          role: m.role, text: m.text, rowId: '', matchOrdinal: m.matchOrdinal, mtime: s.mtime || 0,
        });
      }
    } catch { /* skip unreadable rollouts */ }
  }
  // Kesildiyse çağıran BİLMELİ: `globalSearch` bunu süreye bakarak tahmin
  // edemiyor (kendinden öncekiler bütçeyi yemişse yanlış pozitif üretiyordu).
  return { hits: results, truncated: kesildi };
}

// Köprü kapanırken app-server'ı da götür — opencode-app'teki yetim süreç
// taramasında burada da ebeveynsiz kalmış bir codex süreci çıktı. claude-app'te
// aynı kanca vardı, burada yoktu.
process.on('exit', () => {
  if (appServer && appServer.pid && !appServer.exitCode) {
    try { killChildTreeSync(appServer.pid); } catch {}
  }
});

restorePersistedSessions();
