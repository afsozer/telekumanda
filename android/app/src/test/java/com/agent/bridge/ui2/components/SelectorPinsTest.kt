package com.agent.bridge.ui2.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorPinsTest {

    private fun opts(vararg ids: String) = ids.map { SelectorOption(value = it, label = it) }

    private fun labels(list: List<SelectorOption<String>>) = list.map { it.value }

    @Test
    fun pinnedOptionsMoveToTheTop() {
        val sorted = sortByPins(opts("a", "b", "c", "d"), setOf("c"), { it })
        assertEquals(listOf("c", "a", "b", "d"), labels(sorted))
    }

    @Test
    fun bothGroupsKeepCatalogOrder() {
        // Sabitliler kendi aralarinda da katalog sirasini korur — sabitleme
        // sirasina gore dizilirse liste her yildizlamada karisir.
        val sorted = sortByPins(opts("a", "b", "c", "d"), setOf("d", "b"), { it })
        assertEquals(listOf("b", "d", "a", "c"), labels(sorted))
    }

    @Test
    fun emptyPinsLeaveTheListUntouched() {
        val original = opts("a", "b")
        assertEquals(labels(original), labels(sortByPins(original, emptySet(), { it })))
    }

    @Test
    fun unknownPinsAreIgnored() {
        // Katalogdan kalkan bir model sabitli kalabilir; listeyi bozmamali.
        val sorted = sortByPins(opts("a", "b"), setOf("silinmis-model"), { it })
        assertEquals(listOf("a", "b"), labels(sorted))
    }

    @Test
    fun togglingAddsThenRemoves() {
        val once = togglePin(emptySet(), "a")
        assertEquals(setOf("a"), once)
        assertEquals(emptySet<String>(), togglePin(once, "a"))
    }

    @Test
    fun togglingLeavesOtherPinsAlone() {
        assertEquals(setOf("a", "b"), togglePin(setOf("a"), "b"))
    }
}
