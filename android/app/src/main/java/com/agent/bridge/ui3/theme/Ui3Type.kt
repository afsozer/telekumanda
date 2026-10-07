package com.agent.bridge.ui3.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.agent.bridge.R

// ui3 tipografi rolleri (anayasa v2, bölüm 4). Kod/yol/komut/log HER ZAMAN
// mono + yüzey kutusunda kullanılır (v1 4.3 aynen sürer).
// Mockup'taki px tracking değerleri ui2 üslubunda olduğu gibi .sp birimiyle
// yazılır — 34px/-1.2px ve 42px/-1.8px buradaki letterSpacing karşılığıdır.

val Ui3Mono = FontFamily.Monospace

/**
 * ui3'ün metin yüzü: Liberation Serif (SIL OFL, `res/font`). Dört gerçek
 * kesit var; kalın/italik sentezlenmez.
 */
val Ui3Serif = FontFamily(
    Font(R.font.liberation_serif_regular, FontWeight.Normal),
    Font(R.font.liberation_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.liberation_serif_bold, FontWeight.Bold),
    Font(R.font.liberation_serif_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/**
 * Sayıların yan yana dizildiği yerde tabular rakam.
 *
 * Orantılı rakamda "1" dar, "0" geniştir; saniyede bir değişen bir süre ya da
 * yüzde bu yüzden her tikte yerinden oynar. `tnum` hepsini aynı genişliğe
 * getirir.
 */
const val UI3_TABULAR = "tnum"

object Ui3Type {
    // HANGİ ROL SERİF, HANGİSİ DEĞİL (bilinçli ayrım):
    //   okunan yüzeyler → Mackinac (başlık, bento sayısı, gövde, alt metin)
    //   taranan yüzeyler → sistem sans'ı (etiket, rozet; dock etiketi rozet
    //     kullanıyor, ada kendi fontSize'ıyla çiziyor)
    // Gerekçe: serif okuma hızını artırır, tarama hızını düşürür. Dock ve
    // durum rozetleri okunmuyor, tanınıyor — orada sans kalıyor.

    // Büyük başlık 34sp, tracking −1.2px, ağırlık 800 (mockup .bighead h1, satır 136).
    // Aynı zamanda oturum seçicidir (▾) — ayrı sekme çubuğu yok.
    val baslik = TextStyle(
        fontFamily = Ui3Serif,
        fontSize = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.2).sp,
    )
    // Bento sayıları 42sp, tracking −1.8px (anayasa v2, bölüm 4).
    val bignum = TextStyle(
        fontFamily = Ui3Serif,
        fontSize = 42.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.8).sp,
        fontFeatureSettings = UI3_TABULAR,
    )
    // Etiket 11sp, ağırlık 800, geniş harf aralığı. BÜYÜK HARF'e dönüşümü bileşen
    // yapar (anayasa v2, bölüm 4) — TextStyle'a gerek yok, buradan temiz gider.
    val etiket = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
    // Rozet (bento durum rozeti, mockup .ws-badge): etiketten küçük ve dar
    // aralıklı — pill içinde duruyor, 1.6sp harf aralığı orada genişlik yiyor.
    val rozet = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    // Gövde metni: akan metin. 15 → 15.5sp ve 22 → 24sp satır yüksekliği
    // (kullanıcı isteği, Mackinac'a geçtikten sonra): serif yüz daha çok
    // nefes istiyor, markdown gövdesinde de aynı oranda açıldı (1.05 → 1.15).
    val govde = TextStyle(fontFamily = Ui3Serif, fontSize = 15.5.sp, lineHeight = 24.sp)

    /**
     * SOHBET AKIŞI — ajan metninin boyu. Markdown `textSizeSp`'i buradan okur.
     *
     * Tarihçe: önce ajan 14 / kullanıcı 15.5'ti (yan yana farklı boy, kullanıcı
     * bildirdi) ve ikisi 14'te eşitlendi. 18.08.2026'da kullanıcı ayrımı BİLEREK
     * geri istedi: "ajan metni biraz büyüsün (okunabilirlik), benimki yarım
     * puan" — fark artık kaza değil karar; eşitleme kuralı geçersiz. Aynı gün
     * ikinci kademe: resmî Claude uygulamasıyla kıyasta 15 ürkek kaldı, önce
     * 17 denendi ama TAŞTI (kullanıcı bildirdi). Ders: resmî uygulamanın
     * gövdesi ~17sp (ilk ölçüm yoğunluğu 480 sanıp ~19 demişti, telefon 560)
     * ve o da Tiempos — 17sp'de glif eşit olsa bile Tiempos'un bol dikey
     * metriği + markdown'ın satır çarpanı bizi daha iri gösteriyor.
     * 16, resmî uygulamayla optik denklik: BÜYÜTECEKSEN önce cihazda yan
     * yana ölç, yoğunluk 560'a göre hesapla.
     *
     * SATIR ARASI: ajan metninin pitch'i BU DEĞERDEN GELMİYOR — markdown bir
     * TextView'da çiziliyor ve orada satır yüksekliği `satirCarpani × fontun
     * kendi asc+desc'i` (Ui3Markdown). Tiempos'ta hhea/typo asc 761, desc −239,
     * yani tam 1.0 em; lineGap (200) StaticLayout'a girmez. Efektif pitch bu
     * yüzden 1.28 × 1.0 em × 16sp ≈ 20.5sp. Buradaki lineHeight o değerin
     * AYNASI: kullanıcı balonu (aşağıda) ve akışla aynı dili konuşan diyalog
     * metinleri aynı ritmi tutsun diye. Çarpan değişirse bu sayı da değişmeli
     * (20.5 = 1.28 × 16); ölçmeden büyütme, cihazda yan yana bak.
     */
    val akis = TextStyle(fontFamily = Ui3Serif, fontSize = 16.sp, lineHeight = 20.5.sp)

    /**
     * Kullanıcı balonu — punto ajandan yarım küçük (yukarıdaki karar), ama
     * SATIR ARASI birebir aynı (kullanıcı isteği 18.08): balonun kendi
     * lineHeight'ı 25.5sp'ken ajanınki ~20.5'ti ve yan yana durunca balon
     * belirgin havadar duruyordu. Eşitlik mutlak pitch üzerinden — aynı ritim,
     * yarım puntoluk fark yalnız glifte.
     */
    val akisKullanici = TextStyle(fontFamily = Ui3Serif, fontSize = 15.5.sp, lineHeight = 20.5.sp)

    /**
     * Prompt kutusunda YAZILAN metin.
     *
     * Kural "akıştan 1 punto küçük"tü (akış 14'ken kondu) ve akış 16'ya
     * çıkarken kutu bilerek 13'te bırakılmıştı — "yazma alanı okuma yüzeyi
     * değil" gerekçesiyle. Kullanıcı buna itiraz etti (18.08): yazarken de
     * okuyorsun, 13sp o iş için küçük. 15sp — akıştan yine bir punto küçük,
     * yani asıl kural geri döndü; satır arası sohbetin ritmine bağlandı
     * (20.5sp) ki iki satırlık bir prompt akışla aynı nefesi alsın.
     * Bedeli composer'ın bir tık uzaması, ölçülüp kabul edildi.
     */
    val yazi = TextStyle(fontFamily = Ui3Serif, fontSize = 15.sp, lineHeight = 20.5.sp)
    // Alt/ikincil metin: başlık ve metin arasında bir kademe iner.
    val alt = TextStyle(fontFamily = Ui3Serif, fontSize = 13.sp, lineHeight = 18.sp)
}
