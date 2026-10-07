// Claude Code "app-server" runner — telefondan konuşulan KALICI (uzun-ömürlü) arka uç.
//
// Mevcut claude.mjs her tur için tek-atımlık `claude -p` süreci açar: tek yönlü,
// süreç turun sonunda ölür, bu yüzden interaktif onay / plan modu / turn-ortası
// müdahale YAPAMAZ. Bu modül onun YANINA (claude.mjs'i değiştirmeden) çift-yönlü,
// kalıcı bir kanal ekler — app-server deseninde:
//
//   claude -p --input-format stream-json --output-format stream-json --verbose
//
// Bu modda süreç ölmez. stdin'e NDJSON (satır-bazlı JSON) user mesajı yazarız,
// stdout'tan stream-json event akar (assistant / thinking / tool_use / result —
// claude.mjs'teki reducer ile aynı). Çift yönlü olduğu için CLI bize tool izin
// isteğini `control_request` olarak gönderir; biz `control_response` ile cevap
// veririz → İNTERAKTİF ONAYLAR (cowork'ün anahtarı).
//
// Dışa açık sözleşme (export imzaları + WebSocket'e basılan mesaj şekli) diğer
// app-server backend'leriyle aynıdır: server.mjs registerBackend ile değişmeden bağlar.
//
// control_request/control_response akışı --permission-prompt-tool stdio ile
// ampirik doğrulandı ve çalışıyor (bkz. aşağıdaki CONTROL bölümü). Akış
// (text/thinking/tool/result), süreç yaşam döngüsü ve onay kanalı hepsi aktif.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { hhmm, capMessages, capToolDetails, pruneEvictedThoughtDetail, killChildTree, killChildTreeSync, logWarn, extractChoices, spawnResolvedExecutable, resolveExecutableSync, matchTranscriptMessages, isSafeModelId, isSafeSessionId, isStaleApproval } from './session-utils.mjs';
import { reapIdleChildren as reapIdle } from './idle-reaper.mjs';
import { spawn } from 'node:child_process';
import { archiveMessagesToDisk, clearArchivedMessages, createAgentSessionCore, createSessionWatchdog, createShellPersistence, createTurnLifecycle, ensureMessageRowIds, paginateSessionMessages } from './agent-session-core.mjs';
import { createClaudeEventAdapter } from './claude-app-adapter.mjs';
import { describeSkills, readSkillCatalog } from './skills.mjs';
import { summarizeTool, textFromContent, parseTranscript, parseTranscriptHead, parseTranscriptText, isNoiseUserRecord } from './claude-transcript.mjs';

// A2: capMessages baştan sildiğinde delta halkasını sıfırla. Kırpılan thought
// satırlarının artık referanssız kalan detayları boşaltılır ve toplam toolDetails
// bütçesi uygulanır (codex-app'teki capSession ile aynı disiplin; bunlar olmadan
// claude-app'in toolDetails'i sınırsız büyüyordu).
function capSess(s) {
  ensureMessageRowIds(s);
  const evicted = capMessages(s, undefined, msg => pruneEvictedThoughtDetail(s, msg));
  archiveMessagesToDisk(s, evicted, { dir: path.join(os.homedir(), '.claude', 'bridge-message-archive') });
  if (evicted.some(msg => !msg?.rowId)) s._deltaDirty = true;
  capToolDetails(s);
}

// Model id'leri claude.mjs ile aynı (CLI alias'ları: opus/sonnet/haiku).
export const CLAUDE_APP_MODELS = [
  { label: 'Opus 5.5', id: 'claude-opus-5-5' },
  { label: 'Sonnet 5', id: 'sonnet' },
  { label: 'Fable 5.1', id: 'claude-fable-5-1' },
  { label: 'Haiku 4.5', id: 'haiku' },
];
export const MODELS = CLAUDE_APP_MODELS;

// Opus 5.5 (22 Eyl 2026) Opus 5'in yerini aldı; eski id'yle kaydedilmiş
// oturumlar geri yüklenirken yeni sürüme taşınır.
const RETIRED_MODEL_IDS = { 'claude-opus-5': 'claude-opus-5-5' };
function upgradeModelId(model) { return RETIRED_MODEL_IDS[model] || model; }

// Prompt makroları: stream-json -p modunda CLI slash/skill genişletmesi yapmaz
// (bkz. slash router notu), bu yüzden skill metnini burada tutup normal user
// prompt olarak göndeririz. Kaynak: plannotator/dev-skills → bro.
export const PROMPT_MACROS = {
  bro: [
    'Restate your last message.',
    'Stop using jargon and speak coherently.',
    'State it more simply and concisely,',
    'like one human talking to another.',
  ].join('\n'),
  // devret skill'i uzun ve prosedürel; metnini buraya kopyalarsak iki nüsha
  // zamanla ayrışır. Bunun yerine Skill aracına yönlendiriyoruz — skill
  // model-invocable olduğu için CLI genişletmesine ihtiyaç kalmıyor.
  devret: 'Skill aracıyla "devret" skill\'ini yükle ve yönergesini uygula.',
};

// Tek model-default kaynağı (madde 7): cowork.mjs ve Android buradan okur,
// string'leri gömmek yerine. Default Opus — cowork için en güçlü model.
export function defaultModel() { return 'claude-opus-5-5'; }

const CLAUDE_CONTEXT_WINDOW = 200_000;
const CLAUDE_CONTEXT_WINDOW_1M = 1_000_000;
const ACTIVITY_TIMEOUT_MS = 10 * 60 * 1000;
const TURN_TIMEOUT_MS = 2 * 60 * 60 * 1000;
const FIRST_OUTPUT_TIMEOUT_MS = 120_000;
// tool_result çıktısının toolDetails'e eklenen kısmı için üst sınır (tam metin
// /thought ucundan okunur; snapshot'a taşınmadığı için boyut riski yoktur, yine de sınır).
const MAX_TOOL_RESULT_CHARS = 20_000;

// CLI pencereyi bildirmiyor, tahmin şart. Eski "opus → 1M, gerisi 200k"
// kuralı Fable'ı 200k sayıyordu ve doluluk %48 gibi görünüyordu (gerçek ~%10;
// kullanıcı yakaladı, 18.08.2026). Doğrulanmış tablo (platform.claude.com
// model listesi, aynı gün): Fable 5 / Opus 5-4.8-4.7-4.6 / Sonnet 5-4.6 → 1M;
// Haiku 4.5, Sonnet 4.5, Opus 4.5 → 200k. Bilinmeyen model 200k'da kalır —
// effectiveWindow zaten taşarsa 1M'e yükseltiyor, ters yönde yalan söylemez.
function defaultWindowFor(model) {
  const m = String(model || '').toLowerCase();
  if (m.includes('fable') || m.includes('mythos')) return CLAUDE_CONTEXT_WINDOW_1M;
  if (m.includes('haiku') || m.includes('opus-4-5') || m.includes('sonnet-4-5')) return CLAUDE_CONTEXT_WINDOW;
  if (m.includes('opus') || m.includes('sonnet')) return CLAUDE_CONTEXT_WINDOW_1M;
  return CLAUDE_CONTEXT_WINDOW;
}
function effectiveWindow(s) {
  let cw = defaultWindowFor(s.model);
  if ((s.contextTokens || 0) > cw) cw = CLAUDE_CONTEXT_WINDOW_1M;
  return cw;
}

const sessions = new Map(); // id (uuid) -> session
const BRIDGE_CLAUDE_APP_SESSIONS_FILE = path.join(os.homedir(), '.claude', 'bridge-claude-app-sessions.json');
// /clear sonrası eski id ile gelen istekler için kısa ömürlü alias
const sessionAliases = new Map(); // oldId -> { newId, at }
const SESSION_ALIAS_TTL_MS = 10 * 60_000; // 10 dk
const SESSION_ALIAS_MAX = 20; // son 20 alias
function resolveSession(id) {
  const s = sessions.get(id); if (s) return s;
  const a = sessionAliases.get(id);
  if (a && (Date.now() - a.at) < SESSION_ALIAS_TTL_MS) return sessions.get(a.newId);
  if (a) sessionAliases.delete(id); // TTL dolmuş alias'ı temizle
  return undefined;
}

// ── Tek hesap ───────────────────────────────────────────────────────────────
// Claude CLI'nin kimliği CLAUDE_CONFIG_DIR ile ayrılabildiği için köprü bir dönem
// birden fazla profili (personal/work) yan yana sunuyordu. 28 Ağu 2026'da tek
// hesaba dönüldü — kullanıcı asıl hesabını Max'e çektiği için ikinci hesaba gerek
// kalmadı. Artık tek dizin var: CLI'ın kendi varsayılanı olan ~/.claude.
const CLAUDE_CONFIG_DIR = path.join(os.homedir(), '.claude');
const CLAUDE_PROJECTS_DIR = path.join(CLAUDE_CONFIG_DIR, 'projects');

// Skill dizinleri: proje-yereli genel olanı gölgeler, bu yüzden cwd önce.
function skillDirs(cwd) {
  const dirs = [];
  if (cwd) dirs.push(path.join(cwd, '.claude', 'skills'));
  dirs.push(path.join(CLAUDE_CONFIG_DIR, 'skills'));
  return dirs;
}

// Kurulu skill'leri diskten oku: <CLAUDE_CONFIG_DIR>/skills/<ad> ve <cwd>/.claude/skills/<ad>.
// init event her CLI sürümünde `skills` alanı taşımadığı için fallback envanteri budur
// (setup-cowork-skills.ps1 docx/xlsx/pptx/pdf'i configDir/skills altına kurar).
function listInstalledSkills(cwd) {
  return readSkillCatalog(skillDirs(cwd)).map(s => s.name);
}

// ── Session fabrikası ─────────────────────────────────────────────────────────
// CLI --permission-mode tüm seçenekleri (claude --help). Boş/geçersiz → '' (CLI default).
const VALID_PERMISSION_MODES = ['acceptEdits', 'auto', 'bypassPermissions', 'default', 'dontAsk', 'plan'];
// Mod ALANI HİÇ GEÇİLMEYEN yeni oturumun varsayılanı (kullanıcı isteği,
// 11 Ağu 2026; öncesi bypassPermissions'tı). Açıkça '' geçmek başka bir şey
// demek: "CLI'ın kendi varsayılanı" (cowork onay modu buna dayanıyor), o yüzden
// undefined ile boş string'i ayırıyoruz.
const DEFAULT_PERMISSION_MODE = 'auto';
const _PERM_BY_LOW = new Map(VALID_PERMISSION_MODES.map(m => [m.toLowerCase(), m]));
function normalizePermissionMode(mode) {
  const m = String(mode || '').trim();
  if (!m) return '';
  return _PERM_BY_LOW.get(m.toLowerCase()) || '';
}

function normalizeQuestionOptions(options) {
  if (!Array.isArray(options)) return [];
  return options.map((o, i) => {
    if (typeof o === 'string') return { id: String(i), label: o, description: '' };
    return {
      id: String(o?.id ?? o?.value ?? o?.label ?? i),
      label: String(o?.label ?? o?.value ?? o?.text ?? o?.id ?? ''),
      description: String(o?.description ?? o?.desc ?? ''),
    };
  }).filter(o => o.label);
}

export function normalizeApprovalQuestions(input) {
  const qs = Array.isArray(input?.questions) ? input.questions : [];
  return qs.map((q, i) => ({
    id: String(q?.id ?? `q${i + 1}`),
    header: String(q?.header ?? q?.label ?? ''),
    question: String(q?.question ?? q?.text ?? q?.prompt ?? ''),
    multiple: q?.multiSelect === true || q?.multiple === true,
    custom: q?.custom !== false,
    options: normalizeQuestionOptions(q?.options),
  })).filter(q => q.question || q.options.length);
}

export function buildQuestionUpdatedInput(input, answers) {
  const qs = normalizeApprovalQuestions(input);
  // Aynı soru için birden fazla cevap gelebilir (multiSelect): id'ye göre BİRİKTİR,
  // Map'e tek tek yazsaydık sonuncusu öncekini ezerdi.
  const byId = new Map();
  for (const a of (Array.isArray(answers) ? answers : [])) {
    const id = String(a?.id || '');
    if (!byId.has(id)) byId.set(id, []);
    byId.get(id).push(a);
  }
  // AskUserQuestion input şeması: `answers` bir RECORD'dur ({ [soru metni]: seçilen
  // etiket }), array DEĞİL; ayrıca top-level additionalProperties:false olduğu için
  // yalnız questions/answers/annotations/metadata alanları geçerlidir. Eskiden burada
  // `answers` array + fazladan `responses` alanı gönderiliyordu; host updatedInput'u
  // şemaya uymadığı için düşürüyor ve kullanıcının seçimi ajana HİÇ ulaşmıyordu.
  // Değer tipi string olduğu için çoklu seçim tek satırda virgülle birleşir.
  const responses = {};
  for (const q of qs) {
    const labels = [];
    for (const a of (byId.get(q.id) || [])) {
      const option = q.options.find(o => o.id === String(a.optionId || '') || o.label === String(a.label || ''));
      const label = option?.label || String(a.label || a.text || a.optionId || '');
      if (label && !labels.includes(label)) labels.push(label);
    }
    if (labels.length) responses[q.question || q.id] = labels.join(', ');
  }
  return {
    ...(input || {}),
    answers: responses,
  };
}

function initFromEvent(ev) {
  const src = ev.message || ev.payload || ev;
  return {
    tools: Array.isArray(src.tools) ? src.tools : [],
    mcpServers: Array.isArray(src.mcp_servers) ? src.mcp_servers : [],
    slashCommands: Array.isArray(src.slash_commands) ? src.slash_commands : [],
    agents: Array.isArray(src.agents) ? src.agents : [],
    skills: Array.isArray(src.skills) ? src.skills : [],
    plugins: Array.isArray(src.plugins) ? src.plugins : [],
    model: src.model || '',
    permissionMode: src.permissionMode || src.permission_mode || '',
    memoryPaths: Array.isArray(src.memory_paths) ? src.memory_paths : [],
    version: src.claude_code_version || src.version || '',
  };
}

// Claude CLI --effort seviyeleri (claude --help). Boş = CLI default (model'e göre).
const CLAUDE_EFFORT_LEVELS = ['', 'low', 'medium', 'high', 'xhigh', 'max'];
function normalizeEffort(e) {
  const v = String(e || '').toLowerCase().trim();
  return CLAUDE_EFFORT_LEVELS.includes(v) ? v : '';
}

function freshSession(id, dir, model, permissionMode = '', effort = '', cowork = false) {
  return {
    id, cwd: dir, model: model || defaultModel(), status: 'idle',
    messages: [], toolDetails: [], child: null, subscribers: new Set(),
    contextTokens: 0, awaitingFirstOutput: false,
    permissionMode: normalizePermissionMode(permissionMode),
    effort: normalizeEffort(effort),
    cowork: !!cowork,                  // Cowork modu: outputs/ teslimat sözleşmesi
    init: null,
    interruptStuck: false,
    lastCost: 0, lastUsage: null,      // maliyet/token rozeti (result event'inden)
    lastOutputs: [],                   // cowork: turdan yeni/değişen teslimat dosyaları
    // kalıcı süreç durumu
    stdoutBuf: '',           // stdout NDJSON yarım-satır tamponu
    _turnHadAgent: false,
    _watchdog: null, _lastActivity: 0, _turnStartedAt: 0, _gotFirstOutput: false, _interruptTimer: null,
    _toolUseIndex: new Map(),          // tool_use_id -> toolDetails idx (sonuç eşleştirme)
    _outputsSeen: null,      // cowork: tur başı outputs/ anlık görüntüsü (mtime bazlı diff)
    // onay (control) kuyruğu: request_id -> { tool, input }
    pendingApproval: null,  // { requestId, tool, summary } | null
  };
}

const claudeShellPersistence = createShellPersistence({
  file: BRIDGE_CLAUDE_APP_SESSIONS_FILE,
  sessions,
  serializeSession: s => ({
    id: s.id,
    cwd: s.cwd || '',
    model: s.model || defaultModel(),
    permissionMode: s.permissionMode || '',
    effort: s.effort || '',
    cowork: !!s.cowork,
    lastActivity: s._lastActivity || 0,
    rowSeq: s._rowSeq || 0,
  }),
  restoreSession: p => {
    const dir = p.cwd || os.homedir();
    const s = freshSession(p.id, dir, upgradeModelId(p.model) || defaultModel(), p.permissionMode || '', p.effort || '', !!p.cowork);
    s._lastActivity = p.lastActivity || 0;
    s._rowSeq = Number.isInteger(p.rowSeq) ? p.rowSeq : 0;
    s.resumeFromDisk = true;
    s._restoredShell = true;
    return s;
  },
  log: (message, data) => logWarn('claude-app', 'persist ' + message, data),
});

function persistSessions() {
  claudeShellPersistence.persist();
}

export function __restoreSessionsFromData(data) {
  return claudeShellPersistence.restoreFromData(data);
}

export function __getSession(id) {
  return sessions.get(id);
}

export function newSession({ cwd, model, permissionMode, effort, cowork } = {}) {
  let dir = cwd && String(cwd).trim() ? String(cwd).trim() : os.homedir();
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return { ok: false, error: 'cwd not a directory: ' + dir };
  const id = randomUUID();
  const mode = permissionMode === undefined
    ? DEFAULT_PERMISSION_MODE
    : normalizePermissionMode(permissionMode);
  const eff = normalizeEffort(effort);
  sessions.set(id, freshSession(id, dir, model || defaultModel(), mode, eff, cowork));
  persistSessions();
  // Cowork oturumu: teslimat sözleşmesi olan outputs/ klasörünü cwd altında garantile.
  if (cowork) {
    const outputsDir = path.join(dir, 'outputs');
    try { fs.mkdirSync(outputsDir, { recursive: true }); } catch {}
  }
  return { ok: true, sessionId: id, cwd: dir, model: model || defaultModel(), permissionMode: mode, effort: eff, cowork: !!cowork };
}

function isRunning(s) { return s.status === 'running' && s.child != null; }

function killPersistentChild(s) {
  if (!s?.child) return;
  try { killChildTree(s.child.pid); } catch {}
  s.child = null;
  // Child ölünce oturum refreshViewerFromDisk için "izleyici" gibi görünür. Canlı
  // akıştan gelen satırlar zaten s.messages'ta VE transkriptte olduğundan, imleci
  // dosya sonuna sabitlemezsek aynı tur diskten ikinci kez eklenir (model değişimi
  // → son alışveriş kopyası; canlı üretildi). Bundan SONRA yazılanlar (ör. masaüstünde
  // süren tur) normal akmaya devam eder.
  seedDiskCursorToEof(s);
}

// Disk-senkron imlecini transkriptin mevcut sonuna al. Dosya yoksa no-op.
function seedDiskCursorToEof(s) {
  if (!s) return;
  let file = s._diskFile || '';
  let st = null;
  if (file) { try { st = fs.statSync(file); } catch { st = null; } }
  if (!st) {
    const found = findTranscriptFile(s.id);
    if (!found) return;
    file = found.file;
    try { st = fs.statSync(file); } catch { return; }
  }
  s._diskFile = file;
  s._diskOffset = st.size;
  s._diskLineBuf = '';
  s._diskMtime = st.mtimeMs;
}

// Yeni/temizlenmiş oturum: eski dosyanın imleci taşınırsa silinen sohbet diskten
// geri gelir. clearSession id'yi değiştirdiği için imleç mutlaka sıfırlanmalı.
function resetDiskCursor(s) {
  if (!s) return;
  s._diskFile = '';
  s._diskOffset = 0;
  s._diskLineBuf = '';
  s._diskMtime = 0;
}

function rememberDesiredModel(s, model) {
  const mdl = String(model || '').trim();
  if (!mdl || !isSafeModelId(mdl)) return false;
  const changed = s.model !== mdl;
  s.model = mdl;
  // getInfo() prefers init.model when present; avoid showing the old child model
  // after a model switch has already been requested.
  if (s.init) s.init = { ...s.init, model: mdl };
  return changed;
}

// ── Cowork: outputs/ teslimat sözleşmesi ────────────────────────────────────────
// cwd/outputs altındaki dosyaları { name, path, size, mtime } olarak oku. Tek readdir + stat;
// alt klasörler taranmaz (sadece teslim dosyaları). outputs/ yoksa boş dizi.
export function scanOutputsSnapshot(cwd) {
  const dir = path.join(cwd, 'outputs');
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return []; }
  const out = [];
  for (const e of entries) {
    if (!e.isFile()) continue;
    const full = path.join(dir, e.name);
    let st; try { st = fs.statSync(full); } catch { continue; }
    out.push({ name: e.name, path: full, size: st.size, mtime: st.mtimeMs });
  }
  return out;
}

// Tur başı anlık görüntüsüne (before) göre yeni veya değişen (mtime farkı) dosyaları döndür.
// before null/boşsa mevcut tüm dosyaları döndür (ilk tur veya görüntü alınamadıysa).
export function diffOutputs(cwd, before) {
  const now = scanOutputsSnapshot(cwd);
  if (!before || !before.length) return now;
  const byName = new Map(before.map(o => [o.name, o]));
  return now.filter(o => {
    const old = byName.get(o.name);
    return !old || old.mtime !== o.mtime || old.size !== o.size;
  });
}

// ── Cowork: workspace yönetimi ─────────────────────────────────────────────────
// COWORK_ROOT altında workspace klasörleri oluşturulur/listelenir. Default ~/CoworkSpaces;
// AGENTBRIDGE_COWORK_ROOT env ile ezilir (AGENTBRIDGE_LLAMA_* konvansiyonu).
const COWORK_ROOT = process.env.AGENTBRIDGE_COWORK_ROOT
  ? path.resolve(process.env.AGENTBRIDGE_COWORK_ROOT)
  : path.join(os.homedir(), 'CoworkSpaces');

export function coworkRoot() { return COWORK_ROOT; }

// Workspace adını sanitize et: path ayraçlarını/kontrol karakterlerini temizle, baş/son
// noktaları kaldır. Boş veya 120 karakterden uzunsa null. { ok:false, error } değil null
// döner — çağıran (route) uygun HTTP cevabını kurar.
function sanitizeWorkspaceName(raw) {
  // \w ASCII-only'dir; onunla süzmek Türkçe harfleri yutuyordu ("ışık" → "k").
  // Unicode harf/rakam sınıfları (\p{L}\p{N}) ile süz: path ayraçları, Windows'un
  // yasak karakterleri (< > : " | ? *) ve kontrol karakterleri yine elenir.
  // NFC: ayrık yazılmış harf+birleşik işaret çifti tek koda toplanıp korunur.
  const clean = String(raw || '').normalize('NFC').trim()
    .replace(/[^\p{L}\p{N}._\- ]+/gu, '')   // harf/rakam/boşluk/tire/altçizgi/nokta
    .replace(/^\.+/, '')          // baştaki noktalar (gizli/relative)
    .replace(/[. ]+$/, '');       // sondaki nokta/boşluk (Windows yolları için)
  if (!clean || clean.length > 120) return null;
  return clean;
}

// { name } → { ok, name, path } | { ok:false, error }. COWORK_ROOT altında klasör oluşturur.
// Path traversal reddi: resolve edilen hedef mutlaka COWORK_ROOT altında olmalıdır.
export function createWorkspace({ name } = {}) {
  const clean = sanitizeWorkspaceName(name);
  if (!clean) return { ok: false, error: 'geçersiz workspace adı' };
  const target = path.resolve(path.join(COWORK_ROOT, clean));
  const rel = path.relative(COWORK_ROOT, target);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return { ok: false, error: 'workspace adı kök dışına çıkamaz' };
  try { fs.mkdirSync(COWORK_ROOT, { recursive: true }); fs.mkdirSync(target, { recursive: true }); }
  catch (e) { return { ok: false, error: String(e.message || e) }; }
  return { ok: true, name: clean, path: target };
}

// COWORK_ROOT altındaki workspace klasörlerini listeler (listDirs ile aynı filtreleme).
export function listWorkspaces() {
  try { fs.mkdirSync(COWORK_ROOT, { recursive: true }); } catch {}
  let entries;
  try { entries = fs.readdirSync(COWORK_ROOT, { withFileTypes: true }); } catch { return { ok: true, root: COWORK_ROOT, workspaces: [] }; }
  const dirs = entries
    .filter(e => e.isDirectory() && !e.name.startsWith('.') && e.name !== 'node_modules')
    .map(e => ({ name: e.name, path: path.join(COWORK_ROOT, e.name) }))
    .sort((a, b) => a.name.localeCompare(b.name));
  return { ok: true, root: COWORK_ROOT, workspaces: dirs };
}


function snapshot(s) {
  const obj = {
    type: 'conversation', sessionId: s.id, messages: s.messages, running: isRunning(s),
    awaitingApproval: !!s.pendingApproval,
    approval: s.pendingApproval ? { requestId: s.pendingApproval.requestId, kind: s.pendingApproval.kind || 'tool', summary: s.pendingApproval.summary, tool: s.pendingApproval.tool, description: s.pendingApproval.description, decisionReason: s.pendingApproval.decisionReason, permissionSuggestions: s.pendingApproval.permissionSuggestions, options: s.pendingApproval.options || [], questions: s.pendingApproval.questions || [] } : null,
    awaitingFirstOutput: s.awaitingFirstOutput ?? false,
    contextTokens: s.contextTokens || 0, contextWindow: effectiveWindow(s),
    cost: s.lastCost || 0, usage: s.lastUsage || null,   // maliyet/token rozeti
    permissionMode: s.permissionMode || '',
    effort: s.effort || '',
    cowork: !!s.cowork,                 // UI mod ayrımı (cowork sunumu)
    outputs: s.cowork ? (s.lastOutputs || []) : undefined, // cowork teslimat kartları
    init: s.init || null,
    interruptStuck: !!s.interruptStuck,
  };
  if (!isRunning(s)) {
    const lastAgent = [...s.messages].reverse().find(m => m.role === 'agent');
    const ec = lastAgent ? extractChoices(lastAgent.text) : null;
    obj.choices = ec ? ec.choices : null;
  }
  return obj;
}
const sessionCore = createAgentSessionCore({ snapshot });
const { pushSnapshot, throttledPush, flushThrottle } = sessionCore;
const turnLifecycle = createTurnLifecycle({
  flushThrottle,
  sendEnd: s => sessionCore.sendEnd(s),
  capSession: capSess,
  persist: () => persistSessions(),
  beforeFinalize: s => {
    disarmWatchdog(s);
    clearInterruptTimer(s);
    if (s.cowork) {
      s.lastOutputs = diffOutputs(s.cwd, s._outputsSeen);
      s._outputsSeen = null;
    }
    if (s.pendingApproval) {
      if (s.child) {
        procSend(s, {
          type: 'control_response',
          response: {
            request_id: s.pendingApproval.requestId,
            subtype: 'success',
            response: { behavior: 'deny', message: 'Tur sonlandirildi.' }
          }
        });
      }
      s.pendingApproval = null;
    }
    if (s._pendingRespawn) {
      s._pendingRespawn = false;
      // Ertelenen respawn (tur içinde model/hesap/mod/effort değişimi) de
      // killPersistentChild'dan geçmeli — imleç sabitlenmezse kopya geri gelir.
      killPersistentChild(s);
    }
  },
  errorMessage: (error, s) => {
    const hasAgent = (s.messages || []).some(m => m.role === 'agent');
    return hasAgent
      ? { role: 'thought', text: String(error || ''), thoughtIndex: -1, time: hhmm() }
      : { role: 'agent', text: String(error || ''), time: hhmm() };
  },
});
const claudeWatchdog = createSessionWatchdog({
  activityTimeoutMs: ACTIVITY_TIMEOUT_MS,
  firstOutputTimeoutMs: FIRST_OUTPUT_TIMEOUT_MS,
  turnTimeoutMs: TURN_TIMEOUT_MS,
  isPaused: s => !!s?.pendingApproval,
  log: (kind, s) => logWarn('claude-app', kind === 'turn-timeout' ? 'turn total watchdog timeout' : 'turn watchdog timeout', { sessionId: s.id }),
  onTimeout: (s, kind) => finalizeTurnIdle(s, {
    error: kind === 'turn'
      ? '[claude-app timeout] turn total limit reached.'
      : '[claude-app zaman asimi] cikti alinamadi.'
  }),
});
const handleEvent = createClaudeEventAdapter({
  // capSess: _deltaDirty sigortası + detay bütçesi olan sarmalayıcı. Ham capMessages
  // geçilirse en sık kırpma noktası (event reducer) sigortayı hiç tetiklemez.
  capMessages: capSess,
  clearInterruptTimer,
  extractChoices,
  finalizeTurnIdle,
  hhmm,
  initFromEvent,
  logWarn,
  maxToolResultChars: MAX_TOOL_RESULT_CHARS,
  normalizeApprovalQuestions,
  normalizePermissionMode,
  onAutonomousActivity: resumeAutonomousTurn,
  pushSnapshot,
  summarizeTool,
  textFromContent,
});

// ── Süreç yaşam döngüsü ───────────────────────────────────────────────────────
// İlk prompt'ta kalıcı süreci başlat. stdin AÇIK kalır; sonraki turlar aynı sürece
// yeni user mesajı yazar. (Mevcut claude.mjs'in tersine: orada her tur yeni süreç.)
function ensureProc(s) {
  if (s.child) return s.child;

  // not: workspace trust dialog -p modunda atlanır; --dangerously-skip-permissions
  // YOK çünkü asıl amaç interaktif onay almak (control_request). İzin modu varsayılan.
  // --permission-prompt-tool stdio: izin isteklerini stdout'a control_request
  // (subtype:can_use_tool) olarak yönlendirir; biz control_response ile cevaplarız.
  // Bu flag OLMADAN headless mod izin gerektiren tool'u sessizce reddeder (ampirik).
  const args = [
    '-p',
    '--input-format', 'stream-json',
    '--output-format', 'stream-json',
    '--verbose',
    '--include-partial-messages',
    '--permission-prompt-tool', 'stdio',
    '--model', s.model,
  ];
  // Diskte zaten var olan oturumu sürdürürken --resume; yeni oturumda --session-id.
  // (Mevcut id'yi --session-id ile vermek "Session ID already in use" hatası verir.)
  if (s.resumeFromDisk) args.push('--resume', s.id);
  else args.push('--session-id', s.id);
  if (s.permissionMode) args.push('--permission-mode', s.permissionMode);
  if (s.effort) args.push('--effort', s.effort);
  const child = spawnResolvedExecutable('claude', args, {
    cwd: s.cwd,
    windowsHide: true,
    env: { ...process.env, CLAUDE_CONFIG_DIR },
  }, 'AGENTBRIDGE_CLAUDE_BIN');
  wireChild(s, child);
  return child;
}

/**
 * Child'ın akış/olay dinleyicilerini oturuma bağlar. HER dinleyici önce "ben
 * hâlâ oturumun aktif child'ı mıyım" diye bakar (s.child === child).
 *
 * Bu kontrol olmadan yaşanan canlı vaka (05.08.2026): model değişimi eski
 * child'ı öldürdü, YENİ child spawn edildikten sonra eski child'ın gecikmeli
 * close olayı geldi; handler s.child'ı koşulsuz null'layıp sağlıklı tura sahte
 * "süreç beklenmedik kapandı (code=1)" bastı. Kullanıcı hatayı görüp prompt'u
 * tekrar gönderince İKİNCİ child açıldı ve öksüz kalan birinci child'la birlikte
 * AYNI oturuma paralel yazdılar — her mesaj sohbette çift göründü.
 *
 * Ayrı fonksiyon: testte sahte (EventEmitter) child'la bağlanabilsin diye.
 */
export function wireChild(s, child) {
  s.child = child;
  s.stdoutBuf = '';
  s._stderrTail = ''; // eski child'ın stderr'i yeni child'ın ölümüne yapışmasın
  child.stdout.on('data', chunk => {
    if (s.child !== child) return; // öksüz child oturuma yazamaz
    onStdout(s, chunk);
  });
  child.stderr.on('data', d => {
    if (s.child !== child) return;
    s._stderrTail = ((s._stderrTail || '') + d.toString()).slice(-4096);
  });
  child.on('error', e => {
    logWarn('claude-app', 'spawn hatası', { error: e.message });
    if (s.child !== child) return;
    finalizeTurnIdle(s, { error: '[claude-app spawn error] ' + e.message });
    s.child = null;
  });
  child.on('close', code => {
    if (s.child !== child) {
      // Bilerek öldürülen/değiştirilen eski child: sadece log, tura dokunma.
      logWarn('claude-app', 'eski child kapandı (oturum yeni child kullanıyor)', { code });
      return;
    }
    const tail = s._stderrTail ? s._stderrTail.slice(-500) : '';
    const detail = tail ? ' — stderr: ' + tail : '';
    logWarn('claude-app', 'süreç kapandı', { code, stderr: tail });
    s.child = null;
    if (s.status === 'running') {
      finalizeTurnIdle(s, { error: '[claude-app] süreç beklenmedik kapandı (code=' + code + ')' + detail });
      return;
    }
    // Tur "running" degilken olen child'in hatasi ESKIDEN tamamen yutuluyordu:
    // kullanici bos ekrana bakip "neden cevap vermedi" diyordu (06.08.2026, canli
    // vaka: --resume ile "No conversation found with session ID" → arka arkaya iki
    // gonderim, sifir geri bildirim). Kalici child turlar arasinda AYAKTA kalir;
    // sifirdan farkli kodla olmesi normal degil, kullaniciya gorunmeli.
    if (code !== 0 && tail) surfaceIdleChildFailure(s, code, tail);
  });
}

// Tur disinda olen kalici child'in hatasini sohbete dusur.
//
// Ozel durum: "No conversation found with session ID" — CLI oturumu --resume ile
// surdurulemiyor demektir (transcript bos ya da baska bir id'ye yazilmis). Bu
// oturum ARTIK KALICI OLARAK kirik: her gonderim ayni hatayla olur. Kullaniciya
// teknik stderr yerine ne yapacagini soyle.
function surfaceIdleChildFailure(s, code, tail) {
  const resumeBroken = /No conversation found with session ID/i.test(tail);
  const text = resumeBroken
    ? 'Bu oturum sürdürülemiyor: Claude CLI oturum kaydını bulamıyor, bu yüzden ' +
      'gönderdiğin mesaj işlenmedi. Yeni bir oturum aç (+) — bu sekmede tekrar ' +
      'denemek aynı hatayı verir.'
    : '[claude-app] süreç beklenmedik kapandı (code=' + code + ') — stderr: ' + tail;
  // Ayni hatayi arka arkaya tekrarlama (kullanici iki kez gonderirse iki kopya).
  const last = s.messages && s.messages[s.messages.length - 1];
  if (last && last.role !== 'user' && last.text === text) return;
  s.messages.push({ role: 'agent', text, time: hhmm() });
  s.awaitingFirstOutput = false;
  capSess(s);
  persistSessions();
  pushSnapshot(s);
}

// stdin'e bir NDJSON satırı yaz (user mesajı veya control_response).
function procSend(s, obj) {
  if (!s.child || !s.child.stdin || s.child.stdin.destroyed) return false;
  try { s.child.stdin.write(JSON.stringify(obj) + '\n'); return true; }
  catch (e) { logWarn('claude-app', 'stdin write hatası', { error: e.message }); return false; }
}

// stdout NDJSON akışı: satırlara böl, yarım satır tamponda bekler.
function onStdout(s, chunk) {
  claudeWatchdog.markActivity(s);
  if (s._interruptTimer) { clearInterruptTimer(s); s.interruptStuck = false; }
  claudeWatchdog.markActivity(s, { firstOutput: true });
  s.stdoutBuf += chunk.toString();
  let nl;
  while ((nl = s.stdoutBuf.indexOf('\n')) >= 0) {
    const line = s.stdoutBuf.slice(0, nl).trim();
    s.stdoutBuf = s.stdoutBuf.slice(nl + 1);
    if (!line) continue;
    let ev; try { ev = JSON.parse(line); } catch { continue; }
    try { handleEvent(s, ev); throttledPush(s); } catch (e) { logWarn('claude-app', 'event işleme hatası', { error: e.message }); }
  }
}

// ── Onay API'si (telefon → /claude-app/approve) ───────────────────────────────
export function approve({ sessionId, allow, answers, updatedInput, requestId: sentRequestId }) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.pendingApproval) return { ok: true, already: true };
  if (isStaleApproval(sentRequestId, s.pendingApproval.requestId)) return { ok: false, error: 'stale approval', stale: true };
  const { requestId, input } = s.pendingApproval;
  const nextInput = updatedInput || (answers ? buildQuestionUpdatedInput(input, answers) : input);
  if (allow && s.pendingApproval.kind === 'question') {
    const responseMap = nextInput?.answers && typeof nextInput.answers === 'object' ? nextInput.answers : {};
    const missingQuestionIds = normalizeApprovalQuestions(input)
      .filter(q => !String(responseMap[q.question || q.id] || '').trim())
      .map(q => q.id);
    if (missingQuestionIds.length) {
      return { ok: false, error: 'tüm sorular cevaplanmalı', missingQuestionIds };
    }
  }
  const response = allow
    ? { behavior: 'allow', updatedInput: nextInput || {} }
    : { behavior: 'deny', message: 'Kullanıcı reddetti (telefondan).' };
  procSend(s, {
    type: 'control_response',
    response: { request_id: requestId, subtype: 'success', response },
  });
  s.pendingApproval = null;
  s._lastActivity = Date.now(); // watchdog'u tazele (onay sonrası tur devam ediyor)
  pushSnapshot(s);
  return { ok: true };
}

// ── Interrupt API'si (telefon → /claude-app/interrupt) ─────────────────────────
// stop() süreci öldürür; interrupt() süreci öldürmeden sadece aktif turu keser.
// Bir sonraki prompt aynı oturumda devam eder.
export function interrupt(sessionId) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (s.status !== 'running' || !s.child) return { ok: true, already: true };
  const requestId = randomUUID();
  const ok = procSend(s, {
    type: 'control_request',
    request_id: requestId,
    request: { subtype: 'interrupt' },
  });
  clearInterruptTimer(s);
  if (ok) {
    s.interruptStuck = false;
    s._interruptTimer = setTimeout(() => {
      if (s.status === 'running') {
        s.interruptStuck = true;
        logWarn('claude-app', 'interrupt not acknowledged', { sessionId: s.id, requestId });
        pushSnapshot(s);
      }
    }, 15_000);
    if (s._interruptTimer.unref) s._interruptTimer.unref();
  }
  return { ok, requestId };
}

// ── Slash komut router'ı ─────────────────────────────────────────────────────
// stream-json -p modunda slash komutları genel olarak yorumlanmaz (doğrulandı: '/help' →
// "isn't available in this environment"; '/compact' ise CLI tarafından tanınır ve user text
// olarak gönderildiğinde çalışır). Bu yüzden sadece backend'in sahip olması gerekenleri
// (/clear, /model) burada ele alırız; geri kalanları (/compact dahil, custom komutlar)
// passthrough olarak normal prompt'a gönderip CLI'ın genişletmesine bırakırız.
// control_request ile compact/model DESTEKLENMİYOR (ampirik: "Unsupported control request
// subtype: compact") → bu yüzden model/permission değişimi respawn yoluyla uygulanır.
function clearSession(s) {
  killPersistentChild(s);
  disarmWatchdog(s);
  const oldId = s.id;
  const newId = randomUUID();
  s.id = newId;
  s.messages = [];
  s.toolDetails = [];
  if (s._toolUseIndex) s._toolUseIndex.clear(); else s._toolUseIndex = new Map();
  s.contextTokens = 0;
  s.lastCost = 0;
  s.lastUsage = null;
  s.init = null;
  s.resumeFromDisk = false;   // taze oturum → --resume değil --session-id
  resetDiskCursor(s);         // yeni id → yeni transkript; eski dosyanın imleci taşınmasın
  s.pendingApproval = null;
  s._pendingRespawn = false;
  s.lastOutputs = [];         // cowork: eski turun teslimat kartları temizlensin
  s._outputsSeen = null;
  s.status = 'idle';
  s.awaitingFirstOutput = false;
  s._gotFirstOutput = false;
  s._turnHadAgent = false;
  // Aynı oturum nesnesini yeni id altında re-key'le: mevcut WS aboneleri (s.subscribers)
  // aynı nesne referansında kaldığı için akım sürer; snapshot artık yeni sessionId taşır.
  sessions.delete(oldId);
  sessions.set(newId, s);
  // Eski id ile gelen istekleri yeni id'ye yönlendir (alias haritası).
  sessionAliases.set(oldId, { newId, at: Date.now() });
  // Alias haritası bütçesi: son 20'yi tut, TTL'si dolmuşları temizle.
  if (sessionAliases.size > SESSION_ALIAS_MAX) {
    const oldest = [...sessionAliases.entries()]
      .sort((a, b) => a[1].at - b[1].at)
      .slice(0, sessionAliases.size - SESSION_ALIAS_MAX);
    for (const [k] of oldest) sessionAliases.delete(k);
  }
  persistSessions();
  pushSnapshot(s);
  return newId;
}

// { command, args } → { ok, handled, passthrough?, ... }. handled=false ise çağıran
// (prompt veya /slash ucu) komut metnini normal user prompt olarak CLI'a gönderir.
export function slash({ sessionId, command, args } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const cmd = String(command || '').trim();
  const argStr = String(args || '').trim();
  if (!cmd) return { ok: false, handled: true, error: 'command required' };

  if (cmd === 'clear') {
    const newId = clearSession(s);
    return { ok: true, handled: true, sessionId: newId, cleared: true };
  }
  if (cmd === 'model') {
    if (!argStr) return { ok: false, handled: true, error: 'model required', models: CLAUDE_APP_MODELS };
    const r = setModel({ sessionId, model: argStr });
    return { ...r, handled: true };
  }
  // Prompt makroları (/bro): handled DEĞİL — normal tur olarak akar, ama CLI'a
  // komut yerine skill metni gider. args verilirse metnin sonuna eklenir.
  const macro = PROMPT_MACROS[cmd];
  if (macro) {
    return {
      ok: true, handled: false, passthrough: true, command: cmd, args: argStr,
      expanded: argStr ? `${macro}\n\n${argStr}` : macro,
    };
  }

  // /compact, custom komutlar (.claude/commands/*) ve bilinmeyenler: passthrough.
  // CLI /compact'ı tanır (doğrulandı); custom komutlar metin olarak gönderilir.
  return { ok: true, handled: false, passthrough: true, command: cmd, args: argStr };
}

// ── Turn yaşam döngüsü ────────────────────────────────────────────────────────
export function prompt({ sessionId, text, model, permissionMode } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };

  // Slash komut yönlendirme: '/cmd args' → slash router. /clear ve /model doğrudan
  // ele alınır (running iken bile); diğerleri (/compact, custom) passthrough olarak
  // normal prompt akışına düşer ve CLI'a user text olarak gönderilir.
  const trimmed = String(text || '').trimStart();
  // Makro genişletmesi: sohbet geçmişinde '/bro' görünür, CLI'a skill metni gider.
  let wireText = null;
  if (trimmed.startsWith('/')) {
    const m = trimmed.match(/^\/([a-zA-Z][\w-]*)\s*([\s\S]*)$/);
    if (m) {
      const sr = slash({ sessionId, command: m[1], args: m[2] });
      if (sr.handled) return sr;             // /clear, /model: stdin'e bir şey yazma
      if (sr.expanded) wireText = sr.expanded;
      // passthrough: normal prompt olarak devam et
    }
  }

  if (s.status === 'running') return { ok: false, error: 'busy' };
  if (!text || !String(text).trim()) return { ok: false, error: 'text required' };
  if (model && !isSafeModelId(String(model).trim())) return { ok: false, error: 'geçersiz model adı' };
  const modelChanged = model ? rememberDesiredModel(s, model) : false;
  if (modelChanged && s.child) killPersistentChild(s);
  if (!s.child) s.permissionMode = normalizePermissionMode(permissionMode || s.permissionMode);

  for (const m of s.messages) delete m._open;
  s.messages.push({ role: 'user', text: String(text), time: hhmm() });
  s.lastUserAt = Date.now(); // proje detayi siralamasi: son user prompt ani
  capSess(s);
  s.status = 'running';
  s.awaitingFirstOutput = true;
  s._turnHadAgent = false;
  s._gotFirstOutput = false;
  s._lastActivity = Date.now();
  s._turnStartedAt = s._lastActivity;
  s.interruptStuck = false;
  if (s.cowork) s._outputsSeen = scanOutputsSnapshot(s.cwd); // tur başı anlık görüntü
  ensureProc(s);
  armWatchdog(s);
  pushSnapshot(s);

  // stdin'e user mesajı yaz (stream-json input formatı).
  const ok = procSend(s, {
    type: 'user',
    message: { role: 'user', content: [{ type: 'text', text: String(wireText ?? text) }] },
  });
  if (!ok) { finalizeTurnIdle(s, { error: '[claude-app] stdin yazılamadı' }); return { ok: false, error: 'stdin write failed' }; }
  persistSessions();
  return { ok: true, sessionId: s.id };
}

// Gerçek uygulama createTurnLifecycle config'inde (beforeFinalize: watchdog,
// cowork outputs diff'i, bekleyen onaya deny, pendingRespawn). Buradaki sarmalayıcı
// yalnız delegasyondur — davranış değişikliği oraya yazılır.
function finalizeTurnIdle(s, opts = {}) {
  return turnLifecycle.finalizeTurnIdle(s, opts);
}

// ── Watchdog ──────────────────────────────────────────────────────────────────
// Gerçek uygulama createSessionWatchdog config'inde; bunlar ince delegasyon.
function armWatchdog(s) {
  return claudeWatchdog.arm(s);
}
function disarmWatchdog(s) {
  return claudeWatchdog.disarm(s);
}

function clearInterruptTimer(s) {
  if (s && s._interruptTimer) { clearTimeout(s._interruptTimer); s._interruptTimer = null; }
}

// Kalici Claude sureci bir arka plan gorevi tamamlandiginda, onceki `result`
// olayindan sonra prompt() cagrilmadan yeniden cikti uretebilir. Child ayaktaysa
// bu ilk otonom olayi yeni turun baslangici say; aksi halde Android meta verisi
// mesajlar akmaya devam ederken yanlislikla "bosta" gorunur.
function resumeAutonomousTurn(s) {
  if (!s || s.status === 'running' || !s.child) return;
  const now = Date.now();
  s.status = 'running';
  s.awaitingFirstOutput = false;
  s._turnHadAgent = false;
  s._gotFirstOutput = true;
  s._lastActivity = now;
  s._turnStartedAt = now;
  s.interruptStuck = false;
  armWatchdog(s);
  claudeWatchdog.markActivity(s, { firstOutput: true });
}

// Bir oturum jsonl'ini projects havuzunda ara (disk cache soğukken fallback).
function findTranscriptFile(id) {
  if (!isSafeSessionId(id)) return null;
  let dirs;
  try { dirs = fs.readdirSync(CLAUDE_PROJECTS_DIR, { withFileTypes: true }); } catch { return null; }
  for (const pd of dirs) {
    if (!pd.isDirectory()) continue;
    const candidate = path.join(CLAUDE_PROJECTS_DIR, pd.name, id + '.jsonl');
    if (fs.existsSync(candidate)) return { file: candidate };
  }
  return null;
}

function readFileRangeUtf8(file, start, end) {
  const len = Math.max(0, end - start);
  if (!len) return '';
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const buf = Buffer.alloc(len);
    const n = fs.readSync(fd, buf, 0, len, start);
    return buf.subarray(0, n).toString('utf-8');
  } catch {
    return '';
  } finally {
    try { if (fd != null) fs.closeSync(fd); } catch {}
  }
}
// Bridge'in SÜRMEDİĞİ (child'ı olmayan) "izleyici" oturumları disk jsonl'inden tazele.
// Asıl konuşma başka süreç (masaüstü Claude) tarafından yazılıyorsa, yeni mesajlar yalnız
// diskte olur; bellek kopyası ilk adopt anında donar. mtime değiştiyse yeniden parse ederiz.
function refreshViewerFromDisk(s) {
  if (!s || s.child) return;
  if (s.status === 'running') return;
  // Bilinen dosyayı önce dene: her poll'da iki hesabın projects ağacını taramak
  // yerine tek stat. Dosya kaybolduysa (silme/taşınma) tam aramaya düş.
  let file = s._diskFile || '';
  let st = null;
  if (file) { try { st = fs.statSync(file); } catch { st = null; } }
  if (!st) {
    const found = findTranscriptFile(s.id);
    if (!found) return;
    file = found.file;
    try { st = fs.statSync(file); } catch { return; }
  }
  const mt = st.mtimeMs;
  if (s._diskMtime && mt <= s._diskMtime) return;
  // Boyut değişmeden mtime değiştiyse (metadata dokunuşu) tam parse'a düşme.
  if (s._diskFile === file && s._diskOffset > 0 && st.size === s._diskOffset) {
    s._diskMtime = mt;
    return;
  }

  if (s._diskFile === file && s._diskOffset > 0 && st.size >= s._diskOffset) {
    const raw = readFileRangeUtf8(file, s._diskOffset, st.size);
    if (raw) {
      const combined = (s._diskLineBuf || '') + raw;
      const lastNl = combined.lastIndexOf('\n');
      const complete = lastNl >= 0 ? combined.slice(0, lastNl + 1) : '';
      s._diskLineBuf = lastNl >= 0 ? combined.slice(lastNl + 1) : combined;
      if (complete) parseTranscriptText(complete, { out: s });
      s._diskOffset = st.size;
      s._diskMtime = mt;
      capSess(s);
      return;
    }
  }

  const parsed = parseTranscript(file);
  if (!parsed.messages.length) return;
  s.messages = parsed.messages;
  // Tam re-parse yeni nesneler üretir → rowId'ler kaybolur ve ensureMessageRowIds
  // hepsini yeniden numaralar. Diff bunları "yeni satır" sanıp appendRow üretir ve
  // istemcide TÜM listeyi kopyalar (agent-session-core.mjs:318 not düştüğü mod).
  // Dirty işaretle: bu push delta yerine tam snapshot olarak gitsin.
  s._deltaDirty = true;
  s.toolDetails = parsed.toolDetails;
  if (parsed.contextTokens) s.contextTokens = parsed.contextTokens;
  if (parsed.model) s.model = parsed.model;
  s._diskFile = file;
  s._diskOffset = st.size;
  s._diskLineBuf = '';
  s._diskMtime = mt;
  // Tam re-parse = arşivdeki eski satırlar artık kopya; sıfırla ki birikmesin.
  clearArchivedMessages(s, { dir: path.join(os.homedir(), '.claude', 'bridge-message-archive') });
  capSess(s);
}

// İzleyici canlı sync: abonesi olan ama child'ı olmayan oturumlar (konuşma masaüstü
// Claude'da sürüyor) periyodik olarak diskten tazelenir; jsonl ilerlediyse snapshot
// push edilir → masaüstündeki tur telefonda canlı akar. Maliyet: aboneli izleyici
// oturum başına ~2 sn'de bir stat (dosya biliniyorsa); tam projects taraması yalnız
// dosya henüz bilinmiyorken ve en çok 10 sn'de bir yapılır.
function cleanupWatcher(s) {
  if (s._watcher) {
    try { s._watcher.close(); } catch {}
    s._watcher = null;
  }
  if (s._watchDebounce) {
    clearTimeout(s._watchDebounce);
    s._watchDebounce = null;
  }
}

function ensureWatcher(s) {
  if (s._watcher) return;
  if (!s._diskFile) return;
  if (!s.subscribers || s.subscribers.size === 0) return;
  if (s.child || s.status === 'running') return;

  const dir = path.dirname(s._diskFile);
  const base = path.basename(s._diskFile);

  try {
    const watcher = fs.watch(dir, (eventType, filename) => {
      if (!filename || filename === base) {
        if (s._watchDebounce) clearTimeout(s._watchDebounce);
        s._watchDebounce = setTimeout(() => {
          s._watchDebounce = null;
          if (s.child || s.status === 'running') return;
          const before = s._diskMtime || 0;
          try { refreshViewerFromDisk(s); } catch {}
          if ((s._diskMtime || 0) > before) throttledPush(s);
        }, 150);
      }
    });

    watcher.on('error', () => {
      cleanupWatcher(s);
    });

    s._watcher = watcher;
  } catch (err) {
    s._watcher = null;
  }
}

const VIEWER_POLL_MS = 10000;
const VIEWER_SEARCH_MS = 10_000;
const _viewerPoll = setInterval(() => {
  const now = Date.now();
  for (const s of sessions.values()) {
    if (!s.subscribers || s.subscribers.size === 0) {
      cleanupWatcher(s);
      continue;
    }
    if (s.child || s.status === 'running') {
      cleanupWatcher(s);
      continue;
    }
    if (!s._diskFile) {
      if (now - (s._diskSearchAt || 0) < VIEWER_SEARCH_MS) continue;
      s._diskSearchAt = now;
    }
    const before = s._diskMtime || 0;
    try { refreshViewerFromDisk(s); } catch {}
    if ((s._diskMtime || 0) > before) throttledPush(s);

    if (s._diskFile && !s._watcher) {
      ensureWatcher(s);
    }
  }
}, VIEWER_POLL_MS);
if (_viewerPoll.unref) _viewerPoll.unref();
// -- Standart sözleşme export'ları ─────────────────────────────────────────────
export function getConversation(sessionId, opts = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { messages: [], running: false, awaitingApproval: false, awaitingFirstOutput: false, contextTokens: 0, contextWindow: CLAUDE_CONTEXT_WINDOW, cost: 0, usage: null };
  refreshViewerFromDisk(s);  // izleyici oturumsa diskteki yeni mesajları yansıt
  return { ...snapshot(s), messages: paginateSessionMessages(s, opts) };
}

export function getInfo(sessionId) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const init = s.init || {};
  // init.skills boşsa (CLI sürümü bildirmiyorsa) diskten kurulu skill'leri göster.
  let skills = init.skills || [];
  if (!skills.length) skills = listInstalledSkills(s.cwd);
  return {
    ok: true,
    sessionId: s.id,
    model: init.model || s.model || '',
    permissionMode: init.permissionMode || s.permissionMode || '',
    version: init.version || '',
    tools: init.tools || [],
    mcpServers: init.mcpServers || [],
    slashCommands: init.slashCommands || [],
    agents: init.agents || [],
    skills,
    // Skill pill'i için ad + kısa açıklama; `skills` (düz ad listesi) eski
    // istemciler için olduğu gibi kalıyor.
    skillDetails: describeSkills(skills, skillDirs(s.cwd)),
    plugins: init.plugins || [],
    memoryPaths: init.memoryPaths || [],
    models: CLAUDE_APP_MODELS,                    // model seçici envanteri
    permissionModes: [...VALID_PERMISSION_MODES], // permission-mode seçici envanteri
  };
}

export function setPermissionMode({ sessionId, mode } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const m = normalizePermissionMode(mode);
  s.permissionMode = m;
  if (isRunning(s)) {
    // Tur aktifken control_request ile mode değişimi desteklenmiyor (doğrulandı: 'compact'
    // subtype bile 'Unsupported' hatası döndü). Bayrağı sakla + respawn iste; tur bitince
    // (finalizeTurnIdle) süreç kapatılır, bir sonraki prompt yeni --permission-mode ile doğar.
    s._pendingRespawn = true;
    persistSessions();
    pushSnapshot(s);
    return { ok: true, permissionMode: m, applied: 'deferred', note: 'tur bitiminde uygulanır' };
  }
  // Idle: kalıcı süreç yaşıyorsa hemen kapat ki bir sonraki prompt yeni mod ile doğsun.
  killPersistentChild(s);
  persistSessions();
  pushSnapshot(s);
  return { ok: true, permissionMode: m };
}

// Model değişimi (tur ortası dahil). Kalıcı süreç zaten ayağa kalkmışsa --model yeniden
// uygulanamaz; süreci kapatırız ki bir sonraki prompt ensureProc --resume + yeni --model ile
// yeniden doğsun (resumeFromDisk=true → --resume güvenli, sohbet korunur). control_request ile
// model değişimi desteklenmediği için (doğrulandı) respawn yolunu kullandık. Tur aktifken
// çağrılırsa süreci hemen kesmeden respawn bayrağını saklarız; tur bitince uygulanır.
// Effort değişimi. --effort runtime'da değiştirilemediği için (CLI tek seferlik flag),
// setModel/setPermissionMode ile aynı respawn deseni: tur aktifse defer; idle'da hemen
// child'ı kapat → sonraki prompt yeni --effort ile doğsun (--resume güvenli).
export function setEffort({ sessionId, effort } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const e = normalizeEffort(effort);
  s.effort = e;
  if (isRunning(s)) {
    s._pendingRespawn = true;
    persistSessions();
    pushSnapshot(s);
    return { ok: true, effort: e, applied: 'deferred', note: 'tur bitiminde uygulanır' };
  }
  killPersistentChild(s);
  persistSessions();
  pushSnapshot(s);
  return { ok: true, effort: e };
}

// Effort seçenekleri — UI için.
export const CLAUDE_APP_EFFORT_LEVELS = CLAUDE_EFFORT_LEVELS.filter(x => x);

export function setModel({ sessionId, model } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const mdl = String(model || '').trim();
  if (!mdl) return { ok: false, error: 'model required' };
  if (!isSafeModelId(mdl)) return { ok: false, error: 'geçersiz model adı' };
  rememberDesiredModel(s, mdl);
  if (isRunning(s)) {
    s._pendingRespawn = true;
    persistSessions();
    pushSnapshot(s);
    return { ok: true, model: s.model, applied: 'deferred', note: 'tur bitiminde uygulanır' };
  }
  // Idle: kalıcı süreç yaşıyorsa hemen kapat ki bir sonraki prompt yeni model ile doğsun.
  killPersistentChild(s);
  persistSessions();
  pushSnapshot(s);
  return { ok: true, model: s.model };
}

export function getThought(sessionId, i) {
  const s = resolveSession(sessionId);
  if (!s) return { text: '' };
  const cur = s.toolDetails[i] || '';
  if (cur) return { text: cur };
  backfillThinkingFromTranscript(s);
  return { text: s.toolDetails[i] || '' };
}

// Claude CLI stream-json çıktısında thinking İÇERİĞİNİ basmaz ("thinking":"" +
// signature; 2.1.202-2.1.214 hepsinde, --include-partial-messages dahil) — tam
// metin yalnız transcript jsonl'ine yazılır. Bu yüzden child'lı oturumlarda
// thinking detayları bellekte boş kalır; ilk /thought isteğinde transcript'ten
// doldurulur (izleyici oturumlar zaten parse'la dolu gelir, no-op).
function backfillThinkingFromTranscript(s) {
  const found = findTranscriptFile(s.id);
  if (!found) return;
  let st = null;
  try { st = fs.statSync(found.file); } catch { return; }
  // Aynı dosya hali için parse tekrarı yapma; turlar sürerken mtime zaten ilerler.
  if (s._thinkingBackfillMtime === st.mtimeMs) return;
  s._thinkingBackfillMtime = st.mtimeMs;
  let parsed;
  try { parsed = parseTranscript(found.file); } catch { return; }
  backfillThinkingDetails(s, parsed);
}

// Hizalama thinking satırlarının KENDİ sıralaması üzerinden ve SONDAN yapılır:
// capMessages en eski satırları kırptığı için baştan sayım kayar, en yeni turlar
// (kullanıcının açacağı kartlar) ise iki tarafta da aynı kuyruktadır. Yalnız boş
// detaylar yazılır; dolu olan (ör. araç sonucu eklenmiş) hiç ellenmez.
export function backfillThinkingDetails(s, parsed) {
  const isThinkingRow = m => m && m.role === 'thought' && m.thoughtIndex >= 0 && !String(m.text || '').trim();
  const live = (s.messages || []).filter(isThinkingRow);
  const disk = (parsed && Array.isArray(parsed.messages) ? parsed.messages : []).filter(isThinkingRow);
  const diskDetails = (parsed && parsed.toolDetails) || [];
  let filled = 0;
  for (let k = 1; k <= live.length && k <= disk.length; k++) {
    const row = live[live.length - k];
    const text = String(diskDetails[disk[disk.length - k].thoughtIndex] || '');
    if (text && !(s.toolDetails[row.thoughtIndex] || '')) {
      // Tek detay sınırı: stream detaylarıyla aynı üst sınır (dev thinking blokları
      // toolDetails bütçesini tek kalemde yutmasın).
      s.toolDetails[row.thoughtIndex] = text.slice(0, 64_000);
      filled++;
    }
  }
  return filled;
}

export function stop(sessionId) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'not found' };
  // Onay bekliyorsa reddederek serbest bırak; aksi halde süreci öldür.
  if (s.pendingApproval) { approve({ sessionId, allow: false }); }
  killPersistentChild(s);
  if (s.status === 'running') finalizeTurnIdle(s);
  return { ok: true };
}

export function subscribe(sessionId, ws, opts = {}) {
  const s = resolveSession(sessionId);
  // Bilinmeyen oturum: sessizce dönmek soketi sonsuza dek asılı bırakıyordu
  // (istemci ne veri ne hata görüyor). Öteki dört backend'in davranışı — hata
  // gönder ve kapat — sessionCore'da, oraya devret.
  if (!s) { sessionCore.subscribe(s, ws, opts); return; }
  // İzleyici oturum (child'sız): WS'in ilk snapshot'ı bayat kalmasın — codex-app'in
  // subscribe içindeki hydrateFromThread çağrısının claude-app karşılığı.
  if (s) {
    refreshViewerFromDisk(s);
    
    const sizeBefore = s.subscribers ? s.subscribers.size : 0;
    sessionCore.subscribe(s, ws, opts);
    const sizeAfter = s.subscribers ? s.subscribers.size : 0;

    if (sizeBefore === 0 && sizeAfter > 0) {
      ensureWatcher(s);
    }

    const onWsCleanup = () => {
      if (!s.subscribers || s.subscribers.size === 0) {
        cleanupWatcher(s);
      }
    };
    ws.on('close', onWsCleanup);
    ws.on('error', onWsCleanup);
  }
}

export function __getWatcher(sessionId) {
  const s = resolveSession(sessionId);
  return s ? (s._watcher || null) : null;
}

export function __setSessionDiskFile(sessionId, file) {
  const s = resolveSession(sessionId);
  if (s) {
    s._diskFile = file;
    s._diskOffset = 0;
    try { s._diskMtime = fs.statSync(file).mtimeMs; } catch {}
  }
}

// Canli oturumun basligi. ESKIDEN bellekteki ilk user mesajindan turetiliyordu;
// varsayim "adopt gecmisi yuklu oldugundan disk basligiyla ortusur" idi. Ama
// capMessages tampondan eskiyi ATTIGI icin uzun oturumda "ilk mesaj" kayiyor:
// baslik once ortadaki rastgele bir mesaja ("ttft ne"), sonra bir
// <task-notification> satirina donusuyordu — canli goruldu.
//
// Kalici kaynak transcript'in kendisi. Cekmecedeki disk listesiyle AYNI kural
// (ai-title, yoksa ilk gercek kullanici mesaji) ve AYNI memo kullanilir: hem
// sekme basligi ile oturum listesi birbirini tutar, hem de dosya mtime
// degismedikce yeniden ayristirma yapilmaz.
function liveSessionTitle(s) {
  const f = s._diskFile;
  if (f) {
    try {
      const rec = diskSessionRecord(f, fs.statSync(f).mtimeMs);
      if (rec && rec.title) return rec.title;
    } catch { /* transcript henuz yazilmamis olabilir: bellekten turet */ }
  }
  // Yedek yol: transcript yoksa bellekten, ama gurultu satirlarini atlayarak.
  const mem = s.messages.find(m => m.role === 'user' && !isNoiseUserRecord(null, m.text));
  return (mem?.text || '').replace(/\s+/g, ' ').slice(0, 80);
}

export function listSessions() {
  return [...sessions.values()].map(s => ({
    id: s.id, cwd: s.cwd, model: s.model, status: s.status,
    // lastUserAt proje detayi siralamasi icin.
    title: liveSessionTitle(s),
    lastUserAt: s.lastUserAt || 0,
    turns: s.messages.filter(m => m.role === 'user').length,
    lastText: (s.messages[s.messages.length - 1]?.text || '').slice(0, 80),
    awaitingApproval: !!s.pendingApproval,
    restoredShell: !!s._restoredShell,
  }));
}
export function listLiveProcesses() {
  const out = [];
  for (const s of sessions.values()) {
    if (s.child && !s.child.exitCode) out.push({ pid: s.child.pid, kind: 'claude', sessionId: s.id });
  }
  return out;
}

// Bosta kalan kalici surecleri kapatir; oturum kaydi ve sohbet KALIR (sonraki
// prompt ensureProc --resume ile ayni sohbeti geri acar — setModel'in idle
// yoluyla birebir ayni). Secim kurallari idle-reaper.mjs'te; burada yalniz
// oturumun duz kayda cevrilmesi ve killPersistentChild'a baglanmasi var.
// Zaman olcutu: son tur etkinligi (watchdog'un tazeledigi _lastActivity) ile
// son kullanici promptunun buyugu.
export function reapIdleChildren({ maxIdleMs, now = Date.now() } = {}) {
  const byId = new Map();
  const flat = [];
  for (const s of sessions.values()) {
    byId.set(s.id, s);
    flat.push({
      id: s.id,
      hasChild: !!(s.child && s.child.exitCode == null),
      status: s.status,
      pendingApproval: !!s.pendingApproval,
      lastActivity: Math.max(s._lastActivity || 0, s.lastUserAt || 0),
    });
  }
  const r = reapIdle({
    sessions: flat, maxIdleMs, now,
    kill: rec => {
      const s = byId.get(rec.id);
      if (!s) return { ok: false, error: 'session gone' };
      killPersistentChild(s);
      s._childReapedAt = now;
      return { ok: true };
    },
    log: msg => logWarn('claude-app', msg),
  });
  if (r.killed.length) persistSessions();
  return r;
}

// Tum claude sureclerini oldurur VE bellekteki oturum kayitlarini temizler
// (diger backendlerle ayni sozlesme). Transcript'ler diskte (projects/*.jsonl)
// durdugu icin gecmis kaybolmaz; drawer listDiskSessions'tan dolmaya devam eder.
export function killAllSessions() {
  const killed = [];
  const errors = [];
  for (const s of sessions.values()) {
    if (s.pendingApproval) { try { approve({ sessionId: s.id, allow: false }); } catch {} }
    if (s.child && !s.child.exitCode) {
      // Not: killPersistentChild kill hatasını yutar; burada pid/hata raporu
      // korunsun diye elle öldürüp imleci ayrıca sabitliyoruz (oturum yaşamaya
      // devam ediyor → sonraki prompt --resume ile doğacak).
      try { killChildTree(s.child.pid); killed.push(s.child.pid); }
      catch (e) { errors.push({ pid: s.child.pid, error: String(e?.message || e) }); }
      s.child = null;
      seedDiskCursorToEof(s);
    }
    if (s._watchdog) { clearInterval(s._watchdog); s._watchdog = null; }
    if (s.status === 'running') finalizeTurnIdle(s);
  }
  const cleared = sessions.size;
  sessions.clear();
  persistSessions(); // bos listeyi yaz — restart'ta kayitlar geri gelmesin
  return { ok: errors.length === 0, killed, cleared, errors };
}

// ── Disk oturumları (adopt + persistence) ──────────────────────────────────
// Disk scan cache — tek cache (ortak havuz).
const DISK_CACHE_MS = 5000;
let _diskCache = { at: 0, dirMtime: 0, files: [], idIndex: new Map(), list: null };

function dirSignature(dir) {
  try { return fs.statSync(dir).mtimeMs || 0; } catch { return 0; }
}

function ensureDiskCacheFresh() {
  const projectsDir = CLAUDE_PROJECTS_DIR;
  try { fs.mkdirSync(projectsDir, { recursive: true }); } catch {}
  const now = Date.now();
  const sig = dirSignature(projectsDir);
  const cached = _diskCache;
  if (cached && cached.list && (now - cached.at) < DISK_CACHE_MS && sig === cached.dirMtime) return cached;
  const files = [];
  let projectDirs;
  try { projectDirs = fs.readdirSync(projectsDir, { withFileTypes: true }); } catch { projectDirs = []; }
  for (const pd of projectDirs) {
    if (!pd.isDirectory()) continue;
    const dir = path.join(projectsDir, pd.name);
    let names;
    try { names = fs.readdirSync(dir).filter(f => f.endsWith('.jsonl')); } catch { continue; }
    for (const f of names) {
      const full = path.join(dir, f);
      let st; try { st = fs.statSync(full); } catch { continue; }
      files.push({ f: full, mtime: st.mtimeMs });
    }
  }
  files.sort((a, b) => b.mtime - a.mtime);
  const idIndex = new Map();
  for (const { f } of files) {
    const id = path.basename(f, '.jsonl');
    if (!idIndex.has(id)) idIndex.set(id, f);
  }
  const fresh = { at: now, dirMtime: sig, files, idIndex, list: null };
  _diskCache = fresh;
  return fresh;
}

function invalidateDiskCache() {
  _diskCache = { at: 0, dirMtime: 0, files: [], idIndex: new Map(), list: null };
}

// cwd COWORK_ROOT altında mı? Cowork oturumları (workspace'ler) claude-app disk
// listesinden dışlanır; claude-app oturumları da cowork listesinden dışlanır.
function isCoworkCwd(cwd) {
  if (!cwd) return false;
  const rel = path.relative(COWORK_ROOT, path.resolve(cwd));
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

const _parseMemo = new Map();
// Memo diske de yazılır ki bridge restart'ında soğuk tarama (Windows Defender nedeniyle
// ~7s süren senkron blok) tekrarlanmasın: açılışta memo dosyadan yüklenir, yalnızca
// mtime'ı değişen transcript'ler yeniden parse edilir (tipik açılışta 0-2 dosya).
// v2: parseTranscriptHead görselli ilk mesajlarda da başlık çıkarıyor; eski memo
// boş başlıklı kayıtları mtime değişmeden sonsuza dek taşırdı — sürümle tazele.
// v3: rec.mtime artık içerikteki son mesaj zamanı (lastTs) — v2 memo'daki ham
// dosya-mtime değerleri dosya değişmeden asla düzelmezdi, sürümle tazele.
// v4: rec'e rootKey eklendi (çatal gruplama); eski memo kayıtlarında bu alan
// yok, dosya değişmeden asla dolmazdı — sürümle tazele.
const DISK_MEMO_FILE = path.join(os.homedir(), '.claude', 'bridge-disk-memo-v4.json');
let _memoSaveTimer = null;

function loadParseMemoFromDisk() {
  try {
    const data = JSON.parse(fs.readFileSync(DISK_MEMO_FILE, 'utf8'));
    if (data && typeof data === 'object') {
      for (const [f, v] of Object.entries(data)) {
        if (v && typeof v.mtime === 'number') _parseMemo.set(f, { mtime: v.mtime, rec: v.rec || null });
      }
    }
  } catch { /* yok veya bozuk → soğuk tarama normal yolunda */ }
}
loadParseMemoFromDisk();

function saveParseMemoSoon() {
  if (_memoSaveTimer) return;
  _memoSaveTimer = setTimeout(() => {
    _memoSaveTimer = null;
    try {
      fs.writeFileSync(DISK_MEMO_FILE, JSON.stringify(Object.fromEntries(_parseMemo)), 'utf8');
    } catch (e) { logWarn('claude-app', 'disk memo yazılamadı', { error: e.message }); }
  }, 2000);
  if (_memoSaveTimer.unref) _memoSaveTimer.unref();
}

function diskSessionRecord(f, mtime) {
  const hit = _parseMemo.get(f);
  if (hit && hit.mtime === mtime) return hit.rec;
  const parsed = parseTranscriptHead(f);
  const rec = !parsed.turns ? null : {
    id: path.basename(f, '.jsonl'),
    cwd: parsed.cwd || '',
    title: (parsed.aiTitle || parsed.firstUser || '').replace(/\s+/g, ' ').slice(0, 80),
    lastText: (parsed.lastText || '').replace(/\s+/g, ' ').slice(0, 100),
    turns: parsed.turns,
    // Sıralama anahtarı içerikteki son gerçek mesajın zamanı: CLI eski
    // transcript'lere zaman damgasız bakım satırları ekleyip dosya mtime'ını
    // ilerletiyor; ham mtime kullanmak eski oturumları listede yukarı zıplatır.
    // lastTs çıkmazsa (çok eski/bozuk kayıt) dosya mtime'ına düşülür.
    mtime: parsed.lastTs || mtime,
    // Çatal kökü: aynı sohbetin resume kopyalarında ilk kullanıcı mesajı ve
    // zamanı değişmez. Yalnız ikisi birlikte anahtar olur — tek başına metin
    // ("devam et" gibi) farklı sohbetleri yanlışlıkla birleştirirdi.
    rootKey: parsed.firstUser && parsed.firstTs
      ? `${parsed.firstTs}|${parsed.firstUser.replace(/\s+/g, ' ').trim().slice(0, 120)}`
      : '',
  };
  _parseMemo.set(f, { mtime, rec });
  saveParseMemoSoon();
  return rec;
}

// Aynı sohbetin çatallarını tek satırda toplar. CLI bir oturumu devam ettirirken
// geçmişin TAMAMINI yeni uuid'li bir dosyaya kopyalar; her kesinti/hata/resume bir
// dosya daha doğurur ve liste aynı sohbeti aynı başlıkla N kez gösterirdi (canlı
// görüldü: tek sohbet 4 satır). En güncel kopya temsilci olur, eskiler forkIds'e
// düşer — dosyalar diskte durur, yalnız liste sadeleşir.
//
// Canlı oturum (çalışan çocuğu olan kayıt) GRUBUN TEMSİLCİSİ olur — grubun
// DIŞINDA bırakılmaz. Eski hali onu grup dışına atıyordu ve sohbet listede iki
// kez görünüyordu: bir kez kendi satırı, bir kez "N kopya" grubu (canlı görüldü:
// bir oturumdan geri çıkınca çiftleniyordu). claude-app kalıcı çocuk tuttuğu
// için "idle" bir oturumun da çocuğu olabilir; ölçüt budur.
function collapseForks(list) {
  const byRoot = new Map();
  const out = [];
  const canliMi = (id) => !!sessions.get(id)?.child;
  for (const rec of list) { // mtime'a göre azalan geldiği için ilk görülen en günceli
    if (!rec.rootKey) { out.push(rec); continue; }
    const head = byRoot.get(rec.rootKey);
    if (!head) {
      // Kopya üzerinde çalış: rec nesnesi _parseMemo'da tutuluyor, üstüne sayaç
      // yazarsak memo kirlenir ve diske de öyle yazılır.
      const copy = { ...rec, forks: 1, forkIds: [], canli: canliMi(rec.id) };
      byRoot.set(rec.rootKey, copy);
      out.push(copy);
      continue;
    }
    head.forks++;
    // Canlı kayıt gruba sonradan geldiyse temsilciliği ONA devret: kullanıcı o an
    // o oturumda yazışıyor, listede görmesi ve dokunabilmesi gereken satır o.
    // Eski temsilci forkIds'e düşer; sayaç ve kimlik listesi korunur.
    if (canliMi(rec.id) && !head.canli) {
      head.forkIds.push(head.id);
      const sayac = head.forks;
      const kimlikler = head.forkIds;
      Object.assign(head, rec, { forks: sayac, forkIds: kimlikler, canli: true });
    } else {
      head.forkIds.push(rec.id);
    }
  }
  return out;
}

// Ham disk oturum listesini hesaplar + cache'ler.
function computeDiskSessions() {
  const cache = ensureDiskCacheFresh();
  if (cache.list) return cache.list;
  const result = [];
  const live = new Set();
  for (const { f, mtime } of cache.files.slice(0, 120)) {
    live.add(f);
    const rec = diskSessionRecord(f, mtime);
    if (rec) result.push(rec);
  }
  // Cowork açlığını önle: en yeni 120 dosyanın hepsi normal oturumsa cowork listesi
  // boş kalırdı. İlk dilimde az cowork kaydı varsa daha eski dosyaları yalnız cowork
  // olanları toplayarak tara (diskSessionRecord memoize'lı — maliyet sınırlı).
  let coworkCount = result.filter(s => isCoworkCwd(s.cwd)).length;
  if (coworkCount < 20) {
    for (const { f, mtime } of cache.files.slice(120, 400)) {
      live.add(f);
      const rec = diskSessionRecord(f, mtime);
      if (rec && isCoworkCwd(rec.cwd)) {
        result.push(rec);
        if (++coworkCount >= 20) break;
      }
    }
  }
  // Memo sınırsız büyümesin: listeden düşen dosyaların kaydını at
  if (_parseMemo.size > 400) {
    for (const k of _parseMemo.keys()) {
      if (!live.has(k)) _parseMemo.delete(k);
    }
  }
  result.sort((a, b) => b.mtime - a.mtime);
  // Gruplanmış listeyi hem cache'e yaz hem DÖNDÜR: eskiden `return result` idi,
  // yani ilk çağrı ham listeyi, sonraki çağrılar cache'teki gruplanmışı verirdi.
  const list = collapseForks(result);
  cache.list = list;
  cache.at = Date.now(); // tarama BİTİŞ zamanı — pencere gerçek kullanım için işlesin
  return list;
}

// Soğuk taramayı (ilk çağrı ~7s) telefon isteğine yedirmemek için server açılışında
// bir kez arka planda ısıt.
export function warmDiskSessionsCache(delayMs = 1500) {
  const t = setTimeout(() => {
    try { computeDiskSessions(); } catch {}
  }, delayMs);
  if (typeof t.unref === 'function') t.unref(); // testlerde süreci canlı tutmasın
  return t;
}

// claude-app oturumları: ortak havuzdan (projects dizini junction ile bağlıdır), cowork workspace'leri hariç.
// Telefon aktif hesap için listeyi çeker — ortak havuz sayesinde her profilin oturum listesi
// masaüstü uygulamasının o profildeki oturum listesiyle birebir aynıdır.
export function listDiskSessions({ all: includeAll = false } = {}) {
  const all = computeDiskSessions();
  const sessions = all.filter(s => !isCoworkCwd(s.cwd));
  return { ok: true, sessions: includeAll ? sessions : sessions.slice(0, 100) };
}

// Budama için TAM ve GRUPLANMIŞ liste (bkz. session-prune.mjs). `listDiskSessions`
// budama için YANLIŞ kaynak, iki ayrı sebeple:
//
// 1. O yol yalnız en yeni 120 dosyayı tarar — çekmece için doğru, budama için
//    değil: budanacak olanlar tam da o dilimin dışında kalanlar.
// 2. Aynı sohbetin çatal kopyaları AYRI dosyalar (CLI her resume'da geçmişin
//    tamamını yeni uuid'li bir dosyaya kopyalıyor). Dosya dosya bakılırsa CANLI
//    bir sohbetin eski kopyaları "bir haftadır sessiz" görünür ve silinir.
//    Ölçüt grubun EN YENİ kopyası: grup tazeyse hiçbir üyesi silinmez.
//
// Cowork muafiyeti grubun HERHANGİ bir üyesinden gelebilir; yanlış yön bilerek
// "fazla saklamak" tarafına eğiliyor.
export function listAllDiskSessionsForPrune() {
  const cache = ensureDiskCacheFresh();
  const meta = readBridgeMeta();
  const gruplar = new Map();
  for (const { f, mtime } of cache.files) {
    const rec = diskSessionRecord(f, mtime);
    if (!rec) continue;
    const key = rec.rootKey || rec.id;
    let g = gruplar.get(key);
    if (!g) {
      g = { id: rec.id, ids: [], cwd: rec.cwd, mtime: 0, pinned: false, cowork: false };
      gruplar.set(key, g);
    }
    g.ids.push(rec.id);
    if (rec.mtime > g.mtime) { g.mtime = rec.mtime; g.id = rec.id; g.cwd = rec.cwd || g.cwd; }
    if (isCoworkCwd(rec.cwd)) g.cowork = true;
    if (meta[rec.id]?.pinned) g.pinned = true;
  }
  return [...gruplar.values()];
}

// Oturumu app'ten VE diskten tamamen sil: canlı child'ı kapat, bellek kopyasını
// düşür, transcript .jsonl dosyasını kaldır, pin/archive metadata'sını temizle.
// Geri alınamaz. Dosya bulunamasa bile bellek/metadata temizlenir (ok döner).
export function deleteDiskSession({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  if (!isSafeSessionId(id)) return { ok: false, error: 'geçersiz oturum kimliği' };
  const s = sessions.get(id);
  // Transcript dosyasını bul: canlı oturum _diskFile taşır; yoksa hesap indexinden,
  // son çare tüm hesap havuzlarını tara.
  let file = s && s._diskFile;
  if (!file || !fs.existsSync(file)) {
    invalidateDiskCache();
    file = ensureDiskCacheFresh().idIndex.get(id) || findTranscriptFile(id)?.file || null;
  }
  // Canlı oturumu kapat ve bellekten düş.
  if (s) {
    try { killPersistentChild(s); } catch {}
    try { cleanupWatcher(s); } catch {}
    clearArchivedMessages(s, { dir: path.join(os.homedir(), '.claude', 'bridge-message-archive') });
    sessions.delete(id);
    persistSessions();
  }
  // Diskten kaldır.
  if (file && fs.existsSync(file)) {
    try { fs.unlinkSync(file); }
    catch (e) { return { ok: false, error: 'transcript silinemedi: ' + (e.message || String(e)) }; }
  }
  // pin/archive metadata'sını temizle.
  const meta = readBridgeMeta();
  if (meta[id]) { delete meta[id]; writeBridgeMeta(meta); }
  invalidateDiskCache();
  return { ok: true, id };
}

// ── PC'de devam et ──────────────────────────────────────────────────────────
// Oturumu PC'de görünür bir terminal penceresinde `claude --resume <id>` ile açar.
// Masaüstü Claude (Electron) uygulaması oturum listesini kendi deposundan/sunucudan
// okur, <CLAUDE_CONFIG_DIR>/projects jsonl havuzunu TARAMAZ — köprü oturumları orada
// hiçbir zaman görünmez. PC'de sürdürmenin desteklenen tek yolu aynı config dizini +
// cwd ile terminalde resume'dur; bu uç o terminali telefondan tek dokunuşla açtırır.
// Çift-yazar riski: köprünün kalıcı child'ı (idle'da bile yaşayabilir) kapatılır;
// tur sürüyorsa istek reddedilir. Köprü kaydı izleyici olarak kalır — jsonl watcher'ı
// terminalde yazılanları telefona da akıtmaya devam eder.
export function openOnPc({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  if (!isSafeSessionId(id)) return { ok: false, error: 'geçersiz oturum kimliği' };
  if (process.platform !== 'win32') return { ok: false, error: 'yalnız Windows destekleniyor' };
  const s = sessions.get(id);
  if (s && isRunning(s)) return { ok: false, error: 'tur sürüyor; önce durdurun' };

  // cwd + hesap: canlı kayıttan; yoksa hesap havuzlarından transcript bulunur.
  let cwd = (s && s.cwd) || '';
  if (!cwd || !fs.existsSync(cwd)) {
    invalidateDiskCache();
    let file = ensureDiskCacheFresh().idIndex.get(id) || null;
    if (!file) {
      const fallback = findTranscriptFile(id);
      if (fallback) file = fallback.file;
    }
    if (!file) return { ok: false, error: 'transcript not found for ' + id };
    cwd = parseTranscriptHead(file).cwd || '';
  }
  if (!cwd || !fs.existsSync(cwd)) return { ok: false, error: 'cwd not found: ' + cwd };

  // Aynı jsonl'e iki süreç yazmasın: köprünün kalıcı child'ı varsa kapat.
  if (s) killPersistentChild(s);

  const bin = resolveExecutableSync('claude', 'AGENTBRIDGE_CLAUDE_BIN');
  // `cmd /c start` yeni bir pencere açıp hemen döner; pencere içinde `cmd /k`
  // claude'u çalıştırır ve claude çıksa da pencere açık kalır. "Claude Resume"
  // start'ın başlık argümanıdır (tırnaklı ilk arg başlık sayılır; binary yolu
  // tırnaklanınca başlık sanılmasın diye şart).
  try {
    const child = spawn(process.env.ComSpec || 'cmd.exe',
      ['/d', '/c', 'start', 'Claude Resume', 'cmd', '/k', bin, '--resume', id],
      {
        cwd,
        env: { ...process.env, CLAUDE_CONFIG_DIR },
        detached: true, stdio: 'ignore', windowsHide: false,
      });
    child.unref();
  } catch (e) {
    return { ok: false, error: 'terminal açılamadı: ' + (e.message || String(e)) };
  }
  return { ok: true, id, cwd };
}

const BRIDGE_META_FILE = path.join(os.homedir(), '.claude', 'bridge-claude-app-meta.json');

function readBridgeMeta() {
  try {
    if (fs.existsSync(BRIDGE_META_FILE)) {
      return JSON.parse(fs.readFileSync(BRIDGE_META_FILE, 'utf-8'));
    }
  } catch {}
  return {};
}

function writeBridgeMeta(data) {
  try {
    fs.mkdirSync(path.dirname(BRIDGE_META_FILE), { recursive: true });
    fs.writeFileSync(BRIDGE_META_FILE, JSON.stringify(data, null, 2), 'utf-8');
    return true;
  } catch {
    return false;
  }
}

export function pinThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readBridgeMeta();
  if (!meta[id]) meta[id] = {};
  meta[id].pinned = true;
  writeBridgeMeta(meta);
  return { ok: true, pinned: true };
}

export function unpinThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readBridgeMeta();
  if (meta[id]) {
    meta[id].pinned = false;
    writeBridgeMeta(meta);
  }
  return { ok: true, pinned: false };
}

// Kullanıcının verdiği başlık meta'da tutulur; transcript'ten türetilen başlığın
// (aiTitle/firstUser) önüne geçer. Boş başlık = özel başlığı kaldır (türetilene dön).
export function renameThread({ id, title } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readBridgeMeta();
  if (!meta[id]) meta[id] = {};
  const clean = String(title || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  if (clean) meta[id].title = clean;
  else delete meta[id].title;
  writeBridgeMeta(meta);
  return { ok: true, title: clean };
}

export function archiveThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readBridgeMeta();
  if (!meta[id]) meta[id] = {};
  meta[id].archived = true;
  writeBridgeMeta(meta);
  return { ok: true, archived: true };
}

export function unarchiveThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readBridgeMeta();
  if (meta[id]) {
    meta[id].archived = false;
    writeBridgeMeta(meta);
  }
  return { ok: true, archived: false };
}

export async function listDiskSessionsWithQuery({ query = '', archived = null, pinned = null } = {}) {
  const base = listDiskSessions();
  if (!base.ok) return base;

  let diskSessions = base.sessions;
  const meta = readBridgeMeta();

  // Annotate each session with archived/pinned status + kullanıcı başlığı (varsa).
  diskSessions = diskSessions.map(s => ({
    ...s,
    title: meta[s.id]?.title || s.title,
    archived: !!(meta[s.id]?.archived),
    pinned: !!(meta[s.id]?.pinned),
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

// Cowork oturumları: yalnızca COWORK_ROOT altındaki workspace'lerde açılmış olanlar.
// Cowork hesap-bağımsız çalışır → ortak havuz sayesinde tek havuz üzerinden taranması yeterlidir.
export function listCoworkDiskSessions({ all: includeAll = false } = {}) {
  const all = computeDiskSessions();
  const sessions = all.filter(s => isCoworkCwd(s.cwd));
  return { ok: true, sessions: includeAll ? sessions : sessions.slice(0, 100) };
}

export function adoptSession({ id, cwd, cowork = false }) {
  if (!id) return { ok: false, error: 'id required' };
  if (!isSafeSessionId(id)) return { ok: false, error: 'geçersiz oturum kimliği' };
  if (sessions.has(id)) {
    const s = sessions.get(id);
    if (cowork) {
      s.cowork = true;
      s.lastOutputs = s.lastOutputs || [];
      try { fs.mkdirSync(path.join(s.cwd, 'outputs'), { recursive: true }); } catch {}
    }
    refreshViewerFromDisk(s);  // donmuş izleyici kopyasını diskten tazele
    persistSessions();
    return { ok: true, sessionId: id, cwd: s.cwd, model: s.model };
  }
  invalidateDiskCache();

  let file = ensureDiskCacheFresh().idIndex.get(id) || null, foundCwd = cwd || '';
  if (!file) {
    // missing junction/unmigrated fallback
    const fallback = findTranscriptFile(id);
    if (fallback) {
      file = fallback.file;
    }
  }
  if (!file) return { ok: false, error: 'transcript not found for ' + id };

  const parsed = parseTranscript(file);
  foundCwd = parsed.cwd || foundCwd;
  if (!foundCwd || !fs.existsSync(foundCwd)) return { ok: false, error: 'cwd not found: ' + foundCwd };
  const pm = String(parsed.model || '').toLowerCase();
  const mdl = pm.includes('opus') ? 'claude-opus-5-5'   // alias değil spesifik id (picker etiketiyle aynı)
    : pm.includes('haiku') ? 'haiku'
    : pm.includes('fable') ? 'claude-fable-5-1'   // Fable oturumu sonnet'e düşmesin
    : 'sonnet';

  sessions.set(id, {
    id, cwd: foundCwd, model: mdl, status: 'idle',
    messages: parsed.messages, toolDetails: parsed.toolDetails,
    contextTokens: parsed.contextTokens,
    lastCost: 0, lastUsage: null,
    permissionMode: '',
    effort: '',
    cowork: !!cowork, lastOutputs: [],
    init: null,
    resumeFromDisk: true,   // diskte zaten var → --session-id değil --resume kullan
    child: null, subscribers: new Set(),
    stdoutBuf: '',
    _turnHadAgent: false, _watchdog: null, _lastActivity: 0, _gotFirstOutput: false,
    _toolUseIndex: new Map(),
    _diskFile: file,
    _diskOffset: (() => { try { return fs.statSync(file).size; } catch { return 0; } })(),
    _diskLineBuf: '',
    _diskMtime: (() => { try { return fs.statSync(file).mtimeMs; } catch { return 0; } })(),
    pendingApproval: null,
  });
  if (cowork) {
    try { fs.mkdirSync(path.join(foundCwd, 'outputs'), { recursive: true }); } catch {}
  }
  // Uzun transcript'lerde mesaj tavanını uygula (MAX_MESSAGES). Bu olmadan adopt
  // edilen 1000+ mesajlık oturumun TAMAMI bellekte kalıyor ve ws snapshot'ı tek
  // karede telefona gidiyordu; kırpılanlar arşive düşer, sayfalamayla okunur.
  // Arşiv önce sıfırlanır: tam transcript yeniden parse edildiği için eski arşiv
  // satırları aynı mesajların kopyası olurdu (mükerrer sayfalama).
  clearArchivedMessages(sessions.get(id), { dir: path.join(os.homedir(), '.claude', 'bridge-message-archive') });
  capSess(sessions.get(id));
  persistSessions();
  return { ok: true, sessionId: id, cwd: foundCwd, model: mdl };
}

// ── Rewind (mesaja geri dön) ────────────────────────────────────────────────
// Claude CLI'nin geri sarma API'si yok; transcript-fork ile gerçeklenir:
// jsonl, SONDAN dropUserTurns'uncu kullanıcı satırının ÖNCESİNE kadar yeni bir
// uuid'li dosyaya kopyalanır (satır içi sessionId alanları yeni id'ye yazılır),
// sonra adoptSession o dosyayı --resume ile sürdürülebilir oturum olarak kaydeder.
// Sondan sayım kasıtlı: telefonun mesaj listesi cap'lenmiş olabilir; hedef→son
// aralığındaki kullanıcı-mesajı sayısı her iki görünümde de aynıdır.
// Eski oturum ve dosyası DOKUNULMADAN kalır (geri alma güvencesi); phone yeni
// sessionId'ye geçer.
export function rewindSession({ sessionId, dropUserTurns } = {}) {
  return transcriptForkAt({ sessionId, dropUserTurns, keepOriginal: false });
}

// ── Fork (buradan çatalla) ──────────────────────────────────────────────────
// rewind ile aynı transcript-fork; tek fark orijinal oturuma DOKUNULMAZ
// (child'ı, kaydı ve dosyası aynen kalır). Yeni sessionId telefonda yeni
// sekmede açılır; iki dal bağımsız sürer.
export function forkFromMessage({ sessionId, dropUserTurns } = {}) {
  return transcriptForkAt({ sessionId, dropUserTurns, keepOriginal: true });
}

function transcriptForkAt({ sessionId, dropUserTurns, keepOriginal }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const drop = Math.floor(Number(dropUserTurns));
  if (!Number.isFinite(drop) || drop < 1) return { ok: false, error: 'dropUserTurns >= 1 olmalı' };
  if (isRunning(s)) return { ok: false, error: keepOriginal ? 'tur sürerken çatallanamaz' : 'tur sürerken geri dönülemez' };

  // Oturumun transcript dosyasını bul (canlı oturumlar _diskFile taşır).
  let file = s._diskFile;
  if (!file || !fs.existsSync(file)) {
    invalidateDiskCache();
    file = ensureDiskCacheFresh().idIndex.get(s.id) || null;
    if (!file) file = findTranscriptFile(s.id)?.file || null;
  }
  if (!file || !fs.existsSync(file)) return { ok: false, error: 'transcript bulunamadı: ' + s.id };

  let raw;
  try { raw = fs.readFileSync(file, 'utf-8'); } catch (e) { return { ok: false, error: 'transcript okunamadı: ' + e.message }; }
  const lines = raw.split('\n');
  // parseTranscript ile AYNI kuralla kullanıcı satırlarını topla
  // (type:'user' + metin içerikli; tool_result satırları metinsiz → sayılmaz).
  const userLineIdx = [];
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (!t) continue;
    let r; try { r = JSON.parse(t); } catch { continue; }
    if (r.type === 'user' && r.message && textFromContent(r.message.content)) userLineIdx.push(i);
  }
  if (userLineIdx.length < drop) return { ok: false, error: 'geri dönülecek mesaj bulunamadı (' + userLineIdx.length + ' < ' + drop + ')' };
  const cutLine = userLineIdx[userLineIdx.length - drop];

  const newId = randomUUID();
  const rewritten = [];
  for (let i = 0; i < cutLine; i++) {
    const t = lines[i].trim();
    if (!t) continue;
    try { const r = JSON.parse(t); if (r.sessionId) r.sessionId = newId; rewritten.push(JSON.stringify(r)); }
    catch { rewritten.push(lines[i]); }
  }
  if (!rewritten.length) return { ok: false, error: 'kesme noktasından önce içerik yok' };
  const newFile = path.join(path.dirname(file), newId + '.jsonl');
  try { fs.writeFileSync(newFile, rewritten.join('\n') + '\n', 'utf-8'); }
  catch (e) { return { ok: false, error: 'yeni transcript yazılamadı: ' + e.message }; }

  // Rewind'de eski child kapanır ve kayıt düşer; fork'ta orijinal aynen kalır.
  if (!keepOriginal) killPersistentChild(s);
  const r = adoptSession({ id: newId, cwd: s.cwd, cowork: !!s.cowork });
  if (!r.ok) { try { fs.unlinkSync(newFile); } catch {} return r; }
  const ns = sessions.get(newId);
  if (ns) {
    ns.model = s.model;                      // adopt'un transcript tahmini yerine mevcut seçim
    ns.permissionMode = s.permissionMode;
    ns.effort = s.effort;
  }
  if (!keepOriginal) {
    cleanupWatcher(s); // izleyici watcher'ı yetim kalmasın (fs.watch tutamacı sızar)
    sessions.delete(s.id); // izleyici kopyası kalabalık yapmasın; disk dosyası duruyor
  }
  persistSessions();
  invalidateDiskCache();
  return { ok: true, sessionId: newId, cwd: r.cwd, model: ns ? ns.model : r.model };
}

export function runningSessionId() {
  for (const s of sessions.values()) if (s.status === 'running' && s.child != null) return s.id;
  return null;
}

export function getPendingApproval() {
  for (const s of sessions.values()) {
    if (s.pendingApproval) {
      return {
        backend: 'claude-app',
        sessionId: s.id,
        summary: s.pendingApproval.summary || 'İzin gerekiyor',
      };
    }
  }
  return null;
}

function ensureLawSkills() {
  // Test koşusunda profil klasörlerine yazma (node --test, modülü import ederken
  // kullanıcı profillerine dosya yazmamalı — import yan etkisi).
  if (process.env.NODE_TEST_CONTEXT) return;
  const skillName = 'dilekce-taslagi';
  const skillContent = [
    '---',
    'name: dilekce-taslagi',
    'description: Girdi olarak belgeler/ klasöründeki dava belgelerini okur, outputs/ altına UYAP uyumlu dilekçe taslağı (UDF) hazırlar.',
    '---',
    '',
    '# Dilekçe Taslağı Hazırlama',
    '',
    'Dava dosyası çalışma alanlarında (dava-dosyasi şablonu) dilekçe taslağı üretmek için kullanılır.',
    '',
    '## Adımlar',
    '1. **Belgeleri incele**: `belgeler/` altındaki tüm belgeleri oku; `notlar.md` varsa dikkate al.',
    '2. **Hukuki analiz**: olay özeti, uyuşmazlık konuları, talep sonucu. Emsal/mevzuat gerekiyorsa Emsal MCP araçlarıyla (search_decisions, search_local_corpus, mevzuat_korpus_ara, mevzuat_madde_getir) araştır ve dayanakları dilekçeye işle.',
    '3. **Taslağı yaz**: klasik dilekçe düzeninde — mahkeme başlığı, taraflar, konu, açıklamalar, hukuki nedenler, deliller, netice-i talep.',
    '4. **UDF çıktısı**: .udf dosyasını SAKIN elle XML yazarak üretme — gerçek UDF bir ZIP konteyneridir, elle yazılan XML UYAP\'ta açılmaz. Kurulu `udf` skill\'ini kullanarak `outputs/taslak.udf` üret; `udf` skill\'i yoksa `outputs/taslak.docx` yaz ve kullanıcıya UDF\'e çevrilmesi gerektiğini belirt.',
    '',
    '## Kurallar',
    '- `belgeler/` salt okunurdur; hiçbir belgeyi değiştirme.',
    '- Köşeli parantezli boş alan bırakmak yerine belgelerden çıkarabildiğin gerçek bilgileri doldur; bilinmeyenleri sonda listeleyip kullanıcıya sor.',
    '',
  ].join('\n');

  const skillDir = path.join(CLAUDE_CONFIG_DIR, 'skills', skillName);
  const skillFile = path.join(skillDir, 'SKILL.md');
  try {
    // Var olanı EZME: kullanıcı/ajan skilli geliştirdiyse restart emeği silmesin.
    if (fs.existsSync(skillFile)) return;
    fs.mkdirSync(skillDir, { recursive: true });
    fs.writeFileSync(skillFile, skillContent, 'utf8');
  } catch (e) {
    console.warn('[claude-app] Skill oluşturulamadı:', e.message);
  }
}

// ── Arama sozlugu ───────────────────────────────────────────────────────────
// Her aramada transcript'leri diskten okuyup ayristirmak surdurulemez: havuz
// 531 MB (147 dosya, en buyugu 67 MB) ve tek arama 15sn suruyordu — telefon
// "Read timed out" veriyordu (canli olculdu). Oysa aranan sey yalnizca
// kullanici/asistan MESAJ metni ve o, ham boyutun %0,7'si (~4 MB): geri kalani
// arac ciktisi, dusunme, base64 ekran goruntusu. O yuzden dosya basina yalniz
// mesaj metni bir kez cikarilip bellekte tutulur; arama 531 MB yerine 4 MB tarar.
//
// Gecerlilik anahtari dosya mtime'i: transcript degisince o dosyanin girdisi
// yeniden uretilir. Sozluk diske de yazilir, restart'ta sifirdan kurulmaz.
const SEARCH_INDEX_FILE = path.join(os.homedir(), '.claude', 'bridge-search-index-v1.json');
const SEARCH_TEXT_CAP = 100_000; // tek mesaj metni ust siniri (patolojik kayit korumasi)
// file -> { mtime, size, msgs: [{ role, text }] }
// `size`: son TAM satirin bittigi byte offset'i — artimli okumanin baslangici.
let _searchIndex = new Map();
let _searchIndexLoaded = false;
let _searchIndexSaveTimer = null;

function loadSearchIndex() {
  if (_searchIndexLoaded) return;
  _searchIndexLoaded = true;
  try {
    const data = JSON.parse(fs.readFileSync(SEARCH_INDEX_FILE, 'utf8'));
    for (const [f, v] of Object.entries(data || {})) {
      // `size` alani v1 sozlugunde YOKTU; eksikse 0 kabul edilir ve o dosya
      // bir kez tam ayristirilir (sonra artimli yola gecer).
      if (v && typeof v.mtime === 'number' && Array.isArray(v.msgs)) {
        _searchIndex.set(f, { mtime: v.mtime, size: Number.isFinite(v.size) ? v.size : 0, msgs: v.msgs });
      }
    }
  } catch { /* yok veya bozuk → normal yolda yeniden kurulur */ }
}

function saveSearchIndexSoon() {
  if (_searchIndexSaveTimer) return;
  _searchIndexSaveTimer = setTimeout(() => {
    _searchIndexSaveTimer = null;
    try { fs.writeFileSync(SEARCH_INDEX_FILE, JSON.stringify(Object.fromEntries(_searchIndex)), 'utf8'); }
    catch (e) { logWarn('claude-app', 'arama sozlugu yazilamadi', { error: e.message }); }
  }, 5000);
  if (_searchIndexSaveTimer.unref) _searchIndexSaveTimer.unref();
}

// Dosyanin aranabilir mesajlari. Sozlukte guncel kayit varsa diske hic inilmez.
function aramaSatiriEkle(msgs, m) {
  if (m.role !== 'user' && m.role !== 'agent') return;
  const text = String(m.text || '');
  if (!text) return;
  msgs.push({ role: m.role, text: text.length > SEARCH_TEXT_CAP ? text.slice(0, SEARCH_TEXT_CAP) : text });
}

/**
 * Transcript'in aranabilir metin dokumu — ARTIMLI.
 *
 * Eskiden gecerlilik anahtari yalniz mtime'di ve dosya her degistiginde
 * TAMAMI yeniden ayristiriliyordu. Canli oturumun transcript'i her tur
 * buyudugu icin bu, "her mesajdan sonraki ilk arama" demekti. 18.08.2026'da
 * olculdu: bu makinede acik oturumun jsonl'i 348 MB; arama ucu ayni sorgu icin
 * dosya degistiginde 49,6 sn, degismediginde 7,5 sn suruyordu (ayni surecte
 * arka arkaya, 8/64 isabet). Telefondaki "Read timed out" tam olarak buydu.
 *
 * JSONL satir tabanli ve `applyTranscriptLine` durumsuz: bir satirin anlami
 * kendinden onceki satirlara bagli degil. Yani dosya yalnizca BUYUDUYSE eski
 * dokum aynen gecerli kalir, sadece EKLENEN kuyruk ayristirilir.
 *
 * `size` cache'te BYTE cinsinden ve son TAM satirin sonunu gosterir: dosya
 * yarim bir satirla bitmis olabilir (koprü o an yaziyordur), yarim satir bir
 * sonraki turda bastan okunur. '
' (0x0a) UTF-8'de cok baytli bir karakterin
 * parcasi olamaz, bu yuzden byte sinirindan bolmek karakter bozmaz.
 */
function searchableMessages(file, mtime) {
  loadSearchIndex();
  const hit = _searchIndex.get(file);
  if (hit && hit.mtime === mtime) return hit.msgs;

  // ARTIMLI YOL: ayni dosya daha once dokumlenmis ve o gunden beri yalniz
  // buyumusse, eski dokumun uzerine ekle. Dosya KUCULDUYSE ya da elimizde
  // offset yoksa guvenli taraf tam ayristirmadir (kirpilmis/degistirilmis
  // dosyada eski dokum yanlis olurdu).
  if (hit && Array.isArray(hit.msgs) && Number.isFinite(hit.size) && hit.size > 0) {
    let st;
    try { st = fs.statSync(file); } catch { st = null; }
    if (st && st.size > hit.size) {
      let fd;
      try {
        fd = fs.openSync(file, 'r');
        const uzunluk = st.size - hit.size;
        const kuyrukBuf = Buffer.alloc(uzunluk);
        const okunan = fs.readSync(fd, kuyrukBuf, 0, uzunluk, hit.size);
        const sonNl = kuyrukBuf.lastIndexOf(0x0a, okunan - 1);
        if (sonNl >= 0) {
          const kuyruk = kuyrukBuf.toString('utf-8', 0, sonNl + 1);
          const msgs = hit.msgs.slice();
          for (const m of parseTranscriptText(kuyruk).messages) aramaSatiriEkle(msgs, m);
          const kayit = { mtime, size: hit.size + sonNl + 1, msgs };
          _searchIndex.set(file, kayit);
          saveSearchIndexSoon();
          return msgs;
        }
        // Tam satir yok (dosya yarim satirla buyumus): dokum degismedi, yalniz
        // mtime'i tazele ki her sorguda buraya tekrar girilmesin.
        _searchIndex.set(file, { mtime, size: hit.size, msgs: hit.msgs });
        return hit.msgs;
      } catch { /* artimli okuma basarisiz — asagida tam ayristirmaya dusulur */ }
      finally { if (fd !== undefined) { try { fs.closeSync(fd); } catch {} } }
    }
  }

  // TAM AYRISTIRMA. Buffer uzerinden okunuyor cunku cache'e yazilan offset
  // BYTE olmali; readFileSync('utf-8') string dondurur ve karakter indeksi
  // cok baytli metinde byte offset'i tutmaz.
  const msgs = [];
  let boyut = 0;
  try {
    const buf = fs.readFileSync(file);
    const sonNl = buf.lastIndexOf(0x0a);
    boyut = sonNl >= 0 ? sonNl + 1 : 0;
    const metin = boyut > 0 ? buf.toString('utf-8', 0, boyut) : buf.toString('utf-8');
    for (const m of parseTranscriptText(metin).messages) aramaSatiriEkle(msgs, m);
  } catch { /* okunamayan transcript bos girdiyle gecilir */ }
  _searchIndex.set(file, { mtime, size: boyut, msgs });
  saveSearchIndexSoon();
  return msgs;
}

// Sozlugu arka planda, PARCA PARCA kur. Tek seferde kurmak 531 MB'lik senkron
// ayristirma demek ve event loop'u saniyelerce kilitler — telefon o sirada gelen
// her istekte zaman asimina duser. Dosya basina bir tick ile ilerlenir.
export function warmSearchIndex({ delayMs = 4000, batch = 2 } = {}) {
  const t = setTimeout(() => {
    loadSearchIndex();
    let files;
    try { files = ensureDiskCacheFresh().files.slice(); } catch { return; }
    const live = new Set(files.map(x => x.f));
    for (const k of _searchIndex.keys()) if (!live.has(k)) _searchIndex.delete(k);
    let i = 0;
    const step = () => {
      const until = Math.min(i + batch, files.length);
      for (; i < until; i++) {
        try { searchableMessages(files[i].f, files[i].mtime); } catch {}
      }
      if (i < files.length) { const n = setTimeout(step, 25); if (n.unref) n.unref(); }
    };
    step();
  }, delayMs);
  if (typeof t.unref === 'function') t.unref();
  return t;
}

// Read‑only content search over Claude disk sessions (Phase 7 global search).
// Session‑title matches plus FULL transcript prompt/answer search: tüm turlar
// (yalnız ilk kullanıcı + son asistan değil) taranır; her eşleşme, sohbet içi
// aramanın matchRowIds sırasıyla hizalı matchOrdinal ile döner (deep‑link).
export function searchDiskSessions({ query, cwd, limit = 40, deadline = 0 } = {}) {
  const q = (query || '').toLowerCase().trim();
  if (q.length < 2) return [];
  const { sessions: all } = listDiskSessions({ all: true }) || {};
  // DIKKAT: listDiskSessions kayitlari (diskSessionRecord) file/_diskFile alani
  // TASIMAZ — dosya yolu cache'in idIndex'inden cozulur. Eski `s.file ||
  // s._diskFile` kodu her oturumu sessizce atlayip mesaj aramasini tamamen
  // olduruyordu (yalniz baslik eslesirdi; canli goruldu, "outer wilds" vakasi).
  const cache = ensureDiskCacheFresh();
  const idIndex = cache.idIndex;
  const mtimeByFile = new Map(cache.files.map(x => [x.f, x.mtime]));
  const results = [];
  let kesildi = false;
  for (const s of (all || [])) {
    // SÜRE BÜTÇESİ: bu fonksiyon SENKRON, yani çağıranın kurduğu setTimeout
    // biz dönmeden çalışamaz (bkz. search.mjs gerekçesi). Durma kararı burada
    // verilmezse hiç verilmiyor. Kontrol oturum döngüsünün başında: aşağıdaki
    // transcript taraması dosya başına maliyetli olan iş.
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
    // Full transcript message search
    if (results.length >= limit) break;
    try {
      const file = s.file || s._diskFile || idIndex.get(s.id);
      if (!file) continue;
      const msgs = searchableMessages(file, mtimeByFile.get(file) ?? 0);
      for (const m of matchTranscriptMessages(msgs, q)) {
        if (results.length >= limit) break;
        results.push({
          type: 'message', sessionId: s.id, title: s.title || '', projectPath: s.cwd || '',
          role: m.role, text: m.text, rowId: '', matchOrdinal: m.matchOrdinal, mtime: s.mtime || 0,
        });
      }
    } catch { /* skip unreadable transcripts */ }
  }
  // Kesildiyse çağıran BİLMELİ: `globalSearch` bunu süreye bakarak tahmin
  // edemiyor (kendinden öncekiler bütçeyi yemişse yanlış pozitif üretiyordu).
  return { hits: results, truncated: kesildi };
}

function restorePersistedSessions() {
  claudeShellPersistence.restoreFromDisk();
}

restorePersistedSessions();
ensureLawSkills();

// Senkron sürüm: asenkron killChildTree çıkış kancasında hiç doğmuyor, yani bu
// satır 3 Ağu 2026'ya kadar sessizce hiçbir şey yapmıyordu (ölçüldü).
process.on('exit', () => { for (const s of sessions.values()) if (s.child) { try { killChildTreeSync(s.child.pid); } catch {} } });
