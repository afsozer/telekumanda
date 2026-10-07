package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agent.bridge.BulkSessionActionResponse
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.CompleteProjectDeleteResult
import com.agent.bridge.HubProjectItem
import com.agent.bridge.ProjectArtifact
import com.agent.bridge.DirEntry
import com.agent.bridge.ProjectDelivery
import com.agent.bridge.ProjectFilter
import com.agent.bridge.ProjectSession
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.coworkRootPath
import com.agent.bridge.coworkWorkspaces
import com.agent.bridge.coworkWorkspacesLoading
import com.agent.bridge.filterHubProjects
import com.agent.bridge.hubProjects
import com.agent.bridge.selectedCoworkWorkspace
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.adaptiveColumnCount
import com.agent.bridge.ui2.components.adaptiveItems
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SearchField
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui2.components.SelectorSheet
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusDot
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Başlık taşmasın: 36dp ikon tuşları (ScreenHeader ile aynı), tek satır +
// ellipsis; ikon sayısı moda göre azaltılır (global arama yalnız Projeler'de,
// yeni çalışma alanı tuşu yalnız Çalışma Alanları'nda).
@Composable
private fun HubProjectsScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    searchActive: Boolean,
    onToggleSearch: () -> Unit,
    onOpenSearch: (() -> Unit)?,
    onRefresh: () -> Unit,
    onNewWorkspace: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ui2.colors.ink2)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Ui2.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onOpenSearch != null) {
            IconButton(onClick = onOpenSearch, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Search, "Global ara", tint = Ui2.colors.ink2)
            }
        }
        IconButton(onClick = onToggleSearch, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Search,
                "Ara",
                tint = if (searchActive) Ui2.colors.accent else Ui2.colors.ink2
            )
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Refresh, "Yenile", tint = Ui2.colors.ink2)
        }
        if (onNewWorkspace != null) {
            IconButton(onClick = onNewWorkspace, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, "Ekle", tint = Ui2.colors.accent)
            }
        }
    }
}

enum class ProjectAction {
    NEW_CHAT, TOGGLE_PIN, RENAME, DELETE_COMPLETELY
}

@Composable
fun HubProjectsScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onNewWorkspace: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSearch: () -> Unit = {},
    // Aynı ekran iki rotadan kullanılır: Merkez > Projeler (yalnız backend proje
    // klasörleri) ve Merkez > Çalışma Alanları (yalnız cowork workspace'leri).
    workspacesOnly: Boolean = false,
    // Cowork dosya gezgini girişi (yalnız workspacesOnly modunda gösterilir).
    onOpenCoworkFiles: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        actions.loadProjects()
        actions.loadCoworkWorkspaces()
    }
    val unfilteredProjects = uiState.hubProjects().filter { it.isCoworkWorkspace == workspacesOnly }
    // Teslimat filtresi proje odaklıdır; Çalışma Alanları ekranına geri
    // dönüldüğünde seçili kalıp bütün alanları görünmez yapmasın.
    val activeFilter = if (workspacesOnly && uiState.projectsFilter == ProjectFilter.NEW_DELIVERY) {
        ProjectFilter.ALL
    } else {
        uiState.projectsFilter
    }
    val projects = filterHubProjects(unfilteredProjects, activeFilter, uiState.projectsSearchQuery)
    val loading = uiState.projectsLoading || uiState.coworkWorkspacesLoading
    var completeDeleteTarget by remember { mutableStateOf<HubProjectItem?>(null) }
    var activeActionTarget by remember { mutableStateOf<HubProjectItem?>(null) }
    var renameTarget by remember { mutableStateOf<HubProjectItem?>(null) }
    var quickChatTarget by remember { mutableStateOf<HubProjectItem?>(null) }
    var searchVisible by remember { mutableStateOf(uiState.projectsSearchQuery.isNotEmpty()) }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        HubProjectsScreenHeader(
            title = if (workspacesOnly) "Çalışma Alanları" else "Projeler",
            subtitle = if (workspacesOnly) "Cowork çalışma alanları" else "Backend proje klasörleri",
            onBack = onBack,
            searchActive = searchVisible,
            onToggleSearch = {
                searchVisible = !searchVisible
                if (!searchVisible) actions.updateProjectsSearchQuery("")
            },
            onRefresh = {
                actions.loadProjects()
                actions.loadCoworkWorkspaces()
            },
            onOpenSearch = if (workspacesOnly) null else onOpenSearch,
            onNewWorkspace = if (workspacesOnly) onNewWorkspace else null,
        )

        if (searchVisible) {
            Box(Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4)) {
                SearchField(
                    value = uiState.projectsSearchQuery,
                    onValueChange = { actions.updateProjectsSearchQuery(it) },
                    placeholder = "Proje adı, yol veya matter ara…"
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)
        ) {
            val filters = buildList {
                add(ProjectFilter.ALL to "Tümü")
                add(ProjectFilter.PINNED to "Sabit")
                add(ProjectFilter.ACTIVE to "Aktif")
                if (!workspacesOnly) add(ProjectFilter.NEW_DELIVERY to "Yeni teslimat")
            }
            filters.forEach { (filter, label) ->
                val selected = activeFilter == filter
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) Ui2.colors.accent else Ui2.colors.surface,
                            Ui2Tokens.pill
                        )
                        .border(
                            1.dp,
                            if (selected) Ui2.colors.accent else Ui2.colors.line,
                            Ui2Tokens.pill
                        )
                        .clickable { actions.updateProjectsFilter(filter) }
                        .padding(horizontal = Ui2Tokens.s12, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Ui2.colors.onAccent else Ui2.colors.ink2
                    )
                }
            }
        }

        // Çalışma Alanları modunda dosya gezgini girişi: CoworkSpaces kökünde açılır.
        if (workspacesOnly) {
            Box(Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4)) {
                SurfaceCard(onClick = if (uiState.coworkRootPath.isNotBlank()) onOpenCoworkFiles else null) {
                    ListRow(
                        title = "Dosyalar",
                        detail = if (uiState.coworkRootPath.isNotBlank()) "Tüm çalışma alanı dosyalarına göz at" else "Çalışma alanları yükleniyor…",
                        leading = { Icon(Icons.Default.Folder, null, tint = Ui2.colors.accent) },
                    )
                }
            }
        }

        when {
            loading && projects.isEmpty() -> LoadingSkeleton(
                Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s16),
                rows = 4,
            )
            projects.isEmpty() -> if (workspacesOnly) {
                EmptyState(
                    title = "Çalışma alanı yok",
                    description = "Cowork çalışma alanları burada listelenir. Yeni bir tane oluşturabilirsin.",
                    icon = Icons.Outlined.Workspaces,
                    actionLabel = "Çalışma alanı oluştur",
                    onAction = onNewWorkspace,
                )
            } else {
                EmptyState(
                    title = "Proje bulunamadı",
                    description = "Filtreyi veya arama kelimesini değiştirebilirsin.",
                    icon = Icons.Outlined.FolderOpen,
                )
            }
            // Tablet/yatayda kartlar iki sutun (Merkez ile ayni esik);
            // telefonda columns=1 ve yerlesim eskisiyle birebir ayni.
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = adaptiveColumnCount(maxWidth)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Ui2Tokens.screenPadding,
                        end = Ui2Tokens.screenPadding,
                        top = Ui2Tokens.s8,
                        bottom = Ui2Tokens.s28,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
                ) {
                    adaptiveItems(projects, columns, key = { it.key }) { project ->
                        HubProjectRow(
                            project = project,
                            onClick = {
                                val id = project.projectId
                                if (id != null) {
                                    actions.loadProjectDetail(id)
                                    onOpenProject()
                                } else {
                                    actions.selectCoworkWorkspace(project.workspacePath.orEmpty())
                                    onOpenWorkspace()
                                }
                            },
                            onLongClick = { activeActionTarget = project },
                            onMore = { activeActionTarget = project },
                        )
                    }
                }
            }
        }
    }

    activeActionTarget?.let { target ->
        val options = buildList {
            add(SelectorOption(ProjectAction.NEW_CHAT, "Yeni sohbet"))
            add(SelectorOption(ProjectAction.TOGGLE_PIN, if (target.pinned) "Sabitlemeyi kaldır" else "Sabitle"))
            add(SelectorOption(ProjectAction.RENAME, "Yeniden adlandır"))
            add(SelectorOption(ProjectAction.DELETE_COMPLETELY, "Tamamen sil"))
        }
        SelectorSheet(
            title = target.title,
            subtitle = target.path,
            options = options,
            selectedValue = null,
            onSelect = { action ->
                activeActionTarget = null
                when (action) {
                    ProjectAction.NEW_CHAT -> {
                        // Cowork alanında ajan seçimi sorulmaz: son kullanılan sağlayıcıyla
                        // direkt başlar; ajan sohbet içindeki sağlayıcı çipinden değiştirilir.
                        val ws = target.workspacePath
                        if (ws != null) {
                            actions.startCoworkSession(ws)
                            onOpenChat()
                        } else {
                            quickChatTarget = target
                        }
                    }
                    ProjectAction.TOGGLE_PIN -> {
                        actions.applyProjectPreferences(
                            id = target.projectId.orEmpty(),
                            pinned = !target.pinned,
                            path = target.path
                        )
                    }
                    ProjectAction.RENAME -> {
                        renameTarget = target
                    }
                    ProjectAction.DELETE_COMPLETELY -> {
                        completeDeleteTarget = target
                    }
                }
            },
            onDismiss = { activeActionTarget = null }
        )
    }

    quickChatTarget?.let { target ->
        ProjectQuickChatSheet(
            uiState = uiState,
            project = target,
            onStart = { request, onFailure ->
                actions.startProjectChat(
                    request = request,
                    onSuccess = {
                        quickChatTarget = null
                        onOpenChat()
                    },
                    onFailure = onFailure,
                )
            },
            onDismiss = { quickChatTarget = null }
        )
    }

    renameTarget?.let { target ->
        var renameValue by remember { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = Ui2.colors.surface2,
            title = { Text("Yeniden Adlandır", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        renameTarget = null
                        actions.setProjectLabel(target.projectId.orEmpty(), renameValue, target.path)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ui2.colors.accent,
                        contentColor = Ui2.colors.onAccent,
                    )
                ) { Text("Kaydet") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Vazgeç", color = Ui2.colors.ink2) }
            }
        )
    }

    completeDeleteTarget?.let { target ->
        // Use the new detailed ResultSheet instead of old ConfirmDialog when we have results
        val deleteState = uiState.projectDelete
        val deleteResult = deleteState.result
        if (deleteResult != null) {
            ProjectDeleteResultSheet(
                result = deleteResult,
                onRetry = { actions.retryDeleteProjectCompletely() },
                onDismiss = {
                    actions.dismissProjectDeleteResult()
                    completeDeleteTarget = null
                },
            )
        } else {
            ConfirmDialog(
                title = "Proje tamamen silinsin mi?",
                text = "${target.title} klasörü, klasördeki tüm dosyalar, projeye bağlı tüm oturumlar ve uygulamadaki proje kayıtları kalıcı olarak silinecek. Bu işlem geri alınamaz.\n\n${target.path}",
                confirmLabel = "Tamamen sil",
                onConfirm = {
                    val workspacePath = target.workspacePath
                    if (workspacePath != null) {
                        actions.deleteCoworkProject(workspacePath)
                    } else {
                        target.projectId?.let { actions.deleteProjectCompletely(it) }
                    }
                },
                onDismiss = { completeDeleteTarget = null },
            )
        }
    }
}

@Composable
private fun HubProjectRow(
    project: HubProjectItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    SurfaceCard(onClick = onClick, onLongClick = onLongClick) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (project.isCoworkWorkspace) Icons.Outlined.Workspaces else Icons.Default.Folder,
                null,
                tint = if (project.isCoworkWorkspace) Ui2.colors.accent else Ui2.colors.ink2,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.title, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                    if (project.pinned) {
                        Spacer(Modifier.width(Ui2Tokens.s4))
                        Icon(
                            Icons.Default.PushPin,
                            "Sabit",
                            tint = Ui2.colors.accent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Text(
                    project.matter.ifBlank { project.path },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // "Cowork" rozeti yoktu değil, bilinçli kaldırıldı: liste zaten moda göre
            // filtreli, Çalışma Alanları ekranında her kart cowork'tü (gereksiz tekrar).
            when {
                project.newOutputCount > 0 -> StatusBadge("${project.newOutputCount} yeni", StatusKind.Done)
                project.runningCount > 0 -> StatusBadge("${project.runningCount} çalışıyor", StatusKind.Running)
            }
            IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    if (project.isCoworkWorkspace) "Çalışma alanı işlemleri" else "Proje işlemleri",
                    tint = Ui2.colors.ink2,
                )
            }
        }
        Text(
            "${project.sessionCount} oturum · ${project.outputCount} teslimat" +
                if (project.providers.isNotEmpty()) " · ${project.providers.joinToString(" / ")}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink2,
        )
    }
}

@Composable
fun HubNewWorkspaceScreen(
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var templateIndex by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "Yeni çalışma alanı", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s16),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20),
        ) {
            SectionHeader("Tür")
            SegmentedTabs(
                options = listOf("Çalışma alanı", "Dava dosyası"),
                selectedIndex = templateIndex,
                onSelect = { templateIndex = it },
            )
            SectionHeader("Ad")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (templateIndex == 1) "Dava veya dosya adı" else "Çalışma alanı adı") },
            )
            Text(
                if (templateIndex == 1) {
                    "Dilekçeler, deliller ve teslimatlar için dava-dosyası klasör yapısı hazırlanır."
                } else {
                    "Cowork oturumları ve teslimatlar bu klasörde birlikte tutulur."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink2,
            )
            Button(
                onClick = {
                    creating = true
                    actions.createCoworkWorkspace(
                        name = name,
                        template = if (templateIndex == 1) "dava-dosyasi" else "",
                    ) { success ->
                        creating = false
                        if (success) onDone()
                    }
                },
                enabled = name.isNotBlank() && !creating,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ui2.colors.accent,
                    contentColor = Ui2.colors.onAccent,
                ),
            ) { Text(if (creating) "Oluşturuluyor…" else "Oluştur") }
        }
    }
}

@Composable
fun HubProjectDetailScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSecurity: () -> Unit,
    // Cowork dosya gezginini bu alanın klasöründe açar (parametre: workspace yolu).
    onOpenFiles: (String) -> Unit = {},
    // Proje kapsamlı not listesi; Boolean doğrudan yeni-not diyaloğunu açar.
    onOpenNotes: (projectPath: String, createImmediately: Boolean) -> Unit = { _, _ -> },
    // Teslimata dokununca dosya görüntüleyicisine geçiş.
    onOpenViewer: () -> Unit = {},
) {
    val detail = uiState.selectedProjectDetail
    val project = detail?.project
    var deleteConfirm by remember(project?.id) { mutableStateOf(false) }
    var label by remember(project?.id, project?.displayName) { mutableStateOf(project?.displayName.orEmpty()) }
    var quickChatTarget by remember(project?.id) { mutableStateOf<HubProjectItem?>(null) }
    // Cowork workspace ögeleri (etiket/dosyalar/alan silme) bu sayfada gömülü —
    // ayrı "Cowork alanı" ekranına gidiş kaldırıldı (10.95).
    var wsDeleteConfirm by remember(project?.id) { mutableStateOf(false) }
    LaunchedEffect(project?.id) { actions.loadCoworkWorkspaces() }

    var selectionMode by remember(project?.id) { mutableStateOf(false) }
    val selectedSessionKeys = remember(project?.id) { mutableStateListOf<String>() }
    var lastBulkResult by remember(project?.id) { mutableStateOf<BulkSessionActionResponse?>(null) }
    var bulkDeleteConfirm by remember(project?.id) { mutableStateOf<List<ProjectSession>?>(null) }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedSessionKeys.clear()
    }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        if (selectionMode && detail != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ui2.colors.surface)
                    .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)
            ) {
                IconButton(onClick = {
                    selectionMode = false
                    selectedSessionKeys.clear()
                }) {
                    Icon(Icons.Default.Close, "İptal", tint = Ui2.colors.ink)
                }
                Text(
                    text = "${selectedSessionKeys.size} seçildi",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ui2.colors.ink,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = {
                    val selectedSessions = detail.sessions.filter { "${it.backend}:${it.sessionId}" in selectedSessionKeys }
                    if (selectedSessions.isNotEmpty()) {
                        actions.bulkSessionAction(project!!.id, "archive", selectedSessions) { resp ->
                            lastBulkResult = resp
                            selectionMode = false
                            selectedSessionKeys.clear()
                        }
                    }
                }) {
                    Icon(Icons.Default.Archive, "Arşivle", tint = Ui2.colors.ink2)
                }
                IconButton(onClick = {
                    val selectedSessions = detail.sessions.filter { "${it.backend}:${it.sessionId}" in selectedSessionKeys }
                    if (selectedSessions.isNotEmpty()) {
                        actions.bulkSessionAction(project!!.id, "unarchive", selectedSessions) { resp ->
                            lastBulkResult = resp
                            selectionMode = false
                            selectedSessionKeys.clear()
                        }
                    }
                }) {
                    Icon(Icons.Default.Unarchive, "Arşivden çıkar", tint = Ui2.colors.ink2)
                }
                IconButton(onClick = {
                    val selectedSessions = detail.sessions.filter { "${it.backend}:${it.sessionId}" in selectedSessionKeys }
                    if (selectedSessions.isNotEmpty()) {
                        bulkDeleteConfirm = selectedSessions
                    }
                }) {
                    Icon(Icons.Default.Delete, "Sil", tint = Ui2.colors.danger)
                }

                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Daha fazla", tint = Ui2.colors.ink)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Tümünü seç") },
                            onClick = {
                                menuExpanded = false
                                selectedSessionKeys.clear()
                                selectedSessionKeys.addAll(detail.sessions.map { "${it.backend}:${it.sessionId}" })
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Bitmişleri seç") },
                            onClick = {
                                menuExpanded = false
                                selectedSessionKeys.clear()
                                val finishedKeys = detail.sessions.filter { it.status != "running" && it.status != "waiting" }
                                    .map { "${it.backend}:${it.sessionId}" }
                                selectedSessionKeys.addAll(finishedKeys)
                            }
                        )
                    }
                }
            }
        } else {
            ScreenHeader(
                title = project?.displayName ?: "Proje",
                subtitle = project?.path,
                onBack = {
                    actions.clearProjectDetail()
                    onBack()
                },
                trailing = project?.let {
                    {
                        IconButton(onClick = { deleteConfirm = true }) {
                            Icon(Icons.Default.Delete, "Proje geçmişini sil", tint = Ui2.colors.danger)
                        }
                    }
                },
            )
        }

        when {
            uiState.projectDetailLoading -> LoadingSkeleton(
                Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s16),
                rows = 5,
            )
            detail == null || project == null -> EmptyState(
                title = "Proje yüklenemedi",
                description = "Listeye dönüp yeniden deneyebilirsin.",
                icon = Icons.Outlined.Description,
            )
            else -> {
                val hubItem = uiState.hubProjects().firstOrNull { it.projectId == project.id }
                val wsPath = hubItem?.workspacePath
                val workspace = uiState.coworkWorkspaces.firstOrNull { it.path == wsPath }
                var matter by remember(wsPath, workspace?.matter) { mutableStateOf(workspace?.matter.orEmpty()) }
                // Detayda oturum/teslimat kartlari tablet/yatayda iki sutun.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = adaptiveColumnCount(maxWidth)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Ui2Tokens.screenPadding,
                        end = Ui2Tokens.screenPadding,
                        top = Ui2Tokens.s8,
                        bottom = Ui2Tokens.s28,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16),
                ) {
                    item {
                        SectionHeader("Proje")
                    }
                    item {
                        SurfaceCard {
                            OutlinedTextField(
                                value = label,
                                onValueChange = { label = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Görünen ad") },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                                OutlinedButton(
                                    onClick = { actions.setProjectLabel(project.id, label) },
                                    enabled = label.isNotBlank() && label != project.displayName,
                                ) { Text("Adı kaydet") }
                            }
                            // Cowork workspace: etiket editörü gömülü (eski "Cowork alanı"
                            // ekranından taşındı; ayrı ekran tuşu kaldırıldı).
                            if (wsPath != null) {
                                OutlinedTextField(
                                    value = matter,
                                    onValueChange = { matter = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Dosya / konu etiketi") },
                                )
                                OutlinedButton(
                                    onClick = { actions.setCoworkWorkspaceMatter(wsPath, matter) },
                                    enabled = workspace != null && matter != workspace.matter,
                                ) { Text("Etiketi kaydet") }
                            }
                            if (hubItem != null) {
                                // Cowork alanında seçim penceresi yok: son kullanılan
                                // sağlayıcıyla direkt başlar (ajan sohbetteki çipten
                                // değiştirilir). Normal projede sağlayıcı seçimi kalır —
                                // orada oturum başladığı ajana bağlıdır.
                                Button(
                                    onClick = {
                                        if (wsPath != null) {
                                            actions.startCoworkSession(wsPath)
                                            onOpenChat()
                                        } else {
                                            quickChatTarget = hubItem
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Ui2.colors.accent,
                                        contentColor = Ui2.colors.onAccent,
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(Ui2Tokens.s8))
                                    Text("Yeni sohbet başlat")
                                }
                            }
                            if (wsPath != null) {
                                OutlinedButton(
                                    onClick = { onOpenFiles(wsPath) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Ui2.colors.accent)
                                    Text("Dosyalar")
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                                ) {
                                    OutlinedButton(
                                        onClick = { onOpenNotes(wsPath, false) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Outlined.Description, null, tint = Ui2.colors.accent)
                                        Text("Notlar")
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenNotes(wsPath, true) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.Add, null, tint = Ui2.colors.accent)
                                        Text("Not ekle")
                                    }
                                }
                            }
                            OutlinedButton(onClick = onOpenSecurity, modifier = Modifier.fillMaxWidth()) {
                                Text("Güvenlik & MCP")
                            }
                        }
                    }

                    item { SectionHeader("Oturumlar") }
                    if (detail.sessions.isEmpty()) {
                        item { HubInlineEmpty("Bu projede canlı oturum yok.") }
                    } else {
                        adaptiveItems(detail.sessions, columns, key = { "${it.backend}:${it.sessionId}" }) { session ->
                            val key = "${session.backend}:${session.sessionId}"
                            val isSelected = key in selectedSessionKeys
                            ProjectSessionCard(
                                session = session,
                                selected = isSelected,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelected) selectedSessionKeys.remove(key) else selectedSessionKeys.add(key)
                                        if (selectedSessionKeys.isEmpty()) selectionMode = false
                                    } else {
                                        actions.openProjectSession(session, project.path)
                                        onOpenChat()
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedSessionKeys.add(key)
                                    }
                                }
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Teslimatlar",
                                style = MaterialTheme.typography.titleSmall,
                                color = Ui2.colors.ink,
                            )
                            if (detail.outputs.isNotEmpty()) {
                                TextButton(onClick = {
                                    actions.downloadProjectOutputsFolder(project.id)
                                }) {
                                    Text("Tümünü klasör olarak indir", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (detail.outputs.isEmpty()) {
                        item { HubInlineEmpty("outputs/ altında teslimat yok.") }
                    } else {
                        adaptiveItems(detail.outputs, columns, key = { it.path }) { output ->
                            ProjectDeliveryCard(
                                output,
                                onOpen = {
                                    val entry = DirEntry(
                                        name = output.name, path = output.path,
                                        type = "file", size = output.size,
                                    )
                                    if (actions.openFileEntry(entry)) onOpenViewer()
                                },
                            ) { actions.downloadByPath(output.path, output.name) }
                        }
                    }

                    if (detail.changes.isNotEmpty()) {
                        item { SectionHeader("Değişiklikler") }
                        items(detail.changes) { ProjectArtifactCard(it) }
                    }
                    if (detail.commands.isNotEmpty()) {
                        item { SectionHeader("Komutlar") }
                        items(detail.commands) { ProjectArtifactCard(it) }
                    }
                    if (detail.plan.isNotEmpty()) {
                        item { SectionHeader("Plan") }
                        items(detail.plan) { ProjectArtifactCard(it) }
                    }
                    // Cowork workspace: klasörü de silen alan silme (eski "Cowork alanı"
                    // ekranından taşındı). Başlıktaki çöp ikonu yalnız oturum geçmişini
                    // siler; bu ise klasör + kayıtlar + dosyaların tamamını kaldırır.
                    if (wsPath != null) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                                HorizontalDivider(color = Ui2.colors.line)
                                Text(
                                    "Çalışma alanını silmek klasörü, oturum kayıtlarını ve içindeki dosyaları kalıcı olarak kaldırır.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ui2.colors.danger,
                                )
                                OutlinedButton(onClick = { wsDeleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Delete, null, tint = Ui2.colors.danger)
                                    Text("Çalışma alanını sil", color = Ui2.colors.danger)
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    if (wsDeleteConfirm && project != null) {
        val wsPath = uiState.hubProjects().firstOrNull { it.projectId == project.id }?.workspacePath
        ConfirmDialog(
            title = "Çalışma alanı silinsin mi?",
            text = "${project.displayName} klasörü, Cowork oturum kayıtları ve klasördeki tüm dosyalar kalıcı olarak silinecek. Bu işlem geri alınamaz.",
            confirmLabel = "Klasörü sil",
            onConfirm = {
                wsDeleteConfirm = false
                if (wsPath != null) actions.deleteCoworkProject(wsPath)
                actions.clearProjectDetail()
                onBack()
            },
            onDismiss = { wsDeleteConfirm = false },
        )
    }

    if (deleteConfirm && project != null) {
        ConfirmDialog(
            title = "Proje geçmişi silinsin mi?",
            text = "${project.displayName} projesinin tüm backend oturum geçmişi kalıcı olarak silinecek. Proje klasörü ve kaynak dosyaları yerinde kalacak.",
            confirmLabel = "Geçmişi sil",
            onConfirm = {
                deleteConfirm = false
                actions.deleteProject(project.id)
                onBack()
            },
            onDismiss = { deleteConfirm = false },
        )
    }

    bulkDeleteConfirm?.let { targetSessions ->
        val runningCount = targetSessions.count { it.status == "running" || it.status == "waiting" }
        ConfirmDialog(
            title = "Oturumları sil?",
            text = "${targetSessions.size} oturum kalıcı olarak silinecek." +
                (if (runningCount > 0) "\n\nÇalışan $runningCount oturum da durdurulacak." else ""),
            confirmLabel = "Kalıcı olarak sil",
            onConfirm = {
                val sessionsToDelete = targetSessions
                bulkDeleteConfirm = null
                selectionMode = false
                selectedSessionKeys.clear()
                actions.bulkSessionAction(project!!.id, "delete", sessionsToDelete) { response ->
                    lastBulkResult = response
                }
            },
            onDismiss = { bulkDeleteConfirm = null }
        )
    }

    val bulkResultResponse = lastBulkResult
    if (bulkResultResponse != null) {
        BulkResultSheet(
            response = bulkResultResponse,
            onDismiss = { lastBulkResult = null }
        )
    }

    quickChatTarget?.let { target ->
        ProjectQuickChatSheet(
            uiState = uiState,
            project = target,
            onStart = { request, onFailure ->
                actions.startProjectChat(
                    request = request,
                    onSuccess = {
                        quickChatTarget = null
                        onOpenChat()
                    },
                    onFailure = onFailure,
                )
            },
            onDismiss = { quickChatTarget = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkResultSheet(
    response: BulkSessionActionResponse,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface,
        shape = RoundedCornerShape(topStart = Ui2Tokens.cornerSheet, topEnd = Ui2Tokens.cornerSheet)
    ) {
        Column(
            Modifier
                .padding(horizontal = Ui2Tokens.screenPadding)
                .padding(bottom = Ui2Tokens.sheetBottom),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16)
        ) {
            Column(Modifier.padding(bottom = Ui2Tokens.s4)) {
                Text(
                    text = when (response.action) {
                        "delete" -> "Toplu Silme Sonucu"
                        "archive" -> "Toplu Arşivleme Sonucu"
                        else -> "Toplu İşlem Sonucu"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Ui2.colors.ink
                )
                Text(
                    text = "Başarılı: ${response.succeeded} · Başarısız: ${response.failed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2
                )
            }

            if (response.failed > 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)
                ) {
                    val failures = response.results.filter { !it.ok }
                    items(failures) { item ->
                        SurfaceCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(StatusKind.Danger)
                                Spacer(Modifier.width(Ui2Tokens.s8))
                                Column {
                                    Text(item.sessionId, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                                    Text(item.error, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.danger)
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Tüm işlemler başarıyla tamamlandı.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ui2.colors.accent,
                    modifier = Modifier.padding(vertical = Ui2Tokens.s16)
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ui2.colors.accent,
                    contentColor = Ui2.colors.onAccent
                )
            ) {
                Text("Tamam")
            }
        }
    }
}

@Composable
private fun ProjectSessionCard(
    session: ProjectSession,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    SurfaceCard(
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                androidx.compose.material3.Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = Ui2.colors.accent,
                        checkmarkColor = Ui2.colors.onAccent
                    )
                )
            } else {
                StatusDot(
                    when (session.status) {
                        "waiting" -> StatusKind.Attention
                        "failed" -> StatusKind.Danger
                        "running" -> StatusKind.Running
                        else -> StatusKind.Done
                    }
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                // Başlık oturumun kendi adı; sağlayıcı adı detay satırına iner
                // (eski bridge title göndermiyorsa sağlayıcı adına düşülür).
                Text(
                    session.title.ifBlank { session.backendLabel },
                    style = MaterialTheme.typography.titleSmall,
                    color = Ui2.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detailParts = buildList {
                    if (session.title.isNotBlank()) add(session.backendLabel)
                    val extra = session.summary.ifBlank { session.model }
                    if (extra.isNotBlank()) add(extra)
                }
                if (detailParts.isNotEmpty()) {
                    Text(
                        detailParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink2,
                        maxLines = 2,
                    )
                }
            }
            if (!selectionMode) {
                IconButton(onClick = onClick) {
                    Icon(Icons.Default.PlayArrow, null, tint = Ui2.colors.accent)
                }
            }
        }
    }
}

@Composable
private fun ProjectDeliveryCard(
    output: ProjectDelivery,
    onOpen: (() -> Unit)? = null,
    onDownload: () -> Unit,
) {
    // Karta dokunmak dosyayi ACAR (gezgindeki ile AYNI goruntuleyiciler: gorsel,
    // PDF, video, markdown/docx). Eskiden yalniz indirme tusu vardi, teslimati
    // gormek icin once telefona indirmek gerekiyordu.
    SurfaceCard(onClick = onOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, tint = Ui2.colors.ink2)
            Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                Text(output.name, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                Text("${output.size} bayt", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
            }
            if (output.isNew) StatusBadge("Yeni", StatusKind.Done)
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, "İndir", tint = Ui2.colors.accent)
            }
        }
    }
}

@Composable
private fun ProjectArtifactCard(artifact: ProjectArtifact) {
    SurfaceCard {
        Text(artifact.title, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
        if (artifact.detail.isNotBlank()) {
            Text(artifact.detail, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
        if (artifact.backend.isNotBlank()) StatusBadge(artifact.backend)
    }
}

@Composable
private fun HubInlineEmpty(text: String) {
    SurfaceCard {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink2)
    }
}

@Composable
fun HubWorkspaceScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    // Cowork dosya gezginini bu alanın klasöründe açar (parametre: workspace yolu).
    onOpenFiles: (String) -> Unit = {},
) {
    val path = uiState.selectedCoworkWorkspace
    val workspace = uiState.coworkWorkspaces.firstOrNull { it.path == path }
    var matter by remember(path, workspace?.matter) { mutableStateOf(workspace?.matter.orEmpty()) }
    var deleteConfirm by remember(path) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = workspace?.matter?.ifBlank { workspace.name } ?: "Çalışma alanı",
            subtitle = path,
            onBack = onBack,
        )
        if (workspace == null) {
            EmptyState(
                title = "Çalışma alanı bulunamadı",
                description = "Listeyi yenileyip tekrar deneyebilirsin.",
                icon = Icons.Outlined.Workspaces,
            )
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s16),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20),
            ) {
                SectionHeader("Çalışma alanı")
                SurfaceCard {
                    ListRow(
                        title = workspace.name,
                        detail = workspace.path,
                        leading = { Icon(Icons.Outlined.Workspaces, null, tint = Ui2.colors.accent) },
                    )
                    OutlinedTextField(
                        value = matter,
                        onValueChange = { matter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Dosya / konu etiketi") },
                    )
                    OutlinedButton(
                        onClick = { actions.setCoworkWorkspaceMatter(path, matter) },
                        enabled = matter != workspace.matter,
                    ) { Text("Etiketi kaydet") }
                }

                SectionHeader("Aksiyonlar")
                Button(
                    onClick = {
                        actions.startCoworkSession(path)
                        onOpenChat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ui2.colors.accent,
                        contentColor = Ui2.colors.onAccent,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Yeni Cowork oturumu başlat")
                }
                OutlinedButton(
                    onClick = { onOpenFiles(path) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Folder, null, tint = Ui2.colors.accent)
                    Text("Dosyalar")
                }
                HorizontalDivider(color = Ui2.colors.line)
                Text(
                    "Çalışma alanını silmek klasörü, oturum kayıtlarını ve içindeki dosyaları kalıcı olarak kaldırır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.danger,
                )
                OutlinedButton(onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null, tint = Ui2.colors.danger)
                    Text("Çalışma alanını sil", color = Ui2.colors.danger)
                }
            }
        }
    }

    if (deleteConfirm && workspace != null) {
        ConfirmDialog(
            title = "Çalışma alanı silinsin mi?",
            text = "${workspace.name} klasörü, Cowork oturum kayıtları ve klasördeki tüm dosyalar kalıcı olarak silinecek. Bu işlem geri alınamaz.",
            confirmLabel = "Klasörü sil",
            onConfirm = {
                deleteConfirm = false
                actions.deleteCoworkProject(path)
                onBack()
            },
            onDismiss = { deleteConfirm = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDeleteResultSheet(
    result: CompleteProjectDeleteResult,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface,
        shape = RoundedCornerShape(topStart = Ui2Tokens.cornerSheet, topEnd = Ui2Tokens.cornerSheet),
    ) {
        Column(
            Modifier
                .padding(horizontal = Ui2Tokens.screenPadding)
                .padding(bottom = Ui2Tokens.sheetBottom),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
        ) {
            Text(
                text = if (result.ok) "Proje tamamen silindi" else "Proje tamamen silinemedi",
                style = MaterialTheme.typography.titleMedium,
                color = if (result.ok) Ui2.colors.ink else Ui2.colors.danger,
            )
            if (result.error.isNotBlank()) {
                Text(result.error, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.danger)
            }

            // Session results
            if (result.deletedSessionCount > 0 || result.sessionResults.isNotEmpty()) {
                val total = result.sessionResults.size
                val succeeded = result.sessionResults.count { it.ok }
                val failed = result.sessionResults.count { !it.ok }
                Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (total > 0) "$succeeded/$total oturum silindi" else "${result.deletedSessionCount} oturum silindi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed == 0) Ui2.colors.ink else Ui2.colors.attention,
                    )
                }
                result.sessionResults.filter { !it.ok }.forEach { sr ->
                    Text(
                        "✗ ${sr.backend} / ${sr.sessionId.take(12)}… — ${sr.error.ifBlank { "silinemedi" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.danger,
                    )
                }
            }

            // Phase indicators
            Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4), verticalAlignment = Alignment.CenterVertically) {
                val check = if (result.folderDeleted) "✓" else "—"
                Text("$check Proje klasörü ${if (result.folderDeleted) "silindi" else "korunuyor"}", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4), verticalAlignment = Alignment.CenterVertically) {
                val check = if (result.registryDeleted) "✓" else "—"
                Text("$check Uygulama kayıtları ${if (result.registryDeleted) "kaldırıldı" else "korunuyor"}", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
            }

            Spacer(Modifier.height(Ui2Tokens.s8))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
            ) {
                if (result.retryable) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Ui2.colors.accent),
                    ) { Text("Yeniden dene") }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Kapat")
                }
            }
        }
    }
}
