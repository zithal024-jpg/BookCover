package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class InnerScreenAwakeTest {
    private val minute = 60_000L

    private fun reading(idle: InnerScreenIdle = InnerScreenIdle.DEFAULT, openedAt: Long = 1_000L) =
        InnerScreenAwake(idle).apply { resumed(openedAt); opened(openedAt) }

    @Test fun choicesAreTwoFiveTenMinutesOrAlwaysWithFiveAsDefault() {
        assertEquals(listOf(2, 5, 10, null), InnerScreenIdle.entries.map { it.minutes })
        assertEquals(InnerScreenIdle.FIVE, InnerScreenIdle.DEFAULT)
        assertEquals(listOf(2 * minute, 5 * minute, 10 * minute, null), InnerScreenIdle.entries.map { it.millis })
    }

    @Test fun savedIdsRoundTripAndUnknownValuesFallBackToTheDefault() {
        InnerScreenIdle.entries.forEach { assertEquals(it, InnerScreenIdle.fromId(it.id)) }
        assertEquals(InnerScreenIdle.FIVE, InnerScreenIdle.fromId(null))
        assertEquals(InnerScreenIdle.FIVE, InnerScreenIdle.fromId("30"))
        assertEquals(InnerScreenIdle.FIVE, InnerScreenIdle.fromId(""))
    }

    @Test fun eachTimedChoiceHoldsTheScreenUntilExactlyItsLimit() {
        listOf(InnerScreenIdle.TWO, InnerScreenIdle.FIVE, InnerScreenIdle.TEN).forEach { idle ->
            val awake = InnerScreenAwake(idle)
            awake.resumed(0)
            val opened = awake.opened(1_000)
            assertTrue(opened.keepScreenOn)
            assertEquals(idle.millis, opened.checkAfterMillis)
            val before = awake.check(opened.token, 1_000 + idle.millis!! - 1)!!
            assertTrue("$idle still on 1 ms before its limit", before.keepScreenOn)
            assertEquals(1L, before.checkAfterMillis)
            val expired = awake.check(before.token, 1_000 + idle.millis!!)!!
            assertFalse("$idle releases at its limit", expired.keepScreenOn)
            assertNull("Nothing left to schedule after expiry", expired.checkAfterMillis)
        }
    }

    @Test fun alwaysHoldsTheScreenWithNoScheduledCheck() {
        val awake = InnerScreenAwake(InnerScreenIdle.ALWAYS)
        awake.resumed(0)
        val opened = awake.opened(0)
        assertTrue(opened.keepScreenOn)
        assertNull(opened.checkAfterMillis)
        assertTrue(awake.check(opened.token, 10 * 60 * minute)!!.keepScreenOn)
    }

    @Test fun genuineInputRenewsTheWholeInterval() {
        val awake = reading()
        val renewed = awake.input(1_000 + 4 * minute)
        assertTrue(renewed.keepScreenOn)
        assertEquals(5 * minute, renewed.checkAfterMillis)
        assertTrue(awake.check(renewed.token, 1_000 + 8 * minute)!!.keepScreenOn)
        assertFalse(awake.check(awake.token, 1_000 + 9 * minute)!!.keepScreenOn)
    }

    @Test fun inputAfterExpiryHoldsTheScreenAgain() {
        val awake = reading()
        assertFalse(awake.check(awake.token, 1_000 + 6 * minute)!!.keepScreenOn)
        val back = awake.input(1_000 + 7 * minute)
        assertTrue(back.keepScreenOn)
        assertEquals(5 * minute, back.checkAfterMillis)
    }

    @Test fun onlyInputRenewsNotChecksResumesOrSettingChanges() {
        val awake = reading(openedAt = 0)
        val last = awake.lastInput
        awake.check(awake.token, 3 * minute)
        awake.paused(3 * minute); awake.resumed(4 * minute)
        awake.choose(InnerScreenIdle.TEN, 4 * minute)
        awake.choose(InnerScreenIdle.FIVE, 4 * minute)
        assertEquals("Non-input events never move the last input", last, awake.lastInput)
        assertFalse(awake.check(awake.token, 5 * minute)!!.keepScreenOn)
    }

    @Test fun staleScheduledChecksAreIgnored() {
        val awake = reading(openedAt = 0)
        val first = awake.opened(0)
        awake.input(2 * minute) // Replaces the earlier schedule.
        assertNull("An older token cannot act", awake.check(first.token, 5 * minute))
        assertNull(awake.check(first.token, 100 * minute))
        assertTrue(awake.check(awake.token, 6 * minute)!!.keepScreenOn)
    }

    @Test fun pauseReleasesAndCancelsWhileResumeKeepsTheOriginalDeadline() {
        val awake = reading(openedAt = 0)
        val paused = awake.paused(1 * minute)
        assertFalse(paused.keepScreenOn)
        assertNull(paused.checkAfterMillis)
        val resumed = awake.resumed(3 * minute)
        assertTrue(resumed.keepScreenOn)
        assertEquals("Resuming continues the old interval", 2 * minute, resumed.checkAfterMillis)
        awake.paused(4 * minute)
        assertFalse("Resuming after the deadline does not invent activity", awake.resumed(9 * minute).keepScreenOn)
    }

    @Test fun recreationRestoresTheSavedDeadlineWithoutRenewing() {
        val before = reading(openedAt = 0)
        before.input(2 * minute)
        val saved = before.lastInput

        val recreated = InnerScreenAwake(InnerScreenIdle.FIVE)
        assertFalse("Not resumed yet", recreated.restored(saved, 3 * minute).keepScreenOn)
        val resumed = recreated.resumed(3 * minute)
        assertTrue(resumed.keepScreenOn)
        assertEquals(4 * minute, resumed.checkAfterMillis)
        assertEquals(saved, recreated.lastInput)

        val late = InnerScreenAwake(InnerScreenIdle.FIVE).apply { resumed(10 * minute) }
        assertFalse("A deadline that passed during recreation stays passed", late.restored(saved, 10 * minute).keepScreenOn)
    }

    @Test fun recreationWithoutAUsableSavedInputIsAlreadyExpired() {
        listOf(null, 20 * minute).forEach { saved ->
            val awake = InnerScreenAwake(InnerScreenIdle.FIVE).apply { resumed(minute) }
            assertFalse("saved=$saved", awake.restored(saved, minute).keepScreenOn)
            assertNull(awake.lastInput)
        }
    }

    @Test fun closingReleasesAndInputOutsideTheReaderDoesNotHold() {
        val awake = reading(openedAt = 0)
        val closed = awake.closed(minute)
        assertFalse(closed.keepScreenOn)
        assertNull(closed.checkAfterMillis)
        assertFalse("Library touches never hold the screen", awake.input(2 * minute).keepScreenOn)
        assertNull(awake.check(closed.token, 3 * minute))
    }

    @Test fun choosingAnotherTimeAppliesFromTheLastInput() {
        val awake = reading(InnerScreenIdle.TEN, openedAt = 0)
        assertFalse("2 minutes have already passed", awake.choose(InnerScreenIdle.TWO, 3 * minute).keepScreenOn)
        val longer = awake.choose(InnerScreenIdle.TEN, 3 * minute)
        assertTrue(longer.keepScreenOn)
        assertEquals(7 * minute, longer.checkAfterMillis)
        assertTrue(awake.choose(InnerScreenIdle.ALWAYS, 30 * minute).keepScreenOn)
    }

    @Test fun anOlderInputTimestampNeverMovesTheDeadlineBack() {
        val awake = reading(openedAt = 2 * minute)
        awake.input(minute)
        assertEquals(2 * minute, awake.lastInput)
    }
}
