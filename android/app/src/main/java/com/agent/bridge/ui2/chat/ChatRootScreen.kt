package com.agent.bridge.ui2.chat

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.AppForeground
import com.agent.bridge.ApprovalActionRouter
import com.agent.bridge.ChatDisplayRow
import com.agent.bridge.GroupKind
import com.agent.bridge.buildChatDisplayRows
import com.agent.bridge.ChatSearchState
import com.agent.bridge.detailText
import com.agent.bridge.live
import com.agent.bridge.ConversationPaging
import com.agent.bridge.QuestionAnswerDraft
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.BackendDiskSessionUi
import com.agent.bridge.orderedVisibleBackends
import com.agent.bridge.MarkdownMessage
import com.agent.bridge.RemoteUiState
import com.agent.bridge.activeQueuedPrompts
import com.agent.bridge.visibleTabs
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.SessionStateReducer
import com.agent.bridge.activeProviderMonogramId
import com.agent.bridge.codexAppPlan
import com.agent.bridge.COWORK_PROVIDER_OPTIONS
import com.agent.bridge.coworkProvider
import com.agent.bridge.coworkProviderLabel
import com.agent.bridge.normalizeCoworkProvider
import com.agent.bridge.isToolSummary
import com.agent.bridge.backendCapabilities
import com.agent.bridge.midTurnQueueSupported
import com.agent.bridge.midTurnSteerSupported
import com.agent.bridge.backendShortLabel
import com.agent.bridge.backendAgentLabel
import com.agent.bridge.backendAgentOptions
import com.agent.bridge.opencodeFamily
import com.agent.bridge.backendAgentSupported
import com.agent.bridge.backendEffortOptions
import com.agent.bridge.backendEffortLabel
import com.agent.bridge.backendEffortSupported
import com.agent.bridge.backendModelOptions
import com.agent.bridge.backendPermissionModeOptions
import com.agent.bridge.backendSession
import com.agent.bridge.backendSkillGroups
import com.agent.bridge.backendSkillOptions
import com.agent.bridge.backendSkillsSupported
import com.agent.bridge.loadBackendSkills
import com.agent.bridge.textModelInfo
import com.agent.bridge.loadBackendEfforts
import com.agent.bridge.loadBackendInfo
import com.agent.bridge.loadBackendPermissionModes
import com.agent.bridge.loadBackendAgents
import com.agent.bridge.setBackendAgent
import com.agent.bridge.setBackendEffort
import com.agent.bridge.setBackendModel
import com.agent.bridge.setBackendPermissionMode
import com.agent.bridge.backendDiskSessions
import com.agent.bridge.loadBackendDiskSessions
import com.agent.bridge.backendSessionArchiveSupported
import com.agent.bridge.backendSessionOpenOnPcSupported
import com.agent.bridge.backendSessionPinSupported
import com.agent.bridge.backendSessionRenameSupported
import com.agent.bridge.setBackendSessionPinned
import com.agent.bridge.AttachmentChip
import com.agent.bridge.setBackendSessionArchived
import com.agent.bridge.setBackendSessionTitle
import com.agent.bridge.deleteBackendDiskSession
import com.agent.bridge.resumeBackendDiskSession
import com.agent.bridge.sortedBySessionPriority
import com.agent.bridge.permissionModeLabel
import com.agent.bridge.shortModelLabel
import com.agent.bridge.parseTaskNotification
import com.agent.bridge.ui2.components.EmptyState
import com.agent.bridge.ui2.components.LocalWideLayout
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.ProviderMark
import com.agent.bridge.ui2.components.SelectorInfo
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui2.components.SelectorSheet
import com.agent.bridge.ui2.components.StatusBadge
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.components.AppDrawer
import com.agent.bridge.ui2.components.SearchField
import com.agent.bridge.ui2.components.SegmentedTabs
import com.agent.bridge.ui2.components.ConfirmDialog
import com.agent.bridge.ui2.components.FileSourceChoiceDialog
import com.agent.bridge.ui2.components.LoadingSkeleton
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.hub.UsageGroupCard
import com.agent.bridge.ui2.hub.RunPodUsageCard
import com.agent.bridge.ui2.hub.UsageCardVisibilityList
import com.agent.bridge.ui2.hub.splitUsageGroupsAfterClaude
import com.agent.bridge.RUNPOD_USAGE_CARD_KEY
import com.agent.bridge.isUsageCardVisible
import com.agent.bridge.usageCardToggles
import com.agent.bridge.visibleUsageGroups
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRootScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    onNewSession: () -> Unit,
    onOpenCoworkFiles: (String) -> Unit = {},
    // md/docx dokunuşunda tam ekran dosya editörüne geçiş (chat/dosya rotası).
    onOpenFileViewer: () -> Unit = {},
    // Ek eklerken "bilgisayardan" seçilirse köprü gezginini seçici kipinde açar.
    onPickPcFiles: () -> Unit = {},
    // Çekmece durumu Ui2Root'ta yaşar: alt navdaki Arşiv tuşu aç/kapa yapabilsin,
    // Sohbet tuşu da açık çekmeceyi kapatabilsin diye (toggle dışarıdan sürülür).
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    // Kaydırma konumu belleği de Ui2Root'ta yaşar: bu ekran Merkez/Ayarlar'a
    // geçince bileşimden düşüyor, burada remember'lansa hatırlamazdı.
    scrollMemory: ChatScrollMemory = remember { ChatScrollMemory() },
) {
    val backendId = uiState.backend
    var openSheet by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val activeCoworkWorkspace = if (backendId == "cowork") uiState.backendSession("cowork").cwd else ""
    // Kenar şartı YOK: jest ekranın her yerinden başlar. Kenara sıkıştırmak sistem
    // geri-jestiyle çakışıyordu. Eşik yataydaki kararlı kaydırma mesafesi.
    val swipeThresholdPx = with(LocalDensity.current) { 84.dp.toPx() }
    // Oturum çekmecesi jesti geniş yerleşimde kapalı — kararı AppScaffold veriyor.
    val wideLayout = LocalWideLayout.current
    // Composer + pill satırlarının kapladığı alt bölge; jest burada devre dışı.
    val composerZonePx = with(LocalDensity.current) { 150.dp.toPx() }
    // Üstteki SessionTabBar şeridi de muaf: orası yatay kaydırılan LazyRow'dur
    // (sekme taşması), sağa sürüklemek çekmece jestini tetiklememeli.
    // Yükseklik: dikey contentPadding 8+8 + pill 6+6 + labelMedium ~18 ≈ 46dp.
    val tabBarZonePx = with(LocalDensity.current) { 48.dp.toPx() }

    val context = LocalContext.current
    val thoughtDetails by actions.thoughtDetails.collectAsState()
    // Ek kaynağı sorusu: telefon = bayt yükle, bilgisayar = yalnız yol ver.
    var askAttachSource by remember { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            scope.launch(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                } ?: "attachment.bin"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                actions.attachFile(bytes, name, uri.toString(), resolver.getType(uri))
            }
        }
    }

    val drawerBackendIds = orderedVisibleBackends(uiState).map { it.id }
    LaunchedEffect(drawerState.isOpen, drawerBackendIds) {
        if (drawerState.isOpen) {
            // Oturumlar tuşunun tek davranışı: aktif backend'e bakmadan bütün
            // görünür backend listelerini tazele ve birleşik göster.
            drawerBackendIds.forEach { id ->
                if (id == "cowork") actions.loadAllCoworkSessions()
                else actions.loadBackendDiskSessions(id)
            }
        }
    }
    LaunchedEffect(backendId) {
        if (backendId != null) actions.loadSlashCommands()
    }
    // Yukarıdaki efekt yalnız backendId DEĞİŞİNCE çalışır. Liste arada boşaltılırsa
    // (landing yenilemesi slashCommands'ı sıfırlıyor, ayrıca loadSlashCommands
    // uiState.backend henüz set değilken çağrılırsa boş liste yazıp çıkıyor) bir daha
    // doldurulmuyordu: "/" menüsü uygulama kapatılıp açılana kadar hiç açılmıyordu.
    // İhtiyaç anında kendini onarır.
    val slashMenuWanted = uiState.input.startsWith("/")
    LaunchedEffect(slashMenuWanted, uiState.slashCommands.isEmpty(), backendId) {
        if (backendId != null && slashMenuWanted && uiState.slashCommands.isEmpty()) {
            actions.loadSlashCommands()
        }
    }

    // Açık sohbeti bildirim katmanına duyur: o oturumun bildirimi bastırılsın ve
    // ekrana girildiğinde bekleyenler düşsün. Yalnız bu oturumunkiler temizlenir;
    // başka oturumların bildirimleri durur.
    val openSessionId = if (backendId != null) uiState.backendSession(backendId).sessionId else ""
    DisposableEffect(backendId, openSessionId) {
        if (backendId != null && openSessionId.isNotBlank()) {
            AppForeground.openChat(backendId, openSessionId)
            AppForeground.clearFor(context, backendId, openSessionId)
        }
        onDispose {
            if (backendId != null) AppForeground.closeChat(backendId, openSessionId)
        }
    }

    var longClickedSessionId by remember { mutableStateOf<String?>(null) }
    // Global (repo açık değil) modda uzun basılan oturumun ait olduğu backend;
    // menü/silme onayı bu backend'i hedefler (aktif modda backendId ile aynı).
    var longClickedSessionBackend by remember { mutableStateOf<String?>(null) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var showSessionDeleteConfirm by remember { mutableStateOf(false) }
    var showSessionRename by remember { mutableStateOf(false) }
    // Codex hedef pill'ine dokununca açılan özet. Hedef bitince pill KENDİLİĞİNDEN
    // kaybolmaz (kullanıcı kararı 02.08.2026: "yoksa yakalayamam"); bitmiş hedefi
    // ancak buradan "Tamam" ile kapatınca düşer.
    var showGoalDialog by remember { mutableStateOf(false) }
    // "Bilgisayarda devam et" → hangi Claude profiliyle açılacağı sorulur.
    // Sohbetteki görsel eke tıklanınca dosya yöneticisiyle AYNI tam ekran
    // görüntüleyici açılır (zoom, kaydırma, paylaş). Aynı mesajın ekleri kardeş
    // listesi olur; attachment=true görüntüleyicide "PC'de sakla" ve "PC'den sil"
    // tuşlarını açar — paylaşılan görseller iş bitince siliniyor.
    fun openAttachment(images: List<MsgImage>, path: String) {
        actions.setImageNavigation(images.map { it.path }, local = false, attachment = true)
        actions.openImage(path, local = false, attachment = true)
        onOpenFileViewer()
    }
    // Henüz GÖNDERİLMEMİŞ ek: composer'daki küçük resme dokununca aynı
    // görüntüleyici açılır. Dosya eklenirken zaten köprüye yüklendiği için yol
    // gönderilmiş ekle aynı yoldan okunur. attachment=false: "PC'de sakla"
    // burada anlamsız, ek zaten kullanıcının kendi seçtiği dosya.
    fun previewPendingAttachment(path: String) {
        val images = uiState.attachments.filter { it.isImage }.map { it.path }
        actions.setImageNavigation(images, local = false)
        actions.openImage(path, local = false)
        onOpenFileViewer()
    }
    var longClickedTabId by remember { mutableStateOf<String?>(null) }
    // Sekme kapatma onayı ViewModel'de (actions.pendingTabClose) ve diyalog
    // Ui2Root'ta: çarpı tuşu ile geri jesti aynı onayı paylaşır.

    // Geri jesti: açık çekmece önce kapanır (uygulamadan çıkmaz).
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Yatay kaydırılabilir içeriğin (kod bloğu, tablo, görsel şeridi) çekmece
    // jestinden muaf bölgeleri — bkz. ChatSwipeExclusion.kt ve chatSwipe kural 4.
    val swipeExclusions = remember { ChatSwipeExclusions() }
    var chatSurfaceCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    CompositionLocalProvider(LocalChatSwipeExclusions provides swipeExclusions) {
    AppDrawer(
        drawerState = drawerState,
        // Çekmece KAPALIYKEN kapalı: açma jesti aşağıdaki tek handler'da (kenar
        // şartsız, tüm yüzeyden) — iki recognizer aynı yüzeyde yarışmasın.
        // Çekmece AÇIKKEN açık: sola sürükleyip kapatmayı ModalNavigationDrawer'ın
        // kendi sürüklemesi halleder (bizim handler çekmecenin altında kalıyor,
        // açıkken olayları hiç görmüyor — bu yüzden jestle kapanmıyordu).
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SessionDrawerContent(
                uiState = uiState,
                actions = actions,
                onKapat = { scope.launch { drawerState.close() } },
                onNewSession = onNewSession,
                onSessionLongClick = { b, id ->
                    longClickedSessionId = id
                    longClickedSessionBackend = b
                    showSessionMenu = true
                },
            )
        },
        content = {
            // INITIAL geçişte gözlemlenir, hiçbir event TÜKETİLMEZ. Main-pass bir
            // detektör burada çalışmaz: sohbet yüzeyi seçilebilir TextView'larla
            // (MarkwonText AndroidView) kaplı ve onlar dokunma akışını tüketiyor —
            // ebeveyn detektörü drag'i hiç göremiyordu (jest "hiç çalışmıyor" bug'ı).
            // Initial geçiş çocuklardan ÖNCE görür; tüketmediğimiz için kaydırma,
            // metin seçimi ve pill scroll davranışları aynen sürer.
            // Jest ekranın HER YERİNDEN başlar; kenar şartı kaldırıldı (kenar sistem
            // geri-jestiyle çakışıyordu). Yanlış tetiklemeyi kenar yerine üç kural önler:
            //  1) composer/pill bölgesi ve üst sekme şeridi hariç (oralarda yatay
            //     kaydırma pill/sekme scroll'udur),
            //  2) yatay baskınlık (dikey scroll jesti tetiklemez),
            //  3) uzun-basış muafiyeti: parmak touchSlop'u uzun-basış süresinden SONRA
            //     aştıysa bu bir metin seçimi sürüklemesidir → jest o dokunuş boyunca
            //     kapalı. Seçim yalnız uzun basışla başlar; jest ise slop'u süre
            //     dolmadan aşar. Böylece seçim jesti yutmadan yaşar (kenar şartına
            //     gerek kalmaz). Karar slop anında BİR kez verilir; sonradan yön
            //     değiştirmek kararı bozmaz.
            //  4) yatay kaydırılabilir içerik bölgeleri (kod bloğu, tablo, görsel
            //     şeridi) hariç: bileşenler sınırlarını LocalChatSwipeExclusions'a
            //     yazar, orada başlayan dokunuş içeriğin kendi kaydırmasıdır.
            //     (Tüketim kontrolü işe yaramazdı: Initial geçişte tüketim henüz
            //     görünmez, Main/Final'e geçmek ise yukarıdaki TextView bug'ını
            //     geri getirirdi — bkz. ChatSwipeExclusion.kt.)
            val chatSwipe = Modifier
                .onGloballyPositioned { chatSurfaceCoords = it }
                .pointerInput(activeCoworkWorkspace, drawerState, wideLayout) {
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Alt bölge (composer + pill satırları) ve üst sekme şeridi hariç.
                    if (down.position.y > size.height - composerZonePx) return@awaitEachGesture
                    if (down.position.y < tabBarZonePx) return@awaitEachGesture
                    // Yatay kaydırılabilir içerikte başlayan dokunuş jest değildir.
                    val downInWindow = chatSurfaceCoords?.localToWindow(down.position)
                    if (downInWindow != null && swipeExclusions.contains(downInWindow)) return@awaitEachGesture
                    var dx = 0f
                    var dy = 0f
                    var decided = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dx += change.position.x - change.previousPosition.x
                        dy += change.position.y - change.previousPosition.y
                        if (!decided) {
                            if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) continue
                            decided = true
                            // Metin seçimi sürüklemesi (uzun basış + tutamak çekme).
                            if (change.uptimeMillis - down.uptimeMillis >= longPressMs) break
                            // Dikey baskın: liste scroll'u.
                            if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) break
                        }
                        when (chatSwipeAction(
                            distanceX = dx,
                            threshold = swipeThresholdPx,
                            coworkFilesAvailable = activeCoworkWorkspace.isNotBlank(),
                            sessionsSwipeEnabled = !wideLayout,
                        )) {
                            ChatSwipeAction.Sessions -> { scope.launch { drawerState.open() }; break }
                            ChatSwipeAction.CoworkFiles -> { onOpenCoworkFiles(activeCoworkWorkspace); break }
                            ChatSwipeAction.None -> Unit
                        }
                    }
                }
            }
            Column(Modifier.fillMaxSize().then(chatSwipe)) {
                // 1. SessionTabBar
                val tabs = uiState.visibleTabs.map { tab ->
                    val repo = tab.title.ifBlank { BACKEND_LABELS[tab.backend] ?: tab.backend }
                    val tabStatus = uiState.tabStatuses[tab.id]
                    val status = when {
                        tabStatus?.awaitingApproval == true -> StatusKind.Attention
                        tabStatus?.running == true -> StatusKind.Running
                        tabStatus?.finishedUnseen == true -> StatusKind.Done
                        else -> null
                    }
                    SessionTabUi(tab.id, tab.backend, repo, tabStatus?.liveTitle.orEmpty(), status)
                }
                SessionTabBar(
                    tabs = tabs,
                    activeId = uiState.activeTabId,
                    onSelect = { actions.tabsDelegate.activateTab(it) },
                    // "+" çipi gerçek bir yeni sekme açar: boş+aktif sekme ekler ve
                    // goToLanding ile boş sohbete iner (yeni-oturum ekranına ATLAMAZ).
                    // Ardından buradan yeni oturum başlatmak ya da çekmeceden eski bir
                    // oturum açmak aktif=boş sekmeyi doldurur → İKİNCİ sekme olarak açılır.
                    // (Reboot'ta bu yanlışlıkla onNewSession'a bağlanıp mevcut sekmeyi eziyordu.)
                    onNewTab = { actions.tabsDelegate.newTab() },
                    onClose = { tabId -> actions.requestCloseTab(tabId) },
                    onLongPress = { tabId ->
                        longClickedTabId = tabId
                        openSheet = "tab_menu"
                    }
                )

                if (backendId == null) {
                    // Aktif backend yokken EmptyState
                    EmptyState(
                        title = "Oturum yok",
                        description = "Sekme aç veya çekmeceden oturum seç.",
                        actionLabel = "Yeni oturum",
                        onAction = onNewSession
                    )
                } else {
                    // 2. Başlık satırı: repo adı + (varsa) canlı başlık. Yenile ve kalan
                    // kullanım kısayolları composer üstü pill'lere taşındı (header sade).
                    val activeTab = uiState.visibleTabs.firstOrNull { it.id == uiState.activeTabId }
                    val activeTabStatus = uiState.tabStatuses[uiState.activeTabId]
                    val headerRepo = activeTab?.title?.ifBlank { BACKEND_LABELS[activeTab.backend] ?: activeTab.backend }.orEmpty()
                    val headerLiveTitle = activeTabStatus?.liveTitle.orEmpty()

                    // Dikey dolgu yok + arama butonu 36dp: 48dp'lik varsayılan IconButton
                    // satırı şişirip sekme çubuğu ile arada ölü bölge bırakıyordu (10.79).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Ui2Tokens.screenPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)
                    ) {
                        ProviderMark(
                            providerMonogram(uiState.activeProviderMonogramId()),
                            modifier = Modifier.clickable {
                                scope.launch { drawerState.open() }
                            }
                        )
                        Text(
                            text = headerRepo,
                            style = MaterialTheme.typography.titleMedium,
                            color = Ui2.colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (headerLiveTitle.isNotBlank()) {
                            Text(
                                text = headerLiveTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = Ui2.colors.ink2,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        // Chat search icon
                        IconButton(
                            onClick = { actions.openChatSearch() },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.Search, "Sohbet içinde ara", tint = Ui2.colors.ink2)
                        }
                        // RunPod pill: başlık satırının EN SAĞINDA (kullanıcı isteği
                        // 14.08.2026). Eskiden composer üstü çip satırındaydı; oturum
                        // ayarı değil, makine yaşam döngüsü kontrolü — çipler arasında
                        // kaybolmasın diye üst şeride alındı.
                        if (backendId == "opencode2-app") {
                            val runPodUriHandler = LocalUriHandler.current
                            // Pill gecis halindeyken (basliyor/duruyor) durumu OTOMATIK
                            // tazele. App aksi halde runpod durumunu yalniz manuel eylemde
                            // (Kullanim/Yenile) poll ediyor; baslatma arka planda bitince
                            // pill "basliyor"da takili kaliyordu. Gecis bitince (ready/hata)
                            // rpBusy false olur, key degisir, dongu iptal edilir.
                            val rpBusy = uiState.opencode.runpod.operationActive ||
                                uiState.opencode.runpod.phase == "starting" ||
                                uiState.opencode.runpod.phase == "stopping"
                            LaunchedEffect(rpBusy) {
                                if (rpBusy) {
                                    while (true) {
                                        kotlinx.coroutines.delay(3000)
                                        actions.refreshRunPodStatus(showErrors = false)
                                    }
                                }
                            }
                            RunPodControlPill(uiState.opencode.runpod) {
                                when (runPodPillAction(uiState.opencode.runpod)) {
                                    "stop" -> actions.stopRunPod()
                                    "connect" -> actions.startRunPod()
                                    else -> runCatching { runPodUriHandler.openUri("https://console.runpod.io/pods") }
                                }
                            }
                        }
                    }

                    // Chat search bar
                    if (uiState.chatSearch.open) {
                        ChatSearchBar(
                            state = uiState.chatSearch,
                            onQueryChange = { actions.updateChatSearchQuery(it) },
                            onPrev = { actions.chatSearchGoPrev() },
                            onNext = { actions.chatSearchGoNext() },
                            onClose = { actions.closeChatSearch() },
                        )
                    }

                    // Çip satırı girdileri: satırın kendisi sohbet kutusunun ÜZERİNE
                    // bindirilir (aşağıda, Alignment.TopStart) — arkasında düz şerit yok.
                    val capabilities = backendCapabilities(backendId, uiState.coworkProvider, uiState.backendCatalog)
                    val session = uiState.backendSession(backendId)
                    val activePromptQueue = uiState.activeQueuedPrompts()

                    val modelOptions = uiState.backendModelOptions(backendId)
                    val permissionOptions = uiState.backendPermissionModeOptions(backendId)
                    val showModelChip = session.model.isNotEmpty() || session.defaultModel.isNotEmpty() || modelOptions.isNotEmpty()
                    val showPermissionChip = capabilities.permissionModes && permissionOptions.isNotEmpty()
                    // Effort listesi ilk dokunuşta yüklenir. Görünürlüğü boş listeye
                    // bağlamak seçiciyi erişilemez yapıyordu; destek sağlayıcıdan türetilir.
                    val showEffortChip = uiState.backendEffortSupported(backendId)
                    if (uiState.offlineConversation) {
                        StatusBadge(
                            text = "Çevrimdışı önbellek · son kaydedilen konuşma",
                            kind = StatusKind.Attention,
                            modifier = Modifier.padding(horizontal = Ui2Tokens.screenPadding),
                        )
                    }

                    // 3. Mesaj listesi — reverseLayout: liste DİBE çapalı (WhatsApp modeli).
                    // Eski kurgu normal layout + "her layout değişiminde post-frame dibe
                    // kaydır" düzeltmesiydi; her metin parçası/araç satırında önce kayan,
                    // sonra geri çekilen kareler görünür titreme yapıyordu. Reverse'te
                    // index 0 = en yeni mesaj alt kenara AYNI ölçüm karesinde yapışır:
                    // akan metin büyürken düzeltme kaydırması hiç gerekmez. Klavye
                    // açılınca da alt kenar çapası doğal korunur — eski IME
                    // dispatchRawDelta hilesi gereksizleşti ve kaldırıldı.
                    val listState = rememberLazyListState()
                    // Takip kararı kullanıcı niyetine bağlı: yukarı sürükleyince kapanır,
                    // kendi eliyle dibe dönünce yeniden açılır (reverse'te dip = listenin
                    // BAŞI, yani canScrollBackward=false).
                    var autoFollow by remember { mutableStateOf(true) }
                    LaunchedEffect(listState) {
                        listState.interactionSource.interactions.collect { interaction ->
                            if (interaction is DragInteraction.Start) autoFollow = false
                        }
                    }
                    LaunchedEffect(listState) {
                        snapshotFlow { !listState.canScrollBackward }
                            .collect { atBottom -> if (atBottom) autoFollow = true }
                    }
                    val listSize = uiState.messagesList.size
                    // Ardışık araç satırları tek grup kartına iner (spam önlemi);
                    // lazy liste artık messagesList'i değil bu görünüm satırlarını çizer.
                    val displayRows = remember(uiState.messagesList) { buildChatDisplayRows(uiState.messagesList) }
                    val displayCount = displayRows.size
                    val approval = uiState.approval
                    var questionDraft by remember(approval?.requestId, approval?.questions) {
                        mutableStateOf(QuestionAnswerDraft())
                    }
                    // Oturuma girerken: daha önce kaydırılmış bir yer HATIRLANIYORSA
                    // oraya dön, yoksa dibe in (reverse'te dip = index 0). Eskiden
                    // koşulsuz dibe iniliyordu; Merkez'e bakıp dönmek bile okunan yeri
                    // kaybettiriyordu (kullanıcı isteği 05.08.2026).
                    var pendingBottomAnchor by remember { mutableStateOf(true) }
                    var pendingRestore by remember { mutableStateOf<ChatScrollAnchor?>(null) }
                    val activeSessionId = uiState.backendSession(backendId).sessionId
                    val sessionKey = "$backendId:$activeSessionId"
                    LaunchedEffect(sessionKey) {
                        val saved = scrollMemory.anchor(sessionKey)
                        if (saved == null) {
                            pendingBottomAnchor = true
                            autoFollow = true
                        } else {
                            pendingRestore = saved
                            pendingBottomAnchor = false
                            autoFollow = false
                        }
                    }
                    LaunchedEffect(pendingBottomAnchor, listSize) {
                        if (pendingBottomAnchor && listSize > 0) {
                            listState.scrollToItem(0)
                            pendingBottomAnchor = false
                        }
                    }
                    // Reverse layout'ta dipteki öğenin BÜYÜMESİ kendiliğinden alta yapışık
                    // kalır; kaydırma yalnız YENİ öğe eklenince gerekir (yeni satır index
                    // 0'a girer ama çapa anahtar bazlı olduğundan görünüm eski satırda
                    // kalır). Onay/plan/bekleme kartları da dip tarafına eklenen öğelerdir,
                    // sayıma dahil.
                    val showCodexPlan = SessionStateReducer.shouldShowCodexPlan(uiState)
                    val tailItemCount = displayCount +
                        (approval?.questions?.size ?: 0) +
                        (if (approval != null) 1 else 0) +
                        (if (uiState.awaitingFirstOutput) 1 else 0) +
                        (if (showCodexPlan) 1 else 0)
                    LaunchedEffect(tailItemCount) {
                        if (autoFollow && !pendingBottomAnchor && pendingRestore == null && tailItemCount > 0) {
                            listState.scrollToItem(0)
                        }
                    }

                    // Mesaj satırlarından ÖNCE emit edilen dip kartları (onay/soru/
                    // plan/bekleme) lazy index'i kaydırır; çapa hesabı bu farkı kullanır.
                    val tailExtra = tailItemCount - displayCount
                    // Hatırlanan yere dön. Satır artık listede değilse (silinmiş/eski
                    // sayfa) dibe düşülür — yanlış bir satırda durmaktansa.
                    LaunchedEffect(pendingRestore, listSize, displayCount) {
                        val anchor = pendingRestore ?: return@LaunchedEffect
                        if (listSize == 0) return@LaunchedEffect
                        val index = chatLazyIndexForRowId(uiState.messagesList, displayRows, anchor.rowId, tailExtra)
                        if (index != null) listState.scrollToItem(index, anchor.offset) else pendingBottomAnchor = true
                        pendingRestore = null
                    }
                    // Kaydırdıkça çapayı yaz. Dipteysek kayıt SİLİNİR: dönüşte takip
                    // moduna girilsin, eski bir satıra çakılıp kalmasın. Effect yalnız
                    // oturum değişince kurulur; liste her token'da değiştiği için
                    // güncel değerler rememberUpdatedState ile okunur.
                    val messagesNow = rememberUpdatedState(uiState.messagesList)
                    val rowsNow = rememberUpdatedState(displayRows)
                    val tailExtraNow = rememberUpdatedState(tailExtra)
                    LaunchedEffect(sessionKey, listState) {
                        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                            .collect { (index, offset) ->
                                // Kendi düzeltme kaydırmamız sürerken yazma: ara kare
                                // yanlış satırı çapa yapar.
                                if (pendingRestore != null || pendingBottomAnchor) return@collect
                                val anchor = if (index == 0 && offset == 0) null else
                                    chatAnchorRowId(messagesNow.value, rowsNow.value, index, tailExtraNow.value)
                                        ?.let { ChatScrollAnchor(it, offset) }
                                scrollMemory.remember(sessionKey, anchor)
                            }
                    }

                    // "Daha eskiyi göster": yalnız sayfalı sağlayıcıda (claude/codex) VE
                    // pencere doluyken. size < messagePageSize ise sunucu her şeyi verdi
                    // demektir (mergeOlder boş sayfada pageSize'ı boyun üstüne çeker) —
                    // eskiden liste dolu olduğu sürece hep görünüyordu.
                    val hasOlder = ConversationPaging.target(uiState) != null &&
                        uiState.messagesList.size >= uiState.messagePageSize

                    // Scroll to match when chat search navigates
                    val chatSrch = uiState.chatSearch
                    LaunchedEffect(chatSrch.selectedIndex) {
                        if (chatSrch.open && chatSrch.selectedIndex >= 0 && chatSrch.matchRowIds.isNotEmpty()) {
                            val targetRowId = chatSrch.matchRowIds.getOrNull(chatSrch.selectedIndex) ?: return@LaunchedEffect
                            // idx_<index> geri düşüşü applyChatSearchMatch ile aynı
                            // enumeration index'i kullanmalı (indexOf ilk kopyayı bulur, kayar).
                            val msgIndex = uiState.messagesList.indices.firstOrNull { i ->
                                (uiState.messagesList[i].rowId.ifBlank { "idx_$i" }) == targetRowId
                            } ?: -1
                            // Mesaj indeksi → görünüm satırı (grup, üyesini kapsar).
                            val dispIndex = displayRows.indexOfFirst { row ->
                                when (row) {
                                    is ChatDisplayRow.Single -> row.index == msgIndex
                                    is ChatDisplayRow.ToolGroup -> msgIndex in row.indices
                                }
                            }
                            if (dispIndex >= 0) {
                                // ATLAMADAN ÖNCE takip kapatılır. autoFollow yalnız parmak
                                // sürüklemesinde kapanıyordu; programatik atlama sürükleme
                                // sayılmadığı için açık kalıyor ve ilk liste değişikliğinde
                                // (arama tam geçmişi arkada yüklemeye devam eder — değişiklik
                                // garanti) tailItemCount effect'i kullanıcıyı dibe geri
                                // ışınlıyordu. Canlı yaşandı 06.08.2026: bulunan mesaj ~1 sn
                                // görünüp kayboluyordu, eski mesajlar okunamıyordu.
                                autoFollow = false
                                pendingBottomAnchor = false
                                // reverseLayout: lazy index sondan sayılır ve satırlardan
                                // ÖNCE emit edilen dip kartları (onay/soru/plan/bekleme =
                                // tailItemCount - displayCount) index'i kaydırır; eşleşen
                                // satır görünümün alt kenarına gelir. __older__ listenin
                                // SONUNDA olduğundan ofset gerektirmez.
                                listState.scrollToItem((tailItemCount - displayCount) + (displayCount - 1 - dispIndex))
                            }
                        }
                    }

                    // Sohbet akışı zemini başlık ve komposerdan bir ton ayrılır (chatBg).
                    // Çip satırı (üst) ve composer pill'leri (alt) bu kutunun ÜZERİNE biner:
                    // arkalarında tam genişlik düz şerit yok, pill dışı alan şeffaf — sohbet
                    // zemini görünür. Liste içeriği contentPadding ile altlarından başlar.
                    Box(Modifier.weight(1f).background(Ui2.colors.chatBg)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(
                            start = Ui2Tokens.screenPadding,
                            end = Ui2Tokens.screenPadding,
                            top = 44.dp,
                            bottom = 48.dp,
                        ),
                        // reverseLayout'ta dikey arrangement ALTA paketlemeli (LazyColumn
                        // imzasındaki varsayılan da reverse'te Arrangement.Bottom'dur);
                        // düz spacedBy içerik ekranı doldurmadığında tepeye yapıştırırdı.
                        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12, Alignment.Bottom)
                    ) {
                        // reverseLayout: emisyon sırası DİPTEN TEPEYE. Görsel olarak en
                        // altta duran öğeler (onay/soru kartları) önce, en üstteki "Daha
                        // eskiyi göster" en son yazılır.
                        if (approval != null) {
                            if (approval.questions.isNotEmpty()) {
                                item(key = "__question_submit__") {
                                    val router = ApprovalActionRouter(uiState, actions)
                                    val complete = questionDraft.isComplete(approval.questions)
                                    QuestionSubmitCard(
                                        answered = questionDraft.answeredCount(approval.questions),
                                        total = approval.questions.size,
                                        onSubmit = {
                                            if (complete) router.answerQuestions(questionDraft.orderedAnswers(approval.questions))
                                        },
                                        onReject = { router.approve(false) },
                                    )
                                }
                                // Görsel sıra korunur: ilk soru en üstte → ters emisyon.
                                itemsIndexed(
                                    items = approval.questions.asReversed(),
                                    key = { _, q -> q.id }
                                ) { _, q ->
                                    val selectedIds = questionDraft.selectedOptionIds(q.id)
                                    UserInputCard(
                                        title = q.question,
                                        options = q.options.map { UserInputOption(it.label, it.description) },
                                        selectedIndices = q.options.indices
                                            .filterTo(mutableSetOf()) { q.options[it].id in selectedIds },
                                        multiple = q.multiple,
                                        onSelect = { index ->
                                            val opt = q.options[index]
                                            questionDraft = questionDraft.select(q, opt)
                                        }
                                    )
                                }
                            } else {
                                item(key = "__approval__") {
                                    val router = ApprovalActionRouter(uiState, actions)
                                    val cwdVal = uiState.backendSession(backendId).cwd.ifBlank { null }
                                    val alwaysLabelVal = router.allowForSession()?.let { "bu oturumda hep izin ver" }
                                    val onAlwaysVal = router.allowForSession()

                                    ApprovalCard(
                                        title = approval.summary.ifBlank { approval.tool },
                                        command = approval.description.ifBlank { null },
                                        cwd = cwdVal,
                                        onPrimary = { router.approve(true) },
                                        onSecondary = { router.approve(false) },
                                        alwaysLabel = alwaysLabelVal,
                                        onAlways = onAlwaysVal,
                                        extra = if (approval.options.isNotEmpty()) {
                                            {
                                                approval.options.forEach { opt ->
                                                    ListRow(
                                                        title = opt.label,
                                                        detail = opt.description.ifBlank { null },
                                                        onClick = { router.selectOption(opt) }
                                                    )
                                                }
                                            }
                                        } else null
                                    )
                                }
                            }
                        }

                        if (uiState.awaitingFirstOutput) {
                            item(key = "__awaiting__") {
                                AgentText("Yanıt bekleniyor…", dim = true)
                            }
                        }

                        if (showCodexPlan) {
                            item(key = "__plan__") {
                                PlanCard(
                                    steps = uiState.codexAppPlan.map { step ->
                                        PlanStepUi(step.text, step.status.equals("completed", true) || step.status.equals("done", true))
                                    }
                                )
                            }
                        }

                        items(
                            count = displayCount,
                            key = { pos ->
                                // Görünüm satırından anahtar: tekilde eski üretimle birebir
                                // (rowId, yoksa kronolojik index); grupta ilk üyenin anahtarı
                                // "grp_" önekiyle — akışta gruba yeni adım eklense de anahtar
                                // SABİT kalır (çapa/expand durumu zıplamaz).
                                when (val row = displayRows[displayCount - 1 - pos]) {
                                    is ChatDisplayRow.Single -> {
                                        val m = uiState.messagesList[row.index]
                                        if (m.rowId.isNotEmpty()) m.rowId else row.index
                                    }
                                    is ChatDisplayRow.ToolGroup -> {
                                        val first = row.indices.first()
                                        val m = uiState.messagesList[first]
                                        "grp_" + m.rowId.ifEmpty { "idx_$first" }
                                    }
                                }
                            }
                        ) { pos ->
                            val displayRow = displayRows[displayCount - 1 - pos]
                            if (displayRow is ChatDisplayRow.ToolGroup) {
                                // Aynı kullanıcı turundaki aynı tür faaliyetler: tek kart;
                                // açılınca üyeler kendi kartlarıyla listelenir.
                                val firstIdx = displayRow.indices.first()
                                val groupKey = "grp_" + (uiState.messagesList[firstIdx].rowId.ifEmpty { "idx_$firstIdx" })
                                var groupExpanded by rememberSaveable(groupKey) { mutableStateOf(false) }
                                // Arama eşleşmesi grubun içindeyse kart çerçeveyle işaretlenir.
                                val srch = uiState.chatSearch
                                val groupHasMatch = srch.open && srch.matchRowIds.isNotEmpty() && displayRow.indices.any { mi ->
                                    srch.matchRowIds.contains(uiState.messagesList[mi].rowId.ifBlank { "idx_$mi" })
                                }
                                // Düşünce grubunda üyeler "🧠" olduğu için kapalı başlıkta son
                                // adımın metni bilgi taşımaz; adım sayısı yeter.
                                val thoughtGroup = displayRow.kind == GroupKind.THOUGHT
                                ToolGroupCard(
                                    count = displayRow.indices.size,
                                    lastSummary = if (thoughtGroup) "" else uiState.messagesList[displayRow.indices.last()].text,
                                    label = if (thoughtGroup) "Düşünce" else "Araç",
                                    modifier = if (groupHasMatch) Modifier.border(1.dp, Ui2.colors.accent.copy(alpha = 0.3f), RoundedCornerShape(Ui2Tokens.cornerInline)) else Modifier,
                                    expanded = groupExpanded,
                                    onToggle = { groupExpanded = !groupExpanded },
                                ) {
                                    displayRow.indices.forEach { mi ->
                                        val step = uiState.messagesList[mi]
                                        if (step.thoughtIndex >= 0) {
                                            val stepDetail = thoughtDetails[step.thoughtIndex]
                                            var stepExpanded by rememberSaveable(step.rowId.ifEmpty { "idx_$mi" }) { mutableStateOf(false) }
                                            ToolCallCard(
                                                kind = if (thoughtGroup) "Düşünce" else "Araç",
                                                summary = step.text.ifBlank { "🧠" },
                                                expanded = stepExpanded,
                                                onToggle = {
                                                    stepExpanded = !stepExpanded
                                                    if (stepExpanded) actions.loadThought(step.thoughtIndex)
                                                },
                                                body = {
                                                    Text(
                                                        stepDetail ?: "Yükleniyor…",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Ui2.colors.ink2,
                                                    )
                                                },
                                            )
                                        } else {
                                            ThoughtStrip(step.text)
                                        }
                                    }
                                }
                                return@items
                            }
                            val index = (displayRow as ChatDisplayRow.Single).index
                            val message = uiState.messagesList[index]
                            val role = message.role.lowercase()
                            val taskNotification = parseTaskNotification(message.text)
                            val searchSt = uiState.chatSearch
                            val isSearchMatch = searchSt.open && searchSt.matchRowIds.isNotEmpty() && searchSt.selectedIndex >= 0
                                && searchSt.selectedIndex < searchSt.matchRowIds.size
                                && searchSt.matchRowIds[searchSt.selectedIndex] == (message.rowId.ifBlank { "idx_$index" })
                            val isAnyMatch = searchSt.open && searchSt.query.length >= 2
                                && searchSt.matchRowIds.contains(message.rowId.ifBlank { "idx_$index" })

                            // modifier for search highlight
                            val highlightMod = if (isSearchMatch) Modifier.border(2.dp, Ui2.colors.accent, RoundedCornerShape(Ui2Tokens.cornerInline))
                                else if (isAnyMatch) Modifier.border(1.dp, Ui2.colors.accent.copy(alpha = 0.3f), RoundedCornerShape(Ui2Tokens.cornerInline))
                                else Modifier

                            // Buradan çatalla claude-app/codex-app/omp'de (cowork'te
                            // sağlayıcısı bunlardansa): bridge fork ucu opencode v1/agy'de yok.
                            // OMP çatallaması native `branch` RPC'sine dayanır (/omp/fork-from);
                            // opencode2'de v2'nin native /fork ucu köprüye bağlı (26.09.2026).
                            val forkSupported = when (uiState.backend) {
                                "claude-app", "codex-app", "omp", "opencode2-app" -> true
                                "cowork" -> normalizeCoworkProvider(uiState.coworkProvider) in setOf("claude-app", "codex-app", "omp", "opencode2-app")
                                else -> false
                            }
                            Box(highlightMod) {
                            when {
                                taskNotification != null -> {
                                    var expanded by rememberSaveable(
                                        message.rowId.ifEmpty { "task_notification_$index" }
                                    ) { mutableStateOf(false) }
                                    TaskNotificationCard(
                                        notification = taskNotification,
                                        expanded = expanded,
                                        onToggle = { expanded = !expanded },
                                    )
                                }
                                role == "user" -> {
                                    // Görsel ekleri thumbnail olarak göster (metne gömülü
                                    // "Ek dosyalar" bloğundan ayrıştırılır); tıkla → tam ekran.
                                    val (displayText, msgImages) = parseMessageImages(message.text)
                                    Column(
                                        Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
                                    ) {
                                        if (msgImages.isNotEmpty()) {
                                            ChatImageRow(
                                                images = msgImages,
                                                load = { p -> actions.loadAttachmentBytes(p) },
                                                onClick = { p -> openAttachment(msgImages, p) },
                                            )
                                        }
                                        if (displayText.isNotBlank()) {
                                            MessageBubble(
                                                displayText,
                                                time = message.time,
                                                onReturnToMessage = { actions.returnToMessage(index) },
                                                onFork = if (forkSupported) ({ actions.forkFromMessage(index) }) else null,
                                            )
                                        } else if (message.time.isNotBlank()) {
                                            Text(
                                                message.time,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Ui2.colors.ink3,
                                            )
                                        }
                                    }
                                }
                                role == "thought" && message.thoughtIndex >= 0 -> {
                                    val detail = thoughtDetails[message.thoughtIndex]
                                    // Açık/kapalı durumu yerel: detayın yüklü olmasından bağımsız
                                    // (eskiden expanded = detail != null idi; detay bir kez yüklenince
                                    // kart kapanamıyordu). Dokununca aç/kapa; ilk açılışta detayı yükle.
                                    var expanded by rememberSaveable(
                                        message.rowId.ifEmpty { message.thoughtIndex.toString() }
                                    ) { mutableStateOf(false) }
                                    // Detay indeksli her düşünce satırı katlanabilir kart: araç
                                    // özeti "Araç", reasoning/thinking "Düşünce" (opencode/codex
                                    // 🧠 satırları dev düz blok yerine kompakt kart olsun).
                                    ToolCallCard(
                                        kind = if (isToolSummary(message.text)) "Araç" else "Düşünce",
                                        summary = message.text.ifBlank { "🧠" },
                                        expanded = expanded,
                                        onToggle = {
                                            expanded = !expanded
                                            if (expanded) actions.loadThought(message.thoughtIndex)
                                        },
                                        body = {
                                            Text(
                                                detail ?: "Yükleniyor…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Ui2.colors.ink2,
                                            )
                                        },
                                    )
                                }
                                // Detay indeksi OLMAYAN uzun düşünce (eski opencode geçmişi vb.):
                                // tam metin satırın kendisinde — kompakt kart, gövde yerelden açılır.
                                role == "thought" && message.text.length > 200 -> {
                                    var expanded by rememberSaveable(
                                        message.rowId.ifEmpty { "idx_$index" }
                                    ) { mutableStateOf(false) }
                                    ToolCallCard(
                                        kind = "Düşünce",
                                        summary = message.text.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "🧠" },
                                        expanded = expanded,
                                        onToggle = { expanded = !expanded },
                                        body = {
                                            Text(
                                                message.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Ui2.colors.ink2,
                                            )
                                        },
                                    )
                                }
                                role == "thought" -> ThoughtStrip(message.text)
                                // Ajan metni MarkwonText ile: uzun basınca seçilebilir,
                                // dosya yolları hyperlink olur (agfile://) → md/docx tam
                                // ekran editörde açılır, diğer türler harici uygulamada
                                // (o durumda gezinme yok). Sonda tümünü-kopyala tuşu.
                                else -> AgentBlock {
                                    // Asistan da "Ek dosyalar" bloğuyla görsel paylaşabilir
                                    // (masaustu skill ekran görüntüsü); kullanıcı tarafıyla
                                    // aynı ayrıştırma, aynı thumbnail/tam ekran yolu.
                                    val (agentText, agentImages) = parseMessageImages(message.text)
                                    if (agentImages.isNotEmpty()) {
                                        ChatImageRow(
                                            images = agentImages,
                                            load = { p -> actions.loadAttachmentBytes(p) },
                                            onClick = { p -> openAttachment(agentImages, p) },
                                            modifier = Modifier.padding(bottom = Ui2Tokens.s8),
                                        )
                                    }
                                    // MarkdownMessage: kod blokları kendi kartında ve
                                    // altlarında kendi "Kopyala" tuşuyla çizilir; düz
                                    // parçalar aynı Markwon yolundan geçer.
                                    MarkdownMessage(
                                        text = agentText,
                                        color = Ui2.colors.ink,
                                        onFileClick = { path ->
                                            if (actions.openLinkedFile(path)) onOpenFileViewer()
                                        },
                                    )
                                    AgentCopyAllRow(agentText, time = message.time)
                                }
                            }
                            } // close Box
                        }

                        if (hasOlder) {
                            item(key = "__older__") {
                                TextButton(
                                    onClick = { actions.showOlderMessages() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Daha eskiyi göster", color = Ui2.colors.accent)
                                }
                            }
                        }
                    }

                    // Çip satırı (model/izin): sohbetin ALT kenarına biner, sola
                    // yaslı (kullanıcı kararı 10.87: durum pill'leriyle yer değişti).
                    // Yatayda durum pill'leri de aynı satırın SAĞ ucuna iner (kullanıcı
                    // kararı 10.99): üst kenar boş kalır, iki grup tek alt satırda.
                    val isLandscape =
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    // Sağdaki pill grubu, composer'ın gönder/durdur düğmelerinin
                    // üstüne taşmasın. Son sınırı metin kutusunun sağ kenarıdır.
                    val composerHasDraft = uiState.input.isNotBlank() || uiState.attachments.isNotEmpty()
                    val composerTrailingActionReserve =
                        if (uiState.running && composerHasDraft) 112.dp else 63.dp
                    val statusPills = listOfNotNull(
                        // Durum pill'i her zaman görünür: onay beklerken kehribar,
                        // akarken mavi, durduğunda nötr gri nokta ("duruyor").
                        // Pill hiç kaybolmaz; boş durumu kullanıcıya belirtir.
                        if (uiState.awaitingApproval) ComposerPillUi("onay bekleniyor", StatusKind.Attention)
                        else if (uiState.running) ComposerPillUi("çalışıyor", StatusKind.Running)
                        else ComposerPillUi("duruyor", dotColor = Ui2.colors.ink3),
                        if (uiState.contextWindow > 0) ComposerPillUi("%${uiState.contextTokens * 100 / uiState.contextWindow}")
                        else null,
                        // Codex oturum hedefi doluyken tek pill: hedef metni kısaltılır,
                        // durum "active" değilse eklenir. Aktifken mavi (çalışıyor), aksi
                        // halde kehribar (dikkat) — duraklamış/kısıtlanmış hedef göze çarpsın.
                        uiState.codex.goal?.takeIf { backendId == "codex-app" }?.let { g ->
                            val statusSuffix = if (g.status.isNotBlank() && g.status != "active") " · ${g.status}" else ""
                            ComposerPillUi(
                                text = "hedef: ${g.objective.take(24)}$statusSuffix",
                                status = if (g.status == "active") StatusKind.Running else StatusKind.Attention,
                                // Dokununca tam hedef + durum/token/süre özeti açılır. Bitmiş
                                // hedefte "Tamam" pill'i de düşürür — bildirimi okumadan
                                // kaybolmasın diye kendiliğinden gitmiyor.
                                onClick = { showGoalDialog = true },
                            )
                        },
                        ComposerPillUi(
                            text = if (uiState.conversationRefreshing) "Yenileniyor…" else "Yenile",
                            icon = Icons.Outlined.Refresh,
                            // Soket tazeleme + konuşma + sekme durumları; sürerken kapalı.
                            onClick = if (uiState.conversationRefreshing) null else ({ actions.resyncConversation() }),
                        ),
                        ComposerPillUi(
                            text = "Kullanım",
                            icon = Icons.Outlined.DataUsage,
                            onClick = {
                                actions.loadUsage()
                                actions.refreshRunPodStatus(showErrors = false)
                                openSheet = "usage"
                            },
                        ),
                    )
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(
                                start = Ui2Tokens.screenPadding,
                                end = if (isLandscape) 0.dp else Ui2Tokens.screenPadding,
                                top = Ui2Tokens.s8,
                                bottom = Ui2Tokens.s8,
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            // fill=false: çipler kısayken satırı zorla doldurmaz (pill grubu
                            // sağa yaslanır); uzunken pill'lere yer bırakıp kendi içinde kayar.
                            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Cowork: aktif sağlayıcı (Claude/Codex/OpenCode) çipi. Dokununca
                            // çalışma alanında hangi ajanla konuşulacağı değiştirilir.
                            if (backendId == "cowork") {
                                StatusBadge(
                                    text = coworkProviderLabel(uiState.coworkProvider),
                                    solid = true,
                                    modifier = Modifier.clickable { openSheet = "provider" }
                                )
                            }

                            if (showModelChip) {
                                // Çipte kısa ad (fable, opus, deepseek flash…); tam kimlik
                                // model seçim sheet'inde durur.
                                val modelText = shortModelLabel(session.model.ifBlank { session.defaultModel })
                                StatusBadge(
                                    text = modelText,
                                    solid = true,
                                    modifier = Modifier.clickable {
                                        actions.loadBackendInfo(backendId)
                                        openSheet = "model"
                                    }
                                )
                            }

                            // Ajan çipi: turu build/plan/yerel/… hangisinin
                            // koşacağı. Model çipinin yanında durur çünkü kendi
                            // modeli olan bir ajan seçilince model de değişir.
                            if (uiState.backendAgentSupported(backendId)) {
                                StatusBadge(
                                    text = uiState.backendAgentLabel(backendId),
                                    solid = true,
                                    modifier = Modifier.clickable {
                                        actions.loadBackendAgents(backendId)
                                        openSheet = "agent"
                                    }
                                )
                            }

                            // Effort modelin bir tur-parametresidir; model pill'inin hemen
                            // yanında durur. Seçenekler pill açılırken canlı yüklenir.
                            if (showEffortChip) {
                                val effortText = uiState.backendEffortLabel(backendId)
                                StatusBadge(
                                    text = effortText,
                                    solid = true,
                                    modifier = Modifier.clickable {
                                        actions.loadBackendEfforts(backendId)
                                        openSheet = "effort"
                                    }
                                )
                            }

                            if (showPermissionChip) {
                                val modeText = permissionModeLabel(session.permissionMode)
                                StatusBadge(
                                    text = modeText,
                                    solid = true,
                                    modifier = Modifier.clickable {
                                        actions.loadBackendPermissionModes(backendId)
                                        openSheet = "mode"
                                    }
                                )
                            }


                            // Skill çipi: hangi skill'lerin kurulu olduğunu gösterir
                            // (cowork'te aktif sağlayıcının, normal sohbetlerde backend'in
                            // kendi listesi). Salt görüntü; agy gibi Agent Skills'i
                            // olmayan backend'lerde hiç çizilmez.
                            if (uiState.backendSkillsSupported(backendId)) {
                                StatusBadge(
                                    text = "Skill",
                                    solid = true,
                                    modifier = Modifier.clickable {
                                        actions.loadBackendSkills(backendId)
                                        openSheet = "skills"
                                    }
                                )
                            }
                        }
                        if (isLandscape) {
                            ComposerPillRow(
                                pills = statusPills,
                                modifier = Modifier.padding(
                                    start = Ui2Tokens.s8,
                                    end = composerTrailingActionReserve,
                                ),
                            )
                        }
                    }

                    // Dikeyde durum pill'leri eskisi gibi sohbetin ÜST kenarına biner,
                    // ortalı (kullanıcı kararı 10.87: çip satırıyla yer değişti).
                    if (!isLandscape) {
                        ComposerPillRow(
                            pills = statusPills,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
                        )
                    }

                    // En alta inme kısayolu: kullanıcı yukarı kaydırmışken görünür.
                    // Alt pill satırının üstünde durur (48dp).
                    if (listState.canScrollBackward) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 48.dp)
                                .background(Ui2.colors.surface2, CircleShape)
                                .border(1.dp, Ui2.colors.lineStrong, CircleShape)
                                .clickable {
                                    scope.launch {
                                        // reverseLayout: dip = index 0.
                                        listState.scrollToItem(0)
                                        autoFollow = true
                                    }
                                }
                                .padding(Ui2Tokens.s8),
                        ) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                "En alta in",
                                tint = Ui2.colors.ink,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    }

                    val slashQuery = uiState.input.takeIf { it.startsWith("/") }
                        ?.removePrefix("/")?.substringBefore(' ')?.lowercase().orEmpty()
                    val slashMatches = if (slashQuery.isBlank()) uiState.slashCommands else {
                        uiState.slashCommands.filter { it.name.contains(slashQuery, true) || it.desc.contains(slashQuery, true) }
                    }.take(6)
                    if (uiState.input.startsWith("/") && slashMatches.isNotEmpty()) {
                        SurfaceCard(modifier = Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4)) {
                            slashMatches.forEach { command ->
                                ListRow(
                                    title = "/${command.name}",
                                    detail = command.desc,
                                    onClick = { actions.insertSlashCommand(command.name) },
                                )
                            }
                        }
                    }

                    // Tur sürerken canlı gönderimin İKİ AYRI yolu var ve ayrı
                    // uçlara dayanıyor: yönlendir = /steer, sıraya bırak =
                    // /follow-up. Capability'den türetiliyor (shared/MidTurnSend.kt);
                    // backend adı gömmek OpenCode'un kuyruk desteğini görünmez
                    // bırakmıştı. OpenCode'da yalnız ikincisi çıkar.
                    val midTurnSteerSupported = uiState.midTurnSteerSupported()
                    val midTurnQueueSupported = uiState.midTurnQueueSupported()
                    // 4. Composer — bağlam pill'leri yukarıda ComposerPillRow ile
                    // sohbet üzerine bindirildi; composer yalnız giriş satırını çizer.
                    Composer(
                        value = uiState.input,
                        onValueChange = { actions.updateInput(it) },
                        running = uiState.running,
                        // Prompt göndermek dibe takibi geri açar: kullanıcı yukarıda
                        // gezinirken gönderirse liste dibe iner ve akışla akmaya devam
                        // eder (yalnız gerçek gönderimde — boş tuşlamada kaydırma yok).
                        onSend = {
                            if (uiState.input.isNotBlank() || uiState.attachments.isNotEmpty()) {
                                autoFollow = true
                                pendingBottomAnchor = true
                            }
                            actions.sendPrompt()
                        },
                        onStop = { actions.stop() },
                        onAttach = { askAttachSource = true },
                        sendEnabled = uiState.input.isNotBlank() || uiState.attachments.isNotEmpty(),
                        queuedPrompts = activePromptQueue,
                        onRemoveQueued = { actions.removeQueuedPrompt(it) },
                        // Ek varken kapalı: bu uçlar yalnız metin taşıyor, ek
                        // sessizce düşerdi — kuyruk yolu ekleri koruyor.
                        onSteer = if (midTurnSteerSupported && uiState.attachments.isEmpty()) ({
                            autoFollow = true
                            pendingBottomAnchor = true
                            actions.backendSendDuringTurn(interrupt = true)
                        }) else null,
                        onFollowUp = if (midTurnQueueSupported && uiState.attachments.isEmpty()) ({
                            autoFollow = true
                            pendingBottomAnchor = true
                            actions.backendSendDuringTurn(interrupt = false)
                        }) else null,
                        attachments = if (uiState.attachments.isNotEmpty()) {
                            {
                                uiState.attachments.forEach { att ->
                                    // Görsel eklerde küçük önizleme, diğerlerinde dosya ikonu + ad; ✕ ile kaldırılır.
                                    AttachmentChip(
                                        att,
                                        onRemove = { actions.removeAttachment(att.path) },
                                        onPreview = { previewPendingAttachment(att.path) },
                                    )
                                }
                            }
                        } else null
                    )
                }
            }
        }
    )
    }

    when (openSheet) {
        "tab_menu" -> {
            val tabId = longClickedTabId
            if (tabId != null) {
                val visibleTabs = uiState.visibleTabs
                val index = visibleTabs.indexOfFirst { it.id == tabId }
                if (index >= 0) {
                    val options = buildList {
                        add(SelectorOption("close", "Kapat"))
                        if (visibleTabs.size > 1) {
                            add(SelectorOption("close_others", "Diğerlerini kapat"))
                        }
                        if (index > 0) {
                            add(SelectorOption("move_left", "Sola taşı"))
                        }
                        if (index < visibleTabs.size - 1) {
                            add(SelectorOption("move_right", "Sağa taşı"))
                        }
                    }
                    SelectorSheet(
                        title = "Sekme İşlemleri",
                        options = options,
                        onSelect = { action ->
                            openSheet = null
                            when (action) {
                                "close" -> actions.tabsDelegate.closeTab(tabId)
                                "close_others" -> actions.tabsDelegate.closeOtherTabs(tabId)
                                "move_left" -> actions.tabsDelegate.moveTab(index, index - 1)
                                "move_right" -> actions.tabsDelegate.moveTab(index, index + 1)
                            }
                        },
                        onDismiss = { openSheet = null }
                    )
                }
            }
        }
        else -> {
            if (backendId != null) {
                val session = uiState.backendSession(backendId)
                when (openSheet) {
                    "model" -> {
                        val rawOptions = uiState.backendModelOptions(backendId)
                        val options = rawOptions.map {
                            // Açıklama yalnız katalogda tanımlı modeller için var;
                            // bulunamayan modelde kutu çizilmez (uydurma yok).
                            val modelInfo = if (backendId == "opencode2-app") {
                                textModelInfo(it.id, it.label)
                            } else {
                                null
                            }
                            SelectorOption(
                                value = it.id,
                                label = it.label,
                                detail = it.detail.ifBlank { null },
                                info = modelInfo?.let { info ->
                                    SelectorInfo(
                                        description = info.description,
                                        bestFor = info.bestFor,
                                        profile = info.profile,
                                        sourceUrl = info.sourceUrl,
                                    )
                                },
                            )
                        }
                        SelectorSheet(
                            title = "Model seç",
                            options = options,
                            selectedValue = session.model,
                            loading = rawOptions.isEmpty(),
                            // Her backend'in kataloğu ayrı; sabitlemeler de öyle.
                            pinScope = "backend-model:$backendId",
                            onSelect = { id ->
                                actions.setBackendModel(backendId, id)
                                openSheet = null
                            },
                            onDismiss = { openSheet = null }
                        )
                    }
                    "mode" -> {
                        val rawOptions = uiState.backendPermissionModeOptions(backendId)
                        val options = rawOptions.map {
                            SelectorOption(value = it.id, label = it.label, detail = it.detail.ifBlank { null })
                        }
                        SelectorSheet(
                            title = "İzin modu seç",
                            options = options,
                            selectedValue = session.permissionMode,
                            loading = rawOptions.isEmpty(),
                            onSelect = { id ->
                                actions.setBackendPermissionMode(backendId, id)
                                openSheet = null
                            },
                            onDismiss = { openSheet = null }
                        )
                    }
                    "agent" -> {
                        val rawOptions = uiState.backendAgentOptions(backendId)
                        SelectorSheet(
                            title = "Ajan seç",
                            subtitle = "Turu hangi ajan koşacak; kendi modeli olan ajan seçilince model de değişir",
                            options = rawOptions.map {
                                SelectorOption(value = it.id, label = it.label, detail = it.detail.ifBlank { null })
                            },
                            selectedValue = uiState.opencodeFamily(backendId).agent,
                            // "otomatik" tek başına dolu liste değil: gerçek ajanlar
                            // gelene kadar yükleniyor göster.
                            loading = rawOptions.size <= 1,
                            detailMaxLines = 3,
                            pinScope = "backend-agent:$backendId",
                            onSelect = { id ->
                                actions.setBackendAgent(backendId, id)
                                openSheet = null
                            },
                            onDismiss = { openSheet = null }
                        )
                    }
                    "effort" -> {
                        val rawOptions = uiState.backendEffortOptions(backendId)
                        val options = rawOptions.map {
                            SelectorOption(value = it.id, label = it.label, detail = it.detail.ifBlank { null })
                        }
                        SelectorSheet(
                            title = "Çaba seç",
                            options = options,
                            selectedValue = session.effort,
                            loading = rawOptions.isEmpty(),
                            onSelect = { id ->
                                actions.setBackendEffort(backendId, id)
                                openSheet = null
                            },
                            onDismiss = { openSheet = null }
                        )
                    }
                    "provider" -> {
                        SelectorSheet(
                            title = "Cowork sağlayıcısı",
                            subtitle = "Çalışma alanında hangi ajanla konuşulacağını seçer",
                            options = COWORK_PROVIDER_OPTIONS.map { SelectorOption(it.id, it.label) },
                            selectedValue = normalizeCoworkProvider(uiState.coworkProvider),
                            onSelect = { id ->
                                actions.switchCoworkProvider(id)
                                openSheet = null
                            },
                            onDismiss = { openSheet = null }
                        )
                    }
                    "skills" -> {
                        // Salt görüntü: skill'ler seçilmez, model tarafından
                        // gerektiğinde yüklenir. Satıra dokunmak sheet'i kapatır.
                        val skills = uiState.backendSkillOptions(backendId)
                        // Bazı backend'lerde skill'ler kategori klasörlerinde durur; kategori
                        // rozet olarak gösterilir (75 skill arasında yön bulmak için).
                        val groups = uiState.backendSkillGroups(backendId)
                        // coworkProviderLabel sağlayıcı kimliğini normalize eder;
                        // cowork dışında backendId'nin kendisi zaten sağlayıcıdır.
                        val provider = coworkProviderLabel(
                            if (backendId == "cowork") uiState.coworkProvider else backendId
                        )
                        SelectorSheet(
                            title = "Kurulu skill'ler",
                            subtitle = "$provider · ${skills.size} skill",
                            options = skills.map {
                                SelectorOption(
                                    value = it.id,
                                    label = it.label,
                                    detail = it.detail.ifBlank { null },
                                    badge = groups[it.id],
                                )
                            },
                            detailMaxLines = 3,
                            emptyText = "Bu sağlayıcıda kurulu skill yok.",
                            onSelect = { openSheet = null },
                            onDismiss = { openSheet = null }
                        )
                    }
                    "usage" -> {
                        // Merkez > Kullanım ile AYNI bileşen (UsageGroupCard) —
                        // sohbette özet sheet olarak (anayasa 2).
                        ModalBottomSheet(
                            onDismissRequest = { openSheet = null },
                            containerColor = Ui2.colors.surface,
                            shape = RoundedCornerShape(topStart = Ui2Tokens.cornerSheet, topEnd = Ui2Tokens.cornerSheet),
                        ) {
                            Column(
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = Ui2Tokens.screenPadding),
                                verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s12),
                            ) {
                                // Merkez > Kullanım'daki göster/gizle tercihi burada da
                                // geçerli — aynı kart kümesi, aynı ayar.
                                val runpodEnabled = uiState.opencode.runpod.enabled
                                var editingCards by remember { mutableStateOf(false) }
                                val shownGroups = visibleUsageGroups(uiState.usage.groups, uiState.hiddenUsageCards)
                                val (throughClaude, afterClaude) = splitUsageGroupsAfterClaude(shownGroups)
                                val showRunpod = runpodEnabled &&
                                    isUsageCardVisible(RUNPOD_USAGE_CARD_KEY, uiState.hiddenUsageCards)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Kalan kullanım", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink)
                                        val note = uiState.usage.note
                                        if (note.isNotBlank()) {
                                            Text(note, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
                                        }
                                    }
                                    if (uiState.usage.groups.isNotEmpty() || runpodEnabled) {
                                        TextButton(onClick = { editingCards = !editingCards }) {
                                            Text(if (editingCards) "Bitti" else "Kartlar", color = Ui2.colors.accent)
                                        }
                                    }
                                    // force=true: köprüdeki 5 dk'lık limit önbelleğini de atla.
                                    TextButton(
                                        onClick = {
                                            actions.loadUsage(force = true)
                                            actions.refreshRunPodStatus(showErrors = false)
                                        },
                                        enabled = !uiState.usageLoading,
                                    ) {
                                        Text(if (uiState.usageLoading) "Yenileniyor…" else "Yenile", color = Ui2.colors.accent)
                                    }
                                }
                                if (editingCards) {
                                    UsageCardVisibilityList(
                                        rows = usageCardToggles(uiState.usage.groups, runpodEnabled, uiState.hiddenUsageCards),
                                        onToggle = actions::setUsageCardVisible,
                                        onShowAll = actions::showAllUsageCards,
                                    )
                                }
                                throughClaude.forEach { group -> UsageGroupCard(group) }
                                if (showRunpod) {
                                    RunPodUsageCard(
                                        status = uiState.opencode.runpod,
                                        onStart = actions::startRunPod,
                                        onStop = actions::stopRunPod,
                                    )
                                }
                                if (uiState.usage.groups.isEmpty()) {
                                    LoadingSkeleton(rows = 3)
                                } else {
                                    afterClaude.forEach { group -> UsageGroupCard(group) }
                                    // Hepsi gizliyken sheet bomboş görünmesin; düzenleme
                                    // zaten bir dokunuş uzakta ama çıkış yolu yazılı olsun.
                                    if (throughClaude.isEmpty() && afterClaude.isEmpty() && !showRunpod && !editingCards) {
                                        Text(
                                            "Tüm kullanım kartları gizli — \"Kartlar\" ile geri getirebilirsin.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Ui2.colors.ink2,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(Ui2Tokens.sheetBottom))
                            }
                        }
                    }
                }
            }
        }
    }

    val menuBackend = longClickedSessionBackend
    if (showSessionMenu && longClickedSessionId != null && menuBackend != null) {
        val targetSession = uiState.backendDiskSessions(menuBackend).find { it.id == longClickedSessionId }
        if (targetSession != null) {
            val menuClipboard = LocalClipboardManager.current
            val options = buildList {
                if (backendSessionPinSupported(menuBackend)) {
                    if (targetSession.pinned) {
                        add(SelectorOption("unpin", "Sabitlemeyi kaldır"))
                    } else {
                        add(SelectorOption("pin", "Sabitle"))
                    }
                }
                if (backendSessionArchiveSupported(menuBackend)) {
                    if (targetSession.archived) {
                        add(SelectorOption("unarchive", "Arşivden çıkar"))
                    } else {
                        add(SelectorOption("archive", "Arşivle"))
                    }
                }
                if (backendSessionRenameSupported(menuBackend)) {
                    add(SelectorOption("rename", "Yeniden adlandır"))
                }
                if (backendSessionOpenOnPcSupported(menuBackend)) {
                    add(SelectorOption("open-on-pc", "Bilgisayarda devam et"))
                }
                // Kimlik her backend'de var; koşulsuz. Detayda tam id durur —
                // kopyalamadan önce doğru oturum mu görülebilsin.
                add(SelectorOption("copy-id", "Kimliği kopyala", detail = targetSession.id))
                add(SelectorOption("delete", "Sil"))
            }
            SelectorSheet(
                title = "Oturum İşlemleri",
                subtitle = targetSession.title.ifBlank { targetSession.id.take(8) },
                options = options,
                onSelect = { value ->
                    showSessionMenu = false
                    when (value) {
                        "pin" -> {
                            actions.setBackendSessionPinned(menuBackend, targetSession.id, true)
                            actions.loadBackendDiskSessions(menuBackend)
                        }
                        "unpin" -> {
                            actions.setBackendSessionPinned(menuBackend, targetSession.id, false)
                            actions.loadBackendDiskSessions(menuBackend)
                        }
                        "archive" -> {
                            actions.setBackendSessionArchived(menuBackend, targetSession.id, true)
                            actions.loadBackendDiskSessions(menuBackend)
                        }
                        "unarchive" -> {
                            actions.setBackendSessionArchived(menuBackend, targetSession.id, false)
                            actions.loadBackendDiskSessions(menuBackend)
                        }
                        "rename" -> {
                            showSessionRename = true
                        }
                        "open-on-pc" -> {
                            targetSession.id.let { actions.claudeAppOpenOnPc(it) }
                        }
                        "copy-id" -> {
                            menuClipboard.setText(AnnotatedString(targetSession.id))
                            actions.notifyUser("Oturum kimliği kopyalandı")
                        }
                        "delete" -> {
                            showSessionDeleteConfirm = true
                        }
                    }
                },
                onDismiss = { showSessionMenu = false }
            )
        }
    }

    // Codex hedef özeti. Hedef state'ten düşerse (başka istemci temizledi, oturum
    // değişti) diyalog da kendiliğinden kapanır — bu yüzden koşul goal'a bağlı.
    uiState.codex.goal?.takeIf { showGoalDialog }?.let { goal ->
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            containerColor = Ui2.colors.surface2,
            title = { Text("Oturum hedefi", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s8)) {
                    Text(goal.objective, style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink)
                    Text(goal.detailText(), style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGoalDialog = false
                        // Çalışan hedefi kapatmak onu SİLMEK olurdu; yalnız durmuş
                        // hedefte pill düşürülür.
                        if (!goal.live) actions.dismissCodexAppGoal()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ui2.colors.accent,
                        contentColor = Ui2.colors.onAccent,
                    )
                ) { Text(if (goal.live) "Kapat" else "Tamam") }
            },
        )
    }

    if (showSessionRename && longClickedSessionId != null && menuBackend != null) {
        val targetSession = uiState.backendDiskSessions(menuBackend).find { it.id == longClickedSessionId }
        if (targetSession != null) {
            var renameValue by remember(targetSession.id) { mutableStateOf(targetSession.title) }
            AlertDialog(
                onDismissRequest = { showSessionRename = false },
                containerColor = Ui2.colors.surface2,
                title = { Text("Yeniden Adlandır", style = MaterialTheme.typography.titleMedium, color = Ui2.colors.ink) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = renameValue,
                            onValueChange = { renameValue = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showSessionRename = false
                            actions.setBackendSessionTitle(menuBackend, targetSession.id, renameValue.trim())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ui2.colors.accent,
                            contentColor = Ui2.colors.onAccent,
                        )
                    ) { Text("Kaydet") }
                },
                dismissButton = {
                    TextButton(onClick = { showSessionRename = false }) { Text("Vazgeç", color = Ui2.colors.ink2) }
                }
            )
        }
    }

    if (showSessionDeleteConfirm && longClickedSessionId != null && menuBackend != null) {
        val targetSession = uiState.backendDiskSessions(menuBackend).find { it.id == longClickedSessionId }
        if (targetSession != null) {
            val displayTitle = targetSession.title.ifBlank { targetSession.id.take(8) }
            ConfirmDialog(
                title = "Oturum silinsin mi?",
                text = "\"$displayTitle\" kalıcı olarak silinir. Bu işlem geri alınamaz.",
                confirmLabel = "Sil",
                onConfirm = {
                    showSessionDeleteConfirm = false
                    actions.deleteBackendDiskSession(menuBackend, targetSession.id)
                },
                onDismiss = { showSessionDeleteConfirm = false }
            )
        }
    }

    if (askAttachSource) {
        FileSourceChoiceDialog(
            onPc = { askAttachSource = false; onPickPcFiles() },
            onPhone = { askAttachSource = false; attachmentPicker.launch("*/*") },
            onDismiss = { askAttachSource = false },
        )
    }

    // Sohbetteki agfile:// dokunuşu artık alttan sheet AÇMAZ: md/docx doğrudan tam
    // ekran native editörde (chat/dosya rotası) açılır — Merkez'deki dosya gezgini
    // ile aynı ekran, aynı davranış. Sheet önizlemesi kaldırıldı.
}

// Cowork oturum satırlarının altın vurgusu (çekmece). Tema paletinde karşılığı
// olmayan tek kullanımlık aksan; açık/koyu temada düşük alpha ile çalışır.
// SessionDrawerContent de kullanıyor: çekmece gövdesi ayrı dosyaya çıkınca
// bu üç yardımcı da dosya-özel olmaktan çıktı.
internal val CoworkGold = Color(0xFFC9A227)

internal enum class ChatSwipeAction { None, Sessions, CoworkFiles }

// Kenardan bağımsız: yön + mesafe yeter. Başlangıç noktası ve ekran genişliği
// artık kararın parçası değil (jest her yerden çalışır).
//
// sessionsSwipeEnabled: geniş yerleşimde (tablet/yatay) KAPALI. Çekmece rail'le
// birlikte sağa taşınınca soldan sağa çekmek onu ters yönden açıyordu; doğru
// yön (sağdan sola) ise zaten Cowork dosyalarında. Dar yerleşimde çekmece hâlâ
// soldan açıldığı için jest orada olduğu gibi kalır.
internal fun chatSwipeAction(
    distanceX: Float,
    threshold: Float,
    coworkFilesAvailable: Boolean,
    sessionsSwipeEnabled: Boolean = true,
): ChatSwipeAction = when {
    sessionsSwipeEnabled && distanceX >= threshold -> ChatSwipeAction.Sessions
    coworkFilesAvailable && distanceX <= -threshold -> ChatSwipeAction.CoworkFiles
    else -> ChatSwipeAction.None
}

// shortModelLabel shared/BackendLabels.kt'ye taşındı: ui3 de aynı kısaltmayı
// gösteriyor, iki arayüz aynı modeli farklı adlandırmasın.

internal fun lastFolder(path: String): String {
    if (path.isBlank()) return ""
    val normalized = path.replace('\\', '/')
    val parts = normalized.split('/').filter { it.isNotBlank() }
    return parts.lastOrNull() ?: path
}

internal fun truncateText(text: String, limit: Int = 40): String {
    if (text.length <= limit) return text
    return text.take(limit) + "..."
}

// Chat search bar: text field + match counter + prev/next + close (Phase 7 — Section 16).
@Composable
private fun ChatSearchBar(
    state: ChatSearchState,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val counter = if (state.matchRowIds.isEmpty()) ""
        else "${state.selectedIndex + 1} / ${state.matchRowIds.size}"

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, "Kapat", tint = Ui2.colors.ink2, modifier = Modifier.size(16.dp))
        }
        BasicTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ui2.colors.ink),
            cursorBrush = SolidColor(Ui2.colors.accent),
            modifier = Modifier
                .weight(1f)
                .background(Ui2.colors.surface, Ui2Tokens.pill)
                .border(1.dp, Ui2.colors.line, Ui2Tokens.pill)
                .padding(horizontal = Ui2Tokens.s12, vertical = Ui2Tokens.s4),
            decorationBox = { inner ->
                if (state.query.isEmpty()) {
                    Text("Mesaj ara…", style = MaterialTheme.typography.bodyMedium, color = Ui2.colors.ink3)
                }
                inner()
            },
        )
        // Geçmiş yükleme durumu: yükleniyor / eksik (hata) / kısmi (truncated).
        when {
            state.loadingHistory -> Text("Geçmiş…", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
            state.historyLoadError -> Text("Eksik geçmiş", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.danger)
            state.truncated -> Text("Kısmi", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
        }
        if (counter.isNotEmpty()) {
            Text(counter, style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink2)
        }
        IconButton(onClick = onPrev, modifier = Modifier.size(28.dp), enabled = state.matchRowIds.size > 1) {
            Text("↑", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.accent)
        }
        IconButton(onClick = onNext, modifier = Modifier.size(28.dp), enabled = state.matchRowIds.size > 1) {
            Text("↓", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.accent)
        }
    }
}
