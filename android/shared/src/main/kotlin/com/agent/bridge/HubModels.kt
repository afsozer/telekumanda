package com.agent.bridge


// Faz 2d (ui2 Merkez): RemoteUiState'in proje/cowork/operasyon ailelerini
// Compose ekranlarından uzak tutan, salt-okunur sınır. Saklanmaz; mevcut
// motor state'inden türetilir. Proje ve cowork çalışma alanı aynı path'e
// işaret ediyorsa TEK satır olur.
data class HubProjectItem(
    val key: String,
    val projectId: String? = null,
    val workspacePath: String? = null,
    val path: String,
    val title: String,
    val matter: String = "",
    val exists: Boolean = true,
    val sessionCount: Int = 0,
    val runningCount: Int = 0,
    val outputCount: Int = 0,
    val newOutputCount: Int = 0,
    val providers: List<String> = emptyList(),
    val pinned: Boolean = false,
    val lastOpenedAt: String = "",
    val lastActivityAt: String = "",
) {
    // Cowork kaydı olmasa da (workspace registry'den silinmiş/eski girdiler)
    // CoworkSpaces klasörü altındaki yollar çalışma alanı sayılır — bunlar
    // Merkez'de Projeler yerine Çalışma Alanları listesine düşer.
    val isCoworkWorkspace: Boolean
        get() = workspacePath != null || hubPathKey(path).contains("/coworkspaces/")
}

data class HubSummary(
    val projectCount: Int,
    val runningCount: Int,
    val waitingCount: Int,
    val failedCount: Int,
    val deliveryCount: Int,
    // Teslimat sayilari KAYNAGINA gore ayri: Merkez'de "Projeler" ve "Çalışma
    // Alanları" ayri satirlar ve ayri listelere aciliyor. Tek bir toplam
    // kullanildiginda cowork teslimatlari Projeler satirinda rozet oluyor, ama
    // Projeler listesinde hicbir sey gorunmuyordu (canli goruldu) — rozet yanlis
    // kapiyi isaret ediyordu.
    val newDeliveryCount: Int,
    val workspaceNewDeliveryCount: Int,
    val usageGroupCount: Int,
    val hasUnseenOperationEvents: Boolean,
)

private fun hubPathKey(path: String): String = path
    .trim()
    .replace('\\', '/')
    .trimEnd('/')
    .lowercase()

fun RemoteUiState.hubProjects(): List<HubProjectItem> {
    val workspacesByPath = coworkWorkspaces.associateBy { hubPathKey(it.path) }
    val projectsByPath = projectSummaries.associateBy { hubPathKey(it.path) }
    val allPaths = (projectsByPath.keys + workspacesByPath.keys).filter { it.isNotBlank() }

    return allPaths.map { pathKey ->
        val project = projectsByPath[pathKey]
        val workspace = workspacesByPath[pathKey]
        val path = project?.path ?: workspace?.path.orEmpty()
        HubProjectItem(
            key = project?.id?.let { "project:$it" } ?: "cowork:$pathKey",
            projectId = project?.id,
            workspacePath = workspace?.path,
            path = path,
            title = project?.displayName?.ifBlank { null }
                ?: workspace?.matter?.takeIf { it.isNotBlank() }
                ?: workspace?.name
                ?: project?.name
                ?: path.substringAfterLast('/').substringAfterLast('\\'),
            matter = workspace?.matter.orEmpty(),
            exists = project?.exists ?: true,
            sessionCount = project?.sessionCount ?: 0,
            runningCount = project?.runningCount ?: 0,
            outputCount = project?.outputCount ?: 0,
            newOutputCount = project?.newOutputCount ?: 0,
            providers = project?.providers.orEmpty(),
            pinned = project?.pinned ?: false,
            lastOpenedAt = project?.lastOpenedAt.orEmpty(),
            lastActivityAt = project?.lastActivityAt.orEmpty(),
        )
    }.sortedWith(
        compareByDescending<HubProjectItem> { it.pinned }
            .thenByDescending { it.newOutputCount > 0 }
            .thenByDescending { it.runningCount > 0 }
            .thenByDescending { it.lastActivityAt.ifBlank { "0" } }
            .thenBy { it.title.lowercase() }
    )
}

fun filterHubProjects(
    items: List<HubProjectItem>,
    filter: ProjectFilter,
    query: String
): List<HubProjectItem> {
    val filteredByChip = when (filter) {
        ProjectFilter.ALL -> items
        ProjectFilter.PINNED -> items.filter { it.pinned }
        ProjectFilter.ACTIVE -> items.filter { it.runningCount > 0 }
        ProjectFilter.NEW_DELIVERY -> items.filter { it.newOutputCount > 0 }
    }
    if (query.isBlank()) return filteredByChip
    val q = query.trim().lowercase()
    return filteredByChip.filter {
        it.title.lowercase().contains(q) ||
        it.path.lowercase().contains(q) ||
        it.matter.lowercase().contains(q)
    }
}

/**
 * Olay geçmişini "güncel" ve "geçmiş" olarak böler.
 *
 * Aynı oturum her başlat/bitir döngüsünde yeni olay üretir ve liste "X başladı,
 * X tamamlandı, X başladı, X tamamlandı…" diye şişiyordu (kullanıcı kararı
 * 05.08.2026): oturum+tür başına yalnız EN YENİ olay üstte kalsın, eskileri
 * varsayılan kapalı bir Geçmiş bölümüne insin.
 *
 * Girdi köprüden yeniden→eskiye sıralı gelir; ilk görülen (oturum, tür) çifti
 * günceldir, tekrarları geçmiştir. Oturum kimliği taşımayan olaylar
 * gruplanamaz, güncelde kalır.
 */
data class OperationEventSplit(
    val current: List<OperationEvent>,
    val history: List<OperationEvent>,
)

fun splitOperationEvents(events: List<OperationEvent>): OperationEventSplit {
    val seen = HashSet<String>()
    val current = ArrayList<OperationEvent>(events.size)
    val history = ArrayList<OperationEvent>()
    for (e in events) {
        // diskId kalıcı kimlik; sessionId köprü kabuğudur ve adopt'ta değişir.
        val session = e.diskId.ifBlank { e.sessionId }
        if (session.isBlank() || seen.add("$session:${e.kind}")) current.add(e)
        else history.add(e)
    }
    return OperationEventSplit(current, history)
}

/**
 * Operasyonlar kök tuşundaki kehribar nokta: bekleyen oturum ya da görülmemiş
 * olay var mı. hubSummary'nin aynı iki alanını kullanır ama proje listesini
 * dolaşmaz — alt nav her yeniden çizimde bunu soruyor.
 */
fun RemoteUiState.hasOperationsAttention(): Boolean {
    if (operations.counts.waiting > 0) return true
    val newest = operations.events.firstOrNull()?.id.orEmpty()
    return newest.isNotBlank() && newest != lastSeenOperationEventId
}

fun RemoteUiState.hubSummary(): HubSummary {
    val projects = hubProjects()
    val newestEventId = operations.events.firstOrNull()?.id.orEmpty()
    return HubSummary(
        projectCount = projects.size,
        runningCount = operations.counts.running,
        waitingCount = operations.counts.waiting,
        failedCount = operations.counts.failed,
        deliveryCount = projects.sumOf { it.outputCount },
        // Her rozet KENDI listesinin teslimatlarini saysin.
        newDeliveryCount = projects.filterNot { it.isCoworkWorkspace }.sumOf { it.newOutputCount },
        workspaceNewDeliveryCount = projects.filter { it.isCoworkWorkspace }.sumOf { it.newOutputCount },
        usageGroupCount = usage.groups.size,
        hasUnseenOperationEvents = newestEventId.isNotBlank() && newestEventId != lastSeenOperationEventId,
    )
}
