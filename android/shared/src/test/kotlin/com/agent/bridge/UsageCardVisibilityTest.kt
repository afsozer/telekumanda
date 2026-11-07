package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCardVisibilityTest {
    private fun group(source: String, name: String) =
        UsageGroup(name = name, description = "", buckets = emptyList(), source = source)

    private val groups = listOf(
        group("claude", "Claude — personal"),
        group("openrouter", "OpenRouter"),
        group("deepseek", "DeepSeek"),
    )

    @Test
    fun cardKeyMatchesTheLazyColumnKey() {
        // Ekrandaki key ile aynı olmak ZORUNDA; ayrışırsa gizleme yanlış kartı tutar.
        assertEquals("deepseek:DeepSeek", usageCardKey(group("deepseek", "DeepSeek")))
    }

    @Test
    fun hidingRemovesOnlyTheSelectedCard() {
        val hidden = toggleUsageCard(emptySet(), "openrouter:OpenRouter", visible = false)
        val shown = visibleUsageGroups(groups, hidden)
        assertEquals(listOf("Claude — personal", "DeepSeek"), shown.map { it.name })
    }

    @Test
    fun togglingBackRestoresTheCard() {
        var hidden = toggleUsageCard(emptySet(), "deepseek:DeepSeek", visible = false)
        assertFalse(isUsageCardVisible("deepseek:DeepSeek", hidden))
        hidden = toggleUsageCard(hidden, "deepseek:DeepSeek", visible = true)
        assertTrue(isUsageCardVisible("deepseek:DeepSeek", hidden))
        assertEquals(groups.size, visibleUsageGroups(groups, hidden).size)
    }

    @Test
    fun aBrandNewCardIsVisibleWithoutAnyMigration() {
        // visible_backends'te yaşanan hatanın regresyon testi: orada GÖRÜNENLER
        // saklandığı için sonradan eklenen backend eleniyordu. Gizlenenleri
        // sakladığımız sürece yeni kart hiçbir ek koda gerek olmadan gelmeli.
        val hidden = setOf("openrouter:OpenRouter")
        val withNewProvider = groups + group("yenisaglayici", "Yeni Sağlayıcı")
        val shown = visibleUsageGroups(withNewProvider, hidden)
        assertTrue(shown.any { it.name == "Yeni Sağlayıcı" })
    }

    @Test
    fun hiddenCountIgnoresCardsTheBridgeNoLongerSends() {
        // Anahtarı silinmiş bir sağlayıcı köprüden artık gelmiyor; onun eski
        // gizleme kaydı "1 kart gizli" rozetini tetiklememeli.
        val hidden = setOf("artikyok:Kaldırılmış Sağlayıcı")
        assertEquals(0, hiddenUsageCardCount(groups, hidden = hidden))
        assertEquals(groups.size, visibleUsageGroups(groups, hidden).size)
    }

    @Test
    fun hiddenCountTracksLiveCards() {
        // RunPod kartı 24.10.2026'da söküldü; eski gizleme kaydı sayılmaz.
        val hidden = setOf("deepseek:DeepSeek", "runpod")
        assertEquals(1, hiddenUsageCardCount(groups, hidden = hidden))
    }

    @Test
    fun togglesLabelUnnamedCardsWithoutCrashing() {
        val rows = usageCardToggles(listOf(group("", "")), hidden = emptySet())
        assertEquals("Adsız kart", rows.single().label)
    }
}
