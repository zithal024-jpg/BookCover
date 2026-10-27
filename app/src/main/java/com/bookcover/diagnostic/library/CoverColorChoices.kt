package com.bookcover.diagnostic.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An extracted artwork colour, before the contrast adjustments used for the app's colour roles. */
internal data class BookCoverColor(val bookId: String, val title: String, val seed: Int)

internal data class CoverColorChoice(val id: String, val label: String, val seed: Int)

private val coverColorId = Regex("cover:[0-9a-f]{6}")

/** A selected cover colour is a snapshot, so removing a book cannot invalidate a preference. */
internal fun coverColorThemeId(seed: Int): String = "cover:" + (seed and 0xFFFFFF).toString(16).padStart(6, '0')

internal fun coverColorSeed(id: String?): Int? = id?.takeIf { coverColorId.matches(it) }
    ?.substring(6)?.toInt(16)?.or(0xFF000000.toInt())

internal fun libraryThemeLabel(themeId: String): String = coverColorSeed(themeId)?.let {
    "Cover colour · ${colorCode(it)}"
} ?: LibraryThemeChoice.fromId(themeId).label

/**
 * Only live covers supply choices; there is no ever-growing history of imported colours. Sorting by
 * colour/id makes the representative stable when the library's read or import order changes. Keep
 * the selected RGB first while merging, even when its cover has changed or left the library.
 */
internal fun coverColorChoices(covers: List<BookCoverColor>, selectedTheme: String): List<CoverColorChoice> {
    val choices = mutableListOf<CoverColorChoice>()
    val selectedSeed = coverColorSeed(selectedTheme)
    if (selectedSeed != null) {
        val source = covers.filter { opaque(it.seed) == selectedSeed }.minByOrNull { it.bookId }
        choices += CoverColorChoice(selectedTheme, source?.let(::coverLabel)
            ?: "Selected cover colour · ${colorCode(selectedSeed)}", selectedSeed)
    }
    covers.sortedWith(compareBy<BookCoverColor> { coverColorThemeId(it.seed) }.thenBy { it.bookId }).forEach { cover ->
        val seed = opaque(cover.seed)
        if (choices.none { similarCoverColors(seed, it.seed) }) {
            choices += CoverColorChoice(coverColorThemeId(seed), coverLabel(cover), seed)
        }
    }
    return choices
}

private fun coverLabel(cover: BookCoverColor): String =
    "${cover.title.ifBlank { "Book cover" }} · ${colorCode(cover.seed)}"

private fun colorCode(seed: Int): String = "#" + (seed and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

private fun opaque(seed: Int): Int = seed or 0xFF000000.toInt()

/**
 * Compare artwork hues after the same mild chroma boost used by the UI. This merges small shade
 * differences without merging pale pink into neutral grey or adjacent orange/brown artwork.
 * Every member is compared to the representative (never to a chain of neighbours).
 */
private fun similarCoverColors(first: Int, second: Int): Boolean {
    val a = components(vividAccent(first))
    val b = components(vividAccent(second))
    val neutralA = a.saturation < 0.06
    val neutralB = b.saturation < 0.06
    if (neutralA != neutralB) return false
    if (neutralA) return abs(a.value - b.value) <= 0.16
    val hueDistance = abs(a.hue - b.hue).let { min(it, 360.0 - it) }
    return hueDistance <= 12.0 && abs(a.saturation - b.saturation) <= 0.16 && abs(a.value - b.value) <= 0.20
}

private data class Components(val hue: Double, val saturation: Double, val value: Double)

private fun components(color: Int): Components {
    val red = color ushr 16 and 255
    val green = color ushr 8 and 255
    val blue = color and 255
    val high = max(red, max(green, blue))
    val delta = (high - min(red, min(green, blue))).toDouble()
    val hue = if (delta == 0.0) 0.0 else when (high) {
        red -> 60 * (((green - blue) / delta) % 6)
        green -> 60 * ((blue - red) / delta + 2)
        else -> 60 * ((red - green) / delta + 4)
    }
    return Components((hue + 360) % 360, if (high == 0) 0.0 else delta / high, high / 255.0)
}
