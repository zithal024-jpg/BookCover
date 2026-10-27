package com.bookcover.diagnostic.streak

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reading time that makes a day count toward the streak. */
internal const val DAILY_GOAL_MILLIS = 5 * 60_000L
internal const val DAILY_GOAL_MINUTES = (DAILY_GOAL_MILLIS / 60_000L).toInt()

/** One local calendar date: total reading time and whether the goal was reached that day (never taken back). */
internal data class ReadingDay(val millis: Long = 0, val completed: Boolean = false)

/**
 * Dated reading records. Dates are the phone's local calendar dates at the moment the time was read;
 * they are stored as dates, not instants, so a later timezone change never moves or removes an earned day.
 */
internal data class ReadingDays(val days: Map<LocalDate, ReadingDay> = emptyMap(), val celebrated: LocalDate? = null) {
    fun day(date: LocalDate): ReadingDay = days[date] ?: ReadingDay()
    fun completed(date: LocalDate): Boolean = days[date]?.completed == true

    /** Adds the wall-clock interval [startMillis, endMillis), split at each local midnight in [zone]. */
    fun plus(startMillis: Long, endMillis: Long, zone: ZoneId, goalMillis: Long = DAILY_GOAL_MILLIS): ReadingDays {
        if (endMillis <= startMillis) return this
        val updated = days.toMutableMap()
        var from = startMillis
        while (from < endMillis) {
            val date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            // Calendar-aware: the next midnight in this zone, whatever the day's length (DST).
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(endMillis, nextMidnight)
            val before = updated[date] ?: ReadingDay()
            val total = before.millis + (to - from)
            updated[date] = ReadingDay(total, before.completed || total >= goalMillis)
            from = to
        }
        return copy(days = updated)
    }

    /**
     * Consecutive completed dates ending today, or ending yesterday while today is still in progress.
     * Zero when neither today nor yesterday is complete.
     */
    fun currentStreak(today: LocalDate): Int {
        val end = when {
            completed(today) -> today
            completed(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        var date = end
        while (completed(date)) { count++; date = date.minusDays(1) }
        return count
    }

    /** The longest run of consecutive completed dates ever recorded. */
    fun longestStreak(): Int {
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in days.filterValues { it.completed }.keys.sorted()) {
            run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1
            longest = maxOf(longest, run)
            previous = date
        }
        return longest
    }

    fun everCompleted(): Boolean = days.values.any { it.completed }
}

/** What the Library card says; one per designed state. */
internal enum class StreakState { NEW_READER, ACTIVE_TODAY_OPEN, TODAY_COMPLETE, FRESH_START }

internal data class StreakSummary(
    val today: LocalDate,
    val current: Int,
    val longest: Int,
    val todayMillis: Long,
    val todayComplete: Boolean,
    val state: StreakState,
) {
    /** Whole minutes read today, never shown above the goal while it is still open. */
    val todayMinutes: Int get() = (todayMillis / 60_000L).toInt().let { if (todayComplete) it else minOf(it, DAILY_GOAL_MINUTES - 1) }
    val todayFraction: Float get() = if (todayComplete) 1f else (todayMillis.toFloat() / DAILY_GOAL_MILLIS).coerceIn(0f, 1f)

    companion object {
        fun of(records: ReadingDays, today: LocalDate): StreakSummary {
            val current = records.currentStreak(today)
            val todayDay = records.day(today)
            val state = when {
                todayDay.completed -> StreakState.TODAY_COMPLETE
                current > 0 -> StreakState.ACTIVE_TODAY_OPEN
                records.everCompleted() -> StreakState.FRESH_START
                else -> StreakState.NEW_READER
            }
            return StreakSummary(today, current, records.longestStreak(), todayDay.millis, todayDay.completed, state)
        }
    }
}
