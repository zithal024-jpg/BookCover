package com.bookcover.diagnostic.reader

import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPaceTest {
    /**
     * Exact reference copy of the baseline ReadingPace estimator at origin/main (af0695b2)
     * used strictly to verify side-by-side improvements and regressions on identical inputs.
     */
    private class LegacyReadingPace {
        private data class Sample(val distance: Double, val elapsedMillis: Long)
        private val samples = ArrayDeque<Sample>()
        private var previousPoint: Double? = null
        private var previousTime: Long? = null

        fun pause() {
            previousPoint = null
            previousTime = null
        }

        fun observe(point: Double, nowMillis: Long, eligible: Boolean) {
            if (!eligible || !point.isFinite() || point < 0.0 || point > 1.0 || nowMillis < 0L) {
                pause()
                return
            }
            val beforePoint = previousPoint
            val beforeTime = previousTime
            if (beforePoint != null && point == beforePoint) return
            previousPoint = point
            previousTime = nowMillis
            if (beforePoint == null || beforeTime == null) return

            val elapsed = nowMillis - beforeTime
            val distance = point - beforePoint
            if (elapsed in 3_000L..300_000L && distance > 0.0 && distance <= 0.10) {
                samples.addLast(Sample(distance, elapsed))
                while (samples.size > 20) samples.removeFirst()
            }
        }

        fun remainingMinutes(point: Double, chapterEnd: Double): Int? {
            if (!point.isFinite() || !chapterEnd.isFinite()) return null
            val remainingDistance = (chapterEnd - point).coerceAtLeast(0.0)
            if (remainingDistance == 0.0) return 0
            if (samples.size < 3) return null
            val totalElapsed = samples.sumOf { it.elapsedMillis }
            val totalDistance = samples.sumOf { it.distance }
            if (totalElapsed < 45_000L || totalDistance <= 0.0) return null
            val distancePerMinute = totalDistance / (totalElapsed / 60_000.0)
            if (!distancePerMinute.isFinite() || distancePerMinute <= 0.0) return null
            val minutes = remainingDistance / distancePerMinute
            if (!minutes.isFinite() || minutes <= 0.0 || minutes > Int.MAX_VALUE) return null
            return ceil(minutes).toInt().coerceAtLeast(1)
        }
    }

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

    @Test fun ineligibleMovesBreakTheCandidateIntervalButRepeatsOfThePageDoNot() {
        val pace = ReadingPace()
        pace.observe(0.0, 0, true)
        pace.observe(0.01, 15_000, true)
        pace.observe(0.015, 20_000, false) // Moved while ineligible: that interval is discarded.
        pace.observe(0.02, 30_000, true)
        pace.observe(0.03, 45_000, true)
        assertNull(pace.remainingMinutes(0.03, 0.5))

        val held = ReadingPace()
        held.observe(0.0, 0, true)
        held.observe(0.01, 15_000, true)
        held.observe(0.01, 20_000, false) // The current page re-reported while controls are shown.
        assertTrue(held.observe(0.02, 30_000, true))
        held.observe(0.03, 45_000, true)
        assertEquals(10, held.remainingMinutes(0.03, 0.43)) // 0.01 per 15s = 0.04/min.
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

    @Test fun steadyReadingAtMultipleKnownSpeedsMatchesIndependentGroundTruth() {
        // Slow pace: 0.005 per 30s = 0.01/min (1% of book per minute).
        // Remaining 0.10 of book -> exact ground truth = 0.10 / 0.01 = 10.0 min -> ceil = 10.
        val slow = ReadingPace()
        for (step in 0..4) slow.observe(step * 0.005, step * 30_000L, true)
        assertEquals(10, slow.remainingMinutes(0.02, 0.12))

        // Medium pace: 0.005 per 15s = 0.02/min (2% of book per minute).
        // Remaining 0.10 of book -> exact ground truth = 0.10 / 0.02 = 5.0 min -> ceil = 5.
        val medium = ReadingPace()
        for (step in 0..4) medium.observe(step * 0.005, step * 15_000L, true)
        assertEquals(5, medium.remainingMinutes(0.02, 0.12))

        // Fast pace: 0.01 per 15s = 0.04/min (4% of book per minute).
        // Remaining 0.10 of book -> exact ground truth = 0.10 / 0.04 = 2.5 min -> ceil = 3.
        val fast = ReadingPace()
        for (step in 0..4) fast.observe(step * 0.01, step * 15_000L, true)
        assertEquals(3, fast.remainingMinutes(0.04, 0.14))

        // Natural cadence jitter (+/-20% around 15s per 0.005 page: 12s, 18s, 14s, 16s, 15s, 15s).
        // Mean rate ~ 0.0202/min -> remaining 0.10 -> 4.95 min -> ceil = 5 (within +/-1 min of 5).
        val jittered = ReadingPace()
        val durations = listOf(12_000L, 18_000L, 14_000L, 16_000L, 15_000L, 15_000L)
        var point = 0.0
        var now = 0L
        jittered.observe(point, now, true)
        for (dt in durations) {
            point += 0.005
            now += dt
            jittered.observe(point, now, true)
        }
        val jitterEstimate = jittered.remainingMinutes(point, point + 0.10)
        assertNotNull(jitterEstimate)
        assertTrue("Expected 5 +/- 1 min, got $jitterEstimate", abs(jitterEstimate!! - 5) <= 1)
    }

    @Test fun floatingPointMicroDriftNeitherResetsTimerNorCreatesFakeSlowSamples() {
        val pace = ReadingPace()
        val legacy = LegacyReadingPace()

        // Reader reads 4 pages at 15s each (0.005 per 15s = 0.02/min -> 5 min for 0.10 remaining),
        // with 1e-7 locator jitter arriving 2s before the first three page turns (at 13s, 28s, 43s).
        val events = listOf(
            0.1000000 to 0L,
            0.1000001 to 13_000L,
            0.1050000 to 15_000L,
            0.1050001 to 28_000L,
            0.1100000 to 30_000L,
            0.1100001 to 43_000L,
            0.1150000 to 45_000L,
            0.1200000 to 60_000L,
        )
        for ((pt, t) in events) {
            pace.observe(pt, t, true)
            legacy.observe(pt, t, true)
        }

        // Calibrated estimator ignores <= 1e-6 jitter and learns the true 5-minute pace.
        assertEquals(5, pace.remainingMinutes(0.120, 0.220))
        // Legacy estimator recorded the 13s 1e-7 jitter steps and dropped the 2s real page turns (< 3s),
        // inflating the 5-minute estimate to 18 minutes.
        assertEquals(18, legacy.remainingMinutes(0.120, 0.220))

        // If only 1e-7 jitter occurs over 45s without any real page turn, calibrated stays null.
        val driftOnly = ReadingPace()
        val legacyDriftOnly = LegacyReadingPace()
        for (step in 0..3) {
            val pt = 0.2 + step * 1e-7
            val t = step * 15_000L
            driftOnly.observe(pt, t, true)
            legacyDriftOnly.observe(pt, t, true)
        }
        assertNull(driftOnly.remainingMinutes(0.2000003, 0.3000003))
        // Legacy produced an absurd 250,000-minute estimate from 3e-7 progress over 45s.
        assertEquals(250_000, legacyDriftOnly.remainingMinutes(0.2000003, 0.3000003))
    }

    @Test fun singleDistractedPageTurnDoesNotBlowUpChapterEstimate() {
        val pace = ReadingPace()
        val legacy = LegacyReadingPace()

        // Establish steady 0.005 per 15s = 0.02/min pace (ground truth for 0.10 remaining = 5 min).
        var point = 0.0
        var now = 0L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)
        repeat(6) {
            point += 0.005
            now += 15_000L
            pace.observe(point, now, true)
            legacy.observe(point, now, true)
        }
        assertEquals(5, pace.remainingMinutes(point, point + 0.10))
        assertEquals(5, legacy.remainingMinutes(point, point + 0.10))

        // Reader is distracted for 290s (just under the 300s idle cutoff) on a single page turn.
        point += 0.005
        now += 290_000L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)

        val calibratedAfterOutlier = pace.remainingMinutes(point, point + 0.10)!!
        val legacyAfterOutlier = legacy.remainingMinutes(point, point + 0.10)!!

        // Calibrated Winsorized estimator stays within +1 min of the 5-min ground truth (returns 6).
        // Legacy sum-ratio jumps to 19 minutes (ceil(38.0 / 2.1) = 19, +280% error) because 290s dominates total elapsed time.
        assertEquals(6, calibratedAfterOutlier)
        assertEquals(19, legacyAfterOutlier)
    }

    @Test fun coalescedMultiPageFlipBurstIsRejectedInsteadOfSlashingRemainingTime() {
        val pace = ReadingPace()
        val legacy = LegacyReadingPace()

        // Establish steady 0.005 per 15s = 0.02/min pace (ground truth for 0.10 remaining = 5 min).
        var point = 0.0
        var now = 0L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)
        repeat(4) {
            point += 0.005
            now += 15_000L
            pace.observe(point, now, true)
            legacy.observe(point, now, true)
        }
        assertEquals(5, pace.remainingMinutes(point, point + 0.10))

        // Rapid multi-page flip burst: reader flips forward 6% of the book (12 pages) in 4 seconds
        // without opening TOC/search controls (0.06 in 4s = 1.5%/s, above MAX_PACE_PER_MILLIS).
        point += 0.06
        now += 4_000L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)

        // Also test a moderate skim burst of 4 pages (0.02) in 4 seconds (0.5%/s, 15x median rate).
        point += 0.02
        now += 4_000L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)

        // Calibrated rejects both bursts and preserves the 5-minute estimate.
        // Legacy accepts both bursts and slashes the 5-minute estimate to 2 minutes (-60%).
        assertEquals(5, pace.remainingMinutes(point, point + 0.10))
        assertEquals(2, legacy.remainingMinutes(point, point + 0.10))
    }

    @Test fun backwardPageTurnsAndForwardRetraceDoNotCountAlreadyReadPagesAsFastReading() {
        val pace = ReadingPace()
        val legacy = LegacyReadingPace()

        // Steady reading at 0.005 per 15s = 0.02/min up to point 0.10 (ground truth for 0.10 remaining = 5 min).
        var point = 0.08
        var now = 0L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)
        repeat(4) {
            point += 0.005
            now += 15_000L
            pace.observe(point, now, true)
            legacy.observe(point, now, true)
        }
        assertEquals(5, pace.remainingMinutes(point, point + 0.10))

        // Reader flips back 2 pages (from 0.10 to 0.09) to check a sentence, then flips forward
        // 2 pages back to 0.10 in 3.5 seconds each.
        pace.observe(0.09, now + 5_000L, true)
        legacy.observe(0.09, now + 5_000L, true)
        pace.observe(0.095, now + 8_500L, true)
        legacy.observe(0.095, now + 8_500L, true)
        pace.observe(0.10, now + 12_000L, true)
        legacy.observe(0.10, now + 12_000L, true)

        // Calibrated ignores the retrace up to the high-water mark 0.10 and keeps 5 minutes.
        // Legacy records the two 3.5s retrace flips as new reading and drops the estimate to 4 minutes.
        assertEquals(5, pace.remainingMinutes(0.10, 0.20))
        assertEquals(4, legacy.remainingMinutes(0.10, 0.20))

        // Subsequent genuine reading past 0.10 resumes normal sample collection.
        pace.observe(0.105, now + 27_000L, true)
        assertEquals(5, pace.remainingMinutes(0.105, 0.205))
    }

    @Test fun genuinePaceShiftAdaptsWithinFiveTurnsWithoutRequiringFullWindowTurnover() {
        val pace = ReadingPace()
        val legacy = LegacyReadingPace()

        // Fill the 20-sample window at slow pace: 0.005 per 30s = 0.01/min (10 min for 0.10 remaining).
        var point = 0.0
        var now = 0L
        pace.observe(point, now, true)
        legacy.observe(point, now, true)
        repeat(20) {
            point += 0.005
            now += 30_000L
            pace.observe(point, now, true)
            legacy.observe(point, now, true)
        }
        assertEquals(10, pace.remainingMinutes(point, point + 0.10))
        assertEquals(10, legacy.remainingMinutes(point, point + 0.10))

        // Reader hits fast dialogue and doubles pace to 0.005 per 15s = 0.02/min (true remaining = 5 min)
        // for 6 consecutive turns.
        repeat(6) {
            point += 0.005
            now += 15_000L
            pace.observe(point, now, true)
            legacy.observe(point, now, true)
        }

        // Recency weighting adapts to 6 minutes (within +1 min of the new 5-min ground truth),
        // whereas unweighted 20-sample sum-ratio lags at 9 minutes.
        assertEquals(6, pace.remainingMinutes(point, point + 0.10))
        assertEquals(9, legacy.remainingMinutes(point, point + 0.10))
    }

    @Test fun typographyRepaginationAndFoldPausePreservePaceAcrossChangedPageStepSize() {
        val pace = ReadingPace()

        // Before font size change: 1 page = 0.006 of book every 18s (rate = 0.02/min -> 5 min for 0.10).
        var point = 0.10
        var now = 0L
        pace.observe(point, now, true)
        repeat(4) {
            point += 0.006
            now += 18_000L
            pace.observe(point, now, true)
        }
        assertEquals(5, pace.remainingMinutes(point, point + 0.10))

        // User increases font size or unfolds screen: EpubSession calls pause(), locator shifts slightly,
        // and each new page is now 0.003 of book every 9s (same reading rate = 0.02/min).
        pace.pause()
        point = 0.123
        now += 8_000L
        pace.observe(point, now, true)
        repeat(4) {
            point += 0.003
            now += 9_000L
            pace.observe(point, now, true)
        }
        assertEquals(5, pace.remainingMinutes(point, point + 0.10))
    }

    @Test fun numericalBoundariesReturnSubMinuteZeroAndSuppressImplausibleMultiDayEstimates() {
        val pace = learned() // Rate = 0.04/min (0.01 per 15s).

        // Remaining distance = 0.01 -> 0.01 / 0.04 = 0.25 min (< 0.5 min) -> returns 0 ("Less than a minute left").
        assertEquals(0, pace.remainingMinutes(0.10, 0.11))

        // Remaining distance = 0.025 -> 0.025 / 0.04 = 0.625 min (>= 0.5 min) -> returns 1 ("About 1 min left").
        assertEquals(1, pace.remainingMinutes(0.10, 0.125))

        // Extremely slow valid samples: 0.00025 every 300s (5 min) = 0.00005/min.
        // Remaining 0.90 -> 18,000 minutes (300 hours) -> exceeds MAX_REMAINING_MINUTES (600) -> returns null.
        val ultraSlow = ReadingPace()
        for (step in 0..3) ultraSlow.observe(step * 0.00025, step * 300_000L, true)
        assertNull(ultraSlow.remainingMinutes(0.01, 0.91))
    }

    @Test fun heldOutSyntheticSessionsDemonstrateAccuracyAndStabilityOverLegacyEstimator() {
        // Held-Out Session A:
        // 1. 5 pages of prose at 0.004 per 15s (rate = 0.016/min)
        // 2. 1-page backward reference check + 3.5s forward retrace
        // 3. 10-minute idle break (600s)
        // 4. 8 pages of faster dialogue at 0.004 per 10s (rate = 0.024/min)
        // Ground truth at end of phase 4 for remaining 0.12 of book: 0.12 / 0.024 = 5.0 min.
        val paceA = ReadingPace()
        val legacyA = LegacyReadingPace()
        var ptA = 0.20
        var tA = 0L
        paceA.observe(ptA, tA, true)
        legacyA.observe(ptA, tA, true)
        repeat(5) {
            ptA += 0.004
            tA += 15_000L
            paceA.observe(ptA, tA, true)
            legacyA.observe(ptA, tA, true)
        }
        // Back 1 page and retrace forward in 3.5s.
        paceA.observe(ptA - 0.004, tA + 4_000L, true)
        legacyA.observe(ptA - 0.004, tA + 4_000L, true)
        paceA.observe(ptA, tA + 7_500L, true)
        legacyA.observe(ptA, tA + 7_500L, true)
        // 10-minute idle break on next page turn.
        ptA += 0.004
        tA += 607_500L
        paceA.observe(ptA, tA, true)
        legacyA.observe(ptA, tA, true)
        // 8 pages of dialogue at 10s per 0.004 page (0.024/min).
        repeat(8) {
            ptA += 0.004
            tA += 10_000L
            paceA.observe(ptA, tA, true)
            legacyA.observe(ptA, tA, true)
        }
        val estA = paceA.remainingMinutes(ptA, ptA + 0.12)!!
        assertTrue("Held-out A expected 5..6 min, got $estA", estA in 5..6)

        // Held-Out Session B:
        // Gradual fatigue slowdown (5 turns at 15s, 5 turns at 18s, 5 turns at 22.5s for 0.005 pages;
        // mid-session true pace = 0.005 / 0.30 min = 0.01667/min -> 0.10 remaining = 6.0 min;
        // terminal true pace = 0.005 / 0.375 min = 0.01333/min -> 0.10 remaining = 7.5 min -> ceil 8 min),
        // interrupted mid-session by both a 280s distraction page and a 4s 5-page skim burst (0.025).
        val paceB = ReadingPace()
        val legacyB = LegacyReadingPace()
        var ptB = 0.0
        var tB = 0L
        paceB.observe(ptB, tB, true)
        legacyB.observe(ptB, tB, true)
        repeat(5) { ptB += 0.005; tB += 15_000L; paceB.observe(ptB, tB, true); legacyB.observe(ptB, tB, true) }
        // 280s distraction outlier.
        ptB += 0.005; tB += 280_000L; paceB.observe(ptB, tB, true); legacyB.observe(ptB, tB, true)
        repeat(5) { ptB += 0.005; tB += 18_000L; paceB.observe(ptB, tB, true); legacyB.observe(ptB, tB, true) }

        // Mid-session check right after the 18s phase (true pace = 6 min):
        // Calibrated stays at 6 min; legacy is inflated to 14 min (+133%) by the 280s distraction.
        assertEquals(6, paceB.remainingMinutes(ptB, ptB + 0.10))
        assertEquals(14, legacyB.remainingMinutes(ptB, ptB + 0.10))

        // 4s 5-page skim burst (0.025 in 4s).
        ptB += 0.025; tB += 4_000L; paceB.observe(ptB, tB, true); legacyB.observe(ptB, tB, true)
        repeat(5) { ptB += 0.005; tB += 22_500L; paceB.observe(ptB, tB, true); legacyB.observe(ptB, tB, true) }

        val estB = paceB.remainingMinutes(ptB, ptB + 0.10)!!
        val legacyEstB = legacyB.remainingMinutes(ptB, ptB + 0.10)!!
        // Calibrated stays right on the 7..8 min fatigued ground truth; legacy swings from 14 down to 9 min.
        assertTrue("Held-out B expected 7..8 min, got $estB", estB in 7..8)
        assertEquals(9, legacyEstB)
    }

    @Test fun veryLongBookWithTinyPageStepsStillLearnsPace() {
        // 20,000-screen book: each page is 0.00005 of the publication, read every 15s.
        val pace = ReadingPace()
        var point = 0.5
        var now = 0L
        pace.observe(point, now, true)
        repeat(4) {
            point += 0.00005
            now += 15_000L
            pace.observe(point, now, true)
        }
        // 0.0002/min; 100 screens (0.005) left = 25 minutes.
        assertEquals(25, pace.remainingMinutes(point, point + 0.005))
    }

    @Test fun reReadMarkSurvivesControlsPauseButNotAFarJump() {
        val pace = ReadingPace()
        // Read to 0.10 at 0.005 per 15s, flip back two pages, open and close controls.
        var now = 0L
        for (step in 0..4) pace.observe(0.08 + step * 0.005, now + step * 15_000L, true)
        now += 60_000L
        assertEquals(5, pace.remainingMinutes(0.10, 0.20))
        pace.observe(0.09, now + 5_000L, true)
        pace.observe(0.09, now + 6_000L, false)
        // Flipping forward over already-read pages after the pause is still not new reading.
        pace.observe(0.095, now + 9_500L, true)
        pace.observe(0.10, now + 13_000L, true)
        assertEquals(5, pace.remainingMinutes(0.10, 0.20))

        // A far backward jump (Contents) after the pause drops the mark, so slower reading
        // there counts instead of being ignored until the old position is reached again.
        val jumped = ReadingPace()
        for (step in 0..4) jumped.observe(0.40 + step * 0.005, step * 15_000L, true)
        jumped.observe(0.415, 65_000L, true)
        jumped.pause()
        now = 100_000L
        for (step in 0..6) jumped.observe(0.10 + step * 0.005, now + step * 30_000L, true)
        val estimate = jumped.remainingMinutes(0.13, 0.23)!!
        assertTrue("Expected the slower 10-minute pace to dominate, got $estimate", estimate >= 8)
    }

    private fun perMinute(fraction: Double) = fraction / 60_000.0

    @Test fun rememberedPaceGivesChapterAndBookEstimatesImmediately() {
        val pace = ReadingPace()
        pace.seed(perMinute(0.002))
        assertEquals(50, pace.remainingMinutes(0.4, 0.5))
        // 0.6 of the book at 0.002/min = 300 minutes: beyond a chapter's limit is fine for a book.
        assertEquals(300, pace.bookRemainingMinutes(0.4))
        assertNull("Nothing was measured yet, so there is nothing new to remember", pace.learnedRatePerMillis())
    }

    @Test fun liveReadingProgressivelyOutweighsTheRememberedPace() {
        val pace = ReadingPace()
        pace.seed(perMinute(0.002)) // Remembered: 50 minutes per 0.1.
        var point = 0.2
        var now = 0L
        pace.observe(point, now, true)
        val estimates = mutableListOf<Int>()
        repeat(20) {
            point += 0.00025
            now += 15_000L // Today's reading is twice as slow: 0.001/min, 100 minutes per 0.1.
            assertTrue(pace.observe(point, now, true))
            pace.remainingMinutes(point, point + 0.1)?.let(estimates::add)
        }
        assertEquals("No jump while live samples are still too few", 50, estimates.first())
        assertTrue("Estimates should move steadily towards live reading: $estimates",
            estimates.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("Live reading should dominate after a full window, got ${estimates.last()}",
            estimates.last() in 80..100)
        val learned = pace.learnedRatePerMillis()!! * 60_000.0
        assertTrue("Remembered pace should now lean live, got $learned", learned in 0.001..0.0013)
    }

    @Test fun bookEstimateAllowsLongBooksButNotAbsurdOnes() {
        val pace = ReadingPace()
        pace.seed(perMinute(0.0002))
        assertEquals(2_500, pace.bookRemainingMinutes(0.5))
        assertEquals(0, pace.bookRemainingMinutes(1.0))
        val glacial = ReadingPace()
        glacial.seed(perMinute(0.00001))
        assertNull(glacial.bookRemainingMinutes(0.0))
    }

    @Test fun invalidRememberedPacesAreIgnored() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -perMinute(0.001), perMinute(1.0)).forEach { rate ->
            val pace = ReadingPace()
            pace.seed(rate)
            assertNull("Seed $rate must be ignored", pace.bookRemainingMinutes(0.5))
        }
    }

    @Test fun observeReportsOnlyMeasuredSamples() {
        val pace = ReadingPace()
        assertTrue(!pace.observe(0.1, 0L, true))
        assertTrue(!pace.observe(0.11, 1_000L, true)) // Too quick to be reading.
        assertTrue(pace.observe(0.12, 16_000L, true))
        assertTrue(!pace.observe(0.13, 31_000L, false))
    }

    @Test fun glancingAtControlsEveryPageStillLearnsWithoutCountingTheGlance() {
        // Owner's 2026-10-30 pattern: each page is read for 60s, with a 5s look at the controls
        // in the middle (which re-sends the same position). Previously no sample was ever kept.
        val pace = ReadingPace()
        var point = 0.30
        var now = 0L
        pace.observe(point, now, true)
        repeat(3) { page ->
            now += 30_000L
            pace.hold(now)
            assertTrue(!pace.observe(point, now + 50L, false)) // Same page re-reported with controls shown.
            now += 5_000L
            pace.release(now)
            assertEquals(3 - page, pace.pagesUntilEstimate())
            now += 30_000L
            point += 0.001
            assertTrue("Page ${page + 1} should be timed", pace.observe(point, now, true))
        }
        assertEquals(0, pace.pagesUntilEstimate())
        // 0.001 per 60s of reading (glances excluded): 0.1 more = 100 minutes, not 108.
        assertEquals(100, pace.remainingMinutes(point, point + 0.1))
    }

    @Test fun movingWhileHeldOrBackgroundedBreaksTheInterval() {
        val pace = ReadingPace()
        pace.observe(0.30, 0L, true)
        pace.hold(10_000L)
        pace.observe(0.35, 12_000L, false) // A Contents jump while controls are shown.
        pace.release(15_000L)
        assertTrue("The jump must not become a reading sample", !pace.observe(0.351, 45_000L, true))
        // The app sitting in the background for an hour is left out of the page time.
        pace.hold(60_000L)
        pace.release(3_660_000L)
        assertTrue(pace.observe(0.352, 3_675_000L, true))
    }

    @Test fun quickFlipsBesideRealReadingDoNotShrinkTheEstimateSixfold() {
        // Owner's phone 2026-10-30: equal pages read in 4.4s, 20s and 113s. Averaging speeds
        // implied ~17s per page; per-distance averaging of Winsorized page times gives ~28s, and
        // further real pages quickly pull it towards the 113s reading pace.
        val page = 0.0005
        val pace = ReadingPace()
        var point = 0.01
        var now = 0L
        pace.observe(point, now, true)
        for (seconds in listOf(4.4, 20.0, 113.0)) {
            point += page; now += (seconds * 1_000).toLong(); pace.observe(point, now, true)
        }
        val early = pace.remainingMinutes(point, point + 100 * page)!!
        assertTrue("100 pages should take well over 28 minutes at first, got $early", early >= 45)
        assertTrue(pace.isRough())
        assertNull("Too few pages to be remembered", pace.learnedRatePerMillis())
        repeat(5) { point += page; now += 110_000L; pace.observe(point, now, true) }
        val settled = pace.remainingMinutes(point, point + 100 * page)!!
        assertTrue("After more real pages, 100 pages should approach 3 hours, got $settled", settled in 150..200)
        assertTrue(!pace.isRough())
        assertNotNull(pace.learnedRatePerMillis())
    }

    @Test fun learningTextCountsDownPages() {
        assertEquals("Time left: learning your reading pace (about 3 more pages)", learningText(3))
        assertEquals("Time left: learning your reading pace (about 1 more page)", learningText(1))
    }

    @Test fun timeLeftTextUsesHoursForLongSpans() {
        assertEquals("Less than a minute left in chapter", timeLeftText(0, "chapter"))
        assertEquals("About 1 minute left in chapter", timeLeftText(1, "chapter"))
        assertEquals("About 59 minutes left in book", timeLeftText(59, "book"))
        assertEquals("About 2 h left in book", timeLeftText(120, "book"))
        assertEquals("About 5 h 7 min left in book", timeLeftText(307, "book"))
        assertEquals("Time left in book unavailable", timeLeftText(null, "book"))
    }
}
