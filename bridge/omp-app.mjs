// OMP native RPC backend. One long-lived `omp --mode rpc` child owns one
// AgentBridge session; this mirrors OMP's one-active-session process model.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { OmpRpcClient } from './omp-rpc-client.mjs';
import { discoverOmpMcpCandidates, mcpInventoryFromPrompt, skillInventoryFromCommands } from './omp-inventory.mjs';
import { hhmm, capMessages, capToolDetails, logWarn, matchTranscriptMessages, resolveExecutableSync, isStaleApproval } from './session-utils.mjs';
import { createAgentSessionCore, ensureMessageRowIds, paginateSessionMessages } from './agent-session-core.mjs';

// RPC kataloğu alınamazsa kullanılan statik yedek. Kademeler OMP'nin kendi
// kataloğuyla aynı olmalı (v4-flash low/high/max, v4-pro high/max) — yanlış
// yedek, seçicide modelin desteklemediği kademeyi gösterir.
export const MODELS = [
  { id: 'deepseek/deepseek-flash', label: 'deepseek-flash', variants: ['off', 'low', 'high', 'max'], defaultVariant: '' },
  { id: 'deepseek/deepseek-v4-pro', label: 'DeepSeek V4 Pro', variants: ['off', 'high', 'max'], defaultVariant: '' },
];
export const THINKING_LEVELS = ['', 'off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
export const PERMISSION_MODES = [
  { id: 'yolo', name: 'YOLO', description: 'Araçları onay sormadan çalıştırır' },
  { id: 'write', name: 'Write', description: 'Yazma işlemlerinde onay ister' },
  { id: 'ask', name: 'Ask', description: 'Araç çağrılarında onay ister' },
];

const sessions = new Map();
// Emilmiş (tekilleştirilmiş) kabuk id'leri → kalan kabuk. Bir cihaz elindeki
// eski kabuk id'siyle geldiğinde "session not found" yerine doğru sohbete
// düşsün diye tutulur; kabuk dosyasıyla birlikte diske de yazılır.
const shellAliases = new Map();
const OMP_ROOT = process.env.AGENTBRIDGE_OMP_ROOT || path.join(os.homedir(), '.omp', 'agent');
const SESSION_DIR = path.join(OMP_ROOT, 'agentbridge-sessions');
const SHELL_FILE = path.join(OMP_ROOT, 'agentbridge-sessions.json');
// Sabitleme / yeniden adlandırma / arşiv. OMP transkriptine yazmıyoruz: dosya
// OMP'nin kendi biçimi ve `--resume` onu baştan yazıyor, eklediğimiz alan kaybolur.
// Anahtar OMP'nin KALICI oturum kimliği — köprü kabuğunun uuid'si her adopt'ta
// yenilendiği için onunla anahtarlamak sabitlemeyi bir sonraki açılışta düşürürdü.
const META_FILE = path.join(OMP_ROOT, 'agentbridge-session-meta.json');
// Yeni oturumun ana modeli. Pro orkestratör; ucuz iş zaten alt ajanlara gidiyor
// (`~/.omp/agent/config.yml` içindeki modelRoles: smol/task -> flash). Köprü
// modeli çalışma anında `set_model` ile dayattığı için OMP'nin `default` rolü
// buraya işlemez, varsayılanı burada tutmak gerekiyor.
const DEFAULT_MODEL = 'deepseek/deepseek-v4-pro';
// Boşta duran RPC çocuğunu bellekte tutmanın anlamı yok: omp.exe ~157MB ve
// `--resume <sessionFile>` ile aynı oturuma kayıpsız dönüyor. Sekme açıkken
// (abone varken) kapatmıyoruz, yoksa her dönüşte açılış gecikmesi eklenir.
const IDLE_CHILD_MS = Math.max(5_000, Number(process.env.OMP_IDLE_CHILD_MS) || 10 * 60_000);
// Tur donması nöbetçisi. OMP'nin eval/bash araçları MEŞRU olarak dakikalarca
// (canlıda 30+ dk emülatör turu görüldü) frame üretmeden çalışabiliyor; salt
// sessizlik donma kanıtı DEĞİL. Bu yüzden yalnız zamana bakmıyoruz: N ms hiç
// frame gelmediyse child'a otoriter `get_state` yokluyoruz. Cevap gelirse child
// yaşıyor (araç uzun sürüyor olabilir) → turu KESMEyiz. Yoklama ard arda K kez
// zaman aşımına uğrarsa child'ın RPC döngüsü kilitlenmiştir → turu kapatıp süreci
// düşürürüz (bir sonraki prompt --resume ile aynı oturuma döner). 13.08.2026'da
// bir eval'in python health-probe süreci EventPairLow'da 6.5 saat asılı kaldı ve
// hiçbir koruma olmadığı için oturum sabaha kadar "çalışıyor" göründü.
const OMP_WATCHDOG_TICK_MS = 15_000;
const OMP_TURN_INACTIVITY_MS = Math.max(30_000, Number(process.env.AGENTBRIDGE_OMP_TURN_INACTIVITY_MS) || 120_000);
const OMP_PROBE_TIMEOUT_MS = Math.max(2_000, Number(process.env.AGENTBRIDGE_OMP_PROBE_TIMEOUT_MS) || 8_000);
const OMP_MAX_PROBE_FAILURES = Math.max(1, Number(process.env.AGENTBRIDGE_OMP_MAX_PROBE_FAILURES) || 3);
let ompWatchdogTimer = null;
let modelCache = { at: 0, models: MODELS };
let inventoryCache = { at: 0, value: null };
const INVENTORY_CACHE_MS = 30_000;
// Slash komut kataloğu OMP'nin kendisinden gelir (builtin + skill + extension +
// custom); statik SLASH tablosu gibi bayatlamaz. Her RPC çocuğu açılışta
// available_commands_update basar, cache oradan bedavaya tazelenir.
let slashCache = { at: 0, commands: [] };
const SLASH_CACHE_MS = 60_000;
// Katalog her modelin varsayılan düşünme seviyesini yazmıyor (native deepseek
// yazmıyor, OpenRouter yazıyor). Canlı oturum `get_state` ile gerçek seviyeyi
// söylediğinde öğrenip sakla — seçicideki "varsayılan · high" bundan besleniyor.
const resolvedEffortByModel = new Map();

function ompExecutable() {
  const override = String(process.env.OMP_EXE || '').trim();
  if (override) return override;
  const local = process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'omp', 'omp.exe');
  if (local && fs.existsSync(local)) return local;
  return resolveExecutableSync('omp');
}

function normalizeModel(model) {
  const value = String(model || '').trim();
  return value.includes('/') ? value : DEFAULT_MODEL;
}
function modelParts(model) {
  const value = normalizeModel(model);
  const slash = value.indexOf('/');
  return { provider: value.slice(0, slash), modelId: value.slice(slash + 1) };
}
function normalizePermission(mode) {
  const value = String(mode || '').toLowerCase().trim();
  return value === 'ask' || value === 'always-ask' ? 'ask' : value === 'write' ? 'write' : 'yolo';
}
function approvalMode(mode) { return mode === 'ask' ? 'always-ask' : mode === 'write' ? 'write' : 'yolo'; }
function normalizeEffort(value) { const v = String(value || '').toLowerCase().trim(); return THINKING_LEVELS.includes(v) ? v : ''; }

function freshSession(id, cwd, model, permissionMode, effort = '') {
  return {
    id, cwd, model: normalizeModel(model), permissionMode: normalizePermission(permissionMode), effort: normalizeEffort(effort),
    status: 'idle', messages: [], toolDetails: [], subscribers: new Set(), client: null,
    pendingApproval: null, awaitingFirstOutput: false, contextTokens: 0, contextWindow: 0,
    sessionFile: '', ompSessionId: '', lastUserAt: 0,
    // Açık satırlar İNDEKSLE değil NESNE REFERANSIYLA tutulur: capMessages her
    // push'ta baştan satır atabiliyor, indeks kayınca metin alakasız bir satıra
    // yazılırdı. _toolRows araç çağrısı id'siyle anahtarlı — OMP araçları
    // paralel çalıştırabiliyor, "tek açık düşünce satırı" varsayımı tutmuyor.
    _openAgentRow: null, _openThoughtRow: null, _toolRows: new Map(),
    _idleTimer: null,
    // Nöbetçi durumu: son frame zamanı ve ard arda yanıtsız yoklama sayısı.
    _lastActivity: 0, _staleProbes: 0,
  };
}

function cancelIdleReap(s) {
  if (!s._idleTimer) return;
  clearTimeout(s._idleTimer); s._idleTimer = null;
}

// Çocuğu yalnız gerçekten atıl olduğunda kapat: tur bitmiş, onay beklenmiyor
// ve hiçbir istemci dinlemiyor. Zamanlayıcı unref'li — köprünün kapanmasını
// geciktirmez.
function scheduleIdleReap(s) {
  cancelIdleReap(s);
  if (!s.client || s.status === 'running' || s.pendingApproval || s.subscribers.size) return;
  s._idleTimer = setTimeout(() => {
    s._idleTimer = null;
    if (!s.client || s.status === 'running' || s.pendingApproval || s.subscribers.size) return;
    const client = s.client; s.client = null;
    void client.close().catch(() => {});
  }, IDLE_CHILD_MS);
  s._idleTimer.unref?.();
}

// Bir sohbetin kalıcı kimliği OMP'nin kendi oturum id'sidir; köprü kabuğunun
// uuid'si adopt başına yenilenir. İki kabuk aynı transkripte bağlanırsa her
// biri kendi child'ını ve kendi `messages` dizisini taşır: bir cihazda ilerleyen
// tur diğerine HİÇ ulaşmaz (13.08.2026 kullanıcı şikayeti — canlı köprüde aynı
// diskId'ye bağlı üç kabuk ölçüldü). Aynı hata codex-app'te findShellByThread,
// opencode-app'te byOpencodeSid ile daha önce kapatılmıştı.
function transcriptKey(s) { return s?.ompSessionId || s?.sessionFile || ''; }
function shellsForTranscript(key) {
  const value = String(key || '').trim();
  if (!value) return [];
  return [...sessions.values()].filter(s =>
    s.ompSessionId === value || s.sessionFile === value ||
    (s.sessionFile && path.basename(s.sessionFile) === value));
}
// Kopyalar arasında hayatta kalacak kabuk: çalışan > en son konuşulan > en çok
// mesajı olan > canlı child'ı olan. Canlı child TAZELİK ölçüsü değildir —
// günlerdir konuşulmamış bir kabuğun çocuğu ayakta kalabiliyor; kazanan
// transkriptin en ilerisini taşıyan olmalı.
function pickCanonical(shells) {
  return shells.slice().sort((a, b) =>
    Number(b.status === 'running') - Number(a.status === 'running') ||
    (b.lastUserAt || 0) - (a.lastUserAt || 0) ||
    b.messages.length - a.messages.length ||
    Number(!!b.client) - Number(!!a.client))[0] || null;
}
// Kabuk id'si, OMP disk id'si ya da transkript yolu — hepsi aynı sohbete çözülür.
function resolveSession(id) {
  const key = String(id || '').trim();
  if (!key) return null;
  const direct = sessions.get(key);
  if (direct) return direct;
  const aliased = shellAliases.get(key);
  if (aliased && sessions.has(aliased)) return sessions.get(aliased);
  return pickCanonical(shellsForTranscript(key));
}

// Kopya kabuğu kalan kabuğa katar: child'ı kapatır (tek transkripte iki child
// yazmasın), abonelerini taşır ve id'sini alias olarak bırakır.
function absorbShell(target, dup) {
  if (!target || !dup || target === dup) return;
  cancelIdleReap(dup);
  const client = dup.client; dup.client = null;
  if (client) void client.close().catch(() => {});
  for (const ws of dup.subscribers) {
    // Taşınan abone yeni oturuma sıfırdan bağlanmış sayılır: delta tabanı dup'a
    // aitti, target için geçersiz — sessionCore tam snapshot'la yeniden kurar.
    // Delta tercihi korunur; boş opts geçmek istemciyi sessizce düz kipe düşürürdü.
    sessionCore.subscribe(target, ws, { delta: ws._deltaMode === true });
    const drop = () => { scheduleIdleReap(target); };
    ws.on('close', drop); ws.on('error', drop);
  }
  dup.subscribers.clear();
  sessions.delete(dup.id);
  shellAliases.set(dup.id, target.id);
  for (const [from, to] of shellAliases) if (to === dup.id) shellAliases.set(from, target.id);
}

function dedupeTranscript(key) {
  const shells = shellsForTranscript(key);
  if (shells.length < 2) return shells[0] || null;
  const target = pickCanonical(shells);
  for (const dup of shells) if (dup !== target) absorbShell(target, dup);
  logWarn('omp', 'aynı transkripte bağlı kopya kabuklar birleştirildi',
    { transcript: key, merged: shells.length - 1, sessionId: target.id });
  return target;
}

function persistSessions() {
  try {
    fs.mkdirSync(OMP_ROOT, { recursive: true });
    const rows = [...sessions.values()].map(s => ({
      id: s.id, cwd: s.cwd, model: s.model, permissionMode: s.permissionMode,
      effort: s.effort, sessionFile: s.sessionFile, ompSessionId: s.ompSessionId,
      lastUserAt: s.lastUserAt || 0,
    }));
    fs.writeFileSync(SHELL_FILE, JSON.stringify({ sessions: rows, aliases: Object.fromEntries(shellAliases) }, null, 2), 'utf8');
  } catch (error) { logWarn('omp', 'oturum kabukları yazılamadı', { error: error.message }); }
}

function restoreSessions() {
  if (process.env.NODE_TEST_CONTEXT) return;
  try {
    const parsed = JSON.parse(fs.readFileSync(SHELL_FILE, 'utf8'));
    // Eski biçim çıplak diziydi; alias eklenince nesneye geçildi.
    const rows = Array.isArray(parsed) ? parsed : parsed?.sessions;
    if (!Array.isArray(rows)) return;
    for (const [from, to] of Object.entries(parsed?.aliases || {})) shellAliases.set(String(from), String(to));
    for (const row of rows) {
      if (!row?.id || !row?.cwd || !fs.existsSync(row.cwd)) continue;
      const s = freshSession(row.id, row.cwd, row.model, row.permissionMode, row.effort);
      s.sessionFile = row.sessionFile && fs.existsSync(row.sessionFile) ? row.sessionFile : '';
      s.ompSessionId = String(row.ompSessionId || ''); s.lastUserAt = Number(row.lastUserAt || 0);
      sessions.set(s.id, s);
    }
    // Önceki sürümlerin biriktirdiği kopyaları açılışta topla: bu noktada ne
    // abone ne child var, birleştirme bedelsiz.
    for (const key of new Set([...sessions.values()].map(transcriptKey).filter(Boolean))) dedupeTranscript(key);
  } catch {}
}

function snapshot(s) {
  ensureMessageRowIds(s);
  return {
    type: 'conversation', sessionId: s.id, messages: s.messages,
    running: s.status === 'running', awaitingApproval: !!s.pendingApproval,
    awaitingUserInput: !!s.pendingApproval && s.pendingApproval.method !== 'confirm',
    awaitingFirstOutput: !!s.awaitingFirstOutput,
    approval: s.pendingApproval,
    contextTokens: s.contextTokens || 0, contextWindow: s.contextWindow || 0,
    effort: s.effort || '', permissionMode: s.permissionMode || 'yolo',
  };
}
// Delta yayın çekirdeği: claude-app ve codex-app'in kullandığı createAgentSessionCore.
// Eskiden push() her frame'de TAM snapshot'ı yayınlıyordu — tam transkript,
// değişiklik olmasa bile (ölçüldü 13.08.2026: canlı akan oturumda 45 sn'de düz
// kip 2,3 MB, delta kipi 0 ek bayt). Android 07.08'den beri, web de öyle,
// `delta=1` istiyor; server.mjs opts'u iletiyordu ama omp yok sayıyordu.
const sessionCore = createAgentSessionCore({ snapshot });

// capSess: claude/codex'teki _deltaDirty sigortasının omp karşılığı. rowId'siz
// bir satır kırpılırsa diff kimliği kaybeder; sigorta bir sonraki push'u tüm
// abonelere tam snapshot olarak gönderir.
function capSess(s) {
  capMessages(s, undefined, evicted => {
    if (evicted.some(msg => !msg?.rowId)) s._deltaDirty = true;
  });
  capToolDetails(s);
}
function push(s) { capSess(s); sessionCore.throttledPush(s); }
// Tur sınırında bekletme olmaz: kısıcının ertelediği son kare hemen gitsin.
function pushNow(s) { capSess(s); sessionCore.flushThrottle(s); }
function agentRow(s) {
  if (s._openAgentRow) return s._openAgentRow;
  const row = { role: 'agent', text: '', time: hhmm(), _open: true };
  s.messages.push(row); s._openAgentRow = row; return row;
}
function newThoughtRow(s, label) {
  const thoughtIndex = s.toolDetails.length; s.toolDetails.push('');
  const row = { role: 'thought', text: label, time: hhmm(), thoughtIndex, _open: true };
  s.messages.push(row); return row;
}
function thinkingRow(s) {
  if (s._openThoughtRow) return s._openThoughtRow;
  s._openThoughtRow = newThoughtRow(s, '🧠 Düşünme'); return s._openThoughtRow;
}
// Araç satırı çağrı id'siyle bulunur; id yoksa (eski/eksik frame) araç adına
// düşülür, o da yoksa yeni satır açılır.
function toolRow(s, frame) {
  const key = frame.toolCallId || frame.toolName || frame.name || 'tool';
  const existing = s._toolRows.get(key);
  if (existing) return existing;
  const row = newThoughtRow(s, `🔧 ${frame.toolName || frame.name || 'tool'}`);
  s._toolRows.set(key, row); return row;
}
function finishOpenRows(s) {
  for (const row of s.messages) delete row._open;
  s._openAgentRow = null; s._openThoughtRow = null; s._toolRows.clear();
}

function textParts(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  // Yalnız metin: thinking artık kendi satırında duruyor, buraya karıştırılırsa
  // düşünce metni asistan cevabının içine yapışır (eski davranış).
  return content.filter(p => p?.type === 'text').map(p => String(p.text || '')).join('\n\n');
}
function hhmmAt(ts) {
  const value = typeof ts === 'number' ? ts : Date.parse(ts || '');
  if (!Number.isFinite(value)) return hhmm();
  const d = new Date(value);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function thoughtLabel(text, prefix) {
  const first = String(text || '').trimStart().split('\n', 1)[0].replace(/\s+/g, ' ').slice(0, 70);
  return first ? `${prefix} ${first}` : prefix;
}
// Diskten/oturumdan gelen transkripti satırlara çevirir. Eskiden yalnız düz
// metin üretiliyordu: bir asistan mesajının thinking + text blokları ayraçsız
// TEK paragrafta birleşiyor, araç çağrıları ise tümüyle düşüyordu. Oturum
// yeniden canlandığında (boşta kapanan çocuk yeniden doğduğunda) akışta doğru
// görünen konuşma bu birleşmiş hâliyle eziliyordu.
// Bloklar OMP'nin verdiği sırayla ayrı satır olur; araç sonuçları toolCallId
// ile kendi araç satırının detayına eklenir.
function transcriptRows(messages) {
  const rows = [];
  const details = [];
  const toolRows = new Map();
  const addThought = (label, detail, time) => {
    const thoughtIndex = details.length; details.push(detail);
    const row = { role: 'thought', text: label, time, thoughtIndex };
    rows.push(row); return row;
  };
  for (const message of Array.isArray(messages) ? messages : []) {
    const time = hhmmAt(message?.timestamp);
    if (message?.role === 'user') {
      const text = textParts(message.content);
      if (text) rows.push({ role: 'user', text, time });
      continue;
    }
    if (message?.role === 'toolResult') {
      const row = toolRows.get(message.toolCallId);
      const text = textParts(message.content);
      if (row) {
        if (text) details[row.thoughtIndex] += '\n' + text;
        if (message.isError) row.text = `⚠️ ${row.text}`;
      } else {
        addThought(`🔧 ${message.toolName || 'tool'}`, text, time);
      }
      continue;
    }
    if (message?.role !== 'assistant') continue;
    const parts = Array.isArray(message.content) ? message.content
      : (typeof message.content === 'string' ? [{ type: 'text', text: message.content }] : []);
    for (const part of parts) {
      if (part?.type === 'thinking') {
        const text = String(part.thinking || part.text || '');
        if (text.trim()) addThought(thoughtLabel(text, '🧠'), text, time);
      } else if (part?.type === 'text') {
        const text = String(part.text || '');
        if (text.trim()) rows.push({ role: 'agent', text, time });
      } else if (part?.type === 'toolCall') {
        const name = part.name || 'tool';
        const args = part.arguments ?? part.partialArgs;
        const detail = args == null ? name : `${name}\n${typeof args === 'string' ? args : JSON.stringify(args, null, 2)}`;
        const row = addThought(`🔧 ${name}`, detail, time);
        if (part.id) toolRows.set(part.id, row);
      }
    }
    // SAGLAYICI HATASI ICERIKTE DEGIL, MESAJIN KENDI ALANLARINDA.
    //
    // 19.08.2026 canli: `opencode-go/muse-spark-1.2-contributor` seciliyken
    // gonderilen tur 700ms'de 403 ile dondu ("This model collects data used to
    // improve its quality and requires explicit opt in"). OMP bunu transkripte
    // icerigi BOS bir asistan mesaji olarak yazdi; sebep `stopReason: "error"`
    // ve `errorMessage` alanlarindaydi. `content` uzerinde donen bu dongu hic
    // satir uretmedi, yani kullanici ekranda YALNIZ kendi mesajini gordu ve
    // "tur neden baslamadi" diye sordu -- hata vardi, gosterilmiyordu.
    const hata = message?.stopReason === 'error'
      ? String(message.errorMessage || message.error || '').trim()
      : '';
    if (hata) rows.push({ role: 'agent', text: `⚠️ ${hata}`, time });
  }
  return { rows, details };
}

function slashFromRpcCommands(list) {
  return (Array.isArray(list) ? list : [])
    .filter(c => c?.name)
    .map(c => ({ name: String(c.name), desc: String(c.description || ''), hint: String(c.input?.hint || '') }));
}

function updateSlashCache(list) {
  const commands = slashFromRpcCommands(list);
  if (commands.length) slashCache = { at: Date.now(), commands };
}

function onFrame(s, frame) {
  // Her frame canlılık kanıtıdır (get_state yanıtları buraya DÜŞMEZ; _dispatch
  // onları pending olarak tüketir — yani kendi yoklamamız aktivite sayılmaz).
  s._lastActivity = Date.now(); s._staleProbes = 0;
  switch (frame.type) {
    case 'agent_start': case 'turn_start':
      s.status = 'running'; s.awaitingFirstOutput = true; push(s); return;
    // OMP her içerik bloğunu (thinking / text / toolcall) açık `*_start` ve
    // `*_end` sınırlarıyla veriyor. Bunlar yok sayılınca turun BÜTÜN metni tek
    // satıra yığılıyor: bloklar ayraçsız uç uca ekleniyor ("…İnceledim.Kısa
    // cevap:") ve satır turun başında açıldığı için sonradan gelen düşünce/araç
    // kartlarının ÜSTÜNDE kalıyor — yani cevap en üstte, araçlar en altta
    // görünüyordu. Sınırlarda satırı kapatmak ikisini birden çözer.
    case 'message_update': {
      const event = frame.assistantMessageEvent || {};
      switch (event.type) {
        case 'text_start': s._openAgentRow = null; break;
        case 'text_delta':
          if (event.delta) { agentRow(s).text += event.delta; s.awaitingFirstOutput = false; }
          break;
        case 'text_end':
          if (s._openAgentRow) delete s._openAgentRow._open;
          s._openAgentRow = null; break;
        case 'thinking_start': s._openThoughtRow = null; break;
        case 'thinking_delta': {
          if (!event.delta) break;
          const row = thinkingRow(s);
          s.toolDetails[row.thoughtIndex] = (s.toolDetails[row.thoughtIndex] || '') + event.delta;
          const first = s.toolDetails[row.thoughtIndex].trimStart().split('\n', 1)[0].replace(/\s+/g, ' ').slice(0, 70);
          row.text = first ? '🧠 ' + first : '🧠 Düşünme'; s.awaitingFirstOutput = false;
          break;
        }
        case 'thinking_end':
          if (s._openThoughtRow) delete s._openThoughtRow._open;
          s._openThoughtRow = null; break;
        case 'error': {
          const message = event.error?.errorMessage || event.error?.message || 'OMP model hatası';
          s.messages.push({ role: 'agent', text: `⚠️ ${message}`, time: hhmm() });
          s._openAgentRow = null; s.awaitingFirstOutput = false; break;
        }
        default: break;
      }
      push(s); return;
    }
    case 'tool_execution_start': {
      // Araç satırı açılınca metin satırını da kapat: aracın ardından gelen
      // cevap, aracın ALTINDA yeni bir satırda başlasın.
      s._openAgentRow = null; s._openThoughtRow = null;
      const row = toolRow(s, frame);
      const name = frame.toolName || frame.name || 'tool';
      const args = frame.args ?? frame.arguments;
      s.toolDetails[row.thoughtIndex] = args == null ? name : `${name}\n${JSON.stringify(args, null, 2)}`;
      s.awaitingFirstOutput = false; push(s); return;
    }
    case 'tool_execution_update': {
      const row = toolRow(s, frame);
      const delta = frame.partialResult ?? frame.update ?? frame.output;
      if (delta != null) s.toolDetails[row.thoughtIndex] += '\n' + (typeof delta === 'string' ? delta : JSON.stringify(delta, null, 2));
      push(s); return;
    }
    case 'tool_execution_end': {
      const row = toolRow(s, frame);
      const result = frame.result ?? frame.output ?? frame.error;
      if (result != null) s.toolDetails[row.thoughtIndex] += '\n' + (typeof result === 'string' ? result : JSON.stringify(result, null, 2));
      if (frame.isError) row.text = `⚠️ ${row.text}`;
      s._toolRows.delete(frame.toolCallId || frame.toolName || frame.name || 'tool');
      delete row._open; push(s); return;
    }
    case 'extension_ui_request': return onUiRequest(s, frame);
    case 'model_changed': {
      const model = frame.model || frame.currentModel;
      if (model?.provider && model?.id) s.model = `${model.provider}/${model.id}`;
      push(s); return;
    }
    case 'thinking_level_changed': {
      // Native event çözülen seviyeyi bildirir; kullanıcının "varsayılan"
      // seçimini explicit seviyeye çevirmemeli. Seçim yalnız setEffort/prompt'ta
      // değişir, çözülen değer model kataloğunun etiketi için ayrı tutulur.
      const resolved = normalizeEffort(frame.level || frame.thinkingLevel);
      if (resolved && s.model) resolvedEffortByModel.set(s.model, resolved);
      push(s); return;
    }
    case 'agent_end':
      if (frame.isTerminal === false) return;
      finishTurn(s); return;
    case 'prompt_result':
      if (frame.agentInvoked === false) finishTurn(s);
      return;
    case 'command_output':
      if (frame.output || frame.text) s.messages.push({ role: 'agent', text: String(frame.output || frame.text), time: hhmm() });
      finishTurn(s); return;
    case 'available_commands_update':
      updateSlashCache(frame.commands); return;
    case 'extension_error':
      s.messages.push({ role: 'agent', text: `⚠️ OMP eklenti hatası: ${frame.error || 'bilinmeyen hata'}`, time: hhmm() }); push(s); return;
    default: return;
  }
}

function onUiRequest(s, frame) {
  if (frame.method === 'cancel') {
    if (s.pendingApproval?.requestId === frame.targetId) s.pendingApproval = null;
    push(s); return;
  }
  if (frame.method === 'notify') {
    if (frame.message) s.messages.push({ role: 'agent', text: `ℹ️ ${frame.message}`, time: hhmm() });
    push(s); return;
  }
  if (!['confirm', 'select', 'input', 'editor'].includes(frame.method)) return;
  const options = (frame.options || []).map((label, index) => ({ id: String(index), label: String(label), description: '' }));
  s.pendingApproval = {
    requestId: frame.id, method: frame.method, kind: frame.method === 'confirm' ? 'permission' : 'question',
    summary: frame.message || frame.title || 'OMP yanıt bekliyor', title: frame.title || 'OMP',
    questions: frame.method === 'confirm' ? [] : [{ id: 'omp-ui', header: frame.title || 'OMP', question: frame.placeholder || frame.title || 'Yanıt', options }],
  };
  push(s);
}

function finishTurn(s, error = '') {
  finishOpenRows(s); s.status = 'idle'; s.awaitingFirstOutput = false; s._staleProbes = 0;
  if (error) s.messages.push({ role: 'agent', text: `⚠️ ${error}`, time: hhmm() });
  pushNow(s); persistSessions();
  // TUR BITTI: transkriptin SON mesaji hata tasiyor mu?
  //
  // Ilk surum bunu "tur HIC satir uretmediyse" diye kosullamisti ve YETMEDI --
  // 19.08.2026 saat 18:21'de model once bir cumle yazdi, sonra ilk arac
  // cagrisinda saglayicinin akisi koptu ("OpenAI completions stream closed
  // before a finish_reason was received"). Tur satir URETMISTI, yani kosul
  // tutmadi ve hata yine gorunmedi; kullanici "yine bir cumle yazip turu
  // kapatti" dedi. Ders: bir turun yarida olmesi, hic baslamamasindan daha sik.
  //
  // Bu yuzden kosul kalkti. Bedeli tur basina bir `get_messages` cagrisi;
  // turun kendisi saniyeler-dakikalar surerken bu olculebilir bir yuk degil.
  if (!error) void reportTurnError(s);
  if (s._pendingPermissionRespawn && s.client) {
    const client = s.client; s.client = null; s._pendingPermissionRespawn = false;
    void client.close().catch(() => {});
  }
  void refreshState(s);
  scheduleIdleReap(s);
}

// Kilitlenmiş turu kapat: satırları kapat, oturumu idle'a al, kullanıcıya sebebi
// bildir ve child'ı DÜŞÜR. child.close() önce stdin'i kapatıp closeTimeout sonra
// kill'liyor — asılı süreç için kill devreye girer. client null'lanınca bir
// sonraki prompt --resume ile aynı transcript'e döner (geçmiş kaybolmaz).
function finalizeStalledTurn(s, reason) {
  finishOpenRows(s); s.status = 'idle'; s.awaitingFirstOutput = false; s._staleProbes = 0;
  s.messages.push({ role: 'agent', text: `⚠️ ${reason}`, time: hhmm() });
  pushNow(s); persistSessions();
  const client = s.client; s.client = null;
  if (client) void client.close().catch(() => {});
}

function ensureOmpWatchdog() {
  if (ompWatchdogTimer) return;
  ompWatchdogTimer = setInterval(() => { void ompWatchdogTick(); }, OMP_WATCHDOG_TICK_MS);
  ompWatchdogTimer.unref?.();
}

async function ompWatchdogTick() {
  const now = Date.now();
  for (const s of sessions.values()) {
    // Onay bekleyen tur meşru şekilde "running" ama atıl durur — dokunma.
    if (s.status !== 'running' || s.pendingApproval) continue;
    if (!s.client?.child || s.client.child.exitCode != null) continue;
    if (!s._lastActivity || now - s._lastActivity < OMP_TURN_INACTIVITY_MS) continue;
    // Otoriter yoklama: child hâlâ RPC'ye cevap veriyor mu? get_state yanıtı
    // frame olarak yayılmaz, bu satır _lastActivity'yi kendisi tazelemez.
    let alive = false;
    try { await s.client.request('get_state', {}, { timeoutMs: OMP_PROBE_TIMEOUT_MS }); alive = true; }
    catch { alive = false; }
    if (!s.client?.child || s.client.child.exitCode != null) continue; // arada kapanmış olabilir
    if (alive) {
      // Child yaşıyor → araç meşru şekilde uzun sürüyor olabilir. Turu kesmeyiz,
      // yalnız pencereyi yeniler ve yoklama sayacını sıfırlarız.
      s._lastActivity = Date.now(); s._staleProbes = 0;
      continue;
    }
    s._staleProbes = (s._staleProbes || 0) + 1;
    logWarn('omp', 'tur yanıtsız — get_state yoklaması zaman aşımına uğradı',
      { sessionId: s.id, probe: s._staleProbes, idleMs: now - s._lastActivity });
    if (s._staleProbes < OMP_MAX_PROBE_FAILURES) continue;
    finalizeStalledTurn(s, `OMP turu yanıt vermiyor (RPC yoklaması ${OMP_MAX_PROBE_FAILURES} kez zaman aşımına uğradı); tur otomatik kapatıldı, süreç yeniden başlatılacak.`);
  }
}

// OMP canlı kabuğa her açılışta yeni bir bridge-uuid veriyor; kalıcı native
// kimlik OMP'nin kendi `sessionId`si (disk transcript'inin adı). Cowork kaydının
// resume edebilmesi için o kimlik netleştiği anda haber verilmeli — codex'in
// onThreadResolved / opencode'un onSessionResolved kancasının OMP karşılığı.
const sessionResolvedListeners = new Set();

export function onSessionResolved(fn) {
  if (typeof fn !== 'function') return () => {};
  sessionResolvedListeners.add(fn);
  return () => sessionResolvedListeners.delete(fn);
}

function emitSessionResolved(s) {
  if (!s?.ompSessionId) return;
  const payload = {
    sessionId: s.id,
    threadId: s.ompSessionId,
    cwd: s.cwd,
    model: s.model,
    permissionMode: s.permissionMode,
  };
  for (const fn of sessionResolvedListeners) {
    try { fn(payload); } catch (e) { logWarn('omp', 'sessionResolved listener failed', { error: e.message || String(e) }); }
  }
}

async function refreshState(s) {
  if (!s.client) return;
  try {
    const state = await s.client.request('get_state');
    const priorOmpId = s.ompSessionId;
    s.sessionFile = state.sessionFile || s.sessionFile; s.ompSessionId = state.sessionId || s.ompSessionId;
    if (s.ompSessionId && s.ompSessionId !== priorOmpId) emitSessionResolved(s);
    if (state.model?.provider && state.model?.id) s.model = `${state.model.provider}/${state.model.id}`;
    // Kullanıcının seçimini EZME. Eskiden çözülen seviye doğrudan s.effort'a
    // yazılıyordu: "varsayılan" seçili bir oturum ilk turdan sonra sessizce
    // "high" seçilmiş gibi görünüyor ve kullanıcı bir daha varsayılana
    // dönemiyordu. Çözülen değer ayrı tutulur; katalogda `defaultLevel`
    // yazmayan modellerde (native deepseek) seçiciye "varsayılan · high"
    // yazdırmanın tek kaynağı bu.
    const resolved = normalizeEffort(state.thinkingLevel);
    if (resolved && s.model && resolvedEffortByModel.get(s.model) !== resolved) {
      resolvedEffortByModel.set(s.model, resolved);
      modelCache.at = 0; // katalog bir sonraki istekte yeni varsayılanla kurulsun
    }
    s.contextTokens = Number(state.contextUsage?.tokens || 0); s.contextWindow = Number(state.contextUsage?.contextWindow || 0);
    persistSessions(); push(s);
  } catch {}
}

// Child'ı yeni sahibine bağla. Kare/çıkış işleyicileri kabuğu CLOSURE'DAN değil
// `client._owner`'dan okur; sabitlenmiş `s` ile bağlansaydı devredilen child'ın
// kareleri eski kabuğa akmaya devam ederdi.
function bindClient(client, s) {
  client._owner = s;
  s.client = client;
  if (client._bound) return;
  client._bound = true;
  client.on('frame', frame => { const owner = client._owner; if (owner) onFrame(owner, frame); });
  client.on('exit', ({ error }) => {
    const owner = client._owner;
    if (!owner || owner.client !== client) return;
    owner.client = null;
    if (owner.status === 'running') finishTurn(owner, error?.message || 'OMP süreci kapandı');
  });
  client.on('protocol_error', error => logWarn('omp', 'RPC protokol hatası', { sessionId: client._owner?.id, error: error.message }));
}

const samePath = (a, b) => String(a || '').trim().toLowerCase() === String(b || '').trim().toLowerCase();

// Boştaki bir child'ı yeni oturuma DEVRET (native switch_session / new_session).
// omp.exe ~157 MB ve açılışı 1-2 sn; devir ölçümde ~100 ms.
//
// cwd ve approval-mode SÜREÇ ARGÜMANI: switch_session oturumu değiştiriyor ama
// süreci yeniden başlatmıyor. Çalışma dizininin oturumu takip edip etmediğini
// kesin ölçemedim (araç çıktısı turun uzunluğu yüzünden pencereye düşmedi), o
// yüzden savunmacı davranıyoruz: yalnız cwd ve izin kipi AYNI olan kabuklar
// arasında devir yapılır. Böylece cevap ne olursa olsun araçlar doğru klasörde
// çalışır. Kural gevşetilmeden önce bu ölçüm tamamlanmalı.
async function adoptIdleClient(s) {
  const donor = [...sessions.values()].find(d =>
    d !== s && d.client?.child && d.client.child.exitCode == null &&
    // Yalnız bindClient'tan geçmiş istemci devredilebilir: devir kare/çıkış
    // işleyicilerinin `_owner` dolaylamasına dayanıyor, onu taşımayan bir
    // istemcide kareler eski sahibe akmaya devam ederdi.
    d.client._bound === true &&
    d.status !== 'running' && !d.pendingApproval && d.subscribers.size === 0 &&
    samePath(d.cwd, s.cwd) && d.permissionMode === s.permissionMode);
  if (!donor) return null;
  const client = donor.client;
  const resuming = !!(s.sessionFile && fs.existsSync(s.sessionFile));
  try {
    // Dosyası olan oturum switch_session ile yüklenir; yepyeni oturum için
    // new_session aynı child'ı sıfırlar (ikisi de canlı ölçüldü).
    if (resuming) await client.request('switch_session', { sessionPath: s.sessionFile });
    else await client.request('new_session', {});
  } catch (error) {
    logWarn('omp', 'child devri başarısız, yeni süreç açılıyor', { from: donor.id, to: s.id, error: error.message });
    return null;
  }
  cancelIdleReap(donor);
  donor.client = null;
  bindClient(client, s);
  return client;
}

async function ensureClient(s) {
  cancelIdleReap(s);
  if (s.client?.child && s.client.child.exitCode == null) return s.client;
  fs.mkdirSync(SESSION_DIR, { recursive: true });
  const reused = await adoptIdleClient(s);
  const client = reused || (() => {
    const args = ['--mode', 'rpc', '--cwd', s.cwd, '--session-dir', SESSION_DIR, '--approval-mode', approvalMode(s.permissionMode), '--no-title'];
    if (s.sessionFile && fs.existsSync(s.sessionFile)) args.push('--resume', s.sessionFile);
    const c = new OmpRpcClient({ command: ompExecutable(), args, cwd: s.cwd, requestTimeoutMs: 30_000 });
    bindClient(c, s);
    return c;
  })();
  ensureOmpWatchdog();
  try {
    if (!reused) await client.start();
    const { provider, modelId } = modelParts(s.model);
    await client.request('set_model', { provider, modelId });
    if (s.effort) await client.request('set_thinking_level', { level: s.effort });
    await client.request('set_subagent_subscription', { level: 'progress' }).catch(() => {});
    await hydrateMessages(s);
    await refreshState(s);
    return client;
  } catch (error) {
    s.client = null; await client.close().catch(() => {}); throw error;
  }
}

/**
 * Turun SON asistan mesajindaki saglayici hatasini satir olarak ekler.
 *
 * Neden `hydrateMessages` degil: o butun transkripti satirlara cevirip
 * `s.messages`i KOMPLE degistiriyor. Tur bitisinde tek ihtiyacimiz son mesajin
 * hata tasiyip tasimadigi; canli turda birikmis satirlari yeniden uretmek hem
 * gereksiz hem riskli (acik arac kartlari, thoughtIndex eslesmesi).
 *
 * Hata satiri zaten en altta duruyorsa tekrar eklenmez: `agent_end` iki kez
 * gelebiliyor ve ayni uyari alt alta iki kez yazilirdi.
 */
async function reportTurnError(s) {
  if (!s.client || s.status === 'running') return;
  try {
    const data = await s.client.request('get_messages');
    const list = Array.isArray(data?.messages) ? data.messages : [];
    let son = null;
    for (let i = list.length - 1; i >= 0; i--) {
      if (list[i]?.role === 'assistant') { son = list[i]; break; }
    }
    if (son?.stopReason !== 'error') return;
    const metin = String(son.errorMessage || son.error || '').trim();
    if (!metin) return;
    const satir = `⚠️ ${metin}`;
    if (s.messages.length && s.messages[s.messages.length - 1]?.text === satir) return;
    s.messages.push({ role: 'agent', text: satir, time: hhmm() });
    pushNow(s); persistSessions();
  } catch {}
}

async function hydrateMessages(s) {
  if (!s.client || s.status === 'running') return;
  try {
    const data = await s.client.request('get_messages');
    const { rows, details } = transcriptRows(data.messages);
    // Satırlar ve detaylar birlikte değişmeli: thoughtIndex detay dizisine
    // indeksle bağlı, yalnız biri yenilenirse kartlar yanlış içeriği açar.
    if (rows.length) { s.messages = rows; s.toolDetails = details; finishOpenRows(s); }
    push(s);
  } catch {}
}

function mimeFor(file) {
  switch (path.extname(file).toLowerCase()) {
    case '.png': return 'image/png'; case '.webp': return 'image/webp'; case '.gif': return 'image/gif'; default: return 'image/jpeg';
  }
}
function rpcImages(images) {
  const out = [];
  for (const item of Array.isArray(images) ? images : []) {
    if (item?.data && item?.mimeType) { out.push({ type: 'image', data: item.data, mimeType: item.mimeType, detail: item.detail }); continue; }
    const file = typeof item === 'string' ? item : item?.path;
    if (!file || !fs.existsSync(file)) continue;
    out.push({ type: 'image', data: fs.readFileSync(file).toString('base64'), mimeType: mimeFor(file) });
  }
  return out;
}

export function defaultModel() { return DEFAULT_MODEL; }
export function newSession({ cwd, model, permissionMode, effort } = {}) {
  const dir = String(cwd || os.homedir()).trim();
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return { ok: false, error: 'cwd not a directory: ' + dir };
  const id = randomUUID(); const s = freshSession(id, dir, model, permissionMode, effort); sessions.set(id, s); persistSessions();
  return { ok: true, sessionId: id, cwd: dir, model: s.model, permissionMode: s.permissionMode, effort: s.effort };
}

export async function prompt({ sessionId, text, model, permissionMode, images, variant } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!String(text || '').trim() && !(images?.length)) return { ok: false, error: 'text or image required' };
  if (s.status === 'running') return { ok: false, error: 'busy' };
  const variantProvided = variant !== undefined;
  if (model) s.model = normalizeModel(model); if (permissionMode) s.permissionMode = normalizePermission(permissionMode); if (variantProvided) s.effort = normalizeEffort(variant);
  s.messages.push({ role: 'user', text: String(text || ''), time: hhmm() }); s.lastUserAt = Date.now(); s.status = 'running'; s.awaitingFirstOutput = true;
  // Nöbetçi penceresi prompt anından başlasın: ilk frame gecikirse bile atıllık
  // ölçümü doğru referanstan sayılır.
  s._lastActivity = Date.now(); s._staleProbes = 0; push(s);
  try {
    const previousClient = s.client?.child?.exitCode == null ? s.client : null;
    const client = await ensureClient(s);
    // Çalışan child'da prompt payload'ındaki yeni variantı da uygula. Boş level
    // OMP'de model varsayılanına dönme komutudur.
    if (variantProvided && client === previousClient) await client.request('set_thinking_level', { level: s.effort });
    const data = await client.request('prompt', { message: String(text || ''), images: rpcImages(images) });
    if (data?.agentInvoked === false) finishTurn(s);
    persistSessions(); return { ok: true, sessionId: s.id };
  } catch (error) { finishTurn(s, error.message); return { ok: false, error: error.message }; }
}

// Tur SÜRERKEN mesaj gönderme. OMP'nin belgesiz ama çalışan iki RPC metodu
// (13.08.2026'da yoklanarak bulundu ve canlı turda ölçüldü):
//   steer     → süren turu KESER; model o an yaptığını bırakıp yeni yönergeye uyar
//               (ölçüm: sayma turu ortasında durdu, yönlendirmeye cevap verdi).
//   follow_up → süren tur bitene kadar bekler, hemen ardından yeni tur olarak
//               çalışır (ölçüm: turn_end→turn_start zinciri, agent_end en sonda).
// İkisi de mesajı OMP transkriptine `user` olarak yazar; ama bize user FRAME'İ
// GELMEZ (reducer yalnız asistan olaylarını satıra çeviriyor, kullanıcı satırını
// prompt() yerel olarak ekliyor). Bu yüzden satırı burada da elle eklemek
// ZORUNLU — yoksa gönderdiğin metin sohbette hiç görünmez.
async function sendDuringTurn(method, { sessionId, text } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const message = String(text || '').trim();
  if (!message) return { ok: false, error: 'text required' };
  if (s.status !== 'running') return { ok: false, error: 'tur sürmüyor; normal prompt kullanın' };
  if (!s.client?.child || s.client.child.exitCode != null) return { ok: false, error: 'canlı OMP süreci yok' };
  try {
    await s.client.request(method, { message });
    s.messages.push({ role: 'user', text: message, time: hhmm() });
    s.lastUserAt = Date.now();
    // Nöbetçi penceresini de tazele: kullanıcı müdahalesi aktivitedir.
    s._lastActivity = Date.now(); s._staleProbes = 0;
    pushNow(s); persistSessions();
    return { ok: true, sessionId: s.id };
  } catch (error) { return { ok: false, error: error.message }; }
}

export function steer(args) { return sendDuringTurn('steer', args); }
export function followUp(args) { return sendDuringTurn('follow_up', args); }

export function getConversation(sessionId, opts = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { messages: [], running: false, awaitingApproval: false, awaitingFirstOutput: false, contextTokens: 0, contextWindow: 0 };
  return { ...snapshot(s), messages: paginateSessionMessages(s, opts) };
}
export function listSessions() {
  // Kullanıcı başlığı ve sabitleme CANLI listede de görünmeli: sekme adı ve
  // birleşik liste buradan besleniyor, yalnız disk kaydına yazmak yeniden
  // adlandırmayı açık sekmede etkisiz bırakırdı.
  const meta = readMeta();
  return [...sessions.values()].map(s => {
    const m = meta[s.ompSessionId] || {};
    return {
      id: s.id, cwd: s.cwd, model: s.model, status: s.status, diskId: s.ompSessionId,
      title: m.title || (s.messages.find(x => x.role === 'user')?.text || '').replace(/\s+/g, ' ').slice(0, 80),
      lastUserAt: s.lastUserAt || 0, turns: s.messages.filter(x => x.role === 'user').length,
      lastText: (s.messages.at(-1)?.text || '').slice(0, 80), awaitingApproval: !!s.pendingApproval,
      pinned: !!m.pinned, archived: !!m.archived,
    };
  });
}
export function runningSessionId() { return [...sessions.values()].find(s => s.status === 'running')?.id || null; }
export function subscribe(sessionId, ws, opts = {}) {
  const s = resolveSession(sessionId);
  if (!s) { try { ws.send(JSON.stringify({ type: 'error', error: 'session not found' })); ws.close(); } catch {} return; }
  // Protokol (ilk snapshot / delta replay / abone kaydı) sessionCore'da; burada
  // yalnız omp'ye özgü kısım kalır: atıl-çocuk toplayıcısı ve gerekirse child'ı
  // diriltme. opts artık YUTULMUYOR — delta=1 isteyen istemci delta alır.
  sessionCore.subscribe(s, ws, opts);
  cancelIdleReap(s);
  const drop = () => { scheduleIdleReap(s); };
  ws.on('close', drop); ws.on('error', drop);
  if (!s.client && s.sessionFile) void ensureClient(s).catch(error => finishTurn(s, error.message));
}
export async function stop(sessionId) {
  const s = resolveSession(sessionId); if (!s) return { ok: false, error: 'not found' };
  if (s.pendingApproval) approve({ sessionId, allow: false });
  try { await s.client?.abort(); } catch {}
  if (s.status === 'running') finishTurn(s); return { ok: true };
}
export function getPendingApproval() {
  const s = [...sessions.values()].find(v => v.pendingApproval);
  return s ? { backend: 'omp', sessionId: s.id, requestId: s.pendingApproval.requestId ?? null, summary: s.pendingApproval.summary || 'OMP yanıt bekliyor' } : null;
}
export function approve({ sessionId, allow = false, answers = [], requestId: sentRequestId } = {}) {
  const s = resolveSession(sessionId); if (!s?.pendingApproval || !s.client) return { ok: false, error: 'pending approval not found' };
  if (isStaleApproval(sentRequestId, s.pendingApproval.requestId)) return { ok: false, error: 'stale approval', stale: true };
  const pending = s.pendingApproval; const answer = answers[0]?.label || answers[0]?.optionId || '';
  const response = pending.method === 'confirm'
    ? { type: 'extension_ui_response', id: pending.requestId, confirmed: !!allow }
    : allow ? { type: 'extension_ui_response', id: pending.requestId, value: String(answer) }
      : { type: 'extension_ui_response', id: pending.requestId, cancelled: true };
  try { s.client.send(response); s.pendingApproval = null; push(s); return { ok: true }; }
  catch (error) { return { ok: false, error: error.message }; }
}
export async function setModel({ sessionId, model } = {}) {
  const s = resolveSession(sessionId); if (!s) return { ok: false, error: 'session not found' };
  s.model = normalizeModel(model); const { provider, modelId } = modelParts(s.model);
  try { if (s.client) await s.client.request('set_model', { provider, modelId }); persistSessions(); push(s); return { ok: true, model: s.model }; }
  catch (error) { return { ok: false, error: error.message }; }
}
export async function setEffort({ sessionId, effort } = {}) {
  const s = resolveSession(sessionId); if (!s) return { ok: false, error: 'session not found' };
  s.effort = normalizeEffort(effort);
  // OMP boş level'i kabul edip model varsayılanına döner. İsteği boşken
  // atlamak, UI "varsayılan" gösterirken çalışan child'ın eski high/max
  // seviyesinde kalmasına yol açıyordu.
  try { if (s.client) await s.client.request('set_thinking_level', { level: s.effort }); persistSessions(); push(s); return { ok: true, effort: s.effort }; }
  catch (error) { return { ok: false, error: error.message }; }
}
export async function setPermissionMode({ sessionId, permissionMode, mode } = {}) {
  const s = resolveSession(sessionId); if (!s) return { ok: false, error: 'session not found' };
  const value = normalizePermission(permissionMode || mode); s.permissionMode = value;
  // Approval mode is a process option. Idle child is respawned; a running turn defers it.
  if (s.client && s.status !== 'running') { const client = s.client; s.client = null; await client.close().catch(() => {}); }
  else if (s.client) s._pendingPermissionRespawn = true;
  persistSessions(); push(s); return { ok: true, permissionMode: value, applied: s.status === 'running' ? 'deferred' : 'next prompt' };
}
export async function compact({ sessionId } = {}) {
  const s = resolveSession(sessionId); if (!s) return { ok: false, error: 'session not found' };
  try { const client = await ensureClient(s); await client.request('compact'); await refreshState(s); return { ok: true }; }
  catch (error) { return { ok: false, error: error.message }; }
}
export function getThought(sessionId, index) { const s = resolveSession(sessionId); return { text: s?.toolDetails?.[index] || '' }; }

// Köprü OMP'yi `--session-dir SESSION_DIR` ile açtığı için kendi başlattığı
// oturumlar ayrı bir dizine düşüyor. Kullanıcı `omp`yi terminalden açtığında
// ise oturum OMP'nin DOĞAL deposuna, proje adına göre alt klasöre yazılıyor
// (`sessions/-agtest/*.jsonl`) ve köprü onu hiç görmüyordu.
//
// Diğer backend'lerde böyle bir kör nokta yok, çünkü hepsi backend'in kendi
// dizinini tarıyor: codex ~/.codex/sessions, opencode ~/.local/share/opencode,
// claude ~/.claude. OMP tek istisnaydı — "bilgisayardan başlattığım oturum
// köprüde çıkmıyor" şikâyetinin sebebi buydu.
const NATIVE_SESSION_ROOT = path.join(OMP_ROOT, 'sessions');

function jsonlIn(dir) {
  try {
    return fs.readdirSync(dir, { withFileTypes: true })
      .filter(e => e.isFile() && e.name.endsWith('.jsonl'))
      .map(e => path.join(dir, e.name));
  } catch { return []; }
}

function diskFiles() {
  const files = jsonlIn(SESSION_DIR);
  // Doğal depo düz değil, proje başına bir alt klasör: tek seviye in.
  let projects = [];
  try {
    projects = fs.readdirSync(NATIVE_SESSION_ROOT, { withFileTypes: true })
      .filter(e => e.isDirectory())
      .map(e => path.join(NATIVE_SESSION_ROOT, e.name));
  } catch { /* doğal depo yoksa yalnız köprününki listelenir */ }
  for (const dir of projects) files.push(...jsonlIn(dir));
  return files;
}

// Dosya köprünün kendi deposunda mı, OMP'nin doğal deposunda mı?
// Doğal depodakiler köprü dışında başlatılmış oturumlardır; devralma yolu
// bunları canlı bir OMP süreci hâlâ yazıyor olabileceği için ayırt etmeli.
function isExternalFile(file) {
  return !path.resolve(file).startsWith(path.resolve(SESSION_DIR) + path.sep);
}
// OMP transkriptinin ilk satırı `{"type":"session"}` DEĞİL, yerinde güncellenen
// dolgulu bir `{"type":"title"}` satırı. Eski sürüm yalnız o satırı okuduğu için
// id dosya adından tahmin ediliyor, cwd her zaman ev dizinine düşüyor (oturumu
// devral yanlış klasörde açıyordu) ve başlık dosya adı olarak görünüyordu.
const DISK_SCAN_MAX_BYTES = 8 * 1024 * 1024;
function readHead(file, bytes) {
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(bytes);
    const read = fs.readSync(fd, buf, 0, bytes, 0);
    return buf.subarray(0, read).toString('utf8');
  } finally { fs.closeSync(fd); }
}
function textOf(message) {
  const parts = Array.isArray(message?.content) ? message.content : [];
  return parts.filter(p => p?.type === 'text').map(p => String(p.text || '')).join(' ').replace(/\s+/g, ' ').trim();
}
function diskRecord(file) {
  try {
    const st = fs.statSync(file);
    // Devasa transkriptte tüm dosyayı ayrıştırmak liste açılışını kilitler;
    // o durumda baştan okunabildiği kadarıyla yetin ve tur sayısı verme.
    const truncated = st.size > DISK_SCAN_MAX_BYTES;
    const raw = truncated ? readHead(file, DISK_SCAN_MAX_BYTES) : fs.readFileSync(file, 'utf8');
    let id = '', cwd = '', title = '', model = '', firstUser = '', lastAgent = '', turns = 0;
    for (const line of raw.split(/\r?\n/)) {
      if (!line.trim()) continue;
      let row; try { row = JSON.parse(line); } catch { continue; }
      if (row.type === 'title' && !title) title = String(row.title || '').trim();
      else if (row.type === 'session') { id = String(row.id || id); cwd = String(row.cwd || cwd); }
      else if (row.type === 'model_change' && row.model) model = String(row.model);
      else if (row.type === 'message') {
        const role = row.message?.role;
        if (role === 'user') { turns++; if (!firstUser) firstUser = textOf(row.message); }
        else if (role === 'assistant') { const t = textOf(row.message); if (t) lastAgent = t; }
      }
    }
    if (!id) id = String(path.basename(file, '.jsonl').split('_').at(-1));
    return {
      id,
      cwd: cwd || os.homedir(),
      // Köprü OMP'yi --no-title ile açıyor, yani başlık genelde boş: ilk kullanıcı
      // mesajı canlı oturum listesindekiyle aynı başlığı verir.
      title: title || firstUser.slice(0, 80) || path.basename(file, '.jsonl'),
      lastText: lastAgent.slice(0, 200),
      turns: truncated ? 0 : turns,
      updatedAt: Math.round(st.mtimeMs), mtime: Math.round(st.mtimeMs), source: 'omp', path: file, model,
      // Köprü dışında (terminalden) başlatılmış oturum. Listede görünür; ama
      // devralma bunu bilerek karar vermeli — dosyayı hâlâ canlı bir OMP
      // süreci yazıyor olabilir ve aynı dosyaya iki süreç yazmak transkripti
      // bozar.
      external: isExternalFile(file),
    };
  } catch { return null; }
}
function readMeta() {
  try { const v = JSON.parse(fs.readFileSync(META_FILE, 'utf8')); return v && typeof v === 'object' ? v : {}; }
  catch { return {}; }
}
function writeMeta(data) {
  try {
    fs.mkdirSync(OMP_ROOT, { recursive: true });
    fs.writeFileSync(META_FILE, JSON.stringify(data, null, 2), 'utf8');
    return true;
  } catch (error) { logWarn('omp', 'oturum meta yazılamadı', { error: error.message }); return false; }
}

// İstemci elindeki kimlikle gelir ve bu kimlik CANLI KABUK uuid'si olabilir
// (liste satırı canlıysa `id` odur). Meta kalıcı disk kimliğiyle anahtarlı;
// çevirmezsek sabitleme oturum kapanınca kaybolur.
function metaKeyFor(id) {
  const key = String(id || '').trim();
  if (!key) return '';
  const live = sessions.get(key) || shellsForTranscript(key)[0];
  if (live?.ompSessionId) return live.ompSessionId;
  const record = diskRecords({ withMeta: false }).find(r => r.id === key || r.path === key || path.basename(r.path) === key);
  return record?.id || key;
}

function applyMeta(record, meta) {
  const m = meta[record.id];
  if (!m) return { ...record, pinned: false, archived: false };
  return {
    ...record,
    title: m.title || record.title,
    pinned: !!m.pinned,
    archived: !!m.archived,
  };
}

function diskRecords({ withMeta = true } = {}) {
  const rows = diskFiles().map(diskRecord).filter(Boolean);
  if (!withMeta) return rows.sort((a, b) => b.updatedAt - a.updatedAt);
  const meta = readMeta();
  return rows.map(r => applyMeta(r, meta)).sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    return b.updatedAt - a.updatedAt;
  });
}

function mutateMeta(id, patch) {
  const key = metaKeyFor(id);
  if (!key) return { ok: false, error: 'id required' };
  const meta = readMeta();
  meta[key] = { ...(meta[key] || {}), ...patch };
  // Boşalan kaydı bırakma: dosya zamanla ölü anahtarlarla şişer.
  if (!meta[key].title && !meta[key].pinned && !meta[key].archived) delete meta[key];
  if (!writeMeta(meta)) return { ok: false, error: 'metadata write failed' };
  return { ok: true, key };
}

export function pinThread({ id } = {}) {
  const r = mutateMeta(id, { pinned: true });
  return r.ok ? { ok: true, pinned: true } : r;
}
export function unpinThread({ id } = {}) {
  const r = mutateMeta(id, { pinned: false });
  return r.ok ? { ok: true, pinned: false } : r;
}
export function archiveThread({ id } = {}) {
  const r = mutateMeta(id, { archived: true });
  return r.ok ? { ok: true, archived: true } : r;
}
export function unarchiveThread({ id } = {}) {
  const r = mutateMeta(id, { archived: false });
  return r.ok ? { ok: true, archived: false } : r;
}
export function renameThread({ id, title } = {}) {
  const clean = String(title || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  // Boş başlık = adlandırmayı kaldır; kayıt OMP'nin kendi başlığına döner.
  const r = mutateMeta(id, { title: clean || undefined });
  if (!r.ok) return r;
  if (!clean) {
    const meta = readMeta();
    if (meta[r.key]) { delete meta[r.key].title; if (!Object.keys(meta[r.key]).some(k => meta[r.key][k])) delete meta[r.key]; writeMeta(meta); }
  }
  return { ok: true, title: clean };
}

// Aktif dalın user düğümleri, kök→uç sırasıyla. OMP transkripti id/parentId
// AĞACI ve resume dosyanın son id'li kaydından köke yürüyor; buradaki kural
// birebir aynı olmalı ki "sondan N'inci tur" child'ın gördüğü geçmişle eşleşsin
// (bayat dala saymak yanlış turu çatallar — 13.08 onarımında 5 yapraklı dosya
// canlıda görüldü).
function activeChainUserNodes(file) {
  const byId = new Map(); let leaf = null;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    let r; try { r = JSON.parse(line); } catch { continue; }
    if (r.id) { byId.set(r.id, r); leaf = r; }
  }
  const chain = [];
  for (let cur = leaf; cur; cur = cur.parentId ? byId.get(cur.parentId) : null) chain.push(cur);
  chain.reverse();
  return chain.filter(r => r.type === 'message' && r.message?.role === 'user');
}

// Buradan çatalla — claude/codex `/fork-from` sözleşmesinin OMP karşılığı.
// OMP'nin RPC'sinde BELGESİZ ama çalışan bir `branch` metodu var (13.08.2026'da
// yoklanarak bulundu): verilen user girdisinin ÖNCESİNE dallanır, yeni oturum
// dosyası açar, orijinale dokunmaz. branch sonrası child YENİ oturuma geçmiş
// olur; frame handler'ları eski kabuğun closure'ına bağlı olduğundan child
// taşınamaz — kapatılır ve yeni oturum adopt ile taze kabukta doğar. Orijinal
// kabuk clientsız kalır, sonraki kullanımda --resume ile kendi dosyasına döner.
export async function forkFromMessage({ sessionId, dropUserTurns } = {}) {
  const s = resolveSession(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (s.status === 'running') return { ok: false, error: 'tur sürerken çatallanamaz' };
  const drop = Number(dropUserTurns) || 0;
  if (drop < 1) return { ok: false, error: 'dropUserTurns >= 1 olmalı' };
  if (!s.sessionFile || !fs.existsSync(s.sessionFile)) return { ok: false, error: 'transkript dosyası yok' };
  let users;
  try { users = activeChainUserNodes(s.sessionFile); } catch (error) { return { ok: false, error: error.message }; }
  const target = users[users.length - drop];
  if (!target?.id) return { ok: false, error: 'geri dönülecek kullanıcı turu yok' };
  try {
    const client = await ensureClient(s);
    await client.request('branch', { entryId: target.id });
    const state = await client.request('get_state');
    const newId = String(state?.sessionId || '');
    s.client = null; await client.close().catch(() => {});
    if (!newId || newId === s.ompSessionId) return { ok: false, error: 'branch yeni oturum üretmedi' };
    const adopted = await adoptSession({ id: newId, cwd: s.cwd });
    return adopted.ok ? { ok: true, sessionId: adopted.sessionId } : adopted;
  } catch (error) { return { ok: false, error: error.message }; }
}

// Küresel arama (search.mjs) için içerik taraması. OMP'nin burada HİÇ
// karşılığı yoktu: globalSearch `searchDiskSessions` olmayan modülü sessizce
// atlıyor, OMP oturumları aramada hiç çıkmıyordu. Metin çıkarımı dosya+mtime
// ile önbellekli — claude/codex'teki desenle aynı.
const OMP_SEARCH_TEXT_CAP = 4_000;
const _searchTextCache = new Map(); // file -> { mtime, msgs }
function searchableTranscriptMessages(file) {
  let mtime = 0;
  try { mtime = fs.statSync(file).mtimeMs; } catch { return []; }
  const hit = _searchTextCache.get(file);
  if (hit && hit.mtime === mtime) return hit.msgs;
  const msgs = [];
  try {
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (!line.trim()) continue;
      let r; try { r = JSON.parse(line); } catch { continue; }
      if (r.type !== 'message') continue;
      const role = r.message?.role;
      if (role !== 'user' && role !== 'assistant') continue;
      const text = textOf(r.message);
      // matchTranscriptMessages yalnız user/agent rollerini tarar.
      if (text) msgs.push({ role: role === 'assistant' ? 'agent' : 'user', text: text.slice(0, OMP_SEARCH_TEXT_CAP) });
    }
  } catch { /* okunamayan transkript boş girdiyle geçilir */ }
  _searchTextCache.set(file, { mtime, msgs });
  if (_searchTextCache.size > 300) {
    for (const key of _searchTextCache.keys()) {
      if (_searchTextCache.size <= 200) break;
      _searchTextCache.delete(key);
    }
  }
  return msgs;
}

export function searchDiskSessions({ query, cwd, limit = 40, deadline = 0 } = {}) {
  const q = (query || '').toLowerCase().trim();
  if (q.length < 2) return [];
  const results = [];
  let kesildi = false;
  for (const s of diskRecords()) {
    // Süre bütçesi (bkz. search.mjs): senkron fonksiyon, durma kararı burada.
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
    for (const m of matchTranscriptMessages(searchableTranscriptMessages(s.path), q)) {
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

export async function listDiskSessionsWithQuery({ query = '', archived = null, pinned = null } = {}) {
  let rows = diskRecords();
  const q = String(query || '').trim().toLowerCase();
  if (q) rows = rows.filter(r => `${r.title} ${r.lastText}`.toLowerCase().includes(q));
  if (archived === true) rows = rows.filter(r => r.archived);
  else if (archived === false) rows = rows.filter(r => !r.archived);
  if (pinned === true) rows = rows.filter(r => r.pinned);
  else if (pinned === false) rows = rows.filter(r => !r.pinned);
  return { ok: true, sessions: rows };
}
// Sözleşme: tüm backend'lerin /disk-sessions ucu {ok, sessions} döner. OMP çıplak
// dizi döndürdüğü için istemciler `sessions` alanını bulamıyor ve liste boş
// kalıyordu (Android: optJSONArray("sessions") ?: emptyList()).
// Arşivlenenler varsayılan listede GÖRÜNMEZ (claude-app/codex-app ile aynı);
// arşiv görünümü /disk-sessions-search?archived=true ile geliyor.
export async function listDiskSessions() {
  return { ok: true, sessions: diskRecords().filter(r => !r.archived) };
}
// Gelen kimlik ÜÇ şeyden biri olabilir: köprü kabuğunun uuid'si (yenilemede
// SessionRefreshDelegate `current.omp.sessionId` gönderiyor — eskiden yalnız
// disk kayıtlarına bakıldığı için bu her seferinde "session not found on disk"
// dönüyor, yani OMP'de yenile düğmesi hiç çalışmıyordu), OMP disk id'si ya da
// transkript yolu. Hepsi VAR OLAN kabuğa çözülür; yeni kabuk yalnız o sohbet
// köprüde hiç açılmamışsa doğar.
export async function adoptSession({ id, cwd } = {}) {
  const key = String(id || '').trim();
  if (!key) return { ok: false, error: 'id required' };
  const record = diskRecords().find(r => r.id === key || r.path === key || path.basename(r.path) === key);
  const found = resolveSession(record?.id || key) || resolveSession(key);
  const existing = found ? (dedupeTranscript(transcriptKey(found)) || found) : null;
  if (existing) {
    // Child yoksa burada doğsun: hydrateMessages transkripti diskten tazeler,
    // yenileme akışının tek işi bu.
    try { await ensureClient(existing); } catch (error) { return { ok: false, error: error.message }; }
    return { ok: true, sessionId: existing.id, cwd: existing.cwd, model: existing.model };
  }
  if (!record) return { ok: false, error: 'session not found on disk' };
  const result = newSession({ cwd: cwd || record.cwd, model: record.model || DEFAULT_MODEL, permissionMode: 'yolo' });
  if (!result.ok) return result; const s = sessions.get(result.sessionId); s.sessionFile = record.path; s.ompSessionId = record.id; persistSessions();
  try { await ensureClient(s); return { ok: true, sessionId: s.id, cwd: s.cwd, model: s.model }; }
  catch (error) { return { ok: false, error: error.message }; }
}
export async function deleteDiskSession({ id } = {}) {
  const record = diskRecords().find(r => r.id === id || r.path === id);
  if (!record) return { ok: false, error: 'session not found on disk' };
  // Aynı dosyaya bağlı canlı oturumu kapat ve bellekten düş — claude-app ve
  // codex-app aynısını yapıyor. Eskiden burası 'session is active' dönüyordu:
  // sekme açılmış her oturum silinemez hale geliyordu, çünkü çocuk süreç
  // oturum boyunca kapanmıyor.
  const live = [...sessions.values()].filter(s => s.sessionFile === record.path);
  if (live.some(s => s.status === 'running')) return { ok: false, error: 'session is running' };
  for (const s of live) {
    cancelIdleReap(s);
    const client = s.client; s.client = null;
    if (client) await client.close().catch(() => {});
    sessions.delete(s.id);
  }
  if (live.length) persistSessions();
  try {
    fs.unlinkSync(record.path);
    // Meta kaydını da düş, yoksa dosya silinen oturumların anahtarlarıyla şişer.
    const meta = readMeta();
    if (meta[record.id]) { delete meta[record.id]; writeMeta(meta); }
    return { ok: true };
  } catch (error) { return { ok: false, error: error.message }; }
}
// Efor kademeleri MODELE BAĞLI: deepseek-flash low/high/max, v4-pro high/max,
// glm-5.2 minimal→xhigh, qwen3-max hiç (reasoning yok). RPC bunu `thinking`
// altında veriyor — eski kod olmayan bir `thinkingLevels` alanını okuduğu için
// liste hep boş dönüyor, arayüz de 7 kademelik statik listeye düşüyordu.
// `off` katalogda yazmaz ama akıl yürüten her modelde geçerli (OMP'nin genel
// --thinking off'u; canlı testte deepseek'te çalıştı), o yüzden başa eklenir.
function modelThinking(m) {
  const t = m?.thinking;
  const efforts = t?.mode === 'effort' && Array.isArray(t.efforts) ? t.efforts.filter(Boolean) : [];
  if (!efforts.length) return { variants: [], defaultVariant: '' };
  const id = `${m.provider}/${m.id}`;
  return {
    variants: ['off', ...efforts],
    defaultVariant: normalizeEffort(t.defaultLevel) || resolvedEffortByModel.get(id) || '',
  };
}
// OpenCode Zen token başına ödenir, OpenCode Go düz aboneliktir. Kullanıcının
// kararı (16.08.2026): Go'dan HEPSİ gelsin, Zen'den yalnız BEDAVA olanlar —
// Zen'deki 62 modelin 55'i ücretli, hepsini almak hem çipi boğar hem Go
// aboneliğinde bedava olan işi yanlışlıkla paralıya yaptırır.
// DİKKAT: OMP'de sağlayıcı kimliği `opencode-zen`, opencode CLI'da yalın
// `opencode` (bkz. opencode-app.mjs ZEN_PROVIDER). Aynı sağlayıcı, farklı ad.
const OMP_ZEN_PROVIDER = 'opencode-zen';
// Zen'in bedava modelleri "-free" ekiyle biter; eksiz gelen gizli/rotasyon
// modelleri bu kümede tutulur (opencode-app.mjs ZEN_FREE_UNSUFFIXED ile aynı).
const OMP_ZEN_FREE_NAME_RE = /-free$/;
const OMP_ZEN_FREE_UNSUFFIXED = new Set(['big-pickle', 'grok-code']);
// İKİ İMZA BİRDEN aranır (fiyat 0 VE ad bedava diyor). Tek başına fiyat YETMEZ:
// OMP'nin kataloğu yeni eklenen modellere fiyat gelene kadar 0 yazıyor —
// 16.08.2026'da ölçüldü, `muse-spark-1.2` OMP'de 0 görünürken opencode'un
// (models.dev) kataloğunda 1,25/4,25 USD; Go tarafında da yeni `glm-5.3` ve
// `hy3-preview` 0 yazıyordu. Salt fiyata bakan bir süzgeç bu modelleri
// "bedava" sanıp listeye alır ve kullanıcıya para harcatır.
// Bedelini de biliyoruz: eki olmayan YENİ bir bedava model gözden kaçar.
// Ucuz taraf bu — kaçırılan model listeye girmez, yanlış giren para yakar.
// opencode-app.mjs'de bu ikinci imza aranmaz, çünkü oradaki fiyat kaydı
// canlı /config/providers'tan geliyor ve ölçümde doğru çıktı.
// NanoGPT BEYAZ liste. OMP, models.yml'de tek model tanımlı olsa bile
// sağlayıcının canlı /v1/models kataloğunun TAMAMINI birleştiriyor —
// 24.08.2026'da ölçüldü: nanogpt'den 881 model geldi, çip kullanılamaz oldu.
// Kullanıcının kararı (24.08): nanogpt'den YALNIZ bu model; diğerleri elendi.
// Yeni model eklerken önce neden elendiğini sor. opencode'da bu sorun yok —
// kürate liste.
const OMP_NANOGPT_PROVIDER = 'nanogpt';
const OMP_NANOGPT_ALLOWED = new Set(['qwen/qwen3.8-27b-uncensored']);
export function isAgentBridgeOmpModel(m) {
  if (!m) return false;
  if (m.provider === OMP_NANOGPT_PROVIDER) return OMP_NANOGPT_ALLOWED.has(String(m.id || ''));
  if (m.provider !== OMP_ZEN_PROVIDER) return true;
  const id = String(m.id || '');
  if (!OMP_ZEN_FREE_NAME_RE.test(id) && !OMP_ZEN_FREE_UNSUFFIXED.has(id)) return false;
  const cost = m.cost;
  // Fiyat alanı hiç yoksa ad kuralıyla yetin; varsa sıfır olmasını ŞART koş —
  // bugün bedava olan bir model yarın ücretlenirse ad değişmeden fiyat değişir.
  if (cost && (typeof cost.input === 'number' || typeof cost.output === 'number')) {
    return (cost.input || 0) === 0 && (cost.output || 0) === 0;
  }
  return true;
}
// Seçicide etiketin başına sağlayıcı yazılır. OMP'nin kendi adı sağlayıcıyı
// içermiyor ve katalogda AYNI ada sahip iki satır oluyor — "deepseek-flash"
// hem `deepseek/` hem `opencode-go/` altında var, ikisi ayrı hesaba yazıyor.
// Etiket tek başına hangisini seçtiğini söylemiyordu. Ayıraç `·`, köprünün
// başka yerinde de kullanılan biçim (bkz. opencode-app.mjs RunPod etiketi).
// Android tarafı ayrı bir alan taşımıyor (BackendModel yalnız label/id), o
// yüzden bilgi etikete gömülüyor — APK değişikliği gerekmiyor.
// Ad sağlayıcıyla başlasa bile ("deepseek · deepseek-flash") kısaltma YOK:
// ayrımın en çok gerektiği satır tam olarak o — ikizi `opencode-go · DeepSeek
// V4 Flash (2x usage)`. Tekrarı gürültü sanıp atmak satırı yine belirsiz yapar.
export function ompModelLabel(m) {
  const name = String(m?.name || m?.id || '').trim();
  const provider = String(m?.provider || '').trim();
  return provider ? `${provider} · ${name}` : name;
}
export async function getModels() {
  if (Date.now() - modelCache.at < 60_000) return modelCache.models;
  const client = new OmpRpcClient({ command: ompExecutable(), args: ['--mode', 'rpc', '--no-session', '--cwd', os.homedir()], cwd: os.homedir(), requestTimeoutMs: 20_000 });
  try {
    await client.start(); const data = await client.request('get_available_models');
    const models = (data.models || []).filter(isAgentBridgeOmpModel).map(m => ({
      id: `${m.provider}/${m.id}`, label: ompModelLabel(m), provider: m.provider,
      ...modelThinking(m),
    }));
    if (models.length) modelCache = { at: Date.now(), models };
    return modelCache.models;
  } catch (error) {
    logWarn('omp', 'model kataloğu alınamadı', { error: error.message });
    return modelCache.models;
  }
  finally { await client.close().catch(() => {}); }
}

async function readInventoryFromClient(client) {
  const [commands, state] = await Promise.all([
    client.request('get_available_commands'),
    client.request('get_state'),
  ]);
  const skills = skillInventoryFromCommands(commands);
  const mcpServers = mcpInventoryFromPrompt(state?.systemPrompt, discoverOmpMcpCandidates());
  return { ...skills, mcpServers };
}

// OMP'nin native RPC envanteri. Canlı çocuk varsa onu sorgular; yoksa oturum
// kaydetmeyen kısa ömürlü bir child açar. Böylece UI disk klasörlerini tahmin
// etmek yerine gerçekten keşfedilmiş skill komutlarını ve mount edilmiş MCP'leri
// gösterir.
export async function getInfo({ forceReload = false } = {}) {
  if (!forceReload && inventoryCache.value && Date.now() - inventoryCache.at < INVENTORY_CACHE_MS) {
    return inventoryCache.value;
  }
  const live = [...sessions.values()].find(s => s.client?.child && s.client.child.exitCode == null)?.client;
  let client = live;
  let owned = false;
  try {
    if (!client) {
      client = new OmpRpcClient({
        command: ompExecutable(),
        args: ['--mode', 'rpc', '--no-session', '--cwd', process.cwd()],
        cwd: process.cwd(),
        requestTimeoutMs: 20_000,
      });
      await client.start();
      owned = true;
    }
    const inventory = await readInventoryFromClient(client);
    const value = {
      ok: true,
      serveAlive: !!live,
      skills: inventory.skills,
      skillDetails: inventory.skillDetails,
      mcpServers: inventory.mcpServers,
    };
    inventoryCache = { at: Date.now(), value };
    return value;
  } finally {
    if (owned) await client?.close().catch(() => {});
  }
}

export async function listMcpServers() {
  const info = await getInfo();
  return { ok: true, servers: info.mcpServers || [] };
}

// /slash rotası için canlı katalog. Canlı çocuk varsa onu sorgular (get_* okuma
// komutları çalışan turu etkilemez); yoksa kısa ömürlü child açar. Hata halinde
// bayat cache döner — telefonda boş menüdense eski liste iyidir.
export async function getSlashCommands() {
  if (slashCache.commands.length && Date.now() - slashCache.at < SLASH_CACHE_MS) return slashCache.commands;
  const live = [...sessions.values()].find(s => s.client?.child && s.client.child.exitCode == null)?.client;
  let client = live;
  let owned = false;
  try {
    if (!client) {
      client = new OmpRpcClient({
        command: ompExecutable(),
        args: ['--mode', 'rpc', '--no-session', '--cwd', os.homedir()],
        cwd: os.homedir(),
        requestTimeoutMs: 20_000,
      });
      await client.start();
      owned = true;
    }
    const data = await client.request('get_available_commands');
    updateSlashCache(data?.commands);
    return slashCache.commands;
  } catch (error) {
    logWarn('omp', 'slash komut listesi alınamadı', { error: error.message });
    return slashCache.commands;
  } finally {
    if (owned) await client?.close().catch(() => {});
  }
}
export function listLiveProcesses() { return [...sessions.values()].filter(s => s.client?.child).map(s => ({ pid: s.client.child.pid, kind: 'omp-rpc', sessionId: s.id })); }
export function killAllSessions() {
  const children = [...sessions.values()].filter(s => s.client).map(s => s.client);
  for (const s of sessions.values()) { cancelIdleReap(s); s.client = null; s.status = 'idle'; s.pendingApproval = null; }
  void Promise.allSettled(children.map(c => c.close())); const cleared = sessions.size; sessions.clear(); persistSessions();
  return { ok: true, killed: children.map(c => c.child?.pid).filter(Boolean), cleared, errors: [] };
}

// Üretim API'si değildir; süreç spawn etmeden reducer/lifecycle regresyonlarını
// gerçek session şekli üzerinde test etmeye yarar.
export const __ompAppTest = {
  session(id) { return sessions.get(id); },
  onFrame,
  ompWatchdogTick,
  finalizeStalledTurn,
  addSession(s) { sessions.set(s.id, s); return s; },
  freshSession,
  removeSession(id) { sessions.delete(id); shellAliases.delete(id); },
  resolveSession,
  dedupeTranscript,
  slashFromRpcCommands,
  slashCache() { return slashCache; },
  aliasOf(id) { return shellAliases.get(id) || ''; },
  activeChainUserNodes,
  adoptIdleClient,
  ensureClient,
  bindClient,
  constants: { OMP_TURN_INACTIVITY_MS, OMP_PROBE_TIMEOUT_MS, OMP_MAX_PROBE_FAILURES },
};

restoreSessions();
