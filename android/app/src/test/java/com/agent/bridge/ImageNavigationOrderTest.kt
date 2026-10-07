package com.agent.bridge

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ImageNavigationOrderTest {
    @Test fun `shuffle visits every file once and restores original order at current file`() {
        val original = (1..20).map { "$it.png" }
        val order = ImageNavigationOrder().apply { reset(original) }
        order.toggle("8.png", Random(42))
        assertTrue(order.shuffled)
        assertEquals("8.png", order.paths.first())
        assertEquals(original.size, order.paths.size)
        assertEquals(original.toSet(), order.paths.toSet())
        assertNotEquals(listOf("8.png") + original.filterNot { it == "8.png" }, order.paths)
        val visited = order.paths.take(5)
        assertEquals(visited.reversed(), (4 downTo 0).map { order.paths[it] })
        val current = visited.last()
        order.toggle(current)
        assertFalse(order.shuffled)
        assertEquals(original, order.paths)
        assertEquals(original.indexOf(current), order.paths.indexOf(current))
    }

    @Test fun `deleting while shuffled cannot resurrect a file on restore`() {
        val order = ImageNavigationOrder().apply { reset(listOf("a", "b", "c", "d")) }
        order.toggle("b", Random(7))
        val next = order.paths[1]
        order.remove("b")
        assertEquals(next, order.paths.first())
        order.toggle(next)
        assertEquals(listOf("a", "c", "d"), order.paths)
    }

    @Test fun `a new gallery resets shuffle and empty or single galleries are safe`() {
        val order = ImageNavigationOrder()
        order.toggle("missing")
        assertFalse(order.shuffled)
        order.reset(listOf("a"))
        order.toggle("a")
        assertEquals(listOf("a"), order.paths)
        assertFalse(order.shuffled)
        order.reset(listOf("a", "b"))
        order.toggle("a")
        assertTrue(order.shuffled)
        order.reset(listOf("new", "gallery"))
        assertFalse(order.shuffled)
        assertEquals(listOf("new", "gallery"), order.paths)
    }
}
