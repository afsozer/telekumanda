package com.agent.bridge.ui2.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ui2 tasarım sistemi — Mockup v1 onaylı token seti (docs/ui-anayasasi.md 4.x).
// Koyu tema birincil: tasarım koyu temada kurgulanır ve onaylanır (anayasa 4.1).
// Dinamik renk (Material You) anayasa gereği VARSAYILAN KAPALI — marka paleti
// (menekşe) sabittir; dynamicColor parametresi yalnız ileride bilinçli bir
// ayara bağlamak için duruyor (13.07.2026 kullanıcı kararı: kapalı kalsın).
// Ekranlar renkleri Ui2.colors üzerinden alır; hex değeri ekran içinde YAZILMAZ.

@Immutable
data class Ui2Colors(
    val bg: Color,          // ekran zemini
    val chatBg: Color,      // sohbet akışı zemini — başlık/komposer'dan bir ton ayrı
    val surface: Color,     // kart/sheet yüzeyi (ton farkıyla ayrılır, gölge yok)
    val surface2: Color,    // bir tık açık yüzey (aktif sekme, seçili satır)
    val line: Color,        // ince kenar çizgisi
    val lineStrong: Color,  // aktif/seçili öğe kenarı
    val ink: Color,         // birincil metin
    val ink2: Color,        // ikincil metin
    val ink3: Color,        // üçüncül metin / ipucu / pasif ikon
    val accent: Color,      // TEK marka vurgusu (menekşe) — yalnız birincil aksiyon
    val onAccent: Color,    // vurgu üstü metin
    // Sohbet üzerine binen dolu çip/pill (overlay). Koyu temada yumuşak lavanta
    // zemin + koyu menekşe yazı (saf beyaz parlıyordu, menekşe yazı okunmuyordu —
    // 10.91); açık temada menekşe zemin + beyaz yazı. Yalnız overlay pill'de.
    val pillFill: Color,    // dolu pill zemini
    val pillOn: Color,      // dolu pill üstü yazı/ikon/nötr nokta
    val codeBg: Color,      // mono kod kutusu zemini
    val navBg: Color,       // alt navigasyon zemini
    // Durum renkleri — uygulama genelinde AYNI dört anlam (anayasa 4.2)
    val running: Color,     // mavi: tur sürüyor
    val attention: Color,   // kehribar: onay/girdi bekliyor
    val done: Color,        // yeşil: bitti-görülmedi / başarı
    val danger: Color,      // kırmızı: hata / yıkıcı aksiyon / bağlantı kopuk
    // Modal sheet zemini. ui2'de surface ile AYNI (varsayılan onu kopyalar,
    // görünüm değişmez); rol ui3'ün ödünç köprüsü için var — orada surface
    // saydam cam ve modal seçiciler arkasını gösterip dikkat dağıtıyordu
    // (kullanıcı, 17.08.2026). Köprü bu role opak bir değer basar.
    val sheetSurface: Color = surface,
)

val Ui2Dark = Ui2Colors(
    bg = Color(0xFF131118),
    chatBg = Color(0xFF0B0910),
    surface = Color(0xFF1B1923),
    surface2 = Color(0xFF232030),
    line = Color(0xFF2D2A3C),
    lineStrong = Color(0xFF3D3956),
    ink = Color(0xFFEBE9F4),
    ink2 = Color(0xFFA19DBB),
    ink3 = Color(0xFF6E6A85),
    accent = Color(0xFFA18BFA),
    onAccent = Color(0xFF191430),
    pillFill = Color(0xFFCFC6F0),
    pillOn = Color(0xFF262040),
    codeBg = Color(0xFF14121B),
    navBg = Color(0xFF16141D),
    running = Color(0xFF5B9DFF),
    attention = Color(0xFFF0B34E),
    done = Color(0xFF4ECF8D),
    danger = Color(0xFFFF6B6B),
)

// Açık tema desteklenir ama tasarım koyu temada onaylanır (anayasa 4.1).
// Durum renkleri açık zeminde okunurluk için koyulaştırıldı.
val Ui2Light = Ui2Colors(
    bg = Color(0xFFF6F5F9),
    chatBg = Color(0xFFEBE9F2),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECEAF4),
    line = Color(0xFFE3E1EC),
    lineStrong = Color(0xFFCBC7DE),
    ink = Color(0xFF232130),
    ink2 = Color(0xFF6D6A7E),
    ink3 = Color(0xFF908DA4),
    accent = Color(0xFF7C66E0),
    onAccent = Color(0xFFFFFFFF),
    pillFill = Color(0xFF7C66E0),
    pillOn = Color(0xFFFFFFFF),
    codeBg = Color(0xFFEFEDF6),
    navBg = Color(0xFFFBFAFD),
    running = Color(0xFF2F6FD8),
    attention = Color(0xFFB07D1A),
    done = Color(0xFF199A5F),
    danger = Color(0xFFD84A4A),
)

val LocalUi2Colors = staticCompositionLocalOf { Ui2Dark }

// Kısa erişim: Ui2.colors.accent gibi.
object Ui2 {
    val colors: Ui2Colors
        @Composable @ReadOnlyComposable get() = LocalUi2Colors.current
}

// Material3 bileşenleri (sheet, dialog, switch...) ui2 paletiyle uyumlu çizilsin
// diye tema M3 şemasına da eşlenir. ui2 ekranları yine de Ui2.colors kullanır.
private fun m3Dark(c: Ui2Colors) = darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.surface2,
    onPrimaryContainer = c.ink,
    secondary = c.ink2,
    onSecondary = c.bg,
    secondaryContainer = c.surface2,
    onSecondaryContainer = c.ink,
    tertiary = c.attention,
    onTertiary = Color(0xFF241A05),
    background = c.bg,
    onBackground = c.ink,
    surface = c.bg,
    onSurface = c.ink,
    surfaceVariant = c.surface,
    onSurfaceVariant = c.ink2,
    outline = c.line,
    outlineVariant = c.line,
    error = c.danger,
    onError = Color(0xFF33090B),
    surfaceContainerLowest = c.codeBg,
    surfaceContainerLow = Color(0xFF191722),
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface2,
    surfaceContainerHighest = Color(0xFF2D2A3C),
    scrim = Color(0xFF000000),
    inverseSurface = c.ink,
    inverseOnSurface = c.bg,
    inversePrimary = Color(0xFF6C55C8),
)

private fun m3Light(c: Ui2Colors) = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.surface2,
    onPrimaryContainer = c.ink,
    secondary = c.ink2,
    onSecondary = c.surface,
    secondaryContainer = c.surface2,
    onSecondaryContainer = c.ink,
    tertiary = c.attention,
    onTertiary = Color(0xFFFFFFFF),
    background = c.bg,
    onBackground = c.ink,
    surface = c.bg,
    onSurface = c.ink,
    surfaceVariant = c.surface,
    onSurfaceVariant = c.ink2,
    outline = c.line,
    outlineVariant = c.line,
    error = c.danger,
    onError = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface2,
    surfaceContainerHighest = Color(0xFFE3E1EC),
    scrim = Color(0xFF000000),
    inverseSurface = c.ink,
    inverseOnSurface = c.bg,
    inversePrimary = Color(0xFFB6A5FC),
)

private fun colorsFromDynamic(scheme: ColorScheme, fallback: Ui2Colors) = Ui2Colors(
    bg = scheme.background,
    chatBg = scheme.surfaceContainerLow,
    surface = scheme.surfaceContainer,
    surface2 = scheme.surfaceContainerHigh,
    line = scheme.outlineVariant,
    lineStrong = scheme.outline,
    ink = scheme.onBackground,
    ink2 = scheme.onSurfaceVariant,
    ink3 = scheme.outline,
    accent = scheme.primary,
    onAccent = scheme.onPrimary,
    // Marka rengi: dinamik paletten türetilmez, sabit kalır (durum renkleri gibi).
    pillFill = fallback.pillFill,
    pillOn = fallback.pillOn,
    codeBg = scheme.surfaceContainerLowest,
    navBg = scheme.surfaceContainer,
    running = fallback.running,
    attention = fallback.attention,
    done = fallback.done,
    danger = fallback.danger,
)

@Composable
fun Ui2Theme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val fallback = if (dark) Ui2Dark else Ui2Light
    val dynamicScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    val c = dynamicScheme?.let { colorsFromDynamic(it, fallback) } ?: fallback
    CompositionLocalProvider(LocalUi2Colors provides c) {
        MaterialTheme(
            colorScheme = dynamicScheme ?: if (dark) m3Dark(c) else m3Light(c),
            typography = Ui2Typography,
            shapes = Ui2Shapes,
            content = content,
        )
    }
}
