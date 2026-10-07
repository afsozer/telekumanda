// Telekumanda köprüsü — REST + WS API over Tailscale, CLI-agent orchestration.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { createRouter, json, body, rawBody, BodyTooLargeError, rejectTooLarge, setBodyValidator } from './router.mjs';
import { PATH_KEYS, UnsafePathError, assertSafePathFields, unsafePathReason } from './path-guard.mjs';
import { register as registerGeneral } from './routes/general.mjs';
import { registerBackend, reconcilePromptRequests, promptOriginModel } from './routes/backend.mjs';
import { warmOpenTabBackends } from './backend-warmth.mjs';
import { register as registerMcp } from './routes/mcp.mjs';
import { register as registerUpdate } from './routes/update.mjs';
import * as claudeApp from './claude-app.mjs';
import * as codexApp from './codex-app.mjs';
import * as cowork from './cowork.mjs';
// OpenCode v1 backend'i (opencode-app.mjs) 30.09.2026'da SÖKÜLDÜ: global
// `opencode` komutu artık v2 (@opencode/cli), v1 npm paketi kaldırıldı.
// Tek OpenCode girişi opencode2-app; telefonda etiketi "OpenCode".
import * as opencode2App from './opencode2-app.mjs';
import * as omp from './omp-app.mjs';
import { readUiPins, writeUiPinScope } from './ui-pins.mjs';
import * as agy from './agy.mjs';
import * as mcp from './mcp.mjs';
import * as claudeMcp from './claude-mcp.mjs';
import * as codexMcp from './codex-mcp.mjs';
import * as opencodeMcp from './opencode-mcp.mjs';
import { getExternalUsageGroups } from './usage.mjs';
import { ROTATION_DEFAULTS } from './log-rotation.mjs';
import { initLogger, rotate as rotateLogger } from './logger.mjs';
import { createOperationsTracker } from './operations.mjs';
import { createProjectsStore, archiveProjectOutputs, cleanupOutputsArchive } from './projects.mjs';
import { globalSearch } from './search.mjs';
import { createDeviceAuth } from './device-auth.mjs';
import { buildCatalog, verifyAdapter, POLL_APPROVAL_BACKENDS } from './backend-contract.mjs';
import { buildNotificationFeedState, notificationFeedChanged } from './notification-feed.mjs';
import { createNotificationDelivery } from './notification-delivery.mjs';
import { createScreenshotExtract, DEFAULT_SCREENSHOT_MODEL, EXTRACT_APP_DATA_DIR } from './screenshot-extract.mjs';
import { pruneAll } from './disk-prune.mjs';
import { pruneSessions } from './session-prune.mjs';
import { serveWebUi } from './web-ui.mjs';
import { createReminders } from './reminders.mjs';
import { createWsTickets } from './ws-tickets.mjs';
import { ensureDocumentToolPath } from './tool-path.mjs';
import { createRunPodManager } from './runpod.mjs';
import { createOturumImhaManager } from './oturum-imha.mjs';

// Winget PATH değişikliği mevcut terminale yansımamış olsa da provider child
// süreçleri PDF render/extract araçlarını görsün.
ensureDocumentToolPath();
const CFG_URL = new URL('./config.json', import.meta.url);
const cfg = JSON.parse(fs.readFileSync(CFG_URL));
// Boş ya da örnekteki yer tutucu token herkese tam erişim demek: boş token,
// boş bir `Bearer ` başlığıyla eşleşir. Köprü böyle bir ayarla açılmaz.
if (typeof cfg.authToken !== 'string' || cfg.authToken.trim().length < 24 || /^CHANGE-ME/i.test(cfg.authToken.trim())) {
  throw new Error('bridge/config.json: authToken en az 24 karakterlik, size özel rastgele bir dizgi olmalı (örnekteki CHANGE-ME değeri kullanılamaz)');
}
const LEGACY_TOKEN_DIGEST = crypto.createHash('sha256').update(cfg.authToken).digest();
const runpod = createRunPodManager(cfg.runpod || {});
// Silinmis opencode oturumlarinin kalintilarini imha eden betik. Yol
// yapilandirilabilir (cfg.oturumImha.script); yoksa varsayilan CoworkSpaces
// altindaki betik kullanilir, o da yoksa uc "disabled" doner.
const oturumImha = createOturumImhaManager(cfg.oturumImha || {});
const PROTOCOL_VERSION = 2;

// ── Logger (madde 14) ──────────────────────────────────────────────────────
// console.log/warn/error'u bridge.log'a (append, sync) yönlendir. Diğer
// modüller import edilmeden ÖNCE çağrılmalı ki tüm runtime console çıktısı
// yakalansın (top-level console çağrısı yoktur; güvenli). Eskiden bu çıktıyı
// run-bridge.cmd'deki `>> bridge.log 2>&1` CMD redirect'i yakalıyordu ama o
// redirect dosyaya paylaşımsız HANDLE tuttuğu için rotasyon imkânsızdı; artık
// run-bridge.cmd çıktıyı bootstrap.log'a (erken hatalar için) yönlendiriyor.
const BRIDGE_DIR = path.dirname(fileURLToPath(import.meta.url));
initLogger({
  filePath: path.join(BRIDGE_DIR, 'bridge.log'),
  maxBytes: ROTATION_DEFAULTS.maxBytes,
  maxGenerations: ROTATION_DEFAULTS.maxGenerations,
});

// ── Global güvenlik ağı ────────────────────────────────────────────────────
// Node 22 varsayılanı: yakalanmamış promise rejection süreci öldürür. Backend
// modüllerinde await'siz appSend/http çağrıları (opencode-app, vb.)
// kaçınabilir; tek bir rejection tüm bridge'i (ve tüm oturumların turn'lerini)
// çökertmesin diye burada loglayıp yutuyoruz. uncaughtException için aynı şey:
// supervisor'a bırakmaktansa logla ve devam et (kritik hatalar zaten yakalanır).
process.on('unhandledRejection', (reason, p) => {
  const msg = reason && reason.stack ? reason.stack.split('\n')[0] : String(reason);
  try { console.warn('[server] unhandledRejection yutuldu:', msg); } catch {}
});
process.on('uncaughtException', (err) => {
  const msg = err && err.stack ? err.stack.split('\n')[0] : String(err);
  try { console.warn('[server] uncaughtException yakalandı (devam ediliyor):', msg); } catch {}
});

// ── Çıkış adli kaydı ───────────────────────────────────────────────────────
// 01-02.08.2026: bridge günde birkaç kez code 1 ile, log'a tek satır düşmeden
// öldü. Yukarıdaki ağlar JS hatalarını yuttuğuna göre sessiz ölüm ya içeriden
// process.exit (tek aday: /bridge/restart) ya da dışarıdan taskkill'dir.
// Bu blok içeriden çıkışı ve sinyalleri loglar; bir SONRAKİ ölümde bridge.log'da
// "process exit" satırı varsa iç, hiç satır yoksa dış kill (taskkill /F sinyalsiz
// öldürür, 'exit' event'i çalışmaz) olduğu anlaşılır. Logger sync yazdığı için
// exit handler'daki satır diske iner.
process.on('exit', (code) => {
  try { console.warn(`[server] process exit, code=${code} (içeriden çıkış — dış kill değil)`); } catch {}
});
for (const sig of ['SIGINT', 'SIGTERM', 'SIGBREAK', 'SIGHUP']) {
  try {
    process.on(sig, () => {
      try { console.warn(`[server] ${sig} alındı, çıkılıyor`); } catch {}
      process.exit(1);
    });
  } catch {}
}

export const SLASH = {
  agy: [
    { name: 'goal', desc: 'Hedefe ulaşana kadar çalış' },
    { name: 'review', desc: 'Kod incelemesi yap' },
    { name: 'think', desc: 'Adım adım düşünerek plan yap' },
    { name: 'compact', desc: 'Bağlamı sıkıştır, token tasarrufu yap' },
    { name: 'plan', desc: 'Önce plan yap, sonra uygula' },
  ],
  'claude-app': [
    { name: 'bro', desc: 'Son cevabı jargonsuz, sade anlat' }, // claude-app.mjs PROMPT_MACROS ile eş
    { name: 'devret', desc: 'İşi dış ajana worktree içinde yaptır, diff olarak getir' },
    { name: 'clear', desc: 'Bağlam penceresini temizle' },
    { name: 'compact', desc: 'Bağlamı sıkıştır, token tasarrufu yap' },
    { name: 'review', desc: 'Kod incelemesi yap' },
    { name: 'think', desc: 'Adım adım düşün, ardından cevap ver' },
    { name: 'ultrathink', desc: 'Derin analiz ve planlama yap' },
  ],
  'codex-app': [
    { name: 'Bu kodu detaylı incele; hataları, güvenlik açıklarını ve iyileştirme önerilerini sırala.', desc: 'Kod incelemesi' },
    { name: 'Testleri çalıştır ve başarısız olanları raporla.', desc: 'Test raporu' },
    { name: 'Commit mesajı yaz: son değişiklikleri özetleyen conventional commit formatında.', desc: 'Commit mesajı' },
    { name: 'Bu kodu refactor et; okunabilirliği ve bakım kolaylığını artır.', desc: 'Refactor' },
    { name: 'Proje bağımlılıklarını güncelle ve breaking change varsa bildir.', desc: 'Bağımlılık güncelle' },
    { name: '/compact', desc: 'Bağlamı sıkıştır, token tasarrufu yap' },
  ],
  'opencode2-app': [
    { name: 'Bu kodu detaylı incele; hataları, güvenlik açıklarını ve iyileştirme önerilerini sırala.', desc: 'Kod incelemesi' },
    { name: 'Adım adım düşünerek bir plan yap, sonra uygula.', desc: 'Planla ve uygula' },
    { name: 'Değişiklikleri özetle (git diff).', desc: 'Diff özeti' },
    { name: 'Bu projenin mimarisini analiz et ve açıkla.', desc: 'Proje analizi' },
    { name: 'Commit mesajı yaz: son değişiklikleri özetleyen conventional commit formatında.', desc: 'Commit mesajı' },
  ],
};

const deviceAuth = createDeviceAuth();
setBodyValidator(assertSafePathFields);
const wsTickets = createWsTickets();

// Token yalnız `Authorization: Bearer` başlığından okunur. Adrese (`?token=`)
// konan token proxy, tarayıcı geçmişi ve günlüklerde açıkta kalıyordu; tarayıcı
// WebSocket'i başlık gönderemediği için onun yolu tek kullanımlık bilet
// (`POST /ws-ticket`, bkz. ws-tickets.mjs).
function requestToken(req) {
  const h = req.headers['authorization'] || '';
  return h.startsWith('Bearer ') ? h.slice(7) : null;
}

function authIdentity(req) {
  if (req.wsIdentity) return req.wsIdentity;
  const token = requestToken(req);
  if (token && crypto.timingSafeEqual(crypto.createHash('sha256').update(token).digest(), LEGACY_TOKEN_DIGEST)) return { kind: 'legacy', id: 'legacy' };
  const device = token ? deviceAuth.authenticate(token) : null;
  return device ? { kind: 'device', ...device } : null;
}

function auth(req) {
  return !!authIdentity(req);
}

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MAX_FILE = 200000;
const WS_ROOTS = (cfg.workspaceRoots && cfg.workspaceRoots.length) ? cfg.workspaceRoots : [os.homedir()];
const SKIP_DIRS = new Set(['node_modules', '.git', '.gradle', 'build', '.idea', 'dist', 'out', '.next']);

// Optional config-supplied basename -> absolute path map. When present, a /file request
// whose basename matches an entry here resolves instantly without a recursive walk.
// Configure in bridge/config.json as:  "fileAliases": { "foo.txt": "C:\\path\\to\\foo.txt" }
const FILE_ALIASES = (cfg.fileAliases && typeof cfg.fileAliases === 'object') ? cfg.fileAliases : {};
// Cap the fallback recursive walk so a workspace with many files cannot block the event
// loop for long: stop after visiting this many directory entries total.
const FIND_BASENAME_MAX_ENTRIES = 20000;

// Bounded recursive basename lookup. Visits at most FIND_BASENAME_MAX_ENTRIES directory
// entries across the whole walk, then gives up (returns not-found). Skips SKIP_DIRS.
function findByBasename(root, base, depth, acc) {
  if (depth < 0 || acc.found || acc.visited > FIND_BASENAME_MAX_ENTRIES) return;
  let entries; try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (acc.found || acc.visited > FIND_BASENAME_MAX_ENTRIES) return;
    acc.visited++;
    const full = path.join(root, e.name);
    if (e.isDirectory()) { if (!SKIP_DIRS.has(e.name)) findByBasename(full, base, depth - 1, acc); }
    else if (e.name.toLowerCase() === base.toLowerCase()) { acc.found = full; return; }
  }
}

// Resolve a workspace path (agfile://, absolute, relative, basename) to an absolute file
// path. Shared by /file (text preview) and /download (binary stream). Returns
// { ok, target, name, error }. On ok:true target is the absolute path and name the basename.
function resolveWorkspacePath(p) {
  try {
    if (!p) return { ok: false, error: 'path required' };
    // Strip the agfile:// scheme AND any leading slashes that follow it, so an
    // agfile:///C:/Users/... URL resolves to the absolute path C:/Users/... directly
    // (fast path 2) instead of falling through to the expensive basename walk.
    const cleaned = decodeURIComponent(p).replace(/^agfile:\/+/, '').replace(/^\/+/, '').trim();
    let target = null;
    // Fast path 1: config-supplied basename alias (skip the walk entirely).
    if (!target) {
      const base = path.basename(cleaned).toLowerCase();
      const alias = FILE_ALIASES[base] || FILE_ALIASES[path.basename(cleaned)];
      if (alias && fs.existsSync(alias)) target = alias;
    }
    // Fast path 2: absolute Windows path or UNC path.
    if (!target && (/^[a-zA-Z]:[\\/]/.test(cleaned) || cleaned.startsWith('\\\\'))) {
      if (fs.existsSync(cleaned)) target = cleaned;
    }
    // Fast path 3: path relative to a workspace root.
    if (!target) {
      for (const root of WS_ROOTS) { const c = path.join(root, cleaned); if (fs.existsSync(c) && fs.statSync(c).isFile()) { target = c; break; } }
    }
    // Last resort: bounded recursive basename walk across workspace roots.
    if (!target) {
      const base = path.basename(cleaned);
      for (const root of WS_ROOTS) { const acc = { found: null, visited: 0 }; findByBasename(root, base, 4, acc); if (acc.found) { target = acc.found; break; } }
    }
    if (!target) return { ok: false, error: 'not found', name: path.basename(cleaned) };
    return { ok: true, target, name: path.basename(target) };
  } catch (e) { return { ok: false, error: String(e.message || e) }; }
}

function readWorkspaceFile(p) {
  try {
    const resolved = resolveWorkspacePath(p);
    if (!resolved.ok) return resolved.error === 'path required' ? { ok: false, error: 'path required' } : { ok: false, error: resolved.error, name: resolved.name || '' };
    const stat = fs.statSync(resolved.target);
    const buf = fs.readFileSync(resolved.target);
    const truncated = buf.length > MAX_FILE;
    return {
      ok: true,
      name: resolved.name,
      path: resolved.target,
      size: stat.size,
      mtime: stat.mtimeMs,
      hash: crypto.createHash('sha256').update(buf).digest('hex'),
      truncated,
      content: buf.slice(0, MAX_FILE).toString('utf-8'),
    };
  } catch (e) { return { ok: false, error: String(e.message || e) }; }
}

// ── Active state helper ───────────────────────────────────────────────────
const CLI_MODULES = {
  agy,
  'claude-app': claudeApp,
  'codex-app': codexApp,
  'opencode2-app': opencode2App,
  'omp': omp,
};
const MODULE_LABELS = {
  agy: 'Antigravity CLI',
  'claude-app': 'Claude App',
  'codex-app': 'Codex App',
  // v1 söküldükten sonra tek OpenCode girişi bu: etiket "OpenCode 2" DEĞİL
  // "OpenCode" (kullanıcı artık iki dünya görmüyor).
  'opencode2-app': 'OpenCode',
  'omp': 'OMP',
};

const OPERATIONS_MODULES = {
  agy,
  'claude-app': claudeApp,
  'codex-app': codexApp,
  'opencode2-app': opencode2App,
  'omp': omp,
};
reconcilePromptRequests(OPERATIONS_MODULES);
// Paylasilan icerikten not/hatirlatici cikarimi — paylas menusundeki "Not ekle"
// ucunun (POST /cowork/note/from-share) motoru.
//
// Eskiden ayni cikarim bir ekran goruntusu TARAYICISI tarafindan da beslenirdi:
// adb-push'un saglik turuna binen bir kanca yeni goruntuleri cekiyor, model her
// birine "bu kayda deger mi" diye bakiyordu. O yol 19.08.2026'da sokuldu (bkz.
// docs/ekran-goruntusu-hatirlatici-plani.md, Faz S): 297 goruntu tarandi, 3 not
// cikti — model zamaninin ~%99'u niyet tahminine gidiyordu. Paylas tusuna basmak
// ayni soruyu bedavaya cevapliyor.
const shareNoteDir = path.join(os.homedir(), '.agentbridge', 'share-notes');
// `screenshotWatch` tarayicidan kalan ESKI anahtar adi; mevcut config'ler
// bozulmasin diye agy/model hala oradan da okunuyor.
const noteCfg = cfg.noteExtract || cfg.screenshotWatch || {};
const shareNoteExtract = createScreenshotExtract({
  agy: noteCfg.agy || 'agy',
  model: noteCfg.model || DEFAULT_SCREENSHOT_MODEL,
  addDir: shareNoteDir,
  log: (msg) => console.warn(msg),
});

// Paylasilan icerik nota donustukten sonra ham kopyanin bir degeri kalmiyor;
// notta zaten metin, alinti ve etiketler var. Iki yer birden budanir:
//
// 1. `share-notes/` — paylasilan dosyanin kopyasi.
// 2. Cikarimin agy home'u — her cagri orada bir konusma + brain klasoru
//    birakiyor. Asil birikim BURASI: 19.08.2026'da 239 MB olculdu (297 cagri),
//    kullanicinin hic bakmadigi bir yerde. `bin/` gibi kalici parcalar
//    budanmaz, yalniz konusma uretimi budanir.
const GUN_MS = 24 * 60 * 60 * 1000;
const NOTE_RETENTION_DAYS = Number.isFinite(noteCfg.retentionDays) ? noteCfg.retentionDays : 3;
const extractHome = path.join(os.homedir(), '.gemini', EXTRACT_APP_DATA_DIR);
const NOTE_PRUNE_DIRS = [
  shareNoteDir,
  path.join(extractHome, 'brain'),
  path.join(extractHome, 'conversations'),
  path.join(extractHome, 'log'),
];

// agy'nin KENDI oturumlari — yukaridaki cikarim artigindan tamamen ayri bir sey
// ve ayri bir sure hak ediyor: bunlar kullanicinin telefondan actigi, uygulamada
// listelenen, devam ettirilebilir sohbetler. Silmek geri alinamaz.
//
// YALNIZ CLI home'u budanir. Masaustu Antigravity IDE'nin gecmisi (`ide` home)
// bu kopruye ait degil; oraya dokunmak kullanicinin editorundeki gecmisi silmek
// olurdu. Kural kullaniciyla kararlastirildi (19.08.2026): 7 gun.
const AGY_RETENTION_DAYS = Number.isFinite(cfg.agy?.sessionRetentionDays)
  ? cfg.agy.sessionRetentionDays
  : 7;
const agyCliHome = path.join(os.homedir(), '.gemini', 'antigravity-cli');
const AGY_PRUNE_DIRS = [
  path.join(agyCliHome, 'brain'),
  path.join(agyCliHome, 'conversations'),
  path.join(agyCliHome, 'log'),
];

function pruneNoteJunk() {
  // 0 = budama kapali. Her iki tur da ayri ayri kapatilabilir.
  if (NOTE_RETENTION_DAYS > 0) {
    const r = pruneAll(NOTE_PRUNE_DIRS, {
      maxAgeMs: NOTE_RETENTION_DAYS * GUN_MS,
      log: (msg) => console.warn(msg),
    });
    if (r.removed) {
      console.log(`not artigi budandi: ${r.removed} girdi, ${(r.freedBytes / 1048576).toFixed(1)} MB`);
    }
  }
  if (AGY_RETENTION_DAYS > 0) {
    const r = pruneAll(AGY_PRUNE_DIRS, {
      maxAgeMs: AGY_RETENTION_DAYS * GUN_MS,
      log: (msg) => console.warn(msg),
    });
    if (r.removed) {
      console.log(`agy oturumu budandi: ${r.removed} girdi, ${(r.freedBytes / 1048576).toFixed(1)} MB`);
    }
  }
}

// ── Backend oturum budamasi ─────────────────────────────────────────────────
// claude / codex / opencode: bir haftadan fazla inaktif oturumlar. Olcut ve
// muafiyetler session-prune.mjs'te; burada yalniz her backend'in kendi liste ve
// silme yolunun baglanmasi var. Neyin nasil silinecegini backend biliyor —
// depolari birbirine hic benzemiyor (jsonl havuzu / rollout dosyalari / sqlite).
//
// agy AYRI kaldi (yukarida, klasor yasina gore): oradaki birikim yalniz oturum
// degil, `brain/` ve `log/` de var; ayrica CLI/IDE ayrimi dosya sistemi
// seviyesinde bir gercek, oturum kaydinda degil.
//
// omp kapsam DISI: kullanicinin istegi uc backend'di.
const SESSION_RETENTION_DAYS = Number.isFinite(cfg.sessionPrune?.retentionDays)
  ? cfg.sessionPrune.retentionDays
  : 7;
// Kosma araligi SAKLAMA SURESI DEGIL: neyin silinecegini `retentionDays`
// belirliyor, bu yalniz "uygun hale geldi" ile "fiilen silindi" arasindaki
// gecikme. Aralik buyudukce saklama suresi fiilen uzar (haftada bir kosarsa
// 7 gunluk kural 7-14 gun gibi davranir), o yuzden varsayilan sik: 6 saat.
const SESSION_PRUNE_INTERVAL_MS = (Number.isFinite(cfg.sessionPrune?.intervalHours)
  && cfg.sessionPrune.intervalHours > 0
  ? cfg.sessionPrune.intervalHours
  : 6) * 60 * 60 * 1000;
const SESSION_PRUNE_BACKENDS = [
  {
    label: 'claude-app',
    // Cekmece listesi degil, catal gruplariyla TAM liste — gerekcesi claude-app'te.
    list: () => claudeApp.listAllDiskSessionsForPrune(),
    // Bir "oturum" birden fazla dosya olabilir; hepsi birlikte gider.
    remove: (s) => {
      for (const id of (s.ids?.length ? s.ids : [s.id])) {
        const r = claudeApp.deleteDiskSession({ id });
        if (r && r.ok === false) return r;
      }
      return { ok: true };
    },
  },
  {
    label: 'codex-app',
    list: async () => (await codexApp.listDiskSessions({ all: true }))?.sessions || [],
    remove: (s) => codexApp.deleteDiskSession({ id: s.id }),
  },
  {
    // v2'nin listDiskSessions'ı ASENKRON (sunucu kapalıyken db'yi salt okunur
    // açıyor); v1'in senkron imzasıyla karıştırılmamalı, await şart.
    label: 'opencode2-app',
    list: async () => (await opencode2App.listDiskSessions())?.sessions || [],
    remove: (s) => opencode2App.deleteDiskSession({ id: s.id }),
  },
];

// ── Bosta kalan claude surecleri ────────────────────────────────────────────
// claude-app tur bitince kalici `claude` surecini sicak tutuyor; telefondan
// acilan her sohbet bir gun boyunca ~60-240 MB yiyordu (16.09.2026'da 9 surec
// = 711 MB olculdu). N dakika bosta kalan surec kapatilir, sohbet kalir,
// sonraki mesaj --resume ile devam eder. Kurallar idle-reaper.mjs'te.
// config: idleChildren.claudeMinutes (0 = kapali, varsayilan 30),
//         idleChildren.intervalMinutes (kosma araligi, varsayilan 5).
// codex/opencode kapsam DISI: onlar oturum basina degil backend basina TEK
// app-server tutuyor ve warmOpenTabBackends onu bilerek sicak tutuyor.
const IDLE_CHILD_MINUTES = Number.isFinite(cfg.idleChildren?.claudeMinutes)
  ? cfg.idleChildren.claudeMinutes
  : 30;
const IDLE_CHILD_INTERVAL_MS = (Number.isFinite(cfg.idleChildren?.intervalMinutes)
  && cfg.idleChildren.intervalMinutes > 0
  ? cfg.idleChildren.intervalMinutes
  : 5) * 60 * 1000;

function reapIdleClaudeChildren() {
  if (IDLE_CHILD_MINUTES <= 0) return;
  try {
    const r = claudeApp.reapIdleChildren({ maxIdleMs: IDLE_CHILD_MINUTES * 60 * 1000 });
    if (r.killed.length || r.failed) {
      console.warn(`bosta claude surecleri: ${r.killed.length} kapatildi, ${r.failed} hata; ` +
        `kosan ${r.running}, onay bekleyen ${r.awaiting}, taze ${r.fresh}`);
    }
  } catch (e) {
    console.warn('bosta claude surecleri: tarama hatasi: ' + String(e?.message || e).slice(0, 200));
  }
}

async function pruneStaleSessions() {
  if (SESSION_RETENTION_DAYS <= 0) return; // 0 = budama kapali
  for (const backend of SESSION_PRUNE_BACKENDS) {
    try {
      const sessions = await backend.list();
      const r = await pruneSessions({
        sessions,
        remove: backend.remove,
        maxAgeMs: SESSION_RETENTION_DAYS * GUN_MS,
        coworkRoot: claudeApp.coworkRoot(),
        log: (msg) => console.warn(msg),
      });
      if (r.removed || r.failed) {
        console.log(`${backend.label}: ${r.removed} oturum budandi`
          + ` (cowork muaf ${r.cowork}, pinli ${r.pinned}, taze ${r.fresh}`
          + `${r.emptyUnknown ? `, bos kabuk ${r.emptyUnknown}` : ''}`
          + `${r.failed ? `, basarisiz ${r.failed}` : ''})`);
      }
    } catch (e) {
      console.warn(`${backend.label} oturum budama hatasi: ${String(e?.message || e).slice(0, 200)}`);
    }
  }
}

const notificationNotePaths = new Map();
// Bildirimlerin tek kanali uygulamanin long-poll baglantisi (docs/notification-routing.md).
const notificationDelivery = createNotificationDelivery({
  filePath: path.join(BRIDGE_DIR, 'data', 'notification-delivery.json'),
  isValid: (extras) => {
    if (!['note', 'reminder'].includes(extras.kind) || !extras.noteId) return true;
    let notePath = notificationNotePaths.get(extras.noteId);
    if (!notePath) {
      notePath = cowork.listNotes().notes.find(n => n.id === extras.noteId)?.mdPath;
      if (notePath) notificationNotePaths.set(extras.noteId, notePath);
    }
    if (!notePath || !fs.existsSync(notePath)) return false;
    if (extras.kind === 'note') return true;
    let md;
    try { md = fs.readFileSync(notePath, 'utf8'); } catch { return false; }
    const at = /^reminder_at:\s*(.+)$/m.exec(md)?.[1];
    const time = new Date(at || '');
    return Number.isFinite(time.getTime()) && extras.deliveryId === `reminder:${extras.noteId}:${time.toISOString()}`;
  },
});

// Hatirlatici kaydi = `_genel-notlar/` altinda normal bir markdown notu; ayri
// veritabani yok, boylece mevcut Notlarim arayuzunde gorunur ve duzenlenir.
const reminders = createReminders({
  notesDir: cowork.generalNotesDir(),
  // Kapsam TÜM notlar: proje `notlar/` klasörleri + `_genel-notlar/`. Kullanıcı
  // proje notuna da tarih koyabilmeli; hatırlatıcıyı genel notlara hapsetmenin
  // bir gerekçesi yok.
  listNotePaths: () => cowork.listNotes().notes.map((n) => n.mdPath).filter(Boolean),
  noteIdOf: (mdPath) => cowork.listNotes().notes.find((n) => n.mdPath === mdPath)?.id || '',
  // Hedef verilmez: hatirlatma kayitli TUM cihazlara gider.
  send: (extras, opts) => notificationDelivery.send(extras, opts),
  log: (msg) => console.warn(msg),
});

// SADECE dogrudan calistirildiginda: alti test dosyasi server.mjs'i import
// ediyor (auth, slash, downloadRoute, ...). Bu blok modul kapsaminda kalirsa
// her test kosusu alti hatirlatici zamanlayicisi baslatir ve bunlar GERCEK
// cihaza canli bildirim gonderebilir. 07.08'de ayni hata sokulen ekran
// goruntusu izleyicisiyle yasandi: bridge.log'da alti kez "izleyici aktif"
// gorundu. HTTP dinleyicisi zaten ayni kapinin ardinda (asagida).
if (import.meta.main) {
  // Hatirlatici turu hicbir sey tarafindan kapatilamaz. Eskiden ekran goruntusu
  // taramasinin `if (shotEnabled)` blogunun icindeydi: tarama kapatilinca ELLE
  // kurulan hatirlaticilar da sessizce calmiyordu ve bunun hicbir yerde izi
  // yoktu. Tur 60 sn, cunku 5 dakikalik saglik turu "14:00 durusma" icin fazla
  // kaba kalir. Acilista da bir kez calisir — bridge kapaliyken vakti gecenler
  // gecikmeli de olsa gitsin.
  const reminderTimer = setInterval(() => { void reminders.tick(); }, 60_000);
  reminderTimer.unref?.();
  void reminders.tick();
  // Budama acilista bir kez, sonra 6 saatte bir. Sik olmasinin anlami yok:
  // olcut gun cinsinden ve gecikmenin maliyeti birkac megabayt.
  const pruneTimer = setInterval(pruneNoteJunk, 6 * 60 * 60 * 1000);
  pruneTimer.unref?.();
  pruneNoteJunk();
  // Oturum budamasi AGIR: claude tarafinda her transcript'in basi okunuyor
  // (memo'lu ama sogukken saniyeler suruyor). Acilista hemen kosarsa telefonun
  // ilk istegi o taramanin arkasinda bekler — bu yuzden gecikmeli baslar.
  const sessionPruneTimer = setInterval(() => { void pruneStaleSessions(); }, SESSION_PRUNE_INTERVAL_MS);
  sessionPruneTimer.unref?.();
  const sessionPruneFirst = setTimeout(() => { void pruneStaleSessions(); }, 60_000);
  sessionPruneFirst.unref?.();
  // Bosta surec kapatma UCUZ (bellekteki Map uzerinde bir dongu), sik kosabilir.
  const idleChildTimer = setInterval(reapIdleClaudeChildren, IDLE_CHILD_INTERVAL_MS);
  idleChildTimer.unref?.();
}
// Push'lanan operasyon olaylari. 'started' 16.09.2026'da eklendi (kapsul /
// Android 16 Live Updates, docs/kapsul-live-updates-plani.md §2.1): telefonda
// kapsulu acan olay bu. Ayrica "onay cozuldu" diye ayri bir olay YOK —
// `waiting -> running` gecisi de 'started' uretiyor (operations.mjs:149), yani
// kapsulun ONAY -> TUR donusu de buradan geliyor.
// 'started' telefonda SESSIZ: golgeye satir atmaz, yalniz kapsulu gunceller.
export const OPERATION_PUSH_KINDS = ['started', 'attention', 'completed', 'failed'];

// Bildirim kuyruguna (pushEvents) giden govde. Ayri fonksiyon cunku sozlesme
// testten kilitlenebilsin (bridge/test/operations.test.mjs). `title` ve
// `startedAt` kapsul icin eklendi: kapsul kartinin basligi ve chronometer'in
// baslangici. `startedAt` ISO metindir (operations.mjs:98 `now().toISOString()`),
// sayi degil — Android metin olarak okuyor, ek donusum gereksiz.
export function operationPushBody(event) {
  return {
    deliveryId: event.id,
    kind: event.kind,
    backend: event.backend,
    backendLabel: event.backendLabel,
    sessionId: event.sessionId,
    summary: event.summary,
    title: event.title || '',
    startedAt: event.at || '',
  };
}

const operationsTracker = createOperationsTracker({
  modules: OPERATIONS_MODULES,
  labels: MODULE_LABELS,
  onEvent: (event) => {
    if (!OPERATION_PUSH_KINDS.includes(event.kind)) return;
    // Bildirim TURU BASLATAN cihaza gider. Eskiden hedefsizdi, yani her
    // cihaza: kullanici tabletten prompt verirken yandaki telefon da otuyordu
    // (sikayet 23.08.2026). Kaynak bilinmiyorsa (basliksiz istemci, web
    // arayuzu, kopru yeniden basladi) eski davranis korunur - susturmak
    // fazladan otmekten daha zararli olurdu.
    const model = promptOriginModel(event.backend, event.sessionId, event.diskId);
    if (model === '__agentbridge_lite_no_push__') return;
    const targets = model ? notificationDelivery.deviceIdsForModel(model) : [];
    // KISMA (throttle) YOK — bilincli karar 16.09.2026 (plan §2.1-2). Her onay
    // turu bir 'attention' + bir 'started' push'u demek. Ilk surum sade tutuldu; pil etkisi
    // once OLCULECEK (plan §5, olcum adimi 11), kisma gerekiyorsa ondan sonra
    // eklenecek. Olcmeden kisma koymak kapsulu gecikmeli/yanlis durumda
    // birakma riskini bedava getirirdi.
    notificationDelivery.send(operationPushBody(event), targets.length > 0 ? { targets } : {});
  },
});
operationsTracker.start();
const projectsStore = createProjectsStore({
  modules: OPERATIONS_MODULES,
  // opencode-mcp.mjs config DOSYASI üzerinden çalışıyor (~/.config/opencode/
  // opencode.json `mcp`) — v2 aynı dosyayı okuduğu için v1 sökülürken modül
  // korundu, yalnız backend kimliği değişti.
  mcpModules: { 'codex-app': codexMcp, 'opencode2-app': opencodeMcp },
  coworkModule: cowork,
  labels: MODULE_LABELS,
});

let systemCpuUsage = 0;

function sampleCpu() {
  let lastCpus = os.cpus();
  setInterval(() => {
    const currentCpus = os.cpus();
    let totalDiff = 0;
    let idleDiff = 0;
    for (let i = 0; i < currentCpus.length; i++) {
      if (!lastCpus[i] || !currentCpus[i]) continue;
      const last = lastCpus[i].times;
      const curr = currentCpus[i].times;
      const total = (curr.user - last.user) + (curr.nice - last.nice) + (curr.sys - last.sys) + (curr.irq - last.irq) + (curr.idle - last.idle);
      const idle = curr.idle - last.idle;
      totalDiff += total;
      idleDiff += idle;
    }
    if (totalDiff > 0) {
      systemCpuUsage = Math.round(100 * (1 - idleDiff / totalDiff));
    }
    lastCpus = currentCpus;
  }, 2000).unref();
}
sampleCpu();

function getSystemMemory() {
  const free = os.freemem();
  const total = os.totalmem();
  return Math.round(100 * (1 - free / total));
}

function getActiveState() {
  let running = false;
  let backend = '';
  let sessionId = '';
  let title = '';

  // 1. Basic active state for backwards compatibility
  for (const [name, mod] of Object.entries(CLI_MODULES)) {
    if (typeof mod.runningSessionId === 'function') {
      const sid = mod.runningSessionId();
      if (sid) {
        running = true;
        backend = name;
        sessionId = sid;
        title = MODULE_LABELS[name] || name;
        break;
      }
    }
  }

  // 2. Detailed modules health status
  const modules = {};
  for (const [name, mod] of Object.entries(CLI_MODULES)) {
    let status = 'idle';
    let pid = null;
    let sessionCount = 0;

    if (typeof mod.listSessions === 'function') {
      try {
        const sessList = mod.listSessions();
        sessionCount = sessList.length;
        if (sessList.some(s => s.status === 'running')) {
          status = 'running';
        }
      } catch {}
    }

    if (typeof mod.listLiveProcesses === 'function') {
      try {
        // Şekil farkı: agy { processes, count } nesnesi, diğerleri düz dizi
        // döndürür — normalize etmeden .length kontrolü agy'yi hep atlıyordu.
        const raw = mod.listLiveProcesses();
        const procList = Array.isArray(raw) ? raw
          : (raw && Array.isArray(raw.processes)) ? raw.processes : [];
        if (procList.length > 0) {
          pid = procList[0].pid;
          // status'a DOKUNMA: codex/opencode kalıcı app-server'ı boşta da
          // yaşatır — süreç var diye "running" demek ışığı hep yeşil bırakırdı.
          // "running" yalnız gerçekten koşan turdan (listSessions status'u) gelir.
        }
      } catch {}
    }

    modules[name] = {
      status,
      pid,
      sessionCount,
    };
  }

  return {
    running,
    backend,
    sessionId,
    title,
    modules,
    system: {
      cpu: systemCpuUsage,
      memory: getSystemMemory(),
      platform: os.platform(),
      uptime: Math.round(os.uptime()),
    }
  };
}

// Backend id → modül. Katalog conformance raporu ve onay taraması bunu tek kaynak
// olarak kullanır; backend-özel listeler server.mjs içine dağılmaz (Faz 4).
const BACKEND_MODULES = {
  'claude-app': claudeApp,
  'codex-app': codexApp,
  'opencode2-app': opencode2App,
  'omp': omp,
  'agy': agy,
  'cowork': cowork,
};

function findAnyPendingApproval() {
  // Poll onay listesi kontrattan gelir (backend-contract.mjs); burada tekrar edilmez.
  for (const name of POLL_APPROVAL_BACKENDS) {
    const mod = BACKEND_MODULES[name];
    if (typeof mod?.getPendingApproval === 'function') {
      const p = mod.getPendingApproval();
      if (p) return p;
    }
  }
  return null;
}

function getNotificationsState() {
  const p = findAnyPendingApproval();
  if (p) {
    return { pending: true, backend: p.backend, sessionId: p.sessionId, summary: p.summary };
  }
  return { pending: false, backend: '', sessionId: '', summary: '' };
}

function getNotificationFeedState(deviceId = '') {
  return buildNotificationFeedState(getNotificationsState(), operationsTracker.snapshot(40), notificationDelivery.snapshot(deviceId));
}

const waitingPolls = [];
const _notificationsInterval = setInterval(() => {
  if (waitingPolls.length === 0) return;
  for (let i = waitingPolls.length - 1; i >= 0; i--) {
    const item = waitingPolls[i];
    const current = getNotificationFeedState(item.deviceId);
    if (notificationFeedChanged(current, item)) {
      clearTimeout(item.timeout);
      waitingPolls.splice(i, 1);
      try { json(item.res, 200, current); } catch {}
    }
  }
}, 500);
if (_notificationsInterval.unref) _notificationsInterval.unref();

// ── Route registration ──────────────────────────────────────────────────────
const router = createRouter();

router.post('/notifications/device', async (req, res) => {
  const b = await body(req);
  // Model, "turu baslatan cihaz" eslemesi icin (prompt-origin.mjs); istemci
  // onu zaten her istekte X-Device-Model basligiyla yolluyor.
  const model = typeof b.model === 'string' && b.model ? b.model : String(req.headers?.['x-device-model'] || '');
  const result = notificationDelivery.configure({ deviceId: b.deviceId, model });
  json(res, result.ok ? 200 : 400, result);
});
router.post('/notifications/ack', async (req, res) => {
  const b = await body(req);
  const result = notificationDelivery.acknowledge(b.deviceId, b.ids);
  json(res, result.ok ? 200 : 400, result);
});

router.get('/notifications/poll', (req, res) => {
  const u = new URL(req.url, 'http://x');
  const lastPending = u.searchParams.get('pending') === 'true';
  const lastSessionId = u.searchParams.get('sessionId') || '';
  const lastEventId = u.searchParams.get('eventId') || '';
  const deviceId = u.searchParams.get('deviceId') || '';

  operationsTracker.sample();
  const current = getNotificationFeedState(deviceId);
  const cursor = { pending: lastPending, sessionId: lastSessionId, eventId: lastEventId };
  if (notificationFeedChanged(current, cursor)) {
    return json(res, 200, current);
  }

  const timeout = setTimeout(() => {
    const idx = waitingPolls.indexOf(pollObj);
    if (idx !== -1) waitingPolls.splice(idx, 1);
    json(res, 200, getNotificationFeedState(deviceId));
  }, 30000);

  const pollObj = { res, timeout, deviceId, pending: lastPending, sessionId: lastSessionId, eventId: lastEventId };
  waitingPolls.push(pollObj);

  req.on('close', () => {
    clearTimeout(timeout);
    const idx = waitingPolls.indexOf(pollObj);
    if (idx !== -1) waitingPolls.splice(idx, 1);
  });
});

// Versiyonlu backend yetenek kataloğu (Faz 4). Android kontrolleri buradan üretir;
// `conformance`, kayıtlı modüllerin sözleşmeye uyumunu canlı raporlar (teşhis amaçlı).
router.get('/backends', (req, res) => {
  const catalog = buildCatalog();
  const conformance = catalog.backends.map(b => verifyAdapter(b.id, BACKEND_MODULES[b.id]));
  json(res, 200, { ...catalog, conformance });
});

registerGeneral(router, {
  readWorkspaceFile,
  resolveWorkspacePath,
  getActiveState,
  SLASH,
  dynamicSlash: {
    omp: () => omp.getSlashCommands(),
  },
  recordFileEvent: projectsStore.recordFileEvent,
  getCoworkRoot: () => claudeApp.coworkRoot(),
  getLiteRoot: () => path.join(os.homedir(), 'AgentBridge-Lite'),
});
registerBackend(router, 'agy', agy, {
  extras: [
    { method: 'POST', pattern: '/open', handler: async (req, res) => {
      const r = await agy.openAntigravity();
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/thought', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      const i = parseInt(new URL(req.url, 'http://x').searchParams.get('i') || '0', 10);
      json(res, 200, agy.getThought(sid, i));
    }},
    // B2: Respond to an agy permission/OAuth prompt (e.g., "Allow connection? [y/N]").
    // The phone renders an approval card with Yes/No buttons; the selected answer
    // is POSTed here and forwarded to agy's stdin.
    { method: 'POST', pattern: '/respond', handler: async (req, res) => {
      const b = await body(req);
      const r = agy.respond(b.sessionId, b.answer);
      json(res, r.ok ? 200 : 400, r);
    }},
  ],
});

router.get('/operations', (req, res) => {
  const u = new URL(req.url, 'http://x');
  operationsTracker.sample();
  json(res, 200, operationsTracker.snapshot(parseInt(u.searchParams.get('limit') || '100', 10)));
});
router.get('/projects', async (req, res) => json(res, 200, await projectsStore.list()));
router.get('/projects/detail', async (req, res) => {
  const id = new URL(req.url, 'http://x').searchParams.get('id') || '';
  const result = await projectsStore.detail(id);
  json(res, result.ok ? 200 : 404, result);
});
router.post('/projects/preferences', async (req, res) => {
  const b = await body(req);
  const result = await projectsStore.applyPreferences(b);
  json(res, result.ok ? 200 : 400, result);
});
router.post('/projects/label', async (req, res) => {
  const b = await body(req);
  const result = projectsStore.setLabel(b.id, b.label, b.path);
  json(res, result.ok ? 200 : 404, result);
});
router.post('/projects/outputs/seen', async (req, res) => {
  const b = await body(req);
  const result = projectsStore.markOutputsSeen(b.id);
  json(res, result.ok ? 200 : 404, result);
});
router.post('/projects/outputs/archive', async (req, res) => {
  const b = await body(req);
  if (!b.id) return json(res, 400, { ok: false, error: 'project id required' });
  const project = projectsStore.get(b.id);
  if (!project) return json(res, 404, { ok: false, error: 'project not found' });
  const projectPath = project.path || '';
  if (!projectPath) return json(res, 404, { ok: false, error: 'project path not found' });
  const result = await archiveProjectOutputs(projectPath);
  json(res, result.ok ? 200 : 400, result);
});
// Telefon zip'i indirip açtıktan sonra bridge/tmp'deki geçici arşivi siler.
router.post('/projects/outputs/archive/cleanup', async (req, res) => {
  const b = await body(req);
  const result = cleanupOutputsArchive(b.path);
  json(res, result.ok ? 200 : 400, result);
});
router.post('/projects/security/apply', async (req, res) => {
  const b = await body(req);
  const result = await projectsStore.applySecurityProfile(b.id, b.profile, b.policy);
  json(res, result.ok ? 200 : 400, result);
});
// Projeyi izleme listesinden çıkar + tüm backend'lerden oturum geçmişini sil.
// Proje klasörüne dokunmaz (yalnız transcript/oturum kayıtları). Geri alınamaz.
router.post('/projects/delete', async (req, res) => {
  const b = await body(req);
  const result = await projectsStore.deleteProject(b.id);
  json(res, result.ok ? 200 : 400, result);
});
router.post('/projects/sessions/bulk', async (req, res) => {
  const b = await body(req);
  const result = await projectsStore.bulkSessionAction(b);
  json(res, result.ok ? 200 : 400, result);
});
// Proje klasörü + altındaki tüm dosyalar + projeye bağlı bütün oturum kayıtları.
// Liste ekranındaki uzun basış onayından çağrılır; geçmiş-silme ucundan ayrıdır.
router.post('/projects/delete-completely', async (req, res) => {
  const b = await body(req);
  const result = await projectsStore.deleteProjectCompletely(b.id);
  json(res, result.ok ? 200 : 400, result);
});
router.get('/projects/mcp/servers', async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const result = await projectsStore.listMcpServers(u.searchParams.get('id') || '', u.searchParams.get('provider') || '');
  json(res, result.ok ? 200 : 400, result);
});
router.post('/projects/mcp/apply', async (req, res) => {
  const b = await body(req);
  const result = await projectsStore.applyMcpProfile(b.id, b.provider, b.enabledNames, b.confirmGlobal);
  json(res, result.ok ? 200 : 400, result);
});
router.get('/search/global', async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const q = u.searchParams.get('q') || '';
  const limit = parseInt(u.searchParams.get('limit') || '100') || 100;
  const result = await globalSearch({ query: q, limit, projectsStore, modules: BACKEND_MODULES, coworkModule: cowork });
  json(res, result.ok ? 200 : 400, result);
});
router.post('/pairing/start', async (req, res) => json(res, 200, deviceAuth.startPairing()));
router.post('/ws-ticket', async (req, res) => json(res, 200, { ok: true, ...wsTickets.issue(authIdentity(req)) }));
router.post('/pairing/complete', async (req, res) => {
  // Kimlik kapısından önce çalışan tek gövdeli uç: küçük tavan.
  const b = await body(req, { maxBytes: 4096 });
  const result = deviceAuth.completePairing({ code: b.code, name: b.name, address: req.socket?.remoteAddress || '' });
  json(res, result.ok ? 200 : 400, result);
});
router.post('/devices/rotate', async (req, res) => {
  const identity = authIdentity(req);
  if (identity?.kind !== 'device') return json(res, 400, { ok: false, error: 'device key required' });
  const result = deviceAuth.rotate(identity.id);
  // Eski anahtarla açılmış akışlar da düşsün; istemci yeni anahtarla yeniden bağlanır.
  if (result.ok) closeSocketsOf(identity.id);
  json(res, result.ok ? 200 : 400, result);
});
router.get('/devices', (req, res) => json(res, 200, { ok: true, devices: deviceAuth.list() }));
router.post('/devices/revoke', async (req, res) => {
  const b = await body(req);
  const result = deviceAuth.revoke(String(b.deviceId || ''));
  if (result.ok) closeSocketsOf(String(b.deviceId));
  json(res, result.ok ? 200 : 400, result);
});

router.post('/backends/warm', async (req, res) => {
  const b = await body(req);
  const result = warmOpenTabBackends(
    b.targets,
    {
      'codex-app': codexApp,
      'opencode2-app': opencode2App,
    },
    (backend, error) => console.warn(`[${backend}] open-tab warmup failed:`, error?.message || error),
  );
  json(res, 200, result);
});

// ── Cowork project container (Claude + Codex + OpenCode sessions) ──
router.get('/cowork/providers', async (req, res) => json(res, 200, await cowork.providerCatalog()));
router.get('/cowork/projects', (req, res) => json(res, 200, cowork.listProjects()));
router.post('/cowork/project', async (req, res) => {
  const b = await body(req);
  const r = cowork.createProject({ name: b.name, template: b.template });
  json(res, r.ok ? 200 : 400, r);
});
router.get('/cowork/project', async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const r = cowork.getProject({ projectPath: u.searchParams.get('path') || '' });
  if (r.ok) r.sessions = (await cowork.listResolvedProjectSessions({ projectPath: r.project.path })).sessions;
  json(res, r.ok ? 200 : 400, r);
});
router.get('/cowork/sessions', async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const r = await cowork.listResolvedProjectSessions({ projectPath: u.searchParams.get('projectPath') || '' });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/import', async (req, res) => {
  const b = await body(req);
  const r = cowork.importIntoProject({ projectPath: b.projectPath, sources: b.sources });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/archive', async (req, res) => {
  const b = await body(req);
  const r = await cowork.archiveProject({ projectPath: b.projectPath });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/project/delete', async (req, res) => {
  const b = await body(req);
  const r = await cowork.deleteProject({ projectPath: b.projectPath });
  if (r.ok) projectsStore.forgetProjectPath(r.path);
  json(res, r.ok ? 200 : 400, r);
});
// Cowork registerBackend'e girmez (custom route seti); telefonun ortak
// /<backend>/delete-session sözleşmesinin cowork karşılığı budur.
router.post('/cowork/delete-session', async (req, res) => {
  const b = await body(req);
  const r = await cowork.deleteSession({ projectPath: b.projectPath, sessionId: b.id || b.sessionId });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/project/matter', async (req, res) => {
  const b = await body(req);
  const r = cowork.setProjectMatter({ projectPath: b.projectPath, matter: b.matter });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/session/start', async (req, res) => {
  const b = await body(req);
  const r = await cowork.startSession({ projectPath: b.projectPath, provider: b.provider, model: b.model, sessionId: b.sessionId, permissionMode: b.permissionMode, forceNew: b.forceNew });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/session/switch', async (req, res) => {
  const b = await body(req);
  const r = await cowork.switchSession({ projectPath: b.projectPath, provider: b.provider, model: b.model, sessionId: b.sessionId, permissionMode: b.permissionMode });
  json(res, r.ok ? 200 : 400, r);
});
router.get('/cowork/lease', (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const r = cowork.getLease({ projectPath: u.searchParams.get('projectPath') || '' });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/lease/acquire', async (req, res) => {
  const b = await body(req);
  const r = cowork.acquireLease({ projectPath: b.projectPath, provider: b.provider, sessionId: b.sessionId });
  json(res, r.ok ? 200 : 409, r);
});
router.post('/cowork/lease/release', async (req, res) => {
  const b = await body(req);
  const r = cowork.releaseLease({ projectPath: b.projectPath, provider: b.provider, sessionId: b.sessionId, force: !!b.force });
  json(res, r.ok ? 200 : 409, r);
});
// Telefonda harici uygulamada düzenlenen dosyanın geri yazımı. Yol query'de
// (URL-encoded; OkHttp non-ASCII header reddeder, Türkçe yollar header'da taşınamaz).
router.post('/cowork/savefile', async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const buf = await rawBody(req);
  const r = cowork.saveProjectFile({ filePath: u.searchParams.get('path') || '', buffer: buf });
  if (r.ok) projectsStore.recordFileEvent('user_file_saved', r.path, { size: r.size });
  json(res, r.ok ? 200 : (String(r.error || '').includes('bulunamadı') ? 404 : 400), r);
});
router.post('/cowork/handoff', async (req, res) => {
  const b = await body(req);
  const r = cowork.createHandoff({ projectPath: b.projectPath, fromProvider: b.fromProvider, sessionId: b.sessionId, toProvider: b.toProvider });
  json(res, r.ok ? 200 : 400, r);
});
// Notlar (docs/notlar-plani.md, Faz N0): birleşik liste + oluşturma + projeye
// bağlama. Not içeriği okuma/yazma mevcut cowork-scope'lu /file, /cowork/savefile
// uçlarıyla yapılır; buradakiler yalnız not-özel yaşam döngüsüdür.
// `q` verilirse başlık + gövde araması, verilmezse tam liste. Ayrı uç yerine
// aynı uç: cevap şekli birebir aynı, istemci tek çözümleyiciyle idare eder.
// Paylas menusundeki "Not ekle": paylasilan sey (goruntu ya da metin) modele
// gider, model onu nota cevirir. Otomatik ekran goruntusu taramasinin elle
// tetiklenen karsiligi — ve olcum bu yolun neden dogru oldugunu soyluyor:
// tarayici 297 goruntu tarayip 3 not uretti. Yani modelin zamaninin ~%99'u
// "bu kayda deger mi" sorusuna gitti. Paylas tusuna basmak o soruyu bedavaya
// cevapliyor; geriye kalan cikarim ise zaten degerli olan kisim.
//
// SENKRON: cikarim ~15 sn suruyor ve istemci bu sureyi bekliyor. Ates-et-unut
// + bildirim de olabilirdi ama bildirim kanali tek bir cihaza ayarli; paylasimi
// yapan cihaz baskasiysa haber yanlis yere giderdi. Cevabi bekleyen istemci
// hatayi da gorur.
router.post('/cowork/note/from-share', async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const buf = await rawBody(req);
  if (!buf || !buf.length) return json(res, 400, { ok: false, error: 'bos govde' });
  // Yol kacisi: yalniz dosya ADI kullanilir, ayirici ve surunme reddedilir.
  const rawName = path.basename(String(u.searchParams.get('filename') || '').trim());
  const safeName = rawName.replace(/[^\p{L}\p{N}._-]/gu, '_').replace(/^\.+/, '').slice(0, 80);
  if (!safeName) return json(res, 400, { ok: false, error: 'gecersiz dosya adi' });
  let localPath;
  try {
    fs.mkdirSync(shareNoteDir, { recursive: true });
    // Ad onunde zaman damgasi: ayni adli iki paylasim birbirini ezmesin ve
    // dosya adindan cekim zamani cozulmeye calisilmasin (bu bir ekran
    // goruntusu olmayabilir; referans an dosyanin mtime'i olur).
    localPath = path.join(shareNoteDir, `${Date.now()}_${safeName}`);
    fs.writeFileSync(localPath, buf);
  } catch (e) {
    return json(res, 500, { ok: false, error: `dosya yazilamadi: ${String(e?.message || e).slice(0, 120)}` });
  }
  // `siniflandir: false` — kullanici zaten "not ekle" dedi, kayda-degerlik
  // sorusu sorulmaz.
  const rec = await shareNoteExtract.extract(localPath, null, { siniflandir: false });
  if (!rec) return json(res, 502, { ok: false, error: 'model paylasilan icerikten not cikaramadi' });
  const note = reminders.create({
    baslik: rec.baslik,
    tarihSaat: rec.tarih_saat,
    alinti: rec.alinti,
    aciklama: rec.aciklama,
    metin: rec.metin,
    etiketler: rec.etiketler,
    sourceScreenshot: path.basename(localPath),
  });
  if (!note) return json(res, 500, { ok: false, error: 'not yazilamadi' });
  const noteId = cowork.listNotes().notes.find((n) => n.mdPath === note.path)?.id || '';
  console.log(`paylasim -> not: ${note.id} [${rec.tur}]${rec.tarih_saat ? ` (${rec.tarih_saat})` : ''}`);
  json(res, 200, {
    ok: true,
    noteId,
    baslik: rec.baslik,
    tur: rec.tur,
    tarihSaat: rec.tarih_saat || null,
  });
});
router.get('/cowork/notes', (req, res) => {
  const q = new URL(req.url, 'http://x').searchParams.get('q') || '';
  json(res, 200, cowork.searchNotes(q));
});
router.post('/cowork/note', async (req, res) => {
  const b = await body(req);
  const r = cowork.createNote({ name: b.name, projectPath: b.projectPath || null });
  json(res, r.ok ? 200 : 400, r);
});
// Var olan bir nota (genel ya da proje notu) elle hatırlatıcı kur/kaldır.
// `at` yoksa/boşsa hatırlatıcı silinir. Zamanlayıcı tüm notları taradığı için
// proje notları da çalışır. APK gerekmez — uç bugün curl'le kullanılabilir,
// arayüz düğmesi sonraki APK turunda buna bağlanır.
router.post('/cowork/note/reminder', async (req, res) => {
  const b = await body(req);
  const note = cowork.listNotes().notes.find((n) => n.id === b.noteId);
  if (!note?.mdPath) return json(res, 404, { ok: false, error: 'not bulunamadı' });
  const r = reminders.setReminder(note.mdPath, b.at ?? null);
  json(res, r.ok ? 200 : 400, r.ok ? { ...r, noteId: note.id, title: note.title } : r);
});
router.post('/cowork/note/attach', async (req, res) => {
  const b = await body(req);
  const r = cowork.attachNote({ noteId: b.noteId, projectPath: b.projectPath });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/note/rename', async (req, res) => {
  const b = await body(req);
  const r = cowork.renameNote({ noteId: b.noteId, name: b.name });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/note/delete', async (req, res) => {
  const b = await body(req);
  const r = cowork.deleteNote({ noteId: b.noteId });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/note/ai', async (req, res) => {
  const b = await body(req);
  const r = await cowork.noteAi({
    noteId: b.noteId,
    action: b.action,
    instruction: b.instruction,
  });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/cowork/note/ai/apply', async (req, res) => {
  const b = await body(req);
  const r = cowork.applyNoteAi({
    noteId: b.noteId,
    baseHash: b.base_hash,
    proposed: b.proposed,
  });
  json(res, r.ok ? 200 : (r.conflict ? 409 : 400), r);
});

registerBackend(router, 'claude-app', claudeApp, {
  beforePrompt: (b) => cowork.guardProviderPrompt({ provider: 'claude-app', sessionId: b.sessionId }),
  extras: [
    // ── Cowork workspace yönetimi ────────────────────────────────────────────
    // POST /claude-app/workspace → { name } alır, COWORK_ROOT altında sanitize edilmiş
    // klasör oluşturur. Path traversal reddi (claudeApp.createWorkspace içinde).
    { method: 'POST', pattern: '/workspace', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.createWorkspace({ name: b.name });
      json(res, r.ok ? 200 : 400, r);
    }},
    // GET /claude-app/workspaces → COWORK_ROOT altındaki klasörler.
    { method: 'GET', pattern: '/workspaces', handler: (req, res) => {
      json(res, 200, claudeApp.listWorkspaces());
    }},
    // GET /claude-app/cowork-disk-sessions → yalnızca COWORK_ROOT altındaki oturumlar.
    { method: 'GET', pattern: '/cowork-disk-sessions', handler: (req, res) => {
      json(res, 200, claudeApp.listCoworkDiskSessions());
    }},
    { method: 'GET', pattern: '/thought', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      const i = parseInt(new URL(req.url, 'http://x').searchParams.get('i') || '0', 10);
      json(res, 200, claudeApp.getThought(sid, i));
    }},
    // Faz 5: oturum envanteri (tools/mcp/slash/agents/skills) + plan modu toggle (idle iken).
    { method: 'GET', pattern: '/info', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      const r = claudeApp.getInfo(sid);
      json(res, r.ok ? 200 : 404, r);
    }},
    { method: 'POST', pattern: '/permission-mode', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.setPermissionMode({ sessionId: b.sessionId, mode: b.mode });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Claude MCP yönetimi (<configDir>/.claude.json mcpServers).
    { method: 'GET', pattern: '/mcp/servers', handler: async (req, res) => {
      json(res, 200, await claudeMcp.listServers());
    }},
    { method: 'POST', pattern: '/mcp/server', handler: async (req, res) => {
      const b = await body(req);
      const r = await claudeMcp.saveServer(b);
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/mcp/remove', handler: async (req, res) => {
      const b = await body(req);
      const r = await claudeMcp.removeServer({ name: b.name });
      json(res, r.ok ? 200 : 404, r);
    }},
    { method: 'POST', pattern: '/mcp/toggle', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeMcp.toggleServer({ name: b.name, enabled: !!b.enabled });
      json(res, r.ok ? 200 : 404, r);
    }},
    { method: 'POST', pattern: '/approve', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.approve({ sessionId: b.sessionId, allow: b.allow === true, answers: b.answers, updatedInput: b.updatedInput, requestId: b.requestId });
      json(res, r.ok ? 200 : (r.stale ? 409 : 400), r);
    }},
    { method: 'POST', pattern: '/interrupt', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, claudeApp.interrupt(b.sessionId));
    }},
    // Model değişimi (tur ortası dahil): kalıcı süreç respawn ile uygulanır.
    { method: 'POST', pattern: '/model', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.setModel({ sessionId: b.sessionId, model: b.model });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Reasoning effort (low/medium/high/xhigh/max) — CLI'nin --effort flag'ı.
    { method: 'GET', pattern: '/efforts', handler: (req, res) => json(res, 200, { efforts: claudeApp.CLAUDE_APP_EFFORT_LEVELS }) },
    { method: 'POST', pattern: '/effort', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.setEffort({ sessionId: b.sessionId, effort: b.effort });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/disk-sessions-search', handler: async (req, res) => {
      const q = new URL(req.url, 'http://x').searchParams.get('q') || '';
      const archived = new URL(req.url, 'http://x').searchParams.get('archived');
      const pinned = new URL(req.url, 'http://x').searchParams.get('pinned');
      const r = await claudeApp.listDiskSessionsWithQuery({
        query: q,
        archived: archived === 'true' ? true : archived === 'false' ? false : null,
        pinned: pinned === 'true' ? true : pinned === 'false' ? false : null,
      });
      json(res, 200, r);
    }},
    { method: 'POST', pattern: '/archive', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, claudeApp.archiveThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/unarchive', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, claudeApp.unarchiveThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/pin', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, claudeApp.pinThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/unpin', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, claudeApp.unpinThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/rename', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, claudeApp.renameThread({ id: b.id, title: b.title }));
    }},
    // POST /claude-app/open-on-pc → oturumu PC'de terminal penceresinde
    // `claude --resume` ile açar (masaüstü Claude app köprü oturumlarını
    // listelemediği için PC'de sürdürmenin yolu budur).
    { method: 'POST', pattern: '/open-on-pc', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.openOnPc({ id: b.id });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Slash komut router'ı: /clear, /model backend'de; diğerleri passthrough prompt.
    { method: 'POST', pattern: '/slash', handler: async (req, res) => {
      const b = await body(req);
      const r = claudeApp.slash({ sessionId: b.sessionId, command: b.command, args: b.args });
      if (r.handled) { json(res, r.ok ? 200 : 400, r); return; }
      // passthrough: komut metnini normal prompt olarak gönder (CLI /compact'ı tanır).
      const text = '/' + b.command + (b.args ? ' ' + b.args : '');
      const pr = claudeApp.prompt({ sessionId: b.sessionId, text });
      json(res, pr.ok ? 200 : 400, { ...pr, passthrough: true });
    }},
  ],
});
registerBackend(router, 'codex-app', codexApp, {
  // Model listesi DİSKTEN zenginleşir: yerleşik modeller + Codex profilleri
  // (CODEX_HOME/<ad>.config.toml). Böylece özel bir sağlayıcı tanımlayan
  // kullanıcı onu telefondaki model seçicide görür.
  getModels: () => codexApp.listSelectableModels(),
  beforePrompt: (b) => cowork.guardProviderPrompt({ provider: 'codex-app', sessionId: b.sessionId }),
  extras: [
    { method: 'GET', pattern: '/thought', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      const i = parseInt(new URL(req.url, 'http://x').searchParams.get('i') || '0', 10);
      json(res, 200, codexApp.getThought(sid, i));
    }},
    { method: 'POST', pattern: '/approve', handler: async (req, res) => {
      const b = await body(req);
      const r = codexApp.approve({ sessionId: b.sessionId, allow: b.allow === true, cancel: b.cancel === true, decision: b.decision, scope: b.scope, answers: b.answers, content: b.content, requestId: b.requestId });
      json(res, r.ok ? 200 : (r.stale ? 409 : 400), r);
    }},
    { method: 'POST', pattern: '/respond-user-input', handler: async (req, res) => {
      const b = await body(req);
      const r = codexApp.respondUserInput({ sessionId: b.sessionId, answers: b.answers, text: b.text });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/compact', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexApp.compact(b.sessionId);
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/model', handler: async (req, res) => {
      const b = await body(req);
      const r = codexApp.setModel({ sessionId: b.sessionId, model: b.model });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Reasoning effort — TurnStartParams.effort (sonraki turlar için). Model
    // bazlı destek kümeleri app-server model/list'ten gelir (effortsByModel).
    { method: 'GET', pattern: '/efforts', handler: async (req, res) => json(res, 200, await codexApp.getEffortInfo()) },
    { method: 'POST', pattern: '/effort', handler: async (req, res) => {
      const b = await body(req);
      const r = codexApp.setEffort({ sessionId: b.sessionId, effort: b.effort });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Feature 10: Schema/version info
    { method: 'GET', pattern: '/info', handler: async (req, res) => json(res, 200, await codexApp.getInfo()) },
    // Feature 1: Thread fork
    { method: 'POST', pattern: '/fork', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexApp.forkSession({ sessionId: b.sessionId, cwd: b.cwd });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Feature 2: Turn steer
    { method: 'POST', pattern: '/steer', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexApp.steerTurn({ sessionId: b.sessionId, text: b.text });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Thread goal: objective + status + token bütçesi (app-server thread/goal/*)
    { method: 'GET', pattern: '/goal', handler: async (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      json(res, 200, await codexApp.getGoal(sid));
    }},
    { method: 'POST', pattern: '/goal/set', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexApp.setGoal({ sessionId: b.sessionId, objective: b.objective, status: b.status, tokenBudget: b.tokenBudget });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/goal/clear', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexApp.clearGoal({ sessionId: b.sessionId, silent: !!b.silent });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Feature 4: File changes & diff
    { method: 'GET', pattern: '/changes', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      json(res, 200, codexApp.getChanges(sid));
    }},
    // "Değişiklikler": /opencode-app/diff ile AYNI şema, çünkü telefonda ikisini
    // de aynı gövde çiziyor. Yoklamaya binmez, kullanıcı görünümü açınca çekilir.
    // Parametre adı iki türlü de kabul ediliyor (`session` opencode tarafının,
    // `sessionId` bu backend'in geri kalanının alışkanlığı).
    { method: 'GET', pattern: '/diff', handler: (req, res) => {
      const p = new URL(req.url, 'http://x').searchParams;
      const r = codexApp.sessionDiff({ sessionId: p.get('session') || p.get('sessionId') || '' });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Feature 5: Command execution timeline
    { method: 'GET', pattern: '/commands', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      json(res, 200, codexApp.getCommands(sid));
    }},
    // Feature 8: Context percent helper
    { method: 'GET', pattern: '/context-percent', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      json(res, 200, { ok: true, percent: codexApp.contextPercent(sid) });
    }},
    // Feature 6: Permission mode selector
    { method: 'POST', pattern: '/permission-mode', handler: async (req, res) => {
      const b = await body(req);
      const r = codexApp.setPermissionMode({ sessionId: b.sessionId, permissionMode: b.permissionMode });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/permission-modes', handler: (req, res) => json(res, 200, { modes: codexApp.PERMISSION_MODES }) },
    // Feature 7: Thread archive/pin/search
    { method: 'GET', pattern: '/disk-sessions-search', handler: async (req, res) => {
      const q = new URL(req.url, 'http://x').searchParams.get('q') || '';
      const archived = new URL(req.url, 'http://x').searchParams.get('archived');
      const pinned = new URL(req.url, 'http://x').searchParams.get('pinned');
      const r = await codexApp.listDiskSessionsWithQuery({
        query: q,
        archived: archived === 'true' ? true : archived === 'false' ? false : null,
        pinned: pinned === 'true' ? true : pinned === 'false' ? false : null,
      });
      json(res, 200, r);
    }},
    { method: 'POST', pattern: '/archive', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, codexApp.archiveThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/unarchive', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, codexApp.unarchiveThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/pin', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, codexApp.pinThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/unpin', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, codexApp.unpinThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/rename', handler: async (req, res) => {
      const b = await body(req);
      json(res, 200, codexApp.renameThread({ id: b.id, title: b.title }));
    }},
    // Feature 9: Plan navigation
    { method: 'GET', pattern: '/plan-items', handler: (req, res) => {
      const sid = new URL(req.url, 'http://x').searchParams.get('sessionId') || '';
      json(res, 200, codexApp.getPlanItems(sid));
    }},
    // Codex MCP yönetimi (~/.codex/config.toml [mcp_servers]; yeni oturumda geçerli).
    { method: 'GET', pattern: '/mcp/servers', handler: async (req, res) => {
      const configured = await codexMcp.listServers();
      let native = [];
      try { native = (await codexApp.getNativeInventory()).mcpServers; } catch {}
      json(res, 200, codexApp.mergeMcpServerInventory(configured, native));
    }},
    { method: 'POST', pattern: '/mcp/server', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexMcp.saveServer(b);
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/mcp/remove', handler: async (req, res) => {
      const b = await body(req);
      const r = await codexMcp.removeServer({ name: b.name });
      json(res, r.ok ? 200 : 404, r);
    }},
    { method: 'POST', pattern: '/mcp/toggle', handler: async (req, res) => {
      const b = await body(req);
      const r = codexMcp.toggleServer({ name: b.name, enabled: !!b.enabled });
      json(res, r.ok ? 200 : 404, r);
    }},
  ],
});
// OpenCode (v2.0.x) — TEK OpenCode girişi. 30.09.2026'da v1 backend'i tümüyle
// söküldü: npm `opencode-ai` kaldırıldı, global `opencode` = @opencode/cli,
// 4096 sunucusu v2'nin arka plan servisi, veri kökü gerçek `~/.local/share`.
// Katalog doğrulaması artık v1'in kataloğuyla DEĞİL, v2'nin kendi `opencode
// models` CLI'siyle kesişiyor — kanca adaptörün içinde (bkz. cliCatalogIds).
registerBackend(router, 'opencode2-app', opencode2App, {
  beforePrompt: (b) => cowork.guardProviderPrompt({ provider: 'opencode2-app', sessionId: b.sessionId }),
  getModels: (req) => opencode2App.getModels(new URL(req.url, 'http://x').searchParams.get('directory') || ''),
  defaultModel: () => opencode2App.defaultModel(),
  extras: [
    { method: 'GET', pattern: '/thought', handler: (req, res) => {
      const p = new URL(req.url, 'http://x').searchParams;
      json(res, 200, opencode2App.getThought(p.get('sessionId') || '', parseInt(p.get('i') || '0', 10)));
    }},
    { method: 'POST', pattern: '/approve', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.approve({ sessionId: b.sessionId, allow: b.allow === true, always: b.always === true, requestId: b.requestId });
      json(res, r.ok ? 200 : (r.stale ? 409 : 400), r);
    }},
    { method: 'POST', pattern: '/model', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.setModel({ sessionId: b.sessionId, model: b.model });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/permission-modes', handler: (req, res) => json(res, 200, { modes: opencode2App.PERMISSION_MODES }) },
    { method: 'POST', pattern: '/permission-mode', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.setPermissionMode({ sessionId: b.sessionId, permissionMode: b.permissionMode || b.mode });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/agents', handler: async (req, res) => {
      const r = await opencode2App.listAgents();
      json(res, r.ok ? 200 : 502, r);
    }},
    { method: 'POST', pattern: '/agent', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.setAgent({ sessionId: b.sessionId, agent: b.agent });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Tur sürerken gelen mesaj: v2'de gerçek bir kuyruk var (delivery:"queue").
    { method: 'POST', pattern: '/follow-up', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.followUp({ sessionId: b.sessionId, text: b.text });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Teşhis: v2 sunucusu ayakta mı, hangi izole kökü kullanıyor.
    { method: 'GET', pattern: '/info', handler: async (req, res) => {
      // directory: skill envanteri proje dizinine göre değişiyor (v2 `location`).
      const dir = new URL(req.url, 'http://x').searchParams.get('directory') || '';
      json(res, 200, await opencode2App.getInfo(dir));
    }},
    // ── v1 ile aynı sözleşme, v2 uçlarıyla (ölçüm notları modülde) ──────────
    // "Değişiklikler": kullanıcı açınca çekilir, yoklamaya binmez.
    { method: 'GET', pattern: '/diff', handler: async (req, res) => {
      const p = new URL(req.url, 'http://x').searchParams;
      const r = await opencode2App.sessionDiff({ sessionId: p.get('session') || p.get('sessionId') || '' });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Checkpoint listesi — kullanıcı mesajları; kullanıcı açınca çekilir.
    { method: 'GET', pattern: '/checkpoints', handler: async (req, res) => {
      const p = new URL(req.url, 'http://x').searchParams;
      const r = await opencode2App.listCheckpoints({ sessionId: p.get('session') || p.get('sessionId') || '' });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/revert', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.revertToMessage({ sessionId: b.sessionId || b.session, messageID: b.messageID || b.messageId });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/unrevert', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.unrevertSession({ sessionId: b.sessionId || b.session });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Bağlam özetleme — fire-and-forget, sonuç snapshot'la akar.
    { method: 'POST', pattern: '/compact', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.compact({ sessionId: b.sessionId || b.session });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Özel komutlar: liste 60 sn önbellekli, çalıştırma bir tur koşar.
    // directory query: komutlar proje dizinine göre kayıtlı (ev dizininde boş);
    // verilmezse köprü en yeni açık oturumun cwd'sini kullanır.
    { method: 'GET', pattern: '/commands', handler: async (req, res) => {
      const dir = new URL(req.url, 'http://x').searchParams.get('directory') || '';
      json(res, 200, await opencode2App.listCommands(dir));
    }},
    { method: 'POST', pattern: '/command', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.runCommand({
        sessionId: b.sessionId || b.session,
        command: b.command,
        arguments: b.arguments ?? b.args,
      });
      json(res, r.ok ? 200 : 400, r);
    }},
    // AGENTS.md init — v2'de özel uç yok; prompt turu olarak koşar.
    { method: 'POST', pattern: '/init', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.initAgentsFile({ sessionId: b.sessionId || b.session });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Pin/rename — köprü-lokal metadata (v1 ile aynı sözleşme).
    { method: 'POST', pattern: '/pin', handler: async (req, res) => {
      const b = await body(req);
      const r = opencode2App.pinThread({ id: b.id });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/unpin', handler: async (req, res) => {
      const b = await body(req);
      const r = opencode2App.unpinThread({ id: b.id });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/rename', handler: async (req, res) => {
      const b = await body(req);
      const r = opencode2App.renameThread({ id: b.id, title: b.title });
      json(res, r.ok ? 200 : 400, r);
    }},
    // ── Steer (26.09.2026 dalga 2): süren tura enjeksiyon — v2'de native, ──
    // v1'de hiç yoktu. Telefonda "Yönlendir" tuşu bu ucu çağırır
    // (MidTurnSend.kt: POST /<b>/steer, capability userInputSteer).
    { method: 'POST', pattern: '/steer', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.steer({ sessionId: b.sessionId || b.session, text: b.text });
      json(res, r.ok ? 200 : 400, r);
    }},
    // ── Fork (buradan çatalla): orijinale dokunmadan hedef mesajın öncesine ──
    // kadar kopya çocuk oturum. dropUserTurns sayımı rewind ile aynı; yanıt
    // {ok, sessionId} — telefonda yeni sekmede açılır (claude ile aynı).
    { method: 'POST', pattern: '/fork-from', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.forkFromMessage({ sessionId: b.sessionId || b.session, dropUserTurns: b.dropUserTurns });
      json(res, r.ok ? 200 : 400, r);
    }},
    // ── Oturum talimatları: AGENTS.md'ye dokunmadan oturuma kalıcı kural. ───
    // Telefon UI'sı henüz yok; REST/web'den kullanılır.
    { method: 'GET', pattern: '/instructions', handler: async (req, res) => {
      const p = new URL(req.url, 'http://x').searchParams;
      const r = await opencode2App.listInstructions({ sessionId: p.get('session') || p.get('sessionId') || '' });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/instructions', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.putInstruction({ sessionId: b.sessionId || b.session, key: b.key, value: b.value });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'DELETE', pattern: '/instructions', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.deleteInstruction({ sessionId: b.sessionId || b.session, key: b.key });
      json(res, r.ok ? 200 : 400, r);
    }},
    // ── Kayıtlı izin kuralları: "always" birikiminin listesi ve tek tek ────
    // kaldırılması. Telefon UI'sı henüz yok; REST/web'den kullanılır.
    { method: 'GET', pattern: '/permissions/saved', handler: async (req, res) => {
      json(res, 200, await opencode2App.listSavedPermissions());
    }},
    { method: 'POST', pattern: '/permissions/saved/delete', handler: async (req, res) => {
      const b = await body(req);
      const r = await opencode2App.deleteSavedPermission({ id: b.id });
      json(res, r.ok ? 200 : 400, r);
    }},
    // ── v1 sökülürken buraya TAŞINAN uçlar (30.09.2026) ───────────────────
    // RunPod pod denetimi: sağlayıcı `runpod` (127.0.0.1:18001 SSH tüneli)
    // OpenCode config'inde duruyor, pod'u açıp kapatan uç de OpenCode
    // sekmesinin altında. Sunucu sürümünden bağımsız, runpod modülü işi yapar.
    { method: 'GET', pattern: '/runpod/status', handler: async (req, res) => {
      json(res, 200, await runpod.status());
    }},
    { method: 'POST', pattern: '/runpod/start', handler: (req, res) => {
      const r = runpod.start();
      json(res, r.ok ? 202 : 409, r);
    }},
    { method: 'POST', pattern: '/runpod/stop', handler: (req, res) => {
      const r = runpod.stop();
      json(res, r.ok ? 202 : 409, r);
    }},
    // ── Silinmis oturum kalintilarinin imhasi (30.09.2026) ────────────────
    // opencode'da oturum silmek icerigi yok etmiyor: freelist sayfalari ve
    // checkpoint edilmemis -wal cerceveleri mesaj metnini aynen tutuyor.
    // Telefon "Ayarlar > Gelismis" kartindan tetikler; is CoworkSpaces
    // altindaki oturum-imha.ps1'de (masaustunden de ayni betik kosuyor).
    // ?refresh=1 TTL'i atlayip yeniden tarar; tarama 300 MB okudugu icin
    // varsayilan olarak 60 sn onbelleklenir.
    { method: 'GET', pattern: '/purge/status', handler: async (req, res) => {
      const p = new URL(req.url, 'http://x').searchParams;
      const refresh = p.get('refresh') === '1' || p.get('refresh') === 'true';
      json(res, 200, await oturumImha.report({ refresh }));
    }},
    { method: 'POST', pattern: '/purge/run', handler: async (req, res) => {
      const b = await body(req);
      const r = oturumImha.run({
        eskiDb: !!b.eskiDb,
        snapshotSil: !!b.snapshot,
        loguKoru: !!b.loguKoru,
        kanit: Array.isArray(b.kanit) ? b.kanit : [],
      });
      json(res, r.ok ? 202 : 409, r);
    }},
    // OpenCode MCP yönetimi: opencode.json `mcp` anahtarını DOSYADAN okuyup
    // yazar (CLI/sunucu gerekmez), v2 aynı dosyayı okuyor. Değişiklik yeni
    // sunucu sürecinde geçerli olur — çalışan sunucu config'i yeniden okumaz.
    { method: 'GET', pattern: '/mcp/servers', handler: (req, res) => {
      json(res, 200, opencodeMcp.listServers());
    }},
    { method: 'POST', pattern: '/mcp/server', handler: async (req, res) => {
      const b = await body(req);
      const r = opencodeMcp.saveServer(b);
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/mcp/remove', handler: async (req, res) => {
      const b = await body(req);
      const r = opencodeMcp.removeServer({ name: b.name });
      json(res, r.ok ? 200 : 404, r);
    }},
    { method: 'POST', pattern: '/mcp/toggle', handler: async (req, res) => {
      const b = await body(req);
      const r = opencodeMcp.toggleServer({ name: b.name, enabled: !!b.enabled });
      json(res, r.ok ? 200 : 404, r);
    }},
  ],
});
registerBackend(router, 'omp', omp, {
  getModels: () => omp.getModels(),
  defaultModel: () => omp.defaultModel(),
  extras: [
    { method: 'GET', pattern: '/info', handler: async (req, res) => json(res, 200, await omp.getInfo()) },
    { method: 'GET', pattern: '/mcp/servers', handler: async (req, res) => json(res, 200, await omp.listMcpServers()) },
    { method: 'GET', pattern: '/thought', handler: (req, res) => {
      const url = new URL(req.url, 'http://x');
      json(res, 200, omp.getThought(url.searchParams.get('sessionId') || '', parseInt(url.searchParams.get('i') || '0', 10)));
    }},
    { method: 'POST', pattern: '/approve', handler: async (req, res) => {
      const b = await body(req);
      const r = omp.approve({ sessionId: b.sessionId, allow: b.allow === true, answers: b.answers, requestId: b.requestId });
      json(res, r.ok ? 200 : (r.stale ? 409 : 400), r);
    }},
    { method: 'POST', pattern: '/model', handler: async (req, res) => {
      const b = await body(req); const r = await omp.setModel({ sessionId: b.sessionId, model: b.model });
      json(res, r.ok ? 200 : 400, r);
    }},
    // Sekme işlemleri (sabitle/arşivle/yeniden adlandır) — codex-app ile aynı sözleşme.
    { method: 'GET', pattern: '/disk-sessions-search', handler: async (req, res) => {
      const params = new URL(req.url, 'http://x').searchParams;
      const archived = params.get('archived');
      const pinned = params.get('pinned');
      json(res, 200, await omp.listDiskSessionsWithQuery({
        query: params.get('q') || '',
        archived: archived === 'true' ? true : archived === 'false' ? false : null,
        pinned: pinned === 'true' ? true : pinned === 'false' ? false : null,
      }));
    }},
    { method: 'POST', pattern: '/pin', handler: async (req, res) => {
      const b = await body(req); json(res, 200, omp.pinThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/unpin', handler: async (req, res) => {
      const b = await body(req); json(res, 200, omp.unpinThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/archive', handler: async (req, res) => {
      const b = await body(req); json(res, 200, omp.archiveThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/unarchive', handler: async (req, res) => {
      const b = await body(req); json(res, 200, omp.unarchiveThread({ id: b.id }));
    }},
    { method: 'POST', pattern: '/rename', handler: async (req, res) => {
      const b = await body(req); json(res, 200, omp.renameThread({ id: b.id, title: b.title }));
    }},
    // Tur sürerken mesaj: steer keser, follow-up sıraya alır (native RPC).
    { method: 'POST', pattern: '/steer', handler: async (req, res) => {
      const b = await body(req);
      const r = await omp.steer({ sessionId: b.sessionId, text: b.text });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/follow-up', handler: async (req, res) => {
      const b = await body(req);
      const r = await omp.followUp({ sessionId: b.sessionId, text: b.text });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/efforts', handler: (req, res) => json(res, 200, { efforts: omp.THINKING_LEVELS }) },
    { method: 'POST', pattern: '/effort', handler: async (req, res) => {
      const b = await body(req); const r = await omp.setEffort({ sessionId: b.sessionId, effort: b.effort });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'GET', pattern: '/permission-modes', handler: (req, res) => json(res, 200, { modes: omp.PERMISSION_MODES }) },
    { method: 'POST', pattern: '/permission-mode', handler: async (req, res) => {
      const b = await body(req); const r = await omp.setPermissionMode({ sessionId: b.sessionId, permissionMode: b.permissionMode || b.mode });
      json(res, r.ok ? 200 : 400, r);
    }},
    { method: 'POST', pattern: '/compact', handler: async (req, res) => {
      const b = await body(req); const r = await omp.compact({ sessionId: b.sessionId });
      json(res, r.ok ? 200 : 400, r);
    }},
  ],
});
registerMcp(router, { mcp });
registerUpdate(router);

// agy runs as agy.exe under a PTY (no powershell wrapper). The bridge owns the PTY
// handles in the session map, so list/kill from there instead of the wrapper scan.
router.get('/agy/processes', (req, res) => json(res, 200, agy.listLiveProcesses()));
router.post('/agy/kill-all', (req, res) => json(res, 200, agy.killAllSessions()));
router.get('/opencode2-app/processes', (req, res) => json(res, 200, opencode2App.listLiveProcesses()));
router.post('/opencode2-app/kill-all', (req, res) => json(res, 200, opencode2App.killAllSessions()));
router.get('/codex-app/processes', (req, res) => json(res, 200, codexApp.listLiveProcesses()));
router.post('/codex-app/kill-all', (req, res) => json(res, 200, codexApp.killAllSessions()));
router.get('/claude-app/processes', (req, res) => json(res, 200, claudeApp.listLiveProcesses()));
router.post('/claude-app/kill-all', (req, res) => json(res, 200, claudeApp.killAllSessions()));
router.get('/omp/processes', (req, res) => json(res, 200, omp.listLiveProcesses()));
router.post('/omp/kill-all', (req, res) => json(res, 200, omp.killAllSessions()));

// ── Rewind (mesaja geri dön): POST /<backend>/rewind { sessionId, dropUserTurns }
// dropUserTurns = hedef kullanıcı mesajından (dahil) sona kadarki kullanıcı-mesajı
// sayısı. Sondan sayım: telefon ve bridge görünümleri cap yüzünden farklı uzunlukta
// olsa da hedef→son aralığı iki tarafta aynıdır. claude-app yeni sessionId dönebilir
// (transcript-fork). opencode-app'te bu ucun kendi mekanizması YOK: hedefi mesaj
// kimliğine çevirip /opencode-app/revert ile aynı gövdeye düşüyor, böylece iki
// kapıdan da aynı "geri sarıldı" durumu (ve unrevert şansı) çıkıyor.
router.post('/claude-app/rewind', async (req, res) => {
  const b = await body(req);
  const r = claudeApp.rewindSession({ sessionId: b.sessionId, dropUserTurns: b.dropUserTurns });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/codex-app/rewind', async (req, res) => {
  const b = await body(req);
  const r = await codexApp.rewindSession({ sessionId: b.sessionId, dropUserTurns: b.dropUserTurns });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/opencode2-app/rewind', async (req, res) => {
  const b = await body(req);
  const r = await opencode2App.rewindSession({ sessionId: b.sessionId, dropUserTurns: b.dropUserTurns });
  json(res, r.ok ? 200 : 400, r);
});
// Compact (opencode bağlamını AI ile özetle) OpenCode backend'inin extras
// listesinde: `/opencode2-app/compact`. v1'in ayrı top-level ucu söküldü.

// ── Fork (buradan çatalla): POST /<backend>/fork-from { sessionId, dropUserTurns }
// Rewind ile aynı sayım; farkı orijinal oturumun aynen kalması ve cevaptaki YENİ
// sessionId'nin telefonda ayrı sekmede açılması. opencode sunucusunda fork ucu yok.
router.post('/claude-app/fork-from', async (req, res) => {
  const b = await body(req);
  const r = claudeApp.forkFromMessage({ sessionId: b.sessionId, dropUserTurns: b.dropUserTurns });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/codex-app/fork-from', async (req, res) => {
  const b = await body(req);
  const r = await codexApp.forkFromMessage({ sessionId: b.sessionId, dropUserTurns: b.dropUserTurns });
  json(res, r.ok ? 200 : 400, r);
});
router.post('/omp/fork-from', async (req, res) => {
  const b = await body(req);
  const r = await omp.forkFromMessage({ sessionId: b.sessionId, dropUserTurns: b.dropUserTurns });
  json(res, r.ok ? 200 : 400, r);
});

// ── Seçici sabitlemeleri (yıldızlar) ───────────────────────────────────────
// İstemci tarafındaki SelectorPinStore'un köprü aynası. Yıldız cihaza değil
// buraya yazılır: telefon ve tablet aynı köprüye bağlandığından aralarında
// paylaşılır. Okuma auth kapısının arkasında — token'sız istek zaten 401 alır,
// /model-visibility'nin yaptığı gibi ayrı bir local muafiyet gerekmez.
router.get('/ui-pins', async (req, res) => {
  json(res, 200, readUiPins());
});
router.post('/ui-pins', async (req, res) => {
  const b = await body(req);
  if (!b || typeof b.scope !== 'string' || !b.scope.trim()) {
    return json(res, 400, { ok: false, error: 'scope gerekli' });
  }
  // Kapsam adı SelectorPinStore üretir (backend-model:<id> vb.) ama dosyaya
  // elle de yazılabilir; kontrol path-traversal değil okunabilirlik için:
  // satır atlatan (newline) içerik JSON'a zaten giremez, yine de mantıksız
  // anahtar biriktirmeyelim.
  if (!/^[\w:.@-]+$/.test(b.scope.trim())) {
    return json(res, 400, { ok: false, error: 'geçersiz scope' });
  }
  if (!Array.isArray(b.pinned)) {
    return json(res, 400, { ok: false, error: 'pinned dizisi gerekli' });
  }
  try {
    writeUiPinScope(b.scope.trim(), b.pinned);
    json(res, 200, { ok: true });
  } catch (e) {
    return json(res, 500, { ok: false, error: String(e?.message || e) });
  }
});

const server = http.createServer(async (req, res) => {
  // /health auth istemez: masaüstü bekçi scripti için canlılık ucu (veri sızdırmaz).
  if (req.method === 'GET' && req.url.split('?')[0] === '/health') {
    return json(res, 200, { ok: true, protocolVersion: PROTOCOL_VERSION, protocolMinClient: 2 });
  }
  // Tarayıcı arayüzü (/ui/*) auth'tan ÖNCE servis edilir — gerekçesi
  // web-ui.mjs'in başındaki notta. Paket istemci kodu, sır taşımaz; API
  // uçlarının hepsi aşağıdaki auth kapısının arkasında kalır.
  if (serveWebUi(req, res)) return;
  const pathname = new URL(req.url, 'http://x').pathname;
  if (pathname !== '/pairing/complete' && !auth(req)) {
    return json(res, 401, { error: 'unauthorized' });
  }
  const url = new URL(req.url, 'http://x');
  try {
    for (const [key, value] of url.searchParams) {
      const reason = PATH_KEYS.has(key) ? unsafePathReason(value) : null;
      if (reason) throw new UnsafePathError(reason);
    }
    const xDirReason = unsafePathReason(String(req.headers['x-dir'] || ''));
    if (xDirReason) throw new UnsafePathError(xDirReason);
    const route = router.match(req.method, url.pathname);
    if (route) return await route.handler(req, res);
    return json(res, 404, { error: 'not found' });
  } catch (e) {
    if (e instanceof BodyTooLargeError) return rejectTooLarge(req, res);
    if (e instanceof UnsafePathError) return json(res, 400, { ok: false, error: e.message });
    return json(res, 500, { error: String(e.message || e) });
  }
});

// ── WebSocket servers ──────────────────────────────────────────────────────
const wssCodexApp = new WebSocketServer({ noServer: true });
const wssClaudeApp = new WebSocketServer({ noServer: true });
const wssOpenCode2App = new WebSocketServer({ noServer: true });
const wssOmp = new WebSocketServer({ noServer: true });
const wssAgy = new WebSocketServer({ noServer: true });

// Akış el sıkışması kimliği bağlantı kurulmadan doğrulanır: başlıktaki token
// (Android) ya da tek kullanımlık bilet (tarayıcı). Bilet burada harcandığı için
// kimlik isteğe yazılır, bağlantı işleyicilerindeki auth() onu okur.
function upgradeIdentity(req) {
  const fromHeader = requestToken(req) ? authIdentity(req) : null;
  if (fromHeader) return fromHeader;
  return wsTickets.redeem(new URL(req.url, 'http://x').searchParams.get('ticket'));
}

// Cihaz kimliğine göre açık akışlar: cihaz iptal edildiğinde ya da anahtarı
// yenilendiğinde o cihazın akışları hemen kapatılır.
const socketsByDevice = new Map();
function trackSocket(sock, identity) {
  if (identity?.kind !== 'device') return;
  let set = socketsByDevice.get(identity.id);
  if (!set) socketsByDevice.set(identity.id, set = new Set());
  set.add(sock);
  sock.on('close', () => { set.delete(sock); if (!set.size) socketsByDevice.delete(identity.id); });
}
function closeSocketsOf(deviceId) {
  for (const sock of socketsByDevice.get(deviceId) || []) { try { sock.close(1008, 'device key revoked'); } catch {} }
}

server.on('upgrade', (req, socket, head) => {
  const u = new URL(req.url, 'http://x');
  const identity = upgradeIdentity(req);
  if (!identity) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  req.wsIdentity = identity;
  if (u.pathname === '/codex-app/stream') {
    wssCodexApp.handleUpgrade(req, socket, head, sock => { trackSocket(sock, identity); wssCodexApp.emit('connection', sock, req); });
  } else if (u.pathname === '/claude-app/stream') {
    wssClaudeApp.handleUpgrade(req, socket, head, sock => { trackSocket(sock, identity); wssClaudeApp.emit('connection', sock, req); });
  } else if (u.pathname === '/opencode2-app/stream') {
    wssOpenCode2App.handleUpgrade(req, socket, head, sock => { trackSocket(sock, identity); wssOpenCode2App.emit('connection', sock, req); });
  } else if (u.pathname === '/omp/stream') {
    wssOmp.handleUpgrade(req, socket, head, sock => { trackSocket(sock, identity); wssOmp.emit('connection', sock, req); });
  } else if (u.pathname === '/agy/stream') {
    wssAgy.handleUpgrade(req, socket, head, sock => { trackSocket(sock, identity); wssAgy.emit('connection', sock, req); });
  } else {
    socket.destroy();
  }
});

wssCodexApp.on('connection', (sock, req) => {
  if (!auth(req)) { sock.close(1008, 'unauthorized'); return; }
  const params = new URL(req.url, 'http://x').searchParams;
  const sid = params.get('session') || '';
  codexApp.subscribe(sid, sock, { since: params.get('since'), delta: params.get('delta') === '1' });
});

wssClaudeApp.on('connection', (sock, req) => {
  if (!auth(req)) { sock.close(1008, 'unauthorized'); return; }
  const params = new URL(req.url, 'http://x').searchParams;
  const sid = params.get('session') || '';
  claudeApp.subscribe(sid, sock, { since: params.get('since'), delta: params.get('delta') === '1' });
});

wssOpenCode2App.on('connection', (sock, req) => {
  if (!auth(req)) { sock.close(1008, 'unauthorized'); return; }
  const params = new URL(req.url, 'http://x').searchParams;
  opencode2App.subscribe(params.get('session') || '', sock, { since: params.get('since'), delta: params.get('delta') === '1' });
});

wssOmp.on('connection', (sock, req) => {
  if (!auth(req)) { sock.close(1008, 'unauthorized'); return; }
  const params = new URL(req.url, 'http://x').searchParams;
  omp.subscribe(params.get('session') || '', sock, { since: params.get('since'), delta: params.get('delta') === '1' });
});



wssAgy.on('connection', (sock, req) => {
  if (!auth(req)) { sock.close(1008, 'unauthorized'); return; }
  const params = new URL(req.url, 'http://x').searchParams;
  agy.subscribe(params.get('session') || '', sock, { since: params.get('since'), delta: params.get('delta') === '1' });
});

// ── Startup ───────────────────────────────────────────────────────────────
if (import.meta.main) {
  try { codexApp.cleanupTmp(); opencode2App.cleanupTmp(); agy.cleanupTmp(); } catch (e) { console.warn('[server] startup cleanup failed:', e); }

  // bridge.log rotasyonu (madde 14): açılışta bir kez, sonra her 10 dakikada
  // bir boyut kontrolü. 5 MB üzeri → .1'e taşı, en fazla 3 nesil (~15 MB tavan).
  // logger append-sync yazdığı için rotate güvenle rename yapıp yeni boş dosyaya
  // devam eder. (supervisor-out.log / supervisor-err.log vestigiyaldi — hiçbir
  // kod yazmıyordu, rotasyon hedeflerinden çıkarıldı.)
  console.log('===== bridge start =====');
  rotateLogger();
  setInterval(() => {
    try {
      const r = rotateLogger();
      if (r?.rotated) console.log('[server] bridge.log rotated');
    } catch (e) {
      console.warn('[server] log rotation failed:', e?.message || e);
    }
  }, ROTATION_DEFAULTS.intervalMs).unref?.();

  // opencode disk bakımı (6 saatlik sweepDisk) v1 ile birlikte GİTTİ: v2
  // adaptörünün böyle bir ucu yok, v2 kendi deposunu yönetiyor. Genel oturum
  // budaması session-prune.mjs'de sürüyor (DISK_SESSION_SOURCES).

  server.listen(cfg.port, cfg.host, () => {
    console.log(`Bridge listening on http://${cfg.host}:${cfg.port}  (auth required)`);
    // Hiçbir yerden link verilmeyen sayfa; adresi burada yazılmazsa
    // kullanıcı bilmediği bir yolu tahmin etmek zorunda kalıyor.
    console.log(`  Web UI            http://localhost:${cfg.port}/ui/`);
  });
  // Disk oturum taramasi soguk halde ~7s surer (Windows dosya-acilis maliyeti);
  // ilk telefon istegi timeout yememesi icin acilista arka planda isit.
  claudeApp.warmDiskSessionsCache();
  // Arama sozlugu: ilk global aramanin 531 MB'lik transcript havuzunu ayristirmasini
  // beklememek icin acilista parca parca kurulur (event loop'u kilitlemez).
  claudeApp.warmSearchIndex();
}

export { auth, readWorkspaceFile, resolveWorkspacePath, getActiveState };
