package com.agent.bridge

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream

// DownloadRecord shared modülünde (DownloadRecord.kt).

/**
 * Downloads a workspace file from the bridge and persists it to the shared Downloads
 * directory ("Download/AgentBridge"), recording the result in a SharedPreferences-backed
 * history. Streams the response body straight to the destination so whole-file buffering
 * is avoided (large files stay cheap on memory).
 *
 * API 29+: writes through MediaStore.Downloads (no storage permission needed).
 * API 26–28: writes to Environment.getExternalStoragePublicDirectory(DOWNLOADS) and
 * requires WRITE_EXTERNAL_STORAGE (granted by the caller before invoking download()).
 */
class AndroidDownloadRepo(private val context: Context) : DownloadRepo {

    suspend fun exportBytes(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val (uri, output) = openOutput(displayName, mimeType)
        try {
            output.use { it.write(bytes); it.flush() }
            scanIfLocal(uri)
            DownloadRecord(
                name = displayName,
                sourcePath = "",
                size = bytes.size.toLong(),
                mimeType = mimeType,
                downloadedAt = System.currentTimeMillis(),
                localUri = uri.toString(),
                relativePath = "Download/AgentBridge/",
            ).also(::addHistory)
        } catch (e: Exception) {
            runCatching { deleteUri(uri) }
            throw e
        }
    }

    /**
     * Download [sourcePath] from the bridge via [client], write it to Downloads and record
     * history. [onProgress] receives a 0..1 fraction as bytes arrive. Throws on any failure;
     * partial writes are best-effort cleaned up.
     */
    override suspend fun download(
        client: BridgeClient,
        settings: BridgeSettings,
        sourcePath: String,
        displayName: String,
        subDir: String,
        onProgress: (Float) -> Unit,
    ): DownloadRecord = withContext(Dispatchers.IO) {
        onProgress(0f)
        val response = client.downloadFile(settings, sourcePath, onProgress)
        val hedefRel = if (subDir.isBlank()) "Download/AgentBridge/" else "Download/AgentBridge/$subDir/"
        response.use {
            val body = it.body ?: throw IOException("Boş yanıt gövdesi")
            val mimeType = body.contentType()?.toString() ?: "application/octet-stream"
            val total = body.contentLength()
            val (uri, output) = openOutputAt(hedefRel, displayName, mimeType)
            try {
                var copied = 0L
                output.use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.flush()
                }
                scanIfLocal(uri)
                onProgress(1f)
                val record = DownloadRecord(
                    name = displayName,
                    sourcePath = sourcePath,
                    size = copied,
                    mimeType = mimeType,
                    downloadedAt = System.currentTimeMillis(),
                    localUri = uri.toString(),
                    relativePath = hedefRel,
                )
                addHistory(record)
                record
            } catch (e: Exception) {
                runCatching { deleteUri(uri) }
                throw e
            }
        }
    }

    /**
     * [sourcePath]'teki zip'i geçici dosyaya indirir ve Download/AgentBridge/<folderName>/
     * altına açar; geçmişe tek bir klasör kaydı düşer. Compress-Archive girdileri '\' ayraçlı
     * olabilir — girdi adları normalize edilir. Zip-slip ('..') girdileri atlanır.
     */
    override suspend fun downloadAndExtract(
        client: BridgeClient,
        settings: BridgeSettings,
        sourcePath: String,
        folderName: String,
        onProgress: (Float) -> Unit,
    ): DownloadRecord = withContext(Dispatchers.IO) {
        onProgress(0f)
        val tmp = File.createTempFile("ws-", ".zip", context.cacheDir)
        try {
            val response = client.downloadFile(settings, sourcePath, onProgress)
            response.use { resp ->
                val body = resp.body ?: throw IOException("Boş yanıt gövdesi")
                val total = body.contentLength()
                var copied = 0L
                tmp.outputStream().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) onProgress((copied.toFloat() / total * 0.7f).coerceIn(0f, 0.7f))
                    }
                }
            }
            // Aynı adlı klasör zaten indirildiyse damga ekle (MediaStore "(1)" kopyalarıyla
            // dosyaların karışmaması için).
            val unique = if (history().any { it.folder && it.name == folderName }) {
                folderName + "-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            } else folderName
            val relDir = "Download/AgentBridge/$unique/"
            var totalBytes = 0L
            java.util.zip.ZipFile(tmp).use { zf ->
                val entries = zf.entries().toList().filter { !it.isDirectory }
                entries.forEachIndexed { idx, entry ->
                    val norm = entry.name.replace('\\', '/')
                    if (norm.split('/').any { it == ".." }) return@forEachIndexed
                    val sub = norm.substringBeforeLast('/', "")
                    val fileName = norm.substringAfterLast('/')
                    if (fileName.isBlank()) return@forEachIndexed
                    val rel = if (sub.isBlank()) relDir else "$relDir$sub/"
                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        ?: "application/octet-stream"
                    val (uri, output) = openOutputAt(rel, fileName, mime)
                    try {
                        output.use { out -> zf.getInputStream(entry).use { it.copyTo(out) } }
                        scanIfLocal(uri)
                    } catch (e: Exception) {
                        runCatching { deleteUri(uri) }
                        throw e
                    }
                    totalBytes += entry.size.coerceAtLeast(0)
                    onProgress(0.7f + 0.3f * (idx + 1) / entries.size)
                }
            }
            val record = DownloadRecord(
                name = unique,
                sourcePath = sourcePath,
                size = totalBytes,
                mimeType = "inode/directory",
                downloadedAt = System.currentTimeMillis(),
                localUri = "",
                folder = true,
                relativePath = relDir,
            )
            addHistory(record)
            record
        } finally {
            tmp.delete()
        }
    }

    /** Open an output stream to Downloads/AgentBridge/<displayName>. Returns (uri, stream). */
    private fun openOutput(displayName: String, mimeType: String): Pair<Uri, OutputStream> =
        openOutputAt("Download/AgentBridge/", displayName, mimeType)

    /**
     * [relativePath] "Download/AgentBridge/" veya alt klasörü ("Download/AgentBridge/dava-x/").
     *
     * Tüm dosyalara erişim VARSA yeni düzene (/sdcard/AgentBridge/...) doğrudan
     * dosya olarak yazarız — MediaStore RELATIVE_PATH standart koleksiyonların
     * dışına yazamadığı için başka yolu yok. İzin yoksa eski MediaStore yoluna
     * düşeriz, böylece kullanıcı izni vermeden de indirme çalışmaya devam eder.
     */
    private fun openOutputAt(relativePath: String, displayName: String, mimeType: String): Pair<Uri, OutputStream> {
        if (PhoneStorage.hasPermission()) {
            return openOutputDirect(phoneDirFor(relativePath), displayName)
        }
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore kayıt başarısız")
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Çıkış akışı açılamadı")
            return uri to stream
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativePath.removePrefix("Download/"))
            if (!dir.exists()) dir.mkdirs()
            val target = ensureUniqueLegacy(dir, displayName)
            val stream = target.outputStream()
            return Uri.fromFile(target) to stream
        }
    }

    /**
     * Eski "Download/AgentBridge/alt/" göreli yolunu yeni köke çevirir:
     * "Download/AgentBridge/dava-x/" -> "/sdcard/AgentBridge/dava-x".
     * Çağıranlar hâlâ göreli yol dili konuşuyor; çeviri tek yerde kalsın diye
     * burada yapılıyor.
     */
    private fun phoneDirFor(relativePath: String): String {
        val alt = relativePath.removePrefix("Download/AgentBridge").trim('/')
        return if (alt.isBlank()) PhoneStorage.agentBridgeRoot()
        else PhoneFiles.join(PhoneStorage.agentBridgeRoot(), alt)
    }

    /**
     * Doğrudan yazılan dosyayı MediaStore'a bildirir. Bu olmadan görsel Galeri'de
     * görünmez — /sdcard/AgentBridge standart koleksiyon olmadığı için tarayıcı
     * kendiliğinden uğramıyor. MediaStore yoluyla yazılanlarda gerek yok.
     */
    private fun scanIfLocal(uri: Uri) {
        if (uri.scheme == "file") uri.path?.let { PhoneStorage.notifyMediaScanner(context, it) }
    }

    private fun openOutputDirect(dirPath: String, displayName: String): Pair<Uri, OutputStream> {
        if (!PhoneFiles.ensureDir(dirPath)) throw IOException("Klasör oluşturulamadı: $dirPath")
        val target = PhoneFiles.uniqueChild(dirPath, displayName)
        return Uri.fromFile(target) to target.outputStream()
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

    override fun history(): List<DownloadRecord> {
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
                        folder = o.optBoolean("folder", false),
                        relativePath = o.optString("relativePath"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun addHistory(record: DownloadRecord) {
        val list = history().toMutableList()
        list.add(0, record)
        saveHistory(list.take(MAX_HISTORY))
    }

    private fun saveHistory(list: List<DownloadRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("name", r.name); put("sourcePath", r.sourcePath); put("size", r.size)
                put("mimeType", r.mimeType); put("downloadedAt", r.downloadedAt); put("localUri", r.localUri)
                put("folder", r.folder); put("relativePath", r.relativePath)
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** [relativePath] altındaki dosyaları listeler (app'in kendi yazdıkları görünür). */
    override fun listFolder(relativePath: String): List<DownloadRecord> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val proj = arrayOf(
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE,
                MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.RELATIVE_PATH,
            )
            val out = mutableListOf<DownloadRecord>()
            context.contentResolver.query(
                collection, proj,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$relativePath%"),
                "${MediaStore.Downloads.DISPLAY_NAME} ASC",
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeI = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val mimeI = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val dateI = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                val relI = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idI))
                    out.add(DownloadRecord(
                        name = c.getString(nameI) ?: "",
                        sourcePath = "",
                        size = c.getLong(sizeI),
                        mimeType = c.getString(mimeI) ?: "",
                        downloadedAt = c.getLong(dateI) * 1000,
                        localUri = uri.toString(),
                        relativePath = c.getString(relI) ?: relativePath,
                    ))
                }
            }
            return out
        } else {
            @Suppress("DEPRECATION")
            val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativePath.removePrefix("Download/"))
            if (!base.exists()) return emptyList()
            return base.walkTopDown().filter { it.isFile }.map { f ->
                DownloadRecord(
                    name = f.name, sourcePath = "", size = f.length(), mimeType = "",
                    downloadedAt = f.lastModified(), localUri = Uri.fromFile(f).toString(),
                    relativePath = relativePath,
                )
            }.toList()
        }
    }

    /** Dosyayı klasöre taşır. API 29'da RELATIVE_PATH güncellemesi IS_PENDING ister. */
    override fun moveIntoFolder(localUri: String, folderRelativePath: String): Boolean {
        return try {
            val uri = Uri.parse(localUri)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                val resolver = context.contentResolver
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 1) }, null, null)
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.RELATIVE_PATH, folderRelativePath)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null) > 0
            } else {
                val src = File(uri.path ?: return false)
                @Suppress("DEPRECATION")
                val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folderRelativePath.removePrefix("Download/"))
                destDir.mkdirs()
                src.renameTo(File(destDir, src.name))
            }
            if (ok) {
                // Kök listeden düşsün diye geçmiş kaydının relativePath'ini güncelle.
                saveHistory(history().map {
                    if (it.localUri == localUri && !it.folder) it.copy(relativePath = folderRelativePath) else it
                })
            }
            ok
        } catch (e: Exception) { false }
    }

    /** Kaydı ve diskteki karşılığını siler; klasörse içindeki tüm dosyalar silinir. */
    override fun deleteRecord(record: DownloadRecord): Boolean {
        return try {
            if (record.folder) {
                listFolder(record.relativePath).forEach { runCatching { deleteUri(Uri.parse(it.localUri)) } }
            } else if (record.localUri.isNotBlank()) {
                runCatching { deleteUri(Uri.parse(record.localUri)) }
            }
            saveHistory(history().filterNot {
                it.localUri == record.localUri && it.downloadedAt == record.downloadedAt && it.name == record.name
            })
            true
        } catch (e: Exception) { false }
    }

    override fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    companion object {
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 100
    }
}
