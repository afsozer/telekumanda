package com.agent.bridge.ui3.material

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.agent.bridge.ui3.theme.LocalUi3Palet
import com.agent.bridge.ui3.theme.Ui3Palet
import com.agent.bridge.ui3.theme.Ui3Tokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import java.util.Random

/**
 * Tonlanmış cam tipleri.
 *
 * Yalnız ANLAM taşıyan yerde kullanılır (anayasa v2, bölüm 1.4): kullanıcı balonu,
 * onay kartı, canlı araç çipi. Süs olarak tonlu cam yasaktır; Notr varsayılandır.
 */
enum class GlassTint { Notr, Vio, Amber, Cyan }

// ---------- malzeme sabitleri (kaynak: mockup ui5-liquid-glass.html, .lg) ----------
// Statik Brush/HazeStyle'tır; composable dışında tek kez kurulur, her kompozisyonda
// yeniden yaratmanın karşılığı yok.

// Katman 1 — blur. backgroundColor bilinçli olarak saydam: anayasa v2 bölüm 1.1
// "arkasındaki içeriği bulanıklaştırır, renkli dolgu YOK" ve 1.4 "renk camdan süzülür,
// cam renklendirilmez". noiseFactor 0: Haze'nin kendi gürültüsünü kapatır (eşik 0.005,
// RenderEffect.android.kt) — gren'i 5. katmanda kendimiz çiziyoruz, iki gürültü üst
// üste binmesin.
// Doygunluk (%175) bilinçli olarak UYGULANMADI. Haze 1.5.3'te HazeStyle.saturationFactor
// yok (aar imzaları doğrulandı). CSS saturate(175%)'in tek aday karşılığı olan
// BlendMode.Saturation tint'i cihazda denendi ve camı nötr yerine MAGENTA'ya boyadı
// (ui-olc piksel: kart içi ortalama rgb(131,25,139)); "cam renklendirilmez" (anayasa 1.4)
// çiğnendiği için kaldırıldı. Değer Ui3Tokens.doygunluk'ta duruyor. Blur tek başına
// camın çekirdek etkisidir; doygunluk yalnız Haze saturationFactor desteği gelince eklenir.
// backgroundColor SAYDAM OLAMAZ — burası camın çalışıp çalışmadığını belirliyor.
//
// Önce `Color.Transparent` yazılmıştı ve anayasa v2 §1.1'in "renkli dolgu yok"
// kuralına uyuyor gibi görünüyordu. Ölçüldüğünde blur'un hiç çalışmadığı çıktı:
// dock'un arkasından geçen metnin keskinliği 1297 → 1040, yalnız %20 düşüş.
// 26dp'lik bir blur metni okunamaz hale getirmeliydi; görülen şey blur değil,
// yarı saydamlıktı. Haze `backgroundColor`ı camın arkasındaki zemin rengi olarak
// kompozisyonda kullanıyor ve saydam verilince blur yolu devreye girmiyor.
//
// Zemin rengiyle (bg0) ölçüm: 1334 → 8.9, yani 150 kat. Metin camın arkasında
// gerçekten yok oluyor.
//
// Bu hata Faz 1 boyunca fark edilmedi çünkü camın arkasında yalnız yumuşak mesh
// gradyanı vardı — anayasa v2 §8'in üçüncü tuzağı tam olarak bu: yumuşak zemin
// arkasında "bulanık" ile "yarı saydam" ayırt edilemez. Faz 2'de dock'un altından
// keskin metin geçince hata ilk kez görünür oldu.
// Palete bağlı: açık temada camın arkasındaki zemin de açık. Sabit tutulsaydı
// açık temada cam koyu bir leke gibi dururdu.
private fun camStili(palet: Ui3Palet) = HazeStyle(
    backgroundColor = palet.bg0,
    tints = emptyList(),
    blurRadius = Ui3Tokens.blur,
    noiseFactor = 0f,
)

// Katman 2 — ton dolguları (mockup .lg.t-* gradyanları, 135° = sol üst → sağ alt).
// Renkler token'dan (Ui3Colors), alfa mockup'tan; yalnız tint != Notr iken kullanılır.
private fun tonFircasi(palet: Ui3Palet, tint: GlassTint): Brush? {
    val renk = when (tint) {
        // "renk camdan süzülür, cam renklendirilmez" (anayasa 1.4)
        GlassTint.Notr -> return null
        GlassTint.Vio -> palet.vurgu
        GlassTint.Amber -> palet.amber
        GlassTint.Cyan -> palet.cyan
    }
    // Açık temada aynı alfalar solgun kalıyor; ton biraz güçlendiriliyor ki
    // "bu kart anlam taşıyor" mesajı iki temada da okunsun.
    val g = if (palet.koyuMu) 1f else 1.6f
    // Vio (kullanıcı balonu) mockup'tan DÜZLEŞTİRİLDİ: .22→.02 iniş balonda
    // soldan sağa bariz bir şerit çiziyordu ("renk geçişi çok abartılı",
    // kullanıcı 18.08.2026). Uçlar birbirine çekildi — ton duruyor, geçiş
    // fısıltıya indi. Amber/Cyan tekil kart-çip yüzeyleri, orada mockup değeri.
    if (tint == GlassTint.Vio) {
        return Brush.linearGradient(
            0f to renk.copy(alpha = 0.14f * g),
            0.55f to renk.copy(alpha = 0.08f * g),
            1f to renk.copy(alpha = 0.05f * g),
        )
    }
    return Brush.linearGradient(
        0f to renk.copy(alpha = 0.22f * g),
        0.55f to renk.copy(alpha = 0.06f * g),
        1f to renk.copy(alpha = 0.02f * g),
    )
}

// Katman 3 — kenar kırılması (mockup .lg::before): 135° gradyan hat, beyaz
// .60 → .07 @36% → .02 @62% → .34. Düz border değil: camın kenarının arkadaki
// ışığı tutması (lens rim).
private fun rimFircasi(palet: Ui3Palet) = Brush.linearGradient(
    0f to palet.rim1,
    0.36f to palet.rim2,
    0.62f to palet.rim3,
    1f to palet.rim4,
)

// Katman 5 — gren. Mockup .grain: 120px feTurbulence dokusu, rect opaklığı .05,
// blend overlay. Bitmap runtime'da üretiliyor (res kaynağı yok); sabit tohum her
// açılışta aynı dokuyu verir — piksel ölçümü (ui-olc) tekrarlanabilir kalsın diye.
private const val GREN_BOYUT = 120
private const val GREN_TOHUM = 42L

private fun grenBitmapiUret(opaklik: Int): ImageBitmap {
    val pikseller = IntArray(GREN_BOYUT * GREN_BOYUT)
    val rastgele = Random(GREN_TOHUM)
    for (i in pikseller.indices) {
        val g = rastgele.nextInt(256)
        // Gri gürültü: renk kanalları eşit, yalnız opaklık farkı (feTurbulence fractalNoise).
        pikseller[i] = android.graphics.Color.argb(opaklik, g, g, g)
    }
    return Bitmap.createBitmap(pikseller, GREN_BOYUT, GREN_BOYUT, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * ui3'ün tek cam bileşeni (anayasa v2, bölüm 1.1).
 *
 * Katmanlar alttan üste: 1) Haze blur (+ saturasyon), 2) ton dolgusu (yalnız
 * [tint] != Notr), 3) rim, 4) spekular çizgi, 5) gren. [content] camın en üstünde
 * çizilir.
 *
 * Katman sırası modifier zincirinden gelir: dıştaki önce çizilir. Zincir
 * `clip → hazeEffect → background → border → drawWithCache`; BoxScope [content]
 * en içte olduğu için en üstte görünür.
 *
 * Haze 1.5.3'te `hazeChild` yuvarlak köşe bilmez (shape parametresi yok — doğrulandı;
 * ayrıca hazeChild, hazeEffect'in deprecate edilmiş takma adı). Kırpmayı biz yaparız:
 * `.clip(shape)` blur dahil tüm katmanları köşeye kırpar. Haze'in kendi örneği de
 * aynı deseni kullanır (clip → hazeEffect).
 *
 * @param hazeState kaynağın (MeshBackground + hazeSource) paylaştığı HazeState.
 * @param modifier cam yüzeyinin kendisine uygulanır — testTag dahil dışarıdan gelen.
 * @param shape camın dış yarıçapı (konşentrik aile: 12/18/21/26/33/38).
 * @param tint anlam taşıyan ton; Notr'da renkli dolgu yoktur.
 * @param ortu blur ile içerik arasına serilen ek dolgu. Varsayılan yok — cam
 *   saydam kalır. ODAK YÜZEYLERİ için (bağlam menüsü gibi): arkadan sızan
 *   içerik seçim yaparken dikkat dağıtıyorsa yüzey bununla sakinleştirilir;
 *   rim/spekular/gren kalır, yüzey cam ailesinden kopmaz (kullanıcı isteği,
 *   17.08.2026: "şeffaflık menülerde dikkat dağıtıyor").
 */
@Composable
fun GlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Ui3Tokens.r26),
    tint: GlassTint = GlassTint.Notr,
    ortu: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palet = LocalUi3Palet.current
    val gren = remember(palet.grenOpaklik) { grenBitmapiUret(palet.grenOpaklik) }
    val rim = remember(palet) { rimFircasi(palet) }
    val stil = remember(palet) { camStili(palet) }
    val spekular = palet.spekular
    Box(
        modifier
            // Dışarıdan gelen modifier en dışta: cam yüzeyinin kendisinde.
            .then(
                // Kehribar glow (mockup .lg.t-amber box-shadow: 0 0 40px rgba(251,191,36,.10)).
                // Modifier.shadow yumuşak dış gölgedir; clip=false olduğundan kartın dışına
                // taşar. Yalnız Amber'da: glow da ton gibi anlam taşıyan yerde.
                if (tint == GlassTint.Amber) {
                    Modifier.shadow(
                        elevation = 40.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = palet.amber.copy(alpha = 0.10f),
                        spotColor = palet.amber.copy(alpha = 0.10f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .hazeEffect(hazeState, stil)
            // Örtü blur'un ÜSTÜNDE, tonun altında: blur kenarlarda sızan ışığı
            // yumuşatmaya devam eder, içerik sakin zemine oturur.
            .then(ortu?.let { Modifier.background(it, shape) } ?: Modifier)
            .then(tonFircasi(palet, tint)?.let { Modifier.background(it, shape) } ?: Modifier)
            .border(1.dp, rim, shape)
            .drawWithCache {
                // ShaderBrush'i draw başına değil, cache bloğunda bir kez kuruyoruz.
                val grenFircasi = ShaderBrush(
                    ImageShader(gren, TileMode.Repeated, TileMode.Repeated),
                )
                onDrawBehind {
                    // Katman 4 — spekular çizgi (mockup .lg: inset 0 1px 0
                    // rgba(255,255,255,.16)): üst kenarda 1px açık hat. Y = 0.5dp:
                    // 1dp kalınlığın yarısı yukarı taşmasın, hat içeride kalsın.
                    // clip(shape) köşeleri zaten yuvarlatır.
                    drawLine(
                        color = spekular,
                        start = Offset(0f, 0.5.dp.toPx()),
                        end = Offset(size.width, 0.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                    // Katman 5 — gren: ImageShader TileMode.Repeated ile 120px dokuyu
                    // yüzeye döşer, overlay blend, opaklık bitmap alfasında (çok hafif).
                    // Blur'u ve metin okunabilirliğini bozmamalı.
                    drawRect(brush = grenFircasi, blendMode = BlendMode.Overlay)
                }
            },
    ) {
        content()
    }
}

/**
 * Cam GÖRÜNÜMÜ — blur YOK (anayasa v2 §1.3 çelişki çözümü).
 *
 * Ton + rim + spekular + gren aynen [GlassSurface]'ten gelir; eksik olan tek
 * katman blur'dur. Kaydırılan akıştaki kullanıcı balonu ve araç çipi bunu
 * kullanır: her mesaj kartına `hazeEffect` koymak her kaydırma karesinde blur'u
 * yeniden hesaplatırdı.
 *
 * Sabitler [GlassSurface] ile ORTAK — iki ayrı görsel dil doğmasın diye. Fark
 * yalnız blur; onun da bedeli listede karşılanamıyor.
 *
 * Arkasında dolgu ister: blur olmadığı için yüzey saydam kalır ve metin doğrudan
 * zemine oturur. [dolgu] o yüzden varsayılan olarak hafif bir yüzey rengi.
 */
@Composable
fun GlassLikeSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Ui3Tokens.r18),
    tint: GlassTint = GlassTint.Notr,
    // null = paletin kendi yüzey rengi. Sabit beyaz alfa açık temada görünmüyordu.
    dolgu: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palet = LocalUi3Palet.current
    val gren = remember(palet.grenOpaklik) { grenBitmapiUret(palet.grenOpaklik) }
    val rim = remember(palet) { rimFircasi(palet) }
    val spekular = palet.spekular
    Box(
        modifier
            .clip(shape)
            .background(dolgu ?: palet.yuzey1, shape)
            .then(tonFircasi(palet, tint)?.let { Modifier.background(it, shape) } ?: Modifier)
            .border(1.dp, rim, shape)
            .drawWithCache {
                val grenFircasi = ShaderBrush(
                    ImageShader(gren, TileMode.Repeated, TileMode.Repeated),
                )
                onDrawBehind {
                    drawLine(
                        color = spekular,
                        start = Offset(0f, 0.5.dp.toPx()),
                        end = Offset(size.width, 0.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                    drawRect(brush = grenFircasi, blendMode = BlendMode.Overlay)
                }
            },
    ) {
        content()
    }
}
