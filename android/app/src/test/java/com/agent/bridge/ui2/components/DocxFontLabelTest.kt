package com.agent.bridge.ui2.components

import androidx.compose.ui.text.TextRange
import com.agent.bridge.DocxBlock
import com.agent.bridge.DocxCharStyle
import com.agent.bridge.DocxSpan
import org.junit.Assert.assertEquals
import org.junit.Test

// Üstteki "Font: …" göstergesi. Metin Android varsayılan fontuyla çizildiği için
// kullanıcının gerçek DOCX fontunu görebildiği TEK yer burasıdır.
class DocxFontLabelTest {

    private fun block(vararg spans: DocxSpan) = DocxBlock(
        id = "p0",
        kind = "paragraph",
        text = "Merhaba dunya",
        spans = spans.toList(),
        editable = true,
    )

    @Test
    fun reportsSingleFontOfSelection() {
        val blocks = listOf(block(DocxSpan(0, 13, DocxCharStyle(font = "Times New Roman"))))
        assertEquals("Times New Roman", activeFontLabel(blocks, "p0", TextRange(0, 13)))
    }

    @Test
    fun reportsMixedWhenSelectionSpansTwoFonts() {
        val blocks = listOf(
            block(
                DocxSpan(0, 8, DocxCharStyle(font = "Arial")),
                DocxSpan(8, 13, DocxCharStyle(font = "Calibri")),
            )
        )
        val label = activeFontLabel(blocks, "p0", TextRange(0, 13))
        assertEquals("karışık (Arial, Calibri)", label)
    }

    // Boş seçim (imleç): imlecin sağındaki run'ın fontu okunur.
    @Test
    fun caretReadsFontAtCursor() {
        val blocks = listOf(
            block(
                DocxSpan(0, 8, DocxCharStyle(font = "Arial")),
                DocxSpan(8, 13, DocxCharStyle(font = "Calibri")),
            )
        )
        assertEquals("Arial", activeFontLabel(blocks, "p0", TextRange(2, 2)))
        assertEquals("Calibri", activeFontLabel(blocks, "p0", TextRange(9, 9)))
    }

    @Test
    fun fallsBackWhenNoActiveBlockOrNoFontInfo() {
        val blocks = listOf(block(DocxSpan(0, 13, DocxCharStyle())))
        assertEquals("belge varsayılanı", activeFontLabel(blocks, "p0", TextRange(0, 13)))
        // Hiç seçili paragraf yokken (henüz dokunulmamış belge).
        assertEquals("belge varsayılanı", activeFontLabel(blocks, "", TextRange.Zero))
    }
}
