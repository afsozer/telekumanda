package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui2.components.SelectorPinStore
import com.agent.bridge.ui2.components.selectorScrollTargetIndex
import com.agent.bridge.ui2.components.sortByPins
import com.agent.bridge.ui2.components.togglePin
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import kotlinx.coroutines.flow.first

/**
 * Satır biçimi. `Duz` ui2'den devralınan davranış (soldaki tik + düz etiket);
 * `Model` yalnız MODEL sheet'i için (kullanıcı kararı 22.08.2026): sağlayıcı
 * monogramı, mono ad, alt satırda sağlayıcı, seçim tik yerine VURGU ÇERÇEVESİ.
 *
 * Ayrı bir biçim olarak eklendi çünkü aynı bileşen ajan/izin/hesap/sağlayıcı
 * sheet'lerini de çiziyor; oralarda monogram ve mono ad yanlış olurdu.
 */
internal enum class Ui3SecenekBicimi { Duz, Model }

/**
 * Etiketten sağlayıcı ve ad ayrımı.
 *
 * OMP kataloğu "deepseek-pro · deepseek-v4-pro" biçiminde etiket veriyor;
 * ayıraç varsa sol taraf sağlayıcı, sağ taraf modelin kendi adı. Ayıraç yoksa
 * (ör. "GPT-5.6 Sol") sağlayıcı BİLİNMİYOR demektir ve uydurulmaz.
 *
 * OpenCode kimlikleri "saglayici/model" biçiminde (ör.
 * "nanogpt/abliteration-ai/abliterated-model"): boşluksuz ve eğik çizgili
 * etikette İLK parça sağlayıcıdır, gerisi modelin adı. Böylece ad satırı
 * sağlayıcı önekiyle dolmuyor (20.09.2026: üç model ekranda aynı görünüyordu).
 */
internal fun ui3ModelAdiAyir(etiket: String): Pair<String?, String> {
    val ayrac = " · "
    val i = etiket.indexOf(ayrac)
    if (i <= 0) {
        val egik = etiket.indexOf('/')
        if (egik <= 0 || egik == etiket.length - 1 || etiket.any { it.isWhitespace() }) return null to etiket
        return etiket.substring(0, egik) to etiket.substring(egik + 1)
    }
    val saglayici = etiket.substring(0, i).trim()
    val ad = etiket.substring(i + ayrac.length).trim()
    return if (saglayici.isEmpty() || ad.isEmpty()) null to etiket else saglayici to ad
}

/**
 * Monogram: ilk sözcüğün ilk iki harfi, büyük. Locale.ROOT ŞART — tr-TR'de
 * "i".uppercase() "İ" verir ve monogram cihazın diline göre değişirdi.
 */
internal fun ui3ModelMonogrami(saglayici: String?, ad: String): String {
    val kaynak = (saglayici ?: ad).trim()
    val harfler = kaynak.takeWhile { it != '-' && it != '/' && it != ' ' && it != '.' }
        .filter { it.isLetterOrDigit() }
    val kisa = harfler.take(2).ifEmpty { kaynak.filter { it.isLetterOrDigit() }.take(2) }
    return kisa.uppercase(java.util.Locale.ROOT).ifEmpty { "?" }
}

// Liste sheet'in tamamını kaplamasın: arkadaki sohbet görünür kalsın (yarı açık
// sheet deseni). Uzun katalog kendi içinde kaydırılır.
private val LISTE_MAKS = 400.dp

// Arama alanı bu sayının altındaki listelerde ÇİZİLMEZ: izin modu / efor gibi
// üç-beş satırlık sheet'lerde alan gürültü olur, katalog boyu listelerde ise
// şart (kullanıcı isteği 24.08.2026: model kataloğuna arama). Eşik listeye
// göre kendiliğinden karar verir, çağıranların bayrak taşıması gerekmez.
private const val ARAMA_ESIGI = 10

// Sorgu etikete, alt metne, rozete VE kimliğe (pinAnahtar) bakar: model
// kimliği "nanogpt/qwen/..." biçiminde sağlayıcıyı taşıyor, etikette ise
// sağlayıcı her zaman yok — yalnız etikete bakmak sağlayıcı adıyla aramayı
// (ör. "nanogpt") sessizce boşa çıkarırdı.
private fun <T> secenekEslesir(secenek: SelectorOption<T>, sorgu: String, anahtar: (T) -> String): Boolean =
    secenek.label.contains(sorgu, ignoreCase = true) ||
        secenek.detail?.contains(sorgu, ignoreCase = true) == true ||
        secenek.badge?.contains(sorgu, ignoreCase = true) == true ||
        anahtar(secenek.value).contains(sorgu, ignoreCase = true)

/**
 * ui3'ün tek jenerik seçicisi — model / ajan / effort / izin modu / Skill /
 * hesap / sağlayıcı, hepsi bu.
 *
 * "Backend'e özel seçici YAZILMAZ" kuralı ui2'den aynen devralındı; bu bileşen
 * ui2'nin `SelectorSheet`'inin ui3 malzemesiyle çizilmiş karşılığı.
 *
 * MANTIK ui2'den ithal, ÇİZİM ui3'ten (kullanıcı kararı):
 *  - [SelectorOption] veri modeli aynen kullanılıyor — çağıranlar ui2'nin
 *    kurduğu listeleri hiç dönüştürmeden veriyor.
 *  - Sabitleme (`sortByPins`, `togglePin`, [SelectorPinStore]) ve seçiliye
 *    kaydırma hesabı (`selectorScrollTargetIndex`) ui2'deki dosyalardan
 *    çağrılıyor. Bunlar saf mantık; yeniden yazmak tam da "sessiz regresyon"
 *    riskinin kaynağı olurdu.
 *
 * ui2'nin `ModalBottomSheet`'e özgü anchor koruma numaraları burada YOK ve
 * gerekmiyor: kap [GlassSheet], yüksekliği içeriğinden geliyor, "bilgi kartına
 * geçince sheet zıplıyor" sorunu doğmuyor.
 *
 * TAŞIMA BORCU: `SelectorOption`, `SelectorPinStore` ve saf yardımcılar hâlâ
 * `ui2/components` altında. ui2 silinmeden önce paylaşılan katmana taşınmalı —
 * kullanıcı kararı "ui3 ui2'nin yerini alacak".
 */
@Composable
internal fun <T> ColumnScope.Ui3Selector(
    baslik: String,
    secenekler: List<SelectorOption<T>>,
    onSec: (T) -> Unit,
    altBaslik: String? = null,
    seciliDeger: T? = null,
    yukleniyor: Boolean = false,
    detayMaksSatir: Int = 1,
    bosMetin: String? = null,
    pinKapsam: String? = null,
    pinAnahtar: (T) -> String = { it.toString() },
    bicim: Ui3SecenekBicimi = Ui3SecenekBicimi.Duz,
    // Dolu ise baslikta geri oku: bu sheet'e ... menusunden gelinmis demektir.
    onGeri: (() -> Unit)? = null,
    // SATIR ALTI YUVASI — o seceneğe ÖZGÜ, secmekten baska bir eylem.
    //
    // Bugunku tek musterisi RunPod hapi: RunPod modelinin satirinin altinda
    // pod'u acip kapatiyor (bkz. Ui3Root, MODEL sheet'i). Yuva olarak yazildi
    // cunku bu bilesen ajan/izin/hesap/saglayici sheet'lerini de ciziyor —
    // "RunPod"i bilen bir dal buraya girseydi jenerik secici backend'e ozel
    // olurdu ve "backend'e ozel secici YAZILMAZ" kurali icerden delinirdi.
    //
    // TIP NEDEN "composable DONDUREN duz fonksiyon": ilgisiz satirlarda hicbir
    // sey cizmeyen bir composable yeterli DEGIL — altindaki kutu dolgusuyla
    // birlikte yine de yer tutar ve katalogdaki her model satiri bosuna
    // uzardi. Yuva null dondurunce satir bugunku halinde kalir; kosul da tek
    // yerde, cagiranda durur.
    satirAlti: ((T) -> (@Composable () -> Unit)?)? = null,
) {
    var bilgiSecenegi by remember { mutableStateOf<SelectorOption<T>?>(null) }
    var arama by remember { mutableStateOf("") }
    val context = LocalContext.current
    val pinDeposu = remember(pinKapsam) { pinKapsam?.let { SelectorPinStore(context) } }
    var sabitliler by remember(pinKapsam) {
        mutableStateOf(pinKapsam?.let { pinDeposu?.pinned(it) } ?: emptySet())
    }
    val sirali = if (pinKapsam == null) secenekler else sortByPins(secenekler, sabitliler, pinAnahtar)

    val bilgi = bilgiSecenegi
    if (bilgi != null) {
        BilgiKarti(secenek = bilgi, onGeri = { bilgiSecenegi = null })
        return
    }

    SheetBasligi(baslik, altBaslik, onGeri)

    if (yukleniyor) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s20),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Ui3Colors.vurguHi,
                strokeWidth = 2.dp,
            )
            Text("Yükleniyor…", style = Ui3Type.govde, color = Ui3Colors.ink2)
        }
        return
    }

    if (sirali.isEmpty()) {
        Text(
            bosMetin ?: "Seçenek yok.",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16),
        )
        return
    }

    if (sirali.size >= ARAMA_ESIGI) {
        AramaAlani(deger = arama, onDegis = { arama = it })
    }
    val sorgu = arama.trim()
    val goster = if (sorgu.isEmpty()) sirali else sirali.filter { secenekEslesir(it, sorgu, pinAnahtar) }
    if (goster.isEmpty()) {
        Text(
            "Aramayla eşleşen seçenek yok.",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16),
        )
        return
    }

    val listeDurumu = rememberLazyListState()
    // Seçili öğe listenin dibindeyse kullanıcı hangi seçeneğin açık olduğunu
    // görmek için elle kaydırmak zorunda kalıyordu (ui2'de ölçülen davranış).
    // İlk birkaç satır zaten görünür olduğu için orada dokunulmuyor.
    var kaydirildi by remember(pinKapsam) { mutableStateOf(false) }
    LaunchedEffect(goster.size, seciliDeger) {
        // Arama süzerken kaydırma yapılmaz: süzülen liste zaten kısa ve sorgu
        // her harfte değişip listeyi yeniden kurduğu için kaydırma titretirdi.
        if (kaydirildi || seciliDeger == null || sorgu.isNotEmpty()) return@LaunchedEffect
        val indeks = goster.indexOfFirst { it.value == seciliDeger }
        if (indeks < 0) return@LaunchedEffect
        // Liste henüz ölçülmemişken scrollToItem sessizce düşüyor — ui2'de ilk
        // denemede tam bu yüzden hiçbir şey olmamıştı. Önce yerleşmesini bekle.
        snapshotFlow { listeDurumu.layoutInfo.totalItemsCount }.first { it >= goster.size }
        kaydirildi = true
        val hedef = selectorScrollTargetIndex(indeks) ?: return@LaunchedEffect
        runCatching { listeDurumu.scrollToItem(hedef) }
    }

    LazyColumn(
        state = listeDurumu,
        modifier = Modifier.fillMaxWidth().heightIn(max = LISTE_MAKS).testTag("secici_liste"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Ui3Tokens.s12,
            vertical = Ui3Tokens.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        items(goster, key = { pinAnahtar(it.value) }) { secenek ->
            SecenekSatiri(
                secenek = secenek,
                secili = seciliDeger != null && secenek.value == seciliDeger,
                detayMaksSatir = detayMaksSatir,
                bicim = bicim,
                sabitli = pinKapsam != null && pinAnahtar(secenek.value) in sabitliler,
                onSabitle = if (pinKapsam == null) null else {
                    {
                        val yeni = togglePin(sabitliler, pinAnahtar(secenek.value))
                        sabitliler = yeni
                        pinDeposu?.save(pinKapsam, yeni)
                    }
                },
                onBilgi = if (secenek.info == null) null else ({ bilgiSecenegi = secenek }),
                satirAlti = satirAlti?.invoke(secenek.value),
                onTikla = { onSec(secenek.value) },
            )
        }
    }
}

/**
 * Sheet baslik satiri.
 *
 * [onGeri] doluysa SOL USTTE bir geri oku cizilir ve sheet bir onceki sheet'e
 * doner. Gerekce (kullanici, 22.08.2026): izin modu / caba / skill gibi
 * ekranlara girmenin tek yolu ... menusu ama geri donmenin yolu YOKTU; sheet'i
 * kapatip menuyu yeniden acmak gerekiyordu. Null ise ok hic cizilmez, cunku o
 * sheet'e dogrudan gelinmistir ve donulecek bir yer yoktur (or. composer
 * cipinden acilan model secici).
 */
@Composable
internal fun SheetBasligi(baslik: String, altBaslik: String?, onGeri: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(
            // Ok kendi 48dp dokunma hedefini tasiyor; basligin sol payi onun
            // icinde eridigi icin okla birlikte sol dolgu kuculuyor.
            start = if (onGeri == null) Ui3Tokens.s20 else Ui3Tokens.s8,
            end = Ui3Tokens.s20,
            bottom = Ui3Tokens.s8,
        ),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onGeri != null) {
            KucukEylem(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Menuye don",
                onGeri,
                "sheet_geri",
                Ui3Colors.ink2,
            )
        }
        Column {
            Text(baslik, style = Ui3Type.etiket, color = Ui3Colors.ink3)
            if (!altBaslik.isNullOrBlank()) {
                Text(
                    altBaslik,
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink3,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * İKİ SEÇENEK, TEK SATIR, İKİ SÜTUN.
 *
 * [Ui3Selector]'ın alt alta satır listesi iki seçenek için fazla resmî
 * duruyordu: sheet'in yarısı boş kalıyor, iki geniş şerit üst üste diziliyordu
 * (kullanıcı: "hiç şık değil", 18.08.2026). Seçenek sayısı ikiyken ve ikisi
 * BİRBİRİNİN ALTERNATİFİYKEN doğru biçim yan yana iki kart: karşılaştırma
 * gözle tek hamlede yapılıyor.
 *
 * Bilerek jenerik değil de "tam iki" olarak yazıldı — üçüncü seçenek eklenmek
 * istenirse burası derlenmez ve çağıran [Ui3Selector]'a dönmek zorunda kalır.
 * Sığmayan üçüncü sütunu sessizce kırpmaktansa bu iyi.
 */
@Composable
internal fun <T> ColumnScope.Ui3IkiliSecim(
    baslik: String,
    altBaslik: String?,
    sol: SelectorOption<T>,
    solIkon: ImageVector,
    sag: SelectorOption<T>,
    sagIkon: ImageVector,
    onSec: (T) -> Unit,
) {
    SheetBasligi(baslik, altBaslik)
    // `IntrinsicSize.Min` + `fillMaxHeight`: iki kart AYNI BOYDA olsun. Bu
    // olmadan kartlar kendi içeriği kadar uzuyor ve açıklaması iki satıra
    // sarılan kart diğerinden 63px uzun kalıyordu (cihazda ölçüldü) — yan yana
    // iki farklı boyda kutu, listeden daha dağınık duruyor.
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = Ui3Tokens.s12, vertical = Ui3Tokens.s4),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
    ) {
        IkiliKart(Modifier.weight(1f).fillMaxHeight(), sol, solIkon) { onSec(sol.value) }
        IkiliKart(Modifier.weight(1f).fillMaxHeight(), sag, sagIkon) { onSec(sag.value) }
    }
}

@Composable
private fun <T> RowScope.IkiliKart(
    modifier: Modifier,
    secenek: SelectorOption<T>,
    ikon: ImageVector,
    onTikla: () -> Unit,
) {
    GlassLikeSurface(
        modifier = modifier
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .clickable(onClick = onTikla)
            .testTag("secenek"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Ui3Tokens.s12, vertical = Ui3Tokens.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
            // ÜSTTEN hizalı, ortadan değil: kartlar eşit boyda ama içerikleri
            // farklı uzunlukta (bir açıklama iki satıra sarılıyor). Ortalanınca
            // iki karttaki ikon ve başlık farklı yükseklikte duruyordu (cihazda
            // görüldü); üstten hizalanınca artan yer kartın DİBİNDE toplanıyor.
            verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8, Alignment.Top),
        ) {
            // İkon kendi kuyusunda: iki kartın başlıkları farklı uzunlukta
            // olsa da üstteki daire hizası ortak bir çizgi veriyor.
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Ui3Colors.kuyu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ikon, contentDescription = null, tint = Ui3Colors.vurguHi, modifier = Modifier.size(20.dp))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    secenek.label,
                    style = Ui3Type.govde,
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detay = secenek.detail
                if (!detay.isNullOrBlank()) {
                    Text(
                        detay,
                        style = Ui3Type.alt,
                        color = Ui3Colors.ink3,
                        textAlign = TextAlign.Center,
                        // Iki satir: dar sutunda tek satira sigmayan aciklama
                        // kirpilmak yerine sariliyor.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SecenekSatiri(
    secenek: SelectorOption<T>,
    secili: Boolean,
    detayMaksSatir: Int,
    bicim: Ui3SecenekBicimi,
    sabitli: Boolean,
    onSabitle: (() -> Unit)?,
    onBilgi: (() -> Unit)?,
    satirAlti: (@Composable () -> Unit)?,
    onTikla: () -> Unit,
) {
    val modelBicimi = bicim == Ui3SecenekBicimi.Model
    val (saglayici, kisaAd) = if (modelBicimi) ui3ModelAdiAyir(secenek.label) else null to secenek.label
    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            // MODEL BICIMINDE SECIM CERCEVEYLE (kullanici karari, 22.08.2026):
            // cam yuzeyde yuzde onluk bir zemin farki zayif sinyaldi ve onu tek
            // basina tasiyan tik satirin en solunda kaliyordu. Cerceve + tonlu
            // zemin + sagdaki tik birlikte hic kacmiyor.
            .then(
                if (modelBicimi && secili) {
                    Modifier.border(
                        1.dp,
                        Ui3Colors.vurguHi.copy(alpha = 0.55f),
                        RoundedCornerShape(Ui3Tokens.r18),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onTikla)
            .testTag(if (secili) "secenek_secili" else "secenek"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
        tint = if (secili) GlassTint.Vio else GlassTint.Notr,
    ) {
        Column {
        Row(
            Modifier.padding(
                start = Ui3Tokens.s12,
                end = Ui3Tokens.s4,
                top = 10.dp,
                // Altta bir sey varsa satirin dibi ona birakilir; iki bloğun
                // arasinda 10+10 = 20dp'lik cift bosluk kalmasin.
                bottom = if (satirAlti == null) 10.dp else 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (modelBicimi) {
                // Saglayici monogrami: etiketten TURETILIYOR, uydurulmuyor.
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Ui3Colors.yuzey2)
                        .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ui3ModelMonogrami(saglayici, kisaAd),
                        style = Ui3Type.etiket.copy(fontFamily = Ui3Mono),
                        color = if (secili) Ui3Colors.vurguHi else Ui3Colors.ink3,
                    )
                }
            } else {
                // Secili isareti SOLDA ve yer tutuyor: satirlar secim degisince
                // yatayda kaymasin.
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (secili) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Secili",
                            tint = Ui3Colors.vurguHi,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        kisaAd,
                        style = if (modelBicimi) Ui3Type.alt.copy(fontFamily = Ui3Mono) else Ui3Type.govde,
                        fontWeight = if (modelBicimi) FontWeight.SemiBold else null,
                        color = if (secili || modelBicimi) Ui3Colors.ink else Ui3Colors.ink2,
                        // IKI SATIR (19.08.2026): tek satir + kirpma, ayrimin en
                        // cok gerektigi yerde ayrimi yok ediyordu. Iki uzun model
                        // adi ekranda AYNI gorunuyordu ama biri veri paylasimi
                        // istiyor ve secilirse tur 403 ile oluyor.
                        //
                        // Model biciminde de IKI SATIR (20.09.2026): uzun adlar
                        // ("abliteration-ai/abliterated-...") tek satirda kirpilinca
                        // uc farkli model ayni gorunuyordu.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val rozet = secenek.badge
                    if (!rozet.isNullOrBlank()) {
                        Text(
                            rozet,
                            style = Ui3Type.etiket,
                            color = Ui3Colors.ink3,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(Ui3Tokens.pill)
                                .background(Ui3Colors.yuzey2)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                // Model biciminde alt satir SAGLAYICI; katalog ayrica bir detay
                // verirse arkasina ekleniyor. Baglam boyu / fiyat gibi alanlar
                // bu veri modelinde YOK, uydurulmadi.
                val altMetin = if (modelBicimi) {
                    listOfNotNull(saglayici, secenek.detail?.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() }
                } else {
                    secenek.detail?.takeIf { it.isNotBlank() }
                }
                if (altMetin != null) {
                    Text(
                        altMetin,
                        style = Ui3Type.alt,
                        color = Ui3Colors.ink3,
                        maxLines = if (modelBicimi) 1 else detayMaksSatir,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (modelBicimi && secili) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Secili",
                    tint = Ui3Colors.vurguHi,
                    modifier = Modifier.size(17.dp),
                )
            }
            if (onBilgi != null) {
                KucukEylem(Icons.Filled.Info, "Bilgi", onBilgi, "secenek_bilgi")
            }
            if (onSabitle != null) {
                KucukEylem(
                    ikon = if (sabitli) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    aciklama = if (sabitli) "Sabitlemeyi kaldir" else "Sabitle",
                    onTikla = onSabitle,
                    testEtiketi = "secenek_sabitle",
                    renk = if (sabitli) Ui3Colors.amber else Ui3Colors.ink3,
                )
            }
        }
        if (satirAlti != null) {
            // SATIRIN YANINDA DEĞİL, ALTINDA. Yan yana denendi ve dar cihazda
            // ölçüm tutmuyordu: monogram (34) + sabitle yıldızı (48) + hapın
            // en uzun etiketi ("Model ısınıyor…", 13sp serif ≈ 120dp) 360dp'lik
            // bir satırda model adına 60dp'den az yer bırakıyor, yani tam da
            // ayrımın gerektiği yerde ad kırpılıyordu. Altta hap kendi boyunu
            // alıyor, ad tam okunuyor ve o satır listede "özel" görünüyor.
            Box(
                Modifier.padding(
                    start = Ui3Tokens.s12,
                    end = Ui3Tokens.s12,
                    bottom = 10.dp,
                ),
            ) {
                satirAlti()
            }
        }
        }
    }
}

/**
 * Sheet içi arama pili. ui2'nin `SearchField`'i AYNI iş için var ama Ui2
 * paletiyle çiziyor; cam sheet'te o yüzey/çizgi renkleri yabancı duruyor.
 * Mantığı üç satır olduğu için ödünç almak yerine ui3 malzemesiyle yeniden
 * çizildi (ödünç kap deseni gövdeler içindi, tema atomları için değil).
 */
@Composable
private fun AramaAlani(deger: String, onDegis: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Ui3Tokens.s12, end = Ui3Tokens.s12, bottom = Ui3Tokens.s8)
            .clip(Ui3Tokens.pill)
            .background(Ui3Colors.yuzey2)
            .border(1.dp, Ui3Colors.cizgiInce, Ui3Tokens.pill)
            .padding(horizontal = Ui3Tokens.s12, vertical = 8.dp)
            .testTag("secici_arama"),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Ui3Colors.ink3, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f)) {
            if (deger.isEmpty()) {
                Text("Ara…", style = Ui3Type.govde, color = Ui3Colors.ink3)
            }
            BasicTextField(
                value = deger,
                onValueChange = onDegis,
                singleLine = true,
                textStyle = Ui3Type.govde.copy(color = Ui3Colors.ink),
                cursorBrush = SolidColor(Ui3Colors.vurguHi),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (deger.isNotEmpty()) {
            // KucukEylem'in 48dp hedefi pilin içine sığmaz; buradaki satır zaten
            // tıklanabilir bir gövde taşımadığı için küçük hedef yeterli.
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable { onDegis("") }
                    .testTag("secici_arama_temizle"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Temizle", tint = Ui3Colors.ink3, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun KucukEylem(
    ikon: androidx.compose.ui.graphics.vector.ImageVector,
    aciklama: String,
    onTikla: () -> Unit,
    testEtiketi: String,
    renk: Color = Ui3Colors.ink3,
) {
    // 48dp dokunma hedefi (Material tabanı), 17dp ikon.
    //
    // Burada büyütmek ŞART, çünkü Compose'un "ıskalayan dokunuşu 48dp'ye kadar
    // en yakın düğmeye ver" davranışı bu satırda ÇALIŞMIYOR: seçenek satırının
    // tamamı `clickable` (modeli seçer) ve ikonun çevresindeki pikselleri katı
    // olarak o kapıyor. Yani 34dp'lik kutunun dışına düşen her dokunuş
    // "Sabitle" yerine "modeli seç" oluyordu — sessiz ve can sıkıcı bir hata.
    // Kutunun arka planı yok, büyütmenin görünürde bir bedeli de yok.
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onTikla)
            .testTag(testEtiketi),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ikon, contentDescription = aciklama, tint = renk, modifier = Modifier.size(17.dp))
    }
}

/** Model bilgi kartı — açıklama, ne için uygun, profil, kaynak. */
@Composable
private fun <T> ColumnScope.BilgiKarti(secenek: SelectorOption<T>, onGeri: () -> Unit) {
    val bilgi = secenek.info ?: return
    val uriAcici = LocalUriHandler.current

    Row(
        Modifier.fillMaxWidth().padding(start = Ui3Tokens.s8, end = Ui3Tokens.s20, bottom = Ui3Tokens.s8),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KucukEylem(Icons.AutoMirrored.Filled.ArrowBack, "Listeye dön", onGeri, "bilgi_geri", Ui3Colors.ink2)
        Text(
            secenek.label,
            style = Ui3Type.govde,
            color = Ui3Colors.ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = LISTE_MAKS)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s20),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        Text(bilgi.description, style = Ui3Type.govde, color = Ui3Colors.ink2)
        BilgiSatiri("En uygun", bilgi.bestFor)
        BilgiSatiri("Profil", bilgi.profile)
        val kaynak = bilgi.sourceUrl
        if (!kaynak.isNullOrBlank()) {
            Row(
                Modifier
                    .clip(Ui3Tokens.pill)
                    .clickable { uriAcici.openUri(kaynak) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.OpenInNew,
                    contentDescription = null,
                    tint = Ui3Colors.vurguHi,
                    modifier = Modifier.size(15.dp),
                )
                Text("Hugging Face'te aç", style = Ui3Type.alt, color = Ui3Colors.vurguHi)
            }
        }
    }
}

@Composable
private fun BilgiSatiri(etiket: String, deger: String) {
    if (deger.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(etiket.uppercase(), style = Ui3Type.etiket, color = Ui3Colors.ink3)
        Text(deger, style = Ui3Type.alt, color = Ui3Colors.ink2)
    }
}
