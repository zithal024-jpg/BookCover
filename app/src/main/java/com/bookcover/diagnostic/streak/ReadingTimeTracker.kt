package com.bookcover.diagnostic.streak

/** A credited stretch of reading, in [android.os.SystemClock.elapsedRealtime] milliseconds. */
internal data class ReadingCredit(val startElapsed: Long, val endElapsed: Long) {
    val millis: Long get() = endElapsed - startElapsed
}

/**
 * Counts time while the reader is in the foreground, using a monotonic clock so wall-clock changes
 * cannot add time. Nothing accrues between [pause] and [resume], so time spent backgrounded, locked or
 * away from the reader is never credited. The reader keeps the screen on, so time also stops
 * [idleLimitMillis] after the last page turn, tap or key; that limit is long enough for a slow page.
 * Each method returns the newly credited time, which the caller records exactly once.
 */
internal class ReadingTimeTracker(private val idleLimitMillis: Long = 10 * 60_000L) {
    private var running = false
    private var creditedUntil = 0L
    private var lastActivity = 0L

    val isRunning: Boolean get() = running

    /** Starts counting from [now]; any earlier time stays uncredited. Repeated calls change nothing. */
    fun resume(now: Long) {
        if (running) return
        running = true
        creditedUntil = now
        lastActivity = now
    }

    /** Credits time up to [now], or up to the idle limit when the reader has been left alone. */
    fun collect(now: Long): ReadingCredit? {
        if (!running) return null
        val end = minOf(now, lastActivity + idleLimitMillis)
        if (end <= creditedUntil) return null
        return ReadingCredit(creditedUntil, end).also { creditedUntil = end }
    }

    /** A page turn, tap or key: the reader is present. Time after an idle gap restarts from [now]. */
    fun activity(now: Long): ReadingCredit? {
        if (!running) return null
        val credit = collect(now)
        if (creditedUntil < now) creditedUntil = now
        lastActivity = now
        return credit
    }

    fun pause(now: Long): ReadingCredit? = collect(now).also { running = false }
}
