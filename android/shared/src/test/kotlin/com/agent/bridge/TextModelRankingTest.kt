package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// Model siralamasi 06.08.2026'da sadelesti: bir bulut saglayicisi kaldirilinca
// elle yazilmis kalite tablosu ve eleme listeleri OLU koda dondu (hicbir model
// adi eslesmiyordu). Kalan kural: gunluk iki model ustte, gerisi alfabetik,
// ELEME YOK — katalogda beliren yeni model sessizce kaybolmasin.
class TextModelRankingTest {

    @Test
    fun gunlukIkiModelUstte_gerisiAlfabetik() {
        val models = listOf(
            BackendModel("llamacpp/gemma4-12b-qat", "llamacpp/gemma4-12b-qat"),
            BackendModel("deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-pro"),
            BackendModel("opencode/big-pickle", "opencode/big-pickle"),
            BackendModel("deepseek/deepseek-flash", "deepseek/deepseek-flash"),
            BackendModel("llamaswap/qwen35-2b-title", "llamaswap/qwen35-2b-title"),
        )

        assertEquals(
            listOf(
                "deepseek/deepseek-flash",
                "deepseek/deepseek-v4-pro",
                "llamacpp/gemma4-12b-qat",
                "llamaswap/qwen35-2b-title",
                "opencode/big-pickle",
            ),
            rankOpencodeModels(models).map { it.id },
        )
    }

    // Regresyon: eski surum tanimadigi modelleri ELIYORDU. Katalog degisince
    // (saglayici eklendi/cikarildi) modeller sessizce kaybolmamali.
    @Test
    fun bilinmeyenModelElenmez() {
        val models = listOf(
            BackendModel("yeni-saglayici/Gelecek-Model", "yeni-saglayici/Gelecek-Model"),
            BackendModel("deepseek/deepseek-flash", "deepseek/deepseek-flash"),
        )
        val ranked = rankOpencodeModels(models)
        assertEquals(2, ranked.size)
        assertEquals("deepseek/deepseek-flash", ranked.first().id)
    }

    @Test
    fun modelAciklamasi_yalnizKatalogdaOlanIcinDoner() {
        assertNotNull(textModelInfo("deepseek/deepseek-flash", "deepseek/deepseek-flash"))
        assertNotNull(textModelInfo("deepseek-flash", "deepseek-flash"))
        // Haritada olmayan model icin aciklama UYDURULMAZ.
        assertNull(textModelInfo("yeni-saglayici/Gelecek-Model", "Gelecek-Model"))
    }
}
