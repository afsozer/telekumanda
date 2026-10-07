# UI Anayasası — v1 (12.07.2026)

> Tek yetkili tasarım kararı kaynağı. Yeni bir UI fikri geldiğinde ÖNCE bu
> belgeye bakılır: uyuyorsa uygulanır; uymuyorsa ya fikir uyarlanır ya da bu
> belge BİLİNÇLİ bir kararla güncellenir (commit'te gerekçe yazılır). Ekran
> tek başına yamalanmaz — mevcut UI'nin karmaşasının kök nedeni buydu.
> Envanter kararları: ui-envanter.md (bölüm 8, karara bağlandı).

## 1. Navigasyon modeli

- **3 kök alan**, alt navigasyon çubuğu: **Sohbet · Merkez · Ayarlar**.
- Uygulama Sohbet köküne açılır. Landing kavramı yok.
- Navigation Compose ile gerçek back-stack; sistem geri tuşu her derinlikte
  öngörülebilir çalışır.
- Kök alanlar:
  - **Sohbet:** oturum sekmeleri + aktif sohbet. Oturum çekmecesi buradan açılır.
  - **Merkez:** Projeler (cowork çalışma alanları dahil — tek liste),
    Operasyonlar, Kullanım, Dosyalar (PC gezgini + Telefon indirilenleri).
  - **Ayarlar:** Bağlantı/Eşleme, Sağlayıcılar (görünürlük+sıra), Bildirimler,
    MCP, Güncelleme, Gelişmiş (filo sağlığı, processes, kill-all).

## 2. Yüzey desenleri (hangi içerik hangi kapta yaşar)

| Desen | Ne zaman | Örnek |
|---|---|---|
| **EKRAN** | Tam içerikli akış; listeleme, detay, gezinme | Proje detayı, Operasyonlar, Dosyalar, dosya önizleme |
| **SHEET** | Hafif, tek seçimlik seçici; 1 ekranda bitmeli | model, effort, izin modu, oturum bilgisi, kullanım özeti |
| **DIALOG** | Yıkıcı/geri alınamaz işlem onayı; kısa metin girişi | oturum silme, sekme kapatma onayı |
| **ÇEKMECE** | Sohbet bağlamını terk etmeden oturum değiştirme | oturum çekmecesi (tek örnek; yenisi eklenmez) |
| **KART (inline)** | Tur akışının içindeki etkileşim | onay kartı, kullanıcı-girdisi kartı, plan kartı |

Kesin kurallar:
- Sheet üstüne sheet AÇILMAZ. Sheet'ten derinleşen akış ekrana dönüşür.
- Sheet'te kaydırılabilir uzun içerik varsa yanlış desen seçilmiştir → ekran.
- Dialog'da liste/karmaşık form OLMAZ; en fazla başlık + açıklama + 2 aksiyon
  (+ tek metin alanı).
- Bir işlev iki yüzeyden erişilebilir olabilir ama TEK implementasyonu olur
  (örn. kullanım: Merkez'de ekran, sohbette özet sheet — aynı veri bileşeni).

## 3. Backend'lere davranış: capability-driven, istisnasız

- UI hiçbir yerde backend id'sine göre `when`/`if` dallanmaz. Kontroller
  `backendCapabilities()` + `/backends` kataloğundan üretilir.
- Backend'e özel bir kontrol gerekiyorsa capability olarak modellenir
  (bridge kataloğuna alan eklenir), string-switch yazılmaz.
- Jenerik sheet'ler: model seçici, effort seçici, izin modu seçici, oturum
  bilgisi, hesap seçici. Hepsi tek implementasyon, veri parametrik.
- Kalan backend seti: claude-app, codex-app, opencode-app, zcode, agy, cowork
  (chatgpt-planner ve claude-desktop-cowork kaldırıldı — Faz 0.9).

## 4. Tasarım dili

### 4.1 Tema
- Koyu tema birincil (geliştirici aracı, gece kullanımı); açık tema desteklenir
  ama tasarım koyu temada kurgulanır ve onaylanır.
- Material 3 taban; dinamik renk (Material You) KAPALI — marka paleti sabit
  (durum renkleri anlam taşıdığı için cihaz paletine bırakılmaz).

### 4.2 Renk rolleri (semantik; Ds'de token olur)
- **Zemin/yüzey:** derin nötr zemin, bir tık açık yüzey kartları; kartlar
  gölgeyle değil ton farkı + ince kenarla ayrılır.
- **Vurgu (accent):** tek marka vurgusu — birincil aksiyonlar (gönder, başlat).
- **Durum renkleri (uygulama genelinde AYNI anlam):**
  - `running` — mavi: tur sürüyor (sekme rozeti, durum noktası, composer pill)
  - `attention` — kehribar: onay/girdi bekliyor (kart, rozet, bildirim)
  - `done` — yeşil: bitti-görülmedi rozeti; başarı durumları
  - `danger` — kırmızı: hata, yıkıcı aksiyonlar, bağlantı kopuk
- Sağlayıcı kimliği renkle DEĞİL, monogram/ikonla gösterilir (renk durum içindir).

### 4.3 Tipografi ve yoğunluk
- Sistem fontu; rol ölçeği Ds'de: `display / title / body / label / mono`.
- Kod, yol, komut, log HER ZAMAN mono + yüzey kutusunda.
- Yoğunluk: liste satırları rahat (min 56dp), sohbet kompakt; iki yoğunluk
  arası değer uydurulmaz.

### 4.4 Boşluk ve köşe
- Boşluk ölçeği (dp): 4 / 8 / 12 / 16 / 20 / 28 — ara değer YASAK.
- Köşe: kart 16, sheet üst 24, chip/buton 999 (tam yuvarlak), inline kart 12.
- Ekran yatay dolgusu 20; sheet alt gesture boşluğu 36 (mevcut Ds ile uyumlu).

### 4.5 Terminoloji
- ui-revizyon-talimati.md Faz A sözlüğü aynen geçerli: sağlayıcı, çalışma alanı,
  teslimat, proje klasörü, oturum. Kod tanımlayıcıları ve API string'leri asla
  çevrilmez.

## 5. Bileşen kütüphanesi (2a'da kodlanacak set)

Çekirdek: `AppScaffold` (kök + alt nav), `ScreenHeader`, `SectionHeader`,
`SurfaceCard`, `ListRow` (leading ikon + başlık + detay + trailing),
`StatusDot` / `StatusBadge` (4 durum rengi), `ProviderMark` (monogram),
`SelectorSheet<T>` (tek jenerik seçici), `InfoSheet` (başlık+satırlar),
`ConfirmDialog`, `EmptyState`, `ErrorState`, `LoadingSkeleton`,
`SearchField`, `SegmentedTabs`.

Sohbet: `SessionTabBar`, `MessageBubble` (kullanıcı), `AgentBlock` (ajan metni —
balonsuz, sola yaslı), `ToolCallCard` (katlanabilir), `ThoughtStrip`,
`ApprovalCard`, `UserInputCard`, `PlanCard`, `Composer` (ek + bağlam çipi +
durum pill + gönder/durdur).

Kural: yeni ekran önce bu setten kurulur; sete girmemiş bir bileşen gerekirse
önce sete eklenir (tek dosyada), ekran içinde adhoc tanımlanmaz.

## 6. Durum iletişimi

- Bağlantı durumu (bridge/WS) tek yerden: Sohbet üst barında ince gösterge;
  kopukken kalıcı ama sessiz bant (kırmızı), yeniden bağlanınca kaybolur.
- Her liste ekranının üç hali tasarlanır: dolu / boş (`EmptyState`, tek cümle +
  tek aksiyon) / hata (`ErrorState`, neden + yeniden dene).
- Uzun işlemlerde iskelet (skeleton) tercih edilir; spinner yalnız kısa ve
  belirsiz beklemelerde.
- Onay bekleyen oturum uygulamanın her yerinden fark edilir: sekme rozeti +
  alt nav Sohbet ikonunda kehribar nokta + bildirim (mevcut servis).

## 7. Değişmez ilkeler (üst belgelerden devralınan)

- Sağlayıcı/model otomatik seçen, öneren, değiştiren davranış YOK
  (docs/vision-roadmap.md değişmez kontrol ilkesi).
- Bridge kontratları ve backend id string'leri UI işi sırasında değişmez.
- Her faz `gradlew :app:testDebugUnitTest :app:assembleDebug` geçmeden bitmez.
