package com.bookcover.diagnostic.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookRecordTest {
    @Test fun persistsCollectionsReadingPositionCoverAndBookmarks() {
        val record = BookRecord("a".repeat(64), "A title", "An author", 123, "Favourites", 456, 0.42,
            "{\"href\":\"chapter.xhtml\"}", 789, listOf("{\"href\":\"chapter2.xhtml\"}"))
        assertEquals(record, BookRecord.fromJson(JSONObject(record.toJson().toString())))
    }
    @Test fun oldRecordsDefaultToNoBookmarksAndProgressIsBounded() {
        val json = JSONObject().put("id", "b".repeat(64)).put("progression", 4.0)
        val record = BookRecord.fromJson(json)
        assertEquals(1.0, record.progression, 0.0)
        assertTrue(record.bookmarks.isEmpty())
        assertNull(record.locator)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathAsLibraryIdentifier() {
        BookRecord.fromJson(JSONObject().put("id", "../outside"))
    }
}
