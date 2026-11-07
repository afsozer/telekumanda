package com.agent.bridge.ui3.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.bridge.RemoteUiState
import com.agent.bridge.visibleTabs
import com.agent.bridge.ui3.theme.Ui3Colors
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
 * Telekumanda Lite başlığı, Kovan üst barı biçiminde (7 Kas 2026): solda
 * oturum listesi, ortada oturum adı, altında durum satırı; sağda güncelleme
 * (yalnız varken ya da ekranı açıkken) ve tur ayarları sürgüsü.
 */
@Composable
internal fun LiteTitleBar(
    uiState: RemoteUiState,
    onOturumlarAc: () -> Unit,
    onGuncellemeAc: () -> Unit,
    onMenu: () -> Unit,
    guncellemeVar: Boolean = false,
    guncellemeEkraniAcik: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val durum = liteBaslikDurumu(uiState)
    val durumRengi = when (durum) {
        LiteBaslikDurumu.Duruyor -> Ui3Colors.done
        LiteBaslikDurumu.CevapBekliyor -> Ui3Colors.attention
        LiteBaslikDurumu.DevamEdiyor -> Ui3Colors.running
        LiteBaslikDurumu.Ulasilamiyor -> Ui3Colors.danger
    }
    KovanUstBar(
        baslik = liteBaslikMetni(uiState),
        modifier = modifier.testTag("lite_baslik_bari"),
        solda = {
            KovanSimge(
                Icons.Filled.Menu,
                "Oturumlar",
                onOturumlarAc,
                modifier = Modifier.testTag("lite_oturumlar"),
            )
        },
        alt = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DurumIsigi(durumRengi, durum.etiket)
                Spacer(Modifier.width(8.dp))
                Text(
                    durum.etiket,
                    style = Ui3Type.govde.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = Ui3Colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        sagda = {
            if (guncellemeVar || guncellemeEkraniAcik) {
                KovanSimge(
                    if (guncellemeEkraniAcik) Icons.Filled.Close else Icons.Outlined.SystemUpdate,
                    when {
                        guncellemeEkraniAcik -> "Güncellemeyi kapat"
                        else -> "Yeni güncelleme var"
                    },
                    onGuncellemeAc,
                    modifier = Modifier.testTag("lite_guncelleme"),
                    renk = if (guncellemeEkraniAcik) Ui3Colors.ink else Ui3Colors.attention,
                )
            }
            KovanSimge(Icons.Filled.Tune, "Tur ayarları", onMenu, modifier = Modifier.testTag("composer_menu"))
        },
    )
}

@Composable
private fun DurumIsigi(renk: Color, aciklama: String) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(renk)
            .semantics { contentDescription = aciklama }
            .testTag("lite_durum_noktasi"),
    )
}
