package com.bookcover.diagnostic.reader

/**
 * PT-1 debug-only frame timing for one turn (pure Kotlin, unit tested). The controller feeds it the
 * animation frame times and how long each overlay draw took on the UI thread; debuggable builds
 * (debug and qa) log [summary] once per turn. A frame is over budget when it arrives more than
 * half a refresh interval late (at 120 Hz: an interval over 12.5 ms, i.e. at least one missed
 * 8.3 ms vsync). GPU time on the render thread is not visible here.
 */
internal class PageTurnFrameMeter {
    var refreshHz = 60f; private set
    var frames = 0; private set
    var overBudget = 0; private set
    var worstIntervalNanos = 0L; private set
    var draws = 0; private set
    var worstDrawNanos = 0L; private set
    private var totalDrawNanos = 0L
    private var lastFrameNanos = 0L

    val budgetNanos get() = (1e9 / refreshHz).toLong()

    fun startTurn(refreshHz: Float) {
        this.refreshHz = refreshHz.coerceIn(30f, 240f)
        frames = 0; overBudget = 0; worstIntervalNanos = 0L
        draws = 0; worstDrawNanos = 0L; totalDrawNanos = 0L
        lastFrameNanos = 0L
    }

    /** A new animation (reveal, settle, rubber band) starts: the gap before it is not a frame. */
    fun animationStarted() { lastFrameNanos = 0L }

    fun animationFrame(nanos: Long) {
        if (lastFrameNanos != 0L) {
            val interval = nanos - lastFrameNanos
            frames++
            if (interval > budgetNanos * 3 / 2) overBudget++
            if (interval > worstIntervalNanos) worstIntervalNanos = interval
        }
        lastFrameNanos = nanos
    }

    fun drawn(durationNanos: Long) {
        draws++
        totalDrawNanos += durationNanos
        if (durationNanos > worstDrawNanos) worstDrawNanos = durationNanos
    }

    fun summary(): String {
        val averageDraw = if (draws == 0) 0.0 else totalDrawNanos / draws / 1e6
        return "frames=$frames; overBudget=$overBudget @${refreshHz.toInt()}Hz (budget ${ms(budgetNanos)}ms); " +
            "worstFrame=${ms(worstIntervalNanos)}ms; draws=$draws; drawAvg=${"%.2f".format(java.util.Locale.ROOT, averageDraw)}ms; " +
            "drawWorst=${ms(worstDrawNanos)}ms"
    }

    private fun ms(nanos: Long) = "%.1f".format(java.util.Locale.ROOT, nanos / 1e6)
}
