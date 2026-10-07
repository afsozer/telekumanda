package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.RemoteUiState
import com.agent.bridge.visibleTabs
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/** Lite'ın tek-sohbet kabuğundaki dört anlamlı durum. */
internal enum class LiteBaslikDurumu(val etiket: String) {
    Duruyor("Duruyor"),
    CevapBekliyor("Cevap bekliyor"),
    DevamEdiyor("Devam ediyor"),
    Ulasilamiyor("Ulaşılamıyor"),
}

/**
 * Kırmızı her şeyin önündedir: ekranda eski bir `running` değeri kalsa bile
 * köprüye ulaşılamıyorsa kullanıcıya tur akıyor denmez. İlk çıktı ve kullanıcı
 * onayı aynı sarı "bekliyor" ailesidir; çıktı başladıktan sonra maviye geçer.
 */
internal fun liteBaslikDurumu(uiState: RemoteUiState): LiteBaslikDurumu = when {
    !uiState.healthOk || !uiState.protocolCompatible -> LiteBaslikDurumu.Ulasilamiyor
    uiState.awaitingFirstOutput || uiState.awaitingApproval -> LiteBaslikDurumu.CevapBekliyor
    uiState.running -> LiteBaslikDurumu.DevamEdiyor
    else -> LiteBaslikDurumu.Duruyor
}

/** Aktif Lite oturumunun kullanıcıya ait adı; boş sekmede teknik etiket göstermez. */
internal fun liteBaslikMetni(uiState: RemoteUiState): String {
    val aktif = uiState.visibleTabs.firstOrNull { it.id == uiState.activeTabId }
        ?: return "Yeni oturum"
    val canliAd = uiState.tabStatuses[aktif.id]?.liveTitle.orEmpty().trim()
    if (canliAd.isNotBlank()) return canliAd
    return aktif.title.trim().takeIf { it.isNotBlank() && it != "Yeni Sekme" } ?: "Yeni oturum"
}

/**
 * AgentBridge Lite başlığı. NavHost'un üstünde durduğu için ekran değişiminde
 * kaybolmaz; kaydırılan bir yüzey olmadığı için sekme çipleriyle aynı hafif cam
 * malzemeyi güvenle kullanır.
 */
@Composable
internal fun LiteTitleBar(
    uiState: RemoteUiState,
    onOturumlarAc: () -> Unit,
    onGuncellemeAc: () -> Unit,
    guncellemeVar: Boolean = false,
    guncellemeEkraniAcik: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val durum = liteBaslikDurumu(uiState)
    val durumRengi = when (durum) {
        LiteBaslikDurumu.Duruyor -> Ui3Colors.done
        LiteBaslikDurumu.CevapBekliyor -> Ui3Colors.attention
        LiteBaslikDurumu.DevamEdiyor -> Ui3Colors.vurguHi
        LiteBaslikDurumu.Ulasilamiyor -> Ui3Colors.danger
    }

    GlassLikeSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("lite_baslik_bari"),
        shape = RoundedCornerShape(Ui3Tokens.r18),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ui3Colors.yuzey2)
                    .clickable(onClick = onOturumlarAc)
                    .testTag("lite_oturumlar"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Oturumlar",
                    tint = Ui3Colors.ink,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(9.dp))
            DurumIsigi(durumRengi, durum.etiket)
            Spacer(Modifier.width(9.dp))
            Text(
                text = liteBaslikMetni(uiState),
                modifier = Modifier.weight(1f),
                style = Ui3Type.govde.copy(
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Ui3Colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = durum.etiket,
                style = Ui3Type.rozet,
                color = Ui3Colors.ink3,
                maxLines = 1,
            )
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onGuncellemeAc).testTag("lite_guncelleme"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (guncellemeEkraniAcik) Icons.Filled.Close else Icons.Outlined.SystemUpdate,
                    contentDescription = when {
                        guncellemeEkraniAcik -> "Güncellemeyi kapat"
                        guncellemeVar -> "Yeni güncelleme var"
                        else -> "Güncelleme"
                    },
                    tint = if (guncellemeVar && !guncellemeEkraniAcik) Ui3Colors.attention else Ui3Colors.ink3,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun DurumIsigi(renk: Color, aciklama: String) {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(renk.copy(alpha = 0.18f))
            .semantics { contentDescription = aciklama }
            .testTag("lite_durum_noktasi"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(renk))
    }
}
