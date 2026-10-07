# Haze 1.5.3 — gerçek API yüzeyi

Bu belge **tahmin değil**: `haze-release.aar` içindeki `classes.jar`'dan `javap`
ile çıkarıldı (16.08.2026, Gradle önbelleğindeki gerçek artefakt).

Neden var: Compose'da arka plan bulanıklığının hazır API'si yok ve Haze'in
sürümler arası adlandırması değişti (`hazeChild` → `hazeEffect`). İnternetteki
örneklerin çoğu eski ada göre yazılmış; ajan doğru adı tahmin etmeye
çalışırken tur harcıyordu.

> **Güven notu:** aşağıdaki **tipler ve fonksiyon adları** ikili dosyadan
> okundu, kesindir. **Parametre adları** javap çıktısında yok — Kotlin
> kaynağından/IDE'den doğrulanmalı. Adı kesin olmayan yerler `?` ile işaretli.
> Kesin doğrulama derlemedir: Faz 1'in kabul kriteri zaten bu.

---

## Çekirdek desen — iki modifier, bir state

Cam iki parçadan oluşur: **kaynak** (bulanıklaşacak olan) ve **efekt** (camın
kendisi). İkisi ortak bir `HazeState` üzerinden konuşur.

```kotlin
val hazeState = remember { HazeState() }   // rememberHazeState YOK, düz sınıf

Box {
    // 1) ARKA PLAN — bulanıklaştırılacak içerik
    MeshBackground(Modifier.fillMaxSize().hazeSource(hazeState))

    // 2) CAM — arkasındakini bulanıklaştıran yüzey
    Box(
        Modifier
            .clip(RoundedCornerShape(26.dp))     // kırpma cam modifier'ından ÖNCE
            .hazeEffect(hazeState) {
                blurRadius = 26.dp
                noiseFactor = 0.05f
                tints = listOf(HazeTint(Color.White.copy(alpha = 0.10f)))
            }
    ) { /* içerik */ }
}
```

---

## Fonksiyonlar

```kotlin
// dev.chrisbanes.haze.HazeKt
Modifier.hazeSource(state: HazeState, zIndex: Float = 0f, key: Any? = null): Modifier
Modifier.haze(state: HazeState): Modifier          // eski, kullanma

// dev.chrisbanes.haze.HazeChildKt
Modifier.hazeEffect(
    state: HazeState,
    style: HazeStyle = /* varsayılan */,
    block: (HazeEffectScope.() -> Unit)? = null,
): Modifier

Modifier.hazeChild(...)   // hazeEffect ile AYNI imza — eski ad, yeni kodda kullanma
```

## `HazeEffectScope` — cam bloğunda ayarlanabilenler

İkili dosyadan okunan setter listesi (tamamı):

```
blurRadius        blurEnabled       tints             fallbackTint
noiseFactor       mask              progressive       inputScale
backgroundColor   alpha             style             canDrawArea
```

ui3 için önemli olanlar:

| alan | ne işe yarar |
|---|---|
| `blurRadius` | bulanıklık yarıçapı (Dp). Anayasa v2: ~26.dp |
| `noiseFactor` | gren. Camın "ucuz plastik" gibi parlamasını öldüren şey |
| `tints` | `List<HazeTint>` — tonlanmış cam (vio/amber/cyan) buradan |
| `mask` | `Brush` — camın nerede etkili olacağını maskeler; kenar kırılması (rim) ve spekular geçiş için doğrudan kullanışlı |
| `progressive` | kademeli bulanıklık (bir kenardan diğerine artan) |
| `inputScale` | **performans**: blur'u küçültülmüş tampona uygular. Kaydırma maliyeti sorun olursa ilk bakılacak ayar |
| `blurEnabled` | blur'u kapatır → `fallbackTint`'e düşer |

## Sınıflar

```kotlin
class HazeState()
    val areas: List<HazeArea>

class HazeTint(color: Color, blendMode: BlendMode? = ?)

class HazeStyle(
    backgroundColor: Color,
    tints: List<HazeTint>,        // tek tint alan aşırı yüklemesi de var
    blurRadius: Dp,
    noiseFactor: Float,
    fallbackTint: HazeTint,
)

object HazeDefaults {
    val blurRadius: Dp
    const val noiseFactor: Float
    const val tintAlpha: Float
    fun tint(color: Color): HazeTint
    fun style(backgroundColor: Color, tint: HazeTint?, blurRadius: Dp, noiseFactor: Float): HazeStyle
}

interface HazeProgressive {
    companion object {
        fun verticalGradient(easing: Easing?, /* 4 × Float */, preferPerformance?: Boolean): LinearGradient
        fun horizontalGradient(easing: Easing?, /* 4 × Float */, preferPerformance?: Boolean): LinearGradient
        fun forShader(block: (Size) -> Shader): Brush
    }
}

interface HazeInputScale { companion object { val Default: HazeInputScale } }
```

`@ExperimentalHazeApi` diye bir işaret var — bazı alanlar (özellikle
`inputScale`, `canDrawArea`) opt-in isteyebilir. Derleyici söyler, uyarıyı
`@OptIn` ile karşıla, susturma.

---

## Dikkat edilecekler

1. **Kırpma sırası.** `clip()` cam modifier'ından **önce** gelmeli; sonra
   gelirse bulanıklık köşelerden taşar.
2. **API 31 altı.** Gerçek blur `RenderEffect`'e dayanıyor (Android 12+).
   Altında Haze `fallbackTint`'e düşer — cam değil, yarı saydam bir ton olur.
   Projede `minSdk = 26`, yani bu yol **canlı**; `fallbackTint` boş bırakılırsa
   eski cihazda yüzey okunmaz hale gelebilir. Test cihazlarının ikisi de API 36,
   yani bu gözle görülmeyecek — ölçüyle karar ver.
3. **Kaynak olmadan efekt boş çıkar.** `hazeSource` verilmemiş bir
   `hazeEffect` bulanıklaştıracak bir şey bulamaz; ekran boş/düz görünür ve
   bu sessiz bir hatadır.
4. **Her cam yüzey bedel demektir.** Kaydırılan listede öğe başına
   `hazeEffect` kullanma — anayasa v2 bölüm 1.3 bunu kural olarak yasaklıyor.
5. **`hazeChild` gördüğün her örnek eskidir.** İmzası aynı, adı değişti.
