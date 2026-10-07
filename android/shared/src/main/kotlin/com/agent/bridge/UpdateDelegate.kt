package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// OTA güncelleme yönetimi — RemoteViewModel'den ayrılan delege (madde 11.5).
//
// Kapsam: checkForUpdate, dismissUpdate, downloadAndInstallUpdate. updateManager
// (UpdateManager, Android Context bağımlı) ve iki StateFlow (updateInfo /
// downloadProgress) delegeye geçilir — StateFlow'lar Android'siz olduğu için
// delege JUnit ile test edilebilir (updateManager arayüze çıkarma test maddesine 13).
//
// Davranış değişikliği yok. Kalıp önceki delegelerle aynı.
class UpdateDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val updateManager: UpdateManager,
    private val updateInfo: MutableStateFlow<UpdateInfo?>,
    private val downloadProgress: MutableStateFlow<Float?>,
) {
    fun checkForUpdate(showNoUpdateMessage: Boolean = true) = scope.launch {
        runCatching { updateManager.checkUpdate(state().settings) }
            .onSuccess { info ->
                updateInfo.value = info
                if (info == null && showNoUpdateMessage) emit("Güncel sürümdesiniz")
            }
            // Silent auto-check (showNoUpdateMessage=false) shouldn't blast a toast on cold-start
            // timeout — user explicitly triggered checks still surface errors.
            .onFailure { if (showNoUpdateMessage) reportError("Güncelleme kontrolü başarısız", it) }
    }

    fun dismissUpdate() {
        updateInfo.value = null
    }

    fun downloadAndInstallUpdate() = scope.launch {
        val info = updateInfo.value ?: return@launch
        downloadProgress.value = 0f
        runCatching {
            val file = updateManager.downloadApk(state().settings, info.apkUrl, info.sha256) { progress ->
                downloadProgress.value = progress
            }
            updateManager.installApk(file)
        }.onSuccess { installerStarted ->
            downloadProgress.value = null
            if (installerStarted) updateInfo.value = null else emit("Bilinmeyen kaynaklardan yüklemeye izin verin, sonra tekrar deneyin")
        }.onFailure {
            downloadProgress.value = null
            reportError("Güncelleme indirilemedi", it)
        }
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
