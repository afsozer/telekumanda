package com.agent.bridge

fun unseenOperationEvents(
    eventsNewestFirst: List<OperationEvent>,
    previousEventId: String,
    initialized: Boolean,
): List<OperationEvent> {
    if (!initialized) return emptyList()
    if (previousEventId.isBlank()) return eventsNewestFirst.asReversed()
    if (eventsNewestFirst.none { it.id == previousEventId }) return emptyList()
    return eventsNewestFirst.takeWhile { it.id != previousEventId }.asReversed()
}
