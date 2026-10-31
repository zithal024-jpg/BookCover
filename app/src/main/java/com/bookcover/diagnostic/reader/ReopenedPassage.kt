package com.bookcover.diagnostic.reader

/**
 * Readium 3.4.0's first [currentLocator] value for a reopened book is the same locator
 * the book was opened with, before the page has moved. That match is exact: progression
 * is one of the fields, and a later report that differs only there is a real position.
 * Describing the screen for the exact echo returns the chapter start and must be ignored.
 * Any other report is saved from the visible page, then the previous anchor, then itself.
 */
internal fun <T> reportIsOpenedPassage(holding: Boolean, reported: T, opened: T?): Boolean =
    holding && opened != null && reported == opened

internal fun <T> passageReportedWhileHolding(
    holding: Boolean,
    reported: T,
    opened: T?,
    visible: T?,
    anchor: T?,
): T = if (reportIsOpenedPassage(holding, reported, opened)) reported else visible ?: anchor ?: reported

/** The hold ends once the saved passage is a different resource or a different quote. */
internal fun openedHoldContinues(
    holding: Boolean,
    openedHref: Any?,
    openedHighlight: String?,
    savedHref: Any?,
    savedHighlight: String?,
): Boolean = holding && savedHref == openedHref && savedHighlight == openedHighlight
