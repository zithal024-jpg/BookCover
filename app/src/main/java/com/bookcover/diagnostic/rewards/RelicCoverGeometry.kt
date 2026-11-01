package com.bookcover.diagnostic.rewards

import kotlin.math.max
import kotlin.math.min

/** Drawing coordinates stay independent of Android so extreme cover shapes can be checked on the JVM. */
internal data class RelicRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun inset(amount: Float): RelicRect {
        val x = amount.coerceIn(0f, width / 2f)
        val y = amount.coerceIn(0f, height / 2f)
        return RelicRect(left + x, top + y, right - x, bottom - y)
    }
}

internal data class RelicCoverLayout(
    val viewport: RelicRect,
    val image: RelicRect,
    val frame: RelicRect,
    val plate: RelicRect?,
    val seal: RelicRect,
)

/** Strip actual control/format characters; the regex property escapes are interpreted by Regex. */
internal fun relicInscription(raw: String): String =
    raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(80).ifBlank { "My library" }

private fun fitRelicImage(bounds: RelicRect, imageWidth: Int, imageHeight: Int): RelicRect {
    if (imageWidth <= 0 || imageHeight <= 0 || bounds.width <= 0f || bounds.height <= 0f) return bounds
    val scale = min(bounds.width / imageWidth.toDouble(), bounds.height / imageHeight.toDouble())
    val w = (imageWidth * scale).toFloat()
    val h = (imageHeight * scale).toFloat()
    return RelicRect(bounds.centerX - w / 2, bounds.centerY - h / 2, bounds.centerX + w / 2, bounds.centerY + h / 2)
}

/**
 * Fit the book and its rails as one composition. Framing the entire phone instead
 * puts the corners far away from a normal cover on a tall, narrow outside display. Limit only
 * the composition's aspect for unusual source images; the source itself always stays contain-fit.
 */
private fun fitRelicComposition(bounds: RelicRect, imageWidth: Int, imageHeight: Int,
    appearance: RelicAppearance): RelicRect {
    val coverAspect = if (appearance.mode == RelicMode.STANDALONE || imageWidth <= 0 || imageHeight <= 0)
        2f / 3f else (imageWidth.toDouble() / imageHeight).coerceIn(.5, 1.6).toFloat()
    val rail = .07f
    val heightPerWidth = (1f - rail * 2f) / coverAspect + rail * 2f
    val width = min(bounds.width, bounds.height / heightPerWidth)
    val height = width * heightPerWidth
    return RelicRect(bounds.centerX - width / 2f, bounds.centerY - height / 2f,
        bounds.centerX + width / 2f, bounds.centerY + height / 2f)
}

internal fun relicCoverLayout(width: Int, height: Int, imageWidth: Int, imageHeight: Int,
    appearance: RelicAppearance): RelicCoverLayout {
    val w = width.coerceAtLeast(0).toFloat()
    val h = height.coerceAtLeast(0).toFloat()
    val viewport = RelicRect(0f, 0f, w, h)
    val fullImage = fitRelicImage(viewport, imageWidth, imageHeight)
    if (!appearance.enabled) return RelicCoverLayout(viewport, fullImage, viewport, null, viewport.inset(min(w, h) / 2))
    val unit = min(w, h)
    // Leave room for corner strokes and the crescent beyond the frame, inside the safe viewport.
    val padding = unit * 0.045f
    val frame = if (appearance.mode == RelicMode.OVERLAY && imageWidth > 0 && imageHeight > 0)
        fullImage.inset(min(fullImage.width, fullImage.height) * 0.045f)
        else fitRelicComposition(viewport.inset(padding), imageWidth, imageHeight, appearance)
    val strip = min(frame.width, frame.height) * 0.07f
    val image = when (appearance.mode) {
        RelicMode.OVERLAY -> if (imageWidth > 0 && imageHeight > 0) fullImage else frame.inset(strip)
        RelicMode.STANDALONE -> frame.inset(strip)
        RelicMode.MARGIN -> fitRelicImage(RelicRect(frame.left + strip, frame.top + strip,
            frame.right - strip, max(frame.top + strip, frame.bottom - strip)), imageWidth, imageHeight)
    }
    val sealSize = strip * 0.72f
    val seal = RelicRect(frame.left + (strip - sealSize) / 2, frame.centerY - sealSize / 2,
        frame.left + (strip + sealSize) / 2, frame.centerY + sealSize / 2)
    // Kept nullable for compatibility with earlier layout consumers. Legacy appearance fields
    // never reserve space or render a Bookplate.
    return RelicCoverLayout(viewport, image, frame, null, seal)
}

internal fun relicMotionAllowed(appearance: RelicAppearance, attached: Boolean, visible: Boolean,
    presentationVisible: Boolean, systemAnimations: Boolean, released: Boolean): Boolean =
    appearance.enabled && appearance.edgeTier == MoonlitTier.MYTHIC && appearance.motion && attached &&
        visible && presentationVisible && systemAnimations && !released
