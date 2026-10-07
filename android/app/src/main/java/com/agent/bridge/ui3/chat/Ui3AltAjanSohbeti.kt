package com.agent.bridge.ui3.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.bridge.OpencodeSubagentLine
import com.agent.bridge.OpencodeSubagentTranscript
import com.agent.bridge.ui3.shell.SheetBasligi
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

// Ui3Degisiklikler ile aynı sınır: sheet arkadaki sohbeti tamamen örtmesin.
private val SOHBET_MAKS = 400.dp

/** Başlık altı: "general · 3 tur" — hangi ajan, ne kadar konuşmuş. */
internal fun altAjanSohbetOzeti(t: OpencodeSubagentTranscript?): String? {
    if (t == null) return null
    val parcalar = listOfNotNull(
        t.agent.takeIf { it.isNotBlank() },
        t.messages.count { it.role == "user" || it.role == "agent" }
            .takeIf { it > 0 }?.let { "$it satır" },
    )
    return parcalar.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * ALT AJAN KONUŞMASI — kart yığınındaki bir karta dokununca açılan salt-okunur
 * görünüm.
 *
 * TEK KADEMELİ, bilerek: [Ui3Degisiklikler] iki kademeli çünkü seçim sheet'in
 * İÇİNDE yapılıyor (liste → dosya). Burada seçim sheet'ten ÖNCE, kart yığınında
 * yapıldı; ikinci bir liste kademesi eklemek aynı seçimi iki kez sormak olurdu.
 * Bu yüzden yerel `remember` durumu da yok — hangi ajanın açık olduğunu state
 * taşıyor ve "yeniden açılışta sıfırla" tuzağı (o dosyada `LaunchedEffect`
 * gerektiren şey) burada hiç doğmuyor.
 *
 * SALT OKUNUR: gönderme, durdurma, geri sarma YOK. Çocuk oturum ana turun bir
 * aracı; ona dışarıdan müdahale etmenin köprüde de karşılığı yok (uç yalnız
 * okuyor) ve olsaydı ana ajanın beklediği sonucu bozardı.
 */
@Composable
internal fun ColumnScope.Ui3AltAjanSohbeti(
    transcript: OpencodeSubagentTranscript?,
    yukleniyor: Boolean,
    onGeri: (() -> Unit)?,
) {
    SheetBasligi(
        transcript?.title?.uppercase() ?: "ALT AJAN",
        altAjanSohbetOzeti(transcript),
        onGeri,
    )

    // Elde hiçbir şey yokken çark; tazelemede eski metin korunur (Değişiklikler
    // ile aynı kural — boşaltmak "hiç konuşmadı" yalanına dönüşürdü).
    if (yukleniyor && transcript == null) {
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
            Text("Konuşma okunuyor…", style = Ui3Type.govde, color = Ui3Colors.ink2)
        }
        return
    }

    val satirlar = transcript?.messages.orEmpty()
    if (satirlar.isEmpty()) {
        Text(
            if (transcript == null) "Konuşma alınamadı." else "Bu alt ajan henüz konuşmadı.",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier
                .padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16)
                .testTag("alt_ajan_bos"),
        )
        return
    }

    if (transcript?.truncated == true) {
        Text(
            "Liste kırpıldı — yalnız son satırlar gösteriliyor.",
            style = Ui3Type.alt,
            color = Ui3Colors.attention,
            modifier = Modifier.padding(start = Ui3Tokens.s20, end = Ui3Tokens.s20, bottom = Ui3Tokens.s8),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = SOHBET_MAKS).testTag("alt_ajan_sohbet"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Ui3Tokens.s20,
            vertical = Ui3Tokens.s4,
        ),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        // Anahtar indeks: köprü aynı metni iki kez döndürebiliyor (aynı aracın
        // iki çağrısı), kimlik taşımıyor.
        itemsIndexed(satirlar) { _, satir -> SohbetSatiri(satir) }
    }
}

/**
 * Bir satır. Ana sohbetin balon dili KULLANILMADI: burası bir inceleme
 * görünümü, konuşulan yer değil — rol tek satırlık bir etiketle veriliyor ve
 * metin tam genişlikte akıyor, böylece uzun araç çıktısı dar bir balonda
 * ezilmiyor.
 */
@Composable
private fun SohbetSatiri(satir: OpencodeSubagentLine) {
    val (etiket, renk) = when (satir.role) {
        "user" -> "GÖREV" to Ui3Colors.ink3
        "thought" -> "ARAÇ" to Ui3Colors.ink3
        else -> "CEVAP" to Ui3Colors.vurguHi
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(etiket, style = Ui3Type.rozet, color = renk)
        Text(
            satir.text,
            style = if (satir.role == "thought") Ui3Type.alt else Ui3Type.govde,
            color = if (satir.role == "thought") Ui3Colors.ink3 else Ui3Colors.ink,
            fontWeight = FontWeight.Normal,
        )
    }
}
