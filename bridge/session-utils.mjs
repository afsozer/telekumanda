// Shared session utilities for CLI backend adapters.
// Consolidates patterns duplicated across app-server and planner session managers.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';

export const MAX_MESSAGES = 800;          // en çok kaç GERÇEK TUR (user/agent) tutulur
export const MAX_ROWS_HARD = 4000;        // araç+thinking dâhil mutlak ham-satır tavanı
export const MAX_TOOL_DETAIL_CHARS = 8 * 1024 * 1024;
// Yayın kısıcısı. 120 ms saniyede ~8 güncelleme demekti; delta bant genişliğini
// çözdükten sonra kalan maliyet İSTEMCİ CPU'suydu (tablette ölçüldü 13.08.2026:
// izleme sırasında uygulama 397 mAh, cihazdaki uygulama CPU'sunun üçte ikisi).
// 350 ms saniyede ~3 güncelleme — akış gözle hâlâ akıcı, işleme yükü ~3'te 1.
// Tur sınırları kısıcıyı beklemez (flushThrottle), yani "bitti" anı gecikmez.
const THROTTLE_MS = Math.max(0, Number(process.env.AGENTBRIDGE_PUSH_THROTTLE_MS) || 350);

export function hhmm() {
  const d = new Date();
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

// Mesaj listesini baştan kırpar ve kırpılan satırları döndürür. İki kısıt:
//   1. En çok `max` GERÇEK TUR (role user/agent) tutulur. Araç çağrısı ve thinking
//      satırları (role:'thought') sayıma KATILMAZ — araç-yoğun tek bir tur onlarca
//      'thought' satırı üretip eski konuşmayı erken kırpıyor, "geçmiş kayboluyor"
//      etkisi bundan doğuyordu. Artık tavan turlara bağlı; araç izi bedava.
//   2. Ham satır (araç+thinking dâhil) sayısı `hardMax`'ı aşamaz: patolojik
//      döngülerde bellek/snapshot sigortası.
// Kesme noktası iki kısıttan büyüğü (daha çok kırpan) alınır.
export function capMessages(s, max = MAX_MESSAGES, onEvicted, hardMax = MAX_ROWS_HARD) {
  if (!s || !Array.isArray(s.messages)) return [];
  const rows = s.messages;
  const n = rows.length;
  let cut = 0;
  if (max > 0) {
    let turns = 0;
    for (let i = n - 1; i >= 0; i--) {
      const r = rows[i];
      if (r && (r.role === 'user' || r.role === 'agent') && ++turns === max) { cut = i; break; }
    }
  }
  if (hardMax > 0 && n - cut > hardMax) cut = n - hardMax;
  if (cut <= 0) return [];
  const evicted = rows.splice(0, cut);
  if (typeof onEvicted === 'function') {
    for (const msg of evicted) {
      try { onEvicted(msg); } catch {}
    }
  }
  return evicted;
}

export function capToolDetails(s, budget = MAX_TOOL_DETAIL_CHARS) {
  if (!s || !Array.isArray(s.toolDetails)) return;
  let total = 0;
  for (const v of s.toolDetails) total += String(v || '').length;
  if (total <= budget) return;
  for (let i = 0; i < s.toolDetails.length && total > budget; i++) {
    const cur = String(s.toolDetails[i] || '');
    if (!cur || cur === '[old detail trimmed]') continue;
    if (cur.length <= '[old detail trimmed]'.length) continue;
    s.toolDetails[i] = '[old detail trimmed]';
    total -= cur.length - s.toolDetails[i].length;
  }
}

export function appendToolDetail(s, index, text, maxEntryChars = 20_000) {
  if (!s || !Array.isArray(s.toolDetails) || index == null || index < 0) return;
  const cur = String(s.toolDetails[index] || '');
  let next = cur + String(text || '');
  if (next.length > maxEntryChars) next = '[trimmed]\n' + next.slice(-maxEntryChars);
  s.toolDetails[index] = next;
  capToolDetails(s);
}

export function pruneEvictedThoughtDetail(s, msg) {
  const idx = msg && typeof msg.thoughtIndex === 'number' ? msg.thoughtIndex : -1;
  if (!s || !Array.isArray(s.toolDetails) || idx < 0) return;
  const stillReferenced = s.messages.some(m => m && m.thoughtIndex === idx);
  if (!stillReferenced) s.toolDetails[idx] = '';
}

export function withStreamSeq(s, obj) {
  if (!s || !obj || typeof obj !== 'object') return obj;
  if (obj.seq != null) return obj;
  if (!['conversation', 'snapshot', 'delta', 'end'].includes(obj.type)) return obj;
  s._seq = (Number.isFinite(s._seq) ? s._seq : 0) + 1;
  return { ...obj, seq: s._seq };
}

export function broadcast(s, obj) {
  const msg = JSON.stringify(withStreamSeq(s, obj));
  for (const ws of s.subscribers) {
    try { ws.send(msg); } catch {}
  }
}

export function makeThrottler(pushSnapshot) {
  function throttledPush(s) {
    const nowTs = Date.now();
    if (s._lastPush && (nowTs - s._lastPush) < THROTTLE_MS) {
      if (s._pushTimer) return;
      s._pushTimer = setTimeout(() => {
        s._pushTimer = null;
        s._lastPush = Date.now();
        pushSnapshot(s);
      }, THROTTLE_MS - (nowTs - s._lastPush));
      return;
    }
    s._lastPush = nowTs;
    pushSnapshot(s);
  }
  function flushThrottle(s) {
    if (s._pushTimer) { clearTimeout(s._pushTimer); s._pushTimer = null; }
    pushSnapshot(s);
    s._lastPush = 0;
  }
  return { throttledPush, flushThrottle };
}


export function logWarn(context, msg, meta) {
  try { console.warn(`[${context}] ${msg}`, meta ? JSON.stringify(meta) : ''); } catch {}
}

export function killChildTree(pid) {
  try { spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true }); } catch (e) { logWarn('session-utils', 'killChildTree failed', { pid, error: e.message }); }
}

/**
 * process.on('exit') içinden çağrılacak sürüm.
 *
 * Asenkron spawn orada İŞE YARAMIYOR: 3 Ağu 2026'da ölçüldü — çıkış kancasından
 * killChildTree çağrılan `opencode serve` süreci hayatta kaldı, taskkill hiç
 * doğmadı (event loop kapandığı için libuv spawn'ı tamamlanmıyor). spawnSync
 * çıkış anında da çalışıyor; yetim süreç birikmesinin sebebi buydu.
 */
export function killChildTreeSync(pid) {
  try { spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true }); } catch (e) { logWarn('session-utils', 'killChildTreeSync failed', { pid, error: e.message }); }
}

// Extract multiple-choice options from an assistant message text.
// Returns { question, choices[] } when the text appears to contain a
// question with a numbered/bulleted list of options; otherwise null.
// False-positive guard: only activates when the text ends with a question
// mark OR contains choice-request keywords (hangi/which/select/choose/seç).
export function extractChoices(text) {
  if (!text || typeof text !== 'string') return null;
  const t = text.trim();
  if (!t) return null;

  // Check if this looks like a question asking the user to choose.
  const isQuestion = t.endsWith('?') ||
    /\b(hangi|which|select|choose|se[cç]|pick|tercih|se[cç]enek|option|prefer)\b/i.test(t);
  if (!isQuestion) return null;

  // Extract numbered/bulleted list items. To avoid treating ordinary prose
  // bullet lists as a multiple-choice menu, the choices must form ONE
  // contiguous trailing block: every non-empty line from the first choice to
  // the end of the message must itself be a choice line. A genuine "pick one"
  // menu ends with its options; a document with bulleted sections has prose
  // after (or between) its bullets and is rejected here.
  const lines = t.split(/\r?\n/);
  const isChoiceLine = (l) => l.match(/^\s*(\d+[\).]|[-*•])\s+(.+)$/);

  let choiceStart = -1;
  for (let i = 0; i < lines.length; i++) {
    if (isChoiceLine(lines[i])) { choiceStart = i; break; }
  }
  if (choiceStart < 0) return null;

  const choices = [];
  for (let i = choiceStart; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim() === '') continue; // blank lines between items are fine
    const m = isChoiceLine(line);
    if (!m) return null; // non-choice prose after the list started → not a menu
    choices.push(m[2].trim());
  }
  if (choices.length < 2) return null; // at least 2 options needed

  // Real menu options are short labels, not multi-sentence paragraphs.
  if (choices.some((c) => c.length > 80)) return null;

  // Question is the text before the list starts.
  const question = choiceStart > 0
    ? lines.slice(0, choiceStart).join('\n').trim()
    : t;

  return { question, choices };
}

// Global arama (Phase 7): tam transcript içinde prompt/cevap eşleşmesi çıkarır.
// `messages` [{ role, text }] biçiminde; yalnız user/agent satırları taranır
// (thought/tool gürültüsü dışlanır). `q` locale bağımsız küçük harf sorgu olmalı.
// Dönüş [{ role, text, matchOrdinal }]; matchOrdinal oturum içindeki eşleşmeler
// arasında 0‑tabanlı sıradır ve Android sohbet içi aramanın matchRowIds sırasıyla
// hizalıdır (deep‑link doğru eşleşmeye kaydırabilsin). `perSessionCap` tek oturumun
// sonuç sayısını sınırlar ama ordinal sayacı cap sonrası da doğru ilerler.
export function matchTranscriptMessages(messages, q, { perSessionCap = 8 } = {}) {
  const out = [];
  const query = String(q || '');
  if (!Array.isArray(messages) || !query) return out;
  // `text.toLowerCase().includes(query)` YERİNE büyük/küçük harf duyarsız regex
  // (18.08.2026). Eskisi her mesaj için metnin küçük harfli bir KOPYASINI
  // ayırıyordu; arama tüm transcript havuzunu gezdiği için bu, sorgu başına
  // yüzlerce MB'lık geçici string demek. Regex motoru yerinde tarar, kopya yok.
  // Claude'un sıcak arama indeksinde ölçüldü (17,3 MB / 44 bin mesaj):
  // toLowerCase 65 ms, regex 9 ms — aynı 1761 eşleşme.
  // Sorgu KAÇIRILIR: kullanıcı ".", "(", "*" yazdığında bunlar regex operatörü
  // olarak değil düz metin olarak aranmalı.
  // Türkçe İ/ı: çağıranlar sorguyu zaten `toLowerCase()`tan geçiriyor, yani
  // 'İ' iki kod noktasına ('i' + birleşen nokta) açılıyor ve metindeki 'i' ile
  // eşleşmiyordu — bu ESKİ kodda da böyleydi, regex bunu ne düzeltir ne bozar.
  // Doğru çözüm sorguyu ve metni NFKD ile normalleştirmek; ayrı bir iş.
  const desen = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
  let ordinal = 0;
  for (const m of messages) {
    const role = m && m.role;
    if (role !== 'user' && role !== 'agent') continue;
    const text = String(m.text || '');
    if (desen.test(text)) {
      if (out.length < perSessionCap) out.push({ role, text, matchOrdinal: ordinal });
      ordinal += 1;
    }
  }
  return out;
}

export function spawnAsync(cmd, args, opts = {}) {
  return new Promise(resolve => {
    const { encoding, timeout, ...spawnOpts } = opts;
    let child;
    try {
      child = spawn(cmd, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'], ...spawnOpts });
    } catch (error) {
      resolve({ status: -1, stdout: '', stderr: '', error });
      return;
    }
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', d => stdout += (encoding === 'latin1' ? d.toString('latin1') : d.toString()));
    child.stderr.on('data', d => stderr += d.toString());
    let settled = false;
    const t = timeout > 0 ? setTimeout(() => {
      if (!settled) { child.kill(); resolve({ status: -1, stdout, stderr, error: new Error('timeout') }); settled = true; }
    }, timeout) : null;
    child.on('close', code => { if (settled) return; settled = true; if (t) clearTimeout(t); resolve({ status: code, stdout, stderr, error: null }); });
    child.on('error', err => { if (settled) return; settled = true; if (t) clearTimeout(t); resolve({ status: -1, stdout, stderr, error: err }); });
  });
}

const resolvedBins = new Map();
export function resolveExecutableSync(name, envVar) {
  const override = envVar ? String(process.env[envVar] || '').trim() : '';
  if (override) return override;
  const key = name.toLowerCase();
  if (resolvedBins.has(key)) return resolvedBins.get(key);
  const lookup = process.platform === 'win32' ? 'where.exe' : 'which';
  const r = spawnSync(lookup, [name], { encoding: 'utf8', windowsHide: true });
  const candidates = String(r.stdout || '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  const existing = candidates.filter(p => {
    try { return fs.existsSync(p); } catch { return false; }
  });
  const preferred = existing.find(p => /\.exe$/i.test(p))
    || existing.find(p => /\.(cmd|bat|com)$/i.test(p))
    || existing[0]
    || candidates[0]
    || name;
  resolvedBins.set(key, preferred);
  return preferred;
}

// npm .cmd sarmalayıcısı cmd.exe ile çalışır ve cmd argümanlardaki metakarakterleri
// yorumlar (BatBadBut sınıfı): `--model x&komut` köprü bağlamında komut çalıştırırdı.
// Node'un argüman tırnaklaması cmd için yeterli değil; bu yüzden metakarakterli
// argüman sarmalayıcıya hiç verilmez.
/**
 * Onay isteği bayat mı? İstemci onayladığı isteğin kimliğini gönderir; bekleyen
 * istek başkaysa (bildirim eski, araya yeni bir istek girmiş) onay uygulanmaz.
 * Kimlik gönderilmezse (eski istemci) karşılaştırma yapılmaz.
 */
export function isStaleApproval(sentRequestId, pendingRequestId) {
  if (sentRequestId === undefined || sentRequestId === null || sentRequestId === '') return false;
  return String(sentRequestId) !== String(pendingRequestId);
}

export const CMD_UNSAFE_ARG = /[&|<>^%!"()`\r\n]/;

/** Model kimliği: harf, rakam ve . _ : / [ ] @ + - (ör. claude-opus-5-5[1m], openrouter/x/y). */
export function isSafeModelId(model) {
  return typeof model === 'string' && /^[A-Za-z0-9._:/\[\]@+-]{1,200}$/.test(model);
}

/** Oturum kimliği: UUID ya da harf/rakam/_-. ile sınırlı tek parça (yol ayracı ve `..` yok). */
export function isSafeSessionId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/.test(id) && !id.includes('..');
}

export function spawnResolvedExecutable(name, args = [], opts = {}, envVar) {
  const bin = resolveExecutableSync(name, envVar);
  if (process.platform === 'win32' && /\.(cmd|bat)$/i.test(bin)) {
    const unsafe = args.find(a => CMD_UNSAFE_ARG.test(String(a)));
    if (unsafe !== undefined) throw new Error(`${name}: cmd.exe için güvenli olmayan argüman reddedildi`);
    return spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/c', 'call', bin, ...args], { ...opts, shell: false });
  }
  return spawn(bin, args, { ...opts, shell: false });
}

// spawnAsync'in çıktı/timeout sözleşmesini korur, fakat npm .cmd shim'lerinde
// `shell:true` kullanmaz. Windows'ta yalnız shim gerekiyorsa açıkça cmd.exe
// çağrılır; gerçek executable bulunduğunda süreç doğrudan başlatılır.
export function spawnResolvedAsync(name, args = [], opts = {}, envVar) {
  return new Promise(resolve => {
    const { encoding, timeout, ...spawnOpts } = opts;
    let child;
    try {
      child = spawnResolvedExecutable(name, args, {
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
        ...spawnOpts,
      }, envVar);
    } catch (error) {
      resolve({ status: -1, stdout: '', stderr: '', error });
      return;
    }
    let stdout = '';
    let stderr = '';
    child.stdout?.on('data', d => { stdout += encoding === 'latin1' ? d.toString('latin1') : d.toString(); });
    child.stderr?.on('data', d => { stderr += d.toString(); });
    let settled = false;
    const timer = timeout > 0 ? setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill();
      resolve({ status: -1, stdout, stderr, error: new Error('timeout') });
    }, timeout) : null;
    child.on('close', status => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      resolve({ status, stdout, stderr, error: null });
    });
    child.on('error', error => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      resolve({ status: -1, stdout, stderr, error });
    });
  });
}

// MCP komut alanı telefondan tek satır gelir; CLI'lara doğrudan argv geçebilmek
// için yalnız tırnaklı grupları ve boşlukları ayırır. Shell operatörleri özel
// anlam kazanmaz; `&`, `|` ve benzerleri sıradan argüman olarak kalır.
export function tokenizeCommandLine(value) {
  const tokens = [];
  const re = /"([^"]*)"|'([^']*)'|(\S+)/g;
  let match;
  while ((match = re.exec(String(value || ''))) !== null) {
    tokens.push(match[1] ?? match[2] ?? match[3]);
  }
  return tokens;
}
