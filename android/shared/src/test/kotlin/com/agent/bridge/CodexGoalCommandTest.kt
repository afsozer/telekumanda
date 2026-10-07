package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexGoalCommandTest {

    @Test
    fun nonGoalInputIsNotCaptured() {
        assertNull(CodexGoalCommand.parse("merhaba"))
        assertNull(CodexGoalCommand.parse("/plan"))
        // "/goal" onekiyle baslamayan; alt-dize eslesmesine dusmemeli
        assertNull(CodexGoalCommand.parse("bir /goal degil"))
        assertNull(CodexGoalCommand.parse("/goalsomething"))
    }

    @Test
    fun bareGoalIsShow() {
        assertTrue(CodexGoalCommand.parse("/goal") is CodexGoalCommand.Show)
        assertTrue(CodexGoalCommand.parse("  /goal  ") is CodexGoalCommand.Show)
        // Yerel-bagimsiz lowercase: buyuk harfli girdi de yakalanir (tr-I tuzagi yok)
        assertTrue(CodexGoalCommand.parse("/GOAL") is CodexGoalCommand.Show)
    }

    @Test
    fun clearVariants() {
        assertTrue(CodexGoalCommand.parse("/goal temizle") is CodexGoalCommand.Clear)
        assertTrue(CodexGoalCommand.parse("/goal clear") is CodexGoalCommand.Clear)
        assertTrue(CodexGoalCommand.parse("/goal CLEAR") is CodexGoalCommand.Clear)
    }

    @Test
    fun setKeepsObjectiveText() {
        val cmd = CodexGoalCommand.parse("/goal testleri geçir ve raporla")
        assertEquals(CodexGoalCommand.Set("testleri geçir ve raporla"), cmd)
    }

    @Test
    fun summaryLine() {
        assertEquals("Hedef yok", (null as CodexGoal?).summaryLine())
        val goal = CodexGoal(threadId = "t", objective = "Bitir", status = "active", tokensUsed = 12)
        assertEquals("Hedef: Bitir (active, 12 token)", goal.summaryLine())
    }

    // Pill'i kapatma yalniz DURMUS hedefte sunulur: calisan hedefi kapatmak onu
    // app-server'dan silmek olurdu.
    @Test
    fun onlyActiveGoalIsLive() {
        fun goal(status: String) = CodexGoal(threadId = "t", objective = "x", status = status)
        assertTrue(goal("active").live)
        assertTrue(goal("ACTIVE").live)
        assertTrue(!goal("complete").live)
        assertTrue(!goal("paused").live)
        assertTrue(!goal("").live)
    }

    @Test
    fun durationIsHumanReadable() {
        assertEquals("0 sn", formatGoalDuration(0))
        assertEquals("30 sn", formatGoalDuration(30))
        assertEquals("45 dk", formatGoalDuration(45 * 60))
        assertEquals("1 sa 20 dk", formatGoalDuration(4800))
    }

    @Test
    fun detailTextCoversStatusTokensAndDuration() {
        val goal = CodexGoal(
            threadId = "t", objective = "Bitir", status = "complete",
            tokensUsed = 895_725, timeUsedSeconds = 4799,
        )
        assertEquals(
            "Durum: tamamlandı\nHarcanan: 895725 token\nSüre: 1 sa 19 dk",
            goal.detailText(),
        )
        // Butce varsa satira eklenir; sure 0 iken sure satiri hic yazilmaz.
        val budgeted = CodexGoal(threadId = "t", objective = "x", status = "active", tokenBudget = 500, tokensUsed = 10)
        assertEquals("Durum: çalışıyor\nHarcanan: 10 token / 500 bütçe", budgeted.detailText())
    }
}
