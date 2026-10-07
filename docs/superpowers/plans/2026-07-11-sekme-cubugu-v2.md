# Plan: Sekme Çubuğu v2 — Durum Rozetleri, Sıralama, Önizleme, Tekilleştirme

> Uygulayıcı: Gemini · Gözden geçiren: Fable · Tarih: 2026-07-11
> Öncül: `2026-07-10-sekme-cubugu.md` (v1 tamamlandı, release 10.44'te yayında).
> Kapsam: Android (`android/`) + bu kez SINIRLI bridge değişikliği (§4.1'deki
> tek satırlık `listSessions` eklentileri). Bridge'de BAŞKA hiçbir şeye dokunma.

## 0. Uygulayıcı için çalışma kuralları (ZORUNLU)

- **Satır numarasına / sed'e dayalı patch YASAK.** Değişiklikleri her zaman
  dosyadaki mevcut kod bloğunu birebir bulup tam blok değiştirerek yap.
  Anchor olarak fonksiyon/alan adları verildi; satır numarası verilmedi.
- Mevcut kod stiline uy: Türkçe yorumlar, delegate deseni, mevcut adlandırma
  kalıpları. Commit mesajları Türkçe, aksansız ASCII ("cilasi", "menusu").
- Her fazın sonunda derle + test:
  - Android: `cd android` → `.\gradlew.bat testDebugUnitTest` (BUILD SUCCESSFUL şart)
  - Bridge'e dokunan fazda ayrıca: `cd bridge` → `node --test test/` (mevcut
    testler kırılmasın). Node 22 kullan: `C:\Users\<you>\node22\node.exe`.
- **Bridge sürecini restart ETME, taskkill çalıştırma** (kendi kanalını
  öldürürsün). Bridge kod değişikliği ancak Fable/kullanıcı restart edince
  etkinleşir; bu senin sorunun değil. Android tarafı eski bridge'le de
  çalışacak şekilde yazılacak (§4.2'deki optBoolean/optString default'ları).
- `git push` YASAK (repo local-only). APK build/release GEREKMEZ; Fable yapacak.
- Her faz ayrı commit. Faz sırasına uy — sonraki fazlar öncekilerin
  altyapısını kullanıyor.

## 1. Kapsamdaki özellikler (öncelik sırasıyla)

1. **Aktif sekmeye otomatik kaydırma** — sekme değişince LazyRow aktif sekmeyi
   görünür alana getirsin.
2. **Arka plan sekme durum rozetleri** — çalışıyor (pulse), onay bekliyor
   (amber), arka planda bitti (yeşil, görülene dek), canlı değil (soluk).
3. **Ölü/canlı-değil sekme görünümü** — bridge restart olduysa sekmeler bunu
   tıklamadan önce göstersin.
4. **Aynı oturuma iki sekme tekilleştirme** — bir oturum yalnız bir sekmede
   görünsün.
5. **Uzun basış önizleme menüsü** — mevcut "uzun bas = kapat" jesti tehlikeli;
   yerine bilgi + eylem menüsü.
6. **Sürükle-bırak sekme sıralama** — uzun bas + sürükle ile sekmelerin yeri
   değişsin.

## 2. Mevcut durum (v1'den devralınan, DEĞİŞTİRME)

- `AppTab(id, backend, provider, sessionId, title)` — `RemoteUiState.kt`.
  Sekme = yer imi; oturum state'i bridge'de. Bu mimari AYNEN kalıyor.
- `TabsDelegate` (`android/.../TabsDelegate.kt`): restore/persist/new/close/
  activate/sync + `resetActiveTab` (geri tuşu sekmeyi "Yeni Sekme"ye döndürür).
- `TabBarUi.kt`: 36dp çubuk; aktif sekme dolgun pill + primary kenarlık +
  SemiBold; pasifler `surfaceContainerHigh` pill; backend renk noktası;
  `combinedClickable(onClick=activateTab, onLongClick=closeTab)`; aktif
  sekmede 14dp `×`; sağda `+`.
- `BridgeClient.listBackendSessions(settings, backend)` →
  `List<LiveSession(id, cwd, model, status)>` — `/{backend}/sessions` GET'i.
  Her backend'in `listSessions()`'ı `status: 'idle'|'running'` zaten dönüyor.

## 3. Faz 1 — Aktif sekmeye otomatik kaydırma

`TabBarUi.kt` içinde:

```kotlin
val listState = rememberLazyListState()
LaunchedEffect(uiState.activeTabId, uiState.openTabs.size) {
    val index = uiState.openTabs.indexOfFirst { it.id == uiState.activeTabId }
    if (index >= 0) listState.animateScrollToItem(index)
}
LazyRow(state = listState, ...)
```

Not: `animateScrollToItem` hedefi başa hizalar; sekme zaten tamamen
görünürdeyse kaydırma yapmaması ideal. Basit çözüm yeterli:
`listState.layoutInfo.visibleItemsInfo` içinde index tamamen görünüyorsa
(offset ≥ 0 ve offset+size ≤ viewportEndOffset) hiç kaydırma.

Test: UI davranışı olduğundan unit test beklenmiyor; derleme + manuel kabul.
Commit: `feat: sekme cubugunda aktif sekmeye otomatik kaydirma`

## 4. Faz 2 — Bridge: listSessions'a awaitingApproval + Android parse

### 4.1 Bridge (her dosyada SADECE listSessions map'ine alan ekle)

Her backend'in `export function listSessions()` içindeki nesneye bir alan:

| dosya | eklenecek alan |
|---|---|
| `bridge/claude-app.mjs` | `awaitingApproval: !!s.pendingApproval` |
| `bridge/codex-app.mjs` | `awaitingApproval: !!(s.awaitingApproval \|\| s.awaitingUserInput)` |
| `bridge/opencode-app.mjs` | `awaitingApproval: !!(s.pendingApproval \|\| s.awaitingApproval)` (oturum nesnesinde hangi alan varsa onu kullan; ikisi de yoksa `false` sabitle) |
| `bridge/zcode.mjs` | aynı kural |
| `bridge/agy.mjs` | aynı kural |
| `bridge/chatgpt-planner.mjs` | `awaitingApproval: false` (onay akışı yok) |
| `bridge/claude-desktop-cowork.mjs` | aynı kural (yoksa `false`) |

`lastText` claude/codex/opencode/zcode'da zaten var; OLMAYANLARA EKLEME
(davranış değişikliği istemiyoruz). Başka hiçbir fonksiyona dokunma.

`cd bridge` → `C:\Users\<you>\node22\node.exe --test test/` yeşil kalmalı.

### 4.2 Android: `LiveSession` genişlet

`BridgeClient.kt` içindeki data class'ı bul:

```kotlin
data class LiveSession(val id: String, val cwd: String, val model: String, val status: String)
```

şununla değiştir:

```kotlin
data class LiveSession(
    val id: String, val cwd: String, val model: String, val status: String,
    val awaitingApproval: Boolean = false, val lastText: String = "",
)
```

`BridgeClientGeneral.kt` → `listBackendSessions` içindeki map'i güncelle:

```kotlin
LiveSession(
    o.optString("id"), o.optString("cwd"), o.optString("model"), o.optString("status"),
    o.optBoolean("awaitingApproval", false), o.optString("lastText", ""),
)
```

Default'lar sayesinde ESKİ bridge ile de çalışır (alan yoksa false/boş).

Commit: `feat: listSessions awaitingApproval alani (bridge+android parse)`

## 5. Faz 3 — Durum polling'i + tabStatuses state

### 5.1 Veri modeli — `RemoteUiState.kt`

`AppTab`'ın yanına:

```kotlin
// Sekme başına canlı durum (bridge /sessions polling'inden). AppTab'a
// KOYMUYORUZ: durum geçicidir, persist edilmez. Anahtar = tab.id.
data class TabStatus(
    val live: Boolean = true,            // bridge canlı listesinde var mı
    val running: Boolean = false,        // tur sürüyor
    val awaitingApproval: Boolean = false,
    val finishedUnseen: Boolean = false, // arka plandayken bitti, henüz bakılmadı
    val cwd: String = "",
    val lastText: String = "",           // önizleme menüsü için
)
```

`RemoteUiState` gövdesine (`openTabs`/`activeTabId` yanına):

```kotlin
    val tabStatuses: Map<String, TabStatus> = emptyMap(),
```

### 5.2 Saf hesap fonksiyonu — `TabsDelegate.kt` (dosya sonuna, top-level)

Test edilebilirlik için saf fonksiyon. İmza ve kurallar:

```kotlin
// liveByBackend: backend -> canlı oturum listesi; null = o backend SORGULANAMADI
// (ağ hatası). null'da o backend'in sekmelerine önceki durum AYNEN korunur —
// ağ hatasını "oturum öldü" sanma. finishedUnseen: önceki durumda running/
// awaitingApproval iken şimdi idle'a düşen ve AKTİF OLMAYAN sekmede true olur;
// aktif sekmede her zaman false (kullanıcı zaten görüyor).
fun computeTabStatuses(
    tabs: List<AppTab>,
    activeTabId: String,
    liveByBackend: Map<String, List<LiveSession>?>,
    prev: Map<String, TabStatus>,
): Map<String, TabStatus>
```

Kurallar:
- `sessionId` boş sekme (landing) → map'e HİÇ girmez (rozet yok).
- Sorgu anahtarı: `if (tab.backend == "cowork") tab.provider else tab.backend`
  (activateTab'daki canlılık kontrolüyle aynı kural — o satıra bak).
- Liste null → `prev[tab.id]` varsa aynen taşı, yoksa giriş ekleme.
- Oturum listede yok → `TabStatus(live=false)`; `finishedUnseen` önceki
  değerinden taşınır (bitti rozeti bridge listesinden düşse de kaybolmasın).
- Oturum listede var → `running = (status == "running")`,
  `awaitingApproval`, `cwd`, `lastText` LiveSession'dan.
- `finishedUnseen` hesabı: `tab.id != activeTabId` VE önceki durum
  `(running || awaitingApproval)` VE yeni durum ikisi de false → true.
  Önceki `finishedUnseen == true` ise ve sekme hâlâ aktif değilse korunur.
  `tab.id == activeTabId` → daima false.

### 5.3 Polling döngüsü — `TabsDelegate.kt`

```kotlin
// Sekme durum polling'i: yalnız sessionId'li sekme varken, tekil backend
// başına tek GET. Ağ hatasında o backend null geçilir (durum korunur).
suspend fun refreshTabStatuses() {
    val st = state()
    val tabsWithSession = st.openTabs.filter { it.sessionId.isNotEmpty() }
    if (tabsWithSession.isEmpty()) {
        if (st.tabStatuses.isNotEmpty()) update { it.copy(tabStatuses = emptyMap()) }
        return
    }
    val backends = tabsWithSession.map { if (it.backend == "cowork") it.provider else it.backend }
        .filter { it.isNotEmpty() }.distinct()
    val liveByBackend = backends.associateWith { be ->
        runCatching { client.listBackendSessions(state().settings, be) }.getOrNull()
    }
    update { s -> s.copy(tabStatuses = computeTabStatuses(s.openTabs, s.activeTabId, liveByBackend, s.tabStatuses)) }
}
```

Sürücü: `TabBarUi.kt` içinde lifecycle'a bağlı döngü (çubuk zaten Ayarlar
hariç her ekranda var; uygulama arka plandayken durur):

```kotlin
val lifecycleOwner = LocalLifecycleOwner.current
LaunchedEffect(lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            actions.tabsDelegate.refreshTabStatuses()
            delay(6_000)
        }
    }
}
```

Gerekli bağımlılık: `androidx.lifecycle:lifecycle-runtime-compose` zaten
projede mi bak (`android/app/build.gradle.kts`); yoksa mevcut lifecycle
sürümüyle uyumlu ekle.

Ek anlık tazeleme: `activateTab` başarısında ve `syncActiveTab` sonrasında
`scope.launch { refreshTabStatuses() }` çağır (rozet 6 sn beklemesin).
`activateTab` içindeki mevcut "canlılık kontrolü + emit" bloğu DURSUN
(davranış değişmiyor), sadece yanına refresh eklenir.

Test (`TabsDelegateTest.kt`): `computeTabStatuses` saf fonksiyonuna en az:
- running/awaitingApproval eşleme
- listede olmayan oturum → live=false
- null backend listesi → önceki durum korunur
- arka planda running→idle → finishedUnseen=true; aktif sekmede false;
  sekme aktive olunca (activeTabId değişince) tekrar hesapta temizlenir

Commit: `feat: sekme durum polling altyapisi (tabStatuses)`

## 6. Faz 4 — Rozet görselleri + canlı-değil stili

`TabBarUi.kt`. Nokta (dot) şu an backend kimlik rengi. Yeni kural — durum,
noktanın RENGİNİ ve animasyonunu belirler (öncelik sırasıyla):

1. `awaitingApproval` → amber `Color(0xFFF5A623)` + **pulse** (sonsuz alpha
   animasyonu 0.4f↔1f, `rememberInfiniteTransition`, ~800ms)
2. `running` → backend kimlik rengi + **pulse**
3. `finishedUnseen` → yeşil `Color(0xFF34A853)`, sabit
4. `live == false` → içi boş gri halka (`border(1.dp, gri, CircleShape)`,
   dolgu yok) + sekme başlığı `contentColor.copy(alpha = 0.55f)`
5. diğer (idle, canlı) → bugünkü davranış (kimlik rengi, sabit)

Durum yoksa (`tabStatuses[tab.id] == null`, örn. landing sekmesi) → bugünkü
davranış. Pulse animasyonu YALNIZ görünür durumdaki chip'lerde çalışır
(LazyRow zaten görünmeyeni compose etmez, ek iş yok).

`sessionId` boş sekmede nokta zaten çizilmiyor (`tab.backend.isNotEmpty()`
guard'ı) — bozma.

Canlı-değil sekmeye dokunma davranışı DEĞİŞMEZ: activateTab zaten disk-resume
yoluna düşüyor (claude/codex diskten devam edebiliyor) veya drawer'ı açıyor.

Test: renk seçimi saf fonksiyona çıkarılabilir:
`fun tabDotState(status: TabStatus?): TabDotState` (enum: APPROVAL, RUNNING,
FINISHED, DEAD, NORMAL) → `TabsDelegateTest`'e 4-5 satırlık test.

Commit: `feat: sekme rozetleri - calisiyor/onay/bitti/olu gostergeleri`

## 7. Faz 5 — Aynı oturuma iki sekme tekilleştirme

`TabsDelegate.syncActiveTab` içine, aktif sekme güncellendikten sonra:

```kotlin
// Tekilleştirme: aynı (backend, provider, sessionId) başka bir sekmede de
// varsa o sekme kaldırılır — bir oturum tek sekmede yaşar (Chrome mantığı).
// Aktif sekme her zaman kazanır; kullanıcının şu an baktığı yer orası.
```

Kural: `sessionId` boş olanlara dokunma (iki landing sekmesi meşru).
Silme sonrası `activeTabId` DEĞİŞMEZ (aktif olan zaten korunan taraf).
`persistTabs()` mevcut çağrıyla zaten oluyor — update bloğunun İÇİNDE
tekilleştir ki tek persist yeterli olsun.

Test (`TabsDelegateTest.kt`):
- Sekme A'da oturum X açıkken sekme B'de (drawer'dan) X'e bağlanılır
  (`newTab` + `syncActiveTab(X)`) → tek sekme kalır, aktif sekme B, oturum X.
- Farklı backend'de aynı id (teorik çakışma) → SİLİNMEZ (backend+provider+
  sessionId üçlüsü birlikte eşleşmeli).

Commit: `feat: ayni oturuma iki sekme tekillestirme`

## 8. Faz 6 — Uzun basış önizleme menüsü

Mevcut `combinedClickable(onLongClick = closeTab)` jesti kaldırılıp yerine
menü gelir (yanlışlıkla sekme kapatma jesti ölür):

- `var menuTabId by remember { mutableStateOf<String?>(null) }` (AppTabBar
  seviyesinde). Uzun basış → `menuTabId = tab.id` + haptic
  (`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`).
- Chip'i `Box` içine al; `DropdownMenu(expanded = menuTabId == tab.id,
  onDismissRequest = { menuTabId = null })` chip'e demirlesin.
- Menü içeriği:
  - Başlık satırı: `tab.title` (SemiBold) + altında backend etiketi
    (TabBarUi'daki fallback when bloğundaki adlar; cowork'ta " · provider").
  - Durum satırı (varsa `tabStatuses[tab.id]`): "Çalışıyor" / "Onay bekliyor" /
    "Tamamlandı" (finishedUnseen) / "Canlı değil" / "Boşta".
  - `cwd` (varsa; `labelSmall`, tek satır ellipsis).
  - `lastText` (varsa; `bodySmall`, `maxLines = 2`, ellipsis).
  - `HorizontalDivider()`
  - `DropdownMenuItem("Sekmeyi kapat")` → `closeTab(tab.id)`; menüyü kapat.
  - `DropdownMenuItem("Diğer sekmeleri kapat")` → yeni delegate fonksiyonu
    `closeOtherTabs(tabId)`: o sekme hariç hepsini listeden çıkarır, o sekmeyi
    aktive eder (`activateTab` DEĞİL — zaten aktifse gereksiz geçiş yapma;
    aktif değilse `activateTab(tabId)` çağır), persist eder.

Bilgi satırları `DropdownMenuItem` DEĞİL, tıklanamaz `Column` içinde
(padding'i menü item'larıyla hizala, `Modifier.padding(horizontal = 12.dp)`).

Test: `closeOtherTabs` → tek sekme kalır, o sekme aktif, persist edilmiş.

Commit: `feat: sekme uzun basis onizleme menusu (kapat/digerlerini kapat)`

## 9. Faz 7 — Sürükle-bırak sıralama

### 9.1 Delegate — `TabsDelegate.kt`

```kotlin
fun moveTab(fromIndex: Int, toIndex: Int) {
    update { state ->
        if (fromIndex !in state.openTabs.indices || toIndex !in state.openTabs.indices) return@update state
        val tabs = state.openTabs.toMutableList()
        tabs.add(toIndex, tabs.removeAt(fromIndex))
        state.copy(openTabs = tabs)
    }
    persistTabs()
}
```

### 9.2 UI — `TabBarUi.kt`

Kütüphane EKLEME; standart elle-reorder deseni (~60 satır):

- State: `draggedTabId: String?`, `dragOffsetX: Float` (remember).
- Chip modifier'ına `pointerInput(tab.id) { detectDragGesturesAfterLongPress(...) }`:
  - `onDragStart` → `draggedTabId = tab.id; dragOffsetX = 0f` + haptic.
  - `onDrag` → `dragOffsetX += dragAmount.x`. Sürüklenen chip'in merkezi
    komşu chip'in merkezini geçince `moveTab(from, to)` + `dragOffsetX`'ten
    komşu genişliğini düş (item boyutları `listState.layoutInfo
    .visibleItemsInfo`'dan; `key`'i `tab.id` olan item'ı bul).
  - `onDragEnd/onDragCancel` → toplam yer değiştirme `< 10.dp` ise BU BİR
    MENÜ AÇMA jestidir: `menuTabId = tab.id` (Faz 6 menüsü). Değilse sadece
    state temizle. Her durumda `draggedTabId = null; dragOffsetX = 0f`.
- Sürüklenen chip: `Modifier.graphicsLayer { translationX = dragOffsetX }` +
  `zIndex(1f)` + hafif ölçek (1.05f).
- `LazyRow` item'ları `key = { it.id }` zaten var — reorder animasyonu için
  `Modifier.animateItem()` (mevcut Compose sürümünde varsa; yoksa
  `animateItemPlacement()`) sürüklenmeyen chip'lere eklenebilir.
- ÖNEMLİ: Faz 6'daki uzun basış menüsü artık `combinedClickable.onLongClick`
  ile DEĞİL, buradaki `onDragEnd` küçük-hareket dalıyla tetiklenir.
  `combinedClickable` yerine düz `clickable(onClick = activateTab)` kalır.
  (Faz 6'yı yazarken bunu bilerek yaz: menü tetikleyicisini tek fonksiyona
  çıkar ki Faz 7 geçişi tek satır olsun.)
- Kenar auto-scroll (sürüklerken çubuğun ucuna gelince kaydırma) KAPSAM DIŞI;
  sekme sayısı tipik 2-6.

Test: `moveTab` sınır durumları (geçersiz index no-op, persist round-trip).

Commit: `feat: sekme surukle-birak siralama`

## 10. Kabul kriterleri

- [ ] `gradlew.bat testDebugUnitTest` + `node --test test/` yeşil.
- [ ] 6+ sekme açıkken son sekmeye geçince çubuk otomatik kaydırıyor.
- [ ] Codex sekmesinde tur başlat → başka sekmeye geç → eski sekmenin noktası
      pulse yapıyor; tur bitince yeşile dönüyor; o sekmeye geçince yeşil
      sönüyor.
- [ ] Claude sekmesi onay beklerken noktası amber pulse.
- [ ] (Fable/kullanıcı bridge'i restart ettikten sonra) tüm arka plan
      sekmeleri "canlı değil" görünümüne geçiyor; tıklayınca mevcut davranış
      (disk resume / drawer) çalışmaya devam ediyor.
- [ ] Telefon uçak modundayken rozetler DONUYOR ama "canlı değil"e DÜŞMÜYOR
      (null-koruma kuralı).
- [ ] Drawer'dan, başka sekmede zaten açık oturumu açınca duplike sekme
      OLUŞMUYOR (eski sekme kayboluyor, aktif sekme oturumu gösteriyor).
- [ ] Uzun basış artık kapatMIYOR; menü açıyor (bilgi + kapat + diğerlerini
      kapat). Kısa dokunuş davranışı değişmedi.
- [ ] Uzun bas + sürükle sekme sırasını değiştiriyor; sıra uygulama
      yeniden açılınca korunuyor.
- [ ] Tek sekmeli kullanıcı için görünür davranış değişikliği yok (rozet
      hariç regresyon yok); eski bridge ile app çalışmaya devam ediyor
      (awaitingApproval alanı yoksa false).

## 11. Bilinçli kapsam dışı (yapma)

- Bridge'de push/WS ile durum bildirimi — polling yeterli (6 sn, yalnız
  ön planda, backend başına tek GET).
- Sekme başına mesaj cache'i / paralel canlı stream.
- Drawer'da "bu oturum X sekmesinde açık → sekmeye geç" kısayolu (tekilleştirme
  bunu dolaylı çözüyor; istenirse üçüncü iterasyon).
- Sekme yeniden adlandırma, sekme grid görünümü, kenar auto-scroll.
- Bildirim (notification) entegrasyonu — rozetler yalnız uygulama içi.
