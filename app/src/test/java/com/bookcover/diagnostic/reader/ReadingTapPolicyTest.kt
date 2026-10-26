package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingTapPolicyTest {
    private fun tap(x: Float, y: Float = 500f, width: Float = 1000f, enabled: Boolean = true,
        controls: Boolean = false, busy: Boolean = false, target: Boolean = false, rtl: Boolean = false) =
        readingTapAction(x, y, width, 1000f, 2f, enabled, controls, busy, target, rightToLeft = rtl)

    @Test fun rtlReversesOnlyTheNarrowNavigationEdgesOnBothLayouts() {
        for (width in listOf(1000f, 2200f)) {
            assertEquals(ReadingTapAction.NEXT, tap(1f, width = width, rtl = true))
            assertEquals(ReadingTapAction.PREVIOUS, tap(width - 1f, width = width, rtl = true))
            assertEquals(ReadingTapAction.PREVIOUS, tap(1f, width = width))
            assertEquals(ReadingTapAction.NEXT, tap(width - 1f, width = width))
            assertEquals(ReadingTapAction.NONE, tap(width * 0.15f, width = width, rtl = true))
            assertEquals(ReadingTapAction.CONTROLS, tap(width / 2, width = width, rtl = true))
        }
    }

    @Test fun rtlKeepsDisabledControlsTypographyTargetsAndInsetsProtected() {
        assertEquals(ReadingTapAction.NONE, tap(1f, rtl = true, enabled = false))
        assertEquals(ReadingTapAction.NONE, tap(999f, rtl = true, enabled = false))
        assertEquals(ReadingTapAction.NONE, tap(1f, rtl = true, controls = true))
        assertEquals(ReadingTapAction.NONE, tap(999f, rtl = true, busy = true))
        assertEquals(ReadingTapAction.NONE, tap(1f, rtl = true, target = true))
        assertEquals(ReadingTapAction.NONE, tap(999f, rtl = true, target = true))
        assertEquals(ReadingTapAction.NONE, tap(1f, y = 99f, rtl = true))
        assertEquals(ReadingTapAction.NONE, tap(999f, y = 901f, rtl = true))
    }

    @Test fun defaultOffEdgesAreInertAndCenterStillTogglesControls() {
        assertEquals(ReadingTapAction.NONE, tap(1f, enabled = false))
        assertEquals(ReadingTapAction.NONE, tap(999f, enabled = false))
        assertEquals(ReadingTapAction.CONTROLS, tap(500f, enabled = false))
    }
    @Test fun narrowEdgesAdaptToFoldedAndUnfoldedWidthWithDpCap() {
        assertEquals(ReadingTapAction.PREVIOUS, tap(50f))
        assertEquals(ReadingTapAction.NEXT, tap(950f))
        assertEquals(ReadingTapAction.NONE, tap(61f))
        assertEquals(ReadingTapAction.PREVIOUS, tap(63f, width = 2200f))
        assertEquals(ReadingTapAction.NONE, tap(65f, width = 2200f))
        assertEquals(ReadingTapAction.NONE, tap(60f))
    }
    @Test fun controlsTypographyImagesAndVerticalInsetsSuppressNavigation() {
        assertEquals(ReadingTapAction.NONE, tap(1f, controls = true))
        assertEquals(ReadingTapAction.NONE, tap(999f, busy = true))
        assertEquals(ReadingTapAction.NONE, tap(1f, target = true))
        assertEquals(ReadingTapAction.NONE, tap(1f, y = 99f))
        assertEquals(ReadingTapAction.NONE, tap(999f, y = 901f))
        assertEquals(ReadingTapAction.CONTROLS, tap(500f, controls = true))
    }
    @Test fun invalidOrOutsideCoordinatesNeverNavigate() {
        assertEquals(ReadingTapAction.NONE, tap(-1f))
        assertEquals(ReadingTapAction.NONE, tap(1001f))
        assertEquals(ReadingTapAction.NONE, tap(Float.NaN))
        assertEquals(ReadingTapAction.NONE, tap(0f, width = 0f))
    }
}
