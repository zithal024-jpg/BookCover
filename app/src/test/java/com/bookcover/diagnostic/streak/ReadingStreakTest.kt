package com.bookcover.diagnostic.streak

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReadingStreakTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val today = LocalDate.of(2026, 10, 27)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0, z: ZoneId = zone) =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(z).toInstant().toEpochMilli()
    private fun minutes(n: Int) = n * 60_000L

    /** Records [n] minutes of reading at 20:00 on [date]. */
    private fun ReadingDays.read(date: LocalDate, n: Int) = plus(at(date, 20), at(date, 20) + minutes(n), zone)
    private fun completedDays(vararg offsets: Long) = offsets.fold(ReadingDays()) { days, back -> days.read(today.minusDays(back), 5) }

    @Test fun sessionsFromDifferentBooksCombineTowardTheGoal() {
        val afterTwo = ReadingDays().plus(at(today, 8), at(today, 8) + minutes(2), zone)
        assertFalse(afterTwo.completed(today))
        val afterFive = afterTwo.plus(at(today, 13), at(today, 13) + minutes(3), zone)
        assertTrue(afterFive.completed(today))
        assertEquals(minutes(5), afterFive.day(today).millis)
    }

    @Test fun justUnderTheGoalDoesNotQualify() {
        val days = ReadingDays().plus(at(today, 9), at(today, 9) + minutes(5) - 1, zone)
        assertFalse(days.completed(today))
        assertEquals(StreakState.NEW_READER, StreakSummary.of(days, today).state)
    }

    @Test fun crossingTheThresholdAwardsTheDateOnce() {
        val days = ReadingDays().read(today, 4).read(today, 2).read(today, 30)
        assertEquals(1, days.days.size)
        assertTrue(days.completed(today))
        assertEquals(1, days.currentStreak(today))
        assertEquals(1, days.longestStreak())
    }

    @Test fun earnedDayStaysEarnedWhenTheGoalLaterChanges() {
        val days = ReadingDays().read(today, 5).plus(at(today, 22), at(today, 22) + 1_000, zone, goalMillis = minutes(30))
        assertTrue(days.completed(today))
    }

    @Test fun sessionAcrossMidnightIsSplitBetweenDates() {
        val yesterday = today.minusDays(1)
        val days = ReadingDays().plus(at(yesterday, 23, 58), at(today, 0, 4), zone)
        assertEquals(minutes(2), days.day(yesterday).millis)
        assertEquals(minutes(4), days.day(today).millis)
        assertFalse(days.completed(yesterday))
        assertFalse(days.completed(today))
    }

    @Test fun midnightSplitFollowsDaylightSavingDays() {
        // 25 October 2026 is 25 hours long in Madrid: all of it lands on that one date.
        val change = LocalDate.of(2026, 10, 25)
        val days = ReadingDays().plus(at(change, 0), at(change.plusDays(1), 0), zone)
        assertEquals(1, days.days.size)
        assertEquals(minutes(25 * 60), days.day(change).millis)
    }

    @Test fun yesterdaysStreakStaysActiveBeforeTodayIsComplete() {
        val days = completedDays(1, 2, 3).read(today, 2)
        val summary = StreakSummary.of(days, today)
        assertEquals(3, summary.current)
        assertEquals(StreakState.ACTIVE_TODAY_OPEN, summary.state)
        assertEquals(2, summary.todayMinutes)
        assertEquals("3-day streak", streakTitle(summary))
        assertEquals("2 of 5 minutes today", streakStatus(summary))
    }

    @Test fun completingTodayExtendsTheStreak() {
        val summary = StreakSummary.of(completedDays(0, 1, 2, 3), today)
        assertEquals(4, summary.current)
        assertEquals(StreakState.TODAY_COMPLETE, summary.state)
        assertEquals("4-day streak", streakTitle(summary))
        assertEquals("Today's goal complete", streakStatus(summary))
    }

    @Test fun aMissedDayResetsTheStreakButKeepsTheLongest() {
        // Five days in a row ending three days ago, then nothing on the two days before today.
        val days = completedDays(3, 4, 5, 6, 7)
        val summary = StreakSummary.of(days, today)
        assertEquals(0, summary.current)
        assertEquals(5, summary.longest)
        assertEquals(StreakState.FRESH_START, summary.state)
        assertEquals("Welcome back", streakTitle(summary))
        val restarted = StreakSummary.of(days.read(today, 6), today)
        assertEquals(1, restarted.current)
        assertEquals(5, restarted.longest)
    }

    @Test fun newReaderIsInvitedToStart() {
        val summary = StreakSummary.of(ReadingDays(), today)
        assertEquals(StreakState.NEW_READER, summary.state)
        assertEquals("Start your reading streak", streakTitle(summary))
        assertEquals("Read 5 minutes in a day to count it", streakStatus(summary))
    }

    @Test fun earnedDatesSurviveATimezoneChange() {
        // Read in Madrid late on the 26th; later the phone moves to New York, where it is still the 26th.
        val yesterday = today.minusDays(1)
        val madrid = ReadingDays().plus(at(yesterday, 23, 30), at(yesterday, 23, 36), zone)
        val newYork = ZoneId.of("America/New_York")
        val moved = madrid.plus(at(today, 7, z = newYork), at(today, 7, z = newYork) + minutes(5), newYork)
        assertTrue(moved.completed(yesterday))
        assertTrue(moved.completed(today))
        assertEquals(2, moved.currentStreak(today))
    }

    @Test fun dayLabelsDescribeStateInWords() {
        val days = completedDays(1).read(today, 3)
        val summary = StreakSummary.of(days, today)
        assertTrue(dayLabel(today.minusDays(1), days.day(today.minusDays(1)), summary).endsWith("reading goal completed"))
        assertEquals("Today, 3 of 5 minutes read", dayLabel(today, days.day(today), summary))
        assertTrue(dayLabel(today.minusDays(2), days.day(today.minusDays(2)), summary).endsWith("no reading recorded"))
    }
}
