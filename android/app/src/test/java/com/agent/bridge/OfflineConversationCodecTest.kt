package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineConversationCodecTest {
    @Test
    fun messagesRoundTripWithoutLosingStreamIdentity() {
        val source = listOf(
            ChatMessage("user", "Merhaba", "10:00", rowId = "u-1"),
            ChatMessage("thought", "tool: rg", thoughtIndex = 3, rowId = "t-3"),
        )

        assertEquals(source, OfflineConversationCodec.decode(OfflineConversationCodec.encode(source)))
    }

    @Test
    fun malformedCacheIsIgnored() {
        assertEquals(emptyList<ChatMessage>(), OfflineConversationCodec.decode("broken"))
    }
}
