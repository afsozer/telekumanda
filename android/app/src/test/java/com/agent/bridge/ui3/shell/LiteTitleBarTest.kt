package com.agent.bridge.ui3.shell

import com.agent.bridge.AppTab
import com.agent.bridge.RemoteUiState
import com.agent.bridge.TabStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteTitleBarTest {
    @Test
    fun aktifOturumunCanliAdiniGosterir() {
        val state = RemoteUiState(
            openTabs = listOf(
                AppTab(
                    id = "tab-1",
                    backend = "claude-app",
                    sessionId = "session-1",
                    title = "agtest",
                    bridgeProfileId = "default",
                ),
            ),
            activeTabId = "tab-1",
            tabStatuses = mapOf("tab-1" to TabStatus(liveTitle = "Lite başlık düzeni")),
        )

        assertEquals("Lite başlık düzeni", liteBaslikMetni(state))
    }

    @Test
    fun bosSekmedeYeniOturumYazar() {
        val state = RemoteUiState(
            openTabs = listOf(
                AppTab(id = "tab-1", backend = "", title = "Yeni Sekme", bridgeProfileId = "default"),
            ),
            activeTabId = "tab-1",
        )

        assertEquals("Yeni oturum", liteBaslikMetni(state))
    }

    @Test
    fun saglikliVeBostaYesildir() {
        val durum = liteBaslikDurumu(RemoteUiState(healthOk = true, protocolCompatible = true))
        assertEquals(LiteBaslikDurumu.Duruyor, durum)
    }

    @Test
    fun ilkCevapVeyaOnayBeklerkenSaridir() {
        val ilkCevap = RemoteUiState(
            healthOk = true,
            protocolCompatible = true,
            running = true,
            awaitingFirstOutput = true,
        )
        val onay = RemoteUiState(
            healthOk = true,
            protocolCompatible = true,
            awaitingApproval = true,
        )
        assertEquals(LiteBaslikDurumu.CevapBekliyor, liteBaslikDurumu(ilkCevap))
        assertEquals(LiteBaslikDurumu.CevapBekliyor, liteBaslikDurumu(onay))
    }

    @Test
    fun cevapAkarkenMavidir() {
        val durum = liteBaslikDurumu(
            RemoteUiState(healthOk = true, protocolCompatible = true, running = true),
        )
        assertEquals(LiteBaslikDurumu.DevamEdiyor, durum)
    }

    @Test
    fun kopruYoksaEskiCalismaDurumununOnuneGecer() {
        val durum = liteBaslikDurumu(
            RemoteUiState(healthOk = false, protocolCompatible = true, running = true),
        )
        assertEquals(LiteBaslikDurumu.Ulasilamiyor, durum)
    }

    @Test
    fun uyumsuzProtokolUlasilamazSayilir() {
        val durum = liteBaslikDurumu(
            RemoteUiState(healthOk = true, protocolCompatible = false),
        )
        assertEquals(LiteBaslikDurumu.Ulasilamiyor, durum)
    }
}
