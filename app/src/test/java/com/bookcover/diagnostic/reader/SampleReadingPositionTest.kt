package com.bookcover.diagnostic.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleReadingPositionTest {
    @Test fun aCharacterAnchorSurvivesFontReflowAndExactPageBoundaries() {
        val smallType = listOf(SamplePage(0, 120), SamplePage(120, 220))
        val largeType = listOf(SamplePage(0, 40), SamplePage(40, 80), SamplePage(80, 120), SamplePage(120, 220))
        assertEquals(0, pageForAnchor(smallType, 65))
        assertEquals(1, pageForAnchor(largeType, 65))
        assertEquals(2, pageForAnchor(largeType, 80))
        assertEquals(3, pageForAnchor(largeType, 120))
        assertEquals(0, pageForAnchor(largeType, -20))
        assertEquals(3, pageForAnchor(largeType, 900))
    }

    @Test fun progressUsesAllSampleSectionsAndClampsRestoredOffsets() {
        assertEquals(0, sampleProgressPercent(200, -20))
        assertEquals(50, sampleProgressPercent(200, 100))
        assertEquals(75, sampleProgressPercent(200, 150))
        assertEquals(100, sampleProgressPercent(200, 900))
    }

    @Test fun pagesContinueAcrossParagraphsWithoutLosingOrRepeatingCharacters() {
        val text = "One short paragraph.\n\nA second short paragraph.\n\nThe last paragraph."
        val pages = buildSamplePages(text) { SamplePageFit(30) }
        assertEquals("One short paragraph.\n\nA second", text.substring(pages.first().start, pages.first().end))
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        pages.zipWithNext().forEach { (first, second) -> assertEquals(first.end, second.start) }
    }

    @Test fun zeroHeightAndUnicodeBoundariesStillAdvanceWithoutSplittingCodePoints() {
        val text = "ab\uD83D\uDE00cd\n\nend"
        val pages = buildSamplePages(text) { SamplePageFit(3) }
        assertEquals("ab", text.substring(pages.first().start, pages.first().end))
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        val tinyPages = buildSamplePages("\uD83D\uDE00x") { SamplePageFit(0) }
        assertEquals(2, tinyPages.first().end)
        assertEquals(true, tinyPages.first().needsVerticalScroll)
        assertEquals(3, tinyPages.last().end)
    }
}
