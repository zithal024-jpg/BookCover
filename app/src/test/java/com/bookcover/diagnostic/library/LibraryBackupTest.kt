package com.bookcover.diagnostic.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private val read = BookRecord("a".repeat(64), "Read", "Author", 1, "Favourites", 500, 0.4,
        "{\"href\":\"ch3.xhtml\"}", 9, listOf("{\"href\":\"ch1.xhtml\"}", "{\"href\":\"ch2.xhtml\"}"))
    private val unread = BookRecord("b".repeat(64), "Unread", "Author", 2, "Samples")

    // The same book re-imported: reading data is gone, the local cover stays as it is.
    private fun fresh(record: BookRecord) = BookRecord(record.id, record.title, record.author, record.addedAt, coverRevision = record.coverRevision)

    @Test fun exportThenRestoreBringsBackPositionsBookmarksAndCollections() {
        val backup = LibraryBackup.parse(LibraryBackup.export(listOf(read, unread), exportedAt = 1000))
        val result = LibraryBackup.merge(listOf(fresh(read), fresh(unread)), backup.records)
        assertEquals(listOf(read, unread), result.records)
        assertEquals(2, result.restored)
        assertEquals(0, result.missing)
    }

    @Test fun bookmarksAreCombinedNotReplaced() {
        val current = read.copy(bookmarks = listOf("{\"href\":\"ch2.xhtml\"}", "{\"href\":\"ch9.xhtml\"}"))
        val merged = LibraryBackup.merge(listOf(current), listOf(read)).records.single()
        assertEquals(listOf("{\"href\":\"ch2.xhtml\"}", "{\"href\":\"ch9.xhtml\"}", "{\"href\":\"ch1.xhtml\"}"), merged.bookmarks)
    }

    @Test fun moreRecentReadingPositionIsKept() {
        val newer = read.copy(lastReadAt = 900, progression = 0.8, locator = "{\"href\":\"ch8.xhtml\"}")
        val merged = LibraryBackup.merge(listOf(newer), listOf(read)).records.single()
        assertEquals(0.8, merged.progression, 0.0)
        assertEquals("{\"href\":\"ch8.xhtml\"}", merged.locator)
        assertEquals(900L, merged.lastReadAt)
    }

    @Test fun booksNotInTheLibraryAreCountedAsMissing() {
        val result = LibraryBackup.merge(listOf(fresh(unread)), listOf(read, unread))
        assertEquals(listOf(unread), result.records)
        assertEquals(1, result.restored)
        assertEquals(1, result.missing)
    }

    @Test fun booksOnlyInTheLibraryAreLeftAlone() {
        val result = LibraryBackup.merge(listOf(read, unread), listOf(unread))
        assertEquals(listOf(read, unread), result.records)
    }

    @Test fun rejectsFilesThatAreNotBackups() {
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse("not JSON") }
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse("{\"hello\": 1}") }
        assertThrows(LibraryBackupException::class.java) {
            LibraryBackup.parse(JSONObject().put("format", "something-else").put("books", JSONArray()).toString())
        }
    }

    @Test fun badRecordsInABackupAreSkipped() {
        val text = JSONObject().put("format", "bookcover-library-backup").put("version", 1)
            .put("books", JSONArray().put(read.toJson()).put(JSONObject().put("id", "../x")).put(42)).toString()
        val backup = LibraryBackup.parse(text)
        assertEquals(listOf(read), backup.records)
        assertEquals(2, backup.skipped)
    }

    @Test fun acceptsAPlainLibraryIndexFile() {
        val index = JSONObject().put("version", 1).put("books", JSONArray().put(read.toJson())).toString()
        assertEquals(listOf(read), LibraryBackup.parse(index).records)
    }
}
