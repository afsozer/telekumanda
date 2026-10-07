# Dosya İndirme Özelliği — Implementasyon Planı

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AgentBridge Android uygulamasına, bilgisayardaki dosyaları bağımsız bir tarayıcıda gezip önizleyerek telefona (Downloads) indirme özelliği eklemek.

**Architecture:** Bridge tarafında path çözme tek yardımcıya (`resolveWorkspacePath`) çıkarılır; `/dirs?files=true` klasör+dosya listeler, `/download?path=...` raw stream döner. Android tarafında yeni `FileBrowserScreen` (klasör/dosya gezici), mevcut `FileViewerSheet`'e "İndir" butonu, yeni `DownloadRepo` (OkHttp stream → MediaStore Downloads + SharedPreferences geçmişi), ve `DownloadsListScreen` eklenir. Navigasyon mevcut flag-tabanlı `AppScaffold` `when` bloğuna iki yeni flag ile bağlanır.

**Tech Stack:** Node.js (bridge, `node:test`), Kotlin + Jetpack Compose + Material3 + OkHttp (Android), MediaStore Downloads API (API 29+) / `WRITE_EXTERNAL_STORAGE` (API 26–28).

**Spec:** `docs/superpowers/specs/2026-06-25-file-download-design.md`

---

## Dosya Yapısı

**Bridge:**
- Modify `bridge/server.mjs` — `resolveWorkspacePath` çıkar, export et; `readWorkspaceFile` onu çağırır; `registerGeneral`'a `resolveWorkspacePath` aktar.
- Modify `bridge/routes/general.mjs` — `listDirs`'e `includeFiles`, `/download` route, MIME yardımcısı.

**Android:**
- Modify `android/app/src/main/java/com/agent/bridge/BridgeClient.kt` — `DirEntry`/`workerDirs` genişlet, `downloadFile` ekle.
- Create `android/app/src/main/java/com/agent/bridge/DownloadRepo.kt` — stream → MediaStore + geçmiş.
- Create `android/app/src/main/java/com/agent/bridge/FileBrowserScreen.kt` — dosya tarayıcı ekranı.
- Create `android/app/src/main/java/com/agent/bridge/DownloadsListScreen.kt` — indirilenler geçmişi.
- Modify `android/app/src/main/java/com/agent/bridge/FileViewerSheet.kt` — "İndir" butonu.
- Modify `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt` — indirme/browser/downloads state + fonksiyonlar.
- Modify `android/app/src/main/java/com/agent/bridge/ChatScreen.kt` — `AppScaffold` `when` bloğuna iki ekran.
- Modify `android/app/src/main/java/com/agent/bridge/LandingScreen.kt` — "Dosyalar"/"İndirilenler" kartları.
- Modify `android/app/src/main/AndroidManifest.xml` — `WRITE_EXTERNAL_STORAGE` (maxSdk 28).
- Modify `android/app/src/main/res/xml/file_paths.xml` — Downloads alt klasörü (API 26–28).

**Tests:**
- Create `bridge/test/resolveWorkspacePath.test.mjs`
- Create `bridge/test/listDirs.test.mjs`
- Create `bridge/test/downloadRoute.test.mjs`

---

## Task 1: `resolveWorkspacePath` çıkarımı (bridge)

`readWorkspaceFile` içindeki path çözme mantığını ayrı bir yardımcıya çıkar. Davranış korunur; sadece kod organize edilir.

**Files:**
- Modify: `bridge/server.mjs:101-134` (readWorkspaceFile gövdesi)
- Modify: `bridge/server.mjs:314` (export satırı)
- Test: `bridge/test/resolveWorkspacePath.test.mjs` (yeni)

- [ ] **Step 1: `resolveWorkspacePath` yardımcısını yaz**

`bridge/server.mjs`'te `readWorkspaceFile`'dan (satır ~101) ÖNCE şu yardımcıyı ekle. Mevcut `findByBasename` ve `FILE_ALIASES`'i kullanır:

```js
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
```

- [ ] **Step 2: `readWorkspaceFile`'ı yardımcıyı çağıracak şekilde sadeleştir**

`readWorkspaceFile` gövdesini (satır ~101-134) bununla değiştir:

```js
function readWorkspaceFile(p) {
  try {
    const resolved = resolveWorkspacePath(p);
    if (!resolved.ok) return resolved.error === 'path required' ? { ok: false, error: 'path required' } : { ok: false, error: resolved.error, name: resolved.name || '' };
    const stat = fs.statSync(resolved.target);
    const buf = fs.readFileSync(resolved.target);
    const truncated = buf.length > MAX_FILE;
    return { ok: true, name: resolved.name, path: resolved.target, size: stat.size, truncated, content: buf.slice(0, MAX_FILE).toString('utf-8') };
  } catch (e) { return { ok: false, error: String(e.message || e) }; }
}
```

- [ ] **Step 3: `resolveWorkspacePath`'i export et**

`bridge/server.mjs:314` export satırını güncelle:
```js
export { auth, readWorkspaceFile, resolveWorkspacePath, getActiveState };
```

- [ ] **Step 4: `registerGeneral`'a `resolveWorkspacePath` aktar**

`bridge/server.mjs:155`:
```js
registerGeneral(router, { readWorkspaceFile, resolveWorkspacePath, getActiveState, SLASH });
```

- [ ] **Step 5: Test yaz — `bridge/test/resolveWorkspacePath.test.mjs`**

```js
import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { resolveWorkspacePath, readWorkspaceFile } from '../server.mjs';

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rwp-'));
const testFile = path.join(tmpDir, 'doc.txt');
fs.writeFileSync(testFile, 'content\n', 'utf-8');

after(() => {
  try { fs.unlinkSync(testFile); } catch {}
  try { fs.rmdirSync(tmpDir); } catch {}
});

describe('resolveWorkspacePath()', () => {
  it('resolves an absolute path', () => {
    const r = resolveWorkspacePath(testFile);
    assert.equal(r.ok, true);
    assert.equal(r.target, testFile);
    assert.equal(r.name, 'doc.txt');
  });

  it('strips agfile:// prefix', () => {
    const r = resolveWorkspacePath('agfile:///' + testFile.replace(/\\/g, '/'));
    assert.equal(r.ok, true);
    assert.equal(r.name, 'doc.txt');
  });

  it('returns ok:false for empty path', () => {
    const r = resolveWorkspacePath('');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'path required');
  });

  it('returns ok:false with name for non-existent path', () => {
    const r = resolveWorkspacePath('C:\\nonexistent\\nope.txt');
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not found');
    assert.equal(r.name, 'nope.txt');
  });
});

describe('readWorkspaceFile (uses resolveWorkspacePath)', () => {
  it('still returns content for absolute path', () => {
    const r = readWorkspaceFile(testFile);
    assert.equal(r.ok, true);
    assert.equal(r.content, 'content\n');
    assert.equal(r.name, 'doc.txt');
  });
});
```

- [ ] **Step 6: Testleri çalıştır**

```bash
cd bridge && node --test test/resolveWorkspacePath.test.mjs
```
Beklenen: tüm testler PASS. Mevcut `readWorkspaceFile.test.mjs` hâlâ geçmeli (davranış korundu):
```bash
node --test test/readWorkspaceFile.test.mjs
```

- [ ] **Step 7: Commit**

```bash
git add bridge/server.mjs bridge/test/resolveWorkspacePath.test.mjs
git commit -m "resolveWorkspacePath yardımcısını çıkar; readWorkspaceFile onu çağırır"
```

---

## Task 2: `/dirs?files=true` desteği (bridge)

`listDirs` artık opsiyonel olarak dosyaları da (ad + yol + boyut + tür) listeler. `files` parametresi yoksa mevcut davranış (sadece klasör) korunur.

**Files:**
- Modify: `bridge/routes/general.mjs:13-22` (listDirs), `:28` (/dirs route)
- Test: `bridge/test/listDirs.test.mjs` (yeni)

- [ ] **Step 1: `listDirs`'i genişlet**

`bridge/routes/general.mjs` içindeki `listDirs` fonksiyonunu (satır ~13-22) bununla değiştir. `path` zaten import edilmiş:

```js
function listDirs(root, includeFiles) {
  const base = root && String(root).trim() ? String(root).trim() : os.homedir();
  try {
    if (!fs.existsSync(base) || !fs.statSync(base).isDirectory()) return { ok: false, error: 'not a directory', base };
    const entries = fs.readdirSync(base, { withFileTypes: true })
      .filter(e => {
        if (e.name.startsWith('.') || e.name === 'node_modules') return false;
        if (e.isDirectory()) return true;
        return includeFiles && e.isFile();
      })
      .map(e => {
        let size = 0;
        let type = 'dir';
        if (e.isFile()) {
          try { size = fs.statSync(path.join(base, e.name)).size; } catch {}
          type = 'file';
        }
        return { name: e.name, path: path.join(base, e.name), type, size };
      });
    // klasörler önce, sonra dosyalar; her ikisi alfabetik
    entries.sort((a, b) => (a.type === b.type) ? a.name.localeCompare(b.name) : (a.type === 'dir' ? -1 : 1));
    return { ok: true, base, dirs: entries };
  } catch (e) { return { ok: false, error: String(e.message || e), base }; }
}
```

- [ ] **Step 2: `/dirs` route'una `files` parametresi geçir**

`bridge/routes/general.mjs:28`:
```js
router.get('/dirs', (req, res) => json(res, 200, listDirs(new URL(req.url, 'http://x').searchParams.get('root') || '', new URL(req.url, 'http://x').searchParams.get('files') === 'true')));
```

- [ ] **Step 3: Test yaz — `bridge/test/listDirs.test.mjs`**

`listDirs` export edilmediği için `register` üzerinden HTTP testi yapılması gerekir, ama bu ağır. Bunun yerine `listDirs`'i fonksiyon olarak test etmek üzere export et — `general.mjs`'in sonuna ekle:

```js
export { listDirs };
```

Test:
```js
import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { listDirs } from '../routes/general.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dirs-'));
const subDir = path.join(tmp, 'zfolder');
const aFile = path.join(tmp, 'a.txt');
const bFile = path.join(tmp, 'b.png');

before(() => {
  fs.mkdirSync(subDir);
  fs.writeFileSync(aFile, 'hello');
  fs.writeFileSync(bFile, 'pngdata');
});
after(() => {
  for (const f of [aFile, bFile]) try { fs.unlinkSync(f); } catch {}
  try { fs.rmdirSync(subDir); } catch {}
  try { fs.rmdirSync(tmp); } catch {}
});

describe('listDirs()', () => {
  it('lists only dirs by default (no files flag)', () => {
    const r = listDirs(tmp, false);
    assert.equal(r.ok, true);
    assert.equal(r.base, tmp);
    assert.equal(r.dirs.length, 1);
    assert.equal(r.dirs[0].name, 'zfolder');
    assert.equal(r.dirs[0].type, 'dir');
    assert.equal(r.dirs[0].size, 0);
  });

  it('lists dirs and files when includeFiles=true, dirs first', () => {
    const r = listDirs(tmp, true);
    assert.equal(r.ok, true);
    assert.equal(r.dirs.length, 3);
    assert.equal(r.dirs[0].name, 'zfolder');
    assert.equal(r.dirs[0].type, 'dir');
    // dosyalar alfabetik, klasörden sonra
    assert.equal(r.dirs[1].name, 'a.txt');
    assert.equal(r.dirs[1].type, 'file');
    assert.equal(r.dirs[1].size, 5);
    assert.equal(r.dirs[2].name, 'b.png');
    assert.equal(r.dirs[2].type, 'file');
    assert.equal(r.dirs[2].size, 7);
  });

  it('returns ok:false for non-directory', () => {
    const r = listDirs(aFile, true);
    assert.equal(r.ok, false);
    assert.equal(r.error, 'not a directory');
  });
});
```

- [ ] **Step 4: Testleri çalıştır**

```bash
cd bridge && node --test test/listDirs.test.mjs
```
Beklenen: tüm testler PASS.

- [ ] **Step 5: Commit**

```bash
git add bridge/routes/general.mjs bridge/test/listDirs.test.mjs
git commit -m "/dirs?files=true ile dosya listeleme desteği"
```

---

## Task 3: `/download` route + MIME (bridge)

Yeni raw stream indirme endpoint'i. MIME haritası uzantıdan türetilir.

**Files:**
- Modify: `bridge/routes/general.mjs` (register imzası + /download route)
- Modify: `bridge/server.mjs:155` (resolveWorkspacePath zaten Task 1'de eklendi)
- Test: `bridge/test/downloadRoute.test.mjs` (yeni)

- [ ] **Step 1: MIME yardımcısı ekle**

`bridge/routes/general.mjs`'in üstüne (import'lardan sonra) küçük MIME haritası ekle:

```js
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
```

- [ ] **Step 2: `register` imzasını güncelle**

`bridge/routes/general.mjs:24`:
```js
export function register(router, { readWorkspaceFile, resolveWorkspacePath, getActiveState, SLASH }) {
```

- [ ] **Step 3: `/download` route'unu ekle**

`/file` route'undan (satır ~27) hemen sonra ekle:

```js
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
```

- [ ] **Step 4: Test yaz — `bridge/test/downloadRoute.test.mjs`**

HTTP sunucusunu açıp `/download`'ı test et. Mevcut `auth.test.mjs` / `router.test.mjs` kalıbını izle:

```js
import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { createRouter, json } from '../router.mjs';
import { register } from '../routes/general.mjs';
import { resolveWorkspacePath } from '../server.mjs';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dl-'));
const txt = path.join(tmp, 'note.txt');
const bin = path.join(tmp, 'data.bin');
const png = path.join(tmp, 'img.png');
before(() => {
  fs.writeFileSync(txt, 'hello download');
  fs.writeFileSync(bin, Buffer.from([0, 1, 2, 3, 255]));
  fs.writeFileSync(png, 'not really png');
});
after(() => {
  for (const f of [txt, bin, png]) try { fs.unlinkSync(f); } catch {}
  try { fs.rmdirSync(tmp); } catch {}
});

function startServer() {
  const router = createRouter();
  register(router, { readWorkspaceFile: () => ({}), resolveWorkspacePath, getActiveState: () => ({}), SLASH: {} });
  const server = http.createServer((req, res) => router.handle(req, res));
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

function get(port, urlPath) {
  return new Promise((resolve, reject) => {
    http.get({ hostname: '127.0.0.1', port, path: urlPath }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    }).on('error', reject);
  });
}

describe('GET /download', () => {
  let server, port;
  before(async () => { server = await startServer(); port = server.address().port; });
  after(() => server.close());

  it('streams file bytes with correct MIME and headers', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent(txt));
    assert.equal(r.status, 200);
    assert.equal(r.headers['content-type'], 'text/plain');
    assert.equal(r.headers['content-length'], String(Buffer.byteLength('hello download')));
    assert.equal(r.body.toString('utf-8'), 'hello download');
    assert.ok(r.headers['content-disposition'].includes('note.txt'));
  });

  it('returns application/octet-stream for unknown ext', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent(bin));
    assert.equal(r.status, 200);
    assert.equal(r.headers['content-type'], 'application/octet-stream');
    assert.deepEqual(Array.from(r.body), [0, 1, 2, 3, 255]);
  });

  it('returns image/png for .png', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent(png));
    assert.equal(r.headers['content-type'], 'image/png');
  });

  it('returns 404 JSON for missing path', async () => {
    const r = await get(port, '/download?path=' + encodeURIComponent('C:\\nope\\missing.txt'));
    assert.equal(r.status, 404);
    const parsed = JSON.parse(r.body.toString('utf-8'));
    assert.equal(parsed.error, 'not found');
  });
});
```

- [ ] **Step 5: `router.handle` imzasını doğrula**

`bridge/router.mjs`'i aç ve HTTP sunucusunun çağırdığı dispatch metodunun adını kontrol et (`handle` mı yoksa başka mı). Test buna göre düzeltilir. Testi çalıştır:

```bash
cd bridge && node --test test/downloadRoute.test.mjs
```
Beklenen: tüm testler PASS.

- [ ] **Step 6: Commit**

```bash
git add bridge/routes/general.mjs bridge/test/downloadRoute.test.mjs
git commit -m "/download endpoint: raw stream + MIME + Content-Disposition"
```

---

## Task 4: `BridgeClient` — `workerDirs` genişlet + `downloadFile`

Android tarafında bridge endpoint'lerine erişim. `DirEntry`'ye `type`/`size` eklenir, `workerDirs` `includeFiles` alır, `downloadFile` stream için response döner.

**Files:**
- Modify: `android/app/src/main/java/com/agent/bridge/BridgeClient.kt:65` (DirEntry), `:894-908` (workerDirs), yeni downloadFile

- [ ] **Step 1: `DirEntry` veri sınıfını genişlet**

`BridgeClient.kt:65`:
```kotlin
data class DirEntry(val name: String, val path: String, val type: String = "dir", val size: Long = 0)
```

- [ ] **Step 2: `workerDirs`'i `includeFiles` parametresiyle güncelle**

`BridgeClient.kt` (satır ~895). `WorkerDirs` data class aynı kalır; sadece istek URL'sine `&files=true` eklenir:

```kotlin
suspend fun workerDirs(settings: BridgeSettings, root: String, includeFiles: Boolean = false): WorkerDirs {
    val query = URLEncoder.encode(root, "UTF-8")
    val filesParam = if (includeFiles) "&files=true" else ""
    val json = getJson(settings, "/dirs?root=$query$filesParam")
    val array = json.optJSONArray("dirs")
    val dirs = buildList {
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(DirEntry(
                    name = item.optString("name"),
                    path = item.optString("path"),
                    type = item.optString("type", "dir"),
                    size = item.optLong("size", 0),
                ))
            }
        }
    }
    return WorkerDirs(json.optBoolean("ok"), json.optString("base"), dirs)
}
```

- [ ] **Step 3: `downloadFile` ekle**

`BridgeClient.kt`'ye, `attachFile`'dan sonra (satır ~922) ekle. Response'u KAPATMADAN döndürür; çağıran `use {}` ile stream'i okuyup kapatır:

```kotlin
/**
 * Stream a workspace file as raw bytes. Returns the open [Response] — caller MUST close it
 * (use a `use {}` block) after copying the body to its destination. `onProgress` receives a
 * 0..1 fraction as bytes arrive (best-effort, based on Content-Length).
 */
suspend fun downloadFile(
    settings: BridgeSettings,
    path: String,
    onProgress: (Float) -> Unit,
): Response = withContext(Dispatchers.IO) {
    val query = URLEncoder.encode(path, "UTF-8")
    val request = buildRequest(settings, "/download?path=$query").get().build()
    val response = client.newCall(request).await()
    if (!response.isSuccessful) {
        response.close()
        throw IOException("HTTP ${response.code}")
    }
    response
}
```

- [ ] **Step 4: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -20
```
Beklenen: BUILD SUCCESSFUL. `client.newCall(request).await()` ve `Response.close()` zaten projede kullanılıyor (UpdateManager pattern).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/BridgeClient.kt
git commit -m "BridgeClient: workerDirs includeFiles + downloadFile stream"
```

---

## Task 5: `DownloadRepo` — MediaStore + geçmiş

Stream'i telefona yazan ve geçmişi tutan tek-sorumluluk sınıf. API 29+ MediaStore, API 26–28 `getExternalStoragePublicDirectory`.

**Files:**
- Create: `android/app/src/main/java/com/agent/bridge/DownloadRepo.kt`

- [ ] **Step 1: `DownloadRepo.kt` oluştur**

```kotlin
package com.agent.bridge

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream

data class DownloadRecord(
    val name: String,
    val sourcePath: String,
    val size: Long,
    val mimeType: String,
    val downloadedAt: Long,
    val localUri: String,
)

class DownloadRepo(private val context: Context) {

    /**
     * Download [sourcePath] from the bridge via [client] and persist it to the shared
     * Downloads directory ("Download/AgentBridge"). Streams the response body directly to the
     * destination to avoid holding the whole file in memory. Records the result in history.
     * Throws on any failure; partial writes are best-effort cleaned up.
     */
    suspend fun download(
        client: BridgeClient,
        settings: BridgeSettings,
        sourcePath: String,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): DownloadRecord = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        onProgress(0f)
        val response = client.downloadFile(settings, sourcePath, onProgress)
        response.use {
            val body = it.body ?: throw IOException("Boş yanıt gövdesi")
            val mimeType = body.contentType()?.toString() ?: "application/octet-stream"
            val total = body.contentLength()
            val (uri, output) = openOutput(displayName, mimeType)
            try {
                output.use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.flush()
                }
                onProgress(1f)
                val record = DownloadRecord(
                    name = displayName,
                    sourcePath = sourcePath,
                    size = copiedBytes(uri),
                    mimeType = mimeType,
                    downloadedAt = System.currentTimeMillis(),
                    localUri = uri.toString(),
                )
                addHistory(record)
                record
            } catch (e: Exception) {
                // Partial file cleanup
                runCatching { deleteUri(uri) }
                throw e
            }
        }
    }

    private var copiedSoFar: Long = 0

    private fun copiedBytes(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
        } catch { 0L }
    }

    /** Open an output stream to Downloads/AgentBridge/<displayName>. Returns (uri, stream). */
    private fun openOutput(displayName: String, mimeType: String): Pair<Uri, OutputStream> {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AgentBridge")
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore kayıt başarısız")
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Çıkış akışı açılamadı")
            return uri to stream
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AgentBridge")
            if (!dir.exists()) dir.mkdirs()
            val target = ensureUniqueLegacy(dir, displayName)
            val stream = target.outputStream()
            return Uri.fromFile(target) to stream
        }
    }

    // Legacy (API < 29): avoid overwriting an existing file.
    private fun ensureUniqueLegacy(dir: File, name: String): File {
        val target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(dir, "$base ($i)$ext").exists()) i++
        return File(dir, "$base ($i)$ext")
    }

    private fun deleteUri(uri: Uri) {
        if (uri.scheme == "content") context.contentResolver.delete(uri, null, null)
        else uri.path?.let { File(it).takeIf { f -> f.exists() }?.delete() }
    }

    // --- History (SharedPreferences) ---
    private val prefs get() = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun history(): List<DownloadRecord> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(DownloadRecord(
                        name = o.optString("name"),
                        sourcePath = o.optString("sourcePath"),
                        size = o.optLong("size"),
                        mimeType = o.optString("mimeType"),
                        downloadedAt = o.optLong("downloadedAt"),
                        localUri = o.optString("localUri"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun addHistory(record: DownloadRecord) {
        val list = history().toMutableList()
        list.add(0, record)
        val capped = list.take(MAX_HISTORY)
        val arr = JSONArray()
        for (r in capped) {
            arr.put(JSONObject().apply {
                put("name", r.name); put("sourcePath", r.sourcePath); put("size", r.size)
                put("mimeType", r.mimeType); put("downloadedAt", r.downloadedAt); put("localUri", r.localUri)
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    companion object {
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 100
    }
}
```

Not: `copiedSoFar` alanı gereksiz çıktı — Step 1 kodundan kaldır (size'ı `copiedBytes(uri)` ile sorguluyoruz).

- [ ] **Step 2: Kodu temizle — gereksiz `copiedSoFar` alanını sil**

Step 1'deki `private var copiedSoFar: Long = 0` satırını dosyadan kaldır (kullanılmıyor).

- [ ] **Step 3: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -20
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/DownloadRepo.kt
git commit -m "DownloadRepo: OkHttp stream → MediaStore Downloads + geçmiş"
```

---

## Task 6: Manifest izinleri + FileProvider yolu

API 26–28 için `WRITE_EXTERNAL_STORAGE` ve Downloads alt klasörü için FileProvider yolu.

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Manifest'e `WRITE_EXTERNAL_STORAGE` ekle**

`AndroidManifest.xml`'de `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` satırından sonra ekle:

```xml
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
```

- [ ] **Step 2: `file_paths.xml`'e Downloads yolu ekle**

`android/app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="apk" path="." />
    <files-path name="files" path="." />
    <external-files-path name="downloads" path="Download/AgentBridge" />
</paths>
```

- [ ] **Step 3: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -20
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/res/xml/file_paths.xml
git commit -m "Manifest: WRITE_EXTERNAL_STORAGE (maxSdk 28) + Downloads FileProvider yolu"
```

---

## Task 7: `RemoteViewModel` — browser/download/downloads state + fonksiyonlar

UI state ve aksiyonlar. Yeni ekranların görünür kalması için flag'ler; indirme için `DownloadRepo` entegrasyonu.

**Files:**
- Modify: `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt:30-130` (RemoteUiState), sınıf gövdesi

- [ ] **Step 1: `RemoteUiState`'e alanlar ekle**

`RemoteUiState` data class'ına (satır ~129, kapanış `)` öncesi) ekle:

```kotlin
    // --- File browser & downloads ---
    val fileBrowserVisible: Boolean = false,
    val downloadsVisible: Boolean = false,
    val fileBrowserEntries: List<DirEntry> = emptyList(),
    val fileBrowserBase: String = "",
    val fileBrowserLoading: Boolean = false,
    val activeDownloadName: String? = null,
    val activeDownloadProgress: Float = 0f,
    val downloadRecords: List<DownloadRecord> = emptyList(),
```

- [ ] **Step 2: `RemoteViewModel`'a `DownloadRepo` ekle**

Sınıf başında (satır ~135 civarısı, `private val client = BridgeClient()` yanına):

```kotlin
    private val downloadRepo = DownloadRepo(application.applicationContext)
```

- [ ] **Step 3: Browser fonksiyonları ekle**

Sınıfa (örn. `loadWorkerDirs`'den sonra, satır ~430) ekle. Mevcut `workerDirs` flow'unu ortak kullanır:

```kotlin
    fun showFileBrowser(show: Boolean) {
        _uiState.update { it.copy(fileBrowserVisible = show) }
        if (show) {
            _uiState.update { it.copy(fileBrowserEntries = emptyList(), fileBrowserBase = "", fileBrowserLoading = true) }
            loadBrowserDir("")
        }
    }

    fun showDownloads(show: Boolean) {
        _uiState.update { it.copy(downloadsVisible = show, downloadRecords = downloadRepo.history()) }
    }

    fun loadBrowserDir(root: String) = viewModelScope.launch {
        _uiState.update { it.copy(fileBrowserLoading = true) }
        runCatching { client.workerDirs(_uiState.value.settings, root, includeFiles = true) }
            .onSuccess { dirs ->
                _uiState.update { it.copy(fileBrowserEntries = dirs.dirs, fileBrowserBase = dirs.base, fileBrowserLoading = false) }
            }
            .onFailure {
                _uiState.update { it.copy(fileBrowserLoading = false) }
                reportError("Klasör yüklenemedi", it)
            }
    }

    fun openBrowserFile(path: String) = viewModelScope.launch {
        openFile(path)
    }
```

- [ ] **Step 4: İndirme fonksiyonu ekle**

Sınıfa ekle. `openFile` fonksiyonundan (satır ~602) sonra:

```kotlin
    fun downloadCurrentFile() = viewModelScope.launch {
        val file = _openedFile.value ?: return@launch
        // FileResult gerçek path taşımaz; browser kaynak yolunu ayrı state'ten alacağız.
        // Tarayıcıdan gelen indirme için downloadByPath kullanılır.
        downloadByPath(file.name, file.name)
    }

    fun downloadByPath(sourcePath: String, displayName: String) = viewModelScope.launch {
        _uiState.update { it.copy(activeDownloadName = displayName, activeDownloadProgress = 0f) }
        runCatching {
            downloadRepo.download(_uiState.value.settings.let { client }, _uiState.value.settings, sourcePath, displayName) { progress ->
                _uiState.update { it.copy(activeDownloadProgress = progress) }
            }
        }.onSuccess {
            _uiState.update { it.copy(activeDownloadName = null, activeDownloadProgress = 0f, downloadRecords = downloadRepo.history()) }
            _messages.emit("İndirildi: ${it.name}")
        }.onFailure {
            _uiState.update { it.copy(activeDownloadName = null, activeDownloadProgress = 0f) }
            reportError("İndirme başarısız", it)
        }
    }
```

Not: `downloadRepo.download` imzası `(client, settings, sourcePath, displayName, onProgress)` — Step 4'teki çağrı düzeltilmelidir: `downloadRepo.download(client, _uiState.value.settings, sourcePath, displayName) { ... }`. `client` zaten bir alan (`private val client`).

- [ ] **Step 5: Çağrıyı düzelt**

Step 4'teki `downloadByPath` içindeki çağrıyı düzelt:
```kotlin
            downloadRepo.download(client, _uiState.value.settings, sourcePath, displayName) { progress ->
                _uiState.update { it.copy(activeDownloadProgress = progress) }
            }
```

`downloadCurrentFile` için ise `FileResult` path taşımadığından, tarayıcıdan indirme hep `downloadByPath` üzerinden yapılır; `downloadCurrentFile` kaldırılır.

- [ ] **Step 6: `downloadCurrentFile`'ı kaldır, sadece `downloadByPath` kalsın**

Step 4'te eklenen `downloadCurrentFile` fonksiyonunu sil. Sadece `downloadByPath` kullanılır (UI, açık dosyanın adını/path'ini `downloadByPath`'e geçirir).

- [ ] **Step 7: `clearDownloadHistory` ekle**

```kotlin
    fun clearDownloadHistory() {
        downloadRepo.clearHistory()
        _uiState.update { it.copy(downloadRecords = emptyList()) }
    }
```

- [ ] **Step 8: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -20
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt
git commit -m "RemoteViewModel: browser/downloads state + indirme fonksiyonları"
```

---

## Task 8: `FileBrowserScreen` — dosya tarayıcı UI'sı

İddialı UI: mevcut tema (sıcak altın/çam yeşili), `ListItem` + `SheetRow` estetiği, dosya türü ikonları, boyut göstergeleri, breadcrumb, ilerleme göstergesi.

**Files:**
- Create: `android/app/src/main/java/com/agent/bridge/FileBrowserScreen.kt`

- [ ] **Step 1: `FileBrowserScreen.kt` oluştur**

```kotlin
package com.agent.bridge

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileBrowserScreen(uiState: RemoteUiState, actions: RemoteViewModel) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                title = {
                    Column {
                        Text("Dosyalar", style = MaterialTheme.typography.titleLarge)
                        Text(
                            uiState.fileBrowserBase.ifBlank { "Bilgisayar" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { actions.showFileBrowser(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = { actions.loadBrowserDir("") }) {
                        Icon(Icons.Default.Home, "Ana klasör")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (uiState.fileBrowserLoading && uiState.fileBrowserEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Üst klasöre çıkış satırı (home değilse)
                    val parent = parentPath(uiState.fileBrowserBase)
                    if (parent != null) {
                        item {
                            FileBrowserRow(
                                name = "..",
                                detail = parent,
                                icon = Icons.Default.Folder,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                trailing = null,
                                onClick = { actions.loadBrowserDir(parent) },
                            )
                        }
                    }
                    if (uiState.fileBrowserEntries.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("Bu klasör boş", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(uiState.fileBrowserEntries, key = { it.path }) { entry ->
                        val isDir = entry.type == "dir"
                        FileBrowserRow(
                            name = entry.name,
                            detail = if (isDir) "Klasör" else formatFileSize(entry.size),
                            icon = iconForFile(entry.name),
                            iconTint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            trailing = if (!isDir) {
                                { Text(it) }
                            } else null,
                            onClick = {
                                if (isDir) actions.loadBrowserDir(entry.path)
                                else {
                                    actions.openBrowserFile(entry.path)
                                }
                            },
                            trailingText = if (!isDir) "Önizle" else null,
                        )
                    }
                }
            }

            // Aktif indirme ilerlemesi — altta yapışkan kart
            AnimatedVisibility(
                visible = uiState.activeDownloadName != null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ActiveDownloadCard(uiState.activeDownloadName.orEmpty(), uiState.activeDownloadProgress)
            }
        }
    }
}

@Composable
private fun FileBrowserRow(
    name: String,
    detail: String,
    icon: ImageVector,
    iconTint: Color,
    trailing: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    trailingText: String? = null,
) {
    ListItem(
        headlineContent = {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = {
            Surface(shape = CircleShape, color = iconTint.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun FileBrowserRow(
    name: String,
    detail: String,
    icon: ImageVector,
    iconTint: Color,
    trailing: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") trailingText: String? = null,
) = error("placeholder")

@Composable
private fun ActiveDownloadCard(name: String, progress: Float) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun iconForFile(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> Icons.Default.Image
        "mp4", "webm", "avi", "mov", "mkv" -> Icons.Default.VideoFile
        "mp3", "wav", "ogg", "flac", "m4a" -> Icons.Default.AudioFile
        "txt", "md", "markdown", "json", "xml", "csv", "log", "html", "htm" -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
```

Not: Step 1'de iki kez tanımlanan `FileBrowserRow` overload'u hatalı. Tek bir sade `FileBrowserRow` kullan. Ayrıca `trailingText` parametresi gereksiz. Aşağıdaki adımda düzelt.

- [ ] **Step 2: `FileBrowserRow`'u tek tanıma indir — düzelt**

Step 1'deki ikinci (hatalı) `FileBrowserRow` overload'unu sil. Birinci overload'un imzasını sadeleştir; `trailingText` parametresini kaldır ve çağrı yerlerinde "Önizle" trailing text'ini düzelt. `items` bloğundaki çağrıyı güncelle:

```kotlin
                    items(uiState.fileBrowserEntries, key = { it.path }) { entry ->
                        val isDir = entry.type == "dir"
                        FileBrowserRow(
                            name = entry.name,
                            detail = if (isDir) "Klasör" else formatFileSize(entry.size),
                            icon = iconForFile(entry.name),
                            iconTint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            trailing = if (!isDir) { { Text("Önizle", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } } else null,
                            onClick = {
                                if (isDir) actions.loadBrowserDir(entry.path)
                                else actions.openBrowserFile(entry.path)
                            },
                        )
                    }
```

Ve `FileBrowserRow` tanımından `trailingText: String? = null` parametresini ve `@Suppress` satırını kaldır.

- [ ] **Step 3: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -30
```
Beklenen: BUILD SUCCESSFUL. (Eksik import varsa fix'le.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/FileBrowserScreen.kt
git commit -m "FileBrowserScreen: klasör/dosya gezici + ilerleme kartı"
```

---

## Task 9: `FileViewerSheet` — "İndir" butonu

Mevcut önizleme sheet'ine indirme butonu. Tarayıcıdan açılan dosyanın path'i UI state'te takip edilir.

**Files:**
- Modify: `android/app/src/main/java/com/agent/bridge/FileViewerSheet.kt`
- Modify: `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt` (openBrowserFile path hatırlama)

- [ ] **Step 1: `RemoteUiState`'e `lastOpenedPath` ekle**

`RemoteUiState`'e (Task 7'de eklenen alanların yanına):
```kotlin
    val lastOpenedPath: String = "",
```

- [ ] **Step 2: `openBrowserFile` path'i hatırlasın**

`RemoteViewModel.openBrowserFile`'ı güncelle (Task 7'de eklenen):
```kotlin
    fun openBrowserFile(path: String) = viewModelScope.launch {
        _uiState.update { it.copy(lastOpenedPath = path) }
        openFile(path)
    }
```

- [ ] **Step 3: `FileViewerSheet`'e "İndir" butonu ve callback ekle**

`FileViewerSheet.kt` imzasını güncelle — `onDownload` callback'i ekle:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileViewerSheet(
    file: FileResult?,
    onDismiss: () -> Unit,
    onFileClick: (String) -> Unit,
    onDownload: () -> Unit,
) {
```

Üst satırdaki (Close butonu olan) Row'a "İndir" IconButton ekle. Mevcut:
```kotlin
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
```
Bunu şununla değiştir:
```kotlin
                IconButton(onClick = onDownload, enabled = file.ok) { Icon(Icons.Default.Download, "İndir") }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Kapat") }
```

İndirme import'u dosyanın başındaki import bloğuna ekle (Material icons):
```kotlin
import androidx.compose.material.icons.filled.Download
```

- [ ] **Step 4: `MainActivity`'de `FileViewerSheet` çağrısını güncelle**

`MainActivity.kt:54`:
```kotlin
                FileViewerSheet(
                    openedFile,
                    onDismiss = viewModel::closeFile,
                    onFileClick = viewModel::openFile,
                    onDownload = { viewModel.lastOpenedPathValue().let { p -> if (p.isNotBlank()) viewModel.downloadByPath(p, openedFile?.name ?: "dosya") } },
                )
```

- [ ] **Step 5: `lastOpenedPathValue` helper ekle**

`RemoteViewModel`'a ekle:
```kotlin
    fun lastOpenedPathValue(): String = _uiState.value.lastOpenedPath
```

- [ ] **Step 6: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -30
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/FileViewerSheet.kt android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt android/app/src/main/java/com/agent/bridge/MainActivity.kt
git commit -m "FileViewerSheet: İndir butonu + tarayıcı path takibi"
```

---

## Task 10: `DownloadsListScreen` — indirilenler geçmişi

Basit ama şık geçmiş listesi. Aç/Temizle aksiyonları.

**Files:**
- Create: `android/app/src/main/java/com/agent/bridge/DownloadsListScreen.kt`

- [ ] **Step 1: `DownloadsListScreen.kt` oluştur**

```kotlin
package com.agent.bridge

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsListScreen(uiState: RemoteUiState, actions: RemoteViewModel) {
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                title = { Text("İndirilenler", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { actions.showDownloads(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                },
                actions = {
                    if (uiState.downloadRecords.isNotEmpty()) {
                        IconButton(onClick = { actions.clearDownloadHistory() }) {
                            Icon(Icons.Default.DeleteSweep, "Geçmişi temizle")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.downloadRecords.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Text("Henüz indirme yok", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.downloadRecords, key = { it.localUri + it.downloadedAt }) { record ->
                    ListItem(
                        headlineContent = {
                            Text(record.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                "${formatSize(record.size)} · ${relativeTime(record.downloadedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                    Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        trailingContent = {
                            Text("Aç", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.localUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
```

Not: `formatSize` ve `FileBrowserScreen`'deki `formatFileSize` yineleniyor. DRY için son task'ta ortak bir yardımcıya taşınabilir; şimdilik kabul edilebilir (küçük).

- [ ] **Step 2: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -30
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/DownloadsListScreen.kt
git commit -m "DownloadsListScreen: indirilenler geçmişi + aç/temizle"
```

---

## Task 11: Navigasyon — `AppScaffold` + `LandingScreen` girişleri

Yeni ekranları `AppScaffold` `when` bloğuna bağla ve `LandingScreen`'e giriş kartları ekle.

**Files:**
- Modify: `android/app/src/main/java/com/agent/bridge/ChatScreen.kt:302-308` (AppScaffold when)
- Modify: `android/app/src/main/java/com/agent/bridge/LandingScreen.kt` (kartlar)

- [ ] **Step 1: `AppScaffold` `when` bloğuna iki ekran ekle**

`ChatScreen.kt` `AppScaffold` (satır ~302) içindeki `when` bloğunu güncelle:
```kotlin
                when {
                    uiState.showSettings -> SettingsScreen(uiState, actions)
                    uiState.showMcp -> McpScreen(uiState, actions)
                    uiState.fileBrowserVisible -> FileBrowserScreen(uiState, actions)
                    uiState.downloadsVisible -> DownloadsListScreen(uiState, actions)
                    !uiState.landingDismissed && uiState.backend == "antigravity" ->
                        LandingScreen(uiState, actions)
                    else -> ChatScreen(uiState, actions)
                }
```

- [ ] **Step 2: `LandingScreen`'e "Dosyalar" ve "İndirilenler" girişleri ekle**

`LandingScreen.kt`'de agent kartlarından önce (satır ~129 `Spacer`'dan sonra) bir ayraç ve iki yeni "araç" kartı ekle. Mevcut `AgentCard` bir agent'a girer; burada `ToolCard` adında basit bir UI kullanırız (mode'a girmez, flag kaldırır). `LandingScreen` composable'ının `Column` içine, agent kartlarından ÖNCE ekle:

```kotlin
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolCard(
                name = "Dosyalar",
                icon = Icons.Default.Folder,
                onClick = { actions.showFileBrowser(true) },
                modifier = Modifier.weight(1f),
            )
            ToolCard(
                name = "İndirilenler",
                icon = Icons.Default.Download,
                onClick = { actions.showDownloads(true) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
```

`LandingScreen.kt`'nin import bloğuna ekle (yoksa):
```kotlin
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
```
`Folder` ve `Storage` zaten import edilmiş. `Icons.Default.Folder` mevcut.

- [ ] **Step 3: `ToolCard` composable ekle**

`LandingScreen.kt`'de `AgentCard`'tan sonra ekle. `AgentCard`'ın daha kompakt, yan yana kullanılabilir versiyonu:

```kotlin
@Composable
private fun ToolCard(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, name, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
```

`ToolCard`'ın ihtiyaç duyduğu import'lar: `Box`, `fillMaxSize` zaten import edilmiş; `Column` import edildi; `Surface` import edildi. `Modifier` parametresi için `modifier` import'u zaten var.

- [ ] **Step 4: Derle**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -30
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/ChatScreen.kt android/app/src/main/java/com/agent/bridge/LandingScreen.kt
git commit -m "Navigasyon: FileBrowser/Downloads ekranları + Landing araç kartları"
```

---

## Task 12: Back navigation + uçtan uca derleme

Geri butonu yeni ekranları handle etsin ve tüm proje derlensin.

**Files:**
- Modify: `android/app/src/main/java/com/agent/bridge/MainActivity.kt` (BackHandler)

- [ ] **Step 1: `MainActivity`'de üst düzey BackHandler ekle**

`MainActivity.kt` içinde `AgentBridgeTheme` bloğunda, `AppScaffold` çağrısından önce (ama `uiState` toplandıktan sonra) bir `BackHandler` ekle ki browser/downloads açıkken geri landing'e dönsün:

```kotlin
                BackHandler(enabled = uiState.fileBrowserVisible || uiState.downloadsVisible) {
                    if (uiState.fileBrowserVisible) viewModel.showFileBrowser(false)
                    if (uiState.downloadsVisible) viewModel.showDownloads(false)
                }
```

Import:
```kotlin
import androidx.activity.compose.BackHandler
```

- [ ] **Step 2: Tüm bridge testleri çalıştır**

```bash
cd bridge && node --test test/resolveWorkspacePath.test.mjs test/listDirs.test.mjs test/downloadRoute.test.mjs test/readWorkspaceFile.test.mjs
```
Beklenen: hepsi PASS.

- [ ] **Step 3: Android tam derleme**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -30
```
Beklenen: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/agent/bridge/MainActivity.kt
git commit -m "BackHandler: browser/downloads açıkken geri landing'e"
```

---

## Task 13: Emülatörde uçtan uca doğrulama

`android-dev` skill'i ile emülatörde gerçek akışı doğrula.

- [ ] **Step 1: Emülatörü hazırla**

`android-dev` skill'ini kullanarak hazır bir AVD bul/başlat.

- [ ] **Step 2: Uygulamayı derle ve yükle**

```bash
cd android && ./gradlew assembleDebug
```
APK'yi emülatöre yükle (skill'in `android_build_and_run` aracı).

- [ ] **Step 3: Akışı test et**

1. Uygulama aç → ana ekranda "Dosyalar" kartı görünür.
2. "Dosyalar"a bas → `FileBrowserScreen` açılır, klasör/dosya listesi yüklenir.
3. Bir klasöre gir → alt içerik yüklenir.
4. Bir metin dosyasına bas → `FileViewerSheet` açılır, "İndir" butonu görünür.
5. "İndir"e bas → ilerleme kartı görünür, bitince "İndirildi" mesajı.
6. Geri → "İndirilenler" → kayıt listede.
7. Kayda bas → dosya açılır (uygun app).
8. Ekran görüntüsü al (`android_screenshot`).

- [ ] **Step 4: Hata varsa fix'le ve tekrar dene**

İndirme başarısız olursa `android_logs` ile logları incele. Path çözme, MIME, MediaStore adımlarını kontrol et.

- [ ] **Step 5: Son commit (varsa fix)**

```bash
git add -A
git commit -m "Dosya indirme: uçtan uca doğrulama sonrası düzeltmeler"
```

---

## Self-Review

**1. Spec coverage:**
- ✅ Path çözme tek yardımcı (`resolveWorkspacePath`) — Task 1
- ✅ `/dirs?files=true` — Task 2
- ✅ `/download` raw stream + MIME — Task 3
- ✅ `BridgeClient.workerDirs` + `downloadFile` — Task 4
- ✅ `DownloadRepo` (MediaStore 29+ / legacy 26-28 + geçmiş) — Task 5
- ✅ Manifest izinleri — Task 6
- ✅ `FileBrowserScreen` — Task 8
- ✅ `FileViewerSheet` "İndir" — Task 9
- ✅ `DownloadsListScreen` — Task 10
- ✅ Navigasyon — Task 11
- ✅ Back nav — Task 12
- ✅ Testler (bridge) + emulator — Task 2/3/13

**2. Placeholder scan:** Plan içindeki "Not" kutuları birer düzeltme yönergesidir (Task 5 Step 2, Task 7 Step 4-6, Task 8 Step 2) ve ilgili step'te açık kodla verilmiştir — düzeltme yapılıp commit'lenir. Başka TODO/TBD yok.

**3. Type consistency:**
- `DirEntry(name, path, type, size)` — Task 2 (bridge JSON) ↔ Task 4 (Android data class) ↔ Task 7/8 (UI) tutarlı.
- `resolveWorkspacePath` — Task 1 (tanım) ↔ Task 3 (route'ta kullanım) tutarlı.
- `downloadByPath(sourcePath, displayName)` — Task 7 ↔ Task 9 tutarlı.
- `DownloadRepo.download(client, settings, sourcePath, displayName, onProgress)` — Task 5 ↔ Task 7 tutarlı.
- `showFileBrowser`/`showDownloads`/`loadBrowserDir`/`openBrowserFile`/`clearDownloadHistory` — Task 7 ↔ Task 8/10/11 tutarlı.
- `DownloadRecord` alanları — Task 5 ↔ Task 10 tutarlı.

**Notlar (implementasyon sırasında):**
- Task 5 Step 1 kodunda `copiedSoFar` kullanılmıyor — Step 2'de silinir (belirtildi).
- Task 7'de `downloadCurrentFile` gereksiz çıktı — Step 6'da kaldırılır (belirtildi).
- Task 8'de iki `FileBrowserRow` overload'u hatalı — Step 2'de düzeltilir (belirtildi).
- `formatSize`/`formatFileSize` DRY ihlali küçük; kabul edilebilir.
