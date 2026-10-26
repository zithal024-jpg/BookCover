package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingComfortTest {
    @Test fun freshDefaultsRespectBookAndKeepExistingMarginWidth() {
        val settings = ReadingComfort()
        assertTrue(settings.publisherStyles)
        assertEquals(1.0, settings.margins.factor, 0.0)
        assertEquals(1.5, settings.lineSpacing, 0.0)
        assertTrue(ReadingComfort.defaultPublisherStyles(false))
        assertFalse(ReadingComfort.defaultPublisherStyles(true))
    }
    @Test fun invalidStoredValuesUseSafeBoundedDefaults() {
        assertEquals(1.5, ReadingComfort.normalizedLineSpacing(Double.NaN), 0.0)
        assertEquals(1.5, ReadingComfort.normalizedLineSpacing(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(1.0, ReadingComfort.normalizedLineSpacing(-5.0), 0.0)
        assertEquals(2.0, ReadingComfort.normalizedLineSpacing(9.0), 0.0)
        assertEquals(1.8, ReadingComfort.normalizedLineSpacing(1.79999995), 0.0)
        assertEquals(ReadingMargins.STANDARD, ReadingMargins.fromId("removed"))
        assertEquals(ReadingAlignment.START, ReadingAlignment.fromId(null))
    }
    @Test fun settingIdentifiersRoundTripAndModesKeepTheirCustomValues() {
        ReadingMargins.entries.forEach { assertEquals(it, ReadingMargins.fromId(it.id)) }
        ReadingAlignment.entries.forEach { assertEquals(it, ReadingAlignment.fromId(it.id)) }
        val custom = ReadingComfort(false, 1.8, ReadingMargins.WIDE, ReadingAlignment.JUSTIFIED)
        assertEquals(custom, custom.copy(publisherStyles = true).copy(publisherStyles = false))
        assertTrue(ReadingMargins.NARROW.factor < ReadingMargins.STANDARD.factor)
        assertTrue(ReadingMargins.WIDE.factor > ReadingMargins.STANDARD.factor)
    }
}
