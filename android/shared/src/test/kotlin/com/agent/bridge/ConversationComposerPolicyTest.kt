package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationComposerPolicyTest {
    private val attachment = ChatAttachment(name = "ek.txt", path = "C:\\tmp\\ek.txt")
    private val draft = PendingComposerDraft(input = "mesaj", attachments = listOf(attachment))

    @Test
    fun codexAndOpencodeClearImmediatelyIncludingCowork() {
        assertTrue(shouldClearComposerOptimistically(RemoteUiState(backend = Backend.CODEX_APP.id)))
        assertTrue(shouldClearComposerOptimistically(RemoteUiState(backend = Backend.OPENCODE2_APP.id)))
        assertTrue(
            shouldClearComposerOptimistically(
                RemoteUiState(backend = Backend.COWORK.id, cowork = CoworkUiState(provider = Backend.CODEX_APP.id)),
            ),
        )
        assertTrue(
            shouldClearComposerOptimistically(
                RemoteUiState(backend = Backend.COWORK.id, cowork = CoworkUiState(provider = Backend.OPENCODE2_APP.id)),
            ),
        )
    }

    @Test
    fun claudeKeepsExistingResponseTimeBehavior() {
        assertFalse(shouldClearComposerOptimistically(RemoteUiState(backend = Backend.CLAUDE_APP.id)))
        assertFalse(
            shouldClearComposerOptimistically(
                RemoteUiState(backend = Backend.COWORK.id, cowork = CoworkUiState(provider = Backend.CLAUDE_APP.id)),
            ),
        )
    }

    @Test
    fun clearOnlyRemovesTheDraftThatWasActuallySent() {
        val unchanged = RemoteUiState(input = draft.input, attachments = draft.attachments)
        val cleared = clearComposerIfUnchanged(unchanged, draft)
        assertEquals("", cleared.input)
        assertTrue(cleared.attachments.isEmpty())

        val newlyTyped = unchanged.copy(input = "sonraki mesaj")
        assertEquals(newlyTyped, clearComposerIfUnchanged(newlyTyped, draft))
    }

    @Test
    fun failedSendRestoresDraftWithoutOverwritingNewContent() {
        val restored = restoreComposerIfUntouched(RemoteUiState(), draft)
        assertEquals(draft.input, restored.input)
        assertEquals(draft.attachments, restored.attachments)

        val newlyTyped = RemoteUiState(input = "sonraki mesaj")
        assertEquals(newlyTyped, restoreComposerIfUntouched(newlyTyped, draft))
    }
}
