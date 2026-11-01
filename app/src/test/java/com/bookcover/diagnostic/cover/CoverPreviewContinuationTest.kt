package com.bookcover.diagnostic.cover

import org.junit.Assert.*
import org.junit.Test

class CoverPreviewContinuationTest {
    @Test fun libraryPreviewEndsWithOutsideDisplayOff() {
        val sessions = CoverPreviewContinuation()
        assertTrue(sessions.begin(100))
        assertTrue(sessions.current.requested)
        assertNull(sessions.current.remaining(100_000))
        sessions.end()
        assertFalse(sessions.current.requested)
    }

    @Test fun manyDraftChangesKeepTheReadersOriginalDeadlineAndManualStopChoice() {
        val sessions = CoverPreviewContinuation()
        sessions.reading.start(30, 100)
        sessions.reading.presenting()
        assertTrue(sessions.begin(10_000))
        repeat(50) { assertFalse(sessions.begin(10_000L + it * 1_000)) }
        assertNull(sessions.current.remaining(100_000))
        sessions.end()
        assertEquals(1_800_100L, sessions.current.deadline)
        assertEquals(1_700_100L, sessions.current.remaining(100_000))
        sessions.reading.stop()
        sessions.begin(200_000)
        sessions.end()
        assertFalse(sessions.current.requested)
    }

    @Test fun expiredReaderTimerIsNotExtendedWhilePreviewIsOpen() {
        val sessions = CoverPreviewContinuation()
        sessions.reading.start(1, 100)
        sessions.begin(50_000)
        assertTrue(sessions.current.requested)
        assertNull(sessions.current.remaining(70_000))
        sessions.end()
        assertEquals(0L, sessions.current.remaining(70_000))
        assertEquals(60_100L, sessions.current.deadline)
    }

    @Test fun failedPreviewDoesNotEraseAnExistingReadersDisplayChoice() {
        val sessions = CoverPreviewContinuation()
        sessions.reading.start(30, 100)
        sessions.begin(20_000)
        assertFalse(sessions.current.systemEnded(true))
        assertFalse(sessions.current.requested)
        sessions.end()
        assertTrue(sessions.current.requested)
        assertEquals(1_800_100L, sessions.current.deadline)
    }

    @Test fun readerRecreationCanRestoreItsDeadlineAfterTheEditorPreviewHasStarted() {
        val sessions = CoverPreviewContinuation()
        sessions.begin(40_000)
        // Cover art/publication loading can complete after the cabinet's draft callback.
        sessions.reading.restore(true, 100_000, 50_000)
        assertNull(sessions.current.remaining(50_000))
        sessions.end()
        assertTrue(sessions.current.requested)
        assertEquals(50_000L, sessions.current.remaining(50_000))
    }

    @Test fun screenOffOrExplicitStopCancelsBothRequests() {
        val sessions = CoverPreviewContinuation()
        sessions.reading.start(0, 100)
        sessions.begin(10_000)
        sessions.stop()
        assertFalse(sessions.previewing)
        assertFalse(sessions.reading.requested)
        assertFalse(sessions.current.requested)
        sessions.current.displayChanging(true)
        assertFalse(sessions.current.resumePending)
    }
}
