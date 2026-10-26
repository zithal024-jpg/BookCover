package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingTypographyTest {
    @Test fun textSizeUsesBoundedTenPercentStepsAndHandlesBadPreferences() {
        assertEquals(100, ReadingTextSize.percent(Double.NaN))
        assertEquals(80, ReadingTextSize.percent(-1.0))
        assertEquals(220, ReadingTextSize.percent(9.0))
        assertEquals(120, ReadingTextSize.percent(1.23))
        assertEquals(1.5, ReadingTextSize.scale(150), 0.0)
        assertEquals(100, ReadingTextSize.percent(1.0))
    }
    @Test fun supportedFontPreferencesRoundTripAndUnknownValueHasSafeFallback() {
        ReadingFont.entries.forEach { assertEquals(it, ReadingFont.fromId(it.id)) }
        assertEquals(ReadingFont.SERIF, ReadingFont.fromId("removed-font"))
        assertNotEquals(ReadingFont.SERIF.cssFamily, ReadingFont.SANS.cssFamily)
    }
}
