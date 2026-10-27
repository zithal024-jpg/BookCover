package com.bookcover.diagnostic.library

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal val DEFAULT_COVER_SEED = 0xFF315B49.toInt()

/** Bounded RGB histogram. Opaque, sufficiently common colors vote; tiny bright details do not dominate. */
internal fun extractCoverSeed(samples: IntArray): Int {
    val count = IntArray(4096)
    val red = LongArray(4096)
    val green = LongArray(4096)
    val blue = LongArray(4096)
    var opaqueCount = 0
    samples.forEach { pixel ->
        if (pixel ushr 24 < 192) return@forEach
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        val bucket = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
        count[bucket]++; red[bucket] += r.toLong(); green[bucket] += g.toLong(); blue[bucket] += b.toLong()
        opaqueCount++
    }
    if (opaqueCount == 0) return DEFAULT_COVER_SEED
    fun color(bucket: Int): Int = opaqueColor((red[bucket] / count[bucket]).toInt(),
        (green[bucket] / count[bucket]).toInt(), (blue[bucket] / count[bucket]).toInt())
    val populated = count.indices.filter { count[it] > 0 }
    // Prefer a usable artwork color over large white margins, if it represents at least 2%.
    val chromatic = populated.filter { bucket ->
        val candidate = color(bucket)
        count[bucket] >= max(2, opaqueCount / 50) && saturation(candidate) >= 0.12 &&
            relativeLuminance(candidate) in 0.025..0.90
    }
    val candidates = chromatic.ifEmpty { populated }
    val winner = candidates.maxByOrNull { bucket -> count[bucket] * (1.0 + saturation(color(bucket))) }
        ?: return DEFAULT_COVER_SEED
    return color(winner)
}

/** [dark] is null for Automatic, which follows the system light/dark setting. */
internal enum class LibraryThemeChoice(val id: String, val label: String, val dark: Boolean?, val seed: Int?) {
    AUTOMATIC("automatic", "Automatic (Book Cover)", null, null),
    PAPER("paper", "Paper · light", false, 0xFF8A6441.toInt()),
    FOREST("forest", "Forest · light", false, DEFAULT_COVER_SEED),
    MIDNIGHT("midnight", "Midnight · dark", true, 0xFF577AAC.toInt()),
    CHARCOAL("charcoal", "Charcoal · dark", true, 0xFF84728A.toInt());

    fun isDark(systemDark: Boolean): Boolean = dark ?: systemDark

    companion object {
        fun fromId(id: String?): LibraryThemeChoice = entries.firstOrNull { it.id == id } ?: AUTOMATIC
    }
}

/** Saved "libraryTheme" preference to a known theme id; missing or unknown values mean Automatic. */
internal fun savedLibraryTheme(stored: String?): String = LibraryThemeChoice.fromId(stored).id

/** Color roles remain plain ARGB values so contrast guarantees can be exercised on the JVM. */
internal data class LibraryPalette(
    val dark: Boolean,
    val background: Int, val onBackground: Int,
    val surface: Int, val onSurface: Int,
    val surfaceVariant: Int, val onSurfaceVariant: Int,
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val outline: Int, val outlineVariant: Int,
    val inverseSurface: Int, val inverseOnSurface: Int, val inversePrimary: Int,
    val surfaceDim: Int, val surfaceBright: Int,
    val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
    val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
)

internal fun libraryPalette(themeId: String, coverSeed: Int = DEFAULT_COVER_SEED, systemDark: Boolean = false): LibraryPalette {
    val choice = LibraryThemeChoice.fromId(themeId)
    return seedPalette(choice.seed ?: coverSeed, choice.isDark(systemDark))
}

/** Reader panels: the library's accent, light or dark to match the page (Night is dark, Paper and Sepia light). */
internal fun readerControlsPalette(themeId: String, coverSeed: Int = DEFAULT_COVER_SEED, night: Boolean): LibraryPalette =
    seedPalette(LibraryThemeChoice.fromId(themeId).seed ?: coverSeed, night)

private fun seedPalette(accent: Int, dark: Boolean): LibraryPalette {
    val seed = accent or (0xFF shl 24)
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    fun tint(whiteAmount: Double): Int = mixColors(seed, if (dark) black else white, whiteAmount)
    val background = tint(if (dark) 0.91 else 0.97)
    val surface = background
    val variant = tint(if (dark) 0.72 else 0.86)
    val onSurface = mixColors(seed, if (dark) white else black, 0.90)
    val onVariant = contrastAdjusted(mixColors(seed, if (dark) white else black, 0.78), variant, 7.0)
    val primary = contrastAdjusted(seed, variant, 5.0)
    val secondarySeed = mixColors(seed, if (dark) white else black, 0.22)
    val secondary = contrastAdjusted(secondarySeed, variant, 5.0)
    val tertiarySeed = opaqueColor((seed ushr 8 and 255), (seed and 255), (seed ushr 16 and 255))
    val tertiary = contrastAdjusted(tertiarySeed, variant, 5.0)
    val primaryContainer = tint(if (dark) 0.48 else 0.78)
    val secondaryContainer = mixColors(secondarySeed, if (dark) black else white, if (dark) 0.48 else 0.83)
    val tertiaryContainer = mixColors(tertiarySeed, if (dark) black else white, if (dark) 0.48 else 0.83)
    val error = contrastAdjusted(0xFFB3261E.toInt(), variant, 5.0)
    val errorContainer = if (dark) 0xFF5F1B18.toInt() else 0xFFF9DEDC.toInt()
    val inverseSurface = mixColors(seed, if (dark) white else black, 0.91)
    return LibraryPalette(
        dark, background, onSurface, surface, onSurface, variant, onVariant,
        primary, contrastingText(primary), primaryContainer, contrastingText(primaryContainer),
        secondary, contrastingText(secondary), secondaryContainer, contrastingText(secondaryContainer),
        tertiary, contrastingText(tertiary), tertiaryContainer, contrastingText(tertiaryContainer),
        error, contrastingText(error), errorContainer, contrastingText(errorContainer),
        contrastAdjusted(seed, variant, 4.0), contrastAdjusted(seed, variant, 3.3),
        inverseSurface, contrastingText(inverseSurface), contrastAdjusted(seed, inverseSurface, 5.0),
        tint(if (dark) 0.94 else 0.86), tint(if (dark) 0.72 else 0.99),
        tint(if (dark) 0.97 else 1.0), tint(if (dark) 0.91 else 0.97),
        tint(if (dark) 0.85 else 0.94), tint(if (dark) 0.78 else 0.90), variant,
    )
}

internal fun contrastRatio(first: Int, second: Int): Double {
    val one = relativeLuminance(first)
    val two = relativeLuminance(second)
    return (max(one, two) + 0.05) / (min(one, two) + 0.05)
}

internal fun relativeLuminance(color: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color ushr 16 and 255) + 0.7152 * linear(color ushr 8 and 255) + 0.0722 * linear(color and 255)
}

internal fun mixColors(first: Int, second: Int, amount: Double): Int {
    val weight = amount.coerceIn(0.0, 1.0)
    fun channel(shift: Int): Int = (((first ushr shift and 255) * (1.0 - weight)) +
        ((second ushr shift and 255) * weight)).roundToInt()
    return opaqueColor(channel(16), channel(8), channel(0))
}

private fun saturation(color: Int): Double {
    val channels = listOf(color ushr 16 and 255, color ushr 8 and 255, color and 255)
    val high = channels.max()
    return if (high == 0) 0.0 else (high - channels.min()) / high.toDouble()
}

private fun contrastingText(background: Int): Int {
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    return if (contrastRatio(black, background) >= contrastRatio(white, background)) black else white
}

private fun contrastAdjusted(preferred: Int, background: Int, minimum: Double): Int {
    if (contrastRatio(preferred, background) >= minimum) return preferred
    val target = contrastingText(background)
    var low = 0.0
    var high = 1.0
    repeat(24) {
        val middle = (low + high) / 2
        if (contrastRatio(mixColors(preferred, target, middle), background) >= minimum) high = middle else low = middle
    }
    return mixColors(preferred, target, high)
}

private fun opaqueColor(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
