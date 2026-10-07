package com.agent.bridge

import com.agent.bridge.ui2.components.RootArea
import com.agent.bridge.providerMonogram
import com.agent.bridge.ui2.chat.SessionTabUi
import com.agent.bridge.ui2.chat.activeTabScrollIndex
import org.junit.Assert.assertEquals
import org.junit.Test

// ui2 kabuğunun saf (compose'suz) parçaları: monogram eşlemesi + rota->kök çözümü.
class Ui2ShellTest {

    @Test
    fun providerMonogramKnownBackends() {
        assertEquals("C", providerMonogram("claude-app"))
        assertEquals("X", providerMonogram("codex-app"))
        assertEquals("O", providerMonogram("opencode2-app"))
        assertEquals("A", providerMonogram("agy"))
        assertEquals("W", providerMonogram("cowork"))
    }

    @Test
    fun providerMonogramUnknownFallsBack() {
        assertEquals("?", providerMonogram("yok-boyle-backend"))
    }

    @Test
    fun activeTabScrollIndexTracksActiveTabIdentity() {
        val tabs = listOf(
            SessionTabUi("one", "codex-app", "Bir"),
            SessionTabUi("two", "opencode2-app", "Iki"),
            SessionTabUi("three", "opencode2-app", "Uc"),
        )

        assertEquals(1, activeTabScrollIndex(tabs, "two"))
        assertEquals(-1, activeTabScrollIndex(tabs, "missing"))
    }

    @Test
    fun rootAreaForRouteResolvesRootsAndSubRoutes() {
        assertEquals(RootArea.Chat, RootArea.forRoute("chat"))
        assertEquals(RootArea.Hub, RootArea.forRoute("hub"))
        assertEquals(RootArea.Settings, RootArea.forRoute("settings"))
        // alt rota kök alana bağlanır (alt nav seçimi doğru kalsın)
        assertEquals(RootArea.Hub, RootArea.forRoute("hub/projects"))
        assertEquals(RootArea.Hub, RootArea.forRoute("hub/workspace"))
        // Operasyonlar KENDI kok alani (alt navda Sohbet ile Merkez arasinda),
        // rotasi "hub/" ile baslasa da Merkez'e bagli DEGIL. Enum sirasi bunu
        // garanti eder: Operations, Hub'dan once eslesir.
        assertEquals(RootArea.Operations, RootArea.forRoute("hub/operations"))
        assertEquals(RootArea.Hub, RootArea.forRoute("hub/files"))
        assertEquals(RootArea.Hub, RootArea.forRoute("hub/files/viewer"))
        assertEquals(RootArea.Settings, RootArea.forRoute("settings/baglanti"))
        assertEquals(RootArea.Settings, RootArea.forRoute("settings/mcp"))
        assertEquals(RootArea.Settings, RootArea.forRoute("settings/guncelleme"))
    }

    // Kullanici karari: Operasyonlar Sohbet ile Merkez ARASINDA. Enum sirasi hem
    // alt navi hem geniz ekran rail'ini besliyor, yani sira = ekrandaki diziliş.
    @Test
    fun rootAreaOrderPutsOperationsBetweenChatAndHub() {
        assertEquals(
            listOf(RootArea.Chat, RootArea.Operations, RootArea.Hub, RootArea.Settings),
            RootArea.values().toList(),
        )
    }

    // Nav etiketi dar hucreye sigmali: 1280px ekranda 5 tusla hucre ~256px ve
    // "Operasyonlar" ~272px suruyordu (son harf alt satira dusuyordu). Ekran
    // basligi tam kalir, kisaltma yalniz navigasyonda.
    @Test
    fun operationsNavLabelIsShorterThanScreenTitle() {
        assertEquals("Operasyonlar", RootArea.Operations.title)
        assertEquals("Operasyon", RootArea.Operations.navTitle)
        // Diger alanlar zaten kisa: nav etiketi basliktan farkli olmamali.
        for (area in RootArea.values().filterNot { it == RootArea.Operations }) {
            assertEquals(area.title, area.navTitle)
        }
    }

    @Test
    fun rootAreaForRouteFallsBackToChat() {
        assertEquals(RootArea.Chat, RootArea.forRoute(null))
        assertEquals(RootArea.Chat, RootArea.forRoute("bilinmeyen"))
    }
}
