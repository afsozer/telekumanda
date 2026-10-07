package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.components.SelectorOption
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Mono
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

private val LISTE_MAKS_Y = 400.dp

/**
 * Kurulu skill listesi — jenerik seçici DEĞİL (kullanıcı kararı, 22.08.2026).
 *
 * Ayrılmasının sebebi: burada seçilecek bir şey yok (model skill'i kendi
 * yüklüyor), bu bir KATALOG. 58 kayıt jenerik seçicinin satırlarıyla
 * çizildiğinde ekranda dördü görünüyor, açıklamalar üç satır sürüp kelimenin
 * ortasında kesiliyor ve aramanın olmaması listeyi fiilen kullanılmaz
 * kılıyordu.
 *
 * Üç değişiklik: arama, iki satır kırpma, ve adın önekinden ayrılması —
 * `browser:control-in-app-browser` yazarken önek soluk kalıyor, göz asıl ada
 * gidiyor. Ad MONO, çünkü okunacak bir cümle değil taranacak bir jeton
 * (anayasa v2 §4 ile aynı gerekçe).
 *
 * Grup rozeti UYDURULMUYOR: `backendSkillGroups`'tan geliyor, yoksa çizilmiyor.
 */
@Composable
internal fun ColumnScope.Ui3SkillListesi(
    baslik: String,
    altBaslik: String?,
    skiller: List<SelectorOption<String>>,
    bosMetin: String,
    onGeri: (() -> Unit)? = null,
) {
    SheetBasligi(baslik, altBaslik, onGeri)

    var sorgu by remember { mutableStateOf("") }
    // ignoreCase Kotlin'de karakter bazlı ve yerel ayardan bağımsız; tr-TR'nin
    // I/ı tuzağına düşmüyor (bkz. powershell-turkce-i notu, aynı sınıf hata).
    val suzulmus = remember(skiller, sorgu) {
        val q = sorgu.trim()
        if (q.isEmpty()) skiller
        else skiller.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.detail?.contains(q, ignoreCase = true) == true ||
                it.badge?.contains(q, ignoreCase = true) == true
        }
    }

    if (skiller.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s4)
                .clip(RoundedCornerShape(Ui3Tokens.r12))
                .background(Ui3Colors.kuyu)
                .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r12))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("skill_arama"),
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = Ui3Colors.ink3,
                modifier = Modifier.size(17.dp),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (sorgu.isEmpty()) {
                    Text("Skill ara…", style = Ui3Type.alt, color = Ui3Colors.ink3)
                }
                BasicTextField(
                    value = sorgu,
                    onValueChange = { sorgu = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(Ui3Type.alt.copy(color = Ui3Colors.ink)),
                    cursorBrush = SolidColor(Ui3Colors.vurguHi),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (sorgu.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Aramayı temizle",
                    tint = Ui3Colors.ink3,
                    modifier = Modifier
                        .size(17.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .testTag("skill_arama_temizle"),
                )
            }
        }
    }

    if (suzulmus.isEmpty()) {
        Text(
            if (skiller.isEmpty()) bosMetin else "\"$sorgu\" ile eşleşen skill yok.",
            style = Ui3Type.govde,
            color = Ui3Colors.ink2,
            modifier = Modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s16),
        )
        return
    }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = LISTE_MAKS_Y)
            .padding(horizontal = Ui3Tokens.s16, vertical = Ui3Tokens.s4)
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .background(Ui3Colors.yuzey1)
            .border(1.dp, Ui3Colors.cizgiInce, RoundedCornerShape(Ui3Tokens.r18))
            .testTag("skill_listesi"),
    ) {
        itemsIndexed(suzulmus, key = { _, s -> s.value }) { i, skill ->
            // Ayırıcı satırlar ARASINDA: her satıra ayrı çerçeve çizmek 58
            // kayıtta görsel gürültü olurdu.
            if (i > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(1.dp)
                        .background(Ui3Colors.cizgiInce),
                )
            }
            SkillSatiri(skill)
        }
    }
}

@Composable
private fun SkillSatiri(skill: SelectorOption<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("skill_satiri"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "browser:control-in-app-browser" → önek soluk, ad parlak.
            val onek = skill.label.substringBefore(':', "")
            val ad = if (onek.isEmpty()) skill.label else skill.label.substringAfter(':')
            Row(Modifier.weight(1f, fill = false)) {
                if (onek.isNotEmpty()) {
                    Text(
                        "$onek:",
                        style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                        color = Ui3Colors.ink3,
                        maxLines = 1,
                    )
                }
                Text(
                    ad,
                    style = Ui3Type.alt.copy(fontFamily = Ui3Mono),
                    color = Ui3Colors.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val rozet = skill.badge
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
        val aciklama = skill.detail
        if (!aciklama.isNullOrBlank()) {
            Text(
                aciklama,
                style = Ui3Type.alt,
                color = Ui3Colors.ink3,
                // İKİ SATIR: açıklama okunacak metin değil, tanımaya yarayan
                // ipucu. Üç satır ekranın yarısını yiyordu.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
