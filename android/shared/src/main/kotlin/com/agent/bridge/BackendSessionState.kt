package com.agent.bridge

// Faz 1 (ui2 hazirlik): backend-basina kopyali RemoteUiState alanlarinin
// JENERIK okuma modeli. SAKLANMAZ — mevcut alanlardan TURETILIR; boylece
// cift-yazim (dual-write) riski yoktur. Yeni UI (ui2) yalniz bunu okur.
// Faz 3'te kaynak/turev iliskisi ters cevrilecek (harita kaynak olacak).
data class BackendSessionState(
    val sessionId: String = "",
    val lastSessionId: String = "",
    val cwd: String = "",
    val model: String = "",
    val defaultModel: String = "",
    val permissionMode: String = "",
    val effort: String = "",
    val setupPending: Boolean = false,
    val diskLoading: Boolean = false,
)

// Aktif cowork saglayicisini claude/codex/omp uclusune indirger;
// normalizeCoworkProvider zaten mevcut — kullan, yeniden yazma.
fun RemoteUiState.backendSession(id: String): BackendSessionState = when (id) {
    "agy" -> BackendSessionState(
        sessionId = agySessionId,
        lastSessionId = agy.lastSessionId,
        cwd = agyCwd,
        model = agyModel,
        defaultModel = agy.defaultModel,
        permissionMode = agyPermissionMode,
        setupPending = agySetupPending,
        diskLoading = agyDiskLoading,
    )
    "claude-app" -> BackendSessionState(
        sessionId = claudeAppSessionId,
        lastSessionId = lastClaudeAppSessionId,
        cwd = claudeAppCwd,
        model = claudeAppModel,
        defaultModel = claudeAppDefaultModel,
        permissionMode = claudeAppPermissionMode,
        effort = claudeAppEffort,
        setupPending = claudeAppSetupPending,
        diskLoading = claudeAppDiskLoading,
    )
    "codex-app" -> BackendSessionState(
        sessionId = codexAppSessionId,
        lastSessionId = lastCodexAppSessionId,
        cwd = codexAppCwd,
        model = codexAppModel,
        defaultModel = codexAppDefaultModel,
        permissionMode = codexAppPermissionMode,
        effort = codexAppEffort,
        setupPending = codexAppSetupPending,
        diskLoading = codexAppDiskLoading,
    )
    "omp" -> BackendSessionState(
        sessionId = omp.sessionId,
        lastSessionId = omp.lastSessionId,
        cwd = omp.cwd,
        model = omp.model,
        defaultModel = omp.defaultModel,
        permissionMode = omp.permissionMode,
        effort = omp.variant,
        setupPending = omp.setupPending,
        diskLoading = omp.diskLoading,
    )
    "cowork" -> {
        val provider = normalizeCoworkProvider(coworkProvider)
        val base = backendSession(provider)
        base.copy(
            lastSessionId = lastCoworkSessionId,
            cwd = activeCoworkProjectPath.ifBlank { base.cwd },
            permissionMode = if (coworkYoloMode) "yolo" else "onay",
            diskLoading = coworkSessionsLoading,
        )
    }
    else -> BackendSessionState()
}
