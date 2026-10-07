package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CoworkFileEditDelegateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class MemoryStore : OpenEditStore {
        val records = mutableMapOf<String, OpenEditRecord>()
        override fun put(record: OpenEditRecord) { records[record.remotePath] = record }
        override fun all(): List<OpenEditRecord> = records.values.toList()
        override fun remove(remotePath: String) { records.remove(remotePath) }
    }

    private class Harness {
        val store = MemoryStore()
        val emitted = mutableListOf<String>()
        val opened = mutableListOf<OpenForEditRequest>()
        val savedPaths = mutableListOf<Triple<String, ByteArray, Boolean>>()
        var downloadCount = 0
        var downloadBytes: ByteArray = "içerik".toByteArray()
        var downloadError: Exception? = null
        var saveResult: (String) -> UploadResult = { p -> UploadResult(true, p.substringAfterLast('\\').substringAfterLast('/'), p, "") }
        var saveError: Exception? = null
        var saveHook: ((String, ByteArray) -> Unit)? = null
        var nowMs: Long = 1_000_000L
    }

    private fun delegate(h: Harness, editRoot: File): CoworkFileEditDelegate = CoworkFileEditDelegate(
        scope = CoroutineScope(Dispatchers.Unconfined),
        emit = { h.emitted.add(it) },
        editRoot = editRoot,
        store = h.store,
        download = { h.downloadCount++; h.downloadError?.let { e -> throw e }; h.downloadBytes },
        save = { path, bytes, coworkOnly ->
            h.saveError?.let { e -> throw e }
            h.savedPaths.add(Triple(path, bytes, coworkOnly))
            h.saveHook?.invoke(path, bytes)
            h.saveResult(path)
        },
        requestOpen = { h.opened.add(it) },
        now = { h.nowMs },
    )

    private fun entry(name: String = "not defteri.txt", path: String = "C:\\Users\\x\\CoworkSpaces\\örnek çalışma\\not defteri.txt", size: Long = 6) =
        DirEntry(name = name, path = path, type = "file", size = size)

    @Test
    fun openForEditDownloadsWritesLocalCopyAndTracks() = runBlocking {
        val h = Harness()
        val root = tmp.newFolder("openedit")
        delegate(h, root).openForEdit(entry()).join()

        val record = h.store.records.values.single()
        val local = File(record.localPath)
        assertTrue(local.exists())
        assertEquals("not defteri.txt", local.name)
        assertEquals("içerik", local.readText())
        assertEquals(local.lastModified(), record.baselineMtime)
        assertEquals(local.length(), record.baselineSize)
        assertTrue(record.baselineHash.isNotBlank())
        assertEquals(1, h.opened.size)
        assertEquals(local.absolutePath, h.opened[0].localPath)
    }

    @Test
    fun openForEditRejectsOversizedFile() = runBlocking {
        val h = Harness()
        delegate(h, tmp.newFolder()).openForEdit(entry(size = 26L * 1024 * 1024)).join()
        assertTrue(h.store.records.isEmpty())
        assertTrue(h.opened.isEmpty())
        assertTrue(h.emitted.single().contains("çok büyük"))
    }

    @Test
    fun openForEditReportsDownloadFailure() = runBlocking {
        val h = Harness().apply { downloadError = IOException("bağlantı koptu") }
        delegate(h, tmp.newFolder()).openForEdit(entry()).join()
        assertTrue(h.store.records.isEmpty())
        assertTrue(h.emitted.single().startsWith("Dosya indirilemedi"))
    }

    @Test
    fun syncBackUploadsOnlyChangedAndUpdatesBaseline() = runBlocking {
        val h = Harness()
        val root = tmp.newFolder()
        val d = delegate(h, root)
        d.openForEdit(entry()).join()

        // Değişiklik yokken sync hiçbir şey yüklemez.
        d.syncBack().join()
        assertTrue(h.savedPaths.isEmpty())

        // Yerel kopya düzenlenir (boyut değişir → baseline sapması garantili).
        val record = h.store.records.values.single()
        File(record.localPath).writeText("düzenlenmiş içerik")
        d.syncBack().join()

        assertEquals(1, h.savedPaths.size)
        assertEquals(record.remotePath, h.savedPaths[0].first)
        assertEquals("düzenlenmiş içerik", String(h.savedPaths[0].second))
        assertTrue(h.savedPaths[0].third)
        assertTrue(h.emitted.any { it.startsWith("PC'ye kaydedildi") })

        // Baseline güncellendi → ikinci sync tekrar yüklemez.
        d.syncBack().join()
        assertEquals(1, h.savedPaths.size)
    }

    @Test
    fun syncBackKeepsRecordOnNetworkFailure() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry()).join()
        val record = h.store.records.values.single()
        File(record.localPath).writeText("değişti ama köprü kapalı")

        h.saveError = IOException("Failed to connect")
        d.syncBack().join()

        assertNotNull(h.store.records[record.remotePath])
        assertTrue(h.emitted.any { it.contains("tekrar denenecek") })

        // Köprü dönünce sonraki sync başarır.
        h.saveError = null
        d.syncBack().join()
        assertEquals(1, h.savedPaths.size)
        assertTrue(h.emitted.any { it.startsWith("PC'ye kaydedildi") })
    }

    @Test
    fun syncBackDropsRecordWhenFileMissingOnPc() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry()).join()
        val record = h.store.records.values.single()
        val localDir = File(record.localPath).parentFile
        File(record.localPath).writeText("değişti")

        h.saveResult = { p -> UploadResult(false, "", p, "dosya bulunamadı: $p") }
        d.syncBack().join()

        assertNull(h.store.records[record.remotePath])
        assertFalse(localDir!!.exists())
        assertTrue(h.emitted.any { it.contains("PC'de bulunamadı") })
    }

    @Test
    fun syncBackCleansStaleUnchangedRecords() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry()).join()
        val record = h.store.records.values.single()
        val localDir = File(record.localPath).parentFile

        // 24 saatten yeni: durur.
        h.nowMs += CoworkFileEditDelegate.STALE_TTL_MS - 1000
        d.syncBack().join()
        assertNotNull(h.store.records[record.remotePath])

        // 24 saati aşan değişmemiş kayıt: yerel kopya + kayıt temizlenir.
        h.nowMs += 2000
        d.syncBack().join()
        assertNull(h.store.records[record.remotePath])
        assertFalse(localDir!!.exists())
        assertTrue(h.savedPaths.isEmpty())
    }

    @Test
    fun syncBackDropsRecordWhenLocalCopyDeleted() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry()).join()
        val record = h.store.records.values.single()
        File(record.localPath).delete()

        d.syncBack().join()
        assertNull(h.store.records[record.remotePath])
        assertTrue(h.savedPaths.isEmpty())
    }

    @Test
    fun reopenKeepsDirtyLocalCopyWhenSyncFails() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry()).join()
        val record = h.store.records.values.single()
        File(record.localPath).writeText("telefonda kaydedilmemiş düzenleme")
        h.downloadBytes = "PC sürümü".toByteArray()
        h.saveError = IOException("bridge kapalı")

        d.openForEdit(entry()).join()

        assertEquals("telefonda kaydedilmemiş düzenleme", File(record.localPath).readText())
        assertEquals(1, h.downloadCount)
        assertEquals(1, h.opened.size)
        assertTrue(h.emitted.any { it.contains("yerel düzenleme korunuyor") })
    }

    @Test
    fun syncBackRetriesWhenFileChangesDuringUpload() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry()).join()
        val record = h.store.records.values.single()
        val local = File(record.localPath)
        local.writeText("birinci düzenleme")
        var changedDuringSave = false
        h.saveHook = { _, _ ->
            if (!changedDuringSave) {
                changedDuringSave = true
                local.writeText("ikinci düzenleme")
            }
        }

        d.syncBack().join()

        assertEquals(2, h.savedPaths.size)
        assertEquals("birinci düzenleme", String(h.savedPaths[0].second))
        assertEquals("ikinci düzenleme", String(h.savedPaths[1].second))
        d.syncBack().join()
        assertEquals(2, h.savedPaths.size)
    }

    @Test
    fun hashDetectsSameSizeChangeEvenWhenMtimeIsRestored() = runBlocking {
        val h = Harness().apply { downloadBytes = "AAAA".toByteArray() }
        val d = delegate(h, tmp.newFolder())
        d.openForEdit(entry(size = 4)).join()
        val record = h.store.records.values.single()
        val local = File(record.localPath)
        local.writeText("BBBB")
        assertTrue(local.setLastModified(record.baselineMtime))

        d.syncBack().join()

        assertEquals(1, h.savedPaths.size)
        assertEquals("BBBB", String(h.savedPaths.single().second))
    }

    @Test
    fun generalFileUsesGeneralSaveScope() = runBlocking {
        val h = Harness()
        val d = delegate(h, tmp.newFolder())
        d.openForEdit("C:\\Users\\x\\rapor.docx", coworkOnly = false).join()
        val record = h.store.records.values.single()
        assertFalse(record.coworkOnly)
        File(record.localPath).writeText("changed")

        d.syncBack().join()

        assertFalse(h.savedPaths.single().third)
    }

    @Test
    fun hashKeySeparatesSameNameAcrossWorkspaces() {
        val a = CoworkFileEditDelegate.hashKey("C:\\CoworkSpaces\\alan-a\\not.txt")
        val b = CoworkFileEditDelegate.hashKey("C:\\CoworkSpaces\\alan-b\\not.txt")
        assertTrue(a != b)
        assertEquals(12, a.length)
    }
}
