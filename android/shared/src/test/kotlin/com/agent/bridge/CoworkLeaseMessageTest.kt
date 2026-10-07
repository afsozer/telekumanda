package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Çalışma alanı kilidi reddi (05.08.2026 canlı): aynı alanda Codex turu
// sürerken Claude'a prompt gönderildi, kullanıcı yalnız "HTTP 409" gördü.
class CoworkLeaseMessageTest {

    @Test
    fun kilidiTutaniSoyler() {
        val msg = coworkLeaseBusyMessage("codex-app", "workspace baska bir ajan tarafindan kullaniliyor: codex-app")
        assertTrue(msg, msg.startsWith("Codex "))
        assertTrue(msg, msg.contains("tekrar gönder"))
    }

    @Test
    fun sahipBilinmiyorsaKopruMetnineDuser() {
        assertEquals("HTTP 409", coworkLeaseBusyMessage("", "HTTP 409"))
    }

    @Test
    fun sahipVeMetinYoksaGenelIfade() {
        assertEquals("Çalışma alanı kullanımda", coworkLeaseBusyMessage("", ""))
    }

    @Test
    fun bilinmeyenSaglayiciClaudeSayilmaz_normalizasyonaBirakilir() {
        // normalizeCoworkProvider bilinmeyeni claude-app'e indiriyor; metin yine
        // de bir isim veriyor, "HTTP 409" demiyor.
        val msg = coworkLeaseBusyMessage("opencode2-app", "")
        assertTrue(msg, msg.startsWith("OpenCode "))
    }
}
