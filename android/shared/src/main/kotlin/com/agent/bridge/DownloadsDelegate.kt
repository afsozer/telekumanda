package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// İndirilenler yönetimi — RemoteViewModel'den ayrılan delege (madde 11.4).
//
// Kapsam: downloadByPath, clearDownloadHistory, openDownloadFolder,
// moveDownloadIntoFolder ve deleteDownloads burada kalır; geçmiş yenileme
// ViewModel'in loadDownloads aksiyonundan yapılır.
// (UI state toggle). downloadRepo (DownloadRepo) Android Context bağımlıdır;
// delege somut sınıfı alır — arayüze çıkarma test maddesine (13) bırakılır.
//
// Davranış değişikliği yok. Kalıp önceki delegelerle aynı: BridgeClient +
// CoroutineScope + state okuyucu/updater + mesaj callback'i.
class DownloadsDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val downloadRepo: DownloadRepo,
) {
    fun downloadByPath(sourcePath: String, displayName: String, subDir: String = "") = scope.launch {
        update { it.copy(files = it.files.copy(activeDownloadName = displayName, activeDownloadProgress = 0f)) }
        runCatching {
            downloadRepo.download(client, state().settings, sourcePath, displayName, subDir) { progress ->
                update { it.copy(files = it.files.copy(activeDownloadProgress = progress)) }
            }
        }.onSuccess {
            update { it.copy(files = it.files.copy(activeDownloadName = null, activeDownloadProgress = 0f, downloadRecords = downloadRepo.history())) }
            emit("İndirildi: ${it.name}")
        }.onFailure {
            update { it.copy(files = it.files.copy(activeDownloadName = null, activeDownloadProgress = 0f)) }
            reportError("İndirme başarısız", it)
        }
    }

    fun clearDownloadHistory() {
        downloadRepo.clearHistory()
        update { it.copy(files = it.files.copy(downloadRecords = emptyList())) }
    }

    fun openDownloadFolder(record: DownloadRecord?) = scope.launch {
        if (record == null) {
            update { it.copy(files = it.files.copy(openDownloadFolder = null, downloadFolderEntries = emptyList())) }
            return@launch
        }
        val entries = withContext(Dispatchers.IO) { downloadRepo.listFolder(record.relativePath) }
        update { it.copy(files = it.files.copy(openDownloadFolder = record, downloadFolderEntries = entries)) }
    }

    fun moveDownloadIntoFolder(localUri: String, folder: DownloadRecord) = scope.launch {
        val ok = withContext(Dispatchers.IO) { downloadRepo.moveIntoFolder(localUri, folder.relativePath) }
        if (ok) {
            emit("${folder.name} içine taşındı")
            update { it.copy(files = it.files.copy(downloadRecords = downloadRepo.history())) }
        } else emit("Taşıma başarısız")
    }

    fun deleteDownloads(records: List<DownloadRecord>) = scope.launch {
        val n = withContext(Dispatchers.IO) { records.count { downloadRepo.deleteRecord(it) } }
        emit(if (n > 0) "$n öğe silindi" else "Silme başarısız")
        val folder = state().openDownloadFolder
        val entries = if (folder != null) withContext(Dispatchers.IO) { downloadRepo.listFolder(folder.relativePath) } else emptyList()
        update { it.copy(files = it.files.copy(downloadRecords = downloadRepo.history(), downloadFolderEntries = entries)) }
    }

    /**
     * Arşiv → telefon ZIP → extract → klasör olarak indir (Phase 6).
     * Bridge `/{projectId}/outputs` klasörünü zip'ler; Android zip'i geçici olarak
     * indirir, `Download/AgentBridge/<displayName>-teslimatlar/` altına açar ve
     * hem telefondaki geçici zip'i hem de bridge/tmp'deki arşivi temizler.
     * Ne telefonda ne bridge'de kalıcı .zip kalır (başarı ve hata yollarında).
     */
    fun downloadProjectOutputsFolder(projectId: String) = scope.launch {
        emit("Teslimatlar arşivleniyor…")
        runCatching { client.archiveProjectOutputs(state().settings, projectId) }
            .onSuccess { archive ->
                val folderName = archive.name.removeSuffix(".zip")
                update { it.copy(files = it.files.copy(activeDownloadName = folderName, activeDownloadProgress = 0f)) }
                try {
                    runCatching {
                        downloadRepo.downloadAndExtract(client, state().settings, archive.path, folderName) { p ->
                            update { it.copy(files = it.files.copy(activeDownloadProgress = p)) }
                        }
                    }.onSuccess { rec ->
                        update { it.copy(files = it.files.copy(activeDownloadName = null, activeDownloadProgress = 0f, downloadRecords = downloadRepo.history())) }
                        emit("Teslimatlar klasör olarak indirildi: ${rec.name}")
                    }.onFailure {
                        update { it.copy(files = it.files.copy(activeDownloadName = null, activeDownloadProgress = 0f)) }
                        reportError("İndirme başarısız", it)
                    }
                } finally {
                    // Başarı/başarısızlık fark etmeksizin bridge geçici zip'ini sil.
                    runCatching { client.cleanupProjectOutputsArchive(state().settings, archive.path) }
                }
            }
            .onFailure { reportError("Arşiv oluşturulamadı", it) }
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
