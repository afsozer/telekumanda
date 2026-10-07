package com.agent.bridge.ui2.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Tipografi rol ölçeği (anayasa 4.3): display / title / body / label.
// Sistem fontu; mono ayrı sabit (Ui2Mono). Kod/yol/komut HER ZAMAN mono.

val Ui2Mono = FontFamily.Monospace

val Ui2Typography = Typography(
    // display: büyük ekran başlıkları (Merkez başlığı gibi)
    displaySmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    // title: ekran/sheet/kart başlıkları
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    // body: akan metin (sohbet dahil)
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    // label: buton, rozet, bölüm etiketi
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    // labelSmall: BÖLÜM ETİKETİ deseni — büyük harf + harf aralığı ile kullanılır
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp),
)

// Köşe kararları (anayasa 4.4): kart 16, inline kart 12, sheet üst 24.
// Chip/buton tam yuvarlak — bileşenlerde RoundedCornerShape(999.dp) ile.
val Ui2Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
