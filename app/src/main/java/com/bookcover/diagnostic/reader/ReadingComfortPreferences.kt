package com.bookcover.diagnostic.reader

import android.content.SharedPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

internal fun SharedPreferences.readingComfort(): ReadingComfort = ReadingComfort(
    publisherStyles = getBoolean("publisherStyles", ReadingComfort.defaultPublisherStyles(contains("fontScale") || contains("fontId"))),
    lineSpacing = ReadingComfort.normalizedLineSpacing(getFloat("lineSpacing", 1.5f).toDouble()),
    margins = ReadingMargins.fromId(getString("pageMargins", null)),
    alignment = ReadingAlignment.fromId(getString("textAlignment", null)),
)

internal fun SharedPreferences.saveReadingTypography(comfort: ReadingComfort, fontScale: Double, fontId: String) {
    edit().putBoolean("publisherStyles", comfort.publisherStyles)
        .putFloat("lineSpacing", comfort.lineSpacing.toFloat()).putString("pageMargins", comfort.margins.id)
        .putString("textAlignment", comfort.alignment.id)
        .putFloat("fontScale", fontScale.toFloat()).putString("fontId", fontId).apply()
}

/**
 * Do not submit saved custom overrides while the book's own formatting is selected. Text size is
 * not one of them: Readium CSS applies `--USER__fontSize` with or without publisher styles, so the
 * reader's size always applies and the book keeps its fonts, indents and layout (C1).
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun ReadingComfort.epubPreferences(fontScale: Double, fontId: String, themeName: String) = EpubPreferences(
    fontFamily = if (publisherStyles) null else FontFamily(ReadingFont.fromId(fontId).cssFamily),
    fontSize = fontScale,
    lineHeight = if (publisherStyles) null else lineSpacing,
    pageMargins = if (publisherStyles) 1.0 else margins.factor,
    paragraphSpacing = if (publisherStyles) null else 0.6,
    paragraphIndent = if (publisherStyles) null else 1.2,
    textAlign = if (publisherStyles) null else when (alignment) {
        ReadingAlignment.START -> TextAlign.START
        ReadingAlignment.JUSTIFIED -> TextAlign.JUSTIFY
    },
    columnCount = ColumnCount.ONE, scroll = false, publisherStyles = publisherStyles,
    theme = when (themeName) { "Night" -> Theme.DARK; "Sepia" -> Theme.SEPIA; else -> Theme.LIGHT },
)

/** PT-1: off for the first build so the owner can compare both styles; the smoothed slide is the default turn. */
internal const val PAPER_PAGE_TURN_DEFAULT = false
private const val PAPER_PAGE_TURN_KEY = "paperPageTurn"

internal fun SharedPreferences.paperPageTurn(): Boolean = getBoolean(PAPER_PAGE_TURN_KEY, PAPER_PAGE_TURN_DEFAULT)

internal fun SharedPreferences.savePaperPageTurn(enabled: Boolean) {
    edit().putBoolean(PAPER_PAGE_TURN_KEY, enabled).apply()
}
