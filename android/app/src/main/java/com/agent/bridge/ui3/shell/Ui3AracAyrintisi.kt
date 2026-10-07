package com.agent.bridge.ui3.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.bridge.ChatMessage
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * Araç ayrıntısı — akıştaki "Araç · N adım" çipine dokununca açılan sheet.
 *
 * Önceki sürümde çip yalnız sayıyı yazıyor, içine bakılamıyordu; ui2'nin
 * `ToolCallCard`'ı yerinde genişleyip komutu gösterdiği için bu bir
 * regresyondu. Sheet seçildi (kullanıcı kararı): uzun çıktı sohbeti şişirmiyor
 * ve akış hiç sıçramıyor.
 *
 * ADIMLAR KENDİ İÇİNDE DE AÇILIR (22.08.2026, kullanıcı bildirdi: "ui2'de
 * genişletebiliyordum, ui3'te olmuyor"). Sheet açıldığında görünen metin
 * `ChatMessage.text` — bu yalnızca ÖZET; düşüncenin/araç çıktısının tamamı
 * köprüden ayrıca çekiliyor (`loadThought`) ve `thoughtDetails` haritasında
 * duruyor. Sheet bu haritayı hiç almadığı için tek satırlık özette
 * kalınıyordu. Artık ui2'nin davranışının aynısı: adıma dokun → detay ilk
 * dokunuşta yüklenir, altında açılır.
 *
 * Komut/çıktı HER ZAMAN mono + yüzey kutusunda (anayasa v2 bölüm 4).
 */
@Composable
internal fun ColumnScope.Ui3AracAyrintisi(
    mesajlar: List<ChatMessage>,
    indeksler: List<Int>,
    dusunceMi: Boolean,
    dusunceDetaylari: Map<Int, String>,
    onDusunceYukle: (Int) -> Unit,
) {
    // Düşünce grubunu "ARAÇ" diye başlıklandırmak yanlış bilgi; ayrımı
    // gruplamayı yapan `GroupKind` veriyor, metinden tahmin edilmiyor.
    val baslik = if (dusunceMi) "DÜŞÜNCE" else "ARAÇ"
    SheetBasligi("$baslik · ${indeksler.size} ADIM", null)
    if (indeksler.isEmpty()) {
        Text(
            "Adım bulunamadı.",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s12),
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s16),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
    ) {
        indeksler.forEachIndexed { sira, indeks ->
            val mesaj = mesajlar.getOrNull(indeks) ?: return@forEachIndexed
            AracAdimi(
                sira = sira + 1,
                mesaj = mesaj,
                detay = dusunceDetaylari[mesaj.thoughtIndex],
                onYukle = { onDusunceYukle(mesaj.thoughtIndex) },
            )
        }
    }
}

@Composable
private fun AracAdimi(
    sira: Int,
    mesaj: ChatMessage,
    detay: String?,
    onYukle: () -> Unit,
) {
    // Detay indeksi olmayan adımın (eski geçmiş, düz metin satırı) açılacak
    // bir gövdesi yok; ona tıklama hedefi de vermiyoruz ki boş kart açılmasın.
    val genisletilebilir = mesaj.thoughtIndex >= 0
    var acik by rememberSaveable(mesaj.rowId.ifEmpty { "adim_$sira" }) { mutableStateOf(false) }

    GlassLikeSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .then(
                if (genisletilebilir) {
                    Modifier.clickable {
                        acik = !acik
                        // İlk açılışta çek; loadThought zaten yüklü/yükleniyor olanı eler.
                        if (acik) onYukle()
                    }
                } else {
                    Modifier
                },
            )
            .testTag("arac_adimi"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(Ui3Colors.done),
                )
                // `ChatMessage` araç ADI taşımıyor (alanlar: role, text, time,
                // thoughtIndex, rowId) — uydurma başlık yazmak yerine sıra
                // numarası ve zaman gösteriliyor. Araç adı gerekiyorsa motor
                // katmanına alan eklenmeli, UI'da tahmin edilmemeli.
                Text(
                    "Adım $sira",
                    style = Ui3Type.alt,
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                )
                if (mesaj.time.isNotBlank()) {
                    Text(mesaj.time, style = Ui3Type.etiket, color = Ui3Colors.ink3)
                }
                if (genisletilebilir) {
                    Box(Modifier.weight(1f))
                    Icon(
                        if (acik) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = if (acik) "Adımı kapat" else "Adımı aç",
                        tint = Ui3Colors.ink3,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            val govde = mesaj.text.trim()
            if (govde.isNotEmpty()) {
                MonoKutu(govde, Ui3Colors.ink2)
            }
            // Tam metin: özetin ALTINDA, onun yerine değil — özet başlık işlevi
            // görüyor ve kapatınca nerede olduğun kayboluyor.
            AnimatedVisibility(visible = acik) {
                // Haritada anahtar VARSA ama değer boşsa istek başarısız olmuş
                // ya da backend bu ucu desteklemiyor demektir (loadThought hata
                // yolunda "" yazıyor). "Yükleniyor…"da bırakmak yanlış bilgi.
                when {
                    detay == null -> MonoKutu("Yükleniyor…", Ui3Colors.ink3)
                    detay.isBlank() -> MonoKutu("Bu adımın ayrıntısı alınamadı.", Ui3Colors.ink3)
                    else -> MonoKutu(detay, Ui3Colors.ink)
                }
            }
        }
    }
}

@Composable
private fun MonoKutu(metin: String, renk: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r12))
            .background(Ui3Colors.kuyu)
            .border(1.dp, Ui3Colors.yuzey2, RoundedCornerShape(Ui3Tokens.r12))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(
            metin,
            style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
            color = renk,
        )
    }
}
