package com.agent.bridge

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.agent.bridge.ui2.components.alignSelectedLines
import com.agent.bridge.ui2.components.wrapSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditorLogicTest {

    @Test
    fun wrapsSelectedTextAndKeepsTheInnerSelection() {
        val value = TextFieldValue("Merhaba dünya", selection = TextRange(8, 13))

        val bold = wrapSelection(value, "**", "**")

        assertEquals("Merhaba **dünya**", bold.text)
        assertEquals(TextRange(10, 15), bold.selection)
    }

    @Test
    fun insertsEmptyFormattingPairAtCursor() {
        val value = TextFieldValue("abc", selection = TextRange(1))

        val underline = wrapSelection(value, "<u>", "</u>")

        assertEquals("a<u></u>bc", underline.text)
        assertEquals(TextRange(4), underline.selection)
    }

    @Test
    fun alignmentWrapsTheWholeSelectedLineBlock() {
        val value = TextFieldValue("bir\niki\nüç", selection = TextRange(5, 7))

        val centered = alignSelectedLines(value, "center")

        assertEquals("bir\n<div align=\"center\">\niki\n</div>\nüç", centered.text)
        assertEquals("iki", centered.text.substring(centered.selection.min, centered.selection.max))
    }
}
