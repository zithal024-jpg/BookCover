package com.bookcover.diagnostic.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextWindowTest {
    @Test fun pageSeparatorsPreserveSourceNewlinesAndCorrectOffsets() {
        data class Case(val first: String, val second: String, val joined: String, val secondStart: Int)
        val cases = listOf(
            Case("first", "second", "first\nsecond", 6),
            Case("first\n", "second", "first\nsecond", 6),
            Case("first", "\nsecond", "first\nsecond", 5),
            Case("first\n", "\nsecond", "first\n\nsecond", 6),
        )
        for (case in cases) {
            val window = pdfTextWindow(listOf(PdfTextWindowPage(1, case.first), PdfTextWindowPage(2, case.second)))
            assertEquals(case.joined, window.text)
            assertEquals(listOf(0, case.secondStart), window.starts)
            assertEquals(case.first, window.text.substring(0, case.first.length))
            assertEquals(case.second, window.text.substring(window.starts[1]))
        }
    }

    @Test fun emptyAndSinglePageWindowsHaveStableOffsets() {
        assertEquals("", pdfTextWindow(emptyList()).text)
        assertEquals(emptyList<Int>(), pdfTextWindow(emptyList()).starts)
        val window = pdfTextWindow(listOf(PdfTextWindowPage(9, "\ntext\n")))
        assertEquals("\ntext\n", window.text)
        assertEquals(listOf(0), window.starts)
    }
}
