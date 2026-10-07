package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
fun HubProjectSecurityScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
) {
    val detail = uiState.selectedProjectDetail
    val project = detail?.project
    if (uiState.projectDetailLoading) {
        Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
            ScreenHeader(title = "Güvenlik & MCP", onBack = onBack)
            LoadingSkeleton(Modifier.padding(Ui2Tokens.screenPadding), rows = 5)
        }
        return
    }
    if (detail == null || project == null) {
        Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
            ScreenHeader(title = "Güvenlik & MCP", onBack = onBack)
            EmptyState(title = "Proje yüklenemedi", description = "Proje detayını yeniden açıp deneyebilirsin.")
        }
        return
    }

    val profileIds = listOf("safe", "standard", "full", "custom")
    var profileIndex by remember(project.id, detail.security.profile) {
        mutableStateOf(profileIds.indexOf(detail.security.profile).coerceAtLeast(0))
    }
    val selectedProfile = profileIds[profileIndex]
    var customPermissions by remember(project.id, detail.security.customPermissions) {
        mutableStateOf(detail.security.customPermissions)
    }
    var readRoot by remember(project.id, detail.security.readRoots) {
        mutableStateOf(detail.security.readRoots.firstOrNull() ?: project.path)
    }
    var writeRoot by remember(project.id, detail.security.writeRoots) {
        mutableStateOf(detail.security.writeRoots.firstOrNull() ?: project.path)
    }
    var confirmFullAccess by remember(project.id) { mutableStateOf(false) }

    val mcpProviders = detail.sessions.map { it.backend }.filter { it == "codex-app" || it == "opencode2-app" }.distinct()
    var mcpProviderIndex by remember(project.id, detail.mcpProfile.provider, mcpProviders) {
        mutableStateOf(mcpProviders.indexOf(detail.mcpProfile.provider).coerceAtLeast(0))
    }
    val mcpProvider = mcpProviders.getOrNull(mcpProviderIndex).orEmpty()
    var selectedMcpNames by remember(project.id, detail.mcpProfile) { mutableStateOf(detail.mcpProfile.enabledNames.toSet()) }
    var confirmMcpApply by remember(project.id) { mutableStateOf(false) }

    LaunchedEffect(project.id, mcpProvider) {
        if (mcpProvider.isNotBlank()) actions.loadProjectMcpServers(project.id, mcpProvider)
    }
    LaunchedEffect(uiState.projectMcpServers, mcpProvider) {
        selectedMcpNames = if (detail.mcpProfile.provider == mcpProvider) detail.mcpProfile.enabledNames.toSet()
        else uiState.projectMcpServers.filter { it.enabled }.map { it.name }.toSet()
    }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "Güvenlik & MCP", subtitle = project.displayName, onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s16),
        ) {
            SectionHeader("Proje güvenliği")
            SurfaceCard {
                Text(
                    "Profil otomatik uygulanmaz. Seçim yalnız Uygula düğmesiyle değişir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                )
                SegmentedTabs(
                    options = listOf("Güvenli", "Standart", "Tam", "Özel"),
                    selectedIndex = profileIndex,
                    onSelect = { profileIndex = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when (selectedProfile) {
                        "safe" -> "Planlama ve kısıtlı çalışma; yazma/yürütme yetkileri verilmez."
                        "full" -> "İzin kontrollerini aşabilir; yalnız güvendiğin projelerde kullan."
                        "custom" -> "Sağlayıcı izinlerini ve proje içindeki yol sınırlarını sen belirlersin."
                        else -> "Riskli işlemlerde ajan kullanıcı onayı ister."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedProfile == "full") Ui2.colors.danger else Ui2.colors.ink2,
                )
                if (selectedProfile == "custom") {
                    detail.sessions.map { it.backend to it.backendLabel }.distinct().forEach { (backend, label) ->
                        val permissionIds = listOf("safe", "standard", "full")
                        val current = customPermissions[backend] ?: "standard"
                        Text(label, style = MaterialTheme.typography.labelMedium, color = Ui2.colors.ink)
                        SegmentedTabs(
                            options = listOf("Güvenli", "Standart", "Tam"),
                            selectedIndex = permissionIds.indexOf(current).coerceAtLeast(0),
                            onSelect = { customPermissions = customPermissions + (backend to permissionIds[it]) },
                        )
                    }
                    OutlinedTextField(readRoot, { readRoot = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Okunabilir kök") })
                    OutlinedTextField(writeRoot, { writeRoot = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Yazılabilir kök") })
                    Text("Özel yollar proje klasörünün dışına çıkamaz.", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                }
                Button(
                    onClick = {
                        if (selectedProfile == "full" || (selectedProfile == "custom" && customPermissions.values.any { it == "full" })) {
                            confirmFullAccess = true
                        } else {
                            actions.applyProjectSecurity(project.id, selectedProfile, customPermissions, readRoot, writeRoot)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ui2.colors.accent, contentColor = Ui2.colors.onAccent),
                ) { Text(if (selectedProfile == detail.security.profile) "Profili yeniden uygula" else "Profili uygula") }
                StatusBadge("Aktif: ${securityProfileLabel(detail.security.profile)}")
            }

            if (mcpProviders.isNotEmpty()) {
                SectionHeader("Proje MCP profili")
                SurfaceCard {
                    Text(
                        "Profil projeyle saklanır; uygulamak sağlayıcının global MCP config'ini ve diğer projeleri etkileyebilir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.danger,
                    )
                    SegmentedTabs(
                        options = mcpProviders.map { if (it == "codex-app") "Codex" else "OpenCode" },
                        selectedIndex = mcpProviderIndex,
                        onSelect = { mcpProviderIndex = it },
                    )
                    Text(
                        "Yalnız bu projede CANLI oturumu olan ve proje profili destekleyen sağlayıcılar (Codex/OpenCode) listelenir. Claude ve Antigravity MCP'si globaldir: Ayarlar > MCP sunucuları.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ui2.colors.ink3,
                    )
                    if (uiState.projectMcpLoading) {
                        LoadingSkeleton(rows = 2)
                    } else {
                        uiState.projectMcpServers.forEach { server ->
                            Row(Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = server.name in selectedMcpNames,
                                    onCheckedChange = { enabled ->
                                        selectedMcpNames = if (enabled) selectedMcpNames + server.name else selectedMcpNames - server.name
                                    },
                                    enabled = !server.managed,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(server.name, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
                                    if (server.managed) Text("Sağlayıcı tarafından yönetiliyor", style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { confirmMcpApply = true },
                        enabled = mcpProvider.isNotBlank() && !uiState.projectMcpLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("MCP profilini uygula") }
                }
            }

            if (detail.audit.isNotEmpty()) {
                SectionHeader("Denetim günlüğü")
                SurfaceCard {
                    detail.audit.take(25).forEach { event ->
                        Text(auditActionLabel(event.action), style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
                        Text(listOf(event.actor, event.at).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Ui2.colors.ink3)
                    }
                }
            }
        }
    }

    if (confirmFullAccess) {
        ConfirmDialog(
            title = "Tam erişim verilsin mi?",
            text = "Desteklenen ajanlarda izin kontrolleri kaldırılabilir; komutlar çalıştırılabilir ve proje dosyaları değiştirilebilir.",
            confirmLabel = "Tam erişimi uygula",
            onConfirm = {
                confirmFullAccess = false
                actions.applyProjectSecurity(project.id, selectedProfile, customPermissions, readRoot, writeRoot)
            },
            onDismiss = { confirmFullAccess = false },
        )
    }
    if (confirmMcpApply) {
        ConfirmDialog(
            title = "Global MCP ayarı değişsin mi?",
            text = "Bu proje profili ${if (mcpProvider == "codex-app") "Codex" else "OpenCode"} global config'ini değiştirir ve diğer projeleri de etkileyebilir.",
            confirmLabel = "Global ayarı uygula",
            destructive = false,
            onConfirm = {
                confirmMcpApply = false
                actions.applyProjectMcpProfile(project.id, mcpProvider, selectedMcpNames)
            },
            onDismiss = { confirmMcpApply = false },
        )
    }
}

private fun securityProfileLabel(profile: String) = when (profile) {
    "safe" -> "Güvenli"
    "full" -> "Tam erişim"
    "custom" -> "Özel"
    else -> "Standart"
}

private fun auditActionLabel(action: String) = when (action) {
    "security_profile_applied" -> "Güvenlik profili uygulandı"
    "project_label_changed" -> "Proje adı değiştirildi"
    "mcp_profile_applied" -> "MCP profili uygulandı"
    "agent_command_observed" -> "Ajan komutu kaydedildi"
    "agent_file_changed" -> "Ajan dosya değişikliği kaydedildi"
    else -> action
}
