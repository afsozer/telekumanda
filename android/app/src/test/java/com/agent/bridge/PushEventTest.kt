package com.agent.bridge

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PushEventTest {
    @Test fun reminderRetainsReceiptAndNoteDestination() {
        val events = parsePushEvents(JSONArray("""[{"deliveryId":"device:event","kind":"reminder","noteId":"case/note","title":"Duruşma","summary":"Saat 10.00"}]"""))
        assertEquals(1, events.size)
        assertEquals("device:event", events.single().deliveryId)
        assertEquals("case/note", events.single().noteId)
        assertEquals("Duruşma", events.single().title)
    }

    @Test fun startedCarriesCapsuleFields() {
        // Kapsul olayi: gölgeye satır atmaz ama sözleşmede olmalı; `startedAt`
        // ISO metin olarak taşınır (bridge/server.mjs operationPushBody).
        val events = parsePushEvents(JSONArray("""[{"deliveryId":"d1","kind":"started","backend":"claude-app","backendLabel":"Claude App","sessionId":"s1","title":"Tur","startedAt":"2026-09-16T18:00:00.000Z"}]"""))
        val event = events.single()
        assertEquals("started", event.kind)
        assertEquals("2026-09-16T18:00:00.000Z", event.startedAt)
        assertEquals("s1", event.sessionId)
        assertEquals("Tur", event.title)
    }

    @Test fun missingReceiptOrUnknownKindIsNotAcknowledged() {
        assertEquals(emptyList<PushEvent>(), parsePushEvents(JSONArray("""[{"kind":"reminder"},{"deliveryId":"1","kind":"unknown"},null]""")))
        assertEquals(emptyList<PushEvent>(), parsePushEvents(null))
    }
}
