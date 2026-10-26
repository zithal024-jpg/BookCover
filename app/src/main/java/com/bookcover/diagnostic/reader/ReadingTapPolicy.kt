package com.bookcover.diagnostic.reader

internal enum class ReadingTapAction { NONE, CONTROLS, PREVIOUS, NEXT }

/** Classifies only a completed tap delivered by Readium, never raw pointer/swipe events. */
internal fun readingTapAction(
    x: Float, y: Float, width: Float, height: Float, density: Float,
    edgeEnabled: Boolean, controlsVisible: Boolean, typographyBusy: Boolean, hasTargetElement: Boolean,
    rightToLeft: Boolean = false,
): ReadingTapAction {
    if (!x.isFinite() || !y.isFinite() || width <= 0 || height <= 0 || density <= 0 ||
        x !in 0f..width || y !in (height * 0.10f)..(height * 0.90f)) return ReadingTapAction.NONE
    if (x in (width * 0.25f)..(width * 0.75f)) return ReadingTapAction.CONTROLS
    if (!edgeEnabled || controlsVisible || typographyBusy || hasTargetElement) return ReadingTapAction.NONE
    val edge = minOf(32f * density, width * 0.06f)
    return when {
        x < edge -> if (rightToLeft) ReadingTapAction.NEXT else ReadingTapAction.PREVIOUS
        x > width - edge -> if (rightToLeft) ReadingTapAction.PREVIOUS else ReadingTapAction.NEXT
        else -> ReadingTapAction.NONE
    }
}
