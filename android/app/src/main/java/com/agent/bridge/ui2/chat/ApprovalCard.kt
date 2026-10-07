package com.agent.bridge.ui2.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Mono
import com.agent.bridge.ui2.theme.Ui2Tokens

// Onay kartı — uygulamanın kalbi (Mockup v1 ekran 2, onaylı).
// Kehribar tonlu yüzey; composer'ı KİLİTLEMEZ — kart tur akışında inline durur.
// Aynı kehribar dili: sekme rozeti, durum pill'i, bildirim (anayasa 6).
@Composable
fun ApprovalCard(
    title: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "Onay bekliyor",
    command: String? = null,
    cwd: String? = null,
    primaryLabel: String = "İzin ver",
    secondaryLabel: String = "Reddet",
    primaryEnabled: Boolean = true,
    alwaysLabel: String? = null,
    onAlways: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val attn = Ui2.colors.attention
    val shape = RoundedCornerShape(Ui2Tokens.cornerInline)
    Column(
        modifier
            .fillMaxWidth()
            .background(attn.copy(alpha = 0.09f), shape)
            .border(1.dp, attn.copy(alpha = 0.4f), shape)
            .padding(horizontal = 13.dp, vertical = Ui2Tokens.s12),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Etiket: nokta + büyük harf
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).background(attn, CircleShape))
            Text(
                tag.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = attn,
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
        if (command != null) {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Ui2Mono),
                color = Ui2.colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ui2.colors.codeBg, RoundedCornerShape(8.dp))
                    .border(1.dp, Ui2.colors.line, RoundedCornerShape(8.dp))
                    .padding(horizontal = 11.dp, vertical = Ui2Tokens.s8)
                    .horizontalScroll(rememberScrollState()),
            )
        }
        if (cwd != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s4)) {
                Text("proje klasörü:", style = MaterialTheme.typography.bodySmall, color = Ui2.colors.ink3)
                Text(
                    cwd,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Ui2Mono, fontSize = 11.sp),
                    color = Ui2.colors.ink3,
                )
            }
        }
        if (extra != null) extra()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Ui2Tokens.s8),
        ) {
            PillButton(secondaryLabel, onClick = onSecondary)
            PillButton(primaryLabel, onClick = onPrimary, filled = true, enabled = primaryEnabled)
            if (alwaysLabel != null && onAlways != null) {
                Text(
                    alwaysLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ui2.colors.ink3,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onAlways),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

// Kullanıcı-girdisi kartı: ajan soru soruyor, seçenekler tam genişlik pill.
// Onay kartıyla aynı kehribar dili — ikisi de "girdi bekliyor" durumudur.
// Seçenek: etiket + (varsa) açıklama metni (AskUserQuestion option.description).
data class UserInputOption(val label: String, val description: String = "")

@Composable
fun UserInputCard(
    title: String,
    options: List<UserInputOption>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "Girdi bekliyor",
    selectedIndices: Set<Int> = emptySet(),
    // Ajan bu soruda birden fazla seçeneğe izin veriyor. Seçim davranışı
    // çağıranda (QuestionAnswerDraft) çözülür; kart yalnız ipucunu gösterir —
    // çoklu seçimde "neden ikincisi de kalıyor" sorusu ekranda cevaplanmalı.
    multiple: Boolean = false,
) {
    val attn = Ui2.colors.attention
    val shape = RoundedCornerShape(Ui2Tokens.cornerInline)
    Column(
        modifier
            .fillMaxWidth()
            .background(attn.copy(alpha = 0.09f), shape)
            .border(1.dp, attn.copy(alpha = 0.4f), shape)
            .padding(horizontal = 13.dp, vertical = Ui2Tokens.s12),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).background(attn, CircleShape))
            Text(tag.uppercase(), style = MaterialTheme.typography.labelSmall, color = attn)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ui2.colors.ink)
        if (multiple) {
            Text(
                "Birden fazla seçebilirsin; seçiliye tekrar dokunmak kaldırır.",
                style = MaterialTheme.typography.bodySmall,
                color = Ui2.colors.ink3,
            )
        }
        options.forEachIndexed { index, option ->
            val selected = index in selectedIndices
            // Açıklamalı seçenek çok satıra büyür: pill stadyuma dönüşüp metni
            // köşeden taşırmasın diye field köşesi (anayasa: Ui2Tokens.field notu).
            val optionShape = if (option.description.isBlank()) Ui2Tokens.pill else Ui2Tokens.field
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) attn.copy(alpha = 0.18f) else Ui2.colors.surface, optionShape)
                    .border(1.dp, if (selected) attn else Ui2.colors.line, optionShape)
                    .clickable { onSelect(index) }
                    .padding(horizontal = Ui2Tokens.s16, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = Ui2.colors.ink,
                )
                if (option.description.isNotBlank()) {
                    Text(
                        option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink2,
                    )
                }
            }
        }
    }
}

@Composable
fun QuestionSubmitCard(
    answered: Int,
    total: Int,
    onSubmit: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val complete = total > 0 && answered == total
    ApprovalCard(
        title = if (complete) "Tüm sorular cevaplandı" else "$answered/$total soru cevaplandı",
        command = if (complete) null else "Göndermeden önce kalan soruları da cevapla.",
        primaryLabel = "Cevapları gönder",
        secondaryLabel = "Reddet",
        primaryEnabled = complete,
        onPrimary = onSubmit,
        onSecondary = onReject,
        modifier = modifier,
        tag = "Yanıtlar",
    )
}

// Kehribar aksiyon pili: dolu = birincil (İzin ver), çizgili = ikincil (Reddet).
@Composable
private fun PillButton(label: String, onClick: () -> Unit, filled: Boolean = false, enabled: Boolean = true) {
    val attn = Ui2.colors.attention
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = when {
            !enabled -> Ui2.colors.ink3
            filled -> Color(0xFF241A05)
            else -> Ui2.colors.ink2
        },
        modifier = Modifier
            .background(if (filled && enabled) attn else Color.Transparent, Ui2Tokens.pill)
            .border(1.dp, if (filled && enabled) Color.Transparent else Ui2.colors.line, Ui2Tokens.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = Ui2Tokens.s8),
    )
}
