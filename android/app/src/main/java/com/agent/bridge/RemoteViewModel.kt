package com.agent.bridge
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import com.agent.bridge.udf.MobilImzaDurumu
import com.agent.bridge.udf.MobilImzaManager
import com.agent.bridge.udf.mobilImzaGirdiHatasi
import com.agent.bridge.udf.UdfLineRef
import com.agent.bridge.udf.udfSignerNames
import com.agent.bridge.ui2.components.SelectorPinBridge
import com.agent.bridge.ui2.components.SelectorPinListener
import com.agent.bridge.ui2.components.SelectorPinStore

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val PDF_MIME = "application/pdf"
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

data class MarkdownSaveState(
    val saving: Boolean = false,
    val saved: Boolean = false,
    val conflict: Boolean = false,
    val error: String = "",
)

data class CoworkNotesUiState(
    val projectPath: String = "",
    val notes: List<CoworkNote> = emptyList(),
    // Notlarım arama kutusundaki metin. Kaynak burada: silme/yenileme sonrası
    // liste yeniden çekilirken arama sessizce düşmesin.
    val query: String = "",
    val loading: Boolean = false,
    val creating: Boolean = false,
    val actionNoteId: String = "",
    val error: String = "",
)

data class NoteAiUiState(
    val running: Boolean = false,
    val applying: Boolean = false,
    val preview: CoworkNoteAiPreview? = null,
    val error: String = "",
)

data class ShareFileRequest(
    val localPath: String,
    val name: String,
    val mimeType: String,
)

// Telefon belleğine tek seferde alınacak görsel sınırı (OOM koruması).
internal const val MAX_IMAGE_BYTES: Long = 60L * 1024 * 1024

// Gorunen sayfa + iki komsu icin ham bayt onbellegi tavani.
private const val IMAGE_CACHE_BYTES = 96 * 1024 * 1024

// PDF belleğe DEĞİL diske iniyor, o yüzden sınır görselden yüksek: taranmış UYAP
// dosyaları rahat 100MB'ı geçiyor. Tavan yine de var; sınırsız indirme telefonun
// önbelleğini sessizce doldurur.
internal const val MAX_PDF_BYTES: Long = 300L * 1024 * 1024

// Video da diske iniyor; sınır gezginden açılan çekim dosyaları için var.
internal const val MAX_VIDEO_BYTES: Long = 300L * 1024 * 1024

// Tablo PDF/videodan farklı: diske iniyor AMA sonra tümüyle ayrıştırılıyor, yani
// bellek maliyeti dosya boyutuyla birlikte büyüyor. DOCX ile aynı tavan.
internal const val MAX_SHEET_BYTES: Long = 25L * 1024 * 1024

// Okuma modu yazı boyutu (sp). Sohbet 13.5sp çiziyor; kitap okumak için o küçük,
// varsayılan biraz yukarıda. Sınırlar tek elle basılabilen tuşlarla gezilebilecek
// kadar dar tutuldu.
private const val READER_FONT_KEY = "pdf_reader_font_sp"
internal const val READER_FONT_DEFAULT = 16f
internal const val READER_FONT_MIN = 12f
internal const val READER_FONT_MAX = 26f

// Belge başına "kaldığı yer" — tek anahtarda JSON obje (bkz. PdfResumeStore.kt).
// Yerel dosyalarda da yazılır: konum kaydı köprü gerektirmiyor, doğrudan
// SharedPreferences'a iner.
private const val PDF_RESUME_KEY = "pdf_resume_positions"

// Köprüdeki ortak konum kaydını beklerken belgeyi açmayı geciktirme tavanı.
private const val POSITION_SYNC_TIMEOUT_MS = 4000L

private const val PREF_SHOW_HIDDEN = "browser_show_hidden"
private const val PREF_MIMZA_TEL = "mimza_tel"
private const val PREF_MIMZA_OP = "mimza_operator"

// Dahili görsel görüntüleyici durumu. bytes ham dosya içeriğidir (paylaşım orijinal
// biçimi korusun diye bitmap değil byte saklanır); çözme işi composable tarafında
// arka planda yapılır.
data class ImageViewerState(
    val loading: Boolean = false,
    val name: String = "",
    val path: String = "",
    val bytes: ByteArray? = null,
    val error: String = "",
    // Görsel telefonun kendi diskinde mi? Öyleyse "Telefona indir" anlamsız.
    val local: Boolean = false,
    // Klasördeki komşu görseller arasındaki yeri; index -1 ise gezinme kapalı
    // (dosya listeden değil, dış bir yoldan açılmış).
    val index: Int = -1,
    val total: Int = 0,
    val shuffled: Boolean = false,
    // Sohbette paylaşılan ek mi? Bu görseller iş bitince PC'den silindiği için
    // görüntüleyici iki ekstra tuş açar: kalıcı klasöre kaydet ve PC'den sil.
    // Gezgindeki normal dosyalarda ikisi de anlamsız (dosya zaten kalıcı, silme
    // listede uzun basınca var), o yüzden bayrağa bağlı.
    val attachment: Boolean = false,
)

/**
 * Uygulama içi PDF okuyucunun durumu.
 *
 * Görsel görüntüleyiciden farkı: bayt dizisi TUTULMAZ. PdfRenderer seekable bir
 * dosya istediği için PDF diske iniyor; bellekte tuttuğumuz tek şey o an çizilmiş
 * birkaç sayfa bitmap'i (PdfViewer içindeki önbellek). 200 sayfalık bir UYAP
 * evrakını bayt dizisi olarak tutmak çökme demekti.
 */
@androidx.compose.runtime.Immutable
data class PdfViewerState(
    val loading: Boolean = false,
    val name: String = "",
    // Kaynak yol: köprüdeki (ya da telefondaki) asıl dosya. Başlıkta gösterilir.
    val path: String = "",
    // Telefonun diskindeki okunabilir kopya; uzak dosyada önbellek kopyası olur.
    val localPath: String = "",
    val pageCount: Int = 0,
    val error: String = "",
    val local: Boolean = false,
    // İndirme oranı 0..1; boyut bilinmiyorsa -1 (belirsiz çubuk).
    val progress: Float = -1f,
    val sizeBytes: Long = 0,
)

/**
 * PDF okuma modunun durumu.
 *
 * PdfViewerState'ten AYRI tutuluyor: içerik yüzlerce KB metin ve sayfa
 * görünümünün durumuyla aynı akışta taşınırsa her yükleme adımı pager'ı da
 * yeniden kuruyor.
 *
 * [reason] yalnız hata/uygun değil hâlinde dolu. "taranmis" hata değil bir
 * cevaptır: belgede metin katmanı yok, sayfa görünümünde kalınır.
 */
@androidx.compose.runtime.Immutable
data class PdfReadState(
    val active: Boolean = false,
    val loading: Boolean = false,
    /** İçeriğin ait olduğu belge; başka PDF açılınca eşleşmez ve yeniden çekilir. */
    val path: String = "",
    val pages: List<ReaderPage> = emptyList(),
    val pageCount: Int = 0,
    /** Belge sayfa tavanını aştı: sonu okuma modunda yok. */
    val truncated: Boolean = false,
    /** Tablo tespiti bütçeyi aştı: sonraki sayfalarda tablolar düz metin. */
    val tablesTruncated: Boolean = false,
    val reason: String = "",
)

/**
 * Bir sayfanın dil kontrolü durumu.
 *
 * Sayfa BAŞINA tutulur ve yalnız istenirse çalışır: kontrol modele gidiyor
 * (ölçüm: sayfa başına ~14 sn ve ~28 bin token). Kitabın tamamını otomatik
 * taramak ne süre ne bütçe olarak makul.
 */
@androidx.compose.runtime.Immutable
data class PageLangState(
    val loading: Boolean = false,
    val done: Boolean = false,
    val findings: List<LangFinding> = emptyList(),
    val reason: String = "",
)

/**
 * Uygulama içi tablo okuyucunun durumu.
 *
 * PDF/video ile aynı mantık (dosya diske iner) AMA ek olarak ayrıştırılmış hâli
 * bellekte tutulur: ızgarayı çizmek için hücre metinleri gerekiyor. Tavanlar
 * XlsxLite tarafında (satır/sütun) ve MAX_SHEET_BYTES'ta.
 */
@androidx.compose.runtime.Immutable
data class SheetViewerState(
    val loading: Boolean = false,
    val name: String = "",
    /** Kaynak yol: köprüdeki (ya da telefondaki) asıl dosya. */
    val path: String = "",
    /** Telefonun diskindeki okunabilir kopya; paylaş/birlikte aç bunu kullanır. */
    val localPath: String = "",
    val sheets: List<SheetGrid> = emptyList(),
    /** Açık sekme; sayfa seçimi ekran döndürmede kaybolmasın diye burada. */
    val selected: Int = 0,
    val error: String = "",
    val local: Boolean = false,
    /** İndirme oranı 0..1; boyut bilinmiyorsa -1 (belirsiz çubuk). */
    val progress: Float = -1f,
)

/**
 * Uygulama içi video oynatıcının durumu.
 *
 * PDF'teki mantığın aynısı: bayt dizisi tutulmaz, dosya diske iner ve ExoPlayer
 * o dosyayı okur. Videoyu belleğe almak 8 saniyelik bir klipte bile gereksiz,
 * uzun bir çekimde çökme sebebi.
 */
@androidx.compose.runtime.Immutable
data class VideoViewerState(
    val loading: Boolean = false,
    val name: String = "",
    /** Kaynak yol: köprüdeki (ya da telefondaki) asıl dosya. */
    val path: String = "",
    /** Telefonun diskindeki oynatılabilir kopya. */
    val localPath: String = "",
    val error: String = "",
    val local: Boolean = false,
    /** İndirme oranı 0..1; boyut bilinmiyorsa -1 (belirsiz çubuk). */
    val progress: Float = -1f,
    val sizeBytes: Long = 0,
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val settingsViewModel = SettingsViewModel(
        prefs = prefs,
        defaultUrl = if (BuildConfig.IS_LITE) BuildConfig.LITE_BRIDGE_URL else DEFAULT_URL,
        liteEdition = BuildConfig.IS_LITE,
    )
    private val client = BridgeClient(if (BuildConfig.IS_LITE) "lite" else "")
    private val updateManager = AndroidUpdateManager(application.applicationContext)
    private val downloadRepo = AndroidDownloadRepo(application.applicationContext)
    private val offlineConversationCache = OfflineConversationCache(application.applicationContext)
    // Sekme geçişinde son bilinen konuşmanın anında gösterilmesi (bkz.
    // ConversationMemory). Delegelerden ÖNCE tanımlı olmalı — alanlar bildirim
    // sırasıyla kurulur, delegeler constructor'da bunları alır.
    private val conversationMemory = ConversationMemory()
    private val conversationPrefiller = ConversationPrefiller(
        memory = conversationMemory,
        cache = offlineConversationCache,
        scope = viewModelScope,
        update = { mutator -> _uiState.update(mutator) },
    )
    private val _uiState = MutableStateFlow(settingsViewModel.loadInitialState())
    // Süreç ölümünden dönen kuyruk, aktif oturumun canlı running durumu ilk kez
    // okunmadan gönderilmez. Aksi halde başlangıçtaki varsayılan running=false,
    // gerçekte hâlâ çalışan backend'e prompt'u erken yollayabilirdi.
    private val promptQueueAwaitingHydration =
        _uiState.value.promptQueue.mapTo(mutableSetOf()) { it.id }
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    private val _attaching = MutableStateFlow(false)
    val attaching: StateFlow<Boolean> = _attaching.asStateFlow()
    private val _openedFile = MutableStateFlow<FileResult?>(null)
    val openedFile: StateFlow<FileResult?> = _openedFile.asStateFlow()
    private val _markdownSaveState = MutableStateFlow(MarkdownSaveState())
    val markdownSaveState: StateFlow<MarkdownSaveState> = _markdownSaveState.asStateFlow()
    private val markdownSaveMutex = Mutex()
    private val _coworkNotesState = MutableStateFlow(CoworkNotesUiState())
    val coworkNotesState: StateFlow<CoworkNotesUiState> = _coworkNotesState.asStateFlow()
    private val _noteAiState = MutableStateFlow(NoteAiUiState())
    val noteAiState: StateFlow<NoteAiUiState> = _noteAiState.asStateFlow()
    private val _openedDocx = MutableStateFlow(DocxEditorState())
    val openedDocx: StateFlow<DocxEditorState> = _openedDocx.asStateFlow()

    private val _mobilImza = MutableStateFlow<MobilImzaDurumu>(MobilImzaDurumu.Kapali)
    val mobilImza: StateFlow<MobilImzaDurumu> = _mobilImza.asStateFlow()
    private var mobilImzaIsi: Job? = null
    private val _openedImage = MutableStateFlow(ImageViewerState())
    val openedImage: StateFlow<ImageViewerState> = _openedImage.asStateFlow()
    private var imageOpenGeneration: Long = 0
    private val _openedPdf = MutableStateFlow(PdfViewerState())
    val openedPdf: StateFlow<PdfViewerState> = _openedPdf.asStateFlow()
    // Açık PdfRenderer: kapanışta MUTLAKA serbest bırakılır (native descriptor).
    private var openedPdfRenderer: PdfPageRenderer? = null
    private var pdfOpenGeneration: Long = 0
    private var pdfFontAssets: PdfFontAssets? = null
    private val _pdfRead = MutableStateFlow(PdfReadState())
    val pdfRead: StateFlow<PdfReadState> = _pdfRead.asStateFlow()
    // Okuyucu yazı boyutu belgeye değil OKUYUCUYA ait: her açılışta yeniden
    // ayarlamak istemeyeceği için kalıcı.
    private val _readerFontSize = MutableStateFlow(prefs.getFloat(READER_FONT_KEY, READER_FONT_DEFAULT))
    val readerFontSize: StateFlow<Float> = _readerFontSize.asStateFlow()
    // Konum haritası StateFlow DEĞİL: pager'ın initialPage'i composable'ın ilk
    // kompozisyonunda senkron gerekiyor (bkz. pdfResume). Bellek içi kopya,
    // her yazımda hem güncellenir hem prefs'e yazılır — okuma her seferinde
    // JSON çözmesin diye.
    private var pdfResumeCache: Map<String, PdfResumePosition>? = null
    // Sayfa numarası → dil kontrolü durumu. Belge değişince sıfırlanır.
    private val _pageLang = MutableStateFlow<Map<Int, PageLangState>>(emptyMap())
    val pageLang: StateFlow<Map<Int, PageLangState>> = _pageLang.asStateFlow()
    private val _openedVideo = MutableStateFlow(VideoViewerState())
    val openedVideo: StateFlow<VideoViewerState> = _openedVideo.asStateFlow()
    private var videoOpenGeneration: Long = 0
    private val _openedSheet = MutableStateFlow(SheetViewerState())
    val openedSheet: StateFlow<SheetViewerState> = _openedSheet.asStateFlow()
    private var sheetOpenGeneration: Long = 0
    private var openedDocxPackage: EditableDocumentPackage? = null
    private var openedDocxCoworkOnly: Boolean = false
    private var openedDocxPhoneLocal: Boolean = false
    private var docxOpenGeneration: Long = 0
    private val _thoughtDetails = MutableStateFlow<Map<Int, String>>(emptyMap())
    val thoughtDetails: StateFlow<Map<Int, String>> = _thoughtDetails.asStateFlow()
    private val _loadingThoughts = MutableStateFlow<Set<Int>>(emptySet())
    val loadingThoughts: StateFlow<Set<Int>> = _loadingThoughts.asStateFlow()
    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    // Eylem tuşu taşıyan bildirimler. `messages` düz String taşıyor ve yüzlerce
    // çağrı yeri var; tipini değiştirmek yerine yanına ikinci bir kanal kondu.
    private val _alerts = MutableSharedFlow<AppAlert>()
    val alerts: SharedFlow<AppAlert> = _alerts.asSharedFlow()
    private val _approvalNavigationNonce = MutableStateFlow(0L)
    val approvalNavigationNonce: StateFlow<Long> = _approvalNavigationNonce.asStateFlow()

    // Dışarıdan .md açılınca görüntüleyiciye geç (onay navigasyonuyla aynı desen).
    private val _fileViewerNavigationNonce = MutableStateFlow(0L)
    val fileViewerNavigationNonce: StateFlow<Long> = _fileViewerNavigationNonce.asStateFlow()

    fun requestOpenFileViewer() = _fileViewerNavigationNonce.update { it + 1 }

    // Hatırlatıcı bildiriminden not açma (onay/dosya navigasyonuyla aynı desen).
    private val _noteNavigationNonce = MutableStateFlow(0L)
    val noteNavigationNonce: StateFlow<Long> = _noteNavigationNonce.asStateFlow()

    fun notifyUser(message: String) = viewModelScope.launch { _messages.emit(message) }
    // Sekme kapatma onayı tek yerde: sekme çarpısı ve geri jesti aynı diyaloğu
    // açar (diyalog Ui2Root'ta çizilir; fromBack, onay sonrası Merkez'e dönüş
    // kararı için taşınır).
    private val _pendingTabClose = MutableStateFlow<PendingTabClose?>(null)
    val pendingTabClose: StateFlow<PendingTabClose?> = _pendingTabClose.asStateFlow()
    private val _pendingShare = MutableStateFlow<PendingShare?>(null)
    val pendingShare: StateFlow<PendingShare?> = _pendingShare.asStateFlow()
    private val mcpDelegate = McpDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
    )
    private val fileBrowserDelegate = FileBrowserDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        openFile = { path -> openFile(path) },
        showHidden = { _showHiddenFiles.value },
    )
    // Sohbet şeridinden açılan cowork-DIŞI oturum klasörünün kökü. Normal
    // Merkez > Dosyalar gezginiyle karışmasın diye yalnız o girişte kurulur;
    // loadBrowserDir bu kökün altında gezinildiği sürece son alt klasörü yazar.
    private var activeSessionBrowserRoot = ""

    // Gezginin gizli dosya anahtarı: tüm klasörlerde ortak, kalıcı. Açılınca
    // köprüden de nokta-dosyalar istenir (aksi halde istemci filtresi boşa döner).
    private val _showHiddenFiles = MutableStateFlow(prefs.getBoolean(PREF_SHOW_HIDDEN, false))
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    // Yalnız bayrağı çevirir; listeyi çağıran tazeler (cowork kapsamını yalnız o
    // biliyor). PC listesi köprüden geldiği için tazeleme ŞART, telefon
    // listesinde filtre UI tarafında.
    fun toggleShowHiddenFiles() {
        val yeni = !_showHiddenFiles.value
        _showHiddenFiles.value = yeni
        prefs.edit().putBoolean(PREF_SHOW_HIDDEN, yeni).apply()
    }
    // Download ekran dışına çıkıldıktan sonra tamamlansa bile açma isteği kaybolmasın.
    // SharedFlow(replay=0) abone yokken olayı düşürüyordu; Channel bir sonraki
    // CoworkFilesScreen kolektörüne kadar tek-seferlik isteği saklar.
    private val _openForEditEvents = Channel<OpenForEditRequest>(Channel.BUFFERED)
    val openForEditEvents: Flow<OpenForEditRequest> = _openForEditEvents.receiveAsFlow()
    private val _shareFileEvents = Channel<ShareFileRequest>(Channel.BUFFERED)
    val shareFileEvents: Flow<ShareFileRequest> = _shareFileEvents.receiveAsFlow()
    private val coworkFileEditDelegate = CoworkFileEditDelegate(
        scope = viewModelScope,
        emit = { msg -> _messages.emit(msg) },
        editRoot = java.io.File(application.filesDir, "openedit"),
        store = PrefsOpenEditStore(application.getSharedPreferences("openedit", Context.MODE_PRIVATE)),
        download = { path ->
            client.downloadFile(_uiState.value.settings, path) { }.use { resp ->
                resp.body?.bytes() ?: ByteArray(0)
            }
        },
        save = { path, bytes, coworkOnly ->
            if (coworkOnly) client.saveCoworkFile(_uiState.value.settings, path, bytes)
            else client.saveFile(_uiState.value.settings, path, bytes)
        },
        requestOpen = { req -> _openForEditEvents.trySend(req) },
    )
    private val downloadsDelegate = DownloadsDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        downloadRepo = downloadRepo,
    )
    private val maintenanceDelegate = BackendMaintenanceDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        reportError = { prefix, error -> reportError(prefix, error) },
        awaitForeground = { AppForeground.isForeground.first { it } },
        onRunPodCapacityError = { message ->
            _alerts.emit(
                AppAlert(
                    text = message,
                    action = AppAlertAction.OpenUrl("https://console.runpod.io/pods"),
                    actionLabel = "RunPod'u aç",
                ),
            )
        },
    )
    private val updateDelegate = UpdateDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        updateManager = updateManager,
        updateInfo = _updateInfo,
        downloadProgress = _downloadProgress,
    )
    val tabsDelegate: TabsDelegate = TabsDelegate(
        prefs = PrefsKeyValueStore(prefs),
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        client = client,
        goToLanding = { goToLanding() },
        enterAgyMode = { enterAgyMode() },
        enterClaudeAppMode = { enterClaudeAppMode() },
        enterCodexAppMode = { enterCodexAppMode() },
        enterOpencodeAppMode = { enterOpencodeAppMode() },
        enterOpencode2AppMode = { enterOpencode2AppMode() },
        enterOmpMode = { enterOmpMode() },
        enterCoworkMode = { enterCoworkMode() },
        onTabsChanged = {
            discardClosedTabQueuedPrompts()
            if (!BuildConfig.IS_LITE) maintenanceDelegate.warmOpenTabsNow()
        },
        singleSession = BuildConfig.IS_LITE,
    )
    private val agyDelegate = AgyDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        thoughtDetails = _thoughtDetails,
        openSocket = { sid -> streamManager.open("agy", sid, _uiState.value.settings) },
        closeSocket = { streamManager.close() },
        loadWorkerDirs = { root -> loadWorkerDirs(root) },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        refreshSession = { refreshSession() },
        applyConversation = { c -> applyConversation(c) },
        refreshAll = { refreshAll() },
        syncActiveTab = { backend, provider, sid, title -> tabsDelegate.syncActiveTab(backend, provider, sid, title) },
        displayName = if (BuildConfig.IS_LITE) "Antigravity" else "Antigravity CLI",
    )
    private val providerLifecycleDelegate: ProviderLifecycleDelegate = ProviderLifecycleDelegate(
        client = client,
        scope = viewModelScope,
        prefill = conversationPrefiller,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        reportError = { prefix, error -> reportError(prefix, error) },
        clearThoughts = { _thoughtDetails.value = emptyMap() },
        openSocket = { backend, sid -> streamManager.open(backend, sid, _uiState.value.settings) },
        closeSocket = { streamManager.close() },
        loadWorkerDirs = { root -> loadWorkerDirs(root) },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        refreshSession = { refreshSession() },
        applyConversation = { conversation -> applyConversation(conversation) },
        refreshAll = { refreshAll() },
        refreshRunPodStatus = { showErrors -> maintenanceDelegate.refreshRunPodStatus(showErrors) },
        syncRunPodStatus = { maintenanceDelegate.syncRunPodStatusSilently() },
        syncActiveTab = { backend, provider, sid, title -> tabsDelegate.syncActiveTab(backend, provider, sid, title) },
    )
    private val claudeAppDelegate: ClaudeAppDelegate = ClaudeAppDelegate(
        client = client,
        scope = viewModelScope,
        prefill = conversationPrefiller,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        thoughtDetails = _thoughtDetails,
        openSocket = { sid -> streamManager.open("claude-app", sid, _uiState.value.settings) },
        closeSocket = { streamManager.close() },
        openCoworkSocket = { provider, sid -> openCoworkSocket(provider, sid) },
        cancelCoworkSiblingPolling = { providerLifecycleDelegate.cancelCoworkSiblingPolling() },
        loadWorkerDirs = { root -> loadWorkerDirs(root) },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        refreshSession = { refreshSession() },
        applyConversation = { c -> applyConversation(c) },
        refreshAll = { refreshAll() },
        syncActiveTab = { backend, provider, sid, title -> tabsDelegate.syncActiveTab(backend, provider, sid, title) },
        onSessionRenamed = { id, title -> tabsDelegate.renameSessionTitle("claude-app", "", id, title) },
    )
    private val coworkDelegate = CoworkDelegate(
        client = client,
        scope = viewModelScope,
        prefill = conversationPrefiller,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        thoughtDetails = _thoughtDetails,
        openSocket = { provider, sid -> openCoworkSocket(provider, sid) },
        startCodexAppPolling = { providerLifecycleDelegate.startCodexPolling() },
        startOpencodeAppPolling = { providerLifecycleDelegate.startOpencodePolling() },
        startOpencode2Polling = { providerLifecycleDelegate.startOpencodePolling(Backend.OPENCODE2_APP.id) },
        startOmpPolling = { providerLifecycleDelegate.startOmpPolling() },
        startClaudeAppPolling = { claudeAppDelegate.startClaudeAppPolling() },
        loadCodexAppInfo = { loadCodexAppInfo() },
        loadOpencodeAppInfo = { loadOpencodeAppInfo() },
        loadOpencode2Info = { opencode2ActionsDelegate.loadInfo() },
        loadOmpInfo = { loadOmpInfo() },
        loadClaudeAppInfo = { loadClaudeAppInfo() },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        updateInput = { value -> updateInput(value) },
        sendPrompt = { sendPrompt() },
        downloadRepo = downloadRepo,
        syncActiveTab = { backend, provider, sid, title -> tabsDelegate.syncActiveTab(backend, provider, sid, title) },
        onActiveCoworkDeleted = { handleDeletedCoworkSession() },
        removeSessionTabs = { ids -> forgetDeletedSessions(ids) },
        onProjectDeletedCompletely = { result ->
            tabsDelegate.removeProjectTabs(result.path, result.deletedSessionIds)
        },
        loadProjects = { loadProjects() },
    )
    private val conversationDelegate = ConversationDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { message -> _messages.emit(message) },
        reportError = { prefix, error -> reportError(prefix, error) },
        cache = offlineConversationCache,
        startCowork = { prompt -> coworkDelegate.startCoworkWithAutoWorkspace(prompt) },
        handleClaudeCleared = { json -> claudeAppDelegate.handleClaudeAppCleared(json) },
        refreshCoworkOutputs = { coworkDelegate.refreshCoworkOutputs(showErrors = false) },
        releaseCoworkLease = { current -> releaseCoworkLease(current) },
        openClaudeSocket = { sessionId -> openClaudeAppSocket(sessionId) },
        replaceTabSessionId = { backend, provider, oldSessionId, newSessionId ->
            tabsDelegate.replaceSessionId(backend, provider, oldSessionId, newSessionId)
        },
        onConversationApplied = {
            onPromptQueueSessionHydrated()
            // Taze konuşma her uygulanışta belleğe yazılır; sekme geçişi bu
            // kayıttan anında prefill eder.
            conversationMemory.recordFrom(_uiState.value)
        },
    )
    private val hubDataDelegate = HubDataDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { message -> _messages.emit(message) },
        reportError = { prefix, error -> reportError(prefix, error) },
        onProjectDeletedCompletely = { result ->
            tabsDelegate.removeProjectTabs(result.path, result.deletedSessionIds)
            if (_uiState.value.selectedCoworkWorkspace == result.path) {
                _uiState.update { it.copy(cowork = it.cowork.copy(selectedWorkspace = "")) }
            }
            coworkDelegate.loadCoworkWorkspaces()
        },
    )
    private val globalSearchDelegate = GlobalSearchDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        reportError = { prefix, error -> reportError(prefix, error) },
    )
    private val sessionRefreshDelegate = SessionRefreshDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        reportError = { prefix, error -> reportError(prefix, error) },
        clearThoughts = { _thoughtDetails.value = emptyMap() },
        closeSocket = { streamManager.close() },
        openSocket = { backend, provider, sid -> if (provider.isNotBlank()) openCoworkSocket(provider, sid) else streamManager.open(backend, sid, _uiState.value.settings) },
        coworkProjectPath = { coworkDelegate.coworkProjectPath() },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        loadUsage = { loadUsage() },
        loadOperations = { loadOperations() },
        loadProjects = { loadProjects() },
        loadBackendCatalog = { loadBackendCatalog() },
    )
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<String>>(emptyList())
    val searchResults: StateFlow<List<String>> = _searchResults.asStateFlow()
    private val streamManager = SessionStreamManager(
        client = client,
        scope = viewModelScope,
        onSnapshot = { sessionId, snapshot -> applyStreamSnapshot(sessionId, snapshot) },
        onEnd = { handleStreamEnd() },
        onError = { _, error -> viewModelScope.launch { _messages.emit(error) } },
    )
    private val codexAppActionsDelegate = CodexAppActionsDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        reportError = { prefix, error -> reportError(prefix, error) },
        clearThoughts = { _thoughtDetails.value = emptyMap() },
        closeSocket = { streamManager.close() },
        openSocket = { sessionId -> openCodexAppSocket(sessionId) },
        startPolling = { providerLifecycleDelegate.startCodexPolling() },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        loadDiskSessions = { providerLifecycleDelegate.loadCodexDiskSessions() },
        onSessionRenamed = { id, title -> tabsDelegate.renameSessionTitle("codex-app", "", id, title) },
    )
    // OpenCode (v2). v1 delegesi (opencodeAppActionsDelegate) 30.09.2026'da
    // SOKULDU: v1 backend'i koprude yok. Sinif ayni kaldi, backendId tek.
    private val opencode2ActionsDelegate: OpencodeAppActionsDelegate = OpencodeAppActionsDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        reportError = { prefix, error -> reportError(prefix, error) },
        clearThoughts = { _thoughtDetails.value = emptyMap() },
        openSocket = { sessionId -> streamManager.open("opencode2-app", sessionId, _uiState.value.settings) },
        startPolling = { providerLifecycleDelegate.startOpencodePolling() },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        syncActiveTab = { sessionId, cwd, model ->
            tabsDelegate.syncActiveTab("opencode2-app", "", sessionId, generateTabTitle("opencode2-app", "", cwd, model))
        },
        onSessionRenamed = { id, title -> tabsDelegate.renameSessionTitle("opencode2-app", "", id, title) },
        backendId = Backend.OPENCODE2_APP.id,
    )
    // OpenCode delegesi. Oturum uzerinde calisan butun aksiyonlar (model, onay,
    // ajan, durdur) bundan gecer. DIKKAT: `val x: Sinif` bildirimi olarak kalmali —
    // DelegateReachabilityTest aliciyi bu bildirimden taniyor, fonksiyon donusunu
    // goremiyor (v1/v2 yan yana dururken bu bir property idi, artik dogrudan).
    private val ocDelegate: OpencodeAppActionsDelegate
        get() = opencode2ActionsDelegate

    private val ompActionsDelegate: OmpActionsDelegate = OmpActionsDelegate(
        client = client,
        scope = viewModelScope,
        state = { _uiState.value },
        update = { mutator -> _uiState.update(mutator) },
        emit = { msg -> _messages.emit(msg) },
        reportError = { prefix, error -> reportError(prefix, error) },
        clearThoughts = { _thoughtDetails.value = emptyMap() },
        openSocket = { sessionId -> streamManager.open("omp", sessionId, _uiState.value.settings) },
        startPolling = { providerLifecycleDelegate.startOmpPolling() },
        refreshConversation = { showErrors -> refreshConversation(showErrors) },
        syncActiveTab = { sessionId, cwd, model ->
            tabsDelegate.syncActiveTab("omp", "", sessionId, generateTabTitle("omp", "", cwd, model))
        },
        onSessionRenamed = { id, title -> tabsDelegate.renameSessionTitle("omp", "", id, title) },
    )

    init {
        refreshAll()
        if (BuildConfig.IS_LITE) loadWorkerDirs("")
        // Yıldız senkronu (bkz. bridge/ui-pins.mjs): önce push abonesi, sonra
        // açılış pull'u. Profil değişiminde tekrar pull edilir
        // (onBridgeProfileActivated).
        SelectorPinBridge.listener = object : SelectorPinListener {
            override fun onPinsChanged(scope: String, keys: Set<String>) {
                viewModelScope.launch(Dispatchers.IO) {
                    // Hata yutulur: çevrimdışıyken yıldız yerelde kalır, sıradaki
                    // pull ile hizalanır. Son yazan kazanır protokolünün uzantısı.
                    runCatching { client.selectorPinsPush(_uiState.value.settings, scope, keys) }
                }
            }
        }
        selectorPinPull()
        tabsDelegate.restoreTabs()
        if (!BuildConfig.IS_LITE) maintenanceDelegate.startOpenTabBackendWarmth()
        // Soğuk açılış: sekmeler kendini toparlar. Aktif sekmenin oturumu yeniden
        // bağlanır (kullanıcı sekmeye dokunana kadar boş sohbet beklemesin) ve
        // activateTab içindeki refreshTabStatuses görünür sekmelerin durum noktası +
        // canlı başlığını hemen doldurur. Boş aktif sekmede yalnız durum tazelenir.
        _uiState.value.activeTabId
            .takeIf { id -> id.isNotBlank() && _uiState.value.visibleTabs.any { it.id == id } }
            ?.let { tabsDelegate.activateTab(it, notifyIfDead = false) }
            ?: viewModelScope.launch { tabsDelegate.refreshTabStatuses() }
        syncNotificationService()
        viewModelScope.launch {
            streamManager.isConnected.collect { connected ->
                _uiState.update { it.copy(wsConnected = connected) }
            }
        }
        // Prompt kuyruğu: tur bitip running false'a düşünce kuyruktaki ilk prompt
        // otomatik gönderilir. Zincirleme: her tur bitişinde bir sonraki gider.
        viewModelScope.launch {
            _uiState.map { it.running }.distinctUntilChanged().collect { running ->
                if (!running) drainPromptQueue()
            }
        }
        // Sekmeler geri geldikten SONRA taslağı koy: hangi sekmeye ait olduğunu
        // bilmeden geri yüklemek, metni yanlış oturuma taşıma riski.
        restoreComposerDrafts()
        // Taslak yazarken her tuşta diske yazmak pil yakar; 600 ms sessizlik yeter.
        // Kaybolan mesajın penceresi bu kadarla sınırlı kalır.
        //
        // `composerDrafts` de izleniyor: arka plandaki bir sekme kapanınca
        // (taslağı elenir) etkin sekmenin hiçbir alanı değişmiyor, yani üçlüyle
        // izlemek o yazıyı bir sonraki tuşa kadar erteler ve silinmiş sekmenin
        // taslağı diskte kalırdı.
        viewModelScope.launch {
            _uiState.map { listOf(it.activeTabId, it.input, it.attachments, it.composerDrafts) }
                .distinctUntilChanged()
                .debounce(600)
                .collect { persistComposerDrafts() }
        }
    }

    fun updateBridgeProfileName(value: String) =
        _uiState.update { state -> state.updateActiveBridgeProfile { it.copy(name = value) } }

    fun updateUrl(value: String) =
        _uiState.update { state -> state.updateActiveBridgeProfile { it.copy(baseUrl = value) } }

    fun updateToken(value: String) =
        _uiState.update { state -> state.updateActiveBridgeProfile { it.copy(token = value) } }

    fun selectBridgeProfile(profileId: String) {
        val profile = _uiState.value.bridgeProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id == _uiState.value.activeBridgeProfileId) return
        _uiState.update {
            it.copy(
                settings = profile.settings(),
                activeBridgeProfileId = profile.id,
                healthOk = false,
                bridgeConnected = false,
            )
        }
        settingsViewModel.saveBridgeProfiles(_uiState.value.bridgeProfiles, profile.id)
        onBridgeProfileActivated()
    }

    fun addBridgeProfile() {
        val state = _uiState.value
        val index = generateSequence(1) { it + 1 }
            .first { candidate -> state.bridgeProfiles.none { it.name == "Köprü $candidate" } }
        val profile = BridgeProfile(
            id = UUID.randomUUID().toString(),
            name = "Köprü $index",
            baseUrl = DEFAULT_URL,
            token = "",
        )
        _uiState.update {
            it.copy(
                settings = profile.settings(),
                bridgeProfiles = it.bridgeProfiles + profile,
                activeBridgeProfileId = profile.id,
                healthOk = false,
                bridgeConnected = false,
            )
        }
        settingsViewModel.saveBridgeProfiles(_uiState.value.bridgeProfiles, profile.id)
        onBridgeProfileActivated()
    }

    fun deleteBridgeProfile(profileId: String) {
        val state = _uiState.value
        if (state.bridgeProfiles.size <= 1 || state.bridgeProfiles.none { it.id == profileId }) return
        val remaining = state.bridgeProfiles.filterNot { it.id == profileId }
        val activeProfile = if (state.activeBridgeProfileId == profileId) {
            remaining.first()
        } else {
            remaining.first { it.id == state.activeBridgeProfileId }
        }
        _uiState.update {
            it.copy(
                settings = activeProfile.settings(),
                bridgeProfiles = remaining,
                activeBridgeProfileId = activeProfile.id,
                healthOk = false,
                bridgeConnected = false,
            )
        }
        settingsViewModel.saveBridgeProfiles(remaining, activeProfile.id)
        onBridgeProfileActivated()
    }

    private fun RemoteUiState.updateActiveBridgeProfile(
        transform: (BridgeProfile) -> BridgeProfile,
    ): RemoteUiState {
        var activeProfile: BridgeProfile? = null
        val profiles = bridgeProfiles.map { profile ->
            if (profile.id == activeBridgeProfileId) {
                transform(profile).also { activeProfile = it }
            } else {
                profile
            }
        }
        val updated = activeProfile ?: return this
        return copy(settings = updated.settings(), bridgeProfiles = profiles)
    }

    private fun onBridgeProfileActivated() {
        streamManager.close()
        // Eski köprüden çekilmiş ekran verisi önce boşaltılır; gerekçe
        // BridgeSwitchReset.kt'de.
        _uiState.update { it.clearedForBridgeSwitch() }
        // Köprü değişti: yıldız tablosu da yeni köprününki. Eski önbellek
        // replaceAll ile tamamen ezilir (bkz. selectorPinPull).
        selectorPinPull()
        // Önce eski köprünün aktif backend/polling durumundan çıkılır. Ardından
        // tam sekme listesi korunarak yeni köprüye ait görünür bir sekme seçilir;
        // böylece eski sessionId yeni köprüde hiçbir zaman yeniden bağlanmaz.
        goToLanding()
        val activeTabId = tabsDelegate.reconcileActiveTabForBridge()
        if (activeTabId != null) {
            tabsDelegate.activateTab(activeTabId, notifyIfDead = false)
            // refreshAll açık bir sekme varken merkez verisini çekmiyor; köprü
            // değişiminde boşaltılan merkez yeni köprüden burada dolar.
            loadUsage(); loadOperations(); loadProjects(); loadBackendCatalog()
        } else {
            viewModelScope.launch { tabsDelegate.refreshTabStatuses() }
            refreshAll()
        }
        syncNotificationService()
    }

    fun startDevicePairing() = viewModelScope.launch {
        _uiState.update { it.copy(device = it.device.copy(authLoading = true)) }
        runCatching { client.startDevicePairing(_uiState.value.settings) }
            .onSuccess { result -> _uiState.update { it.copy(device = it.device.copy(pairingCode = result.code, pairingExpiresAt = result.expiresAt, authLoading = false)) } }
            .onFailure { _uiState.update { it.copy(device = it.device.copy(authLoading = false)) }; reportError("Eşleştirme kodu oluşturulamadı", it) }
    }

    fun completeDevicePairing(code: String) = viewModelScope.launch {
        _uiState.update { it.copy(device = it.device.copy(authLoading = true)) }
        runCatching { client.completeDevicePairing(_uiState.value.settings, code, "Android ${Build.MODEL}") }
            .onSuccess { result ->
                prefs.edit().putString("device_id", result.deviceId).putBoolean("device_paired", true).apply()
                _uiState.update { state ->
                    state.updateActiveBridgeProfile { it.copy(token = result.key) }
                        .copy(device = state.device.copy(paired = true, authLoading = false, pairingCode = ""))
                }
                settingsViewModel.saveSettings(_uiState.value)
                // Kimlik değişti: kimliksiz açılışta (Lite ilk kurulum) boş kalan
                // ekran verisi yeni anahtarla dolsun.
                refreshAll()
            }
            .onFailure { _uiState.update { it.copy(device = it.device.copy(authLoading = false)) }; reportError("Cihaz eşleştirilemedi", it) }
    }

    fun loadDevices() = viewModelScope.launch {
        _uiState.update { it.copy(device = it.device.copy(devicesLoading = true)) }
        runCatching { client.listDevices(_uiState.value.settings) }
            .onSuccess { list -> _uiState.update { it.copy(device = it.device.copy(devices = list, devicesLoading = false)) } }
            .onFailure { _uiState.update { it.copy(device = it.device.copy(devicesLoading = false)) }; reportError("Cihaz listesi alınamadı", it) }
    }

    // İptal edilen cihazın anahtarı köprüde anında geçersiz olur; cihaz listede
    // `revokedAt` dolu olarak kalır, liste köprüden yeniden çekilir.
    fun revokeDevice(deviceId: String) = viewModelScope.launch {
        _uiState.update { it.copy(device = it.device.copy(devicesLoading = true)) }
        runCatching { client.revokeDevice(_uiState.value.settings, deviceId) }
            .onSuccess { loadDevices() }
            .onFailure { _uiState.update { it.copy(device = it.device.copy(devicesLoading = false)) }; reportError("Cihaz iptal edilemedi", it) }
    }

    /** Bu telefonun köprüdeki cihaz kimliği (eşleştirmede kaydedilir); yoksa boş. */
    fun currentDeviceId(): String = prefs.getString("device_id", null).orEmpty()

    fun rotateDeviceKey() = viewModelScope.launch {
        _uiState.update { it.copy(device = it.device.copy(authLoading = true)) }
        runCatching { client.rotateDeviceKey(_uiState.value.settings) }
            .onSuccess { result ->
                prefs.edit().putString("device_id", result.deviceId).apply()
                _uiState.update { state ->
                    state.updateActiveBridgeProfile { it.copy(token = result.key) }
                        .copy(device = state.device.copy(authLoading = false))
                }
                settingsViewModel.saveSettings(_uiState.value)
            }
            .onFailure { _uiState.update { it.copy(device = it.device.copy(authLoading = false)) }; reportError("Cihaz anahtarı yenilenemedi", it) }
    }
    fun updateInput(value: String) = _uiState.update { it.copy(input = value) }
    // Back navigation: leave the current agent (if any) and show the landing/agent-picker.
    // Used by the system back button so users return to the agent picker instead of exiting.
    fun goToLanding() {
        when (_uiState.value.backend) {
            "agy" -> exitAgyMode()
            "claude-app", "cowork" -> exitClaudeAppMode()
            "codex-app" -> exitCodexAppMode()
            "opencode2-app" -> exitOpencode2AppMode()
            "omp" -> exitOmpMode()
            // null (zaten landing'de) — no-op.
        }
    }

    private fun handleDeletedCoworkSession() {
        tabsDelegate.resetActiveTab()
        claudeAppDelegate.exitClaudeAppMode(clearLastCowork = true)
    }

    // Bir backend'in içinden doğrudan başka bir backend'e geçiş (drawer başlığındaki
    // dropdown). Önce mevcut moddan çıkılır (oturum köprüde canlı kalır, son-oturum
    // hafızası yazılır), sonra hedefe girilir. exit*/enter* fonksiyonları Main.immediate
    // dispatcher'da sıralı çalıştığı için araya girme olmaz.
    fun switchBackend(target: String) {
        if (!_uiState.value.supportsEditionBackend(target)) return
        if (target == _uiState.value.backend) return
        goToLanding()
        when (target) {
            "agy" -> enterAgyMode()
            "claude-app" -> enterClaudeAppMode()
            "codex-app" -> enterCodexAppMode()
            "opencode2-app" -> enterOpencode2AppMode()
            "omp" -> enterOmpMode()
            "cowork" -> enterCoworkMode()
        }
    }
    // Sekme etkinleştirme. ui2 bunu hiç dışarıdan çağırmıyordu (sekme çubuğu
    // ChatRootScreen'in içindeydi ve delegeye kendi erişiyordu); ui3'te oturum
    // seçici başlığın altındaki sheet'e taşındığı için dışa açık bir yönlendirme
    // gerekiyor. Yeni davranış YOK — tek satırlık delege yönlendirmesi.
    fun activateTab(tabId: String) = tabsDelegate.activateTab(tabId)

    // --- MCP server management (backend'e duyarlı) ---
    // Her backend KENDİ CLI'ının MCP config'ini yönetir; alakasız config
    // gösterilmez. Hedefi mcpTargetFor belirler; null dönerse ChatScreen
    // menüdeki "MCP Sunucuları" butonunu hiç çizmez.
    fun loadMcpServers() = mcpDelegate.loadMcpServers()
    fun saveMcpServer(name: String, type: String, url: String, command: String) =
        mcpDelegate.saveMcpServer(name, type, url, command)
    fun refreshMcp() = mcpDelegate.refreshMcp()
    fun removeMcpServer(name: String) = mcpDelegate.removeMcpServer(name)
    fun toggleMcpServer(name: String, enabled: Boolean) = mcpDelegate.toggleMcpServer(name, enabled)

    // ui2 Ayarlar > MCP: aktif sohbet backend'inden bağımsız, kullanıcının
    // açıkça seçtiği global CLI config hedefini yönetir.
    fun loadSettingsMcpServers(target: String) = mcpDelegate.loadMcpServers(target)
    fun saveSettingsMcpServer(target: String, name: String, type: String, url: String, command: String) =
        mcpDelegate.saveMcpServer(name, type, url, command, target)
    fun refreshSettingsMcp(target: String) = mcpDelegate.refreshMcp(target)
    fun removeSettingsMcpServer(target: String, name: String) = mcpDelegate.removeMcpServer(name, target)
    fun toggleSettingsMcpServer(target: String, name: String, enabled: Boolean) =
        mcpDelegate.toggleMcpServer(name, enabled, target)

    fun insertSlashCommand(name: String) = _uiState.update { it.copy(input = applySlashCommandToInput(it.input, name)) }
    fun insertPromptTemplate(text: String) = _uiState.update { it.copy(input = text) }

    fun loadSlashCommands() = viewModelScope.launch {
        // Landing'de (backend null) slash listesi yok; eski "antigravity" default'u
        // ölü anahtardı (SLASH'ta yok) ve boş liste dönüyordu — artık hiç sorulmaz.
        val backend = _uiState.value.backend
        if (backend == null) {
            _uiState.update { it.copy(slashCommands = emptyList()) }
            return@launch
        }
        runCatching { client.slash(_uiState.value.settings, backend) }
            .onSuccess { r ->
                // /goal app tarafında yakalanır (köprü slash listesinde yok); codex-app'te
                // öneri listesine sentetik olarak eklenir ki "/" yazınca görünsün. Ucuz:
                // liste zaten içeriyorsa (ileride köprü eklerse) tekrar koymayız.
                val commands = if (backend == Backend.CODEX_APP.id && r.commands.none { it.name == "goal" }) {
                    listOf(SlashCommand("goal", "Oturum hedefi: kur ve başlat / göster / temizle", "<hedef | temizle>")) + r.commands
                } else r.commands
                _uiState.update { it.copy(slashCommands = commands) }
            }
    }

    fun updateSearchQuery(q: String) {
        _searchQuery.value = q
        val all = _uiState.value.sessions
        _searchResults.value = if (q.isBlank()) emptyList()
            else all.filter { it.title.contains(q, ignoreCase = true) }.map { it.title }
    }

    fun saveSettings() {
        settingsViewModel.saveSettings(_uiState.value)
        refreshAll()
        syncNotificationService()
    }

    /**
     * Köprüdeki yıldız tablosunu yerel önbelleğe çeker.
     *
     * - Köprüde dosya varsa: köprü KAYNAK, yerel önbellek tamamen ezilir
     *   (replaceAll). İki cihaz farklı setle açılsa bile çakışma doğmaz.
     * - Köprüde dosya hiç yoksa (existed=false): yerel yıldızlar bir kez
     *   taşınır (migration) — eski sürüm APK'ların cihaz-yerel verisi kaybolmaz.
     *
     * Sessiz başarısızlık normal: çevrimdışıysa pull atlanır; sonraki açılışta
     * ya da profil geçişinde tekrar denenir. Push yolu zaten toggle'da canlı.
     */
    private fun selectorPinPull() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = try {
                client.selectorPinsFetch(_uiState.value.settings)
            } catch (e: Exception) {
                return@launch
            }
            try {
                val store = SelectorPinStore(getApplication<Application>().applicationContext)
                if (!snapshot.existed) {
                    store.allScopes().forEach { (scope, keys) ->
                        if (keys.isEmpty()) return@forEach
                        runCatching { client.selectorPinsPush(_uiState.value.settings, scope, keys) }
                    }
                } else {
                    store.replaceAll(snapshot.pins)
                }
            } catch (e: Exception) {
                // Önbellek yazımı başarısız olsa da uygulama çalışmaya devam eder;
                // sıradaki pull yeniden dener.
            }
        }
    }


    fun refreshBatteryOptimizationStatus() {
        val context = getApplication<Application>().applicationContext
        val ignored = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        _uiState.update { it.copy(device = it.device.copy(batteryOptimizationIgnored = ignored)) }
    }

    fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val context = getApplication<Application>().applicationContext
        val packageUri = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }
            .onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }

    fun openBatteryOptimizationSettings() {
        val context = getApplication<Application>().applicationContext
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Telefonda kurulu BAŞKA bir uygulamayı açar (Merkez'deki "Belgelik" tuşu).
     *
     * Paket AndroidManifest'teki `<queries>` listesinde olmalı; yoksa Android 11+
     * görünürlük kuralı yüzünden uygulama kurulu olsa da intent null döner ve
     * kullanıcı "kurulu değil" mesajı görür.
     */
    fun openPhoneApp(packageName: String, label: String) {
        val context = getApplication<Application>().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            notifyUser("$label telefonda bulunamadı")
            return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { notifyUser("$label açılamadı") }
    }

    fun toggleBackendVisibility(id: String) {
        _uiState.update { state -> settingsViewModel.toggleBackendVisibility(state, id) }
    }

    fun setUsageCardVisible(key: String, visible: Boolean) {
        _uiState.update { state -> settingsViewModel.setUsageCardVisible(state, key, visible) }
    }

    fun showAllUsageCards() {
        _uiState.update { state -> settingsViewModel.showAllUsageCards(state) }
    }

    fun moveBackend(from: Int, to: Int) {
        _uiState.update { state -> settingsViewModel.moveBackend(state, from, to) }
    }

    fun checkForUpdate(showNoUpdateMessage: Boolean = true) = updateDelegate.checkForUpdate(showNoUpdateMessage)
    fun dismissUpdate() = updateDelegate.dismissUpdate()
    fun downloadAndInstallUpdate() = updateDelegate.downloadAndInstallUpdate()

    fun testConnection() = viewModelScope.launch {
        _uiState.update { it.copy(testing = true) }
        runCatching { client.health(_uiState.value.settings) }
            .onSuccess { result ->
                _uiState.update { it.copy(healthOk = result.ok && result.compatible, protocolCompatible = result.compatible) }
                _messages.emit(if (!result.compatible) "Bridge protokolü bu uygulamayla uyumlu değil" else if (result.ok) "Bridge reachable" else "Bridge responded but is not healthy")
            }
            .onFailure { reportError("Connection failed", it) }
        _uiState.update { it.copy(testing = false) }
    }

    fun restartBridgeFromDesktop() = viewModelScope.launch {
        _uiState.update { it.copy(bridgeRestarting = true) }
        runCatching { client.restartBridge(_uiState.value.settings) }
            .onSuccess { _messages.emit("Bridge restart başlatıldı") }
            .onFailure { reportError("Bridge restart başlatılamadı", it) }
        _uiState.update { it.copy(bridgeRestarting = false) }
    }

    fun refreshSession(): Job = sessionRefreshDelegate.refreshSession()
    fun refreshAll(): Job = sessionRefreshDelegate.refreshAll()

    fun loadUsage(force: Boolean = false) = hubDataDelegate.loadUsage(force)
    fun loadBackendCatalog() = hubDataDelegate.loadBackendCatalog()
    fun loadOperations() = hubDataDelegate.loadOperations()

    fun markOperationsSeen() {
        val newest = _uiState.value.operations.events.firstOrNull()?.id.orEmpty()
        prefs.edit().putString("last_seen_operation_event", newest).apply()
        _uiState.update { it.copy(lastSeenOperationEventId = newest) }
    }

    fun openOperation(operation: OperationItem) {
        val existing = _uiState.value.visibleTabs.firstOrNull {
            val backend = if (it.backend == "cowork") it.provider else it.backend
            backend == operation.backend && it.sessionId == operation.sessionId
        }
        if (existing != null) {
            tabsDelegate.activateTab(existing.id)
            return
        }
        // switchBackend aynı backend zaten açıksa erken dönüyordu; proje detayından
        // başka bir Codex/Claude oturumuna dokununca hedef hiç adopt edilmeden boş/eski
        // sohbet görünüyordu. Oturum kimliği elimizdeyken doğrudan resume yolu otoritedir.
        //
        // Adopt için KALICI kimlik tercih edilir: köprü kabuk kimliği (sessionId)
        // kabukla birlikte ölüyor, olay geçmişindeki eski kayıtlar hiçbir şeye
        // çözülmediği için dokununca hiçbir şey olmuyordu (02.08.2026).
        val resumeId = operation.diskId.ifBlank { operation.sessionId }
        when (operation.backend) {
            "claude-app" -> resumeClaudeAppDiskSession(ClaudeDiskSession(
                id = resumeId, cwd = operation.cwd, title = operation.summary,
                lastText = operation.summary, turns = 0, mtime = 0L,
            ))
            "codex-app" -> resumeCodexAppDiskSession(CodexDiskSession(
                id = resumeId, cwd = operation.cwd, title = operation.summary,
                lastText = operation.summary, turns = 0, mtime = 0L,
            ))
            "opencode2-app" -> resumeOpencode2AppDiskSession(AppDiskSession(
                id = resumeId, cwd = operation.cwd, title = operation.summary,
                lastText = operation.summary, turns = 0, mtime = 0L,
            ))
            "omp" -> resumeOmpDiskSession(AppDiskSession(
                id = resumeId, cwd = operation.cwd, title = operation.summary,
                lastText = operation.summary, turns = 0, mtime = 0L,
            ))
            "agy" -> resumeAgyDiskSession(AgyDiskSession(
                id = resumeId, cwd = operation.cwd, title = operation.summary,
                lastText = operation.summary, turns = 0, mtime = 0L,
            ))
        }
    }

    /** Olay geçmişi kartından ilgili oturumu açar (openOperation ile aynı yol). */
    fun openOperationEvent(event: OperationEvent) {
        if (event.sessionId.isBlank()) return
        openOperation(OperationItem(
            id = "event:${event.id}",
            backend = event.backend,
            backendLabel = event.backendLabel,
            sessionId = event.sessionId,
            cwd = event.cwd,
            model = event.model,
            summary = event.summary,
            status = event.status,
            needsAttention = false,
            updatedAt = event.at,
            diskId = event.diskId,
        ))
    }

    /** Bildirimden gelen onayı doğrudan ilgili sohbet oturumunda açar. */
    fun openApprovalSession(backend: String, sessionId: String) {
        if (backend.isBlank() || sessionId.isBlank()) return
        _approvalNavigationNonce.update { it + 1 }
        openOperation(OperationItem(
            id = "approval:$backend:$sessionId",
            backend = backend,
            backendLabel = BACKEND_LABELS[backend] ?: backend,
            sessionId = sessionId,
            cwd = "",
            model = "",
            summary = "Bildirimden açılan onay",
            status = "waiting",
            needsAttention = true,
            updatedAt = "",
        ))
    }

    fun loadProjects(): Job = hubDataDelegate.loadProjects()
    fun loadProjectDetail(id: String) = hubDataDelegate.loadProjectDetail(id)

    fun clearProjectDetail() = _uiState.update { it.copy(selectedProjectDetail = null, projectDetailLoading = false) }

    fun setProjectLabel(id: String, label: String, path: String? = null) = hubDataDelegate.setProjectLabel(id, label, path)
    fun applyProjectSecurity(id: String, profile: String, permissions: Map<String, String> = emptyMap(), readRoot: String = "", writeRoot: String = "") = hubDataDelegate.applyProjectSecurity(id, profile, permissions, readRoot, writeRoot)
    fun loadProjectMcpServers(id: String, provider: String) = hubDataDelegate.loadProjectMcpServers(id, provider)
    fun applyProjectMcpProfile(id: String, provider: String, enabledNames: Set<String>) = hubDataDelegate.applyProjectMcpProfile(id, provider, enabledNames)
    fun deleteProject(id: String) = hubDataDelegate.deleteProject(id)
    fun deleteProjectCompletely(id: String) {
        _uiState.update { it.copy(projectDelete = ProjectDeleteUiState(running = true, targetId = id)) }
        hubDataDelegate.deleteProjectCompletely(id)
    }

    fun dismissProjectDeleteResult() {
        _uiState.update { it.copy(projectDelete = ProjectDeleteUiState()) }
    }

    fun retryDeleteProjectCompletely() {
        val id = _uiState.value.projectDelete.targetId
        if (id.isNotBlank()) deleteProjectCompletely(id)
    }

    fun bulkSessionAction(
        projectId: String,
        action: String,
        sessions: List<ProjectSession>,
        onComplete: (BulkSessionActionResponse) -> Unit
    ) = viewModelScope.launch {
        val detail = _uiState.value.selectedProjectDetail ?: return@launch
        val projectPath = detail.project?.path ?: return@launch

        runCatching { client.bulkSessionAction(_uiState.value.settings, projectId, action, sessions) }
            .onSuccess { response ->
                if (action == "delete" && response.succeeded > 0) {
                    val deletedIds = response.results.filter { it.ok }.map { it.sessionId }.toSet()
                    tabsDelegate.removeProjectTabs(projectPath, deletedIds)
                    conversationMemory.remove(deletedIds)
                    viewModelScope.launch { runCatching { offlineConversationCache.delete(deletedIds) } }
                }
                loadProjectDetail(projectId)
                loadProjects()
                onComplete(response)
            }
            .onFailure {
                reportError("Toplu işlem başarısız", it)
            }
    }

    fun applyProjectPreferences(
        id: String,
        pinned: Boolean? = null,
        touch: Boolean? = null,
        quickStartProvider: String? = null,
        quickStartModel: String? = null,
        quickStartPermissionMode: String? = null,
        quickStartEffort: String? = null,
        path: String? = null,
    ) = hubDataDelegate.applyProjectPreferences(
        id, pinned, touch,
        quickStartProvider, quickStartModel,
        quickStartPermissionMode, quickStartEffort, path
    )

    fun updateProjectsFilter(filter: ProjectFilter) = hubDataDelegate.updateProjectsFilter(filter)
    fun updateProjectsSearchQuery(query: String) = hubDataDelegate.updateProjectsSearchQuery(query)

    fun openProjectSession(session: ProjectSession, projectPath: String) {
        if (session.container == "cowork") {
            resumeCoworkSession(CoworkSessionRecord(
                provider = session.backend,
                sessionId = session.sessionId,
                cwd = projectPath,
                model = session.model,
                title = session.title,
            ))
        } else {
            openOperation(OperationItem(
                id = "${session.backend}:${session.sessionId}", backend = session.backend, backendLabel = session.backendLabel,
                sessionId = session.sessionId, cwd = projectPath, model = session.model, summary = session.summary,
                status = session.status, needsAttention = session.status == "waiting", updatedAt = "",
            ))
        }
    }

    fun startProjectChat(
        request: ProjectChatRequest,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {},
    ) = viewModelScope.launch {
        if (_uiState.value.running) {
            onFailure()
            return@launch
        }

        // Yeni oturumun gerçekten oluşturulduğunu doğrulamak için değeri başlatmadan
        // önce al. Önceden açık bir oturum başarısız isteği başarı gibi göstermemeli.
        val previousSessionId = when (request.provider) {
            "claude-app" -> _uiState.value.claude.sessionId
            "codex-app" -> _uiState.value.codex.sessionId
            "opencode2-app" -> _uiState.value.opencode.sessionId
            "omp" -> _uiState.value.omp.sessionId
            "agy" -> _uiState.value.agy.sessionId
            else -> ""
        }

        // Set model/permission/effort in UI state so that delegate picks them up
        _uiState.update { st ->
            st.copy(
                claude = st.claude.copy(
                    model = if (request.provider == "claude-app") request.model.ifBlank { st.claude.model } else st.claude.model,
                    permissionMode = if (request.provider == "claude-app") request.permissionMode.ifBlank { st.claude.permissionMode } else st.claude.permissionMode,
                    effort = if (request.provider == "claude-app") request.effort.ifBlank { st.claude.effort } else st.claude.effort
                ),
                codex = st.codex.copy(
                    model = if (request.provider == "codex-app") request.model.ifBlank { st.codex.model } else st.codex.model,
                    effort = if (request.provider == "codex-app") request.effort.ifBlank { st.codex.effort } else st.codex.effort
                ),
                opencode = st.opencode.copy(
                    model = if (request.provider == "opencode2-app") request.model.ifBlank { st.opencode.model } else st.opencode.model
                ),
                omp = st.omp.copy(
                    model = if (request.provider == "omp") request.model.ifBlank { st.omp.model } else st.omp.model,
                    permissionMode = if (request.provider == "omp") request.permissionMode.ifBlank { st.omp.permissionMode } else st.omp.permissionMode,
                    variant = if (request.provider == "omp") request.effort.ifBlank { st.omp.variant } else st.omp.variant,
                ),

                cowork = st.cowork.copy(
                    provider = if (request.workspacePath != null) request.provider else st.cowork.provider,
                    yoloMode = if (request.workspacePath != null) (request.permissionMode == "yolo") else st.cowork.yoloMode
                )
            )
        }

        // 2. Start session based on provider and whether it is cowork (workspacePath != null)
        val wsPath = request.workspacePath
        val job = if (wsPath != null) {
            val prov = normalizeCoworkProvider(request.provider)
            val model = request.model.ifBlank {
                _uiState.value.backendSession(prov).model.ifBlank {
                    _uiState.value.backendSession(prov).defaultModel
                }
            }
            val nativeMode = if (request.permissionMode == "yolo") "yolo" else null

            viewModelScope.launch {
                try {
                    val result = client.coworkStartSession(_uiState.value.settings, wsPath, prov, model, null, nativeMode, forceNew = true)
                    coworkDelegate.applyCoworkSessionResult(result, wsPath, prov, model)

                    applyProjectPreferences(
                        id = request.projectId,
                        quickStartProvider = request.provider,
                        quickStartModel = request.model,
                        quickStartPermissionMode = request.permissionMode,
                        quickStartEffort = request.effort,
                        path = request.projectPath,
                        touch = true,
                    )
                    onSuccess()
                } catch (e: Throwable) {
                    reportError("Cowork oturumu başlatılamadı", e)
                    onFailure()
                }
            }
        } else {
            when (request.provider) {
                "claude-app" -> {
                    startClaudeAppSession(request.projectPath, request.model, request.permissionMode)
                }
                "codex-app" -> {
                    startCodexAppSession(request.projectPath, request.model)
                }
                "opencode2-app" -> {
                    startOpencodeAppSession(request.projectPath, request.model)
                }
                "omp" -> {
                    startOmpSession(request.projectPath, request.model)
                }
                "agy" -> {
                    startAgySession(request.projectPath, request.model)
                }
                else -> null
            }
        }

        if (wsPath == null && job != null) {
            // Wait for job completion and then save preferences and trigger callback
            job.join()
            // Check if start succeeded — a NEW session must have been created.
            val newSessionId = when (request.provider) {
                "claude-app" -> _uiState.value.claude.sessionId
                "codex-app" -> _uiState.value.codex.sessionId
                "opencode2-app" -> _uiState.value.opencode.sessionId
                "omp" -> _uiState.value.omp.sessionId
                    "agy" -> _uiState.value.agy.sessionId
                else -> ""
            }
            val success = newSessionId.isNotEmpty() && newSessionId != previousSessionId
            if (success) {
                applyProjectPreferences(
                    id = request.projectId,
                    quickStartProvider = request.provider,
                    quickStartModel = request.model,
                    quickStartPermissionMode = request.permissionMode,
                    quickStartEffort = request.effort,
                    path = request.projectPath,
                    touch = true,
                )
                onSuccess()
            } else {
                onFailure()
            }
        }
    }

    fun refreshActiveState() = hubDataDelegate.refreshActiveState()
    fun loadWorkerDirs(root: String) = hubDataDelegate.loadWorkerDirs(root, liteOnly = BuildConfig.IS_LITE)
    fun searchWorkerDirs(root: String, query: String) = hubDataDelegate.searchWorkerDirs(root, query, liteOnly = BuildConfig.IS_LITE)

    private var folderSearchJob: Job? = null
    fun updateFolderSearchQuery(root: String, query: String) {
        folderSearchJob?.cancel()
        if (query.length < 2) {
            _uiState.update { it.copy(folderSearchResults = emptyList()) }
            return
        }
        folderSearchJob = viewModelScope.launch {
            delay(300)
            searchWorkerDirs(root, query)
        }
    }

    fun folderPickerRecent(): List<String> = _folderPickerPrefs.recent()
    fun folderPickerFavorites(): List<String> = _folderPickerPrefs.favorites()
    fun folderPickerIsFavorite(path: String): Boolean = _folderPickerPrefs.isFavorite(path)
    fun folderPickerToggleFavorite(path: String) { _folderPickerPrefs.toggleFavorite(path) }
    fun folderPickerAddRecent(path: String) { _folderPickerPrefs.addRecent(path) }

    private val _folderPickerPrefs by lazy {
        FolderPickerPreferences(getApplication<Application>().applicationContext)
    }

    // --- File browser & downloads ---
    fun setBrowserColumns(columns: Int) {
        val safe = columns.coerceIn(1, 3)
        prefs.edit().putInt("browserColumns", safe).apply()
        _uiState.update { it.copy(files = it.files.copy(browserColumns = safe)) }
    }

    fun loadDownloads() {
        _uiState.update { it.copy(files = it.files.copy(downloadRecords = downloadRepo.history())) }
    }

    fun loadBrowserDir(root: String, coworkOnly: Boolean = false): Job {
        // Cowork gezgininde gezilen her klasör alan bazında hatırlanır (kalıcı,
        // prefs): sohbetteki klasör tuşu alanın köküne değil buraya döner.
        if (coworkOnly) {
            val alan = coworkWorkspaceFor(_uiState.value.cowork.rootPath, root)
            if (alan.isNotBlank()) {
                prefs.edit().putString(coworkSonKlasorKey(alan), root).apply()
            }
        } else if (activeSessionBrowserRoot.isNotBlank() && isUnderDir(root, activeSessionBrowserRoot)) {
            prefs.edit().putString(sessionSonKlasorKey(activeSessionBrowserRoot), root).apply()
        }
        return fileBrowserDelegate.loadBrowserDir(root, coworkOnly)
    }
    fun loadDriveRoots() = fileBrowserDelegate.loadDriveRoots()

    fun uploadToBrowserDir(uri: Uri, coworkOnly: Boolean = false) = viewModelScope.launch {
        // Uri çözme (ContentResolver) Android'li iş — ViewModel'de kalır; delege
        // yalnızca name+bytes alır (madde 11.3: Context/Uri arayüz arkasında).
        val context = getApplication<Application>().applicationContext
        val (bytes, name) = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            var fileName = "upload.bin"
            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) fileName = cursor.getString(idx)
                }
            }
            val b = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            b to fileName
        }
        fileBrowserDelegate.uploadFileToBrowserDir(name, bytes, coworkOnly)
    }

    fun uploadToCoworkWorkspace(uri: Uri) = viewModelScope.launch {
        val context = getApplication<Application>().applicationContext
        val (bytes, name) = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            var fileName = "upload.bin"
            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) fileName = cursor.getString(idx)
                }
            }
            val b = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            b to fileName
        }
        coworkDelegate.uploadToCoworkWorkspace(name, bytes)
    }

    fun loadCoworkImportDir(root: String = "") = coworkDelegate.loadCoworkImportDir(root)
    fun importPcIntoCoworkWorkspace(sources: List<String>) = coworkDelegate.importPcIntoCoworkWorkspace(sources)
    fun convertCoworkOutputToUdf(outputName: String) = coworkDelegate.convertCoworkOutputToUdf(outputName)
    fun downloadCoworkWorkspaceZip() = coworkDelegate.downloadCoworkWorkspaceZip()
    fun downloadProjectOutputsFolder(projectId: String) = downloadsDelegate.downloadProjectOutputsFolder(projectId)

    fun deleteBrowserEntry(path: String, coworkOnly: Boolean = false) = fileBrowserDelegate.deleteBrowserEntry(path, coworkOnly)
    fun renameBrowserEntry(path: String, newName: String, coworkOnly: Boolean = false) = fileBrowserDelegate.renameBrowserEntry(path, newName, coworkOnly)
    fun moveBrowserEntry(path: String, destDir: String, coworkOnly: Boolean = false) = fileBrowserDelegate.moveBrowserEntry(path, destDir, coworkOnly)
    fun openBrowserFile(path: String) = fileBrowserDelegate.openBrowserFile(path)

    // Gezgindeki çoklu seçim
    fun pasteBrowserEntries(paths: List<String>, destDir: String, move: Boolean, coworkOnly: Boolean = false) =
        fileBrowserDelegate.pasteBrowserEntries(paths, destDir, move, coworkOnly)
    fun deleteBrowserEntries(paths: List<String>, coworkOnly: Boolean = false) =
        fileBrowserDelegate.deleteBrowserEntries(paths, coworkOnly)
    fun pastePhoneEntries(paths: List<String>, destDir: String, move: Boolean) =
        fileBrowserDelegate.pastePhoneEntries(paths, destDir, move)
    fun deletePhoneEntries(paths: List<String>) = fileBrowserDelegate.deletePhoneEntries(paths)

    // ---------- telefon gezgini ----------
    // Kökler PhoneStorage'tan (Android'e bağlı); dosya işlerinin kendisi delegede.
    fun phoneRootPath(): String = PhoneStorage.agentBridgeRoot()
    fun phoneExternalRoot(): String = PhoneStorage.externalRoot()
    fun phoneStoragePermitted(): Boolean = PhoneStorage.hasPermission()

    /**
     * Çalışma alanının telefondaki klasörü. TEMBEL: klasör ancak kullanıcı
     * telefon sekmesine bastığında oluşur, yoksa hiç kullanılmayan alanlar için
     * boş klasör birikirdi.
     */
    fun phoneCoworkSpacePath(workspaceName: String): String {
        val path = PhoneFiles.coworkSpacePath(PhoneStorage.agentBridgeRoot(), workspaceName)
        if (path.isNotBlank()) PhoneFiles.ensureDir(path)
        return path
    }

    fun loadPhoneDir(path: String) = fileBrowserDelegate.loadPhoneDir(path)
    fun deletePhoneEntry(path: String) = fileBrowserDelegate.deletePhoneEntry(path)
    fun renamePhoneEntry(path: String, newName: String) = fileBrowserDelegate.renamePhoneEntry(path, newName)
    fun movePhoneEntry(path: String, destDir: String) = fileBrowserDelegate.movePhoneEntry(path, destDir)

    /** Telefondaki dosyayı PC'nin o an açık olan gezgin klasörüne kopyalar. */
    fun copyPhoneEntryToPc(path: String, coworkOnly: Boolean = false) = viewModelScope.launch {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { java.io.File(path).readBytes() }.getOrDefault(ByteArray(0))
        }
        fileBrowserDelegate.copyPhoneEntryToPc(path, bytes, _uiState.value.fileBrowserBase, coworkOnly)
    }

    /** Telefondaki dosyayı dış uygulamada açar (file:// yasak, FileProvider şart). */
    fun phoneFileViewIntent(path: String): Intent? = runCatching {
        val file = java.io.File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            getApplication(), "${getApplication<Application>().packageName}.fileprovider", file,
        )
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()

    // Cowork dosya gezgini: startDir kök (CoworkSpaces) veya bir workspace klasörü.
    // Başlangıç yolu nav arg yerine state'te taşınır (Windows yolu URL-encode derdi yok).
    // sonKlasordenDevam: sohbetteki klasör tuşu true verir — gezgin alanın kökü
    // yerine o alanda en son gezilen alt klasörde açılır (kullanıcı isteği
    // 25.08.2026: belge ile sohbet arasında hızlı gidip gelme). Merkez'deki
    // girişler false bırakır, oradan gelen "Dosyalar" hep alanın kökünü açar.
    // filesOpenDir/filesStart ayrımının gerekçesi CoworkUiState'te.
    fun openCoworkFileManager(startDir: String, sonKlasordenDevam: Boolean = false) {
        val acilis = if (sonKlasordenDevam && startDir.isNotBlank()) {
            coworkResumeDir(startDir, prefs.getString(coworkSonKlasorKey(startDir), null))
        } else {
            startDir
        }
        _uiState.update { it.copy(cowork = it.cowork.copy(filesStart = startDir, filesOpenDir = acilis)) }
        if (acilis.isNotBlank()) loadBrowserDir(acilis, coworkOnly = true)
        else loadCoworkWorkspaces()
    }

    // Sıralama tercihiyle aynı normalizasyon (sortPrefKey): Windows yolları
    // ayraç ve büyük/küçük harf duyarsız, iki taraf da aynı anahtara düşmeli.
    private fun coworkSonKlasorKey(workspace: String) =
        "coworkSonKlasor:" + workspace.replace('\\', '/').trimEnd('/').lowercase()

    /**
     * Cowork dışındaki bir backend oturumunun cwd'sini normal PC gezgininde
     * açmaya hazırlar. Ağ isteğini ekranın LaunchedEffect'i yapar; dönüş değeri
     * bu ziyarette açılacak (kayıtlı son ya da kök) klasördür.
     */
    fun openSessionFileManager(root: String): String {
        activeSessionBrowserRoot = root
        return browserResumeDir(root, prefs.getString(sessionSonKlasorKey(root), null))
    }

    /** Merkez/Spotlight genel Dosyalar girişi oturum hafızasına yazmamalı. */
    fun openGeneralFileManager() {
        activeSessionBrowserRoot = ""
    }

    private fun sessionSonKlasorKey(root: String) =
        "sessionSonKlasor:" + root.replace('\\', '/').trimEnd('/').lowercase()
    fun openCoworkFileForEdit(entry: DirEntry): Job =
        if (isBlockEditorFile(entry.name)) openDocx(entry.path, coworkOnly = true)
        else coworkFileEditDelegate.openForEdit(entry, coworkOnly = true)

    fun openRemoteFileForEdit(entry: DirEntry, coworkOnly: Boolean = false): Job =
        if (isBlockEditorFile(entry.name)) openDocx(entry.path, coworkOnly)
        else coworkFileEditDelegate.openForEdit(entry, coworkOnly)

    fun openRemotePathForEdit(path: String, coworkOnly: Boolean = false): Job =
        if (isBlockEditorFile(path)) openDocx(path, coworkOnly)
        else coworkFileEditDelegate.openForEdit(path, coworkOnly = coworkOnly)

    // Markdown ve DOCX her giriş noktasında uygulama içinde açılır; diğer Office/binary
    // dosyaları telefondaki uygun uygulamaya verilir ve dönüşte PC'ye senkronlanır.
    // Dönüş: true → uygulama içi editör açıldı (çağıran tam ekran viewer'a gitmeli),
    // false → dosya harici uygulamaya devredildi, gezinme yok. openFileEntry ile aynı
    // sözleşme; sohbetteki agfile:// dokunuşu da bunu kullanır.
    // Dosya yöneticisi sıralaması KLASÖR BAZINDA hatırlanır (kalıcı, prefs).
    // Windows yolları büyük/küçük harf ve ayraç duyarsız: anahtar normalize edilir.
    private fun sortPrefKey(path: String) = "fileSort:" + path.replace('\\', '/').trimEnd('/').lowercase()

    fun fileSortFor(path: String): Pair<FileSortKey, Boolean> {
        val raw = prefs.getString(sortPrefKey(path), null) ?: return FileSortKey.NAME to true
        val key = FileSortKey.entries.firstOrNull { it.name == raw.substringBefore('|') } ?: FileSortKey.NAME
        return key to (raw.substringAfter('|', "asc") != "desc")
    }

    fun saveFileSort(path: String, key: FileSortKey, ascending: Boolean) {
        prefs.edit().putString(sortPrefKey(path), key.name + "|" + if (ascending) "asc" else "desc").apply()
    }

    // "Birlikte aç": tür ne olursa olsun (md/docx/görsel dahil) dahili viewer'a
    // GİRMEDEN dosyayı indirir ve uygulama seçiciyle harici uygulamaya verir;
    // dönüşte değişiklik PC'ye geri yazılır (openForEdit sözleşmesi).
    fun openEntryWithExternalApp(entry: DirEntry, coworkOnly: Boolean = false): Job =
        coworkFileEditDelegate.openForEdit(entry, coworkOnly)

    fun openLinkedFile(path: String): Boolean {
        when (viewerKindFor(path)) {
            ViewerKind.MARKDOWN -> openFile(path, coworkOnly = false)
            ViewerKind.DOCX -> openDocx(path, coworkOnly = false)
            ViewerKind.IMAGE -> openImage(path)
            ViewerKind.VIDEO -> openVideo(path)
            ViewerKind.PDF -> openPdf(path)
            ViewerKind.SHEET -> openSheet(path)
            ViewerKind.EXTERNAL -> {
                openRemotePathForEdit(path, coworkOnly = false)
                return false
            }
        }
        return true
    }

    fun openFileEntry(entry: DirEntry, coworkOnly: Boolean = false): Boolean {
        when (viewerKindFor(entry.name)) {
            ViewerKind.MARKDOWN -> openFile(entry.path, coworkOnly)
            ViewerKind.DOCX -> openDocx(entry.path, coworkOnly)
            ViewerKind.IMAGE -> openImage(entry.path)
            ViewerKind.VIDEO -> openVideo(entry.path)
            ViewerKind.PDF -> openPdf(entry.path)
            ViewerKind.SHEET -> openSheet(entry.path)
            ViewerKind.EXTERNAL -> {
                openRemoteFileForEdit(entry, coworkOnly)
                return false
            }
        }
        return true
    }

    fun lastOpenedPathValue(): String = _uiState.value.lastOpenedPath

    fun downloadByPath(sourcePath: String, displayName: String, subDir: String = "") =
        downloadsDelegate.downloadByPath(sourcePath, displayName, subDir)
    fun updateGlobalSearchQuery(query: String) = globalSearchDelegate.updateQuery(query)
    fun dismissGlobalSearch() = globalSearchDelegate.dismiss()

    // Chat search actions
    // Aynı anda başlayan history-load işlerini geçersiz kılmak için nesil sayacı:
    // yalnız en son iş sonucu state'e uygular (eski iş süperseded olur).
    private var chatSearchLoadGen = 0

    fun openChatSearch() {
        _uiState.update { it.copy(chatSearch = ChatSearchState(open = true)) }
        runChatSearchHistoryLoad(initialQuery = "", preferredIndex = -1)
    }
    fun closeChatSearch() {
        chatSearchLoadGen++ // devam eden yüklemeyi süpersede et
        _uiState.update { it.copy(chatSearch = ChatSearchState()) }
    }

    /**
     * Global arama mesaj sonucundan deep-link: TEK sıralı işlem. Önce hedef oturumu
     * (aynı backend içi geçiş dahil) resume/adopt ile açıp konuşması yüklenene kadar
     * bekler; ancak ondan sonra aramayı başlatır. Böylece eski/yanlış oturum üzerinde
     * arama yapılmaz. Oturum açılamazsa hata gösterilir, arama başlatılmaz.
     */
    fun openGlobalSearchMessageHit(hit: GlobalSearchHit, query: String) = viewModelScope.launch {
        val gen = ++chatSearchLoadGen
        val targetProvider = if (hit.container == "cowork") normalizeCoworkProvider(hit.backend) else hit.backend
        // Paneli hemen aç ve stale oturumun mesajlarını temizle: hedef yüklenene kadar
        // hiçbir eşleşme yanlış sohbetten hesaplanmasın.
        _uiState.update { it.copy(
            chatSearch = ChatSearchState(open = true, query = query, pendingTargetRowId = hit.rowId, loadingHistory = true),
            messagesList = emptyList(),
        ) }
        openSessionForSearch(hit)
        val loaded = awaitSessionLoaded(targetProvider, timeoutMs = 8000)
        if (gen != chatSearchLoadGen) return@launch
        if (!loaded) {
            _uiState.update { it.copy(chatSearch = it.chatSearch.copy(loadingHistory = false, historyLoadError = true)) }
            _messages.emit("Hedef oturum açılamadı; arama başlatılmadı")
            return@launch
        }
        applyFullHistoryLoad(gen, initialQuery = query, preferredIndex = hit.matchOrdinal)
    }

    fun updateChatSearchQuery(query: String) = applyChatSearchMatch(query, preferredIndex = -1)

    // Eşleşmeleri tüm yüklü geçmiş üzerinden hesaplar (saf mantık ChatSearchLogic'te).
    private fun applyChatSearchMatch(query: String, preferredIndex: Int) {
        val match = ChatSearchLogic.computeMatch(_uiState.value.messagesList, query, preferredIndex)
        _uiState.update { it.copy(chatSearch = it.chatSearch.copy(
            query = query,
            matchRowIds = match.matchRowIds,
            selectedIndex = match.selectedIndex,
        )) }
    }

    private fun runChatSearchHistoryLoad(initialQuery: String, preferredIndex: Int) = viewModelScope.launch {
        val gen = ++chatSearchLoadGen
        applyFullHistoryLoad(gen, initialQuery, preferredIndex)
    }

    // Tam geçmişi yükler ve eşleşmeleri uygular. Yükleme bitince başlangıçtaki değil,
    // o an state'te bulunan GÜNCEL sorgu kullanılır (kullanıcı yükleme sırasında yazmış
    // olabilir). Sorgu değişmişse deep-link ordinal seçimi düşer (idx = -1). Hata/
    // truncation durumu state'e ayrı bayraklarla yansır.
    private suspend fun applyFullHistoryLoad(gen: Int, initialQuery: String, preferredIndex: Int) {
        _uiState.update { it.copy(chatSearch = it.chatSearch.copy(loadingHistory = true, historyLoadError = false)) }
        val result = conversationDelegate.loadFullHistory()
        if (gen != chatSearchLoadGen) return // süperseded
        _uiState.update { it.copy(chatSearch = it.chatSearch.copy(
            loadingHistory = false,
            truncated = result is FullHistoryLoadResult.Truncated,
            historyLoadError = result is FullHistoryLoadResult.Failed,
        )) }
        val currentQuery = _uiState.value.chatSearch.query
        if (currentQuery.length >= 2) {
            val idx = if (currentQuery == initialQuery) preferredIndex else -1
            applyChatSearchMatch(currentQuery, idx)
        }
    }

    // Deep-link hedef oturumunu doğru resume/adopt yoluyla açar. openOperation'ın aynı
    // backend içinde erken dönmesini (switchBackend early-return) baypas eder: resume
    // fonksiyonları oturumu koşulsuz adopt eder, böylece aynı backend içi farklı oturuma
    // geçiş de güvenilir çalışır.
    private fun openSessionForSearch(hit: GlobalSearchHit) {
        if (hit.container == "cowork") {
            resumeCoworkSession(CoworkSessionRecord(provider = hit.backend, sessionId = hit.sessionId, cwd = hit.projectPath, model = ""))
            return
        }
        when (hit.backend) {
            Backend.CLAUDE_APP.id -> resumeClaudeAppDiskSession(ClaudeDiskSession(id = hit.sessionId, cwd = hit.projectPath, title = hit.title, lastText = "", turns = 0, mtime = hit.mtime))
            Backend.CODEX_APP.id -> resumeCodexAppDiskSession(CodexDiskSession(id = hit.sessionId, cwd = hit.projectPath, title = hit.title, lastText = "", turns = 0, mtime = hit.mtime))
            Backend.OPENCODE2_APP.id -> resumeOpencodeAppDiskSession(AppDiskSession(id = hit.sessionId, cwd = hit.projectPath, title = hit.title, lastText = "", turns = 0, mtime = hit.mtime))
            Backend.OMP.id -> resumeOmpDiskSession(AppDiskSession(id = hit.sessionId, cwd = hit.projectPath, title = hit.title, lastText = "", turns = 0, mtime = hit.mtime))
            Backend.AGY.id -> resumeAgyDiskSession(AgyDiskSession(id = hit.sessionId, cwd = hit.projectPath, title = hit.title, lastText = "", turns = 0, mtime = hit.mtime))
        }
    }

    // Aktif provider+session'ı verir (cowork ise gerçek sağlayıcıya çözülür).
    private fun activeProviderSession(s: RemoteUiState): Pair<String, String> {
        val provider = if (s.backend == Backend.COWORK.id) normalizeCoworkProvider(s.cowork.provider) else (s.backend ?: "")
        val sid = when (provider) {
            Backend.CLAUDE_APP.id -> s.claude.sessionId
            Backend.CODEX_APP.id -> s.codex.sessionId
            Backend.OPENCODE2_APP.id -> s.opencode.sessionId
            Backend.OMP.id -> s.omp.sessionId
            Backend.AGY.id -> s.agy.sessionId
            else -> ""
        }
        return provider to sid
    }

    // Hedef sağlayıcı aktif olup konuşması yüklenene (messagesList dolana) kadar bekler.
    // openSessionForSearch messagesList'i önce boşalttığından, dolu olması taze/doğru
    // oturumun yüklendiğini gösterir. Zaman aşımında false döner.
    private suspend fun awaitSessionLoaded(targetProvider: String, timeoutMs: Long): Boolean {
        var waited = 0L
        val step = 60L
        while (waited < timeoutMs) {
            val s = _uiState.value
            val (provider, sid) = activeProviderSession(s)
            if (provider == targetProvider && sid.isNotBlank() && s.messagesList.isNotEmpty()) return true
            kotlinx.coroutines.delay(step); waited += step
        }
        return false
    }
    fun chatSearchGoNext() {
        val st = _uiState.value.chatSearch
        if (st.matchRowIds.isEmpty()) return
        val next = (st.selectedIndex + 1) % st.matchRowIds.size
        _uiState.update { it.copy(chatSearch = it.chatSearch.copy(selectedIndex = next)) }
    }
    fun chatSearchGoPrev() {
        val st = _uiState.value.chatSearch
        if (st.matchRowIds.isEmpty()) return
        val prev = if (st.selectedIndex <= 0) st.matchRowIds.size - 1 else st.selectedIndex - 1
        _uiState.update { it.copy(chatSearch = it.chatSearch.copy(selectedIndex = prev)) }
    }
    fun clearDownloadHistory() = downloadsDelegate.clearDownloadHistory()
    fun openDownloadFolder(record: DownloadRecord?) = downloadsDelegate.openDownloadFolder(record)
    fun moveDownloadIntoFolder(localUri: String, folder: DownloadRecord) = downloadsDelegate.moveDownloadIntoFolder(localUri, folder)
    fun deleteDownloads(records: List<DownloadRecord>) = downloadsDelegate.deleteDownloads(records)

    fun refreshConversation(showErrors: Boolean = true): Job = conversationDelegate.refresh(showErrors)

    /**
     * Sohbetteki "Yenile" pill'i. Eski hali yalnız HTTP'den konuşmayı çekiyordu;
     * bu 2,5 sn'lik poll'un zaten yaptığı iş olduğu için tuş görünür hiçbir şey
     * yapmıyordu. Asıl bozulan şey soketti: uygulama arka planda dondurulunca WS
     * yarı-açık kalıyor ("bağlı" görünür, veri akmaz) ve akış durmuş gibi
     * görünüyor. Bu yüzden tuş artık ÖNE GELME akışının aynısını yapar —
     * soketi tazele (nudge), konuşmayı çek, sekme durumlarını güncelle.
     */
    fun resyncConversation() = viewModelScope.launch {
        if (_uiState.value.conversationRefreshing) return@launch
        _uiState.update { it.copy(conversationRefreshing = true) }
        streamManager.nudge()
        runCatching { refreshConversation(showErrors = true).join() }
        runCatching { tabsDelegate.refreshTabStatuses() }
        _uiState.update { it.copy(conversationRefreshing = false) }
    }

    // Sekme kapatma onayı — çarpı tuşu (fromBack=false) ve geri jesti (true).
    fun requestCloseTab(tabId: String, fromBack: Boolean = false) {
        if (tabId.isBlank()) return
        _pendingTabClose.value = PendingTabClose(tabId, fromBack)
    }
    fun dismissCloseTab() { _pendingTabClose.value = null }
    fun confirmCloseTab() {
        val pending = _pendingTabClose.value ?: return
        _pendingTabClose.value = null
        tabsDelegate.closeTab(pending.tabId)
    }

    // Uygulama öne gelince (ON_START): yarı-açık kalmış WS'yi anında tazele ve
    // konuşmayı bir kez HTTP'den çek. Arka planda donan süreç dönüşte ping/backoff
    // beklemeden taze veriye kavuşur; oturum açık değilse ikisi de no-op.
    fun onAppForeground() {
        streamManager.nudge()
        refreshConversation(showErrors = false)
        if (!BuildConfig.IS_LITE) maintenanceDelegate.warmOpenTabsNow()
        // Harici editörden dönüş: Cowork veya genel PC dosyalarındaki değişiklikler yazılır.
        coworkFileEditDelegate.syncBack()
    }

    // Tur sürerken gönderilen prompt kuyruğa alınır (backend meşgulken kaybolmasın);
    // boştaysa doğrudan gönderilir.
    fun sendPrompt(): Job {
        val st = _uiState.value
        // Codex /goal yakalama — gönderim yolundan ÖNCE ve ViewModel katmanında (UI'de
        // değil) ki desktop istemcisi de ileride aynı komut sözdizimini paylaşsın; saf
        // ayrıştırma CodexGoalCommand'da, burada yalnız eylem seçimi var. YALNIZ codex-app:
        // cowork'un codex sağlayıcısı bu native /goal davranışına sahip değil, karıştırılmaz.
        if (st.backend == Backend.CODEX_APP.id) {
            val cmd = CodexGoalCommand.parse(st.input)
            if (cmd != null) {
                _uiState.update { it.copy(input = "") }
                return when (cmd) {
                    CodexGoalCommand.Show -> showCodexAppGoal()
                    CodexGoalCommand.Clear -> codexAppClearGoal()
                    is CodexGoalCommand.Set -> codexAppSetGoal(cmd.objective)
                }
            }
        }
        return if (st.running) enqueuePrompt() else conversationDelegate.send()
    }

    private fun enqueuePrompt(): Job = viewModelScope.launch {
        var added = false
        val promptId = UUID.randomUUID().toString()
        _uiState.update { state ->
            enqueueCurrentPrompt(state, promptId).also {
                added = it !== state
            }
        }
        if (!added) return@launch
        persistPromptQueue()
        _messages.emit("Kuyruğa eklendi (${_uiState.value.activeQueuedPrompts().size})")
    }

    // Tur bitince (running=false) kuyruktaki ilk prompt'u gönderir. Kullanıcı o an
    // yeni bir şey yazıyorsa (input/ek dolu) ezmeyiz; bir sonraki tur bitişini bekler.
    // Yalnız aktif sekmenin kuyruğu çözülür; başka sekmedeki prompt yerinde kalır.
    private fun drainPromptQueue() {
        val st = _uiState.value
        val next = st.activeQueuedPrompts().firstOrNull() ?: return
        if (next.id in promptQueueAwaitingHydration) return
        var restored = false
        _uiState.update { state ->
            restoreNextQueuedPrompt(state).also { restored = it !== state }
        }
        if (!restored) return
        persistPromptQueue()
        conversationDelegate.send()
    }

    private fun onPromptQueueSessionHydrated() {
        val activeIds = _uiState.value.activeQueuedPrompts().mapTo(mutableSetOf()) { it.id }
        if (activeIds.isNotEmpty()) promptQueueAwaitingHydration.removeAll(activeIds)
        drainPromptQueue()
    }

    // commit(), apply() DEĞİL: bu yazının hemen ardından süreç ölebiliyor (APK
    // güncellemesi kill gönderiyor) ve apply()'ın kuyruğa aldığı yazı diske
    // inmeden kaybolabiliyor. Yük birkaç yüz bayt; kaybolan mesajın bedeli daha ağır.
    private fun persistPromptQueue() {
        prefs.edit()
            .putString(PROMPT_QUEUE_PREF_KEY, PromptQueueJson.encode(_uiState.value.promptQueue))
            .commit()
    }

    /**
     * Gönderilmemiş composer içeriğini (metin + ekler) diske yazar. Kuyruk zaten
     * kalıcıydı; kuyruğa GİRMEMİŞ taslak süreç ölünce kayboluyordu (03.08.2026
     * canlı şikâyet: APK güncellemesi yazılmış mesajı sildi).
     *
     * Ekler de yazılır: ek zaten köprüye yüklenmiş durumda, state'te taşınan
     * yalnız ad + PC yolu — yani birkaç yüz bayt, yeniden yükleme yok.
     */
    private fun persistComposerDrafts() {
        val drafts = _uiState.value.allComposerDrafts()
        prefs.edit().putString(COMPOSER_DRAFTS_PREF_KEY, ComposerDraftsJson.encode(drafts)).commit()
    }

    /**
     * Açılışta taslakları geri koyar. Etkin sekmeninki composer'a, diğerleri
     * sözlükte bekler — çalışan uygulamadaki kuralın aynısı (`withActiveTab`).
     *
     * Composer doluysa metin EZİLMEZ: sekmeler geri gelirken araya bir paylaşım
     * niyeti düşmüş olabilir, diskteki eski taslak onu silmesin.
     */
    private fun restoreComposerDrafts() {
        val st = _uiState.value
        val drafts = decodeComposerDrafts(
            prefs.getString(COMPOSER_DRAFTS_PREF_KEY, null),
            prefs.getString(COMPOSER_DRAFT_PREF_KEY, null),
        )
        if (drafts.isEmpty()) return
        _uiState.update { state ->
            val aktif = drafts[state.activeTabId]
            val bos = state.input.isBlank() && state.attachments.isEmpty()
            if (aktif == null || !bos) {
                state.copy(composerDrafts = drafts - state.activeTabId)
            } else {
                state.copy(
                    input = aktif.text,
                    attachments = aktif.attachments,
                    composerDrafts = drafts - state.activeTabId,
                )
            }
        }
    }

    private fun discardClosedTabQueuedPrompts() {
        var changed = false
        _uiState.update { state ->
            discardClosedTabQueuedPrompts(state).also { changed = it !== state }
        }
        if (changed) {
            promptQueueAwaitingHydration.retainAll(_uiState.value.promptQueue.map { it.id }.toSet())
            persistPromptQueue()
        }
    }

    fun removeQueuedPrompt(id: String) {
        if (id.isBlank()) return
        var removed = false
        _uiState.update { state ->
            val next = state.promptQueue.filterNot { it.id == id }
            if (next.size == state.promptQueue.size) state
            else state.copy(promptQueue = next).also { removed = true }
        }
        if (removed) {
            promptQueueAwaitingHydration.remove(id)
            persistPromptQueue()
        }
    }

    /**
     * PC gezgininden seçilen ekler. Telefon yolundan farkı: HİÇBİR ŞEY
     * AKTARILMAZ. Dosya zaten köprünün diskinde ve modele giden şey yolun
     * kendisi ("Ek dosyalar:" bloğu), yani indirip tekrar yüklemek boşa iş
     * olurdu — 200 MB'lık bir kaydı eklemek anında oluyor.
     */
    fun attachPcFiles(entries: List<DirEntry>) = viewModelScope.launch {
        val yeni = entries
            .filter { it.type != "dir" && it.path.isNotBlank() }
            .map { ChatAttachment(name = it.name, path = it.path, isImage = isImageFile(it.name)) }
        if (yeni.isEmpty()) return@launch
        // Aynı dosya iki kez eklenmesin: yol kimliktir.
        _uiState.update { state ->
            val mevcut = state.attachments.map { it.path }.toSet()
            state.copy(attachments = state.attachments + yeni.filterNot { it.path in mevcut })
        }
        _messages.emit(if (yeni.size == 1) "Eklendi: ${yeni.first().name}" else "${yeni.size} dosya eklendi")
    }

    fun attachFile(bytes: ByteArray, filename: String, localUri: String? = null, mimeType: String? = null) = viewModelScope.launch {
        if (bytes.isEmpty()) {
            _messages.emit("Dosya boş: $filename")
            return@launch
        }
        if (bytes.size > 25 * 1024 * 1024) {
            _messages.emit("Dosya çok büyük (sınır 25MB): $filename")
            return@launch
        }
        _attaching.value = true
        val state = _uiState.value
        runCatching { client.attachFile(state.settings, bytes, filename) }
            .onSuccess { uploaded ->
                val att = ChatAttachment(
                    name = uploaded.name,
                    path = uploaded.path,
                    localUri = localUri,
                    isImage = (mimeType ?: "").startsWith("image/"),
                )
                _uiState.update { state -> state.copy(attachments = state.attachments + att) }
                _messages.emit("Eklendi: ${uploaded.name}")
            }
            .onFailure { reportError("Dosya eklenemedi", it) }
        _attaching.value = false
    }

    // ── Paylaş menüsünden gelen dosyalar ────────────────────────────────────
    // Eskiden paylaşım koşulsuz sohbet eki oluyordu. Avukat iş akışında gelen
    // evrakın asıl yeri çoğu kez sohbet değil DOSYA (çalışma alanı); artık hedef
    // soruluyor. Çalışma alanı yoksa soru sorulmaz, eski davranış aynen sürer.
    fun offerSharedFiles(files: List<SharedFile>) {
        if (files.isEmpty()) return
        // Hedef sorusu artık çalışma alanı YOKKEN de sorulur. Eskiden alan
        // yoksa soru atlanıp dosya doğrudan sohbete ekleniyordu; "Not ekle" ve
        // "Bilgisayara yükle" alan gerektirmediği için o kapı onları erişilemez
        // yapardı — üstelik alan listesi soğuk açılışta henüz yüklenmemiş de
        // olabiliyor, yani kapı rastgele kapanıyordu. Alansız seçenekler
        // sheet'in kendi içinde eleniyor (shareHasWorkspaceTarget).
        if (_uiState.value.cowork.workspaces.isEmpty()) loadCoworkWorkspaces()
        _pendingShare.value = PendingShare(files)
    }

    fun dismissPendingShare() {
        _pendingShare.value?.files?.forEach { runCatching { java.io.File(it.cachePath).delete() } }
        _pendingShare.value = null
    }

    /** Paylaşılanları sohbet ekine çevirir (paylaşımın eski davranışı). */
    fun attachPendingShareToChat() {
        val files = _pendingShare.value?.files.orEmpty()
        _pendingShare.value = null
        attachSharedFiles(files)
    }

    private fun attachSharedFiles(files: List<SharedFile>) = viewModelScope.launch {
        for (file in files) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { java.io.File(file.cachePath).readBytes() }.getOrDefault(ByteArray(0))
            }
            if (bytes.isEmpty()) { _messages.emit("Dosya okunamadı: ${file.name}"); continue }
            attachFile(bytes, file.name, mimeType = file.mimeType).join()
            withContext(Dispatchers.IO) { runCatching { java.io.File(file.cachePath).delete() } }
        }
    }

    /** Paylaşılanları seçilen çalışma alanının klasörüne yükler. */
    fun savePendingShareToWorkspace(workspacePath: String) =
        savePendingShareTo(workspacePath, coworkOnly = true)

    /**
     * Paylaşılanları bilgisayarda SERBEST bir klasöre yükler (ui3, 18.08.2026).
     *
     * Çalışma alanı yolundan tek farkı `coworkOnly = false`: hedef Cowork
     * kökünün altında olmak zorunda değil, köprü gezgininde gezilen herhangi
     * bir klasör olabilir. Ayrı bir fonksiyon, çünkü adı ne yaptığını söylesin
     * — `savePendingShareToWorkspace(path, coworkOnly = false)` çağrısı
     * okuyanı "çalışma alanı ama değil" diye şaşırtırdı.
     */
    fun savePendingShareToPath(dir: String) = savePendingShareTo(dir, coworkOnly = false)

    /**
     * Paylaşılanı modele okutup not defterine yazar ("Not ekle").
     *
     * Otomatik ekran görüntüsü taramasının elle tetiklenen karşılığı. Aradaki
     * fark pahalı olan tarafta: tarayıcı her görüntü için "bu kayda değer mi"
     * sorusunu modele sorduruyordu (297 görüntü → 3 not), burada o soruyu
     * paylaş tuşuna basmak zaten cevaplıyor.
     *
     * Çağrı MODELİ BEKLER (~15 sn). Sheet hemen kapanır ve bekleme arka planda
     * sürer; kullanıcı bu sırada uygulamayı kullanabilir, sonuç mesaj olarak
     * düşer. Çok dosyada tek tek işlenir — model çağrıları paralel gitse köprü
     * tarafında aynı agy örneğini yarıştırırdı.
     */
    fun savePendingShareAsNote() = viewModelScope.launch {
        val files = _pendingShare.value?.files.orEmpty()
        _pendingShare.value = null
        if (files.isEmpty()) return@launch
        _messages.emit(if (files.size == 1) "Not çıkarılıyor…" else "${files.size} not çıkarılıyor…")
        for (file in files) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { java.io.File(file.cachePath).readBytes() }.getOrDefault(ByteArray(0))
            }
            if (bytes.isEmpty()) { _messages.emit("Dosya okunamadı: ${file.name}"); continue }
            val result = client.createNoteFromShare(_uiState.value.settings, file.name, bytes)
            // Önbellek kopyası her durumda silinir: başarısız çağrı da dosyayı
            // orada bırakmamalı, kaynak zaten paylaşan uygulamada duruyor.
            withContext(Dispatchers.IO) { runCatching { java.io.File(file.cachePath).delete() } }
            if (result.ok) {
                _messages.emit(
                    if (result.kind == "hatirlatici") "Hatırlatıcı kuruldu: ${result.title}"
                    else "Not eklendi: ${result.title}"
                )
            } else {
                _messages.emit("Not çıkarılamadı: ${result.error.ifBlank { file.name }}")
            }
        }
        // Liste tazelensin ki yeni not "Notlarım"da beklemeden görünsün.
        loadCoworkNotes()
    }

    private fun savePendingShareTo(workspacePath: String, coworkOnly: Boolean) = viewModelScope.launch {
        val files = _pendingShare.value?.files.orEmpty()
        _pendingShare.value = null
        if (workspacePath.isBlank() || files.isEmpty()) return@launch
        var saved = 0
        for (file in files) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { java.io.File(file.cachePath).readBytes() }.getOrDefault(ByteArray(0))
            }
            if (bytes.isEmpty()) { _messages.emit("Dosya okunamadı: ${file.name}"); continue }
            runCatching {
                client.uploadFile(
                    settings = _uiState.value.settings,
                    dir = workspacePath,
                    filename = file.name,
                    bytes = bytes,
                    coworkOnly = coworkOnly,
                )
            }.onSuccess { result ->
                if (result.ok) saved++ else _messages.emit("${file.name}: ${result.error.ifBlank { "kaydedilemedi" }}")
            }.onFailure { reportError("${file.name} kaydedilemedi", it) }
            withContext(Dispatchers.IO) { runCatching { java.io.File(file.cachePath).delete() } }
        }
        if (saved > 0) {
            val target = workspacePath.substringAfterLast('\\').substringAfterLast('/')
            _messages.emit(if (saved == 1) "Kaydedildi: $target" else "$saved dosya kaydedildi: $target")
            // Gezgin açıksa yeni dosya görünsün.
            if (_uiState.value.cowork.activeProjectPath == workspacePath) loadBrowserDir(workspacePath)
        }
    }

    fun removeAttachment(path: String) {
        _uiState.update { st -> st.copy(attachments = st.attachments.filterNot { it.path == path }) }
    }

    // Sohbetteki görsel eklerin thumbnail/tam-görüntü çizimi için köprüden ham
    // byte'ları çeker (/download). Ek dosyalar bridge/tmp altında 24 saat yaşar;
    // silinmişse null döner → çağıran yer yolu düz metin olarak gösterir.
    suspend fun loadAttachmentBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.downloadFile(_uiState.value.settings, path) { }.use { resp -> resp.body?.bytes() }
        }.getOrNull()
    }

    // Sohbette paylaşılan ekran görüntüsünü kalıcı klasöre yazar. Paylaşılan
    // görseller iş bitince silindiğinden saklama kararını kullanıcı verir
    // (tam ekran önizlemedeki kaydet tuşu). Sunucuda kopyalama ucu yok; byte'lar
    // /download ile alınıp /upload ile hedefe yazılır — köprüde değişiklik istemez.
    suspend fun saveImageToGallery(path: String): Boolean = withContext(Dispatchers.IO) {
        val dir = savedImagesDir()
        if (dir == null) {
            reportError("Kaydedilemedi", IllegalStateException("PC klasörü belirlenemedi"))
            return@withContext false
        }
        val bytes = loadAttachmentBytes(path) ?: return@withContext false
        val name = path.replace('\\', '/').substringAfterLast('/').ifBlank { "ekran.png" }
        val result = client.uploadFile(_uiState.value.settings, dir, name, bytes, createDir = true)
        if (!result.ok) reportError("Kaydedilemedi", IllegalStateException(result.error))
        result.ok
    }

    /**
     * Kaydedilen görsellerin PC'deki hedefi: <ev dizini>/Pictures/AgentBridge —
     * telefondaki /sdcard/AgentBridge/Pictures'ın karşılığı.
     *
     * Yol KODA GÖMÜLÜ DEĞİL. Köprüye kök vermeden /dirs sorulduğunda dönen
     * `base`, köprünün çalıştığı kullanıcının ev dizinidir; kullanıcı adını
     * sabite yazmak projeyi tek bir makineye çiviliyordu. Ayraç ev dizininden
     * çıkarılır, böylece Windows dışı bir köprüde de doğru birleşir.
     */
    private suspend fun savedImagesDir(): String? {
        val home = runCatching { client.workerDirs(_uiState.value.settings, "") }
            .getOrNull()?.takeIf { it.ok }?.base?.trimEnd('\\', '/')
            ?.takeIf { it.isNotBlank() } ?: return null
        val sep = if (home.contains('\\')) "\\" else "/"
        return listOf(home, "Pictures", "AgentBridge").joinToString(sep)
    }

    private var openedFilePath: String = ""
    private var openedFileCoworkOnly: Boolean = false
    // Açık dosya telefonun kendi diskinde mi? Okuma ve yazma köprü yerine
    // java.io.File üzerinden gider.
    private var openedFilePhoneLocal: Boolean = false
    private var openedCoworkNoteId: String = ""

    fun openFile(path: String, coworkOnly: Boolean = false): Job {
        if (isDocxFile(path)) return openDocx(path, coworkOnly)
        if (isImageFile(path)) return openImage(path)
        return viewModelScope.launch {
            clearViewersExcept(ViewerKind.MARKDOWN)
            openedFilePath = path
            openedFileCoworkOnly = coworkOnly
            _markdownSaveState.value = MarkdownSaveState()
            _openedFile.value = FileResult(ok = true, name = path, content = "", truncated = false, error = "")
            runCatching { client.readFile(_uiState.value.settings, path) }
                .onSuccess { _openedFile.value = it }
                .onFailure {
                    _openedFile.value = FileResult(ok = false, name = path, content = "", truncated = false, error = it.message ?: "unknown error")
                    reportError("Dosya açılamadı", it)
                }
        }
    }

    /**
     * Görüntüleyiciler TEK ekranı paylaşıyor (HubFileViewerScreen dal dal seçiyor),
     * bu yüzden yeni belge açılırken ötekilerin durumu sıfırlanmalı. Eskiden her
     * açıcı ötekileri tek tek temizliyordu; PDF eklenince .md açıcısı onu temizlemeyi
     * atladı ve markdown'a dokununca eski PDF açıldı (canlı hata 03.08.2026).
     * Artık tek kapı: yeni bir görüntüleyici eklenince yalnız burası genişler.
     */
    private fun clearViewersExcept(keep: ViewerKind) {
        if (keep != ViewerKind.MARKDOWN) {
            _openedFile.value = null
            _markdownSaveState.value = MarkdownSaveState()
        }
        if (keep != ViewerKind.DOCX) {
            docxOpenGeneration++
            openedDocxPackage = null
            _openedDocx.value = DocxEditorState()
        }
        if (keep != ViewerKind.IMAGE) {
            imageOpenGeneration++
            _openedImage.value = ImageViewerState()
        }
        if (keep != ViewerKind.PDF) {
            // PDF'te sızıntı bedeli yüksek: açık kalan renderer native descriptor tutar.
            pdfOpenGeneration++
            releasePdfRenderer()
            _openedPdf.value = PdfViewerState()
            _pdfRead.value = PdfReadState()
            _pageLang.value = emptyMap()
        }
        if (keep != ViewerKind.VIDEO) {
            videoOpenGeneration++
            _openedVideo.value = VideoViewerState()
        }
        if (keep != ViewerKind.SHEET) {
            sheetOpenGeneration++
            _openedSheet.value = SheetViewerState()
        }
    }

    fun closeFile() {
        clearViewersExcept(ViewerKind.EXTERNAL) // hiçbiri kalmasın
        openedDocxPackage = null
        _markdownSaveState.value = MarkdownSaveState()
        _noteAiState.value = NoteAiUiState()
        openedFilePath = ""
        openedFileCoworkOnly = false
        openedFilePhoneLocal = false
        openedCoworkNoteId = ""
    }

    /**
     * Telefondaki .md dosyasını uygulamanın kendi editöründe açar. Honor'un
     * dosya yöneticisi .md'yi açamıyor; artık gezginde dokunmak yeterli.
     */
    fun openPhoneMarkdown(path: String): Job = viewModelScope.launch {
        clearViewersExcept(ViewerKind.MARKDOWN)
        openedFilePath = path
        openedFileCoworkOnly = false
        openedFilePhoneLocal = true
        _markdownSaveState.value = MarkdownSaveState()
        val file = java.io.File(path)
        _openedFile.value = FileResult(ok = true, name = file.name, content = "", truncated = false, error = "")
        val okunan = withContext(Dispatchers.IO) {
            runCatching { file.readText(Charsets.UTF_8) }
        }
        okunan
            .onSuccess { text ->
                _openedFile.value = FileResult(
                    ok = true, name = file.name, content = text, truncated = false,
                    error = "", path = path, size = file.length(), mtime = file.lastModified(),
                )
            }
            .onFailure {
                _openedFile.value = FileResult(
                    ok = false, name = file.name, content = "", truncated = false,
                    error = it.message ?: "Dosya okunamadı",
                )
                reportError("Dosya açılamadı", it)
            }
    }

    fun editOpenedFile() {
        val path = openedFilePath.takeIf { it.isNotBlank() } ?: _openedFile.value?.name.orEmpty()
        if (path.isBlank()) return
        if (isDocxFile(path)) openDocx(path, openedFileCoworkOnly)
        else openRemotePathForEdit(path, openedFileCoworkOnly)
    }

    // DOCX, kaynak .md'nin PC'deki klasörüne yazılır (aynı adla, çakışmada köprü
    // " (1)" ekler). PC'ye yazılamazsa (klasör yok / bağlantı hatası) telefondaki
    // İndirilenler/AgentBridge'e düşülür ki dışa aktarım kaybolmasın.
    fun exportOpenedMarkdownAsDocx(content: String): Job = viewModelScope.launch {
        val opened = _openedFile.value ?: return@launch
        val sourceName = opened.name.substringAfterLast('/').substringAfterLast('\\')
        val outputName = sourceName.substringBeforeLast('.', sourceName).ifBlank { "belge" } + ".docx"
        val bytes = runCatching {
            val markdown = loadOpenedMarkdownContent(opened, content)
            withContext(Dispatchers.Default) { MarkdownDocxExporter.export(markdown) }
        }.getOrElse {
            _messages.emit("DOCX dışa aktarılamadı: ${it.message ?: "bilinmeyen hata"}")
            return@launch
        }
        val sourcePath = openedFilePath.takeIf { it.isNotBlank() } ?: opened.path.ifBlank { opened.name }
        val targetDir = browserParentPath(sourcePath)
        val uploaded = targetDir?.let {
            client.uploadFile(_uiState.value.settings, it, outputName, bytes, openedFileCoworkOnly)
        }
        if (uploaded?.ok == true) {
            val kayitliYol = uploaded.path.ifBlank { "$targetDir/$outputName" }
            // "Aç" YALNIZ bu dalda: dosya PC'de duruyor ve uygulamanın kendi DOCX
            // görüntüleyicisi onu açabiliyor. Aşağıdaki telefona-indirme dalında
            // dosya cihazda; onu sistem uygulaması açar, bizim işimiz değil.
            _alerts.emit(
                AppAlert(
                    text = "DOCX kaydedildi: $kayitliYol",
                    action = AppAlertAction.OpenFile(kayitliYol, openedFileCoworkOnly),
                    actionLabel = "Aç",
                ),
            )
            return@launch
        }
        runCatching { downloadRepo.exportBytes(outputName, DOCX_MIME, bytes) }
            .onSuccess {
                val reason = uploaded?.error?.ifBlank { null } ?: "kaynak klasör belirlenemedi"
                _messages.emit("PC'ye yazılamadı ($reason); DOCX telefona aktarıldı: İndirilenler/AgentBridge/${it.name}")
            }
            .onFailure { _messages.emit("DOCX dışa aktarılamadı: ${it.message ?: "bilinmeyen hata"}") }
    }

    fun shareOpenedMarkdown(content: String): Job = viewModelScope.launch {
        val opened = _openedFile.value ?: return@launch
        val rawName = opened.name.substringAfterLast('/').substringAfterLast('\\')
        val name = rawName.takeIf { isMarkdownFile(it) } ?: "belge.md"
        runCatching { loadOpenedMarkdownContent(opened, content).toByteArray(Charsets.UTF_8) }
            .onSuccess { shareBytes(name, "text/markdown", it) }
            .onFailure { _messages.emit("Markdown paylaşıma hazırlanamadı: ${it.message ?: "bilinmeyen hata"}") }
    }

    // Görüntüleyicinin sağa/sola kaydırırken dolaşacağı klasör. Gezgin dosyaya
    // dokunmadan HEMEN ÖNCE doldurur; böylece sıra ekrandaki sıralamayla (ad /
    // tarih / boyut tercihi) birebir aynı olur.
    private val imageNavigationOrder = ImageNavigationOrder()
    private val imageSiblings: List<String> get() = imageNavigationOrder.paths
    private var imageSiblingsLocal: Boolean = false
    private var imageSiblingsAttachment: Boolean = false

    fun setImageNavigation(paths: List<String>, local: Boolean, attachment: Boolean = false) {
        imageNavigationOrder.reset(paths)
        imageSiblingsLocal = local
        imageSiblingsAttachment = attachment
        imageBytesCache.evictAll()
    }

    fun imageSiblingPaths(): List<String> = imageSiblings
    fun imageSiblingsLocal(): Boolean = imageSiblingsLocal

    fun toggleImageShuffle() {
        val current = _openedImage.value
        if (current.loading) return
        imageNavigationOrder.toggle(current.path)
        _openedImage.value = current.copy(
            index = imageSiblings.indexOf(current.path),
            total = imageSiblings.size,
            shuffled = imageNavigationOrder.shuffled,
        )
    }

    /**
     * Kaydırırken komşu sayfalar da çizildiği için aynı dosya kısa sürede birkaç
     * kez isteniyor: PC tarafında bu her seferinde yeni bir indirme demek olurdu.
     * Toplam boyuta göre sınırlı önbellek (bayt sayısıyla ölçülür, adetle değil —
     * 3 fotoğrafın boyutu 100 KB da olabilir 40 MB da).
     */
    private val imageBytesCache = object : android.util.LruCache<String, ByteArray>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    suspend fun loadImageBytes(path: String, local: Boolean): ByteArray {
        imageBytesCache.get(path)?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            if (local) readLocalImageBytes(path) else downloadImageBytes(path)
        }
        imageBytesCache.put(path, bytes)
        return bytes
    }

    /** Sayfa oturunca komşuların baytlarını arka planda çeker: kaydırma boş kalmasın. */
    fun prefetchImageNeighbours(index: Int) {
        listOf(index - 1, index + 1).forEach { i ->
            val path = imageSiblings.getOrNull(i) ?: return@forEach
            if (imageBytesCache.get(path) != null) return@forEach
            viewModelScope.launch { runCatching { loadImageBytes(path, imageSiblingsLocal) } }
        }
    }

    /** Kaydırma oturduğunda: başlık/paylaş/indir hep görünen sayfayı göstersin. */
    fun showImageAt(index: Int) {
        val path = imageSiblings.getOrNull(index) ?: return
        if (path == _openedImage.value.path) return
        openImage(path, imageSiblingsLocal, imageSiblingsAttachment)
    }

    /**
     * Görseli dahili görüntüleyicide açar. [local] false ise byte'lar köprüden
     * (/download), true ise doğrudan telefonun diskinden gelir. Ham byte
     * saklanır (bitmap değil) ki paylaşım orijinal biçimi korusun; çözme işi
     * görüntüleyici composable'ındadır.
     */
    fun openImage(path: String, local: Boolean = false, attachment: Boolean = false): Job = viewModelScope.launch {
        val generation = ++imageOpenGeneration
        clearViewersExcept(ViewerKind.IMAGE)
        openedFilePath = path
        openedFileCoworkOnly = false
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val index = imageSiblings.indexOf(path)
        val total = if (index >= 0) imageSiblings.size else 0
        fun state(bytes: ByteArray? = null, error: String = "", loading: Boolean = false) = ImageViewerState(
            loading = loading, name = name, path = path, bytes = bytes, error = error,
            local = local, index = index, total = total, attachment = attachment,
            shuffled = imageNavigationOrder.shuffled,
        )
        _openedImage.value = state(loading = true)
        val result = runCatching { loadImageBytes(path, local) }
        if (generation != imageOpenGeneration) return@launch
        _openedImage.value = result.fold(
            onSuccess = { state(bytes = it) },
            onFailure = { state(error = it.message ?: "Görsel açılamadı") },
        )
    }

    private suspend fun downloadImageBytes(path: String): ByteArray =
        client.downloadFile(_uiState.value.settings, path) { }.use { response ->
            val body = response.body ?: error("Boş dosya yanıtı")
            val announced = body.contentLength()
            require(announced < 0 || announced <= MAX_IMAGE_BYTES) { "Görsel 60MB sınırını aşıyor" }
            val bytes = body.bytes()
            require(bytes.size <= MAX_IMAGE_BYTES) { "Görsel 60MB sınırını aşıyor" }
            bytes
        }

    private fun readLocalImageBytes(path: String): ByteArray {
        val file = java.io.File(path)
        require(file.isFile) { "Dosya bulunamadı" }
        require(file.length() <= MAX_IMAGE_BYTES) { "Görsel 60MB sınırını aşıyor" }
        return file.readBytes()
    }

    /**
     * Görüntüleyicideki görseli siler ve KAPANMADAN komşusuna geçer. Silinen
     * sayfanın yerine bir sonraki kayar; silinen sonuncuysa bir öncekine
     * düşülür. Gezgine dönmek yanlıştı: eleme genelde arka arkaya yapılıyor ve
     * her silmede görüntüleyiciden düşmek akışı kesiyordu.
     *
     * Liste İYİMSER güncellenir — köprüden cevap beklemek parmağın altında
     * donmuş bir ekran demek. Silme düşerse gezgin tazelemesi dosyayı geri
     * getirir ve hata mesajı zaten basılır.
     *
     * @return görüntüleyici açık kalabilir mi; false ise gidilecek komşu yok.
     */
    fun deleteOpenedImage(): Boolean {
        val current = _openedImage.value
        val path = current.path
        if (path.isBlank()) return false
        if (current.local) deletePhoneEntries(listOf(path)) else deleteBrowserEntry(path)
        // Gönderilmeyi bekleyen bir ekse composer'dan da düşsün: dosya artık
        // yok, çipte durursa mesaj olmayan bir yolu modele gönderirdi.
        _uiState.update { state ->
            if (state.attachments.none { it.path == path }) state
            else state.copy(attachments = state.attachments.filterNot { it.path == path })
        }
        val index = imageSiblings.indexOf(path)
        // Tekil açılmış görsel (gezinme listesi yok): geçilecek komşu da yok.
        if (index < 0) return false
        imageNavigationOrder.remove(path)
        val remaining = imageSiblings
        imageBytesCache.remove(path)
        val next = remaining.getOrNull(index) ?: remaining.lastOrNull() ?: return false
        openImage(next, current.local, imageSiblingsAttachment)
        return true
    }

    /** Klasördeki önceki (-1) / sonraki (+1) görsele geçer; kenardaysa hiçbir şey yapmaz. */
    fun showAdjacentImage(delta: Int) {
        val state = _openedImage.value
        val next = adjacentImageIndex(state.index, state.total, delta) ?: return
        val path = imageSiblings.getOrNull(next) ?: return
        openImage(path, imageSiblingsLocal, imageSiblingsAttachment)
    }

    /**
     * Açık görseli cihazdaki başka bir uygulamada açar (galeri, düzenleyici).
     * Telefondaki dosya doğrudan FileProvider ile gider; PC'deki dosya önce
     * indirilir — harici uygulama akışı zaten o yolu kullanıyor.
     */
    fun openOpenedImageExternally() {
        val state = _openedImage.value
        if (state.path.isBlank()) return
        if (!state.local) {
            openEntryWithExternalApp(DirEntry(name = state.name, path = state.path, type = "file"))
            return
        }
        val intent = phoneFileViewIntent(state.path)
        if (intent == null) {
            notifyUser("Dosya açılamadı")
            return
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { notifyUser("Bu dosyayı açabilecek uygulama bulunamadı") }
    }

    fun shareOpenedImage(): Job = viewModelScope.launch {
        val state = _openedImage.value
        val bytes = state.bytes ?: return@launch
        shareBytes(state.name.ifBlank { "gorsel" }, imageMimeType(state.name), bytes)
    }

    fun shareOpenedDocx(): Job = viewModelScope.launch {
        val pkg = openedDocxPackage ?: return@launch
        val state = _openedDocx.value
        val name = sharedDocumentName(state.name)
        // TÜR DE ADDAN GELİR. Sabit DOCX_MIME yazılıydı; UDF bir ZIP kabı,
        // OOXML belgesi değil — .udf'yi Word tipiyle paylaşmak hem seçicide
        // yanlış uygulamaları listeler hem de alıcıya yanlış şey söyler.
        // Değerin kendisi uydurulmuyor, FileMime'daki ÖLÇÜLMÜŞ eşlemeden
        // geliyor (hedef uygulamaların intent filtreleri okunarak seçilmişti).
        val mime = if (isUdfFile(name)) mimeTypeForExtension("udf") else DOCX_MIME
        runCatching { withContext(Dispatchers.Default) { pkg.toBytes() } }
            .onSuccess { shareBytes(name, mime, it) }
            .onFailure { _messages.emit("DOCX paylaşıma hazırlanamadı: ${it.message ?: "bilinmeyen hata"}") }
    }

    private suspend fun shareBytes(name: String, mimeType: String, bytes: ByteArray) {
        runCatching {
            withContext(Dispatchers.IO) {
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(160).ifBlank { "belge" }
                val root = java.io.File(getApplication<Application>().cacheDir, "share").apply { mkdirs() }
                java.io.File(root, safeName).also { it.writeBytes(bytes) }
            }
        }.onSuccess { file ->
            _shareFileEvents.send(ShareFileRequest(file.absolutePath, file.name, mimeType))
        }.onFailure {
            _messages.emit("Dosya paylaşıma hazırlanamadı: ${it.message ?: "bilinmeyen hata"}")
        }
    }

    private suspend fun loadOpenedMarkdownContent(opened: FileResult, draft: String): String {
        if (!opened.truncated) return draft
        val path = openedFilePath.takeIf { it.isNotBlank() } ?: opened.path.ifBlank { opened.name }
        return withContext(Dispatchers.IO) {
            client.downloadFile(_uiState.value.settings, path) { }.use { response ->
                val body = response.body ?: error("Boş dosya yanıtı")
                val announced = body.contentLength()
                require(announced < 0 || announced <= CoworkFileEditDelegate.MAX_EDIT_BYTES) {
                    "Markdown 25MB sınırını aşıyor"
                }
                val bytes = body.bytes()
                require(bytes.size.toLong() <= CoworkFileEditDelegate.MAX_EDIT_BYTES) {
                    "Markdown 25MB sınırını aşıyor"
                }
                bytes.toString(Charsets.UTF_8)
            }
        }
    }

    /**
     * @param announce Snackbar çıksın mı. Otomatik kayıt (her ~900 ms'de bir)
     *   `false` geçer: durum zaten sessizce görünüyor — editörde "Kaydediliyor…"
     *   düğmesi, not ekranında başlık altı "PC'ye kaydedildi". Her tuş vuruşunda
     *   balon çıkarmak yazmayı boğuyordu. Elle kayıt ve editörden çıkış hâlâ
     *   bildirir; oralarda kullanıcı ekranı terk ediyor ve tek bir onay yararlı.
     */
    fun saveOpenedMarkdown(
        content: String,
        overwriteConflict: Boolean = false,
        announce: Boolean = true,
    ): Job = viewModelScope.launch {
        markdownSaveMutex.withLock {
            val opened = _openedFile.value ?: return@launch
            val path = openedFilePath.takeIf { it.isNotBlank() } ?: opened.path.ifBlank { opened.name }
            if (path.isBlank()) return@launch
            if (opened.truncated) {
                _markdownSaveState.value = MarkdownSaveState(error = "Kısaltılmış dosya düzenlenemez")
                return@launch
            }
            _markdownSaveState.value = MarkdownSaveState(saving = true)
            runCatching {
                val bytes = content.toByteArray(Charsets.UTF_8)
                if (openedFilePhoneLocal) {
                    // Telefondaki dosya: köprüye hiç gitmez. Çakışma kontrolü de
                    // yok — dosyayı bizden başka değiştiren yok.
                    withContext(Dispatchers.IO) {
                        val f = java.io.File(path)
                        f.writeBytes(bytes)
                        PhoneStorage.notifyMediaScanner(getApplication(), path)
                        FileSaveResult(
                            ok = true, name = f.name, path = path, error = "",
                            size = bytes.size.toLong(), mtime = f.lastModified(),
                        )
                    }
                } else if (openedFileCoworkOnly) {
                    client.saveCoworkFile(_uiState.value.settings, path, bytes).let { result ->
                        FileSaveResult(
                            ok = result.ok,
                            name = result.name,
                            path = result.path,
                            error = result.error,
                            size = bytes.size.toLong(),
                            mtime = System.currentTimeMillis(),
                        )
                    }
                } else {
                    client.saveFileChecked(
                        settings = _uiState.value.settings,
                        path = path,
                        bytes = bytes,
                        expectedHash = if (overwriteConflict) "" else opened.hash,
                    )
                }
            }.onSuccess { result ->
                when {
                    result.ok -> {
                        if (openedFilePath == path) {
                            _openedFile.value = opened.copy(
                                content = content,
                                truncated = false,
                                path = result.path.ifBlank { path },
                                size = result.size,
                                mtime = result.mtime,
                                hash = result.hash.ifBlank { opened.hash },
                                error = "",
                            )
                        }
                        _markdownSaveState.value = MarkdownSaveState(saved = true)
                        if (announce) {
                            _messages.emit(
                                if (openedFilePhoneLocal) "Telefona kaydedildi: ${result.name}"
                                else "PC'ye kaydedildi: ${result.name}",
                            )
                        }
                    }
                    result.conflict -> _markdownSaveState.value = MarkdownSaveState(conflict = true)
                    else -> _markdownSaveState.value = MarkdownSaveState(error = result.error.ifBlank { "Kaydetme başarısız" })
                }
            }.onFailure {
                _markdownSaveState.value = MarkdownSaveState(error = it.message ?: "Bağlantı hatası")
            }
        }
    }

    fun reloadOpenedMarkdown() {
        val path = openedFilePath
        if (path.isNotBlank()) openFile(path, openedFileCoworkOnly)
    }

    fun dismissMarkdownSaveStatus() {
        _markdownSaveState.value = MarkdownSaveState()
    }

    fun loadCoworkNotes(projectPath: String? = null): Job = viewModelScope.launch {
        val scopePath = projectPath.orEmpty()
        val query = _coworkNotesState.value.query
        // Kapsam aynıysa eldeki liste yerinde durur: her tuş vuruşunda liste
        // boşalıp iskelete dönseydi arama yazarken ekran zıplardı.
        _coworkNotesState.value = if (scopePath == _coworkNotesState.value.projectPath) {
            _coworkNotesState.value.copy(
                loading = _coworkNotesState.value.notes.isEmpty(),
                error = "",
            )
        } else {
            CoworkNotesUiState(projectPath = scopePath, query = query, loading = true)
        }
        runCatching { client.coworkNotes(_uiState.value.settings, query) }
            .onSuccess { page ->
                val projectKey = normalizedPathKey(scopePath)
                _coworkNotesState.value = CoworkNotesUiState(
                    projectPath = scopePath,
                    query = query,
                    notes = if (scopePath.isBlank()) page.notes else page.notes.filter {
                        normalizedPathKey(it.project?.path.orEmpty()) == projectKey
                    },
                )
            }
            .onFailure { error ->
                _coworkNotesState.value = CoworkNotesUiState(
                    projectPath = scopePath,
                    query = query,
                    error = error.message ?: "Notlar yüklenemedi",
                )
                reportError("Notlar yüklenemedi", error)
            }
    }

    // Arama kutusu. Her harfte köprüye gitmemek için 250 ms bekletilir; kutu
    // boşalınca beklemeden atılır, tam liste anında geri gelsin.
    private var coworkNoteSearchJob: Job? = null
    fun setCoworkNotesQuery(query: String) {
        if (query == _coworkNotesState.value.query) return
        _coworkNotesState.update { it.copy(query = query) }
        coworkNoteSearchJob?.cancel()
        val scope = _coworkNotesState.value.projectPath.takeIf { it.isNotBlank() }
        coworkNoteSearchJob = viewModelScope.launch {
            if (query.isNotBlank()) delay(250)
            loadCoworkNotes(scope).join()
        }
    }

    // Otomatik not adı: "not001", "not002"… ILK BOŞTAKI numara verilir — not001 ve
    // not003 varsa yeni not not002 olur (kullanıcı kararı). Sayım kapsam içidir:
    // genel notlar `_genel-notlar/`, proje notları `<proje>/notlar/` altında ayrı
    // klasörlerde yaşadığı için aynı ad iki kapsamda çakışmaz.
    private val autoNoteNamePattern = Regex("^not(\\d+)$", RegexOption.IGNORE_CASE)

    private fun nextFreeNoteName(projectPath: String?): String {
        val used = _coworkNotesState.value.notes
            .filter {
                if (projectPath.isNullOrBlank()) it.project == null else it.project?.path == projectPath
            }
            .mapNotNull { autoNoteNamePattern.find(it.title.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .toSet()
        var n = 1
        while (n in used) n++
        return "not%03d".format(n)
    }

    fun createCoworkNote(
        name: String,
        projectPath: String? = null,
        onCreated: (CoworkNote?) -> Unit = {},
    ): Job = viewModelScope.launch {
        // Ad artık zorunlu değil: oluşturma diyaloğunda başlık alanı yok, başlık
        // editörün tepesinden veriliyor. Boş gelirse ilk boştaki numara atanır.
        val clean = name.trim().ifBlank { nextFreeNoteName(projectPath) }
        _coworkNotesState.update { it.copy(creating = true, error = "") }
        runCatching {
            client.coworkCreateNote(
                settings = _uiState.value.settings,
                name = clean,
                projectPath = projectPath?.takeIf { it.isNotBlank() },
            )
        }.onSuccess { note ->
            _coworkNotesState.update { state ->
                state.copy(
                    notes = listOf(note) + state.notes.filterNot { it.id == note.id },
                    creating = false,
                )
            }
            _messages.emit("Not oluşturuldu: ${note.title}")
            onCreated(note)
        }.onFailure { error ->
            _coworkNotesState.update {
                it.copy(creating = false, error = error.message ?: "Not oluşturulamadı")
            }
            reportError("Not oluşturulamadı", error)
            onCreated(null)
        }
    }

    // Hatırlatıcı kur/kaldır (docs/ekran-goruntusu-hatirlatici-plani.md, E5.2).
    // atIso null → kaldır. Zamanlayıcı bridge tarafında; burada yalnız notun
    // frontmatter'ı güncellenip liste tazeleniyor.
    fun setCoworkNoteReminder(note: CoworkNote, atIso: String?): Job = viewModelScope.launch {
        if (note.id.isBlank()) return@launch
        val scopePath = _coworkNotesState.value.projectPath
        _coworkNotesState.update { it.copy(actionNoteId = note.id, error = "") }
        runCatching {
            client.coworkSetNoteReminder(_uiState.value.settings, note.id, atIso)
        }.onSuccess {
            _messages.emit(
                if (atIso.isNullOrBlank()) "Hatırlatıcı kaldırıldı: ${note.title}"
                else "Hatırlatıcı kuruldu: ${note.title}",
            )
            loadCoworkNotes(scopePath.takeIf { it.isNotBlank() })
        }.onFailure { error ->
            _coworkNotesState.update {
                it.copy(actionNoteId = "", error = error.message ?: "Hatırlatıcı ayarlanamadı")
            }
            reportError("Hatırlatıcı ayarlanamadı", error)
        }
    }

    fun attachCoworkNote(note: CoworkNote, projectPath: String): Job = viewModelScope.launch {
        if (note.id.isBlank() || projectPath.isBlank()) return@launch
        val scopePath = _coworkNotesState.value.projectPath
        _coworkNotesState.update { it.copy(actionNoteId = note.id, error = "") }
        runCatching {
            client.coworkAttachNote(_uiState.value.settings, note.id, projectPath)
        }.onSuccess {
            _messages.emit("Not projeye bağlandı: ${note.title}")
            loadCoworkNotes(scopePath.takeIf { it.isNotBlank() })
        }.onFailure { error ->
            _coworkNotesState.update {
                it.copy(actionNoteId = "", error = error.message ?: "Not projeye bağlanamadı")
            }
            reportError("Not projeye bağlanamadı", error)
        }
    }

    // onRenamed: adlandırma sonrası GÜNCEL not — dosya yolları da değişmiş olur.
    // Editör açıkken ad değişirse ekranın elindeki yol bayatlar; çağıran yeni notu
    // alıp yeniden açmalı, yoksa sonraki kayıt artık var olmayan yola gider.
    fun renameCoworkNote(
        note: CoworkNote,
        newName: String,
        onRenamed: (CoworkNote?) -> Unit = {},
    ): Job = viewModelScope.launch {
        val clean = newName.trim()
        if (note.id.isBlank() || clean.isBlank()) {
            onRenamed(null)
            return@launch
        }
        val scopePath = _coworkNotesState.value.projectPath
        _coworkNotesState.update { it.copy(actionNoteId = note.id, error = "") }
        runCatching {
            client.coworkRenameNote(_uiState.value.settings, note.id, clean)
        }.onSuccess { renamed ->
            _messages.emit("Not yeniden adlandırıldı: $clean")
            loadCoworkNotes(scopePath.takeIf { it.isNotBlank() }).join()
            // Kimlik dosya adından türüyor (bkz. note-slug.mjs): ad değişince
            // noteId de DEĞİŞİR. Burada ESKİ kimlikle aranıyordu, sonuç her
            // zaman null oluyordu; editör de taşınmış eski yola yazmaya devam
            // edip "not bulunamadı" ile gövdeyi kaybediyordu. Esas kaynak
            // köprünün döndürdüğü güncel not; liste taraması yalnız aynı notun
            // tazelenmiş halini yakalamak için, tutmazsa dönen nota düşülür.
            onRenamed(
                _coworkNotesState.value.notes.firstOrNull { it.id == renamed.id } ?: renamed,
            )
        }.onFailure { error ->
            _coworkNotesState.update {
                it.copy(actionNoteId = "", error = error.message ?: "Not yeniden adlandırılamadı")
            }
            reportError("Not yeniden adlandırılamadı", error)
            onRenamed(null)
        }
    }

    /**
     * Bekleyen içeriği ÖNCE diske indir, sonra notu yeniden adlandır.
     *
     * Sıra şart: adlandırma dosyayı taşıyor. Kayıt havadayken taşıma olursa
     * kayıt artık var olmayan yola gidip "not bulunamadı" ile düşüyor ve
     * taslak kayboluyor. Ekran ikisini ayrı ayrı tetiklediğinde arada `join()`
     * yoktu, yani sıra yalnız şans eseri tutuyordu.
     *
     * viewModelScope'ta: kullanıcı geri tuşuyla çıkarken de tamamlanmalı,
     * ekranın yaşam döngüsüne bağlanamaz.
     *
     * @param pendingContent Kaydedilmemiş taslak; yoksa null geçilir.
     */
    fun saveThenRenameNote(
        note: CoworkNote,
        newName: String,
        pendingContent: String?,
        onRenamed: (CoworkNote?) -> Unit = {},
    ): Job = viewModelScope.launch {
        if (pendingContent != null) {
            // announce=false: hemen ardından "Not yeniden adlandırıldı" balonu
            // geliyor, iki balon üst üste binmesin.
            saveOpenedMarkdown(pendingContent, announce = false).join()
        }
        renameCoworkNote(note, newName, onRenamed).join()
    }

    fun deleteCoworkNote(note: CoworkNote): Job = viewModelScope.launch {
        if (note.id.isBlank()) return@launch
        val scopePath = _coworkNotesState.value.projectPath
        _coworkNotesState.update { it.copy(actionNoteId = note.id, error = "") }
        runCatching {
            client.coworkDeleteNote(_uiState.value.settings, note.id)
        }.onSuccess {
            _messages.emit("Not silindi: ${note.title}")
            loadCoworkNotes(scopePath.takeIf { it.isNotBlank() })
        }.onFailure { error ->
            _coworkNotesState.update {
                it.copy(actionNoteId = "", error = error.message ?: "Not silinemedi")
            }
            reportError("Not silinemedi", error)
        }
    }

    /**
     * Toplu silme. Köprüde toplu uç YOK — tek notluk `/cowork/note/delete`
     * sırayla çağrılır. Bilinçli: tek kullanıcı ve onlarca değil birkaç not
     * siliniyor; ayrı bir uç köprüyü yeniden başlatmayı gerektirirdi.
     *
     * Sıralı (paralel değil): aynı anda çok istek atmak köprüdeki dosya
     * işlemleriyle yarışır. Bir not patlarsa diğerleri denenmeye devam eder,
     * sonuç özetle bildirilir. Liste yalnız SONDA bir kez tazelenir.
     */
    fun deleteCoworkNotes(notes: List<CoworkNote>): Job = viewModelScope.launch {
        val hedefler = notes.filter { it.id.isNotBlank() }
        if (hedefler.isEmpty()) return@launch
        val scopePath = _coworkNotesState.value.projectPath
        _coworkNotesState.update { it.copy(actionNoteId = hedefler.first().id, error = "") }
        var silinen = 0
        val basarisiz = mutableListOf<String>()
        for (note in hedefler) {
            runCatching { client.coworkDeleteNote(_uiState.value.settings, note.id) }
                .onSuccess { silinen += 1 }
                .onFailure { basarisiz += note.title }
        }
        if (basarisiz.isEmpty()) {
            _messages.emit("$silinen not silindi")
        } else {
            _coworkNotesState.update {
                it.copy(error = "Silinemeyen ${basarisiz.size} not: ${basarisiz.take(3).joinToString(", ")}")
            }
            _messages.emit("$silinen not silindi, ${basarisiz.size} tanesi silinemedi")
        }
        loadCoworkNotes(scopePath.takeIf { it.isNotBlank() })
    }

    fun dismissCoworkNotesError() {
        _coworkNotesState.update { it.copy(error = "") }
    }

    /**
     * Hatırlatıcı bildiriminden gelen not kimliğini açar. Liste elde yoksa
     * (uygulama soğuk açıldıysa) önce çekilir — bildirime dokunmak her koşulda
     * notu getirmeli.
     */
    fun openCoworkNoteById(noteId: String): Job = viewModelScope.launch {
        if (noteId.isBlank()) return@launch
        var note = _coworkNotesState.value.notes.firstOrNull { it.id == noteId }
        if (note == null) {
            loadCoworkNotes(null).join()
            note = _coworkNotesState.value.notes.firstOrNull { it.id == noteId }
        }
        if (note == null) {
            _messages.emit("Hatırlatıcının notu bulunamadı")
            return@launch
        }
        openCoworkNote(note)
        _noteNavigationNonce.update { it + 1 }
    }

    fun openCoworkNote(note: CoworkNote): Job {
        val path = note.mdPath
        if (path.isNullOrBlank()) {
            return viewModelScope.launch { _messages.emit("Not dosyası bulunamadı") }
        }
        openedCoworkNoteId = note.id
        _noteAiState.value = NoteAiUiState()
        return openFile(path, coworkOnly = true)
    }

    fun runNoteAi(action: String): Job = viewModelScope.launch {
        val cleanAction = action.trim().lowercase()
        if (cleanAction !in setOf("ozetle", "formatla", "duzelt")) return@launch
        val noteId = resolveOpenedCoworkNoteId()
        if (noteId.isBlank()) {
            _noteAiState.value = NoteAiUiState(
                error = "Bu not Notlarım listesinden yeniden açılmalı.",
            )
            return@launch
        }
        _noteAiState.value = NoteAiUiState(running = true)
        runCatching {
            client.coworkNoteAi(_uiState.value.settings, noteId, cleanAction)
        }.onSuccess { preview ->
            _noteAiState.value = NoteAiUiState(preview = preview)
        }.onFailure { error ->
            _noteAiState.value = NoteAiUiState(
                error = error.message ?: "AI önerisi oluşturulamadı",
            )
        }
    }

    /**
     * "AI'a sor": açık notu bağlam alan YENİ bir ajan oturumu açar.
     *
     * Not içeriği isteme kopyalanmaz, DOSYA YOLU verilir — ajan dosyayı kendi
     * okur. Böylece bağlam notun o anki hâlidir (kopyalasak bayatlardı) ve uzun
     * bir not istem kutusunu doldurmaz. Çalışma klasörü proje notunda projenin
     * kökü, bağımsız notta notun kendi klasörü: ajan yalnız görmesi gerekeni
     * görsün.
     */
    fun askNoteToAgent(provider: String, onStarted: () -> Unit = {}): Job = viewModelScope.launch {
        val note = _coworkNotesState.value.notes.firstOrNull { it.id == resolveOpenedCoworkNoteId() }
        val notePath = note?.mdPath?.takeIf { it.isNotBlank() }
        if (note == null || notePath == null) {
            _messages.emit("Bu not Notlarım listesinden yeniden açılmalı.")
            return@launch
        }
        val cwd = note.project?.path?.takeIf { it.isNotBlank() }
            ?: notePath.replace('\\', '/').substringBeforeLast('/', "")
        if (cwd.isBlank()) {
            _messages.emit("Notun klasörü bulunamadı")
            return@launch
        }
        val session = _uiState.value.backendSession(provider)
        val model = session.model.ifBlank { session.defaultModel }
        val job = when (provider) {
            "claude-app" -> startClaudeAppSession(cwd, model)
            "codex-app" -> startCodexAppSession(cwd, model)
            "opencode2-app" -> startOpencode2AppSession(cwd, model)
            "omp" -> startOmpSession(cwd, model)
            else -> null
        }
        if (job == null) {
            _messages.emit("Bilinmeyen sağlayıcı: $provider")
            return@launch
        }
        job.join()
        if (_uiState.value.backendSession(provider).sessionId.isBlank()) return@launch
        _uiState.update {
            it.copy(input = "Bağlam olarak şu notu oku: $notePath\n\n")
        }
        onStarted()
    }

    fun saveOpenedMarkdownThenRunNoteAi(content: String, action: String): Job = viewModelScope.launch {
        _noteAiState.value = NoteAiUiState(running = true)
        saveOpenedMarkdown(content).join()
        val saveError = _markdownSaveState.value.error
        if (saveError.isNotBlank() || _markdownSaveState.value.conflict) {
            _noteAiState.value = NoteAiUiState(
                error = saveError.ifBlank {
                    "Dosya bilgisayarda değişmiş; AI işleminden önce kayıt çatışmasını çöz."
                },
            )
            return@launch
        }
        runNoteAi(action).join()
    }

    fun applyNoteAi(): Job = viewModelScope.launch {
        val preview = _noteAiState.value.preview ?: return@launch
        _noteAiState.update { it.copy(applying = true, error = "") }
        runCatching {
            client.coworkApplyNoteAi(_uiState.value.settings, preview)
        }.onSuccess {
            _noteAiState.value = NoteAiUiState()
            openFile(preview.mdPath, coworkOnly = true)
            val projectPath = _coworkNotesState.value.projectPath
            loadCoworkNotes(projectPath.takeIf { it.isNotBlank() })
            _messages.emit("AI önerisi kaydedildi")
        }.onFailure { error ->
            // Kod sayısal okunur: hata metni artık köprünün kendi açıklamasını
            // taşıyor, "HTTP 409" dizgesiyle eşleşmiyor (bkz. BridgeHttpException).
            val conflict = (error as? BridgeHttpException)?.code == 409
            _noteAiState.update {
                it.copy(
                    applying = false,
                    error = if (conflict) {
                        "Not, önizleme açıldıktan sonra değişti. Yeni bir AI önerisi oluştur."
                    } else {
                        error.message ?: "AI önerisi kaydedilemedi"
                    },
                )
            }
        }
    }

    fun dismissNoteAiPreview() {
        if (!_noteAiState.value.applying) _noteAiState.value = NoteAiUiState()
    }

    private fun resolveOpenedCoworkNoteId(): String {
        if (openedCoworkNoteId.isNotBlank()) return openedCoworkNoteId
        val pathKey = normalizedPathKey(openedFilePath)
        return _coworkNotesState.value.notes.firstOrNull { note ->
            note.mdPath?.let { normalizedPathKey(it) == pathKey } == true
        }?.id.orEmpty()
    }

    fun clearCoworkNotes() {
        _coworkNotesState.value = CoworkNotesUiState()
    }

    private fun normalizedPathKey(path: String): String =
        path.trim().replace('\\', '/').trimEnd('/').lowercase()

    /**
     * PDF'i uygulama içinde açar. Uzak dosya ÖNCE diske iner: PdfRenderer akış
     * kabul etmiyor, seekable descriptor istiyor. Bu yüzden büyük UYAP evrakında
     * "aç"tan "ilk sayfa"ya kadar geçen süre indirme süresidir — durumu ilerleme
     * oranıyla gösteriyoruz, sessizce donmuş gibi durmasın.
     */
    fun openPdf(path: String, local: Boolean = false): Job = viewModelScope.launch {
        val generation = ++pdfOpenGeneration
        clearViewersExcept(ViewerKind.PDF)
        releasePdfRenderer() // yeni belge: önceki kaynağı bırak
        openedFilePath = path
        openedFileCoworkOnly = false
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        _openedPdf.value = PdfViewerState(loading = true, name = name, path = path, local = local)
        // clearViewersExcept(PDF) okuma modunu temizlemez (PDF dalını korur);
        // yeni belge eskisinin metniyle açılmasın.
        _pdfRead.value = PdfReadState()
        _pageLang.value = emptyMap()
        // Konum İNDİRMEDEN ÖNCE eşitlenir: pager ilk kompozisyonda kayıtlı
        // sayfayı okuyor, sonra gelen bir düzeltme "açıldı, sonra zıpladı"
        // olurdu. Yerel dosyada atlanır — köprü o dosyayı görmüyor.
        // Zaman aşımı BİLİNÇLİ: köprü ulaşılamazken OkHttp'nin 20 sn'lik bağlantı
        // tavanı belgenin açılışını da o kadar geciktirirdi. Konum eşitlemesi
        // beklemeye değmez — yerel kayıtla açılır.
        if (!local) withTimeoutOrNull(POSITION_SYNC_TIMEOUT_MS) {
            runCatching { syncPdfPositionFromBridge(path) }
        }
        if (generation != pdfOpenGeneration) return@launch
        val result = runCatching {
            val file = if (local) java.io.File(path).also {
                require(it.isFile) { "Dosya bulunamadı" }
            } else downloadPdfToCache(path, generation)
            if (generation != pdfOpenGeneration) return@launch
            val renderer = PdfPageRenderer.open(file, pdfFontFixRequest())
            Triple(file, renderer, renderer.pageCount)
        }
        if (generation != pdfOpenGeneration) {
            result.getOrNull()?.second?.close()
            return@launch
        }
        result.onSuccess { (file, renderer, pages) ->
            openedPdfRenderer = renderer
            _openedPdf.value = PdfViewerState(
                name = name, path = path, localPath = file.absolutePath,
                pageCount = pages, local = local, sizeBytes = file.length(),
            )
        }.onFailure { error ->
            _openedPdf.value = PdfViewerState(
                name = name, path = path, local = local,
                error = error.message ?: "PDF açılamadı",
            )
        }
    }

    /** Görüntüleyicinin sayfa çizmek için kullandığı açık kaynak; kapalıysa null. */
    fun pdfRenderer(): PdfPageRenderer? = openedPdfRenderer

    /**
     * Okuma modunu açar/kapatır. İçerik köprüden gelir ve BELGEYE bağlı önbelleklenir:
     * aynı belgede kapatıp açmak ağa çıkmaz.
     *
     * Telefondaki yerel dosyada çalışmaz — çıkarım köprüde, dosya orada yok.
     * Çağıran tuşu zaten göstermiyor; burada da sessizce durur.
     */
    fun togglePdfReadingMode(): Job = viewModelScope.launch {
        val pdf = _openedPdf.value
        if (pdf.local || pdf.path.isBlank()) return@launch
        if (_pdfRead.value.active) {
            _pdfRead.value = _pdfRead.value.copy(active = false)
            return@launch
        }
        loadPdfReadContentIfNeeded(pdf, wantActive = true)
    }

    /**
     * Arama tuşu için: okuma moduna GEÇMEDEN içeriği arka planda hazırlar.
     * Kullanıcı sayfa görünümündeyken arama yapabilsin diye — okuma moduna hiç
     * girmemiş biri "Ara"ya basınca sessizce okuma moduna atılmamalı.
     */
    fun ensurePdfReadContent(): Job = viewModelScope.launch {
        val pdf = _openedPdf.value
        if (pdf.local || pdf.path.isBlank()) return@launch
        loadPdfReadContentIfNeeded(pdf, wantActive = false)
    }

    /**
     * Okuma modu içeriğini gerekiyorsa yükler; togglePdfReadingMode VE
     * ensurePdfReadContent bu ortak yolu kullanır (kod kopyalanmasın).
     *
     * [wantActive] çağıranın niyetidir, ama ağ çağrısı sürerken araya başka bir
     * niyet girebilir (arama tetikledi, kullanıcı sonra "Okuma modu"na bastı) —
     * bu yüzden sonuç uygulanırken NİYET DEĞİL, o andaki `_pdfRead.value.active`
     * esas alınır.
     */
    private suspend fun loadPdfReadContentIfNeeded(pdf: PdfViewerState, wantActive: Boolean) {
        val current = _pdfRead.value
        // Aynı belgenin içeriği elde ya da zaten yükleniyor: ağa çıkmadan aç /
        // niyeti üstüne bindir.
        if (current.path == pdf.path && (current.pages.isNotEmpty() || current.reason.isNotBlank() || current.loading)) {
            if (wantActive && !current.active) _pdfRead.value = current.copy(active = true)
            return
        }
        val generation = pdfOpenGeneration
        _pdfRead.value = PdfReadState(active = wantActive, loading = true, path = pdf.path)
        val result = runCatching { client.readPdfMarkdown(_uiState.value.settings, pdf.path) }
        if (generation != pdfOpenGeneration) return // belge değişti
        val stillActive = _pdfRead.value.active // ara toggle'ları koru
        result.onSuccess { read ->
            if (!read.ok) {
                _pdfRead.value = PdfReadState(active = stillActive, path = pdf.path, reason = read.reason)
                return@onSuccess
            }
            // Bölme metin uzunluğuyla büyüyor (225 KB'lık kitap ölçüldü); ana iş
            // parçacığında yapılırsa açılışta gözle görülür takılma olur.
            val pages = withContext(Dispatchers.Default) { splitReaderPages(read.markdown, read.pageStarts) }
            _pdfRead.value = PdfReadState(
                active = stillActive, path = pdf.path, pages = pages, pageCount = read.pageCount,
                truncated = read.truncated, tablesTruncated = read.tablesTruncated,
            )
        }.onFailure { error ->
            _pdfRead.value = PdfReadState(
                active = stillActive, path = pdf.path,
                reason = error.message ?: "okuma modu hazırlanamadı",
            )
        }
    }

    /**
     * Belgenin kaydedilmiş son konumu (varsa). SENKRON okunur: pager'ın
     * `initialPage`'i composable'ın İLK kompozisyonunda belli olmalı — StateFlow
     * ile bir tık gecikse pager 0. sayfadan açılıp sonra zıplardı.
     */
    fun pdfResume(path: String): PdfResumePosition? =
        loadPdfResumeCache()[normalizedPathKey(path)]

    /**
     * Kaldığı yeri kaydeder. Yerel (telefon içi) PDF'lerde de çalışır — konum
     * köprü gerektirmiyor, doğrudan SharedPreferences'a yazılıyor. UI yalnız
     * çağırır; LRU budama ve JSON kodlama burada, tek yerde toplanır.
     */
    fun savePdfPosition(path: String, page: Int, ri: Int = 0, ro: Int = 0, mode: String, remote: Boolean = false) {
        if (path.isBlank()) return
        val position = PdfResumePosition(
            page = page, ri = ri, ro = ro, mode = mode, savedAt = System.currentTimeMillis(),
        )
        val updated = putPdfResumePosition(
            map = loadPdfResumeCache(),
            key = normalizedPathKey(path),
            position = position,
        )
        pdfResumeCache = updated
        prefs.edit().putString(PDF_RESUME_KEY, encodePdfResumeMap(updated)).apply()
        // Köprüdeki kopya cihazlar arası ortak kayıt: telefonda kalınan satır
        // tablette de açılsın. Ateşle-unut — başarısızlık okumayı bölmemeli,
        // yerel kayıt zaten yazıldı. Telefon içi dosyada anlamsız (köprü o
        // dosyayı görmüyor), o yüzden çağıran karar veriyor.
        if (!remote) return
        viewModelScope.launch {
            runCatching { client.savePdfPosition(_uiState.value.settings, path, position.toBridge()) }
        }
    }

    /**
     * Köprüdeki konumu yerel kopyanın üstüne alır — ama YALNIZ daha yeniyse.
     *
     * Belge açılırken, ilk sayfa çizilmeden ÖNCE beklenir: pager'ın initialPage'i
     * ilk kompozisyonda belli olmalı. Köprü yanıt vermezse sessizce yerel kayıtla
     * devam edilir; bu durumda belge de zaten inemiyordur.
     */
    private suspend fun syncPdfPositionFromBridge(path: String) {
        val remote = client.pdfPosition(_uiState.value.settings, path) ?: return
        val key = normalizedPathKey(path)
        val map = loadPdfResumeCache()
        val local = map[key]
        if (local != null && local.savedAt >= remote.savedAt) return
        val updated = putPdfResumePosition(map, key, remote.toResume())
        pdfResumeCache = updated
        prefs.edit().putString(PDF_RESUME_KEY, encodePdfResumeMap(updated)).apply()
    }

    private fun PdfResumePosition.toBridge() =
        BridgePdfPosition(page = page, ri = ri, ro = ro, mode = mode, savedAt = savedAt)

    private fun BridgePdfPosition.toResume() =
        PdfResumePosition(page = page, ri = ri, ro = ro, mode = mode, savedAt = savedAt)

    private fun loadPdfResumeCache(): Map<String, PdfResumePosition> =
        pdfResumeCache ?: decodePdfResumeMap(prefs.getString(PDF_RESUME_KEY, null).orEmpty()).also { pdfResumeCache = it }

    /**
     * Bir sayfayı dil ve tutarlılık açısından kontrol ettirir.
     *
     * Sonuç ÖNERİDİR; hiçbir şey metne uygulanmaz. Kontrol yalnız kullanıcı
     * isteyince çalışır — sayfa başına ~14 sn ve ~28 bin token, otomatik
     * çalıştırmak kitabı okunamaz hale getirirdi.
     */
    fun checkPageLanguage(pageNumber: Int, text: String): Job = viewModelScope.launch {
        if (_pageLang.value[pageNumber]?.loading == true) return@launch
        val generation = pdfOpenGeneration
        _pageLang.update { it + (pageNumber to PageLangState(loading = true)) }
        val result = runCatching { client.checkPdfLanguage(_uiState.value.settings, text) }
        if (generation != pdfOpenGeneration) return@launch // belge değişti
        val next = result.fold(
            onSuccess = { r ->
                if (r.ok) PageLangState(done = true, findings = r.bulgular)
                else PageLangState(done = true, reason = r.reason)
            },
            onFailure = { PageLangState(done = true, reason = it.message ?: "kontrol edilemedi") },
        )
        _pageLang.update { it + (pageNumber to next) }
    }

    fun setReaderFontSize(sp: Float) {
        val clamped = sp.coerceIn(READER_FONT_MIN, READER_FONT_MAX)
        _readerFontSize.value = clamped
        prefs.edit().putFloat(READER_FONT_KEY, clamped).apply()
    }

    /**
     * Font gömme için gereken kaynaklar. Yüzler ilk kullanımda okunur; düzeltilmiş
     * kopyalar önbelleğe yazılır, aynı evrak ikinci açılışta hazır bulur.
     */
    private fun pdfFontFixRequest(): PdfPageRenderer.Companion.PdfFontFixRequest {
        val context = getApplication<Application>().applicationContext
        val fonts = pdfFontAssets ?: PdfFontAssets(context).also { pdfFontAssets = it }
        return PdfPageRenderer.Companion.PdfFontFixRequest(fonts, java.io.File(context.cacheDir, "pdf-fontfix"))
    }

    /**
     * XLSX'i uygulama içinde açar (SALT OKUNUR).
     *
     * PDF'teki gibi önce diske iner: baytları bellekte tutmadan hem ayrıştırma
     * hem "paylaş / birlikte aç" tek kopyadan beslenir. Ayrıştırma Default
     * dispatcher'da — 5000 satırlık bir hesap tablosu ana iş parçacığında
     * saniyelerce takılıyordu.
     */
    fun openSheet(path: String, local: Boolean = false): Job = viewModelScope.launch {
        val generation = ++sheetOpenGeneration
        clearViewersExcept(ViewerKind.SHEET)
        openedFilePath = path
        openedFileCoworkOnly = false
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        _openedSheet.value = SheetViewerState(loading = true, name = name, path = path, local = local)
        runCatching {
            val file = if (local) java.io.File(path).also {
                require(it.isFile) { "Dosya bulunamadı" }
            } else downloadSheetToCache(path, generation)
            if (generation != sheetOpenGeneration) return@launch
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            require(bytes.size.toLong() <= MAX_SHEET_BYTES) { "Tablo 25MB sınırını aşıyor" }
            file to withContext(Dispatchers.Default) { XlsxLite.open(bytes) }
        }.onSuccess { (file, workbook) ->
            if (generation != sheetOpenGeneration) return@onSuccess
            _openedSheet.value = SheetViewerState(
                name = name, path = path, localPath = file.absolutePath,
                sheets = workbook.sheets, local = local,
            )
        }.onFailure { error ->
            if (generation != sheetOpenGeneration) return@onFailure
            _openedSheet.value = SheetViewerState(
                name = name, path = path, local = local,
                error = error.message ?: "Tablo açılamadı",
            )
        }
    }

    fun selectSheet(index: Int) {
        _openedSheet.update { if (index in it.sheets.indices) it.copy(selected = index) else it }
    }

    // ── Açık tablo üzerindeki işlemler ──────────────────────────────────────
    // PDF'tekiyle aynı sözleşme: hepsi önbellekteki kopyayı kullanır, hiçbiri
    // PC'ye geri yazmaz (bu sürümde düzenleme yok).

    fun shareOpenedSheet(): Job = viewModelScope.launch {
        val state = _openedSheet.value
        val local = state.localPath.takeIf { it.isNotBlank() } ?: return@launch
        _shareFileEvents.send(ShareFileRequest(local, state.name.ifBlank { "tablo.xlsx" }, XLSX_MIME))
    }

    /** Telefondaki bir tablo uygulamasında aç — düzenleme oraya devredilir. */
    fun openOpenedSheetExternally() {
        val state = _openedSheet.value
        val local = state.localPath.takeIf { it.isNotBlank() } ?: return
        // Geri senkron KAYDI YOK (PDF ile aynı gerekçe): bu bir önbellek kopyası,
        // PC'deki dosyanın çalışma kopyası değil. Dışarıda kaydedilse bile geri
        // yazmayız; "salt okunur kopya" sözü sessizce bozulmasın.
        _openForEditEvents.trySend(OpenForEditRequest(local, state.name.ifBlank { "tablo.xlsx" }))
    }

    fun downloadOpenedSheet() {
        val state = _openedSheet.value
        if (state.local || state.path.isBlank()) return
        downloadByPath(state.path, state.name.ifBlank { "tablo.xlsx" })
    }

    /** Modele sorabilmek için ("bu hesap tablosunda toplam ne kadar?"). */
    fun attachOpenedSheetToChat(): Job = viewModelScope.launch {
        val state = _openedSheet.value
        if (state.local) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { java.io.File(state.path).readBytes() }.getOrDefault(ByteArray(0))
            }
            if (bytes.isEmpty()) { _messages.emit("Tablo okunamadı"); return@launch }
            attachFile(bytes, state.name, mimeType = XLSX_MIME)
        } else {
            attachPcFiles(listOf(DirEntry(name = state.name, path = state.path, type = "file")))
        }
    }

    private suspend fun downloadSheetToCache(path: String, generation: Long): java.io.File =
        downloadToCache(
            path = path, subDir = "sheet", extension = "xlsx",
            maxBytes = MAX_SHEET_BYTES, label = "Tablo",
            cancelled = { generation != sheetOpenGeneration },
        ) { ratio, _ ->
            _openedSheet.update { if (it.loading) it.copy(progress = ratio) else it }
        }

    // ── Açık PDF üzerindeki işlemler ────────────────────────────────────────
    // Hepsi ÖNBELLEKTEKİ kopyayı kullanır: dosya zaten telefonda, yeniden
    // indirmek ya da baytları belleğe almak gereksiz (görsel/DOCX yolları
    // baytlardan gittiği için orada shareBytes gerekiyordu, burada gerekmiyor).
    fun shareOpenedPdf(): Job = viewModelScope.launch {
        val state = _openedPdf.value
        val local = state.localPath.takeIf { it.isNotBlank() } ?: return@launch
        _shareFileEvents.send(ShareFileRequest(local, state.name.ifBlank { "belge.pdf" }, PDF_MIME))
    }

    /** Telefondaki başka bir uygulamada aç (imzalama, form doldurma, yazdırma). */
    fun openOpenedPdfExternally() {
        val state = _openedPdf.value
        val local = state.localPath.takeIf { it.isNotBlank() } ?: return
        // Geri senkron KAYDI YOK, bilinçli: bu önbellek kopyası, PC'deki dosyanın
        // çalışma kopyası değil. Dışarıda düzenlenip kaydedilse bile PC'ye
        // yazmayız — sessiz veri kaybı yerine "salt okunur kopya" sözü tutulur.
        _openForEditEvents.trySend(OpenForEditRequest(local, state.name.ifBlank { "belge.pdf" }))
    }

    /** PC'deki PDF'i telefonun indirilenler klasörüne kaydeder. */
    fun downloadOpenedPdf() {
        val state = _openedPdf.value
        if (state.local || state.path.isBlank()) return
        downloadByPath(state.path, state.name.ifBlank { "belge.pdf" })
    }

    /**
     * Açık PDF'i sohbete ek yapar ki modele sorulabilsin ("bu tensipte süre kaç
     * gün?"). Uzak dosyada AKTARIM YOK: köprü zaten diskinde, yalnız yol eklenir.
     * Telefondaki dosya ise yüklenmek zorunda.
     */
    fun attachOpenedPdfToChat(): Job = viewModelScope.launch {
        val state = _openedPdf.value
        if (state.local) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { java.io.File(state.path).readBytes() }.getOrDefault(ByteArray(0))
            }
            if (bytes.isEmpty()) { _messages.emit("PDF okunamadı"); return@launch }
            attachFile(bytes, state.name, mimeType = PDF_MIME)
        } else {
            attachPcFiles(listOf(DirEntry(name = state.name, path = state.path, type = "file")))
        }
    }

    private fun releasePdfRenderer() {
        openedPdfRenderer?.close()
        openedPdfRenderer = null
    }

    /**
     * Köprüdeki PDF'i önbelleğe indirir. Aynı yol+boyut için tekrar indirmez —
     * aynı evrakı gün içinde defalarca açmak tipik (dilekçe, tensip).
     */
    private suspend fun downloadPdfToCache(path: String, generation: Long): java.io.File =
        downloadToCache(
            path = path, subDir = "pdf", extension = "pdf",
            maxBytes = MAX_PDF_BYTES, label = "PDF",
            // Kitaplar büyük (13 MB tipik) ve önbellek hiç temizlenmiyordu;
            // son 20 belge tutulur (bkz. PdfCacheLimit.kt).
            keepNewest = PDF_CACHE_KEEP,
            cancelled = { generation != pdfOpenGeneration },
        ) { ratio, announced ->
            _openedPdf.update { if (it.loading) it.copy(progress = ratio, sizeBytes = announced) else it }
        }

    /**
     * Köprüdeki dosyayı önbelleğe indirir. Aynı yol+boyut için tekrar indirmez —
     * aynı evrakı gün içinde defalarca açmak tipik (dilekçe, tensip).
     *
     * PDF ve video aynı yolu kullanır: ikisi de belleğe değil diske iner, ikisi
     * de ilerleme çubuğu gösterir ve ikisi de görüntüleyici kapanınca yarıda
     * kesilmelidir. Ayrı kopyalar tutulunca farklılaşıyorlar.
     */
    private suspend fun downloadToCache(
        path: String,
        subDir: String,
        extension: String,
        maxBytes: Long,
        label: String,
        cancelled: () -> Boolean,
        keepNewest: Int = 0,
        onProgress: (ratio: Float, announced: Long) -> Unit,
    ): java.io.File =
        withContext(Dispatchers.IO) {
            val dir = java.io.File(getApplication<Application>().cacheDir, subDir).apply { mkdirs() }
            val target = java.io.File(dir, sha256(path.toByteArray()).take(32) + "." + extension)
            client.downloadFile(_uiState.value.settings, path) { }.use { response ->
                val body = response.body ?: error("Boş dosya yanıtı")
                val announced = body.contentLength()
                require(announced <= maxBytes) { "$label ${maxBytes / (1024 * 1024)}MB sınırını aşıyor" }
                // Önbellekte aynı boyutta kopya varsa indirme yok.
                if (announced > 0 && target.isFile && target.length() == announced) {
                    // Damga tazelenir: budama "son KULLANIM"a bakıyor, indirme
                    // zamanına değil — her gün açılan kitap eskimiş sayılmasın.
                    target.setLastModified(System.currentTimeMillis())
                    return@use
                }
                var written = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) return@use // görüntüleyici kapandı
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            require(written <= maxBytes) { "$label ${maxBytes / (1024 * 1024)}MB sınırını aşıyor" }
                            if (announced > 0) onProgress(written.toFloat() / announced.toFloat(), announced)
                        }
                    }
                }
            }
            pruneCacheDir(dir, keepNewest, target)
            target
        }

    /**
     * Önbellek klasörünü [keep] en son kullanılan dosyaya indirir. [protect] her
     * durumda kalır — az önce indirilen belge budamaya kurban gitmesin.
     *
     * Silememek sorun değil: bir sonraki açılışta yeniden denenir.
     */
    private fun pruneCacheDir(dir: java.io.File, keep: Int, protect: java.io.File) {
        if (keep <= 0) return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val evict = cacheFilesToEvict(files.map { CacheEntry(it.name, it.lastModified()) }, keep).toSet()
        files.forEach { if (it.name in evict && it.absolutePath != protect.absolutePath) runCatching { it.delete() } }
    }

    // ── Uygulama içi video oynatıcı ─────────────────────────────────────────
    fun openVideo(path: String, local: Boolean = false): Job = viewModelScope.launch {
        val generation = ++videoOpenGeneration
        clearViewersExcept(ViewerKind.VIDEO)
        openedFilePath = path
        openedFileCoworkOnly = false
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        _openedVideo.value = VideoViewerState(loading = true, name = name, path = path, local = local)
        val result = runCatching {
            if (local) java.io.File(path).also { require(it.isFile) { "Dosya bulunamadı" } }
            else downloadToCache(
                path = path, subDir = "video",
                extension = name.substringAfterLast('.', "mp4").lowercase(),
                maxBytes = MAX_VIDEO_BYTES, label = "Video",
                cancelled = { generation != videoOpenGeneration },
            ) { ratio, announced ->
                _openedVideo.update { if (it.loading) it.copy(progress = ratio, sizeBytes = announced) else it }
            }
        }
        if (generation != videoOpenGeneration) return@launch
        result.onSuccess { file ->
            _openedVideo.value = VideoViewerState(
                name = name, path = path, localPath = file.absolutePath,
                local = local, sizeBytes = file.length(),
            )
        }.onFailure { error ->
            _openedVideo.value = VideoViewerState(
                name = name, path = path, local = local,
                error = error.message ?: "Video açılamadı",
            )
        }
    }

    fun shareOpenedVideo(): Job = viewModelScope.launch {
        val state = _openedVideo.value
        val local = state.localPath.takeIf { it.isNotBlank() } ?: return@launch
        val name = state.name.ifBlank { "video.mp4" }
        _shareFileEvents.send(ShareFileRequest(local, name, videoMimeType(name)))
    }

    /** Telefondaki başka bir oynatıcıda/düzenleyicide aç. */
    fun openOpenedVideoExternally() {
        val state = _openedVideo.value
        val local = state.localPath.takeIf { it.isNotBlank() } ?: return
        _openForEditEvents.trySend(OpenForEditRequest(local, state.name.ifBlank { "video.mp4" }))
    }

    /** PC'deki videoyu telefonun indirilenler klasörüne kaydeder. */
    fun downloadOpenedVideo() {
        val state = _openedVideo.value
        if (state.local || state.path.isBlank()) return
        downloadByPath(state.path, state.name.ifBlank { "video.mp4" })
    }

    /**
     * TELEFONDAKİ bir belgeyi (DOCX/UDF) uygulamanın kendi düzenleyicisinde
     * açar. [openDocx]'ten tek farkı baytların köprüden değil yerel dosyadan
     * gelmesi; gerisi — paket seçimi, imza, salt okunurluk — aynı.
     *
     * Bu yol dışarıdan açılan belgeler için var: dosya yöneticisi, WhatsApp,
     * e-posta (bkz. MainActivity.handleViewDocumentIntent ve manifestteki VIEW
     * filtreleri). Eskiden telefondaki .udf/.docx harici uygulamaya
     * devrediliyordu, çünkü düzenleyici yalnız köprüdeki kopya üzerinden
     * çalışıyordu.
     */
    fun openPhoneDocx(path: String): Job = viewModelScope.launch {
        val generation = ++docxOpenGeneration
        openedFilePath = path
        openedFileCoworkOnly = false
        openedDocxCoworkOnly = false
        openedDocxPhoneLocal = true
        clearViewersExcept(ViewerKind.DOCX)
        openedDocxPackage = null
        val file = java.io.File(path)
        _openedDocx.value = DocxEditorState(loading = true, name = file.name, path = path, phoneLocal = true)
        runCatching {
            val bytes = withContext(Dispatchers.IO) {
                require(file.length() <= CoworkFileEditDelegate.MAX_EDIT_BYTES) { "Belge 25MB sınırını aşıyor" }
                file.readBytes()
            }
            val pkg: EditableDocumentPackage = withContext(Dispatchers.Default) {
                if (isUdfFile(path)) UdfPackage.open(bytes) else DocxLitePackage.open(bytes)
            }
            Triple(pkg, sha256(bytes), pkg.blocks())
        }.onSuccess { (pkg, hash, blocks) ->
            if (generation != docxOpenGeneration) return@onSuccess
            openedDocxPackage = pkg
            _openedDocx.value = DocxEditorState(
                name = file.name,
                path = path,
                blocks = blocks,
                hash = hash,
                signed = pkg.signed,
                readOnly = pkg.readOnly,
                signers = (pkg as? UdfPackage)?.let { udfSignerNames(it.document.signatureBytes) }.orEmpty(),
                udfDocument = (pkg as? UdfPackage)?.document,
                phoneLocal = true,
            )
        }.onFailure { error ->
            if (generation != docxOpenGeneration) return@onFailure
            _openedDocx.value = DocxEditorState(
                name = file.name,
                path = path,
                error = error.message ?: "Belge açılamadı",
                phoneLocal = true,
            )
        }
    }

    fun openDocx(path: String, coworkOnly: Boolean = false): Job = viewModelScope.launch {
        val generation = ++docxOpenGeneration
        openedFilePath = path
        openedFileCoworkOnly = coworkOnly
        openedDocxCoworkOnly = coworkOnly
        openedDocxPhoneLocal = false
        clearViewersExcept(ViewerKind.DOCX)
        openedDocxPackage = null
        _openedDocx.value = DocxEditorState(
            loading = true,
            name = path.substringAfterLast('/').substringAfterLast('\\'),
            path = path,
        )
        runCatching {
            val bytes = withContext(Dispatchers.IO) {
                client.downloadFile(_uiState.value.settings, path) { }.use { response ->
                    val announced = response.body?.contentLength() ?: -1
                    require(announced < 0 || announced <= CoworkFileEditDelegate.MAX_EDIT_BYTES) { "DOCX 25MB sınırını aşıyor" }
                    response.body?.bytes() ?: ByteArray(0)
                }
            }
            require(bytes.size.toLong() <= CoworkFileEditDelegate.MAX_EDIT_BYTES) { "DOCX 25MB sınırını aşıyor" }
            // DOCX ve UDF aynı düzenleyici kabuğunu besler; paket türünü uzantı
            // seçer. UDF yazdırma düzeninde SATIR düzeyinde düzenlenir (blok
            // listesinden değil), imzalıysa salt okunur — gerekçe UdfPackage'da.
            val pkg: EditableDocumentPackage = withContext(Dispatchers.Default) {
                if (isUdfFile(path)) UdfPackage.open(bytes) else DocxLitePackage.open(bytes)
            }
            Triple(pkg, sha256(bytes), pkg.blocks())
        }.onSuccess { (pkg, hash, blocks) ->
            if (generation != docxOpenGeneration) return@onSuccess
            openedDocxPackage = pkg
            _openedDocx.value = DocxEditorState(
                name = path.substringAfterLast('/').substringAfterLast('\\'),
                path = path,
                blocks = blocks,
                hash = hash,
                signed = pkg.signed,
                readOnly = pkg.readOnly,
                signers = (pkg as? UdfPackage)?.let { udfSignerNames(it.document.signatureBytes) }.orEmpty(),
                udfDocument = (pkg as? UdfPackage)?.document,
            )
        }.onFailure { error ->
            if (generation != docxOpenGeneration) return@onFailure
            _openedDocx.value = DocxEditorState(
                name = path.substringAfterLast('/').substringAfterLast('\\'),
                path = path,
                error = error.message ?: "DOCX açılamadı",
            )
        }
    }

    fun updateDocxParagraphText(id: String, text: String) {
        mutateDocxBlock(id) { block ->
            block.copy(text = text, spans = adjustDocxSpansForTextChange(block, text))
        }
    }

    /**
     * UDF'te yazdırma düzeninden bir satırı yeniden yazar. Blok editörü yolundan
     * ([mutateDocxBlock]) ayrıdır: birim karta karşılık gelen paragraf değil,
     * kullanıcının dokunduğu SATIR; ve değişiklik belgenin gövdesine uygulanıp
     * gösterim blokları oradan yeniden türetiliyor.
     */
    fun updateUdfLine(line: UdfLineRef, text: String) {
        val pkg = openedDocxPackage as? UdfPackage ?: return
        runCatching { pkg.replaceLine(line, text) }
            .onSuccess { changed ->
                if (!changed) return@onSuccess
                _openedDocx.update { state ->
                    state.copy(
                        blocks = pkg.blocks(),
                        udfDocument = pkg.document,
                        dirty = true,
                        saved = false,
                        conflict = false,
                        error = "",
                    )
                }
            }
            .onFailure { error -> _openedDocx.update { it.copy(error = error.message ?: "Satır güncellenemedi") } }
    }

    fun formatUdfSelection(line: UdfLineRef, start: Int, end: Int, format: String) {
        mutateUdf { it.toggleLineStyle(line, start, end, format) }
    }

    fun setUdfSelectionFontSize(line: UdfLineRef, start: Int, end: Int, size: Int) {
        mutateUdf { it.setLineFontSize(line, start, end, size) }
    }

    fun setUdfParagraphAlignment(line: UdfLineRef, alignment: Int) {
        mutateUdf { it.setLineAlignment(line, alignment) }
    }

    private fun mutateUdf(change: (UdfPackage) -> Boolean) {
        val pkg = openedDocxPackage as? UdfPackage ?: return
        runCatching { change(pkg) }
            .onSuccess { changed ->
                if (!changed) return@onSuccess
                _openedDocx.update { state ->
                    state.copy(
                        blocks = pkg.blocks(),
                        udfDocument = pkg.document,
                        dirty = true,
                        saved = false,
                        conflict = false,
                        error = "",
                    )
                }
            }
            .onFailure { error -> _openedDocx.update { it.copy(error = error.message ?: "Biçim uygulanamadı") } }
    }

    fun setDocxParagraphAlignment(id: String, alignment: String) {
        mutateDocxBlock(id) { it.copy(alignment = alignment) }
    }

    fun formatDocxSelection(id: String, start: Int, end: Int, format: String) {
        mutateDocxBlock(id) { block ->
            val enable = !docxSelectionAll(block, start, end) { style ->
                when (format) {
                    "bold" -> style.bold
                    "italic" -> style.italic
                    "underline" -> style.underline
                    else -> true
                }
            }
            applyDocxStyle(block, start, end) { style ->
                when (format) {
                    "bold" -> style.copy(bold = enable)
                    "italic" -> style.copy(italic = enable)
                    "underline" -> style.copy(underline = enable)
                    else -> style
                }
            }
        }
    }

    fun setDocxSelectionFont(id: String, start: Int, end: Int, font: String) {
        mutateDocxBlock(id) { block -> applyDocxStyle(block, start, end) { it.copy(font = font) } }
    }

    fun setAllDocxFont(font: String) {
        val pkg = openedDocxPackage ?: return
        runCatching { pkg.applyFontToAll(font) }
            .onSuccess {
                _openedDocx.update { it.copy(blocks = pkg.blocks(), dirty = true, saved = false, error = "") }
            }
            .onFailure { error -> _openedDocx.update { it.copy(error = error.message ?: "Font uygulanamadı") } }
    }

    private fun mutateDocxBlock(id: String, transform: (DocxBlock) -> DocxBlock) {
        val pkg = openedDocxPackage ?: return
        val current = _openedDocx.value.blocks.firstOrNull { it.id == id && it.editable } ?: return
        val changed = transform(current)
        if (changed == current) return
        runCatching { pkg.updateParagraph(changed) }
            .onSuccess {
                _openedDocx.update { state ->
                    state.copy(
                        blocks = state.blocks.map { if (it.id == id) changed else it },
                        dirty = true,
                        saved = false,
                        conflict = false,
                        error = "",
                    )
                }
            }
            .onFailure { error -> _openedDocx.update { it.copy(error = error.message ?: "Paragraf güncellenemedi") } }
    }

    fun saveOpenedDocx(overwriteConflict: Boolean = false): Job = viewModelScope.launch {
        val pkg = openedDocxPackage ?: return@launch
        val state = _openedDocx.value
        if (state.path.isBlank() || state.saving) return@launch
        // Salt okunur paket (ör. imzalı UDF) kaydedilmez: imzalı içeriği yeniden
        // yazmak en iyi ihtimalle gereksiz, en kötü ihtimalle imzayı geçersizdir.
        if (pkg.readOnly) return@launch
        _openedDocx.value = state.copy(saving = true, saved = false, conflict = false, error = "")
        // Telefondan açılan belge telefona geri yazılır; köprüye gönderilmez.
        // Gönderilseydi `state.path` bir PC yolu sanılıp alakasız bir yere
        // (ya da hiçbir yere) yazılırdı.
        if (openedDocxPhoneLocal) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = java.io.File(state.path)
                    // Çakışma denetimi köprüdekiyle aynı sözleşme: biz açtıktan
                    // sonra dosya başka bir yerde değiştiyse üzerine yazmayı
                    // kullanıcı onaylasın.
                    val diskHash = if (file.isFile) sha256(file.readBytes()) else ""
                    if (!overwriteConflict && diskHash.isNotBlank() && diskHash != state.hash) {
                        return@withContext null
                    }
                    val bytes = pkg.toBytes()
                    file.writeBytes(bytes)
                    PhoneStorage.notifyMediaScanner(getApplication(), state.path)
                    sha256(bytes)
                }
            }.onSuccess { yeniHash ->
                if (yeniHash == null) {
                    _openedDocx.update { it.copy(saving = false, conflict = true) }
                } else {
                    _openedDocx.update {
                        it.copy(hash = yeniHash, dirty = false, saving = false, saved = true, conflict = false, error = "")
                    }
                    _messages.emit("Telefona kaydedildi: ${state.name}")
                }
            }.onFailure { error ->
                _openedDocx.update { it.copy(saving = false, error = error.message ?: "Telefona kaydedilemedi") }
            }
            return@launch
        }
        runCatching {
            val bytes = withContext(Dispatchers.Default) { pkg.toBytes() }
            client.saveFileChecked(
                settings = _uiState.value.settings,
                path = state.path,
                bytes = bytes,
                expectedHash = if (overwriteConflict) "" else state.hash,
            )
        }.onSuccess { result ->
            when {
                result.ok -> {
                    _openedDocx.update {
                        it.copy(
                            hash = result.hash,
                            dirty = false,
                            saving = false,
                            saved = true,
                            conflict = false,
                            error = "",
                        )
                    }
                    _messages.emit("PC'ye kaydedildi: ${result.name}")
                }
                result.conflict -> _openedDocx.update { it.copy(saving = false, conflict = true) }
                else -> _openedDocx.update { it.copy(saving = false, error = result.error.ifBlank { "DOCX kaydedilemedi" }) }
            }
        }.onFailure { error ->
            _openedDocx.update { it.copy(saving = false, error = error.message ?: "Bağlantı hatası") }
        }
    }

    fun mobilImzaAc() {
        _mobilImza.value = MobilImzaDurumu.Form()
    }

    /**
     * Son kullanılan mobil imza numarası. Her imzada 11 haneyi yeniden yazmak
     * gereksiz; numara cihazın kendi tercihlerinde kalır, hiçbir yere gitmez.
     */
    fun mobilImzaTelefon(): String = prefs.getString(PREF_MIMZA_TEL, "").orEmpty()
    fun mobilImzaOperator(): String = prefs.getString(PREF_MIMZA_OP, "").orEmpty()

    fun mobilImzaKapat() {
        // İş İPTAL EDİLİR: pencere kapandıktan sonra dönen bir imza, kullanıcının
        // vazgeçtiği belgeyi sessizce imzalayıp diske yazardı.
        mobilImzaIsi?.cancel()
        mobilImzaIsi = null
        _mobilImza.value = MobilImzaDurumu.Kapali
    }

    /**
     * UYAP mobil imzasıyla açık UDF'i imzalar ve imzalı hâlini kaydeder.
     *
     * Adımlar UDF Editor Pro'daki akışın aynısı, çünkü imzalanan ŞEY orada
     * ölçülü: belgenin imzasız, yeniden serileştirilmiş TAM baytları
     * ([UdfPackage.unsignedBytes]) — content.xml değil, dosyanın kendisi.
     * Sonuç `sign.sgn` olarak aynı içeriğin üstüne gömülüyor.
     *
     * `getSignature` TEK sefer çağrılır, döngüyle değil: geçit kullanıcı PIN'i
     * girene kadar isteği açık tutuyor (180 sn) ve tekrar tekrar çağırmak
     * kullanıcının telefonuna üst üste imza isteği düşürür.
     *
     * Editor Pro imzadan önce belgeyi kaydediyor; burada gerek yok, çünkü
     * imzalı baytları zaten biz yazıyoruz ve o yazma kaydedilmemiş
     * düzenlemeleri de içeriyor.
     */
    fun mobilImzaBaslat(telNo: String, operator: String) {
        val girdiHatasi = mobilImzaGirdiHatasi(telNo, operator)
        if (girdiHatasi != null) {
            _mobilImza.value = MobilImzaDurumu.Form(girdiHatasi)
            return
        }
        val pkg = openedDocxPackage as? UdfPackage
        if (pkg == null) {
            _mobilImza.value = MobilImzaDurumu.Hata("Mobil imza yalnız UDF belgelerinde kullanılabilir.")
            return
        }
        if (pkg.signed) {
            _mobilImza.value = MobilImzaDurumu.Hata("Belge zaten imzalı.")
            return
        }
        val durum = _openedDocx.value
        if (durum.path.isBlank()) {
            _mobilImza.value = MobilImzaDurumu.Hata("Belgenin kaydedileceği yol bilinmiyor.")
            return
        }
        prefs.edit().putString(PREF_MIMZA_TEL, telNo).putString(PREF_MIMZA_OP, operator).apply()
        mobilImzaIsi?.cancel()
        mobilImzaIsi = viewModelScope.launch {
            _mobilImza.value = MobilImzaDurumu.HashIsteniyor
            val imzalanacak = runCatching { withContext(Dispatchers.Default) { pkg.unsignedBytes() } }
                .getOrElse {
                    _mobilImza.value = MobilImzaDurumu.Hata("Belge imzaya hazırlanamadı: ${it.message ?: "bilinmeyen hata"}")
                    return@launch
                }
            val hash = MobilImzaManager.getHash(imzalanacak, telNo, operator)
            if (hash.resultCode != MobilImzaManager.RESULT_SUCCESS || hash.apTransId.isBlank()) {
                _mobilImza.value = MobilImzaDurumu.Hata(
                    hash.message.ifBlank { "UYAP mobil imza geçidine ulaşılamadı." }
                )
                return@launch
            }
            _mobilImza.value = MobilImzaDurumu.OnayBekleniyor(hash.fingerPrint, hash.apTransId)

            val imza = MobilImzaManager.getSignature(hash.apTransId)
            val baytlar = imza.signatureBytes
            if (imza.resultCode != MobilImzaManager.RESULT_SUCCESS || baytlar == null) {
                _mobilImza.value = MobilImzaDurumu.Hata(
                    imza.message.ifBlank { "İmza tamamlanmadı." }
                )
                return@launch
            }
            val imzali = runCatching { withContext(Dispatchers.Default) { pkg.signedBytes(baytlar) } }
                .getOrElse {
                    _mobilImza.value = MobilImzaDurumu.Hata("İmza belgeye gömülemedi: ${it.message ?: "bilinmeyen hata"}")
                    return@launch
                }
            val yazmaHatasi = imzaliBelgeyiYaz(durum, imzali)
            if (yazmaHatasi != null) {
                // İmza ALINDI ama yazılamadı. Bunu "hata" diye geçiştirmek
                // kullanıcının imzasını çöpe atmak olurdu; ne olduğu açıkça
                // söyleniyor ki yeniden imzalamayı kendi seçsin.
                _mobilImza.value = MobilImzaDurumu.Hata("İmza alındı ama belge kaydedilemedi: $yazmaHatasi")
                return@launch
            }
            // Paket yeniden açılır: imzalı/salt okunur alanları kuruluşta
            // sabitleniyor, aynı nesne üzerinde tazelenemez.
            if (durum.phoneLocal) openPhoneDocx(durum.path) else openDocx(durum.path, openedDocxCoworkOnly)
            _mobilImza.value = MobilImzaDurumu.Tamam("Belge imzalandı ve kaydedildi.")
        }
    }

    /** İmzalı baytları belgenin geldiği yere yazar; hata mesajı ya da null. */
    private suspend fun imzaliBelgeyiYaz(durum: DocxEditorState, baytlar: ByteArray): String? {
        if (durum.phoneLocal) {
            return runCatching {
                withContext(Dispatchers.IO) {
                    java.io.File(durum.path).writeBytes(baytlar)
                    PhoneStorage.notifyMediaScanner(getApplication(), durum.path)
                }
                null
            }.getOrElse { it.message ?: "telefona yazılamadı" }
        }
        return runCatching {
            val sonuc = client.saveFileChecked(
                settings = _uiState.value.settings,
                path = durum.path,
                bytes = baytlar,
                // Çakışma denetimi YOK: imza zaten alındı, burada durup
                // kullanıcıya soru sormak imzayı kaybettirir.
                expectedHash = "",
            )
            if (sonuc.ok) null else sonuc.error.ifBlank { "PC'ye yazılamadı" }
        }.getOrElse { it.message ?: "PC'ye yazılamadı" }
    }

    fun reloadOpenedDocx() {
        val state = _openedDocx.value
        if (state.path.isBlank()) return
        if (openedDocxPhoneLocal) openPhoneDocx(state.path) else openDocx(state.path, openedDocxCoworkOnly)
    }

    /**
     * Açık belgeyi harici düzenleyiciye devreder (kalem tuşu). UDF'te bu
     * UDF Editor Pro'dur — `.udf` MIME çözümü FileMime'da ölçülerek yazıldı.
     *
     * Yerel kopya uygulama-özel `openedit/` klasörüne iner (kullanıcı kararı
     * 06.08.2026): Editor Pro'nun manifestinde depolama izni YOK, ham yolu
     * zaten okuyamaz — content:// URI ile çalışmak zorunda. Karşılığında
     * openForEdit'in temel-hash takibi ve uygulamaya dönüşte PC'ye geri yazma
     * (syncBack) bedavaya gelir; aynı adlı iki farklı belge de çakışmaz.
     *
     * Kaydedilmemiş uygulama içi düzenleme varsa devretme YAPILMAZ: harici
     * uygulama PC'deki sürümü indirir, geri yazınca telefondaki değişiklik
     * sessizce kaybolurdu.
     */
    fun editOpenedDocumentExternally(): Job = viewModelScope.launch {
        val state = _openedDocx.value
        if (state.path.isBlank()) return@launch
        if (state.dirty) {
            _messages.emit("Önce değişiklikleri PC'ye kaydet, sonra harici düzenleyicide aç")
            return@launch
        }
        coworkFileEditDelegate.openForEdit(state.path, coworkOnly = openedDocxCoworkOnly)
    }

    fun dismissDocxStatus() {
        _openedDocx.update { it.copy(saved = false, conflict = false, error = "") }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun loadThought(index: Int) = viewModelScope.launch {
        if (index < 0 || _thoughtDetails.value.containsKey(index) || _loadingThoughts.value.contains(index)) return@launch
        _loadingThoughts.update { it + index }
        val state = _uiState.value
        val result = when {
            state.backend == "claude-app" && state.claudeAppSessionId.isNotBlank() -> runCatching { client.claudeAppThought(state.settings, state.claudeAppSessionId, index) }
            state.backend == "cowork" && state.coworkProvider == "codex-app" && state.codexAppSessionId.isNotBlank() -> runCatching { client.codexAppThought(state.settings, state.codexAppSessionId, index) }
            state.backend == "cowork" && state.coworkProvider == "opencode2-app" && state.opencodeAppSessionId.isNotBlank() -> runCatching { client.opencodeAppThought(state.settings, state.opencodeAppSessionId, index) }
            state.backend == "cowork" && state.coworkProvider == "opencode2-app" && state.opencode.sessionId.isNotBlank() -> runCatching { client.opencodeAppThought(state.settings, state.opencode.sessionId, index, "opencode2-app") }
            state.backend == "cowork" && state.coworkProvider == "claude-app" && state.claudeAppSessionId.isNotBlank() -> runCatching { client.claudeAppThought(state.settings, state.claudeAppSessionId, index) }
            state.backend == "codex-app" && state.codexAppSessionId.isNotBlank() -> runCatching { client.codexAppThought(state.settings, state.codexAppSessionId, index) }
            state.backend == "opencode2-app" && state.opencodeAppSessionId.isNotBlank() -> runCatching { client.opencodeAppThought(state.settings, state.opencodeAppSessionId, index) }
            state.backend == "opencode2-app" && state.opencode.sessionId.isNotBlank() -> runCatching { client.opencodeAppThought(state.settings, state.opencode.sessionId, index, "opencode2-app") }
            state.backend == "omp" && state.ompSessionId.isNotBlank() -> runCatching { client.ompThought(state.settings, state.ompSessionId, index) }
            state.backend == "agy" && state.agySessionId.isNotBlank() -> runCatching { client.agyThought(state.settings, state.agySessionId, index) }
            else -> Result.success("")
        }
        result
            .onSuccess { text -> _thoughtDetails.update { it + (index to text) } }
            .onFailure {
                _thoughtDetails.update { it + (index to "") }
                reportError("Düşünce detayı alınamadı", it)
            }
        _loadingThoughts.update { it - index }
    }

    fun newConversation() = viewModelScope.launch {
        _uiState.update { it.copy(transcript = "", messagesList = emptyList(), currentSession = "", truncateAfterIndex = null) }
        refreshAll()
    }

    fun selectModel(name: String) = viewModelScope.launch {
        _uiState.update { it.copy(model = name) }
    }

    fun enterAgyMode() = agyDelegate.enterAgyMode()
    fun cancelAgySetup() = agyDelegate.cancelAgySetup()
    fun showAgySessionPicker() = agyDelegate.showAgySessionPicker()
    fun startAgySession(cwd: String, model: String) = agyDelegate.startAgySession(cwd, model)
    fun setAgyModel(id: String) = agyDelegate.setAgyModel(id)
    fun loadAgyDiskSessions() = agyDelegate.loadAgyDiskSessions()
    fun resumeAgyDiskSession(session: AgyDiskSession) = agyDelegate.resumeAgyDiskSession(session)
    fun exitAgyMode() = agyDelegate.exitAgyMode()
    fun agyOpenAntigravity() = agyDelegate.agyOpenAntigravity()

    // ── Claude App (kalıcı çift-yönlü) ──────────────────────────────────────
    fun enterClaudeAppMode() = claudeAppDelegate.enterClaudeAppMode()
    fun cancelClaudeAppSetup() = claudeAppDelegate.cancelClaudeAppSetup()
    // Varsayılan auto: doğrudan "yeni oturum" akışı (NewSessionScreen →
    // startBackendSession) her seferinde auto ile açılsın; sheet gibi açık mod
    // geçen çağrılar kendi seçimini korur.
    fun startClaudeAppSession(cwd: String, model: String, permissionMode: String = "auto") =
        claudeAppDelegate.startClaudeAppSession(cwd, model, permissionMode)

    // ── Cowork (claude-app üstünde sunum + konfigürasyon katmanı) ──────────────
    // COWORK ag katmaninda claude-app uclarini kullanir (Backend.COWORK.apiBackend).
    // Farki: workspace kavrami, outputs/ teslimat sözleşmesi ve sadelestirilmis sunum.
    // Mantık CoworkDelegate'te (madde 11.6c); ViewModel yalnızca yönlendirir.
    fun enterCoworkMode() = coworkDelegate.enterCoworkMode()

    fun loadCoworkWorkspaces() = coworkDelegate.loadCoworkWorkspaces()
    fun loadCoworkProviderCatalog() = coworkDelegate.loadCoworkProviderCatalog()
    fun createCoworkWorkspace(
        name: String,
        template: String = "",
        onResult: (Boolean) -> Unit = {},
    ) = coworkDelegate.createCoworkWorkspace(name, template, onResult)
    fun createLawsuitWorkspaceAndStart(name: String) = coworkDelegate.createLawsuitWorkspaceAndStart(name)
    fun setCoworkWorkspaceMatter(path: String, matter: String) = coworkDelegate.setCoworkWorkspaceMatter(path, matter)
    fun startCoworkSession(workspacePath: String, model: String = "") = coworkDelegate.startCoworkSession(workspacePath, model)
    fun startCoworkWithAutoWorkspace(pendingPrompt: String? = null): Job = coworkDelegate.startCoworkWithAutoWorkspace(pendingPrompt)
    fun startCoworkNamedWorkspace(name: String): Job = coworkDelegate.startCoworkWithAutoWorkspace(name = name)
    fun switchCoworkProvider(provider: String) = coworkDelegate.switchCoworkProvider(provider)
    fun setCoworkModel(id: String) = coworkDelegate.setCoworkModel(id)

    fun setClaudeAppModel(id: String) = claudeAppDelegate.setClaudeAppModel(id)
    fun setClaudeAppPermissionMode(mode: String) = claudeAppDelegate.setClaudeAppPermissionMode(mode)
    fun loadClaudeAppEfforts() = claudeAppDelegate.loadClaudeAppEfforts()
    fun setClaudeAppEffort(effort: String) = claudeAppDelegate.setClaudeAppEffort(effort)
    fun loadClaudeAppInfo() = claudeAppDelegate.loadClaudeAppInfo()
    fun loadClaudeAppModels() = claudeAppDelegate.loadClaudeAppModels()
    fun loadClaudeAppDiskSessions() = claudeAppDelegate.loadClaudeAppDiskSessions()
    fun claudeAppArchive(id: String) = claudeAppDelegate.claudeAppArchive(id)
    fun claudeAppUnarchive(id: String) = claudeAppDelegate.claudeAppUnarchive(id)
    fun claudeAppPin(id: String) = claudeAppDelegate.claudeAppPin(id)
    fun claudeAppUnpin(id: String) = claudeAppDelegate.claudeAppUnpin(id)
    fun claudeAppRename(id: String, title: String) = claudeAppDelegate.claudeAppRename(id, title)
    fun claudeAppOpenOnPc(id: String) = claudeAppDelegate.claudeAppOpenOnPc(id)

    /**
     * Silinen oturumun telefonda bıraktığı her izi temizler.
     *
     * Silme yolları üçe ayrılmıştı (çekmece, cowork, toplu) ve her biri farklı
     * kadar temizlik yapıyordu. İki iz vardı:
     *  - açık sekme: kapanmıyor, artık olmayan id'yi tazeliyordu
     *  - Room cache'i: `find`/`upsert` dışında bir şey yoktu, konuşma metni
     *    telefonda kalıyordu (1 Ağu 2026'da ölçüldü)
     * Üç yol da buradan geçsin ki bir daha ayrışmasın.
     */
    fun forgetDeletedSessions(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        KapsulDenetleyici.silinenOturumlariKaldir(getApplication<Application>(), sessionIds)
        tabsDelegate.removeSessionTabs(sessionIds)
        conversationMemory.remove(sessionIds)
        viewModelScope.launch { runCatching { offlineConversationCache.delete(sessionIds) } }
    }

    // Oturumu app listesinden ve diskten tamamen sil (long-press → onay sonrası).
    // Silme sonrası ilgili backend'in disk listesi yenilenir.
    fun deleteDiskSession(backend: String, id: String) = viewModelScope.launch {
        runCatching { client.deleteDiskSession(_uiState.value.settings, backend, id) }
            .onSuccess { silinenIdler ->
                _messages.emit("Oturum silindi")
                forgetDeletedSessions(silinenIdler)
                when (backend) {
                    "claude-app" -> loadClaudeAppDiskSessions()
                    "codex-app" -> loadCodexAppDiskSessions()
                    "opencode2-app" -> loadOpencode2AppDiskSessions()
                    "omp" -> loadOmpDiskSessions()
                    "agy" -> loadAgyDiskSessions()
                }
            }
            .onFailure { reportError("Oturum silinemedi", it) }
    }

    // Cowork oturum kaydını sil (long-press → onay sonrası): .cowork kaydı + sağlayıcı
    // karşılığı gider, workspace klasörü yerinde kalır. Sonrasında liste tazelenir.
    fun deleteCoworkSession(projectPath: String, id: String) = coworkDelegate.deleteCoworkSession(projectPath, id)
    fun deleteCoworkProject(projectPath: String) = coworkDelegate.deleteCoworkProject(projectPath)

    // Debounce: her tuş vuruşunda köprüye istek atmamak ve hızlı yazarken sırasız
    // dönen cevapların bayat liste göstermesini önlemek için (son istek kazanır).
    private var drawerSearchJob: Job? = null

    private fun reloadDrawerSessions() {
        when (_uiState.value.backend) {
            "claude-app" -> loadClaudeAppDiskSessions()
            "codex-app" -> loadCodexAppDiskSessions()
        }
    }

    fun updateDrawerSearchQuery(q: String) {
        _uiState.update { it.copy(drawerSearchQuery = q) }
        drawerSearchJob?.cancel()
        drawerSearchJob = viewModelScope.launch {
            delay(300)
            reloadDrawerSessions()
        }
    }

    // Arşiv görünümü: arşivlenen oturumlar varsayılan listede gizli; bu toggle
    // olmadan arşivden geri alma imkânsızdı (tek yönlü kapı).
    fun toggleDrawerArchived() {
        _uiState.update { it.copy(drawerShowArchived = !it.drawerShowArchived) }
        reloadDrawerSessions()
    }

    fun clearDrawerSearch() {
        drawerSearchJob?.cancel()
        _uiState.update { it.copy(drawerSearchQuery = "", drawerShowArchived = false) }
        // Çekmece araması global (içerik) aramayı da sürüyor; kapatınca sıfırla
        // ki Merkez arama ekranında bayat sorgu/sonuç görünmesin.
        globalSearchDelegate.dismiss()
    }

    // Cowork drawer veri kaynağı: seçili workspace'in .cowork/providers/*/sessions
    // kayıtlarını çeker (provider etiketli, yenisel eskiye). Eski disk-session listesi
    // artık kullanılmıyor — drawer yalnızca .cowork metadata'sını gösterir.
    fun loadCoworkSessions(projectPath: String = _uiState.value.selectedCoworkWorkspace) = coworkDelegate.loadCoworkSessions(projectPath)
    // Çekmecenin globali: tüm workspace'lerin cowork oturumları tek listede.
    fun loadAllCoworkSessions() = coworkDelegate.loadAllCoworkSessions()

    // Workspace seçimi: seçimi sabitler ve o projenin oturumlarını yükler.
    // Drawer "workspace selection stays fixed while listing sessions" gereksinimi.
    fun selectCoworkWorkspace(path: String) = coworkDelegate.selectCoworkWorkspace(path)

    // Drawer'daki bir .cowork oturum kaydına dokunulunca: o belirli sessionId'yi
    // resume eder (provider + sessionId hedefli) ve aktif oturuma uygular.
    fun resumeCoworkSession(record: CoworkSessionRecord) = coworkDelegate.resumeCoworkSession(record)

    // /clear sonrası bridge yeni sessionId döndüğünde UI state'ini ve socket'i günceller.
    fun handleClaudeAppCleared(json: JSONObject) = claudeAppDelegate.handleClaudeAppCleared(json)

    // Yolo (otomatik onay) toggle'ı — tüm provider'lar için (madde 6). Aktif cowork
    // oturumu varsa modu hemen uygular (best effort; codex/opencode tur sürerken reddeder,
    // o durumda bir sonraki oturum başlangıcında zaten toggle'dan uygulanır).
    fun setCoworkYoloMode(enabled: Boolean) = coworkDelegate.setCoworkYoloMode(enabled)
    fun handoffCoworkTo(provider: String) = coworkDelegate.handoffCoworkTo(provider)

    // Adopt an on-disk claude-app session and continue it.
    fun resumeClaudeAppDiskSession(session: ClaudeDiskSession) = claudeAppDelegate.resumeClaudeAppDiskSession(session)
    fun exitClaudeAppMode() = claudeAppDelegate.exitClaudeAppMode()
    // Onay çağrılarındaki `requestId`: kartın çizildiği durumdaki onay kimliği
    // (ApprovalActionRouter geçer); null ise delege o anki kimliği okur.
    fun claudeAppApprove(allow: Boolean, requestId: String? = null) = claudeAppDelegate.claudeAppApprove(allow, requestId)
    fun claudeAppAnswerQuestion(question: ApprovalQuestion, option: ApprovalOption) =
        claudeAppDelegate.claudeAppAnswerQuestion(question, option)
    fun claudeAppAnswerQuestions(answers: List<ApprovalAnswer>, requestId: String? = null) =
        claudeAppDelegate.claudeAppAnswerQuestions(answers, requestId)

    fun codexAppApprove(allow: Boolean, decision: String = "", scope: String = "", requestId: String? = null) =
        codexAppActionsDelegate.approve(allow, decision, scope, requestId)
    fun codexAppApproveSession(requestId: String? = null) = codexAppApprove(true, "acceptForSession", "session", requestId)
    fun codexAppAnswerQuestions(answers: List<ApprovalAnswer>, requestId: String? = null) =
        codexAppActionsDelegate.answerQuestions(answers, requestId)
    fun loadCodexAppInfo() = codexAppActionsDelegate.loadInfo()
    fun codexAppForkCurrent() = codexAppActionsDelegate.forkCurrent()
    fun showCodexAppSteerToggle(enabled: Boolean) = codexAppActionsDelegate.setSteerMode(enabled)
    fun codexAppSteer(text: String) = codexAppActionsDelegate.steer(text)
    fun loadCodexAppDiff() = codexAppActionsDelegate.loadDiff()
    fun loadCodexAppCommands() = codexAppActionsDelegate.loadCommands()
    fun refreshCodexAppContextPercent() = codexAppActionsDelegate.refreshContextPercent()
    fun loadCodexAppPermissionModes() = codexAppActionsDelegate.loadPermissionModes()
    fun codexAppSetPermissionMode(mode: String) = codexAppActionsDelegate.setPermissionMode(mode)
    fun loadCodexAppEfforts() = codexAppActionsDelegate.loadEfforts()
    fun codexAppSetEffort(effort: String) = codexAppActionsDelegate.setEffort(effort)
    fun codexAppArchive(id: String) = codexAppActionsDelegate.archive(id)
    fun codexAppUnarchive(id: String) = codexAppActionsDelegate.unarchive(id)
    fun codexAppPin(id: String) = codexAppActionsDelegate.pin(id)
    fun codexAppUnpin(id: String) = codexAppActionsDelegate.unpin(id)
    fun codexAppRename(id: String, title: String) = codexAppActionsDelegate.rename(id, title)
    fun loadCodexAppPlanItems() = codexAppActionsDelegate.loadPlanItems()
    fun codexAppRespondUserInput(text: String) = codexAppActionsDelegate.respondUserInput(text)
    fun codexAppSetGoal(objective: String) = codexAppActionsDelegate.setGoal(objective)
    fun codexAppClearGoal() = codexAppActionsDelegate.clearGoal()
    fun refreshCodexAppGoal() = codexAppActionsDelegate.refreshGoal()
    fun showCodexAppGoal() = codexAppActionsDelegate.showGoal()
    fun dismissCodexAppGoal() = codexAppActionsDelegate.dismissGoal()

    fun enterCodexAppMode() = providerLifecycleDelegate.enterCodex()
    fun loadCodexAppModels() = providerLifecycleDelegate.loadCodexAppModels()
    fun cancelCodexAppSetup() = providerLifecycleDelegate.cancelCodexSetup()
    fun startCodexAppSession(cwd: String, model: String) = providerLifecycleDelegate.startCodex(cwd, model)
    fun setCodexAppModel(id: String) = providerLifecycleDelegate.setCodexModel(id)
    fun loadCodexAppDiskSessions() = providerLifecycleDelegate.loadCodexDiskSessions()
    fun resumeCodexAppDiskSession(session: CodexDiskSession) = providerLifecycleDelegate.resumeCodex(session)
    fun exitCodexAppMode() = providerLifecycleDelegate.exitCodex()

    // --- OpenCode App backend ---
    fun enterOpencodeAppMode() = providerLifecycleDelegate.enterOpencode()
    fun enterOpencode2AppMode() = providerLifecycleDelegate.enterOpencode(Backend.OPENCODE2_APP.id)
    fun exitOpencode2AppMode() = providerLifecycleDelegate.exitOpencode(Backend.OPENCODE2_APP.id)
    fun loadOpencodeAppModels() = providerLifecycleDelegate.loadOpencodeAppModels()

    fun cancelOpencodeAppSetup() = ocDelegate.cancelSetup()
    // Bu uc sarmalayici v1 adlariyla KALDI (TabsDelegate ve arayuz onlari cagiriyor),
    // ama tek delegeye bakiyorlar; Opencode2* adlari es anlamli.
    fun startOpencodeAppSession(cwd: String, model: String) = opencode2ActionsDelegate.startSession(cwd, model)
    fun setOpencodeAppModel(id: String) = ocDelegate.setModel(id)
    fun loadOpencodeAppDiskSessions() = opencode2ActionsDelegate.loadDiskSessions()
    fun resumeOpencodeAppDiskSession(session: AppDiskSession) = opencode2ActionsDelegate.resumeDiskSession(session)
    fun startOpencode2AppSession(cwd: String, model: String) = opencode2ActionsDelegate.startSession(cwd, model)
    fun loadOpencode2AppDiskSessions() = opencode2ActionsDelegate.loadDiskSessions()
    fun resumeOpencode2AppDiskSession(session: AppDiskSession) = opencode2ActionsDelegate.resumeDiskSession(session)
    // Oturum kuralları (yalnız v2): talimatlar + kayıtlı izin kuralları.
    fun loadOpencode2Instructions() = opencode2ActionsDelegate.loadInstructions()
    fun putOpencode2Instruction(key: String, value: String) = opencode2ActionsDelegate.putInstruction(key, value)
    fun deleteOpencode2Instruction(key: String) = opencode2ActionsDelegate.deleteInstruction(key)
    fun loadOpencode2SavedPermissions() = opencode2ActionsDelegate.loadSavedPermissions()
    fun deleteOpencode2SavedPermission(id: String) = opencode2ActionsDelegate.deleteSavedPermission(id)
    fun loadOpencode2AppModels() = providerLifecycleDelegate.loadOpencodeAppModels(Backend.OPENCODE2_APP.id)

    fun exitOpencodeAppMode() = providerLifecycleDelegate.exitOpencode()

    // --- OMP native RPC backend ---
    fun enterOmpMode() = providerLifecycleDelegate.enterOmp()
    fun exitOmpMode() = providerLifecycleDelegate.exitOmp()
    fun loadOmpModels() = providerLifecycleDelegate.loadOmpModels()
    fun cancelOmpSetup() = ompActionsDelegate.cancelSetup()
    fun startOmpSession(cwd: String, model: String) = ompActionsDelegate.startSession(cwd, model)
    fun setOmpModel(id: String) = ompActionsDelegate.setModel(id)
    fun loadOmpDiskSessions() = ompActionsDelegate.loadDiskSessions()
    // Tur sürerken gönderim: metin composer'dan alınır ve kutu hemen boşalır
    // (kuyruk yolundaki davranışın aynısı — gönderilen metin ekranda kalmasın).
    fun ompSendDuringTurn(interrupt: Boolean): Job {
        val text = _uiState.value.input.trim()
        if (text.isBlank()) return viewModelScope.launch { }
        _uiState.update { it.copy(input = "") }
        return ompActionsDelegate.sendDuringTurn(text, interrupt)
    }
    // Tur sürerken gönderim — OpenCode'da YALNIZ kuyruk var (bkz. delege).
    fun opencodeAppSendDuringTurn(interrupt: Boolean): Job {
        val text = _uiState.value.input.trim()
        if (text.isBlank()) return viewModelScope.launch { }
        _uiState.update { it.copy(input = "") }
        return ocDelegate.sendDuringTurn(text, interrupt)
    }
    // Tur sürerken gönderim — BACKEND'E GÖRE dağıtır.
    //
    // 24.08.2026'ya kadar dört çağrı yeri de doğrudan `ompSendDuringTurn`
    // çağırıyordu, yani yetenek OMP'ye gömülüydü. OpenCode 25.08.2026'da eklendi.
    //
    // COWORK: sağlayıcı çözülmeden dağıtılırsa cowork+OpenCode oturumu `else`
    // dalından OMP'ye giderdi ve boş oturum kimliğiyle sessizce hiçbir şey
    // yapmazdı — midTurnSendBackend sağlayıcıyı açar.
    fun backendSendDuringTurn(interrupt: Boolean): Job =
        when (midTurnSendBackend(_uiState.value.backend, _uiState.value.coworkProvider)) {
            // v2 de burada: `opencodeAppSendDuringTurn` ocDelegate uzerinden
            // aktif aileye gidiyor. Dal olmadan v2 `else`den OMP'ye dusuyordu.
            "opencode2-app" -> opencodeAppSendDuringTurn(interrupt)
            else -> ompSendDuringTurn(interrupt)
        }
    fun ompPin(id: String) = ompActionsDelegate.pin(id)
    fun ompUnpin(id: String) = ompActionsDelegate.unpin(id)
    fun ompRename(id: String, title: String) = ompActionsDelegate.rename(id, title)
    fun ompArchive(id: String) = ompActionsDelegate.archive(id)
    fun ompUnarchive(id: String) = ompActionsDelegate.unarchive(id)
    fun resumeOmpDiskSession(session: AppDiskSession) = ompActionsDelegate.resumeDiskSession(session)
    fun ompApprove(allow: Boolean, requestId: String? = null) = ompActionsDelegate.approve(allow, requestId = requestId)
    fun ompAnswerQuestions(answers: List<ApprovalAnswer>, requestId: String? = null) =
        ompActionsDelegate.approve(true, answers, requestId)
    fun setOmpPermissionMode(mode: String) = ompActionsDelegate.setPermissionMode(mode)
    fun loadOmpPermissionModes() = ompActionsDelegate.loadPermissionModes()
    fun loadOmpInfo() = ompActionsDelegate.loadInfo()
    fun setOmpEffort(effort: String) = ompActionsDelegate.setEffort(effort)

    fun opencodeAppApprove(allow: Boolean, requestId: String? = null) = ocDelegate.approve(allow, requestId)
    fun opencodeAppAnswerQuestions(answers: List<ApprovalAnswer>, requestId: String? = null) =
        ocDelegate.answerQuestions(answers, requestId)
    fun loadOpencodeAppInfo() = ocDelegate.loadInfo()
    // Görev panosundaki "Sıkıştır" — doluluk çubuğu eşiği geçince beliriyor.
    fun opencodeAppCompact() = ocDelegate.compact()
    // "Değişiklikler" görünümü açılınca çekilir; yoklamaya binmez.
    fun loadOpencodeAppDiff() = ocDelegate.loadDiff()
    // Checkpoint geri sarma. Liste açılınca çekilir (diff ile aynı kural);
    // revertTo YIKICI olduğu için yalnız onay diyaloğundan sonra çağrılmalı.
    fun loadOpencodeAppCheckpoints() = ocDelegate.loadCheckpoints()
    // Alt-ajan kartına dokununca; yoklamaya binmez.
    fun loadOpencodeAppSubagentTranscript(childId: String) =
        ocDelegate.loadSubagentTranscript(childId)
    fun opencodeAppRevertTo(messageID: String) = ocDelegate.revertTo(messageID)
    fun opencodeAppUnrevert() = ocDelegate.unrevert()
    // Oturum paylaşımı — İNTERNETE yayın yapar, yalnız onay diyaloğundan sonra
    // çağrılmalı. [onLink] paylaşım sayfasını (share intent) açan geri çağrı:
    // link snapshot'la da geliyor ama tuşun ölü görünmemesi için anında lazım.
    fun opencodeAppShare(onLink: (String) -> Unit = {}) = ocDelegate.share(onLink)
    fun opencodeAppUnshare() = ocDelegate.unshare()
    // Özel komutlar: liste "/" yazılınca çekilir (kuruluma ait, oturumla
    // sıfırlanmaz); çalıştırma bir TUR başlatır.
    fun loadOpencodeAppCommands() = ocDelegate.loadCommands()
    fun opencodeAppRunCommand(command: String, arguments: String) =
        ocDelegate.runCommand(command, arguments)
    // AGENTS.md init — bir tur koşar, yalnız onay diyaloğundan sonra.
    fun opencodeAppInitAgents() = ocDelegate.initAgents()
    fun setOpencodeAppPermissionMode(mode: String) = ocDelegate.setPermissionMode(mode)
    // Akil yurutme eforu ("variant"). Oturum durumunda tutulur, her prompt'la
    // birlikte gonderilir; ayri bir kopru cagrisi YOK — opencode bunu mesaj
    // govdesinde bekliyor, oturum ayari olarak degil.
    fun setOpencodeAppVariant(variant: String) = _uiState.update {
        // AKTIF aileye yaz: v2 sekmesinde v1'in eforunu degistirmek, secimi
        // hicbir yere gitmeyen bir yazma yapardi (prompt v2 ailesini okuyor).
        it.withOpencodeFamily(it.backend) { f -> f.copy(variant = variant) }
    }
    fun loadOpencodeAppPermissionModes() = ocDelegate.loadPermissionModes()
    fun loadOpencodeAppAgents() = ocDelegate.loadAgents()
    fun setOpencodeAppAgent(name: String) = ocDelegate.setAgent(name)
    fun opencodeAppPin(id: String) = ocDelegate.pin(id)
    fun opencodeAppUnpin(id: String) = ocDelegate.unpin(id)
    fun opencodeAppRename(id: String, title: String) = ocDelegate.rename(id, title)

    fun returnToMessage(index: Int) = conversationDelegate.returnToMessage(index)

    // Buradan çatalla: bridge kopya oturum açar; kopya YENİ sekmede açılır,
    // çatallanan mesajın metni düzenlenmek üzere composer'a konur. Orijinal
    // oturum ve sekmesi aynen kalır.
    fun forkFromMessage(index: Int) = conversationDelegate.forkFromMessage(index) { backend, newSessionId, text ->
        // activateTab ile aynı desen: boş sekme + son-oturum kimliği + enter*Mode.
        // (resumeBackendDiskSession kullanılamaz: taze fork disk listesinde henüz yok.)
        tabsDelegate.newTab()
        _uiState.update {
            when (backend) {
                "claude-app" -> it.copy(
                    claude = it.claude.copy(lastSessionId = newSessionId), input = text,
                    pendingBindBackend = "claude-app", pendingBindSessionId = newSessionId,
                )
                "codex-app" -> it.copy(
                    codex = it.codex.copy(lastSessionId = newSessionId), input = text,
                    pendingBindBackend = "codex-app", pendingBindSessionId = newSessionId,
                )
                // enterOmp pendingBind'i okuyup oturumu kendisi bağlıyor; omp
                // state'inde lastSessionId alanı yok, gerek de yok.
                "omp" -> it.copy(
                    input = text,
                    pendingBindBackend = "omp", pendingBindSessionId = newSessionId,
                )
                else -> it.copy(input = text)
            }
        }
        when (backend) {
            "claude-app" -> enterClaudeAppMode()
            "codex-app" -> enterCodexAppMode()
            "omp" -> enterOmpMode()
        }
        viewModelScope.launch { _messages.emit("Yeni sekmede çatallandı; mesajı düzenleyip gönderebilirsin.") }
    }
    fun clearTruncation() = _uiState.update { it.copy(truncateAfterIndex = null) }
    fun showOlderMessages() = conversationDelegate.showOlderMessages()

    // --- Process admin & local model ---
    fun loadAllBackendSessionCounts() = maintenanceDelegate.loadAllBackendSessionCounts()
    fun loadProcesses() = maintenanceDelegate.loadProcesses()
    fun killAllSessions(backend: String) = maintenanceDelegate.killAllSessions(backend) {
        loadProcesses()
        loadAllBackendSessionCounts()
    }
    // Silinmis opencode oturumlarinin kalintilari: durum/tarama ve imha.
    fun loadPurgeStatus(refresh: Boolean = false) = maintenanceDelegate.loadPurgeStatus(refresh)
    fun runSessionPurge() = maintenanceDelegate.runPurge()
    fun refreshRunPodStatus(showErrors: Boolean = true) = maintenanceDelegate.refreshRunPodStatus(showErrors)
    fun startRunPod() = maintenanceDelegate.startRunPod()
    fun stopRunPod() = maintenanceDelegate.stopRunPod()

    fun setDefaultModel(name: String) = viewModelScope.launch {
        _uiState.update { it.copy(defaultModel = name) }
    }

    fun selectSession(title: String) = viewModelScope.launch {
        _uiState.update { it.copy(currentSession = title, messagesList = emptyList(), transcript = "", truncateAfterIndex = null) }
        refreshAll()
    }

    fun selectProject(name: String) = viewModelScope.launch {
        _uiState.update { it.copy(currentProject = name) }
    }

    fun newConversationInProject(name: String) = viewModelScope.launch {
        _uiState.update { it.copy(currentProject = name, currentSession = "", messagesList = emptyList(), transcript = "", truncateAfterIndex = null) }
        refreshAll()
    }

    fun stop() = viewModelScope.launch {
        val state = _uiState.value
        // Backend → uç eşlemesi `BridgeClient.turDurdur`'da (shared): kapsül
        // bildirimindeki "Durdur" tuşu da aynı yardımcıyı çağırıyor, eşleme
        // iki yerde durursa biri güncellenip öteki unutulur.
        // cowork'ün kendi ucu yok, sağlayıcısına çözülür (eski davranış).
        val hedefBackend =
            if (state.backend == "cowork") normalizeCoworkProvider(state.coworkProvider)
            else state.backend.orEmpty()
        val sessionId = when (hedefBackend) {
            "agy" -> state.agySessionId
            "claude-app" -> state.claudeAppSessionId
            "codex-app" -> state.codexAppSessionId
            "opencode2-app" -> state.opencode.sessionId
            "omp" -> state.ompSessionId
            else -> ""
        }
        val result = runCatching { client.turDurdur(state.settings, hedefBackend, sessionId) }
        result
            .onSuccess {
                _uiState.update { it.copy(running = false) }
                if (state.backend == "cowork") releaseCoworkLease(state)
                refreshConversation(showErrors = false)
            }
            .onFailure { reportError("Stop failed", it) }
    }

    private fun openAgySocket(sessionId: String) {
        streamManager.open("agy", sessionId, _uiState.value.settings)
    }

    private fun openClaudeAppSocket(sessionId: String) {
        streamManager.open("claude-app", sessionId, _uiState.value.settings)
    }

    private fun openCoworkSocket(provider: String, sessionId: String) {
        streamManager.open(normalizeCoworkProvider(provider), sessionId, _uiState.value.settings)
    }


    private fun openCodexAppSocket(sessionId: String) {
        streamManager.open("codex-app", sessionId, _uiState.value.settings)
    }

    // Bildirimlerin tek kanalı bu servisin long-poll'u; tam sürümde hep açık.
    // Lite arka plan bildirimi paketlemez (servis manifestten çıkarılıyor).
    private fun syncNotificationService() {
        if (BuildConfig.IS_LITE) return
        val context = getApplication<Application>().applicationContext
        val intent = Intent(context, BridgeMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    private suspend fun reportError(prefix: String, throwable: Throwable) {
        _messages.emit("$prefix: ${throwable.message ?: "unknown error"}")
    }

    private fun handleStreamEnd() {
        // claude-app snapshot'ı outputs taşır; codex/opencode taşımaz → tur bitince API'den tazele.
        val shouldRefreshOutputs = _uiState.value.backend == "cowork" &&
            normalizeCoworkProvider(_uiState.value.coworkProvider) != "claude-app"
        _uiState.update { it.copy(running = false) }
        if (_uiState.value.backend == "cowork") releaseCoworkLease(_uiState.value)
        if (shouldRefreshOutputs) coworkDelegate.refreshCoworkOutputs(showErrors = false)
    }

    private fun applyConversation(c: ConversationResult) = conversationDelegate.apply(c)

    override fun onCleared() {
        agyDelegate.cancelPolling()
        claudeAppDelegate.cancelPolling()
        providerLifecycleDelegate.cancelAllPolling()
        streamManager.close()
        super.onCleared()
    }

    private fun applyStreamSnapshot(streamSessionId: String, snapshot: BackendStreamSnapshot) {
        val coworkTurnFinished = _uiState.value.backend == "cowork" &&
            _uiState.value.running && snapshot.running == false
        val shouldRefreshOutputs = SessionStateReducer.shouldRefreshCoworkOutputs(_uiState.value, snapshot)
        val idBefore = _uiState.value.claudeAppSessionId
        _uiState.update { state -> SessionStateReducer.reduceStreamSnapshot(state, snapshot) }
        conversationMemory.recordFrom(_uiState.value)
        // A1 savunması reducer'da id'yi değiştirdiyse (sunucu tarafı re-key) soketi de
        // yeni oturuma taşı; yoksa reconnect eski id ile kalır ve alias TTL'i dolunca kopar.
        val idAfter = _uiState.value.claudeAppSessionId
        if (idAfter.isNotBlank() && idAfter != idBefore && streamSessionId == idBefore) {
            _thoughtDetails.value = emptyMap()
            openClaudeAppSocket(idAfter)
        }
        if (shouldRefreshOutputs) coworkDelegate.refreshCoworkOutputs(showErrors = false)
        if (coworkTurnFinished) releaseCoworkLease(_uiState.value)
    }

    private fun releaseCoworkLease(st: RemoteUiState) {
        val projectPath = st.activeCoworkProjectPath
        val provider = normalizeCoworkProvider(st.coworkProvider)
        val sessionId = when (provider) {
            "codex-app" -> st.codexAppSessionId
            "opencode2-app" -> st.opencodeAppSessionId
            else -> st.claudeAppSessionId
        }
        if (projectPath.isBlank() || sessionId.isBlank()) return
        viewModelScope.launch {
            runCatching { client.coworkReleaseLease(st.settings, projectPath, provider, sessionId) }
        }
    }

}
