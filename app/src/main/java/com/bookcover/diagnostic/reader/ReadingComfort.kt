package com.bookcover.diagnostic.reader

import kotlin.math.roundToInt

internal enum class ReadingMargins(val id: String, val label: String, val factor: Double) {
    NARROW("narrow", "Narrow", 0.5), STANDARD("standard", "Standard", 1.0), WIDE("wide", "Wide", 2.0);
    companion object {
        fun fromId(id: String?): ReadingMargins = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

internal enum class ReadingAlignment(val id: String, val label: String, val css: String) {
    START("start", "Start", "start"), JUSTIFIED("justified", "Justified", "justify");
    companion object {
        fun fromId(id: String?): ReadingAlignment = entries.firstOrNull { it.id == id } ?: START
    }
}

/** Stored custom values remain available when the reader temporarily chooses book formatting. */
internal data class ReadingComfort(
    val publisherStyles: Boolean = true,
    val lineSpacing: Double = 1.5,
    val margins: ReadingMargins = ReadingMargins.STANDARD,
    val alignment: ReadingAlignment = ReadingAlignment.START,
) {
    companion object {
        val lineSpacingChoices = listOf(1.2, 1.5, 1.8, 2.0)
        fun normalizedLineSpacing(value: Double): Double = if (!value.isFinite()) 1.5 else
            ((value.coerceIn(1.0, 2.0) * 10).roundToInt() / 10.0)

        // Preserve existing readers' explicitly selected custom typography on upgrade. A fresh
        // installation defaults to book formatting; this is not a silent reset of saved settings.
        fun defaultPublisherStyles(hasSavedTypography: Boolean): Boolean = !hasSavedTypography
    }
}
