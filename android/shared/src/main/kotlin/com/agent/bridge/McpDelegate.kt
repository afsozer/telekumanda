package com.agent.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// MCP sunucu yönetimi — RemoteViewModel'den ayrılan delege (madde 11.2).
//
// Her backend KENDİ CLI'ının MCP config'ini yönetir; alakasız config gösterilmez.
// Hedefi mcpTargetFor belirler; null dönerse ChatScreen menüdeki "MCP Sunucuları"
// butonunu hiç çizmez.
//
// Davranış değişikliği yok: ViewModel'deki aynı fonksiyonlar aynı imzalarla buraya
// taşındı; ViewModel çağrıları bu delegeye yönlendirir. Delege Android framework'e
// bağımlı değil (BridgeClient + CoroutineScope + state okuyucu/updater + mesaj
// callback'i constructor'dan alınır) — düz JUnit ile test edilebilir.
//
// Kalıp (maddenin başındaki yönergeye göre): delege constructor'dan BridgeClient,
// CoroutineScope ve state güncelleyici alır; sonraki delegeler aynen kullanır.
class McpDelegate(
    private val client: BridgeClient,
    private val scope: CoroutineScope,
    private val state: () -> RemoteUiState,
    private val update: ((RemoteUiState) -> RemoteUiState) -> Unit,
    private val emit: suspend (String) -> Unit,
) {
    private fun mcpTarget(targetOverride: String? = null) =
        targetOverride ?: mcpTargetFor(state().backend, state().coworkProvider)

    fun loadMcpServers(targetOverride: String? = null) = scope.launch {
        val target = mcpTarget(targetOverride) ?: return@launch
        update { it.copy(mcpLoading = true) }
        runCatching {
            when (target) {
                MCP_TARGET_CLAUDE -> client.claudeAppMcpServers(state().settings)
                MCP_TARGET_ANTIGRAVITY -> client.mcpServers(state().settings)
                else -> client.backendMcpServers(state().settings, target)
            }
        }
            .onSuccess { list -> update { it.copy(mcpServers = list, mcpLoading = false) } }
            .onFailure { update { it.copy(mcpLoading = false) }; reportError("MCP listesi yüklenemedi", it) }
    }
    fun saveMcpServer(name: String, type: String, url: String, command: String, targetOverride: String? = null) = scope.launch {
        val target = mcpTarget(targetOverride) ?: return@launch
        runCatching {
            when (target) {
                MCP_TARGET_CLAUDE -> client.claudeAppMcpSave(state().settings, name, type, url, command)
                MCP_TARGET_ANTIGRAVITY -> client.mcpSave(state().settings, name, type, url, command)
                else -> client.backendMcpSave(state().settings, target, name, type, url, command)
            }
        }
            .onSuccess {
                when (target) {
                    MCP_TARGET_CLAUDE -> emit("MCP eklendi: $name — yeni Claude oturumunda etkin olur")
                    MCP_TARGET_ANTIGRAVITY -> emit("MCP eklendi ve Antigravity'e yüklendi: $name")
                    else -> emit("MCP eklendi: $name — ${mcpEffectNote(target)}")
                }
                loadMcpServers(target)
            }
            .onFailure { reportError("MCP eklenemedi", it) }
    }
    fun refreshMcp(targetOverride: String? = null) = scope.launch {
        val target = mcpTarget(targetOverride) ?: return@launch
        if (target == MCP_TARGET_ANTIGRAVITY) {
            runCatching { client.mcpRefresh(state().settings) }
                .onSuccess { emit("Antigravity MCP sunucuları yenilendi") }
                .onFailure { reportError("Antigravity yenilenemedi", it) }
        } else {
            // Diğer CLI'lar config'i süreç/oturum başlangıcında okur; canlı yeniden bağlama yok.
            emit("MCP değişiklikleri ${mcpEffectNote(target)}")
        }
        loadMcpServers(target)
    }
    fun removeMcpServer(name: String, targetOverride: String? = null) = scope.launch {
        val target = mcpTarget(targetOverride) ?: return@launch
        runCatching {
            when (target) {
                MCP_TARGET_CLAUDE -> client.claudeAppMcpRemove(state().settings, name)
                MCP_TARGET_ANTIGRAVITY -> client.mcpRemove(state().settings, name)
                else -> client.backendMcpRemove(state().settings, target, name)
            }
        }
            .onSuccess { loadMcpServers(target) }
            .onFailure { reportError("MCP silinemedi", it) }
    }
    fun toggleMcpServer(name: String, enabled: Boolean, targetOverride: String? = null) = scope.launch {
        val target = mcpTarget(targetOverride) ?: return@launch
        runCatching {
            when (target) {
                MCP_TARGET_CLAUDE -> client.claudeAppMcpToggle(state().settings, name, enabled)
                MCP_TARGET_ANTIGRAVITY -> client.mcpToggle(state().settings, name, enabled)
                else -> client.backendMcpToggle(state().settings, target, name, enabled)
            }
        }
            .onSuccess { loadMcpServers(target) }
            .onFailure { reportError("MCP durumu değişmedi", it) }
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        emit("$prefix: ${throwable.message ?: "unknown error"}")
    }
}
