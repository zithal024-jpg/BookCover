package com.bookcover.diagnostic.reader

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Learns publication-coordinate distance per active reading interval; never assumes a WPM rate.
 * The caller pauses around jumps, reflow, controls, lifecycle pauses and viewport changes. */
internal class ReadingPace {
    private companion object {
        const val MIN_SAMPLES = 3
        const val MIN_TOTAL_ELAPSED_MILLIS = 45_000L
        const val MAX_SAMPLES = 20
        const val MIN_INTERVAL_MILLIS = 3_000L
        const val MAX_INTERVAL_MILLIS = 300_000L
        const val UNCHANGED_POINT_EPSILON = 1e-6
        const val MIN_INTERVAL_DISTANCE = 0.0002
        const val MAX_INTERVAL_DISTANCE = 0.10
        /** At most 1% of the full publication per second (60%/min); rejects coalesced flip bursts. */
        const val MAX_PACE_PER_MILLIS = 0.01 / 1_000.0
        const val MULTI_PAGE_STEP_FACTOR = 2.5
        const val MULTI_PAGE_RATE_FACTOR = 3.0
        const val RECENCY_DECAY = 0.85
        const val MIN_RELATIVE_SPREAD = 0.35
        const val MAD_SPREAD_FACTOR = 3.0
        const val MIN_CLAMP_RATIO = 0.40
        const val MAX_CLAMP_RATIO = 2.50
        const val SUB_MINUTE_THRESHOLD = 0.5
        const val MINUTE_ROUNDING_EPSILON = 1e-9
        const val MAX_REMAINING_MINUTES = 600
    }

    private data class Sample(val distance: Double, val elapsedMillis: Long) {
        val ratePerMillis: Double get() = distance / elapsedMillis.toDouble()
    }

    private val samples = ArrayDeque<Sample>()
    private var previousPoint: Double? = null
    private var previousTime: Long? = null
    private var retraceTarget: Double? = null

    /** Breaks the candidate interval while keeping previously learned pace for this session. */
    fun pause() {
        previousPoint = null
        previousTime = null
        retraceTarget = null
    }

    fun observe(point: Double, nowMillis: Long, eligible: Boolean) {
        if (!eligible || !point.isFinite() || point !in 0.0..1.0 || nowMillis < 0) {
            pause()
            return
        }
        val beforePoint = previousPoint
        val beforeTime = previousTime
        if (beforePoint != null && beforeTime != null && nowMillis >= beforeTime &&
            abs(point - beforePoint) <= UNCHANGED_POINT_EPSILON) return
        previousPoint = point
        previousTime = nowMillis
        if (beforePoint == null || beforeTime == null || nowMillis <= beforeTime) return

        val distance = point - beforePoint
        val elapsed = nowMillis - beforeTime
        if (distance < 0.0) {
            retraceTarget = if (-distance <= MAX_INTERVAL_DISTANCE + 1e-9) {
                max(retraceTarget ?: beforePoint, beforePoint)
            } else null
            return
        }
        val target = retraceTarget
        if (target != null) {
            if (point >= target - UNCHANGED_POINT_EPSILON) retraceTarget = null
            return
        }
        if (distance < MIN_INTERVAL_DISTANCE || distance > MAX_INTERVAL_DISTANCE + 1e-9 ||
            elapsed !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) return

        val rate = distance / elapsed.toDouble()
        if (rate > MAX_PACE_PER_MILLIS + 1e-12) return
        if (samples.size >= MIN_SAMPLES) {
            val medianDistance = unweightedMedian(samples.map { it.distance })
            val medianRate = weightedMedianRate()
            if (distance > MULTI_PAGE_STEP_FACTOR * medianDistance && rate > MULTI_PAGE_RATE_FACTOR * medianRate) return
        }

        samples.addLast(Sample(distance, elapsed))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    fun remainingMinutes(point: Double, chapterEnd: Double): Int? {
        if (!point.isFinite() || !chapterEnd.isFinite() || point !in 0.0..1.0 || chapterEnd !in 0.0..1.0) return null
        if (chapterEnd <= point + UNCHANGED_POINT_EPSILON) return 0
        val elapsed = samples.sumOf { it.elapsedMillis }
        if (samples.size < MIN_SAMPLES || elapsed < MIN_TOTAL_ELAPSED_MILLIS) return null
        val ratePerMillis = estimatedRatePerMillis()
        if (!ratePerMillis.isFinite() || ratePerMillis <= 0.0) return null
        val minutes = (chapterEnd - point) / (ratePerMillis * 60_000.0)
        if (!minutes.isFinite() || minutes < 0.0 || minutes > MAX_REMAINING_MINUTES) return null
        if (minutes < SUB_MINUTE_THRESHOLD) return 0
        return ceil(minutes - MINUTE_ROUNDING_EPSILON).toInt().coerceAtLeast(1)
    }

    private fun estimatedRatePerMillis(): Double {
        val count = samples.size
        val weighted = samples.mapIndexed { index, sample ->
            sample.ratePerMillis to RECENCY_DECAY.pow((count - 1 - index).toDouble())
        }
        val medianRate = weightedMedian(weighted)
        val mad = weightedMedian(weighted.map { (rate, weight) -> abs(rate - medianRate) to weight })
        val spread = max(MAD_SPREAD_FACTOR * mad, MIN_RELATIVE_SPREAD * medianRate)
        val low = max(MIN_CLAMP_RATIO * medianRate, medianRate - spread)
        val high = min(MAX_CLAMP_RATIO * medianRate, medianRate + spread)
        val totalWeight = weighted.sumOf { it.second }
        if (totalWeight <= 0.0) return 0.0
        return weighted.sumOf { (rate, weight) -> weight * rate.coerceIn(low, high) } / totalWeight
    }

    private fun weightedMedianRate(): Double {
        val count = samples.size
        return weightedMedian(samples.mapIndexed { index, sample ->
            sample.ratePerMillis to RECENCY_DECAY.pow((count - 1 - index).toDouble())
        })
    }

    private fun weightedMedian(items: List<Pair<Double, Double>>): Double {
        if (items.isEmpty()) return 0.0
        val sorted = items.sortedBy { it.first }
        val threshold = sorted.sumOf { it.second } / 2.0
        var accumulated = 0.0
        for ((value, weight) in sorted) {
            accumulated += weight
            if (accumulated >= threshold - 1e-12) return value
        }
        return sorted.last().first
    }

    private fun unweightedMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
