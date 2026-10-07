package com.agent.bridge.ui2.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.availableBackendIds
import com.agent.bridge.BackendOption
import com.agent.bridge.HubProjectItem
import com.agent.bridge.ProjectChatRequest
import com.agent.bridge.RemoteUiState
import com.agent.bridge.backendEffortOptions
import com.agent.bridge.backendModelOptions
import com.agent.bridge.backendPermissionModeOptions
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectQuickChatSheet(
    uiState: RemoteUiState,
    project: HubProjectItem,
    onStart: (ProjectChatRequest, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val isCowork = project.workspacePath != null
    val availableProviders = if (isCowork) {
        listOf(
            BackendOption("claude-app", "Claude"),
            BackendOption("codex-app", "Codex"),
            BackendOption("opencode2-app", "OpenCode"),
        )
    } else {
        uiState.availableBackendIds().map { id ->
            BackendOption(id, BACKEND_LABELS[id] ?: id)
        }
    }

    val summary = uiState.projectSummaries.firstOrNull { it.id == project.projectId }
    val quickStartProvider = summary?.quickStartProvider.orEmpty()
    val quickStartModel = summary?.quickStartModel.orEmpty()
    val quickStartPermissionMode = summary?.quickStartPermissionMode.orEmpty()
    val quickStartEffort = summary?.quickStartEffort.orEmpty()

    var chosenProvider by remember {
        mutableStateOf<String>(
            quickStartProvider.ifBlank {
                val latestSession = uiState.selectedProjectDetail?.sessions?.firstOrNull()
                latestSession?.backend?.takeIf { it.isNotBlank() }
                    ?: availableProviders.firstOrNull()?.id
                    ?: "claude-app"
            }
        )
    }

    var chosenModel by remember {
        mutableStateOf<String>(
            quickStartModel.ifBlank {
                val latestSession = uiState.selectedProjectDetail?.sessions?.firstOrNull { it.backend == chosenProvider }
                latestSession?.model?.takeIf { it.isNotBlank() }
                    ?: uiState.backendModelOptions(chosenProvider).firstOrNull()?.id
                    ?: ""
            }
        )
    }

    var chosenPermissionMode by remember {
        mutableStateOf<String>(
            // Kayıtlı bir tercih yoksa: varsa "auto" (claude-app'in varsayılanı), yoksa
            // yolo/bypass dengi, o da yoksa listenin ilki.
            quickStartPermissionMode.ifBlank {
                val opts = uiState.backendPermissionModeOptions(chosenProvider)
                opts.firstOrNull { it.id == "auto" }?.id
                    ?: opts.firstOrNull { it.id == "bypassPermissions" || it.id == "yolo" }?.id
                    ?: opts.firstOrNull()?.id ?: ""
            }
        )
    }

    var chosenEffort by remember {
        mutableStateOf<String>(
            quickStartEffort.ifBlank {
                uiState.backendEffortOptions(chosenProvider).firstOrNull()?.id ?: ""
            }
        )
    }

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var permissionMenuExpanded by remember { mutableStateOf(false) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    
    var starting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ui2.colors.surface,
        shape = RoundedCornerShape(topStart = Ui2Tokens.cornerSheet, topEnd = Ui2Tokens.cornerSheet),
    ) {
        Column(
            Modifier
                .padding(horizontal = Ui2Tokens.screenPadding)
                .padding(bottom = Ui2Tokens.sheetBottom),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16)
        ) {
            Column(Modifier.padding(bottom = Ui2Tokens.s4)) {
                Text("Hızlı Yeni Sohbet", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink)
                Text(
                    project.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            SurfaceCard(verticalGap = Ui2Tokens.s4) {
                // Provider Selection
                Box(Modifier.fillMaxWidth()) {
                    ListRow(
                        title = "Sağlayıcı",
                        detail = availableProviders.firstOrNull { it.id == chosenProvider }?.label ?: chosenProvider,
                        onClick = { if (!starting) providerMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        availableProviders.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    chosenProvider = p.id
                                    providerMenuExpanded = false
                                    // Update dependencies
                                    val models = uiState.backendModelOptions(p.id)
                                    chosenModel = models.firstOrNull()?.id ?: ""
                                    val perms = uiState.backendPermissionModeOptions(p.id)
                                    chosenPermissionMode = perms.firstOrNull()?.id ?: ""
                                    val efforts = uiState.backendEffortOptions(p.id)
                                    chosenEffort = efforts.firstOrNull()?.id ?: ""
                                }
                            )
                        }
                    }
                }

                // Model Selection
                val models = uiState.backendModelOptions(chosenProvider)
                if (models.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth()) {
                        ListRow(
                            title = "Model",
                            detail = models.firstOrNull { it.id == chosenModel }?.label ?: chosenModel,
                            onClick = { if (!starting) modelMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.label) },
                                    onClick = {
                                        chosenModel = m.id
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Permission Mode Selection
                val perms = uiState.backendPermissionModeOptions(chosenProvider)
                if (perms.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth()) {
                        ListRow(
                            title = "İzin Modu",
                            detail = perms.firstOrNull { it.id == chosenPermissionMode }?.label ?: chosenPermissionMode,
                            onClick = { if (!starting) permissionMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = permissionMenuExpanded,
                            onDismissRequest = { permissionMenuExpanded = false }
                        ) {
                            perms.forEach { perm ->
                                DropdownMenuItem(
                                    text = { Text(perm.label) },
                                    onClick = {
                                        chosenPermissionMode = perm.id
                                        permissionMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Effort Selection
                val efforts = uiState.backendEffortOptions(chosenProvider)
                if (efforts.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth()) {
                        ListRow(
                            title = "Çaba / Effort",
                            detail = efforts.firstOrNull { it.id == chosenEffort }?.label ?: chosenEffort,
                            onClick = { if (!starting) effortMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = effortMenuExpanded,
                            onDismissRequest = { effortMenuExpanded = false }
                        ) {
                            efforts.forEach { eff ->
                                DropdownMenuItem(
                                    text = { Text(eff.label) },
                                    onClick = {
                                        chosenEffort = eff.id
                                        effortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    starting = true
                    onStart(
                        ProjectChatRequest(
                            projectId = project.projectId.orEmpty(),
                            projectPath = project.path,
                            workspacePath = project.workspacePath,
                            provider = chosenProvider,
                            model = chosenModel,
                            permissionMode = chosenPermissionMode,
                            effort = chosenEffort
                        ),
                        { starting = false }
                    )
                },
                enabled = !starting && chosenProvider.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ui2.colors.accent,
                    contentColor = Ui2.colors.onAccent,
                )
            ) {
                Text(if (starting) "Başlatılıyor…" else "Başlat")
            }
        }
    }
}
