package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.hubProjects
import com.agent.bridge.hubSummary
import com.agent.bridge.ui2.components.AdaptiveCells
import com.agent.bridge.ui2.components.ContentWidth
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusDot
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Belgelik'in paket adı cihazdan okundu (`pm list packages`, 10.08.2026).
// Uygulama güncellenip paket adı değişirse tuş "bulunamadı" der, sessizce
// başarısız olmaz.
private const val BELGELIK_PAKETI = "com.belgelik.hakimlik_app"

@Composable
fun HubRootScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onOpenWorkspaces: () -> Unit,
    // Tek bir çalışma alanına giriş. onOpenWorkspaces (liste) ile karıştırılmamalı:
    // önizleme kartları listeyi açıyordu, oysa amaç doğrudan o alana girmekti.
    onOpenWorkspace: () -> Unit,
    onOpenProjects: () -> Unit,
    // Tek bir projenin/çalışma alanının GÜNCEL detay ekranı. onOpenProjects
    // (liste) ile karıştırılmamalı.
    onOpenProject: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val notesState by actions.coworkNotesState.collectAsState()
    LaunchedEffect(Unit) {
        actions.loadProjects()
        actions.loadCoworkWorkspaces()
        actions.loadOperations()
        actions.loadUsage()
        actions.loadCoworkNotes()
    }

    val summary = uiState.hubSummary()
    val projects = uiState.hubProjects()

    // ContentWidth BASLIGI DA sariyor: yalniz listeyi sarsaydik saglaki yenile
    // ikonu icerikten kopup ekranin uzak kosesinde kalirdi.
    ContentWidth(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
      Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Merkez",
            subtitleContent = {
                val ok = uiState.healthOk && uiState.protocolCompatible
                StatusDot(if (ok) StatusKind.Done else StatusKind.Danger)
                Text(
                    if (ok) "Köprü bağlı" else "Köprü bağlantısı yok",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink2,
                )
            },
            trailing = {
                IconButton(onClick = {
                    actions.loadProjects()
                    actions.loadCoworkWorkspaces()
                    actions.loadOperations()
                    actions.loadUsage(force = true)
                    actions.loadCoworkNotes()
                }) {
                    Icon(Icons.Default.Refresh, "Merkezi yenile", tint = Ui2.colors.ink2)
                }
            },
        )

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
                SectionHeader("Genel görünüm")
            }
            item {
                SurfaceCard {
                    // Cowork çalışma alanları ayrı giriş (Projeler'in üstünde):
                    // proje klasörleriyle karışmasın, ikisi de kendi listesine açılır.
                    val workspaceCount = projects.count { it.isCoworkWorkspace }
                    // Geniş ekranda iki sütun; telefonda tek sütun (AdaptiveCells
                    // dar genişlikte eski Column davranışına düşüyor). Sıra
                    // KORUNUR: soldan sağa, sonra alt satır.
                    AdaptiveCells(listOf(
                    {
                    ListRow(
                        title = "Çalışma Alanları",
                        detail = "$workspaceCount Cowork çalışma alanı",
                        leading = { Icon(Icons.Outlined.Workspaces, null, tint = Ui2.colors.accent) },
                        // Cowork teslimatlarinin rozeti BURADA: teslimatlar bu
                        // listede duruyor, Projeler'de degil.
                        trailing = {
                            if (summary.workspaceNewDeliveryCount > 0) {
                                StatusBadge("${summary.workspaceNewDeliveryCount} yeni teslimat", StatusKind.Done)
                            }
                        },
                        onClick = onOpenWorkspaces,
                    )
                    }, {
                    ListRow(
                        title = "Projeler",
                        detail = "${projects.size - workspaceCount} proje",
                        leading = { Icon(Icons.Outlined.FolderOpen, null, tint = Ui2.colors.accent) },
                        trailing = {
                            if (summary.newDeliveryCount > 0) {
                                StatusBadge("${summary.newDeliveryCount} yeni teslimat", StatusKind.Done)
                            }
                        },
                        onClick = onOpenProjects,
                    )
                    }, {
                    ListRow(
                        title = "Kullanım",
                        detail = "${summary.usageGroupCount} sağlayıcı grubu",
                        leading = { Icon(Icons.Outlined.Assessment, null, tint = Ui2.colors.ink2) },
                        onClick = onOpenUsage,
                    )
                    }, {
                    ListRow(
                        title = "Notlarım",
                        detail = "${notesState.notes.size} not · Genel ve proje notları",
                        leading = { Icon(Icons.Outlined.NoteAlt, null, tint = Ui2.colors.ink2) },
                        onClick = onOpenNotes,
                    )
                    }, {
                    ListRow(
                        title = "Dosyalar",
                        detail = "PC gezgini ve telefon indirilenleri",
                        leading = { Icon(Icons.Default.Folder, null, tint = Ui2.colors.ink3) },
                        onClick = onOpenFiles,
                    )
                    // Belgelik telefonun KENDİ uygulaması; köprüyle işi yok, tuş
                    // yalnız launcher intent'i atar. Merkez'de duruyor çünkü
                    // günlük iş akışının parçası ve ana ekranda aramaktan hızlı.
                    }, {
                    ListRow(
                        title = "Belgelik",
                        detail = "Telefondaki Belgelik uygulamasını açar",
                        leading = { Icon(Icons.Outlined.Inventory2, null, tint = Ui2.colors.ink2) },
                        onClick = { actions.openPhoneApp(BELGELIK_PAKETI, "Belgelik") },
                    )
                    },
                    ))
                }
            }

            // Merkez'in altındaki önizleme ÇALIŞMA ALANLARI: kullanıcı günlük
            // işini burada yürütüyor, proje klasörlerine nadiren giriyor
            // (kullanıcı kararı 05.08.2026). Projelerin tam listesi yukarıdaki
            // "Projeler" satırında duruyor.
            val recentWorkspaces = projects.filter { it.isCoworkWorkspace }
            item {
                SectionHeader("Son çalışma alanları", actionLabel = "Tümü ›", onAction = onOpenWorkspaces)
            }
            if (recentWorkspaces.isEmpty()) {
                item {
                    SurfaceCard {
                        Row(
                            Modifier.fillMaxWidth().padding(Ui2Tokens.s8),
                            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Workspaces, null, tint = Ui2.colors.ink3)
                            Text(
                                "Henüz Cowork çalışma alanı yok.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ui2.colors.ink2,
                            )
                        }
                    }
                }
            } else {
                items(recentWorkspaces.take(3), key = { it.key }) { workspace ->
                    // Çalışma alanları listesindeki kartla AYNI akış. projectId
                    // KONTROLÜ ŞART: kayıt varsa güncel proje detayına gidilir,
                    // yoksa eski çalışma alanı ekranına düşülür. İlk denemede bu
                    // kontrol atlanmıştı ve kart koşulsuz eski ekranı açıyordu.
                    // Kayıt canlı oturumun cwd'sinden doğuyor (bridge/projects.mjs),
                    // yani yalnız hiç oturum açılmamış alanda projectId boş olur.
                    SurfaceCard(onClick = {
                        val id = workspace.projectId
                        if (id != null) {
                            actions.loadProjectDetail(id)
                            onOpenProject()
                        } else {
                            actions.selectCoworkWorkspace(workspace.workspacePath.orEmpty())
                            onOpenWorkspace()
                        }
                    }) {
                        Text(workspace.title, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
                        Text(
                            "${workspace.sessionCount} oturum · ${workspace.outputCount} teslimat" +
                                if (workspace.runningCount > 0) " · ${workspace.runningCount} çalışıyor" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ui2.colors.ink2,
                        )
                    }
                }
            }
        }
    }
  }
}
