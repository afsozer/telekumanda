package com.agent.bridge.ui2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.ui2.theme.Ui2

// providerMonogram shared/BackendLabels.kt'ye taşındı (com.agent.bridge):
// ui3'ün sekme çipi ve oturum listesi de aynı monogramı çiziyor. Çağıranlar
// artık oradan import ediyor.

@Composable
fun ProviderMark(
    monogram: String,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    Box(
        modifier
            .size(size)
            .background(Ui2.colors.surface2, RoundedCornerShape(8.dp))
            .border(1.dp, Ui2.colors.line, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monogram,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = Ui2.colors.ink2,
        )
    }
}
