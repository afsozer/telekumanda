package com.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiModelsTest {
    @Test fun toolSummaryClassifierSeparatesThoughtsFromTools() {
        assertTrue(isToolSummary("Read README.md"))
        assertTrue(isToolSummary("$ gradlew test"))
        assertTrue(isToolSummary("PowerShell Get-ChildItem"))
        assertFalse(isToolSummary("Yaklaşımı değerlendiriyorum"))
        assertFalse(isToolSummary("🧠"))
    }

    @Test fun slashCommandReplacesPartialInput() {
        assertEquals("/model ", applySlashCommandToInput("/mo", "model"))
        assertEquals("/compact ", applySlashCommandToInput("eski", "/compact"))
    }

    @Test fun taskNotificationParsesIntoCompactUiModel() {
        val parsed = parseTaskNotification(
            """
            <task-notification>
            <task-id>a7afc1589d74185a4</task-id>
            <tool-use-id>toolu_01Gad8</tool-use-id>
            <output-file>C:\Temp\tasks\a7af.output</output-file>
            <status>completed</status>
            <summary>Agent "Baslik modeli ayari" finished</summary>
            <note>Arka plan görevi canlı alt ajan kalmadan tamamlandı.</note>
            </task-notification>
            """.trimIndent(),
        )

        requireNotNull(parsed)
        assertEquals("Baslik modeli ayari", parsed.compactSummary())
        assertEquals("Tamamlandı", parsed.statusLabel())
        assertEquals("C:\\Temp\\tasks\\a7af.output", parsed.outputFile)
        assertEquals("toolu_01Gad8", parsed.toolUseId)
    }

    @Test fun ordinaryXmlLikeTextIsNotTaskNotification() {
        assertEquals(null, parseTaskNotification("<status>completed</status>"))
        assertEquals(null, parseTaskNotification("önce <task-notification>sonra"))
    }

    @Test fun taskNotificationMapsFailureAndKeepsUnknownSummary() {
        val failed = parseTaskNotification(
            "<task-notification><status>failed</status><summary>Derleme durdu</summary></task-notification>"
        )

        requireNotNull(failed)
        assertEquals("Derleme durdu", failed.compactSummary())
        assertEquals("Başarısız", failed.statusLabel())
    }
}
