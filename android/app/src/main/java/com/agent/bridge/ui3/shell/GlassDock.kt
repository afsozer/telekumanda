package com.agent.bridge.ui3.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.nav.Ui3Area
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import com.agent.bridge.ui3.theme.Ui3Type
import dev.chrisbanes.haze.HazeState

// Dokunma hedefi: anayasa v2 bölüm 4 asgari 48dp. Etiket eklenince yükseklik
// 52'ye çıktı (ikon 20 + etiket 10sp + nefes). Genişlik SABİT DEĞİL: beş
// etiketli öğe sabit genişlikte 365dp'lik ekrana sığmıyor ve dock taşıyordu —
// öğeler weight(1f) ile eşit bölüşüyor.
private val OGE_Y = 52.dp

// Rail genişliği: ui2'de 88dp ölçülmüştü — en uzun etiket ("Oturumlar") tek
// satıra sığsın, M3'ün 80dp varsayılanı kırpma sınırındaydı. ui3'te Oturumlar
// raile girmiyor ama "Spotlight" de kısa değil; aynı sayı korunuyor.
private val RAIL_G = 88.dp

// Köşeler hap değil yuvarlak dikdörtgen (kullanıcı geri bildirimi: "çok oval").
private val DOCK_YARICAP = RoundedCornerShape(Ui3Tokens.r26)
private val OGE_YARICAP = RoundedCornerShape(Ui3Tokens.r18)

/**
 * Etiketli cam dock — anayasa v2 bölüm 6 (ui2'nin alt navigasyonu, cam yüzeyle).
 *
 * Aktif ikonun altında vurgu→petrol **lens**: düz bir çizgi değil, iç
 * spekularlı radyal mercek (mockup `.dk .lens`).
 *
 * Etiketler mockup'ta yoktu ve ui3 ilk sürümde onu izledi; kullanıcı gerçek
 * işte deneyince ikonun ne yaptığını hatırlamak zorunda kaldığını söyledi.
 * Etiket geri geldi — mockup görünüşü tasarladı, kullanımı değil.
 *
 * Onay nöbeti: onay beklenirken Sohbet ikonunun köşesinde kehribar nokta —
 * kullanıcı başka alandayken de görsün diye (anayasa v1 bölüm 6'dan devralınan
 * kural: onay bekleyen oturum uygulamanın her yerinden fark edilir).
 */
@Composable
internal fun GlassDock(
    hazeState: HazeState,
    aktif: Ui3Area,
    onSec: (Ui3Area) -> Unit,
    onayBekliyor: Boolean,
    // OPERASYON UYARISI (18.08.2026, ui2 kökü taraması): ui2'nin alt navında
    // vardı (`hasOperationsAttention`), ui3'e taşınmamıştı — dikkat isteyen bir
    // süreç varken hiçbir yerde iz kalmıyordu. Onay rozetiyle aynı kural: alan
    // kapalıyken bile görünür.
    operasyonUyarisi: Boolean = false,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.testTag("dock"),
        shape = DOCK_YARICAP,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ui3Tokens.s4, vertical = Ui3Tokens.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (alan in Ui3Area.entries.filter { it.dockta }) {
                DockOgesi(
                    alan = alan,
                    secili = alan == aktif,
                    rozet = (onayBekliyor && alan == Ui3Area.Sohbet) ||
                        (operasyonUyarisi && alan == Ui3Area.Operasyon),
                    onTikla = { onSec(alan) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * GENİŞ EKRAN KARŞILIĞI — sol kenarda dikey cam rail (kullanıcı kararı,
 * 18.08.2026; ui2 railini SAĞA koymuştu, ui3'te sol istendi).
 *
 * Dock'un aynı malzemesi: aynı öğe, aynı lens, aynı basış geri bildirimi —
 * yalnız akış dikey ve öğeler eşit pay yerine doğal boyda. Oturumlar öğesi
 * [oturumlarTusu] ile KOŞULLU: iki panelli yerleşimde liste zaten kalıcı
 * olarak solda, orada tuş anlamsız; tablet dikeyde panel çekmeceye döndüğü
 * için tuş geri geliyor (Ui3Root, UI3_IKI_PANEL_ESIK).
 *
 * Rail dock gibi yüzer: kenara yapışmaz, dört yanında nefes bırakır — camın
 * "arkasından zemin akan levha" fikri kenara yapışınca kayboluyor (dock
 * yorumundaki aynı gerekçe).
 */
@Composable
internal fun GlassRail(
    hazeState: HazeState,
    aktif: Ui3Area,
    onSec: (Ui3Area) -> Unit,
    onayBekliyor: Boolean,
    // OPERASYON UYARISI (18.08.2026, ui2 kökü taraması): ui2'nin alt navında
    // vardı (`hasOperationsAttention`), ui3'e taşınmamıştı — dikkat isteyen bir
    // süreç varken hiçbir yerde iz kalmıyordu. Onay rozetiyle aynı kural: alan
    // kapalıyken bile görünür.
    operasyonUyarisi: Boolean = false,
    modifier: Modifier = Modifier,
    // Oturum listesi kalıcı panelde DEĞİLSE (tablet dikey gibi dar geniş
    // ekranlar) onu açan tuş burada durmalı; kalıcıysa anlamsız.
    oturumlarTusu: Boolean = false,
) {
    GlassSurface(
        hazeState = hazeState,
        modifier = modifier.width(RAIL_G).testTag("rail"),
        shape = DOCK_YARICAP,
    ) {
        // YÜKSEKLİK İÇERİK KADAR (tablette ölçüldü, 18.08): `fillMaxHeight`
        // ile rail ekranın tepesinden dibine uzanan BOŞ bir cam slab olarak
        // duruyordu — dock'un "yüzen levha" diliyle çelişiyor. Şimdi öğeler
        // kadar uzun, dikeyde ortalı (Ui3Root'taki CenterStart hizası).
        Column(
            Modifier.padding(horizontal = Ui3Tokens.s4, vertical = Ui3Tokens.s8),
            verticalArrangement = Arrangement.spacedBy(Ui3Tokens.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ogeler = Ui3Area.entries.filter {
                it.dockta && (oturumlarTusu || it != Ui3Area.Oturumlar)
            }
            for (alan in ogeler) {
                DockOgesi(
                    alan = alan,
                    secili = alan == aktif,
                    rozet = (onayBekliyor && alan == Ui3Area.Sohbet) ||
                        (operasyonUyarisi && alan == Ui3Area.Operasyon),
                    onTikla = { onSec(alan) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DockOgesi(
    alan: Ui3Area,
    secili: Boolean,
    rozet: Boolean,
    onTikla: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // BASIŞ GERİ BİLDİRİMİ: Material'ın varsayılan dalgası (ripple) KAPALI.
    // Cam yüzeyin üstünde gri bir yayılma olarak çıkıyordu ve ui3'ün diline
    // hiç uymuyordu (kullanıcı: "çıkan karartı çirkin"). Yerine öğenin kendi
    // malzemesi konuşuyor: parmak inince seçili öğedeki vurgu→petrol lensin
    // soluk bir kopyası beliriyor ve öğe hafifçe küçülüyor. Kalkınca iz yok.
    val basimKaynagi = remember { MutableInteractionSource() }
    val basili by basimKaynagi.collectIsPressedAsState()
    val olcek by animateFloatAsState(
        targetValue = if (basili) 0.94f else 1f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "dock_basis_olcek",
    )
    val basisParlakligi by animateFloatAsState(
        targetValue = if (basili) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "dock_basis_lens",
    )
    Box(
        modifier
            .height(OGE_Y)
            .clip(OGE_YARICAP)
            .clickable(
                interactionSource = basimKaynagi,
                indication = null,
                onClick = onTikla,
            )
            .testTag(testTagOf(alan)),
        contentAlignment = Alignment.Center,
    ) {
        // Basış lensi: seçili lensin aynısı, yalnız daha soluk ve animasyonlu.
        // Seçili öğede de çiziliyor — basınca kısa bir parlama gibi duruyor.
        if (basisParlakligi > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .clip(OGE_YARICAP)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Ui3Colors.vurguHi.copy(alpha = 0.22f * basisParlakligi),
                                Ui3Colors.cyan.copy(alpha = 0.10f * basisParlakligi),
                                Color.Transparent,
                            ),
                            center = Offset(0.30f * 120f, 0.20f * 120f),
                        )
                    ),
            )
        }
        if (secili) {
            // Lens: öğenin ALTINDA duran mercek, kenarlardan içeri çekili —
            // dock'un cam yüzeyiyle arasında nefes kalsın.
            Box(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .clip(OGE_YARICAP)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Ui3Colors.vurguHi.copy(alpha = 0.34f),
                                Ui3Colors.cyan.copy(alpha = 0.14f),
                                Ui3Colors.cyan.copy(alpha = 0.05f),
                            ),
                            center = Offset(0.30f * 120f, 0.20f * 120f),
                        )
                    )
                    .border(1.dp, Ui3Colors.cizgi, OGE_YARICAP),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { scaleX = olcek; scaleY = olcek },
        ) {
            Icon(
                imageVector = ikonFor(alan, secili),
                contentDescription = null,
                tint = if (secili) Ui3Colors.ink else Ui3Colors.ink2,
                modifier = Modifier.size(20.dp),
            )
            // Etiket TEK SATIR: alt satıra taşarsa öğenin yüksekliği değişip
            // dock kayıyor (ui2'nin RailLabel'ında ölçülen davranışın aynısı).
            Text(
                alan.etiket,
                style = Ui3Type.rozet,
                color = if (secili) Ui3Colors.ink else Ui3Colors.ink3,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (rozet) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Ui3Colors.attention)
                    .testTag("dock_onay_rozeti"),
            )
        }
    }
}

// Seçiliyken dolu, değilken çizgi hâli — ui2'nin `rootIcon` deseninin aynısı.
// Terminal ikonunun dolu karşılığı yok, o yüzden Operasyon tek biçim.
private fun ikonFor(alan: Ui3Area, secili: Boolean): ImageVector = when (alan) {
    // ui2'nin rootIcon eşlemesiyle aynı; Oturumlar ui2'de de History.
    Ui3Area.Oturumlar -> Icons.Outlined.History
    Ui3Area.Sohbet -> if (secili) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
    Ui3Area.Spotlight -> Icons.Outlined.Search
    Ui3Area.Operasyon -> Icons.Outlined.Terminal
    Ui3Area.Merkez -> if (secili) Icons.Filled.GridView else Icons.Outlined.GridView
    Ui3Area.Ayarlar -> if (secili) Icons.Filled.Settings else Icons.Outlined.Settings
}

// Ölçüm aracının (--bekle) aradığı sabit adlar; plan bu listeyi sabitliyor.
private fun testTagOf(alan: Ui3Area): String = when (alan) {
    Ui3Area.Oturumlar -> "dock_oturumlar"
    Ui3Area.Sohbet -> "dock_sohbet"
    Ui3Area.Spotlight -> "dock_spotlight"
    Ui3Area.Operasyon -> "dock_operasyon"
    Ui3Area.Merkez -> "dock_merkez"
    Ui3Area.Ayarlar -> "dock_ayarlar"
}
