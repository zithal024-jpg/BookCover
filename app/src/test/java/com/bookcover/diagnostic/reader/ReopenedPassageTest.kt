package com.bookcover.diagnostic.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * The screen locator is always the chapter start. A near-echo is the same chapter and quote
 * (or the same stable marker) with some other field changed. The saved passage must stay the
 * opened locator. These assertions fail while the hold requires an exact locator match.
 */
class ReopenedPassageTest {
    private val opened = locator(
        href = "chapter.xhtml",
        progression = 0.33,
        highlight = "paragraph 84",
        selector = "#paragraph-84",
        title = "The Stair",
        position = 12,
        totalProgression = 0.42,
        before = "before the passage",
    )
    private val chapterStart = locator(
        href = "chapter.xhtml",
        progression = 0.0,
        highlight = "heading",
        selector = "#heading",
        title = "The Stair",
        position = 1,
        totalProgression = 0.01,
    )

    @Test fun nearEchoWithADifferentProgressionKeepsTheOpenedPassageWhileTheScreenShowsTheChapterStart() {
        val nearEcho = opened.copy(locations = opened.locations.copy(progression = 0.3300001))
        assertSavedOpenedPassage(nearEcho)
    }

    @Test fun nearEchoWithOtherFieldsChangedKeepsTheOpenedPassageWhileTheScreenShowsTheChapterStart() {
        val nearEcho = opened.copy(
            title = "The Stair (reset)",
            locations = opened.locations.copy(progression = 0.331, position = 1, totalProgression = 0.01),
            text = opened.text.copy(before = null, after = "words that were not in the opened locator"),
        )
        assertSavedOpenedPassage(nearEcho)
    }

    @Test fun stableMarkerMatchKeepsTheOpenedPassageWhileTheScreenShowsTheChapterStart() {
        val nearEcho = locator(
            href = "chapter.xhtml",
            progression = 0.05,
            highlight = null,
            selector = "#paragraph-84",
            position = 2,
        )
        val openedWithoutQuote = opened.copy(text = Locator.Text())
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = nearEcho,
            opened = openedWithoutQuote,
            visible = chapterStart,
            anchor = chapterStart,
        )
        assertEquals(openedWithoutQuote, saved)
        assertEquals("#paragraph-84", saved.locations["cssSelector"])
        assertTrue(reportIsOpenedPassage(true, nearEcho, openedWithoutQuote))
        assertTrue(openedHoldContinues(
            true, openedWithoutQuote.href, openedWithoutQuote.text.highlight, saved.href, saved.text.highlight))
    }

    @Test fun nearEchoJumpIsTheOpenedPlacement() {
        val nearEcho = opened.copy(locations = opened.locations.copy(progression = 0.329))
        assertTrue(reportIsOpenedPassage(true, nearEcho, opened))
    }

    @Test fun ambiguousQuoteAndMarkerStillKeepsTheOpenedPassageWhileTheScreenShowsTheChapterStart() {
        val nearEcho = opened.copy(
            locations = opened.locations.copy(
                progression = 0.2,
                otherLocations = mapOf("cssSelector" to "#somewhere-else"),
            ),
        )
        val logs = mutableListOf<String>()
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = nearEcho,
            opened = opened,
            visible = chapterStart,
            anchor = chapterStart,
            onAmbiguous = logs::add,
        )
        assertEquals(opened, saved)
        assertEquals("paragraph 84", saved.text.highlight)
        assertEquals(listOf("EPUB reopen hold is ambiguous; the quote and the stable marker disagree"), logs)
        assertTrue(reportIsOpenedPassage(true, nearEcho, opened))
        assertTrue(openedHoldContinues(true, opened.href, opened.text.highlight, saved.href, saved.text.highlight))
    }

    @Test fun fragmentMarkerKeepsTheOpenedPassageWhileTheScreenShowsTheChapterStart() {
        val openedAtFragment = locator(
            href = "chapter.xhtml#paragraph-84",
            progression = 0.33,
            highlight = null,
            position = 12,
        ).let { it.copy(locations = it.locations.copy(fragments = listOf("paragraph-84"))) }
        val nearEcho = openedAtFragment.copy(locations = openedAtFragment.locations.copy(progression = 0.02, position = 1))
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = nearEcho,
            opened = openedAtFragment,
            visible = chapterStart,
            anchor = chapterStart,
        )
        assertEquals(openedAtFragment, saved)
        assertTrue(reportIsOpenedPassage(true, nearEcho, openedAtFragment))
    }

    @Test fun leadingSlashHrefStillKeepsTheOpenedPassageWhileTheScreenShowsTheChapterStart() {
        val nearEcho = locator(
            href = "/chapter.xhtml",
            progression = 0.01,
            highlight = "paragraph 84",
            title = "reset",
            position = 1,
        )
        assertSavedOpenedPassage(nearEcho)
    }

    @Test fun chapterMatchWithoutQuoteOrMarkerIsLoggedAndTheOpenedPassageIsKept() {
        val openedBare = locator(href = "chapter.xhtml", progression = 0.33, highlight = null, position = 8)
        val nearEcho = locator(href = "chapter.xhtml", progression = 0.0, highlight = null, position = 1)
        val logs = mutableListOf<String>()
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = nearEcho,
            opened = openedBare,
            visible = chapterStart,
            anchor = chapterStart,
            onAmbiguous = logs::add,
        )
        assertEquals(openedBare, saved)
        assertEquals(listOf("EPUB reopen hold is ambiguous; the chapter matches but the report has no quote or stable marker"), logs)
    }

    @Test fun aDifferentQuoteIsNotTheOpenedPlacementAndTheChapterStartIsSaved() {
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = chapterStart,
            opened = opened,
            visible = chapterStart,
            anchor = chapterStart,
        )
        assertEquals(chapterStart, saved)
        assertEquals(false, reportIsOpenedPassage(true, chapterStart, opened))
        assertEquals(false, openedHoldContinues(true, opened.href, opened.text.highlight, saved.href, saved.text.highlight))
    }

    private fun assertSavedOpenedPassage(nearEcho: Locator) {
        val saved = passageReportedWhileHolding(
            holding = true,
            reported = nearEcho,
            opened = opened,
            visible = chapterStart,
            anchor = chapterStart,
        )
        assertEquals(opened, saved)
        assertEquals(0.33, saved.locations.progression)
        assertEquals("paragraph 84", saved.text.highlight)
        assertTrue(reportIsOpenedPassage(true, nearEcho, opened))
        assertTrue(openedHoldContinues(true, opened.href, opened.text.highlight, saved.href, saved.text.highlight))
    }

    private fun locator(
        href: String,
        progression: Double?,
        highlight: String?,
        selector: String? = null,
        title: String? = null,
        position: Int? = null,
        totalProgression: Double? = null,
        before: String? = null,
    ) = Locator(
        Url(href)!!,
        MediaType.XHTML,
        title = title,
        locations = Locator.Locations(
            progression = progression,
            position = position,
            totalProgression = totalProgression,
            otherLocations = if (selector == null) emptyMap() else mapOf("cssSelector" to selector),
        ),
        text = Locator.Text(before = before, highlight = highlight),
    )
}
