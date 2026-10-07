package com.agent.bridge.ui3.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * Büyük başlık — anayasa v2 bölüm 4 ve 6.
 *
 * Seçici DEĞİL: oturum seçme işi sekme çubuğuna ve Oturumlar dock öğesine ait
 * (ui2'nin mimarisi). Yalnız SOHBET DIŞI ekranlarda çizilir — sohbette dikey
 * yer okuma alanıdır, 34sp başlık orayı yer.
 *
 * Altındaki durum satırı KALKTI: turun canlı durumu (çalışıyor/boşta, süre,
 * %bağlam) artık tek yerde, [AppIsland]'da. Aynı bilgiyi iki yerde göstermek
 * hem yer yiyor hem de ikisi bir an için farklı görünebiliyordu.
 */
@Composable
internal fun BigTitle(baslik: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Ui3Tokens.s20, vertical = Ui3Tokens.s8)) {
        Text(
            text = baslik,
            style = Ui3Type.baslik,
            color = Ui3Colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("baslik"),
        )
    }
}
