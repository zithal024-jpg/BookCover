package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingPaceTest {
    private fun learned(): ReadingPace = ReadingPace().apply {
        observe(0.0, 0, true)
        observe(0.01, 15_000, true)
        observe(0.02, 30_000, true)
        observe(0.03, 45_000, true)
    }

    @Test fun waitsForEnoughMeasuredForwardReadingBeforeEstimating() {
        val pace = ReadingPace()
        assertNull(pace.remainingMinutes(0.0, 0.5))
        pace.observe(0.0, 0, true)
        pace.observe(0.01, 15_000, true)
        pace.observe(0.02, 30_000, true)
        assertNull(pace.remainingMinutes(0.02, 0.5))
        pace.observe(0.03, 45_000, true)
        assertEquals(2, pace.remainingMinutes(0.03, 0.1))
    }

    @Test fun threeRapidTurnsStillNeedFortyFiveSecondsOfAcceptedReading() {
        val pace = ReadingPace()
        for (index in 0..3) pace.observe(index * 0.01, index * 4_000L, true)
        assertNull(pace.remainingMinutes(0.03, 0.5))
    }

    @Test fun pauseExcludesTheInterruptedIntervalAndKeepsLearnedStatistics() {
        val pace = learned()
        val before = pace.remainingMinutes(0.03, 0.1)
        pace.pause()
        pace.observe(0.5, 150_000, true)
        assertEquals(before, pace.remainingMinutes(0.03, 0.1))
        pace.observe(0.51, 165_000, true)
        assertEquals(before, pace.remainingMinutes(0.03, 0.1))
    }

    @Test fun controlsOrBackgroundEligibilityBreakTheCandidateInterval() {
        val pace = ReadingPace()
        pace.observe(0.0, 0, true)
        pace.observe(0.01, 15_000, true)
        pace.observe(0.01, 20_000, false)
        pace.observe(0.02, 30_000, true)
        pace.observe(0.03, 45_000, true)
        assertNull(pace.remainingMinutes(0.03, 0.5))
    }

    @Test fun repeatedUnchangedLocationsDoNotResetTheReadingTimer() {
        val pace = ReadingPace()
        pace.observe(0.0, 0, true)
        pace.observe(0.0, 10_000, true)
        pace.observe(0.01, 15_000, true)
        pace.observe(0.01, 25_000, true)
        pace.observe(0.02, 30_000, true)
        pace.observe(0.03, 45_000, true)
        assertEquals(2, pace.remainingMinutes(0.03, 0.1))
    }

    @Test fun backwardsRapidLargeAndIdleMovesAreNotReadingSamples() {
        val pace = ReadingPace()
        pace.observe(0.5, 0, true)
        pace.observe(0.49, 15_000, true) // Backwards.
        pace.observe(0.5, 16_000, true) // Too fast.
        pace.observe(0.8, 31_000, true) // Large jump.
        pace.observe(0.81, 400_000, true) // Idle gap.
        pace.observe(0.82, 415_000, true) // Only one valid sample.
        assertNull(pace.remainingMinutes(0.82, 1.0))
    }

    @Test fun invalidAndReversedClockInputsAreRejected() {
        val pace = ReadingPace()
        pace.observe(Double.NaN, 0, true)
        pace.observe(0.0, 0, true)
        pace.observe(0.01, -1, true)
        pace.observe(0.02, 30_000, true)
        pace.observe(0.03, 20_000, true)
        assertNull(pace.remainingMinutes(0.03, 0.5))
        assertNull(pace.remainingMinutes(Double.NaN, 0.5))
        assertNull(pace.remainingMinutes(0.0, Double.POSITIVE_INFINITY))
        assertEquals(0, pace.remainingMinutes(0.5, 0.5))
    }

    @Test fun recentTwentyIntervalsReplaceStalePace() {
        val pace = learned()
        var point = 0.03
        var now = 45_000L
        repeat(20) { point += 0.01; now += 30_000; pace.observe(point, now, true) }
        assertEquals(5, pace.remainingMinutes(point, point + 0.09))
    }
}
