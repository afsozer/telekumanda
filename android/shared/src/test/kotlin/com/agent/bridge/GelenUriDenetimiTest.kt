package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Dışa açık MainActivity'ye gelen VIEW/SEND URI'lerinin süzgeci.
class GelenUriDenetimiTest {
    private val kendi = "com.agent.bridge.fileprovider"

    @Test
    fun yalnizBaskaSaglayicininContentUrisiKabul() {
        assertEquals(GelenUriKarari.KABUL, gelenUriKarari("content", "com.whatsapp.provider.media", kendi))
        assertEquals(GelenUriKarari.KABUL, gelenUriKarari("content", "media", kendi))
        assertEquals(GelenUriKarari.RED_DOSYA_SEMASI, gelenUriKarari("file", null, kendi))
        assertEquals(GelenUriKarari.RED_DOSYA_SEMASI, gelenUriKarari("FILE", "", kendi))
        assertEquals(GelenUriKarari.RED_KENDI_SAGLAYICI, gelenUriKarari("content", kendi, kendi))
        assertEquals(GelenUriKarari.RED_DESTEKLENMEYEN_SEMA, gelenUriKarari(null, null, kendi))
        assertEquals(GelenUriKarari.RED_DESTEKLENMEYEN_SEMA, gelenUriKarari("https", "x.com", kendi))
        assertEquals(GelenUriKarari.RED_DESTEKLENMEYEN_SEMA, gelenUriKarari("android.resource", "x", kendi))
    }

    // Lite'ın paket adı farklı: tam sürümün sağlayıcısı Lite için "kendi" değildir.
    @Test
    fun kendiSaglayiciPaketAdinaGore() {
        assertEquals(
            GelenUriKarari.RED_KENDI_SAGLAYICI,
            gelenUriKarari("content", "com.agent.bridge.lite.fileprovider", "com.agent.bridge.lite.fileprovider"),
        )
        assertEquals(
            GelenUriKarari.KABUL,
            gelenUriKarari("content", "com.agent.bridge.fileprovider", "com.agent.bridge.lite.fileprovider"),
        )
    }

    @Test
    fun ozelDizinAltindakiYolReddedilir() {
        val kokler = listOf(
            "/data/user/0/com.agent.bridge",
            "/data/user/0/com.agent.bridge/cache",
            "/data/user_de/0/com.agent.bridge",
        )
        assertTrue(yolOzelDizinAltinda("/data/user/0/com.agent.bridge/shared_prefs/settings.xml", kokler))
        assertTrue(yolOzelDizinAltinda("/data/user/0/com.agent.bridge", kokler))
        assertTrue(yolOzelDizinAltinda("/data/user_de/0/com.agent.bridge/files/x", kokler))
        // `..` ile kökün dışına kaçıp geri dönen yol da yakalanır.
        assertTrue(yolOzelDizinAltinda("/sdcard/../data/user/0/com.agent.bridge/files/a.md", kokler))
    }

    @Test
    fun ozelDizinDisiYolKabul() {
        val kokler = listOf("/data/user/0/com.agent.bridge")
        assertFalse(yolOzelDizinAltinda("/storage/emulated/0/Download/dilekce.udf", kokler))
        // Bileşen sınırı: önek benzerliği kapsama sayılmaz.
        assertFalse(yolOzelDizinAltinda("/data/user/0/com.agent.bridge.lite/files/a.md", kokler))
        assertFalse(yolOzelDizinAltinda("/data/user/0/com.agent.bridgex", kokler))
        assertFalse(yolOzelDizinAltinda("", kokler))
        assertFalse(yolOzelDizinAltinda("/data/user/0/com.agent.bridge/x", emptyList()))
    }
}
