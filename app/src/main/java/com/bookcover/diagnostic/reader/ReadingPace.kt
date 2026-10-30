package com.bookcover.diagnostic.reader

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Learns publication-coordinate distance per active reading interval; never assumes a WPM rate.
 * The caller pauses around jumps, reflow and viewport changes, and holds the clock while controls
 * are shown or the app is in the background.
 * A pace remembered from earlier sessions ([seed]) gives an immediate estimate; live samples
 * then progressively outweigh it, so the estimate follows the reader's current speed. */
internal class ReadingPace {
    private companion object {
        const val MIN_SAMPLES = 3
        const val MIN_TOTAL_ELAPSED_MILLIS = 45_000L
        const val MAX_SAMPLES = 20
        const val MIN_INTERVAL_MILLIS = 3_000L
        const val MAX_INTERVAL_MILLIS = 300_000L
        const val UNCHANGED_POINT_EPSILON = 1e-6
        /** One screen of a ~100,000-screen book; larger floors silence estimates in long books. */
        const val MIN_INTERVAL_DISTANCE = 0.00001
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
        const val MAX_BOOK_REMAINING_MINUTES = 100 * 60
        /** The remembered pace counts as this many live samples; a full 20-sample window outweighs it 5:1. */
        const val PRIOR_WEIGHT = 4.0
    }

    private data class Sample(val distance: Double, val elapsedMillis: Long) {
        val ratePerMillis: Double get() = distance / elapsedMillis.toDouble()
    }

    private val samples = ArrayDeque<Sample>()
    private var previousPoint: Double? = null
    private var previousTime: Long? = null
    private var retraceTarget: Double? = null
    private var priorRatePerMillis: Double? = null
    private var heldSince: Long? = null

    /** Starts from a remembered pace (publication fraction per millisecond); invalid values are ignored. */
    fun seed(ratePerMillis: Double) {
        if (ratePerMillis.isFinite() && ratePerMillis > 0.0 && ratePerMillis <= MAX_PACE_PER_MILLIS) {
            priorRatePerMillis = ratePerMillis
        }
    }

    /** Breaks the candidate interval while keeping previously learned pace for this session.
     * A pending re-read mark survives so opening controls mid-retrace does not count old pages. */
    fun pause() {
        previousPoint = null
        previousTime = null
        heldSince = null
    }

    /** Stops the clock without discarding the page being read (controls shown, app in background).
     * Breaking the interval instead cost two page turns per glance at the controls, so a reader
     * who checked the estimate while reading never accumulated enough samples to see one. */
    fun hold(nowMillis: Long) {
        if (heldSince == null && previousTime != null && nowMillis >= 0) heldSince = nowMillis
    }

    /** Restarts the clock; the held time is left out of the current page's interval. */
    fun release(nowMillis: Long) {
        val since = heldSince ?: return
        heldSince = null
        val before = previousTime ?: return
        if (nowMillis >= since) previousTime = before + (nowMillis - since) else pause()
    }

    /** Timed page turns still needed before a live estimate exists; 0 once any estimate is available. */
    fun pagesUntilEstimate(): Int = when {
        currentRatePerMillis() != null -> 0
        else -> (MIN_SAMPLES - samples.size).coerceAtLeast(1)
    }

    /** Returns true when this observation added a measured reading sample. */
    fun observe(point: Double, nowMillis: Long, eligible: Boolean): Boolean {
        val unchanged = previousPoint?.let { point.isFinite() && abs(point - it) <= UNCHANGED_POINT_EPSILON } == true
        // A repeated notification for the page being read (e.g. when controls open) keeps the interval.
        if ((!eligible || heldSince != null) && unchanged) return false
        if (!eligible || heldSince != null || !point.isFinite() || point !in 0.0..1.0 || nowMillis < 0) {
            pause()
            return false
        }
        val beforePoint = previousPoint
        val beforeTime = previousTime
        if (beforePoint != null && beforeTime != null && nowMillis >= beforeTime &&
            abs(point - beforePoint) <= UNCHANGED_POINT_EPSILON) return false
        previousPoint = point
        previousTime = nowMillis
        if (beforePoint == null || beforeTime == null) {
            // First point after a break: a jump past or far away from the re-read mark ends it.
            val target = retraceTarget
            if (target != null && (point >= target - UNCHANGED_POINT_EPSILON ||
                    target - point > MAX_INTERVAL_DISTANCE + 1e-9)) retraceTarget = null
            return false
        }
        if (nowMillis <= beforeTime) return false

        val distance = point - beforePoint
        val elapsed = nowMillis - beforeTime
        if (distance < 0.0) {
            retraceTarget = if (-distance <= MAX_INTERVAL_DISTANCE + 1e-9) {
                max(retraceTarget ?: beforePoint, beforePoint)
            } else null
            return false
        }
        val target = retraceTarget
        if (target != null) {
            if (point >= target - UNCHANGED_POINT_EPSILON) retraceTarget = null
            return false
        }
        if (distance < MIN_INTERVAL_DISTANCE || distance > MAX_INTERVAL_DISTANCE + 1e-9 ||
            elapsed !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) return false

        val rate = distance / elapsed.toDouble()
        if (rate > MAX_PACE_PER_MILLIS + 1e-12) return false
        if (samples.size >= MIN_SAMPLES) {
            val medianDistance = unweightedMedian(samples.map { it.distance })
            val medianRate = weightedMedianRate()
            if (distance > MULTI_PAGE_STEP_FACTOR * medianDistance && rate > MULTI_PAGE_RATE_FACTOR * medianRate) return false
        }

        samples.addLast(Sample(distance, elapsed))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        return true
    }

    fun remainingMinutes(point: Double, chapterEnd: Double): Int? =
        minutesBetween(point, chapterEnd, MAX_REMAINING_MINUTES)

    /** Time to the end of the publication; a long book may legitimately need many hours. */
    fun bookRemainingMinutes(point: Double): Int? = minutesBetween(point, 1.0, MAX_BOOK_REMAINING_MINUTES)

    /** The remembered pace until enough live reading is measured, then a blend that leans further on
     * live reading with each sample (which are themselves recency-weighted). */
    fun currentRatePerMillis(): Double? {
        val prior = priorRatePerMillis
        val live = liveRatePerMillis()
        return when {
            live == null -> prior
            prior == null -> live
            else -> (PRIOR_WEIGHT * prior + samples.size * live) / (PRIOR_WEIGHT + samples.size)
        }
    }

    /** The pace worth remembering for later sessions; null until live reading has been measured. */
    fun learnedRatePerMillis(): Double? = if (liveRatePerMillis() == null) null else currentRatePerMillis()

    private fun liveRatePerMillis(): Double? {
        val elapsed = samples.sumOf { it.elapsedMillis }
        if (samples.size < MIN_SAMPLES || elapsed < MIN_TOTAL_ELAPSED_MILLIS) return null
        return estimatedRatePerMillis().takeIf { it.isFinite() && it > 0.0 }
    }

    private fun minutesBetween(point: Double, end: Double, maxMinutes: Int): Int? {
        if (!point.isFinite() || !end.isFinite() || point !in 0.0..1.0 || end !in 0.0..1.0) return null
        if (end <= point + UNCHANGED_POINT_EPSILON) return 0
        val ratePerMillis = currentRatePerMillis() ?: return null
        val minutes = (end - point) / (ratePerMillis * 60_000.0)
        if (!minutes.isFinite() || minutes < 0.0 || minutes > maxMinutes) return null
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
