package com.agent.bridge

// Faz 2c: disk oturumlarinin jenerik okuma modeli + oturum aksiyon kapisi.
data class BackendDiskSessionUi(
    val id: String,
    val cwd: String = "",
    val title: String = "",
    val lastText: String = "",
    val turns: Int = 0,
    val mtime: Long = 0L,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    // Bu satırda toplanan transcript kopyası sayısı (1 = çatal yok).
    val forks: Int = 1,
)

/**
 * Android ve masaüstü oturum listelerinin ortak sıralaması:
 * sabitliler önce; her grubun içinde en yeni oturum önce.
 */
fun <T> Iterable<T>.sortedBySessionPriority(
    pinned: (T) -> Boolean,
    mtime: (T) -> Long,
): List<T> = sortedWith(
    compareByDescending<T> { pinned(it) }
        .thenByDescending { mtime(it) }
)

// Tablo D — RemoteUiState.backendDiskSessions(id: String): List<BackendDiskSessionUi>
fun RemoteUiState.backendDiskSessions(id: String): List<BackendDiskSessionUi> {
    if (!supportsEditionBackend(id)) return emptyList()
    val sessions = when (id) {
    "agy" -> agyDiskSessions.map {
        BackendDiskSessionUi(
            id = it.id,
            cwd = it.cwd,
            title = it.title,
            lastText = it.lastText,
            turns = it.turns,
            mtime = it.mtime,
        )
    }
    "claude-app" -> claudeAppDiskSessions.map {
        BackendDiskSessionUi(
            id = it.id,
            cwd = it.cwd,
            title = it.title,
            lastText = it.lastText,
            turns = it.turns,
            mtime = it.mtime,
            pinned = it.pinned,
            archived = it.archived,
            forks = it.forks
        )
    }
    "codex-app" -> codexAppDiskSessions.map {
        BackendDiskSessionUi(
            id = it.id,
            cwd = it.cwd,
            title = it.title,
            lastText = it.lastText,
            turns = it.turns,
            mtime = it.mtime,
            pinned = it.pinned,
            archived = it.archived
        )
    }
    // v1 ve v2 AYNI dal: sema ayni, yalniz durum ailesi ayri. Dal yokken
    // opencode2 cekmecesi HEP BOS geliyordu (`else -> emptyList()`).
    "opencode2-app" -> opencodeFamily(id).diskSessions.map {
        BackendDiskSessionUi(
            id = it.id,
            cwd = it.cwd,
            title = it.title,
            lastText = it.lastText,
            turns = it.turns,
            mtime = it.mtime,
            pinned = it.pinned,
        )
    }
    "omp" -> omp.diskSessions.map {
        BackendDiskSessionUi(
            id = it.id,
            cwd = it.cwd,
            title = it.title,
            lastText = it.lastText,
            turns = it.turns,
            mtime = it.mtime,
            pinned = it.pinned,
            archived = it.archived,
        )
    }
    "cowork" -> coworkSessions.map {
        BackendDiskSessionUi(
            id = it.sessionId,
            cwd = it.cwd,
            title = it.title.ifBlank {
                when (it.provider) {
                    "codex-app" -> "Yeni Codex oturumu"
                    "opencode2-app" -> "Yeni OpenCode oturumu"
                    else -> "Yeni Claude oturumu"
                }
            },
            lastText = it.lastText,
            // Global çekmece listesi mtime'a göre sıralanır; ISO lastUsedAt epoch'a
            // çevrilmezse cowork satırları hep listenin dibine çökerdi.
            mtime = parseIsoEpochMillis(it.lastUsedAt.ifBlank { it.createdAt })
        )
    }
        else -> emptyList()
    }
    if (!liteEdition) return sessions
    if (liteWorkspaceRoot.isBlank()) return emptyList()
    return sessions.filter { sessionPathInsideRoot(it.cwd, liteWorkspaceRoot) }
}

internal fun sessionPathInsideRoot(path: String, root: String): Boolean {
    fun key(value: String) = value.trim().replace('\\', '/').trimEnd('/').lowercase()
    val pathKey = key(path)
    val rootKey = key(root)
    return rootKey.isNotBlank() && (pathKey == rootKey || pathKey.startsWith("$rootKey/"))
}

private fun parseIsoEpochMillis(value: String): Long =
    runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

fun backendSessionPinSupported(id: String): Boolean =
    id == "claude-app" || id == "codex-app" || id == "opencode2-app" ||
        id == "opencode2-app" || id == "omp"

fun backendSessionRenameSupported(id: String): Boolean = backendSessionPinSupported(id)

fun backendSessionArchiveSupported(id: String): Boolean =
    id == "claude-app" || id == "codex-app" || id == "omp"

fun backendUsesWorkspaces(id: String): Boolean = id == "cowork"

// "Bilgisayarda devam et": köprü oturumu PC'de `claude --resume` ile terminalde açar.
// Yalnız claude-app'te var — uç /claude-app/open-on-pc ve masaüstü Claude uygulaması
// köprü oturumlarını listelemediği için PC'de sürdürmenin desteklenen tek yolu bu.
fun backendSessionOpenOnPcSupported(id: String): Boolean = id == "claude-app"

// Tablo E-H: RemoteViewModel extension'ları (resume/pin/archive/rename/start/delete)
// app modülünde (BackendSessionsActions.kt). Bu dosyada yalnız UI/okuma tarafı var.
