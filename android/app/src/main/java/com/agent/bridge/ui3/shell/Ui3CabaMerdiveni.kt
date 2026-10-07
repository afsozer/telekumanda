package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Merdivenin çizilebilmesi için gereken en az basamak. Altında ölçek fikri
// anlamını yitiriyor (iki çubuk "ölçek" değil, iki düğmedir) — o durumda
// çağıran normal seçiciye düşüyor.
private const val EN_AZ_BASAMAK = 3
private val BASAMAK_ALANI = 46.dp

/**
 * Çaba seçici — hap listesi değil MERDİVEN (kullanıcı kararı, 22.08.2026).
 *
 * Neden ayrı bir bileşen: çaba SIRALI bir ölçek (low → ultra). Jenerik
 * [Ui3Selector] onu yedi eşit hap olarak çiziyordu; hap listesi "birbirinden
 * bağımsız seçenekler" der ve sıra bilgisini hiç göstermez. Üstelik yedi hap
 * ekranı tek başına dolduruyor, beşi görünüp ikisi kaydırma arkasında
 * kalıyordu. Merdiven aynı yedi seçeneği 46dp'ye sığdırıyor ve üç şeyi birden
 * söylüyor: neredesin, ölçek nereye kadar gidiyor, hangi yön daha pahalı.
 *
 * SIRA LİSTENİN KENDİ SIRASI. Sabit bir "low < medium < high" tablosu
 * yazılmadı: seviyeler sağlayıcıdan ve modelden geliyor (codex'te modele göre
 * değişiyor, opencode'da variant adları başka) ve uydurma bir sıralama yanlış
 * bir merdiven çizerdi. `backendEffortOptions` zaten artan sırada veriyor.
 *
 * [varsayilan] listenin id'si BOŞ olan ilk elemanı — bir basamak değil,
 * "sağlayıcı ne diyorsa o" demek. Bu yüzden merdivende yeri yok, altında ayrı
 * bir satır olarak duruyor.
 */
@Composable
internal fun ColumnScope.Ui3CabaMerdiveni(
    baslik: String,
    altBaslik: String?,
    varsayilan: SelectorOption<String>?,
    basamaklar: List<SelectorOption<String>>,
    seciliDeger: String?,
    onSec: (String) -> Unit,
    onGeri: (() -> Unit)? = null,
) {
    SheetBasligi(baslik, altBaslik, onGeri)

    val seciliIndeks = basamaklar.indexOfFirst { it.value == seciliDeger }
    val varsayilanSecili = seciliIndeks < 0

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s4)
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .background(Ui3Colors.yuzey1)
            .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r18))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("caba_merdiveni"),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Başlık satırı: şu an ne seçili. Merdivende hangi çubuğun yandığını
        // görmek yetmiyor — seçimin ADI da yazıyla dursun.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (varsayilanSecili) varsayilan?.label.orEmpty() else basamaklar[seciliIndeks].label,
                style = Ui3Type.govde,
                color = Ui3Colors.ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "${basamaklar.size} kademe",
                style = Ui3Type.etiket,
                color = Ui3Colors.ink3,
            )
        }

        // ÇUBUKLAR. Yükseklik indeksle artıyor: göz "sağa gitmek daha çok
        // şey demek" bilgisini okumadan alıyor.
        Row(
            Modifier.fillMaxWidth().height(BASAMAK_ALANI),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            basamaklar.forEachIndexed { i, secenek ->
                val oran = (i + 1).toFloat() / basamaklar.size
                val aktif = i == seciliIndeks
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(0.42f + 0.58f * oran)
                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                        .then(
                            if (aktif) {
                                Modifier.background(
                                    Brush.verticalGradient(listOf(Ui3Colors.vurguHi, Ui3Colors.vurgu)),
                                )
                            } else {
                                Modifier
                                    .background(Ui3Colors.yuzey2)
                                    .border(
                                        1.dp,
                                        Ui3Colors.cizgiInce,
                                        RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                                    )
                            },
                        )
                        .clickable { onSec(secenek.value) }
                        .testTag(if (aktif) "caba_basamak_secili" else "caba_basamak"),
                )
            }
        }

        // Etiketler çubuklarla AYNI ağırlık dağılımında: her etiket kendi
        // çubuğunun altında kalsın.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            basamaklar.forEachIndexed { i, secenek ->
                Text(
                    secenek.label,
                    style = Ui3Type.etiket.copy(fontFamily = Ui3Mono),
                    color = if (i == seciliIndeks) Ui3Colors.ink else Ui3Colors.ink3,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Seçili basamağın kendi açıklaması varsa O yazılır; yoksa ölçeğin
        // yönünü söyleyen sabit cümle. Uydurulmuş bir "maliyet" sayısı YOK —
        // sağlayıcı böyle bir veri vermiyor.
        val aciklama = basamaklar.getOrNull(seciliIndeks)?.detail
            ?: varsayilan?.detail
        Text(
            aciklama?.takeIf { it.isNotBlank() }
                ?: "Sağa gittikçe model daha çok düşünür; tur uzar ve daha çok jeton harcar.",
            style = Ui3Type.alt,
            color = Ui3Colors.ink2,
        )
    }

    // VARSAYILAN AYRI SATIR: bir kademe değil, "karar sağlayıcının" demek.
    if (varsayilan != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s4)
                .clip(RoundedCornerShape(Ui3Tokens.r12))
                .background(if (varsayilanSecili) Ui3Colors.vurgu.copy(alpha = 0.16f) else Ui3Colors.yuzey1)
                .border(
                    1.dp,
                    if (varsayilanSecili) Ui3Colors.vurguHi.copy(alpha = 0.5f) else Ui3Colors.cizgiInce,
                    RoundedCornerShape(Ui3Tokens.r12),
                )
                .clickable { onSec(varsayilan.value) }
                .padding(horizontal = 13.dp, vertical = 11.dp)
                .testTag("caba_varsayilan"),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                varsayilan.label,
                style = Ui3Type.alt,
                color = if (varsayilanSecili) Ui3Colors.ink else Ui3Colors.ink2,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (varsayilanSecili) {
                Text("seçili", style = Ui3Type.etiket, color = Ui3Colors.vurguHi)
            }
        }
    }
}

/**
 * Merdiven bu seçenek listesi için çizilebilir mi?
 *
 * Saf ve teste bağlı: `backendEffortOptions` sağlayıcıya göre bambaşka listeler
 * döndürüyor (codex'te modele göre değişiyor, agy'de hiç yok) ve merdiven ancak
 * gerçek bir ÖLÇEK varken doğru bileşen.
 */
internal fun ui3CabaMerdiveniUygun(basamakSayisi: Int): Boolean = basamakSayisi >= EN_AZ_BASAMAK
