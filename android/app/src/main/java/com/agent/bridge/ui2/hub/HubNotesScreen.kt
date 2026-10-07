package com.agent.bridge.ui2.hub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.agent.bridge.CoworkNote
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.IconPillAction
import com.agent.bridge.ui2.components.MarkdownDocumentEditor
import com.agent.bridge.ui2.components.REMINDER_CLEAR
import com.agent.bridge.ui2.components.REMINDER_CUSTOM
import com.agent.bridge.ui2.components.ReminderCustomPicker
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SelectorSheet
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.formatReminder
import com.agent.bridge.ui2.components.reminderQuickOptions
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Proje ayrıntısındaki kapsamlı not listesi. N4'teki birleşik Notlarım ekranı
 * bunu tamamlar; iki görünüm de aynı /cowork/notes sözleşmesini kullanır.
 */
@Composable
fun HubProjectNotesScreen(
    actions: RemoteViewModel,
    projectPath: String,
    projectTitle: String,
    openCreateInitially: Boolean,
    onBack: () -> Unit,
    onOpenNote: () -> Unit,
) {
    val state by actions.coworkNotesState.collectAsState()
    // Tür seçici yok: tuşa basınca metin notu oluşturulup açılır. Başlık
    // editörün tepesinden verilir (boşsa not001/not002…, nextFreeNoteName).
    val startCreate: () -> Unit = {
        actions.createCoworkNote(name = "", projectPath = projectPath) { note ->
            if (note != null) {
                actions.openCoworkNote(note)
                onOpenNote()
            }
        }
    }

    LaunchedEffect(projectPath) {
        actions.loadCoworkNotes(projectPath)
    }
    // "Not ekle" ile gelindiyse not doğrudan oluşturulur.
    LaunchedEffect(openCreateInitially) {
        if (openCreateInitially) startCreate()
    }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "Proje notları",
            subtitle = projectTitle,
            onBack = {
                actions.clearCoworkNotes()
                onBack()
            },
            trailing = {
                IconButton(onClick = { startCreate() }) {
                    Icon(Icons.Default.Add, "Not ekle", tint = Ui2.colors.accent)
                }
            },
        )

        when {
            state.loading -> LoadingSkeleton(
                Modifier.padding(Ui2Tokens.screenPadding),
                rows = 5,
            )
            state.error.isNotBlank() -> EmptyState(
                title = "Notlar yüklenemedi",
                description = state.error,
                icon = Icons.Outlined.Description,
            )
            state.notes.isEmpty() -> EmptyState(
                title = "Henüz not yok",
                description = "Bu projeye not ekleyebilirsin.",
                icon = Icons.Outlined.Description,
                actionLabel = "Not ekle",
                onAction = { startCreate() },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Ui2Tokens.screenPadding,
                    vertical = Ui2Tokens.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
            ) {
                items(state.notes, key = CoworkNote::id) { note ->
                    ListRow(
                        title = note.title,
                        leading = {
                            Icon(
                                Icons.Outlined.Description,
                                null,
                                tint = Ui2.colors.accent,
                            )
                        },
                        // Hatırlatıcı rozeti: bekleyen hatırlatıcı bir DURUM değil
                        // (onay beklemiyor, planlanmış) — nötr rozet. Yalnız failed
                        // danger alır, sent sönük kalır (anayasa §4.2).
                        trailing = reminderBadge(note),
                        onClick = {
                            actions.openCoworkNote(note)
                            if (!note.mdPath.isNullOrBlank()) onOpenNote()
                        },
                    )
                }
            }
        }
    }

}

// Liste satırının sağındaki hatırlatıcı rozeti. Hatırlatıcı yoksa (ya da tarih
// elle bozulmuşsa) trailing slot hiç doldurulmaz. Kendisi @Composable DEĞİL:
// composable çağırmıyor, çağıran bir lambda döndürüyor.
private fun reminderBadge(note: CoworkNote): (@Composable RowScope.() -> Unit)? {
    val text = formatReminder(note.reminderAt)
    if (text.isBlank()) return null
    return {
        when (note.reminderState) {
            "failed" -> StatusBadge("gönderilemedi", StatusKind.Danger)
            "sent" -> StatusBadge(text)
            else -> StatusBadge("⏰ $text")
        }
    }
}

@Composable
fun NoteEditorScreen(
    actions: RemoteViewModel,
    onBack: () -> Unit,
    // "AI'a sor" sonrası sohbete geçiş. Verilmezse düğme çizilmez.
    onOpenChat: (() -> Unit)? = null,
) {
    val file by actions.openedFile.collectAsState()
    val saveState by actions.markdownSaveState.collectAsState()
    val notesState by actions.coworkNotesState.collectAsState()
    var latestDraft by remember(file?.path, file?.name) { mutableStateOf(file?.content.orEmpty()) }
    var dirty by remember(file?.path, file?.name) { mutableStateOf(false) }

    // Açık dosyanın hangi nota ait olduğu: başlık düzenlemesi not kimliği üzerinden
    // yürür (yeniden adlandırma dosya adını da değiştirir).
    val openNote = remember(file?.path, notesState.notes) {
        val p = file?.path.orEmpty()
        notesState.notes.firstOrNull { it.mdPath == p }
    }
    var titleDraft by remember(openNote?.id) { mutableStateOf(openNote?.title.orEmpty()) }
    var attachOpen by remember(openNote?.id) { mutableStateOf(false) }
    // Hatırlatıcı seçimi: hızlı seçenekler sheet'i, "Özel tarih" takvim dalı.
    var reminderPickOpen by remember(openNote?.id) { mutableStateOf(false) }
    var reminderCustomOpen by remember(openNote?.id) { mutableStateOf(false) }
    val uiState by actions.uiState.collectAsState()

    // Başlığı kalıcılaştır. Sıra önemli: önce İÇERİK kaydedilir, sonra ad değişir.
    // Ters sırada, ad değişince eski yol ölür ve bekleyen taslak kaybolurdu.
    // Adlandırma sonrası not yeniden açılır; yoksa ekranın elindeki yol bayat kalır.
    val pendingDraft = { latestDraft.takeIf { dirty && file?.path?.isNotBlank() == true } }
    val titleChanged = {
        val clean = titleDraft.trim()
        openNote != null && clean.isNotBlank() && clean != openNote?.title
    }
    val commitTitle = {
        val note = openNote
        if (note != null && titleChanged()) {
            // Kayıt + adlandırma TEK sıralı iş: ViewModel içeriği diske indirip
            // öyle taşıyor. Ekranda ayrı ayrı tetiklendiğinde arada bekleme
            // yoktu ve adlandırma kaydı altından çekebiliyordu.
            actions.saveThenRenameNote(note, titleDraft.trim(), pendingDraft()) { updated ->
                if (updated != null) actions.openCoworkNote(updated)
            }
        }
    }

    LaunchedEffect(saveState.saved, saveState.saving) {
        if (saveState.saved && !saveState.saving) dirty = false
    }

    val leaveEditor = {
        // Başlık da değiştiyse kaydı BURADA tetikleme: commitTitle zaten
        // kaydet-sonra-adlandır sırasını yürütüyor. Ayrıca kaydedersek aynı
        // taslak iki kez gider ve ikinci kayıt taşınmış yola denk gelebilir.
        if (titleChanged()) {
            commitTitle()
        } else {
            // ViewModel işi ekran yaşam döngüsünden bağımsız tamamlar. Otomatik
            // kayıtla çakışırsa mutex çağrıları sıraya koyar ve son taslak kazanır.
            pendingDraft()?.let { draft -> actions.saveOpenedMarkdown(draft) }
        }
        onBack()
    }
    BackHandler(onBack = leaveEditor)

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        val fullPath = file?.path?.ifBlank { file?.name.orEmpty() }.orEmpty()
        val title = fullPath.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.')
            .ifBlank { "Not" }
        ScreenHeader(
            title = title,
            subtitle = when {
                saveState.saving -> "PC'ye kaydediliyor…"
                dirty -> "Değişiklikler otomatik kaydedilecek"
                saveState.saved -> "PC'ye kaydedildi"
                else -> "Not"
            },
            onBack = leaveEditor,
        )
        // Başlık artık oluşturma diyaloğunda değil burada. Odak kaybında kaydedilir;
        // boş bırakılırsa oluşturma sırasında atanan not001/not002… adı korunur.
        if (openNote != null) {
            OutlinedTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4)
                    .onFocusChanged { if (!it.isFocused) commitTitle() },
                singleLine = true,
                label = { Text("Not başlığı") },
            )
        }

        when {
            file == null || file?.path.isNullOrBlank() -> LoadingSkeleton(
                Modifier.padding(Ui2Tokens.screenPadding),
                rows = 6,
            )
            file?.ok == false -> EmptyState(
                title = "Not açılamadı",
                description = file?.error.orEmpty(),
                icon = Icons.Outlined.Description,
            )
            // Üst boşluk bilinçli dar: başlık kutusu ile eylem şeridi arasında
            // ekran yüksekliğinin bir dilimi boşuna gidiyordu (kullanıcı isteği).
            else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = Ui2Tokens.screenPadding,
                            end = Ui2Tokens.screenPadding,
                            top = Ui2Tokens.s4,
                            bottom = Ui2Tokens.screenPadding,
                        ),
                ) {
                    if (saveState.error.isNotBlank()) {
                        StatusBadge(saveState.error, StatusKind.Danger)
                    }
                    NoteAiStatus(actions)
                    MarkdownDocumentEditor(
                        file = file!!,
                        actions = actions,
                        modifier = Modifier.fillMaxWidth(),
                        editorMinHeight = 520,
                        // Not da önizlemede açılır; boşsa bileşen zaten
                        // düzenlemeye geçiriyor (yeni not akışı bozulmaz).
                        autoSave = true,
                        hideFrontmatter = true,
                        // Şeridin başı: AI ve hatırlatıcı. Eskiden ayrı bir
                        // satırdı, ekranın tepesinden iki kat yer yiyordu.
                        leadingActions = {
                            NoteAiMenuAction(
                                actions = actions,
                                onRun = { action ->
                                    if (dirty) {
                                        actions.saveOpenedMarkdownThenRunNoteAi(latestDraft, action)
                                    } else {
                                        actions.runNoteAi(action)
                                    }
                                },
                                onOpenChat = onOpenChat,
                            )
                            if (openNote != null) {
                                // Kurulu hatırlatıcının TARİHİ eskiden düğmenin
                                // üstünde yazıyordu; ikonda yer yok, o yüzden
                                // tarih basılı tutunca çıkan ada taşındı ve
                                // ikon dolu zile dönüyor.
                                val remText = formatReminder(openNote.reminderAt)
                                IconPillAction(
                                    icon = if (remText.isBlank()) Icons.Outlined.Notifications
                                    else Icons.Filled.NotificationsActive,
                                    label = if (remText.isBlank()) "Hatırlatıcı" else "Hatırlatıcı: $remText",
                                    tint = if (remText.isBlank()) Ui2.colors.ink2 else Ui2.colors.accent,
                                    onClick = { reminderPickOpen = true },
                                )
                            }
                        },
                        trailingActions = {
                            // Not listesindeki "Projeye bağla" ile aynı iş: notu
                            // seçilen cowork çalışma alanının notlar/ klasörüne taşır.
                            if (openNote != null) {
                                IconPillAction(
                                    icon = Icons.Outlined.Link,
                                    label = "Projeye bağla",
                                    onClick = { attachOpen = true },
                                )
                            }
                        },
                        onDraftChanged = { content, isDirty ->
                            latestDraft = content
                            dirty = isDirty
                        },
                    )
                }
        }
    }

    // Not listesindeki diyalogla aynı: çalışma alanı seçilince not oraya taşınır.
    if (reminderPickOpen && openNote != null) {
        val note = openNote
        SelectorSheet(
            title = "Hatırlatıcı",
            subtitle = note.title.ifBlank { null },
            options = reminderQuickOptions(note.reminderAt),
            selectedValue = note.reminderAt,
            onSelect = { value ->
                reminderPickOpen = false
                when (value) {
                    REMINDER_CUSTOM -> reminderCustomOpen = true
                    REMINDER_CLEAR -> actions.setCoworkNoteReminder(note, null)
                    else -> actions.setCoworkNoteReminder(note, value)
                }
            },
            onDismiss = { reminderPickOpen = false },
        )
    }
    if (reminderCustomOpen && openNote != null) {
        val note = openNote
        ReminderCustomPicker(
            onPicked = {
                reminderCustomOpen = false
                actions.setCoworkNoteReminder(note, it)
            },
            onDismiss = { reminderCustomOpen = false },
        )
    }
    if (attachOpen && openNote != null) {
        val note = openNote
        AlertDialog(
            onDismissRequest = { attachOpen = false },
            title = { Text("Projeye bağla") },
            text = {
                if (uiState.cowork.workspaces.isEmpty()) {
                    Text("Bağlanabilecek Cowork çalışma alanı bulunamadı.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(uiState.cowork.workspaces, key = { it.path }) { workspace ->
                            ListRow(
                                title = workspace.matter.ifBlank { workspace.name },
                                detail = workspace.path,
                                onClick = {
                                    // Bağlama dosyayı TAŞIR; açık olan not yolu bayatlar,
                                    // bu yüzden editörden çıkılır (liste tazelenmiş gelir).
                                    actions.attachCoworkNote(note, workspace.path)
                                    attachOpen = false
                                    onBack()
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { attachOpen = false }) { Text("Vazgeç") }
            },
        )
    }
}
