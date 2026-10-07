# Telekumanda web arayüzü

Köprünün tarayıcı istemcisi. Compose Desktop istemcisinin (`android/desktop`)
yerini aldı; o modül 10.08.2026'da silindi.

Köprü `web/dist`i `/ui` altında servis eder. Kurulum yok, güncelleme kanalı
yok: `npm run build` yeter, köprü dosyaları her istekte diskten okur.

React + TypeScript + Vite. Test: Vitest.

## Çalıştırma

```bash
npm install
npm run dev      # http://localhost:5173/ui/
```

Geliştirme sunucusu `/ui` dışındaki **her** yolu köprüye (`http://127.0.0.1:8787`)
proxy'ler, WebSocket dahil. Başka bir köprüye bağlanmak için `BRIDGE_URL` ortam
değişkenini ver.

Üretimde arayüzü köprünün kendisi servis eder:

```bash
npm run build    # -> web/dist
```

Köprü `web/dist`i doğrudan diskten okur; **derledikten sonra köprüyü yeniden
başlatmaya gerek yok**, sayfayı yenilemek yeter.

```bash
npm test         # tek koşu
npm run test:watch
```

## Bilinmesi gerekenler

**Arayüz `/ui/` altında yaşar.** Köprüde ~56 uç nokta kökte duruyor
(`/health`, `/claude-app/*`, `/dirs`, `/download` …) ve router yalnız tam yol
eşleştiriyor. Ayrı önek olmasa yeni bir uç nokta eklendiği gün arayüzle
çakışırdı. Bu yüzden `vite.config.ts`'de `base: '/ui/'`.

**Statik dosyalar kimlik doğrulamasından ÖNCE servis edilir.** Sebep
`bridge/web-ui.mjs`'in başındaki notta: tarayıcı alt kaynak isteklerine
(`<script src>`) başlık ekleyemez, arayüz auth'un arkasında olsaydı beyaz
sayfa gelirdi. Paket istemci kodu, sır taşımaz. **API uçları auth'un arkasında
kalmaya devam ediyor** — oraya bir şey taşıma.

**Token adrese girmez.** Köprü token'ı yalnız `Authorization: Bearer`
başlığından kabul ediyor. REST çağrıları başlığı kullanır; tarayıcı WS el
sıkışmasına başlık ekleyemediği için akış, başlıkla alınan tek kullanımlık ve 30
saniyelik bir biletle açılır (`wsTicket` + `withTicket`). Hepsi `src/lib/api.ts`
içinde; kendi başına `fetch`/`WebSocket` kurma, oradaki yardımcıları kullan.

**Vitest `pool: 'threads'` ile koşar.** Varsayılan `forks` havuzu bu makinede
worker'ları 60 sn zaman aşımına düşürüyor (Node 25 + Windows). `vite.config.ts`
içindeki bu ayarı değiştirme.

**Testler varsayılan olarak `node` ortamında.** Bileşen testi yazarken dosyanın
en üstüne:

```ts
// @vitest-environment happy-dom
```

**İlk koşu soğuk önbellekte 60 sn'yi aşıp `Failed to start threads worker`
verebilir.** Ölçüldü: aynı DOM testi soğukta 52 sn, ikinci koşuda 1,04 sn.
Sebep kod değil, Windows'un `node_modules` ilk okumasını taraması. Hata alırsan
**komutu bir kez daha çalıştır** — testleri değiştirme, zaman aşımını büyütmeye
kalkma.

**Akış TAM geçmişi taşımaz.** Canlı oturum son **4000 satırla** sınırlı
(`bridge/session-utils.mjs`, `MAX_ROWS_HARD`); taşan satırlar arşiv JSONL'ine
düşüyor. Tam geçmiş yalnız `GET /<backend>/conversation?sessionId=&before=&limit=`
ile sayfalanarak gelir. Ölçüldü: 11.791 satırlık bir oturumda akış 4000 satır
verdi. Sohbet ekranı yukarı kaydırmada bu uçtan sayfalamalı — akıştan bekleme.

**Akış katmanını kullanma biçimi.** `useSessionStream(backend, sessionId)`
çağır; dönen `state` DAİMA tam birleşmiş durumdur (satırlar + birikimli meta).
Delta birleştirmeyi kendin yapma, `reduce()` zaten yaptı.

```tsx
const { state, connected, error } = useSessionStream('claude-app', sessionId)
// state.rows -> StreamRow[]   state.meta.running, .contextTokens, .plan ...
```

**Ajan seçmek ZORUNLU değil.** Varsayılan "Tümü": bütün backend'lerin
oturumları tek listede birleşir, bir ajan seçmek listeyi ona filtreler
(Android'deki davranışın aynısı). Bunun iki sonucu var:

- Karışık listede "aktif ajan" diye tek bir değer YOKTUR. Hangi uca
  bağlanılacağı **oturumun kendisinden** gelir (`TaggedSession.backend`).
  `useChatRows`'a geçilen backend, seçili oturumun sahibidir — kenar
  çubuğundaki seçici değil.
- Aynı `apiBackend`'i paylaşan backend'ler bir kez sorgulanır
  (`sessionSources`). cowork claude-app uçlarını kullanıyor; ikisi de
  sorgulansa aynı oturumlar listede iki kez çıkardı.

Bir backend düşerse listenin tamamı kaybolmaz: `listAllSessions` hataları
`errors` dizisinde ayrı taşır, ulaşılabilen backend'lerin oturumları gelir.

**Canlı liste TEK BAŞINA yalan söyler; disk kaydıyla zenginleştirilir.** Köprü
restart'ta bir kabuğu geri yüklerken transkriptini OKUMUYOR — `/sessions` o
oturum için `title: ''`, `turns: 0`, `lastUserAt: 0` diyor. Ölçüldü
(11.08.2026, canlı köprü): 98 "boş" canlı oturumun **41'i aslında başlıklı,
gerçek sohbetti** ve boş-oturum elemesi onları listeden düşürüyordu.
`listAllSessions` bu yüzden `/disk-sessions`i de çekip `unifySessions` ile
birleştiriyor. Canlı değer doluysa o kazanır (tur sürerken disk bayattır);
yalnız canlı boş bıraktığı alanlarda diske düşülür.

Android bu sorunu hiç yaşamıyor çünkü listesini canlı kabuklardan DEĞİL disk
oturumlarından kuruyor (`ChatRootScreen.kt`: `backendDiskSessions`).

**Birleştirme anahtarı `id` DEĞİL, `diskId ?? id`.** OMP canlı kabuğa her
seferinde yeni bir UUID veriyor; disk kaydının kimliği ayrı (OMP'nin kendi
UUIDv7'si) ve ikisi HİÇ kesişmiyor. Ölçüldü (11.08.2026):

| backend | canlı | disk | ortak id |
|---|---|---|---|
| claude-app | 102 | 72 | 44 |
| codex-app | 52 | 55 | 37 |
| **omp** | **3** | **1** | **0** |

Canlı oturum `diskId` alanıyla disk kaydına işaret ediyor. Yalnız `id`ye bakmak
aynı sohbeti listede iki satır yapıyordu (canlıda görüldü). Yeni bir backend
eklerken bu iki listenin kimliklerinin örtüşüp örtüşmediğini ÖLÇ.

**Canlı/Geçmiş sekmeleri KALDIRILDI.** Ayrım kullanıcının umursadığı bir şey
değildi, köprünün iç durumuydu — ve gerçek sohbetleri "Geçmiş"e saklıyordu.
`unifySessions` ikisini tek listede birleştirir; yalnız diskte olan satır
`live: false` taşır ve tıklanınca ÖNCE `adopt` edilir (canlı kabuğu olmayan
oturuma akış kurulamaz), sonra akış açılır. `adopt` gövdesinde `cwd` göndermek
şart: köprü oturumu o dizinde yeniden kuruyor.

**Oturum satırında repo adı başlıktan önce görünür.** Kaynak `cwd`nin son
klasör adıdır; tam yol rozetin ipucunda kalır. Satırdaki üç nokta, sağ tık veya
dokunmatik ekranda uzun basma oturum menüsünü açar. Menü capability güdümlüdür:
sabitleme, arşivleme ve yeniden adlandırma yalnız destekleyen backend'lerde;
kimlik kopyalama her zaman, silme ise `sessionDelete` bildiren backend'lerde
görünür. Silme mutlaka geri alınamazlık onayı ister.

**Kontroller altta.** Hesap/model/izin kipi/çaba/kes-durdur/bağlam yazma
kutusunun ALTINDAKİ şeritte; üstte yalnız başlık + ajan var. Bu şerit metin
sütununun (`.column`, 62rem) DIŞINDA, tam genişlikte duruyor — sütuna
sıkıştırıldığında kes/durdur/bağlam ikinci satıra sarıyordu (1920px'te
ölçüldü).

**Masaüstü kısayolu pencereyi Chromium'a bırakmaz.** Kısayol
`scripts/agentbridge-ac.vbs` → `agentbridge-ac.ps1` zincirini çağırıyor; betik
Brave'i açıp pencereyi `EnumWindows` ile bulup `ShowWindow(SW_MAXIMIZE)` ile
KENDİSİ büyütüyor.

Üç "kolay" yol da denendi, üçü de yetmedi (11.08.2026'da ölçüldü):

| yol | neden yetmiyor |
|---|---|
| `--window-size` bayrağı | Chromium `--app` boyutunu profilde saklar, komut satırını ezer |
| `Preferences`'ı elle yazmak | Brave tüm sözlüğü bellekte tutar; profili kullanan bir süreç açıkken yapılan disk düzenlemesi sonraki flush'ta geri alınır |
| kısayolun "Büyütülmüş başlat" bayrağı | yalnız Brave KAPALIYKEN; açıksa komut var olan sürece devredilir ve `STARTUPINFO` pencereyi hiç görmez |

En kötü hâl ikisinin birleşimi: **Brave açık + profildeki kayıt dar**. Betik bu
durumda da çalışıyor (ölçüldü: 1936×1048).

Kısayol **üç yerde** olabilir — masaüstü, Başlat menüsü ve görev çubuğuna
sabitlenmiş kopya. Yalnız birini düzeltmek yetmez.

## Bölümler

Kabuk (`src/shell/AppShell.tsx`) altı bölüm taşıyor: **Sohbet**, **Ara**,
**Projeler**, **Görsel**, **Notlar**, **Kullanım**. Ray dikey — ekran 1920px geniş, yatay yer bol;
dikey yer ise sohbetin okuma alanı.

Ekranlar **koşullu çiziliyor, gizlenmiyor**: sohbet bir WebSocket tutuyor ve
arka planda açık bırakmak, bakılmayan bir oturum için akış beslemek demekti.

### Oturum çalışma alanı

Kenar listesi Android'le aynı **Tümü / Sabitli / Arşiv** görünümünü taşır.
Arşiv görünümü canlı kabuklardan değil, yalnız destekleyen backend'lerin
`disk-sessions-search?archived=true` kayıtlarından kurulur; aksi hâlde açık ama
arşivlenmiş bir kabuk yanlışlıkla normal kayıt gibi görünürdü.

Açılan sohbetler üstte sekme olur. Sekme kimliği `backend:sessionId`dir — yalnız
`sessionId` kullanma, farklı sağlayıcılarda aynı kimlik olabilir. Sekmeler ve
aktif sekme localStorage'da korunur; ajan seçicisi yalnız kenar listesini süzer,
açık çalışma sekmesini kapatmaz.

### Global arama (`src/search/`)

`GET /search/global` ile proje, oturum ve tam transcript mesajlarını birlikte
arar. Mesaj sonucuna geçiş iki ayrı rastgele işlem değildir: hedef backend'in
oturumu bulunur, gerekirse `adopt` edilir, sonra `/conversation` sayfaları tam
geçmiş bitene kadar yüklenir ve `rowId` ya da `matchOrdinal` ile doğru satır
vurgulanır. Bu sıra bozulursa arama önceki açık sohbet üzerinde çalışabilir.

### Proje merkezi (`src/projects/`)

`/projects` ve `/projects/detail` Android'le aynı sözleşme üzerinden kullanılır.
Liste sabit/aktif/yeni çıktı filtrelerini; detay ekranı oturumları, teslimatları,
projeye bağlı notları, dosya gezginini, değişiklik/komut/plan eserlerini ve
güvenlik-MCP özetini gösterir. Proje oturumuna tıklamak Sohbet sekmesine gider;
disk oturumuysa önce adopt edilir. Dosya indirme auth başlıklı `apiObjectUrl`
üzerinden yapılır; token DOM adresine yazılmaz.

### Kullanım (`src/usage/`)

`GET /usage` — sağlayıcıların RESMİ kalan limitleri (`claude /usage` ve Codex
app ile aynı kaynak); köprü sayı hesaplamıyor, okuyor.

Şekil köprüden doğrulandı: Android'in `UsageBucket`'ı `window`/`metered` ve
grupta `source` da okuyor ama **canlı yanıtta bu alanlar yok** (Kotlin tarafı
`optString` varsayılanına düşüyor). TS tarafında yalnız gerçekten gelenler
tanımlı.

İki yerde gösteriliyor:

- **Kullanım sekmesi** — bütün gruplar, çubuklar, yenilenme saatleri.
- **Sohbetin alt şeridindeki düğme** (`UsageChip`) — tek sayı: aktif hesabın
  **en az kalan** penceresi. "En az kalan" doğru ölçü; kullanıcıyı ilk hangi
  pencerenin durduracağını söyler (5 saatlik %90 iken haftalık %12 ise sıkıntı
  haftalıkta). Tıklanınca hepsi açılır.

Eşleşme bucket id'sinin ÖNEKİ üzerinden: hesap kimlikleri (`personal`, `work`)
ve `codex` id'lerin başında duruyor (`personal-claude-5h`, `codex-primary`).
Önek hiçbir şeye uymazsa tüm bucket'lara düşülür — yanlış bir sayı
göstermektense ilgisiz ama doğru bir sayı göstermek yeğdir, etiket zaten
hangisi olduğunu yazıyor.

**"Yenile" `force=1` göndermeli.** Köprüde hesap başına 5 dk'lık limit
önbelleği var; force'suz yenileme o süre boyunca aynı sayıları geri getiriyor.
Otomatik/periyodik yüklemeler force'SUZ gider.

### Notlar (`src/notes/`)

Kapsam bilerek dar: **yalnız klavye notu**. `.ink` (kalem) notu oluşturulmaz ve
düzenlenmez — o yol Jetpack Ink yakalama + ML Kit tanıma üzerine kurulu, ikisi
de Android'de ve cihaz üstünde. Diskte kalem notu varsa türetilmiş `.md`si salt
okunur gösterilir.

**Frontmatter korunur.** Not `.md`si başında YAML bloğu taşıyor
(`title`, kalem notlarında ayrıca `source`, `source_hash`, `stale`). Editör
yalnız gövdeyi gösterir; `stale`/`source_hash` silinirse köprü notun kaynağıyla
bağını kaybeder. `splitFrontmatter`/`joinFrontmatter` bunu garantiliyor ve
gidiş-dönüş testli.

**Kırpılmış gövde ÜSTÜNE YAZILMAZ.** `/file` büyük dosyayı `truncated: true`
ile kesiyor; onu geri kaydetmek notun kalanını silerdi.

## Sohbet ekranı

```
src/chat/
  ChatScreen.tsx     üç parçayı birleştiren ekran (App.tsx buraya bağlı)
  useChatRows.ts     canlı akış + sayfalanan geçmiş, tek liste
  useStickToBottom.ts akış sürerken dipte kalma
  history.ts         /conversation sayfalama ve satır birleştirme
  composer/          yazma kutusu, ekler, slash komutları, şıklar
  render/            markdown, kod bloğu, mesaj satırı, düşünce grupları
  sessions/          canlı liste, geçmiş (disk) liste, yeni oturum, cwd seçici
  controls/          hesap/model/çaba/izin kipi, onay, durdur
```

**Ek gönderme prompt gövdesinden GİTMEZ.** `/<backend>/prompt` içindeki
`images` alanını hiçbir backend okumuyor (`claude-app.prompt` imzası:
sessionId/text/model/permissionMode) — oraya bir şey koymak sessizce kaybolur.
Gerçek yol: `POST /context/file?filename=` ham bayt → köprünün geçici
klasöründeki PC yolu döner (24 sa TTL), yol prompt metnine "Ek dosyalar:"
bloğu olarak eklenir. Biçim Android'le birebir aynı
(`ConversationDelegate.kt:95`) ki aynı oturuma iki istemciden bakınca geçmiş
tutarlı görünsün. Hepsi `composer/attachments.ts` içinde.

**Tarayıcıda bakmak.** `scripts/cdp.mjs` 9222 portundaki Brave'i sürüyor
(chrome-devtools MCP work profilinde kayıtlı değil):

```bash
& scripts/tarayici-debug.ps1              # debug tarayıcısını aç
node scripts/cdp.mjs open "http://127.0.0.1:8787/ui/#token=..."
node scripts/cdp.mjs eval "document.title"
node scripts/cdp.mjs shot tmp/ui.png 1600 1000
node scripts/cdp.mjs console
```

Bu arayüzün kusurlarının çoğu birim testleriyle GÖRÜNMEDİ: 191 ayrı düşünce
kutusu, dağınık üst çubuk, 98 boş oturumla dolu liste — hepsi ekrana
bakılarak bulundu. Görsel bir değişiklik yaptıysan bak.

**Prompt gönderme tekilleştirmeli.** `POST /<backend>/prompt` gövdesinde
`requestId` var ve köprü aynı id'yi ikinci kez görünce iki farklı şey yapıyor:

| Yanıt | Anlamı | Doğru davranış |
|---|---|---|
| 200 + `duplicate:true` | zaten güvenle teslim edilmiş | tekrar gönderme, başarı say |
| 409 + `duplicate:true` | güvenle teslim EDİLEMEMİŞ | **yeni** `requestId` ile gönder |
| ağ hatası (yanıt yok) | teslim edilip edilmediği bilinmiyor | **aynı** `requestId` ile tekrar dene |

Sonuncusu ters gelebilir ama doğrusu bu: yeni id üretmek mesajı ikilerdi.
Hepsi `composer/promptApi.ts` içinde ve testli — kendi `fetch`'ini yazma.

**`crypto.randomUUID` güvenli bağlam ister** ve Tailscale IP'si üzerinden düz
http ile açıldığında tanımsızdır. `newRequestId()` bu yüzden `getRandomValues`
üzerinden UUID kuruyor; kimlik üretirken onu kullan.

## Çok backend

Arayüz `claude-app`, `codex-app`, `opencode-app`, `omp`, `agy` ve `cowork` ile
çalışır. **Hangi kontrolün çizileceğine backend adına bakarak karar verme** —
köprünün `/backends` kataloğu tek kaynaktır (`bridge/backend-contract.mjs`):

```tsx
{caps?.permissionModes && <PermissionModePicker … />}
{caps?.efforts && <EffortPicker … />}
{caps?.plan && <PlanPanel … />}
```

`lib/backends.ts` katalogu çeker ve köprüye ulaşılamazsa gömülü tabloya düşer.
`apiBackend` gerçek uç önekidir: `cowork` bir sunum katmanıdır ve `claude-app`
uçlarını kullanır — ağ çağrılarında `id` değil `apiBackend` kullan.

Yetenek matrisi (köprüden, `server.mjs` kayıtlarıyla doğrulandı):

| | claude-app | codex-app | opencode-app | agy |
|---|---|---|---|---|
| approvals | ✓ | ✓ | ✓ | — |
| permissionModes | ✓ | ✓ | ✓ | — |
| efforts | ✓ | ✓ | — | — |
| interrupt | ✓ | — | — | — |
| runpod | — | — | ✓ | — |
| plan | — | ✓ | — | — |
| context | ✓ | ✓ | ✓ | — |

`/stop` her backend'de var; `interrupt` ondan ayrı ve yalnız claude-app'te.

**Eski köprüler `efforts`/`interrupt`/`runpod` bildirmiyor** (bu alanlar sonradan
eklendi). O ikisi için varsayılan `false` değil, gömülü tablodaki değer
kullanılıyor — yoksa güncellenmemiş bir köprüde çaba seçicisi sebepsiz
kaybolurdu.

## Canlı köprüye karşı entegrasyon testi

`src/lib/stream/live-bridge.test.ts` varsayılan olarak atlanır. Koşturmak için:

```bash
BRIDGE_TOKEN=<köprü token'ı> npx vitest run live-bridge
```

Token'ı repoya YAZMA; `bridge/config.json` gitignore'da ve orada duruyor.
Bu test akışı gerçek bir oturumdan kurup REST sohbetiyle karşılaştırıyor ve
`since` ile yeniden bağlanmanın satır ikilemediğini ölçüyor.

## Kaynak olarak Android istemcisi

Aynı işi yapan olgun bir uygulama zaten var; ekran davranışı için şartname
niyetine okunabilir:

| Konu | Dosya |
|---|---|
| Akış sözleşmesi (snapshot/seq/delta) | `android/shared/.../SessionStreamManager.kt` |
| Uç nokta listesi ve gövde şekilleri | `android/shared/.../BridgeClientBackend.kt` |
| Sohbet ekranı davranışı | `android/app/.../ui2/chat/` |
| Markdown/kod render kuralları | `android/app/.../Markdown.kt` |
