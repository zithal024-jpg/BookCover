package com.bookcover.diagnostic.reader

import org.readium.r2.shared.publication.Locator

/**
 * Readium 3.4.0's first location value is the locator the book was opened with.
 * See EpubNavigatorFragment.kt lines 1004–1008 at commit 1b1f6b308a7b6f968b2bf1e66c84912879466f75.
 *
 * A later report can change progression or other fields before the page has moved, while the
 * screen still shows the chapter start. That report is still the opened placement when the
 * chapter href and the text quote match, or when the href and the best stable marker match.
 * The stable marker is the css selector, otherwise a fragment. The opened locator is kept.
 * When the quote and the marker disagree, or the chapter matches but neither is present, the
 * match is ambiguous: it is logged and the opened locator is still kept.
 */
internal enum class OpenedPlacement { MATCH, AMBIGUOUS, DIFFERENT }

internal fun classifyOpenedPlacement(reported: Locator, opened: Locator): OpenedPlacement {
    if (passageHref(reported) != passageHref(opened)) return OpenedPlacement.DIFFERENT
    val quotes = sameText(passageQuote(reported), passageQuote(opened))
    val markers = sameText(passageMarker(reported), passageMarker(opened))
    val quoteAgrees = quotes == true
    val markerAgrees = markers == true
    val quoteConflicts = quotes == false
    val markerConflicts = markers == false
    return when {
        quoteAgrees && markerConflicts -> OpenedPlacement.AMBIGUOUS
        markerAgrees && quoteConflicts -> OpenedPlacement.AMBIGUOUS
        quoteAgrees || markerAgrees -> OpenedPlacement.MATCH
        quoteConflicts || markerConflicts -> OpenedPlacement.DIFFERENT
        else -> OpenedPlacement.AMBIGUOUS
    }
}

internal fun ambiguousReopenMessage(reported: Locator, opened: Locator): String {
    val quotes = sameText(passageQuote(reported), passageQuote(opened))
    val markers = sameText(passageMarker(reported), passageMarker(opened))
    return if (quotes == null && markers == null)
        "EPUB reopen hold is ambiguous; the chapter matches but the report has no quote or stable marker"
    else
        "EPUB reopen hold is ambiguous; the quote and the stable marker disagree"
}

internal fun reportIsOpenedPassage(
    holding: Boolean,
    reported: Locator,
    opened: Locator?,
    onAmbiguous: (String) -> Unit = {},
): Boolean {
    if (!holding || opened == null) return false
    return when (classifyOpenedPlacement(reported, opened)) {
        OpenedPlacement.MATCH -> true
        OpenedPlacement.AMBIGUOUS -> {
            onAmbiguous(ambiguousReopenMessage(reported, opened))
            true
        }
        OpenedPlacement.DIFFERENT -> false
    }
}

internal fun passageReportedWhileHolding(
    holding: Boolean,
    reported: Locator,
    opened: Locator?,
    visible: Locator?,
    anchor: Locator?,
    onAmbiguous: (String) -> Unit = {},
): Locator {
    if (reportIsOpenedPassage(holding, reported, opened, onAmbiguous)) return opened!!
    val fallback = visible ?: anchor ?: reported
    return fallback.copy(locations = fallback.locations.copy(
        totalProgression = reported.locations.totalProgression,
        position = reported.locations.position,
    ))
}

/** The hold ends once the saved passage is a different resource or a different quote. */
internal fun openedHoldContinues(
    holding: Boolean,
    openedHref: Any?,
    openedHighlight: String?,
    savedHref: Any?,
    savedHighlight: String?,
): Boolean = holding && savedHref == openedHref && savedHighlight == openedHighlight

private fun passageHref(locator: Locator): String =
    locator.href.removeFragment().toString().removePrefix("/")

private fun passageQuote(locator: Locator): String? =
    locator.text.highlight?.takeIf { it.isNotBlank() }

/** Css selector when the page described one, otherwise the fragment id. */
private fun passageMarker(locator: Locator): String? =
    (locator.locations["cssSelector"] as? String)?.takeIf { it.isNotBlank() }
        ?: locator.locations.fragments.firstOrNull { it.isNotBlank() }
        ?: locator.href.fragment?.takeIf { it.isNotBlank() }

private fun sameText(left: String?, right: String?): Boolean? = when {
    left != null && right != null -> left == right
    else -> null
}
