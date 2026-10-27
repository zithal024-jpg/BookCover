package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingKeysTest {
    private val policy = ReadingKeyPolicy()
    private val turns = mutableListOf<ReadingPageTurn>()
    private fun key(key: ReadingPageKey, down: Boolean = true, repeat: Boolean = false,
                    reading: Boolean = true, volume: Boolean = false, rtl: Boolean = false) =
        policy.handle(key, down, repeat, reading, volume, rtl, turns::add)

    @Test fun volumeRemainsNormalUntilEnabled() {
        assertFalse(key(ReadingPageKey.VOLUME_DOWN))
        assertFalse(key(ReadingPageKey.VOLUME_DOWN, down = false))
        assertTrue(turns.isEmpty())
        assertTrue(key(ReadingPageKey.VOLUME_DOWN, volume = true))
        assertTrue(key(ReadingPageKey.VOLUME_UP, volume = true))
        assertEquals(listOf(ReadingPageTurn.NEXT, ReadingPageTurn.PREVIOUS), turns)
    }

    @Test fun physicalArrowDirectionFollowsReadingDirection() {
        assertTrue(key(ReadingPageKey.LEFT))
        assertTrue(key(ReadingPageKey.LEFT, down = false))
        assertTrue(key(ReadingPageKey.RIGHT))
        assertTrue(key(ReadingPageKey.RIGHT, down = false))
        assertTrue(key(ReadingPageKey.LEFT, rtl = true))
        assertTrue(key(ReadingPageKey.RIGHT, rtl = true))
        assertEquals(listOf(ReadingPageTurn.PREVIOUS, ReadingPageTurn.NEXT,
            ReadingPageTurn.NEXT, ReadingPageTurn.PREVIOUS), turns)
    }

    @Test fun pageKeysFollowBookOrderAlsoInRtl() {
        key(ReadingPageKey.PAGE_DOWN, rtl = true)
        key(ReadingPageKey.PAGE_UP, rtl = true)
        assertEquals(listOf(ReadingPageTurn.NEXT, ReadingPageTurn.PREVIOUS), turns)
    }

    @Test fun heldKeyTurnsOnlyOnceAndReleaseStaysConsumedAfterOpeningControls() {
        assertTrue(key(ReadingPageKey.PAGE_DOWN))
        assertTrue(key(ReadingPageKey.PAGE_DOWN, repeat = true))
        assertTrue(key(ReadingPageKey.PAGE_DOWN, repeat = true, reading = false))
        assertTrue(key(ReadingPageKey.PAGE_DOWN, down = false, reading = false))
        assertEquals(listOf(ReadingPageTurn.NEXT), turns)
        assertTrue(key(ReadingPageKey.PAGE_DOWN))
        assertEquals(2, turns.size)
    }

    @Test fun controlsReflowAndModifiedKeysCanPassThroughWithoutCapturingARelease() {
        ReadingPageKey.entries.forEach {
            assertFalse(key(it, reading = false, volume = true))
            assertFalse(key(it, down = false, reading = false, volume = true))
        }
        assertTrue(turns.isEmpty())
    }

    @Test fun repeatFromAPressThatStartedElsewhereDoesNotNavigate() {
        assertFalse(key(ReadingPageKey.PAGE_DOWN, reading = false))
        assertFalse(key(ReadingPageKey.PAGE_DOWN, repeat = true))
        assertFalse(key(ReadingPageKey.PAGE_DOWN, down = false))
        assertTrue(turns.isEmpty())
    }
}
