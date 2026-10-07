package com.agent.bridge.ui3.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * GENİŞ YERLEŞİM ÖLÇÜLERİ — tablet fazı (kullanıcı kararı, 18.08.2026).
 *
 * Kilitli karar "telefon önce; tablet bozulmasın ama optimize edilmesin"di ve
 * bu faza kadar tablette telefon yerleşimi geniş ekrana yayılıyordu. Kullanıcı
 * iki panel istedi: solda oturum listesi kalıcı, sağda sohbet; gezinme SOL
 * kenarda dikey rail (ui2'de rail sağdaydı — o karar tabletin iki elle
 * tutulmasına dayanıyordu, ui3'te sol istendi).
 *
 * Eşik ui2'nin `AppScaffold`'undaki sayının aynısı: 840dp. Aynı cihazda iki
 * arayüzün farklı genişlikte kabuk değiştirmesi, "ui2'de böyle değildi" diye
 * geri gelen bir fark olurdu. Tab S10+ yatayda ~1400dp (geniş), dikeyde ~900dp
 * (yine geniş); telefon yatayda ~850dp — sınırda ama geçiyor, bu bilinçli:
 * yatay telefonda da tek sütun uzun satır demek.
 */
internal val UI3_GENIS_ESIK = 840.dp

/**
 * İKİ PANEL eşiği — kalıcı oturum listesi bundan dar ekranda ÇİZİLMEZ.
 *
 * Ölçüldü (Tab S10+, 18.08.2026): cihaz 1752×2800 @320dpi, yani dikeyde 876dp,
 * yatayda 1400dp — ikisi de "geniş". Ama dikeyde rail (116) + panel (332)
 * gidince sohbete 428dp kalıyor; okuma sütununun hedefi 720dp, yani iki panel
 * dikeyde sohbeti telefondan DAR bırakıyordu. Sınır rail + panel + makul bir
 * sütun: 116 + 332 + 620 ≈ 1068, yuvarlanmış hâli 1080dp.
 *
 * Bu eşiğin altında geniş yerleşimin geri kalanı (rail, okuma sütunu, sheet
 * sınırı) sürer; yalnız oturum listesi çekmece olarak kalır — kaydırma jesti
 * ve rail'deki Oturumlar tuşu da o yüzden orada geri geliyor.
 */
internal val UI3_IKI_PANEL_ESIK = 1080.dp

/** Rail'in soldan kapladığı toplam yer: kenar boşluğu + rail + içerik arası. */
internal val UI3_RAIL_KENAR = 16.dp
internal val UI3_RAIL_ARA = 12.dp
internal val UI3_RAIL_ALAN = UI3_RAIL_KENAR + 88.dp + UI3_RAIL_ARA

/**
 * Kalıcı oturum panelinin genişliği. 320dp Material'ın liste panosu ölçüsü;
 * oturum satırı (başlık + alt satır + rozet) burada kırpılmadan duruyor.
 */
internal val UI3_OTURUM_PANEL_G = 320.dp
internal val UI3_OTURUM_PANEL_ALAN = UI3_OTURUM_PANEL_G + UI3_RAIL_ARA

/**
 * Sheet'in geniş ekrandaki azami genişliği. Telefon genişliğinin bir tık
 * üstü: sheet'lerin içeriği (seçim listeleri, onay, tur ayarları) telefon
 * ölçüsüne göre tasarlandı, tablette büyütmek satırları esnetmekten başka
 * bir şey yapmıyor.
 */
internal val UI3_SHEET_AZAMI = 520.dp

/**
 * Okuma sütunu: geniş ekranda içeriği ORTALAR ve sınırlar.
 *
 * Gerekçe ui2'nin `ContentWidth`'iyle aynı ama sayı farklı: burada sınırlanan
 * şey kart listesi değil AKAN METİN. Satır uzunluğu 90 karakteri geçince göz
 * satır başını kaybediyor; 16sp Tiempos'ta ~720dp bunu ~75 karakterde tutuyor.
 * Sohbette şerit (composer) ve sekme çubuğu da aynı sütuna giriyor — yoksa
 * metin ortada, şerit ekranın dibinde iki ayrı genişlikte duruyordu.
 *
 * Dar ekranda HİÇBİR ŞEY değişmez: sarmalayıcı Box tam genişlik verir.
 */
internal val UI3_OKUMA_SUTUNU_AZAMI = 720.dp

/**
 * Adanın geniş yerleşimde EKRANIN sağ ucundan yediği yer (kenar payı dahil).
 *
 * Sekme çubuğunun "＋" tuşu okuma SÜTUNUNUN sağ ucunda, ada ise EKRANIN sağ
 * ucunda ve ikisi tam olarak aynı dikey bantta (durum çubuğunun hemen altı,
 * 28dp). Sütun ekrandan yeterince dar kalmazsa ada "＋"ın üstüne biniyor — ada
 * salt gösterge iken bu yalnız görüntüydü, dokunulabilir olunca "＋"ın
 * dokunuşunu YİYORDU. Bu sayı çubuğa ne kadar yer ayırtacağını söylüyor;
 * hesabı `Ui3Root`ta, sütunun sağ boşluğundan düşülerek yapılıyor.
 *
 * 132 = 16dp kenar payı + adanın en geniş hâli (nokta + süre + %100 +
 * ekolayzer + dolgu ≈ 106dp) + pay.
 */
internal val UI3_ADA_ALAN = 132.dp

/**
 * Sekme çubuğunun sağ ucuna ("＋" tuşu) adanın altına düşmemesi için verilecek
 * iz boşluğu. Compose'un DIŞINDA: bu bir aritmetik ve cihazsız sınanabilmeli.
 *
 * Sütun ekranın ortasında ve en fazla [UI3_OKUMA_SUTUNU_AZAMI] genişliğinde;
 * sağında kalan boşluk kadar "＋" zaten adadan uzaklaşmış oluyor, eksik kalanı
 * burada kapatıyoruz. Dar ekranda 0 — orada ada sekme çubuğunun bandında bile
 * değil, sistem durum çubuğunun içinde.
 */
internal fun ui3AdaSeritPayi(genis: Boolean, ekranGenisligi: Dp, icerikSolBosluk: Dp): Dp {
    if (!genis) return 0.dp
    val sagBosluk =
        ((ekranGenisligi - icerikSolBosluk - UI3_OKUMA_SUTUNU_AZAMI) / 2).coerceAtLeast(0.dp)
    return (UI3_ADA_ALAN - sagBosluk).coerceAtLeast(0.dp)
}

@Composable
internal fun Ui3OkumaSutunu(
    genis: Boolean,
    modifier: Modifier = Modifier,
    azami: Dp = UI3_OKUMA_SUTUNU_AZAMI,
    icerik: @Composable () -> Unit,
) {
    if (!genis) {
        icerik()
        return
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = azami).fillMaxSize()) { icerik() }
    }
}

/**
 * Composer şeridinin Yenile + Kullanım'ı TUR SÜRERKEN DE taşıyabildiği en dar
 * ekran genişliği (kullanıcı, 18.08.2026: "tablette gizlemeye gerek yok").
 *
 * Telefonda ikisi tur sürerken gizleniyor çünkü şerit dolup taşıyor
 * (aritmetik Ui3Composer başında). Tabletteyse aynı şeritte 600dp'den fazla
 * boş yer var — gizleme orada sebepsiz bir kayıp.
 *
 * Sayı şeridin en kalabalık hâlinden geliyor: yedi kutu (ek, ⋯, yenile,
 * kullanım, yönlendir, kuyruk, durdur) + aralar + kırpılmamış model çipi
 * ≈ 417dp; buna composer'ın kendi dolgusu (6×2), ekran kenar boşluğu (16×2)
 * ve satır dolgusu (2×2) ekleniyor → 465, yuvarlanmış hâli 470dp.
 *
 * EKRAN genişliğine bakılıyor, composer'ın kendi genişliğine değil: eşik
 * okuma sütununun (720dp) çok altında olduğu için ikisi bu bantta aynı şeyi
 * söylüyor, ama tek yerden okunması ⋯ menüsüyle şeridin ÇELİŞMEMESİNİ
 * garanti ediyor (menü, şeritte gizlenenleri satır olarak gösteriyor —
 * ikisi ayrı ölçüye baksa aynı anda ikisi birden çizilirdi).
 */
internal val UI3_SERIT_TAM_ESIK = 470.dp

/**
 * Merkez'in iki sütuna geçtiği genişlik (tablet fazı 2, 18.08.2026).
 *
 * Merkez tek sütunda üç kart: durum şeridi, çalışma alanları, dizin. Geniş
 * ekranda dizin kartı 720dp'lik sütunda yatay yatay uzayıp altta bitiyordu ve
 * ekranın alt yarısı boştu. İki sütun: solda çalışma alanları (nöbet paneli),
 * sağda dizin. Durum şeridi üstte tam genişlikte kalır — o bir şerit, kart değil.
 *
 * Sayı: rail alanı (116) + iki sütun × 400 + ara (16) ≈ 932; 960'a yuvarlandı.
 * `UI3_GENIS_ESIK`ten (840) ayrı tutuluyor çünkü 840'ta sütun başına ~350dp
 * düşüyor — telefon genişliği, yani iki sütunun bir faydası kalmıyor.
 */
internal val UI3_MERKEZ_IKI_SUTUN_ESIK = 960.dp

/**
 * Merkez iki sütundayken okuma sütununun sınırı. 720 değil çünkü o sayı AKAN
 * METİN için (satır ~75 karakter); burada yan yana iki kart var ve her birinin
 * kendi iç satırları kısa (rozet, ayrıntı). 1040 = iki × ~510 + ara.
 */
internal val UI3_MERKEZ_AZAMI = 1040.dp

/**
 * Geniş ekranda sheet artık ALTTAN değil SAĞDAN gelir — yüzen yan panel.
 *
 * Gerekçe dikey yer: tablet YATAYDA ~547dp yüksek (Tab S10+, 1752px/3.2) ve
 * alttan gelen bir seçim listesi ekranın neredeyse tamamını kaplıyordu.
 * Yatayda bol olan şey genişlik; panel oraya taşınınca liste kendi boyunda
 * kalıyor ve arkadaki sohbet okunmaya devam ediyor.
 *
 * Panel BOYUNCA UZAMAZ: yüksekliği içeriği kadar, dikeyde ortalı — üç satırlık
 * bir menü için tavandan tabana cam sütun çizmek dock'un "yüzen levha" diliyle
 * çelişirdi (aynı hata rail'de yapılıp cihazda görülünce düzeltildi).
 */
internal val UI3_YAN_SHEET_G = 420.dp
