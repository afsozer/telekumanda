// Okuma modu — PDF metin katmanını markdown'a çeviren köprü katmanı.
//
// İş bölümü: `pdf-read.py` yalnız PyMuPDF'in gördüğünü JSON'a çevirir (aptal
// çıkarıcı); temizlik, başlık tespiti ve markdown kurulumu BURADA yapılır.
// Sebebi test: köprünün test çerçevesi `node --test` ve bu mantığın birim
// testine ancak buradan girilebilir. Aşağıdaki saf fonksiyonlar (assemble*,
// strip*, join*) doğrudan test edilir; Python'a hiç ihtiyaç duymazlar.
//
// Taranmış belge burada İŞLENMEZ: metin katmanı yoksa `reason: 'taranmis'`
// döner ve çağıran sayfa görünümünde kalır. OCR bu modülün işi değil.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRIPT = path.join(__dirname, 'pdf-read.py');

// Önbellek: aynı belge her açılışta yeniden çıkarılmasın. 194 sayfalık bir
// ders kitabı ~12 sn sürüyor; ikinci açılışta 0 olmalı.
const CACHE_DIR = path.join(__dirname, 'pdf-read-tmp');
// Çıkarım tavanı. Bir ders kitabı ~12 sn; buradan sonrası takılmış demektir.
const EXTRACT_TIMEOUT_MS = 180000;
// Python'un stdout'u JSON'un tamamını taşır; 400 sayfalık belgede ~2 MB, ama
// tavanı geniş tutuyoruz ki maxBuffer patlaması sessiz bir hataya dönüşmesin.
const MAX_STDOUT = 512 * 1024 * 1024;

// Aday yorumlayıcılar. `py -3` Windows launcher'ı; PATH'te `python` yoksa
// çoğu kurulumda o var.
const PYTHON_CANDIDATES = [
  { file: 'python', prefix: [] },
  { file: 'python3', prefix: [] },
  { file: 'py', prefix: ['-3'] },
];

// ——— saf yardımcılar (test edilen çekirdek) ———————————————————————

/** Bir satırın dikey merkezi tablo kutusunun içinde mi? */
function lineInsideTable(line, table) {
  const cy = (line.y0 + line.y1) / 2;
  const cx = (line.x0 + line.x1) / 2;
  return cy >= table.y0 - 1 && cy <= table.y1 + 1 && cx >= table.x0 - 1 && cx <= table.x1 + 1;
}

/**
 * Her sayfada tekrarlayan üst/alt bilgi satırlarını bulur.
 *
 * Ders kitaplarında her sayfanın tepesinde yayınevi adı, altında sayfa
 * numarası var; okuma moduna akıtılırsa metni her sayfada bir kez kesiyor.
 * Ölçüt: sayfanın üst/alt %12'sinde duran ve sayfaların en az yarısında aynı
 * biçimde görünen satır. Rakamlar maskelenir ki "101"/"102" aynı sayılsın.
 */
export function findRepeatedLines(pages, { minRatio = 0.5, edge = 0.12 } = {}) {
  const counts = new Map();
  let considered = 0;
  for (const page of pages) {
    const h = page.h || 0;
    if (!h) continue;
    considered += 1;
    const seen = new Set();
    for (const line of page.lines) {
      const nearTop = line.y1 <= h * edge;
      const nearBottom = line.y0 >= h * (1 - edge);
      if (!nearTop && !nearBottom) continue;
      const key = normalizeRepeatKey(line.t);
      if (!key || seen.has(key)) continue;
      seen.add(key);
      counts.set(key, (counts.get(key) || 0) + 1);
    }
  }
  const threshold = Math.max(2, Math.ceil(considered * minRatio));
  const repeated = new Set();
  for (const [key, n] of counts) if (n >= threshold) repeated.add(key);
  return repeated;
}

/** Tekrar tespiti için satırı normalleştirir: rakamlar tek sembole iner. */
export function normalizeRepeatKey(text) {
  return String(text || '')
    .replace(/\d+/g, '#')
    .replace(/\s+/g, ' ')
    .trim()
    .toLocaleLowerCase('tr-TR');
}

/** Yalnız sayfa numarasından ibaret satır (üst/alt bilgi eşiğine takılmasa da atılır). */
function isPageNumberOnly(text) {
  return /^[\s|_–—-]*\d{1,4}[\s|_–—-]*$/.test(String(text || ''));
}

/**
 * Satır dizisini paragraflara toplar.
 *
 * İki iş yapar:
 * - **Tire birleştirme:** PDF'te satır sonu tiresi metnin parçası değil,
 *   dizginin artığı ("gös-" / "terilen" → "gösterilen"). Yalnız sonraki satır
 *   küçük harfle başlıyorsa birleştirilir; "Türk-" / "Alman" gibi gerçek
 *   birleşik sözcükler bozulmasın.
 * - **Satır birleştirme:** cümle ortasında kırılan satırlar boşlukla eklenir;
 *   noktalama ile biten satır paragrafı kapatır.
 */
export function joinLines(texts) {
  const out = [];
  let buf = '';
  const flush = () => { if (buf.trim()) out.push(buf.trim()); buf = ''; };
  for (const raw of texts) {
    const text = String(raw).replace(/\s+/g, ' ').trim();
    if (!text) { flush(); continue; }
    if (isBullet(text) || isHeadingLike(text)) { flush(); out.push(text); continue; }
    if (!buf) { buf = text; continue; }
    // Yumuşak tire (U+00AD) ve normal tire: sonraki satır küçükse birleştir.
    // Kalın satırlar `**…**` sarmalıyla geldiği için tire sınaması sarmalı
    // atlamak ZORUNDA; yoksa iki kalın satır arasındaki bölünmüş sözcük
    // ("dairesi-" / "ne") birleşmeden kalıyordu.
    const hyphen = /[-­]\*{0,2}$/.test(buf) && /^\*{0,2}[a-zçğıöşü]/.test(text);
    if (hyphen) buf = buf.replace(/[-­]\*{0,2}$/, '') + text.replace(/^\*{0,2}/, '');
    // Kalınlık DEĞİŞİMİ görsel bir kırılmadır: ders kitaplarında düz madde
    // metninin ardından gelen kalın bilgi kutusu ayrı bir birimdir. Birleşince
    // "…hazine yardımı getirilmiştir **Diyanet İşleri Başkanlığı 1924…**" gibi
    // iki cümle tek paragrafa yapışıyordu (dil kontrolü bunu noktalama hatası
    // olarak bildirince yakalandı). Tire birleştirmesi bundan ÖNCE gelir:
    // sözcüğün yalnız bir parçası kalın olabiliyor.
    else if (isBoldLine(buf) !== isBoldLine(text)) { flush(); buf = text; }
    else if (/[.:!?;]\*{0,2}$/.test(buf)) { flush(); buf = text; }
    else buf = buf + ' ' + text;
  }
  flush();
  return out;
}

/**
 * Birleşen kalın satırların araya sıkışan işaretlerini toparlar:
 * `**a** **b**` → `**a b**`.
 *
 * Bu MUTLAKA paragraf düzeyinde yapılır. Daha önce bütün markdown kurulduktan
 * sonra tek seferde uygulanıyordu; o zaman iki ayrı SAYFANIN kalın satırlarını
 * da birleştiriyor, dizgiyi kısaltarak daha önce hesaplanmış `pageStarts`
 * konumlarını kaydırıyordu (sayfa 31 metnin ortasından başlıyordu).
 */
function mergeBold(text) {
  return text.replace(/\*\*\s+\*\*/g, ' ');
}

// Kalın satırlar `**…**` sarmalıyla gelir; madde/başlık sınaması sarmalı
// görmezse kalın madde işaretleri paragrafa yapışıyordu.
function bare(text) {
  return String(text || '').replace(/^\*\*/, '').replace(/\*\*$/, '').trim();
}

/** Satırın tamamı kalın mı? (Çıkarıcı kalın satırları `**…**` sarmalıyla verir.) */
function isBoldLine(text) {
  const t = String(text || '').trim();
  return t.startsWith('**') && t.endsWith('**') && t.length > 4;
}

// Madde imi simgeleri. Tire/nokta dışında ok ve kare de kullanılıyor: hakimlik
// hazırlık kitaplarının standart imi "→". Simge tanınmayınca madde metni bir
// önceki paragrafa akıyordu.
const BULLET_GLYPHS = '-–—•*→➔➜▪■□‣◦»›';

// Büyük harfli tek karakter + parantez de madde sayılır: soru bankalarında
// şıklar ("A) …", "B) …") böyle geliyor ve sayılmazsa hepsi tek paragrafa
// yapışıp okunmaz hale geliyordu.
function isBullet(text) {
  const t = bare(text);
  // Roma rakamlı öncüller ("I- …", "III- …") soru bankalarının standart
  // biçimi; madde sayılmazsa hepsi tek paragrafa akıyor.
  if (/^[IVX]{1,4}[-–—.)]\s/.test(t)) return true;
  return new RegExp(`^([${BULLET_GLYPHS}]|\\(?[0-9]{1,3}[.)]|\\(?[A-Za-zÇĞİÖŞÜçğıöşü][.)])\\s+`).test(t);
}

/** Kısa, tamamı büyük harfli satırlar başlık gibi davranır (paragrafa yapışmaz). */
function isHeadingLike(input) {
  const text = bare(input);
  if (text.length > 60) return false;
  const letters = text.replace(/[^\p{L}]/gu, '');
  if (letters.length < 3) return false;
  return letters === letters.toLocaleUpperCase('tr-TR');
}

/** Tablo satırlarını markdown boru tablosuna çevirir. */
export function tableToMarkdown(rows) {
  const clean = rows.map(row => row.map(cell => String(cell ?? '')
    .replace(/\r?\n/g, '<br>')
    .replace(/\|/g, '\\|')
    .replace(/\s+/g, ' ')
    .trim()));
  const width = clean.reduce((max, row) => Math.max(max, row.length), 0);
  if (!width) return '';
  const pad = row => { const r = row.slice(); while (r.length < width) r.push(''); return r; };
  const [head, ...body] = clean;
  const lines = [
    '| ' + pad(head).join(' | ') + ' |',
    '| ' + Array(width).fill('---').join(' | ') + ' |',
    ...body.map(row => '| ' + pad(row).join(' | ') + ' |'),
  ];
  return lines.join('\n');
}

/** Gövde yazı boyutunu bulur: en çok satırın kullandığı boyut. */
export function bodyFontSize(pages) {
  const tally = new Map();
  for (const page of pages) {
    for (const line of page.lines) {
      const size = Number(line.s) || 0;
      if (!size) continue;
      tally.set(size, (tally.get(size) || 0) + line.t.length);
    }
  }
  let best = 0; let bestScore = -1;
  for (const [size, score] of tally) if (score > bestScore) { best = size; bestScore = score; }
  return best;
}

/**
 * Çıkarıcının JSON'unu markdown'a çevirir.
 *
 * `pageStarts`: her sayfanın markdown içindeki karakter konumu. Metin akışa
 * girdiği için sayfa sınırı görsel olarak kaybolur; hukuki belgede "3.
 * sayfada geçiyor" demek gerektiğinden eşleme veri olarak korunur.
 */
export function assembleMarkdown(doc) {
  const pages = Array.isArray(doc?.pages) ? doc.pages : [];
  const repeated = findRepeatedLines(pages);
  const body = bodyFontSize(pages);
  const chunks = [];
  const pageStarts = [];
  let length = 0;
  const push = (text) => { chunks.push(text); length += text.length; };

  for (const page of pages) {
    pageStarts.push(length);
    const tables = Array.isArray(page.tables) ? page.tables : [];
    const emitted = new Set();
    const parts = [];
    let pending = [];
    const flushText = () => {
      if (!pending.length) return;
      for (const para of joinLines(pending)) parts.push(decorate(mergeBold(para), body));
      pending = [];
    };

    for (const line of page.lines) {
      const idx = tables.findIndex(t => lineInsideTable(line, t));
      if (idx >= 0) {
        // Tablo, İÇİNDEKİ ilk satırın geldiği yerde basılır; böylece okuma
        // sırası korunur (y'ye göre yeniden sıralamak iki sütunlu sayfalarda
        // metni birbirine karıştırırdı).
        flushText();
        if (!emitted.has(idx)) {
          emitted.add(idx);
          const md = tableToMarkdown(tables[idx].rows);
          if (md) parts.push(md);
        }
        continue;
      }
      const text = String(line.t || '').trim();
      if (!text) continue;
      if (repeated.has(normalizeRepeatKey(text)) || isPageNumberOnly(text)) continue;
      pending.push(line.b ? `**${text}**` : text);
    }
    flushText();
    // Hiç metin satırı içermeyen tablolar (boş resmî form) sayfa sonuna düşer.
    tables.forEach((t, i) => {
      if (emitted.has(i)) return;
      const md = tableToMarkdown(t.rows);
      if (md) parts.push(md);
    });
    if (parts.length) push(parts.join('\n\n') + '\n\n');
  }

  // trimEnd yalnız SONDAN kırpar; baştaki hiçbir konum kaymaz, `pageStarts`
  // geçerli kalır. Buraya dizgiyi kısaltan başka bir işlem EKLENMEMELİ.
  return { markdown: chunks.join('').trimEnd(), pageStarts };
}

/** Paragrafı markdown olarak süsler: başlık seviyesi ve madde işareti. */
function decorate(text, body) {
  if (isBullet(text)) {
    const bold = isBoldLine(text);
    const inner = bold ? bare(text) : text;
    // Yinelenen im ("→ → …") tek ime iner: çıkarıcı bazı kitaplarda simgeyi iki
    // ayrı metin parçası olarak veriyor ve ikisi de satıra giriyor.
    const glyphRun = new RegExp(`^(?:[${BULLET_GLYPHS}]\\s*)+`);
    // Numaralı/harfli madde ("28. …", "A) …") kendi imini TAŞIYOR; `- ` eklemek
    // numarayı ikinci bir ime çevirirdi.
    if (!glyphRun.test(inner)) return text;
    const stripped = inner.replace(glyphRun, '');
    return '- ' + (bold ? `**${stripped}**` : stripped);
  }
  if (body && isHeadingLike(text)) return `### ${bare(text)}`;
  return text;
}

// ——— Python köprüsü ————————————————————————————————————————

let cachedPython = null;

/** Çalışan bir yorumlayıcı bulur; `pymupdf yok` diyeni aday saymaz. */
async function runExtractor(file) {
  const candidates = cachedPython ? [cachedPython, ...PYTHON_CANDIDATES] : PYTHON_CANDIDATES;
  let lastError = '';
  for (const candidate of candidates) {
    const result = await runOnce(candidate, file).catch(e => ({ error: String(e?.message || e) }));
    if (result.error) { lastError = result.error; continue; }
    if (result.data?.reason === 'pymupdf yok') { lastError = 'pymupdf yok'; continue; }
    cachedPython = candidate;
    return result.data;
  }
  return { ok: false, reason: lastError || 'python bulunamadı' };
}

function runOnce(candidate, file) {
  return new Promise((resolve) => {
    execFile(
      candidate.file,
      [...candidate.prefix, SCRIPT, file],
      { maxBuffer: MAX_STDOUT, timeout: EXTRACT_TIMEOUT_MS, encoding: 'utf8', windowsHide: true },
      (err, stdout) => {
        if (err && !stdout) return resolve({ error: String(err.message || err) });
        const text = String(stdout || '').trim();
        if (!text) return resolve({ error: 'boş yanıt' });
        try {
          resolve({ data: JSON.parse(text) });
        } catch (e) {
          resolve({ error: 'JSON çözülemedi: ' + String(e?.message || e).slice(0, 120) });
        }
      },
    );
  });
}

/** Önbellek anahtarı: yol + boyut + değişiklik zamanı. İçerik değişirse düşer. */
function cacheKey(file, stat) {
  const raw = `${path.resolve(file).toLowerCase()}|${stat.size}|${Math.floor(stat.mtimeMs)}`;
  let hash = 0;
  for (let i = 0; i < raw.length; i += 1) hash = (Math.imul(hash, 31) + raw.charCodeAt(i)) | 0;
  return `pdfread-${(hash >>> 0).toString(36)}-${stat.size}.json`;
}

/**
 * Dosyayı okuma moduna hazırlar.
 *
 * Dönen sözleşme her durumda aynı: `{ ok, reason?, markdown?, ... }`. Asla
 * throw etmez — çağıran rota hatayı kullanıcıya cümle olarak gösterir.
 */
export async function readPdfAsMarkdown(file, { cacheDir = CACHE_DIR } = {}) {
  // Junction/symlink çözülür: aynı kitap hem gerçek yolundan hem kısayoldan
  // açılınca önbellek anahtarı ayrışıyor ve belge İKİ KEZ çıkarılıyordu
  // (13 MB'lık kitapta ölçüldü: iki ayrı pdfread-*.json). Kimlik dosyanın
  // kendisi olmalı, ona giden yol değil.
  const target = (() => { try { return fs.realpathSync(file); } catch { return file; } })();
  let stat;
  try {
    stat = fs.statSync(target);
  } catch {
    return { ok: false, reason: 'dosya bulunamadı' };
  }
  if (!stat.isFile()) return { ok: false, reason: 'dosya bulunamadı' };

  const cacheFile = path.join(cacheDir, cacheKey(target, stat));
  try {
    if (fs.existsSync(cacheFile)) return JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
  } catch {
    // Bozuk önbellek dosyası okumayı engellemesin; yeniden üretilir.
  }

  const doc = await runExtractor(target);
  let payload;
  if (!doc?.ok) {
    payload = { ok: false, reason: doc?.reason || 'çıkarılamadı' };
  } else {
    const { markdown, pageStarts } = assembleMarkdown(doc);
    payload = {
      ok: true,
      markdown,
      pageStarts,
      pageCount: doc.pageCount,
      truncated: !!doc.truncated,
      tablesTruncated: !!doc.tablesTruncated,
      tablesUpToPage: doc.tablesUpToPage ?? null,
    };
  }
  // Taranmış belgeyi de önbelleğe alırız: her açılışta yeniden yoklamanın
  // anlamı yok, dosya değişirse anahtar zaten düşer.
  try {
    fs.mkdirSync(cacheDir, { recursive: true });
    const tmp = cacheFile + `.tmp-${process.pid}`;
    fs.writeFileSync(tmp, JSON.stringify(payload));
    fs.renameSync(tmp, cacheFile);
  } catch {
    // Önbelleğe yazamamak özelliği durdurmaz.
  }
  return payload;
}

export const _internals = { CACHE_DIR, lineInsideTable, isBullet, isHeadingLike, decorate };
