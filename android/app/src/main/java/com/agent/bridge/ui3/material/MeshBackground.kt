package com.agent.bridge.ui3.material

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.theme.LocalUi3Palet

// Mockup `.mesh i { opacity: .8 }` — leke katmanının tamamına uygulanan opaklık.
private const val KATMAN_OPAKLIGI = 0.8f

/**
 * Canlı, renkli zemin (anayasa 1.4) — camların kıracağı ışık.
 *
 * Mockup `.mesh` ile birebir dört radyal leke. Mockup 393px genişlikte bir
 * telefon çerçevesine çizildi ve CSS px'i burada dp karşılığıdır.
 *
 * DİKKAT — birim: `DrawScope` içindeki her sayı PİKSELDİR, dp değil. Mockup
 * değerleri bu yüzden `.dp.toPx()` ile çevrilir. İlk sürümde çevrilmemişti:
 * 340dp olması gereken leke 340 piksel olarak çizildi ve 560dpi'lık ekranda
 * (yoğunluk 3.5) mesh 3,5 kat küçük çıktı — ekranın çoğu düz siyah kaldı,
 * camın kıracağı ışık kalmadı. Ölçüm araçları bunu yakalayamaz (taşma yok,
 * kontrast düşmüyor); yalnız göz yakalar.
 *
 * Lekeler ekran DIŞINA taşar (negatif offset) ve KIRPILMAZ (clip yok) —
 * cam kenarlarda ışığı taşmadan kıramaz, taşma bilinçli tasarımdır.
 * Bu yüzden [Canvas] ile elle çizim seçildi: `Modifier.offset` + `Box`
 * yaklaşımına göre tek çizim geçişi, taşmanın kırpılmaması için ek
 * mekanizma (z-index, boyut sıfırlama) gerektirmiyor.
 *
 * DURGUN: `infiniteTransition` KESİNLİKLE kullanılmaz. Anayasa 5 —
 * mesh normalde durgun, yalnız ekran/sheet geçişinde ~600ms canlanır.
 * Faz 1'de HİÇ geçiş yok, o yüzden tamamen statik. Hareket tetiklemesi
 * Faz 2'de (geçişlerle) eklenecek; şimdi eklenmemiş/çağrılmayan bir
 * animasyon API'si ölü kod olur ve sürekli dönen mesh hareket bütçesini
 * ihlal eder (kare üretimi tur çalışmıyorken de sürmemeli).
 *
 * Bileşen içerik almaz, yalnız zemin çizer; [modifier] dıştan gelir.
 */
@Composable
fun MeshBackground(modifier: Modifier = Modifier) {
    // Renkler ve güç Canvas'ın DIŞINDA okunur: DrawScope composable değil.
    val palet = LocalUi3Palet.current
    val guc = palet.meshGucu
    Canvas(modifier = modifier) {
        // Mockup ölçüsü (dp) → piksel. Tek kapı: aşağıdaki hiçbir yerde ham
        // sayı piksel olarak kullanılmaz.
        fun d(value: Float): Float = value.dp.toPx()

        // Her leke: merkezde renk, %68'de şeffaf (mockup `transparent 68%`).
        // İki stop yeter — 0.68'den kenara kadar saydam kalır.
        //
        // katmanOpakligi: mockup'ta `.mesh i { opacity: .8 }` gradyanın kendi
        // alfasının ÜSTÜNE biniyor; ikisi çarpılarak uygulanır, yoksa lekeler
        // olduğundan doygun çıkar.
        fun blotch(center: Offset, diameterDp: Float, color: Color, alpha: Float) {
            val c = color.copy(alpha = alpha * KATMAN_OPAKLIGI * guc)
            drawRect(
                brush = Brush.radialGradient(
                    0f to c,
                    0.68f to c.copy(alpha = 0f),
                    center = center,
                    radius = d(diameterDp) / 2f,
                ),
                topLeft = Offset.Zero,
                size = size,
            )
        }

        // m1 — çelik mavi (baskın leke), sol üstten taşmış (left:-90, top:-80; 340dp çap).
        blotch(
            center = Offset(d(-90f + 340f / 2f), d(-80f + 340f / 2f)),
            diameterDp = 340f,
            color = palet.mesh1,
            alpha = 0.52f,
        )
        // m2 — cyan, sağ üstten taşmış (right:-110, top:160; 320dp çap).
        blotch(
            center = Offset(size.width + d(110f - 320f / 2f), d(160f + 320f / 2f)),
            diameterDp = 320f,
            color = palet.mesh2,
            alpha = 0.34f,
        )
        // m3 — mavi, sol alttan taşmış (left:-60, bottom:-60; 300dp çap).
        blotch(
            center = Offset(d(-60f + 300f / 2f), size.height + d(60f - 300f / 2f)),
            diameterDp = 300f,
            color = palet.mesh3,
            alpha = 0.30f,
        )
        // m4 — bronz (tek ılık leke), sağ alttan taşmış (right:-40, bottom:180; 220dp çap).
        blotch(
            center = Offset(size.width + d(40f - 220f / 2f), size.height - d(180f + 220f / 2f)),
            diameterDp = 220f,
            color = palet.mesh4,
            alpha = 0.20f,
        )
    }
}
