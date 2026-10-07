> **ARŞİV — 10.08.2026.** Bu plan uygulandı ve sonra geri alındı.
> Compose Desktop istemcisi (`android/desktop`) 10.08.2026'da tümüyle
> silindi; yerini köprünün `/ui` altında servis ettiği tarayıcı arayüzü
> (`web/`) aldı. Gerekçe: masaüstü UI'ı Android'in çok gerisinde kalmıştı
> ve her ekran iki kez yazılıyordu. Belge tarihsel kayıt olarak duruyor;
> jpackage/MSI hattı ve `/update/desktop` uçları artık YOK.

# Desktop Client Planı (Compose Multiplatform)

> Tarih: 2026-07-21 · Durum: ONAY BEKLİYOR (uygulamaya başlanmadı)
> Hedef: AgentBridge'in Windows/Mac/Linux masaüstü istemcisi — mevcut Kotlin
> kod tabanını azami yeniden kullanarak.

## 0. Karar Özeti

| Karar | Seçim | Gerekçe |
|---|---|---|
| Teknoloji | **Compose Multiplatform (CMP) Desktop** | 99 Kotlin dosyasının 77'si saf mantık/Compose; OkHttp zaten JVM; delegate deseni platform bağımsız |
| Kod paylaşımı | Faz 1'de **mantık**, Faz 2+'da **UI** | Riski kademelendirir; Faz 1 sonunda Android app davranışı birebir aynı kalır |
| Modül yapısı | Mevcut `android/` Gradle kökünde `:shared`, `:desktop` modülleri | Tek gradlew, release.mjs bozulmaz; ileride kök taşınabilir |
| Ağ katmanı | OkHttp aynen (REST + WS) | 6 dosya, JVM'de değişiksiz çalışır |
| JSON | `org.json:json` artifact'ı eklenir | Android'de framework'te gömülü; desktop JVM'de YOK — açık bağımlılık şart |
| Markdown | Markwon → ortak renderer (aşağıda) | Markwon Android-only (5 dosya) |
| Backend | Bridge'e dokunulmaz (Faz 4'e kadar) | Bridge zaten istemciden bağımsız; pairing/token aynen kullanılır |

## 1. Mevcut Durum (ölçüldü, 2026-07-21)

- `android/app/src/main/java`: **99 .kt dosyası**
  - **22'si** Android'e bağımlı (`android.*` / non-compose `androidx.*` import)
  - **77'si** saf mantık veya saf Compose
- `org.json` kullanan: **15 dosya** (BridgeClient* dahil) — dikkat: Android'in
  org.json implementasyonu ile Maven `org.json:json` birebir aynı değil,
  testle doğrulanmalı
- OkHttp kullanan: 6 dosya (REST: `BridgeClient*`, WS: `SessionStreamManager`)
- Markwon kullanan: 5 dosya
- Room kullanan: **1 dosya** (`OfflineConversationCache`)
- Navigation Compose kullanan: **1 dosya** (`Ui2Root`)
- ViewModel türeyen: `RemoteViewModel` (1741 satır; 17 delegate'e dağıtılmış)
- Delegate imzası platform bağımsız:
  `(client: BridgeClient, scope: CoroutineScope, state: () -> RemoteUiState, update: ((RemoteUiState) -> RemoteUiState) -> Unit)`
- Sürümler: Kotlin 1.9.24, Compose BOM 2024.06, M3, Nav 2.7.7, Room 2.6.1,
  OkHttp 4.12, JVM 17

## 2. Hedef Modül Yapısı

```text
android/                     (Gradle kökü — mevcut)
├── app/                     :app     Android uygulaması (mevcut, incelir)
├── shared/                  :shared  Saf JVM kütüphanesi (Faz 1) → KMP+CMP (Faz 2)
└── desktop/                 :desktop Compose Desktop uygulaması (Faz 2+)
```

- Faz 1'de `:shared` **düz Kotlin/JVM** modülüdür (`org.jetbrains.kotlin.jvm`):
  Android'in de desktop'ın da bağlanabildiği en basit biçim. KMP törenine
  gerek yok — iki hedef de JVM.
- Faz 2'de UI paylaşımı istenirse `:shared` CMP modülüne dönüştürülür
  (`org.jetbrains.compose` eklentisi, `androidTarget` + `jvm`).

### Platform arayüzleri (Faz 1'de tanımlanır, `:shared` içinde)

| Arayüz | Android gerçeklemesi | Desktop gerçeklemesi |
|---|---|---|
| `KeyValueStore` | SharedPreferences | `~/.agentbridge/desktop-settings.json` |
| `Notifier` | NotificationManager | Sistem tepsisi balonu (AWT `TrayIcon.displayMessage`) |
| `ConversationCache` | Room (mevcut) | JSONL dosyaları (`~/.agentbridge/cache/`) |
| `FileOpener` | Intent/FileProvider | `java.awt.Desktop.open` |
| `AttachmentPicker` | SAF (content resolver) | `java.awt.FileDialog` |

## 3. Fazlar

### Faz 0 — Hazırlık (küçük)
1. `:app`'e `implementation("org.json:json:20240303")` EKLENMEZ (Android'de
   çakışır); bunun yerine org.json kullanımının tamamının `:shared`'a inecek
   dosyalarda olduğu doğrulanır ve `:shared`'ın JVM bağımlılığı yapılır.
   Android tarafında framework'teki sürüm kullanılmaya devam eder.
2. Version catalog (`libs.versions.toml`) — üç modül aynı sürümleri görsün.
3. Mevcut JVM unit testlerinin envanteri (hangileri `:shared`'a taşınacak).

### Faz 1 — `:shared` ayrıştırması (işin ~%40'ı, mekanik)
Taşınacaklar (davranış değişikliği YOK, salt taşıma):
- Modeller/mantık: `ChatUiModels`, `RemoteUiState`, `SessionStateReducer`,
  `ChatSearchLogic`, `ConversationPaging`, `BackendCatalog`, `BackendSessions`,
  `BackendSessionState`, `HubModels`, `FileBrowserModels`, `FolderPickerModels`,
  `GlobalSearchModels`, `SettingsModels`, `NotificationCursor`,
  `QuestionAnswerDraft`, `CoworkUiLogic`, `SessionRefreshDelegate` vb. (77 saf
  dosyanın UI olmayanları)
- Ağ: `BridgeClient*` (2660 satır), `SessionStreamManager`
- Delegate'ler: 17 delegate'ten Android importu olmayan tamamı;
  `TabsDelegate` prefs kullanıyor → `KeyValueStore` arayüzüne çekilip taşınır
- `RemoteViewModel` ikiye bölünür:
  - `RemoteStore` (shared): state akışı + delegate kablolaması + scope
  - `RemoteViewModel` (app): `ViewModel()` türeyen ince sarmalayıcı
  - `SettingsViewModel` aynı desen
- Testler: `app/src/test`'teki saf testler `shared/src/test`'e taşınır
- **Kabul ölçütü:** Android APK derlenir, tüm testler yeşil, davranış birebir

### Faz 2 — Desktop MVP (işin ~%25'i)
`:desktop` modülü (`org.jetbrains.compose`, JVM 17):
- `Main.kt`: `application { Window { ... } }` — pencere durumu kalıcı
- Ayarlar + bağlantı testi + **cihaz eşleştirme** (`/pairing/*` mevcut uçlar)
- Oturum listesi + chat: `SessionStreamManager` WS akışı, mesaj kartları
- Onay kartları (`ApprovalCard` deseni) + izin modu seçimi
- Markdown: `com.mikepenz:multiplatform-markdown-renderer-m3` denenir;
  görsel kabul edilmezse mevcut `Markdown.kt` parser'ı CMP annotated-string
  renderer'ıyla eşlenir (parser zaten büyük ölçüde elde)
- Tema: `Ui2Theme`'in statik paleti (marka menekşesi vurgu; dynamic color
  Android'e özel kalır — desktop SABİT palet, ui-anayasasi kuralına uygun)
- Kalıcılık: `KeyValueStore` dosya gerçeklemesi; token OS keychain'e Faz 4'te
- **Kabul ölçütü:** İkinci bir bilgisayardan bridge'e bağlanıp Claude App
  oturumu açmak, mesajlaşmak, onay vermek, oturum devralmak

### Faz 3 — Parite — DURUM: TAMAM, canlı doğrulandı (2026-07-22)

> Gerçekleşen: `feat/desktop-faz3` branch'i. Çoklu backend + parametrik chat,
> sekme çubuğu/çoklu oturum, Hub (Operasyonlar/Projeler/Kullanım/MCP/Arama),
> proje detay + belge önizleme/açma, Cowork (listele, şablonla oluştur, oturum
> aç, sağlayıcı değiştir, Devret, belge ekle, konu, sil), sohbet ekleri +
> satır içi görsel. Derleme + 188 test yeşil.
>
> **Desktop tasarım ilkesi (kullanıcı kararı):** uygulama ajanla AYNI makinede
> koştuğu için indirme/yükleme YOK — dosyalar yerel yol olarak alışverilir,
> belgeler `java.awt.Desktop` ile varsayılan uygulamada açılır.
>
> **Canlı doğrulandı (2026-07-22, kurulu MSI üzerinde):** sohbet akışı
> (prompt→stream→yanıt), sekme aç/kapa/geçiş, backend değiştirme, Hub'ın beş
> sekmesi, proje detay + belge açma, Cowork oturum aç/sağlayıcı değiştir/Devret,
> ek ekleme + satır içi görsel. Bu turda bug bulunmadı.
>
> **Bilinçli sapma (kullanıcı ilkesi gereği):** plandaki "dosya düzenleyici
> (DocxLite/MarkdownDocumentEditor)" desktop'a taşınmadı — aynı makinede
> koşulduğu için belge `java.awt.Desktop` ile Word/VS Code'da açılıyor.
> Uygulama içi editör yazmak burada net kayıp.


- Sekme çubuğu + çoklu oturum (`TabsDelegate` zaten shared'da)
- Hub: Projects / Operations / Usage / Global Search ekranları
- Cowork: workspace listesi, provider/model seçimi, Devret, dosya yöneticisi
  (sıralama dahil), dosya düzenleyici (`DocxLite`/`MarkdownDocumentEditor`
  saf Compose — büyük oranda aynen)
- MCP yönetim ekranları
- İndirmeler: `DownloadRepo` → `java.nio` + `Desktop.open`
- Navigasyon: `Ui2Root` CMP Navigation'a (org.jetbrains.androidx.navigation)
  veya basit back-stack'e uyarlanır (tek dosya)
- Görseller (`ChatImages`): Coil3 (KMP) veya `skia.Image`
- **Atlananlar (bilinçli):** `BridgeMonitorService` (pencere açıkken gereksiz;
  tepsi ikonu durum gösterir), `UpdateManager`/OTA-APK (Android'e özel),
  `ApprovalReceiver` (bildirim aksiyonu yerine pencere odaklanır)

### Paketleme (Faz 4) — ÇALIŞIYOR, üretildi ve doğrulandı (2026-07-22)

**Komutlar**
```powershell
cd android
.\gradlew :desktop:createDistributable   # uygulama imajı (kurulum gerektirmez)
.\gradlew :desktop:runDistributable      # üretilen imajı çalıştır
.\gradlew :desktop:packageMsi            # kurulabilir MSI
```

**Üretilen çıktılar (doğrulandı)**
- Uygulama imajı: `desktop/build/compose/binaries/main/app/AgentBridge/AgentBridge.exe`
  (+ gömülü JRE `runtime/`, jar'lar `app/`) — toplam ~222 MB
- Kurulum: `desktop/build/compose/binaries/main/msi/AgentBridge-1.0.0.msi` — ~119 MB
  (ProductVersion 1.0.0, UpgradeCode `{7F3A1C2E-…}` doğrulandı)

**Araç zinciri — elle kurulum GEREKMEZ**
- **jpackage:** çalıştıran JVM'de değil, **toolchain JDK 17**'de aranır. Android
  Studio'nun JBR'si jpackage İÇERMEZ; `JAVA_HOME` oraya bakarsa paketleme
  `'jpackage.exe' is missing` ile düşer. Bu yüzden `build.gradle.kts` JDK 17 yolunu
  **Gradle toolchain servisinden programatik** alır (makineye özel yol gömülmez).
  Bu makinede Temurin 17 zaten `~/.jdks/temurin-17` altında kurulu.
- **WiX:** Compose eklentisi WiX'i **kendisi indirir** (`android/build/wix311`).
  Elle WiX kurmaya gerek yok.

**Notlar**
- `packageVersion` **1.0.0** — jpackage MSI'da ana sürümün > 0 olmasını şart koşar
  (önceki `0.1.0` paketlemede hata verirdi).
- `upgradeUuid` sabittir; sürüm yükseltmeleri aynı ürünü güncellesin diye
  değiştirilmemeli.
- `includeAllModules = true` — kurulum büyür ama "modül yok" hatası riski kalmaz;
  istenirse `modules(...)` ile daraltılabilir.
- `perUserInstall = true` — kurulum yönetici hakkı istemez.
- ~~Uygulama ikonu yok~~ — Faz 4'te marka menekşesi `.ico` eklendi.
- **Kurulup çalıştırıldı (2026-07-22):** MSI kullanıcı tarafından yüklendi, masaüstü
  kısayolundan açıldı, tüm Faz 3 akışları kurulu sürüm üzerinde test edildi.

### Faz 4 — Desktop cilası — Durum: paketleme + auto-update + release TAMAM (2026-07-22)

> Gerçekleşen: `feat/desktop-faz3` branch'i üstünde (commit'ler ayrı):
> - **Uygulama ikonu:** Android launcher PNG'sinden (xxxhdpi 192px) Pillow ile
>   üretilen çok-katmanlı `.ico` (16/24/32/48/64/128/256) — `windows { iconFile.set(...) }`
>   ile bağlandı (AbstractPlatformSettings üstünden; nativeDistributions seviyesinde
>   DEĞİL — 1.6.11'de hata veriyor). `createDistributable` ikonlu `.exe` üretiyor.
> - **Otomatik güncelleme (bridge):** `/update/desktop/latest.json` +
>   `/update/desktop/latest.msi` (Android `/update` deseninin aynası; versionCode yok,
>   versionName semantic karşılaştırması). ⚠️ bridge restart gerekir.
> - **Otomatik güncelleme (desktop):** açılışta bir kez kontrol, Ayarlar üstünde
>   güncelleme kartı (indir+kur akışı + ilerleme çizgisi) + Tray bildirimi.
>   `DesktopVersion.VERSION` build-time sabit packageVersion ile aynı.
> - **Release otomasyonu:** `bridge/release-desktop.mjs` (packageVersion +
>   DesktopUpdate.kt VERSION + `:desktop:packageMsi` + MSI kopya + latest.json).
>
> **Canlı doğrulandı (2026-07-22):** `latest.json` geçici olarak 1.0.1 yapılıp
> gerçek HTTP yolu sınandı — açılış denetimi 200, semantic karşılaştırma, güncelleme
> kartı ("İndir, kur ve kapat" + notlar + "Sonra"), ve MSI yokken hata yolu
> ("İndirme başarısız: HTTP 404" → buton tekrar etkin, çökme yok). Test sonrası
> `latest.json` yedekten geri yüklendi. msiexec'in kendisi gerçek 1.0.1 MSI
> gerektirdiği için canlı tetiklenmedi; kod yolu birim testli.
>
> **Kur-ve-kapat kararı:** Windows Installer çalışan uygulamanın dosyalarını
> değiştiremediği için kurulum başlayınca uygulama geometriyi kaydedip kapanıyor
> (`onInstallStarted` → `Main.kt`). Buton etiketi bu yüzden dürüstçe
> "İndir, kur ve kapat".

- ~~Klavye kısayolları~~ (Faz 3'te yapıldı: Ctrl+T yeni sekme, Ctrl+W kapat)
- ~~Sistem tepsisi: bridge durumu + bekleyen onay rozeti~~ (Faz 2/3'te yapıldı)
- ~~Paketleme: `jpackage` (MSI); auto-update: `/update/desktop` kanalı~~ (Faz 4'te yapıldı)
- ~~`release.mjs`'e desktop hedefi~~ (`release-desktop.mjs` olarak yapıldı)
- Token'ı OS credential store'a taşıma (JNA gerektirir — bilinçli olarak atlandı;
  token şu an dosya ACL'iyle korunuyor, kullanıcının kararı)

## 7. Plan kapanışı (2026-07-22)

Faz 0–4 **tamamlandı**, hepsi `feat/desktop-faz3` dalında (uzağa pushlandı).
Kapanışta bilinçli olarak yapılmayanlar — hepsi gerekçeli, "eksik" değil:

| Konu | Karar |
|---|---|
| `ConversationCache` desktop (JSONL çevrimdışı önbellek) | **Yapılmadı.** Desktop bridge'le aynı makinede; çevrimdışı senaryo yok. Android'de kalıyor. |
| Uygulama içi belge düzenleyici | **Yapılmadı.** Belge yerel varsayılan uygulamada açılıyor (kullanıcı ilkesi). |
| Token OS credential store | **Ertelendi.** JNA bağımlılığı istenmedi; dosya ACL'i yeterli görüldü. |
| `BridgeMonitorService` / `ApprovalReceiver` | **Atlandı.** Tepsi ikonu + bildirim yerini alıyor. |

**Açık tek karar:** `feat/desktop-faz3` → `master` merge (56 commit).

## 4. 22 Android-bağımlı dosyanın kaderi

| Dosya | Karar |
|---|---|
| `MainActivity` | Android'de kalır; desktop karşılığı `Main.kt` |
| `RemoteViewModel`, `SettingsViewModel` | Çekirdek `:shared`'a, ince VM sarmalayıcı app'te |
| `TabsDelegate` | `KeyValueStore` arayüzü ile `:shared`'a |
| `Markdown` | Parser `:shared`'a; render platforma |
| `OfflineConversationCache` | `ConversationCache` arayüzü; Room app'te kalır, desktop JSONL |
| `FolderPickerPreferences`, `OpenEditStore` | `KeyValueStore` ile `:shared`'a |
| `DownloadRepo`, `FileUtils`, `AttachmentChip`, `ChatImages` | Arayüz + iki gerçekleme |
| `UpdateManager`, `BridgeMonitorService`, `ApprovalReceiver` | Android'e özel kalır; desktop'ta karşılığı yok/farklı |
| `UsageCards` | Android bağımlılığı küçük (muhtemelen clipboard/intent) — ayıklanıp `:shared`/UI'a |
| `ChatRootScreen`, `HubFilesScreen`, `HubProjectsScreen`, `SettingsGeneralScreens`, `Ui2Root`, `Ui2Theme` | Faz 2'de CMP'ye geçince ortaklaşır; Android-özel kısımlar (dynamic color, back-handler, SAF) `expect/actual` veya parametreyle ayrılır |

## 5. Riskler

1. **org.json implementasyon farkı** (Android ≠ Maven): null/escape köşe
   durumları. Önlem: `:shared` testleri her iki ortamda da (app testi JVM'de
   koştuğu için Maven org.json ile koşacak — fark varsa Faz 1'de yakalanır).
2. **Markwon → CMP renderer görsel farkı**: tablolar/HTML. Önlem: MVP'de
   temel markdown yeterli; tablo/HTML Faz 3 sonunda karşılaştırılır.
3. **CMP sürüm uyumu**: Kotlin 1.9.24 → CMP 1.6.x hattı. Compose BOM
   2024.06 ile API yüzeyi aynı; Nav 2.7.7 kısıtı (plan dosyalarındaki
   "YÜKSELTME notu") desktop'ı etkilemez ama CMP Navigation ayrı sürümdür.
4. **`:shared` ayrıştırmasında gizli Android sızıntısı** (ör. `android.net.Uri`
   bir modelde). Önlem: `:shared` düz JVM modülü olduğu için derleyici
   yakalar — sızıntı derleme hatası olur, sessizce geçemez.
5. **Tek geliştirici + iki UI**: Faz 2'den sonra her özellik iki kez mi
   yazılacak? Faz 2'de UI ortaklaşırsa hayır; ortaklaşmazsa desktop bilinçli
   olarak "çekirdek özellik alt kümesi"nde tutulur.

## 6. Sıralama ve bağımlılıklar

```
Faz 0 ──► Faz 1 (Android birebir yeşil) ──► Faz 2 (MVP kullanılabilir)
                                             └──► Faz 3 (parite) ──► Faz 4 (cila)
```

Her faz kendi başına birleştirilebilir (merge edilebilir) durumda biter;
yarım faz push'lanmaz. Faz 1 en kritik kapı: Android testleri + duman testi
birebir yeşil olmadan Faz 2'ye geçilmez.
