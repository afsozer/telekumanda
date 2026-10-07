package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.agent.bridge.FileResult
import com.agent.bridge.MarkwonText
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.stripMarkdownFrontmatter
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlinx.coroutines.delay

// Dosya basindaki YAML frontmatter'i ("---\ntitle: …\n---") ayiklar.
// Not dosyalarinda bu blok var; Markdown olarak cizilince iki yatay cizgi ve
// "title: not001" satiri olarak gorunuyordu — icerik sanilan bir gurultu.
// YALNIZ onizlemede gizlenir: Duzenle sekmesi dosyanin gercegini gosterir,
// orada da gizleseydik kaydederken frontmatter silinirdi (baslik ve
// hatirlatici bilgisi orada yasiyor).
// Gerçek uygulama :shared'da (stripMarkdownFrontmatter): DOCX aktarımı da aynı
// ayıklamaya muhtaç, iki kopya tutulursa biri düzelip diğeri bozuk kalıyor.
internal fun stripFrontmatter(md: String): String = stripMarkdownFrontmatter(md)

@Composable
fun MarkdownDocumentEditor(
    file: FileResult,
    actions: RemoteViewModel,
    modifier: Modifier = Modifier,
    editorMinHeight: Int = 320,
    autoSave: Boolean = false,
    // Not ekranindan acilan dosyalar icin: onizlemede frontmatter gizlenir ve
    // eylem satirina ekstra tus (ornegin "Bagla") konabilir. Genel Markdown
    // gezgininde ikisi de kapalidir.
    hideFrontmatter: Boolean = false,
    // Eylem şeridinin BAŞI (not ekranında AI ve hatırlatıcı). Sekmelerden önce
    // gelir: ayrı bir satır olarak yaşarken ekranın tepesinden fazladan yer
    // yiyordu, artık aynı şeritte.
    leadingActions: @Composable () -> Unit = {},
    trailingActions: @Composable () -> Unit = {},
    onDraftChanged: (content: String, dirty: Boolean) -> Unit = { _, _ -> },
) {
    val saveState by actions.markdownSaveState.collectAsState()
    val documentKey = file.path.ifBlank { file.name }
    // Markdown OLMAYAN duz metinde (.txt, .log, .json…) onizleme anlamsiz:
    // Markdown olarak cizilince "#" ile baslayan satir baslik, "*" madde isareti
    // olur — dosyada olmayan bir bicimlendirme. Bu dosyalar dogrudan duzenleme
    // kipinde acilir ve sekme seridi gizlenir.
    val markdown = com.agent.bridge.isMarkdownFile(file.name.ifBlank { file.path })
    var editing by rememberSaveable(documentKey) { mutableStateOf(!markdown) }
    // Boş dosya istisnası bir KEZ uygulanır: kullanıcı sonradan içeriği silip
    // önizlemeye geçmek isterse ekran onu zorla düzenlemeye çekmesin.
    var bosKontroluYapildi by rememberSaveable(documentKey) { mutableStateOf(false) }
    // Markdown görüntüleyici nereden açılırsa açılsın ÖNİZLEMEDE başlar
    // (kullanıcı kararı 07.08). Tek istisna içi boş dosya: önizlenecek bir şey
    // yokken boş sayfaya bakmaktansa doğrudan yazmaya başlanabilsin.
    //
    // Karar dosya YÜKLENDİKTEN sonra veriliyor. openFile önce `content = ""`
    // olan bir yer tutucu yayımlıyor; ilk kompozisyonda bakılsaydı her dosya
    // boş sanılıp düzenlemede açılırdı. documentKey yer tutucuyla gerçek yanıt
    // arasında değişmediği için state kendiliğinden yeniden hesaplanmıyor.
    LaunchedEffect(documentKey, file.content, file.hash, file.path) {
        if (bosKontroluYapildi || !markdown) return@LaunchedEffect
        val yuklendi = file.hash.isNotBlank() || file.path.isNotBlank()
        if (!yuklendi) return@LaunchedEffect
        bosKontroluYapildi = true
        // Not ekranında "boş" ölçütü frontmatter'sız gövdedir: yeni oluşturulan
        // not `---\ntitle: …\n---` taşıyor, ham metne bakılsaydı asla boş
        // sayılmaz ve yeni not önizlemede açılırdı.
        val govde = if (hideFrontmatter) stripFrontmatter(file.content) else file.content
        if (govde.isBlank()) editing = true
    }
    var draft by rememberSaveable(documentKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(file.content))
    }
    var lastFileContent by remember(documentKey) { mutableStateOf(file.content) }
    val dirty = draft.text != file.content

    // Aynı dosya bridge'den ilk kez/reload ile geldiğinde, kullanıcı bu sırada
    // yazmadıysa taslağı güncelle. Otomatik kayıt sonrası hash değişimi imleci
    // başa sıçratmasın diye state file.hash'e anahtarlanmaz.
    LaunchedEffect(file.content, file.hash) {
        if (draft.text == lastFileContent) {
            draft = draft.copy(
                text = file.content,
                selection = TextRange(draft.selection.start.coerceAtMost(file.content.length)),
            )
        }
        lastFileContent = file.content
    }
    LaunchedEffect(draft.text, dirty) {
        onDraftChanged(draft.text, dirty)
    }
    LaunchedEffect(autoSave, draft.text, file.content, file.truncated, saveState.saving, saveState.conflict) {
        if (autoSave && dirty && !file.truncated && !saveState.saving && !saveState.conflict) {
            delay(900)
            // announce=false: otomatik kayıt sessiz. Durum hemen aşağıdaki
            // düğmede ve not ekranının başlık altında zaten görünüyor.
            actions.saveOpenedMarkdown(draft.text, announce = false)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12)) {
        // Tüm eylemler TEK ikon şeridinde (kullanıcı kararı 07.08). Yazılı
        // tuşlar iki satır yiyordu; adlar basılı tutunca tooltip olarak çıkıyor.
        // Yatay kaydırılabilir: ileride tuş eklenirse dar telefonda kırpılmasın.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            leadingActions()
            if (markdown) {
                // Önizleme/Düzenle bağımsız iki tuş DEĞİL, tek anahtar:
                // birbirinin alternatifi oldukları görünsün.
                IconSegmentedTabs(
                    segments = listOf(
                        IconSegment(Icons.Outlined.Visibility, "Önizleme"),
                        IconSegment(Icons.Outlined.Edit, "Düzenle"),
                    ),
                    selectedIndex = if (editing) 1 else 0,
                    onSelect = { editing = it == 1 },
                )
            } else {
                Text(
                    "Düz metin",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ui2.colors.ink3,
                )
            }
            trailingActions()
            IconPillAction(
                // İndirme oku DEĞİL: aynı şeritte "indir" gibi okunuyordu,
                // oysa yaptığı iş bir Word BELGESİ üretmek (kullanıcı 07.08).
                icon = Icons.Outlined.Article,
                label = "DOCX'e aktar",
                onClick = { actions.exportOpenedMarkdownAsDocx(draft.text) },
            )
            IconPillAction(
                icon = Icons.Outlined.Share,
                label = "Paylaş",
                onClick = { actions.shareOpenedMarkdown(draft.text) },
            )
        }

        when {
            file.truncated -> StatusBadge("Kısaltılmış dosya düzenlenemez", StatusKind.Attention)
            dirty -> StatusBadge("Kaydedilmemiş değişiklik", StatusKind.Attention)
            saveState.saved -> StatusBadge("PC'ye kaydedildi", StatusKind.Done)
        }

        if (editing && !file.truncated) {
            MarkdownFormatToolbar(
                onBold = { draft = wrapSelection(draft, "**", "**") },
                onItalic = { draft = wrapSelection(draft, "*", "*") },
                onUnderline = { draft = wrapSelection(draft, "<u>", "</u>") },
                onAlign = { alignment -> draft = alignSelectedLines(draft, alignment) },
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onDraftChanged(it.text, it.text != file.content)
                    if (saveState.error.isNotBlank() || saveState.saved) actions.dismissMarkdownSaveStatus()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = editorMinHeight.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
                label = { Text("Markdown metni") },
                placeholder = { Text("Metni buraya yaz…") },
            )
            if (saveState.error.isNotBlank()) {
                Text(saveState.error, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.danger)
            }
            Button(
                onClick = { actions.saveOpenedMarkdown(draft.text) },
                enabled = dirty && !saveState.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saveState.saving) "Kaydediliyor…" else "PC'ye kaydet")
            }
        } else {
            // SelectionContainer bilinçli YOK: AndroidView içindeki TextView'a giden
            // dokunuşları yutuyor, linkler tıklanamıyordu (tablette logcat ile
            // doğrulandı: ACTION_UP span'e hiç ulaşmıyor, Intent üretilmiyordu).
            // Metin seçimi TextView'ın kendi setTextIsSelectable'ı ile zaten var.
            val shown = if (hideFrontmatter) stripFrontmatter(draft.text) else draft.text
            SurfaceCard {
                if (shown.isBlank()) Text("Dosya boş", color = Ui2.colors.ink3)
                else MarkwonText(
                    text = shown,
                    color = Ui2.colors.ink,
                    // Zaten viewer'dayız: bağlantı aynı ekranda yeni dosyayı açar,
                    // gezinme gerekmez (dönüş değeri bilinçli yok sayılır).
                    onFileClick = { path -> actions.openLinkedFile(path) },
                )
            }
        }
    }

    if (saveState.conflict) {
        AlertDialog(
            onDismissRequest = actions::dismissMarkdownSaveStatus,
            title = { Text("Dosya bilgisayarda değişmiş") },
            text = { Text("Telefon düzenlemesi açıldıktan sonra PC kopyası değişti. Hangi sürüm korunsun?") },
            confirmButton = {
                TextButton(onClick = { actions.saveOpenedMarkdown(draft.text, overwriteConflict = true) }) {
                    Text("Telefon sürümüyle değiştir")
                }
            },
            dismissButton = {
                TextButton(onClick = actions::reloadOpenedMarkdown) { Text("PC sürümünü yükle") }
            },
        )
    }
}

@Composable
private fun MarkdownFormatToolbar(
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onAlign: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
    ) {
        IconButton(onClick = onBold, modifier = Modifier.size(36.dp)) {
            Text("B", fontWeight = FontWeight.Bold, color = Ui2.colors.ink)
        }
        IconButton(onClick = onItalic, modifier = Modifier.size(36.dp)) {
            Text("I", fontStyle = FontStyle.Italic, color = Ui2.colors.ink)
        }
        IconButton(onClick = onUnderline, modifier = Modifier.size(36.dp)) {
            Text("U", textDecoration = TextDecoration.Underline, color = Ui2.colors.ink)
        }
        IconButton(onClick = { onAlign("left") }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Sola hizala", tint = Ui2.colors.ink2)
        }
        IconButton(onClick = { onAlign("center") }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.FormatAlignCenter, "Ortala", tint = Ui2.colors.ink2)
        }
        IconButton(onClick = { onAlign("right") }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.FormatAlignRight, "Sağa hizala", tint = Ui2.colors.ink2)
        }
    }
}

internal fun wrapSelection(value: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val selected = value.text.substring(start, end)
    val replacement = prefix + selected + suffix
    val updated = value.text.replaceRange(start, end, replacement)
    val selection = if (start == end) TextRange(start + prefix.length)
        else TextRange(start + prefix.length, start + prefix.length + selected.length)
    return value.copy(text = updated, selection = selection)
}

internal fun alignSelectedLines(value: TextFieldValue, alignment: String): TextFieldValue {
    val safeAlignment = alignment.takeIf { it in setOf("left", "center", "right") } ?: "left"
    val selectionStart = value.selection.min.coerceIn(0, value.text.length)
    val selectionEnd = value.selection.max.coerceIn(selectionStart, value.text.length)
    val lineStart = value.text.lastIndexOf('\n', (selectionStart - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val lineEnd = value.text.indexOf('\n', selectionEnd)
        .let { if (it < 0) value.text.length else it }
    val block = value.text.substring(lineStart, lineEnd)
    val prefix = "<div align=\"$safeAlignment\">\n"
    val suffix = "\n</div>"
    val updated = value.text.replaceRange(lineStart, lineEnd, prefix + block + suffix)
    return value.copy(
        text = updated,
        selection = TextRange(lineStart + prefix.length, lineStart + prefix.length + block.length),
    )
}
