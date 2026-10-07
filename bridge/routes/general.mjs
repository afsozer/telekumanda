import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { json, body, rawBody } from '../router.mjs';
import { getExternalUsageGroups, usageGroupsUpdatedAt } from '../usage.mjs';
import { readPdfAsMarkdown } from '../pdf-read.mjs';
import { checkLanguage } from '../pdf-lang.mjs';
import { getPosition as getPdfPosition, putPosition as putPdfPosition } from '../pdf-positions.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BRIDGE_DIR = path.dirname(__dirname);

// Minimal extension → MIME map for the /download endpoint. Falls back to
// application/octet-stream so binary files still stream correctly.
const MIME_TYPES = {
  '.txt': 'text/plain', '.md': 'text/markdown', '.markdown': 'text/markdown',
  '.json': 'application/json', '.xml': 'application/xml', '.csv': 'text/csv',
  '.html': 'text/html', '.htm': 'text/html', '.log': 'text/plain',
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.gz': 'application/gzip',
  '.tar': 'application/x-tar',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.webp': 'image/webp', '.svg': 'image/svg+xml', '.bmp': 'image/bmp',
  '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.mp4': 'video/mp4', '.webm': 'video/webm',
  '.apk': 'application/vnd.android.package-archive',
};
function mime(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  return MIME_TYPES[ext] || 'application/octet-stream';
}

// List immediate subdirectories (and optionally files) of the given root (or home) so the
// phone can pick a cwd when starting a new session, or browse files to download.
// When includeFiles is true, files are returned alongside dirs (with size), dirs first.
function realPathInsideRoot(targetPath, rootPath) {
  if (!rootPath) return { ok: true, path: path.resolve(targetPath) };
  try {
    const rootReal = fs.realpathSync(path.resolve(rootPath));
    const targetReal = fs.realpathSync(path.resolve(targetPath));
    const rootKey = process.platform === 'win32' ? rootReal.toLowerCase() : rootReal;
    const targetKey = process.platform === 'win32' ? targetReal.toLowerCase() : targetReal;
    const rel = path.relative(rootKey, targetKey);
    if (rel === '' || (!!rel && !rel.startsWith('..') && !path.isAbsolute(rel))) {
      return { ok: true, path: path.resolve(targetPath), realPath: targetReal };
    }
  } catch {}
  return { ok: false, error: 'yol Cowork root dışında veya geçersiz' };
}

// includeHidden opt-in: varsayılan davranış (nokta-dosyalar ve node_modules gizli)
// DEĞİŞMEDİ, çünkü /dirs'i proje seçici gibi başka ekranlar da kullanıyor ve
// oralarda .git/.claude görmek istemiyoruz. Yalnız gezgin bayrağı açıyor.
function listDirs(root, includeFiles, confinementRoot = '', includeHidden = false) {
  const requested = root && String(root).trim() ? String(root).trim() : os.homedir();
  const base = path.resolve(requested);
  const confined = realPathInsideRoot(base, confinementRoot);
  if (!confined.ok) return { ok: false, error: confined.error, base, parent: '', dirs: [] };
  const parentDir = path.dirname(base);
  const parentAllowed = parentDir !== base && realPathInsideRoot(parentDir, confinementRoot).ok;
  const parent = parentAllowed ? parentDir : '';
  try {
    if (!fs.existsSync(base) || !fs.statSync(base).isDirectory()) return { ok: false, error: 'not a directory', base, parent };
    const entries = fs.readdirSync(base, { withFileTypes: true })
      .filter(e => {
        if (!includeHidden && (e.name.startsWith('.') || e.name === 'node_modules')) return false;
        const supported = e.isDirectory() || (includeFiles && e.isFile());
        if (!supported) return false;
        // Junction/symlink üzerinden Cowork root dışına gezinmeyi de engelle.
        return realPathInsideRoot(path.join(base, e.name), confinementRoot).ok;
      })
      .map(e => {
        const full = path.join(base, e.name);
        let size = 0;
        let mtime = 0;
        let type = 'dir';
        let target = full;
        if (e.isFile()) {
          // DOSYA yolu gerçek hedefine çözülür: junction üzerinden açılan bir
          // kitap istemcide farklı bir yol dizesi olarak görünüyor ve indirme
          // önbelleği, kaldığı-yer kaydı, metin çıkarımı hepsi ikiye bölünüyordu
          // (13 MB'lık kitap telefonda iki kopya inmişti). KLASÖRLER çözülmez:
          // gezginin kırıntı yolu ve "üst klasör" gezinmesi kullanıcının girdiği
          // yolda kalmalı, yoksa `_kitaplar`a girer girmez oradan çıkılıyordu.
          try { target = fs.realpathSync(full); } catch {}
          try {
            const st = fs.statSync(target);
            size = st.size; mtime = st.mtimeMs;
          } catch {}
          type = 'file';
        }
        return { name: e.name, path: target, type, size, mtime };
      });
    // klasörler önce, sonra dosyalar; her ikisi alfabetik
    entries.sort((a, b) => (a.type === b.type) ? a.name.localeCompare(b.name) : (a.type === 'dir' ? -1 : 1));
    return { ok: true, base, parent, dirs: entries };
  } catch (e) { return { ok: false, error: String(e.message || e), base, parent }; }
}

// Sürücü kökleri. Gezgin C:\'den yukarı çıkamıyordu: Windows'ta bir sürücü
// kökünün üstü YOK, dolayısıyla D:'ye geçmenin hiçbir yolu kalmıyordu.
// Harf taraması ölçüldü: A–Z existsSync ~0 ms (var olmayan harf anında düşer).
// exists enjekte edilebilir ki karar saf kısmı testte gerçek diske bağlı olmasın.
function listDriveRoots(exists = p => fs.existsSync(p)) {
  if (process.platform !== 'win32') return [{ name: '/', path: '/', type: 'dir', size: 0, mtime: 0 }];
  const roots = [];
  for (let code = 'A'.charCodeAt(0); code <= 'Z'.charCodeAt(0); code++) {
    const letter = String.fromCharCode(code);
    const root = letter + ':\\';
    if (exists(root)) roots.push({ name: letter + ':', path: root, type: 'dir', size: 0, mtime: 0 });
  }
  return roots;
}

function scheduleSupervisorRestart() {
  // run-bridge.cmd Node süreci sonlandığında bridge'i yeniden başlatır.
  // Cevabın telefona ulaşması için çıkışı kısa bir süre geciktir.
  const timer = setTimeout(() => process.exit(1), 250);
  timer.unref();
}

// Recursive folder search — walks the filesystem up to maxDepth levels,
// matching directory names against query (case‑insensitive contains).
// Symlink/junction loops are prevented via a visited realpath set.
// Permission/read errors skip the offending branch silently.
function searchDir(root, query, maxDepth, limit) {
  if (maxDepth <= 0 || limit <= 0) return [];
  const resolved = path.resolve(String(root).trim());
  const q = String(query || '').trim().toLowerCase();
  if (q.length < 2) return [];

  const results = [];
  const visited = new Set();
  function walk(dir, depth) {
    if (depth > maxDepth || results.length >= limit) return;
    let real = dir;
    try { real = fs.realpathSync(dir); } catch {}
    const key = path.normalize(real).toLowerCase();
    if (visited.has(key)) return;
    visited.add(key);
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (!e.isDirectory()) continue;
      if (e.name.startsWith('.') || e.name === 'node_modules') continue;
      if (results.length >= limit) return;
      const full = path.join(dir, e.name);
      if (e.name.toLowerCase().includes(q)) {
        results.push({ name: e.name, path: full });
      }
      walk(full, depth + 1);
    }
  }
  walk(resolved, 1);
  return results;
}

export function register(router, {
  readWorkspaceFile,
  resolveWorkspacePath,
  getActiveState,
  SLASH,
  dynamicSlash = {},
  recordFileEvent = () => {},
  scheduleRestart = scheduleSupervisorRestart,
  getCoworkRoot = () => '',
  getLiteRoot = () => path.join(os.homedir(), 'AgentBridge-Lite'),
}) {
  router.get('/health', (req, res) => json(res, 200, { ok: true, protocolVersion: 2, protocolMinClient: 2 }));
  router.get('/active-state', (req, res) => json(res, 200, getActiveState()));
  router.get('/file', (req, res) => json(res, 200, readWorkspaceFile(new URL(req.url, 'http://x').searchParams.get('path') || '')));
  router.get('/download', (req, res) => {
    const resolved = resolveWorkspacePath(new URL(req.url, 'http://x').searchParams.get('path') || '');
    if (!resolved.ok) return json(res, 404, { error: resolved.error });
    try {
      const stat = fs.statSync(resolved.target);
      res.writeHead(200, {
        'Content-Type': mime(resolved.target),
        'Content-Length': stat.size,
        'Content-Disposition': `attachment; filename="${encodeURIComponent(resolved.name)}"`,
      });
      fs.createReadStream(resolved.target).pipe(res);
    } catch (e) {
      return json(res, 500, { error: String(e.message || e) });
    }
  });
  // Okuma modu: PDF'in metin katmanını markdown'a çevirir. Taranmış belgede
  // ok:false + reason döner — çağıran sayfa görünümünde kalır, burada OCR YOK.
  // İlk çağrı belge boyuna göre sürebilir (194 sayfalık ders kitabı ~11 sn);
  // sonuç boyut+mtime anahtarıyla önbelleğe alınır, ikinci çağrı anında döner.
  router.get('/pdf-read', async (req, res) => {
    const requested = new URL(req.url, 'http://x').searchParams.get('path') || '';
    const resolved = resolveWorkspacePath(requested);
    if (!resolved.ok) return json(res, 404, { ok: false, reason: 'dosya bulunamadı' });
    try {
      return json(res, 200, await readPdfAsMarkdown(resolved.target));
    } catch (e) {
      return json(res, 500, { ok: false, reason: String(e?.message || e).slice(0, 200) });
    }
  });
  // Okuma modu dil kontrolü. Gövde: { text }. Metni çağıran gönderir çünkü
  // kontrol edilen şey EKRANDA GÖRÜNEN metin olmalı; köprünün yeniden bölmesi
  // istemcideki sayfa bölmesiyle ayrışabilirdi.
  //
  // Yanıt yalnız BULGU taşır; düzeltilmiş metin ÜRETİLMEZ. Hukuki evrakta yazım
  // hatası belgenin parçasıdır, "düzeltilmiş kopya" belgeyi bozar.
  router.post('/pdf-lang-check', async (req, res) => {
    const buf = await rawBody(req);
    if (!buf.length) return json(res, 400, { ok: false, reason: 'boş istek' });
    let text = '';
    try {
      text = String(JSON.parse(buf.toString('utf8'))?.text || '');
    } catch {
      return json(res, 400, { ok: false, reason: 'gövde çözülemedi' });
    }
    try {
      return json(res, 200, await checkLanguage(text));
    } catch (e) {
      return json(res, 500, { ok: false, reason: String(e?.message || e).slice(0, 200) });
    }
  });
  // Kaldığı yer: telefon ve tablet aynı kitabı aynı satırdan devam alsın diye
  // kayıt köprüde. Cihaz yine kendi kopyasını tutuyor (çevrimdışı ve pager'ın
  // ilk kompozisyonu için); burası hakem. Yol anahtarı normalize edilir.
  router.get('/pdf-position', (req, res) => {
    const requested = new URL(req.url, 'http://x').searchParams.get('path') || '';
    if (!requested.trim()) return json(res, 400, { ok: false, reason: 'yol boş' });
    return json(res, 200, { ok: true, position: getPdfPosition(requested) });
  });
  router.post('/pdf-position', async (req, res) => {
    const buf = await rawBody(req);
    if (!buf.length) return json(res, 400, { ok: false, reason: 'boş istek' });
    let body;
    try {
      body = JSON.parse(buf.toString('utf8'));
    } catch {
      return json(res, 400, { ok: false, reason: 'gövde çözülemedi' });
    }
    const result = putPdfPosition(String(body?.path || ''), body);
    return json(res, result.ok ? 200 : 400, result);
  });
  router.get('/dirs', (req, res) => {
    const url = new URL(req.url, 'http://x');
    const scope = url.searchParams.get('scope') || '';
    const confinementRoot = scope === 'cowork' ? getCoworkRoot() : scope === 'lite' ? getLiteRoot() : '';
    if (scope === 'lite') {
      try { fs.mkdirSync(confinementRoot, { recursive: true }); } catch (e) {
        return json(res, 500, { ok: false, error: `Lite klasörü oluşturulamadı: ${String(e.message || e)}` });
      }
    }
    const result = listDirs(
      url.searchParams.get('root') || confinementRoot,
      url.searchParams.get('files') === 'true',
      confinementRoot,
      url.searchParams.get('hidden') === 'true',
    );
    json(res, result.ok ? 200 : 400, result);
  });
  // Cowork gezgini kök-kilitli: orada sürücü listesi ANLAMSIZ, dahası kilidin
  // etrafından dolaşmanın yolu olurdu. Bu yüzden scope=cowork'te boş döner.
  router.get('/dirs/roots', (req, res) => {
    const url = new URL(req.url, 'http://x');
    if (['cowork', 'lite'].includes(url.searchParams.get('scope'))) return json(res, 200, { ok: true, roots: [] });
    return json(res, 200, { ok: true, roots: listDriveRoots() });
  });
  // Genel PC gezgini/sohbetten telefonda düzenlenen MEVCUT dosyayı geri yaz.
  // resolveWorkspacePath ile aynı çözümleme sözleşmesini kullanır; create-on-save
  // yoktur. Gerçek hedef doğrulanır ve aynı klasörde tmp+rename ile atomik yazılır.
  router.post('/savefile', async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const requested = url.searchParams.get('path') || '';
    const expectedHash = url.searchParams.get('expectedHash') || '';
    const resolved = resolveWorkspacePath(requested);
    if (!resolved.ok) return json(res, 404, { ok: false, error: 'dosya bulunamadı: ' + requested });
    let targetReal; let stat;
    try {
      targetReal = fs.realpathSync(resolved.target);
      stat = fs.statSync(targetReal);
    } catch {
      return json(res, 404, { ok: false, error: 'dosya bulunamadı: ' + requested });
    }
    if (!stat.isFile()) return json(res, 400, { ok: false, error: 'hedef normal dosya değil' });
    const buf = await rawBody(req);
    const tmp = targetReal + `.agbtmp-${process.pid}-${crypto.randomUUID()}`;
    try {
      fs.writeFileSync(tmp, buf);
      // Native editör dosyayı açtıktan sonra PC tarafı değiştiyse sessizce ezme.
      // Harici editör senkronu expectedHash göndermez ve mevcut last-write-wins
      // davranışını korur.
      if (expectedHash) {
        const current = crypto.createHash('sha256').update(fs.readFileSync(targetReal)).digest('hex');
        if (current !== expectedHash) {
          fs.unlinkSync(tmp);
          return json(res, 409, { ok: false, conflict: true, error: 'dosya PC tarafında değişti', hash: current });
        }
      }
      fs.renameSync(tmp, targetReal);
      const now = fs.statSync(targetReal);
      const hash = crypto.createHash('sha256').update(buf).digest('hex');
      recordFileEvent('user_file_saved', targetReal, { size: now.size });
      return json(res, 200, { ok: true, path: targetReal, name: path.basename(targetReal), size: now.size, mtime: now.mtimeMs, hash });
    } catch (e) {
      try { fs.unlinkSync(tmp); } catch {}
      return json(res, 500, { ok: false, error: String(e.message || e) });
    }
  });

  router.get('/dirs/search', (req, res) => {
    const url = new URL(req.url, 'http://x');
    const root = url.searchParams.get('root') || '';
    const scope = url.searchParams.get('scope') || '';
    const confinementRoot = scope === 'cowork' ? getCoworkRoot() : scope === 'lite' ? getLiteRoot() : '';
    const q = url.searchParams.get('q') || '';
    const maxDepth = Math.max(1, Math.min(parseInt(url.searchParams.get('maxDepth') || '6') || 6, 10));
    const limit = Math.max(1, Math.min(parseInt(url.searchParams.get('limit') || '50') || 50, 100));
    if (!root || !realPathInsideRoot(root, confinementRoot).ok || !fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
      return json(res, 400, { ok: false, error: 'root must be an existing directory' });
    }
    if (q.length < 2) return json(res, 200, { ok: true, root, query: q, results: [] });
    const results = searchDir(root, q, maxDepth, limit);
    json(res, 200, { ok: true, root, query: q, results });
  });

  router.post('/bridge/restart', async (req, res) => {
    // Kim istedi? Sessiz code-1 ölümlerini ayırt etmek için kaynağı logla
    // (02.08.2026 tanı: exit(1) hiç iz bırakmıyordu).
    console.warn('[server] /bridge/restart istendi', {
      remote: req.socket?.remoteAddress || '?',
      ua: req.headers?.['user-agent'] || '?',
    });
    json(res, 202, { ok: true, restarting: true, method: 'supervisor' });
    scheduleRestart();
  });

  router.get('/slash', async (req, res) => {
    const backend = new URL(req.url, 'http://x').searchParams.get('backend') || 'antigravity';
    // Canlı katalog veren backend'lerde (omp) liste oradan gelir; sağlayıcı
    // düşerse statik tabloya geri düşülür ki menü hiç boş kalmasın.
    const dynamic = dynamicSlash[backend];
    if (dynamic) {
      try {
        const commands = await dynamic();
        if (commands?.length) return json(res, 200, { commands });
      } catch {}
    }
    json(res, 200, { commands: SLASH[backend] ?? [] });
  });

  router.post('/context/file', async (req, res) => {
    const buf = await rawBody(req);
    if (!buf.length) return json(res, 400, { error: 'empty body' });
    const tmpDir = path.join(BRIDGE_DIR, 'tmp');
    fs.mkdirSync(tmpDir, { recursive: true });
    // 24 saatten eski gecici ekler silinir; tmp sinirsiz buyumesin.
    const ATTACH_TTL_MS = 24 * 60 * 60 * 1000;
    try {
      for (const entry of fs.readdirSync(tmpDir)) {
        const p = path.join(tmpDir, entry);
        try {
          if (Date.now() - fs.statSync(p).mtimeMs > ATTACH_TTL_MS) fs.unlinkSync(p);
        } catch {}
      }
    } catch {}
    // /upload ile ayni sanitizasyon: yalniz Windows'ta gecersiz karakterler
    // degistirilir; Turkce/unicode adlar korunur.
    const safe = (String(req.headers['x-filename'] || new URL(req.url, 'http://x').searchParams.get('filename') || 'upload.bin')
      .replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').trim() || 'upload.bin');
    const fp = path.join(tmpDir, Date.now() + '_' + safe);
    fs.writeFileSync(fp, buf);
    json(res, 200, { ok: true, name: safe, path: fp });
  });

  router.post('/upload', async (req, res) => {
    const buf = await rawBody(req);
    if (!buf.length) return json(res, 400, { error: 'empty body' });
    const u = new URL(req.url, 'http://x');
    const dir = (req.headers['x-dir'] || u.searchParams.get('dir') || '').toString();
    // mkdir=true: hedefi kodda SABİT olan çağrılar için (ör. ekran görüntüsü
    // arşivi). Elle girilen yollarda kapalı kalmalı — yanlış yazılmış bir yol
    // sessizce klasör yaratmasın. Cowork kapsamı bundan muaf değil: önce
    // oluşturup sonra root kontrolü yapmak root dışına klasör açardı.
    const wantMkdir = u.searchParams.get('mkdir') === 'true';
    const isCowork = u.searchParams.get('scope') === 'cowork';
    if (wantMkdir && !isCowork && dir && !fs.existsSync(dir)) {
      try { fs.mkdirSync(dir, { recursive: true }); } catch { /* alttaki kontrol hata verir */ }
    }
    if (!dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
      return json(res, 400, { error: 'hedef klasör geçersiz' });
    }
    if (isCowork && !realPathInsideRoot(dir, getCoworkRoot()).ok) {
      return json(res, 400, { error: 'hedef Cowork root dışında' });
    }
    // /rename ile ayni sanitizasyon: yalniz Windows'ta gecersiz karakterler
    // degistirilir; Turkce/unicode adlar korunur ("dilekçe.docx" artik
    // "dilek_e.docx" olmaz).
    const safe = (String(req.headers['x-filename'] || u.searchParams.get('filename') || 'upload.bin')
      .replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').trim() || 'upload.bin');
    let target = path.join(dir, safe);
    if (fs.existsSync(target)) {
      const ext = path.extname(safe); const stem = safe.slice(0, safe.length - ext.length);
      let n = 1; while (fs.existsSync(path.join(dir, `${stem} (${n})${ext}`))) n++;
      target = path.join(dir, `${stem} (${n})${ext}`);
    }
    fs.writeFileSync(target, buf);
    recordFileEvent('user_file_uploaded', target, { size: buf.length });
    json(res, 200, { ok: true, name: path.basename(target), path: target });
  });

  router.post('/delete', async (req, res) => {
    const b = await body(req);
    if (!b.path || !fs.existsSync(b.path)) return json(res, 400, { error: 'yol geçersiz' });
    if (b.scope === 'cowork' && !realPathInsideRoot(b.path, getCoworkRoot()).ok) {
      return json(res, 400, { error: 'yol Cowork root dışında' });
    }
    fs.rmSync(b.path, { recursive: true, force: true });
    recordFileEvent('user_file_deleted', b.path);
    json(res, 200, { ok: true });
  });

  router.post('/rename', async (req, res) => {
    const b = await body(req);
    if (!b.path || !fs.existsSync(b.path)) return json(res, 400, { error: 'yol geçersiz' });
    if (b.scope === 'cowork' && !realPathInsideRoot(b.path, getCoworkRoot()).ok) {
      return json(res, 400, { error: 'yol Cowork root dışında' });
    }
    const safe = String(b.newName || '').replace(/[\\/:*?"<>|]/g, '_').trim();
    if (!safe) return json(res, 400, { error: 'ad geçersiz' });
    const dest = path.join(path.dirname(b.path), safe);
    if (fs.existsSync(dest)) return json(res, 409, { error: 'aynı adda dosya var' });
    fs.renameSync(b.path, dest);
    recordFileEvent('user_file_renamed', dest, { from: b.path });
    json(res, 200, { ok: true, path: dest });
  });

  router.post('/move', async (req, res) => {
    const b = await body(req);
    if (!b.path || !fs.existsSync(b.path)) return json(res, 400, { error: 'kaynak geçersiz' });
    if (!b.destDir || !fs.existsSync(b.destDir) || !fs.statSync(b.destDir).isDirectory())
      return json(res, 400, { error: 'hedef klasör geçersiz' });
    if (b.scope === 'cowork' && (
      !realPathInsideRoot(b.path, getCoworkRoot()).ok ||
      !realPathInsideRoot(b.destDir, getCoworkRoot()).ok
    )) return json(res, 400, { error: 'kaynak veya hedef Cowork root dışında' });
    const dest = path.join(b.destDir, path.basename(b.path));
    if (fs.existsSync(dest)) return json(res, 409, { error: 'hedefte aynı ad var' });
    try {
      fs.renameSync(b.path, dest);
    } catch {
      // cross-device fallback: copy + remove
      fs.cpSync(b.path, dest, { recursive: true });
      fs.rmSync(b.path, { recursive: true, force: true });
    }
    recordFileEvent('user_file_moved', dest, { from: b.path });
    json(res, 200, { ok: true, path: dest });
  });

  // Gezgindeki çoklu seçim "Kopyala + Yapıştır" için. /move ile aynı doğrulama
  // zinciri; tek farkı kaynağı silmemesi. Ad çakışırsa " (1)" ekleyip devam
  // eder — aynı klasöre yapıştırmak çoğu dosya yöneticisinde geçerli bir iş.
  router.post('/copy', async (req, res) => {
    const b = await body(req);
    if (!b.path || !fs.existsSync(b.path)) return json(res, 400, { error: 'kaynak geçersiz' });
    if (!b.destDir || !fs.existsSync(b.destDir) || !fs.statSync(b.destDir).isDirectory())
      return json(res, 400, { error: 'hedef klasör geçersiz' });
    if (b.scope === 'cowork' && (
      !realPathInsideRoot(b.path, getCoworkRoot()).ok ||
      !realPathInsideRoot(b.destDir, getCoworkRoot()).ok
    )) return json(res, 400, { error: 'kaynak veya hedef Cowork root dışında' });
    // Klasörü kendi altına kopyalamak sonsuz döngü: engelle.
    const normSrc = path.resolve(b.path);
    const normDest = path.resolve(b.destDir);
    if (normDest === normSrc || normDest.startsWith(normSrc + path.sep)) {
      return json(res, 400, { error: 'klasör kendi içine kopyalanamaz' });
    }
    const base = path.basename(b.path);
    const ext = path.extname(base);
    const stem = ext ? base.slice(0, -ext.length) : base;
    let dest = path.join(b.destDir, base);
    for (let i = 1; fs.existsSync(dest); i++) dest = path.join(b.destDir, `${stem} (${i})${ext}`);
    try {
      fs.cpSync(b.path, dest, { recursive: true });
    } catch (e) {
      return json(res, 500, { error: String(e && e.message || e) });
    }
    recordFileEvent('user_file_copied', dest, { from: b.path });
    json(res, 200, { ok: true, path: dest });
  });

  router.get('/usage', async (req, res) => {
    // ?force=1 → uygulamadaki "Yenile" tuşu; önbellekleri atlayıp canlı çek.
    const force = new URL(req.url, 'http://x').searchParams.get('force') === '1';
    const groups = await getExternalUsageGroups(force);
    // updatedAt: grupların ölçüldüğü an (ISO). Ana ekran widget'ı bayatlık
    // rozetini buradan çizer; uygulama ekranı yok sayar.
    const at = usageGroupsUpdatedAt();
    json(res, 200, { groups, note: '', updatedAt: at ? new Date(at).toISOString() : '' });
  });
}

export { listDirs, searchDir, listDriveRoots };
