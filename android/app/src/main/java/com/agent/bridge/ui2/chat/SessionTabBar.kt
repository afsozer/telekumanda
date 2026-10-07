package com.agent.bridge.ui2.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui2.components.ProviderMark
import com.agent.bridge.ui2.components.StatusDot
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

// Oturum sekmesi çipi: backend monogramı + durum noktası + repo adı
// + (varsa) canlı başlığın ilk kısmı + ✕.
// Renk yalnız durumu anlatır (mavi çalışıyor / kehribar onay / yeşil bitti-görülmedi);
// kimlik monogramla. ✕ tıklanınca onay ChatRootScreen'de istenir (her zaman).
data class SessionTabUi(
    val id: String,
    val backend: String,
    val repo: String,
    val liveTitle: String = "",
    val status: StatusKind? = null,
)

internal fun activeTabScrollIndex(tabs: List<SessionTabUi>, activeId: String): Int =
    tabs.indexOfFirst { it.id == activeId }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionTabBar(
    tabs: List<SessionTabUi>,
    activeId: String,
    onSelect: (String) -> Unit,
    onNewTab: () -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val activeIndex = activeTabScrollIndex(tabs, activeId)

    // Yalnız aktif sekme (veya listedeki yeri) değişince kaydır. Kullanıcının
    // aynı sekmedeyken yaptığı elle kaydırma yeni bir effect başlatmadığı için
    // korunur; başka sekmeye geçince yeni aktif sekme yeniden görünür yapılır.
    LaunchedEffect(activeId, activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    // ＋ LazyRow'un DIŞINDA: sekmeler çoğalıp kaydırılsa da hep görünür kalır.
    // weight(fill=false) sayesinde az sekmede ＋ son sekmenin hemen sağına oturur,
    // sekmeler taşınca sağ kenara sabitlenir.
    Row(
        modifier.fillMaxWidth().padding(start = Ui2Tokens.s16, end = Ui2Tokens.s16, top = Ui2Tokens.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LazyRow(
            Modifier.weight(1f, fill = false),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                val on = tab.id == activeId
                // Aktif sekme vurgusu: surface2 + lineStrong ikilisi iki modda da
                // pasiflerden ayırt edilemiyordu (canlı şikâyet). Marka menekşesinin
                // yarı saydam zemini + tam renk kenarı kullanılır — accent her iki
                // temada da (ve Material You'da scheme.primary olarak) tanımlı
                // olduğu için palet dışına çıkmadan net kontrast verir.
                Row(
                    Modifier
                        .background(
                            if (on) Ui2.colors.accent.copy(alpha = 0.18f) else Ui2.colors.surface,
                            Ui2Tokens.pill,
                        )
                        .border(
                            if (on) 1.5.dp else 1.dp,
                            if (on) Ui2.colors.accent else Ui2.colors.line,
                            Ui2Tokens.pill,
                        )
                        .combinedClickable(
                            onClick = { onSelect(tab.id) },
                            onLongClick = onLongPress?.let { cb -> { cb(tab.id) } },
                        )
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProviderMark(providerMonogram(tab.backend), size = 20.dp)
                    if (tab.status != null) StatusDot(tab.status)
                    // Tek Text: repo · başlık aralıkları spacedBy yerine metnin kendi
                    // boşluğuyla (ölü bölge şikayetiyle 11.10'da sıkılaştırıldı).
                    // liveTitle ilk prompt sonrası kilitlenir (TabsDelegate).
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = if (on) Ui2.colors.ink else Ui2.colors.ink2)) {
                                append(tab.repo)
                            }
                            if (tab.liveTitle.isNotBlank()) {
                                withStyle(SpanStyle(color = Ui2.colors.ink3)) { append(" · ") }
                                withStyle(SpanStyle(color = Ui2.colors.ink2)) { append(tab.liveTitle.take(12)) }
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Kapatma: her zaman onay istenir (ChatRootScreen ConfirmDialog).
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Sekmeyi kapat",
                        tint = Ui2.colors.ink3,
                        modifier = Modifier
                            .size(18.dp)
                            .combinedClickable(onClick = { onClose(tab.id) }),
                    )
                }
            }
        }
        Text(
            "＋",
            style = MaterialTheme.typography.labelLarge,
            color = Ui2.colors.ink3,
            modifier = Modifier
                .background(Ui2.colors.surface, Ui2Tokens.pill)
                .border(1.dp, Ui2.colors.line, Ui2Tokens.pill)
                .combinedClickable(onClick = onNewTab)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}
