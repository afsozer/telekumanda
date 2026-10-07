package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PromptQueueTest {
    private fun baseState(
        tabId: String = "tab-a",
        sessionId: String = "session-a",
    ) = RemoteUiState(
        backend = "cowork",
        activeBridgeProfileId = "bridge-a",
        activeTabId = tabId,
        cowork = CoworkUiState(provider = "claude-app"),
        claude = ClaudeUiState(sessionId = sessionId),
    )

    @Test
    fun photoOnlyPromptQueuesWithAttachmentAndSurvivesJsonRoundTrip() {
        val attachment = ChatAttachment(
            name = "delil.jpg",
            path = "C:/bridge/tmp/delil.jpg",
            localUri = "content://photos/1",
            isImage = true,
        )
        val queued = enqueueCurrentPrompt(
            baseState().copy(attachments = listOf(attachment)),
            id = "queue-1",
        )

        assertEquals("", queued.input)
        assertEquals(emptyList<ChatAttachment>(), queued.attachments)
        assertEquals("Yalnızca ekli prompt kuyruğa alınmalı", 1, queued.promptQueue.size)
        assertEquals(listOf(attachment), queued.promptQueue.single().attachments)

        val restored = PromptQueueJson.decode(PromptQueueJson.encode(queued.promptQueue))
        assertEquals(queued.promptQueue, restored)
    }

    @Test
    fun queueIsScopedToItsOwnTabAndRestoresAttachmentsForSend() {
        val attachment = ChatAttachment("foto.png", "C:/tmp/foto.png", isImage = true)
        val tabA = enqueueCurrentPrompt(
            baseState().copy(input = "Bunu incele", attachments = listOf(attachment)),
            id = "queue-a",
        )
        val tabB = tabA.copy(
            activeTabId = "tab-b",
            claude = tabA.claude.copy(sessionId = "session-b"),
        )

        assertEquals(emptyList<QueuedPrompt>(), tabB.activeQueuedPrompts())
        assertSame(tabB, restoreNextQueuedPrompt(tabB))

        val backOnA = tabB.copy(
            activeTabId = "tab-a",
            claude = tabB.claude.copy(sessionId = "session-a"),
        )
        val readyToSend = restoreNextQueuedPrompt(backOnA)

        assertEquals("Bunu incele", readyToSend.input)
        assertEquals(listOf(attachment), readyToSend.attachments)
        assertEquals(emptyList<QueuedPrompt>(), readyToSend.promptQueue)
    }

    @Test
    fun malformedPersistedQueueIsIgnored() {
        assertEquals(emptyList<QueuedPrompt>(), PromptQueueJson.decode("{broken"))
        assertEquals(emptyList<QueuedPrompt>(), PromptQueueJson.decode("""{"version":99,"items":[]}"""))
    }

    
    @Test
    fun closingTabDiscardsItsQueuedPrompts() {
        val queued = enqueueCurrentPrompt(baseState().copy(input = "bekleyen"), "queue-a")
        val withOpenTab = queued.copy(
            openTabs = listOf(
                AppTab(
                    id = "tab-a",
                    backend = "cowork",
                    provider = "claude-app",
                    sessionId = "session-a",
                    bridgeProfileId = "bridge-a",
                )
            )
        )

        assertSame(withOpenTab, discardClosedTabQueuedPrompts(withOpenTab))
        assertEquals(
            emptyList<QueuedPrompt>(),
            discardClosedTabQueuedPrompts(withOpenTab.copy(openTabs = emptyList())).promptQueue,
        )
    }
}
