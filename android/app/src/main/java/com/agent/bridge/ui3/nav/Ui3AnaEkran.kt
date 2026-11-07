package com.agent.bridge.ui3.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.bridge.BACKEND_LABELS
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.chat.SessionTabUi
import com.agent.bridge.ui2.components.StatusKind
import com.agent.bridge.ui3.shell.KovanBolum
import com.agent.bridge.ui3.shell.KovanKart
import com.agent.bridge.ui3.shell.KovanMonogram
import com.agent.bridge.ui3.shell.KovanSatir
import com.agent.bridge.ui3.shell.KovanSatirSimgesi
import com.agent.bridge.ui3.shell.KovanSimge
import com.agent.bridge.ui3.shell.KovanUstBar
import com.agent.bridge.ui3.shell.TelekumandaIsareti
import com.agent.bridge.ui3.theme.Ui3Colors

/**
 * Ana ekran, Kovan'ın "Bilgisayarlar" sayfasının karşılığı (7 Kas 2026).
 *
 * Dock kalktı; gezinmenin kökü burası. Açık sekmeler bir kartta, oturum
 * geçmişi ve Merkez ikinci kartta. Spotlight, yeni sekme ve Ayarlar başlıkta.
 */
@Composable
internal fun Ui3AnaEkran(
    sekmeler: List<SessionTabUi>,
    onSekme: (String) -> Unit,
    onSekmeMenusu: (String) -> Unit,
    onYeniSekme: () -> Unit,
    onAra: () -> Unit,
    onAyarlar: () -> Unit,
    onOturumlar: () -> Unit,
    onMerkez: () -> Unit,
    merkezUyarisi: Boolean,
    altBosluk: Dp,
) {
    Column(Modifier.fillMaxSize().testTag("ana_ekran")) {
        KovanUstBar(
            baslik = "Telekumanda",
            buyuk = true,
            solda = {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    TelekumandaIsareti(olcu = 30.dp)
                }
            },
            sagda = {
                KovanSimge(Icons.Outlined.Search, "Ara", onAra)
                KovanSimge(Icons.Filled.Add, "Yeni sekme", onYeniSekme)
                KovanSimge(Icons.Outlined.Settings, "Ayarlar", onAyarlar)
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (sekmeler.isNotEmpty()) {
                KovanBolum("Sekmeler")
                KovanKart(Modifier.testTag("ana_sekmeler")) {
                    sekmeler.forEachIndexed { i, sekme ->
                        val (durumMetni, durumRengi) = sekmeDurumu(sekme.status)
                        val saglayici = BACKEND_LABELS[sekme.backend] ?: sekme.backend
                        val alt = listOf(
                            saglayici.takeIf { it != sekme.repo },
                            durumMetni ?: sekme.liveTitle.takeIf { it.isNotBlank() && it != sekme.repo },
                        ).filterNotNull().joinToString(" · ")
                        KovanSatir(
                            baslik = sekme.repo,
                            alt = alt,
                            altRengi = if (durumMetni != null) durumRengi ?: Ui3Colors.ink3 else Ui3Colors.ink3,
                            sol = { KovanMonogram(providerMonogram(sekme.backend), durumRengi) },
                            ayrac = i < sekmeler.lastIndex,
                            onUzunBas = { onSekmeMenusu(sekme.id) },
                            onTikla = { onSekme(sekme.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            KovanKart {
                KovanSatir(
                    baslik = "Oturumlar",
                    alt = "Geçmiş ve sabitlenen oturumlar",
                    sol = { KovanSatirSimgesi(Icons.Outlined.History) },
                    ayrac = true,
                    onTikla = onOturumlar,
                )
                KovanSatir(
                    baslik = "Merkez",
                    alt = "Dosyalar, notlar, projeler",
                    sol = {
                        KovanSatirSimgesi(Icons.Outlined.GridView, if (merkezUyarisi) Ui3Colors.attention else null)
                    },
                    onTikla = onMerkez,
                )
            }
            Spacer(Modifier.height(altBosluk + 24.dp))
        }
    }
}

@Composable
private fun sekmeDurumu(durum: StatusKind?): Pair<String?, Color?> = when (durum) {
    StatusKind.Running -> "çalışıyor" to Ui3Colors.running
    StatusKind.Attention -> "onay bekliyor" to Ui3Colors.attention
    StatusKind.Done -> "bitti" to Ui3Colors.done
    StatusKind.Danger -> "hata" to Ui3Colors.danger
    null -> null to null
}
