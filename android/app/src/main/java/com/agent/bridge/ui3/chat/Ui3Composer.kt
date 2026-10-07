package com.agent.bridge.ui3.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.QueuedPrompt
import com.agent.bridge.ui2.chat.queuedPromptPreview
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.material.concentric
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.layout.Spacer

// Kabuk artık daha KÖŞELİ (kullanıcı geri bildirimi: "çok oval"). Hap
// (999dp) yerine yarıçap ailesinden değerler: composer 26, iç alan
// konşentrik kuralıyla 26−6 = 20, şerit düğmeleri 12.
private val DIS_YARICAP = Ui3Tokens.r26
private val IC_DOLGU = 6.dp
private val DUGME_YARICAP_DP = 12.dp
private val DUGME_YARICAP = RoundedCornerShape(DUGME_YARICAP_DP)
// Gönder/durdur artık şerit düğmeleriyle aynı boyda, yarıçapı da aynı olsun.
private val GONDER_YARICAP = DUGME_YARICAP

// ŞERİT GEOMETRİSİ — cihazda ölçüldü ve bir kez YANLIŞ yorumlandı.
//
// uiautomator dökümü her düğmeyi 168px (48dp) gösteriyordu ve "yerleşim 48dp
// yiyor, şerit dolu" sonucuna varılmıştı. Yanlıştı: o sayı Compose'un
// ERİŞİLEBİLİRLİK için genişlettiği dokunma sınırı, yerleşim ölçüsü değil.
// Aritmetik bunu kesinleştirdi (Magic7 Pro, yoğunluk 3.5):
//
//   composer iç genişlik                     1112 px
//   4 × SERIT_DUGME (36dp = 126px)            504
//   GONDER (36dp = 126px, eskiden 40)         126
//   5 × IC_DOLGU (6dp = 21px)                 105
//   ------------------------------------------------
//   sabit yük                                 735  → çipe kalan 377 px
//
// Ölçülen çip genişliği (gönder 40dp'yken) TAM 363 px çıktı. Yani düğmeler yerleşimde zaten
// 36dp; şeritte hiçbir zaman yer sıkıntısı olmamış. Bu yüzden asgari dokunma
// hedefi büyütmesini kapatmaya da gerek yok — hedefler 48dp kalıyor.
//
// MODEL_MAKS = 119dp: çipe ayrılabilecek üst sınır. Çip İÇERİĞİ KADAR geniş
// olur (bir ara `weight(1f)` verilmişti ve boşluğun tamamını yutup içi boş
// kocaman bir hap gibi duruyordu), artan yeri Spacer alır.
private val SERIT_DUGME = 36.dp
// GÖNDER de 36 (kullanıcı isteği 17.08): 40dp'ydi ve "birincil eylem büyük
// olsun" gerekçesiyle bilinçliydi, ama tek başına duran bir tuşta 4dp fark
// vurgu değil hizasızlık gibi okunuyordu. Gönderi ayıran şey artık yalnız
// gradyan dolgusu — boyut değil. Şeritte 4dp daha yer açıldı, çipe gidiyor.
private val GONDER = 36.dp
private val MODEL_MAKS = 119.dp
// ÇİPİN OKUNABİLİR ASGARİSİ — uydurulmadı, çipin KENDİ ölçülerinden çıktı:
// yatay dolgu 2×11dp + metin/ok arası 5dp + "▾" glifi ≈ 9dp = 36dp'lik kabuk,
// üstüne üç karakterlik etiket payı (~24dp). Bunun altında çip fiilen yalnız
// bir "▾" oluyor; o hâlde şeritten bir düğme düşürüp yeri çipe vermek daha
// doğru. Kullanıldığı yer: tur sürerken Yenile'nin kalıp kalmayacağı kararı.
private val CIP_ASGARI = 60.dp

// Metin alanının üst sınırı: yaklaşık 5 satır. Daha uzun metin alanın İÇİNDE
// kaydırılır; şerit her zaman ekranda kalır.
private val METIN_MAKS_Y = 132.dp

/** Şeritte bu turda ne çizilecek + model çipinin tavanı. */
internal data class Ui3SeritYerlesimi(
    val yenile: Boolean,
    val kullanim: Boolean,
    val klasor: Boolean,
    val cipMaks: Dp,
)

/**
 * Şerit yerleşimi — ÖLÇÜLEREK, varsayılmadan.
 *
 * Bileşenin içinde yerel fonksiyonlardı; saf hâle çıkarıldı ki sayılar teste
 * bağlanabilsin (bkz. `Ui3SeritYerlesimiTest`). Kural: önce Kullanım düşer,
 * sonra Klasör, en son Yenile — 18.08.2026'daki karar (tur ortasında tazelemek
 * sık istenir, limitlere bakmak değil), artık her turda genişlikten hesaplanıyor.
 *
 * 22.08.2026'da şeritten İKİ sabit düğme çıktı: "＋" yazı kutusunun içine indi,
 * "kuyruğa ekle" ⋯ menüsüne. Kalan sabitler yalnız ayar sürgüsü ve
 * gönder/durdur; yönlendir yalnız OMP'de ve metin yazılmışken.
 *
 * 25.08.2026: ÇALIŞMA KLASÖRÜ şeride çıktı ([klasorVar]) — ⋯
 * menüsünde saklanıyordu ve kullanıcı sık erişmek istiyor. Telefonun boştaki
 * şeridi onu taşıyor (çip 143.7 → 101.7dp'ye iner, asgarinin üstünde); darlıkta
 * Kullanım'dan sonra, Yenile'den önce düşer — Yenile'nin tur ortasında en son
 * düşme garantisi (18.08) bozulmadı.
 */
internal fun ui3SeritYerlesimi(
    genislik: Dp,
    yonlendirVar: Boolean,
    seritTam: Boolean,
    klasorVar: Boolean = false,
    kullanimVar: Boolean = true,
): Ui3SeritYerlesimi {
    fun cip(yenile: Boolean, kullanim: Boolean, klasor: Boolean): Dp {
        val adet = 2 +
            (if (yenile) 1 else 0) +
            (if (kullanim) 1 else 0) +
            (if (klasor) 1 else 0) +
            (if (yonlendirVar) 1 else 0)
        // Aralık sayısı = öğe sayısı - 1; öğeler = sabitler + çip + Spacer.
        // Spacer sıfır genişlikte ama YANINDA da boşluk üretiyor, sayıma dahil
        // olmazsa hesap 6dp iyimser çıkar (eski aritmetiğin hatası).
        return (genislik - SERIT_DUGME * adet - IC_DOLGU * (adet + 1))
            .coerceIn(0.dp, MODEL_MAKS)
    }
    // Geniş ekranda yarış yok: hepsi kalır (kullanıcı 18.08: "tablette
    // yapmaya gerek yok, yer bolca var").
    if (seritTam) return Ui3SeritYerlesimi(true, kullanimVar, klasorVar, cip(true, kullanimVar, klasorVar))
    val hepsi = cip(true, kullanimVar, klasorVar)
    if (hepsi >= CIP_ASGARI) return Ui3SeritYerlesimi(true, kullanimVar, klasorVar, hepsi)
    if (kullanimVar) {
        val kullanimsiz = cip(true, false, klasorVar)
        if (kullanimsiz >= CIP_ASGARI) return Ui3SeritYerlesimi(true, false, klasorVar, kullanimsiz)
    }
    if (klasorVar) {
        val yalnizYenile = cip(true, false, false)
        if (yalnizYenile >= CIP_ASGARI) return Ui3SeritYerlesimi(true, false, false, yalnizYenile)
    }
    return Ui3SeritYerlesimi(false, false, false, cip(false, false, false))
}

/**
 * Yüzen cam ada — composer (anayasa v2 §1.2: sabit kabuk, gerçek cam).
 *
 * İKİ KATLI (kullanıcı kararı): üstte yalnız metin alanı, altında turun
 * ayarlarını ve kısayollarını taşıyan ince şerit.
 *
 *   ＋ | model ▾ | ⋯ | yenile | kullanım | ····· | gönder
 *
 * Neden burada: ui2'de bu ayarlar sohbet akışının ÜSTÜNE binen bir çip satırıydı
 * (model, ajan, effort, izin, Skill, hesap, sağlayıcı — yedi çip) ve ui3'e hiç
 * taşınmamıştı, yani ui3'te model bile değiştirilemiyordu. Akışın üstüne
 * bindirmek yerine composer'a indirildi: ayar, turu başlattığın yerde durur ve
 * okuma alanı hiç daralmaz.
 *
 * Şeritte GÖRÜNÜR kalan tek ayar **model**; ajan, effort, izin modu, Skill,
 * hesap ve sağlayıcı [onMenu] ile açılan sheet'te. Gizlenenler içinde yanlış
 * değerde kalınca zarar verebilecek tek şey izin modu olduğu için, gevşekken
 * ⋯ tuşunun üstünde kehribar nokta yanar ([izinGevsek]).
 *
 * Konşentrik yarıçap kuralı: dış kapsül 26, iç dolgu 6, iç alan
 * `concentric(26, 6)` = 20. Değer elle yazılmaz, util'den gelir.
 *
 * Tur sürerken composer KİLİTLENMEZ: kullanıcı yazmaya devam edebilir, gönder
 * tuşunun yerini cyan durdur halkası alır. Metin yazılmışsa durdurun soluna
 * "kuyruğa ekle" diski, destekleyen sağlayıcıda bir de "yönlendir" şimşeği
 * gelir ([onYonlendir]).
 *
 * ŞERİT YERİ. Tur sürerken şimşek + kuyruk + durdur birden çıkıyor ve yedi
 * düğme bir satıra sığmıyor. Önce bu yüzden **Yenile ve Kullanım birlikte**
 * gizleniyordu; 18.08.2026'da kullanıcı ikisini ayırdı: "kalan kullanım tuşunu
 * gizleyip 3 dot menüye alsak, sadece yenile kalsa alan yetecek gibi". Doğru —
 * tur sürerken tazelemek sık istenen bir şey, limitlere bakmak değil. Artık
 * **yalnız Kullanım** gizleniyor, Yenile şeritte kalıyor; Kullanım tur boyunca
 * ⋯ menüsünde satır olarak yaşıyor (bkz. Ui3TurAyarlariMenusu).
 *
 * YER HESABI ARTIK ÖLÇÜLÜYOR, VARSAYILMIYOR. Önceki sürüm tek bir cihazda
 * (Magic7 Pro, 317.7dp iç genişlik) elle yapılmış bir aritmetiğe dayanıyordu
 * ve çipin tavanı sabitti — dar bir cihazda ya da uzun model adında şerit
 * taşardı, çünkü çip kendi tavanını alıp sonraki düğmeleri dışarı itiyordu.
 * Şimdi `BoxWithConstraints` gerçek genişliği veriyor, çipin tavanı o turda
 * GÖRÜNEN düğmelerden geriye kalana göre hesaplanıyor. Kalan [CIP_ASGARI]'nin
 * altına düşerse — pratikte yalnız "tur sürüyor + metin yazılmış + yönlendirme
 * destekli sağlayıcı" üçlüsünde — Yenile de düşer ve yerini çipe bırakır.
 *
 * BU DARLIK TELEFONA ÖZGÜ. Tablette şeritte 600dp'den fazla boş yer var ve
 * gizleme sebepsiz bir kayıp oluyordu (kullanıcı 18.08: "tablette yapmaya
 * gerek yok, yer bolca var") — [seritTam] açıkken ikisi tur sürerken de
 * kalır. Eşiğin kendisi ve neden EKRAN genişliğinden okunduğu:
 * `UI3_SERIT_TAM_ESIK`.
 */
@Composable
internal fun Ui3Composer(
    hazeState: HazeState,
    metin: String,
    onMetin: (String) -> Unit,
    calisiyor: Boolean,
    gonderilebilir: Boolean,
    onGonder: () -> Unit,
    onDurdur: () -> Unit,
    modelEtiketi: String,
    // Seçili model RunPod'daki RunPod ise pod'un hâli; null = başka model ya da
    // başka backend, nokta hiç çizilmez.
    runpodIsigi: Ui3RunPodIsigi?,
    onModel: () -> Unit,
    izinGevsek: Boolean,
    onMenu: () -> Unit,
    yenileniyor: Boolean,
    onYenile: () -> Unit,
    onKullanim: () -> Unit,
    kullanimGoster: Boolean = true,
    onEk: () -> Unit,
    // Oturumun çalışma klasörü (cowork'te çalışma alanı, diğerlerinde proje
    // cwd'si) — dosya yöneticisini son gezilen alt klasörde açar. null = cwd
    // henüz yok → tuş hiç çizilmez ve yerleşim hesabına da girmez. Şeride
    // çıkma gerekçesi `ui3SeritYerlesimi`de.
    onKlasor: (() -> Unit)? = null,
    // Lite'ta çalışma klasörünün şeritteki yerini sohbet araması alır. İkisi
    // aynı anda verilmez; aynı yerleşim yuvasını ve aynı 36dp ikon ölçüsünü
    // paylaşırlar. Tam sürümde null kalır.
    onAra: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // Şeride hepsi sığıyor mu (geniş ekran). Varsayılan false = telefon yolu:
    // eski davranışın birebir aynısı.
    seritTam: Boolean = false,
    // Tur ortası canlı gönderim — yalnız OMP'nin native steer/follow_up ucu var.
    // null ise tuş hiç çizilmez ve yalnız istemci kuyruğu kalır (eski davranış).
    // Kuyruk ve "ajana bırak" ⋯ menüsüne taşındı, o yüzden burada yalnız
    // yönlendirme (turu KESEN yol) kaldı.
    onYonlendir: (() -> Unit)? = null,
) {
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth().testTag("composer"),
        shape = RoundedCornerShape(DIS_YARICAP),
    ) {
        Column(
            Modifier.padding(IC_DOLGU),
            verticalArrangement = Arrangement.spacedBy(IC_DOLGU),
        ) {
            val icYaricap = RoundedCornerShape(concentric(DIS_YARICAP, IC_DOLGU))
            Box(
                Modifier
                    .fillMaxWidth()
                    // ÜST SINIR ŞART: alan yalnız `min` ile sınırlıydı ve uzun
                    // metinde sınırsız büyüyüp ŞERİDİ (model çipi, gönder tuşu)
                    // ekranın dışına itiyordu — klavye açıkken de kapalıyken de
                    // gönder tuşu kayboluyordu (kullanıcı iki ekran görüntüsüyle
                    // bildirdi). Sınıra gelince alan kendi içinde kaydırılır.
                    //
                    // Asgari 42 → 44dp: içeri giren "＋" 36dp ve iki yanında
                    // 4dp pay istiyor.
                    .heightIn(min = 44.dp, max = METIN_MAKS_Y)
                    .clip(icYaricap)
                    .background(Ui3Colors.kuyu)
                    .border(1.dp, Ui3Colors.cizgiInce, icYaricap)
                    .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            ) {
            // "＋" ŞERİTTEN BURAYA İNDİ (22.08.2026, kullanıcı önerisi).
            // Şeritte sabit bir 36dp yer kaplıyordu ve tur sürerken Yenile ile
            // Kullanım tam o yer yüzünden düşüyordu. Ek eklemek zaten YAZI
            // eylemidir, yeri de yazı kutusu.
            //
            // ALTA SABİT: kutu büyüdükçe tuş aşağıda, şeride en yakın yerde
            // kalır (kullanıcı kararı) — başparmak onu hep aynı yükseklikte
            // bulur. Tek satırda `Bottom` hizası zaten ortalanmış görünüyor:
            // içerik yüksekliği tuşun 36dp'si, metin 20dp + 8dp alt dolgu = 28,
            // yani altta da üstte de 8dp kalıyor.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                EkTusu(onEk)
                // HİZA ELLE AYARLANMAZ (22.08.2026, kullanıcı bildirdi:
                // "aynı hizada değiller"). Önce metne 8dp alt dolgu verilip
                // tuşun 36dp'siyle denk gelmesi UMULMUŞTU; satır yüksekliği
                // yazı tipinden geldiği için ~4dp kayıyordu. Şimdi metin kutusu
                // TUŞLA AYNI asgari yüksekliği alıyor ve içeriğini kendi
                // ortalıyor: tek satırda iki merkez matematiksel olarak aynı
                // yerde, yazı tipi değişse de bozulmaz. Metin büyüyünce kutu
                // uzar, tuş altta kalır — istenen davranış zaten bu.
                Box(
                    Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .defaultMinSize(minHeight = SERIT_DUGME),
                    contentAlignment = Alignment.CenterStart,
                ) {
                // YAZI ALANI AKIŞTAN 1 PUNTO KÜÇÜK: okunan metin 16sp, yazılan
                // 15sp (kullanıcı 18.08: 13sp "çok küçük kalmış"). Yer tutucu
                // da aynı boydan gider — ikisi ayrı olursa yazmaya başlayınca
                // satır zıplıyor. Kutunun asgari boyu aşağıdaki heightIn'den
                // geliyor ve 15sp tek satır oraya sığıyor (42dp).
                val alanStili = Ui3Type.yazi
                if (metin.isEmpty()) {
                    Text("Mesaj yaz —  /  komutlar", style = alanStili, color = Ui3Colors.ink3)
                }
                // DIŞ SÖZLEŞME String, İÇERİDE TextFieldValue.
                //
                // String sürümünde metin DIŞTAN değişince (slash komutu seçmek,
                // gönderimden sonra temizlemek) seçim taşınmıyordu: "/com" yazıp
                // öneriden "/compact" seçince imleç "com"un m'sinde kalıyor ve
                // devamı oraya yazılıyordu (kullanıcı bildirdi). ui2 aynı sorunu
                // aynı yolla çözmüş (`ui2/chat/Composer.kt`): dış değişimde
                // imleci metnin SONUNA koy.
                var alanDegeri by remember { mutableStateOf(TextFieldValue(metin)) }
                // Son metin yerleşimi: yukarı/aşağı okun hangi satıra gideceği
                // yalnız buradan bilinebiliyor (bkz. `ui3OkTuslari`).
                var yazYerlesimi by remember { mutableStateOf<TextLayoutResult?>(null) }
                if (alanDegeri.text != metin) {
                    alanDegeri = TextFieldValue(metin, TextRange(metin.length))
                }
                BasicTextField(
                    value = alanDegeri,
                    onValueChange = {
                        alanDegeri = it
                        if (it.text != metin) onMetin(it.text)
                    },
                    // YAZARKEN de ui3'ün yüzü (Mackinac). Yer tutucu zaten
                    // `Ui3Type.govde` üzerinden serifti, yazılan metin sistem
                    // sans'ında kalıyordu ve harf harf değişiyordu — kullanıcı
                    // fark etti. Aynı stilden türetmek ikisini kilitliyor.
                    textStyle = LocalTextStyle.current.merge(
                        alanStili.copy(color = Ui3Colors.ink),
                    ),
                    cursorBrush = SolidColor(Ui3Colors.vurguHi),
                    // DIŞTAN verticalScroll VERİLMEZ: BasicTextField'in kendi
                    // kaydırması var ve imleci görünür tutuyor. Dıştan scroll
                    // eklenince o devre dışı kalıyor ve yazdığın son satır
                    // görünmüyordu (kullanıcı bildirdi). Kutunun yüksekliği
                    // sınırlı olduğu sürece alan kendi içinde kayar.
                    onTextLayout = { yazYerlesimi = it },
                    // Fiziksel klavye ok tuşları: gerekçe `ui3OkTuslari`da.
                    modifier = Modifier
                        .fillMaxWidth()
                        .ui3OkTuslari({ alanDegeri }, { alanDegeri = it }, { yazYerlesimi })
                        .testTag("composer_alan"),
                )
                }
            }
            }

            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            // Bu turda hangi düğmeler çizilecek — hesap `ui3SeritYerlesimi`de,
            // saf ve teste bağlı.
            val yonlendirVar = calisiyor && gonderilebilir && onYonlendir != null
            val yerlesim = ui3SeritYerlesimi(
                maxWidth,
                yonlendirVar,
                seritTam,
                onKlasor != null || onAra != null,
                kullanimVar = kullanimGoster,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IC_DOLGU),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "＋" ARTIK BURADA DEĞİL — yazı kutusunun içinde (yukarı bak).
                // Çip İÇERİĞİ KADAR geniş, en fazla MODEL_MAKS. Önceki sürümde
                // `weight(1f)` ile bütün boşluğu yutuyordu ve içi boş kocaman
                // bir hap gibi duruyordu. Artan boşluğu aşağıdaki Spacer alır.
                ModelCipi(
                    etiket = modelEtiketi,
                    runpodIsigi = runpodIsigi,
                    onTikla = onModel,
                    maks = yerlesim.cipMaks,
                )
                SeritDugmesi(
                    // ⋯ yerine ayar sürgüsü: üç nokta "başka bir şey daha var"
                    // der, bu tuş ise turun AYARLARINI açıyor. Simge işi
                    // söylesin (kullanıcı geri bildirimi).
                    ikon = Icons.Filled.Tune,
                    aciklama = "Tur ayarları",
                    testEtiketi = "composer_menu",
                    onTikla = onMenu,
                    uyari = izinGevsek,
                )
                // Yenile tur sürerken de kalır (kullanıcı kararı 18.08) —
                // yalnız çipi okunmaz hâle getirecekse düşer.
                if (yerlesim.yenile) {
                    SeritDugmesi(
                        ikon = Icons.Filled.Refresh,
                        aciklama = if (yenileniyor) "Yenileniyor" else "Yenile",
                        testEtiketi = "composer_yenile",
                        // Sürerken kapalı: ui2'de de öyle, çift tetikleme soketi
                        // gereksiz yere iki kez tazeliyordu.
                        onTikla = onYenile,
                        etkin = !yenileniyor,
                        mesgul = yenileniyor,
                    )
                }
                // Kullanım artık tur sürerken de kalıyor: "＋" ve kuyruk
                // şeritten çıkınca yer açıldı (22.08.2026). Yalnız gerçekten
                // sığmadığı durumda düşer ve ⋯ menüsünde yaşamayı sürdürür.
                if (yerlesim.kullanim) {
                    SeritDugmesi(Icons.Filled.DataUsage, "Kullanım", "composer_kullanim", onKullanim)
                }
                // Lite'ta bu yuva sohbet aramasıdır; çalışma klasörü ⋯ menüsüne
                // iner. Tam sürümde eski hızlı klasör erişimi aynen korunur.
                if (yerlesim.klasor) {
                    when {
                        onAra != null -> SeritDugmesi(
                            Icons.Filled.Search,
                            "Sohbette ara",
                            "composer_ara",
                            onAra,
                        )
                        onKlasor != null -> SeritDugmesi(
                            Icons.Filled.FolderOpen,
                            "Çalışma klasörü",
                            "composer_klasor",
                            onKlasor,
                        )
                    }
                }

                // Tek ağırlıklı öğe: artan boşluğu bu yutar, gönder sağ kenarda.
                Spacer(Modifier.weight(1f))

                if (calisiyor) {
                    // YÖNLENDİR (steer): süren turu KESER, model o an yaptığını
                    // bırakıp yeni yönergeye uyar. Yalnız destekleyen sağlayıcıda
                    // ve yalnız yazılmış metin varken görünür.
                    if (gonderilebilir && onYonlendir != null) {
                        Box(
                            Modifier
                                .size(SERIT_DUGME)
                                .clip(DUGME_YARICAP)
                                .background(Ui3Colors.vurguHi.copy(alpha = 0.14f))
                                .clickable(onClick = onYonlendir)
                                .testTag("composer_yonlendir"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = "Yönlendir (turu kes)",
                                tint = Ui3Colors.vurguHi,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    // KUYRUĞA EKLE ARTIK ŞERİTTE DEĞİL — ⋯ menüsünde iki ayrı
                    // satır (22.08.2026, kullanıcı kararı). Sebebi yer: tur
                    // sürerken şimşek + kuyruk + durdur aynı anda çıkınca Yenile
                    // ve Kullanım'a sıra kalmıyordu ve tazelemek, "sonra gönder"
                    // demekten daha sık isteniyor. Bedeli iki dokunuş; menüde
                    // uzun basış yerine ayrı satır olduğu için native follow_up
                    // da artık keşfedilebilir (eskiden gizli bir jestti).
                    DurdurTusu(onDurdur)
                } else {
                    Box(
                        Modifier
                            .size(GONDER)
                            .clip(GONDER_YARICAP)
                            .background(
                                if (gonderilebilir) Ui3Colors.grad
                                else SolidColor(Ui3Colors.yuzey2)
                            )
                            .clickable(enabled = gonderilebilir, onClick = onGonder)
                            .testTag("composer_gonder"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = "Gönder",
                            // Gradyan disk iki temada da vurgu→petrol; ikon beyaz kalır.
                            tint = if (gonderilebilir) Color.White else Ui3Colors.ink3,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * ÇERÇEVEDE DOLAŞAN IŞIK — "bu tuş şu an bir işin ortasında" demenin ui3'çe
 * söylenişi.
 *
 * Uydurulmuş bir efekt değil: mesaj rayındaki akan ışıkla (`RayliSatir`) aynı
 * renk ailesi, aynı tempo (1500ms, doğrusal, baştan başlar) ve aynı fikir.
 * İkisi aynı anda ekranda olabildiği için tempoyu paylaşmaları önemliydi —
 * farklı periyot iki ayrı saat gibi çarpışırdı.
 *
 * Neden yay değil YOL: tuşlar daire değil, [DUGME_YARICAP_DP] yarıçaplı
 * yuvarlatılmış kare; `drawArc` bu kenara oturmuyor. Kenarın kendisi `Path`
 * olarak kuruluyor ve `PathMeasure` ile bir parçası kesiliyor. Parça kuyruk
 * gibi sönsün diye tek çizgi değil, opaklığı artan altı dilim: baş parlak,
 * kuyruk sönük.
 *
 * [aktif] false iken `rememberInfiniteTransition` HİÇ kurulmuyor, yani kare
 * üretimi de yok. Anayasa v2 §5: sürekli animasyona hakkı olan tek şey devam
 * eden bir iştir; boştaki bir tuşta dönen ışık gürültüdür.
 */
@Composable
private fun Modifier.dolasanIsik(aktif: Boolean, renk: Color, kalinlik: Dp): Modifier {
    if (!aktif) return this
    // KUYRUĞUN OPAKLIK ARALIĞI TEMAYA GÖRE (20.08.2026, kullanıcı bildirdi:
    // "aydınlık modda kendini hiç belli etmiyor").
    //
    // Koyu zeminde bu gerçekten bir IŞIK: çevresinden PARLAK olduğu için göz
    // onu anında yakalıyor, 0.90 opaklık fazlasıyla yetiyor. Açık zeminde
    // "beyazdan parlak" diye bir şey yok — aynı etkiyi ancak KOYULUK farkı
    // verebilir ve o da tam opaklık ister. Yarı saydam bir koyu ton, altındaki
    // açık zeminle karışıp fısıltıya dönüyordu.
    val koyuTema = Ui3Colors.palet.koyuMu
    val tabanOpaklik = if (koyuTema) 0.14f else 0.08f
    val tavanOpaklik = if (koyuTema) 0.90f else 1f
    val gecis = rememberInfiniteTransition(label = "dolasan-isik")
    val ilerleme by gecis.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "dolasan-isik-ilerleme",
    )
    // Path/PathMeasure her karede yeniden kurulmasın: çizim sıcak yolda.
    val yol = remember { Path() }
    val olcer = remember { PathMeasure() }
    val parca = remember { Path() }
    return drawBehind {
        val kalinlikPx = kalinlik.toPx()
        val yaricap = DUGME_YARICAP_DP.toPx()
        yol.reset()
        // Çerçeve kalınlığının YARISI kadar içeri: çizgi kenarın ortasına
        // otursun, dışarı taşıp kırpılmasın.
        yol.addRoundRect(
            RoundRect(
                Rect(
                    kalinlikPx / 2f,
                    kalinlikPx / 2f,
                    size.width - kalinlikPx / 2f,
                    size.height - kalinlikPx / 2f,
                ),
                CornerRadius(yaricap - kalinlikPx / 2f),
            ),
        )
        olcer.setPath(yol, true)
        val boy = olcer.length
        if (boy <= 0f) return@drawBehind
        // Kuyruk çevrenin dörtte biri: daha uzunu "yürüyen ışık" değil "dönen
        // çerçeve" gibi okunuyordu.
        val kuyruk = boy * 0.25f
        val bas = ilerleme * boy
        val dilim = 6
        for (i in 0 until dilim) {
            val opaklik = tabanOpaklik + (tavanOpaklik - tabanOpaklik) * ((i + 1f) / dilim)
            val basla = bas + kuyruk * (i / dilim.toFloat())
            val bitir = bas + kuyruk * ((i + 1f) / dilim)
            parca.reset()
            // Çevre başa sarabilir; `PathMeasure` sarmayı kendisi yapmadığı
            // için parça iki kesitte alınır.
            if (bitir <= boy) {
                olcer.getSegment(basla, bitir, parca, true)
            } else if (basla >= boy) {
                olcer.getSegment(basla - boy, bitir - boy, parca, true)
            } else {
                olcer.getSegment(basla, boy, parca, true)
                olcer.getSegment(0f, bitir - boy, parca, true)
            }
            drawPath(
                parca,
                color = renk.copy(alpha = opaklik),
                style = Stroke(width = kalinlikPx, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Durdur tuşu — cyan halka, içi boş. "Devam eden bir şeyi kes" jesti, başlatan
 * gradyan diskle aynı görsel ağırlıkta olmamalı; o yüzden dolgu değil çerçeve.
 *
 * Çerçevede dolaşan ışık [dolasanIsik]'tan geliyor (kullanıcı isteği
 * 18.08.2026); gerekçesi ve ölçüleri orada. Burada `aktif = true` sabit çünkü
 * bu bileşen zaten yalnız tur sürerken besteleniyor (çağıranda
 * `if (calisiyor)`) — tur bitince ağaçtan düşüyor, animasyon da onunla.
 */
@Composable
private fun DurdurTusu(onDurdur: () -> Unit) {
    val halka = Ui3Colors.running
    // SABİT HALKA AÇIK TEMADA SOLUYOR — hareketin görünmesinin ŞARTI bu.
    //
    // Dolaşan ışıkla sabit çerçeve AYNI renk. Koyu temada sorun değil: halka
    // sönük bir hat, ışık onun üstünde parlıyor. Açık temada `running` zaten
    // KOYU bir ton (#176B64) ve 0.60 alfayla çizilen halka fiilen dolaşan
    // parça kadar koyu oluyordu — ışık kendi çerçevesinin içinde kayboluyordu
    // (kullanıcı bildirdi 20.08.2026). Halkayı geri çekince fark 0.20 → 1.0'a
    // çıkıyor. Tuş yalnız tur sürerken çiziliyor, yani soluk halka tek başına
    // hiç görünmüyor; her zaman ışıkla birlikte.
    val halkaAlfa = if (Ui3Colors.palet.koyuMu) 0.60f else 0.20f
    Box(
        Modifier
            .size(GONDER)
            .clip(GONDER_YARICAP)
            .background(halka.copy(alpha = 0.06f))
            .border(1.5.dp, halka.copy(alpha = halkaAlfa), GONDER_YARICAP)
            .dolasanIsik(aktif = true, renk = halka, kalinlik = 1.5.dp)
            .clickable(onClick = onDurdur)
            .testTag("composer_durdur"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(halka),
        )
    }
}

/**
 * Bekleyen prompt kuyruğu — composer'ın ÜSTÜNDE, kendi cam kartında.
 *
 * Kuyruk istemci tarafında ve kalıcı: tur sürerken gönder tuşuna basılan her
 * prompt buraya düşer, tur bitince sıradaki kendiliğinden gider. Kapalıyken
 * yalnız ilk sıradaki tek satır görünür (composer'ın önüne geçmesin), dokununca
 * bütün sıra açılır ve öğeler tek tek çıkarılabilir — ui2'deki panelin ui3
 * karşılığı, özet metni ([queuedPromptPreview]) oradan ödünç.
 */
@Composable
internal fun Ui3KuyrukPaneli(
    hazeState: HazeState,
    kuyruk: List<QueuedPrompt>,
    onCikar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (kuyruk.isEmpty()) return
    // Anahtar ilk prompt: sıra baştan tükenince panel kendiliğinden kapanır,
    // yeni gelen prompt açık paneli kapatmaz.
    var acik by remember(kuyruk.first().id) { mutableStateOf(false) }
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.fillMaxWidth().testTag("kuyruk_paneli"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { acik = !acik }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            ) {
                Text("Sıra · ${kuyruk.size}", style = Ui3Type.alt, color = Ui3Colors.vurguHi)
                Text(
                    queuedPromptPreview(kuyruk.first()),
                    modifier = Modifier.weight(1f),
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (acik) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (acik) "Sırayı daralt" else "Sırayı aç",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (acik) {
                kuyruk.forEachIndexed { sira, prompt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    ) {
                        Text(
                            "${sira + 1}. ${queuedPromptPreview(prompt)}",
                            modifier = Modifier.weight(1f),
                            style = Ui3Type.alt,
                            color = Ui3Colors.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 28dp kutu, ama hedef 28dp değil: satırın geri kalanı
                        // tıklanabilir DEĞİL (düz metin), o yüzden Compose'un
                        // 48dp'ye kadar en yakın düğmeye verme davranışı burada
                        // çalışıyor. Büyütmek her kuyruk satırını uzatırdı.
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { onCikar(prompt.id) }
                                .testTag("kuyruk_cikar"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Sıradan çıkar",
                                tint = Ui3Colors.ink3,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Model çipi — şeritte görünür kalan tek ayar.
 *
 * Vurgu tonlu: şeritteki nötr ikonlardan ayrılsın, "bu bir değer, komut
 * değil" desin. Genişliği [MODEL_MAKS] ile sınırlı ve uzun ad kırpılır; tam
 * kimlik dokununca açılan seçicide yazıyor.
 *
 * RUNPOD NOKTASI (25.08.2026): seçili model RunPod ise etiketin SOLUNDA
 * pod'un hâlini gösteren 7dp'lik bir nokta yanar. Eskiden bunu sohbetin
 * dibindeki ayrı bir hap söylüyordu ve kullanıcı yerini "eğreti" buldu; hap
 * MODEL SEÇ sheet'ine, RunPod satırının altına indi, ambiyans buraya. Nokta
 * ÇİPİN İÇİNDE olduğu için
 * şeritten yer istemiyor ve çipe dokunmak zaten hapın olduğu sheet'i açıyor —
 * durum ve eylem aynı hedefte.
 */
@Composable
private fun ModelCipi(
    etiket: String,
    runpodIsigi: Ui3RunPodIsigi?,
    onTikla: () -> Unit,
    // Tavan ARTIK ÇAĞIRANDAN geliyor: şeritte o turda hangi düğmelerin
    // çizildiğine göre değişiyor (bkz. Ui3Composer'daki `cipTavani`). Sabit
    // MODEL_MAKS kalsaydı çip kendi tavanını alıp sonraki düğmeleri şeridin
    // dışına iterdi — dar cihazda ya da uzun model adında taşma buydu.
    maks: Dp = MODEL_MAKS,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(SERIT_DUGME)
            .widthIn(max = maks)
            .clip(DUGME_YARICAP)
            .background(Ui3Colors.vurgu.copy(alpha = 0.20f))
            .border(1.dp, Ui3Colors.vurguHi.copy(alpha = 0.34f), DUGME_YARICAP)
            .clickable(onClick = onTikla)
            .padding(horizontal = 11.dp)
            .testTag("composer_model"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (runpodIsigi != null) Ui3RunPodNoktasi(runpodIsigi)
        Text(
            etiket,
            style = Ui3Type.alt,
            // Paletten: sabit açık menekşe (0xFFE4DEFA) koyu temada doğruydu ama
            // açık temada menekşe zemin üstünde okunmuyordu — cihazda görüldü.
            color = Ui3Colors.vurguHi,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("▾", style = Ui3Type.etiket, color = Ui3Colors.ink2)
    }
}


/**
 * Yazı kutusunun içindeki "＋".
 *
 * Şerit düğmelerinden farklı olarak ZEMİNSİZ ve ÇERÇEVESİZ: zaten bir kuyunun
 * içinde duruyor, üstüne bir kutu daha çizmek iki katmanlı görünüyordu.
 * Dokunma hedefi yine 36dp.
 */
@Composable
private fun EkTusu(onTikla: () -> Unit) {
    Box(
        Modifier
            .size(SERIT_DUGME)
            .clip(DUGME_YARICAP)
            .clickable(onClick = onTikla)
            .testTag("composer_ek"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "Ek",
            tint = Ui3Colors.ink3,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun SeritDugmesi(
    ikon: ImageVector,
    aciklama: String,
    testEtiketi: String,
    onTikla: () -> Unit,
    etkin: Boolean = true,
    // İzin modu gevşekken tuşun TAMAMI kehribara döner. Önce köşesine küçük
    // bir nokta konmuştu; kullanıcı onu "orada bir bildirim var" diye okudu —
    // rozet birikmiş bir şey demek, oysa burada anlatılmak istenen bir DURUM.
    // Zeminin kendisi durumu taşıyınca yanlış okuma kalmıyor.
    uyari: Boolean = false,
    // İŞ SÜRÜYOR: çerçevede durdur tuşundakiyle aynı ışık dolaşır (kullanıcı
    // isteği 18.08.2026: "efekti yenile tuşuna da ekler misin"). Renk de aynı
    // (`running`) çünkü söylenen şey aynı — "bir iş sürüyor". Tuş bu sırada
    // zaten `etkin = false`; ikon sönükleşiyor ama sönük bir ikon "kapalı" ile
    // "meşgul"ü ayırt ettirmiyordu, ışık o farkı taşıyor.
    mesgul: Boolean = false,
) {
    Box(
        Modifier
            .size(SERIT_DUGME)
            .clip(DUGME_YARICAP)
            .background(if (uyari) Ui3Colors.attention.copy(alpha = 0.22f) else Ui3Colors.yuzey2)
            .border(
                1.dp,
                if (uyari) Ui3Colors.attention.copy(alpha = 0.55f) else Ui3Colors.cizgi,
                DUGME_YARICAP,
            )
            .dolasanIsik(aktif = mesgul, renk = Ui3Colors.running, kalinlik = 1.dp)
            .clickable(enabled = etkin, onClick = onTikla)
            .testTag(testEtiketi),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            ikon,
            contentDescription = aciklama,
            tint = when {
                uyari -> Ui3Colors.attention
                etkin -> Ui3Colors.ink2
                else -> Ui3Colors.ink3
            },
            modifier = Modifier.size(18.dp),
        )
    }
}
