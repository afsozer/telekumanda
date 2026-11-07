package com.agent.bridge

// Lite sürümün sağlayıcıları. Codex 24.10.2026'da çıkarıldı (kullanıcı isteği).
val LITE_BACKEND_ORDER: List<String> = listOf("claude-app", "agy")

fun RemoteUiState.supportsEditionBackend(id: String): Boolean =
    !liteEdition || id in LITE_BACKEND_ORDER
