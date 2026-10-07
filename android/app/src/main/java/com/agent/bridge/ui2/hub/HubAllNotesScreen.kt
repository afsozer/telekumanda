package com.agent.bridge.ui2.hub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.bridge.CoworkNote
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/** N4 birleşik Notlarım ekranı: genel ve proje notlarının tek görünümü. */
@Composable
fun HubAllNotesScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onOpenNote: () -> Unit,
) {
    val state by actions.coworkNotesState.collectAsState()
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    // Tür seçici yok: yeni not doğrudan metin notu olarak açılır.
    val startCreate: () -> Unit = {
        actions.createCoworkNote(name = "") { note ->
            if (note != null) {
                actions.openCoworkNote(note)
                onOpenNote()
            }
        }
    }
    var actionNote by remember { mutableStateOf<CoworkNote?>(null) }
    var attachNote by remember { mutableStateOf<CoworkNote?>(null) }
    var renameNote by remember { mutableStateOf<CoworkNote?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteNote by remember { mutableStateOf<CoworkNote?>(null) }
    // Toplu seçim. Küme değil LİSTE tutuluyor: rememberSaveable Set'i Bundle'a
    // yazamıyor, ArrayList<String>'i yazıyor — ekran döndüğünde seçim durur.
    var selection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var bulkDeleteOpen by rememberSaveable { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val exitSelection = { selection = emptyList() }
    // Seçim modundayken sistem geri tuşu ekrandan çıkmaz, seçimi bırakır.
    BackHandler(enabled = selecting) { exitSelection() }

    LaunchedEffect(Unit) {
        actions.loadCoworkNotes()
        actions.loadCoworkWorkspaces()
    }

    // Süzgeçler iki ayrı boyutu paylaşıyor: KAPSAM (genel/projeli) ve KÖKEN
    // (ekran görüntüsünden / elle). Tek şeritte duruyorlar çünkü ikisi de
    // "hangi notlar" sorusunu yanıtlıyor; birini ayrı bir menüye almak dar
    // telefonda ikinci bir dokunuş katmanı olurdu.
    val shownNotes = state.notes.filter { note ->
        when (filterIndex) {
            1 -> note.project == null
            2 -> note.project != null
            3 -> note.sourceScreenshot.isNotBlank()
            4 -> note.sourceScreenshot.isBlank()
            5 -> !note.reminderAt.isNullOrBlank()
            else -> true
        }
        // "Hatırlatıcılı" sekmesi tarihe göre sıralanır (en yakın üstte);
        // diğerlerinde liste bridge'in verdiği "son güncellenen" sırasında kalır.
    }.let { list ->
        if (filterIndex == 5) list.sortedBy { it.reminderAt.orEmpty() } else list
    }
    val reminderCount = state.notes.count { !it.reminderAt.isNullOrBlank() }
    val ekranNotuSayisi = state.notes.count { it.sourceScreenshot.isNotBlank() }

    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        // Seçim modunda başlık ve eylemler tamamen değişir: ekranın kipi
        // belirsiz kalmasın, hangi tuşun ne yapacağı bakınca anlaşılsın.
        ScreenHeader(
            title = if (selecting) "${selection.size} not seçildi" else "Notlarım",
            subtitle = when {
                selecting -> "Seçimden çıkmak için geri"
                state.query.isNotBlank() -> "${state.notes.size} sonuç · \"${state.query}\""
                else -> "${state.notes.size} not · Genel ve proje notları"
            },
            onBack = {
                if (selecting) {
                    exitSelection()
                } else {
                    actions.clearCoworkNotes()
                    onBack()
                }
            },
            trailing = {
                Row {
                    if (selecting) {
                        // "Tümünü seç" GÖRÜNEN notlara uygulanır; bir filtredeyken
                        // ekranda olmayan notu sessizce seçmek sürpriz olurdu.
                        val hepsiSecili = shownNotes.isNotEmpty() &&
                            shownNotes.all { it.id in selection }
                        IconButton(
                            onClick = {
                                selection = if (hepsiSecili) emptyList() else shownNotes.map { it.id }
                            },
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                if (hepsiSecili) "Seçimi temizle" else "Tümünü seç",
                                tint = if (hepsiSecili) Ui2.colors.accent else Ui2.colors.ink3,
                            )
                        }
                        IconButton(onClick = { bulkDeleteOpen = true }) {
                            Icon(Icons.Default.Delete, "Seçilenleri sil", tint = Ui2.colors.danger)
                        }
                    } else {
                        IconButton(
                            onClick = { actions.loadCoworkNotes() },
                            enabled = !state.loading,
                        ) {
                            Icon(Icons.Default.Refresh, "Yenile", tint = Ui2.colors.ink3)
                        }
                        IconButton(onClick = { startCreate() }) {
                            Icon(Icons.Default.Add, "Yeni not", tint = Ui2.colors.accent)
                        }
                    }
                }
            },
        )
        // Arama köprüde yapılır: başlık VE not gövdesi taranır. Seçim kipinde
        // gizlenir — o an ekranın işi seçim, kutu yalnız yer kaplardı.
        if (!selecting) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { actions.setCoworkNotesQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4),
                singleLine = true,
                placeholder = { Text("Başlık ve içerikte ara") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Ui2.colors.ink3) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { actions.setCoworkNotesQuery("") }) {
                            Icon(Icons.Default.Close, "Aramayı temizle", tint = Ui2.colors.ink3)
                        }
                    }
                },
            )
        }
        SegmentedTabs(
            // Sayılar sekmenin üstünde: kaç hatırlatıcı kurulu ve kaç not
            // ekrandan doğmuş, sekmeye girmeden görünsün.
            options = listOf(
                "Tümü",
                "Genel",
                "Projeli",
                if (ekranNotuSayisi > 0) "📷 Ekran $ekranNotuSayisi" else "📷 Ekran",
                "✍ Elle",
                if (reminderCount > 0) "⏰ $reminderCount" else "⏰",
            ),
            selectedIndex = filterIndex,
            onSelect = { filterIndex = it },
            // Altı çip dar telefonda satıra sığmıyor; kaydırılmazsa sondakiler
            // sessizce kırpılırdı.
            scrollable = true,
            modifier = Modifier.padding(
                horizontal = Ui2Tokens.screenPadding,
                vertical = Ui2Tokens.s8,
            ),
        )
        if (state.error.isNotBlank()) {
            StatusBadge(
                text = state.error,
                kind = StatusKind.Danger,
                modifier = Modifier.padding(horizontal = Ui2Tokens.screenPadding),
            )
        }
        when {
            state.loading -> LoadingSkeleton(
                Modifier.padding(Ui2Tokens.screenPadding),
                rows = 6,
            )
            shownNotes.isEmpty() -> {
                val bosArama = state.query.isNotBlank()
                val hicNotYok = state.notes.isEmpty() && !bosArama
                EmptyState(
                    title = when {
                        bosArama -> "Eşleşen not yok"
                        hicNotYok -> "Henüz not yok"
                        else -> "Bu filtrede not yok"
                    },
                    description = when {
                        bosArama -> "\"${state.query}\" başlıklarda ve not içeriklerinde bulunamadı."
                        hicNotYok -> "Buradan bağımsız bir not oluşturabilirsin."
                        else -> "Başka bir filtre seçebilirsin."
                    },
                    icon = Icons.Outlined.Description,
                    actionLabel = if (hicNotYok) "Yeni not" else null,
                    onAction = if (hicNotYok) ({ startCreate() }) else null,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Ui2Tokens.screenPadding,
                    vertical = Ui2Tokens.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
            ) {
                items(shownNotes, key = CoworkNote::id) { note ->
                    val secili = note.id in selection
                    val toggle = {
                        selection = if (secili) selection - note.id else selection + note.id
                    }
                    SurfaceCard {
                        ListRow(
                            title = note.title,
                            // Gövde özeti başlığın hemen altında: hangi not
                            // olduğu açmadan anlaşılsın. Aramada köprü buraya
                            // eşleşmenin çevresini koyar.
                            secondary = note.preview,
                            detail = buildString {
                                append(note.project?.name ?: "Genel")
                                append(" · ")
                                append(
                                    when {
                                        // Kökeni satırda da yazsın: süzgeçte
                                        // "elle" derken kartın hangi grupta
                                        // olduğu tahmin işi olmasın.
                                        note.sourceScreenshot.isNotBlank() -> "Ekran görüntüsü"
                                        else -> "Elle"
                                    },
                                )
                            },
                            // Seçim modunda tür ikonunun yerini işaret kutusu alır:
                            // satırın seçili olup olmadığı tek bakışta görünsün.
                            leading = {
                                when {
                                    selecting && secili -> Icon(
                                        Icons.Default.CheckCircle,
                                        "Seçili",
                                        tint = Ui2.colors.accent,
                                    )
                                    selecting -> Icon(
                                        Icons.Outlined.RadioButtonUnchecked,
                                        "Seçili değil",
                                        tint = Ui2.colors.ink3,
                                    )
                                    else -> Icon(
                                        Icons.Outlined.Description,
                                        null,
                                        tint = Ui2.colors.accent,
                                    )
                                }
                            },
                            trailing = {
                                // Seçim modunda tek not menüsü gizlenir: iki ayrı
                                // kip aynı satırda çakışmasın.
                                if (!selecting) {
                                    IconButton(onClick = { actionNote = note }) {
                                        Icon(Icons.Default.MoreVert, "Not işlemleri", tint = Ui2.colors.ink3)
                                    }
                                }
                            },
                            onClick = {
                                if (selecting) {
                                    toggle()
                                } else {
                                    actions.openCoworkNote(note)
                                    onOpenNote()
                                }
                            },
                            // Basılı tutmak toplu seçimi başlatır (kullanıcı isteği).
                            // Tek notun işlem menüsü artık sağdaki üç noktada.
                            onLongClick = { toggle() },
                        )
                    }
                }
            }
        }
    }

    actionNote?.let { note ->
        AlertDialog(
            onDismissRequest = { actionNote = null },
            title = { Text(note.title) },
            text = {
                Column {
                    if (note.project == null) {
                        TextButton(
                            onClick = {
                                actionNote = null
                                attachNote = note
                            },
                        ) { Text("Projeye bağla") }
                    }
                    TextButton(
                        onClick = {
                            actionNote = null
                            renameNote = note
                            renameText = note.title
                        },
                    ) { Text("Yeniden adlandır") }
                    TextButton(
                        onClick = {
                            actionNote = null
                            deleteNote = note
                        },
                    ) { Text("Sil", color = Ui2.colors.danger) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionNote = null }) { Text("Kapat") }
            },
        )
    }

    attachNote?.let { note ->
        AlertDialog(
            onDismissRequest = { attachNote = null },
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
                                    actions.attachCoworkNote(note, workspace.path)
                                    attachNote = null
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { attachNote = null }) { Text("Vazgeç") }
            },
        )
    }

    renameNote?.let { note ->
        AlertDialog(
            onDismissRequest = { renameNote = null },
            title = { Text("Notu yeniden adlandır") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Yeni ad") },
                )
            },
            confirmButton = {
                Button(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        actions.renameCoworkNote(note, renameText)
                        renameNote = null
                    },
                ) { Text("Kaydet") }
            },
            dismissButton = {
                TextButton(onClick = { renameNote = null }) { Text("Vazgeç") }
            },
        )
    }

    if (bulkDeleteOpen) {
        // Seçim filtre değişimini aştığı için hedefler TÜM notlardan süzülür,
        // yalnız o an ekranda olanlardan değil.
        val hedefler = state.notes.filter { it.id in selection }
        ConfirmDialog(
            title = "${hedefler.size} not silinsin mi?",
            text = "Seçilen notlar kalıcı olarak silinecek. " +
                "Bu işlem geri alınamaz.",
            confirmLabel = "Sil",
            onConfirm = {
                actions.deleteCoworkNotes(hedefler)
                bulkDeleteOpen = false
                exitSelection()
            },
            onDismiss = { bulkDeleteOpen = false },
        )
    }

    deleteNote?.let { note ->
        ConfirmDialog(
            title = "${note.title} silinsin mi?",
            text = "Not kalıcı olarak silinecek.",
            confirmLabel = "Sil",
            onConfirm = {
                actions.deleteCoworkNote(note)
                deleteNote = null
            },
            onDismiss = { deleteNote = null },
        )
    }
}
