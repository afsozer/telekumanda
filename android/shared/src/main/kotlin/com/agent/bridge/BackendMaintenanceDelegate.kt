package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backend süreç yönetimi ve açık sekme ısıtması.
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
    // Uygulama öne gelene kadar askıda bekler; varsayılan hiç beklemez (testler).
    // Kapı ZORUNLU: bu döngü 28-29 Ağu gecesi ekrana bakmadan 45 sn'de bir
    // LTE'yi uyandırıp telefonu gecede %16 boşalttı. Isıtmanın arka planda
    // kimseye faydası yok — dönüşte warmOpenTabsNow zaten anında ateşleniyor.
    private val awaitForeground: suspend () -> Unit = {},
) {
    private var openTabsWarmLoop: Job? = null
    private var openTabsWarmSync: Job? = null

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
        val backends = listOf("agy", "claude-app", "codex-app", "omp", "cowork")
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
}

// Acik sekmelerin backend'lerini isitma araligi.
//
// Sinif disinda: sekme aynasi da ayni ritimde kosuyor (RemoteViewModel). Iki ayri
// sayi yazilsaydi cihaz iki ayri pencerede uyanirdi; ayni sayiyi paylasmak
// uyanmayi tek pencerede tutuyor.
const val OPEN_TAB_WARM_INTERVAL_MS = 45_000L

data class BackendWarmTarget(
    val backend: String,
    val sessionId: String = "",
    val cwd: String = "",
)

private val WARMABLE_OPEN_TAB_BACKENDS = setOf("codex-app")

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
