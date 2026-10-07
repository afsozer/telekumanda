package com.agent.bridge.ui3.ayarlar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agent.bridge.RemoteUiState
import com.agent.bridge.SafeNetworkStore
import com.agent.bridge.settingsSummary
import com.agent.bridge.ui3.material.GlassLikeSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type

/** Ayarlar alt ekranlarının rotaları. Kök `Ui3Area.Ayarlar` ile aynı ağaçta. */
internal object Ui3AyarRota {
    const val BAGLANTI = "settings/connection"
    const val GUVENLI_AGLAR = "settings/wireless-debug"
    const val SAGLAYICILAR = "settings/providers"
    const val MCP = "settings/mcp"
    const val MCP_EKLE = "settings/mcp/add"
    const val BILDIRIMLER = "settings/notifications"
    const val GUNCELLEME = "settings/update"
    const val GELISMIS = "settings/advanced"
}

/**
 * ui3 Ayarlar kökü — ui2'nin `SettingsRootScreen`'iyle AYNI satırlar ve aynı
 * özet metinleri (`settingsSummary`), ui3 malzemesiyle.
 *
 * Alt ekranlar (bağlantı, sağlayıcılar, MCP, bildirimler, güncelleme,
 * gelişmiş) ui2'den olduğu gibi çağrılıyor ve `Ui2Theme` ile sarılıyor —
 * eşleme akışı, pil optimizasyonu izni, OTA indirme, MCP hedefleri gibi
 * davranışları yeniden yazmak sessiz regresyon üretirdi. Kabuk ui3, içerik ui2.
 *
 * "Yeni arayüz (ön izleme)" anahtarı BURADA da duruyor: ui3'ten ui2'ye dönmenin
 * yolu ui3'ün kendi ayarlarında olmalı, yoksa geri dönüş için uygulamayı
 * silmek gerekir.
 */
@Composable
internal fun Ui3AyarlarScreen(
    uiState: RemoteUiState,
    onAc: (String) -> Unit,
    altBosluk: Dp,
    modifier: Modifier = Modifier,
) {
    val ozet = uiState.settingsSummary()
    // Güvenli ağ sayısı prefs'ten okunuyor (ucuz); alt ekrandan dönünce bu
    // bileşen yeniden bestelendiği için sayı kendiliğinden tazeleniyor.
    val guvenliAgSayisi = if (uiState.liteEdition) 0 else SafeNetworkStore.list(LocalContext.current).size

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Ui3Tokens.s16)
            .testTag("ekran_ayarlar"),
        verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
    ) {
        Bolum("BAĞLANTI VE GÜVENLİK")
        AyarKarti {
            AyarSatiri(
                ikon = Icons.Outlined.Link,
                baslik = "Bağlantı ve cihaz eşleme",
                detay = when {
                    !ozet.protocolCompatible -> "Bridge protokolü uyumsuz"
                    ozet.connectionHealthy && ozet.devicePaired -> "Bağlı · cihaz anahtarı etkin"
                    ozet.connectionHealthy -> "Bağlı · ortak token"
                    else -> "Bağlantı yok"
                },
                // Rozet rengi ui2'deki koşulun aynısı: sağlıklı VE uyumlu ise
                // "bağlı", aksi halde kullanıcıyı kontrole çağır.
                rozet = if (ozet.connectionHealthy && ozet.protocolCompatible) "Bağlı" else "Kontrol et",
                rozetRengi = if (ozet.connectionHealthy && ozet.protocolCompatible) Ui3Colors.done else Ui3Colors.danger,
                testEtiketi = "ayar_baglanti",
            ) { onAc(Ui3AyarRota.BAGLANTI) }
            if (!uiState.liteEdition) {
                AyarSatiri(
                    ikon = Icons.Outlined.Wifi,
                    baslik = "Kablosuz hata ayıklama",
                    detay = if (guvenliAgSayisi == 0) {
                        "Kapalı — güvenli ağ yok"
                    } else {
                        "$guvenliAgSayisi güvenli ağda kendi kendine açılır"
                    },
                    testEtiketi = "ayar_kablosuz_hata_ayiklama",
                ) { onAc(Ui3AyarRota.GUVENLI_AGLAR) }
            }
        }

        if (!uiState.liteEdition) {
            Bolum("SAĞLAYICILAR")
            AyarKarti {
            AyarSatiri(
                ikon = Icons.Outlined.Hub,
                baslik = "Görünürlük ve sıra",
                detay = "${ozet.visibleProviderCount}/${ozet.providerCount} sağlayıcı görünür",
                testEtiketi = "ayar_saglayicilar",
            ) { onAc(Ui3AyarRota.SAGLAYICILAR) }
            AyarSatiri(
                ikon = Icons.Outlined.DeveloperMode,
                baslik = "MCP sunucuları",
                detay = "Claude, Codex, OpenCode, OMP ve Antigravity",
                testEtiketi = "ayar_mcp",
            ) { onAc(Ui3AyarRota.MCP) }
            }

            Bolum("UYGULAMA")
            AyarKarti {
            AyarSatiri(
                ikon = Icons.Outlined.Notifications,
                baslik = "Bildirimler",
                detay = if (ozet.notificationsEnabled) "Onay ve tur bildirimleri açık" else "Kapalı",
                testEtiketi = "ayar_bildirimler",
            ) { onAc(Ui3AyarRota.BILDIRIMLER) }
            AyarSatiri(
                ikon = Icons.Outlined.SystemUpdate,
                baslik = "Güncelleme",
                detay = "OTA sürümünü kontrol et ve yükle",
                testEtiketi = "ayar_guncelleme",
            ) { onAc(Ui3AyarRota.GUNCELLEME) }
            AyarSatiri(
                ikon = Icons.Outlined.Shield,
                baslik = "Gelişmiş",
                detay = "Oturumlar, süreçler ve toplu sonlandırma",
                testEtiketi = "ayar_gelismis",
            ) { onAc(Ui3AyarRota.GELISMIS) }
            }
        }

        if (uiState.liteEdition) {
            Bolum("UYGULAMA")
            AyarKarti {
                AyarSatiri(
                    ikon = Icons.Outlined.SystemUpdate,
                    baslik = "Güncelleme",
                    detay = "Lite sürümünü kontrol et ve yükle",
                    testEtiketi = "ayar_guncelleme",
                ) { onAc(Ui3AyarRota.GUNCELLEME) }
            }
        }

        Box(Modifier.padding(bottom = altBosluk + Ui3Tokens.s20))
    }
}

@Composable
private fun Bolum(baslik: String) {
    Text(
        baslik,
        style = Ui3Type.etiket,
        color = Ui3Colors.ink3,
        modifier = Modifier.padding(start = Ui3Tokens.s4, top = Ui3Tokens.s8),
    )
}

@Composable
private fun AyarKarti(icerik: @Composable () -> Unit) {
    GlassLikeSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Ui3Tokens.r26),
    ) {
        Column(Modifier.padding(Ui3Tokens.s4)) { icerik() }
    }
}

@Composable
private fun AyarSatiri(
    ikon: ImageVector,
    baslik: String,
    detay: String,
    testEtiketi: String,
    rozet: String? = null,
    rozetRengi: Color = Ui3Colors.done,
    onTikla: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ui3Tokens.r18))
            .clickable(onClick = onTikla)
            .padding(horizontal = Ui3Tokens.s12, vertical = Ui3Tokens.s12)
            .testTag(testEtiketi),
        horizontalArrangement = Arrangement.spacedBy(Ui3Tokens.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IkonKutu(ikon)
        Column(Modifier.weight(1f)) {
            Text(baslik, style = Ui3Type.govde, color = Ui3Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detay, style = Ui3Type.alt, color = Ui3Colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (rozet != null) {
            Text(
                rozet,
                style = Ui3Type.etiket,
                color = rozetRengi,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(Ui3Tokens.pill)
                    .background(rozetRengi.copy(alpha = 0.16f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Ui3Colors.ink3,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun IkonKutu(ikon: ImageVector) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ui3Colors.yuzey2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ikon, contentDescription = null, tint = Ui3Colors.ink2, modifier = Modifier.size(18.dp))
    }
}
