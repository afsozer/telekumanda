import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnAsync } from './session-utils.mjs';

const __dir = path.dirname(fileURLToPath(import.meta.url));

const FIVE_HOURS_MS = 5 * 60 * 60 * 1000;
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

function fmtUsd(value) {
  return Number.isFinite(value) ? `$${value.toFixed(2)}` : '$0.00';
}

// Ön ödemeli bakiye kartlarının (Nano-GPT, DeepSeek, OpenRouter) çubuğu:
// 10 $ dolu çubuktur, bakiye 0'a doğru eksildikçe çubuk da eksilir. 10 $
// üzerindeki bakiyede çubuk dolu kalır; istemci `creditUsd`ye bakıp o durumu
// ayrı renkle çizer (17.09.2026). Bu bir kota değil, sıfıra inen bir bakiye —
// yüzde/pencere/yenilenme gibi kota kavramları bu kovada yok.
export const CREDIT_BAR_FULL_USD = 10;

export function creditBucketFields(usd) {
  const amount = Number.isFinite(usd) ? Math.max(0, usd) : 0;
  return {
    // Çubuk için 4 basamak yeter; kayan nokta artığı (0.95482763599…) telde gezmesin.
    remainingFraction: Number(Math.min(1, amount / CREDIT_BAR_FULL_USD).toFixed(4)),
    metered: true,
    creditUsd: Number(amount.toFixed(2)),
  };
}

// Nano-GPT anahtarı bu makinede OpenCode sağlayıcısında zaten tanımlı. Aynı
// anahtarı ikinci bir dosyaya kopyalamıyoruz: ortam değişkeni varsa onu, yoksa
// OpenCode'un PC-yerel yapılandırmasını okuyoruz. Değer hiçbir yanıta girmez.
function configuredNanoGptKey() {
  const fromEnv = String(process.env.NANOGPT_API_KEY || '').trim();
  if (fromEnv) return fromEnv;
  try {
    const config = JSON.parse(fs.readFileSync(
      path.join(os.homedir(), '.config', 'opencode', 'opencode.json'),
      'utf8',
    ));
    return String(config?.provider?.nanogpt?.options?.apiKey || '').trim();
  } catch {
    return '';
  }
}

// Nano-GPT'nin kullanım ucu: verilen UTC gün aralığındaki harcama ve istek
// sayısı. Uç ANAHTAR BAŞINA sayıyor (bakiye ise hesabın tamamı); bu PC'deki
// anahtar Mac KotaWidget'ınkiyle aynı (05.10.2026, özetiyle karşılaştırıldı),
// yani rakam Mac kartıyla tutar. Gün kutuları UTC: TSİ 03:00'tan önce "bugün"
// hâlâ dünün kutusudur. Bakiyeden bağımsız: düşerse null, kart bakiyeyi yine
// gösterir.
export async function fetchNanoGptToday({
  apiKey = configuredNanoGptKey(),
  fetchImpl = globalThis.fetch,
  now = new Date(),
} = {}) {
  const key = String(apiKey || '').trim();
  if (!key || typeof fetchImpl !== 'function') return null;
  const day = now.toISOString().slice(0, 10);
  try {
    const response = await fetchImpl(
      `https://nano-gpt.com/api/v1/usage?group_by=model&from=${day}&to=${day}`,
      {
        method: 'GET',
        headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
        signal: AbortSignal.timeout(5_000),
      },
    );
    if (!response?.ok) return null;
    const totals = (await response.json())?.totals;
    // netCostUsd indirim düşülmüş tutar; yoksa brüt costUsd.
    const spend = Number(totals?.netCostUsd ?? totals?.costUsd);
    const requests = Number(totals?.requests);
    if (!Number.isFinite(spend) || !Number.isFinite(requests)) return null;
    return { spendUsd: spend, requests: Math.round(requests) };
  } catch {
    return null;
  }
}

// Nano-GPT'nin resmi bakiye ucu. Kota yüzdesi değil ön ödemeli USD bakiyesi;
// çubuk creditBucketFields ölçeğiyle (10 $ = dolu) çizilir. Bugünün harcaması
// aynı kovaya ek alan olarak biner (todaySpendUsd/todayRequests); Android
// widget'ı "bugün $x · n istek" satırını oradan çizer.
export async function fetchNanoGptUsage({
  apiKey = configuredNanoGptKey(),
  fetchImpl = globalThis.fetch,
  now = new Date(),
} = {}) {
  const key = String(apiKey || '').trim();
  if (!key || typeof fetchImpl !== 'function') return null;
  try {
    const [response, today] = await Promise.all([
      fetchImpl('https://nano-gpt.com/api/check-balance', {
        method: 'POST',
        headers: { 'x-api-key': key, Accept: 'application/json' },
        signal: AbortSignal.timeout(5_000),
      }),
      fetchNanoGptToday({ apiKey: key, fetchImpl, now }),
    ]);
    if (!response?.ok) return null;
    const body = await response.json();
    const usd = Number(body?.usd_balance);
    if (!Number.isFinite(usd)) return null;
    const nano = Number(body?.nano_balance);
    const parts = [];
    if (today) parts.push(`Bugün ${fmtUsd(today.spendUsd)} • ${today.requests} istek`);
    if (Number.isFinite(nano) && nano > 0) parts.push(`${nano.toFixed(8)} XNO`);
    return {
      name: 'Nano-GPT',
      source: 'nanogpt',
      description: 'Resmi Nano-GPT API bakiyesi.',
      buckets: [{
        id: 'nanogpt-balance',
        label: 'Kalan bakiye',
        window: '',
        value: fmtUsd(usd),
        description: parts.join(' • '),
        resetTime: '',
        ...creditBucketFields(usd),
        // Kullanım ucu düştüyse alanlar HİÇ yok: istemci "0 $" ile "bilinmiyor"u
        // ayırabilsin.
        ...(today ? {
          todaySpendUsd: Number(today.spendUsd.toFixed(4)),
          todayRequests: today.requests,
        } : {}),
      }],
    };
  } catch {
    return null;
  }
}

// OpenRouter'in resmi kredi ucu hesap toplamlarini verir. Anahtar yalniz PC
// ortam degiskeninden okunur; Android'e veya /usage yanitina asla tasinmaz.
// Standart API anahtari bu hesapta canli olarak dogrulandi (12.08.2026).
export async function fetchOpenRouterUsage({
  apiKey = process.env.OPENROUTER_API_KEY || '',
  fetchImpl = globalThis.fetch,
} = {}) {
  const key = String(apiKey || '').trim();
  if (!key || typeof fetchImpl !== 'function') return null;
  try {
    const response = await fetchImpl('https://openrouter.ai/api/v1/credits', {
      method: 'GET',
      headers: { Authorization: `Bearer ${key}` },
      signal: AbortSignal.timeout(5_000),
    });
    if (!response?.ok) return null;
    const body = await response.json();
    const total = Number(body?.data?.total_credits);
    const used = Number(body?.data?.total_usage);
    if (!Number.isFinite(total) || !Number.isFinite(used)) return null;
    const remaining = Math.max(0, total - used);
    return {
      name: 'OpenRouter',
      source: 'openrouter',
      description: 'Resmi OpenRouter hesap kredi bakiyesi.',
      buckets: [{
        id: 'openrouter-credit',
        label: 'Kalan kredi',
        window: '',
        value: fmtUsd(remaining),
        description: `Toplam ${fmtUsd(total)} • Kullanım ${fmtUsd(used)}`,
        resetTime: '',
        ...creditBucketFields(remaining),
      }],
    };
  } catch {
    return null;
  }
}

function fmtMoney(value, currency) {
  if (!Number.isFinite(value)) return '';
  if (currency === 'USD') return fmtUsd(value);
  return `${value.toFixed(2)} ${currency}`;
}

// DeepSeek'in resmi bakiye ucu. Burada bir KOTA yok, yalnız ön ödemeli bakiye
// var — dolayısıyla "kalanın toplama oranı" diye bir şey de yok. USD satırında
// çubuk creditBucketFields ölçeğiyle (10 $ = dolu) çizilir; başka para birimi
// seçilmek zorunda kalınırsa çubuk yalnız bitip bitmediğini (is_available)
// gösterir. Anahtar yalnız PC ortam değişkeninden okunur; /usage yanıtına asla
// taşınmaz. Canlı olarak doğrulandı (13.08.2026).
export async function fetchDeepSeekUsage({
  apiKey = process.env.DEEPSEEK_API_KEY || '',
  fetchImpl = globalThis.fetch,
} = {}) {
  const key = String(apiKey || '').trim();
  if (!key || typeof fetchImpl !== 'function') return null;
  try {
    const response = await fetchImpl('https://api.deepseek.com/user/balance', {
      method: 'GET',
      headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
      signal: AbortSignal.timeout(5_000),
    });
    if (!response?.ok) return null;
    const body = await response.json();
    const infos = Array.isArray(body?.balance_infos) ? body.balance_infos : [];
    // Hesapta birden çok para birimi olabiliyor; USD varsa onu göster.
    const info = infos.find(i => String(i?.currency || '').toUpperCase() === 'USD') || infos[0];
    const currency = String(info?.currency || 'USD').toUpperCase();
    const total = Number(info?.total_balance);
    if (!Number.isFinite(total)) return null;
    const granted = Number(info?.granted_balance);
    const toppedUp = Number(info?.topped_up_balance);
    const parts = [];
    if (Number.isFinite(toppedUp)) parts.push(`Yüklenen ${fmtMoney(toppedUp, currency)}`);
    // Hediye kredi genelde 0; sıfırken satırı kalabalık etmesin.
    if (Number.isFinite(granted) && granted > 0) parts.push(`Hediye ${fmtMoney(granted, currency)}`);
    return {
      name: 'DeepSeek',
      source: 'deepseek',
      description: 'Resmi DeepSeek hesap bakiyesi.',
      buckets: [{
        id: 'deepseek-balance',
        label: 'Kalan bakiye',
        window: '',
        value: fmtMoney(total, currency),
        description: parts.join(' • '),
        resetTime: '',
        ...(currency === 'USD'
          ? creditBucketFields(total)
          : { remainingFraction: body?.is_available ? 1 : 0, metered: false }),
      }],
    };
  } catch {
    return null;
  }
}

// OpenCode Go'nun resmi kullanim ucu. Zen'den (token basina odeme) FARKLI:
// burada bakiye yok, duz abonelikte uc pencerelik kota var. Zen tarafinin
// bakiye/kredi ucu YOK — 16.08.2026'da /zen/v1 altinda usage, credits,
// account, balance, me denendi, hepsi 404; tamamlama yanitinda da kota
// basligi donmuyor. Yani Zen harcamasi bu karta giremez, aramaya gerek yok.
// Anahtar yalniz PC ortam degiskeninden okunur, /usage yanitina TASINMAZ.
const OPENCODE_GO_WINDOWS = [
  { key: 'rolling', id: 'opencode-go-5h', label: '5 saat' },
  { key: 'weekly', id: 'opencode-go-week', label: 'Haftalık' },
  { key: 'monthly', id: 'opencode-go-month', label: 'Aylık' },
];
export async function fetchOpenCodeGoUsage({
  apiKey = process.env.OPENCODE_API_KEY || '',
  fetchImpl = globalThis.fetch,
} = {}) {
  const key = String(apiKey || '').trim();
  if (!key || typeof fetchImpl !== 'function') return null;
  try {
    const response = await fetchImpl('https://opencode.ai/zen/go/v1/usage', {
      method: 'GET',
      headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
      signal: AbortSignal.timeout(5_000),
    });
    if (!response?.ok) return null;
    const body = await response.json();
    const usage = body?.usage;
    if (!usage || typeof usage !== 'object') return null;
    const buckets = [];
    for (const w of OPENCODE_GO_WINDOWS) {
      const item = usage[w.key];
      const percent = Number(item?.percent);
      // Pencere yoksa ya da yuzde sayi degilse o satiri hic basma: "%100 kaldı"
      // yazip kotanin dolu oldugunu ima etmek yanlis guven verir.
      if (!item || !Number.isFinite(percent)) continue;
      const remaining = Math.max(0, Math.min(100, 100 - percent));
      const status = String(item.status || '');
      buckets.push({
        id: w.id,
        label: w.label,
        window: '',
        value: `%${Math.round(remaining)} kaldı`,
        remainingFraction: remaining / 100,
        // description bos: istemci resetTime'i zaten "Yenilenme: ..." satiri
        // olarak basiyor (bkz. claude/codex kartlari). Yalniz beklenmedik bir
        // durum kodu gelirse yaz — sessizce yutulmasin.
        description: status && status !== 'ok' ? `Durum: ${status}` : '',
        resetTime: item.resetsAt ? new Date(item.resetsAt).toISOString() : '',
        // Bar CIZILSIN. OpenRouter/DeepSeek kartlarinda metered:false, cunku
        // onlar on odemeli bakiye — "kalanin toplama orani" diye bir sey yok.
        // Burada kota var ve yuzde zaten olculu; Claude/Codex kartlariyla ayni
        // tur. Istemci metered:false gorunce cubugu HIC cizmiyor
        // (HubUsageScreen.UsageBucketRow), ilk surumde yanlislikla oyleydi.
        // (Bakiye kartlari 17.09.2026'dan beri creditBucketFields ile
        // metered:true; 10 $ = dolu cubuk.)
        metered: true,
      });
    }
    if (!buckets.length) return null;
    return {
      name: 'OpenCode Go',
      source: 'opencode-go',
      description: 'Resmi OpenCode Go abonelik kotası.',
      buckets,
    };
  } catch {
    return null;
  }
}

function walkJsonl(root, maxFiles = 80) {
  const files = [];
  function walk(dir) {
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      const fp = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(fp);
      else if (entry.name.endsWith('.jsonl')) {
        try { files.push({ fp, mtime: fs.statSync(fp).mtimeMs }); } catch {}
      }
    }
  }
  walk(root);
  return files.sort((a, b) => b.mtime - a.mtime).slice(0, maxFiles).map(f => f.fp);
}

// Kullanım ekranı için tam transcript okumak bridge event-loop'unu dakikalarca
// kilitleyebiliyor (özellikle 30-70 MB'lik araç/görsel satırları). Yalnız dosya
// sonundaki sınırlı pencereyi oku; ilk yarım satırı at.
function readJsonlTail(file, onObject, maxBytes = 256 * 1024) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const st = fs.fstatSync(fd);
    const start = Math.max(0, st.size - maxBytes);
    const len = st.size - start;
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, start);
    let text = buf.toString('utf8');
    if (start > 0) text = text.replace(/^[^\n]*\n?/, '');
    for (const line of text.split(/\r?\n/)) {
      if (!line.trim()) continue;
      try { onObject(JSON.parse(line)); } catch {}
    }
  } catch {
    // best effort
  } finally {
    try { if (fd != null) fs.closeSync(fd); } catch {}
  }
}

export function __testReadJsonlTail(file, onObject, maxBytes) {
  return readJsonlTail(file, onObject, maxBytes);
}

function fmtDate(seconds) {
  if (!seconds) return '';
  try {
    return new Date(seconds * 1000).toLocaleString('tr-TR', {
      day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
    });
  } catch { return ''; }
}

function fmtNum(n) {
  return Math.round(n || 0).toLocaleString('tr-TR');
}

function addUsage(sum, usage) {
  if (!usage) return;
  // Real per-message counts live in usage.iterations[]; top-level values are often 0 in newer JSONL.
  // Fall back to top-level only if iterations is absent (older transcripts).
  const src = (Array.isArray(usage.iterations) && usage.iterations.length)
    ? usage.iterations[0]
    : usage;
  sum.input += src.input_tokens || 0;
  sum.cached += src.cache_read_input_tokens || src.cached_input_tokens || 0;
  sum.cacheCreate += src.cache_creation_input_tokens || 0;
  sum.output += src.output_tokens || 0;
  sum.reasoning += src.reasoning_output_tokens || 0;
  sum.total += src.total_tokens || 0;
  sum.webSearch += (usage.server_tool_use?.web_search_requests || 0);
}

function emptySum() {
  return { input: 0, cached: 0, cacheCreate: 0, output: 0, reasoning: 0, total: 0, turns: 0, webSearch: 0 };
}

function tokenSummary(sum) {
  const total = sum.total || (sum.input + sum.cached + sum.cacheCreate + sum.output + sum.reasoning);
  const parts = [`${fmtNum(total)} token`, `${fmtNum(sum.turns)} tur`];
  if (sum.webSearch) parts.push(`${fmtNum(sum.webSearch)} web ara.`);
  return parts.join(', ');
}

// Live remaining-limit data, the same source the Codex app/CLI shows.
// GET https://chatgpt.com/backend-api/wham/usage with the ChatGPT access token.
// Cached + persisted like the Claude one so we don't hammer the endpoint.
const CODEX_UTIL_TTL = 5 * 60 * 1000;
const CODEX_UTIL_CACHE_FILE = path.join(os.tmpdir(), 'agtest-codex-util.json');
let codexUtilCache = { at: 0, data: null };
try {
  const disk = JSON.parse(fs.readFileSync(CODEX_UTIL_CACHE_FILE, 'utf8'));
  if (disk && disk.data) codexUtilCache = disk;
} catch {}
async function fetchCodexUsage(force = false) {
  const now = Date.now();
  if (!force && codexUtilCache.data && now - codexUtilCache.at < CODEX_UTIL_TTL) return codexUtilCache.data;
  let token = '', account = '';
  try {
    const a = JSON.parse(fs.readFileSync(path.join(os.homedir(), '.codex', 'auth.json'), 'utf8'));
    token = a.tokens?.access_token || '';
    account = a.tokens?.account_id || '';
  } catch {}
  if (!token) return codexUtilCache.data;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 5000);
  try {
    const resp = await fetch('https://chatgpt.com/backend-api/wham/usage', {
      method: 'GET',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token, 'chatgpt-account-id': account },
      signal: ctrl.signal,
    });
    if (!resp.ok) return codexUtilCache.data;
    const data = await resp.json();
    codexUtilCache = { at: now, data };
    try { fs.writeFileSync(CODEX_UTIL_CACHE_FILE, JSON.stringify(codexUtilCache)); } catch {}
    return data;
  } catch { return codexUtilCache.data; }
  finally { clearTimeout(timer); }
}

async function codexUsage(force = false) {
  // Preferred: live wham/usage (matches the Codex app exactly).
  const live = await fetchCodexUsage(force);
  const rl = live?.rate_limit;
  if (rl) {
    const win = (w, id, label) => {
      if (!w || w.used_percent == null) return null;
      const remaining = Math.max(0, Math.min(100, 100 - w.used_percent));
      return {
        id, label,
        value: `%${Math.round(remaining)} kaldı`,
        remainingFraction: remaining / 100,
        // description'a sıfırlanma yazma: istemci zaten resetTime'ı "Yenilenme: ..."
        // satırı olarak basıyor, ikisi birlikte aynı bilgiyi alt alta iki kez gösteriyordu.
        description: '',
        resetTime: w.reset_at ? new Date(w.reset_at * 1000).toISOString() : '',
      };
    };
    const buckets = [
      win(rl.primary_window, 'codex-primary', 'Son 5 saat'),
      win(rl.secondary_window, 'codex-secondary', 'Son 7 gün'),
    ].filter(Boolean);
    if (buckets.length) {
      return {
        name: 'Codex',
        description: [live.plan_type ? `Plan: ${live.plan_type}` : '', 'Resmi kalan limit (Codex app ile aynı kaynak).'].filter(Boolean).join(' • '),
        buckets,
      };
    }
  }

  // Fallback: stale rollout rate_limits / local token summary.
  const root = path.join(os.homedir(), '.codex', 'sessions');
  const now = Date.now();
  const sums = { five: emptySum(), week: emptySum() };
  let latestRate = null;
  let latestRateTs = 0;

  for (const file of walkJsonl(root, 12)) {
    readJsonlTail(file, obj => {
      const ts = Date.parse(obj.timestamp || '');
      const payload = obj.payload || {};
      if (payload.type === 'token_count') {
        const usage = payload.info?.last_token_usage;
        if (Number.isFinite(ts)) {
          if (now - ts <= FIVE_HOURS_MS) { addUsage(sums.five, usage); sums.five.turns += 1; }
          if (now - ts <= WEEK_MS) { addUsage(sums.week, usage); sums.week.turns += 1; }
        }
        if (payload.rate_limits && Number.isFinite(ts) && ts >= latestRateTs) {
          latestRate = payload.rate_limits;
          latestRateTs = ts;
        }
      }
    });
  }

  if (!latestRate) {
    if (!sums.week.turns) return null;
    return {
      name: 'Codex',
      description: 'Resmi limit bilgisi bulunamadı; son kayıtların sınırlı yerel özeti gösteriliyor.',
      buckets: [
        { id: 'codex-local-5h', label: 'Son 5 saat', value: tokenSummary(sums.five), remainingFraction: 1, description: 'Yerel Codex kayıtlarından hesaplandı.' },
        { id: 'codex-local-week', label: 'Son 7 gün', value: tokenSummary(sums.week), remainingFraction: 1, description: 'Yerel Codex kayıtlarından hesaplandı.' },
      ],
    };
  }

  const primaryUsed = latestRate.primary?.used_percent ?? null;
  const secondaryUsed = latestRate.secondary?.used_percent ?? null;
  const desc = [
    latestRate.plan_type ? `Plan: ${latestRate.plan_type}` : '',
    latestRate.rate_limit_reached_type ? `Limit durumu: ${latestRate.rate_limit_reached_type}` : '',
    sums.five.turns ? `Son 5 saat yerel kullanım: ${tokenSummary(sums.five)}` : '',
  ].filter(Boolean).join(' • ');
  return {
    name: 'Codex',
    description: desc || 'Codex rollout kayıtlarındaki resmi rate limit alanından alındı.',
    buckets: [
      {
        id: 'codex-primary',
        label: `${latestRate.primary?.window_minutes || 300} dk pencere`,
        value: primaryUsed == null ? '' : `%${100 - primaryUsed} kaldı`,
        remainingFraction: primaryUsed == null ? 0 : Math.max(0, Math.min(1, 1 - primaryUsed / 100)),
        description: '',
        resetTime: latestRate.primary?.resets_at ? new Date(latestRate.primary.resets_at * 1000).toISOString() : '',
      },
      {
        id: 'codex-secondary',
        label: `${Math.round((latestRate.secondary?.window_minutes || 10080) / 1440)} gün pencere`,
        value: secondaryUsed == null ? '' : `%${100 - secondaryUsed} kaldı`,
        remainingFraction: secondaryUsed == null ? 0 : Math.max(0, Math.min(1, 1 - secondaryUsed / 100)),
        description: `Son 7 gün: ${tokenSummary(sums.week)}`,
        resetTime: latestRate.secondary?.resets_at ? new Date(latestRate.secondary.resets_at * 1000).toISOString() : '',
      },
    ],
  };
}

// Pull the official remaining-limit numbers Claude Code's own /usage uses.
// GET https://api.anthropic.com/api/oauth/usage with the OAuth access token.
// Cached: the endpoint is aggressively rate-limited (429), so we refetch at most every
// 5 min and persist the last good result to disk so it survives bridge restarts. Once
// the cache is stale, failures fall back to local token summaries instead of old quota.
const CLAUDE_UTIL_TTL = 5 * 60 * 1000;
const CLAUDE_CODE_CLIENT_ID = '9d1c250a-e61b-44d9-88ed-5944d1962f5e';
const CLAUDE_TOKEN_URL = 'https://platform.claude.com/v1/oauth/token';

// Tek hesap: CLI'nin varsayılan config dizini (28 Ağu 2026'da çok-hesaplı model
// kaldırıldı — bkz. claude-app.mjs).
const CLAUDE_CONFIG_DIR = path.join(os.homedir(), '.claude');
const CLAUDE_UTIL_CACHE_FILE = path.join(os.tmpdir(), 'agtest-claude-util.json');

let claudeUtilCache = { at: 0, data: null };
try {
  const disk = JSON.parse(fs.readFileSync(CLAUDE_UTIL_CACHE_FILE, 'utf8'));
  if (disk && disk.data) claudeUtilCache = disk;
} catch {}

// Refresh token'ı da düşmüş hesaplar (400 invalid_grant). Bu durumda usage
// çağrısı sessizce disk cache'ine düşüyor ve kart CANLI gibi görünüyordu —
// Kart CANLI gibi görünüp saatlerce "%0 kaldı, sıfırlanma 16:50" (geçmiş saat)
// gösterebiliyordu. Buradan işaretleyip karta "yeniden giriş gerekiyor" notu +
// Önbellek rozeti düşüyoruz.
const claudeAuthExpired = new Map(); // configDir -> true

function claudeCredPath(configDir) {
  return path.join(configDir, '.credentials.json');
}
function readClaudeCreds(configDir) {
  try {
    const file = claudeCredPath(configDir);
    return { file, creds: JSON.parse(fs.readFileSync(file, 'utf8')) };
  } catch {
    return { file: claudeCredPath(configDir), creds: null };
  }
}
function writeClaudeCreds(file, creds) {
  const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(creds, null, 2));
  fs.renameSync(tmp, file);
}
async function refreshClaudeTokenIfNeeded(force = false, configDir) {
  const { file, creds } = readClaudeCreds(configDir);
  const oauth = creds?.claudeAiOauth;
  if (!oauth?.refreshToken) return oauth?.accessToken || '';
  const expiresAt = Number(oauth.expiresAt || 0);
  const shouldRefresh = force || !expiresAt || Date.now() > expiresAt - 60_000;
  if (!shouldRefresh && oauth.accessToken) {
    // Yeniden giriş yapılmışsa creds tazedir ve buradan erken dönülür; bayrağı
    // burada temizlemezsek "yeniden giriş gerekiyor" notu login sonrası da asılı
    // kalır (refresh çağrısı bir daha hiç yapılmadığı için).
    claudeAuthExpired.delete(configDir);
    return oauth.accessToken;
  }

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 15000);
  try {
    const resp = await fetch(CLAUDE_TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'claude-code/1.0' },
      body: JSON.stringify({
        grant_type: 'refresh_token',
        refresh_token: oauth.refreshToken,
        client_id: CLAUDE_CODE_CLIENT_ID,
      }),
      signal: ctrl.signal,
    });
    if (!resp.ok) {
      const body = await resp.text().catch(() => '');
      if (/invalid_grant/.test(body)) claudeAuthExpired.set(configDir, true);
      return oauth.accessToken || '';
    }
    const data = await resp.json();
    if (!data.access_token) return oauth.accessToken || '';
    claudeAuthExpired.delete(configDir);
    const nextOauth = {
      ...oauth,
      accessToken: data.access_token,
      refreshToken: data.refresh_token || oauth.refreshToken,
      expiresAt: Date.now() + Math.max(0, Number(data.expires_in || 0)) * 1000,
      scopes: typeof data.scope === 'string' ? data.scope.split(/\s+/).filter(Boolean) : oauth.scopes,
    };
    writeClaudeCreds(file, { ...creds, claudeAiOauth: nextOauth });
    return nextOauth.accessToken;
  } catch {
    return oauth.accessToken || '';
  } finally {
    clearTimeout(timer);
  }
}
async function fetchClaudeUtilization(force = false) {
  const now = Date.now();
  const cache = claudeUtilCache;
  if (!force && cache.data && now - cache.at < CLAUDE_UTIL_TTL) return cache.data;
  let token = await refreshClaudeTokenIfNeeded(false, CLAUDE_CONFIG_DIR);
  if (!token) return cache.data;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 5000);
  try {
    let resp = await fetch('https://api.anthropic.com/api/oauth/usage', {
      method: 'GET',
      headers: { 'Content-Type': 'application/json', 'anthropic-beta': 'oauth-2025-04-20', 'Authorization': 'Bearer ' + token },
      signal: ctrl.signal,
    });
    if (resp.status === 401) {
      token = await refreshClaudeTokenIfNeeded(true, CLAUDE_CONFIG_DIR);
      if (!token) return cache.data;
      resp = await fetch('https://api.anthropic.com/api/oauth/usage', {
        method: 'GET',
        headers: { 'Content-Type': 'application/json', 'anthropic-beta': 'oauth-2025-04-20', 'Authorization': 'Bearer ' + token },
        signal: ctrl.signal,
      });
    }
    if (!resp.ok) return cache.data;
    const data = await resp.json();
    const next = { at: now, data };
    claudeUtilCache = next;
    try { fs.writeFileSync(CLAUDE_UTIL_CACHE_FILE, JSON.stringify(next)); } catch {}
    return data;
  } catch { return cache.data; }
  finally { clearTimeout(timer); }
}

// Canlı plan bilgisi: /api/oauth/profile. .credentials.json'daki subscriptionType
// yalnız yeniden giriste guncellenir ve BAYATLAR (Pro→Max yukseltmesi sonrasi
// "pro" gostermeye devam ediyordu). Profil yaniti orgun guncel tipini ve
// rate_limit_tier'i tasir; 1 saat onbelleklenir, hata halinde null (cagiran
// creds'e duser).
let claudeProfileCache = { at: 0, data: null };
const CLAUDE_PROFILE_TTL = 60 * 60 * 1000;
async function fetchClaudeProfile() {
  const now = Date.now();
  const cache = claudeProfileCache;
  if (cache.data && now - cache.at < CLAUDE_PROFILE_TTL) return cache.data;
  const token = await refreshClaudeTokenIfNeeded(false, CLAUDE_CONFIG_DIR);
  if (!token) return cache.data;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 5000);
  try {
    const resp = await fetch('https://api.anthropic.com/api/oauth/profile', {
      method: 'GET',
      headers: { 'Content-Type': 'application/json', 'anthropic-beta': 'oauth-2025-04-20', 'Authorization': 'Bearer ' + token },
      signal: ctrl.signal,
    });
    if (!resp.ok) return cache.data;
    const data = await resp.json();
    claudeProfileCache = { at: now, data };
    return data;
  } catch { return cache.data; }
  finally { clearTimeout(timer); }
}

// "Max 5x" / "Max 20x" / "Max" / "Pro"; profil yoksa creds'teki eski deger.
function planLabel(profile, credsSubscription) {
  const acc = profile?.account;
  const org = profile?.organization;
  if (acc?.has_claude_max || org?.organization_type === 'claude_max') {
    const m = /max_(\d+)x/.exec(org?.rate_limit_tier || '');
    return m ? `Max ${m[1]}x` : 'Max';
  }
  if (acc?.has_claude_pro || org?.organization_type === 'claude_pro') return 'Pro';
  return credsSubscription;
}

function pctBucket(id, label, util, resetsAt) {
  if (util == null) return null;
  const remaining = Math.max(0, Math.min(100, 100 - util));
  return {
    id, label,
    value: `%${Math.round(remaining)} kaldı`,
    remainingFraction: remaining / 100,
    // description boş: istemci resetTime'ı zaten "Yenilenme: ..." satırı olarak
    // basıyordu, buraya da yazınca aynı bilgi alt alta iki kez görünüyordu.
    description: '',
    resetTime: resetsAt || '',
  };
}

// Ek kullanım kredisi (plan limiti dolunca devreye giren kredi): usage.spend / extra_usage.
// Masaüstü claude-usage panelindeki "Kredi" satırıyla aynı mantık.
function fmtCredit(m) {
  if (!m || m.amount_minor == null) return null;
  const exp = m.exponent ?? 2;
  const v = m.amount_minor / 10 ** exp;
  const s = Number.isInteger(v) ? String(v) : v.toFixed(exp).replace('.', ',');
  return (m.currency && m.currency !== 'USD' ? `${m.currency} ` : '$') + s;
}

function creditBucket(id, util) {
  const spend = util.spend || {};
  const extra = util.extra_usage || {};
  // Kredi kapalıysa (örn. disabled_reason: org_level_disabled_until) satır HİÇ
  // çizilmez — ham disabled_reason metnini kullanıcıya göstermenin değeri yok.
  if (!spend.enabled && !extra.is_enabled) return null;
  // Hiç kredi yoksa (kullanım 0 + tavan 0/yok) satır yine gereksiz.
  const usedMinor = spend.used?.amount_minor ?? extra.used_credits ?? 0;
  const capMinor = spend.limit?.amount_minor ?? spend.cap?.credits?.amount_minor ?? extra.monthly_limit ?? 0;
  if (!usedMinor && !capMinor) return null;
  const used = fmtCredit(spend.used) ?? '$0';
  const cap = fmtCredit(spend.limit) ?? fmtCredit(spend.cap?.credits)
    ?? fmtCredit({ amount_minor: extra.monthly_limit, exponent: extra.decimal_places, currency: extra.currency });
  const pct = spend.percent ?? extra.utilization ?? 0;
  const remaining = Math.max(0, Math.min(100, 100 - pct));
  return {
    id, label: 'Kredi', window: '',
    value: cap ? `${used} / ${cap}` : used,
    remainingFraction: remaining / 100,
    description: `Ek kullanım kredisi • %${Math.round(remaining)} kaldı`,
    resetTime: '',
  };
}

async function claudeUsage(force = false) {
  let subscription = '';
  let tier = '';
  try {
    const creds = JSON.parse(fs.readFileSync(claudeCredPath(CLAUDE_CONFIG_DIR), 'utf8'));
    subscription = creds.claudeAiOauth?.subscriptionType || '';
    tier = creds.claudeAiOauth?.rateLimitTier || '';
  } catch {}

  // Preferred path: official remaining-limit data, exactly like `claude /usage`.
  const util = await fetchClaudeUtilization(force);
  if (util) {
    const buckets = [
      pctBucket('claude-5h', 'Son 5 saat', util.five_hour?.utilization, util.five_hour?.resets_at),
      pctBucket('claude-week', 'Son 7 gün', util.seven_day?.utilization, util.seven_day?.resets_at),
      pctBucket('claude-week-opus', '7 gün (Opus)', util.seven_day_opus?.utilization, util.seven_day_opus?.resets_at),
    ];
    // Model bazlı haftalık limitler (Fable vb.) artık limits[] içinde weekly_scoped olarak geliyor;
    // eski seven_day_opus/seven_day_sonnet alanları null döndüğünden buradan okunmalı.
    for (const l of util.limits || []) {
      if (l.kind !== 'weekly_scoped' || l.percent == null) continue;
      const model = l.scope?.model?.display_name || 'model';
      buckets.push(pctBucket(
        `claude-week-${model.toLowerCase().replace(/\s+/g, '-')}`,
        `7 gün (${model})`, l.percent, l.resets_at,
      ));
    }
    const filtered = buckets.filter(Boolean);
    // Kredi satırı en altta; kapalı/sıfır kredide hiç çizilmez.
    const credit = creditBucket('claude-credit', util);
    if (credit) filtered.push(credit);
    // Plan canlı profilden (creds'teki subscriptionType bayatlayabiliyor).
    const profile = await fetchClaudeProfile();
    const plan = planLabel(profile, subscription);
    const liveTier = profile?.organization?.rate_limit_tier || tier;
    // Canlı çekim başarısızsa fetchClaudeUtilization sessizce disk cache'ini
    // döndürüyor; cache yaşı TTL'i aştıysa bunu kullanıcıdan saklama.
    const cacheAt = claudeUtilCache.at || 0;
    const needsLogin = claudeAuthExpired.get(CLAUDE_CONFIG_DIR) === true;
    const stale = needsLogin || Date.now() - cacheAt > CLAUDE_UTIL_TTL * 2;
    return {
      name: 'Claude Code',
      description: [
        plan ? `Abonelik: ${plan}` : '',
        liveTier ? `Tier: ${liveTier}` : '',
        needsLogin
          ? `Oturum süresi doldu — yeniden giriş gerekiyor; aşağıdaki değerler ${fmtDate(cacheAt / 1000)} önbelleğinden.`
          : stale
            ? `Canlı veri alınamadı; ${fmtDate(cacheAt / 1000)} önbelleği gösteriliyor.`
            : 'Resmi kalan limit (claude /usage ile aynı kaynak).',
      ].filter(Boolean).join(' • '),
      stale,
      buckets: filtered,
    };
  }

  // Fallback: local token summary from JSONL (when offline / token expired).
  const root = path.join(CLAUDE_CONFIG_DIR, 'projects');
  const now = Date.now();
  const sums = { five: emptySum(), week: emptySum() };
  const models = new Map();
  for (const file of walkJsonl(root, 12)) {
    readJsonlTail(file, obj => {
      const ts = Date.parse(obj.timestamp || '');
      const msg = obj.message || {};
      const usage = msg.usage;
      if (!usage || !Number.isFinite(ts)) return;
      if (msg.model) models.set(msg.model, (models.get(msg.model) || 0) + 1);
      if (now - ts <= FIVE_HOURS_MS) { addUsage(sums.five, usage); sums.five.turns += 1; }
      if (now - ts <= WEEK_MS) { addUsage(sums.week, usage); sums.week.turns += 1; }
    });
  }
  if (!sums.week.turns && !subscription && !tier) return null;
  const topModels = [...models.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([m, n]) => `${m} (${n})`).join(', ');
  return {
    name: 'Claude Code',
    description: [
      subscription ? `Abonelik: ${subscription}` : '',
      tier ? `Tier: ${tier}` : '',
      topModels ? `Modeller: ${topModels}` : '',
      'Resmi limit alınamadı; son kayıtların sınırlı yerel özeti gösteriliyor.',
    ].filter(Boolean).join(' • '),
    buckets: [
      { id: 'claude-local-5h', label: 'Son 5 saat', value: tokenSummary(sums.five), remainingFraction: 1, description: 'Claude Code JSONL kayıtlarındaki usage alanlarından hesaplandı.' },
      { id: 'claude-local-week', label: 'Son 7 gün', value: tokenSummary(sums.week), remainingFraction: 1, description: 'Cache read/create ve output token toplamı dahildir.' },
    ],
  };
}

// ── Antigravity (agy) usage via language_server gRPC-web ────────────────────
//
// The Antigravity IDE starts a language_server.exe child process that exposes a
// gRPC-web endpoint on a random local port. We call RetrieveUserQuotaSummary
// directly (no CDP required) and cache the result indefinitely so the card
// persists when the IDE is closed, marked with stale:true.

const AGY_USAGE_CACHE_FILE = path.join(os.tmpdir(), 'agtest-agy-usage.json');
const AGY_USAGE_TTL = 5 * 60 * 1000; // live re-fetch every 5 min
let agyCache = { at: 0, data: null };
try { const d = JSON.parse(fs.readFileSync(AGY_USAGE_CACHE_FILE, 'utf8')); if (d?.data) agyCache = d; } catch {}

const _sleep = ms => new Promise(r => setTimeout(r, ms));

async function fetchAgyUsage() {
  const now = Date.now();
  const cacheStale = { groups: agyCache.data?.groups || [], stale: true };

  // 1. Find language_server.exe process + CSRF token + listening ports via PowerShell.
  const psScript =
    '$p=Get-CimInstance Win32_Process -Filter "Name=\'language_server.exe\'";' +
    'if(!$p){exit(1)}' +
    '$csrf=if($p.CommandLine -match \'--csrf_token ([a-z0-9-]+)\'){$Matches[1]}else{""};' +
    '$ports=Get-NetTCPConnection -OwningProcess $p.ProcessId -State Listen|Select-Object -ExpandProperty LocalPort;' +
    'Write-Output "$($p.ProcessId)|$csrf|$($ports -join \',\')"';
  const ps = await spawnAsync('powershell.exe', ['-NoProfile', '-Command', psScript], {
    timeout: 10000,
  });
  if (ps.status !== 0 || !ps.stdout) return cacheStale;
  const parts = (ps.stdout || '').trim().split('|');
  if (parts.length < 3) return cacheStale;
  const [, csrfToken, portsStr] = parts;
  const ports = (portsStr || '').split(',').map(p => parseInt(p, 10)).filter(p => p);
  if (!csrfToken || !ports.length) return cacheStale;

  // 2. Build gRPC-web frame: 5-byte header (0,0,0,0,2) + JSON "{}" for empty request.
  const bodyPath = path.join(os.tmpdir(), 'agtest-grpc-body.bin');
  try {
    const header = Buffer.alloc(5);
    header.writeUInt32BE(2, 1);
    fs.writeFileSync(bodyPath, Buffer.concat([header, Buffer.from('{}')]));
  } catch { return cacheStale; }

  // 3. Try each port with https then http until one succeeds.
  let raw = '';
  for (const port of ports) {
    for (const scheme of ['https', 'http']) {
      const url = `${scheme}://127.0.0.1:${port}/exa.language_server_pb.LanguageServerService/RetrieveUserQuotaSummary`;
      const r = await spawnAsync('curl.exe', [
        '-s', '--max-time', '8', '--insecure',
        '-X', 'POST',
        '-H', 'content-type: application/grpc-web+json',
        '-H', 'x-grpc-web: 1',
        '-H', 'x-user-agent: CONNECT_ES_USER_AGENT',
        '-H', `x-codeium-csrf-token: ${csrfToken}`,
        '--data-binary', `@${bodyPath}`,
        url,
      ], { encoding: 'latin1', timeout: 12000 });
      if (r.status === 0 && r.stdout && r.stdout.length > 6) { raw = r.stdout; break; }
    }
    if (raw) break;
  }
  try { fs.unlinkSync(bodyPath); } catch {}

  if (!raw) return cacheStale;

  // 4. Parse the gRPC-web response.
  let jsonStr = '';
  let offset = 0;
  while (offset + 5 <= raw.length) {
    const flag = raw.charCodeAt(offset);
    const len = (raw.charCodeAt(offset + 1) << 24) | (raw.charCodeAt(offset + 2) << 16) |
                (raw.charCodeAt(offset + 3) << 8) | raw.charCodeAt(offset + 4);
    offset += 5;
    if (flag === 0 && len > 0 && offset + len <= raw.length) {
      jsonStr = raw.slice(offset, offset + len);
      break;
    }
    offset += len;
  }

  let parsed;
  try { parsed = JSON.parse(jsonStr); } catch { return cacheStale; }
  const resp = parsed.response || parsed;
  const groups = (resp.groups || []).map(g => ({
    name: g.displayName || '',
    description: g.description || '',
    buckets: (g.buckets || []).map(b => ({
      id: b.bucketId || '',
      label: b.displayName || '',
      window: b.window || '',
      description: b.description || '',
      remainingFraction: typeof b.remainingFraction === 'number' ? b.remainingFraction : 0,
      resetTime: b.resetTime || '',
    })),
  }));
  if (!groups.length) return cacheStale;
  const data = { groups, stale: false };
  agyCache = { at: now, data };
  try { fs.writeFileSync(AGY_USAGE_CACHE_FILE, JSON.stringify({ at: now, data })); } catch {}
  return data;
}

const USAGE_GROUPS_CACHE_FILE = path.join(os.tmpdir(), 'agtest-usage-groups.json');
const USAGE_GROUPS_TTL = 60 * 1000;
const USAGE_RESPONSE_BUDGET_MS = 3500;
// Bu yaştan eski önbellek "arka planda tazele, eskiyi göster" ile verilmez:
// ilk istek tazelemeyi BEKLER (bütçe kadar). Eskiden saatlerce kimse sormayınca
// ilk istek o saatlerin değerini alıyordu — 05.10.2026'da 5 saatlik pencere
// sıfırlandığı hâlde %19 dolu döndü (Mac aynı anda %7 diyordu). Seyrek soran
// istemci (ana ekran widget'ı, 15+ dk'da bir) her seferinde bir önceki
// sorusunun cevabını alıyordu. 5 dk = Claude/Codex limit önbelleğinin TTL'i;
// daha tazesini istemek uçlara fazladan yük getirmez.
const USAGE_GROUPS_WAIT_AFTER_MS = 5 * 60 * 1000;
// İstemcinin okuma zaman aşımı 15 sn; tazeleme sağlayıcıların en yavaşı kadar
// sürer (token yenilemesi + 5 sn'lik uçlar). Bütçe dolarsa eski önbellek döner,
// tazeleme arka planda biter ve bir sonraki istek onu alır.
const USAGE_STALE_WAIT_BUDGET_MS = 9000;
let usageGroupsCache = { at: 0, groups: [] };
let usageRefreshInFlight = null;
let usageRefreshIsForce = false;
try {
  const disk = JSON.parse(fs.readFileSync(USAGE_GROUPS_CACHE_FILE, 'utf8'));
  if (Array.isArray(disk?.groups)) usageGroupsCache = disk;
} catch {}

export function arrangeUsageGroups({
  codex,
  claude,
  agy,
  openRouter,
  nanoGpt,
  deepSeek,
  openCodeGo,
} = {}) {
  // İstenen sabit sıra: Claude → Codex → bakiye kartları.
  const groups = [];
  if (claude) groups.push({ ...claude, source: 'claude' });
  if (codex) groups.push({ ...codex, source: 'codex' });

  if (openRouter) groups.push(openRouter);
  if (nanoGpt) groups.push(nanoGpt);
  if (deepSeek) groups.push(deepSeek);
  if (openCodeGo) groups.push(openCodeGo);
  if (agy && agy.groups.length) {
    groups.push(...agy.groups.map(g => ({ ...g, source: 'antigravity', stale: agy.stale })));
  }
  return groups;
}

async function refreshExternalUsageGroups(force = false) {
  const [codex, claude, agy, openRouter, nanoGpt, deepSeek, openCodeGo] = await Promise.all([
    codexUsage(force),
    claudeUsage(force),
    fetchAgyUsage(),
    fetchOpenRouterUsage(),
    fetchNanoGptUsage(),
    fetchDeepSeekUsage(),
    fetchOpenCodeGoUsage(),
  ]);
  // Claude kartı en üstte; sonra diğerleri. `source` Android/Web tarafinda kart
  // siralama ve ayirt etme icin kanonik kimliktir, etiketten tahmin edilmemeli.
  const groups = arrangeUsageGroups({
    codex, claude, agy, openRouter, nanoGpt, deepSeek, openCodeGo,
  });
  usageGroupsCache = { at: Date.now(), groups };
  try { fs.writeFileSync(USAGE_GROUPS_CACHE_FILE, JSON.stringify(usageGroupsCache)); } catch {}
  return groups;
}

export async function getExternalUsageGroups(force = false) {
  // force = kullanıcı uygulamada "Yenile"ye bastı. Tüm TTL'leri (grup cache'i +
  // hesap bazlı 5 dk'lık util cache'i) atla ve sonucu BEKLE — aksi halde tuş
  // 5 dakika boyunca hiçbir şey değiştirmiyormuş gibi görünüyordu.
  if (force) {
    // Uçuşta force OLMAYAN bir tazeleme varsa ona takılma — o TTL'lere uyduğu
    // için aynı bayat sonucu döndürürdü. Kendi force turumuzu kur.
    if (!usageRefreshInFlight || !usageRefreshIsForce) {
      const p = refreshExternalUsageGroups(true)
        .catch(() => usageGroupsCache.groups)
        .finally(() => { if (usageRefreshInFlight === p) { usageRefreshInFlight = null; usageRefreshIsForce = false; } });
      usageRefreshInFlight = p;
      usageRefreshIsForce = true;
    }
    return usageRefreshInFlight;
  }
  const fresh = usageGroupsCache.groups.length && (Date.now() - usageGroupsCache.at) < USAGE_GROUPS_TTL;
  if (fresh) return usageGroupsCache.groups;
  if (!usageRefreshInFlight) {
    const p = refreshExternalUsageGroups()
      .catch(() => usageGroupsCache.groups)
      .finally(() => { if (usageRefreshInFlight === p) { usageRefreshInFlight = null; usageRefreshIsForce = false; } });
    usageRefreshInFlight = p;
    usageRefreshIsForce = false;
  }
  // Az bayat cache varsa anında göster ve arka planda tazele. Çok bayatsa
  // tazelemeyi sınırlı süre bekle. İlk çalıştırmada cache yoksa da Android'in
  // HTTP timeout'una düşmeden sınırlı sürede boş sonuç dön.
  const cached = usageGroupsCache.groups;
  if (cached.length && Date.now() - usageGroupsCache.at < USAGE_GROUPS_WAIT_AFTER_MS) return cached;
  const budget = cached.length ? USAGE_STALE_WAIT_BUDGET_MS : USAGE_RESPONSE_BUDGET_MS;
  return Promise.race([
    usageRefreshInFlight,
    new Promise(resolve => {
      const timer = setTimeout(() => resolve(cached), budget);
      timer.unref?.();
    }),
  ]);
}

// Döndürülen grupların ölçüldüğü an (ms). /usage yanıtına `updatedAt` olarak
// gider; widget bayatlığı buradan anlar.
export function usageGroupsUpdatedAt() {
  return usageGroupsCache.at || 0;
}

