package com.bookcover.diagnostic.reader

import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder

internal data class ReadingContentsEntry(val link: Link, val depth: Int, val title: String)
internal data class ChapterStatus(
    val title: String,
    val progression: Double?,
    val remainingMinutes: Int?,
    val currentTocIndex: Int?,
    val bookRemainingMinutes: Int? = null,
)

/** Readium adapter: the arithmetic and pace policy live in independently testable pure models. */
@OptIn(ExperimentalReadiumApi::class)
internal class ReadingChapters(private val publication: Publication) {
    val entries: List<ReadingContentsEntry> = buildList {
        fun append(links: List<Link>, depth: Int) {
            if (depth > 32) return
            links.forEach { link ->
                add(ReadingContentsEntry(link, depth, link.title?.takeIf { it.isNotBlank() } ?: "Chapter"))
                append(link.children, depth + 1)
            }
        }
        append(publication.tableOfContents, 0)
    }
    private val resources = publication.readingOrder.map { it.url().removeFragment() }
    private val anchors = entries.mapIndexedNotNull { index, entry ->
        val url = entry.link.url()
        val resource = resources.indexOf(url.removeFragment())
        if (resource < 0) null else ChapterAnchor(index, resource, url.fragment?.takeIf { it.isNotBlank() }, entry.depth, entry.title)
    }
    private var model: ChapterProgressMap? = null
    private val resolvedFragments = mutableMapOf<Int, Double>()
    private var layoutGeneration = 0
    /** Readium positions (about 1,024 characters each) in the whole book; null when it supplies none. */
    var totalPositions: Int? = null
        private set

    fun invalidateLayout() { layoutGeneration++; resolvedFragments.clear() }

    /** A measured page-start locator avoids Readium's RTL fragment snapping one page early. */
    fun locatorForContents(index: Int): Locator? {
        val entry = entries.getOrNull(index) ?: return null
        val locator = publication.locatorFromLink(entry.link) ?: return null
        if (entry.link.url().fragment.isNullOrBlank()) return locator
        val progression = resolvedFragments[index] ?: return null
        return locator.copy(locations = locator.locations.copy(fragments = emptyList(), progression = progression))
    }

    private suspend fun initialize() {
        if (model != null) return
        val positions = publication.positionsByReadingOrder()
        totalPositions = positions.sumOf { it.size }.takeIf { it > 0 }
        val weights = resources.indices.map { positions.getOrNull(it)?.size?.coerceAtLeast(1) ?: 1 }
        val total = weights.sum().coerceAtLeast(1).toDouble()
        var accumulated = 0.0
        val fallback = weights.map { weight -> (accumulated / total).also { accumulated += weight } }
        val supplied = resources.indices.map { positions.getOrNull(it)?.firstOrNull()?.locations?.totalProgression }
        val starts = if (supplied.all { it != null && it.isFinite() && it in 0.0..1.0 } &&
            supplied.zipWithNext().all { (a, b) -> a!! <= b!! }) supplied.map { it!! } else fallback
        model = ChapterProgressMap(starts, publication.readingOrder.mapIndexed { index, link ->
            link.title?.takeIf { it.isNotBlank() } ?: "Section ${index + 1}"
        }, anchors)
    }

    suspend fun resolve(locator: Locator, fragment: EpubNavigatorFragment, reflowable: Boolean,
                        preferredTocIndex: Int? = null): ResolvedChapter? {
        initialize()
        val resource = resources.indexOf(locator.href.removeFragment())
        if (resource < 0) return null
        // Re-read current anchors after each settled location: embedded fonts and images can
        // repaginate without changing the Android view's dimensions.
        if (reflowable) measureFragments(resource, locator, fragment)
        return model?.resolve(resource, locator.locations.progression ?: 0.0, resolvedFragments, preferredTocIndex)
    }

    /** Read-only DOM geometry, bounded to 512 anchors in the current resource. No authored text is read. */
    private suspend fun measureFragments(resource: Int, locator: Locator, fragment: EpubNavigatorFragment) {
        val candidates = anchors.filter { it.resourceIndex == resource && it.fragment != null }.take(512)
        if (candidates.isEmpty()) return
        if (fragment.currentLocator.value.href != locator.href) return
        val ids = JSONArray(candidates.map { it.fragment }).toString()
        val path = JSONObject.quote(resources[resource].path.orEmpty())
        val nativeWidth = fragment.view?.width?.takeIf { it > 0 } ?: return
        val generation = layoutGeneration
        val result = fragment.evaluateJavascript("""
            (function(){try {
                if(!decodeURIComponent(new URL(document.baseURI).pathname).endsWith($path)) return null;
                var ids=$ids, root=document.scrollingElement, W=$nativeWidth/devicePixelRatio;
                var T=Math.max(root.scrollWidth,document.documentElement.scrollWidth,document.body.scrollWidth,W);
                var pages=Math.max(1,Math.round(T/W));
                var rtl=getComputedStyle(document.documentElement).direction==='rtl';
                return ids.map(function(id){
                    var el=document.getElementById(id)||document.getElementsByName(id)[0];
                    if(!el) return null;
                    var r=el.getClientRects()[0];
                    if(!r || getComputedStyle(el).display==='none') return null;
                    var x=rtl ? W-r.right-scrollX : r.left+scrollX;
                    // Match Readium's page-start locator when a heading begins within a column.
                    var page=Math.floor((Math.max(0,x)+1)/W);
                    return Math.max(0,Math.min(1,page/pages));
                });
            } catch(e) {return null;}})()
        """.trimIndent()) ?: return
        if (generation != layoutGeneration || fragment.view?.width != nativeWidth || fragment.currentLocator.value.href != locator.href) return
        val values = runCatching { JSONArray(result) }.getOrNull() ?: return
        candidates.forEachIndexed { index, entry ->
            if (!values.isNull(index)) values.optDouble(index).takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let { resolvedFragments[entry.tocIndex] = it }
        }
    }
}
