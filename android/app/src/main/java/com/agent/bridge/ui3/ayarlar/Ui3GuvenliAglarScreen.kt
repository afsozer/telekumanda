package com.agent.bridge.ui3.ayarlar

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agent.bridge.NetworkIdentity
import com.agent.bridge.SafeNetworkStore
import com.agent.bridge.WirelessDebugHealer
import com.agent.bridge.WirelessDebugWork
import com.agent.bridge.canReadSsid
import com.agent.bridge.currentNetworkIdentity
import com.agent.bridge.ui2.components.ListRow
import com.agent.bridge.ui2.components.ScreenHeader
import com.agent.bridge.ui2.components.SectionHeader
import com.agent.bridge.ui2.components.SurfaceCard
import com.agent.bridge.ui2.theme.Ui2
import com.agent.bridge.ui2.theme.Ui2Tokens

/**
 * Kablosuz hata ayıklamanın kendi kendine açılabileceği ağların listesi.
 *
 * Neden var (kullanıcı, 22.08.2026): healer her Wi-Fi'de ayarı açıyordu, sistem
 * her açılışta izin soruyordu ve "Hayır" cevabı bir dakika sonra soruyu geri
 * getiriyordu. Liste dışındaki ağlarda artık ayara hiç dokunulmuyor.
 *
 * Ekran ui3 ağacında ama gövdesi ui2 bileşenlerinden kuruluyor (ScreenHeader /
 * SurfaceCard / ListRow) — diğer ayar alt ekranlarıyla aynı görünsün diye.
 * ui2 tarafında hiçbir şey değişmiyor, yalnız ödünç alınıyor.
 */
@Composable
fun Ui3GuvenliAglarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var liste by remember { mutableStateOf(SafeNetworkStore.list(context)) }
    var mevcut by remember { mutableStateOf(currentNetworkIdentity(context)) }

    // Ağ, kullanıcı Ayarlar'a gidip Wi-Fi değiştirirken de değişebilir; ekrana
    // her dönüşte yeniden oku (bildirim ekranındaki pil durumu deseninin aynısı).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mevcut = currentNetworkIdentity(context)
                liste = SafeNetworkStore.list(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ayricalikVar = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED
    val simdikiGuvenli = mevcut?.let { m -> liste.any { it.matches(m) } } == true

    Column(
        Modifier
            .fillMaxSize()
            .background(Ui2.colors.bg)
            .verticalScroll(rememberScrollState())
            .testTag("ekran_guvenli_aglar"),
    ) {
        ScreenHeader(
            title = "Kablosuz hata ayıklama",
            subtitle = "Yalnız güvenli saydığın ağlarda kendi kendine açılır",
            onBack = onBack,
        )
        Column(
            Modifier.padding(horizontal = Ui2Tokens.screenPadding, vertical = Ui2Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui2Tokens.s20),
        ) {
            if (!ayricalikVar) {
                // Telefonda durum bu: özellik hiç çalışmıyor. Liste dolu görünüp
                // hiçbir şey olmamasındansa sebebini söyle.
                SurfaceCard {
                    Text(
                        "Bu cihazda kapalı",
                        style = MaterialTheme.typography.titleSmall,
                        color = Ui2.colors.ink,
                    )
                    Text(
                        "Ayarı değiştirmek WRITE_SECURE_SETTINGS ayrıcalığı istiyor ve bu " +
                            "cihaza verilmemiş. Aşağıdaki liste saklanır ama uygulama " +
                            "kablosuz hata ayıklamaya dokunmaz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink2,
                    )
                }
            }

            SectionHeader("Bu ağ")
            SurfaceCard {
                val m = mevcut
                if (m == null) {
                    ListRow(
                        title = "Wi-Fi'ye bağlı değil",
                        detail = "Ağ eklemek için önce o ağa bağlan",
                        leading = { Icon(Icons.Outlined.WifiOff, null, tint = Ui2.colors.ink2) },
                    )
                } else {
                    ListRow(
                        title = m.label(),
                        detail = if (simdikiGuvenli) "Güvenli listede" else "Listede değil",
                        detailMaxLines = 2,
                        leading = { Icon(Icons.Outlined.Router, null, tint = Ui2.colors.ink2) },
                        trailing = {
                            if (simdikiGuvenli) {
                                OutlinedButton(
                                    onClick = {
                                        SafeNetworkStore.remove(context, m)
                                        liste = SafeNetworkStore.list(context)
                                        WirelessDebugWork.schedule(context)
                                    },
                                ) { Text("Çıkar") }
                            } else {
                                Button(
                                    onClick = {
                                        SafeNetworkStore.add(context, m)
                                        liste = SafeNetworkStore.list(context)
                                        // Listeye girer girmez aç: kullanıcı bunu
                                        // ADB bağlanmadığı için açıyor, bir sonraki
                                        // dakikalık turu beklemesin.
                                        WirelessDebugWork.schedule(context)
                                        WirelessDebugHealer.heal(context)
                                    },
                                ) { Text("Güvenli") }
                            }
                        },
                    )
                    if (m.ssid == null) {
                        Text(
                            if (canReadSsid(context)) {
                                "Ağ adı okunamadı (cihazda Konum servisi kapalı olabilir). " +
                                    "Bu ağ alt ağıyla tanınacak; aynı alt ağı kullanan başka " +
                                    "bir router da güvenli sayılır."
                            } else {
                                "Ağ adını okuma izni yok, bu ağ alt ağıyla tanınacak. Adla " +
                                    "eşleşme için adb'den bir kez: pm grant com.agent.bridge " +
                                    "android.permission.ACCESS_FINE_LOCATION"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Ui2.colors.ink2,
                        )
                    }
                }
            }

            SectionHeader("Güvenli ağlar")
            SurfaceCard {
                if (liste.isEmpty()) {
                    Text(
                        "Liste boş. Kablosuz hata ayıklama kendi kendine açılmayacak ve " +
                            "sistem izin sormayacak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ui2.colors.ink2,
                    )
                } else {
                    liste.forEach { ag ->
                        ListRow(
                            title = ag.label(),
                            detail = ag.aciklama(),
                            detailMaxLines = 2,
                            leading = { Icon(Icons.Outlined.Router, null, tint = Ui2.colors.ink2) },
                            trailing = {
                                IconButton(
                                    onClick = {
                                        SafeNetworkStore.remove(context, ag)
                                        liste = SafeNetworkStore.list(context)
                                        WirelessDebugWork.schedule(context)
                                    },
                                ) {
                                    Icon(Icons.Outlined.Delete, "Listeden çıkar", tint = Ui2.colors.ink2)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Kayıtlı satırın alt metni: adla mı yoksa alt ağla mı tanınıyor. */
private fun NetworkIdentity.aciklama(): String = when {
    ssid != null && fingerprint != null -> "Ad ile eşleşir · $fingerprint"
    ssid != null -> "Ad ile eşleşir"
    else -> "Alt ağ ile eşleşir"
}
