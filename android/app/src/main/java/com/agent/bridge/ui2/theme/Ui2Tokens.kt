package com.agent.bridge.ui2.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Boşluk/ölçü token'ları (anayasa 4.4). Ekranlar kendi değerini UYDURMAZ,
// buradan alır. Boşluk ölçeği dışında ara değer YASAK.
object Ui2Tokens {
    // Boşluk ölçeği
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s28 = 28.dp

    // Yerleşim
    val screenPadding = 20.dp      // ekran yatay dış boşluk
    val sheetBottom = 36.dp        // sheet alt gesture boşluğu
    val rowMinHeight = 56.dp       // liste satırı minimum yüksekliği (rahat yoğunluk)

    // Geniş ekran (tablet yatay, DeX, masaüstü penceresi) yerleşimi.
    //
    // contentMaxWidth: içerik bundan geniş olmaz, artan yer iki yana boşluk
    // olarak gider. Tab S10+ yatayda ~1400dp; sınırsız bırakılınca satırlar
    // ekranı baştan sona geçip metin sol uçta minik bir şeride sıkışıyordu.
    // Değer iki sütunu rahat alacak kadar geniş, üçüncüye yetmeyecek kadar dar
    // seçildi — 3 sütun bu kart tipinde okunmaz oluyor.
    //
    // adaptiveMinCell: bir hücrenin altına düşmesine izin verilen genişlik.
    // Sütun sayısı bundan türetilir; başlık+açıklama iki satırı bu genişlikte
    // kırpılmadan sığıyor.
    val contentMaxWidth = 1040.dp
    val adaptiveMinCell = 380.dp

    // Köşe
    val cornerCard = 16.dp         // kart
    val cornerInline = 12.dp       // tur içi inline kart (onay, araç çağrısı)
    val cornerSheet = 24.dp        // sheet üst köşesi
    val pill = RoundedCornerShape(999.dp) // chip / buton / arama alanı
    // Çok satıra büyüyen giriş alanı: pill köşesi stadyuma dönüşüp metni
    // köşelerden taşırdığı için sabit yarıçap (tek satırda pill gibi görünür).
    val field = RoundedCornerShape(24.dp)

    // Durum noktası
    val statusDot = 7.dp
}
