package com.agent.bridge.ui3.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.material.GlassSurface
import com.agent.bridge.ui3.theme.Ui3Colors
import com.agent.bridge.ui3.theme.Ui3Tokens
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Panelin ekran genişliğindeki payı. Ui3Root açılış kararında da kullanıyor. */
internal const val YAN_PANEL_ORANI = 0.90f

/**
 * SOLDAN giren cam panel — oturum çekmecesinin kabı.
 *
 * Neden alttan değil: oturum listesi ui2'de bir **çekmece** ve sağa kaydırma
 * jestiyle açılıyor. ui3'te önce alttan çıkan bir sheet olarak yazılmıştı;
 * jestler gelince yön çelişkisi ortaya çıktı — parmak sağa gidiyor, panel
 * yukarı çıkıyordu (kullanıcı bildirdi). Artık dock'taki Oturumlar tuşu da,
 * sağa kaydırma jesti de AYNI paneli aynı yönden açıyor.
 *
 * SÜRÜKLEME (17.08.2026, kullanıcı isteği — sheet'le aynı gün): panel
 * `AnimatedVisibility` değil, tek bir `Animatable` KONUM üzerinden yaşıyor
 * (-panelW = kapalı, 0 = açık). İki sürücüsü var:
 *  - [acik] true: hedef 0, yayla oturur (dock/rail'deki Oturumlar tuşu).
 *  - Panelin üstünde sola sürükleme: parmağı izler, bırakınca ya kapanır
 *    (üçte birinden fazla çıktıysa / sola savrulduysa) ya yerine yaylanır.
 * Perde karartması konumu izler: panel yarı içerideyse karartma yarı güçte.
 * `GlassSheet`in kardeşi: aynı perde, aynı tek katman cam kuralı.
 *
 * AÇILIŞ SÜRÜKLEMESİ KALDIRILDI (19.08.2026): sohbetteki sağa jest artık
 * sekme değiştiriyor, yani paneli parmakla içeri çeken bir kaynak kalmadı.
 * Ölü bir parametreyi "belki lazım olur" diye tutmak, bir dahaki okuyanı
 * olmayan bir çağıranı aramaya gönderirdi.
 */
@Composable
internal fun GlassYanPanel(
    hazeState: HazeState,
    acik: Boolean,
    onKapat: () -> Unit,
    modifier: Modifier = Modifier,
    icerik: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val panelW = with(LocalDensity.current) { (maxWidth * YAN_PANEL_ORANI).toPx() }
        val esikHiz = with(LocalDensity.current) { 900.dp.toPx() }
        // Başlangıç kapalı konum. Ekran genişliği dönünce (rotasyon) LaunchedEffect
        // yeni hedefe sürer; remember'ı panelW'ye anahtarlamak açık paneli
        // rotasyonda kapatırdı.
        val konum = remember { Animatable(-panelW) }
        val kapsam = rememberCoroutineScope()

        LaunchedEffect(acik, panelW) {
            val hedef = if (acik) 0f else -panelW
            if (konum.value != hedef) {
                konum.animateTo(
                    hedef,
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }

        // Tamamen kapalıysa HİÇ çizme: görünmez bir tam ekran kaplama,
        // altındaki sohbetin dokunuşlarını gölgelerdi.
        val gorunur = acik || konum.value > -panelW + 0.5f
        if (gorunur) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Perde konumu izler: graphicsLayer içinde okunuyor ki her
                    // pikselde recomposition değil yalnız yeniden çizim olsun.
                    .graphicsLayer { alpha = (1f + konum.value / panelW).coerceIn(0f, 1f) }
                    .background(Ui3Colors.perde)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onKapat,
                    ),
            )
            GlassSurface(
                hazeState = hazeState,
                modifier = Modifier
                    .fillMaxWidth(YAN_PANEL_ORANI)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(konum.value.roundToInt(), 0) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            kapsam.launch {
                                konum.snapTo((konum.value + delta).coerceIn(-panelW, 0f))
                            }
                        },
                        onDragStopped = { hiz ->
                            val kapat = hiz < -esikHiz || konum.value < -panelW / 3f
                            if (kapat) {
                                // Kapanış animasyonunu yukarıdaki LaunchedEffect
                                // sürer (acik false'a düşünce hedef -panelW).
                                onKapat()
                            } else {
                                kapsam.launch {
                                    konum.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                        initialVelocity = hiz,
                                    )
                                }
                            }
                        },
                    )
                    .testTag("yan_panel"),
                // Sol kenar ekrana dayalı: yalnız sağ köşeler yuvarlak.
                shape = RoundedCornerShape(topEnd = Ui3Tokens.r38, bottomEnd = Ui3Tokens.r38),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(bottom = Ui3Tokens.s16),
                ) {
                    icerik()
                }
            }
        }
    }
}
