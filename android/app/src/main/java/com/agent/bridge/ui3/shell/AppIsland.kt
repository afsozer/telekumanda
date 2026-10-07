package com.agent.bridge.ui3.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import com.agent.bridge.ui3.theme.Ui3Tokens
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- ölçüler
//
// Aşağıdaki dört sayının üçü CİHAZDAN ÖLÇÜLDÜ (17.08.2026, Honor Magic7 Pro,
// 1280×2800 @ density 3.5 → 365.7dp genişlik). Tahmin değiller:
//
//   çentik     dumpsys window → boundingRect Rect(499,0-781,141)
//              = 142.6dp … 223.1dp, yükseklik 40.3dp (= durum çubuğu yüksekliği)
//   saat       ekran görüntüsü piksel taraması (ui-olc piksel): mürekkep
//              17.7dp'de başlıyor, 57.1dp'de bitiyor
//   ikonlar    aynı tarama: wifi/sinyal/pil mürekkebi 257.1dp … 345.7dp
//
// Yani kullanılabilir iki bant: solda 57.1→142.6 (85.4dp), sağda 223.1→257.1
// (34dp). Kapsüller BU bantların içinde kalmak zorunda — sistem saatinin ya da
// pil ikonunun altına siyah bir hap koymak onları okunmaz yapardı (SystemUI
// bizim pencerenin ÜSTÜNE çiziyor, biz onu itemiyoruz).

/**
 * Kapsül yüksekliği — kamera deliğini KAPSAYACAK kadar.
 *
 * Bunun için iki tur gerekti. Önce iki ayrı hap deliğin yanına kondu ve
 * aralarında boşluk kaldı. Sonra hapların iç köşeleri düzleştirilip delik
 * sınırının 3dp içine sokuldu; yine tek parça olmadı, çünkü FİZİKSEL delik
 * çerçevenin bildirdiği kutudan çok daha alçak: `boundingRect` 141px (40.3dp)
 * diyor, deliğin kendisi kullanıcının fotoğrafında ~17dp çıktı. Hapımız
 * delikten yüksek olduğu için birleşme yerinde basamak oluşuyordu.
 *
 * Çözüm Honor'un kendi kapsülünden geldi: gelen arama sırasında ekranı ölçtüm,
 * sistem kapsülü de delikten yüksek (~30dp) ve deliğin yanına değil, ÜSTÜNE
 * oturuyor — deliği içine alıyor. Biz de öyle yapıyoruz: kamera bölgesi boş
 * bırakılmıyor, DÜZ SİYAH dolduruluyor. Delik o siyahın içinde kayboluyor
 * (siyah üstüne siyah) ve dışbükey tek bir çubuk kalıyor.
 *
 * Yükseklik ve konum kullanıcının fotoğrafından ÖLÇÜLDÜ (17.08.2026, deliğin
 * kamera mercekleri referans alınarak): fiziksel delik, çerçevenin bildirdiği
 * 40.3dp'lik kutunun **15.5 … 33.2dp** aralığında ve 17.7dp yüksekliğinde.
 * Kutunun ortasına hizalanan ilk sürüm bu yüzden yukarı kaçıyordu — altta hiç
 * pay yokken üstte 8.8dp taşma vardı (kullanıcı: "çok az aşağı").
 *
 * 26 → 31.4dp (18.08.2026): sistem kapsülü açıkken (medya çalarken) ikisi üst
 * üste biniyor ve aradaki 5.4dp basamak olarak görünüyordu. Üç ekran görüntüsü
 * piksel taramasıyla ölçüldü — sistem kapsülü 110px (31.43dp) yüksek, dikey
 * merkezi 24.43dp; bizimki 91px, merkezi 24.29dp. Yani merkezler ZATEN aynı,
 * fark yalnız yükseklikte. Eşitlenince iki hap tek çubuk gibi kaynıyor: ikisi
 * de düz siyah ve yarıçapları yükseklik/2, siyah üstüne siyahın dikişi olmuyor.
 *
 * GENİŞLİK bilerek eşitlenmedi (sistem 154dp, biz 168.6dp). Aynı yükseklik ve
 * merkezde iki hapın BİRLEŞİMİ zaten tek uzun kapsül siluetidir; genişliği de
 * sisteme çekmek sol bandı 57dp'den 36dp'ye indirir, "boşta" metnini ve tur
 * sayacını kırpardı. Üstelik ikisinin de genişliği içeriğe göre değişiyor —
 * birebir eşitlik kalıcı olarak tutturulabilir bir şey değil.
 */
private val HAP_Y = 31.4.dp

/**
 * Deliğin ölçülen dikey ORTASI, kesik kutusunun üstünden itibaren.
 * Çubuk buna göre ortalanıyor: 24.35 ∓ 13 → 11.35 … 37.35dp. Delik iki uçtan
 * da 4.15dp içeride kalıyor, durum çubuğunun altına da 3dp pay kalıyor.
 */
private val DELIK_MERKEZI = 24.35.dp

/** Solda sistem saati + senkron simgesi için ayrılan yer (ölçüm 57.1dp, üstüne pay). */
private val SAAT_REZERVI = 60.dp

/** Sağda wifi/sinyal/pil için ayrılan yer (ölçüm: 365.7 − 257.1 = 108.6dp, üstüne pay). */
private val IKON_REZERVI = 109.dp

/** Bant bundan darsa o taraf HİÇ çizilmez — sistem yazısına binmektense boş kalsın. */
private val ASGARI_BANT = 20.dp

/** Çentiksiz ekran (tablet, emülatör): tek ortalanmış hap. */
private val YEDEK_HAP_Y = 28.dp

// Ada HER İKİ TEMADA siyah (sistem kapsülünü taklit ediyor), o yüzden üstündeki
// renkler de paletten GELMEZ — sabittir. Paletten okunduğunda açık temada
// `ink2` (#6D6A7E) ve `attention` (#B07D1A) siyah hapın üstünde okunmuyordu.
private val ADA_MUREKKEP = Color(0xFFD6DAE2)
private val ADA_MUREKKEP2 = Color(0xFF97A0B0)
private val ADA_CALISIYOR = Color(0xFF74A7DB)
private val ADA_ONAY = Color(0xFFF0B34E)
private val ADA_BOSTA = Color(0xFF8792A2)

/**
 * Adanın göstereceği bağlam yüzdesi — ya da HİÇBİR ŞEY.
 *
 * opencode'da TEK KAYNAK köprünün kendi hesabı ([contextPct]). Ada eskiden
 * yüzdeyi kendi bölmesiyle türetiyordu ve canlıda %100 yalanı çıktı: köprü
 * pencere kataloğu soğukken `contextWindow=0, contextPct=null` ("ölçemiyorum")
 * gönderiyor, ama üst-düzey pencere alanında ÖNCEKİ oturumdan kalan küçük bir
 * sayı (yerel modelin 12k'sı) duruyordu; yeni oturumun 44k token'ı ona
 * bölününce oran tavana yapışıyordu. Köprü "bilmiyorum" derken arayüzün kendi
 * başına bir sayı uydurması, panonun ve adanın da farklı şeyler göstermesi
 * demekti — iki gösterge tek kaynağa bağlandı.
 *
 * Diğer backend'lerde köprü `contextPct` göndermiyor; orada bölme tek yol
 * olduğu için duruyor. Pencere bilinmiyorsa (0) yine null: yüzde çizilmez.
 */
internal fun adaBaglamYuzdesi(
    opencodeAktif: Boolean,
    contextPct: Int?,
    contextTokens: Int,
    contextWindow: Int,
): Int? = when {
    opencodeAktif -> contextPct?.coerceIn(0, 100)
    contextWindow > 0 -> (contextTokens * 100 / contextWindow).coerceIn(0, 100)
    else -> null
}

/**
 * Uygulama içi ada — turun canlı durumunun TEK yeri.
 *
 * KONUM: durum çubuğunun İÇİNDE, kameranın sağında ve solunda; ortası (kamera
 * bölgesi) boş. Önce `statusBarsPadding()` ile çubuğun ALTINA konmuştu ve
 * kullanıcı haklı olarak saçma buldu: sistem kapsülleri çentiği kuşatır,
 * altına inmez. Uygulama zaten kenardan kenara çiziyor, o alan bizim.
 *
 * Bu ada **uygulama içi** tek ve kalıcı göstergedir; geçici bir vekil değil.
 *
 * DÜZELTME (16.09.2026): buradaki eski gerekçe — "AOSP Live Updates yolu bu
 * cihazda kapsüle çıkmıyor, `isPromotedOngoing: false` kalıyor" — YANLIŞ çıktı.
 * 16.08.2026 ölçümünde ne `POST_PROMOTED_NOTIFICATIONS` izni ne de
 * `setRequestPromotedOngoing(true)` çağrısı vardı; eksik olan cihaz değil,
 * testti. Yeniden ölçüldüğünde (`capsule-test/live/SONUC.md`) `ProgressStyle`
 * ve `BigTextStyle` promoted-ongoing bildirimleri Magic Capsule'u açtı.
 * Sistem kapsülü 16.09.2026'dan beri Live Updates yoluyla AYRICA var
 * (`KapsulDenetleyici`, `docs/kapsul-live-updates-plani.md`) — ada onun yerine
 * geçmiyor, ikisi bir arada. Çakışma yok: Honor, bildirimi atan uygulama
 * öndeyken kapsülü kendisi gizliyor (`SONUC2.md` §4), yani bu ada ekranda
 * iken kapsül zaten çizilmiyor. MediaSession yolu ise ÇALIŞIYOR olmasına
 * rağmen vazgeçildi (medya yuvasını devralıyordu); ayrıntı:
 * `docs/ui3-liquid-glass-plani.md`, Faz 8.
 *
 * İÇERİK BÖLÜŞÜMÜ bantların genişliğinden geliyor, estetikten değil:
 *  - SOL (≈78dp): nokta + durum metni — asıl bilgi, geniş bant onu kaldırıyor.
 *  - SAĞ (≈27dp): yalnız `%bağlam`. Ekolayzer oraya SIĞMIYOR; tur çalışırken
 *    hareketi tıklayan sayaç ve nabız atan nokta zaten veriyor. Ekolayzer
 *    yalnız bağlam yüzdesi yokken (o zaman bant boş kalacaktı) çiziliyor.
 *
 * HAREKET BÜTÇESİ (anayasa v2 bölüm 5): nabız ve ekolayzer yalnız tur çalışırken
 * ya da onay beklerken kurulur; boştayken sabit nokta, sıfır kare.
 */
@Composable
internal fun AppIsland(
    calisiyor: Boolean,
    onayBekliyor: Boolean,
    baglamYuzdesi: Int?,
    /**
     * Turun BAŞLADIĞI an (epoch ms), aktif sekme için. Sayaç bundan türetiliyor:
     * bileşenin kompozisyona girişine bağlanınca sekme değiştirip geri gelince
     * sıfırlanıyordu (kullanıcı bildirdi). Kaynak state'te bu alan olmadığı için
     * kök tutuyor, ada yalnız okuyor.
     */
    turBaslangici: Long?,
    modifier: Modifier = Modifier,
    // Geniş yerleşim (tablet): kesiksiz ekranda hap ortada duruyor ve orası
    // sohbetin sekme çubuğu — sağ üste çekiliyor. Kesikli ekranda (telefon)
    // hapın yeri deliğe göre hesaplanır, bu bayrak oraya karışmaz.
    hizaSaga: Boolean = false,
    /**
     * Adaya dokununca açılacak şey. YALNIZ geniş yerleşimde bağlanıyor —
     * gerekçe [YedekAda]'da: telefonda ada sistem durum çubuğunun İÇİNDE ve o
     * banttaki dokunuşları SystemUI yutuyor, kodla zorlanabilecek bir şey
     * değil. null = ada salt gösterge.
     */
    onTikla: (() -> Unit)? = null,
) {
    val canli = calisiyor || onayBekliyor
    val durumRengi = when {
        onayBekliyor -> ADA_ONAY
        calisiyor -> ADA_CALISIYOR
        else -> ADA_BOSTA
    }
    val metinRengi = when {
        onayBekliyor -> ADA_ONAY
        calisiyor -> ADA_MUREKKEP
        else -> ADA_MUREKKEP2
    }
    // "onay bekliyor" sol banda sığmıyor (13 karakter ≈ 72dp + nokta + dolgu).
    // Kısaltıldı: kehribar nokta + kehribar metin durumu zaten söylüyor, tam
    // cümle onay kartının kendisinde.
    val durumMetni = when {
        onayBekliyor -> "onay"
        calisiyor -> turSuresi(turBaslangici)
        else -> "boşta"
    }

    val kesik = kameraKesigi()
    if (kesik == null) {
        YedekAda(
            durumMetni, durumRengi, metinRengi, canli, calisiyor, baglamYuzdesi,
            hizaSaga, onTikla, modifier,
        )
        return
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // Çubuk, KUTUNUN değil DELİĞİN ortasına hizalanır (bkz. DELIK_MERKEZI).
            .padding(top = kesik.ust + DELIK_MERKEZI - HAP_Y / 2)
            .height(HAP_Y)
            .testTag("ada"),
    ) {
        val solBant = kesik.sol - SAAT_REZERVI
        val sagBant = (maxWidth - IKON_REZERVI) - kesik.sag
        val solVar = solBant >= ASGARI_BANT
        val sagVar = sagBant >= ASGARI_BANT && (baglamYuzdesi != null || calisiyor)
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            // Sol parça: kameranın sol kenarında biter, SOLA doğru büyür.
            Box(
                Modifier.width(kesik.sol.coerceAtLeast(0.dp)).fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (solVar) {
                    Hap(
                        Modifier.widthIn(max = solBant).animateContentSize(spring()),
                        // Dışa bakan uç yuvarlak, kameraya bakan uç DÜZ: orta
                        // parçayla dikişsiz birleşsin.
                        sekil = ucSekli(yuvarlakBas = true, yuvarlakSon = false),
                        // Sol dolgu 9 → 7: çubuk kameraya göre sola yatık
                        // duruyordu (kullanıcı: "biraz sağa"). Sol parça ne
                        // kadar kısalırsa çubuğun ağırlık merkezi o kadar
                        // deliğe yaklaşıyor.
                        solDolgu = 7.dp,
                    ) {
                        DurumNoktasi(durumRengi, canli)
                        Spacer(Modifier.width(5.dp))
                        AdaMetni(
                            durumMetni,
                            metinRengi,
                            if (calisiyor) "ada_sure" else "ada_durum",
                        )
                    }
                }
            }
            // KAMERA BÖLGESİ: içerik yok ama SİYAH. Deliği yutan parça bu —
            // fiziksel delik bu siyahın içinde kalıyor ve çubuk kesintisiz
            // görünüyor. Yanında parça yoksa o uç yuvarlanır ki çubuk kesik
            // kalmasın.
            Box(
                Modifier
                    .width((kesik.sag - kesik.sol).coerceAtLeast(0.dp))
                    .fillMaxHeight()
                    .clip(ucSekli(yuvarlakBas = !solVar, yuvarlakSon = !sagVar))
                    .background(Color.Black),
            )
            // Sağ parça: kameranın sağ kenarından başlar, SAĞA doğru büyür.
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (sagVar) {
                    val sekil = ucSekli(yuvarlakBas = false, yuvarlakSon = true)
                    if (baglamYuzdesi != null) {
                        // YÜZDE İŞARETİ KÜÇÜK PUNTODA, rakam normal puntoda.
                        //
                        // Bir tur çıplak rakam gösterildi: "%" tam puntoda ~12dp
                        // yiyor ve "%100" 34dp'lik bandı taşırıp "%10"a
                        // kırpılıyordu, yani YANLIŞ sayı. Kullanıcı işareti geri
                        // istedi; 7.5sp'lik "%" ≈4.6dp ve en kötü hal ("%100")
                        // 4.6 + 16.2 + 12 dolgu ≈ 33dp — banda sığıyor.
                        //
                        // ASGARİ GENİŞLİK: sağ parça yalnız iki hane kadar dar
                        // kalınca çubuk sağa doğru erken bitiyordu. Bant
                        // elverdiğince genişletiliyor; hane sayısı değişince de
                        // çubuğun boyu zıplamıyor.
                        Hap(
                            Modifier.widthIn(min = minOf(30.dp, sagBant), max = sagBant),
                            sekil,
                            solDolgu = 4.dp,
                            sagDolgu = 6.dp,
                            icerikHizalama = Arrangement.Center,
                        ) {
                            Text(
                                "%",
                                color = ADA_MUREKKEP2,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "$baglamYuzdesi",
                                color = ADA_MUREKKEP2,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.testTag("ada_baglam"),
                            )
                        }
                    } else {
                        Hap(Modifier.widthIn(max = sagBant), sekil, solDolgu = 6.dp, sagDolgu = 8.dp) {
                            Ekolayzer()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Çubuğun bir parçasının şekli: dışa bakan uç yuvarlak, komşuya bakan uç düz.
 *
 * Üç parça (sol içerik · kamera · sağ içerik) yan yana duruyor ve aralarında
 * boşluk yok; komşu kenarları yuvarlatmak birleşme yerinde mercek biçimli
 * açıklıklar bırakırdı.
 */
private fun ucSekli(yuvarlakBas: Boolean, yuvarlakSon: Boolean) = RoundedCornerShape(
    topStartPercent = if (yuvarlakBas) 50 else 0,
    bottomStartPercent = if (yuvarlakBas) 50 else 0,
    topEndPercent = if (yuvarlakSon) 50 else 0,
    bottomEndPercent = if (yuvarlakSon) 50 else 0,
)

/** Siyah kapsül gövdesi. Cam DEĞİL: sistem kapsülü de opak, arkasında bulanıklaştıracak kendi içeriğimiz yok. */
@Composable
private fun Hap(
    modifier: Modifier = Modifier,
    sekil: Shape = Ui3Tokens.pill,
    solDolgu: Dp = 9.dp,
    sagDolgu: Dp = 9.dp,
    icerikHizalama: Arrangement.Horizontal = Arrangement.Start,
    icerik: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxHeight()
            .clip(sekil)
            // Siyah HER İKİ TEMADA da: ada sistem kapsülünü taklit ediyor ve
            // Honor'un kapsülü açık temada da siyah. Paletten okumak yanlış olurdu.
            .background(Color.Black)
            .padding(start = solDolgu, end = sagDolgu),
        horizontalArrangement = icerikHizalama,
        verticalAlignment = Alignment.CenterVertically,
        content = icerik,
    )
}

/** Erişilebilir dokunma hedefi asgarisi (Material). Ada 28dp, aradaki pay uydurulur. */
private val DOKUNMA_HEDEFI = 48.dp

/**
 * Çentiksiz ekranın yedeği: durum çubuğunun ortasında tek hap.
 *
 * Tablette (Tab S10+) çentik yok; iki bant kurgusunun anlamı da yok. Burada
 * bant darlığı da yok, o yüzden içerik tam: nokta + metin + yüzde + ekolayzer.
 *
 * DOKUNULABİLİRLİK YALNIZ GENİŞ YERLEŞİMDE ([hizaSaga]). Ölçülmüş sebep: dar
 * ekranda ada — çentikli ya da çentiksiz — sistem DURUM ÇUBUĞUNUN İÇİNDE
 * duruyor (üstünde `statusBarsPadding` yok, bkz. dosya başındaki ölçüm notu) ve
 * o banttaki dokunuşları SystemUI kendi penceresinde yutuyor; uygulama oraya
 * dinleyici koyarak dokunuş alamaz. Geniş yerleşimde ada çubuğun ALTINA
 * indiği için (çağıran `statusBarsPadding` veriyor) dokunuş bize geliyor.
 * Yani buradaki `hizaSaga` koşulu bir tercih değil, fiziksel sınır.
 */
@Composable
private fun YedekAda(
    durumMetni: String,
    durumRengi: Color,
    metinRengi: Color,
    canli: Boolean,
    calisiyor: Boolean,
    baglamYuzdesi: Int?,
    hizaSaga: Boolean,
    onTikla: (() -> Unit)?,
    modifier: Modifier,
) {
    val yogunluk = LocalDensity.current
    val durumY = with(yogunluk) { WindowInsets.statusBars.getTop(yogunluk).toDp() }
    // Kesiksiz telefonda hap durum ÇUBUĞUNUN İÇİNE ortalanır. Geniş yerleşimde
    // bu yanlış: tablette durum çubuğunun sağında sistem ikonları (pil, wifi)
    // var ve hap onların üstüne biniyordu (cihazda görüldü, 18.08). Orada hap
    // çubuğun ALTINA, sekme çubuğu satırının boş sağ ucuna geçiyor — üst dolgu
    // çağıran taraftan (statusBarsPadding) geliyor, burada yalnız nefes payı.
    val ustDolgu = if (hizaSaga) 4.dp else ((durumY - YEDEK_HAP_Y) / 2).coerceAtLeast(2.dp)
    val tiklanabilir = hizaSaga && onTikla != null
    // Dokunma hedefi 28 → 48dp, eksik pay AŞAĞI doğru: yukarısı durum çubuğu,
    // oraya büyütmek dokunuşu yine sisteme kaptırırdı. Alan GÖRÜNMEZ; kap kutu
    // da o kadar uzuyor ki dokunuş hedefin dışına düşüp yutulmasın.
    val kapY = if (tiklanabilir) DOKUNMA_HEDEFI else YEDEK_HAP_Y
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = ustDolgu, end = if (hizaSaga) 16.dp else 0.dp)
            .height(kapY)
            .testTag("ada"),
        // Kesiksiz ekranda hap ORTADA durur; geniş yerleşimde (tablet) orası
        // sohbetin sekme çubuğu — hap sekme çipinin üstüne biniyordu (cihazda
        // görüldü, 18.08). Geniş ekranda sağ üste çekiliyor. Dikeyde ÜSTE
        // hizalı: kap kutu dokunma payı yüzünden hapten uzun olabiliyor ve
        // ortalamak hapı aşağı kaydırırdı.
        contentAlignment = if (hizaSaga) Alignment.TopEnd else Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .height(kapY)
                .then(
                    if (tiklanabilir) {
                        Modifier.clickable(
                            // Dalga YOK: ada sistem kapsülünü taklit ediyor,
                            // 48dp'lik bir dalga hapin dışına taşardı.
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Bağlam ayrıntısı",
                            onClick = onTikla!!,
                        )
                    } else {
                        Modifier
                    },
                )
                .testTag("ada_dokunma"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.height(YEDEK_HAP_Y)) {
                Hap(Modifier.animateContentSize(spring())) {
                    DurumNoktasi(durumRengi, canli)
                    Spacer(Modifier.width(7.dp))
                    AdaMetni(durumMetni, metinRengi, if (calisiyor) "ada_sure" else "ada_durum")
                    if (baglamYuzdesi != null) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            // Yedek yerleşimde bant darlığı yok: yüzde işareti kalabilir.
                            "%$baglamYuzdesi",
                            color = ADA_MUREKKEP2,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("ada_baglam"),
                        )
                    }
                    if (calisiyor) {
                        Spacer(Modifier.width(7.dp))
                        Ekolayzer()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- çentik

/** Ön kameranın ekrandaki yeri, dp cinsinden. */
private data class Kesik(val sol: Dp, val sag: Dp, val ust: Dp, val alt: Dp)

/**
 * Kamera kesiğini pencere insets'inden okur.
 *
 * `WindowInsets.displayCutout` yalnız KENAR boşluğunu verir (üstte 40.3dp) —
 * ortadaki deliğin yatay sınırlarını değil. Yatay sınırlar yalnız platformun
 * `DisplayCutout.boundingRectTop`unda var, o yüzden Compose insets'i yerine
 * doğrudan görünümün kök insets'i okunuyor.
 *
 * İlk kompozisyonda insets henüz bağlı olmayabiliyor; birkaç kare denenip
 * bulunamazsa `null` dönülüyor ve çağıran yedek yerleşime düşüyor.
 */
@Composable
private fun kameraKesigi(): Kesik? {
    val view = LocalView.current
    val yogunluk = LocalDensity.current
    var kesik by remember(view) { mutableStateOf<Kesik?>(null) }
    LaunchedEffect(view) {
        repeat(10) {
            // `boundingRects` kenar başına bir dikdörtgen döndürür ve kullanılmayan
            // kenarlar (0,0,0,0) gelir. Üst kenardakini seçiyoruz: top == 0 olan
            // ve en geniş olan. `boundingRectTop` bu androidx sürümünde yok.
            val r = ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects
                ?.filter { it.width() > 0 && it.height() > 0 && it.top == 0 }
                ?.maxByOrNull { it.width() }
            if (r != null) {
                kesik = with(yogunluk) {
                    Kesik(r.left.toDp(), r.right.toDp(), r.top.toDp(), r.bottom.toDp())
                }
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }
    return kesik
}

// ---------------------------------------------------------------- parçalar

@Composable
private fun AdaMetni(metin: String, renk: Color, etiket: String) {
    Text(
        metin,
        color = renk,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier.testTag(etiket),
    )
}

/**
 * Tur süresi — turun BAŞLANGIÇ ANINDAN itibaren geçen zaman.
 *
 * Eskiden sayaç bileşenin kompozisyona girişiyle sıfırlanıyordu; sekme
 * değiştirip geri gelince tur sürerken sayaç baştan başlıyordu. Artık başlangıç
 * anı dışarıdan geliyor ve sayaç yalnız EKRANI tazeliyor — saniye başı bir
 * `delay`, motor katmanına alan eklemeden doğru mertebe.
 *
 * [baslangic] null ise (henüz kaydedilmemişse) 0:00 gösterilir; bir sonraki
 * karede gerçek değer gelir.
 */
@Composable
private fun turSuresi(baslangic: Long?): String {
    var simdi by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(baslangic) {
        while (true) {
            simdi = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val saniye = if (baslangic == null) 0L else ((simdi - baslangic).coerceAtLeast(0L)) / 1000
    return "%d:%02d".format(saniye / 60, saniye % 60)
}

@Composable
private fun DurumNoktasi(renk: Color, canli: Boolean) {
    // Canlı değilken animasyon YOK: sabit nokta, sıfır kare (hareket bütçesi).
    if (!canli) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(renk))
        return
    }
    val gecis = rememberInfiniteTransition(label = "ada-nabiz")
    val opaklik by gecis.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ada-nabiz-opaklik",
    )
    Box(Modifier.size(7.dp).alpha(opaklik).clip(CircleShape).background(renk))
}

/** Üç çubuk, farklı gecikmelerle — "bir şey akıyor" hissinin tamamı bu. */
@Composable
private fun Ekolayzer() {
    val gecis = rememberInfiniteTransition(label = "ekolayzer")
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(11.dp),
    ) {
        listOf(6.dp to 0, 11.dp to 180, 8.dp to 360).forEach { (yukseklik, gecikme) ->
            val olcek by gecis.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500, delayMillis = gecikme),
                    RepeatMode.Reverse,
                ),
                label = "cubuk",
            )
            Box(
                Modifier
                    .width(2.5.dp)
                    .height(yukseklik * olcek)
                    .clip(Ui3Tokens.pill)
                    .background(ADA_CALISIYOR),
            )
        }
    }
}
