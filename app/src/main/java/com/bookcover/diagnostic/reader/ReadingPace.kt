package com.bookcover.diagnostic.reader

import kotlin.math.ceil

/** Learns publication-coordinate distance per active reading interval; never assumes a WPM rate.
 * The caller pauses around jumps, reflow, controls, lifecycle pauses and viewport changes. */
internal class ReadingPace {
    private data class Sample(val distance: Double, val elapsedMillis: Long)
    private val samples = ArrayDeque<Sample>()
    private var previousPoint: Double? = null
    private var previousTime: Long? = null

    /** Breaks the candidate interval while keeping previously learned pace for this session. */
    fun pause() {
        previousPoint = null
        previousTime = null
    }

    fun observe(point: Double, nowMillis: Long, eligible: Boolean) {
        if (!eligible || !point.isFinite() || point !in 0.0..1.0 || nowMillis < 0) {
            pause()
            return
        }
        val beforePoint = previousPoint
        val beforeTime = previousTime
        if (beforePoint != null && beforeTime != null && nowMillis >= beforeTime && point == beforePoint) return
        previousPoint = point
        previousTime = nowMillis
        if (beforePoint == null || beforeTime == null || nowMillis <= beforeTime) return

        val distance = point - beforePoint
        val elapsed = nowMillis - beforeTime
        if (distance <= 0.0 || distance > 0.10 + 1e-9 || elapsed !in 3_000L..300_000L) return
        samples.addLast(Sample(distance, elapsed))
        while (samples.size > 20) samples.removeFirst()
    }

    fun remainingMinutes(point: Double, chapterEnd: Double): Int? {
        if (!point.isFinite() || !chapterEnd.isFinite() || point !in 0.0..1.0 || chapterEnd !in 0.0..1.0) return null
        if (chapterEnd <= point) return 0
        val elapsed = samples.sumOf { it.elapsedMillis }
        if (samples.size < 3 || elapsed < 45_000L) return null
        val distance = samples.sumOf { it.distance }
        val minutes = ceil((chapterEnd - point) * elapsed.toDouble() / distance / 60_000.0)
        return minutes.takeIf { it.isFinite() && it <= Int.MAX_VALUE }?.toInt()
    }
}
