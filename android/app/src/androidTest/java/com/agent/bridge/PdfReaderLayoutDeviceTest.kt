package com.agent.bridge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agent.bridge.ui2.components.ReaderFontButtons
import com.agent.bridge.ui2.theme.Ui2Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Okuma modu şeridinin YERLEŞİM sözleşmesi testi.
 *
 * Neden cihaz testi: Compose'un ölçüm kuralları ihlal edildiğinde hata ancak
 * gerçek bir ölçüm geçişinde atılır. JVM birim testi bunu göremez, `assemble`
 * de göremez — 11.58'de okuma moduna basınca uygulama çöktü:
 *
 *   IllegalStateException: Horizontally scrollable component was measured with
 *   an infinity maximum width constraints
 *
 * Sebep: yazı boyutu tuşları KENDİ `horizontalScroll`'unu taşıyordu ve zaten
 * yatay kaydırılan işlem şeridinin (PdfActions) içinde çiziliyordu. İç içe iki
 * yatay kaydırıcı → içteki sonsuz genişlik kısıtıyla ölçülüyor.
 *
 * Test o düzeni birebir kurar: geçmesi, tuşların yatay kaydırılan bir kabın
 * içinde ölçülebildiği anlamına gelir.
 */
@RunWith(AndroidJUnit4::class)
class PdfReaderLayoutDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun yaziBoyutuTuslariYatayKaydirilanSeritteOlculebilir() {
        compose.setContent {
            Ui2Theme {
                // PdfActions ile AYNI kap: fillMaxWidth + horizontalScroll.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ReaderFontButtons(fontSize = READER_FONT_DEFAULT) {}
                }
            }
        }
        compose.onNodeWithText("A−").assertIsDisplayed()
        compose.onNodeWithText("A+").assertIsDisplayed()
    }
}
