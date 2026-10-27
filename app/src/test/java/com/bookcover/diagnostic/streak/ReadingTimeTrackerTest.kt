package com.bookcover.diagnostic.streak

import org.junit.Assert.*
import org.junit.Test

class ReadingTimeTrackerTest {
    private val minute = 60_000L
    private fun credited(vararg credits: ReadingCredit?) = credits.filterNotNull().sumOf { it.millis }

    @Test fun nothingCountsBeforeResume() {
        val tracker = ReadingTimeTracker()
        assertNull(tracker.collect(5 * minute))
        assertNull(tracker.activity(5 * minute))
    }

    @Test fun backgroundTimeIsNeverCredited() {
        val tracker = ReadingTimeTracker()
        tracker.resume(0)
        val beforePause = tracker.pause(2 * minute)
        // An hour in the background or with the screen locked, then the reader returns.
        tracker.resume(62 * minute)
        val afterResume = tracker.collect(63 * minute)
        assertEquals(3 * minute, credited(beforePause, afterResume))
    }

    @Test fun repeatedLifecycleEventsDoNotDuplicateTime() {
        val tracker = ReadingTimeTracker()
        tracker.resume(0)
        tracker.resume(minute) // A second resume must not move the start or add time.
        val first = tracker.collect(2 * minute)
        val again = tracker.collect(2 * minute)
        val pause = tracker.pause(3 * minute)
        val secondPause = tracker.pause(4 * minute)
        assertNull(again); assertNull(secondPause)
        assertEquals(3 * minute, credited(first, pause))
    }

    @Test fun slowPagesStillCountWithinTheIdleLimit() {
        val tracker = ReadingTimeTracker(idleLimitMillis = 10 * minute)
        tracker.resume(0)
        // Nine minutes on one long page, then a page turn.
        assertEquals(9 * minute, credited(tracker.activity(9 * minute)))
    }

    @Test fun anUnattendedReaderStopsCountingAfterTheIdleLimit() {
        val tracker = ReadingTimeTracker(idleLimitMillis = 10 * minute)
        tracker.resume(0)
        val idle = tracker.collect(45 * minute)
        assertEquals(10 * minute, credited(idle))
        // The reader comes back at 45 min: the 35 idle minutes stay uncredited, new reading counts.
        assertNull(tracker.activity(45 * minute))
        assertEquals(2 * minute, credited(tracker.pause(47 * minute)))
    }

    @Test fun periodicCollectionsAddUpToTheWholeSession() {
        val tracker = ReadingTimeTracker()
        tracker.resume(1_000)
        val parts = listOf(tracker.collect(31_000), tracker.activity(40_000), tracker.collect(61_000), tracker.pause(70_000))
        assertEquals(69_000L, credited(*parts.toTypedArray()))
        // Credits never overlap.
        parts.filterNotNull().zipWithNext { a, b -> assertTrue(a.endElapsed <= b.startElapsed) }
    }
}
