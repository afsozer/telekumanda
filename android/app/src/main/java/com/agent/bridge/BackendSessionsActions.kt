package com.agent.bridge

// BackendSessions.kt'nin RemoteViewModel extension tarafı. plan 4. madde
// (Faz 1.5) kapsamında: VM-bağımlı aksiyonlar app modülünde kalır, okuma
// tarafı shared'dadır. Faz 2'de RemoteStore arayüzü çekilip ikisi de
// shared'a birleştirilebilir.

// Tablo E — RemoteViewModel.resumeBackendDiskSession(backend: String, sessionId: String)
fun RemoteViewModel.resumeBackendDiskSession(backend: String, sessionId: String) {
    val state = uiState.value
    if (!state.supportsEditionBackend(backend)) return
    when (backend) {
        "agy" -> {
            val session = state.agyDiskSessions.find { it.id == sessionId }
            if (session != null) resumeAgyDiskSession(session)
        }
        "claude-app" -> {
            val session = state.claudeAppDiskSessions.find { it.id == sessionId }
            if (session != null) resumeClaudeAppDiskSession(session)
        }
        "codex-app" -> {
            val session = state.codexAppDiskSessions.find { it.id == sessionId }
            if (session != null) resumeCodexAppDiskSession(session)
        }
        "opencode2-app" -> {
            val session = state.opencode.diskSessions.find { it.id == sessionId }
            if (session != null) resumeOpencode2AppDiskSession(session)
        }
        "omp" -> {
            val session = state.ompDiskSessions.find { it.id == sessionId }
            if (session != null) resumeOmpDiskSession(session)
        }
        "cowork" -> {
            val session = state.coworkSessions.find { it.sessionId == sessionId }
            if (session != null) resumeCoworkSession(session)
        }
    }
}

// Tablo F — pin/arşiv kapısı
fun RemoteViewModel.setBackendSessionPinned(backend: String, sessionId: String, pinned: Boolean) {
    if (backend == "claude-app") {
        if (pinned) claudeAppPin(sessionId) else claudeAppUnpin(sessionId)
    } else if (backend == "codex-app") {
        if (pinned) codexAppPin(sessionId) else codexAppUnpin(sessionId)
    } else if (backend == "opencode2-app") {
        if (pinned) opencodeAppPin(sessionId) else opencodeAppUnpin(sessionId)
    } else if (backend == "omp") {
        if (pinned) ompPin(sessionId) else ompUnpin(sessionId)
    }
}

fun RemoteViewModel.setBackendSessionTitle(backend: String, sessionId: String, title: String) {
    if (backend == "claude-app") claudeAppRename(sessionId, title)
    else if (backend == "codex-app") codexAppRename(sessionId, title)
    else if (backend == "opencode2-app") opencodeAppRename(sessionId, title)
    else if (backend == "omp") ompRename(sessionId, title)
}

fun RemoteViewModel.setBackendSessionArchived(backend: String, sessionId: String, archived: Boolean) {
    if (backend == "claude-app") {
        if (archived) claudeAppArchive(sessionId) else claudeAppUnarchive(sessionId)
    } else if (backend == "codex-app") {
        if (archived) codexAppArchive(sessionId) else codexAppUnarchive(sessionId)
    } else if (backend == "omp") {
        if (archived) ompArchive(sessionId) else ompUnarchive(sessionId)
    }
}

// Tablo G — RemoteViewModel.startBackendSession(backend: String, cwd: String)
fun RemoteViewModel.startBackendSession(backend: String, cwd: String) {
    if (!uiState.value.supportsEditionBackend(backend)) return
    val model = uiState.value.backendSession(backend).model
    when (backend) {
        "agy" -> startAgySession(cwd, model)
        "claude-app" -> startClaudeAppSession(cwd, model)
        "codex-app" -> startCodexAppSession(cwd, model)
        "opencode2-app" -> startOpencode2AppSession(cwd, model)
        "omp" -> startOmpSession(cwd, model)
        // cwd = seçilen workspace yolu; boşsa otomatik yeni çalışma alanı oluşturulur.
        // (Global selectedCoworkWorkspace kullanılmaz — her başlatmada dolduğu için
        // "yeni oturum" ekranından hep eski workspace'e dönülüyordu.)
        "cowork" -> startCoworkSession(cwd)
    }
}

// Tablo H — RemoteViewModel.deleteBackendDiskSession(backend: String, sessionId: String)
fun RemoteViewModel.deleteBackendDiskSession(backend: String, sessionId: String) {
    when (backend) {
        // Global çekmecede kayıt başka bir workspace'e ait olabilir; silme hedefi
        // seçili workspace değil kaydın kendi cwd'sidir (boşsa seçiliye düşülür).
        "cowork" -> {
            val recordCwd = uiState.value.coworkSessions
                .find { it.sessionId == sessionId }?.cwd.orEmpty()
            deleteCoworkSession(recordCwd.ifBlank { uiState.value.selectedCoworkWorkspace }, sessionId)
        }
        "agy", "claude-app", "codex-app", "opencode2-app", "omp" -> deleteDiskSession(backend, sessionId)
    }
}
