package com.bookcover.diagnostic.reader

import kotlin.math.roundToInt

/** Multipliers describe CSS text size, never physical points or a WebView page zoom. */
internal object ReadingTextSize {
    const val MIN_PERCENT = 80
    const val MAX_PERCENT = 220
    const val STEP_PERCENT = 10
    fun percent(scale: Double): Int = if (!scale.isFinite()) 100 else
        ((scale * 100 / STEP_PERCENT).roundToInt() * STEP_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)
    fun scale(value: Int): Double = percent(value / 100.0) / 100.0
}

internal enum class ReadingFont(val id: String, val label: String, val cssFamily: String) {
    SERIF("serif", "Serif", "serif"),
    SANS("sans", "Sans serif", "sans-serif"),
    DYSLEXIC("dyslexic", "OpenDyslexic", "OpenDyslexic");
    companion object {
        fun fromId(id: String): ReadingFont = entries.firstOrNull { it.id == id } ?: SERIF
    }
}
