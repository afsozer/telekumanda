# UI Anayasası — v2, liquid glass (16.08.2026)

> Tek yetkili tasarım kararı kaynağı — **ui3 paketi için**. ui2 hâlâ v1'e tabi
> ve v1 geçerliliğini koruyor; ui3 varsayılan olana kadar iki belge yan yana
> yaşar. `ui3/` altına kod yazan herkes ÖNCE bunu okur.
>
> v1'den devralınanlar aynen geçerlidir ve burada tekrar edilmez: navigasyon
> modeli (bölüm 1), yüzey desenleri (2), capability-driven backend davranışı
> (3), terminoloji (4.5), durum iletişimi (6), değişmez ilkeler (7).
>
> Bu belge v1'in **yalnız tasarım dilini** (bölüm 4) değiştirir ve üç yeni bölüm
> ekler: malzeme, konşentrik geometri, hareket bütçesi.
>
> Kaynak tasarım: `docs/mockups/ui5-liquid-glass.html`.
> Uygulama planı: `docs/ui3-liquid-glass-plani.md`.

---

## Neden v2 var

v1'in 4.2 maddesi şunu söylüyordu: *"kartlar gölgeyle değil ton farkı + ince
kenarla ayrılır"*. Bu bilinçli bir karardı ve ui2 boyunca doğru çalıştı: düz,
sessiz, okunaklı bir arayüz.

ui3 bu kuralı **bilerek** bırakıyor. Kartlar artık ışığı kıran cam yüzeyler; ton
farkı yerine derinlik, kenar yerine kırılma var. Bu, v1'in yanlış olduğu anlamına
gelmez — kapsamı değişti. O yüzden v1 yamalanmadı, v2 yazıldı.

Değişmeyen şey şu: **anlam renkten önce gelir.** Cam güzel görünsün diye hiçbir
durum rengi anlamını kaybetmez.

---

## 1. Malzeme — cam

### 1.1 Tanım

Tek bir cam bileşeni vardır (`GlassSurface`). Camın dört katmanı:

1. **Bulanıklık** — arkasındaki içeriği bulanıklaştırır (blur ~26, doygunluk
   ~%175). Compose'da bunun hazır API'si yok; Haze kütüphanesi kullanılır.
2. **Kenar kırılması (rim)** — kenarda 135°'de parlayan ince gradyan hat.
   Düz bir `border` değil: camın kenarının arkadaki ışığı tutması.
3. **Spekular çizgi** — üst kenarda tek piksellik açık hat.
4. **Gren** — çok ince gürültü katmanı. Düz gradyanların "ucuz plastik" gibi
   parlamasını engelleyen şey budur; atlanırsa cam sahte durur.

### 1.2 Cam NEREDE kullanılır

- Sabit kabuk: dock, composer adası, üst başlık şeridi, ada (island).
- Sheet ve Spotlight paneli.
- Tur akışındaki **onay kartı** (tek istisna — dikkat çekmesi gereken tek kart).

### 1.3 Cam NEREDE kullanılmaz — bu bölüm kural, tercih değil

- **Kaydırılan liste öğelerinde.** Her mesaj kartı cam olursa her kaydırma
  karesinde blur yeniden hesaplanır. Liste kartları ton farkı + ince kenarla
  ayrılır (yani v1'in kuralı listelerde aynen sürer).

> **Çelişki çözümü (17.08.2026).** §1.4 kullanıcı balonunu ve canlı araç çipini
> "tonlu cam" sayıyor, ama ikisi de kaydırılan akışın içinde ve bu madde orada
> camı yasaklıyor. Ayrım şöyle kesinleşti:
>
> - **Cam** = blur + ton + rim + spekular + gren. Yalnız sabit kabuk, sheet,
>   Spotlight ve onay kartı. Sayısı ekranda birkaç taneyi geçmez.
> - **Cam görünümü** = ton + rim + spekular + gren, **blur YOK**. Kaydırılan
>   akıştaki kullanıcı balonu ve araç çipi bunu kullanır.
>
> Görsel kimlik korunur, kaydırma maliyeti doğmaz. Kod karşılığı: `GlassSurface`
> (blur'lu) ve `GlassLikeSurface` (blur'suz) — ikisi aynı ton/rim/gren
> sabitlerini paylaşır ki iki ayrı dil doğmasın.
- **Metin okunan yüzeylerde**: PDF/DOCX/Ink/Markdown editörlerinin içerik
  alanı, uzun ayar listeleri. Cam yalnız onları taşıyan kabuğa uygulanır.
- **Metnin doğrudan üstünde ikinci bir cam katman olarak.** Cam üstüne cam
  yığılmaz; derinlik iki katmanla biter.

### 1.4 Zemin

Zemin canlı ve renkli (mesh lekeleri), camlar nötr. Renk camdan süzülerek
gelir; camın kendisi renklendirilmez.

> Ton seti 17.08.2026'da değişti: neon menekşe/cyan/magenta yerine çelik
> mavi / petrol / bronz (profesyonel ton, kullanıcı isteği). İlke aynı —
> zemin canlı, cam nötr; yalnız lekelerin renkleri `Ui3Palet`'ten okunur.

Tonlanmış cam (vurgu / amber / cyan) **yalnız anlam taşıyan yerde** kullanılır:
kullanıcı balonu, onay kartı, canlı araç çipi. Süs olarak tonlu cam yoktur.

---

## 2. Renk

Durum renkleri v1'deki **anlamını korur**, tonları ui3 paletine taşınır:

| rol | ui3 | anlam (v1'den değişmedi) |
|---|---|---|
| `running` | cyan | tur sürüyor |
| `attention` | kehribar | onay/girdi bekliyor |
| `done` | mint | bitti-görülmedi / başarı |
| `danger` | gül | hata / yıkıcı aksiyon / bağlantı kopuk |

- Zemin `#060609`; mürekkep üç kademe (birincil / ikincil / üçüncül).
- Tek gradyan (vurgu→petrol; eskiden menekşe→cyan) yalnız **birincil aksiyon
  ve canlılık** için: gönder tuşu, dock lensi, akan rayın ucu. Dekoratif
  gradyan yoktur.
- Sağlayıcı kimliği hâlâ renkle değil monogramla gösterilir (v1 4.2 devam).
- Dinamik renk (Material You) KAPALI — v1'deki gerekçe aynen geçerli.

---

## 3. Geometri — konşentrik yarıçap

iOS 26 kuralı: **iç yarıçap = dış yarıçap − padding.** İç içe geçmiş iki
yuvarlatılmış dikdörtgenin kenarları paralel kalır; aksi halde göz "yamuk"
görür ama nedenini söyleyemez.

Yarıçap ailesi: **12 / 18 / 21 / 26 / 33 / 38**. Ara değer yasak.

Örnek (composer): dış kapsül 33, iç dolgu 6 → **iç alan 27**.

> Düzeltme (16.08.2026): burada önce mockup'tan devralınan "iç alan 21" örneği
> vardı ve kuralın kendisiyle çelişiyordu (33 − 6 = 27, 21 değil). Mockup'ın
> `.ifield` değeri tasarımcı tercihi; kural doğru olan. Çelişkide **anayasa
> kazanır** — mockup referanstır, kaynak değil. `concentric()` bu çelişki
> yüzünden bir tur yanlış yazıldı.

Boşluk ölçeği v1'den devam: **4 / 8 / 12 / 16 / 20 / 28**.

---

## 4. Tipografi

- Sistem fontu (SF Pro / Roboto ailesi — cihaz neyse o).
- Büyük başlık 34sp, tracking −1.2px, ağırlık 800. Başlık aynı zamanda
  **oturum seçicidir** (▾); ayrı bir sekme çubuğu yoktur.
- Bento sayıları 42sp, tracking −1.8px.
- Etiketler 10–11sp, ağırlık 800, harf aralığı geniş, BÜYÜK HARF.
- **Kod, yol, komut, log HER ZAMAN mono + yüzey kutusunda** (v1 4.3 aynen).
- Liste satırı min 56dp, dokunma hedefi min 48dp — ölçülür, göz kararı değil.

---

## 5. Hareket bütçesi

Hareket bu belgede bir **bütçe**dir, bir efekt değil. Ekran açıkken sürekli
dönen animasyon, pil ve ısı demektir; bu uygulamanın geri kalanı (ekran kapalı
long-poll durdurma) o özenle yazıldı.

**Sürekli animasyona hakkı olan tek şey: çalışan bir tur.**

- Ray akışı ve nabız — yalnız tur çalışırken; tur bitince **durur**.
- Ada ekolayzeri — yalnız tur çalışırken.
- Mesh zemin — normalde **durgun**. Ekran/sheet geçişinde kısa (~600ms)
  canlanır, sonra durur. `infiniteTransition` ile sürekli dönen mesh yasak.
- Geçişler spring (yay) fiziğiyle; doğrusal değil.

Ölçülebilir kural: **ekranda hiçbir tur çalışmıyorken kare üretimi durmalı.**
`dumpsys gfxinfo` iki ölçüm arasında toplam kare sayısını artırmıyorsa kural
sağlanmıştır.

---

## 6. Gezinme — v1'in mimarisi korunur

> **Geri alma (17.08.2026).** Bu bölüm önce mockup'ı izliyordu: etiketsiz dock,
> Oturumlar sekmesi yok, yerine başlıktaki ▾ seçici ve Spotlight. Kullanıcı ui3'ü
> gerçek işte deneyince yargı net çıktı: *"tuş yerleşimleri, oturum listesine ve
> yeni oturum açmaya erişim, sekmeler — ui2 miles ahead."*
>
> Ders: mockup **görünüşü** tasarladı, **kullanımı** değil. Doğrulama altyapısı da
> bu boşluğu görmedi — piksel, kontrast, taşma, kare üretimi ölçüldü; "oturumuma
> tek dokunuşla ulaşabiliyor muyum" hiç sorulmadı. Ölçülebilir olan ölçüldü,
> önemli olan atlandı.
>
> Karar: **bilgi mimarisi v1'den (ui2) devralınır, değiştirilmez.** ui3'ün katkısı
> malzemedir. Aşağıdaki maddeler mockup'ı değil ui2'yi izler.

- Dock 5 ikon, **etiketli**: Oturumlar / Sohbet / Operasyon / Merkez / Ayarlar —
  ui2'deki sıra ve davranışın aynısı. Aktif ikonun altında vurgu→petrol lens.
  Etiketsizlik mockup'ın tercihiydi; ikonun ne yaptığını hatırlamak zorunda
  bırakıyor.
- **Oturumlar birinci sınıf yüzey**: dock'tan tek dokunuşla açılır. Başlığın
  içine gizlenmez.
- **Sekme çubuğu** sohbetin üstünde durur; davranışı ui2'nin `SessionTabBar`'ı
  ile birebir (kaydırma, durum noktası, kapatma onayı, uzun basış menüsü) —
  yalnız yüzeyi cam. Yeni oturum "+" çipi görünür yerdedir.
- **Büyük başlık yalnız sohbet DIŞI ekranlarda** (Merkez, Dosyalar, Operasyon,
  Ayarlar). Sohbette dikey yer okuma alanıdır: sekme çubuğu + ince durum satırı
  yeter, 34sp başlık orayı yer. Başlık artık seçici DEĞİL.
- **Spotlight askıya alındı.** Komut paleti masaüstü alışkanlığı; telefonda
  kalıcı sekme çubuğunun yerini tutmuyor. Faz 5 iptal; gerekirse ileride
  sekme çubuğunun YANINA eklenir, yerine değil.
- Sheet üstüne sheet açılmaz (v1 kuralı aynen sürer).

---

## 7. Bileşen kütüphanesi (ui3)

Çekirdek: `GlassSurface`, `MeshBackground`, `concentric()`, `Ui3Tokens`,
`Ui3Colors`, `Ui3Type`, `GlassDock`, `BigTitle`, `AppIsland`, `GlassSheet`,
`Spotlight`, `GlassCard`, `ListRow` (ui3 sürümü), `StatusDot`, `ProviderMark`.

Kural v1'den aynen: yeni ekran önce bu setten kurulur; sette olmayan bir bileşen
gerekiyorsa **önce sete eklenir**, ekran içinde adhoc tanımlanmaz.

Ekranların içinde ham `Color(0x...)`, ham `dp` ya da ham `RoundedCornerShape`
yazılmaz — hepsi token'dan gelir. Token yoksa token eklenir.

---

## 8. Doğrulama

Bu arayüzü uygulayan ajanların görme yeteneği yok. "Güzel oldu" bir kabul
kriteri değildir. Her kural ölçülebilir karşılığıyla birlikte doğrulanır
(`scripts/ui-olc.mjs`):

| kural | ölçüm |
|---|---|
| cam gerçekten bulanık | **aynı** mesh özelliği camın içinde ve dışında ölçülür; içeride sönümlenmiş hâlde görünmeli |
| metin okunuyor | kontrast ≥ 4.5:1 |
| dokunma hedefi | ≥ 48dp |
| taşma/çakışma yok | `ui-olc duzen` bulgusuz |
| hareket bütçesi | boşta `gfxinfo` kare sayısı artmıyor |

Estetik karar kullanıcınındır; ajanın işi kanıt üretmektir.

**Blur kapısında iki tuzak** (16.08.2026'da canlıda yaşandı, ikisi de ölçümü
sessizce geçersiz kılıyor):

1. **Boş arka plan.** Arkasında mesh lekesi olmayan bir cam bölgesi düz zemin
   rengi ve sıfır keskinlik ölçer — camın hiçbir şey çizmediği durumla
   ayırt edilemez. Ölçüm bölgesi, arkasında **gerçekten bir şey olan** yerden
   seçilir; kıyas "cam vs keskin referans" değil, aynı özelliğin
   "cam içi vs cam dışı" hâlidir.
2. **Gren keskinliği şişirir.** Gren yüksek frekanslı gürültüdür ve Laplacian
   varyansı onu detaydan ayıramaz. Ölçülen: bulanık cam içi 18.5, bulanık
   olmayan çıplak mesh 2.3 — yani blur'lu bölge 8 kat "daha keskin" çıktı.
   Keskinlik tek başına blur kapısı olarak KULLANILAMAZ; renk/sönümlenme
   kıyasıyla birlikte okunur.
3. **Yumuşak zemin blur'u kanıtlanamaz kılar.** Camın arkasında yalnız
   yumuşak mesh gradyanı varsa "bulanıklaştırılmış" ile "sadece yarı saydam"
   birbirinden ayırt EDİLEMEZ — ne gözle ne ölçümle, çünkü bulanıklaşacak
   keskin bir detay yok. Blur'un kendisini kanıtlamak için camın arkasında
   **keskin bir şey** (metin, kenar, ızgara) bulunmalı. Faz 1 önizlemesi bunu
   sağlayamaz; asıl sınav Faz 2'de dock/composer'ın arkasından sohbet metni
   geçtiğinde verilir. O ölçüm yapılana kadar kanıtlanmış olan şey şudur:
   **cam arka planı örnekliyor** — blur'un yarıçapı değil.

---

## 9. v1'e geri dönüş

ui3 varsayılan olana kadar ui2 çalışır durumda kalır ve v1'e tabidir. ui3
kapatılabilir bir ön izlemedir (Ayarlar → Görünüm → "Yeni arayüz"). Bu anahtar
kaldırıldığı gün v1 arşive alınır, o güne kadar **silinmez**.
