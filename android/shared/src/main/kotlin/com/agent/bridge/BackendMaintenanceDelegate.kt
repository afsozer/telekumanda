package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backend süreç yönetimi ve OpenCode yerel-model yaşam döngüsü.
 *
 * RemoteViewModel yalnızca UI'ya yönelik ince bir facade olarak kalır; bu sınıf
 * Android bağımlılığı olmadan test edilebilir durumda tutulur.
 */
class BackendMaintenanceDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val onRunPodCapacityError: suspend (String) -> Unit,
    // Uygulama öne gelene kadar askıda bekler; varsayılan hiç beklemez (testler).
    // Kapı ZORUNLU: bu döngü 28-29 Ağu gecesi ekrana bakmadan 45 sn'de bir
    // LTE'yi uyandırıp telefonu gecede %16 boşalttı. Isıtmanın arka planda
    // kimseye faydası yok — dönüşte warmOpenTabsNow zaten anında ateşleniyor.
    private val awaitForeground: suspend () -> Unit = {},
) {
    private var openTabsWarmLoop: Job? = null
    private var openTabsWarmSync: Job? = null
    private var runpodPollJob: Job? = null
    private var purgePollJob: Job? = null

    fun startOpenTabBackendWarmth() {
        if (openTabsWarmLoop?.isActive == true) return
        openTabsWarmLoop = scope.launch {
            while (isActive) {
                awaitForeground()
                syncOpenTabBackends()
                delay(OPEN_TAB_WARM_INTERVAL_MS)
            }
        }
    }

    fun warmOpenTabsNow() {
        openTabsWarmSync?.cancel()
        openTabsWarmSync = scope.launch { syncOpenTabBackends() }
    }

    private suspend fun syncOpenTabBackends() {
        val current = state()
        val targets = openTabWarmTargets(current)
        if (targets.isEmpty()) return
        // Bağlantı yoksa sessizce atlanır; sonraki periyodik tur yeniden dener.
        runCatching { client.warmOpenTabBackends(current.settings, targets) }
    }

    fun loadAllBackendSessionCounts() = scope.launch {
        val backends = listOf("agy", "claude-app", "codex-app", "opencode2-app", "omp", "cowork")
        backends.forEach { backend ->
            launch {
                runCatching { client.listBackendSessionsCount(state().settings, backend) }
                    .onSuccess { count -> update { current ->
                        current.copy(backendSessionCounts = current.backendSessionCounts + (backend to count))
                    } }
            }
            launch {
                runCatching { client.listProcesses(state().settings, backend) }
                    .onSuccess { result -> update { current ->
                        current.copy(backendProcessCounts = current.backendProcessCounts + (backend to result.count))
                    } }
            }
        }
    }

    fun loadProcesses() = scope.launch {
        val backend = state().backend ?: return@launch
        runCatching { client.listProcesses(state().settings, backend) }
            .onSuccess { result -> update { it.copy(harnessProcesses = result.processes, processCount = result.count) } }
        runCatching { client.listBackendSessionsCount(state().settings, backend) }
            .onSuccess { count -> update { it.copy(activeSessionCount = count) } }
    }

    fun killAllSessions(backend: String, refresh: () -> Unit) = scope.launch {
        runCatching { client.killAllSessions(state().settings, backend) }
            .onSuccess { result ->
                val parts = buildList {
                    if (result.killed.isNotEmpty()) add("${result.killed.size} process sonlandırıldı")
                    if (result.cleared > 0) add("${result.cleared} oturum kaydı temizlendi")
                }
                emit(when {
                    parts.isNotEmpty() -> "$backend: ${parts.joinToString(", ")}"
                    result.errors.isNotEmpty() -> "Process sonlandırma hatası: ${result.errors.size} adet"
                    else -> "Sonlandırılacak $backend process'i veya oturum kaydı yok"
                })
                refresh()
            }
            .onFailure { reportError("Process sonlandırılamadı", it) }
    }

    fun refreshRunPodStatus(showErrors: Boolean = true) = scope.launch {
        runCatching { client.opencodeRunPodStatus(state().settings) }
            .onSuccess(::applyRunPodStatus)
            .onFailure { if (showErrors) reportError("RunPod durumu alınamadı", it) }
    }

    fun syncRunPodStatusSilently() = scope.launch {
        runCatching { client.opencodeRunPodStatus(state().settings) }
            .onSuccess(::applyRunPodStatus)
    }

    fun startRunPod() = scope.launch {
        applyRunPodStatus(state().opencode.runpod.copy(
            ok = true,
            phase = "starting",
            ready = false,
            action = "start",
            step = "requested",
            message = "RunPod başlatılıyor…",
            operationActive = true,
        ))
        runCatching { client.opencodeRunPodStart(state().settings) }
            .onSuccess {
                applyRunPodStatus(it)
                pollRunPodUntilSettled("start")
            }
            .onFailure {
                val message = it.message ?: "RunPod başlatılamadı"
                applyRunPodStatus(state().opencode.runpod.copy(
                    ok = false,
                    phase = "error",
                    ready = false,
                    action = "",
                    message = message,
                    operationActive = false,
                ))
                emitRunPodStartFailure(message)
            }
    }

    fun stopRunPod() = scope.launch {
        applyRunPodStatus(state().opencode.runpod.copy(
            ok = true,
            phase = "stopping",
            action = "stop",
            step = "requested",
            message = "RunPod durduruluyor…",
            operationActive = true,
        ))
        runCatching { client.opencodeRunPodStop(state().settings) }
            .onSuccess {
                applyRunPodStatus(it)
                pollRunPodUntilSettled("stop")
            }
            .onFailure {
                val message = it.message ?: "RunPod durdurulamadı"
                applyRunPodStatus(state().opencode.runpod.copy(
                    ok = false,
                    phase = "error",
                    action = "",
                    message = message,
                    operationActive = false,
                ))
                emit(message)
            }
    }

    private fun pollRunPodUntilSettled(action: String) {
        runpodPollJob?.cancel()
        runpodPollJob = scope.launch {
            // Bridge start timeout is eight minutes. Keep polling long enough to
            // receive its terminal success/error state instead of racing it.
            repeat(420) {
                delay(1_500)
                val status = runCatching { client.opencodeRunPodStatus(state().settings) }.getOrNull() ?: return@repeat
                applyRunPodStatus(status)
                val settled = !status.operationActive && status.phase !in setOf("starting", "stopping", "unknown")
                if (settled) {
                    when {
                        status.phase == "ready" && action == "start" -> emit("RunPod hazır")
                        status.phase == "stopped" && action == "stop" -> emit("RunPod durduruldu")
                        action == "start" -> emitRunPodStartFailure(
                            status.message.takeUnless { it.isBlank() || it == "RunPod kapalı" } ?: "RunPod başlatılamadı",
                        )
                        else -> emit(status.message.ifBlank { "RunPod durdurulamadı" })
                    }
                    return@launch
                }
            }
            applyRunPodStatus(state().opencode.runpod.copy(
                ok = false,
                phase = "error",
                action = "",
                message = "RunPod işlemi zaman aşımına uğradı",
                operationActive = false,
            ))
        }
    }

    private fun applyRunPodStatus(status: RunPodStatus) {
        update { it.copy(opencode = it.opencode.copy(runpod = status)) }
    }

    // ── Silinmis oturum kalintilarinin imhasi ────────────────────────────
    // Kopru tarafi 300 MB tarayip JSON ozet donuyor; tarama pahali oldugu
    // icin koprude 60 sn onbellekli. [refresh] kullanicinin "Tara" tusu.

    fun loadPurgeStatus(refresh: Boolean = false, showErrors: Boolean = true) = scope.launch {
        runCatching { client.opencodePurgeStatus(state().settings, refresh) }
            .onSuccess(::applyPurgeStatus)
            .onFailure { if (showErrors) reportError("Kalıntı durumu alınamadı", it) }
    }

    fun runPurge() = scope.launch {
        applyPurgeStatus(state().opencode.purge.copy(
            ok = true,
            running = true,
            message = "İmha başlatılıyor…",
        ))
        runCatching { client.opencodePurgeRun(state().settings) }
            .onSuccess {
                applyPurgeStatus(it)
                pollPurgeUntilSettled()
            }
            .onFailure {
                val message = it.message ?: "İmha başlatılamadı"
                applyPurgeStatus(state().opencode.purge.copy(
                    ok = false, running = false, message = message,
                ))
                emit(message)
            }
    }

    private fun pollPurgeUntilSettled() {
        purgePollJob?.cancel()
        purgePollJob = scope.launch {
            // Kopru tarafindaki tavan 10 dk; yoklama onu gecmeli ki son
            // durumu (temiz/kalinti) gercekten gorelim.
            repeat(260) {
                delay(2_500)
                val status = runCatching { client.opencodePurgeStatus(state().settings) }.getOrNull()
                    ?: return@repeat
                applyPurgeStatus(status)
                if (!status.running) {
                    emit(status.message.ifBlank { "İmha bitti" })
                    return@launch
                }
            }
            applyPurgeStatus(state().opencode.purge.copy(
                ok = false, running = false, message = "İmha zaman aşımına uğradı",
            ))
        }
    }

    private fun applyPurgeStatus(status: SessionPurgeStatus) {
        update { it.copy(opencode = it.opencode.copy(purge = status)) }
    }

    private suspend fun emitRunPodStartFailure(message: String) {
        emit(message)
        if (isRunPodGpuCapacityError(message)) onRunPodCapacityError(message)
    }
}

// Acik sekmelerin backend'lerini isitma araligi.
//
// Sinif disinda: sekme aynasi da ayni ritimde kosuyor (RemoteViewModel). Iki ayri
// sayi yazilsaydi cihaz iki ayri pencerede uyanirdi; ayni sayiyi paylasmak
// uyanmayi tek pencerede tutuyor.
const val OPEN_TAB_WARM_INTERVAL_MS = 45_000L

fun isRunPodGpuCapacityError(message: String): Boolean {
    val normalized = message.lowercase()
    return "not enough free gpu" in normalized ||
        ("gpu" in normalized &&
            ("yeterli boş" in normalized || "yeterli bos" in normalized) &&
            ("yok" in normalized || "bulunam" in normalized))
}

data class BackendWarmTarget(
    val backend: String,
    val sessionId: String = "",
    val cwd: String = "",
)

private val WARMABLE_OPEN_TAB_BACKENDS = setOf("codex-app", "opencode2-app")

fun openTabWarmTargets(state: RemoteUiState): List<BackendWarmTarget> =
    state.visibleTabs
        .asSequence()
        .map {
            val backend = if (it.backend == "cowork") it.provider else it.backend
            val cwd = state.tabStatuses[it.id]?.cwd.orEmpty().ifBlank {
                if (it.id == state.activeTabId) state.backendSession(it.backend).cwd else ""
            }
            BackendWarmTarget(
                backend = backend,
                sessionId = it.sessionId,
                cwd = cwd,
            )
        }
        .filter { it.backend in WARMABLE_OPEN_TAB_BACKENDS }
        // sessionId boş olsa da backend hedefi korunur: sağlayıcı seçilmiş açık
        // sekme, ilk mesaj yazılmadan önce de ortak süreci sıcak tutmalıdır.
        .distinctBy { it.backend to it.sessionId }
        .toList()
