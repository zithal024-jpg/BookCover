package com.bookcover.diagnostic.library

import kotlin.math.roundToInt

/** Size rules for library-tile thumbnails: at most 400×600 px, about 1 MB decoded instead of ~6.9 MB. */
internal object CoverThumbnail {
    const val MAX_WIDTH = 400
    const val MAX_HEIGHT = 600

    /** The thumbnail size for an image: fits inside MAX_WIDTH × MAX_HEIGHT, keeps its shape, never enlarges. */
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** BitmapFactory.inSampleSize: the largest power-of-two step that still decodes at least [fit]'s size. */
    fun sampleSize(width: Int, height: Int): Int {
        val (fitWidth, fitHeight) = fit(width, height)
        var sample = 1
        while (width / (sample * 2) >= fitWidth && height / (sample * 2) >= fitHeight) sample *= 2
        return sample
    }
}
