# AgentBridge — Opus 4.8 Son Düzeltme Notları

**Tarih:** 2026-07-14  
**Bağlam:** `docs/gemini-cold-start-uygulama-gelistirme-plani.md` uygulandıktan ve ilk incelemede bulunan beş ana sorun Opus 4.8 tarafından düzeltildikten sonra yapılan bağımsız kod incelemesi.  
**Amaç:** Aşağıdaki kalan sorunları giderip arama/deep-link akışını gerçekten uçtan uca tamamlamak.

## Doğrulanmış mevcut durum

- Bridge testleri: `333/333` başarılı.
- Android birim testleri: `98/98` başarılı.
- İlk incelemedeki beş ana konu için gerçek kod değişiklikleri mevcut:
  - Sohbet geçmişini tam yükleme
  - Global arama sonucundan mesaj eşleşmesine geçiş
  - Sağlayıcılarda tam transcript araması
  - Case-insensitive proje araması
  - Bridge geçici ZIP temizliği

Bu belge bu düzeltmeleri geri almayı istemez. Yalnız aşağıdaki yarış durumları ve sonuç kimliği sorunları tamamlanmalıdır.

---

## 1. Sohbet araması yüklenirken yazılan sorgu tam geçmiş üzerinde yeniden çalıştırılmıyor

**Öncelik:** P1  
**İlgili dosyalar:**

- `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt`
- `android/app/src/main/java/com/agent/bridge/ConversationDelegate.kt`

### Mevcut davranış

`openChatSearch()` şu akışı başlatıyor:

1. Arama panelini açıyor.
2. `loadChatSearchHistory(rerunQuery = "", preferredIndex = -1)` çağırıyor.
3. Kullanıcı geçmiş yüklenirken sorgu yazarsa `updateChatSearchQuery()` o anda bellekte bulunan kısmi `messagesList` üzerinde eşleşme hesaplıyor.
4. Tam geçmiş yüklendiğinde `rerunQuery` boş olduğu için sorgu yeniden çalıştırılmıyor.

Sonuç olarak kullanıcı arama panelini açtıktan hemen sonra yazarsa eski sayfalardaki eşleşmeler görünmeyebilir. Arama paneli açık olmasına rağmen sonuç listesi son yüklü sayfanın sonuçlarıyla kalabilir.

### Beklenen düzeltme

Tam geçmiş yükleme tamamlandığında başlangıçta fonksiyona verilen sorguya değil, **o anda state içinde bulunan güncel sorguya** bakılmalı.

Önerilen davranış:

```kotlin
private fun loadChatSearchHistory(preferredIndex: Int) = viewModelScope.launch {
    // ... geçmişi yükle ...
    val currentQuery = _uiState.value.chatSearch.query
    if (currentQuery.length >= 2) {
        applyChatSearchMatch(currentQuery, preferredIndex)
    }
}
```

Burada kullanıcı yükleme sırasında sorguyu değiştirirse en son sorgu kullanılmalıdır. Birden fazla history-load job aynı anda çalışabiliyorsa önceki job iptal edilmeli veya generation/session anahtarıyla geçersiz kılınmalıdır.

### Kabul testleri

1. Yalnız eski sayfalarda bulunan benzersiz bir kelime içeren uzun bir sohbet aç.
2. Arama ikonuna basar basmaz, history yüklenmesi bitmeden kelimeyi yaz.
3. Yükleme tamamlandığında eski mesajdaki eşleşme sayaca dahil olmalı ve eşleşmeye gidilebilmeli.
4. Yükleme sırasında sorguyu `ilk` → `ikinci` şeklinde değiştir; tamamlanınca yalnız `ikinci` sorgusunun sonuçları görünmeli.
5. Android tarafında mümkünse ViewModel/delegate testi eklenmeli; test geciken history yüklemesini simüle etmelidir.

---

## 2. Global arama deep-link'i hedef oturum gerçekten açılmadan aramayı başlatabiliyor

**Öncelik:** P1  
**İlgili dosyalar:**

- `android/app/src/main/java/com/agent/bridge/ui2/hub/HubSearchScreen.kt`
- `android/app/src/main/java/com/agent/bridge/ui2/nav/Ui2Root.kt`
- `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt`
- `android/app/src/main/java/com/agent/bridge/ConversationDelegate.kt`

### Mevcut davranış

Mesaj sonucuna basıldığında iki bağımsız işlem arka arkaya başlatılıyor:

```kotlin
actions.openProjectSession(session, hit.projectPath)
onOpenChatSearch(hit.rowId, hit.matchOrdinal, query)
```

`openProjectSession()` hedef oturumu asenkron olarak açabiliyor. Hemen arkasından çalışan `openChatSearchWithTarget()` ise hedef oturumun açıldığını doğrulamadan `loadFullHistory()` çağırıyor.

`loadFullHistory()` yalnızca `messagesList.isEmpty()` durumunda bekliyor. Önceki sohbetten mesajlar bellekte duruyorsa beklemeden yanlış oturumun geçmişini ve eşleşmelerini kullanabilir.

Ayrıca `openOperation()` hedef backend zaten aktifse yalnız `lastSessionId` yazıp `switchBackend()` çağırıyor. `switchBackend()` aynı backend için erken döndüğünden, hedef oturum açık bir tab değilse aynı backend içindeki başka oturuma geçiş garanti edilmiyor.

### Beklenen düzeltme

Oturum açma ve deep-link tek, sıralı bir ViewModel işlemi olmalı. UI katmanı iki bağımsız action çağırmamalı.

Önerilen genel kontrat:

```kotlin
fun openGlobalSearchMessageHit(hit: GlobalSearchHit, query: String) = viewModelScope.launch {
    val opened = openProjectSessionAndAwait(
        backend = hit.backend,
        sessionId = hit.sessionId,
        projectPath = hit.projectPath,
        container = hit.container,
    )
    if (!opened) {
        // Kullanıcıya hata göster; eski sohbet üzerinde arama başlatma.
        return@launch
    }

    openChatSearchWithTarget(
        targetRowId = hit.rowId,
        matchOrdinal = hit.matchOrdinal,
        query = query,
    )
}
```

`openProjectSessionAndAwait` en azından şunları doğrulamalıdır:

- Aktif provider/backend hedef provider oldu.
- Aktif session ID hedef session ID veya adopt/resume sonucunda dönen gerçek session ID oldu.
- Yeni oturumun ilk conversation sonucu state'e uygulandı ya da kontrollü biçimde yüklenebilir hale geldi.
- Aynı backend içindeki farklı oturuma geçiş gerçekten yapıldı.
- Zaman aşımı veya adopt hatasında deep-link iptal edildi ve hata gösterildi.

`loadFullHistory()` mümkünse beklediği provider/session kimliğini parametre olarak almalı ve başka bir oturumun state'ini kullanmamalıdır.

### Kabul testleri

1. Claude sohbeti açıkken farklı bir Claude oturumundaki global mesaj sonucuna bas; doğru Claude oturumu ve doğru eşleşme açılmalı.
2. Codex sohbeti açıkken farklı bir Codex oturumundaki sonuca bas; aynı kontrol yapılmalı.
3. Claude açıkken Codex sonucuna ve Codex açıkken Claude sonucuna bas; provider geçişi sonrası doğru eşleşme açılmalı.
4. Önceki sohbetin `messagesList` değeri boş değilken deep-link denenmeli.
5. Hedef oturum adopt/resume edilemiyorsa eski sohbette yanlış eşleşme gösterilmemeli.
6. Aynı backend içindeki farklı session geçişi için Android testi eklenmeli.

---

## 3. Global arama aynı oturumdaki birden fazla mesaj eşleşmesini tek sonuca düşürüyor

**Öncelik:** P2  
**İlgili dosyalar:**

- `bridge/search.mjs`
- `bridge/session-utils.mjs`
- `bridge/test/global-search.test.mjs`

### Mevcut davranış

Sağlayıcılar mesaj sonuçlarında çoğunlukla `rowId: ""` döndürüyor ve eşleşmeyi `matchOrdinal` ile tanımlıyor. Ancak global arama dedupe anahtarı yalnız şunlardan oluşuyor:

```text
type + projectId + sessionId + rowId
```

Aynı session içindeki bütün mesaj sonuçlarının `rowId` değeri boş olduğundan ilk sonuçtan sonraki eşleşmeler aynı anahtarı üretiyor ve eleniyor.

Doğrulanan minimal senaryo:

- Aynı session için `matchOrdinal: 0` ve `matchOrdinal: 1` olan iki mesaj sonucu verildi.
- Global arama yalnız ordinal `0` sonucunu döndürdü.

### Beklenen düzeltme

Mesaj sonucunun kararlı kimliği `rowId` varsa onu, yoksa `matchOrdinal` değerini içermelidir. Backend de anahtara dahil edilmelidir; farklı provider'lardaki aynı session ID'leri birbirini yanlışlıkla düşürmemelidir.

Örnek yaklaşım:

```js
function stableKey({ type, backend, projectId, sessionId, rowId, matchOrdinal }) {
  const matchKey = rowId || `ordinal:${matchOrdinal}`;
  const src = `${type}:${backend}:${projectId}:${sessionId}:${matchKey}`;
  // hash...
}
```

Proje ve session sonuçlarında uygun boş/default değerler kullanılabilir. `matchOrdinal = 0` falsy olduğu için `||` ile kaybedilmemeli; `??` veya açık sayı kontrolü kullanılmalıdır.

### Kabul testleri

`bridge/test/global-search.test.mjs` içine en az şu testler eklenmeli:

1. Aynı backend/session için ordinal `0` ve `1` olan iki mesajın ikisi de dönmeli.
2. Gerçekten aynı ordinal/rowId ile tekrarlanan sonuç dedupe edilmeli.
3. Farklı backend'lerde aynı session ID'ye sahip sonuçlar birbirini düşürmemeli.
4. `rowId` bulunan sonuçlarda rowId öncelikli kararlı kimlik olarak kullanılmalı.
5. Mevcut limit ve sıralama testleri geçmeye devam etmeli.

---

## 4. Tam geçmiş yükleme hatası “tamamlandı” gibi yorumlanabiliyor

**Öncelik:** P2  
**İlgili dosyalar:**

- `android/app/src/main/java/com/agent/bridge/ConversationDelegate.kt`
- `android/app/src/main/java/com/agent/bridge/RemoteViewModel.kt`
- `android/app/src/main/java/com/agent/bridge/GlobalSearchModels.kt`

### Mevcut davranış

`loadFullHistory()` yalnız `Boolean` döndürüyor. Şu durumların bir kısmı aynı `false` sonucuna düşüyor:

- Geçmiş gerçekten tamamen yüklendi.
- Ağ/bridge çağrısı hata verdi (`getOrNull()`).
- Sayfa beklenmedik biçimde boş döndü.
- İlerleme/dedupe kontrolü döngüyü durdurdu.

ViewModel bu değeri yalnız `truncated` olarak yorumladığı için bir hata halinde kullanıcı aramanın eksik geçmiş üzerinde çalıştığını anlayamaz.

### Beklenen düzeltme

Boolean yerine açık bir sonuç modeli kullanılmalı. Örneğin:

```kotlin
sealed interface FullHistoryLoadResult {
    data object Complete : FullHistoryLoadResult
    data class Truncated(val loadedCount: Int) : FullHistoryLoadResult
    data class Failed(val loadedCount: Int, val cause: Throwable?) : FullHistoryLoadResult
}
```

UI state en azından `loadingHistory`, `truncated` ve kullanıcıya gösterilebilir `historyLoadError` bilgisini ayırmalıdır. Hata halinde eldeki mesajlarda arama yapılabilir; fakat sonucun bütün geçmişi kapsamadığı açıkça belirtilmelidir.

### Kabul testleri

1. İkinci history sayfası yüklenirken bridge hatası simüle edilmeli.
2. İlk sayfadaki eşleşmeler çalışmaya devam etmeli.
3. UI/state aramanın eksik geçmiş üzerinde olduğunu göstermeli.
4. Başarılı exhaust ile hata sonucu birbirinden ayırt edilebilmeli.

---

## Uygulama sonrası zorunlu doğrulama

```powershell
# Repo kökü
node --test bridge/test/*.test.mjs

# Android dizini
$env:GRADLE_OPTS='--add-opens=java.base/java.io=ALL-UNNAMED'
.\gradlew.bat --no-daemon '-Dkotlin.incremental=false' testDebugUnitTest
.\gradlew.bat --no-daemon '-Dkotlin.incremental=false' assembleDebug

# Repo kökü
git diff --check
```

Beklenen asgari sonuç:

- Mevcut 333 Node testi korunmalı; ek testlerle sayı artmalı.
- Mevcut 98 Android testi korunmalı; yarış durumları için yeni testler eklenmeli.
- `assembleDebug` başarılı olmalı.
- `git diff --check` yeni hata vermemeli.

## Tamamlanma ölçütü

Bu takip işi ancak aşağıdakilerin tamamı sağlandığında kapatılmalıdır:

- [x] Arama yüklenirken değişen güncel sorgu, tam history yüklendikten sonra yeniden uygulanıyor.
- [x] Global mesaj sonucu hedef oturumun açılması tamamlanmadan arama başlatmıyor.
- [x] Aynı backend içindeki farklı oturuma deep-link güvenilir çalışıyor.
- [x] Başka backend'e deep-link güvenilir çalışıyor.
- [x] Aynı session içindeki birden fazla mesaj eşleşmesi ayrı sonuçlar olarak korunuyor.
- [x] History yükleme hatası ile başarılı tamamlanma state seviyesinde ayrılıyor.
- [x] Yeni regresyon testleri eklenmiş ve bütün test/derleme komutları başarılı.

---

## Uygulama notları (Opus 4.8, 2026-07-14)

- **#1** `RemoteViewModel.applyFullHistoryLoad`: yükleme bitince başlangıç sorgusu değil, o an state'teki **güncel** sorgu uygulanıyor. Eşzamanlı yüklemeler `chatSearchLoadGen` nesil sayacıyla süpersede ediliyor (sadece en son iş sonucu state'e yazar). Sorgu yükleme sırasında değişmişse deep-link ordinal seçimi düşürülür.
- **#2** UI artık iki bağımsız action çağırmıyor; tek sıralı `openGlobalSearchMessageHit(hit, query)`:
  - Stale mesajları temizler, hedef oturumu **resume/adopt** ile açar (`openSessionForSearch`) — `openOperation`'ın aynı backend içi `switchBackend` erken dönüşü baypas edilir, aynı backend içi geçiş güvenilir çalışır.
  - `awaitSessionLoaded(targetProvider)`: hedef sağlayıcı aktif olup konuşması yüklenene kadar bekler (8 sn zaman aşımı). Açılamazsa `historyLoadError` gösterilir ve arama **başlatılmaz** (eski oturumda yanlış eşleşme yok).
  - `loadFullHistory()` yalnız doğrulanmış aktif oturum üzerinde çalışır.
- **#3** `search.mjs` `stableKey` artık `backend` + `matchOrdinal` içeriyor (`rowId` varsa öncelikli; `matchOrdinal=0` için `??`). Aynı oturumdaki farklı eşleşmeler ayrı sonuç kalır; farklı sağlayıcılarda aynı sessionId birbirini düşürmez. Not: eski "iki farklı backend aynı hit → dedupe" testi bilinçli olarak "tek sağlayıcı mükerrer → dedupe" ve "farklı backend → korunur" testleriyle değiştirildi (yeni semantik doğru olan).
- **#4** `loadFullHistory` artık `FullHistoryLoadResult` (Complete/Truncated/Failed) döndürüyor; `ChatSearchState.historyLoadError` ayrı bayrak, ChatSearchBar'da "Geçmiş…/Eksik geçmiş/Kısmi" göstergesi.
- **Testler:** `bridge/test/global-search.test.mjs` (+4 senaryo), yeni `ChatSearchLogicTest.kt` (ordinal seçimi, idx_<index> hizası, case-insensitive, thought atlama). Bridge 336/336, Android 104/104, `assembleDebug` başarılı, `git diff --check` temiz.

