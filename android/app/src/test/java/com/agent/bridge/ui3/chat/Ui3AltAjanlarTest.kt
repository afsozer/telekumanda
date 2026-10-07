package com.agent.bridge.ui3.chat

import com.agent.bridge.OpencodeSubagent
import com.agent.bridge.OpencodeSubagentLine
import com.agent.bridge.OpencodeSubagentTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alt-ajan kart yığınının okunabilir tarafı — Compose'un dışında duran saf
 * fonksiyonlar (Ui3GorevPanosuTest ile aynı gerekçe: cihazsız sınanabilmeli).
 *
 * Yığının bütün değeri iki kararda: özet satırı ("2 alt ajan · 1 koşuyor") ve
 * SIRALAMA. Kapalıyken yalnız iki kart görünüyor, yani sıralama "hangisini
 * göreceğim"in kendisi — koşan ajan listenin dibinde kalırsa uzun koşuda canlı
 * olanı hiç göremezsin.
 */
class Ui3AltAjanlarTest {

    private fun ajan(id: String, durum: String, baslik: String = id) =
        OpencodeSubagent(id = id, title = baslik, status = durum)

    @Test
    fun altAjanYokkenHicCizilmez() {
        assertFalse(altAjanlarGorunur(emptyList()))
        assertEquals("", altAjanlarOzeti(emptyList()))
        // Pano'dan ayrıldığı nokta: tur sürüyor olması TEK BAŞINA yığını açmaz.
        assertTrue(altAjanlarGorunur(listOf(ajan("ses_a", "idle"))))
    }

    @Test
    fun ozetKosanSayisiniVerir() {
        val liste = listOf(ajan("ses_a", "running"), ajan("ses_b", "idle"), ajan("ses_c", "running"))
        assertEquals(2, altAjanKosanSayisi(liste))
        assertEquals("3 alt ajan · 2 koşuyor", altAjanlarOzeti(liste))
    }

    @Test
    fun hepsiBitinceBittiYazar() {
        val liste = listOf(ajan("ses_a", "idle"), ajan("ses_b", "idle"))
        assertEquals("2 alt ajan · bitti", altAjanlarOzeti(liste))
    }

    // Hata koşan ajandan ÖNCE gelir: sessizce patlamış bir delege, ana ajanın
    // yanlış varsayımla devam etmesi demek — kullanıcı kart açmadan görmeli.
    @Test
    fun hataKosanAjanIRaportanOnceGelir() {
        val liste = listOf(ajan("ses_a", "running"), ajan("ses_b", "error"))
        assertEquals("2 alt ajan · 1 hata", altAjanlarOzeti(liste))
    }

    @Test
    fun kosanAjanlarBasaAlinir_kalanTersKronolojik() {
        val liste = listOf(
            ajan("ses_1", "idle", "ilk"),
            ajan("ses_2", "idle", "ikinci"),
            ajan("ses_3", "running", "ucuncu"),
            ajan("ses_4", "idle", "dorduncu"),
        )
        // Koşan öne; kalanlar en yeniden eskiye.
        assertEquals(
            listOf("ucuncu", "dorduncu", "ikinci", "ilk"),
            altAjanSirasi(liste).map { it.title },
        )
    }

    @Test
    fun kapaliYiginSadeceIkiKartGosterir() {
        val liste = (1..5).map { ajan("ses_$it", "idle") }
        assertEquals(2, UI3_ALT_AJAN_GORUNEN)
        assertEquals(2, altAjanSirasi(liste).take(UI3_ALT_AJAN_GORUNEN).size)
    }

    @Test
    fun durumBayraklariKopruyleAyniSozcukleriKullanir() {
        assertTrue(ajan("ses_a", "running").kosuyor)
        assertTrue(ajan("ses_a", "error").hatali)
        assertFalse(ajan("ses_a", "idle").kosuyor)
        // Tanınmayan durum sessizce "koşuyor"a düşmez: köprü bir gün yeni bir
        // durum eklerse kart onu duran ajan gibi çizsin, canlı gibi değil.
        assertFalse(ajan("ses_a", "yeni_bir_durum").kosuyor)
        assertFalse(ajan("ses_a", "yeni_bir_durum").hatali)
    }

    @Test
    fun sohbetOzetiAjanAdiniVeSatirSayisiniVerir() {
        val t = OpencodeSubagentTranscript(
            childId = "ses_a",
            title = "dosya sayimi",
            agent = "general",
            messages = listOf(
                OpencodeSubagentLine("user", "Kaç dosya var?"),
                OpencodeSubagentLine("thought", "list · completed"),
                OpencodeSubagentLine("agent", "Üç."),
            ),
        )
        // Araç satırı sayılmaz: "kaç satır konuştu" sorusu konuşmayla ilgili.
        assertEquals("general · 2 satır", altAjanSohbetOzeti(t))
        assertEquals(null, altAjanSohbetOzeti(null))
    }
}
