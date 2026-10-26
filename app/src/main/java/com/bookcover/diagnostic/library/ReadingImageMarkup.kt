package com.bookcover.diagnostic.library

import org.jsoup.nodes.Document

/** Kindle-only panel overlays have no function in Readium, but its tap filter treats all anchors
 * as interactive, even without href. Preserve their layout while making inert overlays passive.
 * Real links, named anchors and unrelated markup are left alone. */
internal fun normalizePassiveImageOverlays(document: Document) {
    document.select("a.app-amzn-magnify[data-app-amzn-magnify]:not([href])").forEach {
        it.tagName("span")
    }
}
