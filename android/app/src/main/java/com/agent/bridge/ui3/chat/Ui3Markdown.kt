package com.agent.bridge.ui3.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.MarkdownSegment
import com.agent.bridge.MarkwonText
import com.agent.bridge.splitMarkdownSegments
import com.agent.bridge.ui2.chat.chatSwipeExclusion
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * Ajan metni — markdown ile.
 *
 * ui3 ilk sürümlerinde ajan metni düz `Text` olarak çiziliyordu; cihazda
 * `**kalın**` yıldızlarıyla, kod blokları da satır içi düz metin olarak
 * görünüyordu. ui2'nin en çok kullanılan özelliği buydu, kapatılmayan en ağır
 * regresyondu.
 *
 * MANTIK ui2'den, ÇİZİM ui3'ten (kullanıcı kararı):
 *  - `splitMarkdownSegments` saf ayrıştırıcı, olduğu gibi kullanılıyor.
 *  - Düz parçalar `MarkwonText` ile — markdown motorunu ui3'te yeniden yazmak
 *    tablo/liste/link davranışlarını sessizce bozardı. Bileşen zaten renk
 *    parametreli, ui3 paletini veriyoruz.
 *  - Kod blokları ui3 malzemesiyle yeniden çizildi (aşağıda).
 */
@Composable
internal fun Ui3AjanMetni(
    metin: String,
    onDosya: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parcalar = remember(metin) { splitMarkdownSegments(metin) }
    // Markdown bir TextView'de çiziliyor; Compose'un fontFamily'si oraya
    // ulaşmıyor, typeface elden veriliyor. Kaynak res/font, yani Ui3Serif'in
    // tam aynısı (Liberation Serif) — ajan metni sohbetin geri kalanıyla aynı
    // yüzde. Ui3Serif değişirse BURASI DA değişmeli; iki kaynak var.
    val yazTipi = androidx.compose.ui.platform.LocalContext.current.let { ctx ->
        remember(ctx) { androidx.core.content.res.ResourcesCompat.getFont(ctx, com.agent.bridge.R.font.liberation_serif_regular) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8)) {
        parcalar.forEach { parca ->
            when (parca) {
                is MarkdownSegment.Prose -> MarkwonText(
                    text = parca.text,
                    color = Ui3Colors.ink,
                    onFileClick = onDosya,
                    yazTipi = yazTipi,
                    // ui2'de 13.5sp/1.05; ui3 serif olduğu için daha açık satır
                    // arası (1.15 → 1.28, kullanıcı 18.08). DİKKAT: bu çarpan
                    // fontun asc+desc'iyle çarpılır (Tiempos'ta tam 1.0 em),
                    // yani efektif pitch = 1.28 × 16sp ≈ 20.5sp. Ui3Type.akis /
                    // akisKullanici'nin lineHeight'ı bu sayının aynası — çarpanı
                    // değiştirirsen ORAYI DA değiştir, yoksa kullanıcı balonu
                    // ajan metninden farklı ritimde akar (kullanıcı yakaladı).
                    // Punto ELLE YAZILMAZ: aynı kaynaktan okunur.
                    textSizeSp = Ui3Type.akis.fontSize.value,
                    satirCarpani = 1.28f,
                )
                is MarkdownSegment.Code -> Ui3KodKarti(parca)
            }
        }
    }
}

/**
 * Kod bloğu kartı — "kod, yol, komut, log HER ZAMAN mono + yüzey kutusunda"
 * (anayasa v2 bölüm 4).
 *
 * Yatay kaydırılır, sarılmaz: girinti okunur kalsın. Altında kendi "Kopyala"
 * tuşu var — uzun bir kod bloğunu tüm mesajı kopyalamadan alabilmek için
 * (ui2'de aynı gerekçeyle eklenmişti).
 */
@Composable
private fun Ui3KodKarti(parca: MarkdownSegment.Code) {
    val pano = LocalClipboardManager.current
    var kopyalandi by remember(parca.code) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .background(Ui3Colors.kuyu)
            .border(1.dp, Ui3Colors.yuzey2, RoundedCornerShape(Ui3Tokens.r12))
            .padding(horizontal = 11.dp, vertical = 9.dp)
            .testTag("kod_blogu"),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
    ) {
        SelectionContainer {
            Text(
                parca.code,
                // JEST MUAFİYETİ (19.08.2026, kullanıcı bildirdi: "kod
                // bloklarını sağa sola kaydırırken sekme geçiyor").
                //
                // Kod bloğu ui3'te YENİDEN çizildi (ui3 malzemesi, kendi
                // "Kopyala" tuşu) ve o sırada ui2'nin `chatSwipeExclusion()`
                // çağrısı düştü. Markwon'un kendi kod/tablo bloklarında
                // muafiyet duruyordu, bu kart onların yanında sessizce muafsız
                // kalmıştı: uzun bir komut satırını yatay kaydırmak sohbet
                // yüzeyindeki yatay jestle aynı hareket, jest de eşiği görünce
                // sekme değiştiriyordu. Kart sınırlarını kayıt defterine
                // yazınca jest bu bölgede hiç başlamıyor.
                modifier = Modifier
                    .fillMaxWidth()
                    .chatSwipeExclusion()
                    .horizontalScroll(rememberScrollState()),
                style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                color = Ui3Colors.vurguHi,
                softWrap = false,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                parca.language.ifBlank { "kod" },
                style = Ui3Type.etiket,
                color = Ui3Colors.ink3,
            )
            Row(
                Modifier
                    .clip(Ui3Tokens.pill)
                    .clickable {
                        pano.setText(AnnotatedString(parca.code))
                        kopyalandi = true
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("kod_kopyala"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
            ) {
                Icon(
                    if (kopyalandi) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = "Kodu kopyala",
                    tint = if (kopyalandi) Ui3Colors.done else Ui3Colors.ink3,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    if (kopyalandi) "Kopyalandı" else "Kopyala",
                    style = Ui3Type.etiket,
                    color = if (kopyalandi) Ui3Colors.done else Ui3Colors.ink3,
                )
            }
        }
    }
}

/**
 * Mesajın tamamını kopyalama satırı + zaman damgası.
 *
 * ui2'de her ajan bloğunun altında var; ui3'te hiç yoktu. Sessiz ama günlük
 * kullanımda çok işleyen bir şey — çıktıyı bilgisayara taşımanın yolu bu.
 */
@Composable
internal fun Ui3KopyalaSatiri(
    metin: String,
    zaman: String,
    modifier: Modifier = Modifier,
    // Kullanıcı balonunun altında iki tuş daha var (ui2'deki `MiniAction`
    // satırının karşılığı): turu bu mesaja geri sar, buradan yeni bir dal aç.
    // Ajan mesajlarında ikisi de null — orada geri dönülecek bir "sen" yok.
    onDon: (() -> Unit)? = null,
    onCatalla: (() -> Unit)? = null,
    saga: Boolean = false,
) {
    val pano = LocalClipboardManager.current
    var kopyalandi by remember(metin) { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (saga) Arrangement.spacedBy(Ui3Tokens.s8, Alignment.End)
                                else Arrangement.spacedBy(Ui3Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(Ui3Tokens.pill)
                .clickable {
                    pano.setText(AnnotatedString(metin))
                    kopyalandi = true
                }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag("mesaj_kopyala"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                if (kopyalandi) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "Mesajı kopyala",
                tint = if (kopyalandi) Ui3Colors.done else Ui3Colors.ink3,
                modifier = Modifier.size(12.dp),
            )
            Text(
                if (kopyalandi) "Kopyalandı" else "Kopyala",
                style = Ui3Type.etiket,
                color = if (kopyalandi) Ui3Colors.done else Ui3Colors.ink3,
            )
        }
        if (onDon != null) {
            MiniEylem(Icons.AutoMirrored.Filled.Undo, "Bu mesaja dön", "mesaj_don", onDon)
        }
        if (onCatalla != null) {
            MiniEylem(Icons.AutoMirrored.Filled.CallSplit, "Buradan çatalla", "mesaj_catalla", onCatalla)
        }
        if (zaman.isNotBlank()) {
            Text(zaman, style = Ui3Type.etiket, color = Ui3Colors.ink3)
        }
    }
}

/**
 * Kopyala satırındaki ikonlu minik tuş (ui2 `MiniAction` ölçüleriyle).
 *
 * 22dp KÜÇÜK GÖRÜNÜYOR ama dokunma hedefi 22dp DEĞİL. Compose'un hit-test'i,
 * katı sınırları ıskalayan bir dokunuşu 48dp'ye kadar en yakın düğmeye veriyor
 * (`NodeCoordinator.minimumTouchTargetSize` / `distanceInMinimumTouchTarget`,
 * derleyicideki sınıfa bakılarak doğrulandı 17.08.2026). Bu satırda tuşların
 * çevresi ÖLÜ alan — tıklanabilir bir kardeş ya da ebeveyn yüzey yok — yani
 * genişletme fiilen çalışıyor.
 *
 * Bir ara kutu 32dp'ye çıkarılmıştı; geri alındı: her kullanıcı balonunun
 * altına 10dp boşluk ekliyordu ve kullanıcı zaten kısa mesajlardaki boş
 * alandan şikâyetçiydi. Bedeli olan yerde büyütme yapılmıyor, karşılığı olan
 * yerde yapılıyor (`Ui3Selector.KucukEylem`, `dibe_in`).
 */
@Composable
private fun MiniEylem(
    ikon: androidx.compose.ui.graphics.vector.ImageVector,
    aciklama: String,
    testEtiketi: String,
    onTikla: () -> Unit,
) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onTikla)
            .testTag(testEtiketi),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ikon, contentDescription = aciklama, tint = Ui3Colors.ink3, modifier = Modifier.size(13.dp))
    }
}

/**
 * Katlanabilir düşünce/araç kartı — ui2'nin `ToolCallCard` davranışı.
 *
 * Kapalıyken tek satır özet, dokununca gövde açılır. Açık/kapalı durumu YEREL
 * ve `rememberSaveable`: ui2'de "detay bir kez yüklenince kart kapanamıyordu"
 * hatası tam olarak durumu detayın varlığına bağlamaktan çıkmıştı, o hata
 * buraya taşınmıyor.
 */
@Composable
internal fun Ui3KatlanirKart(
    tur: String,
    ozet: String,
    acik: Boolean,
    onAcKapa: () -> Unit,
    govde: @Composable () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .clickable(onClick = onAcKapa)
            .testTag("katlanir_kart"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tur,
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink2,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ozet,
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (acik) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (acik) "Kapat" else "Aç",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(visible = acik) {
                Box(Modifier.padding(top = Ui3Tokens.s8)) { govde() }
            }
        }
    }
}

/**
 * Kısa düşünce satırı — kart açmaya değmeyecek kadar kısa olanlar.
 *
 * Sol kenarda ince bir hat, sönük metin: akışta yer kaplar ama okuma sırasını
 * bölmez (ui2'nin `ThoughtStrip`'iyle aynı niyet, ui3 paletiyle).
 */
@Composable
internal fun Ui3DusunceSeridi(metin: String, modifier: Modifier = Modifier) {
    // IntrinsicSize.Min + fillMaxHeight: hat metnin gerçek yüksekliği kadar
    // uzasın. Bu olmadan Box yükseklik alamıyor ve hiç çizilmiyor.
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Ui3Colors.cizgi, Ui3Tokens.pill),
        )
        Text(metin, style = Ui3Type.alt, color = Ui3Colors.ink3)
    }
}
