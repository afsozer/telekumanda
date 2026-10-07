import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import pty from 'node-pty';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { hhmm, capMessages, broadcast, logWarn, matchTranscriptMessages, isSafeSessionId } from './session-utils.mjs';
import { createAgentSessionCore } from './agent-session-core.mjs';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const TMP = path.join(__dir, 'agy-tmp');
// Disa acik: not sayfasini goruntuden coz akisi (cowork.mjs) ayni ikiliyi kullanir.
export const AGY_EXE = path.join(process.env.LOCALAPPDATA || '', 'agy', 'bin', 'agy.exe');

// ── Paylaşımlı depo: desktop IDE + CLI aynı format, farklı home ─────────────
// Antigravity desktop IDE ve agy CLI diskte BİREBİR aynı formatı kullanır
// (brain/<id>/.system_generated/logs/transcript.jsonl + conversations/<id>.db),
// sadece ~/.gemini altında farklı home dizinlerindedir. Home'u seçen şey agy'nin
// GİZLİ --app_data_dir bayrağıdır (--help'te görünmez; desktop language_server.exe
// de aynı bayrakla "antigravity" home'unu alır). 2026-07-07'de izole home ile
// doğrulandı: bayrak tam bir home oluşturuyor ve desktop-kaynaklı bir konuşma
// --conversation=<id> ile CLI'dan resume edilebiliyor. Bu sayede telefon, desktop
// IDE oturumlarını hem canlı izleyebilir hem devam ettirebilir (CDP gerekmez).
const GEMINI_ROOT = path.join(os.homedir(), '.gemini');
export const HOMES = {
  cli: { appDataDir: 'antigravity-cli', label: 'Antigravity CLI' },
  ide: { appDataDir: 'antigravity', label: 'Antigravity IDE' },
};
const normHome = home => (HOMES[home] ? home : 'cli');
const homeRoot = home => path.join(GEMINI_ROOT, HOMES[normHome(home)].appDataDir);
const brainDir = home => path.join(homeRoot(home), 'brain');
const conversationsDir = home => path.join(homeRoot(home), 'conversations');
const lastConversationsFile = home => path.join(homeRoot(home), 'cache', 'last_conversations.json');
const transcriptFile = (home, conversationId) =>
  path.join(brainDir(home), conversationId, '.system_generated', 'logs', 'transcript.jsonl');
// Yabancı-yazar koruması: transcript son N ms içinde BAŞKA bir istemci (desktop IDE)
// tarafından yazıldıysa resume engellenir — aynı .db'ye iki language_server aynı anda
// yazarsa SQLite bozulabilir. Konuşma başına tek yazar ilkesi.
const FOREIGN_WRITE_GUARD_MS = 10_000;
const IDLE_TIMEOUT_MS = 60 * 60 * 1000;
const TURN_IDLE_MS = 6000;        // 6s settle after completion marker (covers prose→next-tool gap)
const TURN_GRACE_MS = 3000;       // 3s grace after last growth before declaring complete
// No absolute turn cap — IDLE_TIMEOUT_MS (60 min no activity) is the only hard timeout.
// Long builds (flutter build apk, 10+ min) must not be killed mid-execution.

// User-curated model ids. These MUST match ids the installed `agy.exe` accepts.
// `Default` (empty id) lets agy pick.
//
// AUTHORITATIVE LIST: `agy.exe models` (a SUBCOMMAND, not a flag — the old note
// here said no listing existed, which sent us guessing). Run it before editing.
//
// Bilinmeyen id SESSIZCE geri dusmez, sert hata verir:
//   "invalid model selection (--model \"x\"): model x is not recognized"
// 07.08.2026'da olculdu: buradaki iki Claude id'si (`claude-4.6-opus`,
// `claude-4.6-sonnet`) uydurmaydi ve secilince model hic calismiyordu. Dogru
// bicim ters: `claude-sonnet-4-6`, `claude-opus-4-6-thinking`.
//
// Not: `--model gemini-3.8-flash` tek basina `--effort` istiyor; bilesik id
// (…-low/-medium/-high) cabayi icinde tasiyor ve ek bayrak gerektirmiyor.
export const AGY_MODELS = [
  { label: 'Gemini 3.8 Flash High', id: 'gemini-3.8-flash-high' },
  { label: 'Gemini 3.8 Flash Medium', id: 'gemini-3.8-flash-medium' },
  { label: 'Gemini 3.8 Flash Low', id: 'gemini-3.8-flash-low' },
  { label: 'Gemini 3.1 Pro High', id: 'gemini-3.1-pro-high' },
  { label: 'Gemini 3.1 Pro Low', id: 'gemini-3.1-pro-low' },
  { label: 'Claude Opus 4.6', id: 'claude-opus-4-6-thinking' },
  { label: 'Claude Sonnet 4.6', id: 'claude-sonnet-4-6' },
  { label: 'GPT-OSS 120B Medium', id: 'gpt-oss-120b-medium' },
];
export const MODELS = AGY_MODELS;

const sessions = new Map();

function snapshot(s) {
  return {
    type: 'conversation',
    sessionId: s.id,
    messages: s.messages,
    running: s.turnActive || s.status === 'running',
    // B2: awaitingApproval is set true when onData detects an unknown prompt
    // (e.g. OAuth/login) in the PTY output buffer. See B2 prompt detection below.
    awaitingApproval: s.awaitingApproval || false,
    // B2: pendingPrompt carries { prompt: rawAnsiStrippedText, options: [...] }
    // so the phone renders an approval card. Set when awaitingApproval goes true.
    pendingPrompt: s.pendingPrompt || null,
    awaitingFirstOutput: s.awaitingFirstOutput ?? false,
    contextTokens: 0,
    contextWindow: 0,
  };
}

// Delta yayın çekirdeği (claude-app/codex-app/opencode-app/omp ile aynı).
// Kısıcı EKLENMEDİ: agy zaten PTY karesi başına değil, 1,5 sn'lik transkript
// yoklaması ve tur/onay sınırlarında itiyor — doğal hız sınırı orada.
// `pendingPrompt` META_KEYS'e eklendi, yoksa onay kartı delta kipinde hiç gelmezdi.
const sessionCore = createAgentSessionCore({ snapshot });
const pushSnapshot = sessionCore.pushSnapshot;

// _deltaDirty sigortası: rowId'siz satır kırpılırsa diff kimliği kaybeder.
function capSess(s) {
  capMessages(s, undefined, evicted => {
    if (evicted.some(msg => !msg?.rowId)) s._deltaDirty = true;
  });
}

export function cleanupTmp() {
  try {
    for (const f of fs.readdirSync(TMP)) {
      if (f.endsWith('.ps1') || f.endsWith('.log')) {
        try { fs.unlinkSync(path.join(TMP, f)); } catch (e) { logWarn('agy', 'cleanupTmp unlink', { file: f, error: e.message }); }
      }
    }
  } catch (e) { logWarn('agy', 'cleanupTmp readdir', { error: e.message }); }
}

export function newSession({ cwd, model, home }) {
  let dir = cwd && String(cwd).trim() ? String(cwd).trim() : os.homedir();
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    return { ok: false, error: 'cwd not a directory: ' + dir };
  }
  // Yeni oturumlar varsayılan olarak IDE home'una yazılır ki desktop Antigravity
  // tarafında da görünsünler (paylaşımlı depo). 'cli' geçilirse eski davranış.
  const sessionHome = HOMES[home] ? home : 'ide';
  const id = randomUUID();
  sessions.set(id, {
    id,
    conversationId: '',
    home: sessionHome,
    cwd: dir,
    model: model || '',
    status: 'idle',
    messages: [],
    toolDetails: [],
    // ── PTY session state ──
    pty: null,
    turnActive: false,
    awaitingApproval: false,
    pendingPrompt: null,
    awaitingFirstOutput: false,
    subscribers: new Set(),
    // Internal turn-tracking fields
    _spawnTime: 0,
    _sessionPoll: null,
    _lastGrowth: 0,
    _lastActivity: 0,
    _turnStartTime: 0,
    _turnPrevAgentCount: 0,
    _turnAgentSeenAt: null,
  });
  return { ok: true, sessionId: id, cwd: dir, model: model || '', home: sessionHome };
}

// ── ANSI / control-character stripper ────────────────────────────────────────
const stripAnsi = s => s
  .replace(/\x1b\][0-9];[^\x07]*\x07/g, '')
  .replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '')
  .replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, '');

// ── Transcript parsing ───────────────────────────────────────────────────────

function cleanUserContent(content) {
  return String(content || '')
    .replace(/<USER_REQUEST>\s*/g, '')
    .replace(/\s*<\/USER_REQUEST>[\s\S]*$/g, '')
    .trim();
}

const baseName = p => (p ? String(p).replace(/[`'"]/g, '').split(/[\\/]/).filter(Boolean).pop() : '');
const TOOL_DETAIL_CAP = 8000;

function summarizeStep(r) {
  const c = String(r.content || '');
  switch (r.type) {
    case 'VIEW_FILE': {
      const m = c.match(/File Path:\s*`?(?:file:\/\/\/)?([^`\n]+)`?/i);
      return 'Read ' + (baseName(m && m[1]) || 'file');
    }
    case 'CODE_ACTION': {
      const created = c.match(/Created file\s+(?:file:\/\/\/)?([^\s\n]+)/i);
      if (created) return 'Write ' + baseName(created[1]);
      const edited = c.match(/(?:changes were made|modified).*?to:?\s*(?:file:\/\/\/)?([^\s\n]+)/i);
      if (edited) return 'Edit ' + baseName(edited[1]);
      return 'Edit';
    }
    case 'RUN_COMMAND': {
      const m = c.match(/Task Description:\s*(.+)/i);
      return '$ ' + (m ? m[1] : 'command').trim().slice(0, 70);
    }
    case 'GREP_SEARCH': return 'Grep';
    case 'LIST_DIRECTORY': return 'List dir';
    case 'GENERATE_IMAGE': return 'Generate image';
    case 'ERROR_MESSAGE': return 'Error';
    default: return (r.type || 'tool').toLowerCase();
  }
}

const TOOL_TYPES = new Set([
  'VIEW_FILE', 'CODE_ACTION', 'RUN_COMMAND', 'GREP_SEARCH',
  'LIST_DIRECTORY', 'GENERATE_IMAGE', 'ERROR_MESSAGE',
]);

function parseTranscript(conversationId, home = 'cli') {
  const file = transcriptFile(home, conversationId);
  const out = { messages: [], toolDetails: [], firstUser: '', lastText: '', turns: 0 };
  let raw;
  try { raw = fs.readFileSync(file, 'utf-8'); } catch { return out; }
  for (const line of raw.split(/\r?\n/)) {
    const t = line.trim();
    if (!t) continue;
    let r; try { r = JSON.parse(t); } catch { continue; }
    if (r.type === 'USER_INPUT') {
      const text = cleanUserContent(r.content);
      if (!text) continue;
      if (!out.firstUser) out.firstUser = text;
      out.lastText = text;
      out.turns++;
      out.messages.push({ role: 'user', text, time: r.created_at || '' });
    } else if (r.type === 'PLANNER_RESPONSE') {
      const text = String(r.content || '').trim();
      if (!text) continue;
      out.lastText = text;
      out.messages.push({ role: 'agent', text, time: r.created_at || '' });
    } else if (TOOL_TYPES.has(r.type)) {
      const detail = String(r.content || '').trim();
      if (!detail) continue;
      const idx = out.toolDetails.length;
      out.toolDetails.push(detail.slice(0, TOOL_DETAIL_CAP));
      out.messages.push({ role: 'thought', text: summarizeStep(r), thoughtIndex: idx, time: r.created_at || '' });
    }
  }
  return out;
}

// ── Deterministic turn-end signal ────────────────────────────────────────────

// DETERMINISTIC turn-end signal (validated against 6 real transcripts, 2026-06-21).
// Every completed turn ends with a non-empty PLANNER_RESPONSE (the model's prose
// reply). While a tool is still running, the last meaningful entry is instead an
// empty PLANNER_RESPONSE (tool-call wrapper) or a tool entry (RUN_COMMAND etc.).
// So: turn is complete IFF the last meaningful entry is a non-empty PLANNER_RESPONSE.
//
// "Meaningful" excludes scaffolding entries (CONVERSATION_HISTORY, CHECKPOINT) that
// agy injects mid-stream and which never represent the model's final reply.
//
// A small TURN_IDLE_MS grace still covers the gap where the model emits intermediate
// prose and is about to dispatch another tool (the next entry lands within ~1-2s).
//
// BACKGROUND-TASK GUARD (validated 2026-06-21, conv ed3d4e23): agy runs long shell
// commands (flutter analyze/build) as ASYNC background tasks — the RUN_COMMAND entry
// reads "Tool is running as a background task" and the model immediately emits an
// intermediate prose ("started it, will act on the result"), which looks like a
// final reply. When the task finishes, agy injects a SYSTEM_MESSAGE that re-prompts
// the model to continue. If we declared the turn done at the intermediate prose, the
// phone shows "finished" while work continues in the background for minutes.
// Fix: a turn is NOT complete while a background task is in flight. agy signals a
// task's completion in one of two shapes: a SYSTEM_MESSAGE re-prompt, OR a message
// entry whose content carries the "[Task Completed] ... finished with code N"
// notification (observed 2026-06-21, conv 3f4208e2: a fast curl bg task whose
// completion arrived as a PLANNER_RESPONSE, not a SYSTEM_MESSAGE — the old
// SYSTEM_MESSAGE-only guard left the turn stuck open forever). The reliable signal
// is ORDER, not count — there can be extra completion entries not tied 1:1 to a
// launch. So: a background task is in flight IFF the LAST background-task launch has
// NO completion signal after it.
const TURN_SCAFFOLD_TYPES = new Set(['CONVERSATION_HISTORY', 'CHECKPOINT']);
function turnIsComplete(conversationId, home = 'cli') {
  const file = transcriptFile(home, conversationId);
  let raw;
  try { raw = fs.readFileSync(file, 'utf-8'); } catch { return false; }
  const lines = raw.split(/\r?\n/).filter(l => l.trim());
  let lastMeaningfulIsReply = false;
  let lastChecked = false;
  let lastBgIdx = -1;
  let lastDoneIdx = -1;
  for (let i = 0; i < lines.length; i++) {
    let r; try { r = JSON.parse(lines[i]); } catch { continue; }
    const content = String(r.content || '');
    if (r.type === 'RUN_COMMAND' && /as a background task/i.test(content)) lastBgIdx = i;
    // Completion signal: a SYSTEM_MESSAGE re-prompt, or any entry carrying the
    // "[Task Completed]" notification (the completion can arrive as a PLANNER_RESPONSE).
    if (r.type === 'SYSTEM_MESSAGE' || /\[Task Completed\]/i.test(content)) lastDoneIdx = i;
  }
  // Last meaningful entry (nearest non-scaffold from the end) must be a prose reply.
  for (let i = lines.length - 1; i >= 0; i--) {
    let r; try { r = JSON.parse(lines[i]); } catch { continue; }
    if (TURN_SCAFFOLD_TYPES.has(r.type)) continue;
    lastMeaningfulIsReply = r.type === 'PLANNER_RESPONSE' && !!String(r.content || '').trim();
    lastChecked = true;
    break;
  }
  if (!lastChecked || !lastMeaningfulIsReply) return false;
  // A background task launched with no completion signal after it is still running.
  if (lastBgIdx > lastDoneIdx) return false;
  return true;
}

// ── Conversation ID discovery via brain directory scan ────────────────────────

// last_conversations.json is written LATE (on clean process exit) — useless for
// live discovery. Instead, scan the session's brain dir for directories created
// after spawnTime whose transcript.jsonl exists and has content.
function discoverBrainConversation(spawnTime, home = 'cli') {
  const base = brainDir(home);
  let dirs;
  try { dirs = fs.readdirSync(base); } catch { return null; }
  let best = null;
  let bestMtime = 0;
  for (const name of dirs) {
    const full = path.join(base, name);
    let st;
    try { st = fs.statSync(full); } catch { continue; }
    if (!st.isDirectory()) continue;
    if (st.mtimeMs <= spawnTime) continue;
    let raw;
    try { raw = fs.readFileSync(transcriptFile(home, name), 'utf-8'); } catch { continue; }
    if (!raw.includes('USER_INPUT')) continue;
    if (st.mtimeMs > bestMtime) { best = name; bestMtime = st.mtimeMs; }
  }
  return best;
}

// ── Disk helpers (kept for fallback and listDiskSessions) ────────────────────

function projectMap(home = 'cli') {
  try { return JSON.parse(fs.readFileSync(lastConversationsFile(home), 'utf-8')); } catch { return {}; }
}

// cwd her iki home'un last_conversations.json'ında aranır (IDE home'unda dosya
// hiç olmayabilir — IDE bunu tutmaz; agy IDE home'unda çalışınca oluşturur).
function cwdForConversation(id) {
  for (const home of Object.keys(HOMES)) {
    for (const [cwd, cid] of Object.entries(projectMap(home))) {
      if (cid === id) return cwd;
    }
  }
  return '';
}

// Bir konuşmanın hangi home'da yaşadığını diskten tespit et (adopt için).
function detectHome(id) {
  for (const home of Object.keys(HOMES)) {
    try {
      if (fs.existsSync(path.join(brainDir(home), id)) ||
          fs.existsSync(path.join(conversationsDir(home), id + '.db'))) return home;
    } catch {}
  }
  return null;
}

function applyParsed(s, parsed) {
  s.messages = parsed.messages;
  s.toolDetails = parsed.toolDetails;
}

function syncFromDisk(s) {
  if (!s.conversationId) return;
  const parsed = parseTranscript(s.conversationId, s.home);
  if (parsed.messages.length >= s.messages.length) applyParsed(s, parsed);
}

// ── B2: Unknown prompt detection ─────────────────────────────────────────────

// Only structural CLI prompt indicators — [y/n], (y/n), (yes/no) at END OF LINE.
// Generic verbs (continue, proceed, allow, sign-in) are EXCLUDED because the model
// frequently uses them in prose replies ("Shall I continue?", "Proceed?"), which
// would false-trigger awaitingApproval and lock the turn forever.
//
// Trust prompt ("Do you trust the contents...") is handled separately above with
// a dedicated exact-string match — do NOT add it here.
//
// If a real OAuth/login prompt is observed with a different format, add its exact
// indicator string (not a loose verb regex) to this alternation.
const UNKNOWN_PROMPT_RE = /(\[y\/n\]|\(y\/n\)|\(yes\/no\))\s*$/im;

function checkForUnknownPrompt(s, strippedBuffer) {
  if (!s.pty || s.pty._exitCode !== undefined) return;
  if (s.awaitingApproval) return;
  const tail = strippedBuffer.slice(-2000);
  if (UNKNOWN_PROMPT_RE.test(tail)) {
    const lines = tail.split(/\r?\n/).filter(l => l.trim());
    const promptText = lines.slice(-3).join('\n').trim();
    s.awaitingApproval = true;
    s.pendingPrompt = {
      prompt: promptText,
      options: ['Yes', 'No'],
    };
    pushSnapshot(s);
  }
}

// ── PTY-based interactive session ────────────────────────────────────────────

function spawnInteractive(s, initialPrompt) {
  const spawnTime = Date.now();
  s._spawnTime = spawnTime;

  // Build args: -i with the first prompt as a REQUIRED argument.
  // An empty -i (no prompt arg) hangs the TUI indefinitely — verified.
  const args = ['-i', initialPrompt];
  if (s.conversationId) args.push('--conversation=' + s.conversationId);
  // Paylaşımlı depo: oturumun home'una göre agy'yi CLI veya desktop IDE
  // deposuna yönelt (gizli bayrak, 2026-07-07 doğrulandı).
  args.push('--app_data_dir=' + HOMES[normHome(s.home)].appDataDir);
  args.push('--dangerously-skip-permissions');
  if (s.model) args.push('--model', s.model);

  const p = pty.spawn(AGY_EXE, args, {
    name: 'xterm-color',
    cols: 120,
    rows: 30,
    cwd: s.cwd,
    env: process.env,
  });

  s.pty = p;

  let ptyBuffer = '';
  let trustAnswered = false;

  p.onData(data => {
    ptyBuffer += data;
    // Cap the buffer to prevent O(n²) stripAnsi processing and memory bloat
    // on long sessions. 8 KB is more than enough for trust + prompt detection;
    // actual chat content comes from transcript.jsonl, not the PTY buffer.
    if (ptyBuffer.length > 8000) ptyBuffer = ptyBuffer.slice(-8000);
    s._lastActivity = Date.now();

    const stripped = stripAnsi(ptyBuffer);

    // ── Trust prompt auto-answer (fires once per session) ──
    // --dangerously-skip-permissions does NOT skip the "Do you trust the contents
    // of this project?" prompt on first open of a directory. Auto-answer with '1'
    // (Yes) after a 400ms settle to let agy's TUI finish rendering.
    if (!trustAnswered && /do you trust the contents/i.test(stripped)) {
      trustAnswered = true;
      setTimeout(() => {
        if (s.pty && s.pty._exitCode === undefined) {
          s.pty.write('1\r');
        }
      }, 400);
    }

    // ── B2: Unknown prompt detection (after trust is handled) ──
    if (trustAnswered && !s.awaitingApproval) {
      checkForUnknownPrompt(s, stripped);
    }
  });

  p.onExit(({ exitCode, signal }) => {
    clearInterval(s._sessionPoll);
    s._sessionPoll = null;

    syncFromDisk(s);

    // ── Respawn path: PTY was killed intentionally by prompt() for multi-turn ──
    // Don't broadcast 'end' or push a terminal snapshot — spawnInteractive will
    // set turnActive=true and push a fresh snapshot immediately after.
    if (s._respawnPending) {
      s.pty = null;
      s.turnActive = false;
      s.status = 'idle';
      s.awaitingFirstOutput = false;
      return;
    }

    const previousAgentCount = s._turnPrevAgentCount || 0;

    if (s.turnActive) {
      const agentCount = s.messages.reduce((n, m) => n + (m.role === 'agent' ? 1 : 0), 0);
      if (agentCount <= previousAgentCount) {
        // No new agent reply arrived — surface the error
        const codeStr = signal ? `signal ${signal}` : `exit ${exitCode}`;
        s.messages.push({ role: 'agent', text: `[agy kapandi | ${codeStr}] Transcript'e yeni model cevabi dusmedi.`, time: hhmm() });
        capSess(s);
      }
    }

    s.turnActive = false;
    s.status = 'idle';
    s.awaitingApproval = false;
    s.pendingPrompt = null;
    s.awaitingFirstOutput = false;
    s.pty = null;
    pushSnapshot(s);
    broadcast(s, { type: 'end', sessionId: s.id });
  });

  const previousAgentCount = s.messages.reduce((n, m) => n + (m.role === 'agent' ? 1 : 0), 0);

  startSessionPoll(s);

  s.turnActive = true;
  s.status = 'running';
  s.awaitingFirstOutput = true;
  s._turnPrevAgentCount = previousAgentCount;
  s._turnAgentSeenAt = null;
  s._lastGrowth = Date.now();
  s._lastActivity = Date.now();
  s._turnStartTime = Date.now();
  pushSnapshot(s);
}

// ── Session poll (1.5s interval) ─────────────────────────────────────────────
// Runs while the PTY is alive. Reads transcript.jsonl, pushes live snapshots,
// detects turn completion via turnIsComplete(), and enforces the idle timeout.

function startSessionPoll(s) {
  const poll = setInterval(() => {
    // PTY exited → stop polling (cleanup handled by onExit)
    if (!s.pty || s.pty._exitCode !== undefined) {
      clearInterval(poll);
      s._sessionPoll = null;
      return;
    }

    // ── Live conversationId discovery via brain scan ──
    if (!s.conversationId && s._spawnTime) {
      const discovered = discoverBrainConversation(s._spawnTime, s.home);
      if (discovered) s.conversationId = discovered;
    }

    if (!s.conversationId) return; // Brain dir not created yet

    const parsed = parseTranscript(s.conversationId, s.home);

    // Push snapshot when transcript grows
    if (parsed.messages.length > s.messages.length) {
      applyParsed(s, parsed);
      capSess(s);
      if (parsed.messages.some(m => m.role === 'agent')) s.awaitingFirstOutput = false;
      s._lastGrowth = Date.now();
      pushSnapshot(s);
    }

    // ── Turn completion detection (only when turn is active, not awaiting approval) ──
    if (s.turnActive && s._lastGrowth && !s.awaitingApproval) {
      const now = Date.now();
      const agentCount = parsed.messages.reduce((n, m) => n + (m.role === 'agent' ? 1 : 0), 0);

      // Track when a new agent reply first appears in this turn
      if (agentCount > (s._turnPrevAgentCount || 0)) {
        if (!s._turnAgentSeenAt) s._turnAgentSeenAt = now;
        s._turnPrevAgentCount = agentCount;
      }

      // Deterministic completion: transcript's last meaningful entry is the model's
      // prose reply, with a TURN_IDLE_MS settle to guard the prose→next-tool gap.
      const complete = turnIsComplete(s.conversationId, s.home);

      if (s._turnAgentSeenAt && complete &&
          (now - s._lastGrowth) > TURN_IDLE_MS &&
          (now - s._turnAgentSeenAt) > TURN_GRACE_MS) {
        s.turnActive = false;
        s.status = 'idle';
        s.awaitingFirstOutput = false;
        s._turnAgentSeenAt = null;
        s._turnPrevAgentCount = 0;
        pushSnapshot(s);
        broadcast(s, { type: 'end', sessionId: s.id });
        return;
      }

      // ── Idle timeout during active turn (60 min no transcript growth) ──
      // No absolute turn cap — long builds (flutter, 10+ min) are safe because
      // _lastGrowth resets on every transcript entry. Only true stalls are killed.
      if ((now - s._lastGrowth) > IDLE_TIMEOUT_MS) {
        s.turnActive = false;
        s.messages.push({ role: 'agent', text: '[agy zaman asimi] 60 dakika boyunca yanit alinamadi, surec sonlandirildi.', time: hhmm() });
        capSess(s);
        s.pty.kill();
        s.status = 'idle';
        s.awaitingFirstOutput = false;
        s._turnAgentSeenAt = null;
        s._turnPrevAgentCount = 0;
        pushSnapshot(s);
        broadcast(s, { type: 'end', sessionId: s.id });
        return;
      }
    }
  }, 1500); // 1.5s poll interval

  s._sessionPoll = poll;
}

// ═══════════════════════════════════════════════════════════════════════════════
// ── Public API ────────────────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════════════════

export function prompt({ sessionId, text, model }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (s.turnActive) return { ok: false, error: 'busy' };
  if (!text || !String(text).trim()) return { ok: false, error: 'text required' };
  if (model !== undefined) s.model = model || '';
  if (!fs.existsSync(AGY_EXE)) return { ok: false, error: 'agy.exe not found at ' + AGY_EXE };

  // ── Yabancı-yazar koruması (paylaşımlı depo) ──
  // Transcript, bizim son gördüğümüz büyümeden (2s marj) SONRA ve son 10s içinde
  // değiştiyse başka bir istemci (muhtemelen desktop IDE) bu konuşmaya şu anda
  // yazıyordur. Aynı .db'ye iki language_server yazarsa SQLite bozulabilir —
  // konuşma başına tek yazar. Kendi turumuzun yazımları _lastGrowth'a yansıdığı
  // için art arda mesajları ENGELLEMEZ.
  if (s.conversationId) {
    try {
      const st = fs.statSync(transcriptFile(s.home, s.conversationId));
      const foreignWrite = st.mtimeMs > (s._lastGrowth || 0) + 2000;
      if (foreignWrite && (Date.now() - st.mtimeMs) < FOREIGN_WRITE_GUARD_MS) {
        return { ok: false, error: 'Bu konusmaya su anda baska bir istemci (muhtemelen Antigravity IDE) yaziyor. Birkac saniye sonra tekrar deneyin.' };
      }
    } catch {} // transcript yoksa (yeni konuşma) koruma gerekmez
  }

  capSess(s);
  s.messages.push({ role: 'user', text: String(text), time: hhmm() });
  s.lastUserAt = Date.now(); // proje detayi siralamasi: son user prompt ani
  capSess(s);

  const promptText = String(text);

  // ── Spawn or reuse PTY ──
  if (!s.pty || s.pty._exitCode !== undefined) {
    // No live PTY: spawn a new interactive session.
    // The first prompt is passed as an argument to -i (required — empty -i hangs).
    // For session resumption (multi-message), include --conversation if known.
    if (!s.conversationId && s._spawnTime) {
      // Try one last discovery from brain before spawn
      const discovered = discoverBrainConversation(s._spawnTime || 0, s.home);
      if (discovered) s.conversationId = discovered;
    }
    spawnInteractive(s, promptText);
  } else {
    // ── Multi-turn: kill + respawn with --conversation to resume ──
    // PTY inline write (pty.write(text) + \r) is UNRELIABLE — agy's TUI may not
    // accept keyboard input while the agent is idle. The verified approach is to
    // kill the current PTY and spawn a fresh one with --conversation=<convId>.
    // The conversation context (history, brain dir) is preserved by agy.
    if (s._sessionPoll) { clearInterval(s._sessionPoll); s._sessionPoll = null; }
    s._respawnPending = true;
    s.pty.kill();
    // onExit sees _respawnPending, skips end broadcast, cleans up state.
    // Short timeout lets node-pty finalise the kill before we spawn again.
    setTimeout(() => {
      if (s._respawnPending) {
        s._respawnPending = false;
        spawnInteractive(s, promptText);
      }
    }, 300);
  }

  return { ok: true, sessionId: s.id };
}

export function getConversation(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { messages: [], running: false, awaitingApproval: false, pendingPrompt: null, contextTokens: 0, contextWindow: 0 };
  syncFromDisk(s);
  return {
    messages: s.messages,
    running: s.turnActive || s.status === 'running',
    awaitingApproval: s.awaitingApproval || false,
    pendingPrompt: s.pendingPrompt || null,
    awaitingFirstOutput: s.awaitingFirstOutput ?? false,
    contextTokens: 0,
    contextWindow: 0,
  };
}

export function getThought(sessionId, i) {
  const s = sessions.get(sessionId);
  if (!s) return { text: '' };
  return { text: (s.toolDetails && s.toolDetails[i]) || '' };
}

export function stop(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'not found' };
  if (!s.pty || s.pty._exitCode !== undefined) {
    if (s.status === 'running' || s.turnActive) {
      s.turnActive = false;
      s.status = 'idle';
      s.awaitingFirstOutput = false;
      pushSnapshot(s);
    }
    return { ok: true, already: true };
  }
  s.pty.kill();
  return { ok: true };
}

// B2: Respond to an agy permission/OAuth prompt by writing the user's answer
// to the PTY. Called by POST /agy/respond.
// Returns 'not awaiting approval' unless B2 prompt detection fired and set
// s.awaitingApproval = true (see checkForUnknownPrompt above).
export function respond(sessionId, answer) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.pty || s.pty._exitCode !== undefined) {
    return { ok: false, error: 'process not running' };
  }
  if (!s.awaitingApproval) return { ok: false, error: 'not awaiting approval' };

  s.pty.write(String(answer) + '\r');
  s.awaitingApproval = false;
  s.pendingPrompt = null;
  s._lastGrowth = Date.now();
  s._lastActivity = Date.now();
  pushSnapshot(s);

  return { ok: true };
}

export function subscribe(sessionId, ws, opts = {}) {
  // İlk snapshot, delta replay ve abone kaydı sessionCore'da. opts iletilmezse
  // delta=1 isteyen istemci sessizce düz kipe düşer.
  sessionCore.subscribe(sessions.get(sessionId), ws, opts);
}

export function listSessions() {
  return [...sessions.values()].map(s => ({
    id: s.id,
    cwd: s.cwd,
    model: s.model,
    home: s.home || 'cli',
    sourceLabel: HOMES[normHome(s.home)].label,
    status: s.turnActive ? 'running' : s.status,
    title: (s.messages.find(m => m.role === 'user')?.text || '').replace(/\s+/g, ' ').slice(0, 80),
    lastUserAt: s.lastUserAt || 0,
    turns: s.messages.filter(m => m.role === 'user').length,
    lastText: (s.messages[s.messages.length - 1]?.text || '').slice(0, 80),
    awaitingApproval: !!s.awaitingApproval,
  }));
}

export function runningSessionId() {
  for (const s of sessions.values()) {
    if ((s.turnActive || s.status === 'running') && s.pty != null && s.pty._exitCode === undefined) return s.id;
  }
  return null;
}

// ── Process admin (PTY model) ────────────────────────────────────────────────
// Since the PTY rewrite, agy no longer runs via powershell wrappers in agy-tmp,
// so process-admin.mjs (which hunts those wrappers) finds nothing. The bridge
// owns the PTY handles directly in the sessions map, so list/kill from there.

// Live agy PTYs, shaped like process-admin's listProcessesRoute output.
export function listLiveProcesses() {
  const processes = [];
  for (const s of sessions.values()) {
    if (s.pty && s.pty._exitCode === undefined && typeof s.pty.pid === 'number') {
      processes.push({ pid: s.pty.pid, name: 'agy.exe', sessionId: s.id, cwd: s.cwd });
    }
  }
  return { processes, count: processes.length };
}

// Kill every live PTY and drop all session entries so "active sessions" clears.
export function killAllSessions() {
  const killed = [];
  const errors = [];
  for (const s of sessions.values()) {
    if (s._sessionPoll) { clearInterval(s._sessionPoll); s._sessionPoll = null; }
    if (s.pty && s.pty._exitCode === undefined) {
      try { s.pty.kill(); killed.push(s.pty.pid); }
      catch (e) { errors.push({ sessionId: s.id, error: e.message }); }
    }
  }
  const cleared = sessions.size;
  sessions.clear();
  return { ok: errors.length === 0, killed, cleared, errors };
}

// ═══════════════════════════════════════════════════════════════════════════════
// ── Disk scan cache ───────────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════════════════

const DISK_CACHE_MS = 5000;
const emptyDiskCache = () => ({ at: 0, dirMtime: 0, files: [], list: null });
const _diskCaches = { cli: emptyDiskCache(), ide: emptyDiskCache() };
function dirSignature(dir) {
  try { return fs.statSync(dir).mtimeMs || 0; } catch { return 0; }
}
function ensureDiskCacheFresh(home) {
  const cache = _diskCaches[home];
  const now = Date.now();
  const convDir = conversationsDir(home);
  const sig = dirSignature(convDir);
  if (cache.list && (now - cache.at) < DISK_CACHE_MS && sig === cache.dirMtime) return;
  let names;
  try { names = fs.readdirSync(convDir).filter(f => f.endsWith('.db')); } catch { names = []; }
  const files = [];
  for (const f of names) {
    const id = f.replace(/\.db$/, '');
    let st; try { st = fs.statSync(path.join(convDir, f)); } catch { continue; }
    files.push({ id, mtime: st.mtimeMs });
  }
  files.sort((a, b) => b.mtime - a.mtime);
  Object.assign(cache, { at: now, dirMtime: sig, files, list: null });
}
function invalidateDiskCache() {
  _diskCaches.cli = emptyDiskCache();
  _diskCaches.ide = emptyDiskCache();
}

function diskSessionsForHome(home) {
  ensureDiskCacheFresh(home);
  const cache = _diskCaches[home];
  if (cache.list) return cache.list;
  const rows = [];
  for (const { id, mtime } of cache.files) {
    const parsed = parseTranscript(id, home);
    if (!parsed.turns) continue;
    rows.push({
      id,
      cwd: cwdForConversation(id),
      title: (parsed.firstUser || '').replace(/\s+/g, ' ').slice(0, 80),
      lastText: (parsed.lastText || '').replace(/\s+/g, ' ').slice(0, 100),
      turns: parsed.turns,
      mtime,
      home,
      source: home,
      sourceLabel: HOMES[home].label,
    });
  }
  cache.list = rows;
  return rows;
}

// Her iki home'u (CLI + desktop IDE) birleşik listeler — telefon, desktop
// Antigravity oturumlarını da görür ve adopt edip canlı izleyebilir/devam ettirebilir.
export function listDiskSessions({ all: includeAll = false } = {}) {
  const all = [...diskSessionsForHome('cli'), ...diskSessionsForHome('ide')];
  all.sort((a, b) => b.mtime - a.mtime);
  return { ok: true, sessions: includeAll ? all : all.slice(0, 100) };
}

// Konuşmayı app'ten VE diskten sil: brain/<id> klasörünü (transcript + loglar) ve
// conversations/<id>.db dosyasını doğru home'dan kaldırır. Canlı oturum varsa önce
// durdurulur ve bellekten düşer. Geri alınamaz. NOT: Antigravity IDE aynı konuşmayı
// açık tutuyorsa silme kilit nedeniyle başarısız olabilir (eski oturumlarda sorun yok).
export function deleteDiskSession({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  if (!isSafeSessionId(id)) return { ok: false, error: 'geçersiz oturum kimliği' };
  const s = sessions.get(id);
  const home = (s && s.home) || detectHome(id);
  if (!home) return { ok: false, error: 'konuşma bulunamadı: ' + id };
  if (s) { try { stop(id); } catch {}; sessions.delete(id); }
  const dir = path.join(brainDir(home), id);
  const dbFile = path.join(conversationsDir(home), id + '.db');
  try {
    if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true, force: true });
    if (fs.existsSync(dbFile)) fs.rmSync(dbFile, { force: true });
  } catch (e) {
    return { ok: false, error: 'silinemedi: ' + (e.message || String(e)) };
  }
  invalidateDiskCache();
  return { ok: true, id, home };
}

export function adoptSession({ id, cwd, home }) {
  if (!id) return { ok: false, error: 'id required' };
  if (!isSafeSessionId(id)) return { ok: false, error: 'geçersiz oturum kimliği' };
  if (sessions.has(id)) return { ok: true, sessionId: id, cwd: sessions.get(id).cwd, model: sessions.get(id).model };
  invalidateDiskCache();
  // Konuşmanın gerçek home'u diskten tespit edilir (IDE oturumları da adopt edilebilir);
  // explicit home parametresi öncelikli, hiçbiri yoksa cli varsayılır.
  const sessionHome = HOMES[home] ? home : (detectHome(id) || 'cli');
  const parsed = parseTranscript(id, sessionHome);
  const foundCwd = cwd || cwdForConversation(id) || os.homedir();
  if (!fs.existsSync(foundCwd)) return { ok: false, error: 'cwd not found: ' + foundCwd };
  sessions.set(id, {
    id,
    conversationId: id,
    home: sessionHome,
    cwd: foundCwd,
    model: '',
    status: 'idle',
    messages: parsed.messages,
    toolDetails: parsed.toolDetails,
    pty: null,
    turnActive: false,
    awaitingApproval: false,
    pendingPrompt: null,
    awaitingFirstOutput: false,
    subscribers: new Set(),
    _spawnTime: 0,
    _sessionPoll: null,
    _lastGrowth: 0,
    _lastActivity: 0,
    _turnStartTime: 0,
    _turnPrevAgentCount: 0,
    _turnAgentSeenAt: null,
  });
  return { ok: true, sessionId: id, cwd: foundCwd, model: '', home: sessionHome };
}

// ═══════════════════════════════════════════════════════════════════════════════
// ── Antigravity IDE management ────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════════════════

const ANTIGRAVITY_EXE = [
  path.join(process.env.LOCALAPPDATA || '', 'Programs', 'antigravity', 'Antigravity.exe'),
  path.join(os.homedir(), 'AppData', 'Local', 'Programs', 'antigravity', 'Antigravity.exe'),
].find(p => { try { return fs.existsSync(p); } catch { return false; } });

let _lastOpenAttempt = 0;
const OPEN_COOLDOWN_MS = 15_000;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function checkLanguageServer() {
  return new Promise(resolve => {
    const c = spawn('powershell.exe', ['-NoProfile', '-Command',
      '$p=Get-CimInstance Win32_Process -Filter "Name=\'language_server.exe\'";' +
      'if($p){exit(0)}exit(1)'],
      { windowsHide: true, timeout: 5000, stdio: 'pipe' });
    let done = false;
    const t = setTimeout(() => { if (!done) { c.kill(); resolve(false); } }, 5000);
    c.on('close', code => { done = true; clearTimeout(t); resolve(code === 0); });
    c.on('error', () => { done = true; clearTimeout(t); resolve(false); });
  });
}

export async function openAntigravity() {
  if (Date.now() - _lastOpenAttempt < OPEN_COOLDOWN_MS) {
    return { ok: false, error: 'cooldown' };
  }
  _lastOpenAttempt = Date.now();
  if (!ANTIGRAVITY_EXE) return { ok: false, error: 'Antigravity IDE bulunamadi' };
  try {
    spawn(ANTIGRAVITY_EXE, [], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
    const deadline = Date.now() + 20_000;
    while (Date.now() < deadline) {
      if (await checkLanguageServer()) return { ok: true };
      await sleep(1000);
    }
    return { ok: false, error: 'language_server.exe zaman asimi' };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

// Read‑only content search over AGY disk sessions (Phase 7 global search).
export function searchDiskSessions({ query, cwd, limit = 40, deadline = 0 } = {}) {
  const q = (query || '').toLowerCase().trim();
  if (q.length < 2) return [];
  const all = [...diskSessionsForHome('cli'), ...diskSessionsForHome('ide')];
  const results = [];
  let kesildi = false;
  for (const s of all) {
    // Süre bütçesi (bkz. search.mjs): senkron fonksiyon, durma kararı burada.
    // AGY'de ayrıca ÖNBELLEK YOK — aşağıdaki parseTranscript her sorguda her
    // oturumu yeniden ayrıştırıyor, yani bu kontrol en çok burada işe yarıyor.
    if (deadline && Date.now() > deadline) { kesildi = true; break; }
    if (cwd && cwd.toLowerCase() !== (s.cwd || '').toLowerCase()) continue;
    if (results.length >= limit) break;
    // Session title match
    if ((s.title || '').toLowerCase().includes(q)) {
      results.push({
        type: 'session', sessionId: s.id, title: s.title, projectPath: s.cwd || '',
        role: '', text: s.lastText || s.title || '', mtime: s.mtime || 0,
      });
    }
    // Full transcript message search (matchOrdinal ile — sohbet içi arama sırasıyla
    // hizalı, deep‑link doğru eşleşmeye kaydırır).
    // DIKKAT: satir alanlari diskSessionsForHome'dan gelir; anahtar `id`dir,
    // `conversationId` diye alan yok — eski kod undefined'la path.join'e girip
    // tum agy aramasini patlatiyordu (globalSearch warning olarak yutuyordu).
    if (results.length >= limit) break;
    const parsed = parseTranscript(s.id, s.home);
    for (const m of matchTranscriptMessages(parsed.messages, q)) {
      if (results.length >= limit) break;
      results.push({
        type: 'message', sessionId: s.id, title: s.title || '', projectPath: s.cwd || '',
        role: m.role, text: m.text, rowId: '', matchOrdinal: m.matchOrdinal, mtime: s.mtime || 0,
      });
    }
  }
  // Kesildiyse çağıran BİLMELİ: `globalSearch` bunu süreye bakarak tahmin
  // edemiyor (kendinden öncekiler bütçeyi yemişse yanlış pozitif üretiyordu).
  return { hits: results, truncated: kesildi };
}
