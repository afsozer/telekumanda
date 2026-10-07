package com.agent.bridge.ui3.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Terminal
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import com.agent.bridge.AlertHostState
import com.agent.bridge.AppAlertAction
import com.agent.bridge.AppAlertHost
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.COWORK_PROVIDER_OPTIONS
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.PendingShare
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.agent.bridge.mimeTypeForExtension
import com.agent.bridge.backendAgentOptions
import com.agent.bridge.backendEffortOptions
import com.agent.bridge.backendModelOptions
import com.agent.bridge.backendPermissionModeOptions
import com.agent.bridge.backendDiff
import com.agent.bridge.backendDiffLoading
import com.agent.bridge.backendDiffSupported
import com.agent.bridge.backendRevertSupported
import com.agent.bridge.backendShareSupported
import com.agent.bridge.backendAgentsInitSupported
import com.agent.bridge.backendSubagentsSupported
import com.agent.bridge.backendDiskSessions
import com.agent.bridge.activeCoworkProjectPath
import com.agent.bridge.backendSession
import com.agent.bridge.backendSessionArchiveSupported
import com.agent.bridge.backendSessionPinSupported
import com.agent.bridge.backendSessionRenameSupported
import com.agent.bridge.backendSessionOpenOnPcSupported
import com.agent.bridge.setBackendSessionTitle
import com.agent.bridge.deleteBackendDiskSession
import com.agent.bridge.setBackendSessionArchived
import com.agent.bridge.setBackendSessionPinned
import com.agent.bridge.backendSkillGroups
import com.agent.bridge.backendSkillOptions
import com.agent.bridge.coworkProvider
import com.agent.bridge.coworkRootPath
import com.agent.bridge.coworkProviderLabel
import com.agent.bridge.hubProjects
import com.agent.bridge.isRunPodModel
import com.agent.bridge.loadBackendAgents
import com.agent.bridge.loadBackendCheckpoints
import com.agent.bridge.loadBackendDiff
import com.agent.bridge.loadBackendEfforts
import com.agent.bridge.loadBackendInfo
import com.agent.bridge.loadBackendPermissionModes
import com.agent.bridge.loadBackendSkills
import com.agent.bridge.revertBackendToMessage
import com.agent.bridge.shareBackendSession
import com.agent.bridge.unshareBackendSession
import com.agent.bridge.initBackendAgentsFile
import com.agent.bridge.unrevertBackend
import com.agent.bridge.loadBackendDiskSessions
import com.agent.bridge.normalizeCoworkProvider
import com.agent.bridge.opencodeAktif
import com.agent.bridge.opencodeFamily
import com.agent.bridge.orderedVisibleBackends
import com.agent.bridge.setBackendAgent
import com.agent.bridge.setBackendEffort
import com.agent.bridge.setBackendModel
import com.agent.bridge.setBackendPermissionMode
import com.agent.bridge.ui2.chat.ChatScrollMemory
import com.agent.bridge.ui2.chat.runPodPillAction
import com.agent.bridge.ui2.chat.SessionDrawerContent
import com.agent.bridge.ui2.chat.SessionTabUi
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui2.hub.CoworkFilesScreen
import com.agent.bridge.ui2.hub.HubAllNotesScreen
import com.agent.bridge.ui2.hub.HubNewWorkspaceScreen
import com.agent.bridge.ui2.hub.HubProjectDetailScreen
import com.agent.bridge.ui2.hub.HubProjectNotesScreen
import com.agent.bridge.ui2.hub.HubProjectSecurityScreen
import com.agent.bridge.ui2.hub.HubProjectsScreen
import com.agent.bridge.ui2.hub.HubWorkspaceScreen
import com.agent.bridge.ui2.hub.NoteEditorScreen
import com.agent.bridge.ui2.hub.PcFilePickerScreen
import com.agent.bridge.ui2.hub.HubFileViewerScreen
import com.agent.bridge.ui2.hub.HubFilesScreen
import com.agent.bridge.ui2.hub.HubOperationsScreen
import com.agent.bridge.ui2.hub.UsageGroupCard
import com.agent.bridge.ui3.shell.Ui3PaylasHedefi
import com.agent.bridge.ui2.settings.SettingsAdvancedScreen
import com.agent.bridge.ui2.settings.SettingsConnectionScreen
import com.agent.bridge.ui2.settings.SettingsMcpAddScreen
import com.agent.bridge.ui2.settings.SettingsMcpScreen
import com.agent.bridge.ui2.settings.SettingsNotificationsScreen
import com.agent.bridge.ui2.settings.SettingsProvidersScreen
import com.agent.bridge.ui2.settings.SettingsUpdateScreen
import com.agent.bridge.ui2.theme.Ui2Theme
import com.agent.bridge.ui3.ayarlar.Ui3AyarRota
import com.agent.bridge.ui3.ayarlar.Ui3AyarlarScreen
import com.agent.bridge.ui3.ayarlar.Ui3GuvenliAglarScreen
import com.agent.bridge.ui3.chat.Ui3ChatScreen
import com.agent.bridge.ui3.chat.Ui3AltAjanSohbeti
import com.agent.bridge.ui3.chat.Ui3BaglamAyrintisi
import com.agent.bridge.ui3.chat.Ui3Degisiklikler
import com.agent.bridge.ui3.chat.Ui3Kurallar
import com.agent.bridge.ui3.chat.Ui3GeriSar
import com.agent.bridge.ui3.chat.Ui3OturumPaylas
import com.agent.bridge.ui3.chat.Ui3AgentsInit
import com.agent.bridge.ui3.chat.Ui3Kullanim
import com.agent.bridge.ui3.chat.Ui3RunPodTusu
import com.agent.bridge.ui3.chat.Ui3Sheet
import com.agent.bridge.ChatBackAction
import com.agent.bridge.chatBackAction
import com.agent.bridge.ui3.chat.Ui3TurAyarlariMenusu
import com.agent.bridge.midTurnQueueSupported
import com.agent.bridge.ui3.chat.ui3OduncOdagiBirak
import com.agent.bridge.ui3.hub.Ui3HubScreen
import com.agent.bridge.ui3.material.MeshBackground
import com.agent.bridge.ui3.material.Ui3OduncKap
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.shell.AppIsland
import com.agent.bridge.ui3.shell.adaBaglamYuzdesi
import com.agent.bridge.ui3.shell.BigTitle
import com.agent.bridge.ui3.shell.GlassDock
import com.agent.bridge.ui3.shell.GlassRail
import com.agent.bridge.ui3.shell.GlassSheet
import com.agent.bridge.ui3.shell.GlassYanPanel
import com.agent.bridge.ui3.shell.GlassTabBar
import com.agent.bridge.ui3.shell.LiteTitleBar
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.shell.Ui3Onayla
import com.agent.bridge.ui3.shell.Ui3AracAyrintisi
import com.agent.bridge.ui3.shell.Ui3CabaMerdiveni
import com.agent.bridge.ui3.shell.Ui3SecenekBicimi
import com.agent.bridge.ui3.shell.Ui3SkillListesi
import com.agent.bridge.ui3.shell.ui3CabaMerdiveniUygun
import com.agent.bridge.ui3.shell.Ui3IkiliSecim
import com.agent.bridge.ui3.shell.Ui3Selector
import com.agent.bridge.ui3.shell.Ui3Spotlight
import com.agent.bridge.ui3.shell.SpotlightEylem
import com.agent.bridge.ui3.shell.Ui3OkumaSutunu
import com.agent.bridge.ui3.shell.UI3_GENIS_ESIK
import com.agent.bridge.ui3.shell.UI3_IKI_PANEL_ESIK
import com.agent.bridge.ui3.shell.UI3_MERKEZ_AZAMI
import com.agent.bridge.ui3.shell.UI3_MERKEZ_IKI_SUTUN_ESIK
import com.agent.bridge.ui3.shell.UI3_SERIT_TAM_ESIK
import com.agent.bridge.ui3.shell.UI3_OTURUM_PANEL_ALAN
import com.agent.bridge.ui3.shell.UI3_OTURUM_PANEL_G
import com.agent.bridge.ui3.shell.UI3_RAIL_ALAN
import com.agent.bridge.ui3.shell.UI3_RAIL_ARA
import com.agent.bridge.ui3.shell.UI3_RAIL_KENAR
import com.agent.bridge.ui3.shell.ui3AdaSeritPayi
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tema
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import com.agent.bridge.hasOperationsAttention
import com.agent.bridge.visibleTabs
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.agent.bridge.BuildConfig

/**
 * ui3 kökü.
 *
 * KATMAN DÜZENİ (bu dosyanın asıl kararı):
 *
 *   Box
 *   ├─ Box .hazeSource(state)      ← KAYNAK: mesh + ekran içeriği
 *   │   ├─ MeshBackground
 *   │   └─ (sekme çubuğu | büyük başlık) + NavHost
 *   ├─ AppIsland                    ← camlar kaynağın DIŞINDA, kardeş olarak
 *   ├─ GlassDock
 *   └─ GlassSheet
 *
 * Camlar kaynak ağacının içine alınırsa Haze kendi çıktısını örneklemeye
 * çalışır. Dışarıda oldukları için dock ve sheet, altlarından geçen **ekran
 * içeriğini** bulanıklaştırıyor — yalnız mesh'i değil.
 *
 * SHEET YÖNLENDİRMESİ burada: composer şeridi ve ⋯ menüsü yalnız bir kimlik
 * gönderiyor ([Ui3Sheet]), hangi seçicinin çizileceğine bu dosya karar veriyor.
 * Sheet açan her ekran kendi sheet'ini kurarsa cam üstüne cam yığılıyor
 * (anayasa v2 §1.3) — tek kap, tek yer.
 */
private const val DOSYALAR_ROTASI = "hub/files"
private const val GORUNTULEYICI_ROTASI = "hub/files/viewer"

// Notlar ve projeler — Faz 6'nın kalanı. Rota adları ui2'dekiyle AYNI: bildirim
// yönlendirmesi ve derin bağlantılar bu adlara bakıyor, ui3'e özel ad uydurmak
// iki arayüz arasında sessiz bir kopukluk üretirdi.
private const val NOTLAR_ROTASI = "hub/notes"
private const val NOT_EDITORU_ROTASI = "hub/project/note"
private const val PROJELER_ROTASI = "hub/projects"
private const val CALISMA_ALANLARI_ROTASI = "hub/workspaces"
private const val YENI_ALAN_ROTASI = "hub/projects/yeni"
private const val PROJE_ROTASI = "hub/project"
private const val PROJE_NOTLARI_ROTASI = "hub/project/notes"
private const val PROJE_GUVENLIK_ROTASI = "hub/project/security"
private const val ALAN_ROTASI = "hub/workspace"
private const val COWORK_DOSYALAR_ROTASI = "hub/cowork-files"
// "chat/" önekli: alt gezinme Sohbet'te kalsın (Ui3Area.forRota önek eşliyor).
private const val EK_SECICI_ROTASI = "chat/dosya-sec"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Ui3Root(
    uiState: RemoteUiState,
    alertHostState: AlertHostState,
    actions: RemoteViewModel,
) = Ui3Tema {
    val hazeState = remember { HazeState() }
    val navController = rememberNavController()
    val girdi by navController.currentBackStackEntryAsState()
    val aktifAlan = Ui3Area.forRota(girdi?.destination?.route)
    val backendId = uiState.backend
    val liteUpdateInfo by actions.updateInfo.collectAsState()

    // Tek sheet yuvası: aynı anda yalnız bir sheet açık olabilir.
    var acikSheet by rememberSaveable { mutableStateOf<String?>(null) }
    // Kapanış animasyonu boyunca gösterilecek son içerik (aşağıda gerekçesi).
    var sonSheetKimligi by remember { mutableStateOf<String?>(null) }
    if (acikSheet != null) sonSheetKimligi = acikSheet

    // OTURUM ÇEKMECESİ AYRI BİR DURUM — sheet yuvasında DEĞİL (18.08.2026,
    // kullanıcı bildirdi: "bir oturuma basılı tuttuğumda liste kapanmasın").
    //
    // Çekmece zaten kendi kabında çiziliyor (GlassYanPanel), ama durumunu
    // `acikSheet`ten ödünç alıyordu. Yuva TEK olduğu için uzun basış bağlam
    // menüsünü oraya yazınca liste ZORUNLU olarak kapanıyordu: sebep bir
    // tasarım kararı değil, iki farklı katmanın aynı değişkeni paylaşmasıydı.
    // Ayrıldı; liste artık menünün ARKASINDA açık kalıyor ve menü kapanınca
    // kullanıcı listeyi bıraktığı yerde buluyor.
    var oturumlarAcik by rememberSaveable { mutableStateOf(false) }
    val liteSohbeteDon: () -> Unit = {
        acikSheet = null
        oturumlarAcik = false
        if (!navController.popBackStack(Ui3Area.Sohbet.rota, inclusive = false)) {
            navController.navigate(Ui3Area.Sohbet.rota) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    // SEKME SEÇMENİN TEK KAPISI. `activateTab`i doğrudan çağırmak yerine
    // buradan geçmek ŞART: geçiş bütün mesaj listesini söküyor ve odağı tutan
    // bir markdown `TextView`i sökülürken uygulama çöküyordu (19.08.2026,
    // gerekçe `ui3OduncOdagiBirak` üstünde). Jest, sekme çubuğu ve Spotlight
    // aynı kapıdan geçsin ki düzeltme birinde unutulmasın.
    val kokGorunum = LocalView.current
    val sekmeSec: (String) -> Unit = { id ->
        kokGorunum.ui3OduncOdagiBirak()
        actions.activateTab(id)
    }

    // ... MENUSUNDEN ACILAN SHEET'IN GERI HEDEFI (22.08.2026, kullanici
    // istedi: "sol uste geri tusu koysak ve 3dot menuye geri donse").
    //
    // Sheet'ler bir yigin degil, tek bir `acikSheet` degeri; "nereden gelindi"
    // bilgisi olmadan geri tusu nereye donecegini bilemez. Menuden acilan her
    // sheet burayi MENU'ye kuruyor, baska her acilis yolu null'a — boylece
    // composer cipinden acilan model secicide ok HIC cizilmiyor.
    var sheetGeriHedefi by remember { mutableStateOf<String?>(null) }
    // Araç ayrıntısı sheet'inin göstereceği mesaj indeksleri.
    var aracIndeksleri by remember { mutableStateOf<List<Int>>(emptyList()) }
    // ...ve o grubun DÜŞÜNCE mi araç mı olduğu. Mesajın kendisinden türetilemez
    // (ChatMessage tür taşımıyor), gruplama kararını veren `GroupKind` ile geliyor.
    var aracGrubuDusunce by remember { mutableStateOf(false) }
    // Sohbetin okunan yeri, OTURUM BAŞINA. Burada yaşamak zorunda: NavHost
    // hedefi değişince Ui3ChatScreen bileşimden düşüyor, ona bağlı bir
    // `remember` Merkez'e bakıp dönmeyi hatırlamaya yetmiyordu. ui2'de de
    // kökte duruyor (`Ui2Root`), sınıf ortak.
    val sohbetKaydirmaBellegi = remember { ChatScrollMemory() }
    // Uzun basılan sekme — sekme menüsü onun üzerinde çalışır.
    var uzunBasilanSekme by remember { mutableStateOf<String?>(null) }

    // Proje kapsamlı not listesinin bağlamı. ui2'de de kökte tutuluyor: ekran
    // yolu ve başlığı rota argümanı olarak taşımıyor (yollar Windows kaçışları
    // içeriyor ve rota kodlamasında bozuluyordu).
    // Uzun basılan oturum (backend, id) — oturum işlemleri sheet'i bunun üstünde çalışır.
    var menuOturumu by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Oturum bağlam menüsünü açan tek nokta. Üç çağıranı var (Spotlight,
    // kalıcı tablet paneli, çekmece) ve üçünde ayrı ayrı yazılıydı; çekmece
    // koda uzakta durduğu için burada düzeltilen bir davranış orada sessizce
    // eskiyebilirdi.
    val oturumMenusuAc: (String, String) -> Unit = { b, id ->
        menuOturumu = b to id
        acikSheet = Ui3Sheet.OTURUM_MENU
    }

    var notProjeYolu by rememberSaveable { mutableStateOf("") }
    var notProjeBasligi by rememberSaveable { mutableStateOf("") }
    var notuHemenOlustur by rememberSaveable { mutableStateOf(false) }
    // Cowork dışındaki oturum klasörü normal PC gezgininde açılır. Windows yolu
    // rota argümanı değildir (kaçış/URL kodlama sorunu); bu ziyarette açılacak
    // klasör kökte tutulup HubFilesScreen'e doğrudan verilir.
    var oturumDosyaAcilisi by rememberSaveable { mutableStateOf("") }
    val genelDosyalariAc: () -> Unit = {
        oturumDosyaAcilisi = ""
        actions.openGeneralFileManager()
        navController.navigate(DOSYALAR_ROTASI)
    }
    val oturumKlasoruAc: (String, String) -> Unit = { oturumBackend, cwd ->
        if (oturumBackend == "cowork") {
            // Cowork kök-kilitli gezgini ve telefon aynasını korur.
            actions.openCoworkFileManager(cwd, sonKlasordenDevam = true)
            navController.navigate(COWORK_DOSYALAR_ROTASI)
        } else {
            // Diğer backend'ler kendi proje cwd'sini normal PC gezgininde,
            // o kök için en son gezilen alt klasörden açar.
            oturumDosyaAcilisi = actions.openSessionFileManager(cwd)
            navController.navigate(DOSYALAR_ROTASI)
        }
    }

    // TELEFONDAN EK: seçilen içerik URI'leri baytlarıyla köprüye yüklenir.
    // Okuma IO'da: büyük dosyada ana iş parçacığı bloklanırsa kare düşer.
    // Mantık ui2'nin `attachmentPicker`ıyla birebir aynı — ad çözümü de dahil,
    // çünkü bazı sağlayıcılar DISPLAY_NAME vermiyor ve adsız ek köprüde
    // "attachment.bin" olarak görünmeli, boş adla değil.
    val ekKapsami = rememberCoroutineScope()
    val ekBaglami = LocalContext.current
    val ekSecici = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uriler ->
        uriler.forEach { uri ->
            ekKapsami.launch(Dispatchers.IO) {
                val cozucu = ekBaglami.contentResolver
                val ad = cozucu.query(uri, null, null, null, null)?.use { imlec ->
                    val sutun = imlec.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (imlec.moveToFirst() && sutun >= 0) imlec.getString(sutun) else null
                } ?: "attachment.bin"
                val baytlar = cozucu.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                actions.attachFile(baytlar, ad, uri.toString(), cozucu.getType(uri))
            }
        }
    }

    // Tam sürümde sheet/çekmece geri katmanı. Lite işleyicisi bu noktada
    // OLAMAZ: NavHost ve sohbet gövdesi aşağıda, bundan sonra kuruluyor ve
    // Compose son kurulan geri işleyicisine öncelik veriyor. Cihazda bunun
    // sonucu sohbet kökünde geri olayının sisteme kaçıp uygulamayı kapatmasıydı.
    // Lite'ın çıkışı kesin engelleyen işleyicisi bütün içeriğin SONUNDA.
    val sistemGeriEylemi = ui3SistemGeriEylemi(
        lite = BuildConfig.IS_LITE,
        sheetAcik = acikSheet != null,
        oturumlarAcik = oturumlarAcik,
    )
    BackHandler(
        enabled = !BuildConfig.IS_LITE &&
            sistemGeriEylemi != Ui3SistemGeriEylemi.TAM_SURUME_BIRAK,
    ) {
        when (sistemGeriEylemi) {
            Ui3SistemGeriEylemi.SHEET_KAPAT -> acikSheet = null
            Ui3SistemGeriEylemi.OTURUMLARI_AC -> oturumlarAcik = true
            Ui3SistemGeriEylemi.OTURUMLARI_KAPAT -> oturumlarAcik = false
            Ui3SistemGeriEylemi.SOHBETE_DON -> liteSohbeteDon()
            Ui3SistemGeriEylemi.TAM_SURUME_BIRAK -> Unit
        }
    }

    /*
     * GERİ TUŞU KÖK ALANLARDA — ui2 paritesi, ui3'e hiç taşınmamıştı.
     *
     * Kullanıcı bildirdi (18.08.2026): "aktif bir sekme açıkken geri tuşu
     * uygulamadan çıkıyor." Doğru: ui3'ün tek geri işleyicisi sheet'lerdi,
     * gerisini sistem alıyordu ve NavHost kök hedefte boş yığınla oturduğu için
     * uygulama kapanıyordu. ui2 aynı sorunu `chatBackAction` ile çözmüş
     * (`Ui2Root`, satır ~214) ve mantık zaten shared'da — ui3 onu ÇAĞIRMIYORDU.
     *
     * Kural:
     *  - Merkez/Ayarlar/Operasyon kökündeyken geri → Sohbet'e döner.
     *  - Sohbet kökündeyken SEKMELER TEK TEK TÜKETİLİR: dolu sekme çarpı
     *    tuşuyla aynı onayı ister, boş sekme sessizce kapanır.
     *  - Elde tek boş sekme kalınca `SYSTEM_BACK` döner, işleyici kapanır ve
     *    uygulama normal şekilde kapanır. "Geri asla çıkmaz" bir tuzak olurdu.
     *
     * KAPANINCA HANGİ SEKMEYE GEÇİLİR motorun kararı (`TabsDelegate.closeTab`):
     * kapanan sekmenin İNDEKSİ korunuyor, yani yerine geçen (sağdaki) sekme
     * aktif oluyor; en sağdaki kapanırken soldaki. Kullanıcının tarif ettiği
     * "bir soldaki" hâli en sık karşılaşılan durum, çünkü yeni sekme sona
     * ekleniyor. Kuralı sola sabitlemek motor katmanını değiştirmek ve ui2'yi
     * de etkilemek demekti (değişmez 3) — dokunulmadı.
     */
    val aktifRota = girdi?.destination?.route
    val kokAlanda = Ui3Area.entries.any { it.rotali && aktifRota == it.rota }
    val sohbetKokunde = aktifRota == Ui3Area.Sohbet.rota
    val geriEylemi = chatBackAction(uiState.visibleTabs, uiState.activeTabId, uiState.backend != null)
    BackHandler(
        // acikSheet/çekmece null şartı: onların kendi işleyicisi yukarıda ve
        // ikisi aynı anda etkin olmamalı.
        enabled = !BuildConfig.IS_LITE && acikSheet == null && !oturumlarAcik && kokAlanda &&
            (!sohbetKokunde || geriEylemi != ChatBackAction.SYSTEM_BACK),
    ) {
        if (!sohbetKokunde) {
            navController.kokeGit(Ui3Area.Sohbet.rota)
        } else when (geriEylemi) {
            ChatBackAction.CONFIRM_CLOSE_TAB ->
                actions.requestCloseTab(uiState.activeTabId, fromBack = true)
            // Boş sekmede soru sormanın anlamı yok: içinde kaybolacak bir şey yok.
            ChatBackAction.CLOSE_EMPTY_TAB -> actions.tabsDelegate.closeTab(uiState.activeTabId)
            // enabled=false olduğu için buraya düşülmez; sistem devralır.
            ChatBackAction.SYSTEM_BACK -> Unit
        }
    }

    val aktifSekme = uiState.visibleTabs.firstOrNull { it.id == uiState.activeTabId }
    // Sekme modeli ui2'deki ChatRootScreen ile BİREBİR: aynı alanlar, aynı
    // durum önceliği (onay > çalışıyor > bitti-görülmedi).
    val sekmeler = uiState.visibleTabs.map { tab ->
        val repo = tab.title.ifBlank { BACKEND_LABELS[tab.backend] ?: tab.backend }
        val durum = uiState.tabStatuses[tab.id]
        SessionTabUi(
            id = tab.id,
            backend = tab.backend,
            repo = repo,
            liveTitle = durum?.liveTitle.orEmpty(),
            status = when {
                durum?.awaitingApproval == true -> StatusKind.Attention
                durum?.running == true -> StatusKind.Running
                durum?.finishedUnseen == true -> StatusKind.Done
                else -> null
            },
        )
    }

    // TUR BAŞLANGIÇ ANLARI, sekme başına.
    //
    // Ada'nın sayacı önce bileşen kompozisyona girdiğinde sıfırlanıyordu; sekme
    // değiştirip geri gelince tur sürerken sayaç baştan başlıyordu (kullanıcı
    // bildirdi). Kaynak state'te turun başlangıç anı YOK, o yüzden burada
    // tutuluyor: kök, sekme değişiminden etkilenmiyor.
    val turBaslangici = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(uiState.running, uiState.activeTabId, uiState.tabStatuses) {
        val aktif = uiState.activeTabId
        uiState.visibleTabs.forEach { sekme ->
            // Aktif sekmede `uiState.running` daha taze; diğerlerinde tabStatuses.
            val calisiyor = if (sekme.id == aktif) {
                uiState.running || uiState.tabStatuses[sekme.id]?.running == true
            } else {
                uiState.tabStatuses[sekme.id]?.running == true
            }
            if (calisiyor) {
                turBaslangici.getOrPut(sekme.id) { System.currentTimeMillis() }
            } else {
                turBaslangici.remove(sekme.id)
            }
        }
    }

    // ── BİLDİRİMDEN GEZİNME ───────────────────────────────────────────────
    //
    // Bu üç nonce ui2 kökünde tüketiliyordu (Ui2Root.kt) ve ui3'e HİÇ
    // taşınmamıştı. Sonuç: bildirime dokununca motor tarafı doğru işi yapıyor
    // — `openApprovalSession` → `openOperation` → `activateTab` ilgili sekmeyi
    // gerçekten aktif ediyor — ama EKRAN değişmiyordu; uygulama en son hangi
    // ekranda bırakıldıysa orası açılıyordu (kullanıcı 18.08.2026: "bildirime
    // bastığımda o ilgili sekmeye değil uygulama hangi ekranda kaldıysa orası
    // açılıyor"). Yani hata sekmenin seçilmemesi değil, sohbete gidilmemesiydi.
    //
    // Hatırlatıcı/not ve dosya görüntüleyici bildirimleri de aynı boşluğa
    // düşüyordu; üçü tek mekanizma olduğu için üçü birden bağlanıyor.
    // ── PAYLAŞILAN DOSYA GELDİ ────────────────────────────────────────────
    //
    // ui2 bunu kökte bir diyalogla soruyordu (Ui2Root.kt); ui3 `pendingShare`i
    // HİÇ toplamıyordu. Sonuç: ui3'te başka bir uygulamadan dosya paylaşmak
    // sessizce hiçbir şey yapmıyordu — dosya "bekleyen" durumda kalıyor,
    // önbellek kopyası da silinmiyordu. Soru artık ui3'ün kendi sheet'i.
    //
    // Kök seviyede duruyor, ui2'deki gerekçenin aynısıyla: paylaşım uygulama
    // hangi ekrandayken gelirse gelsin (hatta kapalıyken açılışta) aynı soru
    // sorulmalı.
    val bekleyenPaylasim by actions.pendingShare.collectAsState()
    LaunchedEffect(bekleyenPaylasim) {
        if (bekleyenPaylasim != null) acikSheet = Ui3Sheet.PAYLAS_HEDEF
    }
    // Sheet başka bir yolla kapanırsa (geri tuşu, örtüye dokunma) bekleyen
    // paylaşım da iptal edilir; yoksa dosya sonsuza kadar bekler ve bir sonraki
    // paylaşımda sheet "eski dosya" ile açılırdı.
    LaunchedEffect(acikSheet) {
        if (acikSheet != Ui3Sheet.PAYLAS_HEDEF && bekleyenPaylasim != null) {
            actions.dismissPendingShare()
        }
    }

    // ── HARİCİ UYGULAMAYLA AÇ / PAYLAŞ ────────────────────────────────────
    //
    // Bu iki olay da ui3 kökünde toplanmıyordu: ViewModel olayı gönderiyor,
    // kimse dinlemiyordu. Yani ui3'teki "harici uygulamayla aç" ve "paylaş"
    // tuşları sessizce ölüydü (dosya ekranları ui2'den ödünç, tuşlar yerinde).
    // Mantık ui2 kökünden birebir; tek yerde toplanıyor ki dosya Cowork'ten,
    // Merkez'den ya da sohbetten açılmış olsun fark etmesin.
    val paylasimBaglami = LocalContext.current
    LaunchedEffect(Unit) {
        actions.openForEditEvents.collect { istek ->
            val uri = FileProvider.getUriForFile(
                paylasimBaglami,
                paylasimBaglami.packageName + ".fileprovider",
                java.io.File(istek.localPath),
            )
            val uzanti = istek.name.substringAfterLast('.', "").lowercase()
            // Android'in haritası .udf'yi bilmiyor ve */* ile seçicide hiçbir
            // uygulama çıkmıyor (hedefler somut tip kaydediyor). Bkz. FileMime.
            val tur = mimeTypeForExtension(uzanti) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
            }
            val duzenle = Intent(Intent.ACTION_EDIT)
                .setDataAndType(uri, tur)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            duzenle.clipData = ClipData.newRawUri(istek.name, uri)
            val niyet = if (duzenle.resolveActivity(paylasimBaglami.packageManager) != null) {
                duzenle
            } else {
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, tur)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .also { it.clipData = ClipData.newRawUri(istek.name, uri) }
            }
            runCatching { paylasimBaglami.startActivity(Intent.createChooser(niyet, istek.name)) }
                .onFailure {
                    Toast.makeText(
                        paylasimBaglami,
                        "Bu dosya türünü açabilecek uygulama yok",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
    LaunchedEffect(Unit) {
        actions.shareFileEvents.collect { istek ->
            val uri = FileProvider.getUriForFile(
                paylasimBaglami,
                paylasimBaglami.packageName + ".fileprovider",
                java.io.File(istek.localPath),
            )
            val niyet = Intent(Intent.ACTION_SEND)
                .setType(istek.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .also { it.clipData = ClipData.newRawUri(istek.name, uri) }
            runCatching {
                paylasimBaglami.startActivity(Intent.createChooser(niyet, "${istek.name} dosyasını paylaş"))
            }.onFailure {
                Toast.makeText(paylasimBaglami, "Dosya paylaşılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val onayNonce by actions.approvalNavigationNonce.collectAsState()
    val goruntuleyiciNonce by actions.fileViewerNavigationNonce.collectAsState()
    val notNonce by actions.noteNavigationNonce.collectAsState()
    // Sohbete KÖK geçişi (dock'un kendi yolu): ayarlar/merkez derinliğinden
    // gelinse bile yığın biriktirmez, geri tuşu başlangıca döner.
    NonceIleGit(onayNonce) { navController.kokeGit(Ui3Area.Sohbet.rota) }
    NonceIleGit(goruntuleyiciNonce) { navController.navigate(GORUNTULEYICI_ROTASI) }
    NonceIleGit(notNonce) { navController.navigate(NOT_EDITORU_ROTASI) }

    // Lite'ta bağlam göstergesi ve onu taşıyan ada yok. Hesabı da çalıştırma;
    // tam sürüm AppIsland için mevcut davranışını korur.
    val baglam = if (BuildConfig.IS_LITE) null else adaBaglamYuzdesi(
        opencodeAktif = uiState.opencodeAktif,
        contextPct = uiState.opencode.contextPct,
        contextTokens = uiState.contextTokens,
        contextWindow = uiState.contextWindow,
    )

    // Dock'un ekrandan yediği yer. Klavye açıkken de aynı: dock gizlenmiyor,
    // klavyenin üstünde duruyor (gerekçesi aşağıdaki uzun notta).
    // 66dp = 50 öğe + 2×4 dock dolgusu + 8 alt boşluk. Lite kabuğunda
    // dock/rail çizilmediği için ekranlar yalnız 8dp nefes payı bırakır.
    val dockYuksekligi = if (BuildConfig.IS_LITE) Ui3Tokens.s8 else 66.dp
    val dockBoslugu = dockYuksekligi
    // Gezinme çubuğunun yediği yer. NavHost artık bunu kendisi almıyor (yukarı
    // bkz.), o yüzden ekranların iz boşluğuna ekleniyor.
    //
    // `exclude(ime)` ŞART (18.08.2026, cihazda ölçüldü): kök Box `imePadding()`
    // taşıyor ve Compose tüketilen inset'i çocuklardan DÜŞÜYOR — klavye
    // açılınca dock'un `navigationBarsPadding()`i 0'a iniyor. Buradaki değer
    // ise düz bir `Dp` olduğu için o düşüşten habersizdi ve 22.3dp olarak
    // kalıyordu: composer 22.3dp daha yukarıda, dock aşağıda, arada ölü alan
    // (kullanıcı: "klavye açıkken dock ile prompt kutusu uzaklaşıyor").
    // Klavye kapalıyken ime = 0, yani değer değişmiyor.
    // Ekran geçişindeki kaymanın piksel karşılığı. Geçiş lambdaları
    // `Density` görmüyor, o yüzden burada bir kez çevriliyor.
    val gecisKaymasi = with(LocalDensity.current) { Ui3Tokens.ekranGecisiKayma.roundToPx() }

    val navCubugu = WindowInsets.navigationBars.exclude(WindowInsets.ime)
        .asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ui3Colors.bg0)
            // Klavye açılınca kabuk yukarı kalksın. Kök Box'ta: hem composer hem
            // dock birlikte kalkar. Olmadığında composer klavyenin ALTINDA kalıyor
            // ve yazdığını göremiyorsun — cihazda görüldü.
            .imePadding()
            .semantics { testTagsAsResourceId = true },
    ) {
        // GENİŞ YERLEŞİM (tablet, 18.08.2026): dock altta yatay şerit olmaktan
        // çıkıp SOL kenarda dikey raile döner ve sohbette oturum listesi kalıcı
        // olarak solda durur. Rail ve panel içeriğin ÜSTÜNDE yüzen cam
        // katmanlar (dock gibi, hazeSource ağacının dışında) — bu yüzden
        // içeriğe onların kapladığı kadar sol dolgu veriliyor.
        val genis = maxWidth >= UI3_GENIS_ESIK
        // Kalıcı panel yalnız GERÇEKTEN yer varken (bkz. UI3_IKI_PANEL_ESIK):
        // tablet dikeyde geniş sayılıyor ama iki panel sohbeti telefondan dar
        // bırakıyor. Dar kalan geniş ekranlarda liste çekmece olarak sürüyor.
        val ikiPanel = maxWidth >= UI3_IKI_PANEL_ESIK
        val oturumPaneliVar = !BuildConfig.IS_LITE && ikiPanel && aktifAlan == Ui3Area.Sohbet
        // Composer şeridi ve ⋯ menüsü AYNI ölçüye bakar: menü, şeritte
        // gizlenenleri satır olarak gösteriyor — ikisi ayrı eşikten karar
        // verse aynı anda ikisi birden çizilirdi.
        val seritTam = maxWidth >= UI3_SERIT_TAM_ESIK
        // Merkez'in iki sütunu ve sheet'in yan panele dönmesi geniş yerleşimin
        // parçası ama kendi eşikleri var (yer gerçekten yetiyor mu).
        val merkezIkiSutun = maxWidth >= UI3_MERKEZ_IKI_SUTUN_ESIK
        val icerikSolBosluk = when {
            BuildConfig.IS_LITE || !genis -> 0.dp
            oturumPaneliVar -> UI3_RAIL_ALAN + UI3_OTURUM_PANEL_ALAN
            else -> UI3_RAIL_ALAN
        }
        // SEKME ÇUBUĞUNUN "＋"I ADANIN ALTINDA KALMASIN.
        //
        // Geniş yerleşimde ikisi AYNI dikey bantta: ada durum çubuğunun hemen
        // altında ekranın sağ ucunda, "＋" ise okuma sütununun sağ ucunda ve
        // aynı 28dp'lik satırda. Sütun ekrandan ne kadar dar kalıyorsa ada o
        // kadar uzağa düşüyor; sütun ekranı doldurduğunda (840-1080dp bandı,
        // tablet DİKEY dahil) ada tam "＋"ın üstüne oturuyor. Salt gösterge
        // iken bu yalnız görüntü kusuruydu; ada dokunulabilir olunca "＋"ın
        // dokunuşunu yiyor. Eksik kalan payı çubuğa iz boşluğu olarak veriyoruz
        // — sütun zaten yeterince içerideyse bu 0'dır ve hiçbir şey değişmez.
        val adaSeritPayi = if (BuildConfig.IS_LITE) 0.dp
            else ui3AdaSeritPayi(genis, maxWidth, icerikSolBosluk)

        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            MeshBackground(Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(start = icerikSolBosluk),
            ) {
                if (BuildConfig.IS_LITE) {
                    LiteTitleBar(
                        uiState = uiState,
                        onOturumlarAc = { oturumlarAcik = true },
                        onGuncellemeAc = {
                            if (girdi?.destination?.route == Ui3AyarRota.GUNCELLEME) {
                                liteSohbeteDon()
                            } else {
                                oturumlarAcik = false
                                navController.navigate(Ui3AyarRota.GUNCELLEME) { launchSingleTop = true }
                            }
                        },
                        guncellemeVar = liteUpdateInfo != null,
                        guncellemeEkraniAcik = girdi?.destination?.route == Ui3AyarRota.GUNCELLEME,
                        modifier = Modifier.padding(
                            start = Ui3Tokens.s16,
                            end = Ui3Tokens.s16,
                            top = Ui3Tokens.s4,
                            bottom = Ui3Tokens.s4,
                        ),
                    )
                }
                // BAŞLIK ARTIK NAVHOST'UN İÇİNDE.
                //
                // Dışarıdayken rota değişir değişmez yeniden çiziliyordu: Merkez
                // başlığı bir anda kayboluyor, ekran geçişi ondan SONRA
                // animasyonla başlıyordu (kullanıcı bildirdi). Başlık hedefin
                // parçası olunca içerikle birlikte animasyonlanıyor.
                NavHost(
                    navController = navController,
                    startDestination = Ui3Area.Sohbet.rota,
                    // NavHost'a ALT İNSET YOK — ne dock payı ne de gezinme
                    // çubuğu payı (kullanıcı kararı 18.08.2026).
                    //
                    // Önce burada `navigationBarsPadding()` vardı ve bu, hiçbir
                    // ekranın jest çubuğu şeridine ULAŞAMAMASI demekti: altta
                    // ekran boyu bir düz bant kalıyordu ve aydınlık temada beyaz
                    // okunuyordu (kullanıcı "orada beyaz bir arka plan çiziyor"
                    // dedi; ölçüm sistemin bir şey çizmediğini, o bandın
                    // uygulamanın kendi zemini olduğunu gösterdi).
                    //
                    // Artık içerik ekranın DİBİNE kadar akıyor; dock ve composer
                    // kendi `navigationBarsPadding()`leriyle çubuğun üstünde
                    // duruyor, ekranlar da gerekli boşluğu KAYDIRILAN GÖVDENİN
                    // SONUNA koyuyor (dolgu değil, iz boşluğu) — böylece
                    // kaydırınca içerik camın ve jest çubuğunun arkasından geçiyor.
                    modifier = Modifier.weight(1f),
                    // GEÇİŞ AÇIKÇA VERİLİYOR: burası boş bırakılınca
                    // navigation-compose kendi varsayılanını uyguluyor ve o
                    // varsayılan 700ms'lik bir SOLMA — ekran değiştirmek
                    // gereğinden yavaş hissettiriyordu (kullanıcı 18.08.2026).
                    //
                    // Süreyi 180'e çekmek yetmedi: bu kez "animasyon tamamen
                    // kalkmış gibi" oldu. Saf solma kısa sürede gözle
                    // yakalanmıyor — özellikle iki ekran da aynı cam zemini
                    // paylaşıyorken opaklık değişimi HAREKET olarak okunmuyor.
                    //
                    // Bu yüzden geçişe küçük bir dikey KAYMA eklendi: gelen
                    // ekran solarken s16 kadar yerine oturuyor. Göz kaymayı
                    // opaklıktan çok daha kolay yakalıyor, yani animasyon 260ms
                    // gibi kısa bir sürede bile görünür oluyor.
                    //
                    // ÇIKAN ekran yalnız SOLUYOR, kaymıyor: ikisi birden
                    // kayınca ekranlar birbirini itiyormuş gibi oluyor ve
                    // hareket gereğinden ağır okunuyor (cihazda denendi).
                    enterTransition = {
                        fadeIn(tween(Ui3Tokens.ekranGecisiMs)) +
                            slideInVertically(tween(Ui3Tokens.ekranGecisiMs)) { gecisKaymasi }
                    },
                    exitTransition = { fadeOut(tween(Ui3Tokens.ekranGecisiMs)) },
                    // GERİ yönünde kayma TERS: ekran yukarıdan aşağı yerleşiyor.
                    // Aynı yönde kalsaydı "geri gittim" hissi kaybolurdu.
                    popEnterTransition = {
                        fadeIn(tween(Ui3Tokens.ekranGecisiMs)) +
                            slideInVertically(tween(Ui3Tokens.ekranGecisiMs)) { -gecisKaymasi }
                    },
                    popExitTransition = { fadeOut(tween(Ui3Tokens.ekranGecisiMs)) },
                ) {
                    for (alan in Ui3Area.entries.filter { it.rotali }) {
                        composable(alan.rota) {
                            when (alan) {
                                Ui3Area.Sohbet -> Ui3OkumaSutunu(genis) {
                                    Column(Modifier.fillMaxSize()) {
                                    if (!BuildConfig.IS_LITE) GlassTabBar(
                                        sekmeler = sekmeler,
                                        aktifId = uiState.activeTabId,
                                        onSec = sekmeSec,
                                        // ui2 ile aynı: "+" gerçek bir BOŞ sekme
                                        // açar, yeni-oturum ekranına ATLAMAZ.
                                        onYeni = { actions.tabsDelegate.newTab() },
                                        onKapat = { actions.requestCloseTab(it) },
                                        onUzunBas = {
                                            uzunBasilanSekme = it
                                            acikSheet = Ui3Sheet.SEKME_MENU
                                        },
                                        // Ada artık durum çubuğunun içinde; sekmelerin
                                        // ona yer açması gerekmiyor, o 32dp akışa döndü.
                                        //
                                        // ÜST PAY 4dp (18.08.2026): ada sistem
                                        // kapsülüyle aynı yüksekliğe çekilince
                                        // (31.4dp) dibi 40.0dp'ye indi, sekme
                                        // çubuğu ise durum çubuğunun hemen altında
                                        // 40.3dp'de başlıyordu — cihazdan ölçüldü,
                                        // aralarında 1px kalmıştı ve üst üste
                                        // biniyor gibi duruyordu (kullanıcı bildirdi).
                                        // Sağdaki iz boşluğu adanın altına
                                        // düşmemek için (bkz. `adaSeritPayi`);
                                        // dar ekranda 0.
                                        modifier = Modifier.padding(
                                            top = Ui3Tokens.s4,
                                            bottom = Ui3Tokens.s4,
                                            end = adaSeritPayi,
                                        ),
                                    )
                                    Ui3ChatScreen(
                                    modifier = Modifier.weight(1f),
                                    // Geniş yerleşimde dock yok (rail solda).
                                    altBosluk = if (genis) Ui3Tokens.s8 + navCubugu else dockBoslugu + navCubugu,
                                    uiState = uiState,
                                    sekmelerEtkin = !BuildConfig.IS_LITE,
                                    bildirimlerEtkin = !BuildConfig.IS_LITE,
                                    actions = actions,
                                    hazeState = hazeState,
                                    backendId = backendId,
                                    onSheetAc = { kimlik ->
                                        if (BuildConfig.IS_LITE && kimlik == Ui3Sheet.EK_KAYNAK) {
                                            ekSecici.launch("*/*")
                                        } else {
                                            acikSheetAc(kimlik, backendId, actions) { acikSheet = it }
                                        }
                                    },
                                    onAracAyrintisi = { indeksler, dusunceMi ->
                                        aracIndeksleri = indeksler
                                        aracGrubuDusunce = dusunceMi
                                        acikSheet = Ui3Sheet.ARAC
                                    },
                                    // Yatay jest artık SEKME değiştiriyor;
                                    // paneli açan/dosyalara giden eski jestler
                                    // devredildi (bkz. Ui3SekmeJesti). Karar
                                    // ekranın kendisinde: gezinme yok, yalnız
                                    // `activateTab` — köke taşınacak bir şey yok.
                                    // Markdown'daki dosya yolları tıklanabilir
                                    // (`agfile://`). `openLinkedFile` true derse
                                    // dosya uygulama içinde açılabilir demektir;
                                    // görüntüleyici Dosyalar fazında gelecek,
                                    // şimdilik o rotaya gidiyoruz. false ise
                                    // motor dosyayı harici uygulamaya yolladı.
                                    // `openLinkedFile` true derse dosya uygulama
                                    // içinde açılabilir: görüntüleyiciye git.
                                    // false ise motor harici uygulamaya yolladı.
                                    onDosya = { yol ->
                                        if (actions.openLinkedFile(yol)) {
                                            navController.navigate(GORUNTULEYICI_ROTASI)
                                        }
                                    },
                                    onGoruntuleyici = { navController.navigate(GORUNTULEYICI_ROTASI) },
                                    seritTam = seritTam,
                                    // Tüm backend oturumlarında cwd varsa klasör
                                    // tuşu görünür. Cowork kilitli gezgine, diğerleri
                                    // normal PC gezginine gider; ikisi de son alt
                                    // klasörü hatırlar.
                                    onCalismaKlasoru = backendId?.let { oturumId ->
                                        uiState.backendSession(oturumId).cwd
                                            .takeIf { it.isNotBlank() }
                                            ?.let { yol -> { oturumKlasoruAc(oturumId, yol) } }
                                    },
                                    kaydirmaBellegi = sohbetKaydirmaBellegi,
                                    )
                                    }
                                }
                                // Merkez iki sütuna geçince 720dp'lik okuma
                                // sütunu dar kalıyor — o sınır AKAN METİN için,
                                // burada yan yana iki kart var.
                                Ui3Area.Merkez -> Ui3OkumaSutunu(
                                    genis,
                                    azami = if (merkezIkiSutun) UI3_MERKEZ_AZAMI else 720.dp,
                                ) {
                                    Column(Modifier.fillMaxSize()) {
                                    BigTitle(Ui3Area.Merkez.etiket)
                                    Ui3HubScreen(
                                    altBosluk = if (genis) {
                                        Ui3Tokens.s8 + navCubugu
                                    } else {
                                        dockYuksekligi + navCubugu
                                    },
                                    uiState = uiState,
                                    actions = actions,
                                    ikiSutun = merkezIkiSutun,
                                    onAlanAc = { hedef -> navController.kokeGit(hedef.rota) },
                                    // Geniş yerleşimde çekmece yok: oturum listesi
                                    // sohbetin solunda kalıcı, oraya götürüyoruz.
                                    onOturumlarAc = {
                                        if (ikiPanel) navController.kokeGit(Ui3Area.Sohbet.rota)
                                        else oturumlarAcik = true
                                    },
                                    onDosyalarAc = genelDosyalariAc,
                                    onNotlarAc = { navController.navigate(NOTLAR_ROTASI) },
                                    onProjelerAc = { navController.navigate(PROJELER_ROTASI) },
                                    onAlanlarAc = { navController.navigate(CALISMA_ALANLARI_ROTASI) },
                                    // Kartın "+" tuşu doğrudan yeni alan
                                    // ekranına: aradaki liste adımı atlanıyor.
                                    onYeniAlan = { navController.navigate(YENI_ALAN_ROTASI) },
                                    // HEDEF EKRAN `projectId`'ye göre seçilir
                                    // (18.08.2026, kullanıcı bildirdi: Merkez'den
                                    // bir alana dokununca ESKİ "Çalışma alanı"
                                    // ekranı açılıyordu, "Tüm çalışma alanları"
                                    // üzerinden aynı alana girince YENİ proje
                                    // detayı açılıyordu).
                                    //
                                    // ui2'de bu ayrım en baştan var
                                    // (HubRootScreen.kt: "projectId KONTROLÜ
                                    // ŞART"); ui3 kurulurken düşmüş. Kayıt canlı
                                    // oturumun cwd'sinden doğduğu için projectId
                                    // yalnız HİÇ oturum açılmamış alanda boş —
                                    // yani eski ekran silinemez, o tek durumun
                                    // yedeği. Silinirse oturumsuz alan hiçbir
                                    // yere açılmaz.
                                    onCalismaAlaniAc = { alan ->
                                        val projeId = alan.projectId
                                        if (projeId != null) {
                                            actions.loadProjectDetail(projeId)
                                            navController.navigate(PROJE_ROTASI)
                                        } else {
                                            // workspacePath null olabilir (registry'den
                                            // düşmüş eski kayıt); klasör yolu yedek.
                                            actions.selectCoworkWorkspace(
                                                alan.workspacePath ?: alan.path,
                                            )
                                            navController.navigate(ALAN_ROTASI)
                                        }
                                    },
                                    )
                                    }
                                }
                                // Operasyon kök bir alan: ui2 ekranı geri oku
                                // OLMADAN çağrılır (onBack = null), dock zaten
                                // gezinmeyi taşıyor.
                                Ui3Area.Operasyon -> Ui2AltEkran(dockYuksekligi + navCubugu) {
                                    HubOperationsScreen(
                                        uiState = uiState,
                                        actions = actions,
                                        onBack = null,
                                        onOpenChat = { navController.kokeGit(Ui3Area.Sohbet.rota) },
                                    )
                                }
                                Ui3Area.Ayarlar -> Ui3OkumaSutunu(genis) {
                                    Column(Modifier.fillMaxSize()) {
                                        BigTitle(Ui3Area.Ayarlar.etiket)
                                        Ui3AyarlarScreen(
                                            uiState = uiState,
                                            onAc = { navController.navigate(it) },
                                            // Geniş yerleşimde dock yok (rail solda):
                                            // alta o boşluğu bırakmak gereksiz.
                                            altBosluk = if (genis) Ui3Tokens.s8 + navCubugu else dockYuksekligi + navCubugu,
                                        )
                                    }
                                }
                                else -> YerTutucuEkran(alan, altBosluk = dockYuksekligi + navCubugu)
                            }
                        }
                    }
                    // Dosyalar dock öğesi değil ama gezinilebilir: Merkez'in
                    // hızlı erişiminden açılıyor (ui2'de de hub'ın içinde).
                    composable(DOSYALAR_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubFilesScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onOpenViewer = { navController.navigate(GORUNTULEYICI_ROTASI) },
                                initialPath = oturumDosyaAcilisi,
                            )
                        }
                    }
                    composable(GORUNTULEYICI_ROTASI) {
                        // Geniş yerleşimde alt dock yok, sol rail var. Burada
                        // 66 dp dock payı bırakmak belge yüzeyinin altında ölü
                        // bir şerit oluşturuyordu; yalnız sistem çubuğunu koru.
                        Ui2AltEkran(if (genis) navCubugu else dockYuksekligi + navCubugu) {
                            HubFileViewerScreen(actions, onBack = { navController.popBackStack() })
                        }
                    }

                    // NOTLAR VE PROJELER — Faz 6'nın kalanı.
                    //
                    // ui2'nin zinciri olduğu gibi bağlandı: not editörü, proje
                    // ayrıntısı, güvenlik, çalışma alanı, cowork gezgini ve genel
                    // arama. Editörler (`NoteEditorScreen`,
                    // `MarkdownDocumentEditor`) planın kararı gereği ui2'den
                    // olduğu gibi çağrılıyor; camlaşan yalnız kapları.
                    //
                    // ui2'deki `returnToHubOnChatBack` bayrağı TAŞINMADI: ui3'te
                    // dock her ekrandan tek dokunuşla Merkez'e dönüyor, o yüzden
                    // "sohbetten geri gelince Merkez'e dön" özel kuralına gerek yok.
                    val sohbeteGec = { navController.kokeGit(Ui3Area.Sohbet.rota) }
                    composable(NOTLAR_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubAllNotesScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onOpenNote = { navController.navigate(NOT_EDITORU_ROTASI) },
                            )
                        }
                    }
                    composable(NOT_EDITORU_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            NoteEditorScreen(
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onOpenChat = sohbeteGec,
                            )
                        }
                    }
                    composable(PROJELER_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubProjectsScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onNewWorkspace = { navController.navigate(YENI_ALAN_ROTASI) },
                                onOpenProject = { navController.navigate(PROJE_ROTASI) },
                                onOpenWorkspace = { navController.navigate(ALAN_ROTASI) },
                                onOpenChat = sohbeteGec,
                                onOpenSearch = { acikSheet = Ui3Sheet.SPOTLIGHT },
                            )
                        }
                    }
                    // Aynı ekranın cowork kipi: yalnız çalışma alanları listelenir.
                    composable(CALISMA_ALANLARI_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubProjectsScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onNewWorkspace = { navController.navigate(YENI_ALAN_ROTASI) },
                                onOpenProject = { navController.navigate(PROJE_ROTASI) },
                                onOpenWorkspace = { navController.navigate(ALAN_ROTASI) },
                                onOpenChat = sohbeteGec,
                                workspacesOnly = true,
                                onOpenCoworkFiles = {
                                    actions.openCoworkFileManager(uiState.coworkRootPath)
                                    navController.navigate(COWORK_DOSYALAR_ROTASI)
                                },
                            )
                        }
                    }
                    composable(YENI_ALAN_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubNewWorkspaceScreen(
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onDone = { navController.popBackStack() },
                            )
                        }
                    }
                    composable(PROJE_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubProjectDetailScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onOpenChat = sohbeteGec,
                                onOpenSecurity = { navController.navigate(PROJE_GUVENLIK_ROTASI) },
                                onOpenViewer = { navController.navigate(GORUNTULEYICI_ROTASI) },
                                onOpenFiles = { alanYolu ->
                                    actions.openCoworkFileManager(alanYolu)
                                    navController.navigate(COWORK_DOSYALAR_ROTASI)
                                },
                                onOpenNotes = { alanYolu, hemenOlustur ->
                                    notProjeYolu = alanYolu
                                    notProjeBasligi = uiState.hubProjects()
                                        .firstOrNull { it.workspacePath == alanYolu }
                                        ?.title
                                        .orEmpty()
                                        .ifBlank {
                                            alanYolu.substringAfterLast('/').substringAfterLast('\\')
                                        }
                                    notuHemenOlustur = hemenOlustur
                                    navController.navigate(PROJE_NOTLARI_ROTASI)
                                },
                            )
                        }
                    }
                    composable(PROJE_NOTLARI_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubProjectNotesScreen(
                                actions = actions,
                                projectPath = notProjeYolu,
                                projectTitle = notProjeBasligi,
                                openCreateInitially = notuHemenOlustur,
                                onBack = { navController.popBackStack() },
                                onOpenNote = {
                                    notuHemenOlustur = false
                                    navController.navigate(NOT_EDITORU_ROTASI)
                                },
                            )
                        }
                    }
                    composable(PROJE_GUVENLIK_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubProjectSecurityScreen(uiState, actions) { navController.popBackStack() }
                        }
                    }
                    composable(ALAN_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            HubWorkspaceScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onOpenChat = sohbeteGec,
                                onOpenFiles = { alanYolu ->
                                    actions.openCoworkFileManager(alanYolu)
                                    navController.navigate(COWORK_DOSYALAR_ROTASI)
                                },
                            )
                        }
                    }
                    composable(COWORK_DOSYALAR_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            CoworkFilesScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onOpenViewer = { navController.navigate(GORUNTULEYICI_ROTASI) },
                            )
                        }
                    }
                    // "＋ > Bilgisayardan": köprü gezgini seçici kipinde. Seçilen
                    // dosyalar YALNIZ YOL olarak eklenir; bayt yüklenmez, dosya
                    // zaten PC'de duruyor.
                    composable(EK_SECICI_ROTASI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            PcFilePickerScreen(
                                uiState = uiState,
                                actions = actions,
                                onBack = { navController.popBackStack() },
                                onPicked = { girdiler ->
                                    actions.attachPcFiles(girdiler)
                                    navController.popBackStack()
                                },
                            )
                        }
                    }

                    // AYARLAR ALT EKRANLARI — ui2'den olduğu gibi, Ui2Theme ile.
                    //
                    // Eşleme akışı, pil optimizasyonu izni, OTA indirme, MCP
                    // hedefleri gibi davranışları yeniden yazmak sessiz
                    // regresyon üretirdi ("ağır bileşen ui2'den, kap ui3'ten").
                    // Ui3OduncKap: ui2 bileşenlerine ui3 PALETİNİ veriyor —
                    // zemin saydam, kartlar cam, mürekkep ve vurgu ui3'ten.
                    val geri: () -> Unit = { navController.popBackStack() }
                    composable(Ui3AyarRota.BAGLANTI) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) { SettingsConnectionScreen(uiState, actions, geri) }
                    }
                    // Gövdesi ui2 bileşenlerinden ama dosyası ui3 ağacında:
                    // güvenli ağ listesi yeni bir davranış, ui2'ye eklenmedi.
                    composable(Ui3AyarRota.GUVENLI_AGLAR) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) { Ui3GuvenliAglarScreen(geri) }
                    }
                    composable(Ui3AyarRota.SAGLAYICILAR) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) { SettingsProvidersScreen(uiState, actions, geri) }
                    }
                    composable(Ui3AyarRota.MCP) {
                        Ui2Theme {
                            SettingsMcpScreen(uiState, actions, geri) { hedef ->
                                navController.navigate("${Ui3AyarRota.MCP_EKLE}/$hedef")
                            }
                        }
                    }
                    composable("${Ui3AyarRota.MCP_EKLE}/{hedef}") { girdi ->
                        val hedef = girdi.arguments?.getString("hedef").orEmpty()
                        Ui2AltEkran(dockYuksekligi + navCubugu) { SettingsMcpAddScreen(hedef, actions, geri, geri) }
                    }
                    composable(Ui3AyarRota.BILDIRIMLER) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) { SettingsNotificationsScreen(uiState, actions, geri) }
                    }
                    composable(Ui3AyarRota.GUNCELLEME) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) {
                            SettingsUpdateScreen(actions, if (BuildConfig.IS_LITE) liteSohbeteDon else geri)
                        }
                    }
                    composable(Ui3AyarRota.GELISMIS) {
                        Ui2AltEkran(dockYuksekligi + navCubugu) { SettingsAdvancedScreen(uiState, actions, geri) }
                    }
                }
            }
        }

        // ADA DURUM ÇUBUĞUNUN İÇİNDE, KAMERANIN İKİ YANINDA.
        //
        // Önce `padding(top = 12.dp)` ile en tepeye konmuştu ve tam kameranın
        // altına denk geliyordu (kullanıcı bileşeni hiç görememiş). Sonra
        // `statusBarsPadding` ile çubuğun ALTINA indirildi — o da yanlıştı:
        // sistem kapsülleri çentiği kuşatır, altına inmez. Şimdi ada kesiğin
        // sağ ve sol bandına yerleşiyor, ortası boş; dikey konumu ve bant
        // genişlikleri AppIsland içinde ölçülmüş sayılardan geliyor.
        if (!BuildConfig.IS_LITE) {
            AppIsland(
                calisiyor = uiState.running,
                onayBekliyor = uiState.awaitingApproval,
                baglamYuzdesi = baglam,
                turBaslangici = turBaslangici[uiState.activeTabId],
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Geniş yerleşimde ada durum çubuğunun ALTINA iner (bkz.
                    // AppIsland/hizaSaga); dar ekranda çubuğun içinde kalır.
                    .then(if (genis) Modifier.statusBarsPadding() else Modifier),
                hizaSaga = genis,
                // Dokunuş yalnız geniş yerleşimde bağlanıyor — dar ekranda ada
                // sistem durum çubuğunun içinde ve dokunuşu SystemUI yutuyor
                // (ölçüldü; gerekçe AppIsland.YedekAda'da). Orada null geçmek
                // "yapılamadı" değil, YAPILAMAZ demek.
                onTikla = if (genis) ({ acikSheet = Ui3Sheet.BAGLAM }) else null,
            )
        }

        // DOCK HİÇ GİZLENMİYOR — ui2'nin davranışının aynısı (kullanıcı kararı).
        //
        // Önce klavye açıkken çizilmiyordu; geri gelişi bir "giriş" olduğu için
        // her düzeltmeden sonra gecikme hissi kaldı: animasyonu kaldırdım,
        // `isImeVisible` ile hedef duruma bağladım, yine de yetmedi. Sebep
        // yapısal: pencere `adjustResize` ile küçülüyor ve dock ancak klavye
        // animasyonu boyunca yerine oturuyor.
        //
        // ui2'de böyle bir sorun yok çünkü alt navigasyon HİÇ kaybolmuyor,
        // klavyenin üstünde durup onunla birlikte iniyor. Aynısını yapıyoruz.
        // Karşılığında klavye açıkken 66dp daha az sohbet görünüyor; slash
        // listesinin yükseklik sınırı geldiğinden beri composer taşmıyor.
        // YATAY BOŞLUK COMPOSER'LA AYNI (16dp) — kullanıcı farkı gördü ve
        // "aynı genişlikte olsun mu" diye sordu. Dock tam genişlikteydi,
        // composer 16dp içeride: üst üste duran iki cam kartın kenarları
        // kaçıktı. Dar olan kazandı, çünkü (a) tam genişlikte dock'un yuvarlak
        // köşesini Magic7 Pro'nun kavisli ekran kenarı kesiyordu, (b) camın
        // fikri "arkasından zemin akan yüzen levha" — kenara yapışınca o gider.
        // Bedeli: öğe başına 71.5 → 65dp; en uzun etiket "Oturumlar" ~50dp,
        // sığıyor.
        //
        // Alt boşluk s12 → s8: dock jest çubuğuna yaklaşsın ama değmesin
        // (kullanıcı isteği). `dockYuksekligi` yorumundaki hesap zaten 8 diyordu.
        // Dar ekranda altta dock, geniş ekranda solda rail — İKİSİ AYNI seçim
        // mantığını paylaşıyor (aşağıdaki lambda). Ayrı ayrı yazılırsa dock'ta
        // düzeltilen bir davranış (Oturumlar toggle'ı, alanın köküne geri sarma)
        // rail'de sessizce eskir.
        val alanSec: (Ui3Area) -> Unit = alanSec@{ alan ->
                if (alan == Ui3Area.Oturumlar) {
                    // ui2'deki davranış: sohbette değilsek önce sohbete geç,
                    // sonra oturum listesini aç. Zaten açıksa kapat (toggle).
                    if (aktifAlan != Ui3Area.Sohbet) {
                        navController.kokeGit(Ui3Area.Sohbet.rota)
                        oturumlarAcik = true
                    } else {
                        oturumlarAcik = !oturumlarAcik
                    }
                    return@alanSec
                }
                // Spotlight her ekrandan açılır: gezinme yapmaz, palet açar.
                if (alan == Ui3Area.Spotlight) {
                    acikSheet = if (acikSheet == Ui3Sheet.SPOTLIGHT) null else Ui3Sheet.SPOTLIGHT
                    return@alanSec
                }
                // ZATEN O ALANDAYSAK: gezinme yok, alanın KÖKÜNE geri sarılır.
                //
                // `kokeGit` `restoreState = true` ile gidiyor, yani alanın
                // kaydedilmiş alt rotası geri yükleniyor: Ayarlar > Bağlantı'dan
                // Sohbet'e geçip Ayarlar'a dönünce yine Bağlantı açılıyordu ve
                // oradan çıkmanın dock'ta yolu yoktu (kullanıcı bildirdi).
                // Kaydedilmiş durumu geri yüklemek başka alandan gelirken DOĞRU
                // — kaldığın yer kaybolmasın — o yüzden yalnız aktif alandaki
                // ikinci basış geri sarıyor. Zaten kökteysek popBackStack
                // hiçbir şey yapmıyor (false döner), geri yığını boşalmıyor.
                if (alan == aktifAlan) {
                    navController.popBackStack(alan.rota, inclusive = false)
                    return@alanSec
                }
                navController.kokeGit(alan.rota)
        }

        if (!BuildConfig.IS_LITE && genis) {
            // Rail dikeyde ortalanmış: tabletin üst kenarına yapışmıyor,
            // başparmak menzili ekranın ortasına yakın.
            GlassRail(
                hazeState = hazeState,
                aktif = aktifAlan,
                onayBekliyor = uiState.awaitingApproval,
                operasyonUyarisi = uiState.hasOperationsAttention(),
                onSec = alanSec,
                // Liste kalıcı değilse onu açan tuş raile geri gelir.
                oturumlarTusu = !ikiPanel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = UI3_RAIL_KENAR, top = Ui3Tokens.s8, bottom = Ui3Tokens.s8),
            )
        } else if (!BuildConfig.IS_LITE) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = Ui3Tokens.s8),
            ) {
                GlassDock(
                    hazeState = hazeState,
                    aktif = aktifAlan,
                    onayBekliyor = uiState.awaitingApproval,
                    operasyonUyarisi = uiState.hasOperationsAttention(),
                    onSec = alanSec,
                )
            }
        }

        // ÇEKMECEDEKİ "YENİ OTURUM" — iki düzeltme birden.
        //
        // 1. `newTab()` ÇAĞIRMIYOR. Ödünç gövde ([SessionDrawerContent]) onu
        //    zaten kendisi çağırıyor ("yeni oturum daima YENİ sekmede açılır");
        //    ui3 bir kez daha çağırdığı için her basışta İKİ boş sekme
        //    açılıyordu, biri hemen öksüz kalıyordu. ui2'de bu geri çağrı
        //    yeni-oturum ROTASINA gidiyor, sekme açmıyor — ödünç alırken kaçmış.
        //
        // 2. Cowork oturumundayken yeni oturumu DOĞRUDAN o çalışma alanında
        //    açıyor (kullanıcı isteği 20.08.2026: "aslında direkt yeni oturumu
        //    o cowork alanında açabilir"). Alan ve sağlayıcı zaten belli;
        //    genel ekranda ikisini yeniden seçtirmek boş bir tur. Genel ekran
        //    KAYBOLMUYOR: sekme çubuğundaki "＋" onu açmaya devam ediyor.
        //
        // Alan yolu burada, KOMPOZİSYON anında okunuyor: gövde önce `newTab()`
        // çağırıyor, o da `goToLanding` üzerinden aktif cowork yolunu
        // temizliyor. Lambda'nın içinden okunsaydı boş gelirdi.
        val aktifCoworkAlani = if (backendId == "cowork") uiState.activeCoworkProjectPath else ""
        val yeniOturum: () -> Unit = {
            if (aktifCoworkAlani.isNotBlank()) actions.startCoworkSession(aktifCoworkAlani)
        }

        // OTURUM ÇEKMECESİ — dar ekranda soldan giren panel. Dock'taki
        // Oturumlar tuşu ve sohbetteki sağa kaydırma jesti aynı paneli açar;
        // jest sürerken panel parmağı izler.
        //
        // SHEET'LERDEN ÖNCE ÇİZİLİYOR ve bu konum ZORUNLU. Kardeşlerin çizim
        // sırası kaynak sırası: çekmece sonra gelseydi, üstüne açılan oturum
        // bağlam menüsünün ARKASINDA değil ÖNÜNDE dururdu. Eskiden ikisi aynı
        // anda açık olamadığı için sıra fark etmiyordu; artık ediyor.
        // (zIndex ile çözülmedi: sheet'leri yükseltmek AppAlertHost'u onların
        // altında bırakırdı ve hata kutuları sheet arkasında kaybolurdu.)
        if (!oturumPaneliVar) {
            GlassYanPanel(
                hazeState = hazeState,
                acik = oturumlarAcik,
                onKapat = { oturumlarAcik = false },
            ) {
                OturumCekmecesi(
                    uiState = uiState,
                    actions = actions,
                    acik = oturumlarAcik,
                    onKapat = {
                        oturumlarAcik = false
                        if (BuildConfig.IS_LITE) navController.kokeGit(Ui3Area.Sohbet.rota)
                    },
                    onYeniOturum = {
                        oturumlarAcik = false
                        if (BuildConfig.IS_LITE) navController.kokeGit(Ui3Area.Sohbet.rota)
                        yeniOturum()
                    },
                    onOturumMenusu = oturumMenusuAc,
                    onAyarlar = if (BuildConfig.IS_LITE) {
                        {
                            oturumlarAcik = false
                            navController.kokeGit(Ui3Area.Ayarlar.rota)
                        }
                    } else null,
                )
            }
        }

        // Sekme kapatma onayı: ui2'deki akışın aynısı. requestCloseTab bekleyen
        // isteği kuruyor, onay/vazgeç motor katmanında. ui3 yalnız soruyu soruyor.
        // Onay standart Material diyaloğunda; ayrıca cam sheet açılmaz.
        val kapatilacak by actions.pendingTabClose.collectAsState()
        val kapatilacakSekme = kapatilacak?.let { bekleyen ->
            uiState.visibleTabs.find { it.id == bekleyen.tabId }
        }
        // BOŞ SEKME ONAYSIZ KAPANIR (ui2 paritesi, 18.08.2026 taraması).
        // ui2 bu atlamayı bilerek kökte yapıyor, motorda değil; ui3'e hiç
        // taşınmamıştı ve içi boş "Yeni Sekme"yi kapatmak da soru soruyordu.
        // Kaybolacak bir şey yokken sorulan onay, ui2'de kullanıcı isteğiyle
        // kaldırılmıştı — geri gelmesi düpedüz gerileme.
        val bosSekme = kapatilacakSekme != null && kapatilacakSekme.sessionId.isBlank()
        LaunchedEffect(kapatilacak?.tabId, bosSekme) {
            if (bosSekme) actions.confirmCloseTab()
        }
        // Onay açıkken geri = vazgeç. Bu işleyici yukarıdaki sekme işleyicisinden
        // SONRA kuruluyor ve Compose son kurulanı önceliyor — yoksa geri tuşu
        // onayı kapatmak yerine ikinci bir onay isterdi.
        BackHandler(enabled = kapatilacak != null) { actions.dismissCloseTab() }
        if (kapatilacak != null && !bosSekme) {
            // Sekmenin ADI soruda geçiyor (ui2'deki gibi): birden çok sekme
            // açıkken "Sekme kapatılsın mı?" hangisini sorduğunu söylemiyordu.
            val kapatilacakAd = kapatilacakSekme?.title?.ifBlank {
                BACKEND_LABELS[kapatilacakSekme.backend] ?: kapatilacakSekme.backend
            } ?: "Sekme"
            Ui3Onayla(
                baslik = "Sekme kapatılsın mı?",
                aciklama = "\"$kapatilacakAd\" kapatılır. Oturum sunucuda durmaya " +
                    "devam eder; sekmeyi sonra yeniden açabilirsin.",
                onayMetni = "Kapat",
                yikici = true,
                onOnay = { actions.confirmCloseTab() },
                onVazgec = { actions.dismissCloseTab() },
            )
        }

        // OTURUMLAR ARTIK BU SHEET'TE DEĞİL: soldan giren kendi panelinde
        // (yukarıda, GlassYanPanel) ve kendi durumunda (`oturumlarAcik`).
        // Kaydırma jestiyle aynı yönden açılsın diye.
        GlassSheet(
            hazeState = hazeState,
            acik = acikSheet != null,
            onKapat = { acikSheet = null },
            // Bağlam menüleri (oturum/sekme uzun basışı) sakin: kullanıcı tam
            // saydam camı seçim anında dikkat dağıtıcı buldu. Kapanış
            // animasyonu boyunca da kimlik korunur (sonSheetKimligi), örtü
            // çıkış sırasında sönmesin.
            //
            // SPOTLIGHT de sakin (18.08.2026, kullanıcı bildirdi: "çok şeffaf,
            // arkaplan renginden spotlight görünmüyor"). Ekran görüntüsünde
            // paletin ARKASINDAKİ koyu snackbar cam üzerinden okunuyor ve
            // "SPOTLIGHT" başlığıyla alt satırını yiyordu. Sebep listenin
            // uzunluğu değil ZEMİN: bu sheet ekranın yarısını kaplayan bir
            // METİN yüzeyi — arkadan sızan her şey doğrudan okunan satırın
            // altına düşüyor. Bağlam menüleriyle aynı sebep, daha büyük ölçekte.
            //
            // OTURUM_AD de sakin (18.08.2026): çekmece artık menünün
            // arkasında AÇIK kaldığı için bu sheet'in zemini sohbet değil
            // OTURUM LİSTESİ. Adlandırma alanının altından akan yoğun liste,
            // Spotlight'ta bildirilen okunamama sorununun aynısını üretirdi —
            // çekmeceyi açık bırakma kararının bedeli, önden ödendi.
            sakin = (acikSheet ?: sonSheetKimligi) in setOf(
                Ui3Sheet.OTURUM_MENU,
                Ui3Sheet.SEKME_MENU,
                Ui3Sheet.SPOTLIGHT,
                Ui3Sheet.OTURUM_AD,
            ),
            // Geniş ekranda alttan değil sağdan (tablet fazı 2): tablet YATAYDA
            // alçak ve alttan gelen liste ekranın tamamını yiyordu. Onay
            // sheet'i (yukarıda) bilerek altta kaldı — o bir soru, panel değil.
            yanPanel = genis,
        ) {
            // SPOTLIGHT EYLEMLERİ. Liste burada kuruluyor çünkü hepsi gezinme
            // ya da sheet açma; ikisi de kökün işi. Sıra "ne sıklıkla lazım
            // olur"a göre: yeni oturum en üstte.
            val spotlightEylemleri = listOf(
                SpotlightEylem("yeni", "Yeni oturum", "Boş sekme aç", Icons.Outlined.AddComment) {
                    navController.kokeGit(Ui3Area.Sohbet.rota)
                    actions.tabsDelegate.newTab()
                },
                SpotlightEylem("dosyalar", "Dosyalar", "Bilgisayar ve telefon gezgini", Icons.Outlined.FolderOpen) {
                    genelDosyalariAc()
                },
                SpotlightEylem("notlar", "Notlarım", "Genel ve proje notları", Icons.Outlined.StickyNote2) {
                    navController.navigate(NOTLAR_ROTASI)
                },
                SpotlightEylem("projeler", "Projeler", "Backend proje klasörleri", Icons.Outlined.Folder) {
                    navController.navigate(PROJELER_ROTASI)
                },
                SpotlightEylem("alanlar", "Çalışma alanları", "Cowork alanları", Icons.Outlined.Groups) {
                    navController.navigate(CALISMA_ALANLARI_ROTASI)
                },
                SpotlightEylem("operasyon", "Operasyonlar", "Çalışan süreçler", Icons.Outlined.Terminal) {
                    navController.kokeGit(Ui3Area.Operasyon.rota)
                },
                SpotlightEylem("merkez", "Merkez", "Bento panosu", Icons.Outlined.GridView) {
                    navController.kokeGit(Ui3Area.Merkez.rota)
                },
                SpotlightEylem("kullanim", "Kalan kullanım", "Limitler ve RunPod", Icons.Outlined.DataUsage) {
                    acikSheet = Ui3Sheet.KULLANIM
                },
                SpotlightEylem("ayarlar", "Ayarlar", "Bağlantı, sağlayıcılar, MCP", Icons.Outlined.Settings) {
                    navController.kokeGit(Ui3Area.Ayarlar.rota)
                },
            )
            // ÇIKIŞ BOYUNCA SON İÇERİK KORUNUR. `acikSheet` null'a düştüğü an
            // `SheetIcerigi` erkenden `return` ediyordu; sheet 260ms boyunca
            // BOŞ bir cam panel olarak aşağı kayıyordu. Kapanışı gözle takip
            // edilebilir yapmak için yapılan uzun çıkış animasyonu (GlassSheet)
            // tam da bu yüzden ters tepiyordu.
            SheetIcerigi(
                seritTam = seritTam,
                kimlik = acikSheet ?: sonSheetKimligi,
                uiState = uiState,
                actions = actions,
                backendId = backendId,
                aracIndeksleri = aracIndeksleri,
                aracGrubuDusunce = aracGrubuDusunce,
                uzunBasilanSekme = uzunBasilanSekme,
                onKapat = { acikSheet = null },
                // Dogrudan acilis: donulecek yer yok.
                onSheetAc = { acikSheet = it; sheetGeriHedefi = null },
                onSheetAcMenudan = { acikSheet = it; sheetGeriHedefi = Ui3Sheet.MENU },
                geriHedefi = sheetGeriHedefi,
                onTelefondanEk = { ekSecici.launch("*/*") },
                onBilgisayardanEk = { navController.navigate(EK_SECICI_ROTASI) },
                spotlightEylemleri = spotlightEylemleri,
                onSohbeteGec = { navController.kokeGit(Ui3Area.Sohbet.rota) },
                onProjeAc = { navController.navigate(PROJE_ROTASI) },
                onOturumMenusu = oturumMenusuAc,
                menuOturumu = menuOturumu,
                onSekmeSec = sekmeSec,
                onCalismaKlasoru = { alanYolu ->
                    // Şeritteki klasör tuşuyla aynı ortak kapı: menü yedeği de
                    // backend türüne göre doğru gezgini açmalı.
                    backendId?.let { oturumKlasoruAc(it, alanYolu) }
                },
                bekleyenPaylasim = bekleyenPaylasim,
            )
        }

        // OTURUM PANELİNİN KALICI HÂLİ — geniş ekran (tablet), sohbetteyken.
        // Açılıp kapanmıyor, perde yok, jest yok: iki panelli yerleşimin sol
        // yarısı o, kapatma geri bildirimi de yok çünkü kapanmıyor.
        //
        // DAR ekranın çekmecesi burada DEĞİL, sheet'lerden ÖNCE (yukarıda) —
        // gerekçesi orada. Kalıcı panel bilerek burada kaldı: sheet'lerden
        // sonra çizildiği için perdenin ÜSTÜNDE, yani tablette bir seçici
        // açılınca sol sütun kararmıyor. Bugünkü davranış bu, korundu.
        if (oturumPaneliVar) {
            GlassSurface(
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(
                        start = UI3_RAIL_ALAN,
                        top = Ui3Tokens.s8,
                        bottom = Ui3Tokens.s8,
                        end = UI3_RAIL_ARA,
                    )
                    .width(UI3_OTURUM_PANEL_G)
                    .fillMaxHeight()
                    .testTag("oturum_panel"),
                shape = RoundedCornerShape(Ui3Tokens.r26),
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = Ui3Tokens.s8)) {
                    OturumCekmecesi(
                        uiState = uiState,
                        actions = actions,
                        acik = true,
                        // Kalıcı panelde "kapat" yok: seçim yapınca panel
                        // yerinde kalır, yalnız sağdaki sohbet değişir.
                        onKapat = {},
                        onYeniOturum = yeniOturum,
                        onOturumMenusu = oturumMenusuAc,
                    )
                }
            }
        }

        val uyariBaglami = LocalContext.current
        // Uyarı/hata kutuları: ui3'te parametre `@Suppress("UNUSED_PARAMETER")`
        // ile duruyordu, yani motorun ürettiği her hata sessizce yutuluyordu.
        // ui2 ile aynı bileşen, en üstte.
        //
        // EYLEMİ DE BAĞLI (18.08.2026, ui2 kökü taraması): `onAction`
        // varsayılanı boş lambda ve ui3 onu HİÇ geçmiyordu — kutunun tuşu
        // çiziliyor, basınca hiçbir şey olmuyordu. İki üreteni var: kaydedilen
        // dosyanın "Aç" tuşu (`AppAlertAction.OpenFile`) ve RunPod uyarısının
        // konsol bağlantısı (`OpenUrl`). Sessizce ölü bir tuş, hata mesajının
        // kendisinden beter: kullanıcı bastığını sanıp bekliyor.
        AppAlertHost(
            state = alertHostState,
            onAction = { eylem ->
                when (eylem) {
                    is AppAlertAction.OpenFile -> {
                        actions.openFile(eylem.path, coworkOnly = eylem.coworkOnly)
                        navController.navigate(GORUNTULEYICI_ROTASI)
                    }
                    is AppAlertAction.OpenUrl -> runCatching {
                        uyariBaglami.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(eylem.url)))
                    }.onFailure {
                        Toast.makeText(uyariBaglami, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )

        // LITE GERİ TUŞUNUN SON SÖZÜ.
        //
        // NavHost, sohbet ve ödünç alınan ekranlardan SONRA kurulması şart:
        // Compose geri çağrılarını kurulum sırasının tersinden seçer. Önceki
        // yerleşimde karar doğruydu ama işleyici NavHost'tan önce kurulduğu için
        // sohbet kökünde Android'e kaçabiliyordu (Honor'da canlı üretildi).
        // Bu callback Lite'ta daima etkin; dolayısıyla sistem hiçbir durumda
        // Activity'yi bitiremez. Önce açık katman kapanır; güncellemede
        // sohbet rotasına döner, sohbet kökünde oturum çekmecesini aç/kapat yapar.
        BackHandler(enabled = BuildConfig.IS_LITE) {
            when (ui3SistemGeriEylemi(
                lite = true,
                sheetAcik = acikSheet != null,
                oturumlarAcik = oturumlarAcik,
                liteGuncellemeAcik = girdi?.destination?.route == Ui3AyarRota.GUNCELLEME,
            )) {
                Ui3SistemGeriEylemi.SHEET_KAPAT -> acikSheet = null
                Ui3SistemGeriEylemi.OTURUMLARI_AC -> oturumlarAcik = true
                Ui3SistemGeriEylemi.OTURUMLARI_KAPAT -> oturumlarAcik = false
                Ui3SistemGeriEylemi.SOHBETE_DON -> liteSohbeteDon()
                Ui3SistemGeriEylemi.TAM_SURUME_BIRAK -> Unit
            }
        }
    }
}

/**
 * ui2'den ödünç alınan tam ekran alt ekranların kabı.
 *
 * İki şey yapıyor: [Ui2Theme] sağlıyor (ui2 bileşenleri `LocalUi2Colors`
 * olmadan koyu varsayılana düşüyor) ve dock'un kapladığı yeri alt dolgu olarak
 * bırakıyor (ui2 ekranlarının dock'tan haberi yok).
 */
@Composable
private fun Ui2AltEkran(altBosluk: androidx.compose.ui.unit.Dp, icerik: @Composable () -> Unit) {
    // Ada durum çubuğunun içinde: ödünç ekranın üstünde ona pay ayırmaya gerek yok.
    Box(Modifier.fillMaxSize().padding(bottom = altBosluk)) {
        Ui3OduncKap { icerik() }
    }
}

/**
 * Seçici sheet'i açmadan önce ilgili listeyi yükler.
 *
 * ui2 bunu her çipin `onClick`'inde tekrar tekrar yapıyordu; burada tek yerde.
 * Yükleme çağrıları idempotent (motor kendi önbelleğini yönetiyor).
 */
private fun acikSheetAc(
    kimlik: String,
    backendId: String?,
    actions: RemoteViewModel,
    ata: (String?) -> Unit,
) {
    when (kimlik) {
        Ui3Sheet.MODEL -> backendId?.let { actions.loadBackendInfo(it) }
        Ui3Sheet.AJAN -> backendId?.let { actions.loadBackendAgents(it) }
        Ui3Sheet.EFFORT -> backendId?.let { actions.loadBackendEfforts(it) }
        Ui3Sheet.IZIN -> backendId?.let { actions.loadBackendPermissionModes(it) }
        Ui3Sheet.SKILL -> backendId?.let { actions.loadBackendSkills(it) }
        // Değişiklikler yoklamaya BİNMİYOR: liste burada, açılışta çekiliyor.
        // Her açılışta yeniden istenmesi bilerek — koşu sürüyorsa dosya listesi
        // dakikalar içinde değişiyor ve bayat bir liste incelemenin kendisini
        // yanıltır.
        Ui3Sheet.DEGISIKLIKLER -> backendId?.let { actions.loadBackendDiff(it) }
        // Geri sarma noktaları da açılışta çekiliyor, aynı gerekçeyle: koşu
        // sürerken liste her turda uzuyor, bayat bir liste yanlış noktaya
        // geri sardırır.
        Ui3Sheet.GERI_SAR -> backendId?.let { actions.loadBackendCheckpoints(it) }
        // Kurallar da açılışta çekiliyor (talimat + izin birlikte; görünüm
        // iki bölümü aynı anda gösteriyor, ikisi de aç-çek).
        Ui3Sheet.KURALLAR -> {
            actions.loadOpencode2Instructions()
            actions.loadOpencode2SavedPermissions()
        }
        // KULLANIM BURADA YOK, BİLEREK: yüklemesini ekranın kendisi yapıyor
        // ([Ui3Kullanim]). Burada dururken Spotlight yolu bu kapıdan geçmediği
        // için RunPod durumu hiç sorulmuyordu; gerekçenin tamamı orada.
        "ara" -> {
            // Arama sheet'te değil, sohbetin üstünde açılır: sheet'i kapat.
            actions.openChatSearch()
            ata(null)
            return
        }
    }
    ata(kimlik)
}

/**
 * Nonce ATEŞLER Mİ — tek seferlik gezinme olayının karar kuralı.
 *
 * ui2 kökünden (silinen `Ui2Root.kt`) buraya taşındı, 18.08.2026. Orada
 * yazılmıştı çünkü sorunu ui2 yaşamıştı: çıplak
 * `LaunchedEffect(nonce) { if (nonce > 0) git() }` deseni ekran DÖNDÜĞÜNDE
 * yeniden çalışıyordu — sayaç ViewModel'de yaşıyor, Activity yeniden kurulunca
 * aynı değerle geri geliyor ve koşul hâlâ doğru olduğu için gezinme tekrar
 * tetikleniyordu. Kullanıcı sohbetteyken telefonu yatay çevirince dosyası
 * olmayan boş bir not editörü açılıyor, dikeye dönünce de kapanmıyordu
 * (09.08.2026, canlı).
 *
 * Ayrı bir fonksiyon olarak duruyor ki test edilebilsin: `NavigationNonceTest`.
 */
internal fun navigationNonceFires(nonce: Long, handled: Long): Boolean =
    nonce > 0 && nonce > handled

/** Sistem geri olayının Ui3 kökündeki en üst katmanda ne yapacağını belirler. */
internal enum class Ui3SistemGeriEylemi {
    SHEET_KAPAT,
    OTURUMLARI_AC,
    OTURUMLARI_KAPAT,
    SOHBETE_DON,
    TAM_SURUME_BIRAK,
}

internal fun ui3SistemGeriEylemi(
    lite: Boolean,
    sheetAcik: Boolean,
    oturumlarAcik: Boolean,
    liteGuncellemeAcik: Boolean = false,
): Ui3SistemGeriEylemi = when {
    sheetAcik -> Ui3SistemGeriEylemi.SHEET_KAPAT
    lite && oturumlarAcik -> Ui3SistemGeriEylemi.OTURUMLARI_KAPAT
    lite && liteGuncellemeAcik -> Ui3SistemGeriEylemi.SOHBETE_DON
    lite -> Ui3SistemGeriEylemi.OTURUMLARI_AC
    oturumlarAcik -> Ui3SistemGeriEylemi.OTURUMLARI_KAPAT
    else -> Ui3SistemGeriEylemi.TAM_SURUME_BIRAK
}

/**
 * Tek seferlik gezinme olayını nonce sayacı üzerinden TÜKETİR.
 *
 * Çıplak `LaunchedEffect(nonce) { if (nonce > 0) git() }` yetmiyor: sayaç
 * ViewModel'de yaşıyor, ekran döndüğünde Activity yeniden kurulup aynı değerle
 * geri geliyor ve gezinme tekrar tetikleniyor. Son işlenen değer
 * `rememberSaveable`da tutuluyor — Bundle'a yazıldığı için dönüşte korunuyor.
 */
@Composable
private fun NonceIleGit(nonce: Long, git: () -> Unit) {
    var islenen by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(nonce) {
        val tetikler = navigationNonceFires(nonce, islenen)
        // Sayaç geriye gitmiş olsa bile hizala: süreç ölüp ViewModel sıfırdan
        // kurulmuşsa bir SONRAKİ gerçek olay yutulmasın.
        islenen = nonce
        if (tetikler) git()
    }
}

/** Kök alanlar arası geçiş yığın biriktirmez: geri tuşu her derinlikten başlangıca döner. */
private fun androidx.navigation.NavHostController.kokeGit(rota: String) {
    navigate(rota) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ColumnScope.SheetIcerigi(
    kimlik: String?,
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    backendId: String?,
    aracIndeksleri: List<Int>,
    aracGrubuDusunce: Boolean,
    uzunBasilanSekme: String?,
    onKapat: () -> Unit,
    onSheetAc: (String) -> Unit,
    // Ayni is, ama geri hedefini ... menusune kuruyor.
    onSheetAcMenudan: (String) -> Unit,
    // Dolu ise bu sheet bir yerden gelindi demek; basliga geri oku konur.
    geriHedefi: String?,
    onTelefondanEk: () -> Unit,
    onBilgisayardanEk: () -> Unit,
    spotlightEylemleri: List<SpotlightEylem>,
    onSohbeteGec: () -> Unit,
    onProjeAc: () -> Unit,
    onOturumMenusu: (String, String) -> Unit,
    menuOturumu: Pair<String, String>?,
    // ⋯ menüsündeki "Çalışma klasörü" — gezinme yaptığı için (dosya yöneticisi
    // ayrı bir rota) sheet'in kendisi karar veremiyor, kökten geliyor.
    onCalismaKlasoru: (String) -> Unit,
    // Spotlight'tan oturum seçmek de sekme geçişidir: aynı kapıdan geçer.
    onSekmeSec: (String) -> Unit,
    // Bekleyen paylaşım — PAYLAS_HEDEF sheet'inin tek verisi. Sheet kimliğiyle
    // birlikte geçiyor çünkü kapanış animasyonu sırasında kimlik hâlâ duruyor
    // ama dosya listesi boşalmış olabilir.
    bekleyenPaylasim: PendingShare?,
    // ⋯ menüsü, composer şeridinde GİZLENENLERİ satır olarak gösteriyor —
    // yani şeridin ne gösterdiğini bilmesi gerek (bkz. UI3_SERIT_TAM_ESIK).
    seritTam: Boolean,
) {
    if (kimlik == null) return
    val oturum = backendId?.let { uiState.backendSession(it) }
    // Geri oku yalnizca bir yerden gelindiyse cizilir.
    val geriMenuye: (() -> Unit)? = geriHedefi?.let { hedef -> { onSheetAc(hedef) } }

    when (kimlik) {
        // Oturum listesi burada YOK: kendi yan panelinde ve kendi durumunda.
        Ui3Sheet.SPOTLIGHT -> Ui3Spotlight(
            uiState = uiState,
            actions = actions,
            eylemler = spotlightEylemleri,
            onOturum = onSekmeSec,
            onSohbeteGec = onSohbeteGec,
            onProjeAc = onProjeAc,
            onKapat = onKapat,
        )

        // Kullanıcının açmadığı tek sheet: paylaşım geldiğinde kök açıyor.
        // `bekleyenPaylasim` null ise (kapanış animasyonu sürerken olur) hiçbir
        // şey çizilmez — dosyasız bir hedef sorusu anlamsız olurdu.
        Ui3Sheet.PAYLAS_HEDEF -> bekleyenPaylasim?.let { paylasim ->
            Ui3PaylasHedefi(
                dosyalar = paylasim.files,
                uiState = uiState,
                actions = actions,
                onKapat = onKapat,
            )
        }

        Ui3Sheet.MENU -> {
            if (backendId == null) {
                SheetBasligi("TUR AYARLARI", "Önce bir oturum aç")
            } else {
                Ui3TurAyarlariMenusu(
                    uiState = uiState,
                    backendId = backendId,
                    // Şerit geniş olduğunda Yenile/Kullanım orada zaten
                    // duruyor; menüde ikinci kez göstermek kopya olurdu.
                    calisiyor = uiState.running && !seritTam,
                    yenileniyor = uiState.conversationRefreshing,
                    // Yenile şeritteki tuşla aynı eylem; menü kapanır ki
                    // yenilenen sohbet görünsün.
                    onYenile = { actions.resyncConversation(); onKapat() },
                    // Şeritten inen iki yol. Koşullar Ui3ChatScreen'deki
                    // yönlendir tuşuyla AYNI kaynaktan: metin/ek var mı, tur
                    // sürüyor mu, sağlayıcı tur ortası gönderimi destekliyor mu.
                    // Ek varken "ajana bırak" kapalı — o uç yalnız metin taşıyor,
                    // ek sessizce düşerdi; kuyruk yolu ekleri koruyor.
                    onKuyruk = if (uiState.running && ui3MetinGonderilebilir(uiState)) ({
                        onKapat()
                        actions.sendPrompt()
                        Unit
                    }) else null,
                    onAjanaBirak = if (
                        uiState.running &&
                        ui3MetinGonderilebilir(uiState) &&
                        // Bu satır KUYRUK yolu (tur bitince işle) — steer değil.
                        // OpenCode'da yalnız bu var, o yüzden ayrı sorulur.
                        uiState.midTurnQueueSupported() &&
                        uiState.attachments.isEmpty()
                    ) ({
                        onKapat()
                        actions.backendSendDuringTurn(interrupt = false)
                        Unit
                    }) else null,
                    // Bütün backend'lerde cwd varsa göster; proje oturumunda
                    // proje klasörü, cowork'te çalışma alanı açılır.
                    onCalismaKlasoru = oturum?.cwd
                        ?.takeIf { it.isNotBlank() }
                        ?.let { yol -> { onKapat(); onCalismaKlasoru(yol) } },
                ) { hedef ->
                    acikSheetAc(hedef, backendId, actions) { yeni ->
                        if (yeni == null) onKapat() else onSheetAcMenudan(yeni)
                    }
                }
            }
        }

        Ui3Sheet.MODEL -> if (backendId != null) {
            val ham = uiState.backendModelOptions(backendId)
            // RunPod hapının yeri BURASI (25.08.2026, kullanıcı: eski yeri
            // "eğreti"ydi). Pod'u açma isteği RunPod'i SEÇERKEN doğuyor; hap
            // da o satırın altında duruyor. Yalnız opencode'da: pod o
            // backend'in modelini barındırıyor.
            val runpodVar = backendId == "opencode2-app"
            if (runpodVar) {
                val runpod = uiState.opencode.runpod
                val rpMesgul = runpod.operationActive ||
                    runpod.phase == "starting" ||
                    runpod.phase == "stopping"
                // DURUM SHEET'İN KENDİ İŞİ, açan kapının değil — Ui3Kullanim'da
                // öğrenilen dersin aynısı: `acikSheetAc` tek yol değil, oradan
                // geçmeyen bir açılış eklenince hap bayat durumla çizilirdi.
                LaunchedEffect(Unit) { actions.refreshRunPodStatus(showErrors = false) }
                // Geçiş sürerken tazele: köprü tarafındaki iş sekiz dakika
                // sürebiliyor ve uygulama runpod'i aksi halde kendiliğinden
                // yoklamıyor — hap "başlıyor"da takılı kalırdı. Geçiş bitince
                // anahtar değişir, döngü iptal olur (ui2/ui3'teki döngünün aynısı).
                LaunchedEffect(rpMesgul) {
                    if (rpMesgul) {
                        while (true) {
                            kotlinx.coroutines.delay(3000)
                            actions.refreshRunPodStatus(showErrors = false)
                        }
                    }
                }
            }
            val runpodKonsolu = LocalUriHandler.current
            Ui3Selector(
                baslik = "MODEL SEÇ",
                secenekler = ham.map { SelectorOption(it.id, it.label, it.detail.ifBlank { null }) },
                seciliDeger = oturum?.model,
                yukleniyor = ham.isEmpty(),
                // Her backend'in kataloğu ayrı; sabitlemeler de öyle.
                pinKapsam = "backend-model:$backendId",
                bicim = Ui3SecenekBicimi.Model,
                onSec = { actions.setBackendModel(backendId, it); onKapat() },
                satirAlti = if (!runpodVar) null else ({ modelId ->
                    // Hap SEÇMİYOR, yalnız pod'u sürüyor: pod açılması dakikalar
                    // alıyor ve modeli hazır olmadan seçmek turu kapalı bir
                    // sunucuya gönderirdi. Satıra dokunmak seçer, hap açar.
                    if (!isRunPodModel(modelId)) null else ({
                        Ui3RunPodTusu(
                            durum = uiState.opencode.runpod,
                            onTikla = {
                                when (runPodPillAction(uiState.opencode.runpod)) {
                                    "stop" -> actions.stopRunPod()
                                    "connect" -> actions.startRunPod()
                                    // Pod kapalıyken API'den AÇMIYORUZ: GPU dolu /
                                    // migrate kararı kullanıcıda. Konsola çıkar, elle
                                    // açıp dönünce hap "bağlan"a döner (ui2'deki kural).
                                    else -> runCatching {
                                        runpodKonsolu.openUri("https://console.runpod.io/pods")
                                    }
                                }
                            },
                        )
                    })
                }),
            )
        }

        Ui3Sheet.IZIN -> if (backendId != null) {
            val ham = uiState.backendPermissionModeOptions(backendId)
            Ui3Selector(
                baslik = "İZİN MODU",
                secenekler = ham.map { SelectorOption(it.id, it.label, it.detail.ifBlank { null }) },
                seciliDeger = oturum?.permissionMode,
                yukleniyor = ham.isEmpty(),
                onGeri = geriMenuye,
                onSec = { actions.setBackendPermissionMode(backendId, it); onKapat() },
            )
        }

        Ui3Sheet.AJAN -> if (backendId != null) {
            val ham = uiState.backendAgentOptions(backendId)
            Ui3Selector(
                baslik = "AJAN SEÇ",
                altBaslik = "Turu hangi ajan koşacak; kendi modeli olan ajan seçilince model de değişir",
                secenekler = ham.map { SelectorOption(it.id, it.label, it.detail.ifBlank { null }) },
                // v1/v2 ayri durum ailesi: sabit `opencode` v2 sekmesinde
                // yanlis secimi isaretlerdi.
                seciliDeger = uiState.opencodeFamily(backendId).agent,
                // "otomatik" tek başına dolu liste değil: gerçek ajanlar gelene
                // kadar yükleniyor göster.
                yukleniyor = ham.size <= 1,
                detayMaksSatir = 3,
                pinKapsam = "backend-agent:$backendId",
                onGeri = geriMenuye,
                onSec = { actions.setBackendAgent(backendId, it); onKapat() },
            )
        }

        Ui3Sheet.EFFORT -> if (backendId != null) {
            val ham = uiState.backendEffortOptions(backendId)
            // Bos id = "saglayici varsayilani"; bir kademe degil, o yuzden
            // merdivenin disinda ayri satir olarak ciziliyor.
            val varsayilan = ham.firstOrNull { it.id.isBlank() }
                ?.let { SelectorOption(it.id, it.label, it.detail.ifBlank { null }) }
            val basamaklar = ham.filter { it.id.isNotBlank() }
                .map { SelectorOption(it.id, it.label, it.detail.ifBlank { null }) }
            if (ui3CabaMerdiveniUygun(basamaklar.size)) {
                Ui3CabaMerdiveni(
                    baslik = "ÇABA",
                    altBaslik = null,
                    varsayilan = varsayilan,
                    basamaklar = basamaklar,
                    seciliDeger = oturum?.effort,
                    onGeri = geriMenuye,
                    onSec = { actions.setBackendEffort(backendId, it); onKapat() },
                )
            } else {
                // Olcek sayilmayacak kadar az kademe (ya da hic): eski liste.
                Ui3Selector(
                    baslik = "ÇABA SEÇ",
                    secenekler = ham.map { SelectorOption(it.id, it.label, it.detail.ifBlank { null }) },
                    seciliDeger = oturum?.effort,
                    yukleniyor = ham.isEmpty(),
                    onGeri = geriMenuye,
                    onSec = { actions.setBackendEffort(backendId, it); onKapat() },
                )
            }
        }

        // DEĞİŞİKLİKLER — "bu oturum neyi değiştirdi". Yalnız değişiklik ucu
        // olan backend'de menüde görünüyor; buradaki dal ise kimlik başka bir
        // yoldan gelirse (Spotlight, geri hedefi) sheet'in boş kalmaması için
        // aynı koşulu tekrar sınıyor.
        Ui3Sheet.DEGISIKLIKLER -> if (backendId != null && uiState.backendDiffSupported(backendId)) {
            // Kutu backend'e göre seçiliyor (`backendDiff`), doğrudan
            // `uiState.opencode.diff` OKUNMUYOR: bu ekran iki backend'de
            // ortak ve sabit kutu, codex sekmesinde satırı görünür ama sheet'i
            // sonsuza dek boş bırakırdı.
            Ui3Degisiklikler(
                diff = uiState.backendDiff(backendId),
                yukleniyor = uiState.backendDiffLoading(backendId),
                onGeri = geriMenuye,
            )
        }

        // OTURUM KURALLARI (yalnız v2) — talimat + izin tek sheet'te. Menüdeki
        // satırla aynı koşul burada da: kimlik başka yoldan gelirse (Spotlight,
        // geri hedefi) sheet boş kalmasın; v1'de uç yok.
        Ui3Sheet.KURALLAR -> if (backendId == com.agent.bridge.Backend.OPENCODE2_APP.id) {
            Ui3Kurallar(
                instructions = uiState.opencode.instructions,
                instructionsLoading = uiState.opencode.instructionsLoading,
                savedPermissions = uiState.opencode.savedPermissions,
                savedPermissionsLoading = uiState.opencode.savedPermissionsLoading,
                onTalimatEkle = { anahtar, deger -> actions.putOpencode2Instruction(anahtar, deger) },
                onTalimatSil = { actions.deleteOpencode2Instruction(it) },
                onIzinSil = { actions.deleteOpencode2SavedPermission(it) },
                onGeri = geriMenuye,
            )
        }

        // GERİ SAR — kimlikli bir noktaya dönüş. Menüdeki satırla AYNI koşul
        // burada da sınanıyor: kimlik başka bir yoldan gelirse (Spotlight, geri
        // hedefi) sheet boş kalmasın. Sheet onaydan sonra kapanıyor; şerit ve
        // "Geri Al" sohbetin üstünde yaşamaya devam ediyor.
        Ui3Sheet.GERI_SAR -> if (backendId != null && uiState.backendRevertSupported(backendId)) {
            Ui3GeriSar(
                // Sabit v1 kutusu OKUNMUYOR: opencode2 sekmesinde satir
                // gorunur, liste sonsuza dek bos kalirdi (diff'te ogrenilen
                // kural, bkz. Ui3MenuYonlendirmeTest).
                noktalar = uiState.opencodeFamily(backendId).checkpoints,
                yukleniyor = uiState.opencodeFamily(backendId).checkpointsLoading,
                calisiyor = uiState.running,
                onGeriSar = { actions.revertBackendToMessage(backendId, it); onKapat() },
                onGeri = geriMenuye,
            )
        }

        // PAYLAŞ — oturumu herkese açık bir linkte yayınlama. Menüdeki satırla
        // AYNI koşul burada da sınanıyor: kimlik başka bir yoldan gelirse
        // (Spotlight, geri hedefi) sheet boş kalmasın. `acikSheetAc`'ta yükleme
        // dalı YOK, bilerek — paylaşım durumu snapshot'la (META_KEYS `share`)
        // zaten akıyor, ayrı bir istek bayat bir ikinci kaynak yaratırdı.
        Ui3Sheet.OTURUM_PAYLAS -> if (backendId != null && uiState.backendShareSupported(backendId)) {
            Ui3OturumPaylas(
                link = uiState.opencode.share,
                onPaylas = { onLink -> actions.shareBackendSession(backendId, onLink) },
                onKaldir = { actions.unshareBackendSession(backendId); onKapat() },
                onGeri = geriMenuye,
            )
        }

        // AGENTS.md — onaydan sonra sheet KAPANIYOR: başlayan şey normal bir tur
        // ve kullanıcı onu sohbette görmeli, açık bir onay ekranının arkasında
        // değil.
        Ui3Sheet.AGENTS_INIT -> if (backendId != null && uiState.backendAgentsInitSupported(backendId)) {
            Ui3AgentsInit(
                calisiyor = uiState.running,
                onBaslat = { actions.initBackendAgentsFile(backendId); onKapat() },
                onGeri = geriMenuye,
            )
        }

        // ALT AJAN — kart yığınından açılan salt-okunur konuşma. Menüde satırı
        // YOK (hangi ajan olduğu ancak kartta seçilebiliyor), ama koşul yine de
        // sınanıyor: kimlik başka bir yoldan gelirse sheet boş kalmasın.
        // "Geri" MENÜYE değil KAPATMAYA gidiyor — bu sheet menüden açılmadı,
        // kullanıcıyı hiç girmediği bir menüye bırakmak yön kaybettirirdi.
        Ui3Sheet.ALT_AJAN -> if (backendId != null && uiState.backendSubagentsSupported(backendId)) {
            Ui3AltAjanSohbeti(
                transcript = uiState.opencode.subagentTranscript,
                yukleniyor = uiState.opencode.subagentTranscriptLoading,
                onGeri = onKapat,
            )
        }

        // BAĞLAM AYRINTISI — adaya dokununca (yalnız geniş yerleşimde) açılıyor.
        // Menüde satırı YOK, o yüzden "Geri" menüye değil KAPATMAYA gidiyor:
        // kullanıcı hiç girmediği bir menüye bırakılmamalı (ALT_AJAN'daki
        // kararın aynısı). Yüzde adayla AYNI fonksiyondan geliyor — iki
        // gösterge farklı sayı söylemesin.
        Ui3Sheet.BAGLAM -> if (!BuildConfig.IS_LITE) Ui3BaglamAyrintisi(
            yuzde = adaBaglamYuzdesi(
                opencodeAktif = uiState.opencodeAktif,
                contextPct = uiState.opencode.contextPct,
                contextTokens = uiState.contextTokens,
                contextWindow = uiState.contextWindow,
            ),
            tokenlar = uiState.contextTokens,
            pencere = uiState.contextWindow,
            calisiyor = uiState.running,
            // Sıkıştırma ucu yalnız opencode'da var; başka backend'de tuşu
            // göstermek çalışmayan bir düğme sunmak olurdu.
            sikistirilabilir = uiState.opencodeAktif,
            onSikistir = { actions.opencodeAppCompact(); onKapat() },
            onGeri = onKapat,
        )

        Ui3Sheet.SKILL -> if (backendId != null) {
            // Salt görüntü: skill'ler seçilmez, model gerektiğinde yükler.
            val skiller = uiState.backendSkillOptions(backendId)
            val gruplar = uiState.backendSkillGroups(backendId)
            val saglayici = coworkProviderLabel(
                if (backendId == "cowork") uiState.coworkProvider else backendId
            )
            Ui3SkillListesi(
                baslik = "KURULU SKILL'LER",
                altBaslik = "$saglayici · ${skiller.size} skill",
                skiller = skiller.map {
                    SelectorOption(it.id, it.label, it.detail.ifBlank { null }, gruplar[it.id])
                },
                bosMetin = "Bu sağlayıcıda kurulu skill yok.",
                onGeri = geriMenuye,
            )
        }

        Ui3Sheet.SAGLAYICI -> Ui3Selector(
            baslik = "COWORK SAĞLAYICISI",
            altBaslik = "Çalışma alanında hangi ajanla konuşulacağını seçer",
            secenekler = COWORK_PROVIDER_OPTIONS.map { SelectorOption(it.id, it.label) },
            seciliDeger = normalizeCoworkProvider(uiState.coworkProvider),
            onGeri = geriMenuye,
            onSec = { actions.switchCoworkProvider(it); onKapat() },
        )

        Ui3Sheet.OTURUM_MENU -> OturumMenusu(uiState, actions, menuOturumu, onKapat, onSheetAc)

        Ui3Sheet.OTURUM_AD -> OturumAdi(uiState, actions, menuOturumu, onKapat)

        Ui3Sheet.SEKME_MENU -> SekmeMenusu(uiState, actions, uzunBasilanSekme, onKapat)

        Ui3Sheet.ARAC -> {
            // Adımın TAM metni `thoughtDetails`'te duruyor, `ChatMessage.text`
            // yalnız özet. Sheet bu haritayı almadığı için adımlar tek satırda
            // kalıyordu (kullanıcı, 22.08.2026: "ui2'de genişletebiliyordum").
            val dusunceDetaylari by actions.thoughtDetails.collectAsState()
            Ui3AracAyrintisi(
                mesajlar = uiState.messagesList,
                indeksler = aracIndeksleri,
                dusunceMi = aracGrubuDusunce,
                dusunceDetaylari = dusunceDetaylari,
                onDusunceYukle = actions::loadThought,
            )
        }

        // Merkez > Kullanım ile AYNI kart kümesi; force yenile burada.
        Ui3Sheet.KULLANIM -> Ui3Kullanim(uiState, actions, geriMenuye)

        // "＋" — ek dosya nereden geliyor. İki kaynak AYNI şey değil:
        // telefondan seçilen dosya baytlarıyla köprüye yüklenir, bilgisayardan
        // seçilen dosya yalnız YOL olarak eklenir (zaten orada duruyor).
        // ui2 bunu bir Material `AlertDialog` ile soruyordu; ui3'te cam sheet.
        //
        // Liste değil İKİ SÜTUN (kullanıcı kararı 18.08.2026): iki seçenek alt
        // alta iki geniş şerit olunca sheet boş ve resmî duruyordu. İkisi
        // birbirinin alternatifi olduğu için yan yana kart doğru biçim.
        Ui3Sheet.EK_KAYNAK -> Ui3IkiliSecim(
            baslik = "DOSYA NEREDEN?",
            altBaslik = "Telefondaki dosya köprüye yüklenir, bilgisayardaki yalnız yol olarak eklenir",
            sol = SelectorOption("telefon", "Telefondan", "Galeri veya dosya yöneticisi"),
            solIkon = Icons.Outlined.Smartphone,
            sag = SelectorOption("pc", "Bilgisayardan", "Köprü gezginiyle seç"),
            sagIkon = Icons.Outlined.Computer,
            onSec = { secim ->
                onKapat()
                if (secim == "telefon") onTelefondanEk() else onBilgisayardanEk()
            },
        )
    }
}

/**
 * Sekme uzun basış menüsü — ui2'deki `tab_menu` sheet'inin aynısı.
 *
 * Seçenekler duruma göre daralır: tek sekme varken "diğerlerini kapat" yok,
 * ilk sekmede "sola taşı" yok. ui2'deki koşulların birebir aynısı.
 */
@Composable
private fun ColumnScope.SekmeMenusu(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    sekmeId: String?,
    onKapat: () -> Unit,
) {
    val sekmeler = uiState.visibleTabs
    val indeks = sekmeler.indexOfFirst { it.id == sekmeId }
    if (sekmeId == null || indeks < 0) {
        SheetBasligi("SEKME İŞLEMLERİ", "Sekme bulunamadı")
        return
    }
    val secenekler = buildList {
        add(SelectorOption("close", "Kapat"))
        if (sekmeler.size > 1) add(SelectorOption("close_others", "Diğerlerini kapat"))
        if (indeks > 0) add(SelectorOption("move_left", "Sola taşı"))
        if (indeks < sekmeler.size - 1) add(SelectorOption("move_right", "Sağa taşı"))
    }
    Ui3Selector(
        baslik = "SEKME İŞLEMLERİ",
        secenekler = secenekler,
        onSec = { eylem ->
            onKapat()
            when (eylem) {
                "close" -> actions.tabsDelegate.closeTab(sekmeId)
                "close_others" -> actions.tabsDelegate.closeOtherTabs(sekmeId)
                "move_left" -> actions.tabsDelegate.moveTab(indeks, indeks - 1)
                "move_right" -> actions.tabsDelegate.moveTab(indeks, indeks + 1)
            }
        },
    )
}

/**
 * Oturum çekmecesi sheet'i — dock'un Oturumlar tuşu.
 *
 * İlk sürüm yalnız AÇIK SEKMELERİ listeliyordu ve kullanıcı bunu ui2'nin
 * davranışı sanmamı düzeltti: ui2'nin çekmecesi DİSKTEKİ tüm oturumları,
 * Tümü/Sabitli/Arşiv segmentlerini, backend süzgecini, aramayı ve mesaj
 * içeriğinde eşleşmeleri taşıyor.
 *
 * O gövde ui2'de [SessionDrawerContent] olarak çıkarıldı; burada olduğu gibi
 * çağrılıyor ve ui3 malzemesiyle ([Ui3OduncKap]) sarılıyor. Yeniden yazmak
 * ölçülerek bulunmuş kenar durumlarını (pin önceliği, kapsam yolu eşleşmesi,
 * içerik araması dalı) kaybederdi.
 *
 * Yükleme efekti BURADA: sheet yalnız açıkken besteleniyor, o yüzden ui2'deki
 * gibi ayrıca "açık mı" anahtarına gerek yok.
 */
@Composable
private fun ColumnScope.OturumCekmecesi(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    acik: Boolean,
    onKapat: () -> Unit,
    onYeniOturum: () -> Unit,
    onOturumMenusu: (String, String) -> Unit,
    onAyarlar: (() -> Unit)? = null,
) {
    val tumBackendler = orderedVisibleBackends(uiState).map { it.id }
    LaunchedEffect(acik, tumBackendler) {
        if (acik) {
            // Her açılışta bütün görünür backend'leri tazele. Aktif sohbetin
            // sağlayıcısı ve çalışma klasörü liste kapsamını değiştirmez.
            tumBackendler.forEach { id ->
                if (id == "cowork") actions.loadAllCoworkSessions()
                else actions.loadBackendDiskSessions(id)
            }
        }
    }

    // SheetBasligi YOK: ödünç gövde kendi "Tüm oturumlar" başlığını taşıyor.
    // İkisi birden çizilince üst üste iki başlık oluyordu (cihazda görüldü).
    Ui3OduncKap {
        Column(
            Modifier
                .fillMaxWidth()
                // Panel tam boy: kalan yüksekliğin tamamı listeye. (Alttan çıkan
                // sheet'teyken 440dp sabitti — orada tavan gerekiyordu, burada
                // panelin kendisi zaten ekran boyu.)
                .weight(1f)
                .padding(horizontal = Ui3Tokens.s16),
        ) {
            SessionDrawerContent(
                uiState = uiState,
                actions = actions,
                onKapat = onKapat,
                onNewSession = onYeniOturum,
                onSessionLongClick = onOturumMenusu,
                onSettings = onAyarlar,
            )
        }
    }
}

/**
 * Çekmecede bir oturuma uzun basınca açılan işlemler — ui2'nin
 * "Oturum İşlemleri" sheet'inin ui3 karşılığı.
 *
 * Seçenekler backend YETENEĞİNE göre daralıyor (ui2'deki koşulların aynısı):
 * her sağlayıcı sabitleme/arşivleme desteklemiyor.
 *
 * "Yeniden adlandır" kendi yüzeyini istiyor (metin alanı), o yüzden menüden
 * ayrı bir sheet açıyor: [Ui3Sheet.OTURUM_AD]. Görünürlükler ui2'deki
 * koşulların aynısı — yeniden adlandırma sabitlemeyi destekleyen backend'lerde,
 * "bilgisayarda devam et" yalnız claude-app'te.
 */
@Composable
private fun ColumnScope.OturumMenusu(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    hedef: Pair<String, String>?,
    onKapat: () -> Unit,
    onSheetAc: (String) -> Unit,
) {
    if (hedef == null) return
    val (backend, oturumId) = hedef
    val oturum = uiState.backendDiskSessions(backend).find { it.id == oturumId } ?: return
    val pano = LocalClipboardManager.current
    val secenekler = buildList {
        if (backendSessionPinSupported(backend)) {
            add(
                if (oturum.pinned) SelectorOption("unpin", "Sabitlemeyi kaldır")
                else SelectorOption("pin", "Sabitle")
            )
        }
        if (backendSessionArchiveSupported(backend)) {
            add(
                if (oturum.archived) SelectorOption("unarchive", "Arşivden çıkar")
                else SelectorOption("archive", "Arşivle")
            )
        }
        if (backendSessionRenameSupported(backend)) {
            add(SelectorOption("rename", "Yeniden adlandır"))
        }
        if (backendSessionOpenOnPcSupported(backend)) {
            add(SelectorOption("open-on-pc", "Bilgisayarda devam et"))
        }
        // Kimlik her backend'de var; detayda tam hâli durur ki kopyalamadan
        // önce doğru oturum olduğu görülebilsin.
        add(SelectorOption("copy-id", "Kimliği kopyala", oturum.id))
        add(SelectorOption("delete", "Sil"))
    }
    Ui3Selector(
        baslik = "OTURUM İŞLEMLERİ",
        altBaslik = oturum.title.ifBlank { oturum.id.take(8) },
        secenekler = secenekler,
        onSec = { secim ->
            onKapat()
            when (secim) {
                "pin", "unpin" -> {
                    actions.setBackendSessionPinned(backend, oturum.id, secim == "pin")
                    actions.loadBackendDiskSessions(backend)
                }
                "archive", "unarchive" -> {
                    actions.setBackendSessionArchived(backend, oturum.id, secim == "archive")
                    actions.loadBackendDiskSessions(backend)
                }
                "rename" -> onSheetAc(Ui3Sheet.OTURUM_AD)
                "open-on-pc" -> actions.claudeAppOpenOnPc(oturum.id)
                "copy-id" -> {
                    pano.setText(AnnotatedString(oturum.id))
                    actions.notifyUser("Oturum kimliği kopyalandı")
                }
                "delete" -> actions.deleteBackendDiskSession(backend, oturum.id)
            }
        },
    )
}

/**
 * Oturumu yeniden adlandırma — ui2'deki `AlertDialog`ın ui3 karşılığı.
 *
 * Material diyalog DEĞİL: kendi kabı ve gölgesi camın üstüne ikinci bir yüzey
 * bindiriyor (sekme kapatma onayında aynı gerekçeyle elenmişti). Aynı cam
 * sheet'in içinde tek satırlık alan + iki tuş.
 */
@Composable
private fun ColumnScope.OturumAdi(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    hedef: Pair<String, String>?,
    onKapat: () -> Unit,
) {
    if (hedef == null) return
    val (backend, oturumId) = hedef
    val oturum = uiState.backendDiskSessions(backend).find { it.id == oturumId } ?: return
    // Anahtar oturum kimliği: başka bir oturuma geçilince taslak sıfırlansın.
    var ad by remember(oturum.id) { mutableStateOf(TextFieldValue(oturum.title, TextRange(oturum.title.length))) }
    val odak = remember { FocusRequester() }
    LaunchedEffect(oturum.id) { odak.requestFocus() }

    SheetBasligi("YENİDEN ADLANDIR", oturum.id.take(8))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s20),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ui3Tokens.r18))
                .background(Ui3Colors.kuyu)
                .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r18))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = ad,
                onValueChange = { ad = it },
                singleLine = true,
                textStyle = Ui3Type.akis.copy(color = Ui3Colors.ink),
                cursorBrush = SolidColor(Ui3Colors.vurguHi),
                modifier = Modifier.fillMaxWidth().focusRequester(odak).testTag("oturum_ad_alani"),
            )
        }
        // Tuş çifti Ui3Onayla ile aynı biçimde: solda çizgi çerçeveli vazgeç,
        // sağda dolu birincil. Ayrı bir tuş bileşeni yok, ui3'te desen bu.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Ui3Colors.cizgi, RoundedCornerShape(14.dp))
                    .clickable(onClick = onKapat)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Vazgeç", style = Ui3Type.akis, color = Ui3Colors.ink2)
            }
            val kaydedilebilir = ad.text.isNotBlank()
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (kaydedilebilir) Ui3Colors.birincilZemin else Ui3Colors.yuzey2)
                    .clickable(enabled = kaydedilebilir) {
                        actions.setBackendSessionTitle(backend, oturum.id, ad.text.trim())
                        onKapat()
                    }
                    .padding(vertical = 11.dp)
                    .testTag("oturum_ad_kaydet"),
                contentAlignment = Alignment.Center,
            ) {
                // BİRİNCİL MÜREKKEP, ink DEĞİL: birincil zemin koyu temada beyaz
                // ve ink de beyaza yakın — ilk denemede tuş beyaz üstüne beyaz
                // çıktı, cihazda görüldü. Ui3Onayla da bu çifti kullanıyor.
                Text(
                    "Kaydet",
                    style = Ui3Type.akis,
                    color = if (kaydedilebilir) Ui3Colors.birincilMurekkep else Ui3Colors.ink3,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Faz 2'de ekranların içi boş — plan bunu açıkça söylüyor, bu bir kabuk fazı.
 *
 * Yine de metin dolu ve kaydırılabilir: dock'un altından **keskin metin**
 * geçmezse blur ölçülemez (anayasa v2 §8, üçüncü tuzak).
 */
@Composable
private fun YerTutucuEkran(
    alan: Ui3Area,
    baslikUstu: String = alan.etiket,
    altBosluk: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val kaydirma = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(kaydirma)
            .padding(horizontal = Ui3Tokens.s20)
            .testTag("ekran_" + alan.name.lowercase()),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        Text(
            "$baslikUstu — sonraki fazda dolacak",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier.padding(top = Ui3Tokens.s8),
        )
        repeat(40) { i ->
            Text(
                "satır ${i + 1} · dock'un altından geçen keskin metin",
                style = Ui3Type.alt,
                color = Ui3Colors.ink3,
            )
        }
        Box(Modifier.padding(bottom = altBosluk + 32.dp))
    }
}

/** Composer'daki `gonderilebilir` ile AYNI koşul: metin ya da ek var mı. */
private fun ui3MetinGonderilebilir(uiState: RemoteUiState): Boolean =
    uiState.input.isNotBlank() || uiState.attachments.isNotEmpty()
