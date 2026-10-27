package com.bookcover.diagnostic.library

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Sample an even grid without allocating a full-size bitmap; caller performs decoding/sampling on IO. */
internal fun coverThemeSeed(bitmap: Bitmap): Int {
    val width = minOf(48, bitmap.width)
    val height = minOf(48, bitmap.height)
    val samples = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        samples[y * width + x] = bitmap.getPixel(
            ((x + 0.5) * bitmap.width / width).toInt().coerceAtMost(bitmap.width - 1),
            ((y + 0.5) * bitmap.height / height).toInt().coerceAtMost(bitmap.height - 1),
        )
    }
    return extractCoverSeed(samples)
}

internal fun libraryColorScheme(themeId: String, coverSeed: Int = DEFAULT_COVER_SEED, systemDark: Boolean = false): ColorScheme =
    colorScheme(libraryPalette(themeId, coverSeed, systemDark))

internal fun readerControlsColorScheme(themeId: String, coverSeed: Int, night: Boolean): ColorScheme =
    colorScheme(readerControlsPalette(themeId, coverSeed, night))

private fun colorScheme(p: LibraryPalette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(p.primary), onPrimary = Color(p.onPrimary),
        primaryContainer = Color(p.primaryContainer), onPrimaryContainer = Color(p.onPrimaryContainer),
        secondary = Color(p.secondary), onSecondary = Color(p.onSecondary),
        secondaryContainer = Color(p.secondaryContainer), onSecondaryContainer = Color(p.onSecondaryContainer),
        tertiary = Color(p.tertiary), onTertiary = Color(p.onTertiary),
        tertiaryContainer = Color(p.tertiaryContainer), onTertiaryContainer = Color(p.onTertiaryContainer),
        background = Color(p.background), onBackground = Color(p.onBackground),
        surface = Color(p.surface), onSurface = Color(p.onSurface),
        surfaceVariant = Color(p.surfaceVariant), onSurfaceVariant = Color(p.onSurfaceVariant),
        surfaceTint = Color(p.primary), inverseSurface = Color(p.inverseSurface),
        inverseOnSurface = Color(p.inverseOnSurface), inversePrimary = Color(p.inversePrimary),
        error = Color(p.error), onError = Color(p.onError),
        errorContainer = Color(p.errorContainer), onErrorContainer = Color(p.onErrorContainer),
        outline = Color(p.outline), outlineVariant = Color(p.outlineVariant), scrim = Color.Black,
        surfaceDim = Color(p.surfaceDim), surfaceBright = Color(p.surfaceBright),
        surfaceContainerLowest = Color(p.surfaceContainerLowest), surfaceContainerLow = Color(p.surfaceContainerLow),
        surfaceContainer = Color(p.surfaceContainer), surfaceContainerHigh = Color(p.surfaceContainerHigh),
        surfaceContainerHighest = Color(p.surfaceContainerHighest),
        primaryFixed = Color(p.primaryContainer), primaryFixedDim = Color(p.primaryContainer),
        onPrimaryFixed = Color(p.onPrimaryContainer), onPrimaryFixedVariant = Color(p.onPrimaryContainer),
        secondaryFixed = Color(p.secondaryContainer), secondaryFixedDim = Color(p.secondaryContainer),
        onSecondaryFixed = Color(p.onSecondaryContainer), onSecondaryFixedVariant = Color(p.onSecondaryContainer),
        tertiaryFixed = Color(p.tertiaryContainer), tertiaryFixedDim = Color(p.tertiaryContainer),
        onTertiaryFixed = Color(p.onTertiaryContainer), onTertiaryFixedVariant = Color(p.onTertiaryContainer),
    )
}
