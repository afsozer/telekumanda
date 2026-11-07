package com.agent.bridge

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TextModelInfoTest {

    @Test
    fun modelAciklamasi_yalnizKatalogdaOlanIcinDoner() {
        assertNotNull(textModelInfo("deepseek/deepseek-flash", "deepseek/deepseek-flash"))
        assertNotNull(textModelInfo("deepseek-flash", "deepseek-flash"))
        // Haritada olmayan model icin aciklama UYDURULMAZ.
        assertNull(textModelInfo("yeni-saglayici/Gelecek-Model", "Gelecek-Model"))
    }
}
