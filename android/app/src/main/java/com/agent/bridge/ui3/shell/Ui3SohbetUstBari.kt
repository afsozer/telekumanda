package com.agent.bridge.ui3.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.RemoteUiState
import com.agent.bridge.backendSession
import com.agent.bridge.permissionModeIsPermissive
import com.agent.bridge.shortModelLabel
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Type

/**
 * Sohbetin Kovan üst barı: geri oku, oturum adı, sağda tur ayarları sürgüsü;
 * altında "Sağlayıcı · model" satırı (7 Kas 2026).
 *
 * Kalkan adanın işini alt satır devraldı: tur sürüyorsa ya da onay bekliyorsa
 * renkli bir ek, bağlam doluluğu yüzde olarak. Alt satıra dokunmak bağlam
 * ayrıntısını açar (adaya dokunmanın telefondaki karşılığı).
 */
@Composable
internal fun Ui3SohbetUstBari(
    uiState: RemoteUiState,
    baglamYuzdesi: Int?,
    onGeri: (() -> Unit)?,
    onMenu: () -> Unit,
    onBaglam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backendId = uiState.backend
    val oturum = backendId?.let { uiState.backendSession(it) }
    val saglayici = backendId?.let { BACKEND_LABELS[it] ?: it }
    val model = oturum?.let { shortModelLabel(it.model.ifBlank { it.defaultModel }) }.orEmpty()
    val durum = when {
        uiState.awaitingApproval -> "onay bekliyor" to Ui3Colors.attention
        uiState.running -> "çalışıyor" to Ui3Colors.running
        else -> null
    }
    val sessiz = Ui3Colors.ink3
    val altMetin = buildAnnotatedString {
        append(listOfNotNull(saglayici, model.ifBlank { null }).joinToString(" · ").ifBlank { "Oturum yok" })
        if (baglamYuzdesi != null) append(" · %$baglamYuzdesi")
        if (durum != null) {
            append(" · ")
            withStyle(SpanStyle(color = durum.second)) { append(durum.first) }
        }
    }
    KovanUstBar(
        baslik = liteBaslikMetni(uiState),
        onGeri = onGeri,
        modifier = modifier.testTag("sohbet_ust_bar"),
        alt = {
            Row(Modifier.fillMaxWidth().clickable(onClick = onBaglam)) {
                Text(
                    altMetin,
                    style = Ui3Type.govde.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = sessiz,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        sagda = {
            KovanSimge(
                Icons.Filled.Tune,
                "Tur ayarları",
                onMenu,
                modifier = Modifier.testTag("composer_menu"),
                nokta = if (permissionModeIsPermissive(oturum?.permissionMode.orEmpty())) Ui3Colors.attention else null,
            )
        },
    )
}
