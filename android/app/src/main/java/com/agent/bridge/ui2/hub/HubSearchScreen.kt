package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.GlobalSearchHit
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SearchField
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun HubSearchScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenChat: () -> Unit,
) {
    var query by remember { mutableStateOf(uiState.search.query) }

    LaunchedEffect(query) { actions.updateGlobalSearchQuery(query) }

    val st = uiState.search
    val hitsByType = remember(st.hits) { st.hits.groupBy { it.type } }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "Ara", onBack = onBack)

        SearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s12),
            placeholder = "Proje, oturum veya mesaj ara…",
        )

        when {
            st.loading -> LoadingSkeleton(Modifier.padding(horizontal = Ui2Tokens.screenPadding), rows = 6)
            st.error.isNotBlank() -> Text(
                st.error,
                style = MaterialTheme.typography.bodyMedium,
                color = Ui2.colors.danger,
                modifier = Modifier.padding(Ui2Tokens.screenPadding),
            )
            query.length < 2 -> Text(
                "Aramak için en az 2 karakter yazın",
                style = MaterialTheme.typography.bodyMedium,
                color = Ui2.colors.ink3,
                modifier = Modifier.padding(Ui2Tokens.screenPadding),
            )
            st.hits.isEmpty() && query.length >= 2 -> Text(
                "Sonuç bulunamadı",
                style = MaterialTheme.typography.bodyMedium,
                color = Ui2.colors.ink3,
                modifier = Modifier.padding(Ui2Tokens.screenPadding),
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                ) {
                    for ((type, hits) in hitsByType) {
                        val heading = when (type) {
                            "project" -> "Projeler"
                            "session" -> "Oturumlar"
                            else -> "Mesajlar"
                        }
                        item { SectionHeader(heading) }
                        items(hits, key = { it.id }) { hit ->
                            SearchHitCard(
                                hit = hit,
                                onClick = {
                                    when (hit.type) {
                                        "project" -> {
                                            actions.loadProjectDetail(hit.projectId)
                                            onOpenProject()
                                        }
                                        "session" -> {
                                            val session = com.agent.bridge.ProjectSession(
                                                backend = hit.backend, backendLabel = hit.backendLabel,
                                                sessionId = hit.sessionId, model = "", status = "",
                                                summary = "", title = hit.title, nativeSessionId = "",
                                                mtime = hit.mtime, live = false, pinned = false,
                                                archived = false, container = hit.container, threadId = "",
                                            )
                                            actions.openProjectSession(session, hit.projectPath)
                                            onOpenChat()
                                        }
                                        "message" -> {
                                            // Oturum açma + deep-link tek sıralı ViewModel
                                            // işleminde (yanlış/eski oturumda arama yapılmaz).
                                            actions.openGlobalSearchMessageHit(hit, query)
                                            onOpenChat()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitCard(hit: GlobalSearchHit, onClick: () -> Unit) {
    val icon = when (hit.type) {
        "project" -> Icons.Filled.Folder
        "session" -> Icons.Filled.Chat
        else -> Icons.Outlined.Article
    }
    val label = hit.backendLabel.ifBlank {
        if (hit.backend.isEmpty()) "" else BACKEND_LABELS[hit.backend] ?: hit.backend
    }
    SurfaceCard(modifier = Modifier.clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(Ui2Tokens.s12),
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, null, tint = Ui2.colors.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                Text(hit.title.ifBlank { hit.snippet }, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hit.snippet.isNotBlank() && hit.type != "project") {
                    Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2, maxLines = 2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                    if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                    if (hit.projectName.isNotBlank()) Text(hit.projectName, style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                }
            }
        }
    }
}
