package com.agent.bridge.ui3.chat

import com.agent.bridge.AppTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ui3SekmeJestiTest {

    private val sekmeler = listOf("a", "b", "c").map { AppTab(id = it, backend = "claude-app", title = it) }

    @Test
    fun `esik altinda karar yok`() {
        assertEquals(0, ui3SekmeJestYonu(60f, 84f))
        assertEquals(0, ui3SekmeJestYonu(-60f, 84f))
    }

    @Test
    fun `sola cekis sonraki sekme`() = assertEquals(1, ui3SekmeJestYonu(-120f, 84f))

    @Test
    fun `saga cekis onceki sekme`() = assertEquals(-1, ui3SekmeJestYonu(120f, 84f))

    @Test
    fun `komsu sekme yon boyunca gider`() {
        assertEquals("c", ui3KomsuSekme(sekmeler, "b", 1))
        assertEquals("a", ui3KomsuSekme(sekmeler, "b", -1))
    }

    @Test
    fun `uclarda sarmaz`() {
        assertNull(ui3KomsuSekme(sekmeler, "c", 1))
        assertNull(ui3KomsuSekme(sekmeler, "a", -1))
    }

    @Test
    fun `listede olmayan aktif sekme null doner`() {
        assertNull(ui3KomsuSekme(sekmeler, "yok", 1))
        assertNull(ui3KomsuSekme(emptyList(), "a", 1))
    }
}
