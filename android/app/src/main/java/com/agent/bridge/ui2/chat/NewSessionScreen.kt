package com.agent.bridge.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.availableBackendIds
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.backendUsesWorkspaces
import com.agent.bridge.coworkWorkspaces
import com.agent.bridge.startBackendSession
import com.agent.bridge.enterBackend
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.ProviderMark
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun NewSessionScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val providers = uiState.availableBackendIds()
    var chosen by remember { mutableStateOf(uiState.backend ?: "claude-app") }
    var chosenCwd by remember { mutableStateOf("") }
    // Cowork workspace seçimi EKRANA ÖZEL tutulur
    var chosenWorkspace by remember { mutableStateOf("") }
    var newWorkspaceName by remember { mutableStateOf("") }

    // Folder picker preferences
    val recentPaths = remember { actions.folderPickerRecent() }
    val favoritePaths = remember { actions.folderPickerFavorites() }

    LaunchedEffect(chosen) {
        if (backendUsesWorkspaces(chosen)) {
            actions.loadCoworkWorkspaces()
        } else {
            actions.loadWorkerDirs("")
        }
    }

    val isStartEnabled = if (backendUsesWorkspaces(chosen)) {
        true
    } else {
        chosenCwd.ifBlank { uiState.workerDirs?.base.orEmpty() }.isNotBlank()
    }

    val dirs = uiState.workerDirs
    val base = dirs?.base.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui2.colors.bg)
    ) {
        ScreenHeader(title = "Yeni oturum", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s16),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20)
        ) {
            SectionHeader("Sağlayıcı")
            SurfaceCard(verticalGap = Ui2Tokens.s8) {
                providers.forEach { providerId ->
                    val selected = chosen == providerId
                    ListRow(
                        title = BACKEND_LABELS[providerId] ?: providerId,
                        leading = { ProviderMark(providerMonogram(providerId)) },
                        onClick = {
                            chosen = providerId
                            actions.enterBackend(providerId)
                        },
                        trailing = {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    "Seçili",
                                    tint = Ui2.colors.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }
            }

            // Başlat butonu — plan 8.6: klasör seçicinin üstünde kalır
            Button(
                onClick = {
                    if (backendUsesWorkspaces(chosen) && chosenWorkspace.isBlank() && newWorkspaceName.isNotBlank()) {
                        actions.startCoworkNamedWorkspace(newWorkspaceName.trim())
                    } else {
                        val finalCwd = if (backendUsesWorkspaces(chosen)) chosenWorkspace
                            else chosenCwd.ifBlank { dirs?.base.orEmpty() }
                        actions.startBackendSession(chosen, finalCwd)
                        // Plan 8.3: recent'e yalnız başarılı başlatmada ekle
                        if (!backendUsesWorkspaces(chosen) && finalCwd.isNotBlank()) {
                            actions.folderPickerAddRecent(finalCwd)
                        }
                    }
                    onDone()
                },
                enabled = isStartEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = Ui2Tokens.s8),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ui2.colors.accent,
                    contentColor = Ui2.colors.onAccent,
                )
            ) {
                Text("Başlat")
            }

            val targetHeader = if (backendUsesWorkspaces(chosen)) "Çalışma alanı" else "Proje klasörü"
            SectionHeader(targetHeader)
            SurfaceCard(verticalGap = Ui2Tokens.s8) {
                if (backendUsesWorkspaces(chosen)) {
                    // Cowork workspace picker — unchanged
                    ListRow(
                        title = "Yeni çalışma alanı",
                        detail = "Bir ad yaz ya da boş bırak (otomatik ad üretilir)",
                        onClick = { chosenWorkspace = "" },
                        trailing = {
                            if (chosenWorkspace.isBlank()) {
                                Icon(
                                    Icons.Default.Check,
                                    "Seçili",
                                    tint = Ui2.colors.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                    if (chosenWorkspace.isBlank()) {
                        OutlinedTextField(
                            value = newWorkspaceName,
                            onValueChange = { newWorkspaceName = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.s4),
                            singleLine = true,
                            label = { Text("Çalışma alanı adı (opsiyonel)") },
                        )
                    }
                    uiState.coworkWorkspaces.forEach { ws ->
                        val isSelected = chosenWorkspace == ws.path
                        ListRow(
                            title = ws.name,
                            detail = ws.path,
                            onClick = { chosenWorkspace = ws.path },
                            trailing = {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        "Seçili",
                                        tint = Ui2.colors.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }
                } else {
                    // Direct provider — use extracted FolderPickerCard
                    FolderPickerCard(
                        dirs = dirs,
                        searchResults = uiState.folderSearchResults,
                        selectedCwd = chosenCwd,
                        recentPaths = recentPaths,
                        favoritePaths = favoritePaths,
                        isFavorite = actions.folderPickerIsFavorite(base),
                        base = base,
                        onNavigateTo = { path -> actions.loadWorkerDirs(path) },
                        onSelectCwd = { path -> chosenCwd = path },
                        onToggleFavorite = { path -> actions.folderPickerToggleFavorite(path) },
                        onSearch = { query -> actions.updateFolderSearchQuery(base, query) },
                        onUseCurrentFolder = { /* selection handled by onSelectCwd */ },
                    )
                }
            }
        }
    }
}
