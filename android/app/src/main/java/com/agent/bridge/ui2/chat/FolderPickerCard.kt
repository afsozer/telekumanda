package com.agent.bridge.ui2.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.agent.bridge.DirEntry
import com.agent.bridge.WorkerDirs
import com.agent.bridge.folderBreadcrumb
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.SearchField
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Extracted folder picker UI card (Phase 5).
 *
 * Renders: search bar, clickable breadcrumb, parent folder ("Üst klasöre çık"),
 * current-folder selection + favorite toggle, subfolder list, and collapsible
 * "Son kullanılanlar" / "Favoriler" shortcuts.
 */
@Composable
fun FolderPickerCard(
    dirs: WorkerDirs?,
    searchResults: List<DirEntry>,
    selectedCwd: String,
    recentPaths: List<String>,
    favoritePaths: List<String>,
    isFavorite: Boolean,
    base: String,
    onNavigateTo: (path: String) -> Unit,
    onSelectCwd: (path: String) -> Unit,
    onToggleFavorite: (path: String) -> Unit,
    onSearch: (query: String) -> Unit,
    onUseCurrentFolder: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) } // true when server call is in flight
    var expandedRecent by remember { mutableStateOf(false) }
    var expandedFavorites by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf("") }

    // 300 ms debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery == lastQuery) return@LaunchedEffect
        lastQuery = searchQuery
        if (searchQuery.length >= 2) {
            isSearching = true
            onSearch(searchQuery)
        } else {
            onSearch("")
        }
    }

    // When search results arrive, stop searching spinner
    LaunchedEffect(searchResults) {
        isSearching = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
        // 1. Arama alanı
        SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Klasör ara…",
        )

        val showSearchResults = searchQuery.length >= 2

        if (showSearchResults) {
            // Show search results
            if (isSearching && searchResults.isEmpty()) {
                Text(
                    "Aranıyor…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ui2.colors.ink3,
                    modifier = Modifier.padding(horizontal = Ui2Tokens.s4, vertical = Ui2Tokens.s8),
                )
            } else if (searchResults.isEmpty()) {
                Text(
                    "Sonuç bulunamadı",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ui2.colors.ink3,
                    modifier = Modifier.padding(horizontal = Ui2Tokens.s4, vertical = Ui2Tokens.s8),
                )
            } else {
                searchResults.forEach { entry ->
                    ListRow(
                        title = entry.name,
                        detail = entry.path,
                        onClick = { onNavigateTo(entry.path) },
                    )
                }
            }
        } else {
            // Normal folder view

            // 2. Breadcrumb
            if (base.isNotBlank()) {
                val crumbs = folderBreadcrumb(base)
                if (crumbs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Ui2Tokens.s4),
                        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        crumbs.forEachIndexed { index, (name, path) ->
                            if (index > 0) {
                                Text("›", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
                            }
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (index == crumbs.lastIndex) Ui2.colors.ink else Ui2.colors.accent,
                                modifier = Modifier.clickable { onNavigateTo(path) },
                            )
                        }
                    }
                }
            }

            // 3. Üst klasöre çık
            if (dirs != null && dirs.parent.isNotBlank()) {
                ListRow(
                    title = "Üst klasöre çık",
                    detail = dirs.parent,
                    leading = {
                        Icon(Icons.Default.ArrowUpward, null, tint = Ui2.colors.accent)
                    },
                    onClick = { onNavigateTo(dirs.parent) },
                )
            }

            // 4. Bu klasörü kullan + 5. Favori
            if (base.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.s4),
                    horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Konum: ", style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink2)
                    StatusBadge(text = base)
                    // Favorite toggle
                    IconButton(
                        onClick = { onToggleFavorite(base) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (isFavorite) "Favorilerden çıkar" else "Favorilere ekle",
                            tint = if (isFavorite) Ui2.colors.attention else Ui2.colors.ink3,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                val isCurrentCwdSelected = selectedCwd == base
                ListRow(
                    title = "Bu klasörü kullan",
                    detail = base,
                    onClick = {
                        onSelectCwd(base)
                        onUseCurrentFolder()
                    },
                    trailing = {
                        if (isCurrentCwdSelected) {
                            Icon(
                                Icons.Default.Check,
                                "Seçili",
                                tint = Ui2.colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )
            }

            // 6. Alt klasör listesi
            if (dirs != null) {
                dirs.dirs.filter { it.type == "dir" }.forEach { dir ->
                    val isSelected = selectedCwd == dir.path
                    ListRow(
                        title = dir.name,
                        detail = dir.path,
                        onClick = { onNavigateTo(dir.path) },
                        trailing = {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    "Seçili",
                                    tint = Ui2.colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }

            // 7. Daraltılabilir "Son kullanılanlar" ve "Favoriler"
            if (recentPaths.isNotEmpty()) {
                ListRow(
                    title = "Son kullanılanlar (${recentPaths.size})",
                    onClick = { expandedRecent = !expandedRecent },
                    trailing = {
                        Text(
                            if (expandedRecent) "▾" else "▸",
                            color = Ui2.colors.ink3,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                AnimatedVisibility(expandedRecent) {
                    Column {
                        recentPaths.take(12).forEach { recentPath ->
                            val isSelected = selectedCwd == recentPath
                            ListRow(
                                title = recentPath.substringAfterLast("\\").substringAfterLast("/"),
                                detail = recentPath,
                                onClick = { onNavigateTo(recentPath) },
                                trailing = {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            "Seçili",
                                            tint = Ui2.colors.accent,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (favoritePaths.isNotEmpty()) {
                ListRow(
                    title = "Favoriler (${favoritePaths.size})",
                    onClick = { expandedFavorites = !expandedFavorites },
                    trailing = {
                        Text(
                            if (expandedFavorites) "▾" else "▸",
                            color = Ui2.colors.ink3,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                AnimatedVisibility(expandedFavorites) {
                    Column {
                        favoritePaths.forEach { favPath ->
                            val isSelected = selectedCwd == favPath
                            ListRow(
                                title = favPath.substringAfterLast("\\").substringAfterLast("/"),
                                detail = favPath,
                                onClick = { onNavigateTo(favPath) },
                                trailing = {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            "Seçili",
                                            tint = Ui2.colors.accent,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
