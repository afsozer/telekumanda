package com.agent.bridge.ui2.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.components.IconPillAction
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

private val noteAiActions = listOf(
    "ozetle" to "Özetle",
    "formatla" to "Formatla",
    "duzelt" to "Düzelt",
)

// "AI'a sor" seçenekleri. Cowork sağlayıcılarıyla aynı üçlü; not bir sohbete
// bağlam olacaksa dosyayı okuyabilen bir ajan gerekiyor, ajansız yollar (agy
// tek atışlık) burada işe yaramaz.
private val noteAskProviders = listOf(
    "claude-app" to "Claude",
    "codex-app" to "Codex",
    "opencode2-app" to "OpenCode",
    "omp" to "OMP",
)

/**
 * Eylem şeridine giren tek AI tuşu: üç yıldız ikonu, menekşe. Basınca menü,
 * basılı tutunca adı çıkar (IconPillAction).
 *
 * Durum rozetleri ve önizleme diyaloğu burada DEĞİL — onlar [NoteAiStatus]'ta.
 * Şerit tek satır kalsın diye ayrıldılar: rozet de aynı bileşende olsaydı
 * şeridin içinde büyüyüp satırı bozardı.
 */
@Composable
internal fun NoteAiMenuAction(
    actions: RemoteViewModel,
    onRun: (String) -> Unit = { action -> actions.runNoteAi(action) },
    // Sohbet ekranına geçiş. null ise "AI'a sor" maddesi hiç çizilmez —
    // gidecek yeri olmayan bir düğme koymaktansa yokluğu dürüst.
    onOpenChat: (() -> Unit)? = null,
) {
    val state by actions.noteAiState.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var askOpen by remember { mutableStateOf(false) }

    // Box, DropdownMenu'nün çapası: menü düğmenin altına açılır.
    Box {
        IconPillAction(
            icon = Icons.Outlined.AutoAwesome,
            label = if (state.running) "AI çalışıyor…" else "AI işlemleri",
            tint = Ui2.colors.accent,
            enabled = !state.running && !state.applying,
            onClick = { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            // "AI'a sor" listenin BAŞINDA (kullanıcı kararı 07.08).
            // Altındaki üçünden doğası farklı: onlar notu değiştirmeyi
            // öneriyor, bu notu bağlam alan bir sohbet açıyor. İkonu bu
            // yüzden var, diğerlerinde yok.
            if (onOpenChat != null) {
                DropdownMenuItem(
                    text = { Text("AI'a sor") },
                    leadingIcon = { Icon(Icons.Outlined.QuestionAnswer, null) },
                    onClick = {
                        menuOpen = false
                        askOpen = true
                    },
                )
            }
            noteAiActions.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menuOpen = false
                        onRun(id)
                    },
                )
            }
        }
    }

    if (askOpen && onOpenChat != null) {
        AlertDialog(
            onDismissRequest = { askOpen = false },
            title = { Text("AI'a sor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                    Text(
                        "Seçtiğin ajanda yeni bir oturum açılır; not dosyası bağlam " +
                            "olarak verilir. Sorunu sohbet kutusuna yazman yeter.",
                        color = Ui2.colors.ink2,
                    )
                    noteAskProviders.forEach { (id, label) ->
                        TextButton(
                            onClick = {
                                askOpen = false
                                actions.askNoteToAgent(id) { onOpenChat() }
                            },
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { askOpen = false }) { Text("Vazgeç") }
            },
        )
    }
}

/**
 * AI işleminin durumu ve öneri önizlemesi. Şeritten ayrı, editörün üstünde
 * yaşar: içeriği duruma göre büyüdüğü için tek satırlık eylem şeridine
 * sığmıyor. Bir şey olmadığında hiç yer kaplamaz.
 */
@Composable
internal fun NoteAiStatus(actions: RemoteViewModel, modifier: Modifier = Modifier) {
    val state by actions.noteAiState.collectAsState()

    if (state.running || (state.error.isNotBlank() && state.preview == null)) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
        ) {
            if (state.running) {
                StatusBadge("AI önerisi hazırlanıyor; not henüz değişmedi", StatusKind.Running)
            }
            if (state.error.isNotBlank() && state.preview == null) {
                StatusBadge(state.error, StatusKind.Danger)
                TextButton(onClick = actions::dismissNoteAiPreview) { Text("Kapat") }
            }
        }
    }

    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = actions::dismissNoteAiPreview,
            title = { Text("${actionLabel(preview.action)} · Önizleme") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
                ) {
                    Text("Onaylayana kadar PC'deki not değişmez.", color = Ui2.colors.ink2)
                    PreviewPanel("Mevcut metin", preview.original)
                    PreviewPanel("Önerilen metin", preview.proposed)
                    if (state.error.isNotBlank()) {
                        StatusBadge(state.error, StatusKind.Danger)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = actions::applyNoteAi,
                    enabled = !state.applying,
                ) {
                    Text(if (state.applying) "Kaydediliyor…" else "Onayla ve kaydet")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = actions::dismissNoteAiPreview,
                    enabled = !state.applying,
                ) { Text("Vazgeç") }
            },
        )
    }
}

@Composable
private fun PreviewPanel(label: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
        Text(label, color = Ui2.colors.ink2)
        SelectionContainer {
            Text(
                text = content,
                color = Ui2.colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ui2.colors.surface, RoundedCornerShape(Ui2Tokens.cornerCard))
                    .border(1.dp, Ui2.colors.line, RoundedCornerShape(Ui2Tokens.cornerCard))
                    .padding(Ui2Tokens.s12),
            )
        }
    }
}

private fun actionLabel(action: String): String =
    noteAiActions.firstOrNull { it.first == action }?.second ?: "AI"
