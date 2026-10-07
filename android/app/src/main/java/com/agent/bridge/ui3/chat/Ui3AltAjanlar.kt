package com.agent.bridge.ui3.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.OpencodeSubagent
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.material.GlassTint
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Kapalıyken kaç kart görünür. Kart yığını composer'ın üstünde duruyor ve
// oradaki her dp sohbetten çalınıyor; görev panosunun "bir durum çubuğu olmalı,
// bir ekran değil" kuralı burada da geçerli. 2: koşan bir ajan + biten sonuncu,
// yani "şu an ne oluyor" sorusunun en dar cevabı.
internal const val UI3_ALT_AJAN_GORUNEN = 2

// Uzun koşuda 20 kart olabiliyor (köprü 24'te kırpıyor); açık liste ekranı
// yutmasın.
private val LISTE_MAKS = 210.dp

/**
 * Kart yığını ÇİZİLİR Mİ?
 *
 * Tek kural: alt-ajan varsa. Görev panosundan AYRILDIĞI nokta burası — pano
 * "tur sürüyorsa boş da olsa göster" diyebiliyor çünkü doluluk çubuğu her
 * zaman anlamlı; alt-ajan kartının boş hâlinin söyleyeceği hiçbir şey yok
 * ("alt ajan yok" bilgi değil, gürültü). Kullanıcının kurulumunda `task` aracı
 * kapalı olabilir (opencode.jsonc'de tools.task=false) — o makinede bu yığın
 * hiç çizilmez ve çizilmemeli.
 */
internal fun altAjanlarGorunur(subagents: List<OpencodeSubagent>): Boolean = subagents.isNotEmpty()

/** Koşan ajan sayısı — başlık rozetinin sayacı. */
internal fun altAjanKosanSayisi(subagents: List<OpencodeSubagent>): Int = subagents.count { it.kosuyor }

/**
 * Başlığın tek satırlık özeti: "2 alt ajan · 1 koşuyor" / "3 alt ajan · bitti"
 * / hata varsa onu öne alır.
 *
 * Hata ÖNCELİKLİ: koşan varken bile bir alt ajan patladıysa kullanıcının bunu
 * kart açmadan görmesi gerekiyor — uzun otonom koşuda sessizce başarısız olan
 * bir delege, ana ajanın yanlış varsayımla devam etmesi demek.
 *
 * Compose'un DIŞINDA: bu satır kart yığınının bütün özeti ve cihazsız
 * sınanabilmesi gerekiyor (gorevPanosuOzeti ile aynı gerekçe).
 */
internal fun altAjanlarOzeti(subagents: List<OpencodeSubagent>): String {
    if (subagents.isEmpty()) return ""
    val adet = "${subagents.size} alt ajan"
    val hatali = subagents.count { it.hatali }
    if (hatali > 0) return "$adet · $hatali hata"
    val kosan = altAjanKosanSayisi(subagents)
    return if (kosan > 0) "$adet · $kosan koşuyor" else "$adet · bitti"
}

/**
 * Kart SIRASI: koşanlar üstte, kalanlar doğuş sırasının TERSİNDE.
 *
 * Kapalıyken yalnız ilk [UI3_ALT_AJAN_GORUNEN] kart görünüyor, yani bu sıralama
 * "hangisini göreceğim" kararının kendisi. Koşan öne alınmasa uzun koşuda liste
 * başındaki bitmiş ajanlar canlı olanı ekranın dışına iterdi; kalanların ters
 * sırası da "en son ne oldu" okuması.
 */
internal fun altAjanSirasi(subagents: List<OpencodeSubagent>): List<OpencodeSubagent> {
    val kosan = subagents.filter { it.kosuyor }
    val kalan = subagents.filterNot { it.kosuyor }.reversed()
    return kosan + kalan
}

/**
 * Alt-ajan kartları — "hangi subagent ne yapıyor", transkript kaydırmadan.
 *
 * Görev panosunun ALTINDA ve onun tasarım dilinde: blur'suz cam, aynı 18dp
 * köşe, aynı tek-satır başlık + genişleyen gövde. Ayrı bir yüzey olmasının
 * nedeni, panonun todo'larıyla alt-ajanların FARKLI şeyler olması — todo ana
 * ajanın kendi planı, buradakiler ayrı oturumlar; tek kutuya doldurmak ikisini
 * de okunmaz yapardı.
 *
 * HAREKET BÜTÇESİ: yalnız koşan ajanın noktası nabız atıyor (alfa 0.35 ↔ 1.0,
 * yön başına 1 sn = 2 sn'lik çevrim). Panonun "nabız/parıltı YOK" kuralından bilerek ayrıldı: pano gün boyu
 * ekranda duran bir sayaç, bu yığın ise yalnız alt-ajan varken çiziliyor ve
 * tam olarak "canlı mı" sorusunu cevaplamak için var. Biten ve hatalı kartın
 * noktası SABİT — hareket, koşuyor demek.
 *
 * Karta dokunmak çocuğun konuşmasını salt-okunur bir sheet'te açar ([onAc]).
 */
@Composable
internal fun Ui3AltAjanlar(
    subagents: List<OpencodeSubagent>,
    onAc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!altAjanlarGorunur(subagents)) return

    var acik by remember { mutableStateOf(false) }
    val sirali = altAjanSirasi(subagents)
    val gizli = (sirali.size - UI3_ALT_AJAN_GORUNEN).coerceAtLeast(0)
    val gorunen = if (acik) sirali else sirali.take(UI3_ALT_AJAN_GORUNEN)
    val hataVar = subagents.any { it.hatali }

    GlassLikeSurface(
        modifier = modifier.fillMaxWidth().testTag("alt_ajanlar"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
        tint = if (hataVar) GlassTint.Amber else GlassTint.Notr,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Gizlenecek kart yoksa başlık bir tuş değil, bir etiket.
                    .clickable(enabled = gizli > 0) { acik = !acik }
                    .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            ) {
                Text(
                    altAjanlarOzeti(subagents),
                    modifier = Modifier.weight(1f).testTag("alt_ajan_ozet"),
                    style = Ui3Type.rozet,
                    color = if (hataVar) Ui3Colors.amber else Ui3Colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (gizli > 0) {
                    Text(
                        if (acik) "gizle" else "+$gizli",
                        modifier = Modifier.testTag("alt_ajan_ac"),
                        style = Ui3Type.rozet,
                        color = Ui3Colors.ink3,
                    )
                }
            }

            // Kapalı liste sabit yükseklikte (en fazla iki satır), açık liste
            // kaydırılabilir — 24 karta kadar çıkabiliyor.
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (acik) Modifier.heightIn(max = LISTE_MAKS).verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = Ui3Tokens.s8, end = Ui3Tokens.s8, bottom = Ui3Tokens.s8),
                verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
            ) {
                for (ajan in gorunen) {
                    AltAjanKarti(ajan) { onAc(ajan.id) }
                }
            }
        }
    }
}

/**
 * Nabız atan durum noktası. AYRI bileşen: `rememberInfiniteTransition` çağrıldığı
 * yerde koşulsuz çalışır, yani duran kartların içinde de kare harcardı. Sonsuz
 * animasyon yalnız koşan ajanın kartında doğsun diye ayrıldı.
 */
@Composable
private fun NabizNoktasi(renk: androidx.compose.ui.graphics.Color) {
    val nabiz = rememberInfiniteTransition(label = "alt-ajan-nabiz")
    val alfa by nabiz.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alt-ajan-nokta",
    )
    Box(
        Modifier.padding(top = 4.dp).size(7.dp).clip(CircleShape).background(renk.copy(alpha = alfa)),
    )
}

/**
 * Tek kart: durum noktası + başlık + son satır.
 *
 * Son satır YOKSA satır hiç çizilmiyor (boş bir gri şerit yerine kart kısalıyor)
 * — ajan daha ilk kelimesini üretmemişken kartın "boş cevap verdi" izlenimi
 * bırakmaması için.
 */
@Composable
private fun AltAjanKarti(ajan: OpencodeSubagent, onTikla: () -> Unit) {
    val renk = when {
        ajan.hatali -> Ui3Colors.danger
        ajan.kosuyor -> Ui3Colors.running
        else -> Ui3Colors.done
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .clickable(onClick = onTikla)
            .testTag("alt_ajan_kart")
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
    ) {
        if (ajan.kosuyor) NabizNoktasi(renk) else Box(
            Modifier.padding(top = 4.dp).size(7.dp).clip(CircleShape).background(renk),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                ajan.title,
                style = Ui3Type.alt,
                color = Ui3Colors.ink,
                fontWeight = if (ajan.kosuyor) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ajan.lastText.isNotBlank()) {
                Text(
                    ajan.lastText,
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
