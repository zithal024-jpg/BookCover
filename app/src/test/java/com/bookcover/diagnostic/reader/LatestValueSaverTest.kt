package com.bookcover.diagnostic.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestValueSaverTest {
    @Test fun burstOfUpdatesSavesOnlyTheLast() = runTest {
        val saved = mutableListOf<String>()
        val saver = LatestValueSaver<String>(backgroundScope, 1_000) { saved += it }
        saver.submit("a"); advanceTimeBy(300); saver.submit("b"); advanceTimeBy(300); saver.submit("c")
        advanceTimeBy(999); runCurrent()
        assertEquals(emptyList<String>(), saved)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("c"), saved)
    }

    @Test fun updatesSeparatedByAQuietPeriodAreEachSaved() = runTest {
        val saved = mutableListOf<String>()
        val saver = LatestValueSaver<String>(backgroundScope, 1_000) { saved += it }
        saver.submit("a"); advanceTimeBy(1_500)
        saver.submit("b"); advanceTimeBy(1_500)
        assertEquals(listOf("a", "b"), saved)
    }

    @Test fun flushSavesThePendingValueAtOnceAndOnlyOnce() = runTest {
        val saved = mutableListOf<String>()
        val saver = LatestValueSaver<String>(backgroundScope, 1_000) { saved += it }
        saver.submit("a"); saver.flush(); runCurrent()
        assertEquals(listOf("a"), saved)
        advanceTimeBy(5_000)
        assertEquals(listOf("a"), saved)
    }

    @Test fun flushWithNothingPendingSavesNothing() = runTest {
        val saved = mutableListOf<String>()
        val saver = LatestValueSaver<String>(backgroundScope, 1_000) { saved += it }
        saver.flush(); advanceTimeBy(5_000)
        assertEquals(emptyList<String>(), saved)
    }

    @Test fun aSaveAlreadyRunningIsNotAbandoned() = runTest {
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val saver = LatestValueSaver<String>(backgroundScope, 1_000) { started += it; delay(500); finished += it }
        saver.submit("a"); advanceTimeBy(1_100)
        assertEquals(listOf("a"), started)
        saver.submit("b"); saver.flush(); advanceTimeBy(2_000)
        assertEquals(listOf("a", "b"), finished)
    }
}
