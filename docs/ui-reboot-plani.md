# UI Reboot Planı — Motoru Koru, Arayüzü Sıfırdan Yaz

> Amaç: Kullanıcının UI memnuniyetsizliğine "fresh breath" cevabı vermek.
> Strateji: ağ/akış/durum motoru (BridgeClient*, SessionStreamManager,
> delegatelar, testler) AYNEN korunur; ekran katmanı (ChatScreen, LandingScreen,
> StartSheets, sheet ormanı) sıfırdan, tutarlı bir tasarım diliyle yeniden yazılır.
> Eski ve yeni UI bir süre yan yana yaşar (toggle); her faz sevkedilebilir.

## Neden tam yeniden yazım değil, UI reboot?

- Motor katmanı savaşta test edilmiş: WS `seq`/epoch resume, oturum re-key
  savunması (A1), prompt `requestId` idempotency, onay long-poll servisi,
  cowork mod-başına son-oturum hafızası. Bunları yeniden keşfetmek aylar sürer
  ve sessiz regresyon üretir.
- Memnuniyetsizliğin kaynağı UI: navigasyon mimarisi yok (NavHost yok, god-state
  boolean bayrakları + ChatScreen içi `sheet` string'i), 25+ modal sheet/dialog,
  1.531 satırlık ChatScreen, embriyonik Ds.kt, backend-başına string-switch.
- Kök neden: farklı zamanlarda rastgele fikirlerin ekran-bazlı yamalanması.
  Çözüm tek başına kod değil; fikirlerin süzüleceği bir "UI anayasası" (Faz 0).

## Dokunulmayacaklar (tüm fazlar boyunca)

- `bridge/` altındaki hiçbir dosya.
- `BridgeClient*.kt` istek/yanıt kontratları (endpoint yolları, JSON alanları).
- `SessionStreamManager`, `SessionStateReducer`, `ConversationPaging` davranışı.
- Backend kimlik string'leri (`"claude-app"` vb.) ve API sözleşmeleri.
- Değişmez kontrol ilkesi (docs/vision-roadmap.md): sağlayıcı/model otomatik
  seçen hiçbir davranış eklenmez.

---

## Faz 0 — Tasarım ve UI Anayasası (kod yazılmaz)

Çıktı: `docs/ui-anayasasi.md` + onaylanmış mockup seti. Bu faz atlanırsa
reboot, aynı karmaşayı yeni paketle yeniden üretir.

### 0.1 Envanter ve budama kararları
- Mevcut her ekran/sheet/dialog listelenir (ChatScreen sheet'leri, LandingScreen,
  StartSheets, SettingsScreen, McpScreen, ProjectsUi, OperationsUi, FileBrowser,
  Downloads, UsageCards...).
- Her biri için karar: **taşı / birleştir / at**. Gerekçe bir cümle.
  (Örn. adaylar: az kullanılan backend'lerin özel sheet'leri birleşir;
  Operations + Projects tek "Merkez" altında toplanabilir — ürün kararı.)

### 0.2 Bilgi mimarisi (navigasyon modeli)
- Navigation Compose ile gerçek back-stack. Öneri: 3 kök alan:
  1. **Sohbet** — sekmeler + aktif oturum (uygulamanın kalbi).
  2. **Merkez** — projeler, cowork çalışma alanları, oturum arşivi, teslimatlar,
     operasyonlar.
  3. **Ayarlar** — bağlantı/eşleme, MCP, hesaplar, görünüm, güncelleme.
- Desen kuralı (anayasaya girer): tam içerikli akışlar EKRAN olur (back tuşu
  çalışır, derin durumda kaybolunmaz); hafif seçiciler (model, effort, izin
  modu) SHEET olur; onay/yıkıcı işlem DIALOG olur. Sheet üstüne sheet YASAK.

### 0.3 Tasarım dili
- Ds.kt tam token setine genişletilir: spacing ölçeği, tipografi rolleri, renk
  rolleri (Material 3 üstünde semantik: durum renkleri — çalışıyor/onay
  bekliyor/bitti-görülmedi), köşe/yoğunluk kararı, ikonografi seti.
- Ortak bileşen kütüphanesi tanımlanır (önce spec, Faz 2'de kod):
  oturum kartı, mesaj balonu, araç-çağrısı kartı, onay kartı, boş-durum,
  hata-durum, yükleniyor iskeleti, bölüm başlığı, seçici sheet şablonu.
- Terminoloji: ui-revizyon-talimati.md Faz A sözlüğü aynen devralınır.

### 0.4 Mockup onayı
- Kilit 4 ekranın (sohbet, sohbet+onay kartı, merkez, oturum çekmecesi) HTML
  mockup'ı hazırlanır ve KULLANICI ONAYINDAN geçer. Onaysız Faz 2 başlamaz.
- "Rastgele fikir" protokolü: yeni UI fikri geldiğinde önce anayasaya
  uygunluk kontrol edilir; uymuyorsa ya fikir uyarlanır ya anayasa bilinçli
  güncellenir. Ekran tek başına yamalanmaz.

## Faz 0.9 — Nuke paketi: chatgpt-planner + claude-desktop-cowork kaldırma

Ürün kararı #4 (12.07.2026): iki backend hem bridge'den hem Android'den tamamen
kaldırılır. Emsal: bir backend'in tümüyle kaldırılması (07.07.2026). Uygulayıcı: Claude (bridge
hassas; Flash'a verilmez). Zamanlama: mockup onayından sonra, Faz 1'den ÖNCE —
Faz 1'in dispatch tablosu 8 yerine 6 backend'e iner.

Kapsam (bridge):
- `chatgpt-planner.mjs`, `chatgpt-selectors.mjs`, `routes/chatgpt-planner.mjs`,
  `chatgpt-planner-tmp/`, `pi-tmp/` (planner'a aitse).
- `claude-desktop-cowork.mjs`, `desktop-cowork-helper/`.
- `server.mjs`: import'lar, BACKEND_MODULES, registerBackend/route kayıtları,
  WS sunucuları (`wssChatGptPlanner`, `wssClaudeDesktopCowork`), upgrade dalları,
  startup cleanup çağrıları.
- `backend-contract.mjs`: BACKENDS girdileri + POLL_APPROVAL_BACKENDS'ten
  claude-desktop-cowork.
- Bridge testleri/conformance beklentileri güncellenir.

Kapsam (Android):
- `ChatGptPlanner.kt`, `BridgeClientChatGptPlanner.kt`.
- `Backend` enum'undan iki girdi; RemoteUiState'ten `chatGptPlanner*` ve
  `desktopCowork*`/`lastDesktopCoworkSessionId` alan aileleri.
- RemoteViewModel/delegate aksiyonları, LandingScreen kartları, ChatScreen
  dalları, visibleBackends/backendOrder varsayılanları, ilgili testler.

Kural: kaldırma TEK commit serisi olur (bridge ayrı, Android ayrı commit);
`gradlew testDebugUnitTest assembleDebug` + bridge test paketi geçmeli. Bridge
node süreci masaüstünden yeniden başlatılmalı (bridge-restart kuralları).

## Faz 1 — Motor/UI sınırını çizme (hazırlık refactoru)

Amaç: yeni UI'nin god-state'e ve kopyalı aksiyonlara bağımlı doğmaması.
Eski UI bu fazda davranış değiştirmeden çalışmaya devam eder.

### 1.1 Jenerik oturum durumu (okuma modeli)
- `BackendSessionState` data class'ı: sessionId, cwd, model, defaultModel,
  effort, permissionMode, setupPending, diskSessions, lastSessionId, info.
- `RemoteUiState` içine `sessionsByBackend: Map<String, BackendSessionState>`
  eklenir; mevcut `claudeApp*/codexApp*/...` alan aileleri İLK ETAPTA kalır
  (eski UI okumaya devam eder), yazan kod her iki yere yazar (köprü dönemi).

### 1.2 Jenerik aksiyon kapısı
- Tek imzalar: `enterBackend(id)`, `exitBackend(id)`, `setModel(backend, id)`,
  `setEffort(backend, e)`, `setPermissionMode(backend, m)`, `loadInfo(backend)`,
  `loadDiskSessions(backend)`, `adopt(backend, id)`, `approve/deny(backend, ...)`.
- Bunlar mevcut delegate fonksiyonlarına dispatch eder (davranış birebir).
  Yeni UI YALNIZ bu kapıyı çağırır; `setClaudeAppModel` gibi kopyalı
  fonksiyonları asla.
- Capability kontrolü tek yerden: `backendCapabilities()` (zaten var).

### 1.3 Ekran-başına ViewModel yüzeyi
- Dar, türetilmiş akışlar: `ChatUiModel`, `HubUiModel`, `SettingsUiModel`
  (RemoteUiState'ten map'lenen `StateFlow`'lar). RemoteViewModel bölünmez;
  sadece önüne dar yüzeyler konur. (Tam bölme Faz 3 temizliğine kalır.)

Doğrulama: `cd android && .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
— mevcut tüm testler geçmeli; davranış değişikliği sıfır.

## Faz 2 — Yeni UI'yi paralel inşa (feature toggle)

- Yeni paket: `com.agent.bridge.ui2` (feature-bazlı alt paketler: `chat/`,
  `hub/`, `settings/`, `components/`, `nav/`). Eski ekranlara DOKUNULMAZ.
- Ayarlarda "Yeni arayüz" anahtarı (SharedPreferences `ui2_enabled` — plan
  DataStore öngörmüştü; tüm ayarlar SharedPreferences'ta olduğu için repo
  geleneğine bilinçli sapma, 12.07.2026). Kapalıyken eski UI; her sürüm
  sevkedilebilir, günlük kullanımda geri dönüş bir dokunuş.
- İnşa sırası (her adım ayrı sürüm/commit):
  1. **2a — Kabuk + tasarım sistemi:** NavHost, 3 kök alan iskeleti, Ds token
     seti + bileşen kütüphanesi kodu, tema (dark/light).
     ✅ TAMAMLANDI 12.07.2026 (Claude): ui2/theme + ui2/components +
     ui2/chat bileşenleri + Ui2Root kabuğu + bileşen galerisi (geçici).
     Uygulayıcı talimatı: 2b için docs/ui-reboot-talimat-faz2b.md hazır.
  2. **2b — Sohbet ekranı:** mesaj listesi (paging), composer + ekler, akış
     durumu göstergeleri, onay kartı, kullanıcı-girdisi kartı, plan görünümü,
     thought akışı. Capability-driven: string-switch YOK.
     ✅ TAMAMLANDI 12.07.2026 (Flash uyguladı, Claude denetledi):
     BackendOptions sınır dosyası + ChatRootScreen gerçek ekran; 69 test.
     Ertelenen: ToolCallCard/PlanCard veri ayrıştırması, ek seçici, slash
     paleti (2c sonrası ayrı karar).
  3. **2c — Oturum yönetimi:** sekme çubuğu yerine yeni sekme/oturum modeli,
     oturum çekmecesi → arama/pin/arşiv/adopt, yeni-oturum akışı (StartSheets
     yerine tek akış).
     ✅ TAMAMLANDI 12.07.2026 (Flash uyguladı, Claude denetledi):
     BackendSessions sınır dosyası (tablolar D–H) + çekmece + NewSessionScreen
     ("chat/yeni" rotası) + sekme uzun-basış menüsü; 75 test. Not: 2c-3
     commit'i boş (sekme menüsü kodu 2c-1 commit'ine girdi — içerik tam).
     Ertelenen: hesap seçici (2e), cowork workspace CRUD (2d), ek seçici,
     ToolCallCard/PlanCard ayrıştırma, slash paleti.
  4. **2d — Merkez:** projeler + cowork çalışma alanları + teslimatlar +
     operasyonlar tek çatıda (0.1'deki birleştirme kararlarına göre).
     ✅ TAMAMLANDI 13.07.2026 (Codex): HubModels türetme sınırı + gerçek Merkez
     kökü + birleşik Proje/Cowork listesi ve workspace CRUD + proje detayı ve
     teslimatlar + Operasyonlar + Kullanım ekranları; 80 test. Proje Güvenlik
     & MCP yönetimi 2e'ye, Dosyalar kartı 2f'ye bırakıldı.
  5. **2e — Ayarlar + MCP + hesaplar + cihaz eşleme + güncelleme.**
     ✅ TAMAMLANDI 13.07.2026 (Codex): SettingsModels türetme sınırı + gerçek
     Ayarlar kökü ve alt sayfaları (bağlantı/eşleme, sağlayıcı görünürlüğü ve
     sıra, bildirimler, Claude hesapları, OTA, gelişmiş process yönetimi) +
     açık hedefli global MCP yönetimi + proje Güvenlik & MCP profili; 85 test.
  6. **2f — Dosya gezgini + indirilenler + dosya görüntüleyici.**
     ✅ TAMAMLANDI 13.07.2026 (Codex): Merkez > Dosyalar altında birleşik PC
     gezgini (yükle, yeniden adlandır, taşı, sil, tam ekran metin görüntüleme)
     ve Telefon indirilenleri/klasörleri yönetimi; saf yol/byte modeli testleri.
- Kural: yeni UI bir özelliği ancak anayasadaki desenle alır; eski UI'deki bir
  tuhaflık "aynen taşınmaz", 0.1 kararına bakılır.
- Test: SessionStateReducer/delegate testleri motoru koruyor; yeni UI için
  kritik akışlara (onay kartı aksiyonları, composer gönderim, sekme geçişi)
  compose-ui testi eklenir.

## Faz 3 — Geçiş ve büyük temizlik

- ✅ TAMAMLANDI 13.07.2026 (Codex): Yeni UI tek varsayılan kabuk oldu;
  ChatScreen.kt, LandingScreen.kt, StartSheets.kt, eski ayar/dosya sheet'leri
  ve `ui/chat/*` kaldırıldı (yaklaşık 7.700 satır). Ortak alert host bağımsız
  bileşene taşındı; eski görünürlük/arayüz anahtarı state'i ile kopyalı aksiyonlar
  ve eski `ui/theme` kabuğu silindi. `ui-revizyon-talimati.md`, `docs/archive/`
  altına taşındı.
- ✅ `RemoteUiState`'teki provider alanları `ClaudeUiState`, `CodexUiState`,
  `OpencodeUiState`, `ZcodeUiState`, `CoworkUiState` ve `AgyUiState` feature
  state'lerine ayrıldı. Dosya ve cihaz aileleri de iç içe state olarak kaldı.
  Okuma/kaynak uyumluluğu extension köprüsüyle korunurken üretim mutasyonları
  yalnızca iç içe state üzerinden yapılıyor.
- ✅ Provider yaşam döngüsü, konuşma, hard-refresh ve landing/proje veri akışları
  sırasıyla `ProviderLifecycleDelegate`, `ConversationDelegate`,
  `SessionRefreshDelegate` ve `HubDataDelegate` içine taşındı. Nihai metrikler:
  `RemoteViewModel.kt` 999 satır, `RemoteUiState` 79 doğrudan alan; iki hedef de
  karşılandı. 82 birim testin tamamı ve debug APK derlemesi geçti.

## Faz 4 — Cila (isteğe bağlı, ayrı ayrı seçilebilir)

- ✅ TAMAMLANDI 13.07.2026 (Codex): ekran rotalarında kayarak/fade geçiş ve
  Dosyalar listesinde öğe-yerleşim animasyonu;
  Android 12+ için Material You renk eşlemesi (eski cihazlarda özel ui2 tema);
  840dp ve üstünde kalıcı sol navigasyon; onay bildiriminin ilgili sohbet
  oturumuna derin bağlantısı; Room ile son konuşmanın çevrimdışı önbelleği ve
  UI'da açık çevrimdışı durum rozeti. Tam birim test + debug APK derlemesi geçti.

## Riskler ve kurallar

- **En büyük risk Faz 0'ın atlanması.** Mockup onayı olmadan 2b'ye başlanmaz.
- Faz 1 köprü dönemi (çift yazım) uzatılmaz; Faz 3'te mutlaka kapanır.
- Her faz sonu: `testDebugUnitTest + assembleDebug` geçmeden ilerlenmez;
  release kadansı (release.mjs, versionCode artışı) aynen devam eder.
- Commit dili mevcut gelenek: Türkçe, diakritiksiz, `feat(ui2)/fix(ui2)` öneki.
- Push yok (repo yerel-kalır kuralı).

## Kaba efor tahmini

| Faz | İçerik | Tahmin |
|---|---|---|
| 0 | envanter + anayasa + mockuplar | 1-2 oturum (onay turları hariç) |
| 1 | jenerik state/aksiyon kapısı | 2-3 oturum |
| 2a-2b | kabuk + sohbet | 3-4 oturum |
| 2c-2f | kalan ekranlar | 4-6 oturum |
| 3 | geçiş + temizlik | 1-2 oturum |
