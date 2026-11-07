package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBrowserSeedTest {
    @Test
    fun `uretilmis gorselin adindan seed okunur`() {
        // Gercek dosya adlari (uretim klasorunden alindi).
        assertEquals(
            "2981237886",
            seedFromGeneratedName("local-ikastrious-v142-stable-generate-1785843469999-2981237886.png"),
        )
        assertEquals(
            "2653807456",
            seedFromGeneratedName("local-nova-orange-xl-v110-generate-1785842916940-2653807456.png"),
        )
    }

    @Test
    fun `video ciktisi da ayni deseni tasir`() {
        assertEquals(
            "123456",
            seedFromGeneratedName("local-ltx-video-2b-video-1785842916940-123456.mp4"),
        )
    }

    @Test
    fun `uretilmemis dosyalarda null doner - sondaki sayi seed sanilmasin`() {
        assertNull(seedFromGeneratedName("tatil-2024.png"))
        assertNull(seedFromGeneratedName("IMG_20260804_121314.jpg"))
        assertNull(seedFromGeneratedName("rapor.pdf"))
        // 'local-' onekli ama zaman damgasi yok:
        assertNull(seedFromGeneratedName("local-notlar.png"))
    }
}
