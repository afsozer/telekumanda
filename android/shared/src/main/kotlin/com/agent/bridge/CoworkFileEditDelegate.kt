package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// Cowork dosya gezgini "doğrudan aç + PC'ye geri yaz" delegesi.
//
// openForEdit: PC'deki dosyayı uygulama-özel klasöre indirir (gerçek ad korunur,
// klasör anahtarı remotePath hash'i — farklı alanlardaki aynı adlar çakışmaz),
// baseline (mtime/size) kaydını OpenEditStore'a yazar ve UI'ya aç isteği yayınlar
// (Intent/MIME çözümü UI tarafında; delege Android framework'e bağımlı değil).
//
// syncBack: her uygulama öne gelişinde (ON_START → onAppForeground) çağrılır.
// Baseline'dan sapan yerel kopyalar PC'deki orijinalin ÜZERİNE yazılır
// (/cowork/savefile). Çakışmada son-yazan-kazanır: ajan/kullanıcı PC kopyasını
// bu arada değiştirdiyse telefonun sürümü ezer — kabul edilmiş politika.
// Dosyayı kopyalayarak açan uygulamalar (ör. Google Dokümanlar) yerel kopyaya
// yazmaz; sync değişiklik görmez ve sessizce geçer.
//
// download/save enjekte edilir (BridgeClient extension'ları newCall kullanır,
// FakeBridgeClient ile taklit edilemez) — düz JUnit testi için fonksiyon tipi.
class CoworkFileEditDelegate(
    private val scope: CoroutineScope,
    private val emit: suspend (String) -> Unit,
    private val editRoot: File,
    private val store: OpenEditStore,
    private val download: suspend (remotePath: String) -> ByteArray,
    private val save: suspend (remotePath: String, bytes: ByteArray, coworkOnly: Boolean) -> UploadResult,
    private val requestOpen: (OpenForEditRequest) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // ON_START art arda gelebilir; aynı kayıt için iki upload/baseline güncellemesi
    // birbirinin üzerine binmesin. Dosya açma da aynı kilidi kullanır ki başarısız
    // senkronizasyondan kalan yerel düzenleme yeniden indirmeyle ezilmesin.
    private val operationMutex = Mutex()

    fun openForEdit(entry: DirEntry, coworkOnly: Boolean = true): Job =
        openForEdit(entry.path, entry.name, entry.size, coworkOnly)

    fun openForEdit(
        remotePath: String,
        name: String = remotePath.substringAfterLast('/').substringAfterLast('\\'),
        size: Long = -1,
        coworkOnly: Boolean = false,
    ): Job = scope.launch {
        operationMutex.withLock {
            if (size > MAX_EDIT_BYTES) {
                emit("Dosya düzenleme için çok büyük (sınır 25MB): $name")
                return@withLock
            }

            // Önceki açılıştan PC'ye gönderilememiş bir kopya varsa onu koru.
            // Başarılı senkronizasyon olmadan PC sürümünü yeniden indirip ezme.
            val existing = store.all().firstOrNull { it.remotePath == remotePath }
            if (existing != null && localChanged(existing) && !syncRecord(existing)) {
                emit("Kaydedilmemiş yerel düzenleme korunuyor; $name yeniden açılmadı")
                return@withLock
            }

            // download IO'ya sabitlenir: gövde okuması çağıranın context'inde koşarsa
            // ana thread'e düşer → NetworkOnMainThreadException (yaşandı, 10.94).
            val bytes = runCatching { withContext(Dispatchers.IO) { download(remotePath) } }
                .getOrElse { e ->
                    emit("Dosya indirilemedi: $name — ${e.message ?: e.javaClass.simpleName}")
                    return@withLock
                }
            // Liste ile indirme arasında dosya büyümüş olabilir; gerçek gövdeyi de sınırla.
            if (bytes.size > MAX_EDIT_BYTES) {
                emit("Dosya düzenleme için çok büyük (sınır 25MB): $name")
                return@withLock
            }
            val local = withContext(Dispatchers.IO) {
                val dir = File(editRoot, hashKey(remotePath))
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(bytes)
                f
            }
            store.put(OpenEditRecord(
                remotePath = remotePath,
                localPath = local.absolutePath,
                baselineMtime = local.lastModified(),
                baselineSize = local.length(),
                baselineHash = hashBytes(bytes),
                openedAt = now(),
                coworkOnly = coworkOnly,
            ))
            requestOpen(OpenForEditRequest(localPath = local.absolutePath, name = name))
        }
    }

    // Idempotent; her foreground'da çağrılabilir. Değişen kopyaları geri yazar,
    // bayat (değişmemiş + 24 saatten eski) kopyaları temizler.
    fun syncBack(): Job = scope.launch {
        operationMutex.withLock {
            for (record in store.all()) {
                val local = File(record.localPath)
                if (!local.exists()) { store.remove(record.remotePath); continue }
                if (localChanged(record)) {
                    syncRecord(record)
                } else if (now() - record.openedAt > STALE_TTL_MS) {
                    cleanupLocal(record)
                }
            }
        }
    }

    // Yüklenen snapshot ile baseline'ın aynı olmasını garanti eder. Harici editör
    // upload sürerken dosyayı yeniden değiştirirse en fazla üç kez yeni snapshot'ı
    // yollar; hâlâ değişiyorsa eski baseline korunur ve sonraki foreground tekrarlar.
    private suspend fun syncRecord(initial: OpenEditRecord): Boolean {
        val local = File(initial.localPath)
        if (!local.exists()) { store.remove(initial.remotePath); return false }

        repeat(MAX_SYNC_PASSES) { pass ->
            val snapshot = readStableSnapshot(local)
            if (snapshot == null) {
                emit("Yerel kopya okunamadı: ${initial.remoteName()}")
                return false
            }
            if (snapshot.matches(initial)) {
                // Eski kayıtları hash alanına geçir ve yalnız metadata değişimini yut.
                store.put(initial.copy(
                    baselineMtime = snapshot.mtime,
                    baselineSize = snapshot.size,
                    baselineHash = snapshot.hash,
                ))
                return true
            }

            val result = runCatching { save(initial.remotePath, snapshot.bytes, initial.coworkOnly) }.getOrNull()
            if (result == null) {
                emit("PC'ye kaydedilemedi: ${initial.remoteName()} — bağlantı yok, tekrar denenecek")
                return false
            }
            when {
                result.ok -> {
                    val after = readStableSnapshot(local)
                    if (after != null && after.hash == snapshot.hash) {
                        store.put(initial.copy(
                            baselineMtime = snapshot.mtime,
                            baselineSize = snapshot.size,
                            baselineHash = snapshot.hash,
                        ))
                        emit("PC'ye kaydedildi: ${result.name}")
                        return true
                    }
                    if (pass == MAX_SYNC_PASSES - 1) {
                        emit("PC'ye kaydedildi ancak yeni yerel değişiklik bekliyor: ${initial.remoteName()}")
                    }
                }
                result.error.contains("bulunamadı") -> {
                    emit("Dosya PC'de bulunamadı: ${initial.remoteName()} — PC'de silinmiş olabilir")
                    cleanupLocal(initial)
                    return false
                }
                else -> {
                    emit("PC'ye kaydedilemedi: ${initial.remoteName()} — ${result.error}")
                    return false
                }
            }
        }
        return false
    }

    private suspend fun localChanged(record: OpenEditRecord): Boolean {
        val local = File(record.localPath)
        if (!local.exists()) return false
        val snapshot = readStableSnapshot(local) ?: return true
        return !snapshot.matches(record)
    }

    private suspend fun readStableSnapshot(local: File): LocalSnapshot? = withContext(Dispatchers.IO) {
        repeat(SNAPSHOT_READ_PASSES) {
            val beforeMtime = local.lastModified()
            val beforeSize = local.length()
            val bytes = runCatching { local.readBytes() }.getOrNull() ?: return@withContext null
            val afterMtime = local.lastModified()
            val afterSize = local.length()
            if (beforeMtime == afterMtime && beforeSize == afterSize && bytes.size.toLong() == afterSize) {
                return@withContext LocalSnapshot(bytes, afterMtime, afterSize, hashBytes(bytes))
            }
        }
        null
    }

    private data class LocalSnapshot(
        val bytes: ByteArray,
        val mtime: Long,
        val size: Long,
        val hash: String,
    ) {
        fun matches(record: OpenEditRecord): Boolean =
            if (record.baselineHash.isNotBlank()) hash == record.baselineHash
            else mtime == record.baselineMtime && size == record.baselineSize
    }

    private fun hashBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun cleanupLocal(record: OpenEditRecord) {
        withContext(Dispatchers.IO) {
            runCatching { File(record.localPath).parentFile?.deleteRecursively() }
        }
        store.remove(record.remotePath)
    }

    companion object {
        const val MAX_EDIT_BYTES = 25L * 1024 * 1024
        const val STALE_TTL_MS = 24L * 60 * 60 * 1000
        private const val MAX_SYNC_PASSES = 3
        private const val SNAPSHOT_READ_PASSES = 3

        // remotePath → kısa hex anahtar (yerel alt klasör adı).
        fun hashKey(remotePath: String): String =
            MessageDigest.getInstance("SHA-1").digest(remotePath.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
    }
}

data class OpenForEditRequest(val localPath: String, val name: String)

private fun OpenEditRecord.remoteName(): String =
    remotePath.substringAfterLast('/').substringAfterLast('\\')
