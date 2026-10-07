package com.agent.bridge.ui3.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class Ui3YeniOturumTest {
    @Test
    fun `lite saglayici kartlari kisa aciklama verir`() {
        assertEquals("Uzun sohbetler ve genel görevler", liteSaglayiciAciklamasi("claude-app"))
        assertEquals("Kodlama ve proje işleri", liteSaglayiciAciklamasi("codex-app"))
        assertEquals("Hızlı, genel amaçlı yardım", liteSaglayiciAciklamasi("agy"))
    }

    @Test
    fun `bilinmeyen saglayici guvenli aciklamaya duser`() {
        assertEquals("Yeni bir oturum başlat", liteSaglayiciAciklamasi("gelecek-backend"))
    }
}
