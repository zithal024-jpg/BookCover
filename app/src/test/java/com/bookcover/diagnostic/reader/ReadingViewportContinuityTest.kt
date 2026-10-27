package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingViewportContinuityTest {
    private data class Passage(val resource: String, val selector: String? = null, val quote: String? = null)
    private fun passage(id: String) = Passage("chapter.xhtml", "#$id", "A stable visible passage $id")

    @Test fun repeatedResizesKeepTheOriginalSemanticPassage() {
        val continuity = ReadingViewportContinuity<Passage>()
        val original = passage("original")
        continuity.remember(original, continuity.generation)
        repeat(20) {
            continuity.begin(passage("page-after-reflow"))
            continuity.remember(passage("intermediate"), continuity.generation)
            assertSame(original, continuity.pending)
        }
        assertSame(original, continuity.complete(continuity.generation))
        assertSame(original, continuity.anchor)
        assertNull(continuity.pending)
    }

    @Test fun explicitNavigationInvalidatesPendingRestorationAndLateDomQueries() {
        val continuity = ReadingViewportContinuity<Passage>()
        val oldCapture = continuity.generation
        continuity.remember(passage("old"), oldCapture)
        continuity.begin(null)
        val oldResize = continuity.generation
        continuity.navigated()
        continuity.remember(passage("late-old-query"), oldCapture)
        assertNull(continuity.complete(oldResize))
        assertNull(continuity.anchor)
        val target = passage("search-or-bookmark-target")
        continuity.remember(target, continuity.generation)
        continuity.begin(null)
        assertSame(target, continuity.pending)
    }

    @Test fun resourceLocatorsWorkForImageAndFixedPagesBeforeFirstTextCapture() {
        val continuity = ReadingViewportContinuity<Passage>()
        val image = Passage("image.xhtml")
        continuity.begin(image)
        assertSame(image, continuity.complete(continuity.generation))
        continuity.begin(null)
        assertSame(image, continuity.pending)
    }
}
