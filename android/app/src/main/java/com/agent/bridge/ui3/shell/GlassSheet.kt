package com.agent.bridge.ui3.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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

/**
 * Cam sheet — aşağıdan yay (spring) fiziğiyle gelir.
 *
 * Material3'ün `ModalBottomSheet`'i yerine elle yazıldı: onun kendi kabı ve
 * gölgesi camın üstüne ikinci bir yüzey bindiriyor, "cam üstüne cam yığılmaz"
 * (anayasa v2 bölüm 1.3) kuralını kırıyordu. Burada kap doğrudan
 * [GlassSurface] ve tek katman kalıyor.
 *
 * Üst köşeler r38, grabber ve alt gesture boşluğu var. Zemine dokunmak kapatır.
 *
 * SÜRÜKLEME (17.08.2026, kullanıcı bildirdi): sheet parmağı takip eder.
 * Aşağı çekilirken birebir iner, bırakınca ya yerine yaylanır ya kapanır
 * (boyunun üçte birinden fazlası indiyse VEYA aşağı fırlatıldıysa). Jest
 * kabın tamamında; içerideki kaydırılabilir listeler kendi jestini önce
 * tükettiği için onların üstünde sürükleme tutamak şeridinden yapılır —
 * platform sözleşmesi de bu.
 *
 * GENİŞ EKRANDA ALTTAN DEĞİL SAĞDAN ([yanPanel], tablet fazı 2, 18.08.2026):
 * aynı bileşen, yalnız ekseni döner — giriş yatay, sürükleme yatay, kap sağ
 * kenarda yüzen bir panel. Ayrı bir "tablet sheet" bileşeni yazılmadı çünkü
 * içerik sözleşmesi (ColumnScope) ve kapatma mantığı birebir aynı; iki kopya
 * ilk davranış değişikliğinde ayrışırdı. Gerekçe ve ölçü: `UI3_YAN_SHEET_G`.
 */
@Composable
internal fun GlassSheet(
    hazeState: HazeState,
    acik: Boolean,
    onKapat: () -> Unit,
    modifier: Modifier = Modifier,
    // SAKİN KİP (17.08.2026, kullanıcı): bağlam menüleri gibi "dur ve seç"
    // yüzeylerinde tam saydam cam dikkat dağıtıyor — arkadaki liste seçeneklerin
    // arasından okunuyordu. true ise cam bir örtüyle koyulaştırılır; rim,
    // spekular ve gren kalır. Bilgi/gezinme sheet'leri saydam kalmaya devam eder.
    sakin: Boolean = false,
    // Geniş ekranda sağdan gelen yüzen panel. Varsayılan false = telefon yolu.
    yanPanel: Boolean = false,
    icerik: @Composable ColumnScope.() -> Unit,
) {
    // Sürükleme durumu px cinsinden; 0 = yerinde. Kapanış yönünün TERSİNE
    // çekmeye izin yok (coerce): sheet tavana/sola itilerek "büyütülen" bir
    // yüzey değil. Yan panelde aynı değer yatay eksende okunuyor.
    val kaydirma = remember { Animatable(0f) }
    // Kapanma eşiği bu boyun üçte biri: dikeyde yükseklik, yatayda genişlik.
    var sheetBoyu by remember { mutableIntStateOf(0) }
    val kapsam = rememberCoroutineScope()
    // Fırlatma eşiği yoğunluğa göre: dp/sn cinsinden düşünmek cihazlar arasında
    // tutarlı, px/sn API'nin verdiği. 900dp/sn ≈ kararlı bir "aşağı savurma".
    val esikHiz = with(LocalDensity.current) { 900.dp.toPx() }
    // Her açılışta sıfırdan başla: önceki kapanış sürüklenmiş konumda bitmiş
    // olabilir ve Animatable kompozisyonda hayatta kalıyor.
    LaunchedEffect(acik) { if (acik) kaydirma.snapTo(0f) }
    // İKİ AYRI AnimatedVisibility YOKTU sorunu: dış katman fadeOut ile hızlıca
    // bitiyor, iç katmanın slideOut'u daha sürerken bileşen ağaçtan düşüyordu —
    // sheet %90 bir anda kapanıp kalan %10'u animasyonla kapanıyor gibi
    // görünüyordu (kullanıcı bildirdi). Artık tek kapsayıcı ve ÇIKIŞ
    // animasyonu girişten uzun: kapanış gözle takip edilebilsin.
    AnimatedVisibility(
        visible = acik,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(260, delayMillis = 40)),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            // Zemin karartması: sheet'in arkasındaki içerik geri çekilsin.
            // interactionSource + null indication — dalga efekti tam ekranda
            // saçma durur, tıklama yalnız kapatma amaçlı.
            Box(
                Modifier
                    .fillMaxSize()
                    // Perde sürüklemeyi izler: sheet yarıya indiyse karartma da
                    // yarı güce iner. graphicsLayer içinde okunuyor ki her
                    // pikselde recomposition değil yalnız yeniden çizim olsun.
                    .graphicsLayer {
                        alpha = if (sheetBoyu > 0) {
                            (1f - kaydirma.value / sheetBoyu).coerceIn(0f, 1f)
                        } else 1f
                    }
                    .background(Ui3Colors.perde)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onKapat,
                    ),
            )
            AnimatedVisibility(
                visible = acik,
                enter = if (yanPanel) {
                    slideInHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        initialOffsetX = { it },
                    )
                } else {
                    slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        initialOffsetY = { it },
                    )
                },
                // Yay DEĞİL: yayın sonu çok yavaş sönüyor ve dış fade önce
                // bitince kesik görünüyordu. Süreli eğri dıştaki fade ile
                // aynı pencereye oturuyor.
                exit = if (yanPanel) {
                    slideOutHorizontally(animationSpec = tween(260), targetOffsetX = { it })
                } else {
                    slideOutVertically(animationSpec = tween(260), targetOffsetY = { it })
                },
                // Durum çubuğu payı: sheet ne kadar uzun olursa olsun oraya
                // TIRMANMASIN. Spotlight klavye açıkken tepeye dayanıyordu ve
                // başlığı sistem saatiyle iç içe giriyordu (cihazda görüldü).
                // Alt hizalı bir çocukta üst dolgu = azami yükseklik sınırı.
                // Yan panelde aynı dolgu ÇİFT taraflı: panel boyunca uzamıyor,
                // içeriği kadar olup dikeyde ortalanıyor (rail'de öğrenildi:
                // tavandan tabana cam sütun "yüzen levha" diliyle çelişiyor).
                modifier = if (yanPanel) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(top = Ui3Tokens.s8, bottom = Ui3Tokens.s8, end = UI3_RAIL_KENAR)
                } else {
                    Modifier.align(Alignment.BottomCenter).statusBarsPadding()
                },
            ) {
                GlassSurface(
                    hazeState = hazeState,
                    // %95 örtü (ilk değer %90'dı, kullanıcı bir tık daha az
                    // şeffaflık istedi — 18.08): içerik sakin zemine oturur,
                    // kalan %5'lik sızıntı camı aileden koparmaz.
                    ortu = if (sakin) Ui3Colors.bg0.copy(alpha = 0.95f) else null,
                    modifier = Modifier
                        // GENİŞ EKRAN: sheet tam genişlik DEĞİL (tablet fazı,
                        // 18.08.2026). 1400dp'lik bir tablette tam genişlik
                        // sheet, üç kelimelik bir seçim listesini ekranın bir
                        // ucundan öbürüne yayıyordu; kaynak ölçü telefonun
                        // genişliği, o yüzden orada sınır GEREKMİYOR. Yan
                        // panelde genişlik zaten sabit (UI3_YAN_SHEET_G).
                        .then(
                            if (yanPanel) Modifier.width(UI3_YAN_SHEET_G)
                            else Modifier.widthIn(max = UI3_SHEET_AZAMI).fillMaxWidth(),
                        )
                        .onSizeChanged { sheetBoyu = if (yanPanel) it.width else it.height }
                        // offset LAMBDA'lı sürüm: değer her karede değişiyor,
                        // yalnız yerleşim aşamasında okunmalı.
                        .offset {
                            if (yanPanel) IntOffset(kaydirma.value.roundToInt(), 0)
                            else IntOffset(0, kaydirma.value.roundToInt())
                        }
                        .draggable(
                            orientation =
                                if (yanPanel) Orientation.Horizontal else Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                kapsam.launch {
                                    kaydirma.snapTo((kaydirma.value + delta).coerceAtLeast(0f))
                                }
                            },
                            onDragStopped = { hiz ->
                                val kapat = hiz > esikHiz ||
                                    (sheetBoyu > 0 && kaydirma.value > sheetBoyu / 3f)
                                if (kapat) {
                                    // Çıkış animasyonu (slideOut + fade) sürüklenmiş
                                    // konumdan devralır — ayrıca animasyon yok.
                                    onKapat()
                                } else {
                                    kapsam.launch {
                                        kaydirma.animateTo(
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
                        .testTag("sheet"),
                    // Yan panel DÖRT köşesi de yuvarlak: kenara yapışmıyor,
                    // yüzüyor — kalıcı oturum paneliyle aynı aile (r26).
                    shape = if (yanPanel) {
                        RoundedCornerShape(Ui3Tokens.r26)
                    } else {
                        RoundedCornerShape(topStart = Ui3Tokens.r38, topEnd = Ui3Tokens.r38)
                    },
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // Gezinme çubuğu payı yan panelde DIŞARIDA veriliyor
                            // (yukarıdaki modifier); burada tekrarlanırsa panel
                            // içinde ikinci bir boşluk açılır.
                            .then(if (yanPanel) Modifier else Modifier.navigationBarsPadding())
                            .padding(bottom = Ui3Tokens.s16)
                            // MORF: sheet açıkken içeriği değişebiliyor (oturum
                            // menüsünden "yeniden adlandır"a geçmek gibi) ve
                            // panel bir kareden ötekine ZIPLAYARAK boyut
                            // değiştiriyordu. Aynı cam yüzey kalsın, yalnız
                            // boyu yaya bağlı aksın. Yay `spring` çünkü giriş
                            // animasyonu da yay — aynı fizik.
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ),
                    ) {
                        // Grabber: sheet'in sürüklenebilir olduğunu söyleyen işaret.
                        // Jestin kendisi yüzeyin tamamında (yukarıdaki draggable);
                        // içerik kaydırılabilirse fiilen bu şeritten tutulur.
                        // Yan panelde YOK: yatay bir tutamak "aşağı çek" der,
                        // oysa panel sağa gidiyor — yanlış yönü işaret ederdi.
                        // AMA YERİ DURUYOR (aşağıdaki Spacer): tutamağı silince
                        // üst dolgu da gitmişti ve başlık panelin kenarına
                        // yapışıyordu (cihazda görüldü, 18.08). 20dp ≈ grabber'ın
                        // kapladığı 12+4+8.
                        if (yanPanel) Spacer(Modifier.height(Ui3Tokens.s20))
                        else Box(
                            Modifier
                                .padding(top = Ui3Tokens.s12, bottom = Ui3Tokens.s8)
                                .align(Alignment.CenterHorizontally)
                                .width(38.dp)
                                .height(4.dp)
                                .clip(Ui3Tokens.pill)
                                .background(Ui3Colors.tutamak),
                        )
                        icerik()
                    }
                }
            }
        }
    }
}
