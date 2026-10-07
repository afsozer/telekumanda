package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Landing, operasyon ve proje veri yükleme/yazma işlemleri. */
class HubDataDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
    private val reportError: suspend (String, Throwable) -> Unit,
    private val onProjectDeletedCompletely: (CompleteProjectDeleteResult) -> Unit,
) {
    fun loadUsage(force: Boolean = false) = scope.launch {
        update { it.copy(usageLoading = true) }
        runCatching { client.usage(state().settings, force) }
            .onSuccess { result -> update { it.copy(usage = result, usageLoading = false) } }
            .onFailure { update { it.copy(usageLoading = false) }; reportError("Kullanım bilgisi yüklenemedi", it) }
    }
    fun loadBackendCatalog() = scope.launch { runCatching { client.backendCatalog(state().settings) }.onSuccess { catalog -> if (catalog.loaded) update { it.copy(backendCatalog = catalog) } } }
    fun loadOperations() = scope.launch {
        update { it.copy(operationsLoading = true) }
        runCatching { client.operations(state().settings) }.onSuccess { result -> update { it.copy(operations = result, operationsLoading = false, operationsLoaded = true) } }.onFailure { update { it.copy(operationsLoading = false) } }
    }
    fun loadProjects() = scope.launch {
        update { it.copy(projectsLoading = true) }
        runCatching { client.projects(state().settings) }.onSuccess { projects -> update { it.copy(projectSummaries = projects, projectsLoading = false) } }.onFailure { update { it.copy(projectsLoading = false) } }
    }
    fun loadProjectDetail(id: String) = scope.launch {
        update { it.copy(projectDetailLoading = true, selectedProjectDetail = null) }
        runCatching { client.projectDetail(state().settings, id) }.onSuccess { detail ->
            update { it.copy(selectedProjectDetail = detail, projectDetailLoading = false) }
            scope.launch { runCatching { client.markProjectOutputsSeen(state().settings, id) }; loadProjects() }
        }.onFailure { update { it.copy(projectDetailLoading = false) }; reportError("Proje yüklenemedi", it) }
    }
    fun setProjectLabel(id: String, label: String, path: String? = null) = scope.launch {
        runCatching { client.setProjectLabel(state().settings, id, label, path) }.onSuccess {
            loadProjects()
            if (id.isNotBlank()) loadProjectDetail(id)
        }.onFailure { reportError("Proje adı kaydedilemedi", it) }
    }
    fun applyProjectSecurity(id: String, profile: String, permissions: Map<String, String>, readRoot: String, writeRoot: String) = scope.launch {
        val policy = if (profile == "custom") JSONObject().put("permissions", JSONObject(permissions)).put("readRoots", JSONArray().put(readRoot)).put("writeRoots", JSONArray().put(writeRoot)) else null
        runCatching { client.applyProjectSecurity(state().settings, id, profile, policy) }.onSuccess { loadProjects(); loadProjectDetail(id) }.onFailure { reportError("Güvenlik profili uygulanamadı", it) }
    }
    fun loadProjectMcpServers(id: String, provider: String) = scope.launch {
        update { it.copy(projectMcpLoading = true, projectMcpServers = emptyList()) }
        runCatching { client.projectMcpServers(state().settings, id, provider) }.onSuccess { servers -> update { it.copy(projectMcpServers = servers, projectMcpLoading = false) } }.onFailure { update { it.copy(projectMcpLoading = false) }; reportError("Proje MCP listesi yüklenemedi", it) }
    }
    fun applyProjectMcpProfile(id: String, provider: String, names: Set<String>) = scope.launch {
        runCatching { client.applyProjectMcpProfile(state().settings, id, provider, names) }.onSuccess { loadProjectMcpServers(id, provider); loadProjectDetail(id) }.onFailure { reportError("Proje MCP profili uygulanamadı", it) }
    }
    fun deleteProject(id: String) = scope.launch {
        runCatching { client.deleteProject(state().settings, id) }.onSuccess { count ->
            update { it.copy(selectedProjectDetail = null) }; emit(if (count > 0) "Proje ve $count oturum geçmişi silindi" else "Proje silindi"); loadProjects()
        }.onFailure { reportError("Proje silinemedi", it) }
    }
    fun deleteProjectCompletely(id: String) = scope.launch {
        runCatching { client.deleteProjectCompletely(state().settings, id) }.onSuccess { result ->
            if (result.ok) {
                update { it.copy(selectedProjectDetail = null) }
                onProjectDeletedCompletely(result)
            }
            emit(
                if (result.ok) {
                    val parts = mutableListOf<String>()
                    if (result.deletedSessionCount > 0) parts.add("${result.deletedSessionCount} oturum")
                    if (result.folderDeleted) parts.add("proje klasörü")
                    if (result.registryDeleted) parts.add("uygulama kayıtları")
                    "${parts.joinToString(", ")} tamamen silindi"
                } else {
                    result.error.ifBlank { "Proje tamamen silinemedi" }
                }
            )
            loadProjects()
            // Store the result for UI display even on partial success
            update { it.copy(projectDelete = it.projectDelete.copy(result = result)) }
        }.onFailure { e ->
            update { it.copy(projectDelete = it.projectDelete.copy(
                result = CompleteProjectDeleteResult(
                    ok = false, path = "", projectId = id, error = e.message ?: "Bilinmeyen hata",
                    retryable = true
                )
            )) }
            reportError("Proje tamamen silinemedi", e)
        }
    }
    fun refreshActiveState() = scope.launch {
        update { it.copy(activeStateLoading = true) }
        runCatching { client.activeState(state().settings) }.onSuccess { result -> update { it.copy(activeState = result, activeStateLoading = false) } }.onFailure { update { it.copy(activeStateLoading = false) } }
    }
    fun loadWorkerDirs(root: String, liteOnly: Boolean = false) = scope.launch {
        runCatching { client.workerDirs(state().settings, root, liteOnly = liteOnly) }
            .onSuccess { dirs ->
                update {
                    it.copy(
                        workerDirs = dirs,
                        liteWorkspaceRoot = if (liteOnly && it.liteWorkspaceRoot.isBlank()) dirs.base else it.liteWorkspaceRoot,
                    )
                }
            }
            .onFailure { reportError("Klasörler yüklenemedi", it) }
    }
    fun searchWorkerDirs(root: String, query: String, liteOnly: Boolean = false) = scope.launch { runCatching { client.searchDirs(state().settings, root, query, liteOnly = liteOnly) }.onSuccess { results -> update { it.copy(folderSearchResults = results) } }.onFailure { reportError("Klasör araması başarısız", it) } }

    fun applyProjectPreferences(
        id: String,
        pinned: Boolean? = null,
        touch: Boolean? = null,
        quickStartProvider: String? = null,
        quickStartModel: String? = null,
        quickStartPermissionMode: String? = null,
        quickStartEffort: String? = null,
        path: String? = null,
    ) = scope.launch {
        runCatching {
            client.applyProjectPreferences(
                state().settings, id, pinned, touch,
                quickStartProvider, quickStartModel,
                quickStartPermissionMode, quickStartEffort, path
            )
        }.onSuccess { loadProjects() }
         .onFailure { reportError("Tercihler kaydedilemedi", it) }
    }

    fun updateProjectsFilter(filter: ProjectFilter) {
        update { it.copy(projectsFilter = filter) }
    }

    fun updateProjectsSearchQuery(query: String) {
        update { it.copy(projectsSearchQuery = query) }
    }
}
