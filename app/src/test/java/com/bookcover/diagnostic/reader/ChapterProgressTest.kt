package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ChapterProgressTest {
    private fun anchor(index: Int, resource: Int, fragment: String? = null, depth: Int = 0) =
        ChapterAnchor(index, resource, fragment, depth, "Chapter $index")

    @Test fun mapsResourceProgressToThePublicationAndChapterInterval() {
        val map = ChapterProgressMap(listOf(0.0, 0.2, 0.6), listOf("A", "B", "C"),
            listOf(anchor(0, 0), anchor(1, 1)))
        val result = map.resolve(2, 0.5)
        assertEquals("Chapter 1", result.title)
        assertEquals(1, result.currentTocIndex)
        assertEquals(0.8, result.globalPoint, 0.00001)
        assertEquals(0.75, result.progression!!, 0.00001)
        assertEquals(1.0, result.globalEnd!!, 0.0)
    }

    @Test fun nestedSameResourceFragmentsChooseTheMostRecentBoundary() {
        val map = ChapterProgressMap(listOf(0.0, 0.8), listOf("File", "End"),
            listOf(anchor(0, 0), anchor(1, 0, "a", 1), anchor(2, 0, "b", 1), anchor(3, 1)))
        val result = map.resolve(0, 0.5, mapOf(1 to 0.25, 2 to 0.75))
        assertEquals(1, result.currentTocIndex)
        assertEquals(0.5, result.progression!!, 0.00001)
        assertEquals(0.6, result.globalEnd!!, 0.00001)
    }

    @Test fun duplicateStartsPreferTheDeeperRowAndSkipAliasesForSectionEnd() {
        val map = ChapterProgressMap(listOf(0.0, 0.5), listOf("First", "Second"),
            listOf(anchor(0, 0), anchor(1, 0, depth = 1), anchor(2, 0), anchor(3, 1)))
        val result = map.resolve(0, 0.5)
        assertEquals(1, result.currentTocIndex)
        assertEquals(0.5, result.progression!!, 0.00001)
        assertEquals(0.5, result.globalEnd!!, 0.0)
    }

    @Test fun unresolvedCurrentResourceFragmentsUseExplicitResourceFallback() {
        val map = ChapterProgressMap(listOf(0.0, 0.5), listOf("File one", "File two"),
            listOf(anchor(0, 0), anchor(1, 0, "missing"), anchor(2, 1)))
        val result = map.resolve(0, 0.4)
        assertEquals("File one", result.title)
        assertNull(result.currentTocIndex)
        assertEquals(0.4, result.progression!!, 0.0)
        assertEquals(0.5, result.globalEnd!!, 0.0)
        assertNull(map.resolve(0, 0.4, mapOf(1 to Double.NaN)).currentTocIndex)
    }

    @Test fun explicitSubsectionSelectionOnlyBreaksTiesOnItsOwnPage() {
        val map = ChapterProgressMap(listOf(0.0), listOf("File"),
            listOf(anchor(0, 0), anchor(1, 0, "a", 1), anchor(2, 0, "b", 1), anchor(3, 0, "c", 1)))
        val fragments = mapOf(1 to 0.25, 2 to 0.25, 3 to 0.75)
        assertEquals(2, map.resolve(0, 0.25, fragments).currentTocIndex)
        assertEquals(1, map.resolve(0, 0.25, fragments, preferredTocIndex = 1).currentTocIndex)
        assertEquals(3, map.resolve(0, 0.80, fragments, preferredTocIndex = 1).currentTocIndex)
    }

    @Test fun unknownFutureFragmentKeepsKnownSelectionButDoesNotInventAnEnd() {
        val map = ChapterProgressMap(listOf(0.0, 0.5, 0.8), listOf("A", "B", "C"),
            listOf(anchor(0, 0), anchor(1, 1, "next"), anchor(2, 2)))
        val result = map.resolve(0, 0.5)
        assertEquals(0, result.currentTocIndex)
        assertNull(result.progression)
        assertNull(result.globalEnd)
    }

    @Test fun noTocFixedAndImageOnlyResourcesHaveHonestResourceProgress() {
        val map = ChapterProgressMap(listOf(0.0, 0.5), listOf("Image one", "Image two"), emptyList())
        val result = map.resolve(1, 0.0)
        assertEquals("Image two", result.title)
        assertNull(result.currentTocIndex)
        assertEquals(0.5, result.globalPoint, 0.0)
        assertEquals(0.0, result.progression!!, 0.0)
    }

    @Test fun finalSectionAndInvalidInputsDoNotProduceNaNProgress() {
        val map = ChapterProgressMap(listOf(0.0, 0.5), listOf("A", "B"), listOf(anchor(0, 0), anchor(1, 1)))
        assertEquals(1.0, map.resolve(1, 1.0).progression!!, 0.0)
        assertNull(map.resolve(9, 0.5).progression)
        assertNull(map.resolve(0, Double.NaN).globalEnd)
        assertNull(ChapterProgressMap(emptyList(), emptyList(), emptyList()).resolve(0, 0.0).currentTocIndex)
    }
}
