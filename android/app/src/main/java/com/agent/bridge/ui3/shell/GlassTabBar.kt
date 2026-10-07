package com.agent.bridge.ui3.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.chat.SessionTabUi
import com.agent.bridge.ui2.chat.activeTabScrollIndex
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// 38 → 32: sekme şeridi dikeyde gereğinden kalındı (kullanıcı bildirdi) ve
// o yer okuma alanından gidiyordu.
// 32 → 28: kullanıcı sekmelerin dikey olarak geniş durduğunu iki kez söyledi.
// 28dp'de tek satır metin (13sp ≈ 17dp) + 2×5.5dp nefes kalıyor; daha aşağısı
// yazıyı kırpar. Dokunma hedefi Compose'un asgari 48dp'siyle zaten korunuyor.
private val CIP_Y = 28.dp

// Köşeler hap değil yuvarlak dikdörtgen (kullanıcı geri bildirimi: "çok oval").
private val CIP_YARICAP = RoundedCornerShape(Ui3Tokens.r12)

// Canlı başlığın çipte gösterilen ilk parçası. ui2 ile aynı sayı: iki arayüz
// aynı sekmeyi farklı uzunlukta kesmesin.
private const val BASLIK_KIRPMA = 12

/**
 * Sekme çubuğu — ui2'nin `SessionTabBar` DAVRANIŞI ve BİLGİ İÇERİĞİ, ui3'ün
 * malzemesiyle (anayasa v2 bölüm 6).
 *
 * ui3 ilk sürümde sekmeleri kaldırıp yerine başlıktaki ▾ seçiciyi koymuştu;
 * kullanıcı gerçek işte deneyince en çok bunu aradı. İkinci sürüm sekmeleri
 * geri getirdi ama bilgi içeriğini eksik taşıdı; bu sürüm onu da kapatıyor:
 *
 * 1. **Monogram geri geldi.** ui2'de renk YALNIZ durumu anlatır (mavi çalışıyor
 *    / kehribar onay / yeşil bitti), kimliği monogram taşır. ui3'te ikisi de
 *    kaybolmuştu ve hangi sekmenin hangi sağlayıcı olduğu anlaşılmıyordu.
 * 2. **"repo · başlık" birlikte.** Önceki sürüm `liveTitle.ifBlank { repo }`
 *    yazıyordu — ikisinden yalnız biri görünüyordu. ui2 ikisini tek
 *    `AnnotatedString`'de veriyor, aynısı yapıldı.
 * 3. **＋ kaydırılan listenin DIŞINDA.** ui2 bunu bilerek yapmış
 *    (`SessionTabBar.kt` yorumu); ui3'te `item {}` olarak listenin içindeydi ve
 *    sekme çoğalınca ekranın dışına kayıyordu. Mockup ölçümünde üç sekmeyle
 *    "＋"nın 39dp dışarıda kaldığı görüldü — bu kodun aynası.
 *
 * Kaydırılan liste olduğu için çipler **cam görünümü** (blur yok, anayasa v2
 * §1.3): her sekmeye `hazeEffect` koymak her kaydırma karesinde blur'u yeniden
 * hesaplatırdı.
 *
 * Model ui2'den ithal (`SessionTabUi`, `activeTabScrollIndex`) — ui3 kendi
 * sekme modelini uydurmuyor. Kaydırma indeksi hesabının ui2'de birim testi var.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GlassTabBar(
    sekmeler: List<SessionTabUi>,
    aktifId: String,
    onSec: (String) -> Unit,
    onYeni: () -> Unit,
    onKapat: (String) -> Unit,
    onUzunBas: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listeDurumu = rememberLazyListState()
    val aktifIndeks = activeTabScrollIndex(sekmeler, aktifId)

    // Yalnız aktif sekme (ya da listedeki yeri) değişince kaydır — kullanıcının
    // elle yaptığı kaydırma korunur. ui2'deki effect'in aynısı.
    LaunchedEffect(aktifId, aktifIndeks) {
        if (aktifIndeks >= 0) listeDurumu.animateScrollToItem(aktifIndeks)
    }

    Row(
        modifier.fillMaxWidth().testTag("sekme_cubugu"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
    ) {
        LazyRow(
            // fill = false: az sekmede "＋" son çipin hemen sağına oturur;
            // sekmeler taşınca liste kendi içinde kayar ve "＋" sağ kenarda
            // sabit kalır. ui2'deki düzenin aynısı.
            modifier = Modifier.weight(1f, fill = false),
            state = listeDurumu,
            contentPadding = PaddingValues(horizontal = Ui3Tokens.s16),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(sekmeler, key = { it.id }) { sekme ->
                SekmeCipi(
                    sekme = sekme,
                    secili = sekme.id == aktifId,
                    onSec = { onSec(sekme.id) },
                    onKapat = { onKapat(sekme.id) },
                    onUzunBas = { onUzunBas(sekme.id) },
                )
            }
        }
        // "＋" GÖRÜNÜR yerde ve listenin dışında: yeni oturum açmak ui3'ün ilk
        // sürümünde hiç yoktu, ikincisinde sekme çoğalınca kayboluyordu.
        Box(
            Modifier
                .padding(end = Ui3Tokens.s16)
                .size(CIP_Y)
                .clip(CIP_YARICAP)
                .background(Ui3Colors.yuzey2)
                .border(1.dp, Ui3Colors.cizgi, CIP_YARICAP)
                .clickable(onClick = onYeni)
                .testTag("sekme_yeni"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Yeni sekme",
                tint = Ui3Colors.ink2,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SekmeCipi(
    sekme: SessionTabUi,
    secili: Boolean,
    onSec: () -> Unit,
    onKapat: () -> Unit,
    onUzunBas: () -> Unit,
) {
    GlassLikeSurface(
        modifier = Modifier
            .height(CIP_Y)
            .widthIn(max = 186.dp)
            .clip(CIP_YARICAP)
            .combinedClickable(onClick = onSec, onLongClick = onUzunBas)
            .testTag(if (secili) "sekme_aktif" else "sekme"),
        shape = CIP_YARICAP,
        // Seçili sekme tonlu: hangi oturumda olduğun tek bakışta görünsün.
        tint = if (secili) GlassTint.Vio else GlassTint.Notr,
    ) {
        // fillMaxHeight ŞART: GlassLikeSurface'in Box'unda hizalama yok, içerik
        // TopStart'a oturuyordu — çipin yazısı yukarı yapışıp altı boş kalıyordu
        // (kullanıcı "çerçeve yanlış çiziliyor" diye bildirdi; çerçeve doğruydu,
        // içerik yanlış yerdeydi).
        Row(
            Modifier.fillMaxHeight().padding(start = 5.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(sekme.backend, olcu = 19.dp)
            val durumRengi = when (sekme.status) {
                StatusKind.Running -> Ui3Colors.running
                StatusKind.Attention -> Ui3Colors.attention
                StatusKind.Done -> Ui3Colors.done
                StatusKind.Danger -> Ui3Colors.danger
                null -> null
            }
            if (durumRengi != null) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(durumRengi))
            }
            // Tek Text, iki parça: repo kimliği + canlı başlığın başı. ui2'nin
            // `buildAnnotatedString`'inin aynısı — ayraç metnin kendi boşluğuyla
            // veriliyor, `spacedBy` ile değil (ui2'de ölü bölge şikâyetiyle
            // sıkıştırılmıştı).
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = if (secili) Ui3Colors.ink else Ui3Colors.ink2)) {
                        append(sekme.repo)
                    }
                    if (sekme.liveTitle.isNotBlank()) {
                        withStyle(SpanStyle(color = Ui3Colors.ink3)) { append(" · ") }
                        withStyle(SpanStyle(color = Ui3Colors.ink2)) {
                            append(sekme.liveTitle.take(BASLIK_KIRPMA))
                        }
                    }
                },
                style = Ui3Type.alt,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Kapatma çarpısı çipin İÇİNDE: ui2'de de öyle. 24dp'lik hedef
            // 48dp kuralının altında ama çipin kendisi 38dp ve asıl hedef o —
            // çarpı yanlışlıkla basılmasın diye bilerek küçük.
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = onKapat)
                    .testTag("sekme_kapat"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Sekmeyi kapat",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * Sağlayıcı monogramı — kimlik göstergesi.
 *
 * Kimliği RENK taşımaz (anayasa v2 bölüm 2: renk yalnız durum): bu kutu nötr
 * beyaz saydamlıkta, harf ink2. Yanındaki durum noktasıyla karışmasın diye
 * kasten renksiz.
 */
@Composable
internal fun Monogram(
    backend: String,
    olcu: androidx.compose.ui.unit.Dp = 21.dp,
    metin: String = providerMonogram(backend),
) {
    Box(
        Modifier
            .size(olcu)
            .clip(RoundedCornerShape(7.dp))
            .background(Ui3Colors.cizgi),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            metin,
            fontSize = (olcu.value * 0.52f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = Ui3Colors.ink2,
        )
    }
}
