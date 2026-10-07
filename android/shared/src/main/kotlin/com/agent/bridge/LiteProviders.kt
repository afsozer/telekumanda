package com.agent.bridge

val LITE_BACKEND_ORDER: List<String> = listOf("claude-app", "codex-app", "agy")

fun RemoteUiState.supportsEditionBackend(id: String): Boolean =
    !liteEdition || id in LITE_BACKEND_ORDER
