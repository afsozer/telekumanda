package com.agent.bridge

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

// Sohbet yoklamasının (açık konuşmayı 2,5 sn'de bir baştan çeken döngüler)
// ortak kapısı. 21.10.2026 ölçümü: döngüler arka planda ve ekran kapalıyken de
// dönüyordu, her turda tüm konuşma (OMP/Agy'de sınırsız) iniyordu;
// telefon 10 saatte mobil veride 247 MB çekti. İki kural:
//  - uygulama görünür değilken döngü uyur: istek atmaz, radyoyu uyandırmaz;
//  - akış soketi bağlıyken istek atılmaz: aynı konuşma soketten delta olarak
//    geliyor, yoklama yalnız soket koptuğunda yedek kanal.
object SohbetYoklamasi {
    const val ARALIK_MS = 2_500L

    // Varsayılan true: süreç aktivitesiz ayağa kalkarsa (widget, testler)
    // döngü zaten yoktur; aktivite ON_START/ON_STOP'ta günceller.
    private val gorunurAkisi = MutableStateFlow(true)
    val gorunur: StateFlow<Boolean> get() = gorunurAkisi

    fun gorunurlukDegisti(deger: Boolean) {
        gorunurAkisi.value = deger
    }

    /** Bir tur bekler (arka plandaysa öne dönene kadar); true ise çağıran konuşmayı çeker. */
    suspend fun siradakiTur(soketBagli: () -> Boolean): Boolean {
        delay(ARALIK_MS)
        gorunurAkisi.first { it }
        return !soketBagli()
    }
}
