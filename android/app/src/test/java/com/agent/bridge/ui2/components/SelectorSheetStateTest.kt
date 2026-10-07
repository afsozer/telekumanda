package com.agent.bridge.ui2.components

import androidx.compose.material3.SheetValue
import androidx.compose.material3.ExperimentalMaterial3Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class SelectorSheetStateTest {

    @Test
    fun targetValueWinsWhileSheetIsMoving() {
        assertEquals(
            SheetValue.Expanded,
            selectorSheetVisibleValue(
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.Expanded,
            ),
        )
    }

    @Test
    fun currentVisibleValueIsUsedWhenTargetIsHidden() {
        assertEquals(
            SheetValue.PartiallyExpanded,
            selectorSheetVisibleValue(
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.Hidden,
            ),
        )
    }

    @Test
    fun fullyHiddenSheetHasNoRestorableValue() {
        assertNull(
            selectorSheetVisibleValue(
                currentValue = SheetValue.Hidden,
                targetValue = SheetValue.Hidden,
            ),
        )
    }

    @Test
    fun visibleSelectionNeedsNoScroll() {
        // Ilk satirlar yari acik sheet'te zaten gorunuyor; kaydirmak kisa
        // listelerde sheet'i gereksiz yere tam ekran acardi.
        assertNull(selectorScrollTargetIndex(0))
        assertNull(selectorScrollTargetIndex(2))
    }

    @Test
    fun deepSelectionScrollsOneRowAbove() {
        // Secilinin bir ustunu tepeye aliyoruz ki listenin ortasinda oldugu belli olsun.
        assertEquals(2, selectorScrollTargetIndex(3))
        assertEquals(41, selectorScrollTargetIndex(42))
    }

    @Test
    fun missingSelectionDoesNotScroll() {
        // Katalogdan kalkmis bir model secili kalabilir; liste basta durmali.
        assertNull(selectorScrollTargetIndex(-1))
    }

    @Test
    fun modelInfoHandlesBackBeforeTheSheetCanDismiss() {
        assertFalse(selectorSheetShouldDismissOnBackPress(infoVisible = true))
        assertTrue(selectorSheetShouldDismissOnBackPress(infoVisible = false))
    }
}
