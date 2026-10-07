package com.agent.bridge

import com.agent.bridge.ui2.components.wrappedLineCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Canlı kusur 06.08.2026: hücreler 4 satır sınırının ALTINDAYKEN 3. satırda "…"
 * ile kesiliyordu (ekran görüntüsünde görüldü).
 *
 * İki ayrı sebep vardı, ikisi de aynı yönde hata veriyordu:
 * 1. Satıra sığan karakter sayısı "yazı boyutunun 0.55'i" katsayısıyla TAHMİN
 *    ediliyordu; labelSmall'un harf aralığı ve orta kalınlığı yüzünden gerçek
 *    genişlik daha fazla. Sığan karakter fazla sanılınca gereken satır az çıktı.
 *    Asıl sebep buydu; artık genişlik ölçülüyor.
 * 2. Satır sayısı `uzunluk / sığan` bölmesiyle hesaplanıyordu. Kelime sınırda
 *    kırılamadığı için satır sonlarında boşluk kalır ve gerçek satır sayısı bu
 *    bölmeden fazla olabilir. Aşağıdaki ilk test tam bu farkı ölçüyor.
 */
class SheetWrapTest {

    @Test
    fun kelimeSonundakiBoslukSayilir() {
        // Üç adet 6 harfli kelime = 20 karakter, satıra 10 sığıyor.
        // Düz bölme 20/10 = 2 satır derdi. Gerçekte hiçbir iki kelime yan yana
        // sığmadığı için 3 satır gerekiyor.
        assertEquals(3, wrappedLineCount("abcdef abcdef abcdef", 10))
    }

    @Test
    fun tamOturanKelimelerFazladanSatirAcmaz() {
        assertEquals(1, wrappedLineCount("abcde fghi", 10))
        assertEquals(2, wrappedLineCount("abcde fghij", 10))
    }

    @Test
    fun sigmayanTekKelimeKendiIcindeKirilir() {
        // Boşluksuz 25 karakter / 10 -> 3 satır (uzun URL, boşluksuz kod).
        assertEquals(3, wrappedLineCount("a".repeat(25), 10))
        assertEquals(1, wrappedLineCount("a".repeat(10), 10))
        assertEquals(2, wrappedLineCount("a".repeat(11), 10))
    }

    @Test
    fun satirSonuSertKirilmadir() {
        assertEquals(3, wrappedLineCount("ab\ncd\nef", 10))
        // Sert kırılma sarmayla birlikte sayılır: ilk paragraf iki satır.
        assertEquals(3, wrappedLineCount("abcdefg hijklm\nxy", 10))
    }

    @Test
    fun kisaMetinTekSatir() {
        assertEquals(1, wrappedLineCount("", 10))
        assertEquals(1, wrappedLineCount("kısa", 10))
    }

    @Test
    fun tavanAsilmaz() {
        assertEquals(6, wrappedLineCount("kelime ".repeat(200), 10))
        // Genişlik ölçülemediyse (0) tavana çıkılır: metni sessizce kesmektense
        // yer ayırmak yeğdir.
        assertEquals(6, wrappedLineCount("herhangi", 0))
    }

    /**
     * Ekran görüntüsündeki gerçek hücre. Sütuna ~28 karakter sığıyor ve doğru
     * karşılık 4 satır — ekranda 3 satırda kesilmesinin sebebi bu hesap değil,
     * sığan karakterin 33 sanılmasıydı (33'te bölme 3 satır verir).
     */
    @Test
    fun gercekHucreDortSatirGerektirir() {
        val metin = "Uzun bir tablo hücresinin kapladığı satır - " +
            "sütuna sığan karakter sayısına bağlıdır"
        assertEquals(4, wrappedLineCount(metin, 28))
        // Ölçüm hatasının sonucu: sığan karakter fazla sanılınca satır eksilir.
        assertEquals(3, wrappedLineCount(metin, 33))
    }
}
