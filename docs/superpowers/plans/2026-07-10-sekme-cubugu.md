# Plan: Uygulama Geneli Sekme Çubuğu (Tab Bar)

> Uygulayıcı: Gemini · Gözden geçiren: Fable · Tarih: 2026-07-10
> Kapsam: YALNIZ Android (`android/`). `bridge/` tarafına DOKUNMA — gerekmiyor.

## 0. Uygulayıcı için çalışma kuralları (ZORUNLU)

- **Satır numarasına / sed'e dayalı patch YASAK.** Değişiklikleri her zaman dosyadaki
  mevcut kod bloğunu birebir bulup tam blok değiştirerek yap. Satır numaraları bu
  dokümanda YOK; anchor olarak fonksiyon/alan adları verildi.
- Mevcut kod stiline uy: Türkçe yorumlar, mevcut adlandırma kalıpları
  (`codexApp*`, `claudeApp*`, delegate deseni).
- Her fazın sonunda derle + test: `cd android` → `.\gradlew.bat testDebugUnitTest`
  (BUILD SUCCESSFUL şart). APK build'i veya release GEREKMEZ; onu Fable yapacak.
- `git push` YASAK (repo local-only). Commit mesajları Türkçe, aksansız ASCII
  (mevcut geçmişe bak: "model-duyarli", "menusu").
- Bridge sürecini restart etme, taskkill çalıştırma (kendi kanalını öldürürsün).

## 1. Amaç ve UX

Uygulamanın en üstüne **çok ince** (~32dp) bir sekme çubuğu:

- **Uygulama seviyesinde** (backend başına değil): her sekme herhangi bir
  backend'in herhangi bir oturumuna işaret edebilir. Aynı backend'de birden çok
  oturum, farklı backend'lerde oturumlar — hepsi yan yana sekme.
- **Ayarlar ekranı hariç her yerde görünür** (landing, chat, MCP, dosya
  gezgini, indirmeler dahil).
- Sekmeye dokun → o oturuma geç. `+` → yeni sekme (landing açılır). Sekmedeki
  `×` (yalnız aktif sekmede görünür) → sekmeyi kapat (oturum bridge'de yaşamaya
  devam eder; yalnız yer imi silinir).
- Sekmeler uygulama yeniden açılınca geri gelmeli (SharedPreferences).

## 2. Mimari karar: sekme = yer imi, canlı bağlantı değil

Oturum durumu zaten bridge'de yaşıyor; telefon `(backend, sessionId)` ile
bağlanıp ayrılıyor. `RemoteViewModel` TEK aktif oturumun state'ini tutar
(`messagesList`, `running`...). Bu planda bunu DEĞİŞTİRMİYORUZ:

- Sekme = `(backend, provider, sessionId, başlık)` kaydı.
- Sekmeye geçiş = mevcut backend'den çık (`exit*` son-oturum hafızasını yazar,
  soketi kapatır; tur bridge'de sürer) + hedef backend'e gir + hedef oturuma
  bağlan. Bu akışların HEPSİ mevcut kodda var; sekme katmanı yalnız orkestre eder.
- Arka plandaki sekmelerin mesajları telefona AKMAZ; geçince bridge'den yeniden
  yüklenir (mevcut resume davranışı). Bu ilk sürüm için kabul edilmiş takas.

## 3. Veri modeli

### 3.1 `RemoteUiState.kt`

`CoworkWorkspace` data class'ının yanına ekle:

```kotlin
// Uygulama geneli sekme: herhangi bir backend'in herhangi bir oturumuna yer imi.
// id: sekmenin kendi kimliği (UUID); sessionId boş olabilir (landing sekmesi).
data class AppTab(
    val id: String,
    val backend: String,          // "claude-app" | "codex-app" | "cowork" | ...
    val provider: String = "",    // yalnız cowork için anlamlı ("claude-app"/"codex-app"/...)
    val sessionId: String = "",   // boş = henüz oturum bağlanmamış (landing sekmesi)
    val title: String = "",       // kısa etiket; boşsa UI backend etiketini gösterir
)
```

`RemoteUiState` gövdesine (backend alanının yakınına) ekle:

```kotlin
    // Sekme çubuğu: açık sekmeler + aktif sekme id'si. Sekmeler yer imidir;
    // oturum state'i bridge'de yaşar, geçişte yeniden bağlanılır.
    val openTabs: List<AppTab> = emptyList(),
    val activeTabId: String = "",
```

### 3.2 Kalıcılık formatı

`SharedPreferences("settings")` içine anahtar `app_tabs`, değer JSON:

```json
{"active":"<tabId>","tabs":[{"id":"...","backend":"codex-app","provider":"","sessionId":"...","title":"agtest · sol"}]}
```

## 4. Yeni dosya: `TabsDelegate.kt`

`CoworkDelegate.kt` desenini kopyala (constructor'ına `state()`, `update{}`,
`emit()`, `scope`, `prefs` ve ViewModel'e geri çağrı için ne gerekiyorsa —
mevcut delegate'lerin nasıl bağlandığına `RemoteViewModel` başındaki delegate
kurulumlarından bak). Sorumluluklar:

- `restoreTabs()` — init'te prefs'ten oku, `openTabs`/`activeTabId` doldur.
  Bozuk JSON'da sessizce boş listeyle başla.
- `persistTabs()` — her mutasyondan sonra yaz.
- `newTab()` — yeni boş sekme ekle + aktif yap + `goToLanding()` çağır.
- `closeTab(tabId)` — listeden çıkar. Aktif sekme kapatıldıysa: komşu sekmeyi
  aktive et; hiç sekme kalmadıysa yeni boş sekme aç (çubuk asla boş kalmasın).
- `activateTab(tabId)` — bkz. §5.
- `syncActiveTab(backend, provider, sessionId, title)` — bkz. §6. Aktif sekmenin
  kaydını günceller; hiç sekme yoksa ilk sekmeyi oluşturur (migrasyon: mevcut
  kullanıcı ilk açılışta tek sekmeyle başlar).

Sekme başlığı üretimi (yardımcı): `cwd`'nin son klasör adı varsa o, yoksa
backend etiketi; codex/claude'da modelin kısa adı eklenebilir ("agtest · sol").
Başlık 20 karakteri geçmesin (UI zaten ellipsize edecek).

## 5. Sekmeye geçiş: `activateTab`

Kaba akış (tümü `Main.immediate`'te sıralı; `switchBackend` ile aynı garanti):

```
tab boş (sessionId "") →
    activeTabId = tab.id; mevcut backend'den goToLanding() ile çık.
tab.backend != mevcut backend →
    1) last<Backend>SessionId alanını tab.sessionId ile güncelle (aşağıdaki tablo)
    2) switchBackend(tab.backend) çağır — enter* fonksiyonları zaten
       "last session'a yeniden bağlan" akışını içeriyor (örn. enterCodexAppMode
       içindeki `prior = lastCodexAppSessionId` bloğu).
tab.backend == mevcut backend ama sessionId farklı →
    backend'e özgü resume yolunu çağır (tablo).
her durumda → activeTabId = tab.id; persistTabs()
```

Backend başına "belirli oturuma bağlan" yolları (hepsi mevcut kodda):

| backend | last-session alanı | aynı-backend geçiş fonksiyonu |
|---|---|---|
| claude-app | `lastClaudeAppSessionId` (adını doğrula) | `resumeClaudeAppDiskSession` / canlı listeden attach |
| codex-app | `lastCodexAppSessionId` | `resumeCodexAppDiskSession` ya da `enterCodexAppMode`'daki canlı-attach bloğunu yeniden kullan |
| cowork | `lastCoworkSessionId` + `lastCoworkProvider` | `resumeCoworkSession(record)` |
| opencode-app | ilgili `last*` alanı | `resumeOpencodeAppDiskSession` |
| zcode | ilgili `last*` alanı | `resumeZcodeDiskSession` |
| agy | ilgili `last*` alanı | `resumeAgyDiskSession` |
| chatgpt-planner | ilgili `last*` alanı | `resumeChatGptPlannerDiskSession` |

Pratik öneri: ilk uygulamada HER geçişi (aynı backend dahil) "last-session
alanını yaz + `goToLanding()` + `enter<Backend>Mode()`" üçlüsüyle yap — tek
kod yolu, tüm backend'lerde çalışır. resume fonksiyonlarının imzaları disk
session nesnesi istiyor; sırf id'yle çağrılamıyorsa bu üçlü daha basit.
DİKKAT: `switchBackend` hedef == mevcut backend'de no-op; aynı-backend geçişte
`switchBackend`'i DEĞİL doğrudan `goToLanding()` + `enter*Mode()` çağır.

**Canlı olmayan oturum:** enter* akışları oturum canlı listede yoksa drawer/disk
listesine düşüyor; bu kabul — kullanıcı ölü sekmeye geçince o backend'in oturum
listesini görür. Hata fırlatma, sadece `emit("Oturum artık canlı değil")`.

**Tur sürerken geçiş:** engelleme YOK. Mevcut `exit*` fonksiyonları oturumu
bridge'de canlı bırakır; tur arka planda sürer. `resumeCoworkSession` içindeki
`state().running` guard'ı sekme geçişi için sorun; bu yüzden cowork'ta da
yukarıdaki üçlü yolu kullan (o yol `running` kontrolü yapmaz). exit/enter
akışlarındaki davranışı DEĞİŞTİRME — sadece etrafından geç.

## 6. Sekme kaydının güncel tutulması: `syncActiveTab`

Kullanıcı sekme çubuğunu hiç kullanmadan oturum açar/değiştirirse aktif sekme
onu takip etmeli. Tek tip kanca: oturum bağlanan HER yerde çağır:

- `enterCodexAppMode` canlı-attach başarısında ve `startCodexAppSession`
  başarısında → `syncActiveTab("codex-app", "", sessionId, başlık)`
- claude-app delegate'inin karşılık gelen attach/start noktaları
- `applyCoworkSessionResult` (CoworkDelegate) → provider'lı çağrı
- opencode/zcode/agy/chatgpt-planner start/resume başarı noktaları
- `resume*DiskSession` fonksiyonlarının başarı kolları

Kural: `syncActiveTab` AKTİF sekmeyi günceller (yeni sekme AÇMAZ; yalnız liste
boşken ilk sekmeyi yaratır). Yeni sekme yalnız `+` ile açılır. Böylece davranış
bugünkünün üstüne saydam biner: tek sekmeyle uygulama bugünkü gibi çalışır.

Not: `activateTab` geçişi sırasında `syncActiveTab` kancaları tetiklenecek —
bu istenen davranıştır (başlık/oturum id tazelenir); sonsuz döngü olmadığından
emin ol (sync yalnız state günceller, geçiş TETİKLEMEZ).

## 7. UI: `ui/chat/TabBarUi.kt` (yeni dosya)

- `@Composable internal fun AppTabBar(uiState: RemoteUiState, actions: RemoteViewModel)`
- Yapı: `Surface(color = surfaceContainerLow)` içinde yükseklik **32.dp** `Row`:
  - `LazyRow(weight(1f))`: sekme çipleri. Çip: `Text(title, labelSmall, maxLines=1,
    ellipsis)` + yalnız AKTİF çipte 14.dp `×` ikonu. Aktif çip
    `secondaryContainer` arka plan, pasifler şeffaf; `RoundedCornerShape(50)`,
    `padding(horizontal = 10.dp)`. Backend'i ayırt etmek için başlık önüne
    6.dp'lik renkli nokta (renkleri mevcut backend etiket/renk yardımcıları
    varsa oradan al — `BackendLabels.kt`'ye bak; yoksa sabit harita yaz).
  - Sağda sabit `+` `IconButton(size 28.dp)` → `newTab()`.
- Uzun basış çipte → `closeTab` (aktif olmayanları kapatmanın yolu).
- Görünürlük ve yerleşim — `ChatScreen.kt` içindeki `AppScaffold`'da mevcut
  `when { ... }` bloğunu sarmala:

```kotlin
Column(Modifier.fillMaxSize()) {
    if (!uiState.showSettings) AppTabBar(uiState, actions)
    Box(Modifier.weight(1f)) {
        /* mevcut when { showSettings -> ... else -> ChatScreen(...) } bloğu AYNEN buraya */
    }
}
```

Status bar inset'ine dikkat: `Scaffold` `WindowInsets(0,0,0,0)` kullanıyor;
çubuğa `Modifier.statusBarsPadding()` ekle, içerik mevcut davranışını korusun
(bugün inset'i kim tüketiyorsa bozma — `ChatScreen`'in üst bar'ı muhtemelen
kendi padding'ini alıyor; çift padding oluşursa chat tarafındakini değil
sekme çubuğununkini ayarla).

## 8. Aşamalar (her aşama ayrı commit)

1. **Faz 1 — state + kalıcılık:** `AppTab`, `openTabs`/`activeTabId`,
   `TabsDelegate` (restore/persist/new/close/sync, activateTab HARİÇ),
   `syncActiveTab` kancaları. Unit test: JSON round-trip + close/aktif-devir
   mantığı (`app/src/test/java/com/agent/bridge/` altına `TabsDelegateTest.kt`;
   mevcut `OtherDelegatesTest.kt` desenine bak).
   Commit: `feat: sekme altyapisi - AppTab, TabsDelegate, persist`
2. **Faz 2 — UI:** `TabBarUi.kt` + `AppScaffold` entegrasyonu.
   Commit: `feat: uygulama geneli ince sekme cubugu (UI)`
3. **Faz 3 — geçiş:** `activateTab` (§5) + cowork/running kenar durumları.
   Commit: `feat: sekmeler arasi oturum gecisi`

## 9. Kabul kriterleri

- [ ] `gradlew.bat testDebugUnitTest` yeşil; yeni `TabsDelegateTest` var ve geçiyor.
- [ ] Ayarlar açıkken çubuk yok; diğer tüm ekranlarda var.
- [ ] Codex'te oturum aç → `+` → landing'den Claude oturumu aç → iki sekme;
      dokununca ikisi arasında geçiş, mesaj geçmişi her geçişte doğru yükleniyor.
- [ ] Aynı backend'de (codex) iki farklı oturum iki sekmede gezilebiliyor.
- [ ] Tur SÜRERKEN başka sekmeye geç, geri dön → tur devam ediyor / sonucu görünüyor.
- [ ] Uygulamayı kapat-aç → sekmeler ve aktif sekme geri geliyor.
- [ ] Son sekme kapatılınca boş sekme (landing) kalıyor; çubuk asla boşalmıyor.
- [ ] Tek sekmeli kullanıcı için davranış bugünkünden farksız (regresyon yok).

## 10. Bilinçli kapsam dışı (yapma)

- Sekme başına mesaj cache'i / paralel canlı WS — ikinci iterasyon.
- Arka plan sekmede "çalışıyor" rozeti — ikinci iterasyon (bridge `/sessions`
  polling'i gerektirir).
- Sekme sürükle-sırala, yeniden adlandırma.
- Bridge tarafında herhangi bir değişiklik.
