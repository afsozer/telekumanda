package com.agent.bridge.ui2.hub

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.agent.bridge.DirEntry
import com.agent.bridge.FileClipboard
import com.agent.bridge.FileSortKey
import com.agent.bridge.canPasteInto
import com.agent.bridge.clipboardAfterPaste
import com.agent.bridge.selectAllToggle
import com.agent.bridge.selectionTitle
import com.agent.bridge.toggleSelection
import com.agent.bridge.PhoneStorage
import com.agent.bridge.phoneBase
import com.agent.bridge.phoneEntries
import com.agent.bridge.phoneError
import com.agent.bridge.phoneLoading
import com.agent.bridge.shortDisplayPath
import com.agent.bridge.sortDirEntries
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.fileBrowserBase
import com.agent.bridge.fileBrowserEntries
import com.agent.bridge.fileBrowserLoading
import com.agent.bridge.fileDriveRoots
import com.agent.bridge.confinedParentPath
import com.agent.bridge.coworkFilesStart
import com.agent.bridge.coworkFilesOpenDir
import com.agent.bridge.coworkRootPath
import com.agent.bridge.coworkBrowserScope
import com.agent.bridge.filterHidden
import com.agent.bridge.isUnderDrive
import com.agent.bridge.formatByteCount
import com.agent.bridge.isImageFile
import com.agent.bridge.isUdfFile
import com.agent.bridge.ViewerKind
import com.agent.bridge.viewerKindFor
import com.agent.bridge.relativeToRoot
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.FileThumbnail
import com.agent.bridge.ui2.components.MobilImzaDialog
import com.agent.bridge.ui2.components.hasThumbnail
import com.agent.bridge.ui2.components.DocxLiteEditor
import com.agent.bridge.ui2.components.ImageViewer
import com.agent.bridge.ui2.components.PdfViewer
import com.agent.bridge.ui2.components.VideoViewer
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.MarkdownDocumentEditor
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.components.SheetViewer
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HubFilesScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onOpenViewer: () -> Unit,
    // Bos = ev dizini (Merkez > Dosyalar'in davranisi). Dolu gelirse gezgin
    // dogrudan o klasorde acilir (oturumun klasorunden acilis bunu kullanir).
    // Kopru tarafinda yol kisiti yok (confinementRoot bos), mutlak yol serbest.
    initialPath: String = "",
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    // Gezgin, bu ZİYARETTE bir kez başlangıç klasörüne gider; sonrası kullanıcının.
    //
    // Bayraklar rememberSaveable: dosya açıp görüntüleyiciye gidince bu ekranın
    // nav girişi back-stack'te durur ve durumu korunur, dönüşte bayrak hâlâ true
    // olduğu için KLASÖRDE KALIRIZ (md/docx/udf/görsel — hepsi aynı yoldan geçer).
    // Merkez'den yeniden girilince yeni bir nav girişi açılır, bayraklar sıfırdan
    // başlar ve başlangıç klasörü yüklenir.
    var pcLoaded by rememberSaveable { mutableStateOf(false) }
    var phoneLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPath) {
        if (pcLoaded) return@LaunchedEffect
        pcLoaded = true
        actions.loadBrowserDir(initialPath)
    }
    // Sürücü listesi bir kez; delege zaten doluysa istek atmıyor.
    LaunchedEffect(Unit) { actions.loadDriveRoots() }
    // Telefon sekmesi AgentBridge kökünde açılır ama KİLİTLİ DEĞİL — üst
    // klasörlere çıkıp /sdcard'ın geri kalanına gidilebilir (rootLock verilmiyor).
    LaunchedEffect(tab) {
        if (tab != 1 || phoneLoaded) return@LaunchedEffect
        phoneLoaded = true
        actions.loadPhoneDir(actions.phoneRootPath())
    }
    val pcOps = rememberPcOps(uiState, actions, coworkOnly = false)
    val phoneOps = rememberPhoneOps(uiState, actions, coworkOnly = false)
    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(title = "Dosyalar", subtitle = "Bilgisayar ve telefon gezgini", onBack = onBack)
        FilesLocationBar(
            path = if (tab == 0) uiState.fileBrowserBase.ifBlank { "Bilgisayar" }
            else uiState.phoneBase.ifBlank { "Telefon" },
            tab = tab,
            onSelect = { tab = it },
        )
        if (tab == 1 && !actions.phoneStoragePermitted()) {
            PhonePermissionNotice(actions)
        } else if (tab == 0) {
            FilesPane(
                ops = pcOps,
                actions = actions,
                onFileTap = { entry -> if (actions.openFileEntry(entry)) onOpenViewer() },
            )
        } else {
            FilesPane(
                ops = phoneOps,
                actions = actions,
                onFileTap = { entry -> openPhoneEntry(entry, actions, phoneOps, onOpenViewer) },
            )
        }
    }
}

/**
 * Telefondaki dosyaya dokunma. Markdown'ı KENDİ editörümüzde açıyoruz — Honor'un
 * dosya yöneticisi .md'yi açamıyor ve dışarı yollarsak kullanıcı boş seçiciyle
 * kalıyor. Görseller de PC tarafıyla aynı dahili görüntüleyiciye gider (zoom +
 * klasörde kaydırma); galeriye göndermek isteyen üç nokta > "Birlikte aç"
 * kullanır. PDF ve video da artık uygulama içinde açılır. Kalan türler (ses,
 * arşiv, ofis biçimleri…) dış uygulamaya gider.
 */
private fun openPhoneEntry(
    entry: DirEntry,
    actions: RemoteViewModel,
    ops: FileBrowserOps,
    onOpenViewer: () -> Unit,
) {
    when (viewerKindFor(entry.name)) {
        ViewerKind.MARKDOWN -> {
            actions.openPhoneMarkdown(entry.path)
            onOpenViewer()
        }
        ViewerKind.IMAGE -> {
            actions.openImage(entry.path, local = true)
            onOpenViewer()
        }
        ViewerKind.PDF -> {
            actions.openPdf(entry.path, local = true)
            onOpenViewer()
        }
        ViewerKind.VIDEO -> {
            actions.openVideo(entry.path, local = true)
            onOpenViewer()
        }
        // Tablo salt okunur olduğu için telefondaki dosyada da uygulama içi
        // okuyucu doğru yer: PC'ye geri yazma sözü verilmiyor.
        ViewerKind.SHEET -> {
            actions.openSheet(entry.path, local = true)
            onOpenViewer()
        }
        // DOCX/UDF de artık uygulama içinde: düzenleyici baytları yerel
        // dosyadan okuyabiliyor (openPhoneDocx). Eskiden harici uygulamaya
        // devrediliyordu, çünkü yalnız köprüdeki kopya üzerinden çalışıyordu;
        // WhatsApp'tan gelen .udf bizde açılırken kendi gezginimizde dışarı
        // çıkması tutarsız olurdu.
        ViewerKind.DOCX -> {
            actions.openPhoneDocx(entry.path)
            onOpenViewer()
        }
        ViewerKind.EXTERNAL -> ops.openExternal(entry)
    }
}

/**
 * Gezginin konum şeridi: solda bulunulan yol, sağda kaynak anahtarı. Anahtar
 * SAĞDA çünkü tek elle tutulan telefonda sol kenar başparmağın erişemediği yer;
 * yol uzun olabildiği için de kalan genişliği o alıyor.
 */
@Composable
private fun FilesLocationBar(path: String, tab: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            shortDisplayPath(path),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = Ui2.colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SourceSwitch(selected = tab, onSelect = onSelect)
    }
}

@Composable
private fun SourceSwitch(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .background(Ui2.colors.surface, Ui2Tokens.pill)
            .border(1.dp, Ui2.colors.line, Ui2Tokens.pill)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SourceSwitchButton(Icons.Default.Computer, "Bilgisayar", selected == 0) { onSelect(0) }
        SourceSwitchButton(Icons.Default.Smartphone, "Telefon", selected == 1) { onSelect(1) }
    }
}

@Composable
private fun SourceSwitchButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(Ui2Tokens.pill)
            .background(if (selected) Ui2.colors.pillFill else Ui2.colors.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) Ui2.colors.pillOn else Ui2.colors.ink3)
    }
}

/**
 * "Tüm dosyalara erişim" normal izin diyaloguyla verilemiyor; kullanıcıyı
 * Ayarlar'a göndermekten başka yol yok. Üretici kabuklarında uygulamaya özel
 * ekran açılmayabilir, o yüzden yedek intent sırayla denenir.
 */
@Composable
private fun PhonePermissionNotice(actions: RemoteViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(Ui2Tokens.screenPadding)) {
        EmptyState(
            title = "Telefon dosyalarına erişim kapalı",
            description = "Gezginin cihazdaki klasörleri okuyabilmesi için " +
                "\"Tüm dosyalara erişim\" iznini açman gerekiyor.",
            icon = Icons.Outlined.Description,
        )
        TextButton(onClick = {
            PhoneStorage.requestIntents(context).firstOrNull { intent ->
                runCatching { context.startActivity(intent) }.isSuccess
            }
        }) { Text("Ayarları aç", color = Ui2.colors.accent) }
    }
}

/**
 * Gezginin kaynağa bağlı olan her şeyi. Panel PC mi telefon mu olduğunu
 * bilmez, yalnız bu kümeyi çağırır — böylece tek bir liste/sıralama/menü
 * gerçeklemesi iki kaynağa da hizmet eder (anayasa 3: kaynağa özel ekran
 * yazılmaz).
 */
data class FileBrowserOps(
    val entries: List<DirEntry>,
    val base: String,
    val loading: Boolean,
    val emptyDescription: String,
    // Doluysa "boş klasör" yerine bu hata gösterilir; ikisi aynı şey değil.
    val errorText: String = "",
    val load: (String) -> Unit,
    val rename: (String, String) -> Unit,
    val move: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val openExternal: (DirEntry) -> Unit,
    // PC'de "Telefona indir", telefonda "PC'ye kopyala" — aynı düğme, ters yön.
    val transfer: (DirEntry) -> Unit,
    val transferLabel: String,
    // Yalnız PC tarafında var; telefona yükleme diye bir şey yok (dosya zaten cihazda).
    val upload: (() -> Unit)? = null,
    val homeLabel: String,
    // Çoklu seçim: N öğeyi tek seferde yapıştır / sil.
    val paste: (List<String>, String, Boolean) -> Unit,
    val deleteMany: (List<String>) -> Unit,
    // Dosyalar telefonun kendi diskinde mi? Görüntüleyici byte'ları köprüden mi
    // yoksa diskten mi okuyacağını buradan öğrenir.
    val localFiles: Boolean = false,
    // PC'nin sürücü kökleri; yalnız PC gezgininde dolu. Telefonda tek kök var.
    val driveRoots: List<DirEntry> = emptyList(),
)

/**
 * Gezgin "dosya seç" kipi. Ek eklerken açıldığında gezgin bir DÜZENLEME aracı
 * değil bir SEÇİCİdir: üç nokta menüsü, yükleme ve pano şeridi kapanır, tek
 * dokunuş dosyayı seçer (açmaz), altta "Seç (n)" çubuğu çıkar. Klasörlere
 * girmek, sıralamak ve gizlileri açmak aynen çalışır — seçmeyi kolaylaştıran
 * şeyler kalıyor, dosyayı değiştirenler gidiyor.
 */
data class FilePickerMode(
    val onConfirm: (List<DirEntry>) -> Unit,
)

// rootLock: verilirse gezinme bu kökün altına KİLİTLENİR (cowork gezgini) — üst
// klasör satırı kökte kaybolur, Home köke döner, başlık köke göreli gösterilir.
// Kilit bir navigasyon korkuluğudur, güvenlik sınırı değil (köprü /dirs geneldir).
// onFileTap: hub'da metin önizleme, cowork'te harici uygulamada aç + geri yaz.
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FilesPane(
    ops: FileBrowserOps,
    actions: RemoteViewModel,
    onFileTap: (DirEntry) -> Unit,
    rootLock: String? = null,
    picker: FilePickerMode? = null,
) {
    val picking = picker != null
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var contextEntry by remember { mutableStateOf<DirEntry?>(null) }
    var editMode by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<DirEntry?>(null) }
    var multiDeleteTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    val parent = confinedParentPath(ops.base, rootLock)

    // Android geri hareketi: gezginden çıkmak yerine ÖNCEKİ klasöre döner.
    // Yığın boşalınca (girişte açılan klasördeyiz) BackHandler devre dışı kalır
    // ve geri navigasyona düşer, yani ekran kapanır — beklenen davranış bu.
    // Not: "üst klasör" değil "önceki klasör" — kullanıcı yukarı çıkıp sonra
    // başka bir dala indiyse geri onu adım adım geri alır.
    val history = remember(rootLock) { mutableStateListOf<String>() }
    fun goTo(path: String) {
        if (path != ops.base) history.add(ops.base)
        ops.load(path)
    }

    // Çoklu seçim: uzun basışla girilir. Pano klasör değiştirince YAŞAR —
    // "kopyala → hedefe git → yapıştır" akışının tamamı bu yüzden mümkün.
    var selected by remember(rootLock) { mutableStateOf(emptySet<String>()) }
    var clipboard by remember(rootLock) { mutableStateOf(FileClipboard()) }
    val selectionMode = selected.isNotEmpty()

    // Geri tuşu sırası: önce seçim modundan çık, sonra klasör geçmişi, en sonda
    // ekranı kapat. Seçimdeyken geri basınca gezginden atılmak sinir bozucu olurdu.
    BackHandler(enabled = selectionMode) { selected = emptySet() }
    BackHandler(enabled = !selectionMode && history.isNotEmpty()) {
        ops.load(history.removeAt(history.lastIndex))
    }
    // Sıralama tercihi KLASÖR BAZINDA kalıcı hatırlanır (prefs): anahtar klasör
    // yolu, başlangıç değeri o klasörün kayıtlı tercihi. Anahtar değiştirilince
    // doğal yön verilir: ad A→Z, tarih yeni→eski, boyut büyük→küçük; aynı
    // anahtara tekrar basmak yönü çevirir. (rememberSaveable enum saklayamaz,
    // ad string'i saklanır.)
    val browserBase = ops.base
    var sortKeyName by rememberSaveable(browserBase) { mutableStateOf(actions.fileSortFor(browserBase).first.name) }
    var sortAscending by rememberSaveable(browserBase) { mutableStateOf(actions.fileSortFor(browserBase).second) }
    var sortMenu by remember { mutableStateOf(false) }
    val sortKey = FileSortKey.entries.firstOrNull { it.name == sortKeyName } ?: FileSortKey.NAME

    // Gizli dosya filtresi İKİ kaynağa da uygulanır: köprü nokta-dosyaları
    // zaten eliyordu ama Thumbs.db/desktop.ini'yi elemiyor, telefon tarafı ise
    // hiçbir şeyi elemiyordu (.nomedia listede duruyordu).
    val showHidden by actions.showHiddenFiles.collectAsState()
    val visible = remember(ops.entries, showHidden) { filterHidden(ops.entries, showHidden) }

    // Sıralama listeden yukarı çekildi: hem "tümünü seç" hem görüntüleyicinin
    // komşu sırası EKRANDAKİ sırayla aynı olsun (tarihe göre sıralıysan
    // kaydırma da tarihe göre ilerler).
    val dirs = sortDirEntries(visible.filter { it.type == "dir" }, sortKey, sortAscending)
    val files = sortDirEntries(visible.filter { it.type != "dir" }, sortKey, sortAscending)
    val visiblePaths = remember(dirs, files) { (dirs + files).map { it.path } }
    val imagePaths = remember(files) { files.filter { isImageFile(it.name) }.map { it.path } }

    Column(Modifier.fillMaxSize()) {
        if (picking) {
            // Seçici şeridi HER ZAMAN görünür (seçim boşken de): "kaç dosya
            // seçtim" ve "nasıl onaylarım" ekranda dursun.
            PickerBar(
                count = selected.size,
                fileCount = files.size,
                allSelected = files.isNotEmpty() && selected.containsAll(files.map { it.path }),
                onSelectAll = { selected = selectAllToggle(selected, files.map { it.path }) },
                onClear = { selected = emptySet() },
                onConfirm = {
                    val chosen = files.filter { it.path in selected }
                    // Seçim klasör değiştirince yaşadığı için, onayda YALNIZ bu
                    // klasörde görünenler değil tüm seçililer lazım. Dosya
                    // nesnesini kaybetmemek adına ad yoldan türetiliyor.
                    val all = selected.map { p ->
                        chosen.firstOrNull { it.path == p }
                            ?: DirEntry(name = p.replace('\\', '/').substringAfterLast('/'), path = p, type = "file")
                    }
                    if (all.isNotEmpty()) picker.onConfirm(all)
                },
            )
        }
        if (!picking && selectionMode) {
            SelectionBar(
                count = selected.size,
                allSelected = visiblePaths.isNotEmpty() && selected.containsAll(visiblePaths),
                onSelectAll = { selected = selectAllToggle(selected, visiblePaths) },
                onClear = { selected = emptySet() },
                onCut = { clipboard = FileClipboard(selected.toList(), move = true, sourceDir = ops.base); selected = emptySet() },
                onCopy = { clipboard = FileClipboard(selected.toList(), move = false, sourceDir = ops.base); selected = emptySet() },
                onDelete = { multiDeleteTargets = selected.toList() },
            )
        }
        // Pano doluyken yapıştırma şeridi: hangi klasörde olursan ol görünür.
        if (!picking && !selectionMode && !clipboard.isEmpty) {
            PasteBar(
                clipboard = clipboard,
                enabled = canPasteInto(clipboard, ops.base),
                onPaste = {
                    ops.paste(clipboard.paths, ops.base, clipboard.move)
                    clipboard = clipboardAfterPaste(clipboard)
                },
                onCancel = { clipboard = FileClipboard() },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { goTo(rootLock ?: "") }) { Icon(Icons.Default.Home, "Ana klasör", tint = Ui2.colors.ink2) }
            // Yenilerken halka: liste aynı kalınca (çoğu zaman öyle) tuş hiçbir
            // şey yapmıyormuş gibi hissettiriyordu — istek gerçekten gidiyordu.
            IconButton(onClick = { ops.load(ops.base) }, enabled = !ops.loading) {
                if (ops.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Ui2.colors.accent,
                    )
                } else {
                    Icon(Icons.Default.Refresh, "Yenile", tint = Ui2.colors.ink2)
                }
            }
            // Anahtar çevrildikten SONRA tazele: PC listesi köprüden geliyor,
            // eleme orada yapıldığı için yeniden çekmeden gizliler gelmez.
            IconButton(onClick = { actions.toggleShowHiddenFiles(); ops.load(ops.base) }) {
                Icon(
                    if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    if (showHidden) "Gizli dosyalar açık" else "Gizli dosyaları göster",
                    tint = if (showHidden) Ui2.colors.accent else Ui2.colors.ink2,
                )
            }
            // Seçicide yükleme yok: buraya dosya SEÇMEYE gelindi, koymaya değil.
            ops.upload?.takeIf { !picking }?.let { upload ->
                IconButton(onClick = upload) { Icon(Icons.Default.UploadFile, "Dosya yükle", tint = Ui2.colors.accent) }
            }
            Column {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, "Sırala", tint = Ui2.colors.ink2)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf(
                        Triple(FileSortKey.NAME, "Ad", true),
                        Triple(FileSortKey.MTIME, "Tarih", false),
                        Triple(FileSortKey.SIZE, "Boyut", false),
                    ).forEach { (option, label, defaultAscending) ->
                        val selected = sortKey == option
                        DropdownMenuItem(
                            text = { Text(label, color = if (selected) Ui2.colors.accent else Ui2.colors.ink) },
                            trailingIcon = {
                                if (selected) Icon(
                                    if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    if (sortAscending) "Artan" else "Azalan",
                                    tint = Ui2.colors.accent,
                                )
                            },
                            onClick = {
                                val newKey = if (selected) sortKey else option
                                val newAscending = if (selected) !sortAscending else defaultAscending
                                sortKeyName = newKey.name
                                sortAscending = newAscending
                                actions.saveFileSort(browserBase, newKey, newAscending)
                                sortMenu = false
                            },
                        )
                    }
                }
            }
            // Yol artık burada değil: üstteki konum şeridinde, kaynak
            // anahtarının solunda duruyor.
            Box(Modifier.weight(1f))
        }
        // Sürücü şeridi. Windows'ta sürücü kökünün ÜSTÜ olmadığı için C:\'den
        // D:\'ye geçmenin başka yolu yoktu — "üst klasör" zinciri C:\'de bitiyordu.
        // Kök-kilitli gezginde ve tek sürücülü makinede gösterilmez: ilki kilidi
        // delerdi, ikincisi tek seçenekli gereksiz bir şerit olurdu.
        if (rootLock == null && ops.driveRoots.size > 1) {
            SegmentedTabs(
                options = ops.driveRoots.map { it.name },
                selectedIndex = ops.driveRoots.indexOfFirst { isUnderDrive(ops.base, it.path) },
                onSelect = { index -> ops.driveRoots.getOrNull(index)?.let { goTo(it.path) } },
                modifier = Modifier.padding(
                    horizontal = Ui2Tokens.screenPadding,
                    vertical = Ui2Tokens.s4,
                ),
            )
        }
        when {
            ops.loading && ops.entries.isEmpty() -> LoadingSkeleton(Modifier.padding(Ui2Tokens.screenPadding), rows = 5)
            // Okunamayan klasörü "boş" diye göstermek kullanıcıyı yanlış yere
            // bakmaya iter; izin sorunu ayrı mesajı hak ediyor.
            ops.errorText.isNotBlank() -> EmptyState(
                title = ops.errorText,
                description = "Klasör okunamadı. Tüm dosyalara erişim izni kapalı olabilir.",
                icon = Icons.Outlined.Description,
            )
            ops.entries.isEmpty() && ops.base.isNotBlank() -> EmptyState(
                title = "Bu klasör boş",
                description = ops.emptyDescription,
                icon = Icons.Outlined.Description,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Ui2Tokens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
            ) {
                if (parent != null) item {
                    SurfaceCard(onClick = { goTo(parent) }) {
                        ListRow(title = "Üst klasör", detail = parent, leading = { Icon(Icons.Default.ArrowUpward, null, tint = Ui2.colors.ink2) })
                    }
                }
                if (dirs.isNotEmpty()) item { SectionHeader("Klasörler") }
                items(dirs, key = { it.path }) { entry ->
                    FileEntryCard(
                        entry, isDirectory = true, modifier = Modifier.animateItem(),
                        selected = entry.path in selected,
                        selectionMode = selectionMode,
                        // Seçim modundayken dokunmak klasöre girmez, seçer.
                        // Seçicide klasör her zaman gezinir: klasör seçilmiyor.
                        onOpen = { if (selectionMode && !picking) selected = toggleSelection(selected, entry.path) else goTo(entry.path) },
                        onMore = { contextEntry = entry },
                        onLongPress = if (picking) ({}) else ({ selected = toggleSelection(selected, entry.path) }),
                        showMore = !picking,
                    )
                }
                if (files.isNotEmpty()) item { SectionHeader("Dosyalar") }
                items(files, key = { it.path }) { entry ->
                    FileEntryCard(
                        entry, isDirectory = false, modifier = Modifier.animateItem(),
                        selected = entry.path in selected,
                        // Seçicide onay dairesi HEP görünür: seçim boşken de
                        // "buraya dokunursam seçilir" belli olsun.
                        selectionMode = selectionMode || picking,
                        // Önizleme yalnız telefon tarafında: PC'deki dosyanın
                        // küçük resmi için dosyanın tamamını indirmek gerekirdi.
                        showThumbnail = ops.localFiles,
                        onOpen = {
                            if (picking || selectionMode) {
                                selected = toggleSelection(selected, entry.path)
                            } else {
                                // Görüntüleyici klasörde kaydırabilsin diye komşu
                                // görselleri açmadan hemen önce veriyoruz.
                                actions.setImageNavigation(imagePaths, ops.localFiles)
                                onFileTap(entry)
                            }
                        },
                        onMore = { contextEntry = entry },
                        onLongPress = if (picking) ({}) else ({ selected = toggleSelection(selected, entry.path) }),
                        showMore = !picking,
                    )
                }
            }
        }
    }

    contextEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { contextEntry = null },
            title = { Text(entry.name) },
            text = {
                Column {
                    // Dahili viewer'ı atlayıp cihazdaki başka bir uygulamayla açar
                    // (seçici çıkar); dönüşte değişiklik PC'ye geri yazılır.
                    if (entry.type != "dir") {
                        TextButton(onClick = { ops.openExternal(entry); contextEntry = null }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text("Birlikte aç")
                        }
                        // Yön kaynağa göre ters: PC'de "telefona indir",
                        // telefonda "PC'ye kopyala".
                        TextButton(onClick = { ops.transfer(entry); contextEntry = null }) {
                            Icon(Icons.Outlined.Download, null); Text(ops.transferLabel)
                        }
                    }
                    // Yol panoya: sohbete elle yol yazmak yerine yapıştırmak için.
                    // Klasörlerde de var — bir agent'a "şu klasörde çalış" demek
                    // en sık istenen şey.
                    TextButton(onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(entry.path))
                        actions.notifyUser("Yol kopyalandı")
                        contextEntry = null
                    }) {
                        Icon(Icons.Default.ContentCopy, null); Text("Yolu kopyala")
                    }
                    TextButton(onClick = { editMode = "rename"; editText = entry.name }) { Icon(Icons.Default.DriveFileRenameOutline, null); Text("Yeniden adlandır") }
                    TextButton(onClick = { editMode = "move"; editText = ops.base }) { Icon(Icons.Default.Folder, null); Text("Taşı") }
                    TextButton(onClick = { deleteEntry = entry }) { Icon(Icons.Default.Delete, null, tint = Ui2.colors.danger); Text("Sil", color = Ui2.colors.danger) }
                }
            },
            confirmButton = { TextButton(onClick = { contextEntry = null }) { Text("Kapat") } },
        )
    }
    if (editMode.isNotBlank() && contextEntry != null) {
        val entry = contextEntry!!
        AlertDialog(
            onDismissRequest = { editMode = "" },
            title = { Text(if (editMode == "rename") "Yeniden adlandır" else "Taşı") },
            text = { OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (editMode == "rename") "Yeni ad" else "Hedef klasör") }) },
            confirmButton = { TextButton(onClick = {
                if (editText.isNotBlank()) {
                    if (editMode == "rename") ops.rename(entry.path, editText)
                    else ops.move(entry.path, editText)
                    editMode = ""; contextEntry = null
                }
            }) { Text(if (editMode == "rename") "Kaydet" else "Taşı") } },
            dismissButton = { TextButton(onClick = { editMode = "" }) { Text("Vazgeç") } },
        )
    }
    deleteEntry?.let { entry ->
        ConfirmDialog(
            title = "${entry.name} silinsin mi?",
            text = if (entry.type == "dir") "Klasör ve içindeki tüm dosyalar kalıcı olarak silinecek." else "Dosya kalıcı olarak silinecek.",
            confirmLabel = "Sil",
            onConfirm = { ops.delete(entry.path); deleteEntry = null; contextEntry = null },
            onDismiss = { deleteEntry = null },
        )
    }
    if (multiDeleteTargets.isNotEmpty()) {
        val hedefler = multiDeleteTargets
        ConfirmDialog(
            title = "${hedefler.size} öğe silinsin mi?",
            text = "Seçilenler kalıcı olarak silinecek; klasörler içindekilerle birlikte gider.",
            confirmLabel = "Sil",
            onConfirm = {
                ops.deleteMany(hedefler)
                multiDeleteTargets = emptyList()
                selected = emptySet()
            },
            onDismiss = { multiDeleteTargets = emptyList() },
        )
    }
}

/**
 * "Bilgisayardan dosya seç" ekranı. Sohbet ekleri buradan geçer.
 *
 * PC/telefon anahtarı YOK: bu ekrana zaten "bilgisayardan" denerek gelindi,
 * ikinci bir yerde aynı soruyu sormak kafa karıştırır. Telefon seçilirse
 * sistemin kendi dosya seçicisi açılır, bu ekran hiç görünmez.
 */
@Composable
fun PcFilePickerScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onPicked: (List<DirEntry>) -> Unit,
) {
    val ops = rememberPcOps(uiState, actions, coworkOnly = false)
    // Ziyaret başına tek yükleme: seçiciden dönüp tekrar açınca kaldığın klasör
    // korunur, ama gezginin kendi durumunu ezmez.
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        loaded = true
        actions.loadBrowserDir("")
    }
    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "Bilgisayardan dosya seç",
            subtitle = shortDisplayPath(ops.base),
            onBack = onBack,
        )
        FilesPane(
            ops = ops,
            actions = actions,
            onFileTap = {},
            picker = FilePickerMode(onConfirm = onPicked),
        )
    }
}

@Composable
private fun rememberPcOps(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    coworkOnly: Boolean,
): FileBrowserOps {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { actions.uploadToBrowserDir(it, coworkOnly) }
    }
    return FileBrowserOps(
        entries = uiState.fileBrowserEntries,
        base = uiState.fileBrowserBase,
        loading = uiState.fileBrowserLoading,
        emptyDescription = "Dosya yükleyebilir veya üst klasöre dönebilirsin.",
        load = { actions.loadBrowserDir(it, coworkOnly) },
        rename = { path, name -> actions.renameBrowserEntry(path, name, coworkOnly) },
        move = { path, dest -> actions.moveBrowserEntry(path, dest, coworkOnly) },
        delete = { actions.deleteBrowserEntry(it, coworkOnly) },
        openExternal = { actions.openEntryWithExternalApp(it, coworkOnly) },
        transfer = { actions.downloadByPath(it.path, it.name) },
        transferLabel = "Telefona indir",
        upload = { picker.launch("*/*") },
        homeLabel = "Bilgisayar",
        paste = { paths, dest, move -> actions.pasteBrowserEntries(paths, dest, move, coworkOnly) },
        deleteMany = { actions.deleteBrowserEntries(it, coworkOnly) },
        // Cowork gezgini kök-kilitli: orada sürücü şeridi kilidin etrafından
        // dolaşmak olurdu, o yüzden yalnız serbest gezginde dolu.
        driveRoots = if (coworkOnly) emptyList() else uiState.fileDriveRoots,
    )
}

@Composable
private fun rememberPhoneOps(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    coworkOnly: Boolean,
): FileBrowserOps {
    val context = LocalContext.current
    return FileBrowserOps(
        entries = uiState.phoneEntries,
        base = uiState.phoneBase,
        loading = uiState.phoneLoading,
        emptyDescription = "PC gezgininden \"Telefona indir\" ile buraya dosya gönderebilirsin.",
        errorText = uiState.phoneError,
        load = { actions.loadPhoneDir(it) },
        rename = { path, name -> actions.renamePhoneEntry(path, name) },
        move = { path, dest -> actions.movePhoneEntry(path, dest) },
        delete = { actions.deletePhoneEntry(it) },
        openExternal = { entry ->
            // file:// doğrudan verilemez (FileUriExposedException) — FileProvider şart.
            actions.phoneFileViewIntent(entry.path)?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        },
        transfer = { actions.copyPhoneEntryToPc(it.path, coworkOnly) },
        transferLabel = "PC'ye kopyala",
        upload = null,
        homeLabel = "Telefon",
        paste = { paths, dest, move -> actions.pastePhoneEntries(paths, dest, move) },
        deleteMany = { actions.deletePhoneEntries(it) },
        localFiles = true,
    )
}

@Composable
private fun FileEntryCard(
    entry: DirEntry,
    isDirectory: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    showThumbnail: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    // Seçici modunda düzenleme yüzeyi yok: üç nokta hiç çizilmez.
    showMore: Boolean = true,
) {
    // Uzun basış artık ÇOKLU SEÇİME girer; tek dosya işlemleri üç nokta menüsünde
    // kaldı (eskiden uzun basış o menüyü açıyordu).
    SurfaceCard(modifier = modifier, onClick = onOpen, onLongClick = onLongPress ?: onMore) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    if (selected) "Seçili" else "Seçili değil",
                    tint = if (selected) Ui2.colors.accent else Ui2.colors.ink3,
                )
            }
            val fallbackIcon: @Composable () -> Unit = {
                Icon(
                    if (isDirectory) Icons.Default.Folder else Icons.Outlined.Description,
                    null,
                    tint = if (isDirectory) Ui2.colors.accent else Ui2.colors.ink2,
                )
            }
            if (showThumbnail && !isDirectory && hasThumbnail(entry.name)) {
                FileThumbnail(path = entry.path, name = entry.name, fallback = fallbackIcon)
            } else {
                fallbackIcon()
            }
            Column(Modifier.weight(1f).padding(horizontal = Ui2Tokens.s12)) {
                // maxLines=1 tek başına uzun bölünemez yolu 2. satıra sarıp gizliyordu
                // ("C:\Users\...\çalışma alanı" yalnız "C:" görünüyordu); Ellipsis şart.
                Text(entry.name, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (isDirectory) entry.path else "${formatByteCount(entry.size)} · ${entry.path}", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Seçim modunda üç nokta yer kaplamasın; işlemler üstteki çubukta.
            if (showMore && !selectionMode) {
                IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "İşlemler", tint = Ui2.colors.ink3) }
            }
        }
    }
}

/**
 * Seçici şeridi. Seçim çubuğunun yerine geçer ama işlevi tersine: kes/kopyala/sil
 * yerine tek bir onay var. Boş seçimde de çizilir ki "n dosya seçildi" sayacı ve
 * onay düğmesi ekrandan kaybolmasın.
 */
@Composable
private fun PickerBar(
    count: Int,
    fileCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
    ) {
        if (count > 0) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Seçimi bırak", tint = Ui2.colors.ink2) }
        }
        Text(
            if (count > 0) selectionTitle(count) else "Dosyaları seç",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = Ui2.colors.ink,
        )
        if (fileCount > 0) {
            IconButton(onClick = onSelectAll) {
                Icon(
                    if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    if (allSelected) "Seçimi kaldır" else "Tümünü seç",
                    tint = if (allSelected) Ui2.colors.accent else Ui2.colors.ink2,
                )
            }
        }
        TextButton(onClick = onConfirm, enabled = count > 0) {
            Text(if (count > 0) "Ekle ($count)" else "Ekle")
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Seçimi bırak", tint = Ui2.colors.ink2) }
        Text(
            selectionTitle(count),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = Ui2.colors.ink,
        )
        // Aynı düğme hepsi seçiliyken seçimi bırakır — ikinci bir düğme çubuğu şişirirdi.
        IconButton(onClick = onSelectAll) {
            Icon(
                if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                if (allSelected) "Seçimi kaldır" else "Tümünü seç",
                tint = if (allSelected) Ui2.colors.accent else Ui2.colors.ink2,
            )
        }
        IconButton(onClick = onCut) { Icon(Icons.Default.ContentCut, "Kes", tint = Ui2.colors.ink2) }
        IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Kopyala", tint = Ui2.colors.ink2) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Sil", tint = Ui2.colors.danger) }
    }
}

@Composable
private fun PasteBar(
    clipboard: FileClipboard,
    enabled: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${clipboard.paths.size} öğe panoda · ${if (clipboard.move) "taşınacak" else "kopyalanacak"}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = Ui2.colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onCancel) { Text("Vazgeç", color = Ui2.colors.ink3) }
        // Kes-yapıştır aynı klasöre kapalı: hiçbir şey değişmezken "taşındı"
        // demek yanıltıcı olur.
        TextButton(onClick = onPaste, enabled = enabled) {
            Text("Yapıştır", color = if (enabled) Ui2.colors.accent else Ui2.colors.ink3)
        }
    }
}

// Cowork dosya gezgini: CoworkSpaces köküne kilitli. Markdown ve DOCX native
// editörde; diğer türler harici uygulamada açılır ve dönüşte PC'ye geri yazılır.
@Composable
fun CoworkFilesScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onBack: () -> Unit,
    onOpenViewer: () -> Unit,
) {
    // İKİ AYRI KAVRAM, tek değişkende birleştirilmemeli:
    // - rootLock: gezginin çıkamayacağı SINIR (CoworkSpaces kökü).
    // - startDir: bu ziyarette AÇILACAK klasör (bir çalışma alanından girildiyse
    //   o alanın klasörü).
    // Eskiden ikisi de rootLock'tu ve kök doluysa hep kök kazanıyordu: çalışma
    // alanının içinden "Dosyalar"a girince alanın klasörü yerine genel
    // CoworkSpaces kökü açılıyordu (canlı hata 03.08.2026). ViewModel doğru
    // klasörü yüklüyordu, buradaki efekt hemen üstüne kökü yazıyordu.
    val scope = coworkBrowserScope(uiState.coworkRootPath, uiState.coworkFilesStart)
    val rootLock = scope.rootLock
    val startDir = scope.startDir
    var tab by rememberSaveable { mutableStateOf(0) }
    // Hub gezginiyle aynı kural: ziyaret başına bir kez yüklenir, dosya açıp
    // dönünce bulunulan klasör korunur (bkz. HubFilesScreen'deki açıklama).
    var pcLoaded by rememberSaveable { mutableStateOf(false) }
    var phoneLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startDir) {
        if (pcLoaded) return@LaunchedEffect
        // startDir sonradan dolabilir; boşken bayrağı yakmıyoruz.
        if (startDir.isBlank()) {
            actions.loadCoworkWorkspaces()
            return@LaunchedEffect
        }
        pcLoaded = true
        // filesOpenDir: bu ziyarette açılacak klasör — sohbetteki klasör tuşu
        // son gezilen alt klasörü verir. startDir yüklenseydi ViewModel'in az
        // önce açtığı alt klasör alanın köküyle EZİLİRDİ (iki yükleme yarışıyor,
        // bu efekt sonra çalışır). Kök kilidi ve telefon aynası startDir'den
        // türemeye devam ediyor, o yüzden yalnız yüklenen yol değişiyor.
        actions.loadBrowserDir(uiState.coworkFilesOpenDir.ifBlank { startDir }, coworkOnly = true)
    }
    // Çalışma alanının telefondaki karşılığı: PC'deki klasör adı birebir
    // korunur. Kaynak startDir — kök değil; aksi halde bir alanın içinden
    // girildiğinde telefon sekmesi kökün aynasını açardı.
    // TEMBEL oluşturma: klasör ancak telefon sekmesine basınca açılır.
    val workspaceName = startDir.replace('\\', '/').trimEnd('/').substringAfterLast('/')
    var phoneRoot by remember(workspaceName) { mutableStateOf("") }
    LaunchedEffect(tab, workspaceName) {
        if (tab == 1 && workspaceName.isNotBlank() && actions.phoneStoragePermitted()) {
            if (phoneRoot.isBlank()) phoneRoot = actions.phoneCoworkSpacePath(workspaceName)
            if (phoneRoot.isNotBlank() && !phoneLoaded) {
                phoneLoaded = true
                actions.loadPhoneDir(phoneRoot)
            }
        }
    }
    val pcOps = rememberPcOps(uiState, actions, coworkOnly = true)
    val phoneOps = rememberPhoneOps(uiState, actions, coworkOnly = true)
    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        ScreenHeader(
            title = "Dosyalar",
            subtitle = "Markdown/DOCX uygulama içinde; diğer dosyalar dış uygulamada açılır",
            onBack = onBack,
        )
        FilesLocationBar(
            // Cowork gezgini köke kilitli; yolu köke göreli göstermek daha
            // okunur (mutlak yol ekrana sığmıyor).
            path = if (tab == 0) relativeToRoot(uiState.fileBrowserBase, rootLock).ifBlank { "Kök klasör" }
            else relativeToRoot(uiState.phoneBase, phoneRoot).ifBlank { "Telefon" },
            tab = tab,
            onSelect = { tab = it },
        )
        when {
            tab == 1 && !actions.phoneStoragePermitted() -> PhonePermissionNotice(actions)
            tab == 1 -> FilesPane(
                ops = phoneOps,
                actions = actions,
                onFileTap = { entry -> openPhoneEntry(entry, actions, phoneOps, onOpenViewer) },
                // Telefon tarafında da alanın klasörüne kilitli: cowork gezgini
                // çalışma alanının dışına çıkmaz, iki kaynakta da aynı korkuluk.
                rootLock = phoneRoot.ifBlank { null },
            )
            rootLock.isBlank() -> LoadingSkeleton(Modifier.padding(Ui2Tokens.screenPadding), rows = 5)
            else -> FilesPane(
                ops = pcOps,
                actions = actions,
                onFileTap = { entry ->
                    if (actions.openFileEntry(entry, coworkOnly = true)) onOpenViewer()
                },
                rootLock = rootLock,
            )
        }
    }
}

@Composable
fun HubFileViewerScreen(actions: RemoteViewModel, onBack: () -> Unit) {
    val file by actions.openedFile.collectAsState()
    val docx by actions.openedDocx.collectAsState()
    val image by actions.openedImage.collectAsState()
    val pdf by actions.openedPdf.collectAsState()
    val video by actions.openedVideo.collectAsState()
    val sheet by actions.openedSheet.collectAsState()
    val showingDocx = docx.loading || docx.path.isNotBlank() || docx.error.isNotBlank()
    val showingImage = image.loading || image.path.isNotBlank() || image.error.isNotBlank()
    val showingPdf = pdf.loading || pdf.path.isNotBlank() || pdf.error.isNotBlank()
    val showingVideo = video.loading || video.path.isNotBlank() || video.error.isNotBlank()
    val showingSheet = sheet.loading || sheet.path.isNotBlank() || sheet.error.isNotBlank()
    // Tam yol ARTIK alt başlıkta değil: 366dp'lik telefonda üç satır kaplayıp
    // belgeye kalan yeri yiyordu (kullanıcı kararı 05.08.2026). Yol "Bilgi"
    // menü öğesinde, kaydetme de aynı taşma menüsünde.
    var viewerMenu by remember { mutableStateOf(false) }
    var showFileInfo by rememberSaveable { mutableStateOf(false) }
    val imzaDurumu by actions.mobilImza.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(Ui2.colors.bg)) {
        val fullPath = when {
            showingImage -> image.path
            showingPdf -> pdf.path
            showingVideo -> video.path
            showingSheet -> sheet.path
            showingDocx -> docx.path
            else -> file?.path?.ifBlank { file?.name.orEmpty() }.orEmpty().ifBlank { file?.name.orEmpty() }
        }
        val fileTitle = fullPath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "Dosya" }
        // Görselin tüm kontrolleri altta: içerik kalan yüksekliği kullanır,
        // işlem şeridi ve ardından geri/dosya menüsü sabit kalır.
        if (showingImage) {
            ImageViewer(
                state = image,
                actions = actions,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(
                    horizontal = Ui2Tokens.screenPadding,
                    vertical = Ui2Tokens.s8,
                ),
                onClose = { actions.closeFile(); onBack() },
            )
        }
        ScreenHeader(
            title = fileTitle,
            onBack = { actions.closeFile(); onBack() },
            // Tek satırlık başlık: dikey boşluk içeriğe gitsin.
            dense = true,
            trailing = {
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Kalem: belgeyi harici düzenleyiciye devreder (.udf →
                        // UDF Editor Pro). Yerel kopya uygulama-özel klasöre iner,
                        // dönüşte değişiklik PC'ye geri yazılır (openForEdit/syncBack).
                        if (showingDocx && docx.path.isNotBlank()) {
                            IconButton(
                                onClick = { actions.editOpenedDocumentExternally() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    if (isUdfFile(docx.path)) "UDF Editor Pro'da düzenle"
                                    else "Harici uygulamada düzenle",
                                    tint = if (docx.dirty) Ui2.colors.ink3 else Ui2.colors.accent,
                                )
                            }
                        }
                        IconButton(onClick = { viewerMenu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, "Dosya menüsü", tint = Ui2.colors.ink2)
                        }
                    }
                    DropdownMenu(expanded = viewerMenu, onDismissRequest = { viewerMenu = false }) {
                        if (showingDocx) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when {
                                            docx.saving -> "Kaydediliyor…"
                                            // Telefondan açılan belgenin hedefi telefon.
                                            docx.phoneLocal -> "Telefona kaydet"
                                            else -> "PC'ye kaydet"
                                        }
                                    )
                                },
                                enabled = docx.dirty && !docx.saving,
                                leadingIcon = { Icon(Icons.Outlined.Save, null) },
                                onClick = { actions.saveOpenedDocx(); viewerMenu = false },
                            )
                        }
                        // Mobil imza YALNIZ imzasız UDF'te: DOCX'te imza kavramı
                        // yok, imzalı belgede ikinci imza da atılmıyor (UYAP
                        // paralel imzayı bu uçtan kabul etmiyor).
                        if (showingDocx && docx.udfDocument != null && !docx.signed) {
                            DropdownMenuItem(
                                text = { Text("Mobil imza") },
                                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                                onClick = { viewerMenu = false; actions.mobilImzaAc() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Bilgi") },
                            leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { viewerMenu = false; showFileInfo = true },
                        )
                    }
                }
            },
        )
        MobilImzaDialog(imzaDurumu, actions)
        if (showFileInfo) {
            AlertDialog(
                onDismissRequest = { showFileInfo = false },
                title = { Text("Dosya bilgisi") },
                text = {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                            Text(fileTitle, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
                            Text(
                                fullPath.ifBlank { "Yol bilinmiyor" },
                                style = MaterialTheme.typography.bodySmall,
                                color = Ui2.colors.ink2,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(fullPath))
                            showFileInfo = false
                        },
                        enabled = fullPath.isNotBlank(),
                    ) { Text("Yolu kopyala") }
                },
                dismissButton = { TextButton(onClick = { showFileInfo = false }) { Text("Kapat") } },
            )
        }
        when {
            showingImage -> Unit // İçerik alt başlıktan önce çizildi.
            // PDF dalı da görsel gibi verticalScroll'suz: sayfa geçişi ve zoom
            // jestleri dış kaydırmayla çakışır.
            showingPdf -> PdfViewer(
                state = pdf,
                actions = actions,
                modifier = Modifier.fillMaxSize().padding(Ui2Tokens.screenPadding),
            )
            showingVideo -> VideoViewer(
                state = video,
                actions = actions,
                modifier = Modifier.fillMaxSize().padding(Ui2Tokens.screenPadding),
            )
            // Izgara ekranın tamamını kullanmalı: yanlarda screenPadding kalırsa
            // 366dp'lik telefonda iki sütun daha az sığıyor.
            showingSheet -> SheetViewer(
                state = sheet,
                actions = actions,
                modifier = Modifier.fillMaxSize().padding(
                    start = Ui2Tokens.s8,
                    end = Ui2Tokens.s8,
                    top = Ui2Tokens.s4,
                    bottom = Ui2Tokens.s8,
                ),
            )
            showingDocx -> DocxLiteEditor(
                state = docx,
                actions = actions,
                // Üstte screenPadding YOK: araç çubuğu başlığa yaslansın, kazanılan
                // yer belgeye gitsin. Yatay/alt boşluk standart kalır.
                modifier = Modifier.fillMaxSize().padding(
                    start = Ui2Tokens.screenPadding,
                    end = Ui2Tokens.screenPadding,
                    top = Ui2Tokens.s4,
                    bottom = Ui2Tokens.screenPadding,
                ),
                // Kaydetme başlıktaki taşma menüsüne taşındı; alttaki tam
                // genişlik tuşu ekranın dibinden 48dp daha yiyordu.
                showSaveButton = false,
            )
            file == null -> LoadingSkeleton(Modifier.padding(Ui2Tokens.screenPadding), rows = 6)
            !file!!.ok -> EmptyState(title = "Dosya açılamadı", description = file!!.error, icon = Icons.Outlined.Description)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Ui2Tokens.screenPadding),
            ) {
                MarkdownDocumentEditor(file = file!!, actions = actions, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
