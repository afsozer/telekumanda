package com.agent.bridge.ui3.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.AppForeground
import com.agent.bridge.ApprovalActionRouter
import com.agent.bridge.ChatDisplayRow
import com.agent.bridge.GroupKind
import com.agent.bridge.ChatMessage
import com.agent.bridge.RemoteUiState
import com.agent.bridge.RemoteViewModel
import com.agent.bridge.activeQueuedPrompts
import com.agent.bridge.coworkProvider
import com.agent.bridge.normalizeCoworkProvider
import com.agent.bridge.midTurnSteerSupported
import com.agent.bridge.buildChatDisplayRows
import com.agent.bridge.backendRevertSupported
import com.agent.bridge.backendCommandsSupported
import com.agent.bridge.opencodeFamily
import com.agent.bridge.backendSlashSuggestions
import com.agent.bridge.backendSubagentsSupported
import com.agent.bridge.loadBackendSubagentTranscript
import com.agent.bridge.loadBackendCommands
import com.agent.bridge.runBackendCommand
import com.agent.bridge.unrevertBackend
import com.agent.bridge.backendSession
import com.agent.bridge.visibleTabs
import com.agent.bridge.isToolSummary
import com.agent.bridge.permissionModeIsPermissive
import com.agent.bridge.isRunPodModel
import com.agent.bridge.shortModelLabel
import com.agent.bridge.AttachmentChip
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.material.Ui3OduncKap
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import com.agent.bridge.ConversationPaging
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import com.agent.bridge.QuestionAnswerDraft
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.delay
import com.agent.bridge.ui2.chat.ChatScrollAnchor
import com.agent.bridge.ui2.chat.ChatScrollMemory
import com.agent.bridge.ui2.chat.ChatSwipeExclusions
import com.agent.bridge.ui2.chat.LocalChatSwipeExclusions

private val RAY_GENISLIK = 2.dp
// Oluk 25 → 18: sohbet soldan gereğinden fazla boşluk bırakıyordu (oluk 25 +
// ekran dolgusu 20 = 45dp). Ray merkezi olukla birlikte sola kayıyor.
private val OLUK = 18.dp
// Rayın oluk içindeki merkezi. Düğümler bu eksene ORTALANIR — önceki sürümde
// sabit bir offset kullanılıyordu ve düğüm rayın sağına taşıyordu
// (kullanıcı: "akma efekti çizilen çizgiden dışarı taşıyor").
private val RAY_MERKEZ = 5.dp

/**
 * ui3 sohbet ekranı.
 *
 * Akış kuralları (anayasa v2 §1.3 çelişki çözümü):
 * - Kullanıcı balonu ve araç çipi **cam görünümü** (blur'suz). Kaydırılan
 *   listede gerçek cam kullanılmaz.
 * - Onay kartı **gerçek cam** — akıştaki tek istisna.
 * - Composer sabit kabuk, gerçek cam.
 *
 * ONAY KARTI ARTIK AKIŞTA DEĞİL (kullanıcı kararı): composer'ın hemen üstünde
 * sabit duruyor. Akışın sonundayken uzun sohbette yukarı kaydırınca onay
 * beklendiği gözden kaçıyordu; sabitlenince nereye kaydırırsan kaydır görünür.
 * Kronolojik izi akışta kalmıyor — bunun yerine kart "ayrıntı" ile araç
 * sheet'ine bağlanıyor.
 *
 * Ray: ajan turunun solundaki ışık hattı. Işık YALNIZ tur çalışırken akar
 * (hareket bütçesi); durunca sabit gradyana döner ve kare üretimi durur.
 *
 * Satır modeli ui2 ile ORTAK: `buildChatDisplayRows` shared'da ve aynı kullanıcı
 * turundaki düşünce/araç adımlarını tür başına tek gruba topluyor. ui3 kendi
 * gruplama mantığını uydurmuyor.
 */
@Composable
internal fun Ui3ChatScreen(
    uiState: RemoteUiState,
    actions: RemoteViewModel,
    hazeState: HazeState,
    backendId: String?,
    onSheetAc: (String) -> Unit,
    // İkinci parametre: grup DÜŞÜNCE mi (araç mı). Sheet başlığı ve çip
    // etiketi buna göre yazılıyor; ui2'de de ayrımı `GroupKind` veriyordu.
    onAracAyrintisi: (List<Int>, Boolean) -> Unit,
    onDosya: (String) -> Unit,
    // Görüntüleyiciyi AÇAR, dosya çözmez: bekleyen ek önizlemesinde yol zaten
    // ViewModel'e verilmiş oluyor, `onDosya` gibi ayrıca çözülmesi gerekmiyor.
    onGoruntuleyici: () -> Unit,
    // Dock'un kapladığı yükseklik. Sohbet ekranı kendi alt yığınını (composer,
    // onay, slash) bu kadar yukarı iter; LİSTE ise dock'un ALTINA kadar uzar.
    altBosluk: Dp,
    modifier: Modifier = Modifier,
    // Geniş ekranda composer şeridi tur sürerken de Yenile + Kullanım'ı taşır
    // (yer var). Karar kökte, ölçü `UI3_SERIT_TAM_ESIK`.
    seritTam: Boolean = false,
    // Oturumun çalışma klasörünü dosya yöneticisinde açar (şeritteki tuş) —
    // cowork'te çalışma alanı, diğer backend'lerde proje cwd'si. Gezinme
    // yaptığı için karar kökte; null = cwd henüz yok.
    onCalismaKlasoru: (() -> Unit)? = null,
    // OKUNAN YERİN OTURUM BAŞINA BELLEĞİ. Kökten geliyor çünkü NavHost hedefi
    // değişince bu ekran bileşimden düşüyor; buradaki bir `remember` sekme
    // değiştirip dönmeyi hatırlamaya yetmez. ui2'nin sınıfının AYNISI
    // (`ChatScrollMemory`) — yerleşimden bağımsız bir sözlük, kopyalanmadı;
    // kopyalanamayan index matematiği `Ui3KaydirmaCapasi`de.
    kaydirmaBellegi: ChatScrollMemory = remember { ChatScrollMemory() },
    sekmelerEtkin: Boolean = true,
    bildirimlerEtkin: Boolean = true,
) {
    val mesajlar = uiState.messagesList
    val satirlar = remember(mesajlar) { buildChatDisplayRows(mesajlar) }
    val listeDurumu = rememberLazyListState()
    // Düşünce gövdeleri ayrı akışta: satır yalnız özeti taşır, tam metin kart
    // açılınca `loadThought` ile gelir. ui2 de aynı kaynaktan okuyor.
    val dusunceDetaylari by actions.thoughtDetails.collectAsState()

    // Slash listesi backend değişince yüklenir. ui2'de bu efekt vardı, ui3'te
    // YOKTU: liste hep boş kaldığı için "/" yazınca öneri hiç çıkmıyordu —
    // cihazda ölçülerek bulundu, "bu backend'de komut yok" sanılabilirdi.
    LaunchedEffect(backendId) {
        if (backendId != null) actions.loadSlashCommands()
    }
    // Kendini onaran ikinci efekt (ui2'den aynen): liste arada boşaltılabiliyor
    // (landing yenilemesi sıfırlıyor, ayrıca backend henüz set değilken çağrı
    // boş liste yazıp çıkıyor). O durumda menü bir daha hiç açılmıyordu.
    val slashIsteniyor = uiState.input.startsWith("/")
    LaunchedEffect(slashIsteniyor, uiState.slashCommands.isEmpty(), backendId) {
        if (backendId != null && slashIsteniyor && uiState.slashCommands.isEmpty()) {
            actions.loadSlashCommands()
        }
    }
    // ÖZEL KOMUTLAR (bugün yalnız opencode): sağlayıcının KENDİ komut+skill
    // kataloğu. Yukarıdaki statik listeden ayrı bir yol, çünkü ayrı bir şey:
    // seçilen satır composer'a yazılmıyor, ÇALIŞIYOR.
    //
    // Aynı "kendini onaran" kural: liste boşken "/" yazılınca yeniden istenir.
    // Köprü serve'e ulaşamadığında boş dönüyor ve şerit hiç çizilmiyor — bir
    // sonraki "/" yeniden dener, yani sunucu geç açılırsa kendiliğinden düzelir.
    val komutlarDestekli = backendId != null && uiState.backendCommandsSupported(backendId)
    val komutlar = uiState.opencodeFamily(backendId).commands
    LaunchedEffect(slashIsteniyor, komutlar.isEmpty(), komutlarDestekli, backendId) {
        if (backendId != null && komutlarDestekli && slashIsteniyor && komutlar.isEmpty()) {
            actions.loadBackendCommands(backendId)
        }
    }

    // ── AÇIK SOHBETİ BİLDİRİM KATMANINA DUYUR ─────────────────────────────
    //
    // Bu efekt ui2'de vardı (ChatRootScreen.kt), ui3'e taşınmamıştı. Sonuç:
    // `AppForeground.openKey` ui3'te HİÇ dolmuyordu, yani bastırma kararını
    // veren `suppressesNotification` her zaman "bastırma" diyordu — sohbetin
    // içinde otururken o sohbetin "bitti" bildirimi düşüyordu (kullanıcı
    // bildirdi 18.08.2026: "ui2'de öyleydi, şu an nedense geliyor").
    //
    // Susturma mekanizmasının kendisi sağlamdı; ona kimin açık olduğunu
    // söyleyen taraf eksikti. Bildirim nonce'ları, `pendingShare` ve paylaş/
    // harici-aç olaylarıyla aynı boşluk: ui2 kökünün uygulama geneli topladığı
    // şeyler ui3'e taşınırken düşmüş.
    //
    // `clearFor` de ui2'deki gerekçeyle burada: ekrana GİRİLDİĞİNDE o oturumun
    // bekleyen bildirimleri düşsün. Yalnız eşleşen tag iptal edilir, başka
    // oturumların bildirimleri durmaya devam eder.
    val bildirimBaglami = LocalContext.current
    val acikOturumId = if (backendId != null) uiState.backendSession(backendId).sessionId else ""
    DisposableEffect(backendId, acikOturumId) {
        if (bildirimlerEtkin && backendId != null && acikOturumId.isNotBlank()) {
            AppForeground.openChat(backendId, acikOturumId)
            AppForeground.clearFor(bildirimBaglami, backendId, acikOturumId)
        }
        // Ekrandan çıkınca (başka rotaya gidince, sekme değişince) kilit açılır;
        // yoksa sohbetten çıktıktan sonra da o oturumun bildirimi bastırılırdı.
        onDispose {
            if (bildirimlerEtkin && backendId != null) AppForeground.closeChat(backendId, acikOturumId)
        }
    }

    // Satır yüksekliği önbelleği — yukarı kaydırırken atlamayı önler, gerekçe
    // [Ui3YukseklikCapasi] KDoc'unda. Oturum başına: sohbet değişince sıfırlanır,
    // yoksa başka oturumun satır boyları yeni akışa çapa olurdu.
    val yukseklikOnbellegi = remember(acikOturumId) { Ui3YukseklikOnbellegi() }

    // "Daha eskiyi göster" listenin 0. ÖĞESİ. Varsa bütün satır indeksleri bir
    // kayıyor — `animateScrollToItem(satirlar.lastIndex)` sondan bir önceki
    // satıra gidiyordu ve "en alta in" bazen hiçbir şey yapmıyor görünüyordu.
    val eskiVar = ConversationPaging.target(uiState) != null &&
        uiState.messagesList.size >= uiState.messagePageSize
    val basOfset = if (eskiVar) 1 else 0

    // SORU KARTLARI AKIŞIN SON ÖĞESİ (gerekçe [Ui3SoruAkisi] KDoc'unda: sabit
    // dururken sohbeti daraltıyor, kalan ince şeritten kaydırınca metin
    // kartların altına giriyordu — kullanıcı bildirdi 20.08.2026).
    //
    // Dip hesabı onu da saymak ZORUNDA: `dibeYerles` ve "en alta in"
    // `sonIndeks`e gidiyor; sayılmazsa son mesajda durur ve sorular ekranın
    // altında görünmez kalırdı.
    val onay = uiState.approval?.takeIf { uiState.awaitingApproval }
    val sorular = onay?.questions.orEmpty()
    val soruEk = if (sorular.isEmpty()) 0 else 1
    val sonIndeks = (satirlar.size - 1 + basOfset + soruEk).coerceAtLeast(0)

    // Taslak istek KİMLİĞİNE bağlı: yeni bir soru gelince sıfırlanır, aynı soru
    // içinde seçim korunur (ui2'deki anahtarların aynısı). Liste ÖĞESİNİN
    // DIŞINDA duruyor — öğe ekrandan çıkınca içindeki `remember` atılır ve
    // seçilmiş şıklar silinirdi.
    var taslak by remember(onay?.requestId, sorular) { mutableStateOf(QuestionAnswerDraft()) }

    // Dibe kilitli akış: kullanıcı yukarı kaydırana kadar takip et.
    // `canScrollForward` false ise liste zaten dipte demektir. Kullanıcının
    // kaydırması bittiğinde bayrak yeniden ölçülür — parmakla yukarı çıkınca
    // takip kapanır, dibe dönünce kendiliğinden açılır.
    val yogunluk = LocalDensity.current
    // Alt yığının (slash + onay + composer) gerçek yüksekliği. Liste bu kadar
    // ALT DOLGU alır ama yığının ALTINA kadar uzar: mesajlar camın arkasından
    // geçer. Sabit bir sayı yazmak yanlış olurdu — onay kartı ve slash listesi
    // yığının boyunu değiştiriyor.
    var yiginY by remember { mutableStateOf(0.dp) }
    // Listenin ölçülen yüksekliği (px). Klavye animasyonu boyunca her karede
    // değişir; aşağıdaki takip efektinin anahtarı bu.
    var akisY by remember { mutableStateOf(0) }

    var otomatikTakip by remember { mutableStateOf(true) }
    LaunchedEffect(listeDurumu.isScrollInProgress) {
        if (!listeDurumu.isScrollInProgress) otomatikTakip = !listeDurumu.canScrollForward
    }

    // Takip YALNIZ satır sayısına bağlanamaz: akış sürerken son mesajın METNİ
    // uzuyor ama satır sayısı değişmiyordu, bu yüzden sohbet dibe kilitli
    // akmıyordu (kullanıcı bildirdi). Son metnin uzunluğu da anahtar.
    val sonMetinBoyu = mesajlar.lastOrNull()?.text?.length ?: 0

    // TEK SEFERLİK `scrollToItem` YETMİYOR: markdown parçaları `AndroidView`
    // (TextView) ve yükseklikleri ilk yerleşimden SONRA değişiyor. Bir kez
    // kaydırınca liste ortalarda kalıp birkaç kare sonra kendini düzeltiyordu
    // — kullanıcı bunu "önce ortaya gidiyor, sonra kasıp en alta iniyor"
    // olarak gördü. Dip gerçekten dip olana kadar kare kare tekrarlıyoruz.
    suspend fun dibeYerles(azami: Int = 8) {
        repeat(azami) {
            listeDurumu.scrollToItem(sonIndeks, Int.MAX_VALUE)
            withFrameNanos { }
            if (!listeDurumu.canScrollForward) return
        }
    }

    // ── OKUNAN YERİ HATIRLA ───────────────────────────────────────────────
    //
    // ui2'de vardı (kullanıcı isteği 05.08.2026: "yan sessiondan veya merkezden
    // geri geldiğimde kaydırdığım yeri hatırlasın"), ui3'e taşınmamıştı: tek
    // bir `rememberLazyListState` bütün oturumlarca paylaşılıyordu, sekme
    // değişince okunan yer kayboluyor ve dibe iniliyordu.
    //
    // İKİ BEKLEME BAYRAĞI, ui2'deki ikilinin aynısı: `bekleyenCapa` kaydedilmiş
    // bir yere dönmeyi, `bekleyenDip` takip moduna inmeyi bekletiyor. İkisi de
    // otomatik takibi SUSTURUYOR — yoksa geri yükleme ile `dibeYerles` aynı
    // karede birbirini eziyor ve liste bir görünüp bir kayboluyor.
    val acikOturum = if (backendId != null) uiState.backendSession(backendId).sessionId else ""
    val oturumAnahtari = "$backendId:$acikOturum"
    var bekleyenCapa by remember { mutableStateOf<ChatScrollAnchor?>(null) }
    var bekleyenDip by remember { mutableStateOf(true) }

    LaunchedEffect(oturumAnahtari) {
        val kayit = kaydirmaBellegi.anchor(oturumAnahtari)
        if (kayit == null) {
            // Kayıt YOK = o oturumda dipteydik. Takip moduna in.
            bekleyenDip = true
            otomatikTakip = true
        } else {
            bekleyenCapa = kayit
            bekleyenDip = false
            otomatikTakip = false
        }
    }

    LaunchedEffect(bekleyenDip, satirlar.size) {
        if (!bekleyenDip || satirlar.isEmpty()) return@LaunchedEffect
        dibeYerles()
        bekleyenDip = false
    }

    // Hatırlanan yere dön. Satır artık listede değilse (eski sayfa düşmüş,
    // oturum geri sarılmış) dibe düşülür — yanlış bir satırda durmaktansa.
    LaunchedEffect(bekleyenCapa, satirlar.size, basOfset) {
        val capa = bekleyenCapa ?: return@LaunchedEffect
        if (satirlar.isEmpty()) return@LaunchedEffect
        val indeks = ui3LazyIndeks(mesajlar, satirlar, capa.rowId, basOfset)
        if (indeks != null) listeDurumu.scrollToItem(indeks, capa.offset) else bekleyenDip = true
        bekleyenCapa = null
    }

    // Kaydırdıkça çapayı yaz. DİPTEYSEK kayıt SİLİNİR: dönüşte eski bir satıra
    // çakılıp kalmak yerine takip moduna girilsin. Efekt yalnız oturum
    // değişince kurulur; liste her token'da değiştiği için güncel değerler
    // `rememberUpdatedState` ile okunur (ui2'deki desenin aynısı).
    val mesajlarSimdi = rememberUpdatedState(mesajlar)
    val satirlarSimdi = rememberUpdatedState(satirlar)
    val basOfsetSimdi = rememberUpdatedState(basOfset)
    LaunchedEffect(oturumAnahtari, listeDurumu) {
        snapshotFlow { listeDurumu.firstVisibleItemIndex to listeDurumu.firstVisibleItemScrollOffset }
            .collect { (indeks, ofset) ->
                // Kendi düzeltme kaydırmamız sürerken yazma: ara kare yanlış
                // satırı çapa yapar.
                if (bekleyenCapa != null || bekleyenDip) return@collect
                val capa = if (!listeDurumu.canScrollForward) {
                    null
                } else {
                    ui3CapaSatirId(mesajlarSimdi.value, satirlarSimdi.value, indeks, basOfsetSimdi.value)
                        ?.let { ChatScrollAnchor(it, ofset) }
                }
                kaydirmaBellegi.remember(oturumAnahtari, capa)
            }
    }

    // `onay?.requestId` de anahtar: soru kartları artık listenin öğesi ve
    // gelişleri SATIR SAYISINI değiştirmiyor. Anahtarsız kalsa, dipteyken gelen
    // bir AskUserQuestion görünen pencerenin altına eklenip fark edilmezdi.
    LaunchedEffect(satirlar.size, sonMetinBoyu, otomatikTakip, onay?.requestId) {
        if (satirlar.isEmpty() || !otomatikTakip) return@LaunchedEffect
        // Geri yükleme kuyruktaysa takip devreye girmez; oraya varınca
        // `otomatikTakip` zaten kaydırma bitişinde yeniden ölçülüyor.
        if (bekleyenCapa != null || bekleyenDip) return@LaunchedEffect
        dibeYerles()
    }

    // Klavye açılıp kapanınca liste GÖRÜNÜR YÜKSEKLİĞİ değişiyor. Bunu
    // `altBosluk`/`yiginY` üzerinden izlemek YETMİYOR: o ikisi tek seferde
    // değişiyor, klavye ise ~250ms boyunca her karede biraz daha yükseliyor.
    // Tek atışlık kaydırma animasyonun ilk karesinde doğru, son karesinde
    // yanlış oluyordu — son mesaj prompt kutusunun altında kalıyordu
    // (kullanıcı iki kez bildirdi).
    //
    // Bu yüzden anahtar listenin ÖLÇÜLEN yüksekliği: klavye animasyonunun her
    // karesinde değişiyor, efekt her karede yeniden koşuyor ve akış dibe
    // yapışık kalıyor. Ölçüm başka bir sebeple değişse de (döndürme, sheet)
    // aynı düzeltme kendiliğinden çalışır.
    LaunchedEffect(akisY, altBosluk, yiginY) {
        if (otomatikTakip && satirlar.isNotEmpty()) dibeYerles(azami = 3)
    }

    val oturum = backendId?.let { uiState.backendSession(it) }
    // Çip yalnız kısa model adını taşır ("gpt sol", "opus"). Effort ayrı bir
    // tur ayarıdır ve ⋯ menüsündeki Effort satırından görülüp değiştirilebilir;
    // model çipine eklenmesi dar şeritte gereksiz gürültü yaratıyordu.
    val modelEtiketi = if (oturum == null) "model" else {
        shortModelLabel(oturum.model.ifBlank { oturum.defaultModel }).ifBlank { "model" }
    }
    // RUNPOD IŞIĞI — çipin başındaki nokta; null = çizilmez.
    //
    // Model kimliğini etiketin okuduğu YERDEN okuyor (`model.ifBlank {
    // defaultModel }`). İkisi ayrı yazılsaydı oturum modeli boşken çip
    // varsayılanı gösterirken nokta sönük kalırdı — bu depodaki tekrarlayan
    // hata sınıfı ("yazan dal düzeltildi, okuyan dal unutuldu").
    val runpodIsigi = if (backendId != "opencode2-app" || oturum == null) null else {
        val model = oturum.model.ifBlank { oturum.defaultModel }
        if (isRunPodModel(model)) ui3RunPodIsigi(uiState.opencode.runpod) else null
    }

    val kapsam = rememberCoroutineScope()

    // CAMIN ARKASINDAN İÇERİK GEÇSİN (kullanıcı: "açık temada glass efekt var mı
    // pek anlayamadım"). Önceki düzende akış, composer ve dock üç AYRI banttı
    // (ölçüldü: akış 330-2099, composer 2120-2470, dock 2470-2680) — camın
    // arkasında yalnız mesh gradyanı kalıyordu ve yumuşak bir gradyanı
    // bulanıklaştırmak görünmez (anayasa v2 §8, üçüncü tuzak). Blur çalışıyordu
    // ama kanıtı yoktu. Artık liste tam ekran; alt yığın onun ÜSTÜNE biniyor.
    // AKIŞIN KENDİ HAZE KAYNAĞI.
    //
    // Kökteki `hazeState`in kaynağı mesh + NavHost'un TAMAMI; composer da o
    // ağacın İÇİNDE. Haze'in kuralı: cam, kaynağın dışında KARDEŞ olmalı —
    // içerideyse kendi çıktısını örneklemeye çalışır ve blur devreye girmez.
    // Dock ve sheet kökte kardeş oldukları için çalışıyordu; composer hiç
    // çalışmamış. Cihazda görüldü: composer'ın arkasındaki metin çıtır çıtır
    // okunuyordu (kullanıcı: "açık temada glass efekt var mı pek anlayamadım").
    //
    // Çözüm yerel: listenin kendi kaynağı, composer onun KARDEŞİ.
    val akisHaze = remember { HazeState() }

    // Sekme boş mu? Ölçüt BACKEND DEĞİL, OTURUM.
    //
    // Eskiden koşul `backendId == null`'dı ve sessiz bir tuzaktı (kullanıcı
    // 18.08.2026 codex'te bildirdi): sağlayıcı çipine dokunmak `enterBackend`
    // çağırıyor, o da yalnız KİPİ değiştiriyor — oturum açmıyor, onu "Başlat"
    // (`startBackendSession`) yapıyor. Backend dolar dolmaz kart kayboluyor,
    // boş bir sohbete düşüyorsun ve her gönderim "Codex App session yok"
    // diyerek geri dönüyordu; kart bir daha da gelmiyordu, yani sekme
    // kurtarılamaz hâle geliyordu.
    //
    // `backendSession(...).sessionId` gönderim yolunun SINADIĞI değerin
    // aynısı (ConversationDelegate.send): kart, gönderim gerçekten
    // çalışabilene kadar duruyor. Oturum listesinden bir oturuma girildiğinde
    // de bu alan dolduğu için akış normal açılır.
    //
    // AMA TEK BAŞINA YETMİYOR (kullanıcı 18.08.2026 bildirdi): `activateTab`
    // sekme geçişinde ÖNCE `goToLanding()` çağırıyor, o da `exit*Mode` ile
    // backend'i ve `sessionId`'yi bir anlığına BOŞALTIYOR — bağlanma
    // (`pendingBind` → `enter*Mode`) ondan sonra geliyor. Yalnız bu alana
    // bakınca kart tam o boşlukta açılıyordu: her sekme geçişinde mesajlar
    // gelene kadar "yeni oturum" ekranı yanıp sönüyordu. ui2'de böyle bir
    // sorun yok çünkü orada yeni-oturum AYRI BİR ROTA (`chat/yeni`), sohbetin
    // içinde koşullu bir ekran değil.
    //
    // İkinci ölçüt SEKMENİN KENDİ KAYDI: `AppTab.sessionId` kalıcı durum,
    // geçiş sırasında boşalmıyor. Yeni açılmış boş sekmede ("+") o da boş
    // olduğu için kart yine çıkıyor; yani Codex tuzağı kapalı kalıyor.
    // İkisinin VEYA'sı: hangisi önce dolarsa akış açılır.
    val sekmeninOturumu = if (sekmelerEtkin) uiState.visibleTabs
        .firstOrNull { it.id == uiState.activeTabId }?.sessionId.orEmpty()
        else ""
    val oturumBasladi = sekmeninOturumu.isNotBlank() ||
        (backendId != null && uiState.backendSession(backendId).sessionId.isNotBlank())
    if (!oturumBasladi) {
        Ui3YeniOturum(
            uiState = uiState,
            actions = actions,
            modifier = modifier.fillMaxSize(),
            // DOLGU DEĞİL: ekran boşluğu kendi içinde dağıtıyor (liste iz
            // boşluğu + sabit Başlat'ın alt payı), böylece klasör listesi
            // dock'un camının arkasından geçiyor.
            altBosluk = altBosluk,
        )
        return
    }

    // Çatallama yalnız köprünün fork ucu olan backend'lerde (cowork'te de
    // sağlayıcısı bunlardansa). ui2'deki `forkSupported`'ın aynısı — OMP'de
    // native `branch` RPC'si, opencode v1/agy'de hiç yok; opencode2'de v2'nin
    // native /fork ucu köprüye bağlı (26.09.2026).
    val catalDestekli = when (uiState.backend) {
        "claude-app", "codex-app", "omp", "opencode2-app" -> true
        "cowork" -> normalizeCoworkProvider(uiState.coworkProvider) in
            setOf("claude-app", "codex-app", "omp", "opencode2-app")
        else -> false
    }

    // ── SEKME GEÇİŞİ: GELEN AKIŞ YANDAN KAYARAK GİRER ─────────────────────
    //
    // Kullanıcı isteği (19.08.2026): jestle sekme değiştirmek "tamamlanmamış"
    // görünüyordu — içerik tek karede takla atıyordu.
    //
    // GİDEN içerik canlandırılMIYOR ve bu bir eksiklik değil, ölçülmüş bir
    // sınır: sohbet durumu (mesajlar) sekmeye değil ViewModel'e ait. Geçiş
    // anında eski sekmenin mesajları artık bellekte yok, yani "çıkan sayfa"
    // diye çizilecek bir şey de yok. Onu üretmek giden listeyi bir grafik
    // katmanına kopyalamak demekti — 260ms'lik bir geri bildirim için
    // ödenmeyecek bedel. Gelen yarı tek başına da "sayfa değişti" diyor.
    //
    // Yön İNDEKS FARKINDAN geliyor, jestin yönünden değil: sekme çubuğuna
    // dokunmak da aynı animasyonu alsın. Önemli olan nereden gelindiği,
    // hangi kapıdan geçildiği değil.
    //
    // Yalnız AKIŞ kayıyor; composer, dock ve sekme çubuğu duruyor. Camın
    // fikri "sabit kabuk, akan içerik" — kabuğu da kaydırmak sayfayı değil
    // ekranı değiştiriyormuş gibi okunurdu.
    val sekmeSirasi = uiState.visibleTabs.indexOfFirst { it.id == uiState.activeTabId }
    var oncekiSira by remember { mutableStateOf(sekmeSirasi) }
    val sekmeGecisi = remember { Animatable(0f) }
    LaunchedEffect(uiState.activeTabId) {
        val yon = sekmeSirasi.compareTo(oncekiSira)
        oncekiSira = sekmeSirasi
        // İlk bileşim ve "aynı yerde kalan" sekme animasyon hak etmiyor.
        // Sıra -1 ise sekme görünür listede değil (köprü değişimi): sessiz geç.
        if (!sekmelerEtkin || yon == 0 || sekmeSirasi < 0) return@LaunchedEffect
        sekmeGecisi.snapTo(yon.toFloat())
        sekmeGecisi.animateTo(
            0f,
            tween(Ui3Tokens.ekranGecisiMs, easing = FastOutSlowInEasing),
        )
    }
    val sekmeKaymaPx = with(yogunluk) { Ui3Tokens.sekmeGecisiKayma.toPx() }

    // KAYDIRMA JESTİ — AÇIK SEKMELER ARASI GEÇİŞ (19.08.2026 kullanıcı kararı).
    //
    // Jest eskiden iki iş yapıyordu: sağa → oturum paneli (parmağı izleyen canlı
    // sürükleme), sola → cowork dosya yöneticisi. İkisi de devredildi; sebep
    // ikisinin de başka bir kapısı olması (dock'ta Oturumlar tuşu, ⋯ menüsünde
    // Çalışma klasörü satırı), sekme değiştirmenin ise yalnız ekranın tepesindeki
    // çubuğa dokunmak olmasıydı.
    //
    // ui2'den gelen KORUMA KURALLARI aynen duruyor, gerekçeleriyle:
    //  - INITIAL geçişte dinlenir ve hiçbir olay TÜKETİLMEZ. Main-pass bir
    //    detektör burada çalışmaz: akış Markwon `TextView`leriyle kaplı ve onlar
    //    dokunma akışını tüketiyor, ebeveyn drag'i hiç göremiyordu. Tüketmemek
    //    artık istisnasız: jest tek atımlık bir karar, panelin parmağı izlemesi
    //    gibi kare kare beslenen bir şey yok.
    //  - Kenar şartı YOK (sistem geri-jestiyle çakışıyordu). Yanlış tetiklemeyi
    //    dört kural önler: alt yığın bölgesi hariç, yatay baskınlık, uzun-basış
    //    muafiyeti (metin seçimi sürüklemesi jest değildir), ve yatay
    //    kaydırılabilir içerik bölgeleri (kod bloğu, tablo, görsel şeridi).
    //  - Muaf bölgeler `LocalChatSwipeExclusions`a kendi sınırlarını yazıyor;
    //    ui3 o kayıt defterini SAĞLAMIYORDU, yani ödünç aldığımız markdown ve
    //    görsel bileşenlerindeki muafiyet ui3'te sessizce ölüydü. Artık burada
    //    sağlanıyor.
    val jestMuafiyetleri = remember { ChatSwipeExclusions() }
    var akisKoordinatlari by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val jestEsigiPx = with(yogunluk) { 84.dp.toPx() }
    val altYiginPx = with(yogunluk) { (yiginY + altBosluk).toPx() }
    // Sekme listesi jest SÜRERKEN değişebilir (arka planda bir tur biter, sekme
    // başlığı tazelenir). pointerInput'u ona anahtarlamak jesti ortasından
    // keserdi; bu yüzden okuma anı ertelenmiş durumdan yapılıyor.
    val sekmelerSimdi = rememberUpdatedState(uiState.visibleTabs)
    val aktifSekmeSimdi = rememberUpdatedState(uiState.activeTabId)
    val yerelGorunum = LocalView.current

    val jest = if (!sekmelerEtkin) Modifier else Modifier
        .onGloballyPositioned { akisKoordinatlari = it }
        .pointerInput(altYiginPx) {
            val uzunBasisMs = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val bas = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Alt yığın (composer, onay, slash, ek şeridi) hariç: orada yatay
                // hareket çip kaydırmasıdır.
                if (bas.position.y > size.height - altYiginPx) return@awaitEachGesture
                val basPencerede = akisKoordinatlari?.localToWindow(bas.position)
                if (basPencerede != null && jestMuafiyetleri.contains(basPencerede)) return@awaitEachGesture
                var dx = 0f
                var dy = 0f
                var karar = false
                var yatay = false
                while (true) {
                    val olay = awaitPointerEvent(PointerEventPass.Initial)
                    val degisim = olay.changes.firstOrNull { it.id == bas.id } ?: break
                    if (!degisim.pressed) break
                    dx += degisim.position.x - degisim.previousPosition.x
                    dy += degisim.position.y - degisim.previousPosition.y
                    if (!karar) {
                        if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) continue
                        karar = true
                        // Uzun basıştan SONRA slop aşıldıysa bu metin seçimidir.
                        if (degisim.uptimeMillis - bas.uptimeMillis >= uzunBasisMs) break
                        // Dikey baskın: liste kaydırması.
                        if (kotlin.math.abs(dx) <= kotlin.math.abs(dy)) break
                        yatay = true
                    }
                    // YATAY KARARDAN SONRA OLAYLAR TÜKETİLİR. İlk sürümde
                    // tüketilmiyordu ve akış parmakla birlikte titriyordu
                    // (kullanıcı bildirdi 19.08.2026): yatay bir jestin bile
                    // birkaç piksellik dikey bileşeni var, o bileşen listeye
                    // sızıp her karede biraz kaydırıyordu. Metin de bu arada
                    // seçime giriyordu. Karar ANINA KADAR hâlâ hiçbir şey
                    // tüketilmiyor, yani dikey kaydırma/metin seçimi ayrımı
                    // bozulmuyor — bu, eski panel jestindeki istisnanın aynısı.
                    if (yatay) degisim.consume()
                    val yon = ui3SekmeJestYonu(dx, jestEsigiPx)
                    if (yon == 0) continue
                    // Uçtaki sekmede komşu yok: jest sessizce biter. "Hiçbir şey
                    // olmadı" burada doğru cevap — sekme çubuğu zaten nerede
                    // olduğunu gösteriyor, sarmak bir sekme kaydım hissini bozardı.
                    val hedef = ui3KomsuSekme(sekmelerSimdi.value, aktifSekmeSimdi.value, yon)
                    if (hedef != null) {
                        // Liste sökülmeden ÖNCE odak bırakılır; gerekçesi
                        // `ui3OduncOdagiBirak` üstünde (canlı çökme).
                        yerelGorunum.ui3OduncOdagiBirak()
                        actions.activateTab(hedef)
                    }
                    break
                }
            }
        }

    CompositionLocalProvider(LocalChatSwipeExclusions provides jestMuafiyetleri) {
    // OKUMA YÜZEYİ KÂĞITLAŞIR (kullanıcı kararı, 18.08.2026): mesh doğrudan
    // metnin altında kalınca zeminin parlaklığı lekeden lekeye değişiyor ve
    // uzun cevap okumayı yoruyordu — resmî Claude uygulamasıyla kıyasta çıktı.
    // %93 opak bg0 örtüsü mesh'i fısıltıya indirir; sheet'lerdeki "sakin kip"in
    // (GlassSheet %95) okuma akışına uyarlanmış hâli. Dock/composer/balonlar
    // cam kalır: örtü hazeSource ağacının İÇİNDE, cam onları örtüyle birlikte
    // bulanıklaştırmaya devam eder. Mesh diğer ekranlarda dokunulmadan sürer.
    Box(modifier.fillMaxSize().background(Ui3Colors.bg0.copy(alpha = 0.93f)).then(jest)) {
        LazyColumn(
            state = listeDurumu,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { if (it.size.height != akisY) akisY = it.size.height }
                .hazeSource(akisHaze)
                .testTag("akis")
                // Katman EN İÇTE: `hazeSource` çerçevesi yerinde kalsın,
                // yalnız çizilen içerik kaysın. Aksi halde composer'ın camı
                // 260ms boyunca kayan bir kaynağı örnekler ve bulanıklık
                // titrerdi. Değer `graphicsLayer` bloğunun İÇİNDE okunuyor:
                // her karede yeniden çizim olur, yeniden BESTELEME olmaz.
                .graphicsLayer {
                    translationX = if (sekmelerEtkin) sekmeGecisi.value * sekmeKaymaPx else 0f
                    alpha = if (sekmelerEtkin) 1f - kotlin.math.abs(sekmeGecisi.value) else 1f
                },
            contentPadding = PaddingValues(
                start = Ui3Tokens.s12,
                end = Ui3Tokens.s16,
                top = Ui3Tokens.s8,
                // Son mesaj camın altında kilitli kalmasın: yığın + dock kadar
                // dolgu bırakılır, kaydırınca metin camın altından geçer.
                // Fazladan boşluk YOK — dipteyken son mesajla composer arasında
                // gereksiz bir açıklık kalıyordu (kullanıcı bildirdi).
                bottom = yiginY + altBosluk,
            ),
            verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        ) {
            // ui3'te liste ters DEĞİL (ui2 `reverseLayout` kullanıyor), yani
            // eski mesajlar yukarıda ve tuş listenin BAŞINA gelir.
            if (eskiVar) {
                item(key = "__eski__") {
                    // METİN DEĞİL SİMGE (kullanıcı kararı): akışın başında duran
                    // bir cümle mesaj gibi okunuyordu. Çift yukarı ok "burada
                    // devamı var" demenin sözsüz hâli; erişilebilirlik metni
                    // `contentDescription`da duruyor.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // Dokunma hedefi 48dp, görünen daire 32dp: tıklama DIŞ
                        // kutuda. Daire büyütülmüyor, akışın başında duran
                        // sessiz bir işaret o.
                        //
                        // Compose ıskalayan dokunuşu zaten 48dp'ye kadar en
                        // yakın düğmeye veriyor; kaydırılabilir bir ATA'nın
                        // (LazyColumn) bunu engelleyip engellemediği
                        // ölçülmedi. Bedeli akışın en başında bir kerelik
                        // 16dp olduğu için belirsizliği taşımak yerine hedef
                        // açıkça büyütüldü.
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { actions.showOlderMessages() }
                                .testTag("daha_eski"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Ui3Colors.yuzey2)
                                    .border(1.dp, Ui3Colors.cizgi, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardDoubleArrowUp,
                                    contentDescription = "Daha eskiyi göster",
                                    tint = Ui3Colors.vurguHi,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            // KARARLI ANAHTAR — ui2'de vardı, ui3'e taşınmamıştı.
            //
            // Anahtarsız `items(count)` öğeyi SIRA NUMARASIYLA tanır. Akışın
            // başına bir şey eklendiğinde (ilk sayfa gelince "daha eskiyi
            // göster" tuşu beliriyor, `showOlderMessages` 100 mesaj öne
            // ekliyor) bütün numaralar kayıyor ve LazyColumn görünen yeri
            // numaraya göre koruduğu için içerik birkaç mesaj zıplıyordu
            // (kullanıcı bildirdi 19.08.2026: "biraz yukarı kaydırınca 2-3
            // mesaj öncesine zıplıyor"). Anahtar verilince liste yeri
            // KİMLİĞE göre koruyor; araya giren satır onu oynatmıyor.
            //
            // Kural ui2'nin birebir aynısı: tekilde rowId (yoksa kronolojik
            // index), grupta ilk üyenin anahtarı "grp_" önekiyle — akışta
            // gruba yeni adım eklense de anahtar sabit kalır.
            items(
                count = satirlar.size,
                key = { i -> ui3SatirAnahtari(mesajlar, satirlar, i) },
            ) { i ->
                val satir = satirlar[i]
                val sonSatir = i == satirlar.lastIndex
                // Çapa sarmalayıcı: satır boyu ölçülür ve yeniden bestelemede
                // ilk karelerde min yükseklik olarak geri verilir. Akan son
                // satırda KAPALI — metni her karede uzuyor.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .ui3YukseklikCapasi(
                            anahtar = ui3SatirAnahtari(mesajlar, satirlar, i),
                            onbellek = yukseklikOnbellegi,
                            etkin = !(sonSatir && uiState.running),
                        ),
                ) {
                    when (satir) {
                        is ChatDisplayRow.Single -> mesajlar.getOrNull(satir.index)?.let { mesaj ->
                            Satir(
                                mesaj = mesaj,
                                canli = sonSatir && uiState.running,
                                dusunceDetaylari = dusunceDetaylari,
                                onDusunceYukle = { actions.loadThought(it) },
                                onDosya = onDosya,
                                // Geri sarma her backend'de var: köprü ucu yoksa
                                // delege görünümü yerel olarak geri sarıyor.
                                onDon = { actions.returnToMessage(satir.index) },
                                // Çatallama YOK ise tuş hiç çizilmez — köprünün fork
                                // ucu opencode/agy'de bulunmuyor.
                                onCatalla = if (catalDestekli) ({ actions.forkFromMessage(satir.index) }) else null,
                            )
                        }
                        is ChatDisplayRow.ToolGroup -> {
                            val dusunceMi = satir.kind == GroupKind.THOUGHT
                            AracGrubu(
                                adet = satir.indices.size,
                                ilk = mesajlar.getOrNull(satir.indices.first())?.text.orEmpty(),
                                canli = sonSatir && uiState.running,
                                dusunceMi = dusunceMi,
                                onAc = { onAracAyrintisi(satir.indices, dusunceMi) },
                            )
                        }
                    }
                }
            }

            // AskUserQuestion — akışın SON öğesi, `items`ten sonra emit ediliyor
            // ki kartlar soruyu doğuran mesajların ALTINDA dursun. ui2'de de
            // listenin parçası (orada liste ters olduğu için emisyon sırası
            // tersine yazılmış).
            if (sorular.isNotEmpty() && onay != null) {
                item(key = "__soru__") {
                    val yonlendirici = ApprovalActionRouter(uiState, actions)
                    Ui3SoruAkisi(
                        sorular = sorular,
                        taslak = taslak,
                        onTaslak = { taslak = it },
                        onCevapla = { yonlendirici.answerQuestions(taslak.orderedAnswers(sorular)) },
                        onRet = { yonlendirici.approve(false) },
                    )
                }
            }
        }

        // En alta inme kısayolu — kullanıcı yukarı kaydırmışken görünür.
        // ui2'de de var; uzun bir turda yukarı bakarken akışa dönmenin tek
        // pratik yolu bu (aksi halde parmakla dibe kadar kaydırıyorsun).
        if (listeDurumu.canScrollForward) {
            // Dokunma hedefi 48dp, görünen daire 36dp. Daire büyütülmüyor:
            // akışın üstünde duran geçici bir kısayol, göz almamalı.
            //
            // Burada büyütmek ŞART: bu tuş listenin ÜSTÜNDE duruyor ve liste
            // kardeş bir kaydırma düğümü. Compose'un "ıskalayan dokunuşu
            // 48dp'ye kadar en yakın düğmeye ver" davranışı ancak katı bir
            // isabet yokken devreye giriyor; burada katı isabet listeye
            // gidiyordu, yani 36dp'nin dışı tuşa değil akışa dokunmaktı.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = yiginY + altBosluk + Ui3Tokens.s8)
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        kapsam.launch {
                            // ANİMASYONLU KAYDIRMA YOK.
                            //
                            // `animateScrollToItem` aradaki BÜTÜN öğeleri sırayla
                            // besteleyip ölçüyor; bu akışta öğeler markdown
                            // `AndroidView`i (TextView) ve her biri pahalı.
                            // Yüzlerce satırlık bir turda iniş takıla takıla
                            // saniyeler sürüyordu (kullanıcı iki kez bildirdi).
                            // `scrollToItem` doğrudan hedefe atlıyor: yalnız
                            // görünen pencere bestelenir.
                            dibeYerles()
                            otomatikTakip = true
                        }
                    }
                    .testTag("dibe_in"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Ui3Colors.cizgi)
                        .border(1.dp, Ui3Colors.cizgi, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "En alta in",
                        tint = Ui3Colors.ink,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        // ALT YIĞIN: slash + onay + composer. Listenin ÜSTÜNE biner ve dock
        // kadar yukarıda durur. Yüksekliği ölçülüp listenin alt dolgusuna
        // veriliyor (yukarıdaki yiginY).
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = altBosluk)
                .onGloballyPositioned { yerlesim ->
                    val yeni = with(yogunluk) { yerlesim.size.height.toDp() }
                    if (yeni != yiginY) yiginY = yeni
                },
        ) {
        // RUNPOD HAPI ARTIK BURADA DEĞİL (25.08.2026, kullanıcı: "eğreti
        // duruyor"). Kendine ait bir satırda, yığının en üstünde duruyordu:
        // opencode oturumunun tamamı boyunca yer yiyen, kardeşi olmayan yalnız
        // bir tuş — RunPod seçili olmasa bile. Hap MODEL SEÇ sheet'ine, RunPod
        // satırının sonuna taşındı (bkz. Ui3Root); sohbette geriye model
        // çipindeki nokta kaldı (aşağıda `runpodIsigi`).
        //
        // Geçiş sürerken durumu tazeleyen döngü BURADA KALDI: noktayı besleyen
        // o. Uygulama runpod'i aksi halde yalnız elle (Kullanım > Yenile)
        // yokluyor; başlatma arka planda bitince nokta "açılıyor"da takılırdı.
        // Geçiş bitince rpMesgul false olur, anahtar değişir, döngü iptal edilir.
        if (backendId == "opencode2-app") {
            val rpMesgul = uiState.opencode.runpod.operationActive ||
                uiState.opencode.runpod.phase == "starting" ||
                uiState.opencode.runpod.phase == "stopping"
            LaunchedEffect(rpMesgul) {
                if (rpMesgul) {
                    while (true) {
                        delay(3000)
                        actions.refreshRunPodStatus(showErrors = false)
                    }
                }
            }
        }

        // GERİ SARILDI ŞERİDİ — panonun da üstünde, çünkü konuşmanın TAMAMINA
        // dair bir durum: altındaki her şey (pano, onay kartı, composer) o
        // sarılmış hâlin üstünde çalışıyor. "Geri Al" yalnız yeni bir mesaj
        // gönderilene kadar işe yarıyor (köprüde ölçüldü), o yüzden burada
        // durup göze çarpması gerekiyor.
        uiState.opencodeFamily(backendId).reverted?.takeIf { backendId != null && uiState.backendRevertSupported(backendId) }?.let { sarilmis ->
            Ui3GeriSarSeridi(
                durum = sarilmis,
                onGeriAl = { actions.unrevertBackend(backendId!!) },
                modifier = Modifier.padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp),
            )
        }

        // GÖREV PANOSU — yalnız opencode'da ve yalnız gösterecek bir şey varken
        // (todo listesi dolu ya da bağlam eşiği aşıldı; bkz. gorevPanosuGorunur).
        // Yığının EN ÜSTÜNDE: onay ve kuyruk eylem kartları, pano ise durum —
        // eylem her zaman composer'a daha yakın durmalı.
        if (backendId == "opencode2-app") {
            Ui3GorevPanosu(
                todos = uiState.opencode.todos,
                baglamYuzdesi = uiState.opencode.contextPct,
                calisiyor = uiState.running,
                onSikistir = { actions.opencodeAppCompact() },
                modifier = Modifier.padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp),
            )
        }

        // ALT AJAN KARTLARI — panonun hemen ALTINDA. Sıra bilinçli: pano ana
        // ajanın kendi planı, bu yığın onun delege ettikleri; alt olan her
        // zaman daha türev olan. Alt-ajan yokken hiçbir şey çizilmez, o yüzden
        // yığın normalde tek satır bile yer kaplamıyor.
        if (uiState.backendSubagentsSupported(backendId.orEmpty())) {
            Ui3AltAjanlar(
                subagents = uiState.opencode.subagents,
                onAc = { childId ->
                    actions.loadBackendSubagentTranscript(backendId.orEmpty(), childId)
                    onSheetAc(Ui3Sheet.ALT_AJAN)
                },
                modifier = Modifier.padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp),
            )
        }

        // Slash komut önerileri — girdi "/" ile başlayınca. ui2'deki filtre ve
        // 6 satır sınırı aynen: liste uzayınca composer ekrandan taşıyordu.
        //
        // GÖVDE ORTAK, MALZEME FARKLI: opencode'da liste sağlayıcının GERÇEK
        // komut kataloğu ve seçilen satır ÇALIŞIYOR; diğerlerinde statik istem
        // şablonları ve seçilen satır composer'a YAZILIYOR (bkz.
        // backendSlashSuggestions). Kaynak boşsa hiçbir şey çizilmiyor —
        // opencode'da statik tabloya DÜŞMÜYOR, çünkü o satırlar çalıştırılamaz
        // ve "seçtiğin komut koşar" sözünü sessizce bozardı.
        val slashSorgu = uiState.input.takeIf { it.startsWith("/") }
            ?.removePrefix("/")?.substringBefore(' ')?.lowercase().orEmpty()
        // "/review HEAD~1" → argüman "HEAD~1". Komut adından SONRAKİ her şey
        // argümandır; serve şablondaki $ARGUMENTS'a bunu koyuyor.
        val slashArguman = uiState.input.takeIf { it.startsWith("/") }
            ?.substringAfter(' ', "")?.trim().orEmpty()
        val slashKaynak = if (backendId != null) uiState.backendSlashSuggestions(backendId) else emptyList()
        val slashEslesme = if (slashSorgu.isBlank()) slashKaynak else {
            slashKaynak.filter {
                it.name.contains(slashSorgu, true) || it.desc.contains(slashSorgu, true)
            }
        }.take(6)
        if (uiState.input.startsWith("/") && slashEslesme.isNotEmpty()) {
            // GERÇEK cam (blur'lu), `GlassLikeSurface` DEĞİL: bu kart akışın
            // kardeşi, altından keskin sohbet metni geçiyor. Blur'suz sürümde
            // arkadaki paragraf komut adlarının içinden okunuyordu — kullanıcı
            // "yazılar çok şeffaf, okunmuyor" diye bildirdi, ekran görüntüsünde
            // kartın altındaki metin komutlarla iç içeydi.
            GlassSurface(
                hazeState = akisHaze,
                modifier = Modifier
                    .padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp)
                    .fillMaxWidth()
                    .testTag("slash_liste"),
                shape = RoundedCornerShape(Ui3Tokens.r18),
            ) {
                // YÜKSEKLİK SINIRI ŞART: Column'da ağırlıksız çocuklar önce
                // ölçülüyor, yani sınırsız bırakılan bu kart klavye açıkken
                // composer'a yer bırakmıyordu — cihazda metin alanı ince bir
                // çizgiye inip şerit tamamen kayboldu. Liste kendi içinde
                // kaydırılır, composer boyunu hep korur.
                Column(
                    Modifier
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Ui3Tokens.s4),
                ) {
                    slashEslesme.forEach { komut ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (komutlarDestekli && backendId != null) {
                                        actions.runBackendCommand(backendId, komut.name, slashArguman)
                                    } else {
                                        actions.insertSlashCommand(komut.name)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "/${komut.name}",
                                style = Ui3Type.govde,
                                color = Ui3Colors.ink,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (komut.desc.isNotBlank()) {
                                Text(
                                    komut.desc,
                                    style = Ui3Type.alt,
                                    // ink3 DEĞİL: ink3 üçüncül mürekkep, cam üstünde
                                    // açık temada okunmuyordu.
                                    color = Ui3Colors.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ONAY: akışın dışında, composer'ın üstünde sabit. Yalnız DÜZ ve
        // SEÇENEKLİ onay — tek dokunuşluk, kısa, gözden kaçmasın diye burada.
        // Soru (AskUserQuestion) artık akışın içinde çiziliyor (bkz. yukarıda
        // `__soru__` öğesi), o yüzden burada dışarıda bırakılıyor.
        if (onay != null && sorular.isEmpty()) {
            val yonlendirici = remember(uiState, actions) { ApprovalActionRouter(uiState, actions) }
            Ui3OnayBolgesi(
                onay = onay,
                // Kök kaynağı DEĞİL: bu kart listenin kardeşi, listeyi bulanıklaştırır.
                hazeState = akisHaze,
                calismaDizini = oturum?.cwd?.ifBlank { null },
                onIzin = { yonlendirici.approve(true) },
                onRet = { yonlendirici.approve(false) },
                onSecenek = { yonlendirici.selectOption(it) },
                onHepsineIzin = yonlendirici.allowForSession(),
                modifier = Modifier.padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp),
            )
        }

        // BEKLEYEN PROMPT KUYRUĞU — tur sürerken gönderilenler. Eklerin de
        // ÜSTÜNDE: kuyruk "gönderdim, sırasını bekliyor" der, ek şeridi ise
        // "henüz göndermedim" — sıralama gönderim anına göre.
        Ui3KuyrukPaneli(
            hazeState = akisHaze,
            kuyruk = uiState.activeQueuedPrompts(),
            onCikar = { actions.removeQueuedPrompt(it) },
            modifier = Modifier.padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp),
        )

        // BEKLEYEN EKLER — henüz gönderilmemiş dosyalar. Composer'ın ÜSTÜNDE,
        // onayın altında: gönder tuşuna basmadan önce neyin ekli olduğunu
        // görmek gerekiyor. Çipler ui2'den ([AttachmentChip]) — küçük görsel
        // önizlemesi, tür ikonu ve ✕ mantığı orada; ui3'e kopyalamak aynı
        // davranışı iki yerde tutmak olurdu.
        if (uiState.attachments.isNotEmpty()) {
            GlassSurface(
                hazeState = akisHaze,
                modifier = Modifier
                    .padding(start = Ui3Tokens.s16, end = Ui3Tokens.s16, bottom = 6.dp)
                    .fillMaxWidth()
                    .testTag("ek_seridi"),
                shape = RoundedCornerShape(Ui3Tokens.r18),
            ) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ui3OduncKap {
                        uiState.attachments.forEach { ek ->
                            AttachmentChip(
                                ek,
                                onRemove = { actions.removeAttachment(ek.path) },
                                onPreview = {
                                    // Gönderilmiş eklerle AYNI tam ekran
                                    // görüntüleyici. attachment=false: "PC'de
                                    // sakla" burada anlamsız, dosya zaten
                                    // kullanıcının kendi seçtiği dosya.
                                    actions.setImageNavigation(
                                        uiState.attachments.filter { it.isImage }.map { it.path },
                                        local = false,
                                    )
                                    actions.openImage(ek.path, local = false)
                                    onGoruntuleyici()
                                },
                            )
                        }
                    }
                }
            }
        }

        // Şeritteki tuş SÜREN TURU YÖNLENDİRİR; kuyruk değil. O yüzden steer
        // yeteneği soruluyor — yalnız kuyruğu olan bir backend'de (OpenCode) bu
        // tuş çizilirse mesaj sessizce başka anlamda gönderilirdi.
        val turOrtasiDestekli = uiState.midTurnSteerSupported()
        Ui3Composer(
            hazeState = akisHaze,
            metin = uiState.input,
            onMetin = { actions.updateInput(it) },
            calisiyor = uiState.running,
            // Yalnız ek varken de gönderilebilir (ui2 ile aynı koşul): "şu
            // dosyaya bak" demek için metin şart değil.
            gonderilebilir = uiState.input.isNotBlank() || uiState.attachments.isNotEmpty(),
            // Gönderim dibe takibi geri açar (ui2 ile aynı): yukarıda gezinirken
            // gönderirsen liste dibe iner ve akışla akmaya devam eder. Tur
            // sürerken bu tuş prompt'u KUYRUĞA alır (RemoteViewModel.sendPrompt).
            onGonder = {
                if (uiState.input.isNotBlank() || uiState.attachments.isNotEmpty()) {
                    otomatikTakip = true
                }
                actions.sendPrompt()
            },
            onDurdur = { actions.stop() },
            modelEtiketi = modelEtiketi,
            runpodIsigi = runpodIsigi,
            onModel = { onSheetAc(Ui3Sheet.MODEL) },
            izinGevsek = permissionModeIsPermissive(oturum?.permissionMode.orEmpty()),
            onMenu = { onSheetAc(Ui3Sheet.MENU) },
            yenileniyor = uiState.conversationRefreshing,
            onYenile = { actions.resyncConversation() },
            onKullanim = { onSheetAc(Ui3Sheet.KULLANIM) },
            kullanimGoster = !uiState.liteEdition,
            onEk = { onSheetAc(Ui3Sheet.EK_KAYNAK) },
            // Lite: klasör kısayolunun yerine arama ikonu gelir. Klasör aynı
            // eylemle ⋯ menüsünde yaşamaya devam eder.
            onKlasor = if (uiState.liteEdition) null else onCalismaKlasoru,
            onAra = if (uiState.liteEdition) ({ actions.openChatSearch() }) else null,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s16, vertical = 6.dp),
            seritTam = seritTam,
            // Tur ortası yönlendirme yalnız steer'i olan backend'lerde. EK VARKEN
            // KAPALI: bu uçlar yalnız metin taşıyor, ek sessizce düşerdi — kuyruk
            // yolu ekleri koruyor, o yüzden ek varken tek yol kuyruk.
            onYonlendir = if (turOrtasiDestekli && uiState.attachments.isEmpty()) ({
                otomatikTakip = true
                actions.backendSendDuringTurn(interrupt = true)
                Unit
            }) else null,
        )
        }
    }
    }
}

/**
 * Akıştaki tek mesaj satırı.
 *
 * Rol ayrımı ui2'nin `ChatRootScreen`'indeki `when` zincirinin aynısı — hangi
 * mesajın düşünce, hangisinin ajan metni sayıldığı iki arayüzde farklı
 * olmamalı:
 *  - kullanıcı → vurgu tonlu balon
 *  - `thought` + detay indeksi ≥ 0 → katlanır kart, açılınca detay yüklenir
 *  - `thought` + uzun (>200) → katlanır kart, gövde satırın kendisinde
 *  - `thought` + kısa → düşünce şeridi
 *  - gerisi → markdown + kopyala satırı
 */
@Composable
private fun Satir(
    mesaj: ChatMessage,
    canli: Boolean,
    dusunceDetaylari: Map<Int, String>,
    onDusunceYukle: (Int) -> Unit,
    onDosya: (String) -> Unit,
    onDon: (() -> Unit)? = null,
    onCatalla: (() -> Unit)? = null,
) {
    val kullanici = mesaj.role.equals("user", ignoreCase = true)
    if (kullanici) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            GlassLikeSurface(
                // İÇERİĞE GÖRE DARALIR: `fillMaxWidth(0.82f)` üst sınırı verir,
                // `wrapContentWidth(End)` çocuğu min=0 ile ölçüp sağa yaslar.
                // Eskiden yalnız ilki vardı ve "/compact" gibi tek kelimelik bir
                // mesaj da ekranın %82'sini kaplıyordu (kullanıcı bildirdi).
                // ui2 aynı çözümü kullanıyor (`ui2/chat/Messages.kt`).
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .wrapContentWidth(Alignment.End)
                    .testTag("mesaj_kullanici"),
                // Mockup .user: 24/24/8/24 — sağ altı kırık, "buradan çıktı" jesti.
                shape = RoundedCornerShape(
                    topStart = 24.dp, topEnd = 24.dp, bottomEnd = 8.dp, bottomStart = 24.dp,
                ),
                tint = GlassTint.Vio,
            ) {
                Text(
                    mesaj.text,
                    // Ajandan yarım punto küçük — bilinçli ayrım (kullanıcı,
                    // 18.08.2026); gerekçe Ui3Type.akis'in üstünde.
                    style = Ui3Type.akisKullanici,
                    color = Ui3Colors.ink,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                )
            }
            // Eylem satırı balonun ALTINDA, İÇİNDE değil (ui2'de içindeydi):
            // balon içeriğe göre daralabilsin diye. Ajan tarafındaki kopyalama
            // satırının aynısı, sağa yaslı ve iki tuş fazlası.
            Ui3KopyalaSatiri(
                metin = mesaj.text,
                zaman = mesaj.time,
                onDon = onDon,
                onCatalla = onCatalla,
                saga = true,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        return
    }
    val dusunce = mesaj.role.equals("thought", ignoreCase = true)
    if (dusunce) {
        RayliSatir(canli = canli, dugum = DugumTuru.Arac) {
            when {
                mesaj.thoughtIndex >= 0 -> {
                    // Açık/kapalı durumu YEREL ve detayın varlığından bağımsız:
                    // ui2'de `expanded = detail != null` yazıldığında kart bir
                    // kez açılınca bir daha kapanmıyordu.
                    var acik by rememberSaveable(
                        mesaj.rowId.ifEmpty { mesaj.thoughtIndex.toString() }
                    ) { mutableStateOf(false) }
                    Ui3KatlanirKart(
                        tur = if (isToolSummary(mesaj.text)) "Araç" else "Düşünce",
                        ozet = mesaj.text.ifBlank { "🧠" },
                        acik = acik,
                        onAcKapa = {
                            acik = !acik
                            if (acik) onDusunceYukle(mesaj.thoughtIndex)
                        },
                    ) {
                        Text(
                            dusunceDetaylari[mesaj.thoughtIndex] ?: "Yükleniyor…",
                            style = Ui3Type.alt,
                            color = Ui3Colors.ink2,
                        )
                    }
                }
                // Detay indeksi OLMAYAN uzun düşünce (eski opencode geçmişi):
                // tam metin satırın kendisinde, gövde yerelden açılır.
                mesaj.text.length > 200 -> {
                    var acik by rememberSaveable(
                        mesaj.rowId.ifEmpty { mesaj.text.take(24) }
                    ) { mutableStateOf(false) }
                    Ui3KatlanirKart(
                        tur = "Düşünce",
                        ozet = mesaj.text.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "🧠" },
                        acik = acik,
                        onAcKapa = { acik = !acik },
                    ) {
                        Text(mesaj.text, style = Ui3Type.alt, color = Ui3Colors.ink2)
                    }
                }
                else -> Ui3DusunceSeridi(mesaj.text)
            }
        }
        return
    }
    RayliSatir(canli = canli, dugum = DugumTuru.Metin) {
        Column(verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s4)) {
            Ui3AjanMetni(metin = mesaj.text, onDosya = onDosya)
            Ui3KopyalaSatiri(metin = mesaj.text, zaman = mesaj.time)
        }
    }
}

/**
 * Araç grubu çipi — dokununca ayrıntı **cam sheet'te** açılır (kullanıcı kararı).
 *
 * Önceki sürümde çip "Araç · 3 adım" yazıp susuyordu; ui2'nin `ToolCallCard`'ı
 * yerinde genişleyip komutu ve çıktıyı gösterdiği için bu bir regresyondu.
 * Sheet seçildi: uzun çıktı akışı şişirmiyor ve liste hiç sıçramıyor.
 */
@Composable
private fun AracGrubu(adet: Int, ilk: String, canli: Boolean, dusunceMi: Boolean, onAc: () -> Unit) {
    RayliSatir(canli = canli, dugum = DugumTuru.Arac) {
        GlassLikeSurface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ui3Tokens.r18))
                .clickable(onClick = onAc)
                .testTag("arac_cipi"),
            shape = RoundedCornerShape(Ui3Tokens.r18),
        ) {
            Row(
                Modifier.padding(start = 13.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canli) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Ui3Colors.running))
                }
                Text(
                    // Düşünce grubunu "Araç" diye etiketlemek yanlış bilgi:
                    // ui2 de `GroupKind`'a bakıp ayırıyordu.
                    if (dusunceMi) "Düşünce · $adet adım" else "Araç · $adet adım",
                    style = Ui3Type.alt,
                    color = if (canli) Ui3Colors.running else Ui3Colors.ink2,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ilk.take(60),
                    style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Araç ayrıntısı",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private enum class DugumTuru { Metin, Arac }

/**
 * Sol oluğunda ray ve düğüm taşıyan satır.
 *
 * Ray'in akan ışığı yalnız [canli] iken çizilir. Bu, "sürekli animasyona hakkı
 * olan tek şey çalışan bir tur" kuralının (anayasa v2 §5) doğrudan karşılığı:
 * tur bitince `rememberInfiniteTransition` hiç kurulmaz, kare üretimi durur.
 */
@Composable
private fun RayliSatir(
    canli: Boolean,
    dugum: DugumTuru,
    icerik: @Composable () -> Unit,
) {
    val dugumBoyu = if (dugum == DugumTuru.Arac) 11.dp else 8.dp
    val akis = if (canli) {
        val gecis = rememberInfiniteTransition(label = "ray")
        gecis.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
            label = "ray-akis",
        ).value
    } else null

    // Palet renkleri DrawScope'a girmeden ÖNCE okunur: `Ui3Colors.*` artık
    // composable erişimci (tema paletinden okuyor) ve `drawBehind` lambdası
    // composable değil.
    val rayUst = Ui3Colors.vurguHi.copy(alpha = 0.55f)
    val rayOrta = Ui3Colors.cizgi
    val rayAlt = Ui3Colors.cizgi.copy(alpha = 0.04f)
    val rayCanli = Ui3Colors.running

    // IntrinsicSize.Min: ray, satırın gerçek yüksekliği kadar uzasın. Bu olmadan
    // Row yüksekliği içerikten gelir ama ray'in fillMaxHeight'ı ölçülemez.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(OLUK)
                .fillMaxHeight()
                // KIRPMA ŞART: akan ışık dikdörtgeni -0.4h ile 1.74h arasında
                // geziyor, yani satırın üstüne ve altına TAŞIYOR. `drawBehind`
                // düğümün sınırlarına kendiliğinden kırpmaz; ışık komşu
                // satırların üstüne biniyor ve "efekt çizginin dışına çıkıyor"
                // olarak görünüyordu (kullanıcı iki kez bildirdi).
                .clipToBounds()
                .drawBehind {
                    val x = RAY_MERKEZ.toPx()
                    // Sabit ray: yukarıdan aşağı sönen vurgu rengi hat.
                    drawLine(
                        brush = Brush.verticalGradient(
                            0f to rayUst,
                            0.45f to rayOrta,
                            1f to rayAlt,
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = RAY_GENISLIK.toPx(),
                    )
                    // Akan ışık: rayın üstünde aşağı kayan cyan parça.
                    if (akis != null) {
                        val boy = size.height * 0.34f
                        val ust = size.height * akis
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.5f to rayCanli,
                                1f to Color.Transparent,
                                startY = ust,
                                endY = ust + boy,
                            ),
                            topLeft = Offset(x - RAY_GENISLIK.toPx() / 2f, ust),
                            size = Size(RAY_GENISLIK.toPx(), boy),
                        )
                    }
                },
        )
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth()) {
                icerik()
                // Düğüm: rayın üstünde, satırın başında duran işaret. Metin
                // satırında içi boş daire, araç satırında kare — mockup'ın
                // .node.hollow / .node.tool ayrımı. Canlıyken uç nabız atar.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        // Düğümün MERKEZİ rayın merkezine otursun:
                        // sol kenar = RAY_MERKEZ − boy/2, oluk kadar geri kaydır.
                        .offset(x = RAY_MERKEZ - dugumBoyu / 2 - OLUK, y = 4.dp)
                        .size(dugumBoyu)
                        .clip(if (dugum == DugumTuru.Arac) RoundedCornerShape(4.dp) else CircleShape)
                        .background(if (canli) Ui3Colors.running else Ui3Colors.bg0)
                        .border(
                            1.5.dp,
                            if (canli) Ui3Colors.running else Ui3Colors.ink3,
                            if (dugum == DugumTuru.Arac) RoundedCornerShape(4.dp) else CircleShape,
                        ),
                )
            }
        }
    }
}

// NOT: burada eskiden `ui3TurOrtasiDestekli()` vardı ve iki yolu (yönlendir /
// ajana bırak) TEK koşulda birleştirip backend adına gömüyordu. 25.08.2026'da
// kaldırıldı: OpenCode'da yalnız kuyruk var, yönlendirme yok — birleşik koşul
// olmayan bir uca tuş çizerdi. Yerine shared/MidTurnSend.kt'teki iki ayrı,
// capability'den türeyen soru geçti (midTurnSteerSupported / midTurnQueueSupported).
