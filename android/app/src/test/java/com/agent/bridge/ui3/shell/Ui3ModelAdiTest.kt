package com.agent.bridge.ui3.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model satırının sağlayıcı/ad ayrımı ve monogramı.
 *
 * Bu iki fonksiyon uydurmanın en kolay olduğu yer: katalog yalnız `id` ve
 * `label` veriyor, bağlam boyu ya da fiyat YOK. Bu yüzden ayrım da monogram da
 * SADECE etiketten türetiliyor ve testler tam olarak bunu bekçiliyor.
 */
class Ui3ModelAdiTest {
    @Test
    fun splitsProviderAndName() {
        val (saglayici, ad) = ui3ModelAdiAyir("deepseek-pro · deepseek-v4-pro")

        assertEquals("deepseek-pro", saglayici)
        assertEquals("deepseek-v4-pro", ad)
    }

    // Ayıraç yoksa sağlayıcı BİLİNMİYOR demektir; "openai" diye tahmin
    // yürütülmez, alt satır hiç çizilmez.
    @Test
    fun keepsWholeLabelWhenThereIsNoSeparator() {
        val (egikSaglayici, egikAd) = ui3ModelAdiAyir("nanogpt/abliteration-ai/abliterated-model")
        assertEquals("nanogpt", egikSaglayici)
        assertEquals("abliteration-ai/abliterated-model", egikAd)
        assertEquals(null to "a / b", ui3ModelAdiAyir("a / b"))
        assertEquals(null to "model/", ui3ModelAdiAyir("model/"))
        val (saglayici, ad) = ui3ModelAdiAyir("GPT-5.6 Sol")

        assertNull(saglayici)
        assertEquals("GPT-5.6 Sol", ad)
    }

    // Etiketin içindeki nokta işareti ayıraç sanılmamalı: ayıraç boşluklu.
    @Test
    fun doesNotSplitOnBareMiddleDot() {
        val (saglayici, ad) = ui3ModelAdiAyir("gpt·mini")

        assertNull(saglayici)
        assertEquals("gpt·mini", ad)
    }

    @Test
    fun emptySideFallsBackToWholeLabel() {
        val (saglayici, ad) = ui3ModelAdiAyir(" · deepseek-v4-pro")

        assertNull(saglayici)
        assertEquals(" · deepseek-v4-pro", ad)
    }

    @Test
    fun monogramComesFromProviderFirstToken() {
        assertEquals("DE", ui3ModelMonogrami("deepseek-pro", "deepseek-v4-pro"))
        assertEquals("OP", ui3ModelMonogrami("opencode-go", "muse-spark-1.2"))
        assertEquals("ZE", ui3ModelMonogrami("zen", "big-pickle"))
    }

    @Test
    fun monogramFallsBackToNameWhenProviderUnknown() {
        assertEquals("GP", ui3ModelMonogrami(null, "GPT-5.6 Sol"))
    }

    // TÜRKÇE-I TUZAĞI: cihaz dili tr-TR iken "i".uppercase() "İ" verir ve aynı
    // model iki cihazda farklı monogram gösterirdi. Locale.ROOT şart.
    @Test
    fun monogramIsLocaleIndependent() {
        val onceki = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertEquals("IN", ui3ModelMonogrami("inception", "mercury"))
        } finally {
            java.util.Locale.setDefault(onceki)
        }
    }

    @Test
    fun monogramNeverEmpty() {
        assertEquals("?", ui3ModelMonogrami(null, ""))
        assertEquals("?", ui3ModelMonogrami("", "   "))
    }

    // Merdiven ancak gerçek bir ölçek varken doğru bileşen: iki kademe ölçek
    // değil, iki düğmedir.
    @Test
    fun ladderNeedsAtLeastThreeSteps() {
        assertFalse(ui3CabaMerdiveniUygun(0))
        assertFalse(ui3CabaMerdiveniUygun(2))
        assertTrue(ui3CabaMerdiveniUygun(3))
        assertTrue(ui3CabaMerdiveniUygun(7))
    }
}
