package com.agent.bridge.ui3.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.agent.bridge.OpencodeCheckpoint
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.shell.Ui3Onayla
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Sheet'in tamamını kaplamasın: arkadaki sohbet görünür kalsın (Ui3Degisiklikler
// ve Ui3Selector'daki LISTE_MAKS ile aynı ölçü, aynı gerekçe).
private val LISTE_MAKS = 400.dp

/**
 * GERİ SAR — uzun otonom koşu yanlış yöne saptığında "şu mesaja dön".
 *
 * İKİ KADEMELİ, AYNI SHEET İÇİNDE: liste → noktaya dokun → onay. Ayrı bir sheet
 * kimliği açılmadı çünkü kök tek yuvalı (`acikSheet`) ve ikinci kimlik "geri"
 * davranışını kökün geri hedefiyle karıştırırdı; burada geri oku yalnızca yerel
 * durumu sıfırlıyor. Aynı desen [Ui3Degisiklikler]'de de var.
 *
 * ONAY ZORUNLU, bilerek: bu eylem konuşmayı kesmekle kalmıyor, DOSYALARI da
 * geri sarabiliyor — yanlış satıra dokunmak saatlerce süren bir koşunun
 * çıktısını siler. Onay metni "dosyalar da geri sarılır" diyor ve nokta metnini
 * tekrar gösteriyor ki kullanıcı neyi seçtiğini onay ekranında da görsün.
 *
 * VERİ AKIŞI: liste açılırken bir kez çekiliyor (bkz. `acikSheetAc`) — diff ile
 * aynı gerekçe, her istek köprüye oturumun bütün mesaj listesini okutuyor.
 */
@Composable
internal fun ColumnScope.Ui3GeriSar(
    noktalar: List<OpencodeCheckpoint>,
    yukleniyor: Boolean,
    calisiyor: Boolean,
    onGeriSar: (String) -> Unit,
    onGeri: (() -> Unit)?,
) {
    var secili by remember { mutableStateOf<OpencodeCheckpoint?>(null) }

    // SHEET KAPANINCA BİLEŞEN YAŞAMAYA DEVAM EDİYOR: kök çıkış animasyonu
    // boyunca son içeriği koruyor, yani `remember` sıfırlanmıyor. Onay ekranında
    // sheet'i kapatan kullanıcı "Geri sar"ı yeniden açtığında liste yerine o
    // onayı bulurdu — hem kafa karıştırıcı hem tehlikeli. Yükleme bayrağının
    // yükselen kenarı "yeniden açıldı" demek (Ui3Degisiklikler'deki aynı çözüm).
    LaunchedEffect(yukleniyor) { if (yukleniyor) secili = null }

    val hedef = secili
    if (hedef != null) {
        SheetBasligi("BURAYA GERİ SAR", "${hedef.turn}. tur") { secili = null }
        Text(
            hedef.text.ifBlank { "(boş mesaj)" },
            style = Ui3Type.alt,
            color = Ui3Colors.ink2,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s8),
        )
        Ui3Onayla(
            baslik = "Bu noktaya geri sarılsın mı?",
            // Kesin konuşmuyor ("sarılır" değil "sarılabilir"): opencode anlık
            // görüntüyü yalnız git deposunda ve kendi düzenleme araçlarıyla
            // tutuyor — köprüde ölçüldü. Sonucun gerçeği işlem BİTİNCE
            // bildiriliyor (bkz. OpencodeAppActionsDelegate.revertTo).
            aciklama = "${hedef.turn}. tur: ${hedef.text.ifBlank { "(boş mesaj)" }}\n\n" +
                "Bu mesajdan sonraki konuşma geri alınır ve ajanın " +
                "değiştirdiği DOSYALAR da o ana geri sarılabilir. " +
                "Yeni bir mesaj gönderene kadar “Geri Al” ile vazgeçebilirsin.",
            onayMetni = "Geri sar",
            yikici = true,
            onOnay = { onGeriSar(hedef.messageID); secili = null },
            onVazgec = { secili = null },
        )
        return
    }

    SheetBasligi("GERİ SAR", noktalar.size.takeIf { it > 0 }?.let { "$it tur" }, onGeri)

    // Tur sürerken köprü zaten reddediyor; satırları tıklanır bırakıp hata
    // bastırmak yerine sebebini burada söylüyoruz.
    if (calisiyor) {
        Text(
            "Tur sürerken geri sarılamaz — önce durdur ya da bitmesini bekle.",
            style = Ui3Type.govde,
            color = Ui3Colors.attention,
            modifier = Modifier
                .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16)
                .testTag("geri_sar_calisiyor"),
        )
        return
    }

    // YÜKLENİRKEN ESKİ LİSTE KORUNUR: tazeleme sırasında boşaltmak "geri
    // sarılacak nokta yok" boş durumunu bir an yanlışlıkla gösterirdi.
    if (yukleniyor && noktalar.isEmpty()) {
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
            Text("Noktalar okunuyor…", style = Ui3Type.govde, color = Ui3Colors.ink2)
        }
        return
    }

    if (noktalar.isEmpty()) {
        Text(
            "Bu oturumda geri sarılacak nokta yok.",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier
                .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16)
                .testTag("geri_sar_bos"),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = LISTE_MAKS).testTag("geri_sar_liste"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Ui3Tokens.s12,
            vertical = Ui3Tokens.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // EN YENİ ÜSTTE: geri sarma neredeyse her zaman son birkaç tura
        // yapılıyor; kronolojik sırada kullanıcı her seferinde dibe kaydırırdı.
        items(noktalar.reversed(), key = { it.messageID }) { nokta ->
            NoktaSatiri(nokta) { secili = nokta }
        }
    }
}

/**
 * "Geri sarıldı · Geri Al" — sohbetin altındaki yığında duran ince şerit.
 *
 * NEDEN ŞERİT: geri sarma sahnelenmiş bir durum. Yeni bir mesaj gönderilene
 * kadar geri alınabiliyor, gönderildiği anda kalıcı oluyor (köprüde ölçüldü) —
 * yani "vazgeçme penceresi" açıkken kullanıcının bunu GÖRMESİ gerek. Şerit
 * kendiliğinden kayboluyor: köprü yeni turda `reverted`ı null'a çekiyor.
 *
 * Dosya bilgisi UYDURULMUYOR: köprü kaç dosyanın sarıldığını ölçüp gönderiyor,
 * sarılmadıysa şerit "yalnız konuşma" diyor.
 */
@Composable
internal fun Ui3GeriSarSeridi(
    durum: com.agent.bridge.OpencodeRevertState,
    onGeriAl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassLikeSurface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .testTag("geri_sar_serit"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Row(
            Modifier.padding(start = Ui3Tokens.s12, end = Ui3Tokens.s4, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("Geri sarıldı")
                    if (durum.filesReverted && durum.files > 0) append(" · ${durum.files} dosya")
                    else if (!durum.filesReverted) append(" · yalnız konuşma")
                },
                style = Ui3Type.alt,
                color = Ui3Colors.attention,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(Ui3Tokens.pill)
                    .clickable(onClick = onGeriAl)
                    .padding(horizontal = Ui3Tokens.s12, vertical = 6.dp)
                    .testTag("geri_sar_geri_al"),
                contentAlignment = Alignment.Center,
            ) {
                Text("Geri Al", style = Ui3Type.alt, color = Ui3Colors.vurguHi, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NoktaSatiri(nokta: OpencodeCheckpoint, onTikla: () -> Unit) {
    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .clickable(onClick = onTikla)
            .testTag("geri_sar_nokta"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Row(
            Modifier.padding(horizontal = Ui3Tokens.s12, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tur numarası: iki mesaj aynı metinle başlıyorsa ayırt edici olan bu.
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Ui3Colors.yuzey2),
                contentAlignment = Alignment.Center,
            ) {
                Text("${nokta.turn}", style = Ui3Type.rozet, color = Ui3Colors.ink2)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    nokta.text.ifBlank { "(boş mesaj)" },
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
