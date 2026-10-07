# Notlar Planı (S-Pen + Klavye Not Alma & Notlarım Bölümü)

> Tarih: 2026-07-24 · Durum: TAMAMLANDI — Faz N0–N5 tamamlandı
> Kapsam: Cowork çalışma alanlarında not alma (el yazısı + klavye), AI ile
> dönüştürme/özetleme ve merkezdeki "Notlarım" bölümü.
> Kapsam DIŞI: Belgelik (hakimlik-app) özelliklerinin içeri alınması — İPTAL
> edildi (PDF okuma/çizim dahil). Yalnızca Belgelik'in kaynak/türev + LWW
> desenlerinden fikir olarak yararlanılır, kod taşınmaz.

## 0. Karar Özeti (kullanıcıyla kararlaştırıldı — DEĞİŞTİRMEDEN uygula)

| Karar | Seçim | Gerekçe |
|---|---|---|
| Not modeli | **Tek modlu**: not ya kalem ya klavye kaynaklı; karışık blok YOK | İki-sekme (El Yazısı/Yazı) mantığı net kalır |
| Kalem notu | `.ink` = gerçek kaynak, `.md` = ondan üretilen türev | Ajan yalnız `.md` okur; el yazısı düzenlenebilir kalır |
| Moddan geçiş | Kalem → metin **tek yönlü**: "yazıya çevir" sonrası `.md` üzerinde klavyeyle devam, `.ink` arşive düşer | Kullanıcı kararı: "klavye istersem metne çevirir oradan devam ederim" |
| Dosya adları | Ortak kök ad: `<ad>.ink` + `<ad>.md` | Kullanıcıya tek not, diskte iki dosya |
| Bayat takibi | `.md` frontmatter: `source`, `source_hash`, `stale` | Ajan da türev/güncellik bilgisini görür; sidecar dosya yok |
| Elle `.md` düzeltme | Serbest; "yeniden dönüştür" `.md` elle değiştiyse UYARIR (kayıp olabilir) | Kaynak-türev modelinin bilinen tek çatışma noktası |
| Ink yakalama | **Jetpack Ink 1.0.0 (stable)** + `ink-storage` serileştirme | Düşük gecikmeli stylus; format icat etmeye gerek yok |
| El yazısı→metin | **ML Kit Digital Ink Recognition** (cihaz-üstü, ücretsiz, `tr` modeli) | Stroke'tan tanır (görüntüden değil); model çağrısı israfı yok |
| AI eylemleri | Özet/format/düzelt **yalnız elle tetiklenir**, bridge'in claude-app backend'i üzerinden | Otomatik model çağrısı yok (maliyet + istenmeyen dönüşüm) |
| Yerleşim | Proje notu: `<proje>/notlar/` · Bağımsız not: cowork kökünde `_genel-notlar/` | Ajan proje notlarını cwd'den kendiliğinden okur |
| Projeye bağlama | Bağımsız notu projeye bağlama = dosya çiftini `<proje>/notlar/`e TAŞIMA | Bağlama = yer değiştirme; ek metadata yok |
| Senkron | **Hep-online**: not PC'de dosya, tablet bridge üstünden okur/yazar; offline SONRA | Basitlik; mimariyi kilitlemiyor |
| Editör UI | `MarkdownDocumentEditor`'daki Önizleme/Düzenle deseni gibi **El Yazısı / Yazı** segmented sekmeleri | Mevcut UI diline uyum (`SegmentedTabs`) |

Ek varsayımlar (kullanıcıya sorulmadı, makul varsayım — itiraz gelirse değişir):
- `_genel-notlar/` bir cowork PROJESİ DEĞİLDİR; `listProjects`'e girmez,
  yalnız Notlarım bölümü tarar. (Alternatifti: "Genel" adında gerçek proje.)
- Mevcut şablonun tohumladığı `<proje>/notlar.md` dosyasına DOKUNULMAZ
  (ajan talimatları ona referans veriyor); yeni not sistemi ayrı `notlar/`
  klasöründe yaşar. Şablona `notlar/` klasörü tohumlama eklenebilir.
- Ink çizimi paleti UI anayasasına uyar (palet dışı renk yok; vurgu marka
  menekşesi — bkz. `docs/ui-anayasasi.md`).

## 1. Dosya Formatları

### `.ink` (kaynak — kalem notu)
JSON zarf; stroke verisi `ink-storage` serileştirmesiyle:

```json
{
  "schema": 1,
  "title": "Duruşma notu",
  "created_at_ms": 0,
  "updated_at_ms": 0,
  "pages": [
    { "height_px": 2400, "strokes_b64": "<ink-storage serileştirilmiş>" }
  ]
}
```

- `updated_at_ms`: kaydetmede güncellenir; bridge LWW'ye şimdilik GEREK YOK
  (tek yazar: tablet). Alan yine de yazılır ki ileride senkron eklenebilsin.
- Sayfa/tuval modeli: v1'de dikeyde büyüyen tek sayfa yeterli; `pages` dizisi
  ileriye dönük.

### `.md` (türev — veya klavye notunun kendisi)
```markdown
---
title: Duruşma notu
source: durusma-notu.ink        # yalnız kalem-kaynaklı notta
source_hash: sha256:...         # dönüştürme anındaki .ink içerik hash'i
stale: false                    # .ink değişti ama yeniden dönüştürülmedi
converted_at: 2026-07-23T10:00:00+03:00
converted_body_hash: sha256:... # son tanıma çıktısının gövde hash'i
---
(metin)
```
- Klavye-kaynaklı notta frontmatter'da `source*`/`stale` alanları YOKTUR.
- `converted_body_hash`, yeniden dönüştürmeden önce `.md` gövdesindeki elle
  yapılan değişiklikleri saptamak için kullanılır.
- "Yazıya çevir" (tek yönlü geçiş) yapılınca: `source*`/`stale` alanları
  SİLİNİR, `.ink` olduğu yerde arşiv olarak kalır (salt-okunur rozetle
  gösterilir), not artık klavye notudur.

## 2. Fazlar

### Faz N0 — Bridge: not uçları + Genel alanı — TAMAMLANDI (2026-07-23)
Dosya içeriği okuma/yazma/taşıma/silme için **mevcut** cowork-scope'lu genel
rotalar kullanılır (`bridge/routes/general.mjs`: `/file`, `/savefile`,
`/move`, `/delete`, `/rename`; `bridge/server.mjs`: `/cowork/savefile`).
Yeni eklenecekler (`bridge/cowork.mjs` + `server.mjs`):

1. `GET /cowork/notes` — tüm projelerin `notlar/` klasörleri + `_genel-notlar/`
   birleşik listesi. Öğe: `{id, title, kind: 'ink'|'text', project|null,
   mdPath, inkPath|null, stale, updated_at_ms}`. (`.ink`+`.md` çifti TEK öğe.)
2. `POST /cowork/note` — `{name, project|null, kind}` → boş not dosya(ları)
   oluşturur; ad çakışmasında `-2` eki (slugify: Türkçe karakter → ASCII,
   mevcut dosya adı kurallarına uy).
3. `POST /cowork/note/attach` — `{noteId, projectPath}` → dosya çiftini
   `<proje>/notlar/`e taşır (tek dosyalık `/move`'un çift-dosya sarmalayıcısı).
4. `createProject` şablonuna `notlar/` klasörü tohumlama.

Test: `bridge/test/cowork-notes.test.mjs` (listeleme, çift-dosya bütünlüğü,
attach'in kök dışına taşıma reddi — mevcut `cowork-file-routes` deseninde).
**Kabul:** curl ile not oluştur/listele/bağla çalışır; `node --test` yeşil.

Uygulama notları:
- Birleşik liste, bağımsız/proje notu oluşturma, çift dosyayı projeye taşıma
  ve proje şablonunda `notlar/` klasörü tamamlandı. Cowork kökü dışına çıkan
  proje ve dosya yolları reddediliyor; `_genel-notlar/` proje listesine
  karışmıyor.
- N4 ile eklenen yeniden adlandırma/silme uçları da aynı çift-dosya
  bütünlüğünü kullanıyor. 2026-07-23 bridge restartı sonrasında canlı
  oluştur → yeniden adlandır → listele → sil zinciri doğrulandı.

### Faz N1 — Android: klavye notu (ince dilim, uçtan uca) — TAMAMLANDI (2026-07-23)
`:shared`'a istemci fonksiyonları (`coworkNotes`, `coworkCreateNote`,
`coworkAttachNote` — mevcut `BridgeClientBackend` cowork deseninde). App'te:

1. `NoteEditorScreen`: klavye notu için mevcut `MarkdownDocumentEditor`
   (Önizleme/Düzenle) yeniden kullanılır; debounce'lu otomatik kaydet +
   çıkışta kaydet.
2. Giriş noktaları: proje ekranından "not ekle" (projeye bağlı) ve geçici
   olarak basit bir listeden açma (N4'te Notlarım'a taşınır).

**Kabul:** Tablette klavyeyle not yazılır, PC'de `<proje>/notlar/<ad>.md`
oluşur, aynı projedeki ajan oturumu nota soru sorulunca içeriği okur.

Uygulama notları:
- `:shared` istemcisine `coworkNotes`, `coworkCreateNote` ve
  `coworkAttachNote` sözleşmeleri/model çözümlemesi eklendi.
- Proje ayrıntısına **Notlar** ve **Not ekle** girişleri; N4'e kadar yaşayacak
  basit proje-notları listesi ve `NoteEditorScreen` eklendi.
- Mevcut `MarkdownDocumentEditor` not modunda yeniden kullanılıyor. 900 ms
  debounce'lu otomatik kayıt, çıkışta son taslağı kayıt ve eşzamanlı
  kayıtları sıraya koyan son-yazan-kazanır koruması var. Cowork notu
  `/cowork/savefile` üzerinden kapsam-kısıtlı yazılıyor.
- Doğrulama: `:shared:test` + `:app:testDebugUnitTest` yeşil; debug APK
  derlendi. Canlı bridge'de oluştur → kaydet → oku zincirinde içerik ve
  `<proje>/notlar/` yerleşimi doğrulandı. OTA 11.20 (versionCode 335)
  yayımlandı.
- Galaxy Tab S10+ üzerinde 11.20 kurularak gerçek uçtan uca kabul tamamlandı:
  proje ekranından not oluşturuldu, klavye metni 900 ms otomatik kayıtla ve
  başlık geri tuşuyla anında çıkışta ayrı ayrı PC'deki `.md` dosyasında
  doğrulandı. Test notu doğrulama sonrası silindi; uygulama logunda çökme yok.

### Faz N2 — Android: S-Pen ink editörü — TAMAMLANDI (2026-07-23)
1. Bağımlılıklar: `androidx.ink:ink-authoring-compose`, `ink-strokes`,
   `ink-storage`, `ink-rendering` (1.0.0).
2. `NoteEditorScreen`'e **El Yazısı** sekmesi: `InProgressStrokes` ile düşük
   gecikmeli çizim; kalem/silgi/geri-al; palet UI anayasasından.
3. Kaydet: stroke'lar `ink-storage` ile serileştirilip `.ink` zarfına;
   parmak = kaydırma, stylus = çizim (pointer type ayrımı).
4. Kalem notunda **Yazı** sekmesi henüz dönüştürme yapılmadıysa boş durum
   gösterir ("Henüz yazıya dökülmedi — Dönüştür").

**Kabul:** S-Pen ile yazılan not kapat-aç sonrası aynen geri gelir; parmakla
kaydırma çizim bırakmaz; `.ink` PC'de proje klasöründe durur.

Uygulama notları:
- Jetpack Ink 1.0.0'ın Compose modülleri projenin o zamanki sabit Kotlin
  1.9.24/Compose 1.6 hattını zorla Kotlin 2.x'e yükselttiği için Ink çekirdeği
  ayrı Java-only `:ink` Android kütüphane modülünde yalıtıldı. **Bu kısıt
  16.08.2026'da kalktı** (proje Kotlin 2.3.20'ye çıktı); `:ink`'i Compose
  modülleriyle sadeleştirmek artık mümkün, ama gerekmedikçe dokunulmadı. Düşük gecikmeli
  `InProgressStrokesView`, `CanvasStrokeRenderer`, `ink-strokes` ve
  `ink-storage` kullanılıyor; uygulamanın mevcut Compose hattı korunuyor.
- Kalem olayları kök Ink görünümünde yakalanıp düşük gecikmeli katmana
  aktarılıyor. Stylus sırasında üst kaydırma kapsayıcısının olayı kesmesi
  engelleniyor; parmak olayları tüketilmeyerek `verticalScroll`a bırakılıyor.
- Kalem/silgi/geri al, kâğıt-siyahı + marka menekşesi paleti, **El Yazısı /
  Yazı** sekmeleri ve N3'e hazırlanan "Henüz yazıya dökülmedi — Dönüştür" boş
  durumu eklendi. Stroke değişiklikleri 850 ms debounce ile PC'ye yazılıyor;
  çıkışta son zarf ayrıca kaydediliyor.
- JSON `null` yol alanlarının Android'de `"null"` dosya adına dönüşmesi
  düzeltildi; `.ink` yolu artık doğru seçiliyor. Zarf `schema`, başlık ve
  oluşturulma zamanını koruyup tek 2400 px sayfada ink-storage tabanlı stroke
  yükünü `strokes_b64` olarak saklıyor.
- Doğrulama: app 119 test (0 hata, 1 atlanan), shared 74 test (0 hata) ve
  `cowork-notes` 13/13 yeşil; debug APK derlendi. Galaxy Tab S10+ üzerinde
  OTA 11.23 (versionCode 338) kuruldu. Android'in gerçek `stylus` kaynağıyla
  vuruş çizildi, PC'deki `.ink` zarfı 150 bayttan 656 bayta çıktı ve kapat-aç
  sonrası çizgi geri geldi. Parmakla kaydırmada dosya hash'i
  ve mtime değişmedi; geri al ve silgi stroke sayısını 1'den 0'a indirdi.
  Kabul için oluşturulan geçici not test sonunda silindi; uygulama logunda
  çökme yok.

### Faz N3 — Dönüştürme: ML Kit + bayat yönetimi + tek yönlü geçiş — TAMAMLANDI (2026-07-23)
1. ML Kit Digital Ink Recognition (`tr` modeli; ilk kullanımda model indirme
   akışı — indirme durumu UI'da gösterilir). Stroke → satır satır metin.
2. "Dönüştür/Yeniden dönüştür": `.md` üretir, frontmatter (`source`,
   `source_hash`, `stale:false`) yazar. `.ink` her kaydedildiğinde app
   `.md`'nin `stale:true` bayrağını günceller.
3. `.md` elle değiştiyse yeniden dönüştürmede UYARI ("el düzenlemeleri
   kaybolacak"). Tespit: son dönüştürme çıktısının hash'i frontmatter'a
   yazılır, mevcut gövdeyle karşılaştırılır.
4. "Yazıya çevir ve devam et" (tek yönlü geçiş): §1'deki alan temizliği;
   `.ink` salt-okunur arşiv rozetiyle görünür kalır.

**Kabul:** El yazısı Türkçe metne dönüşür; ink değişince Yazı sekmesinde
"bayat" rozeti; geçiş sonrası klavyeyle devam edilir ve bir daha ink moduna
dönülmez.

Uygulama notları:
- Java-only `:ink` sınırına ML Kit Digital Ink Recognition 19.0.0 eklendi.
  Jetpack Ink vuruş noktaları zaman bilgileri korunarak ML Kit `Ink` modeline
  çevriliyor; `tr` modeli cihazda yoksa dinamik indirme ve tanıma durumları
  Yazı sekmesinde gösteriliyor. Tanıma cihaz üzerinde çalışıyor.
- Dönüştürme aynı kök adlı `.md` dosyasını oluşturuyor; `source`,
  `source_hash`, `stale`, `converted_at` ve `converted_body_hash`
  frontmatter alanlarını yazıyor. Sonraki her `.ink` kaydı türevi
  `stale: true` yapıyor ve Yazı sekmesinde **El yazısı değişti** rozeti
  gösteriliyor.
- Türevin gövdesi son tanıma hash'inden farklıysa **Elle düzenlendi** rozeti
  çıkıyor. Yeniden dönüştürme, kullanıcı açıkça **Üzerine yaz** demeden
  metni değiştirmiyor.
- **Yazıya çevir ve devam et** onayından sonra kaynak/türev alanları
  frontmatter'dan siliniyor, editör klavye modundaki `.md` dosyasını açıyor
  ve not listesi **Klavye notu · El yazısı arşivde** durumunu gösteriyor.
  Türevli Ink notu kapatılıp yeniden açıldığında `.md` yerine `.ink`
  editörünün seçilmesi ayrıca regresyon testiyle korunuyor.
- Doğrulama: app 124 test (0 hata, 1 atlanan), shared 74 test (0 hata) ve
  `cowork-notes` 13/13 yeşil; debug APK derlendi. Galaxy Tab S10+ üzerinde
  S-Pen kaynağıyla yazılan `ALI` cihaz-üstü Türkçe model tarafından `ALI`
  olarak tanındı. Ek vuruş sonrası diskte `stale: true` ve UI'da
  **El yazısı değişti** görüldü; elle değiştirilen gövde yeniden dönüştürme
  uyarısını tetikledi. Tek yönlü geçişten sonra yalnız klavye editörü açıldı,
  `.ink` arşivi yerinde kaldı ve klavye kaydı PC'deki `.md` dosyasına ulaştı.
  Kabul dosyaları test sonunda silindi; uygulama logunda çökme yok.
- OTA 11.24 (versionCode 339) yayımlandı ve aynı APK Galaxy Tab S10+'a
  kuruldu. Canlı manifest ile APK uçları bridge yeniden başlatılmadan HTTP
  200 döndü.

### Faz N4 — Merkez: "Notlarım" bölümü — TAMAMLANDI (2026-07-23)
1. `HubRootScreen`'e yeni bölüm kartı **Notlarım** (Projeler/Operasyonlar/
   Kullanım/Dosyalar yanına), `HubNotesScreen`: birleşik liste (`GET
   /cowork/notes`), proje etiketi/filtre, bayat rozeti, kind ikonu.
2. Merkezden "+ yeni not" → `_genel-notlar/`e bağımsız not; karttan
   "projeye bağla" → attach ucu.
3. Sil / yeniden adlandır: çift dosyayı birlikte işler.
4. Dosyalar bölümü `.ink` uzantısını not editörüyle açar (mevcut opener
   eşlemesine ekle); `notlar/` içeriği Dosyalar'da da görünür — bu KABUL
   edilir (aynı dosyalar, iki görünüm).

**Kabul:** Merkezden bağımsız not oluşur, sonradan projeye bağlanınca dosyalar
proje `notlar/`ine taşınır ve ajan okur; Notlarım tüm notları tek listede gösterir.

Uygulama notları:
- Merkez genel görünümüne **Notlarım** satırı eklendi; toplam not ve bayat
  türev sayısını gösteriyor. Birleşik ekran `GET /cowork/notes` sonucundaki
  genel + proje notlarını **Tümü / Genel / Projeli** filtreleriyle listeliyor.
  Her satırda proje/Genel etiketi, klavye/el yazısı türü, Ink arşiv durumu ve
  gerekiyorsa **Bayat** rozeti var.
- Merkezdeki `+` ile `_genel-notlar/` altında bağımsız Ink veya klavye notu
  oluşturuluyor. Genel notun işlem menüsündeki **Projeye bağla**, mevcut
  `POST /cowork/note/attach` sözleşmesiyle `.md + .ink` çiftini seçilen
  çalışma alanının `notlar/` klasörüne taşıyor.
- `POST /cowork/note/rename` ve `/cowork/note/delete` uçları eklendi.
  Yeniden adlandırma başlığı, ortak dosya kökünü, Ink zarfını ve türev
  frontmatter `source` alanını birlikte güncelliyor; çakışmada `-2` eki
  kullanıyor. Silme iki dosyayı önce geçici adlara alarak yarım not bırakma
  riskini azaltıyor. Cowork içindeki sıradan dosyalar noteId ile işleme
  sokulamıyor.
- Cowork **Dosyalar** gezgininde `.ink` dokunuşu artık harici uygulamaya
  gitmiyor; aynı `NoteEditorScreen` Ink editörünü açıyor. `notlar/` klasörü
  Dosyalar görünümünde ayrıca görünmeye devam ediyor.
- Doğrulama: app 124 test (0 hata, 1 atlanan), shared 75 test (0 hata) ve
  `cowork-notes` 17/17 yeşil; debug APK derlendi. Galaxy Tab S10+ üzerinde
  Merkezden bağımsız Ink notu oluşturuldu, S-Pen vuruşu PC'deki
  `_genel-notlar/<ad>.ink` dosyasına yazıldı, **Projeye bağla** sonrasında
  kaynak genel alandan kaybolup `<proje>/notlar/` altına taşındı.
  Genel/Projeli filtreleri ve Dosyalar > `<proje>` > notlar yolundan aynı
  `.ink` dosyasının Ink editöründe açılması doğrulandı. Kabul dosyası test
  sonunda silindi; uygulama logunda çökme yok.
- OTA 11.25 (versionCode 340) yayımlandı. Canlı bridge kullanıcıya ait
  süreç olduğu için yeniden başlatılmadı; yeni rename/delete uçları
  kullanıcının sonraki bridge yeniden başlatmasında etkinleşecek.

### Faz N5 — AI eylemleri (elle tetiklenen) — TAMAMLANDI (2026-07-24)
1. Bridge'e `POST /cowork/note/ai` — `{noteId, action: 'ozetle'|'formatla'|
   'duzelt', ...}`; mevcut claude-app backend'iyle tek atımlık istek (kalıcı
   oturum açmadan; ayrıntı implementasyonda netleşir).
2. UI: Yazı sekmesinde eylem menüsü; sonuç diff/önizleme ile onaya sunulur,
   onaylanınca `.md`'ye yazılır (sessizce üzerine yazma YOK).

**Kabul:** "Özetle" notun sonuna/ayrı bölüme özet ekler; kullanıcı onayı
olmadan içerik değişmez.

Uygulama notları:
- `POST /cowork/note/ai`, Claude App üzerinde `plan` izin modunda geçici,
  tek-atımlık bir oturum açıyor; sonucu yalnız önizleme olarak döndürüyor ve
  oturumu her durumda durdurup disk kaydını siliyor. Not içeriği komut değil
  veri olarak sınırlandırılıyor, beklenmeyen araç izni reddediliyor ve
  gövde/yanıt boyutu sınırlanıyor.
- Kayıt ayrı `POST /cowork/note/ai/apply` onayıyla yapılıyor. Önizleme
  sırasındaki dosya SHA-256 değeri değişmişse HTTP 409 ile reddediliyor;
  frontmatter korunuyor. **Özetle** mevcut gövdeyi koruyup sonuna
  `## AI Özeti` bölümü ekliyor.
- Android istemcisinde bu uzun çağrı için 210 saniyelik ayrı istemci
  kullanılıyor; normal köprü zaman aşımı gevşetilmedi. Yazı sekmesindeki
  **AI işlemleri** menüsü Özetle/Formatla/Düzelt eylemlerini sunuyor.
  Mevcut ve önerilen metin iki ayrı panelde gösteriliyor; yalnız
  **Onayla ve kaydet** dosyayı değiştiriyor. Kaydedilmemiş klavye taslağı
  varsa AI çağrısından önce otomatik kayıt tamamlanıyor.
- Doğrulama: app 124 test (0 hata, 1 atlanan), shared 76 test (0 hata),
  `cowork-notes` 21/21 ve ilgili geniş bridge seçkisi 93/93 yeşil; debug APK
  derlendi. OTA 11.26 (versionCode 341) yayımlandı ve Galaxy Tab S10+'a
  kuruldu; eylem menüsü ve üç seçenek gerçek cihazda görüldü.
- Restart sonrası canlı Claude App kabulünde Özetle önizlemesi 9,3 saniyede
  üretildi ve dosya hash'i değişmedi. Onaydan sonra özet diske yazıldı;
  aynı eski önizlemenin yeniden uygulanması HTTP 409 ile reddedildi. Kabul
  notu test sonunda silindi.

### Sonraya bırakılanlar (bilinçli)
- Offline not alma + senkron kuyruğu (karar: şimdilik hep-online).
- Desktop istemcide not görüntüleme/düzenleme (desktop planı Faz 3 sürüyor;
  `.md` görüntüleme ucuz, ink çizimi desktop'ta ayrı iş).
- Notlarda arama (Hub Arama'ya entegre), el yazısı içinde arama.
- Çoklu sayfa/tuval, PDF'e aktarma.

## 3. Riskler / Tuzaklar

- **ML Kit `tr` modeli ilk kullanımda internetten iner** — tablet Tailscale
  dışı gerçek internete de çıkabilmeli; indirme başarısızsa dönüştürme
  düğmesi açıklayıcı hata göstermeli.
- **El yazısı tanıma kalitesi** hukuk terimlerinde/karalamada düşebilir —
  bu yüzden ham `.ink` hep saklanıyor; "düzelt" AI eylemi (N5) tanıma
  hatalarını toparlamak için de kullanılır.
- **Ad çakışması/slugify**: Türkçe karakterli başlık → dosya adı dönüşümü
  tek yerde (bridge) yapılmalı; app tarafı ad üretmez.
- **`.ink`+`.md` çift bütünlüğü**: taşıma/silme/yeniden adlandırma HER ZAMAN
  çift üzerinden (N0'daki sarmalayıcı uçlar); tekil `/move` ile yarım
  taşınmış not oluşmamalı.
- **APK dağıtımı**: app tarafı her fazda yeni APK ister (bridge kod
  değişikliği de tam node restart ister — bilinen kural).
- **Jetpack Ink 1.0.0** stable ama yeni; Compose modülleri mevcut proje
  hattıyla sürüm çakışmasına girdiği için N2'de Java-only `:ink` modülü ve
  `InProgressStrokesView` uyarlaması kullanıldı. Bu sınır korunmalı; Ink
  bağımlılıkları doğrudan `:app`e taşınmamalı.

## 4. Sıra ve Bağımlılıklar

N0 → N1 → N2 → N3 → N4 → N5. N0–N5 tamamlandı. N1 sonunda sistem uçtan
uca çalışır (klavye notu + ajan context'i); S-Pen değeri N2–N3'te gelir;
N4–N5 merkez ve kontrollü AI katmanını tamamlar. Her faz
kendi başına commit'lenebilir ve APK'sız fazlar (N0) bridge restart'ıyla
canlıya alınır.
