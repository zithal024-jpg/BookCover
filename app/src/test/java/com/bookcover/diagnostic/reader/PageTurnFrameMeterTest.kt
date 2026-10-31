package com.bookcover.diagnostic.reader

import org.junit.Assert.*
import org.junit.Test

class PageTurnFrameMeterTest {
    private val frame120 = 8_333_333L

    @Test fun countsFramesThatMissAVsyncAt120Hz() {
        val meter = PageTurnFrameMeter()
        meter.startTurn(120f)
        meter.animationStarted()
        var t = 1_000_000_000L
        repeat(30) { meter.animationFrame(t); t += frame120 }
        assertEquals(29, meter.frames)
        assertEquals(0, meter.overBudget)
        t += frame120 * 2 // one dropped frame: a 25 ms gap
        meter.animationFrame(t)
        assertEquals(1, meter.overBudget)
        assertTrue(meter.worstIntervalNanos >= 3 * frame120 - 10)
    }

    @Test fun theGapBetweenAnimationsIsNotAFrame() {
        val meter = PageTurnFrameMeter()
        meter.startTurn(60f)
        meter.animationFrame(0L + 1)
        meter.animationFrame(16_666_667L + 1)
        meter.animationStarted() // e.g. the finger was held still, then released
        meter.animationFrame(900_000_000L)
        assertEquals(1, meter.frames)
        assertEquals(0, meter.overBudget)
    }

    @Test fun drawTimesAndANewTurnReset() {
        val meter = PageTurnFrameMeter()
        meter.startTurn(120f)
        meter.drawn(1_000_000L)
        meter.drawn(3_000_000L)
        assertEquals(2, meter.draws)
        assertEquals(3_000_000L, meter.worstDrawNanos)
        val summary = meter.summary()
        assertTrue(summary, summary.contains("@120Hz") && summary.contains("drawAvg=2.00ms") && summary.contains("drawWorst=3.0ms"))
        meter.startTurn(500f)
        assertEquals(0, meter.draws)
        assertEquals(240f, meter.refreshHz, 0f)
    }
}
