# Kovan arayüzü → Telekumanda (ui3)

7 Kas 2026. Kullanıcı Kovan'ın (1 Kas'ta silinen, OpenBot esinli çoklu ajan uygulaması)
telefon arayüzünü Telekumanda ve Lite'a geçirmek istedi. Kovan Expo/React Native'di;
kod taşınmadı, görsel dil Compose'a çevrildi.

## Kovan belirteçleri (kaynak: Kovan `mobile/src/theme.ts`)

| rol | açık | koyu |
|---|---|---|
| zemin | `#F6F5F2` | `#161514` |
| kart | `#FFFFFF` | `#1F1E1C` |
| metin | `#1D1C1A` | `#ECEBE7` |
| sessiz metin | `#6D6A63` | `#9B978E` |
| çizgi | `#D9D6CE` (ui3'te `#DEDBD3`) | `#34322E` |
| vurgu | `#9E5615` | `#D9893F` |
| vurgu üstü yazı | `#FFFFFF` | `#1A1208` |
| tehlike | `#B3261E` | `#F2827A` |

## Ne değişti (taban katman)

- `ui3/theme/Ui3Palet.kt`: iki palet Kovan değerleriyle; mesh renkleri saydam, `meshGucu` 0,
  `grad` düz vurgu.
- `ui3/material/GlassSurface.kt`: cam (Haze blur, rim, spekular, gren) yerine opak kart +
  1dp çizgi. `GlassTint.Amber` vurgu kenarı (Kovan izin kartı).
- `ui3/material/MeshBackground.kt`: güç 0 iken hiçbir şey çizmez.
- `ui3/theme/Ui3Type.kt` + `Ui3Markdown.kt`: Tiempos yerine sistem sans'ı; başlık 28sp kalın,
  bölüm etiketi 14sp yarı kalın, büyük harf yok.
- Dock seçimi düz vurgu hapı, ada renkleri sıcak tonlar, ödünç ui2 yüzeyleri Kovan tonları.

- Sohbet (kullanıcı isteği, Kovan'dan sapma): ajan metni de balonda, kullanıcı balonunun
  aynası (sola yaslı, sol alt köşe kırık, en çok %92 genişlik). Sol ray ve düğüm kaldırıldı
  (`RayliSatir` artık düz kutu); canlılık araç çipi noktası ve adadan okunur.

## Kovan kabuğu (ikinci adım, 7 Kas 2026)

İlk adım yalnız renk ve malzemeyi değiştirdi; görenler "hiçbir şey değişmemiş" dedi, çünkü
Kovan'ı Kovan yapan şey düzendi. Kullanıcı kararı: ana ekran + sohbet birlikte, dock kalkar.

- Kök artık ana ekran (`nav/Ui3AnaEkran.kt`, rota `home`): Kovan başlığı (Telekumanda, ara, +,
  ayarlar), "Sekmeler" kartında açık sekmeler (monogram + durum noktası + chevron, uzun basış
  sekme menüsü), ikinci kartta Oturumlar (çekmece) ve Merkez.
- Sohbet: sekme çubuğu yerine `shell/Ui3SohbetUstBari.kt` (geri, oturum adı, tur ayarları
  sürgüsü; alt satırda sağlayıcı · model · bağlam yüzdesi · durum, dokununca Bağlam).
- Composer tek kapsül + yuvarlak gönder. Model, dosya ekle, yenile, kullanım, klasör, ara ve
  bağlam tur ayarları menüsünde (`Ui3TurAyarlariMenusu`).
- Dock, rail, ada ve tablet kalıcı oturum paneli kalktı (`GlassDock.kt` silindi,
  `Ui3SeritYerlesimiTest` şeritle birlikte gitti). Geri: kök ekranlardan ana ekrana.
- Lite: başlık Kovan barı (☰ oturumlar, ad, durum satırı, güncelleme varsa simgesi, sürgü),
  yalnız sohbet rotasında.
- Ortak parçalar `shell/KovanBilesenleri.kt`: `KovanUstBar`, `KovanBolum`, `KovanKart`,
  `KovanSatir`, `KovanMonogram`. Sheet başlıkları büyük harften cümle düzenine indi.

## Logo, Lite adı, başlık, "+" (7 Kas 2026)

- Yeni işaret "Yörünge": açık uçlu halka + boşluğunda nokta (`docs/logo/yorunge.svg`).
  Ana sürüm krem halka/kehribar nokta, Lite ters renk (`src/lite/res/drawable/ic_launcher_foreground.xml`);
  iki uygulama artık aynı adı taşıdığı için ayrım simgede. Arka katman ikisinde ortak, sıcak koyu.
  minSdk 26 olduğu için PNG mipmap'ler silindi, yalnız adaptive ikon. Uygulama içinde
  `TelekumandaIsareti` (Canvas, tema renkleri). Diğer adaylar `docs/logo/logo-adaylari.png`.
- "Lite" görünür metinden kalktı: uygulama adı, köprü profil adı, güncelleme satırı, sürüm
  gösterimi (`-lite` soneki ekranda kırpılıyor; gerçek versionName değişmedi), hata mesajları.
- Sohbet/Lite barında başlık 18sp yarı kalın, alt satır 14sp; ana ekran başlığı 22sp
  (telefon 366dp, 26sp kesiliyordu).
- Composer'a "+" geri geldi (solda); menüdeki "Dosya ekle" satırı kaldırıldı.

## Yayın (7 Kas 2026)

Kovan arayüzü ana uygulamada 446 (11.124), Lite'ta 443 sürümüyle OTA'dan dağıtıldı.
