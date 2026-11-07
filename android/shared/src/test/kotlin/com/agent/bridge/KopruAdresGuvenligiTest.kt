package com.agent.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ayarlardaki düz HTTP uyarısının sınıflandırması: engelleme yok, yalnız uyarı.
class KopruAdresGuvenligiTest {
    @Test
    fun tailscaleVeCihazinKendisiUyarisiz() {
        listOf(
            "http://100.64.0.1:8787",
            "http://100.101.102.103:8787",
            "http://100.127.255.254",
            "http://desktop-qdmp3ic.tail1234.ts.net:8787",
            "http://DESKTOP.TAIL1234.TS.NET",
            "http://localhost:8787",
            "http://127.0.0.1:8787",
            "http://[::1]:8787",
        ).forEach { assertFalse(it, duzHttpUyarisiGerekli(it)) }
    }

    @Test
    fun tailscaleDisiDuzHttpUyarilir() {
        listOf(
            "http://192.168.1.20:8787",
            "http://10.0.0.5",
            // 100.64.0.0/10'un hemen dışı.
            "http://100.63.255.255:8787",
            "http://100.128.0.1:8787",
            // Kısa makine adı yerel DNS'ten de çözülebilir: Tailscale sayılmaz.
            "http://desktop-qdmp3ic:8787",
            "http://evil-ts.net:8787",
            "http://ts.net.example.com",
            "HTTP://192.168.1.20",
        ).forEach { assertTrue(it, duzHttpUyarisiGerekli(it)) }
    }

    @Test
    fun httpsBosVeBozukAdresUyarisiz() {
        assertFalse(duzHttpUyarisiGerekli("https://192.168.1.20:8787"))
        assertFalse(duzHttpUyarisiGerekli(""))
        assertFalse(duzHttpUyarisiGerekli("   "))
        assertFalse(duzHttpUyarisiGerekli("http://"))
        assertFalse(duzHttpUyarisiGerekli("http://bozuk adres"))
    }
}
