package com.bookcover.diagnostic.cover

import org.junit.Assert.*
import org.junit.Test

class CoverSessionContinuationTest {
    @Test fun onlyAnInterruptedDisplayTransitionCanRetryAndOnlyOnce() {
        val cover = CoverSessionContinuation()
        cover.start(30, 0)
        cover.presenting()
        cover.displayChanging()
        assertTrue(cover.systemEnded(false))
        assertTrue(cover.resumePending)
        cover.presenting()
        assertFalse(cover.systemEnded(false))
        assertFalse(cover.requested)
    }

    @Test fun survivingAResizeDoesNotRestartOnALaterOrdinaryEnd() {
        val cover = CoverSessionContinuation()
        cover.start(0, 0)
        cover.presenting()
        cover.displayChanging()
        cover.displaySettled()
        assertFalse(cover.systemEnded(false))
        assertFalse(cover.resumePending)
        // No generic availability callback may retry. A later explicit resize can recover it.
        assertTrue(cover.requested)
        cover.stop()
        assertFalse(cover.requested)
    }

    @Test fun systemEndBeforeConfigurationStillRecoversOnThatDisplayChange() {
        val cover = CoverSessionContinuation()
        cover.start(30, 0)
        cover.presenting()
        assertFalse(cover.systemEnded(false))
        assertFalse(cover.resumePending)
        cover.displayChanging(suspended = true)
        assertTrue(cover.resumePending)
        assertEquals(1_800_000L, cover.deadline)
        cover.stop() // Exit while suspended must cancel recovery too.
        cover.displayChanging(suspended = true)
        assertFalse(cover.resumePending)
    }

    @Test fun repeatedTransitionsNeverExtendTheExistingTimer() {
        val cover = CoverSessionContinuation()
        cover.start(30, 100L)
        repeat(20) { step ->
            val now = 100L + step * 1_000
            assertEquals(1_800_000L - step * 1_000, cover.remaining(now))
            assertTrue(cover.requested)
        }
        assertEquals(0L, cover.remaining(1_800_100L))
    }

    @Test fun stopOrReaderExitCancelsEvenASuspendedCover() {
        val cover = CoverSessionContinuation()
        cover.start(0, 0L)
        assertNull(cover.remaining(500L))
        cover.stop()
        assertFalse(cover.requested)
        assertNull(cover.remaining(800L))
    }

    @Test fun recreationRespectsManualStopAndTheOriginalDeadline() {
        val cover = CoverSessionContinuation()
        cover.restore(true, 50_000, 40_000)
        assertEquals(10_000L, cover.remaining(40_000))
        cover.restore(true, 50_000, 60_000)
        assertFalse(cover.requested)
        cover.restore(false, 0, 60_000)
        assertFalse(cover.requested)
        cover.restore(true, 0, 60_000)
        assertTrue(cover.requested)
        assertNull(cover.remaining(70_000))
    }
}
