package com.agent.bridge.ui3.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * ui3 paleti — koyu ve açık iki örnek.
 *
 * ui3 başlangıçta "koyu tema tek birincildir, çalışma zamanında değişen değer
 * yok" varsayımıyla düz `object` sabitleriyle yazılmıştı. Kullanıcı telefonu
 * açık temadayken ui3'ü açınca sistem durum çubuğu ikonları (saat, pil, sinyal)
 * koyu zeminde koyu kalıp kayboldu; karar açık temanın da yazılması yönünde
 * güncellendi. Bu dosya o varsayımı kaldırıyor.
 *
 * MALZEME ROLLERİ de burada: camın dolgusu, kenarı ve spekular hattı koyu zemine
 * göre BEYAZ alfalarla ayarlanmıştı (`Color.White.copy(alpha = .07f)` gibi).
 * Açık zeminde beyaz üstüne beyaz görünmez; her rol iki temada ayrı ayrı
 * tanımlanıyor. Bileşenler artık ham `Color.White`/`Color.Black` yazmaz.
 *
 * RENK REVİZYONU (17.08.2026): mockup'ın neon menekşe/cyan seti (v0'dan gelen
 * #8B5CF6 / #22D3EE ailesi) kullanıcı isteğiyle emekli edildi — "çoğunlukla
 * profesyonel iş yapılan uygulamaya profesyonel ton". Yeni kimlik çelik mavi +
 * petrol yeşili + kısık altın; menekşe tamamen çıktı (o ui2'nin kimliği).
 * CAM MALZEMESİ DEĞİŞMEDİ: alfalar, mesh gücü ve geometri aynen duruyor,
 * yalnız tonlar değişti. Her metin rengi iki zeminde (cam kart + düz) WCAG
 * 4.5'in üstünde; tablo `docs/ui3-liquid-glass-plani.md` renk revizyonunda.
 */
data class Ui3Palet(
    val koyuMu: Boolean,

    // ---- zemin ve mürekkep ----
    val bg0: Color,
    val ink: Color,
    val ink2: Color,
    // ink3 = en sessiz mürekkep (zaman damgası, ikincil etiket, detay satırı).
    // Faz 9'da kontrast için açılmıştı (eski lavanta tonlar 3.25/3.41
    // veriyordu); renk revizyonunda aynı aydınlık korunarak nötr mavi-griye
    // çevrildi. "Sessiz" kalması amaç, 4.5 tabanı şart.
    val ink3: Color,

    // ---- marka ve durum ----
    /** Vurgu: seçili öğe, aktif ikon, bağlantı. Eski adı `vio` idi — menekşe
     *  emekli olunca ad da değişti; ton artık çelik mavi. */
    val vurgu: Color,
    /** Vurgunun parlak kademesi (eski `vioHi`). */
    val vurguHi: Color,
    val cyan: Color,
    val amber: Color,
    val mint: Color,
    val rose: Color,

    // ---- mesh ----
    // Konuma göre adlandırıldı (renk adları revizyonda yalan söylemeye
    // başlamıştı): 1 sol üst (baskın), 2 sağ üst, 3 sol alt, 4 sağ alt (sıcak
    // karşı ağırlık — maviler tekdüzeleşmesin diye tek ılık leke).
    val mesh1: Color,
    val mesh2: Color,
    val mesh3: Color,
    val mesh4: Color,
    /** Mesh lekelerinin toplam gücü. Açık temada zemin okunur kalsın diye düşük. */
    val meshGucu: Float,

    // ---- cam malzemesi ----
    /** Kaydırılan cam görünümlü yüzeylerin dolgusu (kart, çip, balon). */
    val yuzey1: Color,
    /** Tuş ve rozet dolgusu — yuzey1'den bir tık belirgin. */
    val yuzey2: Color,
    /** Tuş/kart kenarlığı. */
    val cizgi: Color,
    /** İç kutuların (metin alanı, kod bloğu) ince kenarlığı. */
    val cizgiInce: Color,
    /** Camın üst kenarındaki 1dp'lik ışık hattı. */
    val spekular: Color,
    /** Lens rim gradyanının dört durağı (135°). */
    val rim1: Color,
    val rim2: Color,
    val rim3: Color,
    val rim4: Color,
    /** Gömük kutu zemini: metin alanı, kod bloğu, komut kutusu. */
    val kuyu: Color,
    /** Sheet arkasındaki karartma. */
    val perde: Color,
    /** Sheet tutamağı. */
    val tutamak: Color,
    /** Gren dokusunun opaklığı (0-255). */
    val grenOpaklik: Int,

    // ---- birincil aksiyon ----
    /** Onay kartındaki birincil tuşun zemini (koyuda beyaz, açıkta koyu). */
    val birincilZemin: Color,
    /** O tuşun üstündeki yazı. */
    val birincilMurekkep: Color,
) {
    /** Birincil aksiyon dolgusu. Kovan'da gradyan yok: düz vurgu. Brush
     *  tipi çağıranlar değişmesin diye kaldı. */
    val grad: Brush get() = SolidColor(vurgu)

    // Durum rolleri — ANLAM sabit, ton temaya göre değişir.
    val running: Color get() = cyan
    val attention: Color get() = amber
    val done: Color get() = mint
    val danger: Color get() = rose
}

/**
 * Koyu palet: Kovan'ın koyu teması (7 Kas 2026, kullanıcı isteği "Kovan'ın
 * UI'sini Telekumanda'ya geçir"). Kaynak Kovan mobil `src/theme.ts`.
 *
 * Cam yok: yüzeyler opak, kenar düz 1dp çizgi. Rim/spekular rolleri
 * duruyor ama düz kenara eşit (rim) ya da saydam (spekular); malzeme
 * dosyası onları artık okumuyor, alanlar API uyumu için kaldı.
 */
val Ui3PaletKoyu = Ui3Palet(
    koyuMu = true,
    bg0 = Color(0xFF161514),
    ink = Color(0xFFECEBE7),
    ink2 = Color(0xFFC2BEB5),
    ink3 = Color(0xFF9B978E),
    vurgu = Color(0xFFD9893F),
    vurguHi = Color(0xFFE8A566),
    // Durum seti Kovan'ın sıcak zeminine göre: adaçayı yeşili bağlı/bitti
    // noktasıyla aynı aile, onay rengi vurgunun kendisi (Kovan'da izin
    // kartı vurgu kenarlıydı).
    cyan = Color(0xFF7DB3A8),
    amber = Color(0xFFE0A04F),
    mint = Color(0xFF6DB27A),
    rose = Color(0xFFF2827A),
    mesh1 = Color.Transparent,
    mesh2 = Color.Transparent,
    mesh3 = Color.Transparent,
    mesh4 = Color.Transparent,
    meshGucu = 0f,
    yuzey1 = Color(0xFF1F1E1C),
    yuzey2 = Color.White.copy(alpha = 0.06f),
    cizgi = Color(0xFF34322E),
    cizgiInce = Color(0xFF2B2A27),
    spekular = Color.Transparent,
    rim1 = Color(0xFF34322E),
    rim2 = Color(0xFF34322E),
    rim3 = Color(0xFF34322E),
    rim4 = Color(0xFF34322E),
    kuyu = Color(0xFF121110),
    perde = Color.Black.copy(alpha = 0.50f),
    tutamak = Color.White.copy(alpha = 0.25f),
    grenOpaklik = 0,
    birincilZemin = Color(0xFFD9893F),
    birincilMurekkep = Color(0xFF1A1208),
)

/**
 * Açık palet: Kovan'ın açık teması. Sıcak kırık beyaz zemin, beyaz kart,
 * ince bej çizgi, kahve-amber vurgu. Metin renkleri beyaz kartta ve zeminde
 * WCAG 4.5'in üstünde (ink3 #6D6A63: kartta 5.4, zeminde 5.0).
 */
val Ui3PaletAcik = Ui3Palet(
    koyuMu = false,
    bg0 = Color(0xFFF6F5F2),
    ink = Color(0xFF1D1C1A),
    ink2 = Color(0xFF4A4844),
    ink3 = Color(0xFF6D6A63),
    vurgu = Color(0xFF9E5615),
    vurguHi = Color(0xFF7F4410),
    cyan = Color(0xFF2F6B63),
    amber = Color(0xFF9E5615),
    mint = Color(0xFF3B7A4A),
    rose = Color(0xFFB3261E),
    mesh1 = Color.Transparent,
    mesh2 = Color.Transparent,
    mesh3 = Color.Transparent,
    mesh4 = Color.Transparent,
    meshGucu = 0f,
    yuzey1 = Color.White,
    yuzey2 = Color.Black.copy(alpha = 0.05f),
    cizgi = Color(0xFFDEDBD3),
    cizgiInce = Color(0xFFE8E5DF),
    spekular = Color.Transparent,
    rim1 = Color(0xFFDEDBD3),
    rim2 = Color(0xFFDEDBD3),
    rim3 = Color(0xFFDEDBD3),
    rim4 = Color(0xFFDEDBD3),
    kuyu = Color(0xFFF1EFEA),
    perde = Color.Black.copy(alpha = 0.32f),
    tutamak = Color.Black.copy(alpha = 0.20f),
    grenOpaklik = 0,
    birincilZemin = Color(0xFF9E5615),
    birincilMurekkep = Color.White,
)

/**
 * static: palet çalışma zamanında nadiren değişir (yalnız sistem teması
 * dönünce). `staticCompositionLocalOf` okuyanları izlemez, değişince ağacı
 * baştan kurar — sık okunan bir değer için doğru olan bu.
 */
val LocalUi3Palet = staticCompositionLocalOf { Ui3PaletKoyu }

/**
 * ui3 tema kapsayıcısı. Sistem temasını takip eder (kullanıcı kararı).
 *
 * ui2'nin tema katmanından bağımsız: ui2 kendi `Ui2` sağlayıcısını kullanıyor
 * ve ikisi aynı anda çizilmiyor.
 */
@Composable
fun Ui3Tema(icerik: @Composable () -> Unit) {
    val palet = if (isSystemInDarkTheme()) Ui3PaletKoyu else Ui3PaletAcik
    CompositionLocalProvider(LocalUi3Palet provides palet, content = icerik)
}

/**
 * Eski `Ui3Colors.x` çağrı yolunu koruyan cephe.
 *
 * Bütün ui3 dosyaları `Ui3Colors.ink` gibi yazıyor; alan adlarını değiştirmek
 * yerine erişimciler paletten okuyor. `@ReadOnlyComposable`: bu getter'lar
 * kompozisyona yazmaz, okuma maliyeti sabit erişime yakın kalır.
 *
 * DİKKAT: artık composable — `DrawScope` lambdası gibi composable OLMAYAN
 * bağlamlarda çağrılamaz. Oralarda değer önce yerel bir `val`e alınmalı
 * (MeshBackground ve GlassSurface bu yüzden paleti dışarıda okuyor).
 */
object Ui3Colors {
    val palet: Ui3Palet @Composable @ReadOnlyComposable get() = LocalUi3Palet.current

    val bg0: Color @Composable @ReadOnlyComposable get() = palet.bg0
    val ink: Color @Composable @ReadOnlyComposable get() = palet.ink
    val ink2: Color @Composable @ReadOnlyComposable get() = palet.ink2
    val ink3: Color @Composable @ReadOnlyComposable get() = palet.ink3

    val vurgu: Color @Composable @ReadOnlyComposable get() = palet.vurgu
    val vurguHi: Color @Composable @ReadOnlyComposable get() = palet.vurguHi
    val cyan: Color @Composable @ReadOnlyComposable get() = palet.cyan
    val amber: Color @Composable @ReadOnlyComposable get() = palet.amber
    val mint: Color @Composable @ReadOnlyComposable get() = palet.mint
    val rose: Color @Composable @ReadOnlyComposable get() = palet.rose

    val running: Color @Composable @ReadOnlyComposable get() = palet.running
    val attention: Color @Composable @ReadOnlyComposable get() = palet.attention
    val done: Color @Composable @ReadOnlyComposable get() = palet.done
    val danger: Color @Composable @ReadOnlyComposable get() = palet.danger

    val grad: Brush @Composable @ReadOnlyComposable get() = palet.grad

    val yuzey1: Color @Composable @ReadOnlyComposable get() = palet.yuzey1
    val yuzey2: Color @Composable @ReadOnlyComposable get() = palet.yuzey2
    val cizgi: Color @Composable @ReadOnlyComposable get() = palet.cizgi
    val cizgiInce: Color @Composable @ReadOnlyComposable get() = palet.cizgiInce
    val kuyu: Color @Composable @ReadOnlyComposable get() = palet.kuyu
    val perde: Color @Composable @ReadOnlyComposable get() = palet.perde
    val tutamak: Color @Composable @ReadOnlyComposable get() = palet.tutamak
    val birincilZemin: Color @Composable @ReadOnlyComposable get() = palet.birincilZemin
    val birincilMurekkep: Color @Composable @ReadOnlyComposable get() = palet.birincilMurekkep
}
