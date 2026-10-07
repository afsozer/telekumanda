package com.agent.bridge.ui2.hub

import com.agent.bridge.UsageGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunPodUsageCardTest {
    @Test
    fun `formats RunPod dollar values without locale drift`() {
        assertEquals("$12.34", formatRunPodUsd(12.344))
        assertEquals("$0.47", formatRunPodUsd(0.47))
        assertEquals("—", formatRunPodUsd(null))
    }

    @Test
    fun `estimates remaining runtime from live spend`() {
        assertEquals("~1 gün 2 sa", runPodRuntimeEstimate(12.43, 0.47))
        assertEquals("~30 dk", runPodRuntimeEstimate(0.25, 0.5))
        assertNull(runPodRuntimeEstimate(12.43, 0.0))
        assertNull(runPodRuntimeEstimate(null, 0.47))
    }

    @Test
    fun `places RunPod split directly after all Claude cards`() {
        val groups = listOf(
            UsageGroup("Claude kişisel", "", emptyList(), source = "claude"),
            UsageGroup("Claude iş", "", emptyList(), source = "claude"),
            UsageGroup("OpenRouter", "", emptyList(), source = "openrouter"),
            UsageGroup("Antigravity", "", emptyList(), source = "antigravity"),
            UsageGroup("Codex", "", emptyList(), source = "codex"),
        )

        val (before, after) = splitUsageGroupsAfterClaude(groups)

        assertEquals(listOf("Claude kişisel", "Claude iş"), before.map { it.name })
        // RunPod karti bu iki listenin arasina girer; dolayisiyla OpenRouter
        // dogrudan RunPod'un altinda cizilir.
        assertEquals(listOf("OpenRouter", "Antigravity", "Codex"), after.map { it.name })
    }
}
