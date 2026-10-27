package com.bookcover.diagnostic.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryHomeTextTest {
    private val book = BookRecord("a".repeat(64), "Title", "Author", 0L, collection = "Poetry")

    @Test fun chapterComesFromTheSavedReadiumLocator() {
        assertEquals("The Long Stair", chapterTitle("""{"href":"c3.xhtml","title":" The Long Stair ","locations":{}}"""))
    }

    @Test fun missingBlankOrBrokenLocatorsGiveNoChapter() {
        assertNull(chapterTitle(null))
        assertNull(chapterTitle("""{"href":"c3.xhtml"}"""))
        assertNull(chapterTitle("""{"title":"   "}"""))
        assertNull(chapterTitle("not json"))
    }

    @Test fun tileStatusNamesProgressAndOptionallyTheCollection() {
        assertEquals("New", tileStatus(book, showCollection = false))
        assertEquals("New · Poetry", tileStatus(book, showCollection = true))
        assertEquals("Started", tileStatus(book.copy(progression = 0.004), showCollection = false))
        assertEquals("42%", tileStatus(book.copy(progression = 0.42), showCollection = false))
        assertEquals("Finished · Poetry", tileStatus(book.copy(progression = 1.0), showCollection = true))
    }
}
