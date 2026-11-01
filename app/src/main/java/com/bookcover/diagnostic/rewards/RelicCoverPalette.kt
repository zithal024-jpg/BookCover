package com.bookcover.diagnostic.rewards

import com.bookcover.diagnostic.cover.matchingCoverColor
import com.bookcover.diagnostic.library.contrastRatio
import com.bookcover.diagnostic.library.extractCoverSeed
import com.bookcover.diagnostic.library.mixColors
import com.bookcover.diagnostic.library.relativeLuminance
import kotlin.math.max
import kotlin.math.min

internal data class RelicCoverPalette(val background: Int, val ink: Int, val keyline: Int,
    val plate: Int, val plateText: Int, val secondary: Int)

private const val RELIC_LIGHT = -0x11100b
private const val RELIC_DARK = -0xe5e0df
private val MOONLIT_ACCENT = 0xffa395bc.toInt()

private fun opaqueRelicColor(color: Int): Int = color or (0xff shl 24)
private fun neutralRelicColor(color: Int): Boolean {
    val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
    return max(r, max(g, b)) - min(r, min(g, b)) < 18
}

private fun readableRelicColor(preferred: Int, background: Int, ratio: Double): Int {
    if (contrastRatio(preferred, background) >= ratio) return preferred
    val black = 0xff000000.toInt(); val white = 0xffffffff.toInt()
    val target = if (contrastRatio(black, background) >= contrastRatio(white, background)) black else white
    var low = 0.0; var high = 1.0
    repeat(24) {
        val amount = (low + high) / 2
        if (contrastRatio(mixColors(preferred, target, amount), background) >= ratio) high = amount else low = amount
    }
    return mixColors(preferred, target, high)
}

/** One settled palette per cover/layout. A keyline and opaque plate protect busy-cover overlays. */
internal fun relicCoverPalette(globalSamples: IntArray, footprintSamples: IntArray, fallbackBackground: Int,
    mode: RelicColorMode, customAccent: Int, set: RelicSet = RelicSet.MOONLIT): RelicCoverPalette {
    val usableGlobal = globalSamples.filter { it ushr 24 >= 192 }.toIntArray()
    val usableLocal = footprintSamples.filter { it ushr 24 >= 192 }.toIntArray()
    val background = if (usableLocal.isEmpty()) opaqueRelicColor(fallbackBackground) else matchingCoverColor(usableLocal)
    val source = if (usableGlobal.isEmpty()) MOONLIT_ACCENT else extractCoverSeed(usableGlobal)
    val setAccent = when (set) {
        RelicSet.MOONLIT -> MOONLIT_ACCENT
        RelicSet.WAYFARER -> 0xff4b8c88.toInt()
        RelicSet.HEARTHKEEPER -> 0xffba7744.toInt()
    }
    val grey = run {
        val value = ((source ushr 16 and 255) + (source ushr 8 and 255) + (source and 255)) / 3
        (0xff shl 24) or (value shl 16) or (value shl 8) or value
    }
    val preferred = when (mode) {
        RelicColorMode.LIGHT -> RELIC_LIGHT
        RelicColorMode.DARK -> RELIC_DARK
        RelicColorMode.SET -> setAccent
        RelicColorMode.CUSTOM -> opaqueRelicColor(customAccent)
        RelicColorMode.AUTO -> if (neutralRelicColor(source)) grey else mixColors(source, grey, 0.34)
    }
    val localLuminances = usableLocal.map(::relativeLuminance)
    val busy = localLuminances.isNotEmpty() &&
        localLuminances.max() - localLuminances.min() > .28
    // Highly varied pixels cannot share one harmonious contrasting hue. Use a neutral two-tone
    // edge in Automatic; the set's shape still identifies it and the artwork is never washed out.
    val ink = if (mode == RelicColorMode.AUTO && busy) {
        if (contrastRatio(0xff000000.toInt(), background) >= contrastRatio(0xffffffff.toInt(), background))
            0xff000000.toInt() else 0xffffffff.toInt()
    } else readableRelicColor(preferred, background, 3.0)
    val keyline = if (relativeLuminance(ink) > 0.35) 0xff000000.toInt() else 0xffffffff.toInt()
    // Standalone titles and seals use an opaque backing, never arbitrary book pixels. Original
    // set colors also supply a parchment/amber surface; Automatic stays independent of the set.
    val darkSurface = relativeLuminance(ink) > 0.35
    val surface = if (mode == RelicColorMode.SET) when (set) {
        RelicSet.MOONLIT -> if (darkSurface) 0xff111918.toInt() else 0xfffcfaf3.toInt()
        RelicSet.WAYFARER -> if (darkSurface) 0xff172f30.toInt() else 0xfff4eddc.toInt()
        RelicSet.HEARTHKEEPER -> if (darkSurface) 0xff2b1c16.toInt() else 0xffffefda.toInt()
    } else if (darkSurface) 0xff111918.toInt() else 0xfffcfaf3.toInt()
    val plate = mixColors(if (mode == RelicColorMode.SET) setAccent else source, surface, 0.93)
    val plateText = readableRelicColor(preferred, plate, 4.5)
    return RelicCoverPalette(background, ink, keyline, plate, plateText,
        readableRelicColor(mixColors(ink, background, 0.15), background, 3.0))
}
