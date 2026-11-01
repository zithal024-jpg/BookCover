package com.bookcover.diagnostic.reader

/** A flattened TOC row. Fragment offsets, when present, are supplied by the navigator adapter. */
internal data class ChapterAnchor(
    val tocIndex: Int,
    val resourceIndex: Int,
    val fragment: String?,
    val depth: Int,
    val title: String,
)

/** A null TOC index means the progress describes the resource, not a resolved TOC section.
 * A null end/progression means a section is known, but its exact end has not been resolved. */
internal data class ResolvedChapter(
    val title: String,
    val progression: Double?,
    val currentTocIndex: Int?,
    val globalPoint: Double,
    val globalEnd: Double?,
)

/** Pure chapter arithmetic over normalized publication coordinates, independent of page layout. */
internal class ChapterProgressMap(
    private val resourceStarts: List<Double>,
    private val resourceTitles: List<String>,
    private val anchors: List<ChapterAnchor>,
) {
    init {
        require(resourceStarts.size == resourceTitles.size)
        require(resourceStarts.all { it.isFinite() && it in 0.0..1.0 })
        require(resourceStarts.zipWithNext().all { (left, right) -> left <= right })
    }

    private data class Boundary(val anchor: ChapterAnchor, val point: Double)

    private companion object {
        /** Fraction of a resource: far below any real page, far above sub-pixel scroll rounding. */
        const val PAGE_START_SLACK = 1e-4
    }

    fun resolve(
        resourceIndex: Int,
        progression: Double,
        resolvedFragments: Map<Int, Double> = emptyMap(),
        preferredTocIndex: Int? = null,
    ): ResolvedChapter {
        if (resourceIndex !in resourceStarts.indices || !progression.isFinite()) {
            return ResolvedChapter("Current chapter", null, null, 0.0, null)
        }
        val resourceStart = resourceStarts[resourceIndex]
        val resourceEnd = resourceStarts.getOrElse(resourceIndex + 1) { 1.0 }
        val resourceProgress = progression.coerceIn(0.0, 1.0)
        val point = resourceStart + resourceProgress * (resourceEnd - resourceStart)
        fun fallback() = ResolvedChapter(
            resourceTitles[resourceIndex].ifBlank { "Section ${resourceIndex + 1}" },
            resourceProgress.takeIf { resourceEnd > resourceStart }, null, point, resourceEnd,
        )

        val validAnchors = anchors.filter { it.resourceIndex in resourceStarts.indices }
        fun offset(anchor: ChapterAnchor): Double? =
            if (anchor.fragment.isNullOrBlank()) 0.0
            else resolvedFragments[anchor.tocIndex]?.takeIf { it.isFinite() && it in 0.0..1.0 }

        val unresolved = validAnchors.filter { offset(it) == null }
        // No guessed selection when this file contains an unresolved subsection boundary.
        if (unresolved.any { it.resourceIndex == resourceIndex }) return fallback()

        val boundaries = validAnchors.mapNotNull { anchor ->
            offset(anchor)?.let { local ->
                val start = resourceStarts[anchor.resourceIndex]
                val end = resourceStarts.getOrElse(anchor.resourceIndex + 1) { 1.0 }
                Boundary(anchor, start + local * (end - start))
            }
        }
        // A measured page start and Readium's scroll-derived progression can differ by a few device
        // pixels when the column width is fractional in CSS pixels (1768 px at 2.625 dpr). That
        // sliver is still the boundary's own page, not the previous one.
        val pageStartSlack = PAGE_START_SLACK * (resourceEnd - resourceStart) + 1e-9
        val defaultActive = boundaries.filter { it.anchor.resourceIndex <= resourceIndex && it.point <= point + pageStartSlack }
            .maxWithOrNull(compareBy<Boundary> { it.point }.thenBy { it.anchor.depth }.thenBy { it.anchor.tocIndex })
            ?: return fallback()
        // Several subsection headings can share a rendered page. Preserve an explicit Contents
        // destination only among that page's tied boundaries; never pin it across later pages.
        val active = boundaries.firstOrNull { it.anchor.tocIndex == preferredTocIndex &&
            kotlin.math.abs(it.point - defaultActive.point) < 1e-9 } ?: defaultActive

        // A section beginning in an earlier file may still be current; don't skip an unknown
        // intervening boundary simply because only a preceding parent link was measurable.
        if (unresolved.any { it.resourceIndex in active.anchor.resourceIndex..resourceIndex }) return fallback()

        val next = boundaries.filter { it.point > active.point }.minByOrNull { it.point }
        val nextPoint = next?.point ?: 1.0
        val nextResource = next?.anchor?.resourceIndex ?: resourceStarts.lastIndex
        val unknownEnd = unresolved.any {
            it.resourceIndex > resourceIndex && it.resourceIndex <= nextResource &&
                resourceStarts[it.resourceIndex] < nextPoint
        }
        if (unknownEnd) return ResolvedChapter(active.anchor.title, null, active.anchor.tocIndex, point, null)

        val sectionProgress = (nextPoint - active.point).takeIf { it > 0.0 }?.let {
            ((point - active.point) / it).coerceIn(0.0, 1.0)
        }
        return ResolvedChapter(active.anchor.title, sectionProgress, active.anchor.tocIndex, point, nextPoint)
    }
}
