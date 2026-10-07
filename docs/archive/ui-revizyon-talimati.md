# ARŞİV — UI Revizyon Talimatı (Faz A–D)

> Uygulayıcı: Gemini (Agy). Denetleyici: Claude. Bu belge tek yetkili talimat
> kaynağıdır; belirsizlikte bu belgeye dön, tahmin yürütme.

## 0. Bağlam ve sınırlar

Proje: `C:\Users\<you>\agtest` — Android (Jetpack Compose, Material 3) telefon
uygulaması + Node bridge. Bu revizyon YALNIZ Android UI katmanını kapsar.

**Dokunulmayacaklar (kesin yasak):**

- `bridge/` altındaki hiçbir dosya değiştirilmez.
- `BridgeClient*.kt` içindeki istek/yanıt kontratları (endpoint yolları, JSON
  alan adları) değiştirilmez.
- `RemoteViewModel` fonksiyon imzaları ve `RemoteUiState` alan adları
  değiştirilmez (yeni alan EKLENEBİLİR, mevcut alan silinip yeniden
  adlandırılamaz).
- Kod içindeki backend kimlikleri (`"claude-app"`, `"codex-app"`,
  `"opencode-app"`, `"zcode"`, `"agy"`, `"cowork"`, `"chatgpt-planner"`,
  `"claude-desktop-cowork"`) ve API string'leri ASLA çevrilmez/değiştirilmez.
  Sözlük yalnız KULLANICIYA GÖRÜNEN metinler içindir.
- `release.mjs` çalıştırılmaz, bridge restart edilmez, git push yapılmaz.
- Değişmez kontrol ilkesi (docs/vision-roadmap.md): sağlayıcı/model otomatik
  seçen, öneren veya değiştiren HİÇBİR davranış eklenmez.

**Bu turda BİLİNÇLİ OLARAK yapılmayacaklar (ekleme, başlama, hazırlık yok):**

- Alt navigasyon çubuğu / sheet mimarisinin değişmesi (ayrı tur).
- Cowork ile Proje Merkezi'nin birleştirilmesi (ürün kararı bekliyor).

**Çalışma kuralları:**

- Dosya düzenlemelerinde satır-numarası/sed tabanlı yama YASAK; tam bağlam
  eşleşmeli düzenleme kullan.
- Her fazın sonunda doğrulama komutu çalıştırılır ve GEÇMEDEN sonraki faza
  geçilmez:
  `cd android && .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
- Her faz ayrı commit olur. Commit mesajı Türkçe, diakritiksiz, şu kalıpta:
  `fix(ui): <faz harfi> - <kisa ozet>`. Push yok.
- Mevcut unit testler bozulursa testi silme/gevşetme; kodu düzelt. Davranış
  bilinçli değiştiyse testi davranışla birlikte güncelle ve commit mesajında
  belirt.

## Faz A — Terminoloji sözlüğü + boşluk ölçeği

### A1. Terminoloji

Kullanıcıya görünen TÜM metinlerde aşağıdaki sözlük uygulanır. Arama kapsamı:
`android/app/src/main/java/com/agent/bridge/**` içindeki string literal'ler.

| Eski (karışık) | Yeni (standart) |
|---|---|
| provider, Provider | sağlayıcı |
| backend (kullanıcıya görünen) | sağlayıcı |
| workspace (kullanıcıya görünen) | çalışma alanı |
| output/outputs (kullanıcıya görünen) | teslimat |
| cwd / working directory (görünen) | proje klasörü |
| session (görünen, tekil İngilizce kalanlar) | oturum |

İstisnalar (OLDUĞU GİBİ KALIR): klasör adı olarak `outputs/`, `.cowork/`,
dosya yolları, log/audit ham değerleri, "MCP", "APK", "OTA", "PID", "CPU",
ürün adları (Claude, Codex, OpenCode, Cowork, Z-Code, Planner, Antigravity),
izin modu kimlikleri (`yolo` vb.) ve tüm kod tanımlayıcıları.

Örnek dönüşümler:

- "Provider seçimi projeye girince yine sana aittir" →
  "Sağlayıcı seçimi projeye girince yine sana aittir"
- "Profil projeyle saklanır; Uygula işlemi provider'ın global MCP ayarını..." →
  "...Uygula işlemi sağlayıcının global MCP ayarını..."
- "Provider tarafından yönetiliyor" → "Sağlayıcı tarafından yönetiliyor"

### A2. Boşluk ölçeği ve ortak başlıklar

`ui/Ds.kt` adında yeni dosya oluştur (package `com.agent.bridge.ui`):

```kotlin
// Tasarim sistemi sabitleri: ekranlar kendi degerini uydurmaz, buradan alir.
object Ds {
    val screenPadding = 20.dp   // ekran/sheet yatay dis bosluk
    val sheetBottom = 36.dp     // sheet alt bosluk (gesture alani)
    val sectionGap = 12.dp      // dikey blok araligi
    val cardInner = 14.dp       // kart ic dolgusu
    val rowGap = 8.dp           // yatay eleman araligi
}
```

İki ortak bileşen ekle (aynı dosyaya):

- `SheetHeader(title, subtitle?, onRefresh?)`: sheet üst satırı — başlık
  Column'u `weight(1f)` alır, sağdaki "Yenile" `TextButton(maxLines=1,
  softWrap=false)`. `OperationsSheet` ve `ProjectsSheet`'teki (`ProjectsUi.kt`)
  el yapımı başlık satırları bununla değiştirilir.
- `SectionHeader(text)`: bölüm içi `titleMedium + SemiBold` başlık; ProjectsUi
  ve OperationsUi'daki tekrar eden `Text(..., titleMedium, SemiBold)` satırları
  bununla değiştirilir. (LandingScreen'deki `SectionLabel` — büyük harfli etiket
  — FARKLI bir bileşendir, ona dokunma.)

Uygulama kapsamı: `LandingScreen.kt`, `OperationsUi.kt`, `ProjectsUi.kt` bu
sabitlere geçirilir (24dp→20dp ekran dolgusu dahil). Diğer ekranlar bu turda
zorunlu değil; dokunduğun yerde tutarlılaştır, dokunmadığın ekrana girme.

### Faz A kabul kriterleri

- [ ] Kullanıcıya görünen metinlerde "provider/backend/workspace/output"
      İngilizce olarak kalmadı (istisna listesi hariç).
- [ ] Kod kimlikleri ve API string'leri byte-byte aynı (git diff'te yalnız
      UI string ve layout satırları değişmiş olmalı).
- [ ] `Ds`, `SheetHeader`, `SectionHeader` üç ekranda kullanımda.
- [ ] Doğrulama komutu geçti.

## Faz B — Proje detayı sekmeleri

Sorun: `ProjectsUi.kt` → `ProjectDetailView` tek uzun sütun; form (ad,
güvenlik, MCP) üstte, asıl içerik (oturumlar, teslimatlar) altta kalıyor.

Yapılacak: detay görünümü üç sekmeye bölünür (Material 3 `SecondaryTabRow`
veya `TabRow`; seçim `remember { mutableStateOf(0) }` ile yerel, proje
değişince sıfırlanır: `remember(project.id)`).

1. **Genel** (varsayılan sekme): proje yolu satırı, Oturumlar, Teslimatlar,
   Değişiklikler, Komutlar, Plan. (Mevcut `ArtifactSection` blokları buraya.)
2. **Güvenlik & MCP**: proje adı düzenleme, güvenlik profili bloğu, Özel
   politika alanları, MCP profili bloğu. Mevcut davranış AYNEN korunur:
   ikinci onay diyalogları, `FilterChip` seçimleri, LaunchedEffect'ler.
   MCP LaunchedEffect'leri yalnız bu sekme görünürken çalışmalı (sekme
   index'ini effect key'ine ekle) — sekme açılmadan ağ çağrısı yapılmasın.
3. **Günlük**: denetim günlüğü listesi (mevcut `detail.audit` bloğu).
   Boşsa "Bu projede henüz denetim kaydı yok." metni.

Geri düğmesi (`Projeler`) ve başlık sekmelerin ÜSTÜNDE sabit kalır.

### Faz B kabul kriterleri

- [ ] Üç sekme var; varsayılan Genel; proje değişince Genel'e döner.
- [ ] Hiçbir eylem kaybolmadı: ad kaydet, profil uygula (tam erişim onayı),
      özel politika, MCP listele/uygula (global onay), teslimat indir, oturum Aç.
- [ ] MCP sunucu listesi yalnız Güvenlik & MCP sekmesi açıkken yükleniyor.
- [ ] Doğrulama komutu geçti.

## Faz C — Ana sayfada canlı ajan durumu

Sorun: ajan kartları statik; "ne çalışıyor" bilgisi ayrı sheet'lerde.

Yapılacak: `LandingScreen.kt` → `AgentCard`'a opsiyonel durum parametreleri
ekle: `runningCount: Int = 0`, `needsAttention: Boolean = false`.

- Veri kaynağı YALNIZ mevcut state: `uiState.operations.operations`
  (alanlar: `backend`, `status`; `status` değerleri: `running`, `waiting`,
  `failed`). Yeni ağ çağrısı, yeni endpoint, ViewModel değişikliği YOK.
- Landing'de ajan listesi çizilirken backend id'ye göre grupla:
  `running = ops.count { it.backend == info.id && it.status == "running" }`,
  `needsAttention = ops.any { it.backend == info.id && it.status == "waiting" }`.
  Cowork kartı için `apiBackend` eşlemesi kullanılmaz; cowork bu gösterimde
  kapsam dışı (kartı sade kalır).
- Görsel: ikon dairesinin sağ-alt köşesinde 10dp nokta — çalışıyorsa
  `primary`, onay bekliyorsa `tertiary`; ikisi de varsa `tertiary` öncelikli.
  Kart başlığının altına tek satır `labelSmall`: "2 oturum çalışıyor" /
  "Yanıt bekliyor". Hiçbiri yoksa alt satır ve nokta HİÇ çizilmez (boş satır
  bırakma, kart yüksekliği eskisi gibi kalsın).
- Kartın tıklama davranışı değişmez.

### Faz C kabul kriterleri

- [ ] Çalışan oturumu olan sağlayıcı kartında nokta + sayı görünüyor.
- [ ] Boşta sağlayıcı kartı bugünkünden farksız.
- [ ] Yeni ağ çağrısı eklenmedi (BridgeClient diff'i boş).
- [ ] Doğrulama komutu geçti.

## Faz D — Boş ve yükleniyor durumları

1. **Operasyon sheet'i**: `result.operations` boşsa "Şu an" bölümü yerine
   "Şu an çalışan veya bekleyen operasyon yok." (`onSurfaceVariant`) göster.
2. **Yükleme/0 ayrımı**: `RemoteUiState`'e `operationsLoaded: Boolean = false`
   alanı ekle; `loadOperations` başarılı olunca `true` yapılır (imza değişmez).
   - Landing Operasyon Kutusu alt metni: `operationsLoaded == false` iken
     sayılar yerine "Yükleniyor…".
   - Proje Merkezi kartı aynı desen: `projectsLoading && projectSummaries
     .isEmpty()` iken "Yükleniyor…", değilse mevcut sayılar.
3. **Proje detayı**: `projectDetailLoading == true` ve `selectedProjectDetail
   == null` iken sheet ortasında `CircularProgressIndicator` (şu an boş sheet
   açılabiliyor).

### Faz D kabul kriterleri

- [ ] İlk açılışta "0 çalışıyor" yanıp sönmesi yok; "Yükleniyor…" görünüyor.
- [ ] Boş operasyon listesi sessiz değil, açıklamalı.
- [ ] Doğrulama komutu geçti; yeni alan mevcut testleri bozmadı.

## Teslim

Dört faz = dört commit. İş bitince `git log --oneline -5` çıktısını ve her
fazın kabul kriterleri karşısındaki durumunu kısa raporla. Kararsız kaldığın
her noktada değişiklik yapmak yerine raporda "SORU:" satırı bırak.
