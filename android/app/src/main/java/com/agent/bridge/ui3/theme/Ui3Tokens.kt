package com.agent.bridge.ui3.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ui3 ölçü token'ları (anayasa v2, bölüm 3).
// Ekranlar ham dp YAZMAZ; buradan alır. Değer uydurma yok: boşluk ölçeği ve
// yarıçap ailesi anayasadan, cam parametreleri mockup'tan gelir.
//
// ui2'den farklı olarak CompositionLocal katmanı yok: ui3'te dinamik renk
// (Material You) KAPALI ve koyu tema tek birincildir, yani çalışma zamanında
// değişen bir değer yok. Düz object token'ları hem daha ucuz (okuma = sabit
// erişim) hem de bu kısıtı yapısal olarak garanti eder — değişebilecek bir
// katman hiç yoktur.
object Ui3Tokens {
    // Boşluk ölçeği — v1'den aynen devam (anayasa v2, bölüm 3). Ara değer yasak.
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s28 = 28.dp

    // Konşentrik yarıçap ailesi (anayasa v2, bölüm 3): iç yarıçap = dış − padding.
    // Yalnız bu altı değer geçerlidir; ara değer kullanmak paralel kenar kuralını
    // bozar ve göz "yamuk" görür ama nedenini söyleyemez. Yeni değer ekleme.
    val r12 = 12.dp
    val r18 = 18.dp
    val r21 = 21.dp
    val r26 = 26.dp
    val r33 = 33.dp
    val r38 = 38.dp

    // Cam malzeme parametreleri (mockup .lg, satır ~113-115). Blur ve doygunluk
    // Haze kütüphanesine aktarılır; aynı değerin iki yerde yazılmasın diye token.
    val blur = 26.dp          // Haze blur yarıçapı
    val doygunluk = 1.75f     // %175 saturasyon
    val pill = RoundedCornerShape(999.dp)  // tam yuvarlak chip/buton

    // HAREKET SÜRELERİ (ms). Kabuğun mevcut sözlüğü: dock basışı 140,
    // sheet açılışı 180, sheet kapanışı 260.
    //
    // Ekran geçişi = 260, kabuğun en uzun hareketi. Gerekçe iki adımda oluştu:
    //
    // 1) NavHost'a hiç geçiş verilmemişti, navigation-compose'un VARSAYILANI
    //    çalışıyordu: 700ms solma. Kullanıcı "animasyon güzel ama hızı sorun"
    //    dedi (18.08.2026), 180'e çekildi.
    // 2) 180'de kullanıcı "animasyon tamamen kalkmış gibi, gözle fark
    //    edemiyorum" dedi. Sebep süre değil: SAF SOLMA, birbirine benzeyen iki
    //    cam ekran arasında kısa sürede gözle yakalanmıyor — opaklık değişimi
    //    hareket olarak okunmuyor. Çözüm süreyi tekrar uzatmak değil, geçişe
    //    YER DEĞİŞTİRME eklemek (bkz. Ui3Root: gelen ekran solarken kayıyor).
    //    Süre 260'a çıkarıldı çünkü kayma göz için solmadan daha uzun bir yol.
    const val ekranGecisiMs = 260

    // Ekran geçişindeki dikey kayma. Boşluk ölçeğinden (s16): hareketin
    // GÖRÜLMESİ yeter, ekranın kaydığı hissi verilmemeli — daha büyük bir
    // değer geçişi yeniden yavaş gösterir.
    val ekranGecisiKayma = 16.dp

    // Sekme geçişindeki YATAY kayma. Ekran geçişininkinden büyük (28 > 16) ve
    // gerekçesi yukarıdaki hikâyenin devamı: orada kayma solmayı GÖRÜNÜR
    // kılmak için eklenen bir ipucuydu, burada hareketin KENDİSİ mesaj —
    // parmak yatay gitti, sayfa yandan geliyor. Yine boşluk ölçeğinden (s28),
    // uydurulmuş bir sayı değil.
    val sekmeGecisiKayma = s28
}
