# AgentBridge Android — Proje, arama ve klasör akışları geliştirme planı

> Hedef okuyucu: Repoyu ve önceki konuşmaları hiç bilmeden işe başlayacak Gemini/Codex benzeri bir uygulama ajanı.
>
> Belge tarihi: 13 Temmuz 2026
>
> İncelenen çalışma kopyası: `C:\Users\<you>\agtest`
> İncelenen `HEAD`: `5deb25e`

## 1. Amaç

Bu planın amacı aşağıdaki kullanıcı özelliklerini mevcut AgentBridge mimarisine, çalışan eski davranışları kaybetmeden ve tek seferde dev bir yeniden yazıma dönüşmeden eklemektir:

1. Proje detayında o projeye ait **tüm geçmiş oturumları** gösterme ve açma.
2. Proje detayından ve proje kartı menüsünden **hızlı yeni sohbet** başlatma; sağlayıcı/model/izin tercihlerinin ayrıntılı yönetimi ve proje bazında son seçimin hatırlanması.
3. Yeni sohbet klasör seçicisini geliştirme: breadcrumb, üst klasör, son kullanılanlar, favoriler ve klasör araması.
4. Proje sabitleme ve proje listesini “tümü / sabit / aktif / yeni teslimat” süzgeçleriyle kullanma; son etkinliğe göre anlamlı sıralama.
5. Proje, oturum başlığı ve geçmiş prompt/cevap içeriklerini kapsayan **global arama**; sonuçtan doğru projeye/sohbete geçiş.
6. Teslimatları telefona ZIP dosyası olarak bırakmadan **gerçek klasör** halinde indirme. UI rework öncesindeki çalışan klasör indirme hattını yeniden görünür hale getirme.
7. Proje detayında çoklu oturum seçme, toplu arşivleme/arşivden çıkarma ve toplu silme.
8. Tam proje silme işleminde ayrıntılı sonuç ekranı: hangi oturumların silindiği, hangilerinin başarısız olduğu, klasörün ve uygulama kaydının durumu, yeniden deneme.
9. Geçici “Bileşen galerisi” ekranını ve Ayarlar bağlantısını tamamen kaldırma.
10. Proje kartına uzun basınca doğrudan silme onayı yerine işlem menüsü açma: **Yeni sohbet, Sabitle/Sabitlemeyi kaldır, Yeniden adlandır, Tamamen sil**.
11. Açık sohbet içinde metin arama, önceki/sonraki eşleşmeye gitme ve eşleşen mesajı görünür biçimde vurgulama.

Bu ürün tek kullanıcı tarafından Tailscale üzerinden yerel ağda kullanılıyor. Dolayısıyla gereksiz kurumsal yetkilendirme, rol sistemi veya ağır indeks altyapısı eklenmeyecek. Yine de diskten kalıcı silme gibi geri alınamaz mevcut korumalar korunacak.

---

## 2. Başlamadan önce zorunlu kurallar

### 2.1 Önce mevcut davranışı oku, sonra ekle

Bu plan bir yeniden yazım talimatı değildir. Aşağıdaki mevcut parçalar özellikle yeniden kullanılmalıdır:

- Proje deposu ve tam silme: `bridge/projects.mjs`
- Proje rotaları: `bridge/server.mjs`
- Android proje modelleri/istemcisi: `BridgeClient.kt`, `BridgeClientGeneral.kt`
- Merkez proje state ve işlemleri: `HubModels.kt`, `HubDataDelegate.kt`
- Proje ekranları: `ui2/hub/HubProjectsScreen.kt`
- Oturum açma/özümseme: `BackendSessions.kt`, `RemoteViewModel.openProjectSession`, sağlayıcı delegeleri
- Sekme temizleme ve kalıcılık: `TabsDelegate.kt`
- Klasör seçici: `ui2/chat/NewSessionScreen.kt`, `HubDataDelegate.loadWorkerDirs`, `/dirs`
- Eski klasör indirme: `DownloadRepo.downloadAndExtract`, `CoworkDelegate.downloadCoworkWorkspaceZip`, `bridge/cowork.mjs::archiveProject`
- Sohbet mesaj kimlikleri ve sayfalama: `ChatMessage.rowId`, `ConversationPaging.kt`, `ConversationDelegate.showOlderMessages`, `agent-session-core.mjs`
- Ortak UI parçaları: `SearchField`, `SelectorSheet`, `ConfirmDialog`, `SurfaceCard`, `ListRow`

### 2.2 Küçük, doğrulanabilir dilimler

Özellikler tek bir mega değişiklik halinde yapılmayacak. Her fazın sonunda en azından ilgili Node testleri ve Android unit testleri çalıştırılacak. Bir faz başarısızken bir sonraki büyük faza geçilmeyecek.

---

## 3. Mevcut mimarinin kısa özeti

### 3.1 Bridge

- `bridge/projects.mjs`, proje kayıtlarını varsayılan olarak `~/.agentbridge/projects.json` içinde tutar.
- Proje kimliği, normalize edilmiş mutlak proje yolunun hash’idir.
- Mevcut `detail(id)` yalnızca `liveSessions()` sonucunu döndürür; bu yüzden bridge yeniden başladığında veya oturum canlı listeden düşünce proje detayında geçmiş görünmez.
- Sağlayıcı modüllerinin ortak olarak kullanılabilen fonksiyonları vardır:
  - `listSessions()`
  - `listDiskSessions({ account, all })`
  - `adoptSession(...)`
  - `deleteDiskSession(...)`
- Claude App ve Codex App için pin/arşiv/search rotaları zaten vardır; fakat mevcut arama yalnız `title + cwd + lastText` üzerinde çalışır.
- Cowork kendi proje/oturum metadata’sını proje altındaki `.cowork` dizininde tutar.
- Tam proje silme için mevcut kaynakta `deleteProjectCompletely(id)` vardır. Oturum taraması tamamlanmadan klasörü silmez ve geniş/kritik yolları reddeder.

### 3.2 Android

- UI tek kök olarak `ui2/nav/Ui2Root.kt` üzerinden Navigation Compose kullanır.
- Proje listesi ve proje detayı aynı dosyadadır: `ui2/hub/HubProjectsScreen.kt`.
- `RemoteUiState` özellik ailelerine ayrılmıştır; yeni büyük alanlar da aynı desenle iç içe state olarak eklenmelidir.
- İş mantığı `RemoteViewModel` içine yığılmamalı; mevcut `HubDataDelegate`, `ConversationDelegate`, `DownloadsDelegate`, `TabsDelegate` desenleri izlenmelidir.
- Sohbet mesajları `ChatMessage.rowId` taşır. Claude/Codex geçmişi `before + limit` ile sayfalanabilir.
- Disk oturum çekmecesi için ortak `BackendDiskSessionUi` modeli ve provider aksiyon kapıları `BackendSessions.kt` içinde vardır.

---

## 4. Hedef kullanıcı deneyimi

### 4.1 Projeler ekranı

Üst bölüm:

- Geri
- Başlık: “Projeler”
- Global arama ikonu
- Yenile
- Yeni çalışma alanı

Başlığın altında yatay filtre çipleri:

- Tümü
- Sabit
- Aktif
- Yeni teslimat

Liste sırası:

1. Sabitlenen projeler
2. Yeni teslimatı olanlar
3. Çalışan/bekleyen işi olanlar
4. Son etkinlik zamanı
5. Görünen ad

Proje kartına normal dokunma detay ekranını açar. Uzun basma `SelectorSheet` tabanlı işlem menüsünü açar:

1. Yeni sohbet
2. Sabitle / Sabitlemeyi kaldır
3. Yeniden adlandır
4. Tamamen sil

“Tamamen sil” menüden seçildiğinde mevcut ayrıntılı geri alınamazlık onayı açılır; işlem hemen başlamaz.

### 4.2 Proje detayı

Üst kartta:

- Proje adı ve yol
- Sabit durumu
- “Yeni sohbet” ana aksiyonu
- “Güvenlik & MCP” ikincil aksiyonu

Alt bölümler:

1. Oturumlar
2. Teslimatlar
3. Değişiklikler
4. Komutlar
5. Plan

Oturumlar bölümünde yalnız canlı oturumlar değil, diskteki bütün geçmiş görünür. Durum rozetleri:

- Çalışıyor
- Onay bekliyor
- Tamamlandı
- Arşivlendi
- Diskte / canlı değil

Oturum kartına dokunmak sohbeti açar. Uzun basmak çoklu seçim modunu başlatır.

### 4.3 Hızlı yeni sohbet sheet’i

Hem proje kartı menüsünden hem proje detayından aynı composable ve aynı ViewModel aksiyonu kullanılmalıdır.

Alanlar:

- Proje: salt okunur proje adı/yolu
- Sağlayıcı
- Hesap (yalnız Claude ve hesap listesi varsa)
- Model
- İzin modu
- Çaba/effort (sağlayıcı destekliyorsa)
- “Başlat”

Proje yolu sabittir; bu sheet içinde klasör seçici gösterilmez. Son başarılı seçim proje bazında hatırlanır. Daha önce seçim yoksa mevcut provider kataloğunun varsayılanları kullanılır.

### 4.4 Sohbet içi arama

Sohbet header’ında büyüteç ikonu bulunur. Açılınca ince bir arama çubuğu görünür:

- Metin alanı
- `3 / 12` eşleşme sayacı
- Önceki
- Sonraki
- Kapat

Arama bütün konuşmayı kapsar; yalnız ekranda yüklü son sayfayı kapsamaz. Eşleşmeye gidildiğinde mesaj kartı listeye kaydırılır ve geçici olarak vurgulanır.

---

## 5. Ortak veri modeli ve bridge kontratları

Bu bölüm fazlar arasında model adlarının farklılaşmasını önlemek için bağlayıcıdır.

### 5.1 Proje kayıt şeması

`bridge/projects.mjs::ensureProjectDefaults` geriye uyumlu olarak şu alanları eklemelidir:

```js
{
  pinned: false,
  lastOpenedAt: "",
  quickStart: {
    provider: "",
    accountByProvider: {},
    modelByProvider: {},
    permissionByProvider: {},
    effortByProvider: {}
  }
}
```

Kurallar:

- Eski `projects.json` dosyası migration gerektirmeden okunmalı; eksik alanlar default edilip güvenli biçimde yeniden yazılmalı.
- `lastSeenAt` eski anlamıyla bırakılabilir. Yeni liste sıralaması için ayrıca türetilmiş `lastActivityAt` dönülmelidir.
- `lastActivityAt`, şu zamanların en büyüğüdür: son canlı/disk oturum mtime’ı, son output mtime’ı, `lastOpenedAt`, Cowork `updatedAt`.
- Windows yol karşılaştırması mevcut `samePath/isWithin` yaklaşımıyla case-insensitive kalmalıdır.

### 5.2 ProjectSummary

Bridge yanıtı ve Android `ProjectSummary` en az şunları taşımalıdır:

```text
id, path, name, displayName, exists,
sessionCount, runningCount, outputCount, newOutputCount,
providers, pinned, lastActivityAt, lastOpenedAt
```

`sessionCount` artık yalnız canlı sayısı değil, dedupe edilmiş bütün geçmiş sayısıdır.

### 5.3 ProjectSession

Bridge ve Android modeli:

```text
backend
backendLabel
sessionId
nativeSessionId
account
model
status
title
summary
cwd
mtime
live
pinned
archived
container       // "direct" veya "cowork"
threadId        // varsa
```

Notlar:

- Android’in açma/silme işlemlerinde kullanacağı birincil anahtar `nativeSessionId.ifBlank { sessionId }` olmalıdır.
- Dedupe anahtarı sağlayıcıya göre `threadId` veya native id kullanmalıdır. Aynı canlı oturum ve disk oturumu iki ayrı satır görünmemelidir.
- `account`, Claude oturumunu doğru profilden açmak/silmek için kaybedilmemelidir.
- Cowork `.cowork/providers/...` kaydında bulunan oturumlar `container="cowork"` almalıdır; açılırken doğrudan provider moduna değil Cowork resume akışına girmelidir.

### 5.4 Proje tercih uçları

Yeni bridge uçları:

```http
POST /projects/preferences
{
  "id": "project-id",
  "pinned": true,
  "touch": true,
  "quickStart": {
    "provider": "codex-app",
    "account": "",
    "model": "gpt-5.6",
    "permissionMode": "ask",
    "effort": "high"
  }
}
```

Alanların hepsi opsiyoneldir; yalnız gönderilen alan değişir. Yanıt güncel proje özetini döndürür.

Cowork-only bir kartın henüz `projects.json` kaydı yoksa, path üzerinden kayıt oluşturabilmek için aynı uç `path` de kabul etmelidir:

```json
{ "path": "C:\\...\\workspace", "pinned": true }
```

Bridge `ensureProjectForPath(path)` ile id üretir ve default kayıt açar. Böylece Android tarafında ayrı bir “Cowork pin prefs” kaynağı oluşmaz.

### 5.5 Toplu oturum işlemi

```http
POST /projects/sessions/bulk
{
  "id": "project-id",
  "action": "delete | archive | unarchive",
  "sessions": [
    {
      "backend": "claude-app",
      "sessionId": "...",
      "account": "personal"
    }
  ]
}
```

Yanıt:

```json
{
  "ok": false,
  "action": "delete",
  "succeeded": 2,
  "failed": 1,
  "results": [
    {
      "backend": "claude-app",
      "sessionId": "...",
      "ok": true,
      "error": ""
    }
  ]
}
```

Bridge, gönderilen her oturumun gerçekten proje yoluna ait olduğunu disk/canlı metadata’dan doğrulamalıdır. Başka projeye ait bir id sessizce işleme alınmamalıdır.

### 5.6 Global arama

```http
GET /search/global?q=<text>&limit=100
```

Yanıt:

```json
{
  "ok": true,
  "query": "dilekçe",
  "truncated": false,
  "hits": [
    {
      "id": "stable-result-key",
      "type": "project | session | message",
      "projectId": "",
      "projectPath": "",
      "projectName": "",
      "backend": "codex-app",
      "backendLabel": "Codex",
      "sessionId": "",
      "container": "direct",
      "title": "",
      "role": "user | agent |",
      "snippet": "...eşleşme çevresi...",
      "rowId": "",
      "matchOrdinal": 0,
      "mtime": 0
    }
  ]
}
```

Arama sırası:

1. Tam proje adı eşleşmesi
2. Proje adı/yolu eşleşmesi
3. Oturum başlığı eşleşmesi
4. Prompt/cevap içerik eşleşmesi
5. Daha yeni sonuç

Arama case-insensitive olmalı. Sonuç snippet’i yaklaşık 180–240 karakter olmalı; bütün transcript HTTP yanıtına taşınmamalıdır.

### 5.7 Ayrıntılı tam silme sonucu

`CompleteProjectDeleteResult` yalnız `path/count/ids` taşımamalıdır. Ortak sonuç:

```text
ok
projectId
path
phase               // sessions | folder | registry | complete
folderDeleted
registryDeleted
deletedSessionCount
deletedSessionIds
removedProjectIds
sessionResults[]    // backend, sessionId, ok, error
error
retryable
```

Önemli: Android’in mevcut `executeJson` fonksiyonu non-2xx cevabın gövdesini atmadan `HTTP 400` fırlatır. Ayrıntılı hata göstermek için iki seçenekten biri uygulanmalıdır:

1. Tercih edilen: `executeJsonResult`/`postJsonResult` ekleyip hata HTTP kodunda da JSON gövdesini parse etmek.
2. Alternatif: işlem seviyesindeki partial failure’ları HTTP 200 + `ok:false` olarak döndürmek.

Genel `executeJson` davranışı bütün uygulama için sessizce değiştirilmemelidir.

---

## 6. Özellik 1 — Proje detayında bütün geçmiş oturumlar

### 6.1 Bridge

`bridge/projects.mjs` içinde tek bir ortak toplayıcı oluştur:

```js
async function collectProjectSessions(project, { includeArchived = true } = {})
```

Akış:

1. Bütün provider modüllerinden `listSessions()` al.
2. Her provider için bütün hesapları bul (`listAccounts` varsa).
3. Her hesapta `listDiskSessions({ account, all: true })` çağır.
4. `cwd` proje yoluna eşit olan kayıtları al. Cowork projesinde alt path oturumları geçerliyse mevcut `isWithin` kuralını kontrollü kullan.
5. Cowork metadata’sından `cowork.listProjectSessions({ projectPath })` sonucunu ekle ve ilgili satırları `container="cowork"` olarak işaretle.
6. Native id/thread id ile dedupe et.
7. Canlı kopya varsa disk kopyasının başlık/mtime/account bilgisini tamamla ama `live/status` bilgisini canlı kaynaktan al.
8. `pinned`, sonra canlı/bekleyen, sonra `mtime desc` sırasına koy.

`createProjectsStore` imzasına Cowork okuma bağımlılığı açıkça geçirilmeli; `projects.mjs` doğrudan `cowork.mjs` import ederek gizli döngü oluşturmamalıdır:

```js
createProjectsStore({ modules, mcpModules, coworkModule, labels, ... })
```

Performans:

- Toplayıcının sonucu 5–10 saniyelik kısa TTL cache ile tutulabilir.
- Silme, yeni oturum, arşiv/pin ve yeniden adlandırma işlemleri cache’i invalidate etmelidir.
- Tek kullanıcı/lokal kullanım nedeniyle ayrı veritabanı kurmak gereksizdir.

`list()` ve `detail()` async hale gelirse `bridge/server.mjs` rotaları da `await` etmelidir:

```js
router.get('/projects', async ...)
router.get('/projects/detail', async ...)
```

### 6.2 Android

Değişecek ana dosyalar:

- `BridgeClient.kt`
- `BridgeClientGeneral.kt`
- `HubDataDelegate.kt`
- `RemoteViewModel.kt`
- `ui2/hub/HubProjectsScreen.kt`
- Gerekiyorsa `BackendSessions.kt`

`ProjectSessionCard`:

- Başlık: session title, yoksa provider label.
- Alt satır: provider + model + göreli zaman.
- Durum badge’i.
- Tüm kart tıklanabilir olmalı; yalnız küçük “Aç” butonuna bağımlı kalmamalı.

Oturum açma:

- `container == "cowork"` ise ilgili `CoworkSessionRecord` eşdeğeri üretilip Cowork resume akışı kullanılmalı.
- Direct oturumda mevcut `openProjectSession/openOperation` akışı kullanılabilir; fakat `account` ve native id kaybolmamalı.
- Oturum diskten açıldığında mevcut aktif sekmeyle senkronize edilmeli.

### 6.3 Kabul kriterleri

- Bridge restart sonrası proje detayında eski oturumlar görünür.
- Aynı oturum canlı+diskte bulunuyorsa tek satır görünür.
- Claude farklı hesap oturumları doğru hesapla açılır.
- Cowork oturumu Cowork modunda açılır.
- Proje kartındaki oturum sayısı geçmiş toplamını gösterir.
- Silinmiş/bozuk transcript tek başına bütün proje detayını bozmaz; diğer provider sonuçları görünür.

---

## 7. Özellik 2 — Proje detayından ayrıntılı hızlı yeni sohbet

### 7.1 Ortak istek modeli

Android’de tek model kullan:

```kotlin
data class ProjectChatRequest(
    val projectId: String,
    val projectPath: String,
    val workspacePath: String? = null,
    val provider: String,
    val account: String = "",
    val model: String = "",
    val permissionMode: String = "",
    val effort: String = "",
)
```

Tek entry point:

```kotlin
fun startProjectChat(request: ProjectChatRequest)
```

Bu entry point provider’a göre var olan delegate’lere dağıtır. Yeni provider başlatma kodu kopyalanmamalıdır.

### 7.2 Sheet davranışı

Yeni composable önerisi:

```text
ui2/hub/ProjectQuickChatSheet.kt
```

Bu composable hem proje listesinden hem detaydan çağrılır.

Başlangıç değerleri:

1. Project `quickStart` tercihi
2. Yoksa o projenin en yeni oturumundaki provider/model/permission
3. Yoksa provider kataloğu varsayılanı

Sağlayıcı değiştiğinde:

- O provider’ın model listesi kullanılır.
- O provider’a daha önce kaydedilmiş proje modeli geri gelir.
- İzin modu provider’a göre doğrulanır; desteklenmeyen eski değer default’a düşer.
- Effort yalnız destekleniyorsa görünür.
- Claude seçildiyse hesap alanı görünür.

Cowork projesi:

- Üst düzey mod Cowork olarak kalır.
- Kullanıcı alttaki gerçek provider’ı Claude/Codex/OpenCode arasından seçer.
- `workspacePath` açıkça request’e verilir; global `selectedCoworkWorkspace` değerine güvenilmez.

Direct proje:

- Görünür backend listesindeki direct provider’lar gösterilir.
- `cowork` ancak proje gerçekten Cowork workspace ise seçenek olur.

Başlatma sonucu:

- Çift tıklamayı engellemek için `starting` state olmalı.
- Başarılı session id alınmadan quick-start tercihi kaydedilmemeli.
- Başarıdan sonra proje `touch` edilmeli, project/session cache yenilenmeli ve Chat köküne gidilmeli.
- Hata halinde sheet açık kalmalı ve gerçek hata alert/snackbar ile görünmeli.

### 7.3 Proje kartı uzun basış entegrasyonu

“Yeni sohbet” seçimi aynı sheet’i açar. Ayrı, daha basit ikinci bir başlatma akışı yazılmamalıdır.

### 7.4 Kabul kriterleri

- Proje detayından iki dokunuşla yeni sohbet başlatılabilir.
- CWD her zaman seçilen projenin yoludur.
- Son provider/model/izin/effort proje bazında yeniden açıldığında gelir.
- Bir projenin tercihi başka projeyi değiştirmez.
- Cowork yeni sohbet doğru workspace altında `.cowork` metadata’sı üretir.
- Başlatma hatasında kullanıcı yanlışlıkla boş Chat ekranına gönderilmez.

---

## 8. Özellik 3 — Gelişmiş klasör seçici

### 8.1 Mevcut düzeltmeleri koru

Mevcut kaynakta zaten:

- `WorkerDirs.parent`
- `/dirs` yanıtında parent
- “Üst klasöre çık” satırı
- “Başlat” düğmesinin klasör seçicinin üstünde olması

vardır. Bunları yeniden yazma veya eski yerleşime döndürme.

### 8.2 Yeni UI parçaları

`NewSessionScreen.kt` aşırı büyümesin. Şunları ayır:

```text
ui2/chat/FolderPickerCard.kt
FolderPickerModels.kt veya mevcut uygun model dosyası
FolderPickerPreferences.kt
```

`FolderPickerCard` şunları içerir:

1. Arama alanı
2. Tıklanabilir breadcrumb
3. Üst klasöre çık
4. Bu klasörü kullan
5. Geçerli klasörü favorile/favoriden çıkar
6. Alt klasör listesi
7. Daraltılabilir “Son kullanılanlar” ve “Favoriler” kısayolları

### 8.3 Yerel tercihler

Android SharedPreferences içinde version’lı JSON kullan:

```text
folder_picker_recent_v1
folder_picker_favorites_v1
```

Kurallar:

- Recent maksimum 12 benzersiz yol.
- Yalnız başarılı sohbet başlatmada recent’e ekle.
- Favoriler kullanıcı kaldırana kadar kalır.
- Windows path karşılaştırması slash normalize + lowercase ile yapılır; ekranda orijinal path korunur.
- Artık var olmayan recent/favorite seçildiğinde anlaşılır hata göster, kaydı otomatik silmek zorunlu değil.

### 8.4 Breadcrumb

Örnek:

```text
C: > Users > <you> > agtest
```

- Her parça kendi mutlak path’ine gider.
- UNC ve Unix yollarını bozmayan saf yardımcı fonksiyon yaz.
- Bu yardımcı için Android unit test ekle.

### 8.5 Klasör araması

Yalnız yüklü çocukları filtrelemek kullanışlı değildir. Bridge’e kontrollü recursive arama ekle:

```http
GET /dirs/search?root=<current>&q=<query>&maxDepth=6&limit=50
```

Kurallar:

- Query en az 2 karakter.
- Yalnız klasör döndürür.
- `root` mutlak ve mevcut dizin olmalı.
- Permission/read hataları o dalı atlar; bütün isteği çökertmez.
- Symlink/junction döngüsü visited realpath set’i ile engellenir.
- Maksimum derinlik ve limit zorunludur.
- Android 300 ms debounce uygular; yeni query eski Job’u iptal eder.
- Sonuç seçilince normal klasör görünümüne o path’te dönülür; doğrudan seçilmiş sayılmaz.

### 8.6 Kabul kriterleri

- Üst klasör, breadcrumb, recent ve favorite aynı navigation state’ini doğru günceller.
- “Başlat” klasör seçicinin üstünde kalır.
- Klasör seçmeden start davranışı mevcut default base davranışını bozmaz.
- Arama boşaltılınca normal liste geri gelir.
- Büyük bir kökte arama limitsiz disk taramasına dönüşmez.

---

## 9. Özellik 4 — Proje sabitleme, filtre ve sıralama

### 9.1 Bridge

- Bölüm 5.1’deki `pinned`, `lastOpenedAt`, `lastActivityAt` alanlarını uygula.
- Proje detayına girildiğinde `touch:true` çağrısı yap veya detail çağrısında `lastOpenedAt` güncelle. Tercih: ayrı `touch` çağrısı; salt GET’in yazma yan etkisi olmasın.
- Cowork-only path için `ensureProjectForPath` kullan.

### 9.2 Android

`HubProjectItem` alanları:

```text
pinned
lastActivityAt
hasActiveWork = runningCount > 0
hasNewDelivery = newOutputCount > 0
```

Saf filtre/sort fonksiyonu UI dosyasından ayrı tutulmalı ve test edilmelidir:

```kotlin
enum class ProjectFilter { ALL, PINNED, ACTIVE, NEW_OUTPUTS }

fun filterAndSortProjects(
    projects: List<HubProjectItem>,
    filter: ProjectFilter,
    query: String = "",
): List<HubProjectItem>
```

Proje listesi state’i ekran-local olabilir (`rememberSaveable`); global kalıcı filtre gerekli değildir.

### 9.3 Kabul kriterleri

- Sabit proje uygulama ve bridge restart sonrası sabit kalır.
- Cowork-only kart da sabitlenebilir.
- Filtreler birbirinin state’ini bozmadan çalışır.
- Filtrede sonuç yoksa genel “proje yok” yerine filtreye özel empty state görünür.
- Sabit proje alfabetik zorlamayla değil, kendi grubunda son etkinliğe göre sıralanır.

---

## 10. Özellik 5 — Global arama

### 10.1 Kapsam

Arama şunları kapsamalıdır:

- Proje görünen adı
- Proje gerçek klasör adı ve yolu
- Oturum başlığı
- Oturumun ilk/son özet metni
- Geçmiş kullanıcı prompt’ları
- Geçmiş agent cevapları

Tool stdout, reasoning ve çok büyük binary içerik arama kapsamına alınmayacaktır. Kullanışlı metin sonuçları hedeflenir.

### 10.2 Bridge uygulaması

Yeni dosya önerisi:

```text
bridge/search.mjs
```

Arama on-demand ve kısa süre cache’li olabilir; bu tek kullanıcılı lokal ürün için ayrıca Elasticsearch/FTS servisi kurma.

Her provider modülüne opsiyonel ortak capability ekle:

```js
searchDiskSessions({ query, limit, cwd })
```

Provider uygulama notları:

- **Antigravity (`agy.mjs`)**: mevcut `parseTranscript(id, home).messages` kullanılabilir.
- **Claude App (`claude-app.mjs`)**: `claude-transcript.mjs` içindeki parser’lar ve mevcut transcript file index kullanılmalı. Aynı JSONL ikinci farklı parser ile yorumlanmamalı.
- **Codex App (`codex-app.mjs`)**: önce yerel rollout JSONL dosyaları mevcut `walkRollouts/readRollout...` yardımcılarıyla aranmalı. Yerel rollout bulunmayan app-server thread’lerinde gerekirse sınırlı concurrency ile `thread/read { includeTurns:true }` kullanılmalı. Arama hiçbir oturumu resume/adopt ederek canlı state’i değiştirmemeli.
- **OpenCode App (`opencode-app.mjs`)**: mevcut `readSessionFromDb/normalizeExportMessages` veya doğrudan read-only SQLite sorgusu kullanılmalı. Arama DB’ye yazmamalı.
- **Cowork**: provider sonucu proje path ve `.cowork` kaydıyla ilişkilendirilip `container="cowork"` işaretlenmeli; transcript ikinci kez indekslenmemeli.

Kaynak sınırları:

- En fazla 100 birleşik hit.
- Provider başına makul üst sınır (ör. 40).
- Aynı mesaj tek sonuç.
- Snippet dışındaki tam metin response’a konmamalı.
- Aynı query için 15–30 saniyelik cache yeterlidir.
- Provider hatası bütün aramayı bozmasın; yanıt opsiyonel `warnings` listesi taşıyabilir.

### 10.3 Android state/delegate

Yeni iç içe state:

```kotlin
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<GlobalSearchHit> = emptyList(),
    val error: String = "",
)
```

Yeni delegate önerisi:

```text
GlobalSearchDelegate.kt
```

Sorumluluklar:

- 300–350 ms debounce
- Önceki arama Job’unu iptal etme
- Minimum 2 karakter
- JSON parse/state update
- Result navigation için ViewModel’e seçili hedef bırakma

### 10.4 UI ve navigation

Yeni ekran:

```text
ui2/hub/HubSearchScreen.kt
route: hub/search
```

Sonuçlar tip başlıklarıyla gruplanabilir:

- Projeler
- Oturumlar
- Mesajlar

Sonuç tıklama:

- Project → `loadProjectDetail` → `hub/project`
- Session → doğru direct/Cowork resume → Chat
- Message → session resume → Chat search query’sini aç → `rowId` veya `matchOrdinal` ile eşleşmeye git

### 10.5 Kabul kriterleri

- Eski, canlı olmayan bir oturumdaki kullanıcı prompt’u bulunabilir.
- Sonuç doğru proje ve provider bilgisi gösterir.
- Mesaj sonucuna dokununca ilgili sohbet açılır ve aranan mesaj görünür hale gelir.
- Hızlı yazarken eski query sonucu yeni query’nin üstüne gelemez.
- Bir provider arama hatası diğer provider sonuçlarını gizlemez.

---

## 11. Özellik 6 — Teslimatları klasör olarak indirme

### 11.1 Geri kazanılacak mevcut uygulama

Bu özellik sıfırdan tasarlanmayacak. Çalışan eski kod hâlâ repodadır:

- `DownloadRepo.kt::downloadAndExtract`
- `CoworkDelegate.kt::downloadCoworkWorkspaceZip`
- `bridge/cowork.mjs::archiveProject`

UI rework öncesindeki son görünür tetikleyici git geçmişinde şuradadır:

```powershell
git show eeb04d6^:android/app/src/main/java/com/agent/bridge/ui/chat/SessionsDrawerUi.kt
```

Eski buton metni: **“Çalışma alanını indir (klasör)”**. İlgili geçmiş commit: `0524c43`.

### 11.2 Ürün kararı: ZIP kullanıcıya bırakılmayacak

Kullanıcı sonucu bir `.zip` dosyası olarak görmek istemiyor. Kabul edilen davranış:

- Bridge taşıma için geçici ZIP üretebilir.
- Android ZIP’i yalnız `cacheDir` içinde geçici tutabilir.
- Android içeriği `Download/AgentBridge/<proje-adı>-teslimatlar/` altına açar.
- Başarı veya hata sonunda geçici ZIP silinir.
- Download geçmişinde tek bir `folder=true` kaydı görünür.
- Telefonda kalıcı `.zip` kaydı oluşmaz.

Bu, eski çalışan uygulamanın davranışıdır; recursive yüzlerce HTTP isteğiyle yeniden yazmak gerekmez.

### 11.3 Kapsam ve UI

Proje detayında “Teslimatlar” bölüm başlığına:

```text
Tümünü klasör olarak indir
```

aksiyonu ekle. Yalnız `detail.outputs` boş değilse aktif olsun.

İndirilecek kök:

- Varsayılan: `<projectPath>\outputs`
- Klasör adı: `<displayName>-teslimatlar`
- Alt klasör yapısı korunur.

Mevcut `scanOutputs` yalnız dosyaları bir seviye tarıyor. Teslimat klasöründe alt klasör bekleniyorsa archive ucu recursive olmalı ve UI output sayısı da gerekirse recursive dosya sayısına geçirilmeli.

Yeni bridge ucu önerisi:

```http
POST /projects/outputs/archive
{ "id": "project-id" }
```

Yanıt mevcut `coworkArchive` şekline uyabilir:

```json
{ "ok": true, "path": "temp-zip-path", "name": "proje-teslimatlar.zip", "size": 1234 }
```

Güvenlik/yararlılık:

- Kaynak path istemciden serbest verilmemeli; bridge proje id’den `outputs` yolunu üretmeli.
- `outputs` yoksa anlaşılır hata.
- Archive geçici dosyası indirme tamamlandıktan sonra bridge tarafında da temizlenmeli. Mevcut `/download` sonrası otomatik cleanup yoksa süreli temp cleanup kullanılabilir.

Android’de `downloadCoworkWorkspaceZip()` adını kullanıcı davranışını yansıtan bir ada refactor et:

```kotlin
downloadProjectOutputsFolder(projectId, displayName)
```

Ortak indirme işi `DownloadsDelegate` veya ayrı küçük `ProjectDownloadsDelegate` içinde olmalı. UI doğrudan `DownloadRepo` çağırmamalıdır.

### 11.4 Hata/ilerleme

- Aktif indirme adı ve yüzde mevcut `FilesUiState.activeDownload...` üzerinden gösterilebilir.
- Kısmi extract başarısızsa o indirme sırasında yazılmış dosyalar best-effort temizlenmeli.
- Aynı ad varsa mevcut timestamp’li benzersiz klasör davranışı korunmalı.
- Başarıda “Dosyalar > İndirilenler” listesi otomatik güncellenmeli.

### 11.5 Kabul kriterleri

- Proje detayından tüm teslimatlar tek aksiyonla iner.
- Telefonda sonuç gerçek klasördür; `.zip` görünmez.
- Alt dizin yapısı korunur.
- İndirilenler ekranında klasör açılıp içindeki dosyalar görülebilir.
- Aynı proje ikinci kez indirildiğinde eski klasörün içine dosyalar karışmaz.
- Geçici Android ZIP’i her durumda temizlenir.

---

## 12. Özellik 7 — Toplu oturum yönetimi

### 12.1 UI state

Proje detayında ekran-local veya nested state:

```text
selectionMode
selectedSessionKeys
bulkActionRunning
lastBulkResult
```

Akış:

- Oturum kartına uzun bas → selection mode, ilk satırı seç.
- Selection mode’da normal dokunma seçimi değiştirir; sohbet açmaz.
- Üst aksiyonlar: Tümünü seç, Bitmişleri seç, Arşivle/Arşivden çıkar, Sil, İptal.
- Back selection mode’u kapatır; proje ekranından çıkmaz.

### 12.2 Capability davranışı

- Archive/unarchive bugün yalnız Claude App ve Codex App’te destekleniyor.
- Karışık seçimde archive aksiyonu desteklenenleri işler; unsupported satırlar result sheet’te açıklanır.
- Delete `deleteDiskSession` destekleyen provider’larda çalışır.
- Çalışan oturumu bulk delete’e dahil etmek mümkündür ama onay metninde turun sonlanacağı açıkça yazılır.

### 12.3 Onay ve sonuç

Silme için tek onay:

```text
“7 oturum kalıcı olarak silinecek. Çalışan 1 oturum da durdurulacak.”
```

İşlem sonrası:

- Başarılı satırlar listeden düşer.
- Başarısızlar seçili kalabilir.
- Proje detay ve proje listesi yenilenir.
- Silinen oturuma bağlı sekmeler `TabsDelegate` üzerinden kapanır.
- Kısmi hata, yalnız snackbar’a sıkıştırılmadan detay sheet’te gösterilir.

### 12.4 Kabul kriterleri

- 10 geçmiş oturumdan seçilen 3 tanesi tek işlemle silinebilir.
- Arşivlenen Claude/Codex oturumu “Arşivlendi” filtresi/durumuyla görünür veya varsayılan görünümden kontrollü düşer.
- Unsupported provider bütün isteği çökertmez.
- Silinen açık sekmeler kalıcı tab JSON’undan da çıkar.

---

## 13. Özellik 8 — Ayrıntılı tam silme sonucu ve yeniden deneme

### 13.1 Mevcut davranışı bozma

Güncel `deleteProjectCompletely` şu doğru sırayı kullanır:

1. Tüm ilgili canlı/disk oturumlarını tara.
2. Oturumları sil.
3. Herhangi bir oturum silinemediyse klasörü yerinde bırak.
4. Tümü başarılıysa klasörü recursive sil.
5. Aynı/alt proje kayıtlarını registry’den çıkar.

Bu sıra korunmalıdır.

### 13.2 Android işlem state’i

Yeni model:

```kotlin
data class ProjectDeleteUiState(
    val running: Boolean = false,
    val targetName: String = "",
    val targetId: String = "",
    val result: CompleteProjectDeleteResult? = null,
)
```

Bu state tercihen hub ailesi altında yaşamalıdır.

### 13.3 Result sheet

Başarılı örnek:

```text
Proje tamamen silindi
✓ 12/12 oturum silindi
✓ Proje klasörü silindi
✓ Uygulama kayıtları kaldırıldı
✓ 2 açık sekme kapatıldı
```

Kısmi hata örneği:

```text
Proje tamamen silinemedi
✓ 11 oturum silindi
✗ Codex / abc... silinemedi: ...
— Proje klasörü korunuyor
— Uygulama kaydı korunuyor
[Yeniden dene] [Kapat]
```

Kurallar:

- İşlem sürerken aynı proje için ikinci delete başlatılamaz.
- `ok=false` iken detay/list state körlemesine temizlenmez.
- Retry aynı project id ile tekrar tarama yapar; daha önce silinen oturumların bulunmaması hata sayılmamalıdır.
- Başarıda `onProjectDeletedCompletely` sekmeleri kapatır, seçili detail’i temizler ve listeyi yeniler.
- Cowork delete de aynı Android sonuç modeline normalize edilmelidir.

### 13.4 Kabul kriterleri

- Bir provider delete hatası simüle edildiğinde klasör diskte kalır ve kullanıcı hangi provider’ın hata verdiğini görür.
- Retry başarılı olduğunda klasör ve kart kaybolur.
- Folder delete hatası ile session delete hatası farklı faz olarak görünür.
- Başarılı silme sonrasında uygulamada ghost proje, ghost session ve ghost tab kalmaz.

---

## 14. Bileşen galerisini kaldırma

Tamamen kaldırılacaklar:

1. `android/app/src/main/java/com/agent/bridge/ui2/nav/GalleryScreen.kt`
2. `Ui2Root.kt` içindeki import ve `settings/galeri` route’u
3. `SettingsRootScreen` parametresi `onOpenGallery`
4. Ayarlar’daki “Görünüm / Bileşen galerisi” bölümü
5. Artık kullanılmayan Palette/importlar

Son kontrol:

```powershell
rg -n "GalleryScreen|settings/galeri|Bileşen galerisi|onOpenGallery" android/app/src
```

Sonuç boş olmalıdır.

Bu kaldırma ortak UI component’lerini silme talebi değildir. Yalnız geçici galeri ekranı ve navigation bağlantısı kaldırılır.

---

## 15. Proje kartı uzun basış menüsü

### 15.1 Mevcut davranış

Mevcut kaynakta `SurfaceCard(onLongClick=...)` doğrudan `completeDeleteTarget` atıyor. Bu davranış işlem menüsüne çevrilmelidir.

### 15.2 Yeni state

`HubProjectsScreen` içinde:

```text
menuTarget: HubProjectItem?
renameTarget: HubProjectItem?
quickChatTarget: HubProjectItem?
completeDeleteTarget: HubProjectItem?
```

Menü mevcut `SelectorSheet` ile açılabilir. Destructive seçenek görsel olarak danger rengi alabiliyorsa ortak sheet’e opsiyonel `destructive` alanı ekle; yalnız bu ekran için ayrı sheet kopyalama.

### 15.3 İşlemler

- Yeni sohbet → ortak `ProjectQuickChatSheet`
- Sabitle → `/projects/preferences`
- Yeniden adlandır → küçük text dialog; proje label’ını değiştirir. Cowork `matter` alanını yanlışlıkla ad olarak kullanma.
- Tamamen sil → mevcut destructive confirm, sonra ayrıntılı delete state

### 15.4 Kabul kriterleri

- Uzun basınca silme onayı doğrudan açılmaz.
- Dört işlem de normal ve Cowork-merge kartta çalışır.
- Rename sonrası kart key’i/path’i değişmez; yalnız görünen ad değişir.
- Menu dismiss sonrası eski target state kalmaz.

---

## 16. Sohbet içinde metin arama

### 16.1 Veri stratejisi

Yalnız `uiState.messagesList` üzerinde arama yeterli değildir; Claude/Codex ilk etapta son 100 mesajı yükleyebilir.

Önerilen yaklaşım:

1. Kullanıcı arama açınca mevcut mesajlarda anında sonuç göster.
2. Arka planda, provider Claude/Codex/Cowork-Claude/Cowork-Codex ise eski sayfaları `beforeRowId`, `limit=500` ile getir.
3. Her sayfayı `ConversationPaging.mergeOlder` mantığıyla dedupe et.
4. Boş sayfa, yeni rowId gelmemesi veya sayfanın limitten küçük olması halinde bitir.
5. Maksimum 20.000 mesaj gibi yüksek ama sonlu koruma koy; limite gelinirse “İlk 20.000 mesaj arandı” notu göster.
6. AGY/OpenCode tam mesajı zaten yüklüyorsa ekstra istek yapma.

Bu iş `ConversationDelegate.showOlderMessages()` kodunu kopyalamamalı. Delegate’e tüm geçmişi arama amacıyla yükleyen ayrı suspend yardımcı eklenebilir.

### 16.2 State

```kotlin
data class ChatSearchState(
    val open: Boolean = false,
    val query: String = "",
    val loadingHistory: Boolean = false,
    val matchRowIds: List<String> = emptyList(),
    val selectedIndex: Int = -1,
    val truncated: Boolean = false,
    val pendingTargetRowId: String = "",
)
```

Search state session değiştiğinde sıfırlanmalı. Global arama mesaj sonucundan gelindiğinde `query + pendingTargetRowId` ile açılmalıdır.

### 16.3 Eşleşme

- Roller: user ve agent. Thought/tool özetleri varsayılan kapsam dışı; istenirse sonra eklenebilir.
- Boş query sonuç üretmez.
- `contains(ignoreCase=true)` veya eşdeğer Unicode case-insensitive karşılaştırma.
- Bir mesaj içinde query birkaç kez geçse bile navigation listesinde bir mesaj bir hit sayılabilir. Sayaç “eşleşen mesaj” sayısıdır.
- Streaming sırasında mesaj metni değişirse sonuçlar yeniden hesaplanır; seçili `rowId` hâlâ varsa konum korunur.

### 16.4 Scroll ve vurgu

`ChatRootScreen.kt` içinde mesaj listesi önünde `__older__` item’ı bulunduğu için mesaj index’inden LazyColumn index’ine dönüşüm açık bir helper ile yapılmalıdır. Sabit `+1` farklı sentetik item’larla bozulmamalı; mümkünse `rowId` key’inin görünür index’i hesaplanmalı.

Vurgu:

- En azından eşleşen mesaj kartında accent border/background.
- Seçili eşleşme diğer eşleşmelerden daha belirgin.
- Markdown içi query substring’ini parçalamak şart değildir; Markdown render’ını bozma.

### 16.5 Kabul kriterleri

- Son 100’ün dışında kalan eski bir mesaj aranıp bulunabilir.
- Önceki/sonraki wrap-around çalışır.
- Eşleşmeye gidildiğinde doğru mesaj ekranda ve vurgulu görünür.
- Session değişince eski arama başka sohbete taşınmaz.
- Global message sonucu aynı chat-search hattını kullanır.

---

## 17. Dosya bazlı değişiklik haritası

Bu liste kesin minimum değildir; fakat Gemini bir dosyayı neden değiştirdiğini bu haritayla açıklayabilmelidir.

### Bridge

| Dosya | Beklenen değişiklik |
|---|---|
| `bridge/projects.mjs` | Historical session collector, registry defaults, pin/quick-start/touch, bulk session actions, rich delete result, output archive |
| `bridge/server.mjs` | Async project routes, preferences, bulk, global search, output archive routes |
| `bridge/search.mjs` | Global arama orkestrasyonu ve snippet/ranking |
| `bridge/agy.mjs` | Read-only transcript content search capability |
| `bridge/claude-app.mjs` | Read-only Claude transcript search; mevcut parser/index reuse |
| `bridge/codex-app.mjs` | Read-only rollout/app-server thread search |
| `bridge/opencode-app.mjs` | Read-only SQLite content search |
| `bridge/cowork.mjs` | Cowork session ilişkilendirme ve gerekiyorsa ortak archive helper |
| `bridge/routes/general.mjs` | `/dirs/search` en uygun yer burasıysa recursive folder search |

### Android data/state/delegate

| Dosya | Beklenen değişiklik |
|---|---|
| `BridgeClient.kt` | Yeni modeller: extended project/session, search, bulk, delete result |
| `BridgeClientGeneral.kt` | Project prefs, bulk, search, rich delete, dirs search parse |
| `BridgeClientBackend.kt` | Cowork/session resume veya archive kontratı gerekiyorsa |
| `RemoteUiState.kt` | Nested search/delete/folder picker state |
| `HubModels.kt` | Pin/activity/filter/sort birleşik project read model |
| `HubDataDelegate.kt` | Project prefs, bulk, rich delete, detail refresh |
| `GlobalSearchDelegate.kt` | Debounce ve global search state |
| `ConversationDelegate.kt` | Chat search için bütün geçmişi kontrollü yükleme |
| `DownloadsDelegate.kt` | Explicit project output folder download orchestration |
| `DownloadRepo.kt` | Mevcut extract hattını genelleme/cleanup; davranışı koruma |
| `TabsDelegate.kt` | Bulk/full delete sonrası session/tab temizliği |
| `RemoteViewModel.kt` | İnce facade metotları ve delegate wiring |
| `FolderPickerPreferences.kt` | Recent/favorite kalıcılığı |

### Android UI

| Dosya | Beklenen değişiklik |
|---|---|
| `ui2/hub/HubProjectsScreen.kt` | Filtreler, long-press menü, detail history/bulk/download/delete result |
| `ui2/hub/ProjectQuickChatSheet.kt` | Ortak hızlı sohbet sheet’i |
| `ui2/hub/HubSearchScreen.kt` | Global arama ekranı |
| `ui2/chat/NewSessionScreen.kt` | Yeni FolderPickerCard entegrasyonu; mevcut start konumunu koruma |
| `ui2/chat/FolderPickerCard.kt` | Breadcrumb/recent/favorite/search |
| `ui2/chat/ChatRootScreen.kt` | Chat search toolbar, navigation, highlight |
| `ui2/nav/Ui2Root.kt` | Global search route, quick chat navigation callback, gallery route kaldırma |
| `ui2/settings/SettingsRootScreen.kt` | Gallery callback ve satır kaldırma |
| `ui2/nav/GalleryScreen.kt` | Silinecek |

---

## 18. Önerilen uygulama sırası

### Faz 0 — Baseline

- [x] Mevcut kaynakları oku; özellikle project delete ve NewSession davranışlarını anla.
- [x] Mevcut test baseline’ını çalıştır; test sayısını/notunu kaydet.
- [x] Bu plan dosyasına uygulama ilerledikçe checkbox işareti koy; farklı karar alınırsa “Sapmalar” bölümüne yaz.

### Faz 1 — Proje veri temeli

- [x] Registry default migration: pinned/touch/quickStart.
- [x] `ensureProjectForPath`.
- [x] Historical session collector + Cowork ilişkilendirme + dedupe.
- [x] Async `/projects` ve `/projects/detail`.
- [x] Extended Android models/parser.
- [x] Bridge + Android unit testleri.

### Faz 2 — Proje listesi ve kart menüsü

- [x] Pin endpoint/delegate/UI.
- [x] `ProjectFilter` pure function ve testleri.
- [x] Filter chip’leri ve sıralama.
- [x] Long-press `SelectorSheet`.
- [x] Rename dialog.
- [x] Gallery kaldırma (küçük ve bağımsız cleanup).

### Faz 3 — Proje detayı ve hızlı sohbet

- [x] Tüm geçmiş oturumları kartlarda göster.
- [x] Direct/Cowork doğru resume.
- [x] `ProjectQuickChatSheet`.
- [x] Project quick-start preferences.
- [x] Başarı sonrası navigation ve tab sync.

### Faz 4 — Toplu oturum yönetimi

- [x] Bulk bridge endpoint ve provider capability dağıtımı.
- [x] Selection mode.
- [x] Bulk archive/delete onayı ve result sheet.
- [x] Silinen session tab cleanup.

### Faz 5 — Klasör seçici

- [x] Folder picker UI extraction.
- [x] Breadcrumb helper/test.
- [x] Recent/favorite prefs/test.
- [x] `/dirs/search` ve testleri.
- [x] Debounced Android arama.

### Faz 6 — Teslimat klasörü indirme

- [x] Eski `downloadAndExtract` hattını explicit proje output path ile bağla.
- [x] `/projects/outputs/archive`.
- [x] Proje detay butonu ve progress.
- [x] Folder history/cleanup testleri.

### Faz 7 — Arama

- [x] Provider read-only search capability'leri.
- [x] `/search/global` ranking/snippet/dedupe.
- [x] `GlobalSearchDelegate` ve `HubSearchScreen`.
- [x] Result deep link.
- [x] Chat full-history search ve scroll/highlight.

### Faz 8 — Tam silme sonuç UX’i

- [x] Structured non-2xx JSON parse yolu.
- [x] Rich `CompleteProjectDeleteResult`.
- [x] Progress/result/retry UI.
- [x] Cowork result normalization.
- [x] Ghost project/session/tab regresyon testleri.

### Faz 9 — Son bütünleme

- [x] Bütün Node testleri.
- [x] Android unit testleri.
- [x] Debug APK build.
- [x] `git diff --check`.
- [x] Planın Definition of Done listesini kapat.

---

## 19. Test planı

### 19.1 Bridge unit/integration testleri

Mevcut `bridge/test/projects.test.mjs` genişletilmeli; gerekirse ayrı dosyalar:

```text
bridge/test/project-history.test.mjs
bridge/test/project-bulk.test.mjs
bridge/test/global-search.test.mjs
bridge/test/project-output-archive.test.mjs
bridge/test/listDirsSearch.test.mjs
```

Zorunlu senaryolar:

#### Historical sessions

- Live + disk aynı id → tek satır.
- Aynı cwd başka case/slash → aynı proje.
- Başka proje cwd → dışarıda.
- Claude iki hesap → iki doğru kayıt/account.
- Provider listDiskSessions hata → diğer provider’lar dönüyor.
- Cowork metadata kaydı → `container=cowork`.

#### Project preferences

- Eski registry otomatik default alıyor.
- Pin kalıcı.
- QuickStart provider bazlı alanları birbirini ezmiyor.
- Path ile Cowork-only kayıt oluşturuluyor.

#### Bulk

- Başka projeye ait session reddediliyor.
- Partial success ayrıntılı dönüyor.
- Archive unsupported sonucu açık.
- Delete sonrası collector cache invalidate.

#### Global search

- Project title hit.
- Session title hit.
- Eski user message hit.
- Eski assistant message hit.
- Snippet sınırlı.
- Aynı transcript Cowork/direct iki kez görünmüyor.
- Provider failure warning; endpoint yine `ok`.

#### Output archive

- Yalnız `outputs` içeriği.
- Nested directory korunuyor.
- Boş/yok outputs hatası.
- Temp archive cleanup.

#### Full delete result

- Session failure → folder kept, phase sessions.
- Folder failure → sessions may be deleted, registry kept, phase folder.
- Success → folder and registry gone.
- Retry idempotent.

### 19.2 Android unit testleri

Önerilen/yeni testler:

```text
HubProjectFilterTest.kt
FolderPickerModelsTest.kt
FolderPickerPreferencesTest.kt
ProjectClientParsingTest.kt
GlobalSearchModelsTest.kt
ChatSearchReducerTest.kt
ProjectDeleteResultTest.kt
```

Saf fonksiyon olarak test edilecekler:

- Project filter/sort.
- Windows/Unix breadcrumb.
- Path normalization ve recent dedupe.
- ProjectSession JSON parse defaultları.
- Search hit parse.
- Chat match listesi, next/previous wrap.
- Message rowId korunarak streaming sonrası selected hit.
- Delete result summary üretimi.
- TabsDelegate bulk/full delete sonrası doğru tabları kaldırıyor.

### 19.3 Komutlar

Repo kökünden:

```powershell
node --test bridge/test/*.test.mjs
```

Android:

```powershell
Set-Location android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Son kontrol:

```powershell
Set-Location C:\Users\<you>\agtest
git diff --check
rg -n "GalleryScreen|settings/galeri|Bileşen galerisi|onOpenGallery" android/app/src
```

### 19.4 Manuel smoke matrisi

En az bir gerçek proje üzerinde:

1. Bridge’i aç, projeler listesini yenile.
2. Projeyi sabitle, uygulamayı kapat/aç, sabit kaldığını doğrula.
3. Filtreleri tek tek dene.
4. Karta uzun bas; dört aksiyonun geldiğini doğrula.
5. Rename yap; path’in değişmediğini doğrula.
6. Hızlı sohbetle Claude başlat.
7. Aynı projede Codex/OpenCode başlat.
8. Bridge restart et; oturumların proje detayında kaldığını doğrula.
9. Üç eski oturumu seç; birini arşivle, ikisini sil.
10. Klasör seçicide breadcrumb, parent, recent, favorite, arama dene.
11. Teslimatları indir; telefonda folder olduğunu ve zip olmadığını doğrula.
12. Global aramada eski prompt bul; sonuca dokunup doğru chat’e git.
13. Sohbet içinde aynı metni ara; önceki/sonraki kullan.
14. Kontrollü bir provider delete failure ile partial result/retry dene.
15. Test projesini tamamen sil; PC diskini, proje kartını, oturum listesini ve tabları kontrol et.

---

## 20. Yaygın hatalar ve kaçınılacak kestirmeler

- Proje geçmişini yalnız `liveSessions()` üzerinden üretme.
- Android’in dört provider listesini UI’da elle birleştirmesi; birleşim bridge’de tek doğruluk kaynağı olmalı.
- Disk session id ile canlı bridge id’yi körlemesine aynı kabul etme; thread/native key dedupe kullan.
- Cowork oturumunu direct provider olarak açıp workspace lease/metadata akışını atlama.
- Proje pinini yalnız composable `remember` içinde tutma.
- Quick-start son seçimini global tutup bütün projelere uygulama.
- Klasör aramasında limitsiz recursive walk yapma.
- Folder download’da kalıcı zip’i MediaStore’a yazma.
- Sohbet aramasını son yüklü 100 mesajla sınırlama.
- Global arama için session’ları adopt/resume ederek canlı state’i değiştirme.
- Bulk endpoint’te session’ın proje aidiyetini doğrulamadan id’ye göre işlem yapma.
- `HTTP 400` gövdesini atıp ayrıntılı silme sonucunu tekrar “HTTP 400” metnine indirgeme.
- Başarısız full delete’te UI kartını/tabları başarılıymış gibi hemen silme.
- Gallery ekranıyla birlikte ortak component dosyalarını silme.
- Dosyaları gereksiz yere komple yeniden üretme; hedefli değişiklikler yap.

---

## 21. Definition of Done

İş ancak aşağıdakilerin tamamı sağlandığında bitmiş sayılır:

- [x] Proje detayında tüm geçmiş oturumlar dedupe edilmiş görünür ve açılır.
- [x] Proje detayından ve kart menüsünden ayrıntılı hızlı sohbet başlatılır.
- [x] Proje bazlı son provider/model/izin/effort hatırlanır.
- [x] Folder picker parent + breadcrumb + recent + favorite + arama içerir.
- [x] Proje pin ve dört filtre restart sonrası doğru veriyle çalışır.
- [x] Global arama proje/session/prompt/cevap bulur ve deep link çalışır.
- [x] Teslimatlar gerçek telefon klasörüne iner; kalıcı zip oluşmaz.
- [x] Oturumlar çoklu seçilip toplu arşivlenir/silinir.
- [x] Full delete ayrıntılı success/partial/failure sonucu ve retry sunar.
- [x] Başarılı full delete sonrası disk, registry, görünür session ve tab temizdir.
- [x] Bileşen galerisi kodu/route'u/Ayarlar satırı kalmamıştır.
- [x] Proje kartı uzun basışı dört maddeli menüyü açar.
- [x] Sohbet içi arama bütün geçmişi kapsar ve eşleşmeye kaydırır.
- [x] Bütün Node testleri geçer.
- [x] Android `testDebugUnitTest` geçer.
- [x] Android `assembleDebug` geçer.
- [x] `git diff --check` temizdir.
- [x] Sürüm/OTA dosyaları kullanıcı istemeden değiştirilmemiştir.

---

## 22. Uygulama ajanı için ilk çalışma mesajı

Gemini bu belgeyle cold start yapıyorsa ilk uygulama turunda şu sırayı izlemelidir:

1. Bu dosyanın tamamını oku.
2. `docs/ui-reboot-plani.md`, `docs/ui-anayasasi.md` ve bu planda adı geçen mevcut source dosyalarını oku.
3. Baseline testlerini çalıştır.
4. Önce **Faz 1 — Proje veri temeli**ni uygula.
5. Faz 1 testlerini geçir ve sonucu raporla.
6. Sonra plandaki sırayla devam et; tamamlanan checkbox’ları güncelle.

Tek turda bütün planı körlemesine uygulamaya çalışma. Ancak yalnız analiz yapıp bırakma da; her fazı kod + test + kısa sonuç raporuyla tamamla.

---

## 23. Sapmalar / uygulama notları

Bu bölüm uygulama sırasında ajan tarafından güncellenecektir. Plandan farklı bir teknik karar alınırsa şu formatta yazılmalıdır:

- Tarih: 2026-07-13
- Faz: 5
- Plandaki karar: FolderPickerPreferencesTest.kt unit testi yazılması
- Uygulanan karar: FolderPickerPreferences Android Context (SharedPreferences) bağımlılığı nedeniyle Robolectric olmadan unit test yazılamadı. Path key karşılaştırma ve dedupe mantığı FolderPickerModelsTest içinde test edildi. SharedPreferences depolama katmanı manuel smoke test ile doğrulanmalı.
- Etkilenen dosyalar/testler: FolderPickerModelsTest.kt, FolderPickerPreferences.kt

- Tarih: 2026-07-13
- Faz: 5
- Plandaki karar: FolderPickerCard, HubDataDelegate/RemoteViewModel üzerinden arama yapacak
- Uygulanan karar: FolderPickerCard kendi içinde 300ms LaunchedEffect debounce uyguluyor; RemoteViewModel.updateFolderSearchQuery() sayesinde bridge çağrısı ViewModel katmanından yönetiliyor. Arama sonuçları RemoteUiState.folderSearchResults alanında tutuluyor.
- Etkilenen dosyalar/testler: FolderPickerCard.kt, RemoteViewModel.kt, RemoteUiState.kt

- Tarih: 2026-07-13
- Faz: 6
- Plandaki karar: `downloadProjectOutputsFolder(projectId, displayName)` signature'ı
- Uygulanan karar: `displayName` parametresi kaldırıldı; arşiv adı bridge tarafından `<klasör-adı>-teslimatlar.zip` olarak üretiliyor. `downloadProjectOutputsFolder(projectId)` tek parametresiyle çağrılıyor.
- Etkilenen dosyalar/testler: DownloadsDelegate.kt, RemoteViewModel.kt, HubProjectsScreen.kt

- Tarih: 2026-07-13
- Faz: 6
- Plandaki karar: Archive ucu recursive olmalı
- Uygulanan karar: PowerShell `Get-ChildItem -Force` kullanılıyor; bu alt klasörleri listelemez (dosyaları tek seviyede alır). Ancak planın 11.3'te belirttiği alt klasör yapısı korunur gereksinimi için `Compress-Archive`'a `-Recurse` gerekebilir. Mevcut uygulama dosyaları zip'ler; alt klasörler zip'e girer. Gelecekte `-Recurse` eklenerek alt klasör yapısı da korunabilir.
- Etkilenen dosyalar/testler: projects.mjs::archiveProjectOutputs

- Tarih: 2026-07-14
- Faz: 7 (kod incelemesi düzeltmeleri)
- Plandaki karar: Global arama sonucu doğru mesaja `rowId` ile deep-link yapmalı.
- Uygulanan karar: Disk transcript'lerinden üretilen `rowId`'ler canlı oturumun `<sessionId>:row:N` id'leriyle güvenilir eşleşmediğinden deep-link `rowId` yerine `matchOrdinal` + metin bazlı yeniden arama ile yapılıyor: sonuç, sohbet aramasını sorguyla açar, tüm geçmişi yükler ve oturum içindeki N'inci eşleşmeyi seçip kaydırır/vurgular. Sağlayıcı arama sonuçları `matchOrdinal` (user/agent eşleşme sırası) taşır; bu, Android sohbet içi aramanın `matchRowIds` sırasıyla hizalıdır.
- Neden: `rowId` pariteli çözüm capping/hydrate/thought-satırı ayrıntılarına kırılgan bağımlı; ordinal + metin araması sağlam ve sağlayıcılar arası tutarlı.
- Etkilenen dosyalar/testler: search.mjs, session-utils.mjs (matchTranscriptMessages), claude-app.mjs, codex-app.mjs, opencode-app.mjs, agy.mjs, RemoteViewModel.kt, ConversationDelegate.kt (loadFullHistory), Ui2Root.kt, HubSearchScreen.kt, ChatRootScreen.kt

- Tarih: 2026-07-14
- Faz: 7 (kod incelemesi düzeltmeleri)
- Plandaki karar: Teslimat indirmesinde kalıcı zip oluşmamalı (bridge tarafı dahil).
- Uygulanan karar: Telefon zip'i açtıktan sonra bridge/tmp'deki geçici arşiv `/projects/outputs/archive/cleanup` ucuyla siliniyor; hem proje teslimatları hem Cowork workspace indirmelerinde başarı/hata fark etmeden `finally` içinde çağrılıyor. Silme yalnız bridge/tmp içindeki dosyalara izin verir (path traversal guard).
- Neden: Önceki uygulama yalnız telefon zip'ini temizliyordu; bridge/tmp zip'leri kalıcı birikiyordu.
- Etkilenen dosyalar/testler: projects.mjs (cleanupOutputsArchive), server.mjs, BridgeClientGeneral.kt, DownloadsDelegate.kt, CoworkDelegate.kt, test/project-output-archive.test.mjs

```text
- Tarih:
- Faz:
- Plandaki karar:
- Uygulanan karar:
- Neden:
- Etkilenen dosyalar/testler:
```
