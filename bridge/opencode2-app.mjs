// OpenCode 2 app-server backend — `@opencode/cli` (v2) `opencode serve` sunucusu.
//
// opencode-app.mjs'in v2 kardeşi. AYRI BİR BACKEND olmasının sebebi: v2'nin
// sunucu API'si kasıtlı olarak kırıcı (breaking) — yol öneki `/api`, kimlik
// doğrulama HTTP Basic, olay akışı tamamen farklı bir sözlük kullanıyor. v1
// adaptörünü parametreleştirmek iki protokolü tek dosyada karıştırırdı; üstelik
// telefonda ikisi yan yana durabilsin isteniyor (v1 köprüsü 4096 sunucusuyla
// çalışmaya devam ediyor).
//
// PROTOKOL NOTLARI — 24.09.2026'da canlı v2.0.16 sunucusuna karşı ölçüldü
// (ölçüm betikleri: CoworkSpaces\opencode-v2\scripts\probe-*.mjs, ham olay
// kaydı logs\turn-events.ndjson):
//   • Spawn: `opencode serve --hostname 127.0.0.1 --port 0`. stdout'a İKİ satır
//     yazar ve İKİSİ de gerekir:
//       "server listening on http://127.0.0.1:<port>"
//       "server password <token>"
//     Şifre her sunucu örneğinde YENİDEN üretilir; `opencode pair` çıktısındaki
//     şifre ARKA PLAN SERVİSİNE aittir, bizim çocuğumuza değil (ölçüldü: o
//     şifreyle bizim porta 401 döner).
//   • Kimlik: `Authorization: Basic base64("opencode:<password>")`. Şifresiz her
//     istek 401; /doc ve web arayüzü açıktır, bu yüzden "200 geldi" kimlik
//     doğrulamasının çalıştığını GÖSTERMEZ.
//   • Oturum aç:   POST /api/session  {title?, location:{directory}}  → {data:{id:"ses_..."}}
//   • Mesaj:       POST /api/session/:id/prompt {text, delivery:"steer"|"queue"}
//                  → 200 + {data:{id:"msg_..."}}; tur SSE üzerinden akar.
//   • Kesme:       POST /api/session/:id/interrupt
//   • İzin yanıtı: POST /api/session/:id/permission/:requestID/reply {decision:"once"|"always"|"reject"}
//                  → 204
//   • Modeller:    GET /api/model?directory=<cwd>   (directory YOKSA liste BOŞ döner)
//   • Ajanlar:     GET /api/agent?directory=<cwd>
//   • Oturumlar:   GET /api/session, mesajlar GET /api/session/:id/message (yeniden eskiye)
//   • Olaylar:     GET /api/event (SSE, `data: {...}` satırları)
//
// SSE olay sözlüğü (ölçülen, v1'dekiyle HİÇ örtüşmüyor):
//   session.execution.started / .succeeded / .failed / .aborted  — tur sınırı
//   session.text.started / .delta / .ended                        — asistan metni
//   session.reasoning.started / .delta / .ended                   — düşünce
//   session.tool.input.started / .input.ended / .called / .progress / .success / .failed
//   session.step.started / .streamed / .ended                     — adım + token
//   session.usage.updated                                         — maliyet/token
//   session.inbox.enqueued / .delivered                           — kullanıcı mesajı
//   permission.asked / permission.replied                         — onay akışı
// Metin olayları DELTA taşır (v1'de part snapshot'ıydı): satıra EKLENİR.
//
// KÖK (30.09.2026'da DEĞİŞTİ): v2 artık GERÇEK config/veri kökünde koşuyor
// (`~/.config/opencode`, `~/.local/share/opencode`). Eskiden ayrı bir XDG kökü
// şarttı çünkü v2 ilk açılışta gerçek opencode.db üzerinde v1→v2 göçünü
// başlatıyordu ve bu, 4096 sunucusunu + v1 köprüsünü bozuyordu. O gün v1 tümüyle
// söküldü (npm `opencode-ai` kaldırıldı, global `opencode` = v2, 4096 servisi v2
// arka plan servisi), göç bilinçli olarak yapıldı ve db yedeklendi
// (CoworkSpaces\opencode-v2\yedek\gecis-20260930). İzolasyon artık YALNIZ
// testler için: AGENTBRIDGE_OPENCODE2_HOME verilirse XDG kökleri onun altına
// kurulur, verilmezse çocuk süreç hiçbir XDG değişkeni ALMAZ.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { hhmm, capMessages, logWarn, killChildTree, spawnAsync, resolveExecutableSync } from './session-utils.mjs';
import { createAgentSessionCore, ensureMessageRowIds, paginateSessionMessages } from './agent-session-core.mjs';
// Katalog süzgeci ve etiket kuralı v1 ile ORTAK: iki listede aynı modeller
// görünsün diye (kullanıcı isteği 26.09.2026). Tek kaynak bu modül.
import { isAgentBridgeOpencodeModel, opencodeModelLabel } from './opencode-model-filter.mjs';

// İzole kök YALNIZ testlerde verilir (bkz. yukarıdaki KÖK notu). Boşsa gerçek
// kök kullanılır ve XDG değişkenleri hiç yazılmaz.
const ISOLATED_HOME = String(process.env.AGENTBRIDGE_OPENCODE2_HOME || '').trim();
// Veri kökü: izole modda <home>/data, gerçek modda XDG_DATA_HOME ya da
// ~/.local/share. listDiskSessions buradan opencode.db'yi açıyor; yol yanlış
// olursa geçmiş SESSİZCE boş görünür, o yüzden tek yerden türetiliyor.
const DATA_ROOT = ISOLATED_HOME
  ? path.join(ISOLATED_HOME, 'data')
  : (String(process.env.XDG_DATA_HOME || '').trim() || path.join(os.homedir(), '.local', 'share'));
// Global `opencode` artık v2 (npm @opencode/cli). .cmd shim'i spawn edilirse
// Windows'ta stdout tamponlanıyor ve "listening on" satırı gecikiyor; v1'de
// öğrenilen düzeltme: shim'in yanındaki gerçek .exe'ye in.
function resolveOc2Exe() {
  const override = String(process.env.AGENTBRIDGE_OPENCODE2_EXE || '').trim();
  if (override) return override;
  const resolved = resolveExecutableSync('opencode');
  if (process.platform === 'win32' && /\.(cmd|ps1)$/i.test(resolved)) {
    const native = path.join(path.dirname(resolved), 'node_modules', '@opencode', 'cli', 'bin', 'opencode.exe');
    if (fs.existsSync(native)) return native;
  }
  return resolved;
}
const OC2_EXE = resolveOc2Exe();
// Varsayılan model: v2 model kimliği {providerID, id} çiftidir; köprü/telefon
// tarafında tek string taşındığı için "provider/model" biçimi kullanılır.
const DEFAULT_MODEL = process.env.AGENTBRIDGE_OPENCODE2_DEFAULT_MODEL || 'deepseek/deepseek-flash';
const SERVE_READY_MS = 45_000;
const REQ_TIMEOUT_MS = 30_000;
const SSE_RECONNECT_MS = 1500;
const MODEL_CACHE_MS = 60_000;

export const PERMISSION_MODES = [
  { id: 'ask', label: 'Ask' },
  { id: 'yolo', label: 'YOLO' },
];

// Canlı liste getModels() ile sunucudan okunur. SABİT FALLBACK YOK (26.09.2026
// 'da tamamen kaldırıldı): sunucu açılamadığında/boş katalogta dönülen 2 modellik
// statik liste GERÇEK durumu kullanıcıdan gizliyordu — telefon "hiçbir şey
// yüklenmiyor" derken gördüğü tam olarak bu yanıltıcı liste + boş disk
// oturumlarıydı. Boş liste dürüst sonuç: sunucu ayakta ve katalogu boşsa
// bilgidir, açılamadıysa hatadır — ikisi de gizlenmez.

export function defaultModel() { return DEFAULT_MODEL; }

const sessions = new Map();     // köprü uuid → oturum
const byRemote = new Map();     // ses_... → köprü uuid
const sessionResolvedListeners = new Set();

export function onSessionResolved(fn) {
  if (typeof fn !== 'function') return () => {};
  sessionResolvedListeners.add(fn);
  return () => sessionResolvedListeners.delete(fn);
}

function snapshot(s) {
  ensureMessageRowIds(s);
  return {
    type: 'conversation',
    sessionId: s.id,
    messages: s.messages,
    running: s.status === 'running',
    awaitingApproval: !!s.awaitingApproval,
    awaitingFirstOutput: !!s.awaitingFirstOutput,
    awaitingUserInput: false,
    contextTokens: s.contextTokens || 0,
    contextWindow: s.contextWindow || 0,
    contextPct: null,
    todos: [],
    approval: s.awaitingApproval ? s.pendingApproval : null,
    agent: s.agent || '',
    model: s.model || '',
    reverted: s.reverted || null,
  };
}

const sessionCore = createAgentSessionCore({ snapshot });
const pushSnapshot = sessionCore.pushSnapshot;

function capSess(s) {
  capMessages(s, undefined, evicted => {
    if (evicted.some(msg => !msg?.rowId)) s._deltaDirty = true;
  });
}

// ── Sunucu yaşam döngüsü ────────────────────────────────────────────────────
const server = {
  child: null,
  base: '',
  password: '',
  starting: null,
  sse: null,
  sseAbort: null,
};

function serveEnv() {
  if (!ISOLATED_HOME) return { ...process.env };
  return {
    ...process.env,
    XDG_CONFIG_HOME: path.join(ISOLATED_HOME, 'config'),
    XDG_DATA_HOME: path.join(ISOLATED_HOME, 'data'),
  };
}

// Skill envanteri v2'nin KENDİ kaydından okunur (`GET /api/skill`), diskten
// DEĞİL: v2 izole XDG kökünde koşuyor ve v1'in dizinleriyle (`~/.config/opencode`,
// `~/.claude/skills`) aynı listeyi görmüyor. Sunucu AYAKTA DEĞİLSE boş döner —
// bu uç teşhis amaçlı da çağrılıyor ve 205 MB'lık çocuğu uyandırmamalı
// (listDiskSessions'ta öğrenilen kural).
async function listSkills(directory) {
  if (!serverUp()) return [];
  const dir = String(directory || '').trim() || [...sessions.values()][0]?.cwd || os.homedir();
  try {
    const r = await api('GET', '/api/skill?directory=' + encodeURIComponent(dir));
    const data = Array.isArray(r.json?.data) ? r.json.data : [];
    return data.map(mapSkillInfo).filter(x => x.name);
  } catch (e) {
    logWarn('opencode2', 'listSkills', { error: e?.message || String(e) });
    return [];
  }
}

export async function getInfo(directory) {
  const skillDetails = await listSkills(directory);
  return {
    ok: !!server.base,
    // v1'in info sözleşmesiyle ortak alanlar: telefon ikisini de aynı
    // OpencodeAppInfo'ya ayrıştırıyor.
    serveAlive: serverUp(),
    skills: skillDetails.map(s => s.name),
    skillDetails,
    exe: OC2_EXE,
    home: ISOLATED_HOME || DATA_ROOT,
    base: server.base,
    pid: server.child?.pid || 0,
    sessions: sessions.size,
  };
}

async function ensureServer() {
  if (server.base && server.child && server.child.exitCode === null) return server.base;
  if (server.starting) return server.starting;
  server.starting = new Promise((resolve, reject) => {
    if (!fs.existsSync(OC2_EXE)) {
      server.starting = null;
      return reject(new Error('opencode v2 ikilisi yok: ' + OC2_EXE));
    }
    let settled = false;
    const child = spawn(OC2_EXE, ['serve', '--hostname', '127.0.0.1', '--port', '0'], {
      env: serveEnv(),
      cwd: os.homedir(),
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    server.child = child;
    server.base = '';
    server.password = '';
    let buf = '';
    const onLine = line => {
      const listen = line.match(/listening on\s+(http:\/\/\S+)/i);
      if (listen) server.base = listen[1].replace(/\/+$/, '');
      const pass = line.match(/server password\s+(\S+)/i);
      if (pass) server.password = pass[1];
      if (!settled && server.base && server.password) {
        settled = true;
        clearTimeout(timer);
        startEventStream();
        resolve(server.base);
      }
    };
    child.stdout.on('data', d => {
      buf += d.toString();
      let i;
      while ((i = buf.indexOf('\n')) >= 0) { onLine(buf.slice(0, i)); buf = buf.slice(i + 1); }
    });
    child.stderr.on('data', d => { const t = d.toString(); if (t.trim()) logWarn('opencode2', 'serve stderr', { text: t.slice(0, 300) }); });
    child.on('exit', (code, signal) => {
      logWarn('opencode2', 'serve exited', { code, signal });
      server.base = ''; server.password = ''; server.child = null;
      stopEventStream();
      for (const s of sessions.values()) {
        if (s.status === 'running') { s.status = 'idle'; s.awaitingFirstOutput = false; pushSnapshot(s); }
      }
    });
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { killChildTree(child.pid); } catch {}
      server.starting = null;
      reject(new Error('opencode v2 serve zamanında açılmadı'));
    }, SERVE_READY_MS);
  }).then(base => { server.starting = null; return base; },
          err => { server.starting = null; throw err; });
  return server.starting;
}

async function api(method, p, body) {
  const base = await ensureServer();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), REQ_TIMEOUT_MS);
  try {
    const r = await fetch(base + p, {
      method,
      headers: {
        Authorization: 'Basic ' + Buffer.from('opencode:' + server.password).toString('base64'),
        'content-type': 'application/json',
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal,
    });
    const text = await r.text();
    let json = null;
    if (text) { try { json = JSON.parse(text); } catch { json = text; } }
    return { status: r.status, ok: r.status >= 200 && r.status < 300, json };
  } finally { clearTimeout(timer); }
}

// Sunucuyu BAŞLATMADAN sorgula. Bu engel yalnız listDiskSessions'ta durur:
// sözleşme testi onu GERÇEKTEN çağırıyor (exe yolu makineye özel, spawn'lı
// sürüm testi exe'siz makinede kırdığı gibi süreci açık da tutuyordu) ve web
// ana sayfası her açılışta çekiyor. getModels/listAgents ise bilerek spawn
// eder (aşağıda) — telefonun OpenCode 2'ye ilk girişte model kataloğu
// dolu gelsin diye (kullanıcı kararı 26.09.2026); disk listesi models'ten
// SONRA çekildiği için sunucuyu zaten ayakta bulur.
function serverUp() { return !!(server.base && server.child && server.child.exitCode === null); }

// ── Olay akışı ──────────────────────────────────────────────────────────────
function stopEventStream() {
  try { server.sseAbort?.abort(); } catch {}
  server.sseAbort = null;
  server.sse = null;
}

async function startEventStream() {
  if (server.sse) return;
  const base = server.base;
  if (!base) return;
  const ctrl = new AbortController();
  server.sseAbort = ctrl;
  server.sse = true;
  try {
    const r = await fetch(base + '/api/event', {
      headers: {
        Authorization: 'Basic ' + Buffer.from('opencode:' + server.password).toString('base64'),
        accept: 'text/event-stream',
      },
      signal: ctrl.signal,
    });
    if (!r.ok || !r.body) throw new Error('event stream status ' + r.status);
    const reader = r.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const chunk = buf.slice(0, i); buf = buf.slice(i + 2);
        const line = chunk.split('\n').find(l => l.startsWith('data:'));
        if (!line) continue;
        let ev = null;
        try { ev = JSON.parse(line.slice(5).trim()); } catch { continue; }
        try { onEvent(ev); } catch (e) { logWarn('opencode2', 'event handler', { error: e.message, type: ev?.type }); }
      }
    }
  } catch (e) {
    if (!ctrl.signal.aborted) logWarn('opencode2', 'event stream', { error: e?.message || String(e) });
  } finally {
    server.sse = null;
    if (!ctrl.signal.aborted && server.base) setTimeout(() => startEventStream(), SSE_RECONNECT_MS);
  }
}

function sessionForEvent(ev) {
  const rid = ev?.data?.sessionID || '';
  if (!rid) return null;
  const id = byRemote.get(rid);
  return id ? sessions.get(id) : null;
}

// Akan metin/düşünce satırlarını mesaj id + ordinal ile anahtarlar; aynı
// anahtara gelen delta var olan satıra EKLENİR (v2 delta gönderir, snapshot değil).
function streamRow(s, key, role, initial = '') {
  const existing = s._streamRows.get(key);
  if (existing && s.messages.includes(existing)) return existing;
  const row = { role, text: initial, time: hhmm() };
  s.messages.push(row);
  s._streamRows.set(key, row);
  capSess(s);
  return row;
}

/**
 * DÜŞÜNCE İŞARETİ — satırı telefonda "Düşünce" grubuna sokan tek sinyal.
 *
 * Telefonun sınıflandırıcısı (`ChatUiModels.activityKind`) bir `thought`
 * satırını yalnız metni bu işaretle BAŞLIYORSA (ya da metni tamamen boşsa)
 * Düşünce sayıyor; `thoughtIndex` taşıyan ve metni dolu olan her satır Araç
 * grubuna gidiyor. İşaretsiz "Düşündü · …" etiketi bu yüzden araç adımlarıyla
 * aynı kartta, "Araç" başlığı altında birikiyordu (ölçüm 30.09.2026,
 * CoworkSpaces\opencode-v2\scripts\satir-sinif-olcum.mjs). codex-app aynı
 * sözleşmeye düz `'🧠'` metniyle uyuyor.
 *
 * Ayrım kullanıcı isteğiyle açıldı (30.09.2026): düşünce ve araç adımları iki
 * ayrı katlanabilir grup. Bedeli kabul edildi — gruplar tür başına toplandığı
 * için düşünce↔araç ARASINDAKİ kronoloji kartlar arasında görünmez olur
 * (grup İÇİNDE sıra korunur).
 */
const DUSUNCE_ISARETI = '🧠';

/**
 * Düşünce/araç satırının AYRINTI YUVASINI AÇAR (thoughtIndex).
 *
 * Yuva satır DOĞARKEN açılmak ZORUNDA. Telefonun görünüm modeli bir `thought`
 * satırını yalnız `thoughtIndex >= 0` ise "faaliyet" sayıp katlanabilir gruba
 * koyuyor (ChatUiModels.activityKind); indekssiz kısa satır ise bağımsız bir
 * durum şeridi olarak çiziliyor. Yuvayı sonradan açmak satırı önce ŞERİT,
 * birkaç yüz ms sonra GRUP ÜYESİ yapıyordu: ekranda satır görünüp kayboluyor,
 * LazyColumn anahtarı da (`rowId` → `grp_rowId`) değiştiği için kart yok edilip
 * yeniden kuruluyordu. Kullanıcı bildirdi 30.09.2026; ölçüm
 * CoworkSpaces\opencode-v2\scripts\titreme-deney.mjs (kare 7→8, 10→13, 14→15).
 *
 * codex-app ve omp-app zaten bu sözleşmeye uyuyor (satır `thoughtIndex` ile
 * push ediliyor) — titreme yalnız v2'de vardı.
 */
function thoughtSlot(s, row) {
  if (row.thoughtIndex === undefined) {
    row.thoughtIndex = s.toolDetails.length;
    s.toolDetails.push('');
  }
  return row.thoughtIndex;
}

function onEvent(ev) {
  const type = String(ev?.type || '');
  const d = ev?.data || {};
  const s = sessionForEvent(ev);
  if (!s) return;

  switch (type) {
    case 'session.execution.started':
      s.status = 'running';
      s.awaitingFirstOutput = true;
      pushSnapshot(s);
      break;

    case 'session.execution.succeeded':
    case 'session.execution.failed':
    case 'session.execution.aborted':
      s.status = 'idle';
      s.awaitingFirstOutput = false;
      s._streamRows.clear();
      if (type === 'session.execution.failed') {
        s.messages.push({ role: 'agent', text: '⚠ Tur hata ile bitti: ' + (d.error?.message || d.error || 'bilinmeyen hata'), time: hhmm() });
        capSess(s);
      }
      pushSnapshot(s);
      break;

    case 'session.text.started':
      s.awaitingFirstOutput = false;
      streamRow(s, `t:${d.assistantMessageID}:${d.ordinal}`, 'agent');
      pushSnapshot(s);
      break;

    case 'session.text.delta': {
      const row = streamRow(s, `t:${d.assistantMessageID}:${d.ordinal}`, 'agent');
      row.text += String(d.delta || '');
      s.awaitingFirstOutput = false;
      sessionCore.throttledPush(s);
      break;
    }

    case 'session.text.ended': {
      const row = streamRow(s, `t:${d.assistantMessageID}:${d.ordinal}`, 'agent');
      if (typeof d.text === 'string' && d.text) row.text = d.text;
      pushSnapshot(s);
      break;
    }

    case 'session.reasoning.started':
      // thoughtSlot ilk karede: yoksa satır bir kare şerit, sonra grup üyesi olur.
      thoughtSlot(s, streamRow(s, `r:${d.assistantMessageID}:${d.ordinal}`, 'thought', DUSUNCE_ISARETI + ' Düşünüyor…'));
      pushSnapshot(s);
      break;

    case 'session.reasoning.delta': {
      const key = `r:${d.assistantMessageID}:${d.ordinal}`;
      const row = streamRow(s, key, 'thought', '');
      const full = (s._thoughtText.get(key) || '') + String(d.delta || '');
      s._thoughtText.set(key, full);
      // Satırda yalnız kısa etiket durur; tam metin toolDetails'e gider ve
      // telefon ona dokununca /thought ucundan çekilir (v1 ile aynı sözleşme).
      s.toolDetails[thoughtSlot(s, row)] = full;
      row.text = DUSUNCE_ISARETI + ' Düşünüyor · ' + full.replace(/\s+/g, ' ').slice(-60);
      sessionCore.throttledPush(s);
      break;
    }

    case 'session.reasoning.ended': {
      const key = `r:${d.assistantMessageID}:${d.ordinal}`;
      const row = s._streamRows.get(key);
      const full = (typeof d.text === 'string' && d.text) ? d.text : (s._thoughtText.get(key) || '');
      if (row) {
        s.toolDetails[thoughtSlot(s, row)] = full;
        row.text = DUSUNCE_ISARETI + ' Düşündü · ' + full.replace(/\s+/g, ' ').slice(0, 60);
      }
      pushSnapshot(s);
      break;
    }

    case 'session.tool.input.started': {
      const row = streamRow(s, `x:${d.id}`, 'thought', String(d.name || 'araç'));
      row.text = String(d.name || 'araç');
      // Girdi henüz gelmedi ama yuva ŞİMDİ açılır: satır ilk karede de araç
      // grubuna ait olsun (bkz. thoughtSlot KDoc'u — titreme).
      thoughtSlot(s, row);
      s.awaitingFirstOutput = false;
      pushSnapshot(s);
      break;
    }

    case 'session.tool.called': {
      const row = streamRow(s, `x:${d.id}`, 'thought');
      const input = d.input ? JSON.stringify(d.input) : '';
      s.toolDetails[thoughtSlot(s, row)] = 'girdi: ' + input.slice(0, 4000);
      if (input) row.text = row.text + ' · ' + input.replace(/\s+/g, ' ').slice(0, 60);
      pushSnapshot(s);
      break;
    }

    case 'session.tool.success':
    case 'session.tool.failed': {
      const row = streamRow(s, `x:${d.id}`, 'thought');
      const ok = type.endsWith('success');
      const body = Array.isArray(d.content)
        ? d.content.map(c => (typeof c?.text === 'string' ? c.text : '')).join('\n')
        : String(d.error?.message || d.error || '');
      const yuva = thoughtSlot(s, row);
      s.toolDetails[yuva] = (s.toolDetails[yuva] || '') + '\n\nçıktı:\n' + body.slice(0, 8000);
      row.text = (ok ? '✓ ' : '✗ ') + row.text.replace(/^[✓✗] /, '');
      pushSnapshot(s);
      break;
    }

    case 'session.usage.updated':
      s.contextTokens = Number(d.tokens?.input || 0) + Number(d.tokens?.output || 0);
      s.cost = Number(d.cost || 0);
      break;

    // Compact (bağlam özetleme) — POST /compact bir inbox compaction mesajı
    // kuyruğa koyar; sonuç bu olaylardan akar (ölçüm 26.09.2026: started ve
    // failed canlıda görüldü; başarılı adı sürümde değişebilir, o yüzden önek
    // eşleşmesi). compact() "⏳ özetleniyor" satırını basar, burada tamamlanır.
    case 'session.compaction.failed':
      s._compacting = false;
      s.messages.push({ role: 'agent', text: '⚠️ Özetleme başarısız: ' + (d.error?.message || d.error || 'bilinmeyen hata'), time: hhmm() });
      capSess(s);
      pushSnapshot(s);
      break;

    case 'session.compaction.succeeded':
    case 'session.compaction.completed':
      s._compacting = false;
      s.messages.push({ role: 'agent', text: '✅ Bağlam özetlendi — sonraki tur küçültülmüş bağlamla çalışır.', time: hhmm() });
      capSess(s);
      pushSnapshot(s);
      break;

    case 'permission.asked': {
      const resources = Array.isArray(d.resources) ? d.resources.join(', ') : '';
      s.pendingApproval = {
        id: d.id,
        requestId: d.id,
        summary: `${d.action || 'işlem'}${resources ? ' · ' + resources : ''}`,
        description: 'OpenCode 2 izin istiyor',
        tool: d.source?.type === 'tool' ? (d.source.id || 'tool') : (d.source?.type || ''),
        patterns: Array.isArray(d.save) ? d.save : [],
        diff: '',
        raw: d,
      };
      s.awaitingApproval = true;
      s.awaitingFirstOutput = false;
      pushSnapshot(s);
      break;
    }

    case 'permission.replied':
      if (s.pendingApproval && s.pendingApproval.id === d.requestID) {
        s.pendingApproval = null;
        s.awaitingApproval = false;
        pushSnapshot(s);
      }
      break;

    default:
      break;
  }
}

// Model.Info → köprü sözleşmesi. SAF: sunucusuz test edilir (bkz. __testMap*).
//
// Efor kademeleri ("variant") v2'de Model.Info.variants DİZİSİNDE, her biri
// {id, settings, ...}. v1'de kaynak /config/providers'in variants NESNESİYDİ;
// şema farklı, sözleşme aynı: ad listesi. Bunlar taşınmayınca telefonda efor
// çipi HİÇ çizilmiyordu (backendEffortSupported boş variants listesine bakıyor).
function mapCatalogModel(m) {
  const id = `${m?.providerID}/${m?.modelID || m?.id}`;
  // Dostane ad ETİKETE yazılmıyor: arayüz "saglayici/model" kimliğini ikiye
  // ayırıp alt satıra sağlayıcıyı yazıyor (ui3ModelAdiAyir) ve etikette boşluk
  // görünce bu ayrımı YAPMIYOR — v2'de sağlayıcı satırı bu yüzden hiç
  // çizilmiyordu. Ad, alt satıra sağlayıcının yanına `detail` olarak gidiyor.
  const ad = String(m?.name || '').trim();
  return {
    id,
    label: opencodeModelLabel(id),
    detail: ad && ad !== id ? ad : '',
    variants: Array.isArray(m?.variants)
      ? m.variants.map(v => String(v?.id || '')).filter(Boolean)
      : [],
    // config'te bir kademe ZORLANMIŞSA arayüz "varsayılan" yerine gerçek
    // değeri göstersin (v1'deki options.reasoningEffort'un v2 karşılığı).
    defaultVariant: String(m?.settings?.reasoningEffort || ''),
  };
}

// Skill.Info → köprü sözleşmesi (v1'in skillDetails şemasıyla aynı). SAF.
function mapSkillInfo(x) {
  return { name: String(x?.name || x?.id || ''), description: String(x?.description || '') };
}

// ── Model / ajan katalogları ────────────────────────────────────────────────
// Cache DİZİN BAZLI (26.09.2026): katalog `?directory=` ile dizine göre
// değişiyor; tek genel cache proje değiştirilince eski projenin modellerini
// 60 sn boyunca gösteriyordu. Anahtar = sorgulanan dizin; giriş sayısı
// sınırlı (FIFO düşürme) — dizin sayısı pratikte az, önlem amaçlı.
const MODEL_CACHED_DIRS = 16;
const modelCache = new Map(); // dir → { at, models }

// Katalog yarışı (ölçüldü 26.09.2026): serve "listening" der demez /api/model
// henüz BOŞ dönebiliyor — sunucu HTTP'yi açıyor ama sağlayıcı kataloğu birkaç
// saniye sonra doluyor. Boş cevap = "henüz hazır değil" sayılır: kısa
// aralıklarla tekrar sorulur. Retry bütçesi 4 sn'de sabitlendi: telefonun read
// timeout'u 15 sn ve soğuk spawn (~10 sn ölçüldü) bu bütçenin dışında ayrı
// harcanıyor — retry + spawn birlikte 15 sn'yi AŞMAMALI, yoksa ilk istek
// kopar (sunucu arka planda açılmaya devam eder, ikinci girişte liste dolu
// gelir; ama kullanıcıya kopuş yaşatmamak esas).
const CATALOG_RETRY_MS = 1000;
// Ölçüldü (30.09.2026): yeni v2 sunucusu ilk çağrıda 0, ~3 sn sonra TAM katalog
// veriyor. Bütçe o ölçümün iki katı — bekleme yalnız liste yarımken harcanıyor.
const CATALOG_RETRY_BUDGET_MS = 8000;
// Yarım katalog uzun önbelleğe ALINMAZ; bir sonraki açılışta yeniden sorulur.
const PARTIAL_CACHE_MS = 5000;

// KATALOG DOGRULAMA KANCASI. Sorun (olculdu 26.09.2026): calisan v2 sunucusu
// acilistan hemen sonra YANLIS katalog veriyor — 21:34'te kalkan sunucu 21:35'te
// openai/gpt-4o, gpt-4.1-mini gibi ONLARCA modeli listeledi, 21:38'de ayni istek
// 27 modele indi. Yani "bos mu" kontrolu yetmez: DOLU ama YANLIS liste 60 sn
// onbellege giriyor. Cozum listeyi ikinci bir kaynakla kesistirmek.
//
// O ikinci kaynak 26 Eyl'de v1 kataloguydu (server.mjs `setPeerCatalog` ile
// opencodeApp'i bagliyordu). 30.09.2026'da v1 tumuyle SOKULDU; yerine v2'nin
// KENDI CLI'si geldi: `opencode models` her cagrida taze bir surec olarak
// guncel katalogu okuyor ve sunucunun bellekteki bayat/yanlis listesinden
// etkilenmiyor (v1'de de kaynak buydu). Kanca `setPeerCatalog` olarak duruyor:
// testler sahte katalog verebiliyor, verilmezse CLI yolu kullanilir.
let peerCatalog = null;
export function setPeerCatalog(fn) { peerCatalog = typeof fn === 'function' ? fn : null; }
export function __testSetPeerCatalog(fn) { setPeerCatalog(fn); }

// `opencode models` cikisindan kimlik kumesi. Ayrastirma v1 ile AYNI kurallara
// tabi (bkz. opencode-app.mjs fetchModelList): ayirac ILK egik cizgi, kimlikte
// birden fazla egik cizgi ve iki nokta olabilir (nous/stealth/ox-alpha,
// nanogpt/qwen3.8-max:thinking). Daha kati bir desen bu modelleri sessizce eler.
const CLI_ID_RE = /^[\w.:-]+\/[\w.:-]+(?:\/[\w.:-]+)*$/;

/**
 * Config'te TANIMLI saglayici kimlikleri (`opencode.json` → `provider`).
 *
 * Neden gerekli: katalogu dogrulayan kaynagin kendisi EKSIK gelebiliyor.
 * 30.09.2026 09:07:55Z olculdu — `opencode models` yalniz deepseek + Zen
 * dondu, kesisim de nanogpt/evren/mac/runpod modellerini SILDI ve telefonda
 * "model listesinde cok az model var" haline dustu (kullanici bildirdi,
 * bridge.log'da "katalogunda olmayan modeller gizlendi" satiri).
 * Bu dosya diskte duran duz JSON; hicbir surece bagli degil, o yuzden
 * "beklenen saglayici" icin guvenilir taban.
 */
function configuredProviderIds() {
  const kok = ISOLATED_HOME
    ? path.join(ISOLATED_HOME, 'config')
    : (String(process.env.XDG_CONFIG_HOME || '').trim() || path.join(os.homedir(), '.config'));
  try {
    const raw = JSON.parse(fs.readFileSync(path.join(kok, 'opencode', 'opencode.json'), 'utf-8'));
    const kapali = new Set(Array.isArray(raw?.disabled_providers) ? raw.disabled_providers : []);
    return new Set(Object.keys(raw?.provider || {}).filter(p => !kapali.has(p)));
  } catch {
    // Config okunamadiysa beklenti YOK: dogrulama eski davranisina duser.
    return new Set();
  }
}

/** SAF: listede hic modeli olmayan beklenen saglayicilar (bkz. __testEksikSaglayicilar). */
function eksikSaglayicilar(ids, beklenen) {
  if (!beklenen || !beklenen.size) return [];
  const gorulen = new Set();
  for (const id of ids || []) {
    const kes = String(id).indexOf('/');
    if (kes > 0) gorulen.add(String(id).slice(0, kes));
  }
  return [...beklenen].filter(p => !gorulen.has(p));
}
export function __testEksikSaglayicilar(ids, beklenen) { return eksikSaglayicilar(ids, beklenen); }

let cliCatalog = null;
let cliCatalogAt = 0;
async function cliCatalogIds() {
  const now = Date.now();
  if (cliCatalog && (now - cliCatalogAt) < MODEL_CACHE_MS) return cliCatalog;
  try {
    const r = await spawnAsync(OC2_EXE, ['models'], { encoding: 'utf-8', windowsHide: true, timeout: 60_000 });
    const ids = String(r.stdout || '').split(/\r?\n/).map(s => s.trim()).filter(s => s && CLI_ID_RE.test(s));
    // BOS KAYIT YAZILMAZ: cagri basarisizsa (CLI yok, zaman asimi) eldeki
    // kumeyi silmek butun listeyi gizlerdi — dogrulama devre disi kalsin.
    if (!ids.length) return cliCatalog;
    // EKSIK KAYIT DA YAZILMAZ. Bos kontrolu yetmiyordu: config'te tanimli bir
    // saglayicinin TEK bir modeli bile yoksa cevap yarim demektir ve o hâliyle
    // 60 sn onbellege girip butun ozel saglayicilari gizliyordu (30.09.2026).
    const eksik = eksikSaglayicilar(ids, configuredProviderIds());
    if (eksik.length) {
      logWarn('opencode2', 'cli katalogu YARIM geldi, dogrulama atlandi', { eksik, gelen: ids.length });
      return cliCatalog;   // eldeki saglam kayit (yoksa null → hizalama yapilmaz)
    }
    cliCatalog = ids; cliCatalogAt = now;
  } catch (e) {
    logWarn('opencode2', 'cli katalogu okunamadi', { error: e?.message || String(e) });
  }
  return cliCatalog;
}

// SAF: sunucusuz test edilir (bkz. __testPeerIleHizala).
function peerIleHizala(models, peer) {
  if (!peer || !peer.size) return { models, gizlenen: [] };
  const tutulan = [];
  const gizlenen = [];
  for (const m of models) (peer.has(m.id) ? tutulan : gizlenen).push(m);
  return { models: tutulan, gizlenen: gizlenen.map(m => m.id) };
}

let sonGizlenen = '';
async function peerIdSet() {
  try {
    const ids = peerCatalog ? await peerCatalog() : await cliCatalogIds();
    return Array.isArray(ids) && ids.length ? new Set(ids) : null;
  } catch (e) {
    logWarn('opencode2', 'peerCatalog', { error: e?.message || String(e) });
    return null;
  }
}

export async function getModels(directory) {
  // directory: oturum-bazlı katalog (v1 ile aynı sözleşme). Yoksa ilk açık
  // oturumun cwd'si, o da yoksa ev dizini.
  const dir = String(directory || '').trim() || [...sessions.values()][0]?.cwd || os.homedir();
  const hit = modelCache.get(dir);
  if (hit && Date.now() - hit.at < (hit.omur || MODEL_CACHE_MS)) return hit.models;
  // "serverUp" engeli YOK (seçenek a): api() → ensureServer() sunucuyu
  // kaldırır.
  try {
    let models = [];
    let eksik = [];
    const beklenenSaglayicilar = configuredProviderIds();
    const started = Date.now();
    for (;;) {
      const r = await api('GET', '/api/model?directory=' + encodeURIComponent(dir));
      const data = Array.isArray(r.json?.data) ? r.json.data : [];
      // Süzgeç v1'in ta kendisi: Zen'in ücretli modelleri ve deprecated
      // sürümler elenir. v2 süzmediği için listede 7 fazla model vardı.
      models = data.map(mapCatalogModel).filter(m => isAgentBridgeOpencodeModel(m.id));
      // v1 kataloğuyla hizala. Kayıt yoksa (CLI okunamadı) hizalama YAPILMAZ:
      // eldeki listeyi silmektense süzülmemiş hâlini göstermek yeğdir.
      const hizali = peerIleHizala(models, await peerIdSet());
      models = hizali.models;
      // Gizlenenler BIR KEZ loglanir; ayni kume her 60 sn'lik cagrida
      // tekrar yazilmasin (v1'deki filterToServeCatalog ile ayni desen).
      const anahtar = hizali.gizlenen.join(',');
      if (anahtar !== sonGizlenen) {
        sonGizlenen = anahtar;
        if (hizali.gizlenen.length) {
          logWarn('opencode2', 'v1 katalogunda olmayan modeller gizlendi', { gizlenen: hizali.gizlenen });
        }
      }
      // TAM MI: config'te tanımlı her sağlayıcıdan en az bir model gelmeli.
      // Ölçüm (30.09.2026, ayrı sunucu örneği): ilk çağrıda 0, ~3 sn sonra 39
      // model ve bütün sağlayıcılar. Yani "boş mu" kontrolü yetmiyor, yarım
      // katalog da 60 sn önbelleğe giriyordu.
      eksik = eksikSaglayicilar(models.map(m => m.id), beklenenSaglayicilar);
      if (models.length && !eksik.length) break;
      // Bütçe kontrolü: bir bekleme daha sığmıyorsa çık — toplam ek süre
      // CATALOG_RETRY_BUDGET_MS ile sınırlı kalır.
      if (Date.now() - started + CATALOG_RETRY_MS > CATALOG_RETRY_BUDGET_MS) break;
      await new Promise(res => setTimeout(res, CATALOG_RETRY_MS));
    }
    if (models.length) {
      if (modelCache.size >= MODEL_CACHED_DIRS) {
        modelCache.delete(modelCache.keys().next().value);
      }
      // YARIM liste KISA ömürlü önbelleğe girer: kullanıcı eksik listeyle bir
      // dakika baş başa kalmasın, bir sonraki açılışta yeniden sorulsun.
      const omur = eksik.length ? PARTIAL_CACHE_MS : MODEL_CACHE_MS;
      if (eksik.length) {
        logWarn('opencode2', 'katalog YARIM döndü (kısa önbellek)', { dir, eksik, gelen: models.length });
      }
      modelCache.set(dir, { at: Date.now(), models, omur });
      return models;
    }
    // Boş katalog: statik fallbacke DÜŞÜLMEZ — gerçek durum döner. Teşhis
    // için loglanır (sunucu ayakta mı, hangi dizin, ne kadar denendi).
    logWarn('opencode2', 'getModels.bos-katalog', {
      dir, gecenMs: Date.now() - started,
    });
    return [];
  } catch (e) {
    // Sunucu açılamadı / istek patladı: yine boş liste + log. Önceki davranış
    // (2 sabit model dönmek) hatayı maskeliyordu.
    logWarn('opencode2', 'getModels', { error: e?.message || String(e) });
    return [];
  }
}

export async function listAgents() {
  // getModels ile aynı kural (seçenek a): api() sunucuyu kaldırır. Testlerde
  // çağrılmıyor, ana sayfa çekmiyor — yan etkisi yok.
  try {
    const dir = [...sessions.values()][0]?.cwd || os.homedir();
    const r = await api('GET', '/api/agent?directory=' + encodeURIComponent(dir));
    const data = Array.isArray(r.json?.data) ? r.json.data : [];
    const agents = mapAgents(data);
    for (const a of agents) {
      if (a.model) ajanModelleri.set(a.name, a.model); else ajanModelleri.delete(a.name);
    }
    return { ok: true, agents };
  } catch (e) {
    return { ok: false, error: e?.message || String(e), agents: [] };
  }
}

// v2 /api/agent {id,name} döner; ortak köprü sözleşmesi {name,...} bekler.
// Yalnız ana turu koşabilen ajanlar seçicide yer alır.
//
// MODEL ALANI NESNE: v2 ajanın modelini `{providerID, id}` çifti olarak veriyor
// (30.09.2026 canlı ölçüm: muse → {providerID:"nanogpt", id:"meta/muse-spark-1.3-contributor"}).
// Yalnız string kabul eden eski okuma her ajanda "" döndürüyordu — telefonda
// ajanın modeli hiç görünmüyordu.
function mapAgents(data) {
  return data.filter(a => a && !a.hidden && a.mode !== 'subagent' && String(a.id || '').trim())
    .map(a => ({
      name: String(a.id).trim(),
      description: a.description || '',
      mode: a.mode || '',
      builtIn: !!a.builtIn,
      model: agentModelString(a.model),
    }));
}

/** SAF: v2'nin model alanı (string | {providerID,id}) → "saglayici/model". */
function agentModelString(model) {
  if (typeof model === 'string') return model;
  const p = String(model?.providerID || '').trim();
  const id = String(model?.id || '').trim();
  if (!id) return '';
  return p ? `${p}/${id}` : id;
}
export function __testAgentModelString(model) { return agentModelString(model); }

// Ajan → sabitlediği model. listAgents her çağrıda tazeliyor; setAgent bunu
// kullanıp oturumun modelini ajanın modeline çekiyor (bkz. setAgent).
const ajanModelleri = new Map();

// "provider/model" → v2 Model.Ref. Önek yoksa sağlayıcı boş bırakılamaz, o
// yüzden varsayılanın sağlayıcısına düşülür.
function modelRef(model, variant) {
  const raw = String(model || DEFAULT_MODEL);
  const i = raw.indexOf('/');
  const providerID = i > 0 ? raw.slice(0, i) : DEFAULT_MODEL.split('/')[0];
  const id = i > 0 ? raw.slice(i + 1) : raw;
  const ref = { providerID, id };
  if (variant) ref.variant = String(variant);
  return ref;
}

// ── Oturum yönetimi ─────────────────────────────────────────────────────────
function newLocalSession({ cwd, model, agent, permissionMode }) {
  const id = randomUUID();
  const s = {
    id,
    remoteId: '',
    cwd,
    model: model || DEFAULT_MODEL,
    agent: agent || '',
    permissionMode: permissionMode === 'ask' ? 'ask' : 'yolo',
    status: 'idle',
    messages: [],
    toolDetails: [],
    awaitingApproval: false,
    awaitingFirstOutput: false,
    pendingApproval: null,
    contextTokens: 0,
    contextWindow: 0,
    cost: 0,
    reverted: null,
    subscribers: new Set(),
    _streamRows: new Map(),
    _thoughtText: new Map(),
  };
  sessions.set(id, s);
  return s;
}

export function newSession({ cwd, model, agent, permissionMode }) {
  const dir = cwd && String(cwd).trim() ? String(cwd).trim() : os.homedir();
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    return { ok: false, error: 'cwd not a directory: ' + dir };
  }
  const s = newLocalSession({ cwd: dir, model, agent, permissionMode });
  // Uzak oturum tembel açılır: ilk prompt'ta. Böylece telefonda açılan ama hiç
  // kullanılmayan sekme v2 deposunda çöp oturum bırakmaz.
  return { ok: true, sessionId: s.id, cwd: dir, model: s.model };
}

async function ensureRemote(s) {
  if (s.remoteId) return s.remoteId;
  const body = { location: { directory: s.cwd } };
  if (s.agent) body.agent = s.agent;
  if (s.model) body.model = modelRef(s.model);
  body.permissions = permissionRules(s.permissionMode);
  const r = await api('POST', '/api/session', body);
  const rid = r.json?.data?.id;
  if (!r.ok || !rid) throw new Error('oturum açılamadı (HTTP ' + r.status + ')');
  s.remoteId = rid;
  byRemote.set(rid, s.id);
  for (const fn of sessionResolvedListeners) {
    try { fn({ sessionId: s.id, threadId: rid, cwd: s.cwd, model: s.model }); }
    catch (e) { logWarn('opencode2', 'sessionResolved listener failed', { error: e?.message || String(e) }); }
  }
  return rid;
}

function permissionRules(mode) {
  return [{ action: '*', resource: '*', effect: mode === 'ask' ? 'ask' : 'allow' }];
}

export async function setPermissionMode({ sessionId, permissionMode }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (permissionMode !== 'ask' && permissionMode !== 'yolo') return { ok: false, error: 'geçersiz izin modu' };
  if (s.remoteId) {
    try {
      const r = await api('PATCH', `/api/session/${s.remoteId}`, { permissions: permissionRules(permissionMode) });
      if (!r.ok) return { ok: false, error: 'izin modu ayarlanamadı (HTTP ' + r.status + ')' };
    } catch (e) { return { ok: false, error: e?.message || String(e) }; }
  }
  s.permissionMode = permissionMode;
  return { ok: true, permissionMode };
}

export async function prompt({ sessionId, text, model, agent, variant, permissionMode }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const clean = String(text ?? '');
  if (!clean.trim()) return { ok: false, error: 'empty prompt' };
  // Teslim kipi DURUM DEĞİŞMEDEN önce okunur: aşağıda status 'running' yapılıyor,
  // sonra bakılsaydı her mesaj kuyruğa düşerdi.
  const busy = s.status === 'running';
  try {
    if (permissionMode && permissionMode !== s.permissionMode) {
      const mode = await setPermissionMode({ sessionId, permissionMode });
      if (!mode.ok) return mode;
    }
    const rid = await ensureRemote(s);
    if (agent !== undefined && agent !== s.agent) {
      s.agent = String(agent || '');
      if (s.agent) await api('POST', `/api/session/${rid}/agent`, { agent: s.agent });
    }
    if (model && model !== s.model) {
      s.model = String(model);
      await api('POST', `/api/session/${rid}/model`, { model: modelRef(s.model, variant) });
    }
    s.messages.push({ role: 'user', text: clean, time: hhmm() });
    capSess(s);
    // Yeni tur = geri sarma kalıcı: şerit bu snapshot'la birlikte düşsün.
    commitRevert(s);
    s.awaitingFirstOutput = true;
    s.status = 'running';
    pushSnapshot(s);
    // Süren turda `queue`: mesaj sıraya girer ve tur bitince kendi turu olur.
    // Boştaysa `steer` (v2'nin varsayılanı) doğrudan yeni tur başlatır.
    const delivery = busy ? 'queue' : 'steer';
    const r = await api('POST', `/api/session/${rid}/prompt`, { text: clean, delivery });
    if (!r.ok) {
      s.status = 'idle';
      s.awaitingFirstOutput = false;
      pushSnapshot(s);
      return { ok: false, error: 'prompt reddedildi (HTTP ' + r.status + ')' };
    }
    return { ok: true, sessionId: s.id };
  } catch (e) {
    s.status = 'idle';
    s.awaitingFirstOutput = false;
    pushSnapshot(s);
    return { ok: false, error: e?.message || String(e) };
  }
}

// Süren tura ek mesaj: v2'de kuyruk VAR (delivery:"queue"), v1'den farkı bu.
export async function followUp({ sessionId, text }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.remoteId) return { ok: false, error: 'oturum henüz açılmadı' };
  const clean = String(text ?? '');
  if (!clean.trim()) return { ok: false, error: 'empty prompt' };
  const r = await api('POST', `/api/session/${s.remoteId}/prompt`, { text: clean, delivery: 'queue' });
  if (!r.ok) return { ok: false, error: 'kuyruğa alınamadı (HTTP ' + r.status + ')' };
  s.messages.push({ role: 'user', text: clean, time: hhmm() });
  capSess(s);
  pushSnapshot(s);
  return { ok: true, sessionId: s.id };
}

export async function stop(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.remoteId) { s.status = 'idle'; pushSnapshot(s); return { ok: true }; }
  const r = await api('POST', `/api/session/${s.remoteId}/interrupt`, {});
  s.status = 'idle';
  s.awaitingFirstOutput = false;
  pushSnapshot(s);
  return r.ok ? { ok: true } : { ok: false, error: 'interrupt başarısız (HTTP ' + r.status + ')' };
}

export async function approve({ sessionId, allow = true, always = false }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const pending = s.pendingApproval;
  if (!pending || !s.remoteId) return { ok: false, error: 'bekleyen izin yok' };
  const decision = allow ? (always ? 'always' : 'once') : 'reject';
  const r = await api('POST', `/api/session/${s.remoteId}/permission/${pending.id}/reply`, { decision });
  if (!r.ok) return { ok: false, error: 'izin yanıtı reddedildi (HTTP ' + r.status + ')' };
  s.pendingApproval = null;
  s.awaitingApproval = false;
  pushSnapshot(s);
  return { ok: true };
}

export function getPendingApproval() {
  for (const s of sessions.values()) {
    if (s.pendingApproval && s.awaitingApproval) {
      return { backend: 'opencode2-app', sessionId: s.id, summary: s.pendingApproval.summary || 'İzin gerekiyor' };
    }
  }
  return null;
}

export async function setModel({ sessionId, model }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  s.model = String(model || DEFAULT_MODEL);
  if (s.remoteId) {
    const r = await api('POST', `/api/session/${s.remoteId}/model`, { model: modelRef(s.model) });
    if (!r.ok) return { ok: false, error: 'model ayarlanamadı (HTTP ' + r.status + ')' };
  }
  pushSnapshot(s);
  return { ok: true, model: s.model };
}

export async function setAgent({ sessionId, agent }) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  s.agent = String(agent || '');
  if (s.remoteId && s.agent) {
    const r = await api('POST', `/api/session/${s.remoteId}/agent`, { agent: s.agent });
    if (!r.ok) return { ok: false, error: 'ajan ayarlanamadı (HTTP ' + r.status + ')' };
  }
  // AJANIN SABİTLEDİĞİ MODELE GEÇ. Kullanıcının ajanları (deepseek-41, mimo,
  // muse) birer MODEL ÖN AYARI: dosyada `model:` satırı var. Köprü her turda
  // oturumun modelini açıkça gönderdiği için ajanın modeli aksi hâlde hiç
  // uygulanmıyor ve "muse ajanı deepseek'le koşuyor" oluyordu (30.09.2026
  // ölçümü: `opencode run --agent muse` → "muse · deepseek-flash").
  // Kullanıcı sonradan model çipinden başka bir model seçerse o kazanır.
  const ajanModeli = ajanModelleri.get(s.agent);
  if (ajanModeli && ajanModeli !== s.model) {
    const r = await setModel({ sessionId, model: ajanModeli });
    if (r.ok) return { ok: true, agent: s.agent, model: s.model };
  }
  pushSnapshot(s);
  return { ok: true, agent: s.agent, model: s.model };
}

export function getConversation(sessionId, opts = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { messages: [], running: false, awaitingApproval: false, awaitingFirstOutput: false, awaitingUserInput: false, contextTokens: 0, contextWindow: 0, contextPct: null, todos: [] };
  // paginateSessionMessages DİZİ döner (nesne değil) — yayılırsa mesajlar kaybolur.
  return { ...snapshot(s), messages: paginateSessionMessages(s, opts) };
}

export function getThought(sessionId, i) {
  const s = sessions.get(sessionId);
  if (!s) return { text: '' };
  return { text: s.toolDetails[i] || '' };
}

export function listSessions() {
  return [...sessions.values()].map(s => ({
    id: s.id,
    cwd: s.cwd,
    model: s.model,
    status: s.status,
    diskId: s.remoteId || '',
    title: (s.messages.find(m => m.role === 'user')?.text || '').replace(/\s+/g, ' ').slice(0, 80),
  }));
}

export function runningSessionId() {
  for (const s of sessions.values()) if (s.status === 'running') return s.id;
  return null;
}

export async function listDiskSessions() {
  // Ana ekrandaki geçmiş sorgusu 205 MB'lık sunucuyu başlatmasın. Sunucu
  // kapalıyken aynı izole v2 deposunu salt okunur aç; aksi halde her köprü
  // yeniden açılışında OpenCode 2 geçmişi ilk ziyaret öncesi boş görünür.
  if (!serverUp()) {
    const dbPath = path.join(DATA_ROOT, 'opencode', 'opencode.db');
    if (!fs.existsSync(dbPath)) return { ok: true, sessions: [] };
    let db;
    try {
      db = new DatabaseSync(dbPath, { readOnly: true });
      const data = db.prepare('SELECT id, title, slug, directory, model, time_created, time_updated FROM session_v2').all();
      const open = new Set([...sessions.values()].map(s => s.remoteId).filter(Boolean));
      const meta = readSessionMetadata();
      const rows = data.map(x => {
        let model = '';
        try { const ref = JSON.parse(x.model || 'null'); if (ref?.providerID && ref?.id) model = `${ref.providerID}/${ref.id}`; } catch {}
        return {
          id: x.id,
          title: meta[x.id]?.title || x.title || x.slug || x.id,
          cwd: x.directory || '',
          model,
          updated: x.time_updated || x.time_created || 0,
          mtime: x.time_updated || x.time_created || 0,
          pinned: !!meta[x.id]?.pinned,
          open: open.has(x.id),
        };
      });
      rows.sort((a, b) => a.pinned !== b.pinned ? (a.pinned ? -1 : 1) : b.updated - a.updated);
      return { ok: true, sessions: rows };
    } catch (e) {
      return { ok: false, error: e?.message || String(e), sessions: [] };
    } finally { db?.close(); }
  }
  try {
    const r = await api('GET', '/api/session');
    const data = Array.isArray(r.json?.data) ? r.json.data : [];
    const open = new Set([...sessions.values()].map(s => s.remoteId).filter(Boolean));
    const meta = readSessionMetadata();
    const rows = data.map(x => ({
      id: x.id,
      title: meta[x.id]?.title || x.title || x.slug || x.id,
      cwd: x.location?.directory || '',
      model: x.model ? `${x.model.providerID}/${x.model.id}` : '',
      updated: x.time?.updated || x.time?.created || 0,
      mtime: x.time?.updated || x.time?.created || 0,
      pinned: !!meta[x.id]?.pinned,
      open: open.has(x.id),
    }));
    // Sabitlenenler her zaman üstte (v1 ile aynı sıralama sözleşmesi).
    rows.sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      return (b.updated || 0) - (a.updated || 0);
    });
    return { ok: true, sessions: rows };
  } catch (e) {
    return { ok: false, error: e?.message || String(e), sessions: [] };
  }
}

// Diskteki (v2 deposundaki) bir oturumu köprü sekmesi olarak aç: geçmiş
// mesajlar okunur, sonraki turlar canlı olay akışından gelir.
export async function adoptSession({ id, cwd }) {
  if (!id) return { ok: false, error: 'id required' };
  const existing = byRemote.get(id);
  if (existing && sessions.has(existing)) return { ok: true, sessionId: existing, adopted: true };
  try {
    const info = await api('GET', `/api/session/${id}`);
    const data = info.json?.data || {};
    const dir = cwd || data.location?.directory || os.homedir();
    const s = newLocalSession({
      cwd: dir,
      model: data.model ? `${data.model.providerID}/${data.model.id}` : DEFAULT_MODEL,
      agent: data.agent || '',
    });
    s.remoteId = id;
    byRemote.set(id, s.id);
    const msgs = await api('GET', `/api/session/${id}/message`);
    const rows = Array.isArray(msgs.json?.data) ? [...msgs.json.data].reverse() : [];
    for (const m of rows) {
      if (m.type === 'user') {
        s.messages.push({ role: 'user', text: String(m.text || ''), time: hhmm() });
      } else if (m.type === 'assistant') {
        for (const part of (m.content || [])) {
          if (part.type === 'text' && part.text) s.messages.push({ role: 'agent', text: String(part.text), time: hhmm() });
          else if (part.type === 'reasoning' && part.text) {
            const idx = s.toolDetails.push(String(part.text)) - 1;
            s.messages.push({ role: 'thought', text: DUSUNCE_ISARETI + ' Düşündü · ' + String(part.text).replace(/\s+/g, ' ').slice(0, 60), thoughtIndex: idx, time: hhmm() });
          } else if (part.type === 'tool' || part.tool) {
            s.messages.push({ role: 'thought', text: String(part.name || part.tool || 'araç'), time: hhmm() });
          }
        }
      }
    }
    capSess(s);
    pushSnapshot(s);
    return { ok: true, sessionId: s.id, adopted: true };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

export async function deleteDiskSession({ id }) {
  if (!id) return { ok: false, error: 'id required' };
  if (!serverUp()) return { ok: false, error: 'v2 sunucusu çalışmıyor' };
  try {
    const r = await api('DELETE', `/api/session/${id}`);
    const local = byRemote.get(id);
    if (local) { sessions.delete(local); byRemote.delete(id); }
    // Köprü tarafındaki pin/rename meta kaydı da düşsün (v1 ile aynı kural).
    const meta = readSessionMetadata();
    if (meta[id]) { delete meta[id]; writeSessionMetadata(meta); }
    return r.ok ? { ok: true } : { ok: false, error: 'silinemedi (HTTP ' + r.status + ')' };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Oturum meta verisi (pin/rename) ─────────────────────────────────────────
// v1'in kalıbının aynısı: v2 API'de PATCH /api/session {title} olsa da pin'i
// taşacak yer yok; ikisini de köprü-lokal dosyada tutmak tek tutarlı kaynak ve
// telefonda zaten bu sözleşme. Sunucudaki başlığı DEĞİŞTİRMİYORUZ — disk
// listesi köprü meta verisiyle birleştirilirken bizimki kazanır.
const OC2_META_FILE = process.env.AGENTBRIDGE_OPENCODE2_META ||
  path.join(import.meta.dirname, 'data', 'opencode2-session-metadata.json');

function readSessionMetadata() {
  try {
    if (fs.existsSync(OC2_META_FILE)) return JSON.parse(fs.readFileSync(OC2_META_FILE, 'utf-8'));
  } catch {}
  return {};
}

function writeSessionMetadata(data) {
  try {
    fs.mkdirSync(path.dirname(OC2_META_FILE), { recursive: true });
    fs.writeFileSync(OC2_META_FILE, JSON.stringify(data, null, 2), 'utf-8');
    return true;
  } catch {
    return false;
  }
}

export function pinThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readSessionMetadata();
  meta[id] = { ...(meta[id] || {}), pinned: true };
  if (!writeSessionMetadata(meta)) return { ok: false, error: 'metadata write failed' };
  return { ok: true, pinned: true };
}

export function unpinThread({ id } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readSessionMetadata();
  if (meta[id]) meta[id].pinned = false;
  if (!writeSessionMetadata(meta)) return { ok: false, error: 'metadata write failed' };
  return { ok: true, pinned: false };
}

export function renameThread({ id, title } = {}) {
  if (!id) return { ok: false, error: 'id required' };
  const meta = readSessionMetadata();
  const clean = String(title || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  meta[id] = { ...(meta[id] || {}) };
  if (clean) meta[id].title = clean;
  else delete meta[id].title;
  if (!writeSessionMetadata(meta)) return { ok: false, error: 'metadata write failed' };
  return { ok: true, title: clean };
}

// ── Checkpoint / geri sarma ─────────────────────────────────────────────────
// ÖLÇÜLDÜ (26.09.2026, canlı v2.0.16 sunucusuna karşı):
//   • POST /api/session/:id/revert/stage {messageID} → 200 {data:{messageID,
//     files:[FileDiff.Info]}} — SAHNE kurar, konuşma henüz DEĞİŞMEZ; pending
//     sahne Session.Info.revert alanında taşınır.
//   • POST .../revert/commit → 204 — uygular; mesaj listesi o noktaya kadar
//     SİLİNİYOR (ölçüm: 3 → 0). Dosya sarımı stage'in files'ında bildirilir.
//   • DELETE .../revert → 204 — commit'ten SONRA çağrılırsa konuşmayı KISMEN
//     geri getiriyor (ölçüm: 0 → 1 mesaj). Upstream semantiği belirsiz;
//     unrevert bunu dener ve yerel taraf konuşmayı sunucudan tazeler.
//   • Tur sürerken stage 409 SessionBusyError döner — telefon tarafında
//     "tur sürerken geri sarılamaz" koşulu zaten var, buna ek güvence.

async function fetchUserMessages(s) {
  const r = await api('GET', `/api/session/${s.remoteId}/message`);
  const rows = Array.isArray(r.json?.data) ? r.json.data : [];
  return rows.filter(m => m?.type === 'user' && m?.id).map(m => ({ messageID: m.id, text: String(m.text || '') }));
}

const CHECKPOINT_TEXT_LIMIT = 120;
const CHECKPOINT_LIMIT = 30;

/** Telefonun "şu mesaja dön" listesi — kullanıcı mesajları. */
export async function listCheckpoints({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.remoteId) return { ok: true, items: [], reverted: s.reverted || null };
  try {
    const users = await fetchUserMessages(s);
    const items = users.map((u, i) => ({
      messageID: u.messageID,
      text: u.text.slice(0, CHECKPOINT_TEXT_LIMIT),
      truncated: u.text.length > CHECKPOINT_TEXT_LIMIT,
      turn: i + 1,
    }));
    return { ok: true, items: items.slice(-CHECKPOINT_LIMIT), total: items.length, reverted: s.reverted || null };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// Yerel mesaj kuyruğunu sondan N kullanıcı satırına kadar kes (aradaki agent/
// thought satırları dahil). Delta akışına "kırpma" anlatılamadığı için bu
// push TAM snapshot olarak gider (v1'deki _deltaDirty kuralı).
function cutLocalMessages(s, userTurns) {
  let seen = 0;
  for (let i = s.messages.length - 1; i >= 0; i--) {
    if (s.messages[i]?.role === 'user') {
      seen++;
      if (seen >= userTurns) {
        s.messages.length = i;
        break;
      }
    }
  }
  s._streamRows.clear();
  s._thoughtText.clear();
}

/**
 * "Geri sarıldı · Geri Al" şeridini düşürür: yeni bir tur gidince geri sarma
 * kalıcı olur, şeridin anlamı biter.
 *
 * v1'de bu `commitRevert` vardı ve `prompt()` turun başında çağırıyordu; v2
 * modülü yazılırken atlandı — `s.reverted` bir kez set edilip HİÇBİR YERDE
 * temizlenmiyordu, yani şerit oturum boyunca ekranda kalıyordu (30.09.2026
 * kullanıcı bildirimi). `reverted` META_KEYS'te olduğu için null'a çekmek
 * delta kipinde de telefona gidiyor.
 *
 * YALNIZ YEREL: sunucuya `revert/commit` GÖNDERİLMİYOR, çünkü yeni tur gidince
 * sunucu kendiliğinden commit ediyor (30.09.2026 ölçümü). Erken commit
 * "Geri Al"ı imkânsız kılan eski hataydı.
 */
function commitRevert(s) {
  if (!s || !s.reverted) return false;
  s.reverted = null;
  s._deltaDirty = true;
  return true;
}

// Konuşmayı sunucudan yeniden yükle (unrevert sonrası yerel kuyruğun gerçekle
// senkronu). adoptSession'ın mesaj okuma kısmıyla aynı şekil.
async function reloadConversation(s) {
  const msgs = await api('GET', `/api/session/${s.remoteId}/message`);
  const rows = Array.isArray(msgs.json?.data) ? [...msgs.json.data].reverse() : [];
  s.messages = [];
  s._streamRows.clear();
  s._thoughtText.clear();
  for (const m of rows) {
    if (m.type === 'user') {
      s.messages.push({ role: 'user', text: String(m.text || ''), time: hhmm() });
    } else if (m.type === 'assistant') {
      for (const part of (m.content || [])) {
        if (part.type === 'text' && part.text) s.messages.push({ role: 'agent', text: String(part.text), time: hhmm() });
      }
    }
  }
  capSess(s);
  s._deltaDirty = true;
  pushSnapshot(s);
}

/** POST /opencode2-app/revert — mesaj kimliğiyle checkpoint'e geri sar (stage+commit). */
export async function revertToMessage({ sessionId, messageID } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const id = String(messageID || '').trim();
  if (!id) return { ok: false, error: 'messageID gerekli' };
  if (s.status === 'running') return { ok: false, error: 'tur sürerken geri sarılamaz' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  try {
    const users = await fetchUserMessages(s);
    const idx = users.findIndex(u => u.messageID === id);
    if (idx < 0) return { ok: false, error: 'bilinmeyen messageID: ' + id, unknownMessage: true };
    // SADECE STAGE — commit BİLEREK ÇAĞRILMIYOR (30.09.2026 canlı ölçüm,
    // revert-deney*.mjs). v2'de geri sarma iki kademeli:
    //   stage  → mesajlar SİLİNMEZ, oturuma `revert` işareti konur, dosyalar
    //            (git deposunda) o ana sarılır. DELETE /revert ile TAMAMEN
    //            geri alınır: hem konuşma hem dosya geri gelir (ölçüldü).
    //   commit → kalıcı; bundan sonra DELETE /revert hiçbir şeyi geri getirmez.
    // Köprü eskiden ikisini peş peşe çağırıyordu, yani "Geri Al"ı kendi eliyle
    // imkânsız kılıyordu. Commit'e gerek de yok: kullanıcı yeni mesaj
    // gönderdiğinde SUNUCU kendiliğinden commit ediyor (ölçüldü — sarılan tur
    // yenisiyle değişti, `revert` işareti null'a döndü).
    const staged = await api('POST', `/api/session/${s.remoteId}/revert/stage`, { messageID: id, files: true });
    if (!staged.ok) return { ok: false, error: 'geri sarma hazırlanamadı (HTTP ' + staged.status + ')' };
    const rev = staged.json?.data || {};
    cutLocalMessages(s, users.length - idx);
    s.reverted = {
      messageID: rev.messageID || id,
      // Dosya sarımı stage'in files listesinde bildiriliyor; boşsa dosya
      // sarılmadı demektir (v1'deki snapshot alanı gibi).
      filesReverted: Array.isArray(rev.files) && rev.files.length > 0,
      files: Array.isArray(rev.files) ? rev.files.length : 0,
    };
    s._deltaDirty = true;
    pushSnapshot(s);
    return { ok: true, sessionId: s.id, ...s.reverted, droppedUserTurns: users.length - idx };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

/**
 * POST /opencode2-app/unrevert — "Geri Al": geri sarmayı iptal eder.
 *
 * 26.09.2026'da "v2'de imkânsız" diye kapatılmıştı; o ölçüm COMMIT EDİLMİŞ bir
 * geri sarma üzerinde yapılmıştı — doğru sonuç, yanlış genelleme. Köprü artık
 * commit etmediği için (bkz. revertToMessage) `DELETE /api/session/:id/revert`
 * gerçekten çalışıyor: 30.09.2026 canlı ölçümde konuşma da (KIRAZ turu geri
 * geldi) dosya da (deney.txt BIRINCI → IKINCI) eski hâline döndü.
 */
export async function unrevertSession({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (s.status === 'running') return { ok: false, error: 'tur sürerken geri alınamaz' };
  if (!s.reverted) return { ok: false, error: 'geri sarılmış bir nokta yok' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  try {
    const r = await api('DELETE', `/api/session/${s.remoteId}/revert`);
    // 204 No Content bekleniyor; api() 2xx'i ok sayıyor.
    if (!r.ok) return { ok: false, error: 'geri alınamadı (HTTP ' + r.status + ')' };
    s.reverted = null;
    // Yerel kuyruk geri sararken kesilmişti; gerçeği sunucudan yeniden oku.
    await reloadConversation(s);
    return { ok: true, sessionId: s.id };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

/** POST /opencode2-app/rewind — sondan sayımlı geri dönüş; revert'e devreder. */
export async function rewindSession({ sessionId, dropUserTurns } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const drop = Math.floor(Number(dropUserTurns));
  if (!Number.isFinite(drop) || drop < 1) return { ok: false, error: 'dropUserTurns >= 1 olmalı' };
  if (s.status === 'running') return { ok: false, error: 'tur sürerken geri dönülemez' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  try {
    const users = await fetchUserMessages(s);
    if (users.length < drop) return { ok: false, error: 'geri dönülecek mesaj bulunamadı (' + users.length + ' < ' + drop + ')' };
    return await revertToMessage({ sessionId, messageID: users[users.length - drop].messageID });
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Değişiklikler (oturumun dosya diff'i) ───────────────────────────────────
// v2: GET /api/session/:id/diff?from=<msg_ilk user>&to=<msg_son user> — tek
// istekle tüm oturumun aralık diff'i. Kayıt şekli FileDiff.Info {file, patch,
// additions, deletions, status} — v1'in normalizeDiffEntry'iyle birebir.
const DIFF_PATCH_LIMIT = 100 * 1024;
const DIFF_FILE_LIMIT = 300;

function normalizeDiffEntry(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const p = String(raw.file || raw.path || '').trim();
  if (!p) return null;
  const say = v => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0);
  return {
    path: p,
    patch: String(raw.patch || ''),
    additions: say(raw.additions),
    deletions: say(raw.deletions),
    status: String(raw.status || '').trim(),
    truncated: false,
  };
}

function mergeDiffEntry(target, entry) {
  target.additions += entry.additions;
  target.deletions += entry.deletions;
  if (entry.patch) target.patch = target.patch ? target.patch + '\n' + entry.patch : entry.patch;
}

/** GET /opencode2-app/diff — oturumun toplam dosya değişikliği. */
export async function sessionDiff({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.remoteId) return { ok: true, sessionId: s.id, cwd: s.cwd, files: [], additions: 0, deletions: 0, turns: 0, truncated: false };
  try {
    const users = await fetchUserMessages(s);
    if (!users.length) return { ok: true, sessionId: s.id, cwd: s.cwd, files: [], additions: 0, deletions: 0, turns: 0, truncated: false };
    const from = users[0].messageID;
    const to = users[users.length - 1].messageID;
    // ÖLÇÜLDÜ (26.09.2026, bridge.log): v2.0.16'da from+to aralık diff'i
    // HTTP 400 döndürüyor (nedeni belirsiz — geçerli mesaj kimlikleriyle de);
    // PARAMETRESİZ çağrı ("en yeni kullanıcı mesajının turu" varsayılanı)
    // güvenilir: 200 + dosya kayıtları. Zincir: aralığı dene (upstream
    // düzelirse tek istekle tüm oturumu kapsar), reddedilirse/boşsa son
    // turun diff'ine düş. Son tur kısıtı bilinçli: çok turlu birikimli diff
    // için tur-başına toplama ayrıca yapılmalı (bkz. ölçüm notları).
    const variants = [
      ['aralik', `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`],
      ['son-tur', ``],
    ];
    let data = [];
    let kullanilan = 'aralik';
    for (const [ad, q] of variants) {
      const r = await api('GET', `/api/session/${s.remoteId}/diff${q}`);
      const d = Array.isArray(r.json?.data) ? r.json.data : null;
      console.log(`[opencode2-diff-olcum] ${ad}: HTTP ${r.status}, ham=${d === null ? 'null' : d.length} kayit`);
      if (d && d.length > 0 && data.length === 0) { data = d; kullanilan = ad; }
    }
    console.log(`[opencode2-diff-olcum] kullanilan: ${kullanilan}`);
    const byPath = new Map();
    let tasan = false;
    for (const raw of data) {
      const entry = normalizeDiffEntry(raw);
      if (!entry) continue;
      const varOlan = byPath.get(entry.path);
      if (varOlan) { mergeDiffEntry(varOlan, entry); continue; }
      if (byPath.size >= DIFF_FILE_LIMIT) { tasan = true; continue; }
      byPath.set(entry.path, entry);
    }
    const files = [...byPath.values()]
      .sort((a, b) => a.path.localeCompare(b.path))
      .map(f => {
        if (f.patch.length > DIFF_PATCH_LIMIT) {
          return { ...f, patch: f.patch.slice(0, DIFF_PATCH_LIMIT), truncated: true };
        }
        return f;
      });
    return {
      ok: true,
      sessionId: s.id,
      cwd: s.cwd,
      files,
      additions: files.reduce((n, f) => n + f.additions, 0),
      deletions: files.reduce((n, f) => n + f.deletions, 0),
      turns: users.length,
      truncated: tasan || files.some(f => f.truncated),
    };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Compact (bağlam özetle) ─────────────────────────────────────────────────
// v2: POST /api/session/:id/compact {} → 200, gövde inbox compaction mesajı
// (delivery steer). Özetleme ARKADAN koşar; sonuç session.compaction.*
// olaylarından akar (onEvent). Fire-and-forget: ok hemen döner.
export async function compact({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  if (s.status === 'running' || s.awaitingFirstOutput) return { ok: false, error: 'tur sürerken özetlenemez' };
  try {
    const r = await api('POST', `/api/session/${s.remoteId}/compact`, {});
    if (!r.ok) return { ok: false, error: 'özetleme reddedildi (HTTP ' + r.status + ')' };
    s._compacting = true;
    s.messages.push({ role: 'agent', text: '⏳ Bağlam özetleniyor…', time: hhmm() });
    capSess(s);
    pushSnapshot(s);
    return { ok: true, sessionId: s.id };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Özel komutlar + AGENTS.md init ──────────────────────────────────────────
// v2: GET /api/command → Command.Info {name, description} (hints/template
// YOK; v1'deki hint alanı telefonda opsiyonel, boş kalıyor). location param
// deepObject ister (?location[directory]=...) — parametresiz çağrı sunucunun
// varsayılan konumunu kullanır, katalog global olduğundan yeterli.
let commandCache = { at: 0, list: [] };
const COMMAND_CACHE_MS = 60_000;
const COMMAND_DESC_MAX = 160;

export async function listCommands(directory) {
  const now = Date.now();
  if (commandCache.list.length && now - commandCache.at < COMMAND_CACHE_MS) {
    return { ok: true, commands: commandCache.list };
  }
  try {
    // ÖLÇÜLDÜ 26.09.2026: OpenAPI location'ı deepObject gösteriyor ama sunucu
    // DÜZ ?directory= bekliyor — parametresiz VE location[directory]= biçimi
    // ikisi de BOŞ liste döndürüyor, düz parametre 2 komut getirdi (init,
    // review). Komutlar PROJE DİZİNİNE göre kayıtlı: ev dizininde boş döner,
    // agtest'te dolu. Dizin önceliği: istemciden gelen directory (query),
    // sonra EN YENİ açık oturumun cwd'si ([0] değil — o en eski oturum),
    // yoksa ev dizini.
    const dir = String(directory || '').trim()
      || [...sessions.values()].at(-1)?.cwd
      || os.homedir();
    const r = await api('GET', '/api/command?directory=' + encodeURIComponent(dir));
    const raw = Array.isArray(r.json?.data) ? r.json.data : [];
    const list = raw.filter(c => c?.name).map(c => ({
      name: String(c.name),
      desc: String(c.description || '').replace(/\s+/g, ' ').trim().slice(0, COMMAND_DESC_MAX),
      hint: '',
      source: '',
    }));
    if (list.length) commandCache = { at: now, list };
    return { ok: true, commands: list };
  } catch (e) {
    // Bayat liste yoksa BOŞ dönüyoruz: telefon boş listede hiçbir şey çizmiyor.
    return { ok: false, error: e?.message || String(e), commands: [] };
  }
}

/** POST /opencode2-app/command — komutu bir tur olarak koşar (v2: {name, text}). */
export async function runCommand({ sessionId, command, arguments: args } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const ad = String(command || '').trim().replace(/^\//, '');
  if (!ad) return { ok: false, error: 'command gerekli' };
  if (s.status === 'running') return { ok: false, error: 'busy' };
  const arg = String(args || '').trim();
  try {
    const rid = await ensureRemote(s);
    // Kullanıcı satırı komutun kendisi ("/review HEAD~1") — v1 ile aynı kural:
    // serve'ün açtığı şablon satırı ekranı doldurmasın.
    s.messages.push({ role: 'user', text: '/' + ad + (arg ? ' ' + arg : ''), time: hhmm() });
    capSess(s);
    // Komut da bir tur: prompt() ile aynı gerekçe, geri sar şeridi düşer.
    commitRevert(s);
    s.awaitingFirstOutput = true;
    s.status = 'running';
    pushSnapshot(s);
    // v2 gövdesi: {name, text} — text ZORUNLU (argüman yoksa boş dizi değil "").
    const r = await api('POST', `/api/session/${rid}/command`, { name: ad, text: arg });
    if (!r.ok) {
      s.status = 'idle';
      s.awaitingFirstOutput = false;
      pushSnapshot(s);
      return { ok: false, error: 'komut reddedildi (HTTP ' + r.status + ')' };
    }
    return { ok: true, sessionId: s.id };
  } catch (e) {
    s.status = 'idle';
    s.awaitingFirstOutput = false;
    pushSnapshot(s);
    return { ok: false, error: e?.message || String(e) };
  }
}

/**
 * POST /opencode2-app/init — AGENTS.md turu.
 * v2'nin "init" komutu gerçek (ölçüldü 26.09.2026: /api/command listesinde
 * "guided AGENTS.md setup") — prompt taklidi yerine komut olarak koşuyor,
 * v1'in /session/:id/init ucuna denk.
 */
export async function initAgentsFile({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (s.status === 'running') return { ok: false, error: 'busy' };
  return runCommand({ sessionId, command: 'init', arguments: '' });
}

// ── Steer (süren tura enjeksiyon) ───────────────────────────────────────────
// v2'nin teslim kiplerinden "steer": süren tura ANINDA enjekte edilir, tur
// kesilmez — ajan mesajı okur ve yönünü değiştirebilir. v1'de bu yoktu
// (v1'de yalnız kuyruk vardı); telefonda "Yönlendir" tuşu bu ucu çağırır
// (POST /opencode2-app/steer, MidTurnSend.kt sözleşmesi).
export async function steer({ sessionId, text } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const clean = String(text ?? '');
  if (!clean.trim()) return { ok: false, error: 'empty prompt' };
  if (!s.remoteId) return { ok: false, error: 'oturum henüz açılmadı' };
  try {
    const r = await api('POST', `/api/session/${s.remoteId}/prompt`, { text: clean, delivery: 'steer' });
    if (!r.ok) return { ok: false, error: 'yönlendirme reddedildi (HTTP ' + r.status + ')' };
    // Tur zaten sürüyor (steer ancak o zaman anlamlı): durum makinesine
    // dokunulmaz, yalnız kullanıcı satırı sohbete düşer.
    s.messages.push({ role: 'user', text: clean, time: hhmm() });
    capSess(s);
    pushSnapshot(s);
    return { ok: true, sessionId: s.id };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Fork (buradan çatalla) ──────────────────────────────────────────────────
// v2: POST /api/session/:id/fork {before: msg} → 200 {data: Session.Info} —
// "before" mesajından ÖNCEKİ geçmişi kopyalayan ÇOCUK oturum (hedef ve
// sonrası yok). dropUserTurns sayımı rewind ile aynı sözleşme (Sondan N.
// kullanıcı turu → hedef); telefon "buradan çatalla" menüsü bu sayıyı üretir.
// Orijinal oturuma dokunulmaz; yeni oturum köprü sekmesi olarak açılır.
export async function forkFromMessage({ sessionId, dropUserTurns } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const drop = Math.floor(Number(dropUserTurns));
  if (!Number.isFinite(drop) || drop < 1) return { ok: false, error: 'dropUserTurns >= 1 olmalı' };
  if (s.status === 'running') return { ok: false, error: 'tur sürerken çatallanamaz' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  try {
    const users = await fetchUserMessages(s);
    if (users.length < drop) return { ok: false, error: 'çatallanacak mesaj bulunamadı (' + users.length + ' < ' + drop + ')' };
    const hedef = users[users.length - drop];
    const r = await api('POST', `/api/session/${s.remoteId}/fork`, { before: hedef.messageID });
    if (!r.ok) return { ok: false, error: 'çatallanamadı (HTTP ' + r.status + ')' };
    const yeniId = r.json?.data?.id;
    if (!yeniId) return { ok: false, error: 'serve yeni oturum kimliği döndürmedi' };
    // Fork her çağrıda YENİ kimlik üretir; adoptSession'ın "varsa aç" kontrolü
    // çakışmaz. Dönen köprü kimliği telefonda YENİ SEKME olarak açılır
    // (claude-app fork-from ile aynı sözleşme).
    const adopt = await adoptSession({ id: yeniId });
    if (!adopt.ok) return { ok: false, error: adopt.error || 'yeni oturum açılamadı' };
    return { ok: true, sessionId: adopt.sessionId };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Oturum talimatları (instructions entries) ───────────────────────────────
// v2 experimental: oturuma AGENTS.md'ye dokunmadan KALICI talimat parçası
// ekleme. Key şeması ^[a-z0-9][a-z0-9._-]*$; value serbest JSON (metin ver).
// Değişiklik "bir sonraki adım sınırında" duyurulur — koşan turu bölmez.
// Telefon UI'sı henüz yok; REST/web üzerinden kullanılır.
const INSTRUCTION_KEY_RE = /^[a-z0-9][a-z0-9._-]*$/;

export async function listInstructions({ sessionId } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  try {
    const r = await api('GET', `/api/experimental/session/${s.remoteId}/instructions/entries`);
    return { ok: true, entries: Array.isArray(r.json?.data) ? r.json.data : [] };
  } catch (e) {
    return { ok: false, error: e?.message || String(e), entries: [] };
  }
}

export async function putInstruction({ sessionId, key, value } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const k = String(key || '').trim();
  if (!INSTRUCTION_KEY_RE.test(k)) {
    return { ok: false, error: 'anahtar biçimi geçersiz (a-z 0-9 . _ -)' };
  }
  if (value === undefined || value === null) return { ok: false, error: 'value gerekli' };
  try {
    const rid = await ensureRemote(s);
    const r = await api('PUT', `/api/experimental/session/${rid}/instructions/entries/${encodeURIComponent(k)}`, { value });
    return r.ok ? { ok: true } : { ok: false, error: 'kaydedilemedi (HTTP ' + r.status + ')' };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

export async function deleteInstruction({ sessionId, key } = {}) {
  const s = sessions.get(sessionId);
  if (!s) return { ok: false, error: 'session not found' };
  const k = String(key || '').trim();
  if (!k) return { ok: false, error: 'key gerekli' };
  if (!s.remoteId) return { ok: false, error: 'opencode oturumu başlatılmamış' };
  try {
    const r = await api('DELETE', `/api/experimental/session/${s.remoteId}/instructions/entries/${encodeURIComponent(k)}`);
    return r.ok ? { ok: true } : { ok: false, error: 'silinemedi (HTTP ' + r.status + ')' };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

// ── Kayıtlı izin kuralları ──────────────────────────────────────────────────
// v2: "always" yanıtı verilen izinler sunucuda kural olarak birikir. Bunlar
// listelenip tek tek kaldırılabilsin — telefonda güvenlik yüzeyini görünür
// kılar. projectID ile süzülebilir; süzme yoksa hepsi.
export async function listSavedPermissions() {
  try {
    const r = await api('GET', '/api/permission/saved');
    const rules = Array.isArray(r.json?.data) ? r.json.data : [];
    return {
      ok: true,
      rules: rules.map(x => ({
        id: x.id || '',
        projectID: x.projectID || '',
        action: x.action || '',
        resource: x.resource || '',
        created: x.time?.created || 0,
      })),
    };
  } catch (e) {
    return { ok: false, error: e?.message || String(e), rules: [] };
  }
}

export async function deleteSavedPermission({ id } = {}) {
  if (!id) return { ok: false, error: 'id gerekli' };
  try {
    const r = await api('DELETE', `/api/permission/saved/${encodeURIComponent(id)}`);
    return r.ok ? { ok: true } : { ok: false, error: 'silinemedi (HTTP ' + r.status + ')' };
  } catch (e) {
    return { ok: false, error: e?.message || String(e) };
  }
}

export function subscribe(sessionId, ws, opts = {}) {
  sessionCore.subscribe(sessions.get(sessionId), ws, opts);
}

export function listLiveProcesses() {
  const out = [];
  if (server.child && server.child.exitCode === null) out.push({ pid: server.child.pid, kind: 'serve-v2' });
  return out;
}

export function killAllSessions() {
  const n = sessions.size;
  sessions.clear();
  byRemote.clear();
  if (server.child) { try { killChildTree(server.child.pid); } catch {} }
  stopEventStream();
  server.child = null; server.base = ''; server.password = '';
  return { ok: true, killed: n };
}

export function cleanupTmp() { /* v2 geçici dosya bırakmıyor; sözleşme uyumu için var */ }

// Açık sekme ısıtması (bkz. backend-warmth.mjs). v1'den DEVRALINDI: sunucu
// ~200 MB'lık bir süreç ve soğuk açılışı ölçümde 30-45 sn sürüyor; telefon
// açık sekmeyi bildirdiğinde ilk prompt'u beklemeden kaldırıyoruz.
// LİSTE uçları bunu ÇAĞIRMAZ — `serverUp()` kuralı orada geçerli (sunucuyu
// teşhis çağrısıyla uyandırmak 205 MB'lık çocuğu boşa açıyordu).
export async function warmup() {
  const base = await ensureServer();
  return { ok: !!base, pid: server.child?.pid || null };
}

// ── Test kancaları (v1'in __testSetDeps geleneği) ───────────────────────────
// Yalnız okuma/saf parçalar: api() MOCK'LANMIYOR — spawn eden yolların
// (getModels, revert, …) testi canlı ölçümlere (probe-features.mjs) bırakıldı;
// buradaki amaç yerel/saf mantığın (kesme, normalize, doğrulama, metadata)
// gerçek sunucu olmadan da kırılmasın.
export function __testSession(sessionId) { return sessions.get(sessionId) || null; }
export function __testCutLocalMessages(s, userTurns) { return cutLocalMessages(s, userTurns); }
export function __testCommitRevert(s) { return commitRevert(s); }
export function __testThoughtSlot(s, row) { return thoughtSlot(s, row); }
export function __testNormalizeDiffEntry(raw) { return normalizeDiffEntry(raw); }
export function __testMapCatalogModel(m) { return mapCatalogModel(m); }
export function __testMapSkillInfo(x) { return mapSkillInfo(x); }
export function __testMapAgents(data) { return mapAgents(data); }
export function __testPeerIleHizala(models, peer) { return peerIleHizala(models, peer); }
