package com.agent.bridge.ui3.material

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Konşentrik yarıçap kuralı (anayasa v2 bölüm 3, iOS 26): iç = dış − padding.
 *
 * NEDEN `* 2` DEĞİL: kural yarıçapla ilgili, genişlikle değil. Dış köşenin yay
 * merkezi köşeden (R, R) kadar içeride; padding kadar içeri çekilmiş iç
 * dikdörtgenin köşesi (p, p) noktasında. İki eğrinin paralel kalması için
 * ikisinin yay merkezi AYNI nokta olmalı, bu da iç yarıçapı R − p yapar.
 * `* 2` kullanılırsa merkezler ayrışır ve eğriler birbirinden uzaklaşır.
 *
 * Bu fonksiyon önce `outer − padding * 2` olarak yazılmıştı: anayasa v2 kuralı
 * doğru yazıyor ama altındaki örneği (dış 33, dolgu 6 → 21) mockup'tan
 * devralmıştı ve o örnek kuralla çelişiyordu (33 − 6 = 27). Mockup'ın kendi
 * `.ifield` değeri tasarımcı tercihi/hatası; çelişkide anayasa kazanır.
 * Örnek anayasa v2'de düzeltildi.
 *
 * Sonuç negatife düşerse sıfıra kıstırılır — negatif yarıçap geçersizdir ve
 * kırılma yüzeylerinde bozuk çizime yol açar.
 */
fun concentric(outer: Dp, padding: Dp): Dp =
    (outer - padding).coerceAtLeast(0.dp)
