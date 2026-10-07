package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCursorTest {
    private fun event(id: String) = OperationEvent(
        id = id,
        kind = "completed",
        status = "completed",
        backend = "codex-app",
        backendLabel = "Codex App",
        sessionId = "s1",
        cwd = "",
        model = "",
        summary = "",
        at = "",
    )

    @Test
    fun firstResponseOnlyInitializesCursor() {
        assertEquals(emptyList<OperationEvent>(), unseenOperationEvents(listOf(event("e1")), "", initialized = false))
    }

    @Test
    fun firstEventAfterEmptyBaselineIsDelivered() {
        assertEquals(listOf("e1"), unseenOperationEvents(listOf(event("e1")), "", initialized = true).map { it.id })
    }

    @Test
    fun eventsAfterKnownCursorAreDeliveredOldestFirst() {
        val events = listOf(event("e3"), event("e2"), event("e1"))
        assertEquals(listOf("e2", "e3"), unseenOperationEvents(events, "e1", initialized = true).map { it.id })
    }

    @Test
    fun missingCursorDoesNotReplayHistory() {
        assertEquals(emptyList<OperationEvent>(), unseenOperationEvents(listOf(event("e3"), event("e2")), "e1", initialized = true))
    }
}
