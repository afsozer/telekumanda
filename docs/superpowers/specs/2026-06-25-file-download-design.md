# Dosya İndirme Özelliği — Tasarım Spec'i

**Tarih:** 2026-06-25
**Durum:** Tasarım (onay bekliyor)
**Yaklaşım:** A — Hafif Genişletme (mevcut `/file` + `/dirs` altyapısını genişlet)

## 1. Hedef ve Kapsam

AgentBridge Android uygulamasına, bilgisayarda (bridge sunucu) bulunan herhangi bir dosyayı telefona **binary olarak indirme** özelliği eklemek. Kullanıcı, bağımsız bir **dosya tarayıcı** ile bilgisayardaki klasörleri gezer, bir dosyayı **önizler** ve "İndir" diyerek onu Android'in paylaşımlı **Downloads** klasörüne kaydeder. App içinde ayrıca bir **indirilenler geçmişi** tutulur.

### Hedef senaryo
1. Kullanıcı ana ekrandan "Dosyalar" girişine basar → `FileBrowserScreen` açılır.
2. Bilgisayardaki klasörleri gezer (`/dirs?files=true`), bir dosyaya basar.
3. `FileViewerSheet` (mevcut önizleme) açılır; metin/markdown dosyaları için içerik gösterilir.
4. Kullanıcı "İndir" der → dosya `/download?path=...` üzerinden stream edilip MediaStore Downloads'a yazılır.
5. Bitince bildirim + `DownloadsListScreen`'de geçmişte görünür.

### Kapsam dışı (YAGNI)
- HTTP Range / parçalı indirme, duraklat-devam
- Sistem seviyesinde DownloadManager (Tailscale + custom auth ile uyumsuz)
- Arka plan (foreground service) indirme — app açıkken indirme yeterli
- Klasör/ziple toplu indirme
- Çoklu paralel indirme kuyruğu

## 2. Mimari

İki katman değişir: **bridge** (Node.js) ve **Android** (Kotlin/Compose).

```
[FileBrowserScreen] --/dirs?files=true--> [bridge: listDirs]      (klasör + dosya listesi)
[FileBrowserScreen] --/file?path=...-----> [bridge: readWorkspaceFile] (önizleme metni)
[FileViewerSheet]   --/download?path=...-> [bridge: GET /download] (raw stream + MIME)
[DownloadRepo]      --> MediaStore.Downloads                             (telefona kayıt)
[DownloadsListScreen] <-- DownloadRepo --> SharedPreferences             (geçmiş)
```

### Path çözme: tek ortak yardımcı
`readWorkspaceFile` içindeki path çözme mantığı (alias → absolute → workspace root → basename walk) `resolveWorkspacePath(p)` adlı yardımcıya çıkarılır. `/file` ve `/download` aynı güvenli çözümlemeyi kullanır.

**Güvenlik:** Çözümlenen mutlak path'in normalize edilmiş hali workspace root'larından en az birinin altında olmalı (`path.relative` + `..` kontrolü). Aksi halde 403. Mevcut kodda workspace'le sınırlama zaten var (root walk + alias); bu, onu açık ve doğrulanabilir hale getirir.

## 3. Bridge Değişiklikleri

### 3.1 `bridge/server.mjs` — `resolveWorkspacePath(p)`
`readWorkspaceFile`'daki path çözme (satır 101–134) çıkarılıp `resolveWorkspacePath` olur:

```js
// returns { ok, target, name, error }
function resolveWorkspacePath(p) {
  try {
    if (!p) return { ok: false, error: 'path required' };
    const cleaned = decodeURIComponent(p).replace(/^agfile:\/+/, '').replace(/^\/+/, '').trim();
    let target = null;
    // fast path 1: alias
    const base = path.basename(cleaned).toLowerCase();
    const alias = FILE_ALIASES[base] || FILE_ALIASES[path.basename(cleaned)];
    if (alias && fs.existsSync(alias)) target = alias;
    // fast path 2: absolute / UNC
    if (!target && (/^[a-zA-Z]:[\\/]/.test(cleaned) || cleaned.startsWith('\\\\'))) {
      if (fs.existsSync(cleaned)) target = cleaned;
    }
    // fast path 3: workspace root relative
    if (!target) {
      for (const root of WS_ROOTS) { const c = path.join(root, cleaned); if (fs.existsSync(c) && fs.statSync(c).isFile()) { target = c; break; } }
    }
    // fallback: bounded basename walk
    if (!target) {
      for (const root of WS_ROOTS) { const acc = { found: null, visited: 0 }; findByBasename(root, base, 4, acc); if (acc.found) { target = acc.found; break; } }
    }
    if (!target) return { ok: false, error: 'not found', name: path.basename(cleaned) };
    return { ok: true, target, name: path.basename(target) };
  } catch (e) { return { ok: false, error: String(e.message || e) }; }
}
```

`readWorkspaceFile` artık `resolveWorkspacePath`'i çağırıp metin okuma kısmını yapar (davranış korunur).

### 3.2 `bridge/routes/general.mjs`

**`GET /dirs?root=...&files=true`** — `listDirs` genişletilir:
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
        let size = 0, type = 'dir';
        if (e.isFile()) {
          try { size = fs.statSync(path.join(base, e.name)).size; } catch {}
          type = 'file';
        }
        return { name: e.name, path: path.join(base, e.name), type, size };
      });
    // klasörler önce, sonra dosyalar; alfabetik
    entries.sort((a, b) => (a.type === b.type) ? a.name.localeCompare(b.name) : (a.type === 'dir' ? -1 : 1));
    return { ok: true, base, dirs: entries };
  } catch (e) { return { ok: false, error: String(e.message || e), base }; }
}
```
Route: `router.get('/dirs', (req, res) => json(res, 200, listDirs(...root, params.get('files') === 'true')))`.

**`GET /download?path=...`** — yeni route, raw stream:
```js
router.get('/download', (req, res) => {
  const resolved = resolveWorkspacePath(new URL(req.url, 'http://x').searchParams.get('path') || '');
  if (!resolved.ok) return json(res, 404, { error: resolved.error });
  const stat = fs.statSync(resolved.target);
  res.writeHead(200, {
    'Content-Type': mime(resolved.target),
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${encodeURIComponent(resolved.name)}"`,
  });
  fs.createReadStream(resolved.target).pipe(res);
});
```
`mime()` küçük bir uzantı→MIME haritası (yoksa `application/octet-stream`). MIME tahmini bridge'de yapılır çünkü Android tarafı `Content-Type`'ı kullanır.

`resolveWorkspacePath` route'lara ek parametre olarak `register(router, { readWorkspaceFile, resolveWorkspacePath, ... })` ile aktarılır (route dosyalarında `resolveWorkspaceFile` → `resolveWorkspacePath` olarak tutarlı adlandırma).

## 4. Android Değişiklikleri

### 4.1 `BridgeClient.kt`

**`workerDirs` genişlet** — `files=true` desteği; mevcut `WorkerDirs`/`DirEntry`'ye `type` ve `size` eklenir:
```kotlin
data class DirEntry(val name: String, val path: String, val type: String = "dir", val size: Long = 0)
suspend fun workerDirs(settings: BridgeSettings, root: String, includeFiles: Boolean = false): WorkerDirs
// url: /dirs?root=...&files=true  (includeFiles true ise)
```

**Yeni `downloadFile`** — bridge'den stream edip progress ile callback:
```kotlin
suspend fun downloadFile(
    settings: BridgeSettings,
    path: String,
    onProgress: (Float) -> Unit,
): okhttp3.Response = withContext(Dispatchers.IO) {
    // buildRequest + Bearer token, response'u KAPATMADAN dön (çağıran stream'i okuyacak)
}
```
`UpdateManager.downloadApk`'daki stream+progress modeli referans alınır; fark: response body doğrudan çağırana verilir (MediaStore'a yazma orada yapılır).

### 4.2 Yeni: `FileBrowserScreen.kt`
Bağımsız dosya tarayıcı ekranı. Mevcut `workerDirs` UI kalıbını izler (SetupSheet'lerdeki klasör seçici gibi), ama hem klasör hem dosya gösterir.

- **TopAppBar:** geri butonu + başlık ("Dosyalar") + "Ana klasör" (home) butonu.
- **Breadcrumb / üst satır:** mevcut klasör yolu + "üst klasöre çık" butonu.
- **LazyColumn:** girişler (klasör → `Icons.Default.Folder`, dosya → türüne göre ikon), boyut gösterimi (KB/MB) dosyalar için.
- **Klasöre tıkla:** `loadWorkerDirs(path, includeFiles = true)` ile alt klasörü yükle.
- **Dosyaya tıkla:** `openFile(path)` → mevcut `FileViewerSheet` açılır (önizleme).

State `RemoteUiState`'e eklenir: `fileBrowserRoot: String`, `fileBrowserEntries: List<DirEntry>`, `fileBrowserLoading: Boolean`, `fileBrowserVisible: Boolean`.

### 4.3 `FileViewerSheet.kt` — "İndir" butonu
Mevcut sheet'e, dosya adı satırındaki `IconButton(Close)` yanına bir **Download IconButton** eklenir. `onDownload: (FileResult) -> Unit` callback'i `FileViewerSheet`'e verilir. Sadece `file.ok && file.content.isNotBlank()` durumunda görünür değil — başarılı her dosya için görünür (önizleme metni olmasa bile, örn. binary, indirme yapılabilir; sheet zaten yalnızca önizleme yapabildiği dosyalar için çağrılır). 

**Tasarım notu:** `FileResult` şu an içerik (`content`) taşır ve `readWorkspaceFile` 200KB'a kadar metin okur. İndirme, dosya yolundan `downloadFile` ile yapılır; önizlemeden bağımsız. Kullanıcı tarayıcıdan bir dosyaya bastığında: (a) önizlenebilir türse sheet açılır ve "İndir" gösterilir; (b) önizlenemeyen türse yine sheet açılıp "Bu dosya önizlenemiyor, indirebilirsiniz" mesajı + "İndir" gösterilir.

### 4.4 Yeni: `DownloadRepo.kt` (indirme + kayıt + geçmiş)
Tek sorumluluk: bridge'den indir + MediaStore Downloads'a yaz + geçmişe ekle.

```kotlin
class DownloadRepo(private val context: Context) {
    suspend fun download(
        client: BridgeClient,
        settings: BridgeSettings,
        path: String,
        displayName: String,
        onProgress: (Float) -> Unit,
    ): DownloadRecord  // başarılı kayıt veya hata fırlatır
    fun history(): List<DownloadRecord>
    fun clearHistory()
}

data class DownloadRecord(
    val name: String,
    val sourcePath: String,      // bilgisayardaki yol
    val size: Long,
    val mimeType: String,
    val downloadedAt: Long,
    val localUri: String,        // MediaStore content:// uri
)
```

**MediaStore Downloads yazımı — `minSdk = 26` nedeniyle iki yol:**

`MediaStore.Downloads` koleksiyonu ve `RELATIVE_PATH` yalnızca **API 29+** (Android 10). `minSdk = 26` olduğu için API 26–28'de `Environment.getExternalStoragePublicDirectory(DOWNLOADS)` + `WRITE_EXTERNAL_STORAGE` kullanılır (eski yol).

```kotlin
// API 29+
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/AgentBridge")
    }
    val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
    resolver.openOutputStream(uri)?.use { /* stream kopyala */ }
}
// API 26–28 (eski yol)
else {
    // WRITE_EXTERNAL_STORAGE gerekli — manifest'te maxSdk=28 ile eklenecek
    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AgentBridge")
    dir.mkdirs()
    val target = File(dir, displayName)
    target.outputStream().use { /* stream kopyala */ }
}
```

**İzin (manifest):** API 26–28 için `WRITE_EXTERNAL_STORAGE` `maxSdkVersion="28"` ile eklenir:
```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```
API 26–28'de runtime permission isteği gerekir (`ActivityResultContracts.RequestPermission`), `DownloadRepo` indirme öncesi kontrol eder.

**Geçmiş:** `SharedPreferences` ("downloads" key), JSON array olarak `DownloadRecord` listesi. Basit — DataStore aşırı olur; app restart'ta kalıcı olması yeterli. Limit: son 100 kayıt.

**Progress bildirimi:** İndirme başında, bitince ve hata durumunda `_messages` SharedFlow'a mesaj gönderilir (mevcut pattern). İsteğe bağlı: indirme sırasında LinearProgressIndicator gösterilir (UI state'e `activeDownload: DownloadRecord? + progress`).

### 4.5 Yeni: `DownloadsListScreen.kt`
İndirilenler geçmişi ekranı. Basit LazyColumn:
- Her satır: dosya adı, boyut, tarih (`relativeTime`), "Aç" aksiyonu (MediaStore uri ile `ACTION_VIEW`).
- Boş durumda "Henüz indirme yok".
- TopAppBar'da "Temizle" butonu.

### 4.6 Navigasyon — `MainActivity.kt` + `RemoteUiState`
Compose, `landingDismissed` gibi flag'lere göre dallanıyor. İki yeni flag eklenir:
- `fileBrowserVisible: Boolean` — `FileBrowserScreen` gösterimi
- `downloadsVisible: Boolean` — `DownloadsListScreen` gösterimi

Giriş noktaları:
- **LandingScreen:** iki yeni buton/kart eklenir — "Dosyalar" (`Icons.Default.Folder`) ve "İndirilenler" (`Icons.Default.Download`). Tıpkı agent kartları gibi ama mode'a girmez, sadece flag kaldırır.
- **Geri butonu:** `BackHandler` ile her iki ekrandan landing'e dönüş.

Öncelik: Landing → FileBrowser/Downloads, ve FileBrowser → FileViewerSheet (modal). Dosya indirme akışı Landing'den başlar çünkü agent modda değilken de dosya indirmek istenebilir.

## 5. İzinler

| İzin | Gerekli mi | Not |
|------|-----------|-----|
| `INTERNET` | ✅ var | OkHttp zaten |
| `POST_NOTIFICATIONS` | ✅ var | İndirme bitince bildirim |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | ➕ yeni | Yalnızca API 26–28 cihazlar için; runtime izni gerekir |
| `MANAGE_EXTERNAL_STORAGE` | ❌ HAYIR | MediaStore Downloads API ile gerekmez |
| `READ/WRITE_EXTERNAL_STORAGE` (API 29+) | ❌ HAYIR | Scoped storage; Downloads MediaStore üzerinden |

**Sonuç: AndroidManifest'e `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) eklenir.** `network_security_config` bridge Tailscale cleartext'e izin veriyor (mevcut).

## 6. Hata Durumları

| Durum | Davranış |
|-------|----------|
| Dosya bulunamadı (path çözülemedi) | Snackbar: "Dosya bulunamadı: {path}" |
| Aynı isimde dosya zaten indirilmiş | MediaStore otomatik benzersiz ad üretir (örn. `report (1).pdf`); geçmişe yeni kayıt eklenir |
| İndirme sırasında ağ kopması | OkHttp IOException → Snackbar: "İndirme başarısız: {error}"; geçmişe eklenmez |
| Disk dolu | IOException → Snackbar; MediaStore kısmi dosya silinmeli |
| Medya yazma reddedildi (nadir) | `openOutputStream` null → Snackbar |
| Çok büyük dosya (GB'lar) | Stream yapısı destekler; progress gösterilir; UI donmaz (Dispatchers.IO) |

## 7. Test Stratejisi

### Bridge (Node testi — mevcut `bridge/test/` pattern'i)
- `listDirs` `files=true` ile dosya + klasör döndürür, sıralama doğru (klasör önce).
- `listDirs` `files` yoksa mevcut davranış (sadece klasör).
- `/download` raw stream döner, doğru `Content-Type`/`Content-Disposition`, Content-Length uyumlu.
- `/download` bulunamayan path → 404 JSON.
- `resolveWorkspacePath` workspace dışına çıkmaz (`..` ile kaçış denemesi).

### Android (manuel + emulator — `android-dev` skill'i)
- FileBrowserScreen klasör/dosya listesi doğru render.
- Dosyaya basınca FileViewerSheet açılır, "İndir" butonu görünür.
- İndirme sonrası Downloads klasöründe dosya belirir (`adb shell ls` veya ekran görüntüsü).
- Geçmişe kayıt eklenir, DownloadsListScreen'de görünür.
- Aynı dosyayı tekrar indirme (benzersiz ad).

## 8. Uygulama Sırası (önerilen)

1. **Bridge:** `resolveWorkspacePath` çıkarımı + `readWorkspaceFile` refactor + testler.
2. **Bridge:** `/dirs` `files` desteği + testler.
3. **Bridge:** `/download` route + testler.
4. **Android:** `BridgeClient.workerDirs` genişlet + `downloadFile`.
5. **Android:** `DownloadRepo` (MediaStore + geçmiş) + birim mantık.
6. **Android:** `FileBrowserScreen`.
7. **Android:** `FileViewerSheet` "İndir" butonu + `RemoteViewModel.downloadCurrentFile`.
8. **Android:** `DownloadsListScreen`.
9. **Android:** Navigasyon (`MainActivity` + `LandingScreen` girişleri).
10. **Entegrasyon:** Emülatörde uçtan uca deneme.

## 9. Riskler ve Azaltıcılar

- **Path güvenliği:** Workspace dışına çıkma denemesi `resolveWorkspacePath`'te doğrulanır; testle kapsanır.
- **MediaStore API farklılıkları (API seviyesi):** `minSdk = 26`. API 29+ `MediaStore.Downloads` + `RELATIVE_PATH`; API 26–28 `getExternalStoragePublicDirectory` + `WRITE_EXTERNAL_STORAGE` (runtime izni). İki yol da `DownloadRepo` içinde `Build.VERSION.SDK_INT` ile dallanır.
- **Runtime izni (API 26–28):** `DownloadRepo` indirme öncesi `WRITE_EXTERNAL_STORAGE` iznini kontrol eder; yoksa UI state'e izin gerekli sinyali koyar, `RequestPermission` launcher tetiklenir.
- **OkHttp response leak:** `downloadFile` response'u çağırana verince kapatma sorumluluğu çağıranda; `use {}` bloğu ile garanti altına alınır (DownloadRepo içinde).
- **`FileResult` binary içeremez:** Önizleme ile indirme ayrıştırılır — önizleme `/file` (metin), indirme `/download` (binary). İkisi farklı endpoint, ayrı kod yolu.
