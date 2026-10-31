package com.bookcover.diagnostic.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReopenedPassageTest {
    private data class Report(val href: String, val progression: Double?, val highlight: String?)

    private val opened = Report("chapter.xhtml", 0.33, "paragraph 84")
    private val chapterStart = Report("chapter.xhtml", 0.0, "heading")

    @Test fun exactEchoKeepsTheOpenedPassageWhileTheScreenStillShowsTheChapterStart() {
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = opened,
            opened = opened,
            visible = chapterStart,
            anchor = chapterStart,
        )
        assertTrue(reportIsOpenedPassage(true, opened, opened))
        assertEquals(opened, saved)
        assertEquals("paragraph 84", saved.highlight)
        assertTrue(openedHoldContinues(true, opened.href, opened.highlight, saved.href, saved.highlight))
    }

    @Test fun progressionDifferenceKeepsTheVisibleOpenedQuote() {
        // Same chapter and quote, but not Readium's exact echo. The visible page still
        // shows the opened quote, so the chapter start is not what gets saved.
        val reported = opened.copy(progression = 0.3300001)
        val visible = opened.copy(progression = reported.progression)
        assertFalse(reported == opened)
        assertFalse(reportIsOpenedPassage(true, reported, opened))
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = reported,
            opened = opened,
            visible = visible,
            anchor = chapterStart,
        )
        assertEquals(visible, saved)
        assertEquals("paragraph 84", saved.highlight)
        assertTrue(openedHoldContinues(true, opened.href, opened.highlight, saved.href, saved.highlight))
    }

    @Test fun progressionDifferenceOnAJumpIsNotTheOpenedPlacement() {
        val jumped = opened.copy(progression = 0.3300001)
        assertFalse(reportIsOpenedPassage(true, jumped, opened))
        assertTrue(reportIsOpenedPassage(true, opened, opened))
    }
}
