package com.bookcover.diagnostic.reader

internal data class SamplePage(
    val start: Int,
    val end: Int,
    val needsVerticalScroll: Boolean = false,
)

/** Character anchors survive reflow; a screen/page number would not. */
internal fun pageForAnchor(pages: List<SamplePage>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    return pages.indexOfLast {
        it.start <= offset
    }.coerceIn(0, pages.lastIndex)
}

internal fun sampleProgressPercent(total: Int, offset: Int): Int {
    if (total == 0) return 0
    val position = offset.coerceIn(0, total)
    return (position.toDouble() / total * 100).toInt().coerceIn(0, 100)
}

internal data class SamplePageFit(val characterCount: Int, val needsVerticalScroll: Boolean = false)

/** Keep exact global character ranges, including paragraph breaks, across every measured page. */
internal fun buildSamplePages(text: String, measureFit: (String) -> SamplePageFit): List<SamplePage> {
    if (text.isEmpty()) return listOf(SamplePage(0, 0))
    val result = mutableListOf<SamplePage>()
    var start = 0
    while (start < text.length) {
        val remaining = text.substring(start)
        val fit = measureFit(remaining)
        val firstCodePointLength = Character.charCount(remaining.codePointAt(0))
        var count = fit.characterCount.coerceAtLeast(firstCodePointLength).coerceAtMost(remaining.length)
        if (count < remaining.length && Character.isHighSurrogate(remaining[count - 1]) &&
            Character.isLowSurrogate(remaining[count])) {
            count = (count - 1).coerceAtLeast(firstCodePointLength)
        }
        result += SamplePage(start, start + count, fit.needsVerticalScroll || fit.characterCount <= 0)
        start += count
    }
    return result
}
