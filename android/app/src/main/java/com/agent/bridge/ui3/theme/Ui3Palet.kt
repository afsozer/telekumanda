package com.agent.bridge.ui3.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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
    /** Vurgu→petrol gradyanı: yalnız birincil aksiyon ve canlılıkta. */
    val grad: Brush get() = Brush.linearGradient(listOf(vurgu, cyan))

    // Durum rolleri — ANLAM sabit, ton temaya göre değişir.
    val running: Color get() = cyan
    val attention: Color get() = amber
    val done: Color get() = mint
    val danger: Color get() = rose
}

/**
 * Koyu palet — ui3'ün ilk ve referans teması. Cam/malzeme alfaları mockup ve
 * cihaz ölçümlerinden geliyor, dokunulmadı; RENKLER 17.08.2026 revizyonunda
 * profesyonel sete çevrildi (dosya başındaki not).
 */
val Ui3PaletKoyu = Ui3Palet(
    koyuMu = true,
    bg0 = Color(0xFF060609),
    // Mürekkepler lavantadan nötr soğuk griye: menekşe alt tonu paletle
    // birlikte çıktı, aydınlıklar korundu (ink3 4.94 kartta / 5.59 düzde).
    ink = Color(0xFFF3F5F9),
    ink2 = Color(0xFFA9B1C2),
    ink3 = Color(0xFF7D8799),
    vurgu = Color(0xFF6C95BE),
    vurguHi = Color(0xFF93B5D8),
    // Durum seti kısık: petrol (çalışıyor), altın (onay), adaçayı (bitti),
    // kiremit (hata). Hepsi kartta ≥5.6 : 1 — anlam ayrımı kayıpsız.
    cyan = Color(0xFF56AEA4),
    amber = Color(0xFFD2A354),
    mint = Color(0xFF66A98A),
    rose = Color(0xFFD97D75),
    // Neonların aydınlığı doygunluk yerine AÇIKLIKLA korundu: mesh çok
    // koyulaşırsa camın kıracağı ışık kalmıyor (açık temada %35 denemesinde
    // ölçülen ders). İlk taslak (#33628F ailesi) önizlemede tam da bu yüzden
    // söndü, bir kademe aydınlatıldı.
    mesh1 = Color(0xFF3E76B0),
    mesh2 = Color(0xFF2E96A0),
    mesh3 = Color(0xFF2F62B0),
    mesh4 = Color(0xFFA57C42),
    meshGucu = 1f,
    yuzey1 = Color.White.copy(alpha = 0.05f),
    yuzey2 = Color.White.copy(alpha = 0.07f),
    cizgi = Color.White.copy(alpha = 0.10f),
    cizgiInce = Color.White.copy(alpha = 0.07f),
    spekular = Color.White.copy(alpha = 0.16f),
    rim1 = Color.White.copy(alpha = 0.60f),
    rim2 = Color.White.copy(alpha = 0.07f),
    rim3 = Color.White.copy(alpha = 0.02f),
    rim4 = Color.White.copy(alpha = 0.34f),
    kuyu = Color.Black.copy(alpha = 0.42f),
    perde = Color.Black.copy(alpha = 0.45f),
    tutamak = Color.White.copy(alpha = 0.30f),
    grenOpaklik = 13,
    birincilZemin = Color.White,
    birincilMurekkep = Color(0xFF0A0E14),
)

/**
 * Açık palet.
 *
 * İki şey basit ton çevirmesi DEĞİL:
 *  - **Vurgu renkleri koyulaştı.** Koyu temanın orta tonları açık zeminde
 *    okunmaz; her renk WCAG 4.5 tabanının üstüne inecek kadar koyultuldu
 *    (tablo plandaki renk revizyonunda). Anlam eşlemesi (petrol=çalışıyor,
 *    altın=onay, adaçayı=bitti) korunuyor.
 *  - **Cam ters çevrildi.** Koyuda cam beyaz alfayla aydınlanıyor; açıkta beyaz
 *    üstüne beyaz kaybolur, o yüzden dolgu ve kenar siyah alfaya döndü. Üst
 *    spekular hattı beyaz kaldı — cam yüzeyin üstten ışık aldığı hissi oradan
 *    geliyor ve açık zeminde de doğru.
 *
 * Mesh gücü %62: koyu temanın tamamı açık zeminde metnin arkasında kirli
 * duruyordu, ama önce %35'e indirilince CAM GÖRÜNMEZ oldu — camın arkasında
 * bulanıklaştıracak renk kalmıyordu ve açık zemin üstündeki blur açık gri bir
 * panele dönüşüyordu (cihazda ölçüldü). %62 ikisinin arası: metin okunur,
 * cam arkasında renk süzülüyor.
 */
val Ui3PaletAcik = Ui3Palet(
    koyuMu = false,
    // Zemin lavanta beyazından mavi-gri beyaza (#F4F2FA → #F2F4F8): menekşe
    // alt tonunun son izi buradaydı.
    bg0 = Color(0xFFF2F4F8),
    ink = Color(0xFF131820),
    ink2 = Color(0xFF454F5E),
    // 4.71 : 1 cam kart zemininde, 5.43 : 1 düz zeminde.
    ink3 = Color(0xFF5A6474),
    vurgu = Color(0xFF2E5A88),
    vurguHi = Color(0xFF23486E),
    cyan = Color(0xFF176B64),
    amber = Color(0xFF7D5915),
    mint = Color(0xFF276B50),
    rose = Color(0xFF9C443D),
    // Koyu temanın mesh'inden bir tık canlı: %62 güçle süzülünce aynı
    // yumuşaklığa iniyor.
    mesh1 = Color(0xFF4F86C2),
    mesh2 = Color(0xFF35A5AE),
    mesh3 = Color(0xFF4478C4),
    mesh4 = Color(0xFFB8905A),
    meshGucu = 0.62f,
    // Açık temada kart dolgusu SİYAH değil BEYAZ: koyu temada cam, zeminden
    // daha AYDINLIK olduğu için okunuyor. Açıkta siyah alfa (%4) denendi ve
    // kartlar zeminden ayırt edilemedi — "Merkez'de hiç glass yok" (kullanıcı).
    // Buzlu beyaz + koyu ince kenar, açık zeminde camın doğru karşılığı.
    yuzey1 = Color.White.copy(alpha = 0.58f),
    // yuzey2 kartların ÜSTÜNDEKİ tuş/rozet dolgusu: beyaz kart üstünde beyaz
    // kaybolur, o yüzden burada siyah alfa kalıyor.
    yuzey2 = Color.Black.copy(alpha = 0.06f),
    cizgi = Color.Black.copy(alpha = 0.12f),
    cizgiInce = Color.Black.copy(alpha = 0.08f),
    spekular = Color.White.copy(alpha = 0.75f),
    rim1 = Color.Black.copy(alpha = 0.16f),
    rim2 = Color.Black.copy(alpha = 0.05f),
    rim3 = Color.Black.copy(alpha = 0.02f),
    rim4 = Color.Black.copy(alpha = 0.10f),
    kuyu = Color.Black.copy(alpha = 0.06f),
    perde = Color.Black.copy(alpha = 0.28f),
    tutamak = Color.Black.copy(alpha = 0.24f),
    grenOpaklik = 6,
    birincilZemin = Color(0xFF131820),
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
